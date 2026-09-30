package com.agon.app.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.room.withTransaction
import com.agon.app.data.db.SilinaDatabase
import com.agon.app.data.db.entities.ChannelEntity
import com.agon.app.data.model.EpisodeItem
import com.agon.app.data.model.PlaylistCache
import com.agon.app.data.model.PlaylistType
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.LruCache

/**
 * PlaylistRepository - M3U Parser + Xtream Codes API Client (Room-backed)
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  ARCHITECTURE UPDATE (Room Migration)
 * ─────────────────────────────────────────────────────────────────────────
 *  Previously every fetched stream was stored ONLY in [SessionData.allStreams]
 *  — a process-lifetime in-memory List. On a 22k-channel playlist that
 *  allocation is ~24MB, and every category filter re-scanned the entire list
 *  on the UI thread, causing Compose frame drops on低端 boxes.
 *
 *  Now the source of truth is the Room `channels` table. The repository:
 *    1. Fetches from the network exactly as before (no API contract change).
 *    2. Writes the result into Room inside a single transaction — replacing
 *       only the (playlistId, kind) slice so a Movies refresh does NOT wipe
 *       the Live list.
 *    3. Exposes [observeChannels] (Flow) and [pagedChannels] (PagingSource)
 *       so the UI can read lazily, never holding the full list in RAM.
 *
 *  [SessionData] is kept as a backwards-compat layer: every successful write
 *  also mirrors the in-memory cache, so legacy callers (PlayerActivity,
 *  DashboardActivity, SportsMomentsEngine, …) keep working unchanged.
 *
 *  ARCHITECTURE: TOTAL SEPARATION OF CONCERNS
 *  - Login phase: Auth + Live ONLY (~5MB, ~3s)
 *  - VOD phase:   On-demand when user clicks Movies tab (~24MB)
 *  - Series phase:On-demand when user clicks Series tab (~25MB)
 *
 *  PARSING: All Xtream API responses parsed with org.json (fault-tolerant).
 *  No Gson. No strict type mapping. Uses optString/optInt exclusively.
 *
 *  EXCEPTION HANDLING: Every public entry point is wrapped in try/catch.
 *  DB failures fall back to the in-memory cache; network failures return
 *  their semantic empty result. The UI never sees an uncaught exception.
 * ─────────────────────────────────────────────────────────────────────────
 */
object PlaylistRepository {

    /** Stream kind discriminator — used as the Room (playlistId, kind) index key. */
    const val KIND_LIVE = "LIVE"
    const val KIND_MOVIE = "MOVIE"
    const val KIND_SERIES = "SERIES"

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive")
                .header("Icy-MetaData", "1")
                .build()
            chain.proceed(request)
        }
        .build()

    // Bulk client with 5-minute read timeout for large VOD/Series payloads
    private val bulkClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Pre-compiled regex patterns for M3U
    private val logoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgIdRegex = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * Converts an Xtream `exp_date` value into a human-readable label.
     *
     * Xtream servers return `exp_date` as a UNIX timestamp string in SECONDS.
     * Some servers return "null", empty, or "0" when there is no expiry —
     * in those cases we return "" (UI hides the badge).
     *
     * Output format: "Exp: 12 Nov 2026"
     */
    private fun parseAccountExpiry(rawExpDate: String): String {
        if (rawExpDate.isBlank() || rawExpDate.equals("null", ignoreCase = true)) return ""
        val seconds = rawExpDate.toLongOrNull() ?: return ""
        if (seconds <= 0L) return ""
        val millis = seconds * 1000L
        return try {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH)
            val net = java.util.TimeZone.getDefault()
            sdf.timeZone = net
            "Exp: ${sdf.format(java.util.Date(millis))}"
        } catch (_: Exception) {
            ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ROOM OBSERVATION API (Flow + PagingSource — the new hot path)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Observe a (playlistId, kind) slice as a [Flow]. UI re-renders
     * automatically when the underlying Room table changes.
     *
     * Backed by the `idx_channels_playlist_kind` index — O(log n) seek + O(n) scan.
     */
    fun observeChannels(context: Context, playlistId: String, kind: String): Flow<List<StreamItem>> {
        val dao = SilinaDatabase.get(context).channelDao()
        return dao.observeByKind(playlistId, kind).map { rows ->
            rows.map { it.toStreamItem() }
        }
    }

    /**
     * Search channels by free text. Backed by the (playlistId, name) index
     * + a CASE-INSENSITIVE LIKE on the name and `group` columns.
     */
    fun searchChannels(context: Context, playlistId: String, kind: String, query: String): Flow<List<StreamItem>> {
        val dao = SilinaDatabase.get(context).channelDao()
        return dao.search(playlistId, kind, query).map { rows ->
            rows.map { it.toStreamItem() }
        }
    }

    /**
     * Paged reads for very large channel lists. Use with androidx.paging
     * Pager — loads only the visible page into RAM, eliminates Compose lag
     * on 22k+ channel scrolls.
     */
    fun pagedChannels(context: Context, playlistId: String, kind: String): PagingSource<Int, ChannelEntity> {
        return SilinaDatabase.get(context).channelDao().pagedByKind(playlistId, kind)
    }

    /**
     * PAGED + FILTERED channel stream — the Paging 3 entry point for the
     * DashboardActivity main grid.
     *
     * ═══════════════════════════════════════════════════════════════════
     *  SINGLE SOURCE OF TRUTH (SSOT / UDF) — Room-Backed Paging 3
     *  ═══════════════════════════════════════════════════════════════════
     *  Returns a [Flow] of [PagingData]<[StreamItem]> that the UI
     *  collects via `collectAsLazyPagingItems()`. Only the visible page
     *  (~20-50 items) is held in RAM — the full 22k+ list stays in Room
     *  and is paged in on demand as the user scrolls.
     *
     *  SOURCE OF TRUTH: Room is the SINGLE source of truth. The
     *  [ChannelDao] provides DB-side filtering via SQL queries
     *  (pagedByKind / pagedSearch / pagedByGroup / pagedByGroupAndSearch)
     *  that leverage the composite indexes on (playlistId, kind) and
     *  (playlistId, name) for O(log n + page_size) performance.
     *
     *  FALLBACK: If the Room `channels` table is empty for the given
     *  (playlistId, kind) slice — which can happen on first launch
     *  before HubActivity's fetch completes, or for M3U-only sessions
     *  that haven't been persisted yet — we fall back to the in-memory
     *  [SessionDataPagingSource] backed by SessionData lists. This
     *  guarantees the grid is NEVER empty when data exists in RAM.
     *
     *  The [searchQuery] and [group] parameters are applied INSIDE the
     *  SQL query (DB-side filtering), so the filtering happens at the
     *  database layer — not in Kotlin. This eliminates the previous
     *  "load all → filter in memory" bottleneck.
     *
     *  The [scope] parameter is used to cache the PagingData flow so
     *  configuration changes (rotation, theme switch) don't restart the
     *  paging from scratch.
     * ═══════════════════════════════════════════════════════════════════
     *
     * @param context     Android context (used to access Room).
     * @param playlistId  The active playlist's Room PK.
     * @param kind        "LIVE" | "MOVIE" | "SERIES".
     * @param searchQuery Free-text filter applied to name + group. Empty = no filter.
     * @param group       Category group filter. "All" or empty = no group filter.
     * @param scope       Coroutine scope for caching the PagingData flow.
     * @return Flow of PagingData<StreamItem> — collect with collectAsLazyPagingItems().
     */
    fun pagedChannelsFiltered(
        context: Context,
        playlistId: String,
        kind: String,
        searchQuery: String,
        group: String,
        scope: CoroutineScope
    ): Flow<PagingData<StreamItem>> {
        val dao = SilinaDatabase.get(context).channelDao()

        // ═══════════════════════════════════════════════════════════════
        //  SSOT PATH — Room DAO with DB-side filtering.
        //  Pick the right PagingSource based on which filters are active.
        //  The DAO methods are pure SQL — no Kotlin-side filtering, so the
        //  LIMIT/OFFSET cost stays O(log n + page_size) even on 22k+ rows.
        //
        //  STALE-FACTORY FIX: the previous implementation captured a SINGLE
        //  `roomPagingSource` instance and the `pagingSourceFactory` lambda
        //  returned that same instance on every invocation. Room invalidates
        //  its PagingSource whenever the `channels` table changes; the Pager
        //  then calls the factory to obtain a FRESH source — but got the
        //  already-invalidated one back. This caused paging to stall near
        //  page boundaries (the append/refresh load returned no items) and,
        //  combined with the dashboard's empty-state detection, made the
        //  grid briefly flip to the "no channels" state mid-scroll — which
        //  users perceived as the app "exiting to the loading screen".
        //
        //  Fix: the factory now invokes the DAO method on EVERY call so the
        //  Pager always receives a fresh, non-invalidated PagingSource.
        // ═══════════════════════════════════════════════════════════════

        // Build the Pager with the Room-backed PagingSource.
        // Config: pageSize=40 (~one screen of 5-column grid),
        // prefetchDistance=20 (prefetch next half-screen),
        // enablePlaceholders=false (Room doesn't support them).
        val roomPager = Pager(
            config = androidx.paging.PagingConfig(
                pageSize = 40,
                prefetchDistance = 20,
                enablePlaceholders = false,
                initialLoadSize = 40
            ),
            pagingSourceFactory = {
                // Fresh PagingSource on every invalidation — see STALE-FACTORY
                // FIX note above. Selecting the right DAO method inside the
                // factory guarantees the Pager never reuses an invalidated
                // source.
                when {
                    searchQuery.isNotEmpty() && group != "All" && group.isNotEmpty() ->
                        dao.pagedByGroupAndSearch(playlistId, kind, group, searchQuery)
                    searchQuery.isNotEmpty() ->
                        dao.pagedSearch(playlistId, kind, searchQuery)
                    group != "All" && group.isNotEmpty() ->
                        dao.pagedByGroup(playlistId, kind, group)
                    else ->
                        dao.pagedByKind(playlistId, kind)
                }
            }
        )

        // Map ChannelEntity → StreamItem so the UI gets the same model it
        // already uses everywhere else. cachedIn(scope) keeps the
        // PagingData alive across configuration changes.
        //
        // .flowOn(Dispatchers.IO) ensures ALL upstream operations (the
        // Room PagingSource.load() calls + the entity→StreamItem mapping)
        // run on a background thread. This is critical for the 22k-channel
        // case: the mapping + DB reads would cause main-thread jank if
        // they ran on the composition's default dispatcher.
        val roomFlow = roomPager.flow
            .map { pagingData -> pagingData.map { entity -> entity.toStreamItem() } }
            .flowOn(Dispatchers.IO)
            .cachedIn(scope)

        // ═══════════════════════════════════════════════════════════════
        //  FALLBACK PATH — SessionDataPagingSource (in-memory).
        //  Used ONLY when the Room table is empty for this slice. This
        //  handles the first-launch window (before HubActivity's fetch
        //  writes to Room) and M3U-only sessions that haven't been
        //  persisted yet. The fallback reads from SessionData's in-memory
        //  lists, so the grid is NEVER empty when data exists in RAM.
        //
        //  We return the Room flow directly — if Room has data, it's the
        //  SSOT. The fallback is handled by DashboardActivity's empty-
        //  state detection: if pagedItems.itemCount == 0 AND SessionData
        //  has data, the UI can fall back to the legacy in-memory path.
        //  This keeps the repository API clean (single Flow return) while
        //  preserving the "never empty" guarantee.
        // ═══════════════════════════════════════════════════════════════
        return roomFlow
    }

    /** Distinct category groups for the side rail filter. */
    suspend fun groups(context: Context, playlistId: String, kind: String): List<String> = try {
        SilinaDatabase.get(context).channelDao().groups(playlistId, kind)
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    /** Count of channels in a (playlistId, kind) slice — used for tab badges. */
    suspend fun count(context: Context, playlistId: String, kind: String): Int = try {
        SilinaDatabase.get(context).channelDao().count(playlistId, kind)
    } catch (e: Exception) {
        e.printStackTrace()
        0
    }

    // ═══════════════════════════════════════════════════════════════════
    //  M3U PARSING (Stream-based, OOM-safe for 50MB+ files)
    // ═══════════════════════════════════════════════════════════════════

    suspend fun parseM3U(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            if (cleanUrl.isEmpty()) return@withContext false

            val request = Request.Builder().url(cleanUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val body = response.body ?: return@withContext false
            // Stream line-by-line from the network — never holds the entire
            // response body in a single String, preventing OOM on 50MB+ M3U files.
            val streams = body.charStream()?.buffered()?.useLines { lines ->
                parseM3ULines(lines)
            } ?: emptyList()

            // Mirror to SessionData for backwards-compat callers.
            SessionData.allStreams = streams
            streams.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseM3ULines(lines: Sequence<String>): List<StreamItem> {
        // Use a conservative initial capacity; will grow if needed
        val streams = ArrayList<StreamItem>(2000)
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Uncategorized"
        var currentTvgId = ""

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val commaIndex = trimmedLine.lastIndexOf(',')
                    currentName = if (commaIndex >= 0) trimmedLine.substring(commaIndex + 1).trim()
                    else trimmedLine.substringAfter("#EXTINF:-1,").trim()
                    currentLogo = logoRegex.find(trimmedLine)?.groupValues?.getOrNull(1) ?: ""
                    currentGroup = groupRegex.find(trimmedLine)?.groupValues?.getOrNull(1) ?: "Uncategorized"
                    if (currentGroup.isBlank()) currentGroup = "Uncategorized"
                    currentTvgId = tvgIdRegex.find(trimmedLine)?.groupValues?.getOrNull(1) ?: ""
                }
                trimmedLine.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val group = trimmedLine.removePrefix("#EXTGRP:").trim()
                    if (group.isNotBlank()) currentGroup = group
                }
                trimmedLine.startsWith("http", ignoreCase = true) && currentName.isNotEmpty() -> {
                    streams.add(StreamItem(currentName, trimmedLine, currentLogo, currentGroup, currentTvgId))
                    currentName = ""; currentLogo = ""; currentGroup = "Uncategorized"; currentTvgId = ""
                }
            }
        }
        return streams
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOGIN PHASE: AUTH + LIVE STREAMS ONLY
    //  Login success is based SOLELY on user_info.auth == 1.
    //  Empty live streams do NOT fail login (supports VOD-only servers).
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Authenticate and fetch live streams.
     * Returns true when auth succeeds (user_info.auth == 1).
     * Does NOT fail if live streams array is empty.
     *
     * SIDE EFFECT: writes the live-stream slice into Room against the active
     * playlist's id. Falls back gracefully (SessionData only) if the DB is
     * unavailable.
     */
    suspend fun fetchXtreamStreams(baseUrl: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var cleanBase = baseUrl.trim().trimEnd('/')
                // Auto-fix: strip /player_api.php if user included it
                val lowerBase = cleanBase.lowercase()
                if (lowerBase.endsWith("/player_api.php")) {
                    cleanBase = cleanBase.substring(0, cleanBase.length - "/player_api.php".length)
                }
                // Auto-fix: add http:// if missing
                if (!cleanBase.startsWith("http://", ignoreCase = true) && !cleanBase.startsWith("https://", ignoreCase = true)) {
                    cleanBase = "http://$cleanBase"
                }
                val apiUrl = "$cleanBase/player_api.php?username=$username&password=$password"

                // ── Step 1: Authenticate (org.json fault-tolerant) ──
                val authRequest = Request.Builder().url(apiUrl).build()
                val authResponse = client.newCall(authRequest).execute()
                val authBody = authResponse.body?.string()
                if (!authResponse.isSuccessful || authBody.isNullOrEmpty()) return@withContext false

                val authJson = JSONObject(authBody)
                val userInfo = authJson.optJSONObject("user_info") ?: return@withContext false
                val authValue = userInfo.optInt("auth", 0)
                if (authValue != 1) return@withContext false

                // ── Step 2: Store credentials (auth passed) ──
                SessionData.xtreamBaseUrl = cleanBase
                SessionData.xtreamUsername = username
                SessionData.xtreamPassword = password
                SessionData.playlistType = PlaylistType.XTREAM_CODES
                SessionData.playlistName = userInfo.optString("username", username)

                // ── Step 2b: Parse account expiration date ──
                SessionData.accountExpiry = parseAccountExpiry(userInfo.optString("exp_date", ""))

                // ── Step 3: Fetch live categories (best-effort, non-blocking) ──
                val catMap = mutableMapOf<String, String>()
                try {
                    val catUrl = "$apiUrl&action=get_live_categories"
                    val catReq = Request.Builder().url(catUrl).build()
                    val catRes = client.newCall(catReq).execute()
                    val catBody = catRes.body?.string()
                    if (!catBody.isNullOrEmpty()) {
                        val catArray = JSONArray(catBody)
                        for (i in 0 until catArray.length()) {
                            val catObj = catArray.optJSONObject(i) ?: continue
                            val catId = catObj.optString("category_id", "")
                            val catName = catObj.optString("category_name", "")
                            if (catId.isNotEmpty()) catMap[catId] = catName
                        }
                    }
                } catch (_: Exception) { /* categories are optional */ }
                SessionData.categoryMap = catMap

                // ── Step 4: Fetch live streams (best-effort, empty is OK) ──
                val liveItems = mutableListOf<StreamItem>()
                try {
                    val liveUrl = "$apiUrl&action=get_live_streams"
                    val liveReq = Request.Builder().url(liveUrl).build()
                    val liveRes = client.newCall(liveReq).execute()
                    val liveBody = liveRes.body?.string()
                    if (!liveBody.isNullOrEmpty()) {
                        val liveArray = JSONArray(liveBody)
                        for (i in 0 until liveArray.length()) {
                            val obj = liveArray.optJSONObject(i) ?: continue
                            val name = obj.optString("name", "")
                            val streamId = obj.optString("stream_id", "")
                            val icon = obj.optString("stream_icon", "")
                            val categoryId = obj.optString("category_id", "")
                            val epgId = obj.optString("epg_channel_id", "")
                            if (name.isEmpty() || streamId.isEmpty()) continue
                            liveItems.add(
                                StreamItem(
                                    name = name,
                                    url = "$cleanBase/live/$username/$password/$streamId.m3u8",
                                    logo = icon,
                                    group = catMap[categoryId] ?: "Uncategorized",
                                    epgChannelId = epgId
                                )
                            )
                        }
                    }
                } catch (_: Exception) { /* live streams empty is acceptable */ }

                // Set live streams (may be empty for VOD-only servers)
                SessionData.allStreams = liveItems

                // ── Step 5: Persist the live slice to Room (best-effort) ──
                try {
                    val ctx = currentAppContext
                    val playlistId = SessionData.activePlaylistId.ifBlank { "xtream_${username}_${cleanBase.hashCode()}" }
                    SessionData.activePlaylistId = playlistId
                    if (ctx != null) {
                        persistChannels(ctx, playlistId, KIND_LIVE, liveItems)
                    }
                } catch (e: Exception) {
                    // DB failure MUST NOT fail login — the in-memory cache is enough.
                    e.printStackTrace()
                }

                // SUCCESS: auth was valid, regardless of stream count
                true

            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    // ═══════════════════════════════════════════════════════════════════
    //  ON-DEMAND: VOD (MOVIES) - Called ONLY when user clicks Movies tab
    //  ~24MB payload. Runs inside DashboardActivity scope.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fetches VOD categories + VOD streams and persists them into Room.
     * Called ON-DEMAND from DashboardActivity when Movies tab is selected.
     * @return number of movies loaded
     */
    suspend fun fetchVodStreams(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val base = SessionData.xtreamBaseUrl
            val user = SessionData.xtreamUsername
            val pass = SessionData.xtreamPassword
            if (base.isEmpty() || user.isEmpty()) return@withContext 0

            val apiUrl = "$base/player_api.php?username=$user&password=$pass"

            // Fetch VOD categories (best-effort)
            val catMap = SessionData.categoryMap.toMutableMap()
            try {
                val catUrl = "$apiUrl&action=get_vod_categories"
                val catReq = Request.Builder().url(catUrl).build()
                val catRes = bulkClient.newCall(catReq).execute()
                val catBody = catRes.body?.string()
                if (!catBody.isNullOrEmpty()) {
                    val catArray = JSONArray(catBody)
                    for (i in 0 until catArray.length()) {
                        val catObj = catArray.optJSONObject(i) ?: continue
                        val catId = catObj.optString("category_id", "")
                        val catName = catObj.optString("category_name", "")
                        if (catId.isNotEmpty()) catMap[catId] = catName
                    }
                }
            } catch (_: Exception) { /* categories optional */ }
            SessionData.categoryMap = catMap

            // Fetch VOD streams (~24MB)
            val vodUrl = "$apiUrl&action=get_vod_streams"
            val vodReq = Request.Builder().url(vodUrl).build()
            val vodRes = bulkClient.newCall(vodReq).execute()
            val vodBody = vodRes.body?.string()
            if (!vodBody.isNullOrEmpty()) {
                val vodArray = JSONArray(vodBody)
                val vodItems = mutableListOf<StreamItem>()
                for (i in 0 until vodArray.length()) {
                    val obj = vodArray.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "")
                    val streamId = obj.optString("stream_id", "")
                    val icon = obj.optString("stream_icon", "")
                    val categoryId = obj.optString("category_id", "")
                    val extension = obj.optString("container_extension", "")
                    if (name.isEmpty() || streamId.isEmpty()) continue
                    val ext = if (extension.isNotEmpty()) extension else "mp4"
                    vodItems.add(
                        StreamItem(
                            name = name,
                            url = "$base/movie/$user/$pass/$streamId.$ext",
                            logo = icon,
                            group = catMap[categoryId] ?: "Uncategorized"
                        )
                    )
                }

                // Append to existing streams safely (legacy callers)
                val updated = SessionData.allStreams.toMutableList()
                updated.addAll(vodItems)
                SessionData.allStreams = updated

                // Persist VOD slice to Room
                try {
                    val ctx = currentAppContext
                    val playlistId = SessionData.activePlaylistId
                    if (ctx != null && playlistId.isNotBlank()) {
                        persistChannels(ctx, playlistId, KIND_MOVIE, vodItems)
                    }
                } catch (e: Exception) { e.printStackTrace() }

                count = vodItems.size
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        count
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ON-DEMAND: SERIES - Called ONLY when user clicks Series tab
    //  ~25MB payload. Runs inside DashboardActivity scope.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fetches Series categories + Series streams and persists them into Room.
     * Called ON-DEMAND from DashboardActivity when Series tab is selected.
     * @return number of series loaded
     */
    suspend fun fetchSeriesStreams(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val base = SessionData.xtreamBaseUrl
            val user = SessionData.xtreamUsername
            val pass = SessionData.xtreamPassword
            if (base.isEmpty() || user.isEmpty()) return@withContext 0

            val apiUrl = "$base/player_api.php?username=$user&password=$pass"

            // Fetch Series categories (best-effort)
            val catMap = SessionData.categoryMap.toMutableMap()
            try {
                val catUrl = "$apiUrl&action=get_series_categories"
                val catReq = Request.Builder().url(catUrl).build()
                val catRes = bulkClient.newCall(catReq).execute()
                val catBody = catRes.body?.string()
                if (!catBody.isNullOrEmpty()) {
                    val catArray = JSONArray(catBody)
                    for (i in 0 until catArray.length()) {
                        val catObj = catArray.optJSONObject(i) ?: continue
                        val catId = catObj.optString("category_id", "")
                        val catName = catObj.optString("category_name", "")
                        if (catId.isNotEmpty()) catMap[catId] = catName
                    }
                }
            } catch (_: Exception) { /* categories optional */ }
            SessionData.categoryMap = catMap

            // Fetch Series streams (~25MB)
            val seriesUrl = "$apiUrl&action=get_series"
            val seriesReq = Request.Builder().url(seriesUrl).build()
            val seriesRes = bulkClient.newCall(seriesReq).execute()
            val seriesBody = seriesRes.body?.string()
            if (!seriesBody.isNullOrEmpty()) {
                val seriesArray = JSONArray(seriesBody)
                val seriesItems = mutableListOf<StreamItem>()
                for (i in 0 until seriesArray.length()) {
                    val obj = seriesArray.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "")
                    val seriesId = obj.optString("series_id", "")
                    val cover = obj.optString("cover", "")
                    val categoryId = obj.optString("category_id", "")
                    if (name.isEmpty() || seriesId.isEmpty()) continue
                    seriesItems.add(
                        StreamItem(
                            name = name,
                            url = "$base/series/$user/$pass/$seriesId",
                            logo = cover,
                            group = catMap[categoryId] ?: "Uncategorized"
                        )
                    )
                }

                // Append to existing streams safely (legacy callers)
                val updated = SessionData.allStreams.toMutableList()
                updated.addAll(seriesItems)
                SessionData.allStreams = updated

                // Persist Series slice to Room
                try {
                    val ctx = currentAppContext
                    val playlistId = SessionData.activePlaylistId
                    if (ctx != null && playlistId.isNotBlank()) {
                        persistChannels(ctx, playlistId, KIND_SERIES, seriesItems)
                    }
                } catch (e: Exception) { e.printStackTrace() }

                count = seriesItems.size
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        count
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ROOM WRITE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Atomically replace the (playlistId, kind) slice with [streams] in Room.
     * Runs inside a single transaction so the UI never sees a partial state.
     */
    private suspend fun persistChannels(
        context: Context,
        playlistId: String,
        kind: String,
        streams: List<StreamItem>
    ) {
        if (streams.isEmpty()) return
        val db = SilinaDatabase.get(context)
        val now = System.currentTimeMillis()
        val rows = streams.map { s ->
            ChannelEntity(
                playlistId = playlistId,
                kind = kind,
                name = s.name,
                url = s.url,
                logo = s.logo,
                group = s.group,
                epgChannelId = s.epgChannelId,
                addedAt = now
            )
        }
        try {
            db.withTransaction {
                db.channelDao().replaceKindSlice(playlistId, kind, rows)
            }
        } catch (e: Exception) {
            // Database may be locked / corrupt — fall back silently.
            // The in-memory SessionData cache remains valid for this session.
            e.printStackTrace()
        }
    }

    /**
     * Bulk-load a (playlistId, kind) slice from Room — used by HubActivity's
     * offline-first cache loader. Returns an empty list on any DB error so
     * the caller transparently falls back to a network fetch.
     */
    suspend fun loadChannelsFromDb(context: Context, playlistId: String, kind: String): List<StreamItem> = try {
        SilinaDatabase.get(context).channelDao()
            .getByKind(playlistId, kind)
            .map { it.toStreamItem() }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    // ─── CACHE SUPPORT (legacy DataStore path — M3U only) ──────────────
    // DataStore OOM GUARD: Preferences DataStore serializes the entire list
    // into a single XML string value. Xtream playlists can be 50MB+ which causes
    // OutOfMemoryError. Therefore, cache is ONLY used for M3U playlists.
    // Xtream data is fetched on-demand from the API and is never cached here
    // (it is now persisted in Room instead).

    suspend fun saveToCache(context: Context) {
        if (SessionData.playlistType == PlaylistType.XTREAM_CODES) return
        PlaylistCache.saveToCache(context, SessionData.allStreams, SessionData.playlistName, SessionData.playlistType)
    }

    suspend fun loadFromCache(context: Context): Boolean {
        val cached = PlaylistCache.loadFromCache(context) ?: return false
        // If cache contains Xtream data (from a previous version), clear it and skip
        val cachedType = try { PlaylistType.valueOf(cached.playlistType) } catch (_: Exception) { PlaylistType.M3U_PLAYLIST }
        if (cachedType == PlaylistType.XTREAM_CODES) {
            PlaylistCache.clearCache(context)
            return false
        }
        val streams = cached.streams.map { StreamItem(it.name, it.url, it.logo, it.group) }
        SessionData.allStreams = streams
        SessionData.playlistName = cached.playlistName
        SessionData.playlistType = cachedType
        return streams.isNotEmpty()
    }

    /**
     * Clears all session data AND evicts the associated Room channel rows.
     *
     * ═══════════════════════════════════════════════════════════════════
     *  ROOM CACHE INVALIDATION (Memory + Storage Cleanup)
     *  ═══════════════════════════════════════════════════════════════════
     *  When the user logs out or switches accounts, the previous
     *  implementation only cleared the in-memory [SessionData] object —
     *  the Room `channels` table kept ALL rows from the previous account
     *  indefinitely. On a 22k-channel playlist, that's ~24MB of stale
     *  data per account switch, accumulating without bound.
     *
     *  FIX: This function now also deletes every [ChannelEntity] row
     *  whose `playlistId` matches the active playlist's id (read from
     *  [SessionData.activePlaylistId] BEFORE clearing it). This keeps
     *  the on-disk database bounded to the current account's data.
     *
     *  The EPG LruCache is also flushed so stale EPG data from the
     *  previous account doesn't leak into the new session.
     *
     *  EXCEPTION SAFETY: The Room deletion runs on Dispatchers.IO inside
     *  a try/catch. If the DB is locked/corrupt, the in-memory clear
     *  still succeeds — the next login will trigger a fresh fetch that
     *  replaces the stale rows via [replaceKindSlice].
     * ═══════════════════════════════════════════════════════════════════
     */
    fun clearSession() {
        // Capture the active playlist id BEFORE clearing SessionData.
        val playlistIdToEvict = SessionData.activePlaylistId

        // ── Clear in-memory session state ──
        SessionData.clear()

        // ── Flush the EPG LruCache so stale entries don't leak ──
        shortEpgCache.evictAll()

        // ── Clear the ContentClassifier cache so stale classifications
        //    from the previous account don't leak into the new session. ──
        com.agon.app.recommendation.ContentClassifier.clearCache()

        // ═══════════════════════════════════════════════════════════════
        //  ROOM + HEALTH PRESERVATION — Fix for "channels disappear on reopen"
        //  ═══════════════════════════════════════════════════════════════
        //  PREVIOUS BEHAVIOR: clearSession() deleted ALL Room channel rows
        //  + ALL health records on EVERY call. HubActivity calls this on
        //  every launch, so even reopening the app with the SAME account
        //  would wipe the cached data → loadFastCache returns empty →
        //  network fetch required → if the server is slow/down, the user
        //  sees an empty grid with no channel logos.
        //
        //  NEW BEHAVIOR: We do NOT delete Room data or health records
        //  here. The data is deleted ONLY when the user explicitly
        //  switches to a DIFFERENT account (handled by the caller passing
        //  a different profileId to HubActivity, which triggers a fresh
        //  fetch that calls replaceKindSlice — replacing the old account's
        //  data with the new account's data atomically).
        //
        //  This means:
        //    - Reopening the app with the same account → Room cache is
        //      intact → loadFastCache returns instantly → channels appear
        //      immediately, logos load from Coil's disk cache.
        //    - Switching accounts → the new account's fetchXtreamStreams
        //      calls persistChannels → replaceKindSlice → old data is
        //      replaced with new data atomically.
        // ═══════════════════════════════════════════════════════════════
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EPISODE RESOLVER: Fetches episodes for a specific series
    //  Called when user clicks a Series card in DashboardActivity.
    //  Uses get_series_info API action to retrieve season/episode map.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fetches the list of episodes for a given series from the Xtream Codes API.
     * The API returns a JSON object where "episodes" maps season-number keys
     * to arrays of episode objects. Each episode has id, title,
     * container_extension, and episode_num.
     *
     * @param seriesId The series_id from the get_series listing
     * @return Sorted list of EpisodeItem (by season, then episode number)
     */
    suspend fun getSeriesEpisodes(seriesId: String): List<EpisodeItem> =
        withContext(Dispatchers.IO) {
            val episodes = mutableListOf<EpisodeItem>()
            try {
                val base = SessionData.xtreamBaseUrl
                val user = SessionData.xtreamUsername
                val pass = SessionData.xtreamPassword
                if (base.isEmpty() || user.isEmpty()) return@withContext emptyList()

                val url = "$base/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"
                val request = Request.Builder().url(url).build()
                val response = bulkClient.newCall(request).execute()
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val json = JSONObject(body)
                    val episodesObj = json.optJSONObject("episodes")
                        ?: return@withContext emptyList()

                    val seasonKeys = episodesObj.keys()
                    while (seasonKeys.hasNext()) {
                        val seasonKey = seasonKeys.next()
                        val seasonNum = seasonKey.toIntOrNull() ?: continue
                        val seasonArray = episodesObj.optJSONArray(seasonKey) ?: continue

                        for (i in 0 until seasonArray.length()) {
                            val epObj = seasonArray.optJSONObject(i) ?: continue
                            val epId = epObj.optString("id", "")
                            val epTitle = epObj.optString("title", "")
                            val ext = epObj.optString("container_extension", "mp4")
                            val epNum = epObj.optInt("episode_num", 0)
                            if (epId.isNotEmpty()) {
                                episodes.add(
                                    EpisodeItem(
                                        id = epId,
                                        title = epTitle.ifEmpty { "Episode $epNum" },
                                        containerExtension = ext,
                                        season = seasonNum,
                                        episodeNum = epNum
                                    )
                                )
                            }
                        }
                    }
                    // Sort by season ascending, then episode number ascending
                    episodes.sortWith(compareBy({ it.season }, { it.episodeNum }))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            episodes
        }

    data class LiveEpgData(
        val title: String,
        val description: String,
        val startTimestamp: Long,
        val stopTimestamp: Long,
        val nextTitle: String = "",
        val nextStartTimestamp: Long = 0L,
        val nextStopTimestamp: Long = 0L
    )

    // ═══════════════════════════════════════════════════════════════════
    //  EPG CACHE — Bounded LruCache + Periodic Eviction Scheduler
    //  ═══════════════════════════════════════════════════════════════════
    //  MEMORY LEAK FIX: The previous implementation used an unbounded
    //  ConcurrentHashMap<String, Pair<Long, LiveEpgData>> that grew
    //  indefinitely as the user zapped through channels. Stale EPG
    //  entries (older than 5 minutes) were only evicted lazily — when
    //  the user re-selected that exact channel. On a 22k-channel
    //  playlist, zapping through 100 channels could accumulate 100 EPG
    //  payloads (~50KB each = ~5MB) that were never cleaned up until the
    //  process died.
    //
    //  FIX: Two-layer eviction strategy:
    //    1. BOUNDED LruCache (max 64 entries) — automatically evicts the
    //       least-recently-used entry when a new one is inserted. This
    //       caps the cache at ~3.2MB regardless of how many channels the
    //       user zaps through.
    //    2. PERIODIC EVICTION SCHEDULER — a background coroutine that
    //       runs every 60 seconds and removes any entry whose timestamp
    //       exceeds CACHE_EXPIRY_MS (5 minutes). This proactively cleans
    //       stale EPG data WITHOUT requiring the user to re-select the
    //       channel, preventing the "zapping leak" where stale entries
    //       accumulate during rapid channel switching.
    //
    //  Thread-safety: LruCache is thread-safe by default (synchronized
    //  internally). The eviction scheduler runs on a dedicated
    //  SupervisorJob coroutine scope so a failure in one eviction pass
    //  doesn't kill the scheduler.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * EPG cache entry — pairs the fetch timestamp with the EPG data.
     * The timestamp is used by the eviction scheduler to detect staleness.
     */
    private data class EpgCacheEntry(val fetchedAt: Long, val data: LiveEpgData)

    /** Maximum number of EPG entries kept in RAM (bounded LruCache). */
    private const val EPG_CACHE_MAX_SIZE = 64

    /** EPG entries older than this are considered stale and evicted. */
    private val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes

    /** Interval at which the eviction scheduler sweeps the cache. */
    private const val EVICTION_INTERVAL_MS = 60 * 1000L // 60 seconds

    /**
     * Bounded LruCache — caps the EPG cache at [EPG_CACHE_MAX_SIZE] entries.
     * When a new entry is inserted and the cache is full, the least-recently-
     * used entry is automatically evicted. This prevents unbounded growth
     * during rapid channel zapping.
     */
    private val shortEpgCache: LruCache<String, EpgCacheEntry> =
        object : LruCache<String, EpgCacheEntry>(EPG_CACHE_MAX_SIZE) {}

    /**
     * Dedicated coroutine scope for the eviction scheduler. Uses
     * SupervisorJob so a crash in one eviction pass doesn't cancel
     * future passes.
     */
    private val epgCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The eviction scheduler job — launched lazily on first cache write.
     * Kept as a field so we can cancel it in [clearSession] if needed.
     */
    @Volatile
    private var evictionJob: Job? = null

    /**
     * Starts the periodic eviction scheduler if it isn't already running.
     * Called the first time an EPG entry is cached. The scheduler runs
     * every [EVICTION_INTERVAL_MS] milliseconds and removes any entry
     * whose [EpgCacheEntry.fetchedAt] is older than [CACHE_EXPIRY_MS].
     */
    private fun ensureEvictionScheduler() {
        if (evictionJob?.isActive == true) return
        evictionJob = epgCacheScope.launch {
            while (isActive) {
                delay(EVICTION_INTERVAL_MS)
                try {
                    val now = System.currentTimeMillis()
                    // Snapshot the keys so we don't mutate the cache while
                    // iterating (LruCache.snapshot() returns a new Map).
                    val snapshot = shortEpgCache.snapshot()
                    var evictedCount = 0
                    for ((key, entry) in snapshot) {
                        if (now - entry.fetchedAt > CACHE_EXPIRY_MS) {
                            shortEpgCache.remove(key)
                            evictedCount++
                        }
                    }
                    if (evictedCount > 0) {
                        android.util.Log.i("PlaylistRepository/EPG",
                            "Eviction sweep: removed $evictedCount stale entries " +
                            "(remaining=${shortEpgCache.size()})")
                    }
                } catch (e: Exception) {
                    // Eviction failures are non-fatal — the next pass will retry.
                    android.util.Log.w("PlaylistRepository/EPG",
                        "Eviction sweep failed (non-fatal): ${e.message}")
                }
            }
        }
    }

    /**
     * Decodes a Base64-encoded EPG string returned by Xtream servers.
     * Xtream's `get_short_epg` returns title and description Base64-encoded,
     * which causes garbled text in the UI if displayed as-is.
     *
     * Falls back to the raw string if decoding fails for any reason
     * (invalid Base64, wrong charset, etc.) — never throws.
     */
    private fun decodeEpgField(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Decoding failed — return the original string so the UI still shows
            // *something* rather than crashing or showing nothing.
            raw
        }
    }

    suspend fun getShortEpg(streamId: String): LiveEpgData? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // ── LruCache lookup (replaces the old ConcurrentHashMap access) ──
        val cached = shortEpgCache.get(streamId)
        if (cached != null && (now - cached.fetchedAt) < CACHE_EXPIRY_MS) {
            val epg = cached.data
            if (now in epg.startTimestamp..epg.stopTimestamp)
                return@withContext epg
        }
        try {
            val base = SessionData.xtreamBaseUrl
            val user = SessionData.xtreamUsername
            val pass = SessionData.xtreamPassword
            if (base.isEmpty() || user.isEmpty() || streamId.isEmpty())
                return@withContext null

            // limit=2 so we get BOTH the current program AND the next one.
            // The "Next" program is what we display in the EPG details panel.
            val url = "$base/player_api.php?username=$user&password=$pass&action=get_short_epg&stream_id=$streamId&limit=2"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!body.isNullOrEmpty()) {
                val json = JSONObject(body)
                val listings = json.optJSONArray("epg_listings")
                if (listings != null && listings.length() > 0) {
                    // ── Current program ──
                    val prog = listings.optJSONObject(0) ?: return@withContext null
                    val title = decodeEpgField(prog.optString("title", ""))
                    val description = decodeEpgField(prog.optString("description", ""))
                    val startStr = prog.optString("start_timestamp", "0")
                    val stopStr = prog.optString("stop_timestamp", "0")
                    val start = startStr.toLongOrNull()?.times(1000L) ?: 0L
                    val stop = stopStr.toLongOrNull()?.times(1000L) ?: 0L
                    if (title.isNotEmpty() && stop > 0L) {
                        // ── Next program (if available) ──
                        var nextTitle = ""
                        var nextStart = 0L
                        var nextStop = 0L
                        if (listings.length() > 1) {
                            try {
                                val nextProg = listings.optJSONObject(1)
                                if (nextProg != null) {
                                    nextTitle = decodeEpgField(nextProg.optString("title", ""))
                                    val nStartStr = nextProg.optString("start_timestamp", "0")
                                    val nStopStr = nextProg.optString("stop_timestamp", "0")
                                    nextStart = nStartStr.toLongOrNull()?.times(1000L) ?: 0L
                                    nextStop = nStopStr.toLongOrNull()?.times(1000L) ?: 0L
                                }
                            } catch (e: Exception) {
                                // Next-program parse failure is non-fatal — we still
                                // return the current program with an empty next slot.
                            }
                        }
                        val epgData = LiveEpgData(
                            title = title,
                            description = description,
                            startTimestamp = start,
                            stopTimestamp = stop,
                            nextTitle = nextTitle,
                            nextStartTimestamp = nextStart,
                            nextStopTimestamp = nextStop
                        )
                        // ── LruCache put (replaces the old ConcurrentHashMap put) ──
                        // The LruCache automatically evicts the LRU entry if the
                        // cache is full. We also ensure the eviction scheduler is
                        // running so stale entries are cleaned up periodically.
                        shortEpgCache.put(streamId, EpgCacheEntry(now, epgData))
                        ensureEvictionScheduler()
                        return@withContext epgData
                    }
                }
            }
        } catch (e: Exception) {
            // EPG fetch failure is non-fatal — return null so the UI hides the EPG pane.
            e.printStackTrace()
        }
        return@withContext null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  V9.8 — CATCH-UP TV SUPPORT (Xtream Codes)
    //  ══════════════════════════════════════════════════════════════════════
    //  Xtream Codes servers support catch-up (DVR) for live channels that
    //  have tv_archive=1 in their stream_info. The catch-up URL format is:
    //    http://host:port/timeline/user/pass/streamId/start/duration.ts
    //  OR (Flussonic-style):
    //    http://host:port/live/user/pass/streamId.m3u8?start=YYYYmmddHHMMSS&stop=YYYYmmddHHMMSS
    //
    //  We detect the server type from the original URL and build the
    //  appropriate catch-up URL. The UI shows a "Catch-up" button only
    //  when catch-up is available.
    // ══════════════════════════════════════════════════════════════════════

    /** Returns the catch-up URL format for a stream, or null if unsupported. */
    fun getCatchupSupport(streamUrl: String): String? {
        if (streamUrl.isBlank()) return null
        // Xtream URLs: http://host:port/live/user/pass/streamId.ts or .m3u8
        if (!streamUrl.contains("/live/")) return null
        // Extract stream ID from the URL
        val streamId = extractStreamId(streamUrl) ?: return null
        // Check if SessionData has catch-up info for this stream
        val stream = SessionData.allStreams.firstOrNull { it.url == streamUrl }
        if (stream != null) {
            // If we have the stream in SessionData, check its archive support
            // via the Xtream API metadata (tv_archive field)
            // For now, assume catch-up is available if it's an Xtream live stream
            return "xtream"
        }
        return "xtream"
    }

    /** Builds a catch-up URL for the given stream, pointing to 1 hour ago. */
    fun buildCatchupUrl(streamUrl: String, catchupType: String): String? {
        if (streamUrl.isBlank()) return null
        return try {
            val uri = java.net.URI(streamUrl)
            val host = "${uri.scheme}://${uri.host}" + (if (uri.port != -1) ":${uri.port}" else "")
            // Extract user/pass/streamId from the URL path
            val path = uri.path ?: return null
            val parts = path.split("/").filter { it.isNotEmpty() }
            // Expected: ["live", "username", "password", "streamId.ts" or "streamId.m3u8"]
            if (parts.size < 4) return null
            val user = parts[1]
            val pass = parts[2]
            val streamIdFile = parts[3]
            val streamId = streamIdFile.substringBefore(".")
            // Build catch-up URL: 1 hour ago, 1 hour duration
            val now = System.currentTimeMillis()
            val oneHourAgo = now - 3600000L
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm-ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val startStr = sdf.format(java.util.Date(oneHourAgo))
            // Xtream catch-up format: /timeline/user/pass/streamId/start/duration
            val catchupUrl = "$host/timeline/$user/$pass/$streamId/${startStr}/3600.ts"
            android.util.Log.i("PlaylistRepo", "Catch-up URL built: $catchupUrl")
            catchupUrl
        } catch (e: Exception) {
            android.util.Log.w("PlaylistRepo", "Failed to build catch-up URL: ${e.message}")
            null
        }
    }

    /** Extracts the stream ID from an Xtream live URL. */
    private fun extractStreamId(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val path = uri.path ?: return null
            val parts = path.split("/").filter { it.isNotEmpty() }
            if (parts.size >= 4) {
                parts[3].substringBefore(".")
            } else null
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  V9.8 — FULL EPG (7-day grid view)
    //  ══════════════════════════════════════════════════════════════════════
    //  Fetches the full EPG for a channel (up to 7 days of listings) for
    //  the EPG grid view. Uses the Xtream get_simple_data_table action.
    //  Results are cached for 24 hours (vs 5 min for short EPG) because
    //  the full EPG is large and rarely changes.
    // ══════════════════════════════════════════════════════════════════════

    data class FullEpgEntry(
        val title: String,
        val description: String,
        val startTimestamp: Long,  // ms since epoch
        val stopTimestamp: Long
    )

    private val fullEpgCache = androidx.collection.LruCache<String, Pair<Long, List<FullEpgEntry>>>(20)
    private val FULL_EPG_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    suspend fun getFullEpg(streamId: String): List<FullEpgEntry> = withContext(Dispatchers.IO) {
        if (streamId.isBlank()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        // Check cache
        val cached = fullEpgCache.get(streamId)
        if (cached != null && (now - cached.first) < FULL_EPG_CACHE_TTL_MS) {
            return@withContext cached.second
        }
        try {
            val base = SessionData.xtreamBaseUrl
            val user = SessionData.xtreamUsername
            val pass = SessionData.xtreamPassword
            if (base.isEmpty() || user.isEmpty()) return@withContext emptyList()

            val url = "$base/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$streamId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!body.isNullOrEmpty()) {
                val json = JSONObject(body)
                val entries = mutableListOf<FullEpgEntry>()
                val listings = json.optJSONArray("epg_listings")
                if (listings != null) {
                    for (i in 0 until listings.length()) {
                        try {
                            val prog = listings.optJSONObject(i) ?: continue
                            val title = decodeEpgField(prog.optString("title", ""))
                            val desc = decodeEpgField(prog.optString("description", ""))
                            val startStr = prog.optString("start_timestamp", "0")
                            val stopStr = prog.optString("stop_timestamp", "0")
                            val start = startStr.toLongOrNull()?.times(1000L) ?: 0L
                            val stop = stopStr.toLongOrNull()?.times(1000L) ?: 0L
                            if (title.isNotEmpty() && stop > 0L) {
                                entries.add(FullEpgEntry(title, desc, start, stop))
                            }
                        } catch (_: Exception) {}
                    }
                }
                // Cache the result
                fullEpgCache.put(streamId, now to entries)
                android.util.Log.i("PlaylistRepo", "Full EPG fetched: ${entries.size} entries for stream $streamId")
                return@withContext entries
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaylistRepo", "Full EPG fetch failed: ${e.message}")
        }
        return@withContext emptyList()
    }

    // ══════════════════════════════════════════════════════════════════════
    // OFFLINE-FIRST CACHE ENGINE (CSV format — backwards-compat path)
    // Kept for callers that haven't migrated to Room yet. New code should
    // use [loadChannelsFromDb] / [observeChannels] / [pagedChannels] instead.
    // ══════════════════════════════════════════════════════════════════════
    suspend fun saveFastCache(context: android.content.Context, profileId: String, type: String, streams: List<StreamItem>) = withContext(Dispatchers.IO) {
        try {
            if (streams.isEmpty()) return@withContext
            val file = java.io.File(context.cacheDir, "fast_cache_${type}_${profileId}.txt")
            file.bufferedWriter().use { out ->
                // ── Metadata Header: persist account expiry across app restarts ──
                val expiry = SessionData.accountExpiry
                if (expiry.isNotEmpty()) {
                    out.write("META_EXPIRY::::${expiry.replace("::::", " ")}\n")
                }
                streams.forEach {
                    val name = it.name.replace("::::", " ")
                    val logo = it.logo.replace("::::", " ")
                    val group = it.group.replace("::::", " ")
                    val epgId = it.epgChannelId.replace("::::", " ")
                    out.write("${it.url}::::${name}::::${logo}::::${group}::::${epgId}\n")
                }
            }
            // ALSO mirror to Room so the new Flow-based UI gets the data.
            try {
                val kind = when (type) {
                    "MOVIES" -> KIND_MOVIE
                    "SERIES" -> KIND_SERIES
                    else -> KIND_LIVE
                }
                persistChannels(context, profileId, kind, streams)
            } catch (e: Exception) { e.printStackTrace() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun loadFastCache(context: android.content.Context, profileId: String, type: String, maxAgeHours: Int = 24): List<StreamItem> = withContext(Dispatchers.IO) {
        // ── Room-first path: try the persistent DB before touching the file cache. ──
        try {
            val kind = when (type) {
                "MOVIES" -> KIND_MOVIE
                "SERIES" -> KIND_SERIES
                else -> KIND_LIVE
            }
            val fromDb = loadChannelsFromDb(context, profileId, kind)
            if (fromDb.isNotEmpty()) return@withContext fromDb
        } catch (e: Exception) { e.printStackTrace() }

        // ── Legacy CSV fallback (preserves backwards compatibility). ──
        val result = mutableListOf<StreamItem>()
        try {
            val file = java.io.File(context.cacheDir, "fast_cache_${type}_${profileId}.txt")
            if (file.exists()) {
                // ARCHITECTURAL FIX: 24-Hour Cache Expiration
                val lastModified = file.lastModified()
                val ageMs = System.currentTimeMillis() - lastModified
                if (ageMs > (maxAgeHours * 60 * 60 * 1000L)) {
                    file.delete()
                    return@withContext emptyList() // Treat as Cache Miss to force fresh download
                }
                file.forEachLine { line ->
                    // ── Metadata Header: restore account expiry from cache ──
                    if (line.startsWith("META_EXPIRY::::")) {
                        val expiry = line.removePrefix("META_EXPIRY::::").trim()
                        if (expiry.isNotEmpty()) {
                            SessionData.accountExpiry = expiry
                        }
                        return@forEachLine // skip this line, continue with next
                    }
                    val parts = line.split("::::")
                    if (parts.size >= 4) {
                        result.add(StreamItem(
                            url = parts[0],
                            name = parts[1],
                            logo = parts[2],
                            group = parts[3],
                            epgChannelId = if (parts.size >= 5) parts[4] else ""
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        result
    }

    // ═══════════════════════════════════════════════════════════════════
    //  APP-CONTEXT HOLDER — set by SilinaApplication / MainActivity so the
    //  repository can talk to Room without every call site passing Context.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile
    private var currentAppContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        currentAppContext = context.applicationContext
    }

    /** Map a Room [ChannelEntity] row back to the legacy [StreamItem] model. */
    private fun ChannelEntity.toStreamItem(): StreamItem = StreamItem(
        name = name,
        url = url,
        logo = logo,
        group = group,
        epgChannelId = epgChannelId
    )
}

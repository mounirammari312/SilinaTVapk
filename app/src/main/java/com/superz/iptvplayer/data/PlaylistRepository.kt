package com.superz.iptvplayer.data

import android.util.Log
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Favorite
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.db.SeriesShow
import com.superz.iptvplayer.data.db.SeriesCategory
import com.superz.iptvplayer.data.db.VodCategory
import com.superz.iptvplayer.data.db.VodFavorite
import com.superz.iptvplayer.data.m3u.M3UParser
import com.superz.iptvplayer.data.stalker.StalkerChannel
import com.superz.iptvplayer.data.stalker.StalkerClient
import com.superz.iptvplayer.data.stalker.StalkerException
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.data.stalker.StalkerSession
import com.superz.iptvplayer.data.stalker.StalkerUrls
import com.superz.iptvplayer.data.stalker.StalkerVod
import com.superz.iptvplayer.data.xtream.PortalUrl
import com.superz.iptvplayer.data.xtream.VodInfoParser
import com.superz.iptvplayer.data.xtream.VodMovieInfo
import com.superz.iptvplayer.data.xtream.VodSeason
import com.superz.iptvplayer.data.xtream.VodSeriesInfo
import com.superz.iptvplayer.data.xtream.XtreamClient
import com.superz.iptvplayer.data.xtream.XtreamException
import com.superz.iptvplayer.ui.theme.PremiumPolicy
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Single source of truth for playlist data: login, sync (Xtream API / M3U download+parse),
 * activation, deletion, favorites and engine memory.
 */
class PlaylistRepository(private val app: IPTVApp) {

    private val db = app.database
    private val okHttp = app.okHttp
    private val xtream = XtreamClient(okHttp)

    // v1.5.0 — stalker-portal engine (new code; XC/M3U paths untouched).
    private val stalker = StalkerClient(okHttp)

    private val playlistDao get() = db.playlistDao()
    private val channelDao get() = db.channelDao()
    private val favoriteDao get() = db.favoriteDao()
    private val engineMemoryDao get() = db.engineMemoryDao()
    private val vodDao get() = db.vodDao()

    // ── Flows ──────────────────────────────────────────────────

    fun playlistsFlow(): Flow<List<Playlist>> = playlistDao.playlistsFlow()
    fun activePlaylistFlow(): Flow<Playlist?> = playlistDao.activePlaylistFlow()

    /**
     * v2.1.0 — THE FREE-PLAN ACCOUNT LIMIT (user, 2026-10-07: "يمكن
     * للمستخدم العادي تشغيل ثلاث حسابات… اما المستخدم premium يمكنه
     * تشغيل عدد غير محدود من الحسابات"). Enforced here — the single
     * choke point every add path flows through (manual forms, the code
     * login's generated accounts, the QR bridge, M3U→Xtream conversion).
     *
     * Two deliberate exceptions:
     *  • an account on the panel's LOCKED PREMIUM HOST always passes —
     *    that IS the purchase (PremiumScreen → loginXtream(premium.host));
     *  • once such an account's exp_date is still ahead (premium ACTIVE),
     *    the ceiling is gone entirely — unlimited accounts.
     */
    private suspend fun accountSlotBlocked(server: String?): Boolean {
        val host = com.superz.iptvplayer.data.remote.OriaRemote.current.premium.host
        if (server != null && PremiumPolicy.sameHost(server, host)) return false
        val accounts = playlistDao.playlistsOnce()
        val premiumActive = PremiumPolicy.status(
            premiumHost = host,
            accounts = accounts.map {
                PremiumPolicy.Account(it.server, it.expiryDate, it.createdAt, it.name)
            },
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        ).active
        return PremiumPolicy.accountLimitReached(accounts.size, premiumActive)
    }

    // ── Login / creation ───────────────────────────────────────

    /**
     * Authenticates against an Xtream panel and stores the playlist as ACTIVE.
     *
     * Reference-grade input tolerance: [server] may be a plain base URL, a base
     * behind a reverse-proxy path, a full `player_api.php` URL or even a full
     * `get.php` M3U link — [PortalUrl] extracts the clean base and any
     * credentials embedded in the query (used when the fields were left blank).
     * Throws [XtreamException] with machine-readable codes on failure.
     */
    suspend fun loginXtream(name: String, server: String, username: String, password: String): Playlist {
        val parsed = PortalUrl.parse(server) ?: throw XtreamException("INVALID_URL")
        val base = parsed.base
        val user = username.trim().ifBlank { parsed.username?.trim().orEmpty() }
        val pass = password.trim().ifBlank { parsed.password?.trim().orEmpty() }
        if (user.isBlank() || pass.isBlank()) throw XtreamException("ALL_FIELDS")
        val auth = xtream.authenticate(base, user, pass)
        val info = auth.userInfo
        if (info == null || info.auth != 1) {
            val status = info?.status ?: "unknown"
            val msg = info?.message ?: ""
            throw XtreamException("AUTH_DENIED|$status|$msg")
        }
        return saveXtreamPlaylist(name, base, user, pass, info)
    }

    /** Shared persistence path for both direct Xtream login and M3U→Xtream conversion. */
    private suspend fun saveXtreamPlaylist(
        name: String, base: String, user: String, pass: String,
        info: com.superz.iptvplayer.data.xtream.UserInfo
    ): Playlist {
        // Prevent duplicate accounts
        playlistDao.findXtream(base, user)?.let { existing ->
            playlistDao.activateExclusive(existing.id)
            return playlistDao.playlistById(existing.id)!!
        }
        // v2.1.0 — the free-plan ACCOUNT LIMIT (after the duplicate check:
        // re-activating an EXISTING account is never blocked).
        if (accountSlotBlocked(base)) {
            throw XtreamException("ACCOUNT_LIMIT|${PremiumPolicy.FREE_ACCOUNT_LIMIT}")
        }
        val playlist = Playlist(
            name = name.ifBlank { "Xtream: $user" },
            type = "XTREAM",
            server = base,
            username = user,
            password = pass,
            isActive = true
        )
        val id = playlistDao.upsert(playlist)
        playlistDao.activateExclusive(id)
        updateAccountInfo(id, info)
        return playlistDao.playlistById(id)!!
    }

    /**
     * Stores an M3U playlist as ACTIVE.
     *
     * Reference-grade smart detection: when the pasted URL carries Xtream
     * credentials in its query (the `get.php?username=…&password=…` links
     * providers hand out), the account is transparently converted to a full
     * Xtream login — categories, numbers, account info and API playback URLs.
     * Only when that fails (or the URL is a plain M3U) does it fall back to a
     * direct download.
     */
    suspend fun loginM3U(name: String, url: String): Playlist {
        val parsed = PortalUrl.parse(url)
        if (parsed != null && !parsed.username.isNullOrBlank() && !parsed.password.isNullOrBlank()) {
            try {
                val auth = xtream.authenticate(parsed.base, parsed.username, parsed.password)
                val info = auth.userInfo
                if (info != null && info.auth == 1) {
                    Log.i(TAG, "M3U link carried Xtream credentials — converted to API account")
                    return saveXtreamPlaylist(name, parsed.base, parsed.username, parsed.password, info)
                }
            } catch (e: XtreamException) {
                Log.w(TAG, "Xtream conversion failed (${e.message}) — falling back to direct M3U")
            }
        }
        // v2.1.0 — the free-plan ACCOUNT LIMIT (plain-M3U path; the
        // Xtream-conversion path above goes through saveXtreamPlaylist,
        // which allows the premium host through).
        if (accountSlotBlocked(null)) {
            throw XtreamException("ACCOUNT_LIMIT|${PremiumPolicy.FREE_ACCOUNT_LIMIT}")
        }
        val playlist = Playlist(
            name = name.ifBlank { "M3U Playlist" },
            type = "M3U",
            m3uUrl = url.trim(),
            isActive = true
        )
        val id = playlistDao.upsert(playlist)
        playlistDao.activateExclusive(id)
        return playlistDao.playlistById(id)!!
    }

    /**
     * v1.5.0 — Stalker-portal login (the reference's checkPortalUrl →
     * getToken → getProfile → addPortalToRealm ladder): normalize the URL
     * (Utils.getRealPortal), handshake (modern portal.php, legacy
     * stalker_portal fallback), validate the profile's id, then store the
     * account as ACTIVE — type "PORTAL", server = real base, username = MAC.
     * Throws [StalkerException] ("PORTAL_NOT_WORKING" / "DUPLICATE_NAME").
     */
    suspend fun loginPortal(name: String, url: String, mac: String): Playlist {
        val base = StalkerUrls.realPortal(url)
            ?: throw StalkerException("INVALID_PORTAL_URL")
        if (playlistDao.countByName(name.trim()) > 0) {
            throw StalkerException("DUPLICATE_NAME")
        }
        // v2.1.0 — the free-plan ACCOUNT LIMIT.
        if (accountSlotBlocked(base)) {
            throw StalkerException("ACCOUNT_LIMIT")
        }
        val session = stalker.handshake(base, mac.trim())
        if (!stalker.profileOk(session)) {
            throw StalkerException("PORTAL_NOT_WORKING")
        }
        val playlist = Playlist(
            name = name.ifBlank { "Portal: $mac" },
            type = "PORTAL",
            server = base,
            username = mac.trim(),
            isActive = true
        )
        val id = playlistDao.upsert(playlist)
        playlistDao.activateExclusive(id)
        return playlistDao.playlistById(id)!!
    }

    /**
     * v1.5.0 — local .m3u file account (the reference's checkFileUrl): the
     * SAF document uri (persistable read permission was taken at pick time)
     * is stored in m3uUrl and synced from disk, never from the network.
     * Throws [StalkerException] ("DUPLICATE_NAME").
     */
    suspend fun loginBrowserFile(name: String, fileUri: String): Playlist {
        if (playlistDao.countByName(name.trim()) > 0) {
            throw StalkerException("DUPLICATE_NAME")
        }
        // v2.1.0 — the free-plan ACCOUNT LIMIT.
        if (accountSlotBlocked(null)) {
            throw StalkerException("ACCOUNT_LIMIT")
        }
        val playlist = Playlist(
            name = name.ifBlank { "File Playlist" },
            type = "BROWSER",
            m3uUrl = fileUri,
            isActive = true
        )
        val id = playlistDao.upsert(playlist)
        playlistDao.activateExclusive(id)
        return playlistDao.playlistById(id)!!
    }

    private suspend fun updateAccountInfo(id: Long, info: com.superz.iptvplayer.data.xtream.UserInfo) {
        playlistDao.updateAccountInfo(
            id = id,
            active = info.activeConnectionsInt,
            max = info.maxConnectionsInt,
            expiry = info.expiryEpoch
        )
    }

    // ── Sync (runs on caller's scope; designed for appScope) ────

    /**
     * Refreshes the channel cache for a playlist. Returns the channel count.
     * Xtream: two API calls. M3U: full download + stream parse.
     * v1.5.0: PORTAL (stalker genres+channels) and BROWSER (local .m3u)
     * are NEW branches — the XC/M3U paths are byte-identical to v1.4.x.
     */
    suspend fun syncPlaylist(playlist: Playlist, onProgress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val count = when (playlist.type) {
            "XTREAM" -> syncXtream(playlist, onProgress)
            "M3U" -> syncM3U(playlist, onProgress)
            "PORTAL" -> syncStalker(playlist, onProgress)
            "BROWSER" -> syncBrowserFile(playlist, onProgress)
            else -> 0
        }
        // v1.6.0 — the reference stamps SharedPreferenceLastPlaylistDate when
        // a reload finishes; we stamp the row (the home's "Last Update" source).
        playlistDao.markSynced(playlist.id, System.currentTimeMillis() / 1000L)
        count
    }

    private suspend fun syncXtream(playlist: Playlist, onProgress: (Int) -> Unit): Int {
        val base = playlist.server ?: return 0
        val user = playlist.username ?: return 0
        val pass = playlist.password ?: return 0

        // Categories are BEST-EFFORT (reference behavior): a failure here must
        // never block the channel list — streams keep their raw category ids.
        val cats = try {
            xtream.liveCategories(base, user, pass)
        } catch (e: XtreamException) {
            Log.w(TAG, "live categories failed (${e.message}) — continuing without names")
            emptyList()
        }
        onProgress(0)

        val streams = try {
            xtream.liveStreams(base, user, pass)
        } catch (e: XtreamException) {
            // Some panels serve get.php M3U even when API actions fail — import
            // the playlist with direct URLs so channels still work.
            val viaM3u = try {
                syncXtreamViaGetPhp(playlist, base, user, pass, onProgress)
            } catch (e2: Exception) {
                -1
            }
            if (viaM3u > 0) return viaM3u
            throw e
        }

        val catRows = cats.map { Category(playlistId = playlist.id, categoryId = it.categoryId, name = it.categoryName) }
        val chRows = streams.mapIndexed { idx, s ->
            Channel(
                playlistId = playlist.id,
                key = "s:${s.streamId}",
                num = s.num ?: (idx + 1),
                name = s.name,
                logo = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId,
                streamId = s.streamId,
                directUrl = s.directSource?.takeIf { it.startsWith("http", true) },
                // v1.12.1 — the XC tv-archive flag (get_live_streams' tv_archive
                // = 1), the Catch-Up screen source for XC playlists (the
                // reference's RealmController.getLiveCatchupChannelsByCategory
                // queries the same field on EPGChannel).
                tvArchive = s.tvArchive?.takeIf { it == 1 }
            )
        }
        persist(playlist.id, catRows, chRows)
        onProgress(chRows.size)
        Log.i(TAG, "Xtream sync: ${chRows.size} channels, ${catRows.size} categories")
        return chRows.size
    }

    /** get.php fallback for panels whose player_api actions fail: import as M3U. */
    private suspend fun syncXtreamViaGetPhp(
        playlist: Playlist, base: String, user: String, pass: String,
        onProgress: (Int) -> Unit
    ): Int {
        val candidates = listOf(
            "$base/get.php?username=${XtreamClient.enc(user)}&password=${XtreamClient.enc(pass)}&type=m3u_plus",
            "$base/get.php?username=${XtreamClient.enc(user)}&password=${XtreamClient.enc(pass)}"
        )
        for (url in candidates) {
            try {
                val bytes = downloadM3U(url)
                val result = M3UParser.parse(bytes, playlist.id, onProgress)
                if (result.channels.isNotEmpty()) {
                    persist(playlist.id, result.categories, result.channels)
                    Log.i(TAG, "Xtream sync via get.php fallback: ${result.channels.size} channels")
                    return result.channels.size
                }
            } catch (e: Exception) {
                Log.w(TAG, "get.php fallback failed for $url (${e.message})")
            }
        }
        throw XtreamException("GET_PHP_FAILED")
    }

    private suspend fun syncM3U(playlist: Playlist, onProgress: (Int) -> Unit): Int {
        val url = playlist.m3uUrl ?: throw XtreamException("M3U_URL_MISSING")
        val bytes = downloadM3U(url)
        val result = M3UParser.parse(bytes, playlist.id, onProgress)
        if (result.channels.isEmpty()) throw XtreamException("M3U_EMPTY")
        persist(playlist.id, result.categories, result.channels)
        Log.i(TAG, "M3U sync: ${result.channels.size} channels, ${result.categories.size} categories")
        return result.channels.size
    }

    // ── v1.5.0 — PORTAL + BROWSER sync (new engines) ───────────

    /**
     * Stalker sync — the reference's getLiveData → getLiveGenre →
     * get_live_channels ladder: fresh handshake, genres (best-effort
     * category names, exactly like the Xtream path), then the full live
     * list. Channels persist with their `cmd` in [Channel.stalkerCmd] —
     * URLs are resolved per-play via create_link (StalkerPlayback).
     */
    private suspend fun syncStalker(playlist: Playlist, onProgress: (Int) -> Unit): Int {
        val base = playlist.server ?: throw StalkerException("PORTAL_URL_MISSING")
        val mac = playlist.username ?: throw StalkerException("MAC_MISSING")
        val session = stalker.handshake(base, mac)

        // Genres are BEST-EFFORT (reference behavior / same as Xtream sync).
        val genres = try {
            stalker.genres(session)
        } catch (e: Exception) {
            Log.w(TAG, "stalker genres failed (${e.message}) — continuing without names")
            emptyList()
        }
        onProgress(0)

        val channels = stalker.allChannels(session)
        if (channels.isEmpty()) throw StalkerException("PORTAL_EMPTY")

        val catRows = genres.mapNotNull { g ->
            val id = g.idString ?: return@mapNotNull null
            Category(playlistId = playlist.id, categoryId = id, name = g.title ?: id)
        }
        val chRows = channels.mapIndexedNotNull { idx, c ->
            stalkerChannelRow(playlist.id, c, idx)
        }
        persist(playlist.id, catRows, chRows)
        onProgress(chRows.size)
        Log.i(TAG, "Stalker sync: ${chRows.size} channels, ${catRows.size} genres")

        // v1.12.5 — BaseActivity.getAdultChannel, VERBATIM: portals EXCLUDE
        // the xxx genre's channels from get_all_channels (live-proven: the
        // user's portal hides 320 channels behind genre 75 "FOR ADULTS" —
        // the reference app carries them, we didn't). The reference detects
        // the genre id from the genre list (title contains "xxx"/"adult",
        // LAST match overwrites Constants.xxx_category_id) and pulls EVERY
        // page of get_ordered_list&genre=<id> (14 rows/page, pages =
        // total/14 [+1 when remainder]), UPSERTING the rows additively.
        val xxxGenreId = StalkerClient.xxxGenreId(genres)
        var totalCount = chRows.size
        if (xxxGenreId != null) {
            try {
                val adultRows = ArrayList<Channel>(64)
                var page = 1
                var pages = 1
                while (page <= pages) {
                    val (rows, totalItems) = stalker.adultChannelsPage(session, xxxGenreId, page)
                    if (rows.isEmpty()) break
                    pages = if (totalItems > 0) StalkerClient.adultPagesFor(totalItems) else page
                    rows.forEachIndexed { i, c -> stalkerChannelRow(playlist.id, c, i)?.let { adultRows.add(it) } }
                    page++
                }
                if (adultRows.isNotEmpty()) {
                    db.withTransaction {
                        adultRows.chunked(500).forEach { channelDao.insertChannels(it) }
                        totalCount = chRows.size + adultRows.size
                        playlistDao.updateChannelCount(playlist.id, totalCount)
                    }
                    Log.i(TAG, "Stalker adult sync (genre $xxxGenreId): +${adultRows.size} channels")
                }
            } catch (e: Exception) {
                // The reference retries 5x then continues with the regular
                // list — a failed adult fetch must never fail the sync.
                Log.w(TAG, "stalker adult sync failed (${e.message}) — continuing")
            }
        }
        onProgress(totalCount)
        return totalCount
    }

    /** One StalkerChannel wire row → our Channel row (get_all_channels AND the adult pages share the mapping). */
    private fun stalkerChannelRow(pid: Long, c: StalkerChannel, fallbackIdx: Int): Channel? {
        val id = c.idString ?: return null
        return Channel(
            playlistId = pid,
            key = "k:$id",
            num = c.numberInt ?: (fallbackIdx + 1),
            name = c.name ?: "Channel $id",
            logo = c.logo?.takeIf { it.isNotBlank() },
            categoryId = c.genreId,
            stalkerCmd = c.cmd,
            // v1.12.0 — the tv-archive flag (Catch-Up screen source).
            tvArchive = c.tvArchiveFlag?.takeIf { it == 1 }
        )
    }

    /**
     * Local .m3u file sync (the reference's checkFileUrl → parse): reads
     * the persistable SAF document, validates it carries stream URLs
     * (Utils.checkFileContainUrl), then reuses the SAME M3U parser the
     * network path uses.
     */
    private suspend fun syncBrowserFile(playlist: Playlist, onProgress: (Int) -> Unit): Int {
        val uriStr = playlist.m3uUrl ?: throw XtreamException("M3U_URL_MISSING")
        val bytes = try {
            android.net.Uri.parse(uriStr).let { uri ->
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(1 shl 20)
                    val chunk = ByteArray(1 shl 15)
                    while (true) {
                        val r = input.read(chunk)
                        if (r < 0) break
                        out.write(chunk, 0, r)
                    }
                    out.toByteArray()
                } ?: throw XtreamException("FILE_NOT_READABLE")
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            throw XtreamException("FILE_NOT_READABLE")
        }
        if (!bytes.toString(Charsets.UTF_8).contains("http")) {
            throw XtreamException("FILE_NO_URL")
        }
        val result = M3UParser.parse(bytes, playlist.id, onProgress)
        if (result.channels.isEmpty()) throw XtreamException("M3U_EMPTY")
        persist(playlist.id, result.categories, result.channels)
        Log.i(TAG, "Browser file sync: ${result.channels.size} channels")
        return result.channels.size
    }

    // ── v1.4.0 — VOD sync (movies + series) ────────────────────

    /**
     * Refreshes the movie + series caches. NEVER throws (silent-degradation
     * contract): a panel without VOD, or a transient VOD failure, can never
     * break live-TV sync or the loading pipeline.
     *
     * v1.11.0 — PORTAL playlists sync through the STALKER engine
     * (syncVodStalker): categories + the FIRST pages of the "All" lists
     * (the server pages 14 rows per request — syncing all 64 K movies of a
     * big portal would need 4 6 0 0 + requests, exactly why the reference
     * browses live page-by-page). The browse screen continues the list
     * lazily via [stalkerVodLoadMore] as the user scrolls.
     *
     * @param onStage "MOVIES" / "SERIES" — lets the loading screen show
     *        a stage per section.
     * @return (movieCount, seriesCount)
     */
    suspend fun syncVod(
        playlist: Playlist,
        onStage: (String) -> Unit = {}
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (playlist.type == "PORTAL") {
            return@withContext try {
                syncVodStalker(playlist, onStage)
            } catch (e: Exception) {
                Log.w(TAG, "stalker VOD sync failed (${e.message})")
                0 to 0
            }
        }
        if (playlist.type != "XTREAM") return@withContext 0 to 0
        val base = playlist.server ?: return@withContext 0 to 0
        val user = playlist.username ?: return@withContext 0 to 0
        val pass = playlist.password ?: return@withContext 0 to 0

        var movieCount = 0
        var seriesCount = 0

        try {
            onStage("MOVIES")
            val cats = try { xtream.vodCategories(base, user, pass) } catch (e: Exception) {
                Log.w(TAG, "vod categories failed (${e.message})")
                emptyList()
            }
            val streams = try { xtream.vodStreams(base, user, pass) } catch (e: Exception) {
                Log.w(TAG, "vod streams failed (${e.message})")
                emptyList()
            }
            if (streams.isNotEmpty()) {
                val catRows = cats.map { VodCategory(playlistId = playlist.id, categoryId = it.categoryId, name = it.categoryName) }
                val rows = streams.mapIndexed { idx, s ->
                    Movie(
                        playlistId = playlist.id,
                        key = "m:${s.streamId}",
                        num = s.num ?: (idx + 1),
                        name = s.name,
                        poster = s.streamIcon?.takeIf { it.isNotBlank() },
                        categoryId = s.categoryId,
                        streamId = s.streamId,
                        containerExtension = s.containerExtension?.takeIf { it.isNotBlank() }
                    )
                }
                db.withTransaction {
                    vodDao.clearMovies(playlist.id)
                    vodDao.clearVodCategories(playlist.id)
                    vodDao.insertVodCategories(catRows)
                    rows.chunked(500).forEach { vodDao.insertMovies(it) }
                }
                movieCount = rows.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "VOD sync failed: ${e.message}")
        }

        try {
            onStage("SERIES")
            val cats = try { xtream.seriesCategories(base, user, pass) } catch (e: Exception) {
                Log.w(TAG, "series categories failed (${e.message})")
                emptyList()
            }
            val shows = try { xtream.seriesList(base, user, pass) } catch (e: Exception) {
                Log.w(TAG, "series list failed (${e.message})")
                emptyList()
            }
            if (shows.isNotEmpty()) {
                val catRows = cats.map { SeriesCategory(playlistId = playlist.id, categoryId = it.categoryId, name = it.categoryName) }
                val rows = shows.mapIndexed { idx, s ->
                    SeriesShow(
                        playlistId = playlist.id,
                        key = "sr:${s.seriesId}",
                        num = s.num ?: (idx + 1),
                        name = s.name,
                        poster = s.cover?.takeIf { it.isNotBlank() },
                        categoryId = s.categoryId,
                        // v1.4.1 fix — v1.4.0 omitted this, leaving seriesId
                        // NULL in every row: the browse grid then navigated
                        // with seriesId=0 and info pages showed 0 episodes.
                        seriesId = s.seriesId
                    )
                }
                db.withTransaction {
                    vodDao.clearSeries(playlist.id)
                    vodDao.clearSeriesCategories(playlist.id)
                    vodDao.insertSeriesCategories(catRows)
                    rows.chunked(500).forEach { vodDao.insertSeries(it) }
                }
                seriesCount = rows.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "Series sync failed: ${e.message}")
        }

        Log.i(TAG, "VOD sync: $movieCount movies, $seriesCount series")
        movieCount to seriesCount
    }

    // ── v1.11.0 — Stalker VOD & Series sync (reference-faithful) ───

    /** Companion of [syncVod] for PORTAL playlists — see its doc. */
    private suspend fun syncVodStalker(
        playlist: Playlist,
        onStage: (String) -> Unit
    ): Pair<Int, Int> {
        val session = StalkerPlayback.session(app, playlist)
        val base = session.base
        var movieCount = 0
        var seriesCount = 0

        // ── movies ──
        try {
            onStage("MOVIES")
            val cats = stalker.vodCategories(session)
            val catRows = StalkerVod.categoryRows(playlist.id, cats)
                .map { (id, name) -> VodCategory(playlistId = playlist.id, categoryId = id, name = name) }
            val pages = stalkerInitialPages(session, series = false)
            var total: Int? = null
            val rows = ArrayList<Movie>(pages * 14)
            for (p in 1..pages) {
                val env = stalker.orderedList(session, series = false, category = "*", search = "", page = p)
                val items = env.js?.data.orEmpty()
                total = total ?: env.js?.totalItems
                if (items.isEmpty()) break
                val start = rows.size
                items.forEachIndexed { i, row ->
                    StalkerVod.movieRow(playlist.id, row, start + i + 1, StalkerVod.json.encodeToString(StalkerVod.StalkerVodRow.serializer(), row), base)?.let { rows.add(it) }
                }
            }
            if (rows.isNotEmpty()) {
                db.withTransaction {
                    vodDao.clearMovies(playlist.id)
                    vodDao.clearVodCategories(playlist.id)
                    vodDao.insertVodCategories(catRows)
                    rows.chunked(500).forEach { vodDao.insertMovies(it) }
                }
                movieCount = rows.size
            }
            updateStalkerTotals(playlist.id, vod = total, series = null)
            // the tracker now knows pages 1..N are cached for "*|"
            stalkerPageTracker[pageKey(playlist.id, false, "*", "")] = pages
            stalkerTotalTracker[pageKey(playlist.id, false, "*", "")] = total ?: rows.size
            Log.i(TAG, "stalker VOD sync: ${rows.size} movies (total $total), ${catRows.size} categories")
        } catch (e: Exception) {
            Log.w(TAG, "stalker VOD sync failed: ${e.message}")
        }

        // ── series ──
        try {
            onStage("SERIES")
            val cats = stalker.seriesCategories(session)
            val catRows = StalkerVod.categoryRows(playlist.id, cats)
                .map { (id, name) -> SeriesCategory(playlistId = playlist.id, categoryId = id, name = name) }
            val pages = stalkerInitialPages(session, series = true)
            var total: Int? = null
            val rows = ArrayList<SeriesShow>(pages * 14)
            for (p in 1..pages) {
                val env = stalker.orderedList(session, series = true, category = "*", search = "", page = p)
                val items = env.js?.data.orEmpty()
                total = total ?: env.js?.totalItems
                if (items.isEmpty()) break
                val start = rows.size
                items.forEachIndexed { i, row ->
                    StalkerVod.seriesRow(playlist.id, row, start + i + 1, StalkerVod.json.encodeToString(StalkerVod.StalkerVodRow.serializer(), row), base)?.let { rows.add(it) }
                }
            }
            if (rows.isNotEmpty()) {
                db.withTransaction {
                    vodDao.clearSeries(playlist.id)
                    vodDao.clearSeriesCategories(playlist.id)
                    vodDao.insertSeriesCategories(catRows)
                    rows.chunked(500).forEach { vodDao.insertSeries(it) }
                }
                seriesCount = rows.size
            }
            updateStalkerTotals(playlist.id, vod = null, series = total)
            stalkerPageTracker[pageKey(playlist.id, true, "*", "")] = pages
            stalkerTotalTracker[pageKey(playlist.id, true, "*", "")] = total ?: rows.size
            Log.i(TAG, "stalker series sync: ${rows.size} series (total $total), ${catRows.size} categories")
        } catch (e: Exception) {
            Log.w(TAG, "stalker series sync failed: ${e.message}")
        }

        return movieCount to seriesCount
    }

    /**
     * v1.11.0 — the lazy continuation of a Stalker browse list
     * (ItemActivity's infinite scroll): fetches the NEXT page for
     * (playlist, kind, category, search) and UPSERTS the rows into Room —
     * the grid's reactive flow picks them up as they land. Page state is
     * tracked HERE so the initial sync's pages count.
     *
     * @return true when a page was fetched and inserted (the caller may
     *         call again immediately).
     */
    suspend fun stalkerVodLoadMore(
        playlist: Playlist,
        series: Boolean,
        categoryId: String,
        query: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (playlist.type != "PORTAL") return@withContext false
        val cat = categoryId.ifBlank { "*" }
        val search = query.trim()
        val key = pageKey(playlist.id, series, cat, search)
        val loaded = stalkerPageTracker[key] ?: 0
        val total = stalkerTotalTracker[key]
        // the reference's guard: page ≤ total/14 + 1
        if (total != null && loaded >= total && loaded > 0) return@withContext false
        val next = loaded + 1
        if (total != null && !StalkerVod.pageAllowed(next, total)) return@withContext false
        try {
            // v1.12.5 — renewed on 401/403 (runtime browse path: the token
            // can die mid-session; the fresh handshake feeds the retry).
            var sessionBase = ""
            val env = StalkerPlayback.authed(app, playlist) { s ->
                sessionBase = s.base
                stalker.orderedList(s, series, cat, search, next)
            }
            val items = env.js?.data.orEmpty()
            val serverTotal = env.js?.totalItems ?: 0
            stalkerTotalTracker[key] = serverTotal
            if (items.isEmpty()) {
                stalkerPageTracker[key] = Int.MAX_VALUE / 2   // hard stop
                return@withContext false
            }
            val start = loaded * 14
            val rowJsons = items.map { StalkerVod.json.encodeToString(StalkerVod.StalkerVodRow.serializer(), it) }
            if (series) {
                val rows = items.mapIndexedNotNull { i, row ->
                    StalkerVod.seriesRow(playlist.id, row, start + i + 1, rowJsons[i], sessionBase)
                }
                rows.chunked(500).forEach { vodDao.insertSeries(it) }
            } else {
                val rows = items.mapIndexedNotNull { i, row ->
                    StalkerVod.movieRow(playlist.id, row, start + i + 1, rowJsons[i], sessionBase)
                }
                rows.chunked(500).forEach { vodDao.insertMovies(it) }
            }
            stalkerPageTracker[key] = next
            Log.i(TAG, "stalker loadMore ${if (series) "series" else "movies"} cat=$cat p=$next (+${items.size})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "stalker loadMore failed: ${e.message}")
            false
        }
    }

    /**
     * v1.11.0 — the FIRST page fetch for a (category, search) the cache
     * has nothing for yet: fetches page 1 when the Room cache for the key
     * is empty, so selecting a category fills its grid. Cheap no-op when
     * the tracker already knows the key.
     */
    suspend fun stalkerVodEnsureFirstPage(
        playlist: Playlist,
        series: Boolean,
        categoryId: String,
        query: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (playlist.type != "PORTAL") return@withContext false
        val cat = categoryId.ifBlank { "*" }
        val search = query.trim()
        val key = pageKey(playlist.id, series, cat, search)
        if (stalkerPageTracker.containsKey(key)) return@withContext false
        stalkerVodLoadMore(playlist, series, categoryId, query)
    }

    private fun pageKey(pid: Long, series: Boolean, category: String, search: String): String =
        "$pid|${if (series) "S" else "M"}|$category|${search.lowercase()}"

    private val stalkerPageTracker = HashMap<String, Int>()
    private val stalkerTotalTracker = HashMap<String, Int>()

    /** Test visibility for the trackers. */
    internal fun stalkerTrackerPagesForTest(pid: Long, series: Boolean, cat: String, q: String): Int? =
        stalkerPageTracker[pageKey(pid, series, cat, q)]

    /** How many pages the initial sync prefetches (84 rows per section). */
    private suspend fun stalkerInitialPages(session: StalkerSession, series: Boolean): Int =
        if (session.html) 4 else 6

    private suspend fun updateStalkerTotals(pid: Long, vod: Int?, series: Int?) {
        if (vod == null && series == null) return
        try {
            val pl = playlistDao.playlistById(pid) ?: return
            playlistDao.upsert(
                pl.copy(
                    stalkerVodTotal = vod ?: pl.stalkerVodTotal,
                    stalkerSeriesTotal = series ?: pl.stalkerSeriesTotal
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "stalker totals update failed: ${e.message}")
        }
    }

    // ── v1.4.0 — VOD info (lazy fetch + memory cache) ──────────

    private val infoCache = HashMap<String, Pair<Long, Any>>()
    private val infoCacheLock = Any()
    private val infoCacheTtlMs = 10 * 60 * 1000L

    private suspend fun cachedInfo(key: String, fetch: suspend () -> Any?): Any? {
        synchronized(infoCacheLock) {
            infoCache[key]?.let { (at, value) ->
                if (System.currentTimeMillis() - at < infoCacheTtlMs) return value
            }
        }
        val fresh = fetch() ?: return null
        synchronized(infoCacheLock) { infoCache[key] = System.currentTimeMillis() to fresh }
        return fresh
    }

    /**
     * Movie details for the info page — never throws (null on failure).
     * v1.11.0 — PORTAL playlists: the stalker row IS the info source
     * (stalker portals have no get_vod_info; the reference passes the
     * whole Movie object to MovieInfoActivity) — pure Room read, no net.
     */
    suspend fun movieInfo(playlist: Playlist, streamId: Long): VodMovieInfo? {
        if (playlist.type == "PORTAL") {
            return stalkerMovieInfo(playlist, streamId)
        }
        val cached = cachedInfo("m:${playlist.id}:$streamId") {
            try {
                val raw = xtream.vodInfoRaw(
                    playlist.server ?: return@cachedInfo null,
                    playlist.username ?: return@cachedInfo null,
                    playlist.password ?: return@cachedInfo null,
                    streamId
                )
                VodInfoParser.parseMovieInfo(raw)
            } catch (e: Exception) {
                Log.w(TAG, "vod info failed: ${e.message}")
                null
            }
        }
        return cached as? VodMovieInfo
    }

    /** v1.11.0 — PORTAL movie info from the stored row JSON. */
    private suspend fun stalkerMovieInfo(playlist: Playlist, streamId: Long): VodMovieInfo? {
        val movie = vodDao.movieByStreamId(playlist.id, streamId) ?: return null
        val rowJson = movie.stalkerRow
        val row = rowJson?.let { StalkerVod.parseRow(it) }
        return if (row != null) {
            StalkerVod.movieInfo(row, streamId, playlist.server)
        } else {
            // degraded fallback — the bare Room row (name + poster)
            VodMovieInfo(
                name = movie.name, poster = movie.poster, plot = null, genre = null,
                director = null, cast = null, releaseDate = null, duration = null,
                rating = null, streamId = streamId,
                containerExtension = movie.containerExtension
            )
        }
    }

    /**
     * Series details (seasons + episodes) — never throws (null on failure).
     * v1.11.0 — PORTAL playlists: metadata from the stored row + seasons
     * through the portal family's flow (Ministra get_season_models with
     * the embedded episode numbers / HTML season+episode middleware).
     */
    suspend fun seriesInfo(playlist: Playlist, seriesId: Long): VodSeriesInfo? {
        if (playlist.type == "PORTAL") {
            val cached = cachedInfo("srs:${playlist.id}:$seriesId") {
                try {
                    stalkerSeriesInfo(playlist, seriesId)
                } catch (e: Exception) {
                    Log.w(TAG, "stalker series info failed: ${e.message}")
                    null
                }
            }
            return cached as? VodSeriesInfo
        }
        val cached = cachedInfo("sr:${playlist.id}:$seriesId") {
            try {
                val raw = xtream.seriesInfoRaw(
                    playlist.server ?: return@cachedInfo null,
                    playlist.username ?: return@cachedInfo null,
                    playlist.password ?: return@cachedInfo null,
                    seriesId
                )
                VodInfoParser.parseSeriesInfo(raw)
            } catch (e: Exception) {
                Log.w(TAG, "series info failed: ${e.message}")
                null
            }
        }
        return cached as? VodSeriesInfo
    }

    /**
     * v1.11.0 — PORTAL series info (SeriesInfoActivity, verbatim flows):
     *  • Ministra: get_season_models(movie_id = the FULL row id
     *    "46170:46170", category) → rows REVERSED → episodes = the numbers
     *    embedded in each season's series[] → create_link(season.cmd,
     *    series = epNum) at play time.
     *  • HTML: get_season_middleware → per season, paged
     *    get_episode_middleware → play via the single-item file-id lookup.
     */
    private suspend fun stalkerSeriesInfo(playlist: Playlist, seriesId: Long): VodSeriesInfo? {
        val show = vodDao.seriesBySeriesId(playlist.id, seriesId) ?: return null
        val row = show.stalkerRow?.let { StalkerVod.parseRow(it) }
        val movieId = row?.idString ?: show.key.removePrefix("sr:")
        val category = row?.categoryId ?: "*"

        // v1.12.5 — the whole ladder renewed on 401/403 (runtime info path:
        // a dead token previously returned an empty seasons list forever).
        val seasons: List<VodSeason> = StalkerPlayback.authed(app, playlist) { session ->
            if (session.html) {
                val seasonRows = stalker.seasonMiddleware(session, movieId, category)
                val episodesBySeason = HashMap<String, List<StalkerVod.EpisodeMiddlewareRow>>()
                for (season in seasonRows) {
                    val sid = season.idString ?: continue
                    val eps = ArrayList<StalkerVod.EpisodeMiddlewareRow>()
                    var page = 1
                    // the reference pages these the same way as lists; cap at
                    // 30 pages/season (420 episodes) to stay bounded.
                    while (page <= 30) {
                        val env = stalker.episodeMiddleware(session, movieId, sid, category, page)
                        val items = env.js?.data.orEmpty()
                        if (items.isEmpty()) break
                        eps.addAll(items)
                        val total = env.js?.totalItems ?: break
                        if (!StalkerVod.pageAllowed(page, total)) break
                        page++
                    }
                    episodesBySeason[sid] = eps
                }
                StalkerVod.htmlSeasons(seasonRows, episodesBySeason, movieId, category)
            } else {
                val env = stalker.seasons(session, movieId, category, 1)
                StalkerVod.ministraSeasons(env.js?.data.orEmpty())
            }
        }

        return StalkerVod.seriesInfo(row, show, playlist.server, seasons)
    }

    // ── v1.4.0 — VOD playback URLs (standard Xtream paths) ─────

    fun movieUrl(playlist: Playlist, streamId: Long, ext: String?): String? {
        val base = playlist.server?.trimEnd('/') ?: return null
        val u = playlist.username ?: return null
        val p = playlist.password ?: return null
        val safeExt = ext?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/movie/$u/$p/$streamId.$safeExt"
    }

    fun episodeUrl(playlist: Playlist, episodeId: Long, ext: String?): String? {
        val base = playlist.server?.trimEnd('/') ?: return null
        val u = playlist.username ?: return null
        val p = playlist.password ?: return null
        val safeExt = ext?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/series/$u/$p/$episodeId.$safeExt"
    }

    private suspend fun persist(pid: Long, catRows: List<Category>, chRows: List<Channel>) {
        db.withTransaction {
            channelDao.clearChannels(pid)
            channelDao.clearCategories(pid)
            channelDao.insertCategories(catRows)
            chRows.chunked(500).forEach { channelDao.insertChannels(it) }
            playlistDao.updateChannelCount(pid, chRows.size)
        }
    }

    private fun downloadM3U(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        try {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw XtreamException("HTTP_${response.code}")
                val body = response.body ?: throw XtreamException("EMPTY_BODY")
                val out = java.io.ByteArrayOutputStream(1 shl 20)
                body.byteStream().use { input ->
                    val chunk = ByteArray(1 shl 15)
                    while (true) {
                        val r = input.read(chunk)
                        if (r < 0) break
                        out.write(chunk, 0, r)
                    }
                }
                if (out.size() < 16) throw XtreamException("M3U_EMPTY")
                return out.toByteArray()
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw XtreamException("TIMEOUT")
        } catch (e: java.net.UnknownHostException) {
            throw XtreamException("HOST_NOT_FOUND")
        } catch (e: javax.net.ssl.SSLException) {
            throw XtreamException("SSL_ERROR")
        } catch (e: Exception) {
            throw XtreamException(e.message ?: "DOWNLOAD_ERROR")
        }
    }

    // ── Playlist management ─────────────────────────────────────

    suspend fun activate(id: Long) {
        db.withTransaction { playlistDao.activateExclusive(id) }
    }

    /** v1.4.8 — edit an existing account in place (user-list page edit
     *  button). Same primary key → Room REPLACE keeps the row's id and all
     *  synced children (channels / VOD / favorites) attached. */
    suspend fun update(playlist: Playlist) {
        db.withTransaction { playlistDao.upsert(playlist) }
    }

    suspend fun delete(playlist: Playlist) {
        db.withTransaction {
            playlistDao.delete(playlist.id)
            channelDao.clearChannels(playlist.id)
            channelDao.clearCategories(playlist.id)
            favoriteDao.clearFor(playlist.id)
            engineMemoryDao.clearFor(playlist.id)
            vodDao.clearMovies(playlist.id)
            vodDao.clearVodCategories(playlist.id)
            vodDao.clearSeries(playlist.id)
            vodDao.clearSeriesCategories(playlist.id)
            vodDao.clearVodFavorites(playlist.id)
        }
        // If the active one was deleted → activate the newest remaining
        if (playlist.isActive) {
            val remaining = playlistDao.playlistsOnce()
            if (remaining.isNotEmpty()) playlistDao.activateExclusive(remaining.first().id)
        }
    }

    // ── Favorites ───────────────────────────────────────────────

    suspend fun isFavorite(pid: Long, key: String): Boolean = favoriteDao.isFavorite(pid, key)

    suspend fun toggleFavorite(pid: Long, key: String): Boolean {
        val exists = favoriteDao.isFavorite(pid, key)
        if (exists) favoriteDao.remove(pid, key) else favoriteDao.add(Favorite(pid, key))
        return !exists
    }

    /** v1.4.0 — heart toggle for movies ("m:123") and series ("sr:456"). */
    suspend fun toggleVodFavorite(pid: Long, contentKey: String, contentType: String): Boolean {
        val exists = vodDao.isVodFavorite(pid, contentKey)
        if (exists) vodDao.removeVodFavorite(pid, contentKey)
        else vodDao.addVodFavorite(VodFavorite(pid, contentKey, contentType))
        return !exists
    }

    // ── Engine memory ───────────────────────────────────────────

    suspend fun engineMemory(pid: Long): Map<String, EngineMemory> =
        engineMemoryDao.memoryForPlaylist(pid).associateBy { it.channelKey }

    suspend fun rememberEngine(pid: Long, key: String, engine: String, variant: String?) {
        try {
            engineMemoryDao.save(EngineMemory(pid, key, engine, variant))
        } catch (e: Exception) {
            Log.w(TAG, "engine memory save failed", e)
        }
    }

    companion object {
        private const val TAG = "PlaylistRepo"

        @Volatile private var instance: PlaylistRepository? = null
        fun get(app: IPTVApp): PlaylistRepository =
            instance ?: synchronized(this) { instance ?: PlaylistRepository(app).also { instance = it } }
    }
}

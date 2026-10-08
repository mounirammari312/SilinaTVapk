package com.superz.iptvplayer.data.epg

import com.superz.iptvplayer.data.db.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * EPG data source over the player_api (Xtream panels).
 *
 *  • get_short_epgs   → "now" line under each channel row (chunked, cached 2 min)
 *  • get_simple_data_table → full program table for the selected channel
 *    (cached 5 min)
 *
 * Uses the ONE shared OkHttpClient (VLC User-Agent spoofing + DoH chain +
 * timeouts) — identical network behavior to login/sync/playback, so panels
 * treat EPG requests exactly like any other player_api call.
 *
 * Silent-degradation contract: every failure returns empty data (the UI
 * shows the graceful "EPG unavailable" state) — EPG must NEVER break zapping
 * or playback.
 */
class EpgRepository private constructor(private val okHttp: OkHttpClient) {

    companion object {
        @Volatile
        private var instance: EpgRepository? = null

        fun get(okHttp: OkHttpClient): EpgRepository =
            instance ?: synchronized(this) {
                instance ?: EpgRepository(okHttp).also { instance = it }
            }

        /** "Now" lines: refresh cost is cheap, panels limit short-EPG rows. */
        private const val SHORT_TTL_MS = 2 * 60 * 1000L

        /** Full table: bigger payload — hold it longer. */
        private const val FULL_TTL_MS = 5 * 60 * 1000L

        /** get_short_epgs batch size (URL length safety on picky panels). */
        private const val SHORT_CHUNK = 50

        /** Guard rail for gigantic categories. */
        private const val SHORT_MAX_IDS = 400
    }

    private class Entry(val at: Long, val programs: List<EpgProgram>)

    private val shortCache = ConcurrentHashMap<Long, Entry>()
    private val fullCache = ConcurrentHashMap<Long, Entry>()

    // A trimmed-timeout client for metadata fetches (same interceptors/DoH).
    private val quickClient: OkHttpClient by lazy {
        okHttp.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** v1.12.5 — the uncapped fetch (full tables): the main client's 60 s
     *  read timeout governs — multi-day get_simple_data_table payloads are
     *  big and the reference caps nothing. */
    private suspend fun fetchFull(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = okHttp.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                if (body.isBlank() || body.trim().startsWith("<")) return@withContext null
                body
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * "Now" titles for the given stream ids — served from cache when fresh,
     * fetched (chunked) otherwise. Returns streamId → current program.
     */
    suspend fun shortEpg(
        playlist: Playlist,
        streamIds: List<Long>,
        nowMs: Long = System.currentTimeMillis()
    ): Map<Long, EpgProgram> {
        val ids = streamIds.distinct().take(SHORT_MAX_IDS)
        if (ids.isEmpty() || !isXtream(playlist)) return emptyMap()

        val fresh = HashMap<Long, EpgProgram>()
        val stale = ArrayList<Long>(ids.size)

        synchronized(this) {
            for (id in ids) {
                val e = shortCache[id]
                if (e != null && nowMs - e.at < SHORT_TTL_MS) {
                    EpgParser.currentProgram(e.programs, nowMs)?.let { fresh[id] = it }
                } else {
                    stale += id
                }
            }
        }
        if (stale.isEmpty()) return fresh

        stale.chunked(SHORT_CHUNK).forEach { chunk ->
            val url = apiUrl(playlist) +
                "action=get_short_epgs&stream_id=${chunk.joinToString(",")}&limit=2"
            val body = fetch(url) ?: return@forEach
            val parsed = EpgParser.parseShortResponse(body)
            synchronized(this) {
                for ((id, programs) in parsed) {
                    shortCache[id] = Entry(nowMs, programs)
                    EpgParser.currentProgram(programs, nowMs)?.let { fresh[id] = it }
                }
                // Panels that return nothing for an id: cache the miss too so
                // we stop re-asking within the TTL window.
                for (id in chunk) if (id !in parsed) shortCache[id] = Entry(nowMs, emptyList())
            }
        }
        return fresh
    }

    /**
     * Full program table for one stream (now + upcoming), cached.
     * Empty list ⇒ panel has no EPG for this channel (UI shows the
     * graceful "unavailable" state).
     *
     * v1.12.5 — TWO Catch-Up root-cause fixes, reference-verbatim behavior:
     *  1. the MAIN client (60 s read) — the old 12 s quickClient cap killed
     *     the multi-day get_simple_data_table payloads on slower links (the
     *     reference caps nothing);
     *  2. a FAILED fetch is NOT cached — the old code cached emptyList for
     *     5 minutes after one timeout, so every reopen within the window
     *     showed "No Catch Up Found" without even retrying.
     */
    suspend fun fullEpg(
        playlist: Playlist,
        streamId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): List<EpgProgram> {
        if (!isXtream(playlist)) return emptyList()
        synchronized(this) {
            val hit = fullCache[streamId]
            if (hit != null && nowMs - hit.at < FULL_TTL_MS) return hit.programs
        }
        val url = apiUrl(playlist) +
            "action=get_simple_data_table&stream_id=$streamId"
        val body = fetchFull(url)
        if (body == null) return emptyList()    // failure — retry next open
        val programs = EpgParser.parseFullResponse(body)
        synchronized(this) {
            fullCache[streamId] = Entry(nowMs, programs)
        }
        return programs
    }

    // ── internals ──────────────────────────────────────────────

    private fun isXtream(playlist: Playlist): Boolean =
        playlist.type == "XTREAM" &&
            !playlist.server.isNullOrBlank() &&
            !playlist.username.isNullOrBlank() &&
            !playlist.password.isNullOrBlank()

    private fun apiUrl(playlist: Playlist): String {
        val base = playlist.server!!.trim().trimEnd('/')
        val u = java.net.URLEncoder.encode(playlist.username, "UTF-8")
        val p = java.net.URLEncoder.encode(playlist.password, "UTF-8")
        return "$base/player_api.php?username=$u&password=$p&"
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = quickClient.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                if (body.isBlank() || body.trim().startsWith("<")) return@withContext null
                body
            }
        } catch (_: Throwable) {
            // Network/panel failure — silent degradation to "no EPG".
            null
        }
    }
}

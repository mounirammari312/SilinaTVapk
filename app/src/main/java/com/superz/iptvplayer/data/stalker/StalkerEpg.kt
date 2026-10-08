package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.7.0 — Stalker EPG engine, replicated from the reference's
 * StalkerEpgDownloadService + StalkerCatchUpDetailFragment:
 *
 *  • ROWS ("now" line under each channel row):
 *      `type=itv&action=get_epg_info&period=10` — ONE bulk download that
 *      carries the whole channel→programs map (what the reference stores
 *      into Realm and reads back via getEpgModel(stream_id)). Cached in
 *      memory for [BULK_TTL_MS] (the reference's STALKER_EPG_DATE_PERIOD
 *      is 18000 s = 5 h).
 *  • PANEL (full table of the selected channel):
 *      `type=epg&action=get_simple_data_table&ch_id=<id>&date=<mysql>&p=<n>`
 *      — 10 rows per page, fetched until the portal's total_items is
 *      covered (the reference's CatchUp pagination math), cached per
 *      (channel, date) for 10 minutes.
 *
 *  ── TIMEZONE SEMANTICS (proven live against mag.max-cdn.com) ──
 *  A controlled triple-request experiment (cookie timezone = UTC /
 *  Europe/London / Asia/Tokyo, same program) pinned the exact formula:
 *  the portal receives the broadcast's real epoch R, then answers with
 *  ts = R + offset(cookieTz) − offset(portalDefaultTz) — the wall-clock
 *  delta between the requested zone and the portal's own zone
 *  (get_profile → js.default_timezone), encoded as an epoch shift. The
 *  reference inverts EXACTLY this (Function.getDateCurrentTimeZone with
 *  fromTimeZone = the profile's default_timezone):
 *        R = ts − offset(cookieTz) + offset(portalTz)
 *  Our cookie always carries the DEVICE timezone
 *  (StalkerUrls.cookie), so rendering R with the plain device-local
 *  formatter (EpgProgram.toEpgTimeLabel) shows the correct wall clock.
 *
 *  Silent-degradation contract (same as EpgRepository): every failure
 *  returns empty data — EPG must NEVER break zapping or playback.
 */
object StalkerEpgParser {

    /**
     * Portal epoch → real epoch ms (the reference's getDateCurrentTimeZone
     * with fromTimeZone = profile default_timezone, both offsets evaluated
     * at the same instant like the reference does).
     *
     * @param epochSeconds the raw start/stop_timestamp
     * @param cookieTz the timezone the cookie carried (device default)
     * @param portalTz the portal's profile default_timezone (GMT when absent)
     */
    fun normalizeTimestamp(
        epochSeconds: Long,
        cookieTz: TimeZone = TimeZone.getDefault(),
        portalTz: TimeZone = TimeZone.getTimeZone("GMT")
    ): Long {
        val ms = epochSeconds * 1000L
        return ms - cookieTz.getOffset(ms) + portalTz.getOffset(ms)
    }

    /** One entry → EpgProgram (null when unusable). v1.12.0: carries the
     *  catch-up file id + mark_archive when the row provides them. */
    private fun toProgram(
        entry: StalkerEpgEntry,
        cookieTz: TimeZone,
        portalTz: TimeZone
    ): EpgProgram? {
        val start = entry.startSeconds ?: return null
        val stop = entry.stopSeconds ?: return null
        val title = entry.name?.trim().orEmpty()
        if (title.isEmpty() || stop <= start) return null
        val desc = entry.descr?.trim()?.takeIf { it.isNotEmpty() }
        return EpgProgram(
            startMs = normalizeTimestamp(start, cookieTz, portalTz),
            endMs = normalizeTimestamp(stop, cookieTz, portalTz),
            title = title,
            description = desc,
            fileId = entry.fileId,
            markArchive = entry.markArchiveFlag == 1
        )
    }

    /**
     * Bulk get_epg_info body → ch_id → sorted programs.
     * Tolerates every malformation (missing js, missing data, junk rows).
     */
    fun parseBulk(
        body: String?,
        cookieTz: TimeZone = TimeZone.getDefault(),
        portalTz: TimeZone = TimeZone.getTimeZone("GMT")
    ): Map<Long, List<EpgProgram>> {
        val json = body?.trim()?.takeIf { it.startsWith("{") } ?: return emptyMap()
        return try {
            val data = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }.decodeFromString<BulkEpgEnvelope>(json).js?.data ?: return emptyMap()
            val out = HashMap<Long, List<EpgProgram>>(data.size)
            for ((chId, entries) in data) {
                val id = chId.toLongOrNull() ?: continue
                val programs = entries.mapNotNull { toProgram(it, cookieTz, portalTz) }
                    .sortedBy { it.startMs }
                if (programs.isNotEmpty()) out[id] = programs
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /**
     * One get_simple_data_table page body → (programs of this page,
     * total_items the portal reports).
     */
    fun parseTablePage(
        body: String?,
        cookieTz: TimeZone = TimeZone.getDefault(),
        portalTz: TimeZone = TimeZone.getTimeZone("GMT")
    ): Pair<List<EpgProgram>, Int> {
        val json = body?.trim()?.takeIf { it.startsWith("{") } ?: return emptyList<EpgProgram>() to 0
        return try {
            val js = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }.decodeFromString<TableEpgEnvelope>(json).js
            val programs = js?.data.orEmpty().mapNotNull { toProgram(it, cookieTz, portalTz) }
                .sortedBy { it.startMs }
            programs to (js?.totalItems ?: programs.size)
        } catch (_: Throwable) {
            emptyList<EpgProgram>() to 0
        }
    }

    /**
     * The reference's page-count math (StalkerCatchUpDetailFragment):
     * 10 rows per page, `total/10` when divisible, `total/10 + 1` otherwise.
     */
    fun pagesFor(totalItems: Int): Int = when {
        totalItems <= 0 -> 0
        totalItems % 10 == 0 -> totalItems / 10
        else -> totalItems / 10 + 1
    }

    /** Today's mysql date ("yyyy-MM-dd") in the device timezone. */
    fun todayMysql(nowMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(nowMs))
    }

    /**
     * v1.12.0 — the Catch-Up / Timeline day tabs, EXACTLY the reference's
     * CatchUpDetailActivity.setUpViewPager: today, yesterday, the day before
     * (oldest first, the LAST tab selected). Each tab = (mysql date,
     * "dd MMM yyyy" label).
     */
    fun catchUpDays(nowMs: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val label = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        label.timeZone = TimeZone.getDefault()
        return (2L downTo 0L).map { back ->
            val at = nowMs - back * 86_400_000L
            fmt.format(Date(at)) to label.format(Date(at))
        }
    }
}

/**
 * In-memory stalker EPG store over the shared OkHttp client + the playback
 * handshake cache (StalkerPlayback.session — no second handshake while a
 * stream is live). Singleton, exactly like EpgRepository.
 */
class StalkerEpgRepository private constructor(private val app: IPTVApp) {

    companion object {
        @Volatile
        private var instance: StalkerEpgRepository? = null

        fun get(app: IPTVApp): StalkerEpgRepository =
            instance ?: synchronized(this) {
                instance ?: StalkerEpgRepository(app).also { instance = it }
            }

        /** Bulk rows cache — the reference's STALKER_EPG_DATE_PERIOD (5 h). */
        internal const val BULK_TTL_MS = 18000 * 1000L

        /** Day-table cache — cheap responses, keep a short window. */
        internal const val TABLE_TTL_MS = 10 * 60 * 1000L

        /** Never follow more pages than this (48 items = 5 pages typical). */
        private const val MAX_PAGES = 30
    }

    private class BulkEntry(val at: Long, val programs: Map<Long, List<EpgProgram>>)

    private class TableEntry(val at: Long, val programs: List<EpgProgram>)

    private val bulkCache = HashMap<String, BulkEntry>()

    private val tableCache = HashMap<String, TableEntry>()

    /** Per-playlist portal default_timezone (get_profile) — stable, cached forever per process. */
    private val portalTzCache = HashMap<String, TimeZone>()

    /**
     * The portal's default_timezone (Europe/Amsterdam on mag.max-cdn.com) —
     * the zone the EPG timestamps are shifted against (see the parser's
     * KDoc). GMT when the profile fails or the field is absent, exactly
     * like the reference's TimeZone.getTimeZone("") fallback.
     */
    private suspend fun portalTz(playlist: Playlist, session: StalkerSession): TimeZone {
        val key = cacheKey(playlist) ?: return TimeZone.getTimeZone("GMT")
        synchronized(this) {
            portalTzCache[key]?.let { return it }
        }
        val tz = try {
            val name = StalkerClient(app.okHttp).profile(session)
                ?.default_timezone?.takeIf { it.isNotBlank() }
            TimeZone.getTimeZone(name ?: "GMT")
        } catch (_: Throwable) {
            TimeZone.getTimeZone("GMT")
        }
        synchronized(this) {
            portalTzCache[key] = tz
        }
        return tz
    }

    /**
     * The bulk channel→programs map (rows' "now" lines). Empty map ⇒ portal
     * has no EPG at all / network failed — rows simply keep no "now" line.
     */
    suspend fun bulkEpg(playlist: Playlist, nowMs: Long = System.currentTimeMillis()): Map<Long, List<EpgProgram>> {
        if (playlist.type != "PORTAL") return emptyMap()
        val key = cacheKey(playlist) ?: return emptyMap()
        synchronized(this) {
            bulkCache[key]?.let { if (nowMs - it.at < BULK_TTL_MS) return it.programs }
        }
        val fetched = withContext(Dispatchers.IO) {
            try {
                // v1.12.5 — renewed on 401/403 (the token can die mid-session:
                // the reference app handshaking the same MAC kills ours).
                StalkerPlayback.authed(app, playlist) { session ->
                    val portalTz = portalTz(playlist, session)
                    val client = StalkerClient(app.okHttp)
                    val body = client.rawGet(session, StalkerUrls.epgInfoUrl(session.base, session.html))
                    StalkerEpgParser.parseBulk(body, TimeZone.getDefault(), portalTz)
                }
            } catch (_: Throwable) {
                emptyMap()
            }
        }
        synchronized(this) {
            bulkCache[key] = BulkEntry(nowMs, fetched)
        }
        return fetched
    }

    /**
     * The selected channel's day table (the right panel). All pages are
     * fetched (up to [MAX_PAGES]) so the panel can show current + upcoming
     * exactly like the XC panel. Empty list ⇒ no EPG for the channel (the
     * UI shows its graceful "unavailable" state).
     */
    suspend fun dayTable(
        playlist: Playlist,
        chId: Long,
        date: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<EpgProgram> {
        if (playlist.type != "PORTAL") return emptyList()
        val key = cacheKey(playlist) ?: return emptyList()
        val cacheKey = "$key|$chId|$date"
        synchronized(this) {
            tableCache[cacheKey]?.let { if (nowMs - it.at < TABLE_TTL_MS) return it.programs }
        }
        val fetched = withContext(Dispatchers.IO) {
            try {
                // v1.12.5 — renewed on 401/403: a dead token previously
                // answered every Catch-Up day table with an EMPTY list —
                // the root cause of the persistent "No Catch Up Found".
                StalkerPlayback.authed(app, playlist) { session ->
                    val portalTz = portalTz(playlist, session)
                    val client = StalkerClient(app.okHttp)
                    val cookieTz = TimeZone.getDefault()
                    val all = ArrayList<EpgProgram>()
                    var page = 1
                    var total = -1
                    while (page <= MAX_PAGES) {
                        val body = client.rawGet(
                            session,
                            StalkerUrls.simpleDataTableUrl(session.base, session.html, chId, date, page)
                        )
                        val (programs, totalItems) = StalkerEpgParser.parseTablePage(body, cookieTz, portalTz)
                        if (programs.isEmpty()) break
                        all += programs
                        total = if (totalItems > 0) totalItems else all.size
                        val pages = StalkerEpgParser.pagesFor(total)
                        if (page >= pages) break
                        page++
                    }
                    all.sortedBy { it.startMs }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
        synchronized(this) {
            tableCache[cacheKey] = TableEntry(nowMs, fetched)
        }
        return fetched
    }

    private fun cacheKey(playlist: Playlist): String? {
        val base = playlist.server?.trim()?.trimEnd('/') ?: return null
        val mac = playlist.username ?: return null
        return "$base|$mac"
    }
}

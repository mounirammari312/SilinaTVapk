package com.superz.iptvplayer.data.epg

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.12.1 — Xtream Catch-Up, replicated 1:1 from the reference's
 * XCCatchUpDetailActivity + CatchUpDetailFragment + CatchUpPlayActivity +
 * CatchUpEpg model:
 *
 *  • EPG source: GET /player_api.php?action=get_simple_data_table
 *    &username&password&stream_id (APIService.get_full_epg) — the WHOLE
 *    table arrives at once; the reference splits it locally into the three
 *    day tabs (getCatchupModels: today / yesterday(-1) / yesterday(-2)),
 *    and only adds tabs that actually have programs (setUpViewPager).
 *  • Rows: EVERY row is clickable (CatchDetailRecyclerAdapter sets a
 *    click listener unconditionally); the clock icon shows when
 *    has_archive == 1.
 *  • Playback URL — CatchUpEpg.getUrl, VERBATIM:
 *
 *      host + "/timeshift/" + username + "/" + password + "/59/"
 *            + startForUrl + "/" + stream_id + ".ts"
 *
 *    where startForUrl = Function.formateDateFromstring(
 *      "yyyy-MM-dd HH:mm:ss" → "yyyy-MM-dd:HH-mm", start) — the panel's own
 *    `start` string passed back re-formatted. The hardcoded 59 (minutes)
 *    and the .ts extension are the reference's exact constants.
 */
object XcCatchUp {

    /** The reference's input pattern (getStart's "yyyy-MM-dd HH:mm:ss"). */
    private const val START_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** The reference's output pattern (getStartForUrl). */
    private const val URL_PATTERN = "yyyy-MM-dd:HH-mm"

    /**
     * Function.formateDateFromstring("yyyy-MM-dd HH:mm:ss",
     * "yyyy-MM-dd:HH-mm", start) — empty string on a parse failure,
     * exactly like the reference's catch (returns "").
     */
    fun startForUrl(startRaw: String?): String {
        if (startRaw.isNullOrBlank()) return ""
        return try {
            val inFmt = SimpleDateFormat(START_PATTERN, Locale.US)
            val outFmt = SimpleDateFormat(URL_PATTERN, Locale.US)
            outFmt.format(inFmt.parse(startRaw.trim()) ?: return "")
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Fallback startForUrl when the raw string is missing: rebuild it from
     * the epoch (the start string was parsed as UTC, so formatting in UTC
     * reproduces the panel's original string).
     */
    fun startForUrlFromEpoch(startMs: Long): String {
        if (startMs <= 0L) return ""
        val fmt = SimpleDateFormat(URL_PATTERN, Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(startMs))
    }

    /**
     * CatchUpEpg.getUrl — VERBATIM:
     * `{host}/timeshift/{username}/{password}/59/{startForUrl}/{streamId}.ts`
     *
     * The host is the playlist's normalized base (the reference stores the
     * bare host; our playlists may carry /player_api.php, stripped here).
     */
    fun timeshiftUrl(
        server: String?,
        username: String?,
        password: String?,
        streamId: Long,
        startForUrl: String
    ): String {
        val host = com.superz.iptvplayer.data.xtream.XtreamClient
            .normalizeBaseUrl(server ?: "")
        val u = username ?: ""
        val p = password ?: ""
        return "$host/timeshift/$u/$p/59/$startForUrl/$streamId.ts"
    }

    /** The three day-tab keys (device tz), oldest first — the reference's
     *  checkIsToday / checkIsYesterday(-1) / checkIsYesterday(-2) window. */
    fun dayKeys(nowMs: Long = System.currentTimeMillis()): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return (2L downTo 0L).map { back -> fmt.format(Date(nowMs - back * 86_400_000L)) }
    }

    /** The day key of a program (device tz) — the bucket selector. */
    fun dayKeyOf(startMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(startMs))
    }

    /**
     * getCatchupModels — the whole table split into the three day buckets
     * (keyed "yyyy-MM-dd", device tz). Programs outside the window are
     * dropped, exactly like the reference (rows land in no tab).
     */
    fun dayBuckets(
        programs: List<EpgProgram>,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, List<EpgProgram>> {
        val window = dayKeys(nowMs).toSet()
        return programs
            .filter { dayKeyOf(it.startMs) in window }
            .groupBy { dayKeyOf(it.startMs) }
    }

    /** "dd MMM yyyy" tab label for a day key (device locale). */
    fun dayLabel(dayKey: String): String {
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val date = try { inFmt.parse(dayKey) } catch (_: Throwable) { null }
        return date?.let { outFmt.format(it) } ?: dayKey
    }
}

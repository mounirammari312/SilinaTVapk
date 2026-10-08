package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.ui.channelview.epgLookupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * v1.7.0 — the stalker EPG engine's parsing + normalization contract,
 * locked against LIVE responses captured from mag.max-cdn.com
 * (00:1A:79:B6:39:9A, Task 34):
 *
 *  • the bulk get_epg_info map shape {"js":{"data":{"<ch_id>":[…]}}};
 *  • the get_simple_data_table page shape {"js":{"data":[…],"total_items":N}};
 *  • the timezone formula PROVEN by a controlled triple experiment (cookie
 *    timezone = UTC / Europe/London / Asia/Tokyo, same program
 *    "Black and Blue" on ch 36058, date 2026-10-03):
 *        UTC:    ts=1790972400 (20:20Z)  t_time=22:20
 *        London: ts=1790976000 (21:20Z)  t_time=23:20
 *        Tokyo:  ts=1791004800 (05:20Z)  t_time=07:20(+1d)
 *    All three describe the SAME real instant 22:20Z, i.e.
 *        R = ts − offset(cookieTz) + offset(portalTz)
 *    with the portal's get_profile default_timezone = Europe/Amsterdam
 *    (CEST = +2 in October). That is exactly the reference's
 *    Function.getDateCurrentTimeZone(fromTimeZone = profile zone)
 *    inversion — replicated here verbatim;
 *  • the reference's page-count math (10 rows per page);
 *  • the EPG lookup id (stream_id for XC, "k:<id>" key for stalker).
 */
class StalkerEpgTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val london = TimeZone.getTimeZone("Europe/London")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val amsterdam = TimeZone.getTimeZone("Europe/Amsterdam") // the live portal's zone

    private fun hourMinuteUtc(ms: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = ms
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    // ── normalizeTimestamp (the reference's getDateCurrentTimeZone) ──

    @Test
    fun `normalize restores the same real instant across cookie timezones`() {
        // The live triple: three different raw timestamps, ONE real program.
        val rUtc = StalkerEpgParser.normalizeTimestamp(1790972400L, utc, amsterdam)
        val rLondon = StalkerEpgParser.normalizeTimestamp(1790976000L, london, amsterdam)
        val rTokyo = StalkerEpgParser.normalizeTimestamp(1791004800L, tokyo, amsterdam)
        assertEquals(rLondon, rUtc)
        assertEquals(rTokyo, rUtc)
        assertEquals(22 to 20, hourMinuteUtc(rUtc))
    }

    @Test
    fun `normalize shifts by the portal-minus-cookie offset delta`() {
        // London (+1) vs Amsterdam (+2): R = ts − 1h + 2h = ts + 1h.
        val r = StalkerEpgParser.normalizeTimestamp(1791035100L, london, amsterdam)
        assertEquals(14 to 45, hourMinuteUtc(r))
        // UTC (0) vs Amsterdam (+2): R = ts + 2h.
        val rUtc = StalkerEpgParser.normalizeTimestamp(1791035100L, utc, amsterdam)
        assertEquals(15 to 45, hourMinuteUtc(rUtc))
    }

    @Test
    fun `normalize is identity when cookie zone equals portal zone`() {
        assertEquals(
            1791035100000L,
            StalkerEpgParser.normalizeTimestamp(1791035100L, london, london)
        )
        assertEquals(
            1791035100000L,
            StalkerEpgParser.normalizeTimestamp(1791035100L, utc, TimeZone.getTimeZone("GMT"))
        )
    }

    // ── parseBulk ──────────────────────────────────────────────────

    @Test
    fun `bulk parse reads the live mag response shape`() {
        val body = """
            {"js":{"data":{"36058":[
              {"id":"56168625","ch_id":"36058","time":"2026-10-03 15:45:00",
               "time_to":"2026-10-03 15:55:00","duration":-600,"name":"ZIB Flash",
               "descr":"","real_id":"36058_1791038700",
               "start_timestamp":1791035100,"stop_timestamp":1791035700,
               "t_time":"15:45","t_time_to":"15:55"},
              {"id":"56168626","ch_id":"36058","time":"2026-10-03 15:55:00",
               "time_to":"2026-10-03 16:45:00","name":"Vier Frauen und ein Todesfall",
               "descr":"Krimiserie",
               "start_timestamp":1791035700,"stop_timestamp":1791038700,
               "t_time":"15:55","t_time_to":"16:45"}
            ],"36059":[
              {"id":"56169000","ch_id":"36059","name":"Late News",
               "start_timestamp":1791040000,"stop_timestamp":1791043000}
            ]}}}
        """.trimIndent()
        val map = StalkerEpgParser.parseBulk(body, london, amsterdam)
        assertEquals(2, map.size)
        val ch = map[36058L]!!
        assertEquals(2, ch.size)
        assertEquals("ZIB Flash", ch[0].title)
        // R = ts − offset(London) + offset(Amsterdam) = ts + 3600_000
        assertEquals(1791035100000L - 3600000L + 7200000L, ch[0].startMs)
        assertEquals(1791035700000L - 3600000L + 7200000L, ch[0].endMs)
        // sorted by start; description kept when present, null when blank
        assertNull(ch[0].description)
        assertEquals("Krimiserie", ch[1].description)
        assertEquals("Late News", map[36059L]!![0].title)
    }

    @Test
    fun `bulk parse drops junk rows and non-numeric keys`() {
        val body = """
            {"js":{"data":{
              "36058":[{"name":"Ok","start_timestamp":100,"stop_timestamp":200}],
              "abc":[{"name":"Bad key","start_timestamp":100,"stop_timestamp":200}],
              "36060":[{"name":"No times"}],
              "36061":[{"name":"Inverted","start_timestamp":300,"stop_timestamp":200}]
            }}}
        """.trimIndent()
        val map = StalkerEpgParser.parseBulk(body, london, amsterdam)
        assertEquals(setOf(36058L), map.keys)
    }

    @Test
    fun `bulk parse survives empty and malformed bodies`() {
        assertTrue(StalkerEpgParser.parseBulk(null, london, amsterdam).isEmpty())
        assertTrue(StalkerEpgParser.parseBulk("", london, amsterdam).isEmpty())
        assertTrue(StalkerEpgParser.parseBulk("<html>error</html>", london, amsterdam).isEmpty())
        assertTrue(StalkerEpgParser.parseBulk("{}", london, amsterdam).isEmpty())
        assertTrue(StalkerEpgParser.parseBulk("""{"js":null}""", london, amsterdam).isEmpty())
    }

    // ── parseTablePage (get_simple_data_table) ────────────────────

    @Test
    fun `table page parse reads the live response shape`() {
        val body = """
            {"js":{"cur_page":0,"total_items":48,"data":[
              {"id":"56168594_36058","ch_id":"36058","t_time":"23:20","t_time_to":"01:00",
               "name":"Black and Blue","descr":"","real_id":"56168594",
               "start_timestamp":1790976000,"stop_timestamp":1790982000}
            ]}}
        """.trimIndent()
        val (programs, total) = StalkerEpgParser.parseTablePage(body, london, amsterdam)
        assertEquals(48, total)
        assertEquals(1, programs.size)
        assertEquals("Black and Blue", programs[0].title)
        assertEquals(1790976000000L - 3600000L + 7200000L, programs[0].startMs)
    }

    @Test
    fun `table page parse survives malformed bodies`() {
        assertEquals(emptyList<com.superz.iptvplayer.data.epg.EpgProgram>() to 0,
            StalkerEpgParser.parseTablePage(null, london, amsterdam))
        assertEquals(0, StalkerEpgParser.parseTablePage("""{"js":{}}""", london, amsterdam).second)
    }

    // ── pagination math (the reference's CatchUp loop) ─────────────

    @Test
    fun `pagesFor matches the reference CatchUp pagination`() {
        // total % 10 == 0 → total/10, else total/10 + 1
        assertEquals(0, StalkerEpgParser.pagesFor(0))
        assertEquals(1, StalkerEpgParser.pagesFor(10))
        assertEquals(5, StalkerEpgParser.pagesFor(48))
        assertEquals(5, StalkerEpgParser.pagesFor(50))
        assertEquals(6, StalkerEpgParser.pagesFor(51))
    }

    // ── todayMysql ─────────────────────────────────────────────────

    @Test
    fun `today mysql is yyyy-MM-dd in device timezone`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.OCTOBER, 3, 23, 30, 0)
        val label = StalkerEpgParser.todayMysql(cal.timeInMillis)
        assertTrue(label, Regex("\\d{4}-\\d{2}-\\d{2}").matches(label))
    }

    // ── the EPG lookup id (rows + panel) ──────────────────────────

    private fun channel(key: String, streamId: Long? = null) = Channel(
        playlistId = 1L, key = key, num = 1, name = "Ch", streamId = streamId
    )

    @Test
    fun `epg lookup id prefers stream id`() {
        assertEquals(77L, channel(key = "x77", streamId = 77L).epgLookupId())
    }

    @Test
    fun `epg lookup id falls back to the stalker k key`() {
        assertEquals(36058L, channel(key = "k:36058").epgLookupId())
    }

    @Test
    fun `epg lookup id is null for non-numeric stalker keys`() {
        assertNull(channel(key = "k:abc").epgLookupId())
        assertNull(channel(key = "m3u:http://x").epgLookupId())
        assertNull(channel(key = "k:").epgLookupId())
    }
}

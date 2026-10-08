package com.superz.iptvplayer.data.epg

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.12.1 — XC Catch-Up tests, all expectations VERBATIM from the reference
 * (CatchUpEpg.getUrl + Function.formateDateFromstring +
 * XCCatchUpDetailActivity.getCatchupModels + CatchDetailRecyclerAdapter).
 */
class XcCatchUpTest {

    // ── startForUrl (CatchUpEpg.getStartForUrl) ────────────────

    @Test
    fun `startForUrl reformat is verbatim`() {
        // "yyyy-MM-dd HH:mm:ss" → "yyyy-MM-dd:HH-mm"
        assertEquals("2026-10-04:21-30", XcCatchUp.startForUrl("2026-10-04 21:30:00"))
        assertEquals("2025-01-02:03-04", XcCatchUp.startForUrl("2025-01-02 03:04:05"))
        assertEquals("2026-10-04:00-00", XcCatchUp.startForUrl(" 2026-10-04 00:00:00 "))
    }

    @Test
    fun `startForUrl failures yield empty string like the reference`() {
        assertEquals("", XcCatchUp.startForUrl(null))
        assertEquals("", XcCatchUp.startForUrl(""))
        assertEquals("", XcCatchUp.startForUrl("garbage"))
    }

    @Test
    fun `startForUrlFromEpoch reproduces the panel string in UTC`() {
        val epoch = EpgParser.parseTime("2026-10-04 21:30:00")
        assertEquals("2026-10-04:21-30", XcCatchUp.startForUrlFromEpoch(epoch))
        assertEquals("", XcCatchUp.startForUrlFromEpoch(0L))
    }

    // ── timeshiftUrl (CatchUpEpg.getUrl) ──────────────────────

    @Test
    fun `timeshift url formula is verbatim`() {
        // host + "/timeshift/" + user + "/" + pass + "/59/" + startForUrl + "/" + id + ".ts"
        assertEquals(
            "http://panel.example:8080/timeshift/myuser/mypass/59/2026-10-04:21-30/1824.ts",
            XcCatchUp.timeshiftUrl(
                "http://panel.example:8080", "myuser", "mypass",
                1824L, "2026-10-04:21-30"
            )
        )
    }

    @Test
    fun `timeshift url keeps the hardcoded 59 minutes and ts extension`() {
        val url = XcCatchUp.timeshiftUrl("http://h", "u", "p", 42L, "2026-01-01:09-15")
        assertTrue(url.contains("/59/"))
        assertTrue(url.endsWith("/42.ts"))
        assertFalse(url.contains(".m3u8"))
    }

    @Test
    fun `timeshift url strips player_api php from the host`() {
        assertEquals(
            "http://panel.example/timeshift/u/p/59/2026-10-04:21-30/7.ts",
            XcCatchUp.timeshiftUrl(
                "http://panel.example/player_api.php", "u", "p",
                7L, "2026-10-04:21-30"
            )
        )
    }

    // ── day window (getCatchupModels) ──────────────────────────

    @Test
    fun `day keys are three and oldest first`() {
        val keys = XcCatchUp.dayKeys()
        assertEquals(3, keys.size)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        assertEquals(fmt.format(Date()), keys.last())          // today = LAST
        // consecutive days
        for (i in 1 until keys.size) {
            val a = fmt.parse(keys[i - 1])!!.time
            val b = fmt.parse(keys[i])!!.time
            assertTrue(b - a in 86_400_000L..2 * 86_400_000L)
        }
    }

    @Test
    fun `day buckets split the window and drop the rest`() {
        val nowMs = System.currentTimeMillis()
        fun at(dayOffset: Long, hour: Int): Long {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Date(nowMs - dayOffset * 86_400_000L))
            return fmt.parse("$day ${"%02d".format(hour)}:00:00")!!.time
        }
        val programs = listOf(
            EpgProgram(at(2, 10), at(2, 11), "TwoDaysAgo"),
            EpgProgram(at(1, 10), at(1, 11), "Yesterday"),
            EpgProgram(at(0, 8), at(0, 9), "Today1"),
            EpgProgram(at(0, 10), at(0, 11), "Today2"),
            EpgProgram(at(9, 10), at(9, 11), "WayOld")        // outside → dropped
        )
        val buckets = XcCatchUp.dayBuckets(programs, nowMs)
        val keys = XcCatchUp.dayKeys(nowMs)
        assertEquals(setOf("Yesterday"), buckets[keys[1]]!!.map { it.title }.toSet())
        assertEquals(setOf("Today1", "Today2"), buckets[keys[2]]!!.map { it.title }.toSet())
        assertEquals(1, buckets[keys[0]]!!.size)               // -2d bucket exists
        assertTrue(buckets.values.flatten().none { it.title == "WayOld" })
    }

    @Test
    fun `day label uses the dd MMM yyyy pattern`() {
        val label = XcCatchUp.dayLabel("2026-10-04")
        assertTrue(
            "label was: $label",
            Regex("^\\d{2} [^ ]+ \\d{4}$").matches(label)
        )
    }

    // ── EPG listing decode (CatchUpEpg fields) ────────────────

    @Test
    fun `full table keeps id has_archive and the raw start`() {
        val json = """{"epg_listings":[
            {"id":"55164434","channel_id":"1824",
             "start":"2026-10-04 21:00:00","end":"2026-10-04 22:00:00",
             "title":"${b64("Movie Night")}",
             "description":"${b64("Desc")}",
             "has_archive":1,"now_playing":1}
        ]}"""
        val programs = EpgParser.parseFullResponse(json)
        assertEquals(1, programs.size)
        val p = programs.first()
        assertEquals("Movie Night", p.title)
        assertEquals("55164434", p.fileId)
        assertTrue(p.markArchive)
        assertEquals("2026-10-04 21:00:00", p.startRaw)
        // the URL source round-trips through the verbatim reformat
        assertEquals("2026-10-04:21-00", XcCatchUp.startForUrl(p.startRaw))
    }

    @Test
    fun `has_archive zero means no clock icon`() {
        val json = """{"epg_listings":[
            {"id":"1","start":"2026-10-04 21:00:00","end":"2026-10-04 22:00:00",
             "title":"${b64("Upcoming")}","has_archive":0}
        ]}"""
        val p = EpgParser.parseFullResponse(json).first()
        assertFalse(p.markArchive)
        assertFalse(p.catchUpPlayable)
    }

    @Test
    fun `listings without id keep the row clickable-neutral`() {
        val json = """{"epg_listings":[
            {"start":"2026-10-04 21:00:00","end":"2026-10-04 22:00:00",
             "title":"${b64("Plain")}"}
        ]}"""
        val p = EpgParser.parseFullResponse(json).first()
        assertNull(p.fileId)
        assertFalse(p.markArchive)
    }

    // ── XC stream decode (tv_archive → the Catch-Up source) ───

    @Test
    fun `live stream rows decode the tv_archive flag`() {
        val json = """[
            {"num":1,"name":"Archived","stream_id":100,"tv_archive":1},
            {"num":2,"name":"Plain","stream_id":101,"tv_archive":0},
            {"num":3,"name":"Absent","stream_id":102}
        ]"""
        val rows = Json { ignoreUnknownKeys = true }.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(
                com.superz.iptvplayer.data.xtream.XtreamStream.serializer()
            ), json
        )
        // syncXtream's mapping: tvArchive = tv_archive?.takeIf { it == 1 }
        assertEquals(1, rows[0].tvArchive)
        assertEquals(0, rows[1].tvArchive)
        assertNull(rows[2].tvArchive)
        assertEquals(1, rows[0].tvArchive?.takeIf { it == 1 })
        assertEquals(null, rows[1].tvArchive?.takeIf { it == 1 })
        assertEquals(null, rows[2].tvArchive?.takeIf { it == 1 })
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray())
}

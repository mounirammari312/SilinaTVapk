package com.superz.iptvplayer.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * EpgParser unit tests — Base64 title decoding, UTC time parsing, and the
 * tolerant handling of every get_short_epgs / get_simple_data_table
 * response shape seen in the wild (plus garbage inputs).
 *
 * Runs on the plain JVM (java.util.Base64 works identically here).
 */
class EpgParserTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // ── decodeBase64Text ───────────────────────────────────────

    @Test
    fun `decodes base64 utf8 title`() {
        assertEquals("Football: Algeria vs Morocco", EpgParser.decodeBase64Text(b64("Football: Algeria vs Morocco")))
    }

    @Test
    fun `decodes base64 arabic title`() {
        val arabic = "مباراة الوداد البيضاوي"
        assertEquals(arabic, EpgParser.decodeBase64Text(b64(arabic)))
    }

    @Test
    fun `plain text falls through unchanged`() {
        // Not valid Base64 → returned as-is (some panels send plain text).
        assertEquals("Not Base64 !!", EpgParser.decodeBase64Text("Not Base64 !!"))
    }

    @Test
    fun `null and blank decode to empty`() {
        assertEquals("", EpgParser.decodeBase64Text(null))
        assertEquals("", EpgParser.decodeBase64Text(""))
        assertEquals("", EpgParser.decodeBase64Text("   "))
    }

    // ── parseTime ──────────────────────────────────────────────

    @Test
    fun `parses utc timestamp`() {
        val viaFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").let {
            it.timeZone = TimeZone.getTimeZone("UTC")
            it.parse("2026-10-03 21:00:00")!!.time
        }
        assertEquals(viaFormatter, EpgParser.parseTime("2026-10-03 21:00:00"))
    }

    @Test
    fun `bad time strings yield zero`() {
        assertEquals(0L, EpgParser.parseTime(null))
        assertEquals(0L, EpgParser.parseTime(""))
        assertEquals(0L, EpgParser.parseTime("not-a-time"))
        assertEquals(0L, EpgParser.parseTime("2026-10-03"))
    }

    // ── parseFullResponse ──────────────────────────────────────

    @Test
    fun `parses standard full table`() {
        val json = """
            {"epg_listings":[
              {"epg_id":"1","channel_id":"1824",
               "start":"2026-10-03 21:00:00","end":"2026-10-03 22:30:00",
               "title":"${b64("Kabyle News Night")}",
               "description":"${b64("Full bulletin")}"},
              {"epg_id":"2","channel_id":"1824",
               "start":"2026-10-03 22:30:00","end":"2026-10-03 23:15:00",
               "title":"${b64("Late Movie")}"}
            ]}
        """.trimIndent()
        val programs = EpgParser.parseFullResponse(json)
        assertEquals(2, programs.size)
        assertEquals("Kabyle News Night", programs[0].title)
        assertEquals("Late Movie", programs[1].title)
        assertEquals("Full bulletin", programs[0].description)
        assertTrue(programs[0].startMs < programs[1].startMs)
    }

    @Test
    fun `parses nested epg_data full table`() {
        val json = """{"epg_data":{"epg_listings":[
            {"channel_id":"5","start":"2026-10-03 20:00:00","end":"2026-10-03 21:00:00","title":"${b64("Show")}"}
        ]}}"""
        assertEquals(1, EpgParser.parseFullResponse(json).size)
    }

    @Test
    fun `skips broken listings`() {
        // v1.12.5 — reference-verbatim: a row with an unusable START is
        // dropped (no bucket possible), but a reversed/absent END now
        // SURVIVES with a 30-minute clamp (the reference's gson never drops
        // rows — the old drop erased whole Catch-Up days on panels that
        // send start_timestamp without stop/end).
        val json = """{"epg_listings":[
            {"channel_id":"5","start":"garbage","end":"2026-10-03 21:00:00","title":"${b64("A")}"},
            {"channel_id":"5","start":"2026-10-03 21:00:00","end":"2026-10-03 20:00:00","title":"${b64("B")}"},
            {"channel_id":"5","start":"2026-10-03 21:00:00","end":"2026-10-03 22:00:00","title":""},
            {"channel_id":"5","start":"2026-10-03 21:00:00","end":"2026-10-03 22:00:00","title":"${b64("OK")}"}
        ]}"""
        val programs = EpgParser.parseFullResponse(json)
        assertEquals(2, programs.size)
        // "B" survives with the clamp: end = start + 30 min.
        assertEquals("B", programs[0].title)
        assertEquals(programs[0].startMs + 30 * 60 * 1000L, programs[0].endMs)
        assertEquals("OK", programs[1].title)
    }

    @Test
    fun `garbage bodies yield empty`() {
        assertTrue(EpgParser.parseFullResponse(null).isEmpty())
        assertTrue(EpgParser.parseFullResponse("").isEmpty())
        assertTrue(EpgParser.parseFullResponse("<html>404</html>").isEmpty())
        assertTrue(EpgParser.parseFullResponse("{broken json").isEmpty())
        assertTrue(EpgParser.parseFullResponse("[]").isEmpty())
    }

    // ── parseShortResponse ─────────────────────────────────────

    @Test
    fun `parses map form with per-stream objects`() {
        val json = """{"epg_listings_map":{
            "1824":{"epg_listings_count":1,"epg_listings":[
                {"channel_id":"1824","start":"2026-10-03 21:00:00","end":"2026-10-03 22:00:00","title":"${b64("Ch One Now")}"}
            ]},
            "1825":{"epg_listings":[
                {"channel_id":"1825","start":"2026-10-03 21:30:00","end":"2026-10-03 22:30:00","title":"${b64("Ch Two Now")}"}
            ]}
        }}"""
        val map = EpgParser.parseShortResponse(json)
        assertEquals(2, map.size)
        assertEquals("Ch One Now", map[1824L]!![0].title)
        assertEquals("Ch Two Now", map[1825L]!![0].title)
    }

    @Test
    fun `parses map form with bare arrays`() {
        val json = """{"epg_listings_map":{
            "7":[{"channel_id":"7","start":"2026-10-03 21:00:00","end":"2026-10-03 22:00:00","title":"${b64("Bare")}" }]
        }}"""
        val map = EpgParser.parseShortResponse(json)
        assertEquals(1, map.size)
        assertEquals("Bare", map[7L]!![0].title)
    }

    @Test
    fun `parses flat single-stream form`() {
        val json = """{"epg_listings":[
            {"channel_id":"99","start":"2026-10-03 21:00:00","end":"2026-10-03 22:00:00","title":"${b64("Flat")}"}
        ]}"""
        val map = EpgParser.parseShortResponse(json)
        assertEquals("Flat", map[99L]!![0].title)
    }

    @Test
    fun `short garbage yields empty map`() {
        assertTrue(EpgParser.parseShortResponse(null).isEmpty())
        assertTrue(EpgParser.parseShortResponse("not json").isEmpty())
        assertTrue(EpgParser.parseShortResponse("[]").isEmpty())
    }

    // ── currentProgram ─────────────────────────────────────────

    @Test
    fun `current program selection`() {
        val base = EpgParser.parseTime("2026-10-03 21:00:00")
        val programs = listOf(
            EpgProgram(base - 3_600_000, base, "Before"),
            EpgProgram(base, base + 1_800_000, "Now"),          // 21:00 → 21:30
            EpgProgram(base + 1_800_000, base + 3_600_000, "After")
        )
        assertNotNull(EpgParser.currentProgram(programs, base + 60_000))
        assertEquals("Now", EpgParser.currentProgram(programs, base + 60_000)!!.title)
        assertNull(EpgParser.currentProgram(programs, base + 10 * 3_600_000))
    }
}

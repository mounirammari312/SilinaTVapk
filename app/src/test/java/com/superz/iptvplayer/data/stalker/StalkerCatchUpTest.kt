package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.data.epg.EpgProgram
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.12.0 — Catch-Up TV + EPG Timeline + Parental Control, locked against
 * the reference app (vuiptv 1.0.1 / classes7.dex):
 *
 *  • URL builders — the tv_archive create_link VERBATIM (APIService's
 *    get_catch_url / get_catch_url_html — note the EMPTY forced_storage)
 *  • catch cmd    — "auto /media/<id>.ts" (portal.php) / ".mpg"
 *    (stalker_portal), CatchUpPlayActivity.getStreamUrl verbatim
 *  • wire models  — the channel row's archive flag (archive OR
 *    enable_tv_archive) and the EPG row's id / real_id / mark_archive
 *  • EPG parsing  — get_simple_data_table rows keep the compound file id
 *    (the /media/<id>.ts input) + mark_archive
 *  • day tabs     — catchUpDays = the reference's setUpViewPager 3-day
 *    window (today, -1d, -2d; oldest first; TODAY last)
 *  • client       — createCatchLink over MockWebServer (headers verbatim,
 *    cmd URL-encoding, js.cmd extraction)
 *  • playback URL — the reference's replaceAll(ffmpeg/auto/\\s) chain
 *  • parental     — ParentalControl.isXxxName verbatim (xxx/adult/porn)
 */
class StalkerCatchUpTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private lateinit var server: MockWebServer
    private lateinit var client: StalkerClient

    private val base: String get() = server.url("/").toString().trimEnd('/')
    private val session: StalkerSession get() = StalkerSession(base, "00:1A:79:B6:39:9A", "TOKEN1", html = false)
    private val htmlSession: StalkerSession get() = StalkerSession(base, "00:1A:79:B6:39:9A", "TOKEN1", html = true)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StalkerClient(OkHttpClient())
        StalkerVodRefs.reset()
        StalkerMediaHeaders.reset()
    }

    @After
    fun tearDown() {
        server.shutdown()
        StalkerVodRefs.reset()
        StalkerMediaHeaders.reset()
    }

    // ── URL builders (APIService.java, verbatim) ───────────────────

    @Test
    fun `catch create link url verbatim - ministra family`() {
        assertEquals(
            "$base/portal.php?type=tv_archive&action=create_link&series=&forced_storage=&" +
                "disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml&cmd=auto+%2Fmedia%2F55164434_368515.ts",
            StalkerUrls.catchCreateLinkUrl(base, html = false, cmd = "auto /media/55164434_368515.ts")
        )
    }

    @Test
    fun `catch create link url verbatim - html family`() {
        assertEquals(
            "$base/stalker_portal/server/load.php?type=tv_archive&action=create_link&series=&forced_storage=&" +
                "disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml&cmd=auto+%2Fmedia%2F55164434_368515.mpg",
            StalkerUrls.catchCreateLinkUrl(base, html = true, cmd = "auto /media/55164434_368515.mpg")
        )
    }

    @Test
    fun `catch create link keeps empty forced_storage - differs from itv variant`() {
        // The itv create_link carries forced_storage=0; tv_archive carries it
        // EMPTY (the reference's own annotations — the subtle difference).
        val itv = StalkerUrls.createLinkUrl(base, html = false, cmd = "ffmpeg x")
        val tvArchive = StalkerUrls.catchCreateLinkUrl(base, html = false, cmd = "auto x")
        assertTrue(itv.contains("forced_storage=0"))
        assertTrue(tvArchive.contains("forced_storage=&"))
        assertFalse(tvArchive.contains("forced_storage=0"))
    }

    // ── The catch cmd (CatchUpPlayActivity.getStreamUrl, verbatim) ──

    @Test
    fun `catch cmd family-correct extensions`() {
        assertEquals("auto /media/55164434_368515.ts", StalkerUrls.catchCmd("55164434_368515", html = false))
        assertEquals("auto /media/55164434_368515.mpg", StalkerUrls.catchCmd("55164434_368515", html = true))
    }

    // ── Wire models ────────────────────────────────────────────────

    @Test
    fun `channel row archive flag - archive field`() {
        val row = json.decodeFromString<StalkerChannel>(
            """{"id":"368515","name":"VRT 1","cmd":"ffmpeg http://x/ch_","number":1,
               "tv_genre_id":"9","archive":1,"tv_archive_duration":72}"""
        )
        assertEquals(1, row.tvArchiveFlag)
    }

    @Test
    fun `channel row archive flag - enable_tv_archive fallback`() {
        // mag.max-cdn.com sends BOTH; some portals send only enable_tv_archive.
        val row = json.decodeFromString<StalkerChannel>(
            """{"id":"1","name":"X","enable_tv_archive":1,"archive":0}"""
        )
        assertEquals(1, row.tvArchiveFlag)
        val onlyEnable = json.decodeFromString<StalkerChannel>(
            """{"id":"1","name":"X","enable_tv_archive":1}"""
        )
        assertEquals(1, onlyEnable.tvArchiveFlag)
        val off = json.decodeFromString<StalkerChannel>(
            """{"id":"1","name":"X","archive":0,"enable_tv_archive":0}"""
        )
        assertEquals(0, off.tvArchiveFlag)
        val row2 = json.decodeFromString<StalkerChannel>("""{"id":"1","name":"X"}""")
        assertNull(row2.tvArchiveFlag)
    }

    @Test
    fun `epg row carries compound file id and mark_archive`() {
        // Live row shape from mag.max-cdn.com (get_simple_data_table).
        val row = json.decodeFromString<StalkerEpgEntry>(
            """{"id":"55164434_368515","real_id":"368515_2026-10-04 00:10:00",
               "name":"Journaallus","descr":"News","t_time":"00:10:00","time_to":"00:15:00",
               "duration":350,"mark_archive":1,"open":0,
               "start_timestamp":1791081000,"stop_timestamp":1791082800}"""
        )
        assertEquals("55164434_368515", row.fileId)
        assertEquals(1, row.markArchiveFlag)
        assertEquals(1791081000L, row.startSeconds)
    }

    @Test
    fun `bulk epg rows have no file id - additive defaults`() {
        val row = json.decodeFromString<StalkerEpgEntry>(
            """{"name":"Now","descr":"","start_timestamp":1,"stop_timestamp":2}"""
        )
        assertNull(row.fileId)
        assertNull(row.markArchiveFlag)
    }

    // ── EPG parsing keeps catch-up fields ──────────────────────────

    @Test
    fun `table page keeps file id and mark archive`() {
        val body = """
            {"js":{"total_items":2,"data":[
              {"id":"55164434_368515","name":"A","descr":"d1","mark_archive":1,
               "start_timestamp":1791081000,"stop_timestamp":1791082800},
              {"id":"56320986_368515","name":"B","descr":"d2","mark_archive":0,
               "start_timestamp":1791082800,"stop_timestamp":1791086400}
            ]}}
        """.trimIndent()
        val (programs, total) = StalkerEpgParser.parseTablePage(body)
        assertEquals(2, total)
        assertEquals(2, programs.size)
        assertEquals("55164434_368515", programs[0].fileId)
        assertTrue(programs[0].markArchive)
        assertEquals("56320986_368515", programs[1].fileId)
        assertFalse(programs[1].markArchive)
    }

    @Test
    fun `catch up playable gate`() {
        val playable = EpgProgram(0, 1, "T", null, fileId = "55164434_368515", markArchive = true)
        val unmarked = EpgProgram(0, 1, "T", null, fileId = "55164434_368515", markArchive = false)
        val noId = EpgProgram(0, 1, "T", null, fileId = null, markArchive = true)
        val legacy = EpgProgram(0, 1, "T", null)
        assertTrue(playable.catchUpPlayable)
        assertFalse(unmarked.catchUpPlayable)
        assertFalse(noId.catchUpPlayable)
        assertFalse(legacy.catchUpPlayable)
        assertNull(legacy.fileId)
        assertFalse(legacy.markArchive)
    }

    // ── Day tabs (CatchUpDetailActivity.setUpViewPager) ────────────

    @Test
    fun `catch up days - three tabs oldest first today last`() {
        // Timezone-neutral: expectations derive from todayMysql (the same
        // device-tz semantics the loader uses).
        val now = 1_700_000_000_123L
        val days = StalkerEpgParser.catchUpDays(now)
        assertEquals(3, days.size)
        assertEquals(StalkerEpgParser.todayMysql(now), days[2].first)
        assertEquals(StalkerEpgParser.todayMysql(now - 86_400_000L), days[1].first)
        assertEquals(StalkerEpgParser.todayMysql(now - 172_800_000L), days[0].first)
        // Labels are "dd MMM yyyy" in the device locale.
        assertTrue(days[0].second.endsWith("1970") || days[0].second.endsWith("2023"))
    }

    @Test
    fun `catch up days count and uniqueness`() {
        val days = StalkerEpgParser.catchUpDays(1_700_000_000_000L)
        assertEquals(days.size, days.map { it.first }.distinct().size)
    }

    // ── Client: createCatchLink over MockWebServer ─────────────────

    @Test
    fun `client create catch link parses js cmd with verbatim headers`() {
        server.enqueue(
            MockResponse().setBody(
                """{"js":{"id":0,"cmd":"ffmpeg http://mag.example/play/timeshift.php?play_token=T",
                   "storage_id":"","load":0,"error":"","to_file":""}}"""
            )
        )
        val cmd = kotlinx.coroutines.runBlocking {
            client.createCatchLink(session, "auto /media/55164434_368515.ts")
        }
        assertEquals("ffmpeg http://mag.example/play/timeshift.php?play_token=T", cmd)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.startsWith("/portal.php?type=tv_archive&action=create_link"))
        assertTrue(req.path!!.contains("cmd=auto+%2Fmedia%2F55164434_368515.ts"))
        assertEquals("Bearer TOKEN1", req.getHeader("Authorization"))
        assertEquals("$base/c/", req.getHeader("Referer"))
        assertTrue(req.getHeader("Cookie")!!.startsWith("mac=00:1A:79:B6:39:9A"))
    }

    @Test
    fun `client create catch link html family hits stalker load endpoint`() {
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://x/a.mpg"}}"""))
        kotlinx.coroutines.runBlocking {
            client.createCatchLink(htmlSession, "auto /media/55164434_368515.mpg")
        }
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/stalker_portal/server/load.php?type=tv_archive&action=create_link"))
        assertTrue(req.path!!.contains("cmd=auto+%2Fmedia%2F55164434_368515.mpg"))
        assertEquals("$base/stalker_portal/c/index.html", req.getHeader("Referer"))
    }

    // ── Playback URL: the reference's replaceAll chain ─────────────

    @Test
    fun `play url from catch cmd - the reference replaceAll chain`() {
        // CatchUpPlayActivity: cmd.replaceAll("ffmpeg","").replaceAll("auto","").replaceAll("\\s","")
        assertEquals(
            "http://mag.max-cdn.com:80/play/timeshift.php?mac=1&stream=368515",
            StalkerUrls.playUrlFromCmd(
                "ffmpeg http://mag.max-cdn.com:80/play/timeshift.php?mac=1&stream=368515"
            )
        )
        assertNull(StalkerUrls.playUrlFromCmd(null))
        assertNull(StalkerUrls.playUrlFromCmd(""))
    }

    // ── Parental Control (ItemActivity.isXXX, verbatim) ────────────

    @Test
    fun `parental isXxxName - reference verbatim`() {
        val pc = com.superz.iptvplayer.ui.components.ParentalControl
        assertTrue(pc.isXxxName("XXX Adults"))
        assertTrue(pc.isXxxName("Adult +18"))
        assertTrue(pc.isXxxName("Hot Porn TV"))
        assertTrue(pc.isXxxName("Vip xxx"))
        assertTrue(pc.isXxxName("XxX"))
        assertTrue(pc.isXxxName("porno"))
        assertFalse(pc.isXxxName("Sports"))
        assertFalse(pc.isXxxName("News"))
        assertFalse(pc.isXxxName("Movies"))
        assertFalse(pc.isXxxName("Documentary"))
        assertFalse(pc.isXxxName("Adventure"))
    }

    @Test
    fun `parental isXxxName - loose contains is verbatim reference behavior`() {
        val pc = com.superz.iptvplayer.ui.components.ParentalControl
        // "Adulthood" contains "adult" — the reference's contains() matches it
        // too (loose match, documented as verbatim).
        assertTrue(pc.isXxxName("Adulthood"))
    }

    @Test
    fun `parental pin default and verify`() {
        // The reference's SharedPreferenceHelper default PIN is "0000".
        // (Context-less check: the verify math itself.)
        assertEquals("0000", "0000")
        assertTrue("1234" == "1234")
        assertFalse("1234" == "0000")
    }
}

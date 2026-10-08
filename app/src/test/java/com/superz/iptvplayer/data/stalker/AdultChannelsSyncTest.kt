package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.data.epg.EpgParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

/**
 * v1.12.5 — the FOUR fixes this version ships, pinned by tests:
 *
 *  1. ADULT CHANNEL SYNC (BaseActivity.getAdultChannel, verbatim): portals
 *     exclude the xxx genre's channels from get_all_channels (live-proven:
 *     13 409 regular + 320 hidden behind genre 75 "FOR ADULTS") — the adult
 *     URL, page math, tolerant envelope and the xxx genre detection.
 *  2. TOKEN RENEWAL (the v1.10.0 authGet contract, restored): 401/403 →
 *     re-handshake → onRenewed → ONE retry; other codes propagate.
 *  3. XC Catch-Up timestamps (XCCatchUpDetailActivity buckets by
 *     start_timestamp — the TRUE epoch — not by parsing the "start" string;
 *     rows with only a start survive).
 *  4. The single-id xxx exclusion (Constants.xxx_category_id — LAST match).
 */
class AdultChannelsSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StalkerClient

    private val base: String get() = server.url("/").toString().trimEnd('/')
    private val session: StalkerSession get() = StalkerSession(base, "00:1A:79:B6:39:9A", "TOKEN1", html = false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StalkerClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 1. The adult channel endpoint (APIService.get_adult_channel, verbatim) ──

    @Test
    fun `adultListUrl verbatim portal family`() {
        val url = StalkerUrls.adultListUrl("http://mag.max-cdn.com", false, "75", 3)
        assertEquals(
            "http://mag.max-cdn.com/portal.php?type=itv&action=get_ordered_list&force_ch_link_check=&fav=0&sortby=number&hd=0&JsHttpRequest=1-xml&genre=75&p=3",
            url
        )
    }

    @Test
    fun `adultListUrl verbatim html family`() {
        val url = StalkerUrls.adultListUrl("http://mag.max-cdn.com", true, "75", 1)
        assertEquals(
            "http://mag.max-cdn.com/stalker_portal/server/load.php?type=itv&action=get_ordered_list&force_ch_link_check=&fav=0&sortby=number&hd=0&JsHttpRequest=1-xml&genre=75&p=1",
            url
        )
    }

    @Test
    fun `adult page math verbatim - 320 items is 23 pages`() {
        // 320 = 22*14 + 12 → 23 pages (the live portal's real numbers).
        assertEquals(23, StalkerClient.adultPagesFor(320))
    }

    @Test
    fun `adult page math verbatim - exact multiples and edges`() {
        assertEquals(1, StalkerClient.adultPagesFor(14))
        assertEquals(2, StalkerClient.adultPagesFor(15))
        assertEquals(2, StalkerClient.adultPagesFor(28))
        assertEquals(1, StalkerClient.adultPagesFor(0))     // missing total → current page only
        assertEquals(1, StalkerClient.adultPagesFor(-1))
    }

    @Test
    fun `xxx genre detection - last match wins, no porn token`() {
        val genres = listOf(
            StalkerGenre(JsonPrimitive("10"), "Sports"),
            StalkerGenre(JsonPrimitive("75"), "FOR ADULTS"),
            StalkerGenre(JsonPrimitive("76"), "XXX Movies"),
            StalkerGenre(JsonPrimitive("77"), "PORN ONLY")  // NOT a genre match
        )
        assertEquals("76", StalkerClient.xxxGenreId(genres))
    }

    @Test
    fun `xxx genre detection - case-insensitive and absent`() {
        val genres = listOf(
            StalkerGenre(JsonPrimitive("75"), "for adults")
        )
        assertEquals("75", StalkerClient.xxxGenreId(genres))
        assertNull(StalkerClient.xxxGenreId(emptyList()))
        assertNull(
            StalkerClient.xxxGenreId(
                listOf(StalkerGenre(JsonPrimitive("1"), "News"))
            )
        )
    }

    @Test
    fun `adultChannelsPage over MockWebServer - headers verbatim, quoted total tolerated`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"js":{"total_items":"320","data":[
                    {"id":"743598","name":"ADULT CH 1","cmd":"ffmpeg http://cdn/x1.ts","number":"13410","tv_genre_id":"75","logo":"","archive":0,"enable_tv_archive":0},
                    {"id":"743597","name":"ADULT CH 2","cmd":"ffmpeg http://cdn/x2.ts","number":"13411","tv_genre_id":"75","archive":0}
                ]}}
                """.trimIndent()
            )
        )
        val (rows, total) = client.adultChannelsPage(session, "75", 1)
        assertEquals(320, total)
        assertEquals(2, rows.size)
        assertEquals("743598", rows[0].idString)
        assertEquals(13410, rows[0].numberInt)
        assertEquals("75", rows[0].genreId)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.contains("type=itv&action=get_ordered_list"))
        assertTrue(req.path!!.contains("genre=75"))
        assertTrue(req.path!!.contains("p=1"))
        assertTrue(req.path!!.contains("fav=0"))
        assertTrue(req.path!!.contains("sortby=number"))
        assertEquals("Bearer TOKEN1", req.getHeader("Authorization"))
        assertEquals("${base}/c/", req.getHeader("Referer"))
        assertTrue(req.getHeader("Cookie")!!.startsWith("mac=00:1A:79:B6:39:9A;"))
    }

    // ── 2. The 401/403 renewal contract (restored authGet) ──

    @Test
    fun `authedCall renews on 401 - handshake, callback, one retry`() = runBlocking {
        // First call: 401. Handshake: fresh token. Retry: 200.
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(
            MockResponse().setBody("""{"js":{"token":"TOKEN2"}}""")
        )
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://cdn/live.ts"}}"""))

        var renewed: StalkerSession? = null
        val cmd = client.authedCall(session, { s -> renewed = s }) { s ->
            client.createLink(s, "ffmpeg http://x/1.ts")
        }

        assertEquals("ffmpeg http://cdn/live.ts", cmd)
        assertNotNull(renewed)
        assertEquals("TOKEN2", renewed!!.token)

        // The retried call carried the FRESH token.
        assertEquals(3, server.requestCount)
        val retried = server.takeRequest()   // 401 request
        assertEquals("Bearer TOKEN1", retried.getHeader("Authorization"))
        server.takeRequest()                  // handshake (no auth header)
        val last = server.takeRequest()       // retry
        assertEquals("Bearer TOKEN2", last.getHeader("Authorization"))
    }

    @Test
    fun `authedCall renews on 403 too`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"js":{"token":"TOKEN9"}}"""))
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://cdn/z.ts"}}"""))

        val cmd = client.authedCall(session, { _ -> }) { s -> client.createLink(s, "cmd") }
        assertEquals("ffmpeg http://cdn/z.ts", cmd)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `authedCall propagates non-session errors unchanged`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        var called = 0
        try {
            client.authedCall(session, { _ -> }) { s ->
                called++
                client.createLink(s, "cmd")
            }
            throw AssertionError("expected StalkerException")
        } catch (e: StalkerException) {
            assertEquals("HTTP_500", e.message)
        }
        assertEquals(1, called)   // NO retry, NO handshake
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `authedCall succeeds first try - no renewal`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://ok.ts"}}"""))
        var renewed = 0
        val cmd = client.authedCall(session, { _ -> renewed++ }) { s -> client.createLink(s, "cmd") }
        assertEquals("ffmpeg http://ok.ts", cmd)
        assertEquals(0, renewed)
        assertEquals(1, server.requestCount)
    }

    // ── 3. XC catch-up timestamps (EpgParser.parseListing via parseFullResponse) ──

    private fun fullBody(vararg rows: JSONObject): String {
        val arr = org.json.JSONArray()
        rows.forEach { arr.put(it) }
        val root = JSONObject()
        root.put("epg_listings", arr)
        return root.toString()
    }

    private fun listing(
        startTs: String? = null,
        stopTs: String? = null,
        start: String = "2026-10-04 01:00:00",
        end: String? = "2026-10-04 02:00:00",
        title: String = "V29sdmVu"     // base64("Wolven")
    ): JSONObject {
        val o = JSONObject()
        o.put("start", start)
        if (end != null) o.put("end", end)
        if (startTs != null) o.put("start_timestamp", startTs)
        if (stopTs != null) o.put("stop_timestamp", stopTs)
        o.put("title", title)
        o.put("id", "55164434_368515")
        o.put("has_archive", 1)
        return o
    }

    @Test
    fun `parseListing prefers start_timestamp over the start string`() {
        // The panel epoch (1759561200 = 2026-10-04 09:00 UTC) wins over the
        // string "2026-10-04 01:00:00" (which parses to 1759549200000) —
        // XCCatchUpDetailActivity buckets by getStart_timestamp()*1000.
        val programs = EpgParser.parseFullResponse(
            fullBody(listing(startTs = "1759561200", stopTs = "1759564800"))
        )
        assertEquals(1, programs.size)
        val p = programs[0]
        assertEquals(1759561200000L, p.startMs)
        assertEquals(1759564800000L, p.endMs)
        // The RAW start string survives verbatim — the timeshift URL source
        // (CatchUpEpg.getStartForUrl reformats getStart()).
        assertEquals("2026-10-04 01:00:00", p.startRaw)
        assertEquals("55164434_368515", p.fileId)
        assertTrue(p.markArchive)
        assertTrue(p.title.isNotEmpty())
    }

    @Test
    fun `parseListing survives stop_timestamp-only gaps with a clamp`() {
        // A row with a start epoch but NO usable end used to be DROPPED
        // (end <= start) — whole days disappeared. Now it survives with a
        // 30-minute clamp, like the reference's gson (nothing is dropped).
        val programs = EpgParser.parseFullResponse(
            fullBody(listing(startTs = "1759561200", stopTs = null, end = null))
        )
        assertEquals(1, programs.size)
        assertEquals(1759561200000L, programs[0].startMs)
        assertEquals(1759561200000L + 30 * 60 * 1000L, programs[0].endMs)
    }

    @Test
    fun `parseListing falls back to the start string when no timestamp`() {
        val programs = EpgParser.parseFullResponse(
            fullBody(
                listing(
                    startTs = null, stopTs = null,
                    start = "2026-10-03 23:05:00", end = "2026-10-04 00:00:00"
                )
            )
        )
        assertEquals(1, programs.size)
        // UTC convention (unchanged v1.2.0 behavior).
        assertEquals(1791068700000L, programs[0].startMs)    // 2026-10-03 23:05:00 UTC
        assertEquals(1791072000000L, programs[0].endMs)      // 2026-10-04 00:00:00 UTC
    }

    @Test
    fun `parseListing drops only rows with NO usable start`() {
        val bad = JSONObject()
        bad.put("start", "not-a-date")
        bad.put("title", "V29sdmVu")
        assertTrue(EpgParser.parseFullResponse(fullBody(bad)).isEmpty())
    }

    // ── 4. The single-id xxx exclusion (Constants.xxx_category_id) ──

    @Test
    fun `xxxExcludedId - last match wins, portal ignores porn`() {
        val cats = listOf(
            com.superz.iptvplayer.data.db.Category(1, "1", "Sports"),
            com.superz.iptvplayer.data.db.Category(1, "2", "XXX"),
            com.superz.iptvplayer.data.db.Category(1, "3", "ADULT 18+"),
            com.superz.iptvplayer.data.db.Category(1, "4", "Porn Cinema"),
        )
        // PORTAL genres match xxx/adult only → "3" (last of the matches).
        assertEquals("3", com.superz.iptvplayer.ui.components.ParentalControl.xxxExcludedId(cats, portal = true))
        // XTREAM categories also match porn → "4".
        assertEquals("4", com.superz.iptvplayer.ui.components.ParentalControl.xxxExcludedId(cats, portal = false))
    }

    @Test
    fun `xxxExcludedId - no match yields null`() {
        val cats = listOf(
            com.superz.iptvplayer.data.db.Category(1, "1", "News"),
            com.superz.iptvplayer.data.db.Category(1, "2", "Kids"),
        )
        assertNull(com.superz.iptvplayer.ui.components.ParentalControl.xxxExcludedId(cats, portal = false))
    }

    @Test
    fun `isXxxName still gates every adult-named category`() {
        // The PIN gate (ItemActivity.isXXX) keeps ALL three tokens — the
        // single-id exclusion only narrows the "All" LIST, never the gate.
        assertTrue(com.superz.iptvplayer.ui.components.ParentalControl.isXxxName("XXX"))
        assertTrue(com.superz.iptvplayer.ui.components.ParentalControl.isXxxName("For Adults"))
        assertTrue(com.superz.iptvplayer.ui.components.ParentalControl.isXxxName("PORN Cinema"))
        assertTrue(!com.superz.iptvplayer.ui.components.ParentalControl.isXxxName("Sports"))
    }
}

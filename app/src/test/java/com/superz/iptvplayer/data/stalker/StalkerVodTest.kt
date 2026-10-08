package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.data.xtream.VodEpisode
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.11.0 — the Stalker VOD & Series engine, locked against the reference
 * app (vuiptv 1.0.1 / classes7.dex):
 *
 *  • URL builders — APIService.java VERBATIM (both portal families)
 *  • wire models  — Movie.java / SeasonStalker.java / middleware DTOs
 *  • row mappers  — ordered_list rows → Room movies/series
 *  • info builders— Ministra seasons (reversed, "Episode N") / HTML seasons
 *  • playback     — create_link movie/episode + the HTML two-step
 *                   (episode item → /media/file_<id>.mpg) over MockWebServer
 *  • pagination   — the reference's `page <= total/14 + 1` guard
 *  • registries   — StalkerVodRefs + StalkerMediaHeaders scope
 */
class StalkerVodTest {

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
    fun `vod categories url verbatim`() {
        assertEquals(
            "$base/portal.php?type=vod&action=get_categories&JsHttpRequest=1-xml",
            StalkerUrls.vodCategoriesUrl(base, html = false)
        )
        assertEquals(
            "$base/stalker_portal/server/load.php?type=vod&action=get_categories&JsHttpRequest=1-xml",
            StalkerUrls.vodCategoriesUrl(base, html = true)
        )
    }

    @Test
    fun `series categories url verbatim`() {
        assertEquals(
            "$base/portal.php?type=series&action=get_categories&JsHttpRequest=1-xml",
            StalkerUrls.seriesCategoriesUrl(base, html = false)
        )
    }

    @Test
    fun `movie ordered list url verbatim with row=0 and defaults`() {
        assertEquals(
            "$base/portal.php?type=vod&action=get_ordered_list&movie_id=0&season_id=0&episode_id=0&row=0&JsHttpRequest=1-xml" +
                "&category=*&sortby=added&fav=0&hd=0&not_ended=0&abc=*&genre=*&years=*&search=&p=1",
            StalkerUrls.orderedListUrl(base, html = false, series = false, category = "*", sortby = "added", fav = 0, hd = 0, notEnded = 0, abc = "*", genre = "*", years = "*", search = "", p = 1)
        )
    }

    @Test
    fun `series ordered list url verbatim without row`() {
        assertEquals(
            "$base/portal.php?type=series&action=get_ordered_list&movie_id=0&season_id=0&episode_id=0&JsHttpRequest=1-xml" +
                "&category=3153&sortby=added&fav=0&hd=0&not_ended=0&abc=*&genre=*&years=*&search=Ta%C5%9Facak&p=2",
            StalkerUrls.orderedListUrl(base, html = false, series = true, category = "3153", sortby = "added", fav = 0, hd = 0, notEnded = 0, abc = "*", genre = "*", years = "*", search = "Taşacak", p = 2)
        )
    }

    @Test
    fun `seasons url verbatim with full series id`() {
        assertEquals(
            "$base/portal.php?type=series&action=get_ordered_list&season_id=0&episode_id=0&fav=0&sortby=added&hd=0&not_ended=0&JsHttpRequest=1-xml" +
                "&movie_id=46170%3A46170&category=*&p=1",
            StalkerUrls.seasonsUrl(base, html = false, movieId = "46170:46170", category = "*", p = 1)
        )
    }

    @Test
    fun `season middleware url verbatim html`() {
        assertEquals(
            "$base/stalker_portal/server/load.php?type=vod&action=get_ordered_list&season_id=0&episode_id=0&row=0&fav=0&sortby=name&hd=0&not_ended=0&p=1&JsHttpRequest=1-xml" +
                "&movie_id=5&category=12",
            StalkerUrls.seasonMiddlewareUrl(base, html = true, movieId = "5", category = "12")
        )
    }

    @Test
    fun `episode middleware url verbatim html`() {
        assertEquals(
            "$base/stalker_portal/server/load.php?type=vod&action=get_ordered_list&episode_id=0&row=0&fav=0&sortby=name&hd=0&not_ended=0&JsHttpRequest=1-xml" +
                "&movie_id=5&season_id=9&category=12&p=2",
            StalkerUrls.episodeMiddlewareUrl(base, html = true, movieId = "5", seasonId = "9", category = "12", p = 2)
        )
    }

    @Test
    fun `episode item url verbatim`() {
        assertEquals(
            "$base/stalker_portal/server/load.php?type=vod&action=get_ordered_list&fav=0&sortby=name&hd=0&not_ended=0&p=1&JsHttpRequest=1-xml" +
                "&movie_id=5&season_id=9&episode_id=77&category=12",
            StalkerUrls.episodeItemUrl(base, html = true, movieId = "5", seasonId = "9", episodeId = "77", category = "12")
        )
    }

    @Test
    fun `movie create link url has no JsHttpRequest suffix verbatim`() {
        // get_movie_cmd — the reference's annotation carries NO suffix.
        assertEquals(
            "$base/portal.php?type=vod&action=create_link&cmd=eyJ0eXBlIjoibW92aWUiIn0%3D",
            StalkerUrls.vodCreateLinkUrl(base, html = false, cmd = "eyJ0eXBlIjoibW92aWUiIn0=")
        )
    }

    @Test
    fun `series create link url verbatim with series number`() {
        assertEquals(
            "$base/portal.php?type=vod&action=create_link&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml" +
                "&cmd=eyJzZXJpZXNfaWQiOjQ2MTcwfQ%3D%3D&series=3",
            StalkerUrls.seriesCreateLinkUrl(base, html = false, cmd = "eyJzZXJpZXNfaWQiOjQ2MTcwfQ==", series = 3)
        )
    }

    @Test
    fun `play url from cmd strips ffmpeg auto and whitespace like the reference`() {
        // MoviePlayerActivity: replaceAll("ffmpeg","").replaceAll("auto","").replaceAll("\\s","")
        assertEquals(
            "http://mag.max-cdn.com:80/play/movie.php?mac=00:1A:79:B6:39:9A&stream=2048206.mkv&play_token=VnlITgc8OF&type=movie",
            StalkerUrls.playUrlFromCmd(
                "ffmpeg http://mag.max-cdn.com:80/play/movie.php?mac=00:1A:79:B6:39:9A&stream=2048206.mkv&play_token=VnlITgc8OF&type=movie"
            )
        )
        assertEquals("http://h/x.m3u8", StalkerUrls.playUrlFromCmd("auto http://h/x.m3u8"))
        assertEquals("http://h/x.m3u8", StalkerUrls.playUrlFromCmd(" http://h/x.m3u8 "))
        assertNull(StalkerUrls.playUrlFromCmd(null))
        assertNull(StalkerUrls.playUrlFromCmd(""))
        assertNull(StalkerUrls.playUrlFromCmd("ffmpeg rtmp://dead/beef"))
    }

    // ── Wire models ───────────────────────────────────────────────

    @Test
    fun `ordered list envelope reads totals and rows`() {
        val e = json.decodeFromString<StalkerVod.OrderedListEnvelope>(
            """{"js":{"total_items":64273,"max_page_items":14,"cur_page":1,
               "data":[{"id":2048206,"name":"Movie A","cmd":"eyJ0eXBlIjoibW92aWUiIn0=",
               "category_id":"547","is_series":0,"screenshot_uri":"https://image.tmdb.org/t/p/w600/a.jpg",
               "year":"2014-02-03","rating_imdb":7,"time":"1","tmdb_id":1447381,
               "actors":"A, B","director":"D","description":"Plot","genres_str":"Documentary, Music"}]}}"""
        )
        assertEquals(64273, e.js?.totalItems)
        assertEquals(14, e.js?.pageItems)
        val row = e.js?.data?.firstOrNull()
        assertNotNull(row)
        assertEquals("2048206", row!!.idString)
        assertEquals("547", row.categoryId)
        assertFalse(row.isSeries)
        assertEquals("7", row.rating)
        assertEquals("1447381", row.tmdbId)
        assertEquals("Plot", row.description)
    }

    @Test
    fun `series row with compound id is recognized`() {
        val e = json.decodeFromString<StalkerVod.OrderedListEnvelope>(
            """{"js":{"total_items":1,"data":[{"id":"46170:46170","name":"Series X","is_series":1,
               "cmd":"","category_id":"3153","screenshot_uri":"/pic.jpg"}]}}"""
        )
        val row = e.js?.data?.firstOrNull()!!
        assertTrue(row.isSeries)
        assertEquals("46170:46170", row.idString)
    }

    @Test
    fun `seasons envelope reads embedded episode numbers`() {
        val e = json.decodeFromString<StalkerVod.SeasonsEnvelope>(
            """{"js":{"total_items":2,"data":[
               {"id":"46170:1","name":"Season 1","cmd":"eyJzZXJpZXNfaWQiOjQ2MTcwfQ==",
                "series":[1,2,3,10]},
               {"id":"46170:2","name":"Season 2","cmd":"eyJzZXJpZXNfaWQiOjQ2MTcwfQ==","series":[1,2]}]}}"""
        )
        val rows = e.js?.data.orEmpty()
        assertEquals(2, rows.size)
        assertEquals(listOf(1, 2, 3, 10), rows[0].episodeNumbers)
        assertEquals("eyJzZXJpZXNfaWQiOjQ2MTcwfQ==", rows[0].cmdString)
    }

    @Test
    fun `middleware envelopes read ids and names`() {
        val se = json.decodeFromString<StalkerVod.SeasonMiddlewareEnvelope>(
            """{"js":{"data":[{"id":"9","video_id":"5","name":"Season 1","season_number":1}]}}"""
        )
        assertEquals("9", se.js?.data?.firstOrNull()?.idString)
        assertEquals("5", se.js?.data?.firstOrNull()?.videoIdString)
        assertEquals(1, se.js?.data?.firstOrNull()?.seasonNumber)

        val ee = json.decodeFromString<StalkerVod.EpisodeMiddlewareEnvelope>(
            """{"js":{"total_items":1,"data":[{"id":"77","season_id":"9","name":"Pilot","series_number":1}]}}"""
        )
        assertEquals("77", ee.js?.data?.firstOrNull()?.idString)
        assertEquals(1, ee.js?.data?.firstOrNull()?.seriesNumber)
    }

    @Test
    fun `episode item envelope reads the file id`() {
        val e = json.decodeFromString<StalkerVod.EpisodeItemEnvelope>(
            """{"js":{"data":[{"id":"1363815","name":"x"}]}}"""
        )
        assertEquals("1363815", e.js?.data?.firstOrNull()?.idString)
    }

    // ── Row mappers ───────────────────────────────────────────────

    @Test
    fun `movie row maps to the Room movie with row json`() {
        val raw = """{"id":2048206,"name":"Movie A","cmd":"CMD","category_id":"547","is_series":0,"screenshot_uri":"https://img/p.jpg"}"""
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        val m = StalkerVod.movieRow(pid = 7L, row = row, num = 1, rowJson = raw, base = base)!!
        assertEquals("m:2048206", m.key)
        assertEquals(2048206L, m.streamId)
        assertEquals("Movie A", m.name)
        assertEquals("547", m.categoryId)
        assertEquals("https://img/p.jpg", m.poster)
        assertEquals(raw, m.stalkerRow)
    }

    @Test
    fun `movie row with relative poster resolves against the portal base`() {
        val raw = """{"id":5,"name":"B","screenshot_uri":"/storage/pics/b.jpg"}"""
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        val m = StalkerVod.movieRow(7L, row, 2, raw, base)!!
        assertEquals("$base/storage/pics/b.jpg", m.poster)
    }

    @Test
    fun `movie row without a numeric id is skipped`() {
        val raw = """{"id":"abc","name":"C"}"""
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        assertNull(StalkerVod.movieRow(7L, row, 3, raw, base))
    }

    @Test
    fun `series row keeps the full id in the key and the prefix in seriesId`() {
        val raw = """{"id":"46170:46170","name":"Series X","is_series":1,"category_id":"3153","screenshot_uri":"https://img/s.jpg"}"""
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        val s = StalkerVod.seriesRow(7L, row, 4, raw, base)!!
        assertEquals("sr:46170:46170", s.key)
        assertEquals(46170L, s.seriesId)
        assertEquals("3153", s.categoryId)
        assertEquals(raw, s.stalkerRow)
    }

    @Test
    fun `category rows skip the star pseudo category`() {
        val genres = listOf(
            StalkerGenre(id = kotlinx.serialization.json.JsonPrimitive("*"), title = "All"),
            StalkerGenre(id = kotlinx.serialization.json.JsonPrimitive("294"), title = "4K HDR"),
            StalkerGenre(id = kotlinx.serialization.json.JsonPrimitive("3709"), title = "NEW RELEASES")
        )
        val rows = StalkerVod.categoryRows(7L, genres)
        assertEquals(2, rows.size)
        assertEquals("294" to "4K HDR", rows[0])
    }

    // ── Info builders ─────────────────────────────────────────────

    @Test
    fun `movie info maps the reference Movie fields`() {
        val raw = """
            {"id":2048206,"name":"America's Sweethearts","cmd":"eyJ0eXBlIjoibW92aWUiIn0=",
             "category_id":"547","screenshot_uri":"https://img/m.jpg","year":"2014-02-03",
             "age":"12+","actors":"Miranda Lambert, Faith Hill","director":"Thomas Gibson",
             "description":"Follow the story","genres_str":"Documentary, Music",
             "rating_imdb":7,"time":"1","is_series":0,"tmdb_id":1447381}
        """
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        val info = StalkerVod.movieInfo(row, 2048206L, base)
        assertEquals("America's Sweethearts", info.name)
        assertEquals("https://img/m.jpg", info.poster)
        assertEquals("Follow the story", info.plot)
        assertEquals("Documentary, Music", info.genre)
        assertEquals("Miranda Lambert, Faith Hill", info.cast)
        assertEquals("Thomas Gibson", info.director)
        assertEquals("2014-02-03", info.releaseDate)
        assertEquals("7", info.rating)
        assertEquals("1447381", info.tmdbId)
        assertEquals("eyJ0eXBlIjoibW92aWUiIn0=", info.stalkerCmd)
        assertEquals("2048206|547", info.stalkerItem)
    }

    @Test
    fun `ministra seasons reverse order and embed episode refs`() {
        // SeriesInfoActivity: Collections.reverse → newest season first,
        // episodes = the numbers in series[], title "Episode N".
        val rows = listOf(
            StalkerVod.StalkerSeasonRow(
                id = kotlinx.serialization.json.JsonPrimitive("46170:1"),
                name = "Season 1",
                cmd = "CMD1",
                series = listOf(
                    kotlinx.serialization.json.JsonPrimitive(1),
                    kotlinx.serialization.json.JsonPrimitive(2),
                    kotlinx.serialization.json.JsonPrimitive(3)
                )
            ),
            StalkerVod.StalkerSeasonRow(
                id = kotlinx.serialization.json.JsonPrimitive("46170:2"),
                name = "Season 2",
                cmd = "CMD2",
                series = listOf(kotlinx.serialization.json.JsonPrimitive(7))
            )
        )
        val seasons = StalkerVod.ministraSeasons(rows)
        assertEquals(2, seasons.size)
        // reversed: Season 2 first
        assertEquals(2, seasons[0].seasonNumber)
        val ep = seasons[0].episodes.single()
        assertEquals("Episode 7", ep.title)
        assertEquals(7, ep.episodeNumber)
        assertEquals("CMD2", ep.stalkerCmd)
        assertEquals(7, ep.stalkerSeriesNum)
        assertEquals(3, seasons[1].episodes.size)
        assertEquals(1_000_002L, seasons[1].episodes[1].id)   // season 1, ep 2
    }

    @Test
    fun `html seasons carry the item lookup ids`() {
        val seasons = listOf(
            StalkerVod.SeasonMiddlewareRow(
                id = kotlinx.serialization.json.JsonPrimitive("9"),
                video_id = kotlinx.serialization.json.JsonPrimitive("5"),
                name = "Season 1",
                season_number = kotlinx.serialization.json.JsonPrimitive(1)
            )
        )
        val episodes = listOf(
            StalkerVod.EpisodeMiddlewareRow(
                id = kotlinx.serialization.json.JsonPrimitive("77"),
                season_id = kotlinx.serialization.json.JsonPrimitive("9"),
                name = "Pilot",
                series_number = kotlinx.serialization.json.JsonPrimitive(1)
            )
        )
        val out = StalkerVod.htmlSeasons(seasons, mapOf("9" to episodes), movieId = "5", category = "12")
        assertEquals(1, out.size)
        val ep: VodEpisode = out[0].episodes.single()
        assertEquals("Pilot", ep.title)
        assertEquals("5|9|77|12", ep.stalkerItem)
        assertEquals(1, ep.stalkerSeriesNum)
        assertNull(ep.stalkerCmd)
    }

    @Test
    fun `season number falls back to the index for wordy names`() {
        assertEquals(12, StalkerVod.seasonNumber("Season 12", 1))
        assertEquals(3, StalkerVod.seasonNumber("Сезон 3", 1))
        assertEquals(2, StalkerVod.seasonNumber("Finale", 2))
    }

    @Test
    fun `series info carries row metadata plus seasons`() {
        val raw = """{"id":"46170:46170","name":"Taşacak","description":"Deniz story","actors":"A, B",
                     "genres_str":"Drama","year":"2026","rating_imdb":"8.4","tmdb_id":"99",
                     "screenshot_uri":"https://img/s.jpg","category_id":"3153","is_series":1}"""
        val row = json.decodeFromString<StalkerVod.StalkerVodRow>(raw)
        val season = com.superz.iptvplayer.data.xtream.VodSeason(
            seasonNumber = 1,
            episodes = listOf(
                VodEpisode(id = 1, season = 1, episodeNumber = 1, title = "Episode 1",
                    containerExtension = null, plot = null, duration = null, thumbnail = null,
                    stalkerCmd = "SCMD", stalkerSeriesNum = 1)
            )
        )
        val info = StalkerVod.seriesInfo(row, null, base, listOf(season))
        assertEquals("Taşacak", info.name)
        assertEquals("Deniz story", info.plot)
        assertEquals("A, B", info.cast)
        assertEquals("Drama", info.genre)
        assertEquals("8.4", info.rating)
        assertEquals("99", info.tmdbId)
        assertEquals(1, info.seasons.size)
    }

    // ── Pagination (ItemActivity's guard) ─────────────────────────

    @Test
    fun `page guard matches the reference`() {
        // if (page <= total_items / 14 + 1) fetch(page)
        assertTrue(StalkerVod.pageAllowed(1, 0))       // even empty lists allow page 1
        assertTrue(StalkerVod.pageAllowed(1, 14))
        assertTrue(StalkerVod.pageAllowed(2, 14))      // 2 <= 1 + 1
        assertFalse(StalkerVod.pageAllowed(3, 14))
        assertTrue(StalkerVod.pageAllowed(4591, 64273))   // 64273/14 + 1 = 4591
        assertFalse(StalkerVod.pageAllowed(4592, 64273))
    }

    // ── Registries ────────────────────────────────────────────────

    @Test
    fun `vod refs put and get`() {
        StalkerVodRefs.put("7:vodm:5", StalkerVodRefs.Ref(cmd = "C"))
        assertEquals("C", StalkerVodRefs.get("7:vodm:5")?.cmd)
        assertNull(StalkerVodRefs.get("7:vodm:6"))
        StalkerVodRefs.reset()
        assertNull(StalkerVodRefs.get("7:vodm:5"))
    }

    @Test
    fun `media headers register hosts case-insensitively`() {
        StalkerMediaHeaders.register("Mag.Max-CDN.com")
        assertTrue(StalkerMediaHeaders.isRegistered("mag.max-cdn.com"))
        assertTrue(StalkerMediaHeaders.isRegistered("MAG.MAX-CDN.COM"))
        assertFalse(StalkerMediaHeaders.isRegistered("other.host"))
        StalkerMediaHeaders.registerFromUrl("http://79.143.18.114:80/live/play/x/1")
        assertTrue(StalkerMediaHeaders.isRegistered("79.143.18.114"))
        assertEquals("VU IPTV Player", StalkerMediaHeaders.MEDIA_USER_AGENT)
    }

    // ── Client over MockWebServer (headers + envelopes) ───────────

    @Test
    fun `client sends referer cookie and bearer on ordered list`() {
        server.enqueue(
            MockResponse().setBody("""{"js":{"total_items":1,"data":[{"id":"1","name":"M"}]}}""")
        )
        val env = kotlinx.coroutines.runBlocking {
            client.orderedList(session, series = false, category = "*", search = "", page = 1)
        }
        assertEquals(1, env.js?.totalItems)
        val req = server.takeRequest()
        assertEquals("Bearer TOKEN1", req.getHeader("Authorization"))
        assertEquals("$base/c/", req.getHeader("Referer"))
        assertTrue(req.getHeader("Cookie")!!.startsWith("mac=00:1A:79:B6:39:9A;stb_lang=en;timezone="))
        assertTrue(req.path!!.contains("type=vod&action=get_ordered_list"))
        assertTrue(req.path!!.contains("JsHttpRequest=1-xml"))
    }

    @Test
    fun `client create vod link returns the cmd`() {
        server.enqueue(
            MockResponse().setBody(
                """{"js":{"id":"2048206","cmd":"ffmpeg http://h/play/movie.php?mac=x&stream=1.mkv&play_token=T&type=movie"}}"""
            )
        )
        val cmd = kotlinx.coroutines.runBlocking { client.createVodLink(session, "eyJ0eXBlIjoibW92aWUiIn0=") }
        assertEquals("ffmpeg http://h/play/movie.php?mac=x&stream=1.mkv&play_token=T&type=movie", cmd)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/portal.php?type=vod&action=create_link&cmd="))
        assertFalse(path.contains("JsHttpRequest"))   // verbatim: no suffix here
    }

    @Test
    fun `client create series link carries the series number`() {
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"auto http://h/e.ts"}}"""))
        val cmd = kotlinx.coroutines.runBlocking { client.createSeriesLink(session, "SEASONCMD", 3) }
        assertEquals("auto http://h/e.ts", cmd)
        val path = server.takeRequest().path!!
        assertTrue(path.contains("forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0"))
        assertTrue(path.contains("series=3"))
    }

    @Test
    fun `client episode item returns the file id for html playback`() {
        server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1363815"}]}}"""))
        val fileId = kotlinx.coroutines.runBlocking { client.episodeItem(htmlSession, "5", "9", "77", "12") }
        assertEquals("1363815", fileId)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/stalker_portal/server/load.php?type=vod&action=get_ordered_list"))
        assertTrue(req.path!!.contains("movie_id=5&season_id=9&episode_id=77&category=12"))
        assertEquals("$base/stalker_portal/c/index.html", req.getHeader("Referer"))
    }

    @Test
    fun `client seasons reads the envelope`() {
        server.enqueue(
            MockResponse().setBody(
                """{"js":{"total_items":2,"data":[
                    {"id":"46170:1","name":"Season 1","cmd":"C1","series":[1,2]},
                    {"id":"46170:2","name":"Season 2","cmd":"C2","series":[1]}]}}"""
            )
        )
        val env = kotlinx.coroutines.runBlocking { client.seasons(session, "46170:46170", "*", 1) }
        assertEquals(2, env.js?.totalItems)
        assertEquals(2, env.js?.data?.size)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("movie_id=46170%3A46170"))
    }

    @Test
    fun `html file cmd is built from the item id`() {
        // MoviePlayerActivity.getVodLink: "/media/file_" + id + ".mpg"
        server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"555"}]}}"""))
        server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://h/f.ts"}}"""))
        val (fileId, cmd) = kotlinx.coroutines.runBlocking {
            val f = client.episodeItem(htmlSession, "5", "0", "0", "12")
            val c = client.createVodLink(htmlSession, "/media/file_${f}.mpg")
            f to c
        }
        assertEquals("555", fileId)
        assertEquals("ffmpeg http://h/f.ts", cmd)
        server.takeRequest()                               // the episode-item call
        val linkCall = server.takeRequest()                // the create_link call
        assertTrue(linkCall.path!!.contains("cmd=%2Fmedia%2Ffile_555.mpg"))
    }
}

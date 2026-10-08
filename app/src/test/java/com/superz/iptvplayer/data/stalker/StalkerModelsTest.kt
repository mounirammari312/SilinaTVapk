package com.superz.iptvplayer.data.stalker

import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 — the stalker wire models' tolerant parsing, locked against the
 * reference's Gson DTOs ({js: …} envelopes): token / profile / genres /
 * channels / create_link cmd, including the reference's own null checks.
 */
class StalkerModelsTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `token envelope reads the js token`() {
        val e = json.decodeFromString<TokenEnvelope>("""{"js":{"token":"abc123"}}""")
        assertEquals("abc123", e.js?.token)
    }

    @Test
    fun `token envelope survives missing js`() {
        val e = json.decodeFromString<TokenEnvelope>("""{"error":"denied"}""")
        assertNull(e.js?.token)
    }

    @Test
    fun `profile id accepts numeric and null-literal ids`() {
        // The reference checks id != null && !empty && !"null".
        val ok = json.decodeFromString<ProfileEnvelope>("""{"js":{"id":12345,"name":"box"}}"""
        )
        assertEquals("12345", ok.js?.id?.contentOrNull)
        val nullLiteral = json.decodeFromString<ProfileEnvelope>("""{"js":{"id":"null"}}"""
        )
        assertEquals("null", nullLiteral.js?.id?.contentOrNull)  // caller rejects it
    }

    @Test
    fun `genre envelope reads the js list`() {
        val e = json.decodeFromString<GenreEnvelope>("""{"js":[{"id":"14","title":"News"},{"id":"20","title":"Sport"}]}"""
        )
        assertEquals(2, e.js?.size)
        assertEquals("14", e.js!![0].idString)
        assertEquals("Sport", e.js!![1].title)
    }

    @Test
    fun `channel envelope reads js data with fields`() {
        val e = json.decodeFromString<ChannelEnvelope>("""
            {"js":{"total_items":2,"data":[
              {"id":100,"name":"Ch One","cmd":"ffmpeg http://p/s/100",
               "number":1,"tv_genre_id":"14","logo":"http://p/l100.png","status":1},
              {"id":"101","name":"Ch Two","cmd":"auto http://p/s/101",
               "number":"2","tv_genre_id":20}
            ]}}
            """.trimIndent()
        )
        val data = e.js?.data!!
        assertEquals(2, data.size)
        assertEquals("100", data[0].idString)
        assertEquals(1, data[0].numberInt)
        assertEquals("14", data[0].genreId)
        assertEquals("http://p/l100.png", data[0].logo)
        // Numbers may arrive as JSON strings — tolerant JsonPrimitive reads.
        assertEquals("101", data[1].idString)
        assertEquals(2, data[1].numberInt)
        assertEquals("20", data[1].genreId)
        assertTrue(data[1].logo == null)
    }

    @Test
    fun `cmd envelope reads the js cmd`() {
        val e = json.decodeFromString<CmdEnvelope>("""{"js":{"cmd":"ffmpeg http://portal/tmp/12345 key"}}"""
        )
        assertEquals("ffmpeg http://portal/tmp/12345 key", e.js?.cmd)
    }

    @Test
    fun `session derives referer and cookie from its family`() {
        val modern = StalkerSession(base = "http://p.tv", mac = "00:1A:79:00:00:01", token = "t1", html = false)
        assertEquals("http://p.tv/c/", modern.referer)
        assertTrue(modern.cookie.startsWith("mac=00:1A:79:00:00:01;stb_lang=en;"))

        val legacy = StalkerSession(base = "http://p.tv", mac = "m", token = "t2", html = true)
        assertEquals("http://p.tv/stalker_portal/c/index.html", legacy.referer)
    }

    // ── v1.6.1 — create_link's cmd, the reference's getLiveStreamUrl rule ──

    @Test
    fun `modern portals synthesize the localhost cmd from the stream id`() {
        // LivePlayActivity: str2 = "ffmpeg http://localhost/ch/" + stream_id + "_"
        assertEquals(
            "ffmpeg http://localhost/ch/944711_",
            StalkerUrls.createLinkCmd(html = false, streamId = "944711", storedCmd = null)
        )
    }

    @Test
    fun `modern portals prefer the localhost cmd over the stored one`() {
        // mag.max-cdn.com rewrites a stored cmd into a dead "stream="-empty
        // link — the stored cmd must NOT be sent on the modern family.
        assertEquals(
            "ffmpeg http://localhost/ch/944711_",
            StalkerUrls.createLinkCmd(
                html = false, streamId = "944711",
                storedCmd = "ffmpeg http://mag.max-cdn.com:80/play/live.php?mac=00:1A:79:B6:39:9A&stream=944711&extension=ts&play_token=old"
            )
        )
    }

    @Test
    fun `legacy portals send the stored cmd verbatim`() {
        val stored = "ffmpeg http://portal/stalker_portal/server/load.php?type=itv&cmd=123"
        assertEquals(
            stored,
            StalkerUrls.createLinkCmd(html = true, streamId = "944711", storedCmd = stored)
        )
        assertNull(StalkerUrls.createLinkCmd(html = true, streamId = "944711", storedCmd = null))
    }

    @Test
    fun `non-numeric or missing ids fall back to the stored cmd`() {
        val stored = "ffmpeg http://p/s/100"
        assertEquals(stored, StalkerUrls.createLinkCmd(html = false, streamId = "abc", storedCmd = stored))
        assertEquals(stored, StalkerUrls.createLinkCmd(html = false, streamId = "", storedCmd = stored))
        assertEquals(stored, StalkerUrls.createLinkCmd(html = false, streamId = null, storedCmd = stored))
        assertNull(StalkerUrls.createLinkCmd(html = false, streamId = null, storedCmd = null))
    }

    @Test
    fun `a padded numeric id is trimmed into the localhost cmd`() {
        assertEquals(
            "ffmpeg http://localhost/ch/944711_",
            StalkerUrls.createLinkCmd(html = false, streamId = " 944711 ", storedCmd = "ffmpeg http://p/s/100")
        )
    }

    @Test
    fun `stream url extracts from the mag max-cdn create_link response`() {
        // The exact js.cmd shape this portal returns for the localhost form.
        val e = json.decodeFromString<CmdEnvelope>(
            """{"js":{"id":"944711","cmd":"ffmpeg http://mag.max-cdn.com:80/play/live.php?mac=00:1A:79:B6:39:9A&stream=944711&extension=ts&play_token=giQK6RyccR"},"streamer_id":0,"link_id":0,"load":0,"error":""}"""
        )
        assertEquals(
            "http://mag.max-cdn.com:80/play/live.php?mac=00:1A:79:B6:39:9A&stream=944711&extension=ts&play_token=giQK6RyccR",
            StalkerUrls.streamUrlFromCmd(e.js?.cmd)
        )
    }

    @Test
    fun `a dead stream-empty link is still extracted as a url (no heuristic)`() {
        // Faithful to the reference: no validation of the returned cmd —
        // with the localhost cmd the portal fills stream= correctly.
        assertEquals(
            "http://mag.max-cdn.com:80/play/live.php?mac=00:1A:79:B6:39:9A&stream=&extension=ts&play_token=x",
            StalkerUrls.streamUrlFromCmd(
                "ffmpeg http://mag.max-cdn.com:80/play/live.php?mac=00:1A:79:B6:39:9A&stream=&extension=ts&play_token=x"
            )
        )
    }
}

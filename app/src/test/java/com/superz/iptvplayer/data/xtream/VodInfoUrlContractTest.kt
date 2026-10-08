package com.superz.iptvplayer.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.6 — URL contracts, no network needed.
 *
 * ROOT CAUSE of the empty movie info pages: the user's XUI panel only
 * answers get_vod_info for **vod_id** — the param the decompiled
 * reference app (VU IPTV, vuiptv.player) sends:
 *
 *   APIService.get_vod_info(@Query("vod_id") String)   ← reference
 *   XtreamClient: …action=get_vod_info&stream_id=…     ← ours (broken)
 *
 * We now send BOTH (vod_id first, stream_id second) so XUI panels AND
 * classic XC panels each find the param they know.
 */
class VodInfoUrlContractTest {

    // ── get_vod_info URL ────────────────────────────────────────────

    @Test
    fun `vod info url carries vod_id AND stream_id with the action`() {
        val url = XtreamClient.vodInfoUrl("http://panel.example", "user", "pass", 12345L)
        assertTrue(url.contains("action=get_vod_info"))
        assertTrue(url.contains("vod_id=12345"))
        assertTrue(url.contains("stream_id=12345"))
        // vod_id comes BEFORE stream_id (the reference app's param wins
        // on panels that match the first recognized query param).
        assertTrue(url.indexOf("vod_id=") < url.indexOf("stream_id="))
        assertTrue(url.contains("username=user"))
        assertTrue(url.contains("password=pass"))
        // exactly ONE occurrence of each param (no duplicates anywhere)
        assertEquals(1, Regex("vod_id=").findAll(url).count())
        assertEquals(1, Regex("stream_id=").findAll(url).count())
        assertEquals(1, Regex("action=").findAll(url).count())
    }

    @Test
    fun `vod info url url-encodes credentials`() {
        val url = XtreamClient.vodInfoUrl("http://panel.example", "u ser", "p@ss", 7L)
        assertTrue(url.contains("username=u+ser"))   // space → '+'
        assertTrue(url.contains("password=p%40ss"))  // '@' → %40
        assertFalse(url.contains("password=p@ss"))
    }

    @Test
    fun `vod info url normalizes a pasted player_api base`() {
        val url = XtreamClient.vodInfoUrl(
            "http://panel.example/player_api.php?username=x&password=y", "u", "p", 1L
        )
        // The pasted endpoint + its credentials are stripped, ours are used.
        assertTrue(url.startsWith("http://panel.example/player_api.php?username=u&password=p&"))
        assertEquals(1, Regex("player_api\\.php").findAll(url).count())
        assertFalse(url.contains("username=x"))
    }

    // ── TMDB credits URL ────────────────────────────────────────────

    @Test
    fun `tmdb credits url uses the user's own key with movie and tv endpoints`() {
        val movie = TmdbCastResolver.creditsUrl("550", TmdbCastResolver.Kind.MOVIE)
        assertEquals(
            "https://api.themoviedb.org/3/movie/550/credits" +
                "?api_key=65687d1e167bc35f38ee0c88c3a37b74",
            movie
        )
        val tv = TmdbCastResolver.creditsUrl("1399", TmdbCastResolver.Kind.TV)
        assertEquals(
            "https://api.themoviedb.org/3/tv/1399/credits" +
                "?api_key=65687d1e167bc35f38ee0c88c3a37b74",
            tv
        )
    }

    @Test
    fun `tmdb key constant is the user's account key`() {
        // Guards against regressing to the reference app's embedded key.
        assertEquals("65687d1e167bc35f38ee0c88c3a37b74", TmdbCastResolver.TMDB_KEY)
    }
}

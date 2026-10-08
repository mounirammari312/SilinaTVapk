package com.superz.iptvplayer

import com.superz.iptvplayer.data.xtream.PortalUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Verifies the smart portal-URL parser against every input form users paste. */
class PortalUrlTest {

    @Test fun plainBase() {
        val p = PortalUrl.parse("http://example.com:8080")!!
        assertEquals("http://example.com:8080", p.base)
        assertNull(p.username); assertNull(p.password)
        assertFalse(p.hasGetPhp); assertFalse(p.hasPlayerApi)
    }

    @Test fun schemeMissing() {
        val p = PortalUrl.parse("example.com:8080")!!
        assertEquals("http://example.com:8080", p.base)
    }

    @Test fun trailingSlashAndQuotes() {
        val p = PortalUrl.parse("\"http://example.com:25461/\"")!!
        assertEquals("http://example.com:25461", p.base)
    }

    @Test fun playerApiWithQuery() {
        val p = PortalUrl.parse("http://example.com:8080/player_api.php?username=u1&password=p1")!!
        assertEquals("http://example.com:8080", p.base)
        assertEquals("u1", p.username); assertEquals("p1", p.password)
        assertTrue(p.hasPlayerApi)
    }

    @Test fun getPhpFullM3ULink() {
        val p = PortalUrl.parse("http://example.com/get.php?username=u2&password=p2&type=m3u_plus&output=ts")!!
        assertEquals("http://example.com", p.base)
        assertEquals("u2", p.username); assertEquals("p2", p.password)
        assertTrue(p.hasGetPhp)
    }

    @Test fun reverseProxyPathPreserved() {
        val p = PortalUrl.parse("http://panel.example.com/portal/")!!
        assertEquals("http://panel.example.com/portal", p.base)
    }

    @Test fun reverseProxyPathWithPlayerApi() {
        val p = PortalUrl.parse("http://panel.example.com/portal/player_api.php?username=a&password=b")!!
        assertEquals("http://panel.example.com/portal", p.base)
        assertEquals("a", p.username); assertEquals("b", p.password)
    }

    @Test fun urlEncodedPassword() {
        val p = PortalUrl.parse("http://example.com/get.php?username=u&password=p%40ss")!!
        assertEquals("p@ss", p.password)
    }

    @Test fun httpsKept() {
        val p = PortalUrl.parse("https://secure.example.tv")!!
        assertEquals("https://secure.example.tv", p.base)
    }

    @Test fun garbageRejected() {
        assertNull(PortalUrl.parse("not a url"))
        assertNull(PortalUrl.parse("ftp://example.com"))
        assertNull(PortalUrl.parse(""))
    }

    @Test fun plainM3uUrlKeepsPath() {
        // A non-Xtream M3U link: credentials absent, path is the file itself
        val p = PortalUrl.parse("http://provider.example/playlist.m3u")!!
        assertEquals("http://provider.example/playlist.m3u", p.base)
        assertNull(p.username)
    }
}

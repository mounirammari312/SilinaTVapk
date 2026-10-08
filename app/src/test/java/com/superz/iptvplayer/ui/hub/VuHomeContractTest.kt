package com.superz.iptvplayer.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * v1.6.0 — the home page's pure text contracts (reference-verbatim rules).
 */
class VuHomeContractTest {

    private val US = Locale.US

    // ── reloadedTimeAgo — Utils.getReloadedTimeAgo, line for line ──

    @Test
    fun `never synced shows nothing`() {
        assertEquals("", VuHomeContract.reloadedTimeAgo(1_000_000L, 0L, US))
    }

    @Test
    fun `just synced or clock skew shows 00mins ago`() {
        assertEquals("00mins ago", VuHomeContract.reloadedTimeAgo(1_000_000L, 1_000L, US))
        assertEquals("00mins ago", VuHomeContract.reloadedTimeAgo(1_000_000L, 2_000_000L, US))
    }

    @Test
    fun `under one hour shows zero padded minutes`() {
        // 5 minutes elapsed (now = lastSync*1000 + 300_000ms)
        assertEquals("05mins ago", VuHomeContract.reloadedTimeAgo(1_000_300_000L, 1_000_000L, US))
        // 59 minutes elapsed
        assertEquals("59mins ago", VuHomeContract.reloadedTimeAgo(1_003_540_000L, 1_000_000L, US))
    }

    @Test
    fun `one hour or more shows hours and minutes`() {
        // 1h 07m
        assertEquals("01hours 07mins ago", VuHomeContract.reloadedTimeAgo(1_004_020_000L, 1_000_000L, US))
        // 26h 5m
        assertEquals("26hours 05mins ago", VuHomeContract.reloadedTimeAgo(1_093_900_000L, 1_000_000L, US))
    }

    // ── homeExpirationValue — the ly_expiration pill's ladder ──

    @Test
    fun `xtream expiry formats as long month day year`() {
        // 2026-10-03 00:00 UTC → "October 03, 2026"
        assertEquals(
            "October 03, 2026",
            VuHomeContract.homeExpirationValue("XTREAM", 1_790_985_600L, "Unlimited", US)
        )
    }

    @Test
    fun `xtream zero expiry is blank and null is unlimited`() {
        assertEquals("", VuHomeContract.homeExpirationValue("XTREAM", 0L, "Unlimited", US))
        assertEquals("unlimited", VuHomeContract.homeExpirationValue("XTREAM", null, "Unlimited", US))
    }

    @Test
    fun `portal and file playlists show nothing or unlimited`() {
        assertEquals("", VuHomeContract.homeExpirationValue("PORTAL", 1L, "Unlimited", US))
        assertEquals("Unlimited", VuHomeContract.homeExpirationValue("M3U", null, "Unlimited", US))
        assertEquals("Unlimited", VuHomeContract.homeExpirationValue("BROWSER", 1L, "Unlimited", US))
    }

    // ── homeLoggedInValue — the ly_logged pill's identity ──

    @Test
    fun `portal and xc show the stored identity`() {
        assertEquals("00:1A:79:xx", VuHomeContract.homeLoggedInValue("PORTAL", "00:1A:79:xx", "My Portal"))
        assertEquals("john", VuHomeContract.homeLoggedInValue("XTREAM", "john", "Server One"))
        assertEquals("", VuHomeContract.homeLoggedInValue("XTREAM", null, "Server One"))
    }

    @Test
    fun `m3u and browser fall back to the playlist name`() {
        assertEquals("My List", VuHomeContract.homeLoggedInValue("M3U", null, "My List"))
        assertEquals("File X", VuHomeContract.homeLoggedInValue("BROWSER", null, "File X"))
    }

    // ── the account dialog's rows ──

    @Test
    fun `account dialog user row is empty for m3u`() {
        assertEquals("john", VuHomeContract.accountUserValue("XTREAM", "john"))
        assertEquals("00:1A:79:xx", VuHomeContract.accountUserValue("PORTAL", "00:1A:79:xx"))
        assertEquals("", VuHomeContract.accountUserValue("M3U", "ignored"))
        assertEquals("", VuHomeContract.accountUserValue("BROWSER", null))
    }

    @Test
    fun `account dialog expiry uses day slash month slash year`() {
        assertEquals(
            "03/10/2026",
            VuHomeContract.accountExpiryValue("XTREAM", 1_790_985_600L, US)
        )
        assertEquals("", VuHomeContract.accountExpiryValue("XTREAM", 0L, US))
        assertEquals("unlimited", VuHomeContract.accountExpiryValue("XTREAM", null, US))
        assertEquals("", VuHomeContract.accountExpiryValue("PORTAL", 1L, US))
        assertEquals("", VuHomeContract.accountExpiryValue("M3U", null, US))
    }
}

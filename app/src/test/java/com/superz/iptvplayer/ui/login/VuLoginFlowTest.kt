package com.superz.iptvplayer.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.11 — the login flow's contract, replicated from the reference
 * app's Java + layouts and locked here:
 *
 *  • the entry gate (v1.19.12: the hasPlaylists verdict only — the
 *    introDone pref is gone with the intro slider);
 *  • EditPortalActivity's type → field-visibility switch;
 *  • v1.19.12 — the language list is the two locales the app ships
 *    (English + Arabic; the reference's 17-entry list was cut).
 */
class VuLoginFlowTest {

    // ── entryRoute: MainActivity's startDestination ──

    @Test
    fun `existing user goes straight to accounts`() {
        assertEquals("accounts", VuLoginFlow.entryRoute(hasPlaylists = true))
    }

    @Test
    fun `brand new install lands directly on the method picker`() {
        // v1.19.12 — the intro slider (and every other startup page) is
        // GONE: a fresh install opens straight on the portal method grid,
        // already speaking the device's own language (attachBaseContext
        // follows the system locale when no preference is stored).
        assertEquals("addPortal", VuLoginFlow.entryRoute(hasPlaylists = false))
    }

    @Test
    fun `the gate never emits a startup or language route`() {
        // The gate NEVER emits 'intro' or 'language' — the intro route no
        // longer exists, and the language grid is only reachable by
        // explicit navigation from the Settings page, never as a landing
        // destination, so a relaunch can never loop back into it.
        listOf(true, false).forEach { has ->
            val route = VuLoginFlow.entryRoute(hasPlaylists = has)
            assertNotEquals("language", route)
            assertNotEquals("intro", route)
        }
    }

    // ── fieldsFor: EditPortalActivity.onCreate's switch ──

    @Test
    fun `xc shows username password and portal url`() {
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.XC)
        assertTrue(f.username && f.password && f.address && f.addressEnabled)
        assertEquals(false, f.browseButton)
        assertEquals(VuLoginFlow.Header.LOGIN_DETAILS, f.header)
        assertEquals(VuLoginFlow.Hint.USERNAME, f.usernameHint)
        assertEquals(VuLoginFlow.Hint.PORTAL_URL, f.addressHint)
    }

    @Test
    fun `m3u shows only the m3u url`() {
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.M3U)
        assertEquals(false, f.username)
        assertEquals(false, f.password)
        assertTrue(f.address && f.addressEnabled)
        assertEquals(false, f.browseButton)
        assertEquals(VuLoginFlow.Header.LOGIN_DETAILS, f.header)
        assertEquals(VuLoginFlow.Hint.M3U_URL, f.addressHint)
    }

    @Test
    fun `portal uses the mac hint and stalker header`() {
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.PORTAL)
        assertTrue(f.username && !f.password && f.address && f.addressEnabled)
        assertEquals(VuLoginFlow.Header.STALKER, f.header)
        assertEquals(VuLoginFlow.Hint.MAC_ADDRESS, f.usernameHint)
        assertEquals(VuLoginFlow.Hint.PORTAL_URL, f.addressHint)
    }

    @Test
    fun `browser disables the path and shows the browse button`() {
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.BROWSER)
        assertEquals(false, f.username)
        assertEquals(false, f.password)
        assertTrue(f.address)
        assertEquals(false, f.addressEnabled)   // et_address.setEnabled(false)
        assertTrue(f.browseButton)
        assertEquals(VuLoginFlow.Header.BROWSE_PLAYLIST, f.header)
        assertEquals(VuLoginFlow.Hint.M3U_FILE_PATH, f.addressHint)
    }

    // ── LANGUAGES: exactly the two locales the app ships (v1.19.12) ──

    @Test
    fun `exactly english and arabic in the settings grid`() {
        assertEquals(2, VuLoginFlow.LANGUAGES.size)
        assertEquals("ENGLISH (US)", VuLoginFlow.LANGUAGES.first().display)
        assertEquals("عربي (AR)", VuLoginFlow.LANGUAGES[1].display)
    }

    @Test
    fun `language tags are unique`() {
        assertEquals(VuLoginFlow.LANGUAGES.size, VuLoginFlow.LANGUAGES.map { it.tag }.toSet().size)
    }

    @Test
    fun `languageForTag falls back to english`() {
        assertEquals("en", VuLoginFlow.languageForTag(null).tag)
        assertEquals("en", VuLoginFlow.languageForTag("xx").tag)
        assertEquals("en", VuLoginFlow.languageForTag("nl").tag)
        assertEquals("ar", VuLoginFlow.languageForTag("ar").tag)
    }

    // ── v1.13.0 / v1.14.0 — CODE + QR portal types ──

    @Test
    fun `code login shows a single enabled address field for the code`() {
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.CODE)
        assertEquals(false, f.username)
        assertEquals(false, f.password)
        assertTrue(f.address && f.addressEnabled)
        assertEquals(false, f.browseButton)
        assertEquals(VuLoginFlow.Header.LOGIN_DETAILS, f.header)
        assertEquals(VuLoginFlow.Hint.CODE, f.addressHint)
    }

    @Test
    fun `qr login has no form fields at all`() {
        // v1.14.0 — the QR pill opens the bridge dialog directly; the
        // FormFields entry exists only to keep fieldsFor exhaustive.
        val f = VuLoginFlow.fieldsFor(VuLoginFlow.PortalType.QR)
        assertEquals(false, f.username)
        assertEquals(false, f.password)
        assertEquals(false, f.address)
        assertEquals(false, f.addressEnabled)
        assertEquals(false, f.browseButton)
        assertEquals(VuLoginFlow.Header.LOGIN_DETAILS, f.header)
    }

    @Test
    fun `portal type set is exactly the six method-grid buttons`() {
        // The v1.14.0 method grid: 2 rows × 3 columns =
        // m3u | code | xc  //  browse | qr | portal.
        assertEquals(
            setOf("M3U", "CODE", "XC", "BROWSER", "QR", "PORTAL"),
            VuLoginFlow.PortalType.entries.map { it.name }.toSet()
        )
    }
}

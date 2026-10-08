package com.superz.iptvplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7.0 — the "Active Subtitle" language ladder: the app's chosen language
 * first, the device locale second, English last, no duplicates, "und"
 * tracks accepted downstream via selectUndeterminedLanguage.
 */
class SubtitleActivationTest {

    @Test
    fun `app language leads the ladder`() {
        assertEquals(
            listOf("ar", "en"),
            SubtitleActivation.preferredSubtitleLanguages("ar", "en")
        )
    }

    @Test
    fun `device locale fills the first slot when app language is absent`() {
        assertEquals(
            listOf("fr", "en"),
            SubtitleActivation.preferredSubtitleLanguages(null, "fr")
        )
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(
            listOf("ar", "en"),
            SubtitleActivation.preferredSubtitleLanguages("ar", "ar")
        )
    }

    @Test
    fun `blank app language falls back to device then english`() {
        assertEquals(
            listOf("fr", "en"),
            SubtitleActivation.preferredSubtitleLanguages("", "fr")
        )
        assertEquals(
            listOf("en"),
            SubtitleActivation.preferredSubtitleLanguages(null, "_")
        )
    }

    @Test
    fun `english is always present as the universal fallback`() {
        assertTrue(
            SubtitleActivation.preferredSubtitleLanguages("de", "tr").contains("en")
        )
    }

    // ── v1.16.0 — the side-load fix: effectiveLanguages merge semantics ──
    // The v1.15.0 bug: the side-loaded .srt never rendered because the
    // general toggle (default OFF) disabled the text track TYPE and the
    // ladder overwrote the injected preferred language. The fix prepends
    // the side-loaded language to the ladder; these tests pin that.

    @Test
    fun `side-loaded language leads the merged ladder`() {
        assertEquals(
            listOf("fr", "ar", "en"),
            SubtitleActivation.effectiveLanguages("fr", "ar", "en")
        )
    }

    @Test
    fun `side-loaded language equals the app language collapses the duplicate`() {
        assertEquals(
            listOf("ar", "en"),
            SubtitleActivation.effectiveLanguages("ar", "ar", "en")
        )
    }

    @Test
    fun `case-insensitive duplicate collapse between side-load and ladder`() {
        assertEquals(
            listOf("EN", "ar"),
            SubtitleActivation.effectiveLanguages("EN", "ar", "en")
        )
    }

    @Test
    fun `null side-load returns the classic ladder untouched`() {
        assertEquals(
            listOf("ar", "en"),
            SubtitleActivation.effectiveLanguages(null, "ar", "en")
        )
        assertEquals(
            listOf("fr", "en"),
            SubtitleActivation.effectiveLanguages(null, "fr", "en")
        )
    }

    @Test
    fun `blank side-load is treated as absent`() {
        assertEquals(
            listOf("ar", "en"),
            SubtitleActivation.effectiveLanguages("", "ar", "en")
        )
    }

    @Test
    fun `side-load outside the ladder still wins`() {
        // The exact v1.15.0 failure shape: user picks Turkish while the
        // app/device ladder is Arabic→English. Turkish MUST lead now.
        assertEquals(
            listOf("tr", "ar", "en"),
            SubtitleActivation.effectiveLanguages("tr", "ar", "en")
        )
    }
}

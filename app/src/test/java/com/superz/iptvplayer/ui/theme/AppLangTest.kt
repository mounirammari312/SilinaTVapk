package com.superz.iptvplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * v2.1.3 — [AppLang.tagOfLocale]'s mapping (plain JVM): every Arabic
 * locale maps to "ar", everything else to "en" — the two locales the app
 * actually ships. Never null, never an exception.
 */
class AppLangTest {

    @Test
    fun arabicLocalesMapToArabic() {
        assertEquals("ar", AppLang.tagOfLocale(Locale("ar")))
        assertEquals("ar", AppLang.tagOfLocale(Locale("ar", "DZ")))
        assertEquals("ar", AppLang.tagOfLocale(Locale("ar", "SA")))
        assertEquals("ar", AppLang.tagOfLocale(Locale.forLanguageTag("ar-MA")))
    }

    @Test
    fun everythingElseMapsToEnglish() {
        assertEquals("en", AppLang.tagOfLocale(Locale("en")))
        assertEquals("en", AppLang.tagOfLocale(Locale("en", "US")))
        assertEquals("en", AppLang.tagOfLocale(Locale.FRENCH))
        assertEquals("en", AppLang.tagOfLocale(Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun nullAndUnknownLocalesAreEnglish() {
        // the app's default — never a crash, never a null tag.
        assertEquals("en", AppLang.tagOfLocale(null))
        assertEquals("en", AppLang.tagOfLocale(Locale("")))
    }
}

package com.superz.iptvplayer.ui.theme

import android.content.Context
import java.util.Locale

/**
 * v2.1.3 — THE APP'S ACTIVE LANGUAGE (one source of truth).
 *
 * The app speaks exactly two locales (values/ English + values-ar/ Arabic).
 * Which one is LIVE is decided by:
 *
 *   1. the stored per-app preference ("vu_prefs"/"lang") — written by the
 *      Settings language page and applied by MainActivity.attachBaseContext;
 *   2. otherwise the DEVICE locale the app opened with (v1.19.12 first-run
 *      behavior: Arabic device → Arabic, anything else → English).
 *
 * WHY this object exists — the v2.1.3 bug reports:
 *
 *  • THE REVERSED SETTINGS CHECKMARK: on first run nothing is stored, so
 *    the language page received `null` and fell back to DEFAULT_TAG ("en")
 *    — on an Arabic device the app RAN Arabic while the grid showed the
 *    checkmark on English, the exact opposite of what the app detected
 *    (user: "اجد اللغة عكس لغة التي اكتشفها التطبيق"). The page now
 *    receives [currentTag], which resolves the live configuration locale
 *    when no preference is stored.
 *
 *  • THE SINGLE-LANGUAGE WHATSAPP MESSAGE: the premium contact message
 *    must follow the APP's language (Arabic for Arabic users, English for
 *    English users — user directive), not `Locale.getDefault()` (which is
 *    the DEVICE locale and stays Arabic even after the app switches to
 *    English in Settings).
 *
 *  • THE ARABIC DATES ON ENGLISH UI: `SimpleDateFormat(…, Locale.getDefault())`
 *    rendered Arabic month names while the app ran English for the same
 *    reason — see [OriaDateFmt].
 *
 * Pure JVM (no Compose) so the mapping is unit-tested (AppLangTest).
 */
object AppLang {

    const val ARABIC = "ar"
    const val ENGLISH = "en"

    private const val PREFS_NAME = "vu_prefs"
    private const val PREF_KEY = "lang"

    /**
     * The app's LIVE language tag — the stored preference when present,
     * else the locale the configuration is actually running (the activity
     * context's resources carry the attachBaseContext override, and with
     * nothing stored that IS the device locale the app detected).
     */
    fun currentTag(context: Context): String {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)
        if (stored == ARABIC || stored == ENGLISH) return stored
        return tagOfLocale(context.resources.configuration.locales[0])
    }

    /**
     * Any Arabic locale (ar, ar-DZ, ar-SA…) maps to "ar"; everything else
     * maps to "en" — the two locales the app actually ships. Never null:
     * a null/unknown locale is simply English (the app's default).
     */
    fun tagOfLocale(locale: Locale?): String =
        if (locale?.language?.startsWith(ARABIC) == true) ARABIC else ENGLISH
}

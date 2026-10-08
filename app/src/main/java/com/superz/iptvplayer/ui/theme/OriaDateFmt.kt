package com.superz.iptvplayer.ui.theme

import java.util.Calendar
import java.util.Locale

/**
 * v2.1.3 — DATES THAT FOLLOW THE APP'S LANGUAGE (user report: "عندما تكون
 * لغة المستخدم انجليزية يضهر تاريخ في الحساب باللغة العربية" — with the app
 * running English, the account dates still showed Arabic month names).
 *
 * THE ROOT CAUSE: every subscription date was formatted with
 * `SimpleDateFormat("d MMM yyyy", Locale.getDefault())` — but the app
 * switches locale through the ACTIVITY configuration (attachBaseContext →
 * createConfigurationContext), which never touches the process-wide
 * `Locale.getDefault()`. An Arabic device kept feeding the Arabic locale
 * into the formatter even while the whole UI ran English — the exact
 * reversed-language class of bug as the settings checkmark and the
 * WhatsApp message (see [AppLang]).
 *
 * THE FIX — deterministic and ICU-independent: the month names come from
 * the app's OWN localized resources (R.array.oria_months: values/ English,
 * values-ar Arabic), so the date ALWAYS speaks the UI's language, and the
 * digits stay Latin in BOTH languages (the professional subscription-card
 * convention — "12 نوفمبر 2026", never ICU's Arabic-Indic "١٢ نوفمبر ٢٠٢٦"
 * whose glyph shaping splits RTL text runs mid-number).
 *
 * Pure JVM — unit-tested in OriaDateFmtTest.
 */
object OriaDateFmt {

    /** The localized month names R.array.oria_months carries (1-based order). */
    const val MONTH_COUNT = 12

    /**
     * "d MMMM yyyy" with [months] (January..December order, as resolved from
     * R.array.oria_months): "12 November 2026" / "12 نوفمبر 2026".
     * Calendar field math runs on [Locale.US] so the extraction itself is
     * locale-stable — only the month NAME is localized, by the caller's
     * resources, i.e. by the app's live language.
     */
    fun format(epochMillis: Long, months: List<String>): String {
        val cal = Calendar.getInstance(Locale.US)
        cal.timeInMillis = epochMillis
        val month = months.getOrNull(cal.get(Calendar.MONTH)) ?: ""
        return "${cal.get(Calendar.DAY_OF_MONTH)} $month ${cal.get(Calendar.YEAR)}"
    }

    /** True when the array can serve [format] (12 month names). */
    fun monthsValid(months: List<String>): Boolean = months.size == MONTH_COUNT
}

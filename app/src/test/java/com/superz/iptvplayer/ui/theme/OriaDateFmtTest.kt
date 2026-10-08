package com.superz.iptvplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * v2.1.3 — [OriaDateFmt]'s contract (plain JVM).
 *
 * The dates must follow the APP's language: the month names arrive from
 * R.array.oria_months (the UI's own resources), the digits stay Latin in
 * BOTH languages, and the field math is locale-stable (Locale.US calendar
 * extraction — only the month NAME is localized, by the caller).
 */
class OriaDateFmtTest {

    private val en = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    private val ar = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    /** 2026-11-12 00:00 UTC as epoch millis (fixed reference). */
    private val nov122026: Long = utc(2026, Calendar.NOVEMBER, 12)

    private fun utc(y: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.clear()
        cal.set(y, month, day, 0, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun englishMonthsRenderInEnglish() {
        assertEquals("12 November 2026", OriaDateFmt.format(nov122026, en))
    }

    @Test
    fun arabicMonthsRenderInArabicWithLatinDigits() {
        // THE v2.1.3 fix: the Arabic UI gets Arabic month names while the
        // digits stay Latin (never ICU's Arabic-Indic ١٢ نوفمبر ٢٠٢٦).
        assertEquals("12 نوفمبر 2026", OriaDateFmt.format(nov122026, ar))
    }

    @Test
    fun theSameInstantSpeaksWhicheverLanguageItIsGiven() {
        // the two renderings differ ONLY in the month word — same digits,
        // same order: the formatter itself is language-neutral.
        assertEquals(
            OriaDateFmt.format(nov122026, en).replace("November", "نوفمبر"),
            OriaDateFmt.format(nov122026, ar)
        )
    }

    @Test
    fun monthBoundariesAreCorrect() {
        assertEquals("1 January 2027", OriaDateFmt.format(utc(2027, Calendar.JANUARY, 1), en))
        assertEquals("31 October 2026", OriaDateFmt.format(utc(2026, Calendar.OCTOBER, 31), en))
        assertEquals("29 February 2028", OriaDateFmt.format(utc(2028, Calendar.FEBRUARY, 29), en))
    }

    @Test
    fun shortMonthArraysNeverCrash() {
        // a malformed resource (11 months — December missing) renders an
        // empty month word for a DECEMBER date, never an exception — dates
        // degrade gracefully.
        assertEquals("12  2026", OriaDateFmt.format(utc(2026, Calendar.DECEMBER, 12), en.dropLast(1)))
        // and an intact array still renders December correctly
        assertEquals("12 December 2026", OriaDateFmt.format(utc(2026, Calendar.DECEMBER, 12), en))
    }

    @Test
    fun monthsValidGuardsTheArrayShape() {
        assertTrue(OriaDateFmt.monthsValid(en))
        assertTrue(OriaDateFmt.monthsValid(ar))
        assertFalse(OriaDateFmt.monthsValid(en.dropLast(1)))
        assertFalse(OriaDateFmt.monthsValid(emptyList()))
    }
}

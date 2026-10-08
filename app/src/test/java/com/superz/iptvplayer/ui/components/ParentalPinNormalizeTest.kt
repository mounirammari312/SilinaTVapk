package com.superz.iptvplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.19.14 — the parental-PIN digit-normalization contract.
 *
 * An Arabic keyboard's digit row produces ARABIC-INDIC digits (٠٠٠٠,
 * U+0660–U+0669); on the gate's masked field they render as four dots
 * exactly like "0000", yet the OLD raw comparison rejected them — the
 * user typed four zeros and the adult section never opened ("أدخل 0000
 * أربع أصفار ولا تفتح"). Persian layouts add ۰–۹ (U+06F0–U+06F9).
 *
 * ParentalControl.verify/normalizePin fold BOTH sides of the comparison
 * (the entered string AND the stored value — a PIN saved from an Arabic
 * keyboard sits in prefs as Arabic-Indic) onto ASCII. These tests pin
 * the folding rules themselves; verify() is pin() + the entered side's
 * normalizePin, both exercised here.
 */
class ParentalPinNormalizeTest {

    // ── The reported bug, verbatim ──────────────────────────────────

    @Test
    fun `arabic-indic zeros are the default pin`() {
        // What an Arabic keyboard sends for "0000":
        assertEquals("0000", ParentalControl.normalizePin("\u0660\u0660\u0660\u0660"))
    }

    @Test
    fun `verify semantics - arabic entry matches ascii stored`() {
        // verify() = normalizePin(entered) == normalizePin(stored):
        val storedAscii = ParentalControl.normalizePin("0000")   // pref default
        val enteredArabic = ParentalControl.normalizePin("\u0660\u0660\u0660\u0660")
        assertEquals(storedAscii, enteredArabic)
    }

    @Test
    fun `verify semantics - pin stored as arabic still matches ascii entry`() {
        // A PIN SAVED from an Arabic keyboard (legacy store):
        val storedArabic = ParentalControl.normalizePin("\u0661\u0662\u0663\u0664") // ١٢٣٤
        val enteredAscii = ParentalControl.normalizePin("1234")
        assertEquals(storedArabic, enteredAscii)
    }

    // ── The full folding table ──────────────────────────────────────

    @Test
    fun `all arabic-indic digits fold onto ascii`() {
        // ٠١٢٣٤٥٦٧٨٩ -> 0123456789
        assertEquals(
            "0123456789",
            ParentalControl.normalizePin("\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669")
        )
    }

    @Test
    fun `all extended persian digits fold onto ascii`() {
        // ۰۱۲۳۴۵۶۷۸۹ -> 0123456789
        assertEquals(
            "0123456789",
            ParentalControl.normalizePin("\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9")
        )
    }

    @Test
    fun `mixed glyph variants fold`() {
        // ٠٩ (Arabic) + "12" (ASCII) -> "0912" — glyph variants of the SAME
        // digit fold to identical ASCII on both sides of the comparison.
        assertEquals("0912", ParentalControl.normalizePin("\u0660\u0669" + "12"))
        // Same digit typed with BOTH glyphs in one entry: ٩ and 9 both -> "9".
        assertEquals("99", ParentalControl.normalizePin("\u0669" + "9"))
    }

    @Test
    fun `ascii passes through unchanged`() {
        assertEquals("0000", ParentalControl.normalizePin("0000"))
        assertEquals("9021", ParentalControl.normalizePin("9021"))
    }

    // ── Invisible IME baggage ───────────────────────────────────────

    @Test
    fun `whitespace and bidi marks are dropped`() {
        // RTL mark + spaces + LTR mark around the digits:
        assertEquals("0000", ParentalControl.normalizePin("\u200F 0000 \u200E"))
    }

    @Test
    fun `zero-width joiners and BOM are dropped`() {
        assertEquals("1111", ParentalControl.normalizePin("\u200B\u0661\u200C1\u200D1\uFEFF1"))
    }

    // ── A wrong PIN must STILL be wrong ────────────────────────────

    @Test
    fun `different digits stay different`() {
        assertNotEquals(
            ParentalControl.normalizePin("0000"),
            ParentalControl.normalizePin("\u0660\u0660\u0660\u0661") // ٠٠٠١
        )
    }

    @Test
    fun `letters pass through so alphanumeric pins work`() {
        assertEquals("ab12", ParentalControl.normalizePin("ab12"))
        // An Arabic-digit pin next to letters folds only the digits:
        assertEquals("ab12", ParentalControl.normalizePin("ab\u0661\u0662"))
    }

    @Test
    fun `empty and junk-only entries normalize to empty`() {
        assertEquals("", ParentalControl.normalizePin(""))
        assertEquals("", ParentalControl.normalizePin("\u200F\u200E "))
    }
}

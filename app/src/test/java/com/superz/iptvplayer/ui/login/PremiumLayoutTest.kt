package com.superz.iptvplayer.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.3 — the premium page's pure layout math. The page NEVER scrolls
 * (the user's directive: "لا يجب أن تكون بالتمرير وكأنه موقع"), so every
 * sizing decision has to be provable on paper:
 *
 *  • two panes from 640dp up, one stacked column below;
 *  • plan cards wrap to more rows (≤ 4 per row) instead of overflowing;
 *  • a row of cards ALWAYS fits the available width.
 */
class PremiumLayoutTest {

    @Test
    fun wideThreshold() {
        assertTrue(PremiumLayout.isWide(640f))
        assertTrue(PremiumLayout.isWide(1280f))
        assertEquals(false, PremiumLayout.isWide(639.9f))
        assertEquals(false, PremiumLayout.isWide(360f))   // portrait phone
    }

    @Test
    fun rowsWrapAtFourPerRow() {
        assertEquals(1, PremiumLayout.rows(1, 1))
        assertEquals(1, PremiumLayout.rows(4, 4))
        assertEquals(2, PremiumLayout.rows(5, 4))
        assertEquals(2, PremiumLayout.rows(8, 4))
        assertEquals(3, PremiumLayout.rows(9, 4))
    }

    @Test
    fun perRowFitsTheAvailableWidth() {
        // portrait phone: 4 plans fit one row of 73dp cards
        assertEquals(4, PremiumLayout.perRow(328f, 4))
        // landscape phone's start pane (~200dp): only 2 cards fit
        assertEquals(2, PremiumLayout.perRow(208f, 4))
        // never more than 4 per row, whatever the width
        assertEquals(4, PremiumLayout.perRow(1200f, 8))
        // a single plan never divides
        assertEquals(1, PremiumLayout.perRow(208f, 1))
    }

    @Test
    fun planRowAlwaysFitsItsWidth() {
        // THE invariant: for any realistic available width and plan count,
        // the row's total width (cards + gaps) does not exceed the space.
        val widths = (200..1200 step 50).map { it.toFloat() }
        val counts = 1..8
        for (w in widths) {
            for (n in counts) {
                val perRow = PremiumLayout.perRow(w, n)
                val cardW = PremiumLayout.planCardW(w, perRow)
                val row = perRow * cardW + (perRow - 1) * PremiumLayout.PLAN_GAP
                assertTrue(
                    "row $row must fit $w (n=$n perRow=$perRow cardW=$cardW)",
                    row <= w + 0.01f
                )
            }
        }
    }

    @Test
    fun cardGeometry() {
        // one row of 4 on a portrait phone → 73dp cards (aspect-fair thirds)
        assertEquals(73f, PremiumLayout.planCardW(328f, 4), 0.01f)
        // a lone plan on a wide pane clamps at the 170dp max
        assertEquals(170f, PremiumLayout.planCardW(900f, 1), 0.01f)
        // v2.1.1 — slimmer cards (was 118/100): the freed height hosts the
        // benefits deck with the page still scroll-free.
        assertEquals(88f, PremiumLayout.planCardH(1))
        assertEquals(84f, PremiumLayout.planCardH(2))
    }

    // ══════════ v2.1.1 — the benefits-deck wrapping math ══════════

    @Test
    fun deckPerRowThresholds() {
        // the landscape start pane of a wide phone (~502dp) — all five
        // feature chips ride ONE golden strip
        assertEquals(5, PremiumLayout.deckPerRow(502f))
        assertEquals(5, PremiumLayout.deckPerRow(460f))
        // just under → wrap to 3 per row
        assertEquals(3, PremiumLayout.deckPerRow(459.9f))
        // portrait phone (~328dp) → 3 + 2
        assertEquals(3, PremiumLayout.deckPerRow(328f))
        assertEquals(3, PremiumLayout.deckPerRow(240f))
        // very narrow → pairs
        assertEquals(2, PremiumLayout.deckPerRow(239.9f))
        assertEquals(2, PremiumLayout.deckPerRow(180f))
    }

    @Test
    fun deckPerRowIsMonotoneAndBounded() {
        // THE invariant: wider available width never wraps to FEWER chips
        // per row, and the count always stays within 2..5.
        var previous = PremiumLayout.deckPerRow(100f)
        val widths = (100..1200 step 20).map { it.toFloat() }
        for (w in widths) {
            val perRow = PremiumLayout.deckPerRow(w)
            assertTrue("perRow $perRow out of bounds at w=$w", perRow in 2..5)
            assertTrue(
                "perRow went DOWN as width grew (w=$w: $perRow < $previous)",
                perRow >= previous
            )
            previous = perRow
        }
    }

    // ══════════ v2.1.2 — the contact CTA above the login card ══════════

    @Test
    fun contactCtaGeometry() {
        // "مع تصغير حجمه قليلا": slimmer than the retired 40dp green pill,
        // a hairline gap above the login card, 92% of the login column.
        assertTrue(PremiumLayout.CONTACT_CTA_H < 40f)
        assertTrue(PremiumLayout.CONTACT_CTA_GAP in 6f..14f)
        assertEquals(0.92f, PremiumLayout.CONTACT_CTA_W_FRAC, 0.001f)
    }

    @Test
    fun loginStackFitsEveryRenderScale() {
        // THE paper proof — the user's 2400×1080 landscape TV (sdp 1.8×):
        // pane 540 − (46+8)×1.8 = 442.8dp; login card ≈ 175sdp × 1.8 = 315dp.
        assertTrue(PremiumLayout.loginStackFits(442.8f, 315f, scale = 1.8f))
        // a 420dpi phone (sw 411 → sdp 1.3×): pane 411 − 59.8 − 10.4 = 340.8dp;
        // login card ≈ 175sdp × 1.3 = 227.5dp.
        assertTrue(PremiumLayout.loginStackFits(340.8f, 227.5f, scale = 1.3f))
        // a plain-dp phone (scale 1.0), generous slack.
        assertTrue(PremiumLayout.loginStackFits(360f, 260f))
        // a pane shorter than the stack honestly reports it.
        assertEquals(false, PremiumLayout.loginStackFits(300f, 315f))
    }

    @Test
    fun theCardSinksHalfTheCtaGroup() {
        // "نزل بطاقة التسجيل الى اسفل قليلا": the centered CTA+card group
        // sinks the login card by exactly HALF the CTA block — no more.
        val sink = (PremiumLayout.CONTACT_CTA_H + PremiumLayout.CONTACT_CTA_GAP) / 2f
        assertEquals(22f, sink, 0.01f)
        assertTrue("the card must sink by a little, not a lot", sink < 30f)
    }
}

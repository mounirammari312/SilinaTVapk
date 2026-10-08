package com.superz.iptvplayer.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.7 — the AddPortal option-grid geometry, provable on paper (the
 * PremiumLayoutTest pattern). This test class exists because v2.0.6
 * shipped an overlap: the count-aware top row placed the xtream pill at
 * `sideMargin + row1W + colGap * (count - 1)`, which is only correct for
 * TWO pills — with all three visible, xtream sat a full column left, ON
 * TOP of the golden 6-digit-code pill, and since both faces are
 * translucent the user saw ONE jumbled pill reading "Login With Xtream
 * Codes API" (screenshot 2026-10-07, Screenshot_20261007_015500.jpg).
 */
class VuPortalGridTest {

    private val margin = 10f
    private val gap = 20f

    // THE regression: with three pills the xtream pill must clear the
    // code pill by a full gap, not sit inside it.
    @Test
    fun threePillsNeverOverlap() {
        // v2.0.6's actual landscape-phone numbers (800dp screen):
        // xtream started at 50 + row1W instead of 50 + 2*row1W, covering
        // ~92% of the golden code pill (row1W - gap of its width).
        val sw = 800f
        val w = VuPortalGrid.rowW(sw, margin, gap, 3)
        val xs = VuPortalGrid.rowX(sw, margin, gap, 3)
        assertEquals(3, xs.size)
        for (i in 0 until 2) {
            val clearance = xs[i + 1] - (xs[i] + w)
            assertTrue(
                "pill $i clearance $clearance must be the full gap $gap",
                clearance >= gap - 0.01f
            )
        }
    }

    @Test
    fun noOverlapAndFlushEdgesForEveryWidthAndCount() {
        // THE invariant: for any realistic screen width and pill count,
        // neighbours never overlap (a full gap separates them) and the
        // row is flush with BOTH side margins — no overflow, no hole.
        val widths = (320..1920 step 64).map { it.toFloat() }
        for (sw in widths) {
            for (count in 1..3) {
                val xs = VuPortalGrid.rowX(sw, margin, gap, count)
                val w = VuPortalGrid.rowW(sw, margin, gap, count)
                for (i in 0 until xs.size - 1) {
                    assertTrue(
                        "screenW=$sw count=$count pill $i overlaps pill ${i + 1}",
                        xs[i + 1] >= xs[i] + w + gap - 0.01f
                    )
                }
                assertEquals(
                    "screenW=$sw count=$count first pill must sit on the left margin",
                    margin, xs.first(), 0.01f
                )
                assertEquals(
                    "screenW=$sw count=$count last pill must end on the right margin",
                    sw - margin, xs.last() + w, 0.01f
                )
            }
        }
    }

    @Test
    fun threePillsReproduceTheV205Thirds() {
        // count = 3 must equal the v1.14.0/v2.0.5 col1/col2/col3/colW
        // formulas byte-for-byte, so the fixed build is pixel-identical
        // to v2.0.5 whenever the code pill is visible.
        val sw = 960f   // TV-ish width
        val colW = (sw - margin * 2 - gap * 2) / 3
        val w = VuPortalGrid.rowW(sw, margin, gap, 3)
        val xs = VuPortalGrid.rowX(sw, margin, gap, 3)
        assertEquals(colW, w, 0.0001f)
        assertEquals(margin, xs[0], 0.0001f)                       // col1
        assertEquals(margin + colW + gap, xs[1], 0.0001f)          // col2
        assertEquals(margin + (colW + gap) * 2, xs[2], 0.0001f)    // col3
    }

    @Test
    fun twoEqualHalvesWhenTheCodePillIsHidden() {
        // the panel hides the code pill → m3u | xtream re-flow to halves
        val sw = 800f
        val w = VuPortalGrid.rowW(sw, margin, gap, 2)
        val xs = VuPortalGrid.rowX(sw, margin, gap, 2)
        assertEquals(2, xs.size)
        assertEquals((sw - margin * 2 - gap) / 2, w, 0.0001f)
        assertEquals(margin, xs[0], 0.0001f)
        assertEquals(margin + w + gap, xs[1], 0.0001f)
        assertEquals(sw - margin, xs[1] + w, 0.01f)
    }

    @Test
    fun singlePillSpansBetweenTheMargins() {
        val sw = 600f
        val w = VuPortalGrid.rowW(sw, margin, gap, 1)
        val xs = VuPortalGrid.rowX(sw, margin, gap, 1)
        assertEquals(1, xs.size)
        assertEquals(sw - margin * 2, w, 0.0001f)
        assertEquals(margin, xs[0], 0.0001f)
        assertEquals(sw - margin, xs[0] + w, 0.0001f)
    }
}

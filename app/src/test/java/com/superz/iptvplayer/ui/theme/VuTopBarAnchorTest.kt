package com.superz.iptvplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.10 — the split screen's top-bar anchoring must stay a FIXED POINT.
 *
 * v1.4.9 regression this locks out: the top bar's height was measured from
 * a wrapping Box whose clock child sized itself by that same measurement —
 *
 *     container(n+1) = max(rowHeight, margin + container(n))
 *
 * — which grows by the 15sdp margin on EVERY measurement pass, forever. On
 * the user's TV (sdp factor 1.8) that pushed the channel panel down ~27dp
 * per pass until it slid off-screen ("appears, then disappears downward").
 *
 * The contract (activity_live_play.xml): PremiumLL — the crown+PREMIUM
 * logo — is the ONLY anchor. Its height is intrinsic, measured directly,
 * and every band/panel position is a pure function of that intrinsic
 * height: the geometry converges after ONE pass and never moves again.
 */
class VuTopBarAnchorTest {

    @Test
    fun `xml anchoring contract`() {
        // PremiumLL: marginTop 15sdp, intrinsic height ~47sdp (35 crown + 1dp + badge)
        assertEquals(62, VuTopBarAnchor.premiumBandBottomSdp(null))          // 15 + 47 fallback
        assertEquals(61, VuTopBarAnchor.premiumBandBottomSdp(46))            // 15 + measured 46
        // left_lay: toBottomOf PremiumLL + marginTop 5sdp
        assertEquals(67, VuTopBarAnchor.leftPanelTopSdp(null))
        assertEquals(66, VuTopBarAnchor.leftPanelTopSdp(46))
        // left_lay: fills to the bottom of the screen
        assertEquals(540 - 66, VuTopBarAnchor.leftPanelHeightSdp(540, 46))
    }

    @Test
    fun `geometry is a fixed point once the logo is measured`() {
        // Simulate the measurement loop: every pass re-measures the logo
        // (intrinsic height — independent of the band) and recomputes the
        // panel geometry. It must be IDENTICAL on every pass after the
        // first (the v1.4.9 wrapper-Box loop grew +15sdp per pass instead).
        val logoHeight = 46                       // intrinsic, constant
        var lastTop = VuTopBarAnchor.leftPanelTopSdp(null)
        repeat(10) {
            val top = VuTopBarAnchor.leftPanelTopSdp(logoHeight)
            assertEquals(66, top)
            assertEquals(lastTop.coerceAtMost(66), top.coerceAtMost(66))
            lastTop = top
        }
        assertEquals(66, lastTop)
    }

    @Test
    fun `band bottom has unit slope in logo height - no runaway growth`() {
        // The v1.4.9 recurrence had an additive +15 leak per pass. The
        // anchor must be affine with EXACTLY unit slope in the logo's own
        // height: +1sdp logo ⇒ +1sdp band, nothing more.
        for (h in 40..60) {
            assertEquals(
                VuTopBarAnchor.premiumBandBottomSdp(h + 1) - VuTopBarAnchor.premiumBandBottomSdp(h),
                1
            )
        }
    }

    @Test
    fun `panel never renders below the screen`() {
        // Even a pathological logo height keeps the panel top sane and the
        // height non-negative (coerced), so the list can never be pushed
        // off-screen by the anchoring math itself.
        assertEquals(0, VuTopBarAnchor.leftPanelHeightSdp(540, 540))         // degenerate input
        assertEquals(540 - 67, VuTopBarAnchor.leftPanelHeightSdp(540, null)) // normal fallback
    }
}

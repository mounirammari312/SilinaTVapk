package com.superz.iptvplayer.ui.loading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.2 — the loading page's paper height budget. The page NEVER
 * scrolls, and the error actions (retry / back to accounts) must ALWAYS
 * render inside the viewport — the v2.1.1 vertical ring stack pushed
 * them off a ~411dp landscape viewport (the user's “الزر… لا يضهر يكون
 * متخفي” report).
 */
class LoadingLayoutTest {

    @Test
    fun worstCaseFitsEveryRealViewport() {
        // THE invariant: the FULL worst case (app name + error + BOTH
        // buttons) fits the shortest landscape phone viewport…
        assertTrue(LoadingLayout.fits(411f, appName = true, error = true))
        // …the user's 2400×1080 TV (540dp tall in landscape)…
        assertTrue(LoadingLayout.fits(540f, appName = true, error = true))
        // …and a compact 640dp-tall portrait phone with huge slack.
        assertTrue(LoadingLayout.fits(640f, appName = true, error = true))
        // an honest refusal for a viewport that genuinely cannot host it
        assertEquals(false, LoadingLayout.fits(200f, appName = true, error = true))
    }

    @Test
    fun theOldRingStackClippedTheRetryRow() {
        // regression reference — the retired v2.1.1 vertical stack:
        // 84 logo + 8 + 18 name + 10 + 120 ring + 22 + 8 dots + 16 status
        // + 18 + 14 account + 14 + 12 error + 18 + 40 buttons = 402dp
        // NOMINAL — already 98% of a 411dp viewport BEFORE real text
        // metrics / font scale, so the centered column overtopped the
        // screen and the error actions clipped off (the user's report).
        val oldStack =
            84f + 8f + 18f + 10f + 120f + 22f + 8f + 16f + 18f + 14f + 14f + 12f + 18f + 40f
        assertEquals(402f, oldStack, 0.01f)
        assertTrue("old stack consumed >= 97% of the shortest viewport", oldStack / 411f >= 0.97f)
        val now = LoadingLayout.worstCaseH(appName = true, error = true)
        assertTrue("new stack $now must be dramatically slimmer", now < oldStack - 100f)
        assertTrue("new stack leaves real slack on the shortest viewport", now / 411f <= 0.65f)
    }

    @Test
    fun geometryContract() {
        // the progress row is the tallest line under the mark and hosts
        // the 8dp bar; the dots are smaller; the logo shrank 84 → 56.
        assertTrue(LoadingLayout.PROGRESS_ROW_H >= 8f)
        assertTrue(LoadingLayout.DOTS_H < LoadingLayout.PROGRESS_ROW_H)
        assertEquals(56f, LoadingLayout.LOGO_H)
        // every block separated by the same air — the compact rhythm
        assertEquals(10f, LoadingLayout.GAP)
    }

    @Test
    fun worstCaseGrowsOnlyWithItsBlocks() {
        // adding the app-name line costs NAME_H + one gap; adding the
        // error state costs the error line + the action row + two gaps.
        val base = LoadingLayout.worstCaseH(appName = false, error = false)
        val withName = LoadingLayout.worstCaseH(appName = true, error = false)
        val withError = LoadingLayout.worstCaseH(appName = false, error = true)
        assertEquals(LoadingLayout.NAME_H + LoadingLayout.GAP, withName - base, 0.01f)
        assertEquals(
            LoadingLayout.ERROR_H + LoadingLayout.ACTIONS_H + 2 * LoadingLayout.GAP,
            withError - base,
            0.01f
        )
    }
}

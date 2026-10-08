package com.superz.iptvplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.3 — the ad card's height budget (pure math; the fix for the user's
 * "زر زيارة دائماً متخفي بسبب الحجم" report: a 16:9 image + header + body
 * + CTA used to overflow the screen and push the CTA below the fold).
 *
 * The contract: on EVERY screen ≥ 200dp tall — portrait phones, landscape
 * phones, TVs — the whole card (header + media + body cap + CTA + fixed
 * spacings + margins) fits inside the screen height, so the gold CTA pill
 * is always fully visible.
 */
class AnnouncementFitTest {

    private val headerH = 46f
    private val spacings = 44f
    private val margin = 16f

    @Test
    fun ctaAlwaysFitsOnEveryRealisticScreen() {
        // portrait phones, landscape phones, short TV overscan, tall tablets
        val heights = listOf(200f, 240f, 320f, 360f, 400f, 480f, 540f, 640f, 720f, 800f, 900f)
        val widths = listOf(320f, 360f, 412f, 480f, 640f, 800f, 960f, 1280f)
        for (h in heights) {
            for (w in widths) {
                for (hasImage in listOf(true, false)) {
                    for (hasBody in listOf(true, false)) {
                        val fit = AnnouncementFit.fit(w, h, hasImage, hasBody)
                        val total = margin + headerH + spacings +
                            fit.mediaH + fit.bodyMaxH + AnnouncementFit.CTA_H
                        assertTrue(
                            "card $total must fit screen $h (w=$w img=$hasImage body=$hasBody)",
                            total <= h + 0.01f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun mediaNeverExceedsAThirdOfTheScreen() {
        // landscape phone: the 16:9 aspect would demand ~half the height —
        // the budget caps it at 32% so the CTA keeps its place.
        val fit = AnnouncementFit.fit(screenW = 780f, screenH = 360f, hasImage = true, hasBody = true)
        assertTrue(fit.mediaH <= 360f * 0.32f + 0.01f)
        // and when there IS room (portrait), the aspect still applies
        val tall = AnnouncementFit.fit(screenW = 360f, screenH = 800f, hasImage = true, hasBody = true)
        assertTrue(tall.mediaH <= (360f - margin) * 9f / 16f + 0.01f)
    }

    @Test
    fun textOnlyCardHasNoMedia() {
        val fit = AnnouncementFit.fit(780f, 360f, hasImage = false, hasBody = true)
        assertEquals(0f, fit.mediaH)
        // no body either → a minimal badge+title+CTA card
        val bare = AnnouncementFit.fit(780f, 360f, hasImage = false, hasBody = false)
        assertEquals(0f, bare.mediaH)
        assertEquals(0f, bare.bodyMaxH)
    }

    @Test
    fun bodyCapShrinksOnShortScreens() {
        // a landscape phone body cap is ~65dp, a tall phone gets 18% of its
        // height (144dp on an 800dp screen — the 200dp cap binds only on
        // screens taller than ~1.1k dp)
        val short = AnnouncementFit.fit(780f, 360f, true, true)
        val tall = AnnouncementFit.fit(360f, 800f, true, true)
        assertTrue(short.bodyMaxH < tall.bodyMaxH)
        assertEquals(144f, tall.bodyMaxH, 0.01f)
    }

    @Test
    fun cardWidthCappedAt420() {
        // TV widths produce the same budget as a 436dp+ screen: the card
        // never grows past 420dp wide.
        val tv = AnnouncementFit.fit(1280f, 720f, true, true)
        val wide = AnnouncementFit.fit(900f, 720f, true, true)
        assertEquals(tv.mediaH, wide.mediaH, 0.01f)
    }
}

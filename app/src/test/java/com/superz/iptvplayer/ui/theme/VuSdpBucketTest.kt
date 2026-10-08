package com.superz.iptvplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.9 — the sdp dimension layer must reproduce the reference app's
 * intuit-sdp bucket math EXACTLY (verified against the decompiled
 * dimens.xml of every values-swXXXdp bucket):
 *
 *   bucket    _35sdp   _9sdp    factor
 *   default     35dp    9.0dp    1.0   (sw < 330)
 *   sw330     38.5dp    9.9dp    1.1
 *   sw360       42dp   10.8dp    1.2
 *   sw540       63dp   16.2dp    1.8   ← the user's TV (2400×1080, sw=540)
 *   sw600       70dp     18dp    2.0
 *   sw720       84dp   21.6dp    2.4
 *   sw1080     126dp   32.4dp    3.6   (cap)
 *
 * Bucket selection floors to the nearest 30dp step (values-sw540dp applies
 * to sw 540..569) and caps at sw1080dp.
 */
class VuSdpBucketTest {

    @Test
    fun `default bucket below sw330 is 1x`() {
        assertEquals(1f, bucketFactor(0))
        assertEquals(1f, bucketFactor(320))
        assertEquals(1f, bucketFactor(329))
    }

    @Test
    fun `buckets floor to the 30dp step like the sdp library`() {
        assertEquals(1.1f, bucketFactor(330))
        assertEquals(1.1f, bucketFactor(359))
        assertEquals(1.2f, bucketFactor(360))
        assertEquals(1.8f, bucketFactor(540))
        assertEquals(1.8f, bucketFactor(569))     // 569 floors to bucket 540
        assertEquals(1.9f, bucketFactor(570))
        assertEquals(2.0f, bucketFactor(600))
    }

    @Test
    fun `the user TV bucket sw540 scales 1_8x`() {
        assertEquals(1.8f, bucketFactor(540))
        // every element renders at the same pixel size as the reference:
        // 23sdp pill → 41.4dp → (× density 2) 82.8px ≈ the measured 83px
        val s = VuSdp(1.8f, 1f)
        assertEquals(41.4f, s.d(23f).value, 0.001f)
        assertEquals(63f, s.d(35f).value, 0.001f)
        assertEquals(180f, s.d(100f).value, 0.001f)
    }

    @Test
    fun `cap at sw1080 = 3_6x and beyond`() {
        assertEquals(3.6f, bucketFactor(1080))
        assertEquals(3.6f, bucketFactor(1600))
    }

    @Test
    fun `text size divides by font scale like sdp`() {
        val s = VuSdp(1.8f, 1.5f)
        // 9sdp at 150% font scale → 10.8sp so the VISUAL size stays 16.2dp
        assertEquals(10.8f, s.t(9f).value, 0.001f)
    }
}

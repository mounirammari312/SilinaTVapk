package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.19.0 — BingeNavigation contract: the "next episode" offer only
 * exists for series episodes that HAVE a series-episode sibling after
 * them in the zap list, and the countdown window is the reference's
 * 60 seconds.
 */
class BingeNavigationTest {

    private fun episode(id: Long, name: String = "S01E$id") = Channel(
        playlistId = 1L,
        key = "vode:$id",
        num = id.toInt(),
        name = name,
        logo = null,
        categoryId = null,
        directUrl = "http://example.com/$id.mp4"
    )

    private fun movie(id: Long) = episode(id).copy(key = "vodm:$id")

    @Test
    fun `next episode returns the sibling after the current index`() {
        val list = listOf(episode(1), episode(2), episode(3))
        assertEquals("vode:2", BingeNavigation.nextEpisode(list, 0)?.key)
        assertEquals("vode:3", BingeNavigation.nextEpisode(list, 1)?.key)
    }

    @Test
    fun `last episode of the list has no next`() {
        val list = listOf(episode(1), episode(2))
        assertNull(BingeNavigation.nextEpisode(list, 1))
    }

    @Test
    fun `movies never get a next-episode offer`() {
        val list = listOf(movie(10), movie(11))
        assertNull(BingeNavigation.nextEpisode(list, 0))
    }

    @Test
    fun `saved local files never get a next-episode offer`() {
        val list = listOf(episode(1).copy(key = "vodm:local:0"), episode(2))
        assertNull(BingeNavigation.nextEpisode(list, 0))
    }

    @Test
    fun `episode followed by a non-episode channel has no next`() {
        // a series list ending in some portal oddity — never offer it
        val list = listOf(episode(1), movie(99))
        assertNull(BingeNavigation.nextEpisode(list, 0))
    }

    @Test
    fun `out of range index has no next`() {
        val list = listOf(episode(1))
        assertNull(BingeNavigation.nextEpisode(list, 5))
        assertNull(BingeNavigation.nextEpisode(list, -1))
    }

    @Test
    fun `empty list has no next`() {
        assertNull(BingeNavigation.nextEpisode(emptyList(), 0))
    }

    @Test
    fun `countdown visible only inside the 60s window with a next`() {
        assertTrue(BingeNavigation.shouldShowCountdown(59_999L, hasNext = true))
        assertTrue(BingeNavigation.shouldShowCountdown(0L, hasNext = true))
        // seek past the window's end — fire immediately
        assertTrue(BingeNavigation.shouldShowCountdown(-1L, hasNext = true))
        assertFalse(BingeNavigation.shouldShowCountdown(60_001L, hasNext = true))
        // no sibling — never visible
        assertFalse(BingeNavigation.shouldShowCountdown(1_000L, hasNext = false))
    }

    @Test
    fun `window constant is the reference 60s`() {
        assertEquals(60_000L, BingeNavigation.NEXT_EPISODE_WINDOW_MS)
    }

    @Test
    fun `episode key detection`() {
        assertTrue(BingeNavigation.isEpisodeKey("vode:123"))
        assertFalse(BingeNavigation.isEpisodeKey("vodm:123"))
        assertFalse(BingeNavigation.isEpisodeKey("vodm:local:2"))
        assertFalse(BingeNavigation.isEpisodeKey(null))
    }

    // ── v2.2.3 — SKIP INTRO (the Netflix-style binge pair) ──────────

    @Test
    fun `skip intro visible inside the settle-to-target window`() {
        val dur = 45 * 60_000L // a 45-minute episode
        assertFalse(BingeNavigation.shouldShowSkipIntro(0L, dur))
        assertFalse(BingeNavigation.shouldShowSkipIntro(3_999L, dur))
        assertTrue(BingeNavigation.shouldShowSkipIntro(4_000L, dur))
        assertTrue(BingeNavigation.shouldShowSkipIntro(45_000L, dur))
        assertTrue(BingeNavigation.shouldShowSkipIntro(89_999L, dur))
        assertFalse(BingeNavigation.shouldShowSkipIntro(90_000L, dur))
    }

    @Test
    fun `resumed episode past the titles never offers skip intro`() {
        val dur = 45 * 60_000L
        // Continue-Watching restore at 12:00 — long past the intro
        assertFalse(BingeNavigation.shouldShowSkipIntro(12 * 60_000L, dur))
        assertFalse(BingeNavigation.shouldShowSkipIntro(dur - 30_000L, dur))
    }

    @Test
    fun `short content never offers skip intro`() {
        // a 2-minute clip — shorter than target + countdown window
        assertFalse(BingeNavigation.shouldShowSkipIntro(5_000L, 120_000L))
        // exactly the floor — still no
        assertFalse(BingeNavigation.shouldShowSkipIntro(5_000L, 150_000L))
        // one ms past the floor — a real episode, offer it
        assertTrue(BingeNavigation.shouldShowSkipIntro(5_000L, 150_001L))
    }

    @Test
    fun `skip intro seek target is the intro budget capped to the content`() {
        assertEquals(90_000L, BingeNavigation.skipIntroSeekTargetMs(45 * 60_000L))
        // degenerate durations clamp safely
        assertEquals(0L, BingeNavigation.skipIntroSeekTargetMs(0L))
        assertEquals(0L, BingeNavigation.skipIntroSeekTargetMs(500L))
        // a 60s clip would land past its end — clamped to just inside
        assertEquals(59_000L, BingeNavigation.skipIntroSeekTargetMs(60_000L))
    }

    @Test
    fun `skip intro constants are the netflix-style budget`() {
        assertEquals(90_000L, BingeNavigation.SKIP_INTRO_TARGET_MS)
        assertEquals(4_000L, BingeNavigation.SKIP_INTRO_SHOW_FROM_MS)
    }
}

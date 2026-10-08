package com.superz.iptvplayer.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.19.12 — the OPEN-TIME RESUME BAKE contract (the fix for the
 * Continue-Watching card whose play button refused to roll the parked
 * video). The bake decides everything BEFORE the media prepares:
 *
 *  • a valid stored stop point → open PARKED exactly at it;
 *  • no record / below the restore minimum / watched to the end /
 *    broken this session → open FRESH (rolling from 0);
 *  • a mid-watch recovery of the same channel → re-bake the last
 *    known position ROLLING (the recovery must be invisible).
 */
class ResumeBakeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(pos: Long, dur: Long): PlaybackPositionManager.PositionRecord =
        PlaybackPositionManager.PositionRecord(
            key = "vodm:1", playlistId = 1L, title = "Movie", posterUrl = null,
            kind = PlaybackPositionManager.KIND_MOVIE, contentId = 1L, seriesId = null,
            path = null, positionMs = pos, durationMs = dur, updatedAtMs = 0L
        )

    // ── the parked verdict ──

    @Test
    fun `a valid stop point bakes parked at it`() {
        val d = ResumeBake.decide(record(600_000L, 7_200_000L), brokenThisSession = false, restartAtMs = 0L)
        assertEquals(600_000L, d.resumeAtMs)
        assertTrue(d.parked)
    }

    @Test
    fun `no record opens fresh`() {
        val d = ResumeBake.decide(null, brokenThisSession = false, restartAtMs = 0L)
        assertEquals(ResumeBake.Decision.FRESH, d)
        assertFalse(d.parked)
    }

    @Test
    fun `a sub-minimum stop point is noise and opens fresh`() {
        val d = ResumeBake.decide(record(4_000L, 7_200_000L), brokenThisSession = false, restartAtMs = 0L)
        assertEquals(ResumeBake.Decision.FRESH, d)
    }

    @Test
    fun `a watched-to-the-end record opens fresh`() {
        // >= 95% of the runtime: the movie was finished — restart clean.
        val d = ResumeBake.decide(record(6_900_000L, 7_200_000L), brokenThisSession = false, restartAtMs = 0L)
        assertEquals(ResumeBake.Decision.FRESH, d)
    }

    @Test
    fun `a record without a measured duration opens fresh`() {
        // durationMs <= 0: cannot judge the watched fraction — never park.
        val d = ResumeBake.decide(record(600_000L, 0L), brokenThisSession = false, restartAtMs = 0L)
        assertEquals(ResumeBake.Decision.FRESH, d)
    }

    @Test
    fun `a broken-this-session key opens fresh`() {
        // The chain already abandoned this offset once — do not stall on it again.
        val d = ResumeBake.decide(record(600_000L, 7_200_000L), brokenThisSession = true, restartAtMs = 0L)
        assertEquals(ResumeBake.Decision.FRESH, d)
    }

    // ── the mid-watch recovery ──

    @Test
    fun `a mid-watch recovery re-bakes the last known position rolling`() {
        val d = ResumeBake.decide(record(600_000L, 7_200_000L), brokenThisSession = false, restartAtMs = 1_500_000L)
        assertEquals(1_500_000L, d.resumeAtMs)
        assertFalse(d.parked)
    }

    @Test
    fun `a sub-minimum restart hint falls through to the record verdict`() {
        val d = ResumeBake.decide(record(600_000L, 7_200_000L), brokenThisSession = false, restartAtMs = 3_000L)
        assertEquals(600_000L, d.resumeAtMs)
        assertTrue(d.parked)
    }

    // ── the store round-trip: PlaybackPositionManager.record() ──

    @Test
    fun `record returns the full stored record and null when absent`() {
        val f = tmp.newFile("positions.json")
        PlaybackPositionManager.save(f, record(482_000L, 7_200_000L))
        val rec = PlaybackPositionManager.record(f, "vodm:1")
        assertEquals(482_000L, rec?.positionMs)
        assertEquals(7_200_000L, rec?.durationMs)
        assertEquals(null, PlaybackPositionManager.record(f, "vodm:999"))
        PlaybackPositionManager.clear(f, "vodm:1")
        assertEquals(null, PlaybackPositionManager.record(f, "vodm:1"))
    }
}

package com.superz.iptvplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * v1.12.4 — DailySync's gate, locked to the reference's semantics
 * (BaseActivity.getAllChannel: "today's yyyy-MM-dd != the stored
 * LastPlaylistDate's yyyy-MM-dd" ⇒ re-sync the live list).
 */
class DailySyncTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** A fixed instant: 2026-10-04 12:00:00 UTC. */
    private val nowMs: Long = utc(2026, 10, 4, 12, 0)

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val cal = Calendar.getInstance(utc, Locale.US)
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, 0)
        return cal.timeInMillis
    }

    private fun atSec(ms: Long): Long = ms / 1000L

    @Test
    fun `never synced - run`() {
        assertTrue(DailySync.shouldSync(0L, nowMs, utc))
        assertTrue(DailySync.shouldSync(-1L, nowMs, utc))
    }

    @Test
    fun `synced earlier today - skip`() {
        val earlierToday = utc(2026, 10, 4, 0, 30)
        assertFalse(DailySync.shouldSync(atSec(earlierToday), nowMs, utc))
    }

    @Test
    fun `synced yesterday - run`() {
        val yesterday = utc(2026, 10, 3, 23, 0)
        assertTrue(DailySync.shouldSync(atSec(yesterday), nowMs, utc))
    }

    @Test
    fun `synced last week - run`() {
        val lastWeek = utc(2026, 9, 27, 9, 0)
        assertTrue(DailySync.shouldSync(atSec(lastWeek), nowMs, utc))
    }

    @Test
    fun `midnight boundary - local date rules`() {
        // 23:59 vs 00:01 the next day: different LOCAL dates ⇒ run.
        val lateNight = utc(2026, 10, 3, 23, 59)
        val afterMidnight = utc(2026, 10, 4, 0, 1)
        assertTrue(DailySync.shouldSync(atSec(lateNight), afterMidnight, utc))
        // Same statement across a timezone whose date differs from UTC's:
        // the pair spans Algiers midnight but not UTC midnight.
        val algiers = TimeZone.getTimeZone("Africa/Algiers")
        // 2026-10-03 22:00 UTC == 2026-10-03 23:00 Algiers (UTC+1)
        val a = utc(2026, 10, 3, 22, 0)
        // 2026-10-03 23:30 UTC == 2026-10-04 00:30 Algiers → next LOCAL day
        val b = utc(2026, 10, 3, 23, 30)
        assertTrue(DailySync.shouldSync(atSec(a), b, algiers))
        // ...but the same pair is ONE local date in UTC ⇒ skip.
        assertFalse(DailySync.shouldSync(atSec(a), b, utc))
    }

    @Test
    fun `epoch seconds input - not milliseconds`() {
        // lastSyncAt is stored in SECONDS (markSynced(now/1000)). A value
        // interpreted as milliseconds would be 1970 — must still "run".
        val yesterdaySec = atSec(utc(2026, 10, 3, 12, 0))
        assertTrue(DailySync.shouldSync(yesterdaySec, nowMs, utc))
    }
}

package com.superz.iptvplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.12.2 — the live-stream auto-recovery budget:
 *  • a restart after ≥15s of stable playback RESETS the strike count —
 *    long-lived streams stay protected indefinitely (the portal cuts the
 *    connection every ~20s: each restart lived long enough to reset);
 *  • after 6 consecutive QUICK restarts (<15s each — the portal is
 *    genuinely dead), further restarts are refused and the normal
 *    error/fatal UI takes over.
 */
class RestartBudgetTest {

    private fun budget() = RestartBudget()

    @Test
    fun `first quick restart is allowed`() {
        val b = budget()
        b.onStable(0L)
        assertTrue(b.shouldRestart(1_000L))
    }

    @Test
    fun `six quick restarts are allowed, the seventh is refused`() {
        val b = budget()
        b.onStable(0L)
        var t = 0L
        repeat(6) {
            t += 1_000L
            assertTrue("restart ${it + 1} must be allowed", b.shouldRestart(t))
            b.onStable(t)          // first frame of the restarted stream
        }
        t += 1_000L
        assertFalse("the 7th quick restart must be refused", b.shouldRestart(t))
    }

    @Test
    fun `fifteen seconds of stability resets the strike count`() {
        val b = budget()
        b.onStable(0L)
        // five quick restarts — one away from the limit
        var t = 0L
        repeat(5) {
            t += 1_000L
            assertTrue(b.shouldRestart(t))
            b.onStable(t)
        }
        // now a stream that survives 20s (>= 15s threshold)
        t += 20_000L
        assertTrue(b.shouldRestart(t))
        b.onStable(t)
        assertEquals("stability must reset strikes to zero", 0, b.strikes())
        // the budget is fresh again: six more quick restarts allowed
        repeat(6) {
            t += 1_000L
            assertTrue(b.shouldRestart(t))
            b.onStable(t)
        }
        t += 1_000L
        assertFalse(b.shouldRestart(t))
    }

    @Test
    fun `long-lived portal cuts never exhaust the budget`() {
        val b = budget()
        var t = 0L
        // the real-world pattern on mag.max-cdn.com: the CDN cuts the
        // connection every ~20s, every restart plays ~20s again. This
        // must loop forever without ever being refused.
        repeat(50) {
            b.onStable(t)
            t += 20_000L
            assertTrue("iteration ${it + 1}: a 20s-lived stream must always restart", b.shouldRestart(t))
        }
    }

    @Test
    fun `stability window starts at the first frame`() {
        val b = budget()
        // no onStable call: lastEventMs stays 0 — the very first restart
        // decision must not be treated as "stable since the epoch".
        assertTrue(b.shouldRestart(500L))
    }

    @Test
    fun `custom thresholds are honored`() {
        val b = RestartBudget(stableResetMs = 10_000L, maxStrikes = 2)
        b.onStable(0L)
        assertTrue(b.shouldRestart(1_000L))
        b.onStable(1_000L)
        assertTrue(b.shouldRestart(2_000L))
        b.onStable(2_000L)
        // third quick restart with maxStrikes = 2 → refused
        assertFalse(b.shouldRestart(3_000L))
    }
}

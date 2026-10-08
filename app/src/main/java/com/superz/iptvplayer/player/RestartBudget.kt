package com.superz.iptvplayer.player

/**
 * v1.12.2 — live-stream auto-recovery budget (pure logic, unit-tested).
 *
 * The reference app restarts a playing stream on STATE_ENDED and on
 * ERROR_CODE_BEHIND_LIVE_WINDOW (LivePlayActivity.PlayerEventListener /
 * MoviePlayerActivity / CatchUpPlayActivity) — unconditionally. Stalker
 * CDNs cut long-lived connections routinely; a restart is the CORRECT
 * response to a stream that died while playing.
 *
 * We keep the reference's behavior but add ONE safety net it lacks: a
 * strike budget so a portal that is genuinely down cannot trap the app
 * in an invisible restart loop (background playback would burn CPU and
 * battery forever). The rule is deliberately simple:
 *
 *  • A restart after ≥ [STABLE_RESET_MS] of stable playback RESETS the
 *    strike count — long-lived streams stay protected indefinitely.
 *  • Consecutive quick restarts (each living < [STABLE_RESET_MS]) count
 *    as strikes; after [MAX_STRIKES] quick restarts in a row, further
 *    restarts are refused (the normal error/fatal UI takes over).
 */
class RestartBudget(
    private val stableResetMs: Long = STABLE_RESET_MS,
    private val maxStrikes: Int = MAX_STRIKES
) {
    private var strikes = 0
    private var lastEventMs = 0L

    /** A new play()/first frame — fresh window, budget survives (strikes
     *  only reset through stability, not through restarts). */
    fun onStable(nowMs: Long) {
        lastEventMs = nowMs
    }

    /**
     * Decide whether a stream that just died should restart.
     * @param nowMs the current elapsed-realtime clock.
     * @return true when the restart budget allows another restart.
     */
    fun shouldRestart(nowMs: Long): Boolean {
        val stableFor = nowMs - lastEventMs
        return if (stableFor >= stableResetMs) {
            strikes = 0
            lastEventMs = nowMs
            true
        } else {
            strikes++
            lastEventMs = nowMs
            strikes <= maxStrikes
        }
    }

    /** Test hook — strikes consumed so far. */
    internal fun strikes(): Int = strikes

    companion object {
        /** 15s of stable playback proves the stream is healthy again. */
        const val STABLE_RESET_MS = 15_000L

        /** Allow up to 6 quick restarts before giving up. */
        const val MAX_STRIKES = 6
    }
}

package com.superz.iptvplayer.data.playback

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v1.19.12 — ResumeBake: the OPEN-TIME resume decision, pure Kotlin.
 *
 *  Replaces the v1.19.10 "open-park" machinery (pause after the first
 *  frame, then an async read + a supervised mid-flight seek + a re-armed
 *  resume phase). That post-hoc design raced ExoPlayer's own event
 *  delivery: the park's pause could land before/after the player's
 *  isPlaying flip, and the post-seek first frame re-fired onPlaying —
 *  leaving the UI's isPlaying and the player's playWhenReady in
 *  disagreement, so the user's play press toggled a STALE flag and the
 *  parked video refused to roll (user report: "opens paused and the
 *  play button never works").
 *
 *  The bake instead decides EVERYTHING up-front, before the media even
 *  prepares: ExoPlayer opens with setMediaSource(source, positionMs)
 *  and playWhenReady = !parked — exactly ONE pause-flip exists (the
 *  bake itself), so the user's play press is the only thing that can
 *  ever flip it again. No supervision, no re-arm, no event race.
 *
 *  Pure Kotlin on purpose: the whole decision contract is unit-tested
 *  (ResumeBakeTest) without Android.
 * ═══════════════════════════════════════════════════════════════════
 */
object ResumeBake {

    /** The verdict handed to SmartPlayer.play(...). */
    data class Decision(
        /** The position to bake into the open (< 0 = open fresh at 0). */
        val resumeAtMs: Long,
        /** True = open PARKED at the stop point; false = open rolling. */
        val parked: Boolean
    ) {
        companion object {
            val FRESH = Decision(-1L, parked = false)
        }
    }

    /**
     * The open-time verdict for a channel.
     *
     * @param record        the stored position record (null = never
     *                      watched / cleared → fresh open).
     * @param brokenThisSession true when this key's stored offset already
     *                      proved unservable this session (the chain
     *                      abandoned the baked attempt) → open fresh
     *                      instead of stalling on it again.
     * @param restartAtMs   >= MIN when this is a MID-WATCH recovery of
     *                      the SAME channel (engine chain re-resolve):
     *                      re-bake the last known position ROLLING — the
     *                      recovery must be invisible, not a re-park.
     */
    fun decide(
        record: PlaybackPositionManager.PositionRecord?,
        brokenThisSession: Boolean,
        restartAtMs: Long
    ): Decision {
        // Mid-watch recovery outranks everything: the user WAS watching;
        // the re-open rolls at the last known position.
        if (restartAtMs >= PlaybackPositionManager.MIN_RESTORE_POSITION_MS) {
            return Decision(restartAtMs, parked = false)
        }
        if (record == null || brokenThisSession) return Decision.FRESH
        val pos = record.positionMs
        val dur = record.durationMs
        // The same honesty the shelf itself applies: a stop point below
        // the restore minimum is noise; one at/after the watched fraction
        // (>= 95%) means the movie was finished — both open FRESH.
        val watchedCap = (dur * PlaybackPositionManager.WATCHED_CLEAR_FRACTION).toLong()
        val resumable = pos >= PlaybackPositionManager.MIN_RESTORE_POSITION_MS &&
            dur >= PlaybackPositionManager.MIN_VOD_DURATION_MS &&
            pos < watchedCap
        return if (resumable) Decision(pos, parked = true) else Decision.FRESH
    }
}

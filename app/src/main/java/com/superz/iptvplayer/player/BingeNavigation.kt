package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v1.19.0 — BINGE-WATCH NAVIGATION (pure decision logic).
 *
 *  The reference app's startBingeWatchLoop shows a "Next Episode"
 *  countdown when a SERIES episode nears its end, then auto-plays the
 *  next episode inline. Oria's zap list already carries the season's
 *  episodes (prepareEpisodePlayback), so "the next episode" is simply
 *  the next entry of the player's channel list — with three guards:
 *
 *   • only EPISODE keys (vode:) qualify — a movie's list is the movie
 *     itself and the offline library's list is unrelated saves, so
 *     neither gets a "next" card;
 *   • the next entry must ALSO be an episode (a series list ending in
 *     a movie channel would otherwise offer a nonsense jump);
 *   • the NEXT entry exists (last episode of the season = no card).
 *
 *  Pure functions, zero Android types → plain-JVM unit tests.
 * ═══════════════════════════════════════════════════════════════════
 */
object BingeNavigation {

    /** The reference's NEXT_EPISODE_WINDOW_MS — countdown starts this
     *  far from the end of the current episode. */
    const val NEXT_EPISODE_WINDOW_MS = 60_000L

    /**
     * v2.2.3 — SKIP INTRO (the Netflix-style binge pair's first half).
     * Xtream/Stalker streams carry no intro markers, so the intro is a
     * WINDOW heuristic, exactly like the big platforms' players on
     * unmarked content: the button offers itself from the first settled
     * seconds of an episode until the jump target; a tap (or OK on the
     * remote) seeks straight past it. 90s is the classic series-intro
     * budget — long enough to clear recaps + title sequences, short
     * enough to never feel like skipping content.
     */
    const val SKIP_INTRO_TARGET_MS = 90_000L

    /** The button waits for playback to settle before appearing — a
     *  still-buffering player flashing a "skip" button is noise. */
    const val SKIP_INTRO_SHOW_FROM_MS = 4_000L

    /**
     * The channel the binge overlay should offer, or null when the
     * current content does not continue (movie / saved file / last
     * episode of the season / empty list).
     */
    fun nextEpisode(channels: List<Channel>, currentIndex: Int): Channel? {
        val current = channels.getOrNull(currentIndex) ?: return null
        if (!isEpisodeKey(current.key)) return null
        val next = channels.getOrNull(currentIndex + 1) ?: return null
        return next.takeIf { isEpisodeKey(it.key) }
    }

    /** Only series episodes participate in auto-advance. */
    fun isEpisodeKey(key: String?): Boolean =
        key != null && key.startsWith("vode:")

    /**
     * Whether the binge countdown card should be visible right now.
     * [remainingMs] < 0 means the stream already ended (a seek past the
     * window) — visible=true lets the loop fire the auto-play at once.
     */
    fun shouldShowCountdown(remainingMs: Long, hasNext: Boolean): Boolean =
        hasNext && remainingMs <= NEXT_EPISODE_WINDOW_MS

    /**
     * v2.2.3 — whether the SKIP INTRO button should be visible for an
     * episode at [positionMs] of [durationMs]:
     *  • the episode must be long enough to HAVE an intro — anything
     *    shorter than the target + one countdown window is a clip, not
     *    an episode (short content never offers the jump);
     *  • the playhead must be INSIDE the window: past the settle floor
     *    (a resumed episode at 12:00 is long past its titles — no
     *    button) and before the target itself;
     *  • a playhead at exactly the target is already past — hidden.
     */
    fun shouldShowSkipIntro(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= SKIP_INTRO_TARGET_MS + NEXT_EPISODE_WINDOW_MS) return false
        return positionMs in SKIP_INTRO_SHOW_FROM_MS until SKIP_INTRO_TARGET_MS
    }

    /**
     * v2.2.3 — where a SKIP INTRO tap lands: the intro target, never
     * beyond the content itself (short-edge safety for a tap racing the
     * last seconds of the window).
     */
    fun skipIntroSeekTargetMs(durationMs: Long): Long =
        SKIP_INTRO_TARGET_MS.coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L))
}

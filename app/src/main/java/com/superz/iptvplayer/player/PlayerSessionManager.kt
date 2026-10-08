package com.superz.iptvplayer.player

import android.content.Context
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PlayerSessionManager — the app-scoped playback session.
 *
 *  THE seam that makes the mini player (channel view screen) and the
 *  fullscreen player ONE continuous stream:
 *  • The SmartPlayer instance is created ONCE per session and shared.
 *  • When the user expands the mini player to fullscreen, the SAME
 *    engine keeps playing — only the video surface re-binds. Zero
 *    re-buffering, zero restart, zero engine churn.
 *  • When the user returns, the mini player's surface re-binds the
 *    still-playing engine the same way.
 *
 *  The engine core (SmartPlayer / EngineRouter / Preconnect) is never
 *  modified — the session merely changes WHO owns and observes it.
 * ═══════════════════════════════════════════════════════════════════
 */
object PlayerSessionManager {

    class Session(
        val smartPlayer: SmartPlayer,
        val dispatcher: ListenerDispatcher
    ) {
        // Shared browse context — written by whoever drives the session
        // (channel view or fullscreen), read by whoever takes it over.
        @Volatile var playlist: Playlist? = null
        @Volatile var channels: List<Channel> = emptyList()
        @Volatile var currentIndex: Int = -1
        val memoryMap: MutableMap<String, EngineMemory> = java.util.concurrent.ConcurrentHashMap()

        /** True once the session has been bootstrapped with a playlist. */
        val started: Boolean get() = playlist != null
    }

    @Volatile
    private var current: Session? = null

    /** The live session, if one exists (fullscreen takeover / state sync). */
    fun peek(): Session? = current

    /**
     * Get the live session or create a fresh one. The SmartPlayer is
     * constructed with the stable [ListenerDispatcher] — never with a
     * ViewModel directly — so listener hand-over never re-creates the engine.
     */
    fun ensure(context: Context, scope: CoroutineScope, okHttp: OkHttpClient): Session {
        val s = current
        if (s != null) return s
        synchronized(this) {
            val again = current
            if (again != null) return again
            val dispatcher = ListenerDispatcher()
            val player = SmartPlayer(context.applicationContext, scope, okHttp, dispatcher)
            // v1.19.12 — the dispatcher's isPlaying seed must read the
            // ENGINE's live state (a baked PARKED open is NOT playing): wire
            // the read-back AFTER construction (circular otherwise).
            dispatcher.livePlaying = { player.isPlaying }
            val fresh = Session(player, dispatcher)
            current = fresh
            return fresh
        }
    }

    /** Tear the session down — called when the channel view screen is left. */
    fun release() {
        val s = current ?: return
        current = null
        s.dispatcher.active = null
        try {
            s.smartPlayer.release()
        } catch (_: Throwable) {
            // Never let a double-release crash the app.
        }
    }

    /**
     * True while a stream is actively playing — powers the automatic
     * picture-in-picture transition when the user swipes home.
     */
    fun isStreamPlaying(): Boolean {
        val s = current ?: return false
        return s.started && s.dispatcher.isPlaying
    }
}

/**
 * Forwards SmartPlayer events to the currently-active ViewModel listener.
 *
 * SmartPlayer is constructed ONCE per session with this stable dispatcher,
 * so listener hand-over (mini player ⇄ fullscreen ⇄ back) never touches the
 * engine. `deactivate` is identity-checked to survive overlapping Compose
 * navigation transitions (the outgoing screen may dispose AFTER the
 * incoming screen has already activated).
 */
class ListenerDispatcher : SmartPlayer.Listener {

    @Volatile
    var active: SmartPlayer.Listener? = null
        internal set

    /** Last engine that produced events — seeds UI state on takeover. */
    @Volatile
    var lastEngine: Engine? = null
        private set

    /** Playback running right now — seeds UI state + auto-PiP decision. */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** v1.19.12 — the ENGINE's live isPlaying read-back, wired by
     *  PlayerSessionManager.ensure after construction. A baked PARKED
     *  open (Continue-Watching) renders its first frame with
     *  playWhenReady=false — the old unconditional true seed lied to
     *  takeover UIs and the auto-PiP gate for exactly that case. */
    @Volatile
    var livePlaying: (() -> Boolean)? = null

    fun activate(listener: SmartPlayer.Listener) {
        active = listener
    }

    fun deactivate(listener: SmartPlayer.Listener) {
        if (active === listener) active = null
    }

    override fun onChannelChanged(channel: Channel) {
        active?.onChannelChanged(channel)
    }

    override fun onEngineAttempt(engine: Engine, attemptIndex: Int, totalAttempts: Int) {
        lastEngine = engine
        active?.onEngineAttempt(engine, attemptIndex, totalAttempts)
    }

    override fun onBuffering(buffering: Boolean, percent: Int?) {
        active?.onBuffering(buffering, percent)
    }

    override fun onFirstFrame(engine: Engine, ttffMs: Long, attempt: PlayAttempt) {
        lastEngine = engine
        active?.onFirstFrame(engine, ttffMs, attempt)
    }

    override fun onPlaying(engine: Engine) {
        lastEngine = engine
        // v1.19.12 — read the ENGINE, not the event: a parked baked open
        // must seed isPlaying=false (the stream is paused at its stop point).
        isPlaying = livePlaying?.invoke() ?: true
        active?.onPlaying(engine)
    }

    override fun onPlayPauseChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        active?.onPlayPauseChanged(isPlaying)
    }

    override fun onFatalError() {
        isPlaying = false
        active?.onFatalError()
    }

    /** v1.12.2 — a playing stream ended/died: forward to the ACTIVE
     *  listener so the driving ViewModel can re-resolve + replay (the
     *  reference's auto-restart). isPlaying flips off until the restart's
     *  first frame — auto-PiP and takeover state stay truthful. */
    override fun onStreamEnded() {
        isPlaying = false
        active?.onStreamEnded()
    }

    /** v1.19.12 — the open-time resume bake was abandoned by the chain
     *  (unservable stored offset): forward so the ACTIVE listener can mark
     *  the key broken for this session. No isPlaying change — the chain is
     *  already advancing to the next attempt. */
    override fun onResumeSeekFailed(channelKey: String) {
        active?.onResumeSeekFailed(channelKey)
    }
}

package com.superz.iptvplayer.player

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * ═══════════════════════════════════════════════════════════════════
 *  SmartPlayer — the hybrid playback core.
 *
 *  • ExoPlayer (Media3) is the primary engine: fastest HLS/TS start and
 *    precise buffer control (400ms playback threshold).
 *  • libVLC is the fallback: RTSP/UDP/RTMP + exotic stream formats.
 *  • Fallback chain per channel with a Timeout Guard: if the first frame
 *    is not rendered in time, the next attempt starts automatically.
 *  • Engine memory: the ViewModel feeds remembered (engine, variant)
 *    pairs so previously-hard channels start on the right engine.
 *  • Double-buffered zapping: a second ExoPlayer ("preload") buffers the
 *    NEXT channel in the list while the current one plays. On zap the
 *    players swap instantly — no network wait.
 *  • The primary player is NEVER destroyed across zaps (no re-init cost).
 * ═══════════════════════════════════════════════════════════════════
 */
@OptIn(UnstableApi::class)
class SmartPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    private val listener: Listener
) {

    interface Listener {
        /** Fired the instant a new channel is requested (before any engine work). */
        fun onChannelChanged(channel: Channel)
        fun onEngineAttempt(engine: Engine, attemptIndex: Int, totalAttempts: Int)
        fun onBuffering(buffering: Boolean, percent: Int?)
        fun onFirstFrame(engine: Engine, ttffMs: Long, attempt: PlayAttempt)
        fun onPlaying(engine: Engine)
        fun onPlayPauseChanged(isPlaying: Boolean)
        fun onFatalError()
        /** v1.12.2 — a PLAYING stream ended or died mid-flight (stalker CDNs
         *  cut long-lived connections; ExoPlayer reports EOF as STATE_ENDED
         *  and live-window drift as ERROR_CODE_BEHIND_LIVE_WINDOW). The
         *  reference's player activities RESTART playback in exactly this
         *  case (LivePlayActivity.PlayerEventListener: onPlaybackStateChanged(4)
         *  → replay, onPlayerError(1002) → fresh getLiveStreamUrl). The
         *  active listener should re-resolve the channel and call play().
         *  Default no-op so listeners that don't care are unaffected. */
        fun onStreamEnded() {}
        /** v1.19.12 — the OPEN-TIME resume bake could not be served: the
         *  chain abandoned the attempt that carried the baked stop-point
         *  seek (timeout / error before any frame). The listener should
         *  mark the key broken for this session so a retry/re-open of the
         *  same content opens FRESH instead of stalling on the same
         *  unservable offset again. Default no-op so other listeners
         *  (the channel view) are unaffected. */
        fun onResumeSeekFailed(channelKey: String) {}
    }

    // ── Views (attached once by the player screen) ──────────────
    private var exoView: PlayerView? = null
    private var vlcView: VLCVideoLayout? = null

    // ── ExoPlayer pool ──────────────────────────────────────────
    private var activeExo: ExoPlayer? = null
    private var preloadExo: ExoPlayer? = null
    private var preloadedKey: String? = null
    private var preloadedUrl: String? = null

    // ── VLC engine ──────────────────────────────────────────────
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null

    /** True once VLC proved unavailable on this device (native load failure
     *  — e.g. 16KB-page phones, or lite builds without the natives).
     *  Playback degrades gracefully to the Exo engine instead of crashing. */
    @Volatile
    private var vlcUnavailable = false

    // ── Current playback state ──────────────────────────────────
    private var generation = 0L
    private var currentChannel: Channel? = null
    private var currentPlaylist: Playlist? = null
    private var chain: List<PlayAttempt> = emptyList()
    private var chainIndex = 0
    private var currentEngine: Engine? = null
    private var currentAttempt: PlayAttempt? = null
    private var timeoutJob: Job? = null
    private var playStartMs = 0L
    private var successNotified = false
    private var wasPlaying = false
    private var vlcAttached = false

    // v1.12.2 — live-stream auto-recovery state (the reference's
    // LivePlayActivity restarts on STATE_ENDED / error 1002):
    //  • restartOnEnd — true for live channels AND catch-up (vodc:/vodx:
    //    hour-long archive streams through the same cut-prone CDNs), false
    //    for finite movies/episodes (vodm:/vode:) whose STATE_ENDED is the
    //    movie's natural ending, not a cut.
    //  • restartBudget — strikes guard so a dead portal cannot loop.
    private var restartOnEnd = true
    private val restartBudget = RestartBudget()

    // ── v1.19.12 — the OPEN-TIME RESUME BAKE ────────────────────
    // One play() may carry a stored stop point to bake INTO the open:
    // the media source is prepared AT that position and playWhenReady
    // is false when the verdict says "parked". There is exactly ONE
    // pause-flip (the bake itself) and exactly one thing that can flip
    // it afterwards — the user's play press. The bake applies to the
    // FIRST attempt of the chain only; later attempts (variant/engine
    // fallbacks) open clean from 0, exactly like a fresh watch.
    private var pendingResumeAtMs = -1L
    private var pendingResumeParked = false
    /** The key whose bake the current chain is still holding (cleared
     *  once consumed or reported broken via onResumeSeekFailed). */
    private var bakedKey: String? = null

    /** Loaded by the ViewModel before first play. channelKey → memory. */
    @Volatile
    var engineMemory: Map<String, EngineMemory> = emptyMap()

    /** Preloading real stream data is only allowed on unmetered networks. */
    var isPreloadEnabled = true

    // v1.4.2 — Timeout Guard budgets became configurable vars. The DEFAULTS
    // are the v1.2.0 live values, byte-identical: live zapping stays exactly
    // as tuned (4.5s / 4.5s / 9s). VOD playback opts into longer budgets via
    // these vars before play(): big movie files legitimately need 10-20s to
    // the first frame (moov-atom fetch on non-faststart MP4s, server open),
    // which the 4.5s live guard killed — the "most movies never start" bug.
    @Volatile var firstAttemptTimeoutMs = FIRST_ATTEMPT_TIMEOUT_MS
    @Volatile var midAttemptTimeoutMs = MID_ATTEMPT_TIMEOUT_MS
    @Volatile var lastAttemptTimeoutMs = LAST_ATTEMPT_TIMEOUT_MS

    /** Public accessor so the UI can bind the PlayerView to the active instance. */
    val activeExoForView: ExoPlayer? get() = activeExo

    // ═══════════════════════════ PUBLIC API ═══════════════════════════

    fun attachViews(playerView: PlayerView) {
        exoView = playerView
        activeExo?.let { playerView.player = it }
    }

    /** The Exo PlayerView left the hierarchy (engine switched / screen
     *  disposed) — drop the stale reference so a FUTURE surface binds the
     *  fresh view instead of writing video into a detached one. */
    fun detachExoView(playerView: PlayerView) {
        if (exoView === playerView) exoView = null
    }

    fun attachVlcView(vlcLayout: VLCVideoLayout) {
        vlcView = vlcLayout
        // The VLC surface is composed ONLY while the VLC engine is active —
        // which means it may enter the hierarchy AFTER the fallback chain has
        // already switched to VLC and started playback (the view factory runs
        // on the next frame, engine state changes first). In that case the
        // player is already playing audio with no video surface: attach NOW.
        if (currentEngine == Engine.VLC && !vlcAttached) {
            try {
                vlcPlayer?.attachViews(vlcLayout, null, false, true)
                vlcAttached = true
            } catch (t: Throwable) {
                Log.w(TAG, "vlc late attach failed", t)
            }
        }
    }

    /** The VLCVideoLayout left the hierarchy (engine switched to Exo / screen
     *  disposed). Defensively detach the renderer so a later VLC attempt
     *  re-attaches to the FRESH layout instead of rendering into a view that
     *  is no longer on screen (invisible video with audio). */
    fun detachVlcView(vlcLayout: VLCVideoLayout) {
        if (vlcView !== vlcLayout) return
        vlcView = null
        if (vlcAttached) {
            try {
                vlcPlayer?.detachViews()
            } catch (t: Throwable) {
                Log.w(TAG, "vlc detach on release", t)
            }
            vlcAttached = false
        }
    }

    /** Main entry: play a channel with instant overlay + fallback chain.
     *
     *  v1.19.12 — [resumeAtMs]/[resumeParked]: the open-time resume bake
     *  (see the bake block's KDoc above). The FIRST Exo attempt prepares
     *  the media AT resumeAtMs and parks (playWhenReady=false) when
     *  resumeParked — the parked-open of the Continue-Watching feature
     *  without the v1.19.10 post-hoc pause race. VLC attempts and
     *  post-first attempts ignore the bake (VLC has no pre-prepare seek
     *  in this architecture; it restarts from 0 by design). */
    fun play(playlist: Playlist, channel: Channel, resumeAtMs: Long = -1L, resumeParked: Boolean = false) {
        val gen = ++generation
        currentChannel = channel
        currentPlaylist = playlist
        chainIndex = 0
        successNotified = false
        playStartMs = SystemClock.elapsedRealtime()
        restartOnEnd = !channel.key.startsWith("vodm:") && !channel.key.startsWith("vode:")
        pendingResumeAtMs = resumeAtMs
        pendingResumeParked = resumeParked
        bakedKey = if (resumeAtMs >= 0L) channel.key else null
        listener.onChannelChanged(channel)

        chain = StreamUrls.buildChain(playlist, channel, engineMemory[channel.key])
        if (chain.isEmpty()) {
            listener.onFatalError()
            return
        }
        startAttempt(gen)
    }

    /** Preload the next channel on the spare player (double-buffer zap). */
    fun preloadNext(playlist: Playlist, next: Channel?) {
        if (!isPreloadEnabled || next == null) return
        val memory = engineMemory[next.key]
        val url = StreamUrls.primary(playlist, next, engineMemory)
        if (url == null || !url.startsWith("http", true)) return
        // Only the remembered engine matters here: if this channel is known
        // to need VLC, preloading on Exo would be wasted bandwidth.
        if (engineMemory[next.key]?.engine == "VLC") return

        try {
            val pre = preloadExo ?: createExoPlayer().also { preloadExo = it }
            preloadedKey = next.key
            preloadedUrl = url
            // v1.12.4 — the preload player uses the SAME cached media-source
            // stack as the active one (buildMediaSource), so a zap into a
            // preloaded channel also inherits the disk-cache fast path.
            pre.setMediaSource(buildMediaSource(url), /* resetPosition = */ true)
            pre.prepare()
            pre.playWhenReady = false
        } catch (e: Exception) {
            Log.w(TAG, "preload failed", e)
        }
    }

    /** Warm TCP connections for neighbours (cheap, allowed even on metered). */
    fun preconnect(playlist: Playlist, channels: List<Channel?>) {
        val urls = channels.mapNotNull { ch ->
            ch?.let { StreamUrls.primary(playlist, it, engineMemory) }
        }
        Preconnector.warm(okHttp, scope, urls.take(3))
    }

    fun togglePlayPause() {
        when (currentEngine) {
            Engine.EXO -> activeExo?.let { it.playWhenReady = !it.playWhenReady }
            Engine.VLC -> vlcPlayer?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
            else -> Unit
        }
    }

    val isPlaying: Boolean
        get() = when (currentEngine) {
            Engine.EXO -> activeExo?.isPlaying == true
            Engine.VLC -> vlcPlayer?.isPlaying == true
            null -> false
        }

    // ── Aspect ratio ────────────────────────────────────────────

    fun cycleResizeMode(): Int {
        val next = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        currentResizeMode = next
        exoView?.resizeMode = next
        return next
    }

    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    // ── Audio tracks ────────────────────────────────────────────

    data class AudioTrackInfo(val id: Int, val name: String, val selected: Boolean)

    fun audioTracks(): List<AudioTrackInfo> {
        when (currentEngine) {
            Engine.EXO -> {
                val player = activeExo ?: return emptyList()
                val out = mutableListOf<AudioTrackInfo>()
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        val format = group.getTrackFormat(i)
                        val label = format.label
                            ?: format.language
                            ?: format.id
                            ?: "Audio ${out.size + 1}"
                        out += AudioTrackInfo(out.size, label, group.isTrackSelected(i))
                    }
                }
                return out
            }
            Engine.VLC -> {
                val player = vlcPlayer ?: return emptyList()
                val current = player.audioTrack
                return try {
                    player.audioTracks.orEmpty().mapIndexed { idx, td ->
                        AudioTrackInfo(td.id, td.name ?: "Track ${idx + 1}", td.id == current)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            null -> return emptyList()
        }
    }

    fun selectAudioTrack(track: AudioTrackInfo): Boolean {
        return when (currentEngine) {
            Engine.EXO -> {
                val player = activeExo ?: return false
                var idx = -1
                val tracks = player.currentTracks
                outer@ for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until group.length) {
                        idx++
                        if (idx == track.id) {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(
                                        group.mediaTrackGroup, i
                                    )
                                )
                                .build()
                            break@outer
                        }
                    }
                }
                true
            }
            Engine.VLC -> vlcPlayer?.setAudioTrack(track.id) ?: false
            null -> false
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────

    fun release() {
        generation++
        timeoutJob?.cancel()
        timeoutJob = null
        try { activeExo?.release() } catch (e: Exception) { Log.w(TAG, "exo release", e) }
        try { preloadExo?.release() } catch (e: Exception) { Log.w(TAG, "preload release", e) }
        activeExo = null
        preloadExo = null
        try {
            vlcPlayer?.let {
                it.setEventListener(null)
                it.stop()
                if (vlcAttached) {
                    it.detachViews()
                    vlcAttached = false
                }
                it.release()
            }
        } catch (e: Exception) { Log.w(TAG, "vlc release", e) }
        vlcPlayer = null
        try { libVlc?.release() } catch (e: Exception) { Log.w(TAG, "libvlc release", e) }
        libVlc = null
        abandonAudioFocus()
        exoView?.player = null
        exoView = null
        vlcView = null
    }

    // ═══════════════════════ INTERNAL CORE ═══════════════════════════

    private fun startAttempt(gen: Long) {
        if (gen != generation) return
        val attempt = chain.getOrNull(chainIndex)
        if (attempt == null) {
            listener.onFatalError()
            return
        }
        currentAttempt = attempt
        currentEngine = attempt.engine
        listener.onEngineAttempt(attempt.engine, chainIndex, chain.size)
        listener.onBuffering(true, null)

        when (attempt.engine) {
            Engine.EXO -> playExo(attempt, gen)
            Engine.VLC -> playVlc(attempt, gen)
        }

        // Timeout Guard: no first frame in time → next attempt automatically.
        val timeoutMs = when {
            chainIndex == 0 -> firstAttemptTimeoutMs
            chainIndex == chain.lastIndex -> lastAttemptTimeoutMs
            else -> midAttemptTimeoutMs
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (gen == generation && !successNotified) {
                Log.w(TAG, "timeout on attempt $chainIndex (${attempt.engine}/${attempt.variant})")
                advanceChain(gen, "timeout")
            }
        }
    }

    private fun advanceChain(gen: Long, reason: String) {
        if (gen != generation) return
        Log.d(TAG, "advancing chain: $reason")
        // v1.19.12 — the FAILING attempt carried the open-time resume bake
        // (chainIndex == 0 and a baked key is still held): the stored stop
        // point's offset could not be served by this engine/variant. Report
        // it so the listener marks the key broken for this session — a
        // retry/re-open goes fresh instead of stalling on the same offset.
        if (chainIndex == 0) {
            bakedKey?.let { key ->
                bakedKey = null
                pendingResumeAtMs = -1L
                pendingResumeParked = false
                listener.onResumeSeekFailed(key)
            }
        }
        // Stop whichever engine was attempting
        when (currentEngine) {
            Engine.EXO -> { activeExo?.stop() }
            Engine.VLC -> { try { vlcPlayer?.stop() } catch (_: Exception) {} }
            null -> Unit
        }
        chainIndex++
        if (chainIndex < chain.size) {
            startAttempt(gen)
        } else {
            listener.onFatalError()
        }
    }

    // ── ExoPlayer path ──────────────────────────────────────────

    private fun playExo(attempt: PlayAttempt, gen: Long) {
        // VLC must never render while Exo owns the screen
        stopAndDetachVlc()

        val channel = currentChannel
        // v1.19.12 — consume the open-time resume bake on the FIRST attempt
        // (a preload hit serves the same contract: seek the spare player to
        // the stop point and park it there).
        val bake = chainIndex == 0 && pendingResumeAtMs >= 0L
        // Preload hit → instant swap, no network wait at all
        if (channel != null && preloadExo != null && preloadedKey == channel.key && preloadedUrl == attempt.url) {
            val pre = preloadExo!!
            val old = activeExo
            activeExo = pre
            preloadExo = old
            preloadedKey = null
            preloadedUrl = null
            try { old?.stop(); old?.clearMediaItems() } catch (e: Exception) { Log.w(TAG, "old stop", e) }
            if (bake) {
                try { pre.seekTo(pendingResumeAtMs) } catch (_: Throwable) {}
                pre.playWhenReady = !pendingResumeParked
            } else {
                pre.playWhenReady = true
            }
            if (bake) clearPendingResume()
            exoView?.player = pre
            Log.i(TAG, "ZAP: preload hit for ${channel.name}")
            return
        }

        val player = activeExo ?: createExoPlayer().also { activeExo = it }
        // v1.12.2 — NO setLiveConfiguration: the reference sets none, so the
        // player follows the manifest's own live offset. Our old aggressive
        // 1500ms-edge chase parked the stream ~1.5s behind the live edge,
        // where any CDN hiccup tips it out of the window (error 1002).
        //
        // v1.12.4 — the reference's playVideo media-source branch, VERBATIM:
        //   m3u8  → HlsMediaSource over the plain HTTP factory (no cache);
        //   other → ProgressiveMediaSource over CacheDataSource(simpleCache)
        //           with FLAG_IGNORE_CACHE_ON_ERROR — the reference's exact
        //           stack (LivePlayActivity.playVideo). The disk cache is the
        //           reference's seamless-recovery secret: this portal's tmp
        //           stream URL is STABLE (verified live — same URL from every
        //           create_link), so every auto-restart / loader retry serves
        //           the already-downloaded TS prefix INSTANTLY from disk while
        //           the upstream reconnects quietly behind it. The user sees
        //           a fleeting spinner instead of a black screen + spinner.
        if (bake) {
            // v1.19.12 — THE BAKED OPEN: the media prepares AT the stored
            // stop point (setMediaSource(source, startPositionMs)) and the
            // player is PARKED there when the verdict says so. The first
            // frame renders at the stop point; the icon shows the play
            // form (onPlaying publishes the player's live isPlaying);
            // the user's play press — the ONLY pause-flip left — rolls it.
            player.setMediaSource(buildMediaSource(attempt.url), pendingResumeAtMs)
            player.prepare()
            player.playWhenReady = !pendingResumeParked
            clearPendingResume()
        } else {
            player.setMediaSource(buildMediaSource(attempt.url), /* resetPosition = */ true)
            player.prepare()
            player.playWhenReady = true
        }
        exoView?.player = player
    }

    /** v1.19.12 — the bake was consumed by an attempt; only the baked-key
     *  tracker stays alive so a failure of attempt 0 can be reported. */
    private fun clearPendingResume() {
        pendingResumeAtMs = -1L
        pendingResumeParked = false
    }

    /**
     * v1.12.4 — LivePlayActivity.playVideo's source construction, verbatim:
     * ```java
     * if (content_url.contains("m3u8")) {
     *     player.setMediaSource(new HlsMediaSource.Factory(dataSourceFactory)
     *         .createMediaSource(mediaItem), true);
     * } else {
     *     CacheDataSource.Factory flags = new CacheDataSource.Factory()
     *         .setCache(GioTVApp.getInstance().simpleCache)
     *         .setUpstreamDataSourceFactory(dataSourceFactory).setFlags(2);
     *     player.setMediaSource(new ProgressiveMediaSource.Factory(flags)
     *         .createMediaSource(mediaItem), true);
     * }
     * ```
     * Our upstream is the shared OkHttp client (pooled connections + the
     * interceptor's per-host media UA) instead of the reference's
     * DefaultHttpDataSource — the v1.2.0 architecture decision, unchanged.
     */
    private fun buildMediaSource(url: String): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT))
        val item = MediaItem.Builder().setUri(url).build()
        if (url.contains("m3u8")) {
            return HlsMediaSource.Factory(httpFactory).createMediaSource(item)
        }
        // v1.19.13 — LOOPBACK (Saved Videos) URLs BYPASS the disk cache:
        // the served file IS local storage — routing a multi-GB save through
        // the 512 MB LRU cache doubles the disk IO, evicts the live streams'
        // seamless-restart window, and (the actual bug) could grind a big
        // save past the 15 s first-attempt timeout, dropping the open to the
        // VLC fallback whose session has no bottom time bar at all. Plain
        // progressive over OkHttp is strictly faster for a local file.
        if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
            return ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
        }
        val cache = appCache() ?: return ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(item)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(item)
    }

    /** The app-scoped media disk cache (IPTVApp.exoCache); null-safe when
     *  the context is not our application (defensive — never in practice). */
    private fun appCache(): androidx.media3.datasource.cache.SimpleCache? =
        (context.applicationContext as? IPTVApp)?.exoCache

    private fun createExoPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs        */ 1024,
                /* maxBufferMs        */ 65536,
                /* bufferForPlaybackMs        */ 400,
                /* bufferForPlaybackAfterRebufferMs */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // v1.12.4 — keep the TIME cap the binding constraint. media3's
            // shouldContinueLoading (verified against the 1.5.1 bytecode)
            // PAUSES the loader when the allocator reaches its resolution-
            // based target bytes — a few MB on a 1080p TS, i.e. a handful of
            // buffered seconds — which recreates the idle-connection cut the
            // v1.12.2 time thresholds were meant to eliminate. The reference's
            // ExoPlayer2 uses per-renderer byte defaults so large the 65 s
            // time cap always binds first; a 256 MB target reproduces exactly
            // that: the loader reads continuously until 65.5 s sit in the
            // buffer, RAM stays bounded by (65.5 s × bitrate), and the disk
            // cache (exoCache) bridges whatever cut still happens.
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .build()

        // v1.12.2 — the reference's LIVE stability secret (LivePlayActivity
        // playVideo: setBufferDurationsMs(1024, 65536, …)): with our old
        // (8000, 20000) pair, the loader PAUSED mid-live-stream as soon as
        // 20s of content sat in the buffer — the stalker CDN proxy then
        // idle-timed-out the connection, and the buffer-drain resume opened
        // a Range request the live edge refuses — the "channel plays ~20
        // seconds then stops" bug. With min=1024 / max=65536 the loader
        // reads CONTINUOUSLY for a live 1x stream (the buffer never nears
        // the 65s ceiling), the connection never idles, and a burst-happy
        // CDN has 3x more head-room before a pause. Start thresholds stay
        // our faster 400/1000 (zap latency wins).
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT))
        // Cache-first for repeated connections, HTTP for everything else
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        player.addListener(createExoListener(player))
        player.addAnalyticsListener(createFirstFrameListener(player))
        return player
    }

    private fun createExoListener(player: ExoPlayer) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (player !== activeExo || currentEngine != Engine.EXO) return
            Log.w(TAG, "exo error: ${error.errorCodeName}")
            // v1.12.2 — the reference's restart pair, plus our extension:
            // a stream that was PLAYING and died mid-flight is a CDN cut —
            // restart it (fresh resolve via onStreamEnded), do not treat it
            // as an engine failure. BehindLiveWindow (1002) is the
            // reference's verbatim gate; the "playing" gate extends the
            // same recovery to the IO errors of a failed resume-after-pause.
            val recoverable = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                (successNotified && restartOnEnd)
            if (recoverable && restartBudget.shouldRestart(SystemClock.elapsedRealtime())) {
                Log.i(TAG, "stream died while playing (${error.errorCodeName}) — auto-restart")
                listener.onStreamEnded()
                return
            }
            advanceChain(generation, "exo error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (player !== activeExo || currentEngine != Engine.EXO) return
            when (playbackState) {
                Player.STATE_BUFFERING -> listener.onBuffering(true, null)
                Player.STATE_READY -> listener.onBuffering(false, null)
                Player.STATE_ENDED -> {
                    // v1.12.2 — the reference's PlayerEventListener:
                    // onPlaybackStateChanged(4) hides its progress bar FIRST
                    // (setVisibility(8)), THEN replays via playChannelByPosition
                    // — the brief ENDED blink must not stack with the restart's
                    // own spinner. v1.12.4: same order here.
                    listener.onBuffering(false, null)
                    // v1.12.2 — the reference's PlayerEventListener:
                    // onPlaybackStateChanged(4) REPLAYS the channel. Only a
                    // stream that was actually playing restarts (a finite
                    // movie's natural end is governed by restartOnEnd); the
                    // strike budget keeps a dead portal from looping.
                    if (restartOnEnd && successNotified) {
                        if (restartBudget.shouldRestart(SystemClock.elapsedRealtime())) {
                            Log.i(TAG, "live stream ENDED — auto-restart")
                            listener.onStreamEnded()
                        } else {
                            listener.onFatalError()
                        }
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player !== activeExo || currentEngine != Engine.EXO) return
            if (isPlaying && !wasPlaying && !successNotified) {
                markSuccess(Engine.EXO)
            }
            wasPlaying = isPlaying
            // v1.19.11 — forward the player's LIVE state, not the queued
            // parameter: markSuccess → onPlaying may have just PARKED the
            // player (the open-paused resume) inside this very delivery, so
            // the parameter can be a stale "true" that would flip the UI's
            // play/pause icon back to the playing form while the video sits
            // parked. The live read is also the more truthful value whenever
            // delivery lags a rapid play/pause toggle — it converges to NOW.
            listener.onPlayPauseChanged(player.isPlaying)
        }
    }

    private fun createFirstFrameListener(player: ExoPlayer) = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            if (player !== activeExo || currentEngine != Engine.EXO) return
            val ttff = SystemClock.elapsedRealtime() - playStartMs
            Log.i(TAG, "TTFF exo=${ttff}ms url=${currentAttempt?.url?.take(60)}")
            markSuccess(Engine.EXO, ttff)
        }
    }

    // ── VLC path ────────────────────────────────────────────────

    private fun ensureVlc(): MediaPlayer? {
        if (vlcPlayer != null) return vlcPlayer
        if (vlcUnavailable) return null
        val options = arrayListOf(
            "--network-caching=$VLC_NETWORK_CACHING_MS",
            "--live-caching=$VLC_NETWORK_CACHING_MS",
            "--file-caching=$VLC_NETWORK_CACHING_MS",
            "--drop-late-frames",
            "--skip-frames",
            "--no-osd"
        )
        return try {
            val lib = LibVLC(context, options)
            libVlc = lib
            val player = MediaPlayer(lib)
            player.setEventListener(vlcEventListener)
            vlcPlayer = player
            requestAudioFocus()
            player
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (16KB-page devices / missing natives) or
            // NoClassDefFoundError (lite build) — remember and degrade to Exo.
            vlcUnavailable = true
            libVlc = null
            vlcPlayer = null
            Log.e(TAG, "VLC engine unavailable on this device — Exo-only mode", t)
            null
        }
    }

    private val vlcEventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                if (currentEngine == Engine.VLC && !successNotified) {
                    val ttff = SystemClock.elapsedRealtime() - playStartMs
                    Log.i(TAG, "TTFF vlc=${ttff}ms")
                    markSuccess(Engine.VLC, ttff)
                }
                listener.onPlayPauseChanged(true)
            }
            MediaPlayer.Event.Vout -> {
                if (currentEngine == Engine.VLC && !successNotified) {
                    val ttff = SystemClock.elapsedRealtime() - playStartMs
                    markSuccess(Engine.VLC, ttff)
                }
            }
            MediaPlayer.Event.EndReached -> {
                // v1.12.2 — same recovery as the Exo path: a PLAYING live
                // stream that ends is a server-side cut — restart it.
                if (currentEngine == Engine.VLC && restartOnEnd && successNotified &&
                    restartBudget.shouldRestart(SystemClock.elapsedRealtime())
                ) {
                    Log.i(TAG, "vlc stream ENDED — auto-restart")
                    listener.onStreamEnded()
                }
            }
            MediaPlayer.Event.Buffering -> {
                if (currentEngine == Engine.VLC) {
                    val percent = event.buffering.toInt()
                    listener.onBuffering(percent < 100, percent.takeIf { it in 0..100 })
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                if (currentEngine == Engine.VLC) {
                    advanceChain(generation, "vlc error")
                }
            }
            MediaPlayer.Event.Paused -> listener.onPlayPauseChanged(false)
        }
    }

    private fun playVlc(attempt: PlayAttempt, gen: Long) {
        // Exo must yield the screen
        try { activeExo?.stop() } catch (e: Exception) { Log.w(TAG, "exo yield", e) }

        val player = ensureVlc()
            ?: run { advanceChain(gen, "vlc unavailable"); return }
        val lib = libVlc ?: return
        try {
            player.stop()
            val media = Media(lib, Uri.parse(attempt.url))
            media.setHWDecoderEnabled(true, false)
            player.setMedia(media)
            media.release()

            val layout = vlcView
            if (layout != null && !vlcAttached) {
                // textureView = true: VLC renders into a TextureView instead of a
                // SurfaceView. CRITICAL FIX: a second SurfaceView stacked above the
                // ExoPlayer's SurfaceView is composited by the system in creation
                // order — an idle/black VLC surface sits ON TOP of the Exo video
                // ("black screen but audio"). A TextureView is a normal view in the
                // hierarchy: transparent when VLC isn't rendering, zero z-order
                // conflicts, and it never covers ExoPlayer's video.
                player.attachViews(layout, null, false, true)
                vlcAttached = true
            }
            player.play()
        } catch (t: Throwable) {
            Log.w(TAG, "vlc play failed", t)
            advanceChain(gen, "vlc exception")
        }
    }

    private fun stopAndDetachVlc() {
        try {
            vlcPlayer?.let {
                it.stop()
                if (vlcAttached) {
                    it.detachViews()
                    vlcAttached = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "vlc detach", e)
        }
    }

    // ── Success / stats ─────────────────────────────────────────

    private fun markSuccess(engine: Engine, ttffMs: Long? = null) {
        // v1.19.12 — fire ONCE per attempt chain: onRenderedFirstFrame
        // re-fires after every seek-driven position reset, and the second
        // delivery used to re-run onPlaying's optimistic isPlaying=true
        // publish + re-arm the resume phase — the exact race that made the
        // parked-open's play button untrustworthy.
        if (successNotified) return
        successNotified = true
        restartBudget.onStable(SystemClock.elapsedRealtime())
        timeoutJob?.cancel()
        timeoutJob = null
        currentAttempt?.let { attempt ->
            listener.onFirstFrame(engine, ttffMs ?: (SystemClock.elapsedRealtime() - playStartMs), attempt)
        }
        listener.onPlaying(engine)
    }

    // ── Audio focus (for VLC — ExoPlayer manages its own) ────────

    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        abandonAudioFocus()
        val attrs = AndroidAudioAttributes.Builder()
            .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
            .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> vlcPlayer?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (currentEngine == Engine.VLC && !isPlaying) vlcPlayer?.play()
                    }
                }
            }
            .build()
        audioFocusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        private const val TAG = "SmartPlayer"
        private const val USER_AGENT = "IPTVPlayer/1.0 (Android; Media3+LibVLC)"

        /** v1.12.4 — see createExoPlayer's KDoc: large enough that the
         *  65.5 s TIME cap binds before the byte cap, like the reference. */
        private const val TARGET_BUFFER_BYTES = 256 * 1024 * 1024

        private const val FIRST_ATTEMPT_TIMEOUT_MS = 4500L
        private const val MID_ATTEMPT_TIMEOUT_MS = 4500L
        private const val LAST_ATTEMPT_TIMEOUT_MS = 9000L
        private const val VLC_NETWORK_CACHING_MS = 400
    }
}

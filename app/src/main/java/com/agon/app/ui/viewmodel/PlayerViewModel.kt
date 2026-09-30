package com.agon.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agon.app.data.PlaybackPositionManager
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.agon.app.data.repository.SubtitleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PlayerViewModel
 *
 * Extracted from PlayerActivity as part of the MVVM refactoring.
 *
 * Owns three concerns that previously polluted the Compose layer:
 *
 *   1. Binge-Watching Engine
 *      - Auto-resume: seeks ExoPlayer to the last saved position on VOD/Series.
 *      - Periodic save: writes current position to PlaybackPositionManager every 5s.
 *      - Auto-next-episode: detects the 60s pre-end window, surfaces a countdown,
 *        and (when count reaches zero or user confirms) hands the next MediaItem
 *        back to the player via [SwitchToStream] events.
 *
 *   2. Fallback Stream Routing
 *      - When ExoPlayer reports a [PlaybackException], attempts up to [MAX_RECONNECTS]
 *        reconnects. On attempts 2..4 it tries a fuzzy-name-matched fallback stream
 *        from [SessionData.allStreams] before falling back to a plain retry.
 *      - After [MAX_RECONNECTS] exhausted failures, raises [isFatalError].
 *
 *   3. Subtitle Fetching State
 *      - Resolves a remote subtitle URL via [SubtitleRepository.fetchSubtitleUri].
 *      - Downloads + decompresses via [SubtitleRepository.downloadAndExtractSubtitle].
 *      - Emits a [SubtitleResult] that the UI layer consumes to inject the .srt
 *        into ExoPlayer's MediaItem.SubtitleConfiguration.
 *
 * Communication contract with the UI layer (PlayerActivity):
 *
 *   UI → ViewModel:
 *     - bindExoPlayer(exoPlayer)         : once after the player is created.
 *     - onStreamChanged(url, name)       : when the user switches streams via Mini-Surfer.
 *     - onPlayerError()                  : forwarded from Player.Listener.onPlayerError.
 *     - onPlaybackStateChanged(state)    : forwarded from Player.Listener.
 *     - onIsPlayingChanged(playing)      : forwarded from Player.Listener.
 *     - onFatalRetry()                   : user tapped Retry on the fatal-error overlay.
 *     - requestSubtitle(languageCode)    : user picked a subtitle language.
 *     - clearSubtitle()                  : user turned subtitles off.
 *     - playNextEpisode()                : user tapped "Play Next" on the binge overlay.
 *
 *   ViewModel → UI (via StateFlow):
 *     - uiState    : the full snapshot of player UI state.
 *     - events     : one-shot commands the UI must execute against ExoPlayer
 *                    (e.g. "switch to this URL", "inject this subtitle Uri").
 */
class PlayerViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        /** Maximum reconnect attempts before declaring a fatal playback error. */
        const val MAX_RECONNECTS = 5

        /** Time (ms) before end-of-content at which the next-episode countdown appears. */
        const val NEXT_EPISODE_WINDOW_MS = 60_000L

        /** Min duration (ms) for content to be considered VOD (not live). */
        const val MIN_VOD_DURATION_MS = 60_000L

        /** Upper bound (ms) for VOD duration. Live streams report 24h+. */
        const val MAX_VOD_DURATION_MS = 86_400_000L

        /** Min saved position (ms) worth restoring. */
        const val MIN_RESTORE_POSITION_MS = 5_000L
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EXOPLAYER REFERENCE (set by UI)
    // ══════════════════════════════════════════════════════════════════════

    private var exoPlayerRef: androidx.media3.common.Player? = null
    private var positionManager: PlaybackPositionManager? = null
    private var triedFallbackUrls: MutableSet<String> = mutableSetOf()

    private var resumeJob: Job? = null
    private var savePositionJob: Job? = null
    private var bingeWatchJob: Job? = null

    /**
     * Active flag — when false, the auto-reconnect logic in [onPlayerError]
     * is suppressed so a paused/stopped player cannot be silently restarted
     * by a delayed coroutine in [viewModelScope].
     *
     * The host Activity sets this to false in onPause()/onDestroy() so the
     * singleton ExoPlayer stays paused when the user leaves (BACK gesture,
     * HOME button, system navigation). Without this guard, a 2-second
     * delayed `player.prepare()` in onPlayerError() could unpause the
     * player AFTER the user exited, causing the audio to leak.
     */
    @Volatile
    private var playbackActive: Boolean = true

    // ══════════════════════════════════════════════════════════════════════
    //  STATE
    // ══════════════════════════════════════════════════════════════════════

    data class PlayerUiState(
        val isPlaying: Boolean = false,
        val isLoading: Boolean = true,
        val hasError: Boolean = false,
        val isFatalError: Boolean = false,
        val reconnectCount: Int = 0,
        val currentStreamUrl: String = "",
        val currentStreamName: String = "",
        val fallbackOverlay: String? = null,
        val subtitleOverlay: String? = null,
        val isSubtitleLoading: Boolean = false,
        val nextEpisodeCountdown: Int = -1,
        val nextEpisodeName: String = "",
        val nextEpisodeUrl: String = "",
        val positionRestored: Boolean = false
    )

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    //  V8.5 — PLAYBACK GENERATION COUNTER (same-URL replay fix)
    //  ══════════════════════════════════════════════════════════════════════
    //  The UI's playback sink is a LaunchedEffect keyed on currentStreamUrl.
    //  When the user replays the SAME channel (same URL), the URL key does
    //  not change so Compose does NOT re-launch the effect — playStream()
    //  is never called and the channel won't start a second time.
    //
    //  This counter increments on EVERY onStreamChanged() call (even when
    //  the URL is identical), so the UI can add it as a LaunchedEffect key
    //  and force the sink to re-fire for replays.
    // ══════════════════════════════════════════════════════════════════════
    private val _playbackGeneration = MutableStateFlow(0L)
    val playbackGeneration: StateFlow<Long> = _playbackGeneration.asStateFlow()

    // One-shot events for the UI to act on (e.g. "switch player to this URL").
    sealed class PlayerEvent {
        data class SwitchToStream(
            val url: String,
            val isLive: Boolean
        ) : PlayerEvent()

        data class InjectSubtitle(
            val uri: Uri,
            val languageCode: String,
            val languageLabel: String
        ) : PlayerEvent()

        /** Clear subtitles from the current MediaItem. */
        object ClearSubtitle : PlayerEvent()

        /** Clear saved position for the given URL (next-episode watch completed). */
        data class ClearPosition(val url: String) : PlayerEvent()
    }

    private val _events = MutableStateFlow<PlayerEvent?>(null)
    val events: StateFlow<PlayerEvent?> = _events.asStateFlow()

    /** Consumed by the UI to mark the latest event as handled. */
    fun consumeEvent() { _events.value = null }

    // ══════════════════════════════════════════════════════════════════════
    //  BINDING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Binds the ViewModel to a freshly created ExoPlayer instance.
     * Called exactly once per player lifetime from the Compose layer.
     */
    fun bindExoPlayer(exoPlayer: androidx.media3.common.Player) {
        exoPlayerRef = exoPlayer
        positionManager = PlaybackPositionManager.getInstance(getApplication())
        triedFallbackUrls.clear()
    }

    /**
     * Toggle the playback-active flag. Called by the host Activity:
     *   - onResume / onCreate → true  (auto-reconnect permitted)
     *   - onPause / onDestroy → false (auto-reconnect suppressed)
     *
     * This is the SECOND line of defense against audio leaks. The first
     * line is GlobalPlaybackCoordinator.pause() in onPause(). Even if a
     * delayed coroutine in onPlayerError() fires after pause(), the
     * `playbackActive == false` check will prevent it from calling
     * player.prepare() / playWhenReady = true.
     */
    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (!active) {
            // Cancel any in-flight auto-reconnect coroutine immediately.
            // The user has left — no more retry attempts.
            resumeJob?.cancel()
            savePositionJob?.cancel()
            bingeWatchJob?.cancel()
        }
    }

    /**
     * Notifies the ViewModel that the current stream has changed (either because
     * the user picked a new one from Mini-Surfer or because the next episode
     * auto-played).
     */
    fun onStreamChanged(url: String, name: String) {
        _uiState.value = _uiState.value.copy(
            currentStreamUrl = url,
            currentStreamName = name,
            reconnectCount = 0,
            isFatalError = false,
            hasError = false,
            isLoading = true,
            nextEpisodeCountdown = -1,
            nextEpisodeName = "",
            nextEpisodeUrl = "",
            positionRestored = false
        )
        // V8.5 — bump the generation so the UI's LaunchedEffect re-fires
        // even when the URL is unchanged (same-channel replay).
        _playbackGeneration.value = _playbackGeneration.value + 1L
        triedFallbackUrls.clear()
        // Re-arm auto-resume + binge-watch loops for the new stream.
        startAutoResume()
        startBingeWatchLoop()
        startPositionSaveLoop()
    }

    /**
     * Silent state sync — clears the loading indicator WITHOUT triggering
     * a re-prepare or re-scrape.
     *
     * ════════════════════════════════════════════════════════════════════════
     *  FIX: Infinite Loading Indicator (State Sync Fix)
     * ════════════════════════════════════════════════════════════════════════
     *
     *  PROBLEM: When the user zooms in from the Feed, the ViewModel is
     *  initialized with isLoading = true (via onStreamChanged). But the
     *  player is ALREADY STATE_READY (the Feed was just playing this URL
     *  on the same singleton ExoPlayer). Since the state doesn't
     *  transition (READY → READY), no onPlaybackStateChanged event fires,
     *  and the spinner keeps spinning forever over a perfectly playing
     *  video.
     *
     *  SOLUTION: When the UI detects that the player is already playing
     *  (reusePlayer == true OR exoPlayer.isPlaying == true), it calls
     *  this method to force-clear the loading flag. The spinner
     *  disappears instantly on launch.
     */
    fun onStreamReady() {
        val current = _uiState.value
        if (current.isLoading || current.hasError) {
            _uiState.value = current.copy(
                isLoading = false,
                hasError = false,
                isFatalError = false,
                // Mark position as restored so the auto-resume loop doesn't
                // try to seek after we've already declared the stream ready.
                positionRestored = true
            )
        }
    }

    fun onIsPlayingChanged(playing: Boolean) {
        _uiState.value = _uiState.value.copy(isPlaying = playing)
        if (playing) startPositionSaveLoop() else savePositionNow()
    }

    fun onPlaybackStateChanged(state: Int) {
        // V8.6.3 — Only update isLoading if transitioning to READY or BUFFERING.
        // If it goes to STATE_IDLE (due to p.stop() in playStream before scrape),
        // we keep the previous isLoading state (which was set to true by onStreamChanged).
        val currentlyLoading = _uiState.value.isLoading
        val newIsLoading = when (state) {
            androidx.media3.common.Player.STATE_BUFFERING -> true
            androidx.media3.common.Player.STATE_READY -> false
            androidx.media3.common.Player.STATE_ENDED -> false
            else -> currentlyLoading // Keep previous state for STATE_IDLE
        }

        // Do NOT set hasError here. Real errors are surfaced exclusively via onPlayerError().
        _uiState.value = _uiState.value.copy(
            isLoading = newIsLoading
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AUTO-FALLBACK STREAM ROUTING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called from the UI's Player.Listener.onPlayerError callback.
     * Drives the auto-reconnect + fallback-stream logic.
     */
    fun onPlayerError() {
        val state = _uiState.value
        _uiState.value = state.copy(hasError = true, isLoading = false)

        // AUDIO LEAK GUARD — if the host Activity has paused/destroyed
        // the player (user pressed BACK / HOME), DO NOT auto-reconnect.
        // The singleton would otherwise be silently restarted by this
        // coroutine, causing audio to leak in the background.
        if (!playbackActive) {
            android.util.Log.i("PlayerViewModel",
                "onPlayerError suppressed — playback not active (user left)")
            return
        }

        viewModelScope.launch {
            val player = exoPlayerRef ?: return@launch
            val currentReconnect = _uiState.value.reconnectCount

            if (currentReconnect >= MAX_RECONNECTS) {
                _uiState.value = _uiState.value.copy(isFatalError = true)
                return@launch
            }

            delay(2000)

            // Double-check the active flag AFTER the delay — the user may
            // have pressed BACK during the 2-second window.
            if (!playbackActive) {
                android.util.Log.i("PlayerViewModel",
                    "Reconnect cancelled after delay — playback not active")
                return@launch
            }

            // First attempt: just retry the same URL.
            if (currentReconnect == 0) {
                _uiState.value = _uiState.value.copy(
                    hasError = false,
                    isLoading = true,
                    reconnectCount = currentReconnect + 1
                )
                player.prepare()
                player.playWhenReady = true
                return@launch
            }

            // Attempts 1..MAX-2: try a fuzzy fallback stream if available.
            if (currentReconnect in 1 until MAX_RECONNECTS - 1) {
                val cleanName = SubtitleRepository.stripQualityTags(_uiState.value.currentStreamName)
                val fallback = findBestFallbackStream(
                    cleanName = cleanName,
                    currentUrl = _uiState.value.currentStreamUrl,
                    triedUrls = triedFallbackUrls
                )
                if (fallback != null) {
                    triedFallbackUrls.add(fallback.url)
                    _uiState.value = _uiState.value.copy(
                        currentStreamUrl = fallback.url,
                        hasError = false,
                        isLoading = true,
                        reconnectCount = currentReconnect + 1,
                        fallbackOverlay = "Auto-switching to: ${fallback.name}"
                    )
                    _events.value = PlayerEvent.SwitchToStream(fallback.url, isLive = false)
                    delay(3000)
                    _uiState.value = _uiState.value.copy(fallbackOverlay = null)
                    return@launch
                }
            }

            // No fallback available — plain retry.
            _uiState.value = _uiState.value.copy(
                hasError = false,
                isLoading = true,
                reconnectCount = currentReconnect + 1
            )
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Called when the user taps Retry on the fatal-error overlay. */
    fun onFatalRetry() {
        val player = exoPlayerRef ?: return
        // User-initiated retry — re-arm the active flag (the UI is in
        // the foreground, so auto-reconnect is safe again).
        playbackActive = true
        _uiState.value = _uiState.value.copy(
            reconnectCount = 0,
            isFatalError = false,
            hasError = false,
            isLoading = true
        )
        triedFallbackUrls.clear()
        player.prepare()
        player.playWhenReady = true
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BINGE-WATCHING: Auto-Resume
    // ══════════════════════════════════════════════════════════════════════

    private fun startAutoResume() {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val player = exoPlayerRef ?: return@launch
            val posMgr = positionManager ?: return@launch
            // V8.6 — Reduced from 1500ms to 400ms. The original 1500ms
            // delay was excessive and delayed the positionRestored flag,
            // which gates startPositionSaveLoop. For live streams (the
            // common case for this app) auto-resume is skipped entirely
            // (live has no duration), so the delay only affects VOD.
            // 400ms is enough for ExoPlayer to load metadata.
            delay(400)
            if (_uiState.value.positionRestored) return@launch

            val dur = player.duration
            val isLive = player.isCurrentMediaItemLive
            // V8.6 — Skip auto-resume entirely for live streams. Live
            // streams report TIME_UNSET or 24h+ durations and have no
            // meaningful "position" to restore. This avoids pointless
            // work and lets positionRestored flip to true immediately
            // for live, so the position-save loop can start (it will
            // also no-op for live).
            if (dur > 0 && !isLive && dur < MAX_VOD_DURATION_MS) {
                val saved = withContext(Dispatchers.IO) {
                    posMgr.getPosition(getApplication(), _uiState.value.currentStreamUrl)
                }
                if (saved > MIN_RESTORE_POSITION_MS) {
                    player.seekTo(saved)
                }
            }
            _uiState.value = _uiState.value.copy(positionRestored = true)
        }
    }

    private fun startPositionSaveLoop() {
        savePositionJob?.cancel()
        val player = exoPlayerRef ?: return
        val posMgr = positionManager ?: return
        if (!_uiState.value.isPlaying || !_uiState.value.positionRestored) return

        savePositionJob = viewModelScope.launch {
            while (_uiState.value.isPlaying) {
                delay(5000)
                if (!_uiState.value.isPlaying) break
                val dur = player.duration
                val isLive = player.isCurrentMediaItemLive
                if (dur > 0 && !isLive && dur < MAX_VOD_DURATION_MS) {
                    val pos = player.currentPosition
                    val url = _uiState.value.currentStreamUrl
                    withContext(Dispatchers.IO) {
                        posMgr.savePosition(getApplication(), url, pos)
                    }
                }
                // V9.7 — Feed the Watch History engine so recommendations work.
                // recordWatch is safe to call for live streams too (it tracks
                // group affinity + hour×group matrix, which power the
                // "Smart Zapping" recommendations in SilinaFeed).
                try {
                    val stream = com.agon.app.data.model.StreamItem(
                        name = _uiState.value.currentStreamName,
                        url = _uiState.value.currentStreamUrl,
                        logo = "",
                        group = com.agon.app.data.model.SessionData.allStreams
                            .firstOrNull { it.url == _uiState.value.currentStreamUrl }?.group ?: ""
                    )
                    com.agon.app.recommendation.WatchHistoryManager.recordWatch(
                        getApplication(), stream, 5L
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private fun savePositionNow() {
        savePositionJob?.cancel()
        val player = exoPlayerRef ?: return
        val posMgr = positionManager ?: return
        val dur = player.duration
        val isLive = player.isCurrentMediaItemLive
        if (dur > 0 && !isLive && dur < MAX_VOD_DURATION_MS) {
            val pos = player.currentPosition
            val url = _uiState.value.currentStreamUrl
            viewModelScope.launch(Dispatchers.IO) {
                posMgr.savePosition(getApplication(), url, pos)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BINGE-WATCHING: Auto-Next Episode
    // ══════════════════════════════════════════════════════════════════════

    private fun startBingeWatchLoop() {
        bingeWatchJob?.cancel()
        val player = exoPlayerRef ?: return
        bingeWatchJob = viewModelScope.launch {
            // Reset next-episode state for the new stream.
            _uiState.value = _uiState.value.copy(
                nextEpisodeCountdown = -1,
                nextEpisodeName = "",
                nextEpisodeUrl = ""
            )
            while (_uiState.value.isPlaying) {
                delay(1000)
                val dur = player.duration
                val pos = player.currentPosition
                val isLive = player.isCurrentMediaItemLive

                if (dur > MIN_VOD_DURATION_MS && !isLive && dur < MAX_VOD_DURATION_MS) {
                    val remaining = dur - pos
                    if (remaining in 1..NEXT_EPISODE_WINDOW_MS && _uiState.value.nextEpisodeCountdown < 0) {
                        val nextEp = findNextEpisode(
                            _uiState.value.currentStreamName,
                            _uiState.value.currentStreamUrl
                        )
                        if (nextEp != null) {
                            _uiState.value = _uiState.value.copy(
                                nextEpisodeName = nextEp.name,
                                nextEpisodeUrl = nextEp.url,
                                nextEpisodeCountdown = (remaining / 1000).toInt()
                            )
                        }
                    }
                    if (_uiState.value.nextEpisodeCountdown > 0) {
                        val cd = (remaining / 1000).toInt()
                        _uiState.value = _uiState.value.copy(nextEpisodeCountdown = cd)
                        if (cd <= 0) {
                            // Auto-play next episode inline.
                            val nextUrl = _uiState.value.nextEpisodeUrl
                            val nextName = _uiState.value.nextEpisodeName
                            if (nextUrl.isNotBlank()) {
                                _events.value = PlayerEvent.ClearPosition(_uiState.value.currentStreamUrl)
                                onStreamChanged(nextUrl, nextName)
                                _events.value = PlayerEvent.SwitchToStream(nextUrl, isLive = false)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Called when the user manually taps "Play Next" on the binge overlay. */
    fun playNextEpisode() {
        val nextUrl = _uiState.value.nextEpisodeUrl
        val nextName = _uiState.value.nextEpisodeName
        if (nextUrl.isBlank()) return
        _events.value = PlayerEvent.ClearPosition(_uiState.value.currentStreamUrl)
        onStreamChanged(nextUrl, nextName)
        _events.value = PlayerEvent.SwitchToStream(nextUrl, isLive = false)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SUBTITLE STATE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves and downloads a subtitle for the given language code.
     * Emits a [PlayerEvent.InjectSubtitle] when the .srt is ready locally.
     */
    fun requestSubtitle(languageCode: String, languageLabel: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubtitleLoading = true)
            val movieName = SubtitleRepository.extractCleanMovieName(_uiState.value.currentStreamName)

            // Step 1: Fetch subtitle URL from the Production Microservice API
            val (subtitleUrl, errMsg) = SubtitleRepository.fetchSubtitleUri(movieName, languageCode)

            if (subtitleUrl == null) {
                _uiState.value = _uiState.value.copy(
                    isSubtitleLoading = false,
                    subtitleOverlay = errMsg ?: "No ${languageLabel} subtitles found."
                )
                delay(4000)
                _uiState.value = _uiState.value.copy(subtitleOverlay = null)
                return@launch
            }

            // Step 2: Download & decompress the subtitle (.gz → .srt)
            val localUri = SubtitleRepository.downloadAndExtractSubtitle(subtitleUrl, getApplication())
            _uiState.value = _uiState.value.copy(isSubtitleLoading = false)

            if (localUri != null) {
                _events.value = PlayerEvent.InjectSubtitle(localUri, languageCode, languageLabel)
                _uiState.value = _uiState.value.copy(subtitleOverlay = "Subtitles: $languageLabel")
                delay(3000)
                _uiState.value = _uiState.value.copy(subtitleOverlay = null)
            } else {
                _uiState.value = _uiState.value.copy(subtitleOverlay = "Failed to download $languageLabel subtitle.")
                delay(4000)
                _uiState.value = _uiState.value.copy(subtitleOverlay = null)
            }
        }
    }

    /** Tells the UI to remove subtitles from the current MediaItem. */
    fun clearSubtitle() {
        _events.value = PlayerEvent.ClearSubtitle
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FALLBACK: FUZZY NAME MATCHING (internal)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Finds the next episode in the same group/series as the current stream.
     * Returns the stream immediately after the current one in the sorted list
     * of same-group streams, or the first one if the current is not found.
     */
    private fun findNextEpisode(currentName: String, currentUrl: String): StreamItem? {
        val currentGroup = SessionData.allStreams
            .firstOrNull { it.url == currentUrl }?.group ?: return null

        val sameGroup = SessionData.allStreams
            .filter { it.group == currentGroup && it.url != currentUrl }
            .sortedBy { it.name.lowercase() }

        val currentIndex = sameGroup.indexOfFirst {
            it.name.lowercase() == currentName.lowercase()
        }

        return if (currentIndex >= 0 && currentIndex < sameGroup.size - 1) {
            sameGroup[currentIndex + 1]
        } else if (sameGroup.isNotEmpty()) {
            sameGroup.first()
        } else null
    }

    /**
     * Levenshtein distance between two strings — used to score fuzzy name matches
     * when picking a fallback stream.
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    /**
     * Picks the best fuzzy-matched fallback stream from [SessionData.allStreams].
     * Skips the current URL and any URLs already tried. Returns null if no
     * candidate is close enough (Levenshtein distance > 40% of name length).
     */
    private fun findBestFallbackStream(
        cleanName: String,
        currentUrl: String,
        triedUrls: Set<String>
    ): StreamItem? {
        val candidates = SessionData.allStreams.filter { stream ->
            stream.url != currentUrl &&
            !triedUrls.contains(stream.url) &&
            stream.name.isNotBlank()
        }
        if (candidates.isEmpty()) return null

        var bestMatch: StreamItem? = null
        var bestScore = Int.MAX_VALUE

        for (candidate in candidates) {
            val candidateClean = SubtitleRepository.stripQualityTags(candidate.name)
            val distance = levenshteinDistance(cleanName, candidateClean)
            val maxLen = maxOf(cleanName.length, candidateClean.length, 1)
            if (distance > maxLen * 0.4) continue
            if (distance < bestScore) {
                bestScore = distance
                bestMatch = candidate
            }
        }
        return bestMatch
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        resumeJob?.cancel()
        savePositionJob?.cancel()
        bingeWatchJob?.cancel()
        // V8.6 — Null out references so a stale ViewModel does not hold
        // a reference to the singleton ExoPlayer after the Activity is
        // destroyed. The player itself survives (singleton); only our
        // reference is released.
        exoPlayerRef = null
        positionManager = null
    }
}

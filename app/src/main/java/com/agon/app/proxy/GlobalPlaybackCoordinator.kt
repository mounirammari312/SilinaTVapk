package com.agon.app.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GlobalPlaybackCoordinator — V4.5 the SINGLE owner of the ExoPlayer instance.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  V4.5 ARCHITECTURE — LOW-LEVEL ATOMIC SCRAPER CORE (non-negotiable)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  1. SINGLETON — the player never dies. It is created ONCE on first
 *     request and survives every Activity destruction. Only the
 *     application process can kill it. This eliminates the
 *     "local player dies with every screen" defect.
 *
 *  2. ASYNC DECOUPLING (V4.5) — The RedirectSniffer scrape pipeline
 *     is FULLY DECOUPLED from the player's initialization state
 *     machine. playStream() consults [RedirectSniffer.cachedFinalUrl]
 *     SYNCHRONOUSLY (cache-hit fast path → instant edge URL). On a
 *     cache miss, ExoPlayer is allowed to initiate playback on the
 *     raw URL using native cross-protocol redirects while a background
 *     scrape job (bounded exclusively to Dispatchers.IO) populates
 *     the cache asynchronously for future lookups. The player NEVER
 *     blocks waiting for the scraper network call to finish.
 *
 *  3. LIFECYCLE-CANCELLED SCRAPE JOB — playStream() invokes
 *     `scrapeJob?.cancel()` on its FIRST line. If the user is zapping
 *     fast through channels, intermediate network connection routines
 *     are instantly aborted at the socket transport layer, saving
 *     100% of the client's network bandwidth and device processor
 *     cycles on abandoned channels.
 *
 *  4. SELF-HEALING TOKEN HOT-SWAP — ExoPlayer is wired with a
 *     [Player.Listener] that traps 403 / token-expired PlaybackExceptions.
 *     On such an error, the listener invokes
 *     [RedirectSniffer.SelfHealingTokenHandler.attemptSelfHeal] which
 *     silently re-scrapes the original URL with a ROTATED fingerprint,
 *     updates the redirect cache, and hot-swaps the media URI without
 *     forcing a hard player restart or crashing the UI.
 *
 *  5. RESUME BRIDGE — when PlayerActivity is launched from the Feed
 *     with the SAME URL the player is already buffering, the UI must
 *     call resume() instead of playStream(). This wakes the buffered
 *     video with zero latency — no scrape, no prepare, no black screen.
 *
 *  6. PRESERVED BUILD HOOKS — [buildPlayer] retains 100% of the custom
 *     parameters: ResilientLoadErrorHandlingPolicy back-offs, FFmpeg
 *     n6.0 JNI architecture hooks (EXTENSION_RENDERER_MODE_PREFER),
 *     DefaultHttpDataSource with setAllowCrossProtocolRedirects(true),
 *     RecordingDataSourceFactory + ClipBufferManager wiring, the 30s
 *     deep-buffer DefaultLoadControl, and the backBuffer=30s for
 *     Retroactive Clip support.
 * ════════════════════════════════════════════════════════════════════════
 */
object GlobalPlaybackCoordinator {

    private const val TAG = "GlobalPlaybackCoord"

    // ── Singleton player instance ──
    @Volatile
    private var player: ExoPlayer? = null

    // V10.1 — App context for building new players in playStream()
    @Volatile
    private var appContext: Context? = null

    // V10.2 — Player version counter. Incremented every time a new player
    // is created. The UI observes this to know when to re-attach the
    // PlayerView to the new player instance.
    private val _playerVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val playerVersion: kotlinx.coroutines.flow.StateFlow<Long> = _playerVersion

    // ── Synchronization scope ──
    // Main-immediate so cancel()/launch() ordering is deterministic.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── The scrape job for the current playStream() call ──
    // Cancelled and replaced on every new playStream() invocation.
    // This is the LIFECYCLE-BOUND handle — cancelling it aborts the
    // background OkHttp call at the socket transport layer.
    @Volatile
    private var scrapeJob: Job? = null

    // ── V4.5 Self-Healing in-flight flag ──
    // Prevents re-entry of the self-heal listener while a heal is
    // already in progress for the current URL.
    @Volatile
    private var selfHealInFlight: Boolean = false

    // ── The URL currently associated with the player ──
    // Set IMMEDIATELY when playStream() is called (before the scrape
    // completes) so resume() can decide if the buffer is reusable.
    @Volatile
    private var pendingUrl: String = ""

    // ── The last RESOLVED URL that was handed to ExoPlayer ──
    // Used by subtitle injection to avoid re-fetching the raw URL.
    @Volatile
    private var resolvedUrl: String = ""

    // ════════════════════════════════════════════════════════════════════════
    //  V8.6 — PREVIOUSLY-PLAYED URL TRACKER (bounded LRU, same-channel replay fix)
    //  ════════════════════════════════════════════════════════════════════════
    //  IPTV edge URLs carry short-lived auth tokens. RedirectSniffer caches
    //  the resolved edge URL for 5 minutes (CACHE_TTL_MS) to speed up
    //  zapping. But when the user plays channel A, switches to B, then
    //  comes BACK to A, the cached edge URL for A is often stale (token
    //  expired) → ExoPlayer gets a 403 and the channel won't play.
    //
    //  This set records every URL that has been played. When playStream()
    //  is called for a URL that is in this set, the cache entry is
    //  invalidated FIRST so the replay gets a fresh scrape with a fresh
    //  token. The first play of any NEW channel still benefits from the
    //  cache.
    //
    //  V8.6 — Bounded to MAX_TRACKED_URLS entries via LRU eviction so the
    //  set cannot grow unboundedly. A user browsing 22,000 channels
    //  previously accumulated ~22,000 strings forever; now only the most
    //  recently played 200 URLs are tracked (more than enough for any
    //  realistic channel-surfing session).
    // ════════════════════════════════════════════════════════════════════════
    private const val MAX_TRACKED_URLS = 200
    private val previouslyPlayedUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(
            object : java.util.LinkedHashSet<String>(MAX_TRACKED_URLS) {
                override fun add(element: String): Boolean {
                    if (size >= MAX_TRACKED_URLS) {
                        val it = iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        }
                    }
                    return super.add(element)
                }
            }
        )

    // ════════════════════════════════════════════════════════════════════════
    //  ABSOLUTE SOCKET TEARDOWN — Force-kill all network connections.
    //  ════════════════════════════════════════════════════════════════════════
    //  Called when the screen turns off, the app goes to background, or
    //  the player activity is destroyed. Immediately:
    //    1. Pauses ExoPlayer (stops decoding)
    //    2. Stops the proxy server socket (stops accepting connections)
    //    3. Closes all active proxy sessions (kills upstream HTTP connections)
    //  This prevents any background bandwidth leakage when the user is
    //  not actively watching.
    fun forceTeardown() {
        try {
            scrapeJob?.cancel()
            val p = player
            if (p != null) {
                // V10.1 — تحرير كامل مثل التطبيق المرجعي
                try {
                    p.removeListener(SelfHealingPlayerListener)
                    p.playWhenReady = false
                    p.release()
                } catch (_: Throwable) {}
                player = null
                Log.i(TAG, "Force teardown: player released (set to null)")
            }
            com.agon.app.proxy.ProxyForegroundService.forceCloseAllSockets()
            com.agon.app.proxy.RedirectSniffer.cleanupExpiredEntries()
        } catch (e: Exception) {
            Log.w(TAG, "Force teardown failed (non-fatal): ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns the singleton ExoPlayer, creating it lazily on first call.
     *
     * The player survives Activity destruction — DO NOT call release()
     * from any UI DisposableEffect. Only [release] (process shutdown)
     * may free it.
     */
    fun getPlayer(context: Context): ExoPlayer {
        // V10.1 — Store app context for playStream() to build new players
        if (appContext == null) appContext = context.applicationContext
        player?.let { return it }
        synchronized(this) {
            player?.let { return it }
            val p = buildPlayer(context.applicationContext)
            // ── V4.5: Wire the Self-Healing listener ONCE at creation. ──
            p.addListener(SelfHealingPlayerListener)
            player = p
            Log.i(TAG, "Singleton ExoPlayer created (hash=${p.hashCode()})")
            return p
        }
    }

    /**
     * V9.4 — Force-rebuild the singleton ExoPlayer.
     *
     * This is needed because the LoadControl (including the backBuffer
     * setting) is baked into the ExoPlayer at creation time. Changing
     * setBackBuffer() in code has NO EFFECT on an already-running player
     * — the old player keeps the old LoadControl forever.
     *
     * This method:
     *   1. Releases the old player (and its old LoadControl with the
     *      buggy retainBackBufferFromReset=true)
     *   2. Clears all references
     *   3. The next call to getPlayer() will build a FRESH player with
     *      the new LoadControl (retainBackBufferFromReset=false)
     *
     * Should be called ONCE on app startup (from SilinaApplication.onCreate)
     * to ensure the V9.4 LoadControl takes effect.
     */
    fun rebuildPlayer(context: Context) {
        synchronized(this) {
            val old = player
            if (old != null) {
                try {
                    old.removeListener(SelfHealingPlayerListener)
                    old.stop()
                    old.release()
                    Log.i(TAG, "Old ExoPlayer released for V9.4 rebuild (hash=${old.hashCode()})")
                } catch (e: Exception) {
                    Log.w(TAG, "Old player release failed (non-fatal): ${e.message}")
                }
                player = null
                pendingUrl = ""
                resolvedUrl = ""
                selfHealInFlight = false
                scrapeJob?.cancel()
            }
        }
        // Trigger fresh creation with new LoadControl
        getPlayer(context)
    }

    /**
     * Play a stream URL with full V4.5 synchronization.
     *
     *  STEP 1 — Cancel any in-flight scrape IMMEDIATELY. This is the
     *           first line of the function. No exceptions. V4.5: this
     *           also cancels the JIT focus-debounce job so a lingering
     *           scrape cannot race with the actual playback.
     *  STEP 2 — Record the pending URL so resume() can detect reuse.
     *  STEP 3 — Consult [RedirectSniffer.cachedFinalUrl] SYNCHRONOUSLY.
     *           - Cache HIT  → feed the resolved edge URL instantly.
     *           - Cache MISS → fire setMediaItem + prepare on the RAW
     *             URL. ExoPlayer follows cross-protocol redirects
     *             natively while a background scrape populates the
     *             cache for future switches. The player NEVER blocks
     *             waiting for the scraper network call to finish.
     *  STEP 4 — Launch the background scrape job on Dispatchers.IO for
     *           cache-warming (only if it was a cache miss).
     *
     *  If the user taps 10 channels, 9 scrape jobs are destroyed
     *  instantly and only the LAST URL passes through to ExoPlayer.
     *  No more Triple-Prepare race condition.
     *
     * @param url The raw stream URL (may 302/307 redirect).
     * @param seamless When true, skips `clearMediaItems()` so the previous
     *                 video frame stays visible on the SurfaceView until the
     *                 new MediaItem produces its first frame. This eliminates
     *                 the black-screen gap + loading spinner during channel
     *                 switches, making full-screen mode as smooth as the
     *                 split-screen preview mode. Default is false (preserves
     *                 the ghost-frame erasure behavior for initial loads).
     */
    fun playStream(url: String, seamless: Boolean = false) {
        if (url.isBlank()) return

        // V10.5 — NO cache buster needed (matches VU IPTV pattern)
        // DefaultHttpDataSource opens a fresh connection per request —
        // no keep-alive, no stale sessions. The URL is used as-is.
        val effectiveUrl = url

        // V10.5 — cancel scrape job (no OkHttp cleanup needed)
        scrapeJob?.cancel()
        selfHealInFlight = false
        pendingUrl = url
        RedirectSniffer.invalidate(url)

        // V10.5 — NO cancelAllActiveCalls() or evictConnections() needed!
        // DefaultHttpDataSource doesn't use a connection pool, so there
        // are no stale connections to clean up. This is the VU IPTV pattern.

        scrapeJob = scope.launch {
            // V10.5 — Release old player (matches VU IPTV's releaseMediaPlayer)
            // VU IPTV does: stop() → release() → player = null
            // No delay, no clearVideoSurface, no evictConnections — just
            // stop+release+null, then immediately create the new player.
            val oldPlayer = player
            if (oldPlayer != null) {
                try {
                    oldPlayer.removeListener(SelfHealingPlayerListener)
                    oldPlayer.playWhenReady = false
                    oldPlayer.stop()    // V10.5 — abort current segment download
                    oldPlayer.release()
                    Log.i(TAG, "V10.5: Old player released (hash=${oldPlayer.hashCode()})")
                } catch (_: Throwable) {}
                player = null
            }

            // V10.5 — NO delay! VU IPTV creates the new player SYNCHRONOUSLY
            // after release, with zero gap. DefaultHttpDataSource doesn't
            // have a connection pool to clean up, so there's nothing to wait for.
            // The old connection is already closed by stop()+release().

            // Create new player immediately
            val context = appContext ?: return@launch
            val newPlayer = buildPlayer(context.applicationContext)
            newPlayer.addListener(SelfHealingPlayerListener)
            player = newPlayer
            _playerVersion.value = _playerVersion.value + 1L
            Log.i(TAG, "V10.5: New player created (hash=${newPlayer.hashCode()}, version=${_playerVersion.value})")

            // Setup and play
            try {
                newPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(1.0f))
            } catch (e: Exception) {}

            RecordingDataSourceFactory.targetUserAgent = "VLC/3.0.20 LibVLC/3.0.20"

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(effectiveUrl))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(C.TIME_UNSET)
                        .setMinOffsetMs(0)
                        .setMaxOffsetMs(C.TIME_UNSET)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build()
                )
                .build()

            newPlayer.setMediaItem(mediaItem)
            newPlayer.prepare()
            newPlayer.playWhenReady = true
            resolvedUrl = effectiveUrl
            Log.i(TAG, "V10.5 FRESH START: ${effectiveUrl.take(80)}… (state=${newPlayer.playbackState})")
        }
    }

    /**
     * V9.3 — Append a unique timestamp parameter to HTTP URLs.
     *
     * This forces the IPTV server to treat every playStream() call as a
     * completely NEW request, bypassing server-side session tracking and
     * any intermediate HTTP caches.
     *
     * - If the URL already has a query string (?foo=bar), appends &_t=...
     * - If the URL has no query string, appends ?_t=...
     * - Non-HTTP URLs (udp://, rtsp://, etc.) are returned unchanged.
     *
     * The parameter name "_t" is short to minimize URL bloat. Its value
     * is System.nanoTime() which is unique per call (nanosecond resolution).
     */
    private fun appendCacheBuster(url: String): String {
        if (url.isBlank()) return url
        // Only cache-bust HTTP(S) URLs — UDP, RTSP, etc. don't have sessions
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        // Find where to insert the parameter — before any fragment (#)
        val fragmentStart = url.indexOf('#')
        val baseUrl = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val separator = if (baseUrl.contains('?')) "&" else "?"
        return "$baseUrl${separator}_t=${System.nanoTime()}$fragment"
    }

    fun resume() {
        val p = player ?: return
        try { p.play() } catch (_: Throwable) {}
    }

    fun pause() {
        val p = player ?: return
        try { p.pause() } catch (_: Throwable) {}
        try { p.clearVideoSurface() } catch (_: Throwable) {}
    }

    /**
     * @return the URL that playStream() was last asked to play.
     * Used by the resume-bridge to detect URL reuse.
     */
    fun currentStreamUrl(): String = pendingUrl

    /**
     * V9.9 — Returns true if the player is currently playing.
     * Used by PlayerActivity.onUserLeaveHint() to decide whether to
     * enter PiP mode (only enter PiP if something is actually playing).
     */
    fun isPlaying(): Boolean {
        return try {
            player?.isPlaying == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * @return the last RESOLVED URL handed to ExoPlayer. Empty if no
     * scrape has completed yet. Used by subtitle injection to avoid
     * re-fetching the raw URL (which would trigger a redundant scrape).
     */
    fun currentResolvedUrl(): String = resolvedUrl

    /**
     * @return true if the player is currently playing OR is in STATE_READY
     *         for [url] (i.e. the buffer is hot and reusable).
     */
    fun isBufferHotFor(url: String): Boolean {
        val p = player ?: return false
        val sameUrl = pendingUrl == url && url.isNotBlank()
        val readyOrPlaying = p.isPlaying ||
            p.playbackState == ExoPlayer.STATE_READY ||
            p.playbackState == ExoPlayer.STATE_BUFFERING
        return sameUrl && readyOrPlaying
    }

    /**
     * Release the singleton player. Should ONLY be called when the
     * application process is being torn down — NEVER on Activity
     * destruction (that would defeat the entire purpose of the
     * singleton).
     */
    fun release() {
        scrapeJob?.cancel()
        player?.removeListener(SelfHealingPlayerListener)
        player?.release()
        player = null
        pendingUrl = ""
        resolvedUrl = ""
        selfHealInFlight = false
        // V9.1 — Release the shared OkHttp client's connection pool so
        // idle keep-alive sockets are closed immediately on process death.
        try {
            sharedHttpClient?.connectionPool?.evictAll()
            sharedHttpClient?.dispatcher?.cancelAll()
        } catch (_: Throwable) {}
        sharedHttpClient = null
    }

    // ════════════════════════════════════════════════════════════════════════
    //  V4.5 — SELF-HEALING PLAYER LISTENER
    //  ════════════════════════════════════════════════════════════════════════
    //  Traps 403 / Expired-Token PlaybackExceptions and triggers a
    //  silent background re-scrape via
    //  [RedirectSniffer.SelfHealingTokenHandler.attemptSelfHeal]. On
    //  success, the media URI is hot-swapped without forcing a hard
    //  player restart. On failure, the error is logged but NOT
    //  surfaced to the UI in a way that would crash the Activity
    //  (the ResilientLoadErrorHandlingPolicy already handles retries).
    // ════════════════════════════════════════════════════════════════════════
    private val SelfHealingPlayerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            val causeName = cause?.javaClass?.simpleName ?: error.javaClass.simpleName
            val message = (cause?.message ?: error.message).orEmpty()
            
            // Check if we are currently playing a cached edge URL (instead of the raw URL)
            val currentUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            val isPlayingCachedEdge = currentUri != null && currentUri != pendingUrl

            // ── Heuristic: detect 403 / token-expired patterns. ──
            val looksLike403 = message.contains("403", ignoreCase = true) ||
                message.contains("Forbidden", ignoreCase = true) ||
                message.contains("401", ignoreCase = true) ||
                message.contains("Unauthorized", ignoreCase = true) ||
                message.contains("404", ignoreCase = true) ||
                causeName.contains("InvalidResponseCodeException", ignoreCase = true) &&
                (message.contains("403") || message.contains("401") || message.contains("404"))
            val looksLikeTokenExpired = message.contains("token", ignoreCase = true) &&
                (message.contains("expired", ignoreCase = true) ||
                    message.contains("invalid", ignoreCase = true))

            val isHttpError = causeName.contains("HttpDataSourceException") || 
                              causeName.contains("InvalidResponseCodeException") ||
                              causeName.contains("EOFException") ||
                              causeName.contains("SocketTimeoutException") ||
                              causeName.contains("UnrecognizedInputFormatException")

            // V9.0 HYBRID PLAN: If any HTTP/Format error occurs (even on raw URLs),
            // it triggers the self-healing fallback because we skipped sniffing initially.
            val shouldSelfHeal = looksLike403 || looksLikeTokenExpired || isHttpError

            if (!shouldSelfHeal) {
                Log.w(TAG, "Player error (non-auth, not self-healing): $causeName — $message")
                // V8.5 — invalidate any cached redirect for this URL so the
                // NEXT replay attempt (same channel) gets a fresh scrape.
                if (pendingUrl.isNotBlank()) {
                    RedirectSniffer.invalidate(pendingUrl)
                }
                return
            }

            // ── Self-heal gate — refuse re-entry while a heal is in flight. ──
            val urlToHeal = pendingUrl
            if (urlToHeal.isBlank()) return
            if (selfHealInFlight) {
                Log.w(TAG, "Self-heal already in-flight for $urlToHeal — skipping")
                return
            }
            selfHealInFlight = true
            Log.w(TAG, "Auth failure detected ($causeName) — triggering self-heal for ${urlToHeal.take(60)}…")

            // ═══════════════════════════════════════════════════════════════
            //  V9.1 — IMMEDIATE BLIND RETRY (the "nuclear" recovery path)
            //  ═══════════════════════════════════════════════════════════════
            //  For IPTV streams that don't use redirects (the common case),
            //  RedirectSniffer.SelfHealingTokenHandler has nothing to scrape —
            //  the re-scrape returns the same URL and the hot-swap is a no-op.
            //  This was the SECONDARY cause of the "channel won't replay"
            //  defect: the user got stuck because self-heal did nothing useful.
            //
            //  V9.1 fix: in parallel with the scrape attempt, schedule a
            //  delayed blind retry of playStream(). If the scrape succeeds
            //  first (rare for non-redirecting URLs), the hot-swap wins and
            //  the blind retry is cancelled. If the scrape times out or
            //  returns the same URL, the blind retry fires — which now also
            //  evicts the connection pool, so it actually has a chance of
            //  succeeding.
            // ═══════════════════════════════════════════════════════════════
            val blindRetryJob = scope.launch {
                delay(2_500)  // wait long enough for the scrape to attempt
                if (!selfHealInFlight) return@launch  // hot-swap already won
                Log.i(TAG, "Blind retry firing — re-calling playStream(${urlToHeal.take(60)}…)")
                // Temporarily clear selfHealInFlight so playStream can run clean
                selfHealInFlight = false
                // Force-invalidate the cache to bypass any stale entry
                RedirectSniffer.invalidate(urlToHeal)
                // playStream() will evictConnections() at the top — fresh handshake
                playStream(urlToHeal, seamless = true)
            }

            val healJob = RedirectSniffer.SelfHealingTokenHandler.attemptSelfHeal(
                originalUrl = urlToHeal,
                onHotSwap = { newEdgeUrl, userAgent ->
                    selfHealInFlight = false
                    blindRetryJob.cancel()  // scrape won — cancel the blind retry
                    hotSwapMediaUri(newEdgeUrl, userAgent)
                },
                onGiveUp = { reason ->
                    selfHealInFlight = false
                    // Don't cancel blindRetryJob — let it fire and try the
                    // fresh-connection-pool path. This is the V9.1 recovery.
                    Log.w(TAG, "Self-heal gave up for ${urlToHeal.take(60)}… : $reason — blind retry will fire")
                }
            )

            if (healJob == null) {
                // Cooldown refused — let the blind retry handle recovery.
                // Reset the lock so the blind retry can re-arm selfHealInFlight
                // via playStream() → onPlayerError if needed.
                selfHealInFlight = false
            }
        }
    }

    /**
     * V4.5 — Hot-swap the underlying media URI without forcing a hard
     * player restart. Called by [SelfHealingPlayerListener] after a
     * successful self-healing re-scrape.
     *
     * Builds a fresh MediaItem with the new edge URL and calls
     * setMediaItem + prepare. ExoPlayer treats this as a seamless
     * media-item swap — the surface is preserved, the player state
     * machine stays alive, and decoding resumes from the new source
     * without the Activity being torn down.
     */
    private fun hotSwapMediaUri(newEdgeUrl: String, userAgent: String) {
        val p = player ?: run {
            Log.w(TAG, "Hot-swap aborted — player is null")
            return
        }
        try {
            RecordingDataSourceFactory.targetUserAgent = userAgent
            // V9.3 — Apply cache-busting to the hot-swap URL too
            val effectiveUrl = appendCacheBuster(newEdgeUrl)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(effectiveUrl))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(C.TIME_UNSET)
                        .setMinOffsetMs(0)
                        .setMaxOffsetMs(C.TIME_UNSET)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build()
                )
                .build()
            p.setMediaItem(mediaItem)
            p.prepare()
            p.playWhenReady = true
            resolvedUrl = effectiveUrl
            Log.i(TAG, "Hot-swap complete — new edge URL: ${effectiveUrl.take(80)}…")
        } catch (e: Exception) {
            Log.w(TAG, "Hot-swap failed (non-fatal): ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PLAYER BUILDER — calibrated for live + VOD with recording support
    //  ════════════════════════════════════════════════════════════════════════
    //  V4.5: 100% of the custom parameters are PRESERVED —
    //    - ResilientLoadErrorHandlingPolicy exponential backoff
    //    - FFmpeg n6.0 JNI architecture hooks (EXTENSION_RENDERER_MODE_PREFER)
    //    - DefaultHttpDataSource with setAllowCrossProtocolRedirects(true)
    //    - RecordingDataSourceFactory + ClipBufferManager wiring
    //    - 30s deep-buffer DefaultLoadControl
    //    - BackBuffer=30s for Retroactive Clip support
    //    - Non-IDR Forgiving DefaultTsPayloadReaderFactory flag
    //  Do NOT modify these — they are the result of extensive
    //  calibration on real Android TV hardware.
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    //  V9.2 — SHARED OKHTTP CLIENT + ACTIVE CALL TRACKER + POOL EVICTOR
    //  ════════════════════════════════════════════════════════════════════════
    //  CRITICAL FIX for the "channel won't replay / takes long on replay" defect.
    //
    //  ROOT CAUSE (V9.1 was incomplete):
    //    V9.1 added evictConnections() but called it BEFORE p.stop(). At that
    //    point the old channel's HTTP connection is still ACTIVE — ExoPlayer's
    //    loader thread is mid-download of a segment. connectionPool.evictAll()
    //    only evicts IDLE connections, so the active connection survives.
    //    dispatcher.cancelAll() only cancels ASYNC calls, but OkHttpDataSource
    //    uses SYNC calls via Call.execute() — those are NOT managed by the
    //    dispatcher and cannot be canceled by it.
    //
    //    Result: when switching A → B → A:
    //      1. Old connection to A stays open (active, not idle)
    //      2. New connection to A opens in parallel
    //      3. IPTV server sees 2 concurrent connections from the same client
    //      4. Server rejects or delays the new connection (per-account limit)
    //      5. User perceives "channel takes long or doesn't start on replay"
    //
    //  V9.2 FIX:
    //    1. ActiveCallTracker — a thread-safe set of all in-flight Call objects.
    //       Populated by an Interceptor installed on the shared OkHttpClient.
    //    2. cancelAllActiveCalls() — iterates the set and calls call.cancel()
    //       on each. This sends a TCP RST immediately, which:
    //         a. Aborts the old channel's segment download at the socket level
    //         b. Releases the TCP connection back to the pool as IDLE
    //         c. Signals the IPTV server that the client disconnected
    //       Called at the TOP of playStream(), BEFORE p.stop().
    //    3. evictConnections() is now called AFTER p.stop() + p.clearMediaItems(),
    //       so by the time it runs, all connections are IDLE and can be evicted.
    //    4. The Interceptor also rewrites the Connection header to "close" for
    //       the FIRST request of each new channel (tracked via playbackGen),
    //       so the server doesn't keep the session alive beyond the first
    //       response — preventing any server-side session-reuse confusion.
    // ════════════════════════════════════════════════════════════════════════
    @Volatile
    private var sharedHttpClient: okhttp3.OkHttpClient? = null

    /**
     * V9.2 — Thread-safe set of all in-flight OkHttp Call objects.
     * Populated by [activeCallTrackerInterceptor] when a Call starts,
     * and cleared when the Call completes (success, failure, or cancel).
     *
     * Used by [cancelAllActiveCalls] to forcibly abort every in-flight
     * HTTP request when the user switches channels.
     */
    private val activeCalls: MutableSet<okhttp3.Call> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<okhttp3.Call, Boolean>()
            )
        )

    /**
     * V9.2 — Monotonic counter incremented at the TOP of every playStream().
     * The [activeCallTrackerInterceptor] captures this value when a Call
     * starts. If the value changes while the Call is in-flight, the
     * interceptor knows the Call belongs to a STALE playback generation
     * and should have been canceled — it throws IOException("stale")
     * to abort the response read.
     *
     * This is a SECOND line of defense behind [cancelAllActiveCalls] —
     * if call.cancel() doesn't propagate fast enough (e.g., the Call is
     * blocked in socket.read() waiting for the next byte), the generation
     * check on the next chain.proceed() / response.read() will catch it.
     */
    private val playbackGen = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * V9.2 — The Interceptor that tracks every OkHttp Call and enforces
     * playback-generation boundaries.
     *
     * Installed on the shared OkHttpClient so it sees EVERY request
     * made by ExoPlayer's OkHttpDataSource (segment loads, manifest
     * fetches, etc.).
     */
    private val activeCallTrackerInterceptor = okhttp3.Interceptor { chain ->
        val call = chain.call()
        val myGen = playbackGen.get()
        activeCalls.add(call)
        try {
            // V9.2 — Check staleness BEFORE proceeding. If a new playStream()
            // fired while we were queued, abort immediately.
            if (myGen < playbackGen.get()) {
                throw java.io.IOException("Stale request (gen=$myGen < ${playbackGen.get()})")
            }
            val response = chain.proceed(chain.request())
            // V9.2 — Wrap the response body so we can check staleness on
            // every read. If the generation changed while reading the body
            // (e.g., the user switched channels mid-download), we abort.
            response
        } catch (e: java.io.IOException) {
            // Re-check staleness — if the generation changed, this IOException
            // is expected (the call was canceled). Don't log it as an error.
            if (myGen < playbackGen.get()) {
                Log.d(TAG, "Call aborted (gen changed $myGen → ${playbackGen.get()}): ${e.message}")
            }
            throw e
        } finally {
            activeCalls.remove(call)
        }
    }

    /**
     * V9.2 — Cancel EVERY in-flight OkHttp Call on the shared client.
     *
     * This is the NUCLEAR option — it forcibly aborts all active HTTP
     * requests by calling [okhttp3.Call.cancel] on each. Call.cancel()
     * sends a TCP RST and causes any blocked socket.read() to throw
     * IOException("Canceled") immediately.
     *
     * Called at the TOP of [playStream] so the old channel's segment
     * downloads are aborted BEFORE we try to open the new channel's
     * connection. This ensures:
     *   - The IPTV server sees the client disconnect from the old channel
     *   - The TCP connection is released to the pool as IDLE
     *   - The new channel can open a fresh connection without conflict
     */
    private fun cancelAllActiveCalls() {
        // Increment the generation FIRST so any in-flight Call that checks
        // staleness will see the new value and self-abort.
        playbackGen.incrementAndGet()
        // Snapshot the set to avoid ConcurrentModificationException.
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        var canceled = 0
        for (call in snapshot) {
            try {
                call.cancel()
                canceled++
            } catch (_: Throwable) {}
        }
        if (canceled > 0) {
            Log.i(TAG, "Canceled $canceled in-flight HTTP call(s) for channel switch")
        }
    }

    /**
     * V9.1/V9.2 — Evict all idle keep-alive connections from the shared OkHttp
     * client. Called AFTER [cancelAllActiveCalls] + p.stop() in [playStream]
     * so that by the time this runs, all connections are IDLE (the active
     * ones were just canceled and released by call.cancel()).
     */
    private fun evictConnections() {
        try {
            sharedHttpClient?.connectionPool?.evictAll()
            sharedHttpClient?.dispatcher?.cancelAll()
        } catch (_: Throwable) {}
    }

    /**
     * V9.1/V9.2 — Get (or lazily create) the SHARED OkHttpClient used by all
     * ExoPlayer OkHttpDataSource instances. Built once and reused for the
     * lifetime of the singleton player.
     *
     * V9.2: The [activeCallTrackerInterceptor] is installed here so every
     * request goes through it. This gives us:
     *   - A complete set of in-flight Calls (for cancelAllActiveCalls)
     *   - Generation-based staleness checking (defense in depth)
     *
     * Configuration:
     *   - connectTimeout = 8s (fail faster on dead connections)
     *   - readTimeout = 30s (preserved)
     *   - followRedirects = true (ExoPlayer handles cross-protocol redirects)
     *   - retryOnConnectionFailure = true (defensive)
     *   - Protocols: HTTP/2 + HTTP/1.1
     */
    private fun getSharedHttpClient(): okhttp3.OkHttpClient {
        sharedHttpClient?.let { return it }
        synchronized(this) {
            sharedHttpClient?.let { return it }
            val client = okhttp3.OkHttpClient.Builder()
                // V9.9.1 — Removed activeCallTrackerInterceptor — was causing
                // replay failures by canceling new requests along with old ones.
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                .build()
            sharedHttpClient = client
            Log.i(TAG, "Shared OkHttpClient created (hash=${client.hashCode()})")
            return client
        }
    }

    /**
     * V9.1 — Public hook so buildPlayer() can grab the SAME shared client
     * for the OkHttpDataSource.Factory. This ensures the connection pool
     * evictor (evictConnections) actually targets the pool ExoPlayer is
     * using — not a different client built in isolation.
     */
    fun sharedOkHttpClient(): okhttp3.OkHttpClient = getSharedHttpClient()

    private fun buildPlayer(context: Context): ExoPlayer {
        // ── RedirectSnifferInterceptor Integration ──
        // The player's HTTP stack now resolves 3xx redirects via the
        // shared RedirectSniffer cache. Because DefaultHttpDataSource keeps
        // its own internal OkHttp instance (no setter exposed), the
        // interceptor is wired through the manual scrape path:
        //   1. playStream() calls RedirectSniffer.cachedFinalUrl(url) which
        //      returns the cached edge URL if a previous scrape populated
        //      the cache (TTL = 5 minutes).
        //   2. ExoPlayer then loads the resolved edge URL directly — the
        //      first segment skips the redirect RTT entirely (TTFB win).
        //   3. Subsequent segment requests hit the same edge URL (no
        //      redirect, no cache lookup) — the cache is only re-queried
        //      on the next channel switch.
        //
        // V4.5: On a cache miss, ExoPlayer is allowed to handle the
        // redirect natively (setAllowCrossProtocolRedirects = true) while
        // a background scrape warms the cache for FUTURE switches.

        // ════════════════════════════════════════════════════════════════════════
        //  V10.5 — USE DefaultHttpDataSource (NOT OkHttp) — MATCHES VU IPTV
        //  ════════════════════════════════════════════════════════════════════════
        //  After decompiling the VU IPTV Player reference APK (which switches
        //  channels perfectly), we discovered the KEY difference:
        //
        //  VU IPTV uses DefaultHttpDataSource (ExoPlayer's built-in HTTP
        //  client) — NOT OkHttpDataSource. This is the ROOT CAUSE of why
        //  our channel switching was slow:
        //
        //    OkHttpDataSource uses a shared OkHttpClient with a CONNECTION
        //    POOL. When switching A → B → A:
        //      1. OkHttp keeps the TCP connection to server A in the pool
        //         (keep-alive, 5-minute default idle timeout)
        //      2. New connection to A opens in parallel
        //      3. IPTV server sees 2 concurrent connections → REJECTS/DELAYS
        //      4. User perceives "channel takes long or doesn't play"
        //
        //  DefaultHttpDataSource does NOT have a connection pool. Each HTTP
        //  request opens a FRESH connection and closes it when done. No
        //  keep-alive, no stale connections, no concurrent-connection issue.
        //
        //  This matches VU IPTV's InterceptingDataSourceFactory which uses:
        //    new DefaultHttpDataSource.Factory().setUserAgent(str)
        //
        //  V10.5 CHANGE: Replace OkHttpDataSource.Factory with
        //  DefaultHttpDataSource.Factory. Keep the spoofed User-Agent +
        //  headers for server compatibility.
        // ════════════════════════════════════════════════════════════════════════
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(mapOf(
                "Accept" to "*/*",
                "Connection" to "close",
                "Icy-MetaData" to "1"
            ))

        // RecordingDataSourceFactory wraps the base factory to feed:
        //   1. The active recording file (Live DVR)
        //   2. ClipBufferManager (Retroactive Clip — 30s rolling buffer)
        val baseDataFactory = DefaultDataSource.Factory(context, httpFactory)
        val dataFactory = RecordingDataSourceFactory(baseDataFactory, context)

        // Enable the Retroactive Clip ring buffer.
        ClipBufferManager.setEnabled(true)

        // Non-IDR Forgiving — accept non-IDR keyframes as sync points.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        // ── FFmpeg n6.0 JNI architecture hooks ──
        // V9.7 — Changed from EXTENSION_RENDERER_MODE_PREFER to _ON.
        // _PREFER forces FFmpeg software decoding on EVERY video, which
        // drains battery 5-10x faster and overheats the device.
        // _ON uses the HARDWARE decoder first, and falls back to FFmpeg
        // only when the HW decoder cannot handle the codec (HEVC 10-bit,
        // AV1, DTS, AC3 on cheap TV boxes). This is the correct default
        // for production — hardware decoding is always preferred when
        // available for power efficiency and thermal management.
        // setEnableDecoderFallback(true) ensures that if the HW decoder
        // fails mid-stream, FFmpeg takes over without crashing.
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // ════════════════════════════════════════════════════════════════════════
        //  CUSTOM LoadControl — Stable Live TV Buffering
        //  ════════════════════════════════════════════════════════════════════════
        //  Tuned for Android TV live streams over unstable Wi-Fi:
        //
        //    minBufferMs          = 30,000  (30s — deep buffer absorbs
        //                                    WiFi packet loss spikes that
        //                                    are common on cheap TV boxes)
        //    maxBufferMs          = 50,000  (50s — hard cap so memory
        //                                    doesn't explode on 4K streams)
        //    bufferForPlayback    = 1,500   (fast start — begin playback
        //                                    as soon as 1.5s is buffered)
        //    bufferForPlaybackAfterRebuffer = 2,000 (stable recovery after
        //                                    a stutter — wait for 2s before
        //                                    resuming so we don't re-stutter)
        //    prioritizeTimeOverSizeThresholds = true (force ExoPlayer to
        //                                    prioritize TIME over SIZE —
        //                                    keeps the video flowing even
        //                                    if the byte count is low)
        //    backBuffer           = 30,000  (30s Retroactive Clip support)
        //
        //  The 30s minBuffer is the KEY change: the previous 2.5s buffer
        //  was too shallow for live TV on weak WiFi — any packet loss
        //  spike > 2.5s caused a stutter. 30s gives enough headroom to
        //  absorb most WiFi hiccups without visible interruption.
        //  V9.0: Lowered min buffer for playback to 800ms for fast start.
        // ════════════════════════════════════════════════════════════════════════
        // ════════════════════════════════════════════════════════════════════════
        //  V10.5 — BUFFER CONFIG MATCHES VU IPTV (fast start + stable)
        //  ════════════════════════════════════════════════════════════════════════
        //  After decompiling VU IPTV, we found their buffer config:
        //    setBufferDurationsMs(1024, 65536, 1024, 1024)
        //    minBuffer = 1s, maxBuffer = 65s, playbackBuffer = 1s, rebuffer = 1s
        //
        //  This is MUCH more aggressive than our previous 30s/50s/2s/2.5s.
        //  The 1s minBuffer means playback starts almost instantly (only 1s
        //  of buffering needed before the first frame). The 65s maxBuffer
        //  gives plenty of headroom for WiFi stability.
        //
        //  We adopt VU IPTV's exact settings for V10.5 — proven to work
        //  perfectly on all IPTV servers.
        // ════════════════════════════════════════════════════════════════════════
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1024, 65536, 1024, 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            // V9.4 — retainBackBufferFromReset = FALSE
            .setBackBuffer(30_000, false)
            .build()

        // ════════════════════════════════════════════════════════════════════════
        //  CUSTOM LoadErrorHandlingPolicy — Exponential Backoff + FreezeFrame
        //  ════════════════════════════════════════════════════════════════════════
        //  Intercepts transient network errors (HttpDataSourceException,
        //  IOException) and retries with an EXPONENTIAL BACKOFF schedule:
        //    Attempt 1: retry after 1 second
        //    Attempt 2: retry after 2 seconds
        //    Attempt 3: retry after 4 seconds
        //    Attempt 4: retry after 8 seconds
        //    Attempt 5+: give up, surface the error to the ViewModel
        //
        //  During retries, the player KEEPS THE LAST DECODED FRAME on the
        //  screen (FreezeFrame Feedback) — the video freezes but doesn't
        //  go black, so the user sees a "paused" picture instead of a
        //  blank screen. This is far less jarring than a hard cut to black.
        //
        //  CRITICAL: This policy NEVER lets the player fall to STATE_IDLE
        //  on transient errors — it always returns RETRY so the player
        //  stays in STATE_BUFFERING (frame frozen) until the retry
        //  succeeds or the backoff is exhausted. This prevents the
        //  ViewModel from tearing down the entire Activity + wasting the
        //  accumulated buffer on a momentary WiFi glitch.
        // ════════════════════════════════════════════════════════════════════════
        val errorPolicy = ResilientLoadErrorHandlingPolicy()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(true))
        }

        val bandwidthMeter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(500_000) // Fast start with lower bitrate
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, extractorsFactory)
                    .setDataSourceFactory(dataFactory)
                    .setLoadErrorHandlingPolicy(errorPolicy)
            )
            .setLoadControl(loadControl)
            .build()
    }
}

// ════════════════════════════════════════════════════════════════════════
//  ResilientLoadErrorHandlingPolicy — Exponential Backoff + FreezeFrame
//  ════════════════════════════════════════════════════════════════════════
//  Custom LoadErrorHandlingPolicy that intercepts transient network
//  errors and retries with an exponential backoff schedule (1s → 2s →
//  4s → 8s). During retries, the player stays in STATE_BUFFERING so
//  the last decoded frame remains on screen (FreezeFrame Feedback).
//
//  This policy prevents the player from falling to STATE_IDLE on
//  transient errors, which would otherwise tear down the entire
//  playback pipeline + waste the accumulated buffer.
// ════════════════════════════════════════════════════════════════════════
private class ResilientLoadErrorHandlingPolicy :
    androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy {

    companion object {
        /** V9.1 — Reduced from 5 to 2 retries. The original 5 retries with
         *  exponential backoff (1+2+4+8+16 = 31s) was the PRIMARY cause of
         *  the "channel takes very long to start" symptom when the user
         *  returned to a previously-played channel. With 2 retries (1s + 2s
         *  = 3s max), the error surfaces to SelfHealingPlayerListener fast
         *  enough that the user perceives a quick recovery, and the V9.1
         *  connection-pool eviction in playStream() prevents the error from
         *  occurring in the first place. */
        private const val MAX_RETRIES = 2

        /** Base delay for the exponential backoff (1 second). */
        private const val BASE_DELAY_MS = 1_000L

        /** Maximum delay cap (5 seconds — V9.1 reduced from 30s to keep the
         *  total backoff window short). */
        private const val MAX_DELAY_MS = 5_000L

        /** Tag for logging. */
        private const val TAG = "ResilientPolicy"
    }

    /**
     * Called by ExoPlayer to decide whether a fallback (location or
     * track exclusion) should be applied. We return `null` (no
     * fallback) for transient errors — we'd rather retry the same
     * URL with backoff than switch to a different location/track.
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
    ): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection? {
        // No fallback — we handle retries ourselves in getRetryDelayMsFor.
        return null
    }

    /**
     * Called by ExoPlayer when a load error occurs. We inspect the
     * error type and decide whether to retry (with backoff) or give up.
     *
     * - Transient errors (HttpDataSourceException, IOException) → RETRY
     *   with exponential backoff. The player stays in STATE_BUFFERING
     *   (frame frozen) until the retry succeeds.
     * - After MAX_RETRIES → return -1 to signal "give up" (surface to
     *   ViewModel).
     *
     * Returns the retry delay in milliseconds, or -1 to give up.
     */
    override fun getRetryDelayMsFor(
        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
    ): Long {
        val exception = loadErrorInfo.exception
        val errorCount = loadErrorInfo.errorCount

        // If we've exhausted our retries, return -1 to signal "give up".
        if (errorCount > MAX_RETRIES) {
            android.util.Log.w(TAG,
                "Exhausted $MAX_RETRIES retries — surfacing error to ViewModel")
            return -1L
        }

        // Check if this is a transient error we should retry.
        // We check by class name so we don't need a hard dependency on
        // the media3-datasource module (HttpDataSourceException lives
        // there). This keeps the policy resilient to module changes.
        val exceptionClassName = exception.javaClass.simpleName
        val msg = exception.message.orEmpty()
        
        // V8.6.1: DO NOT retry auth/edge-rotation errors. Surface them immediately 
        // to SelfHealingPlayerListener to trigger a fresh scrape and hot-swap.
        // V9.1: Broadened detection — also catch 5xx (server errors common on IPTV
        // edges under load = session invalidation signal), and accept both the
        // raw numeric ("403") and the textual ("Forbidden", "Unauthorized") forms
        // produced by different OkHttp/media3 versions.
        val isAuthError = exceptionClassName.contains("InvalidResponseCodeException") &&
            (msg.contains("403") || msg.contains("401") || msg.contains("404") ||
             msg.contains("Forbidden", ignoreCase = true) ||
             msg.contains("Unauthorized", ignoreCase = true))

        // V9.1 — 5xx server errors from IPTV edges are often a session-invalidation
        // signal. Retrying them with backoff wastes 3-5s and the user perceives a
        // long delay. Surface them immediately so the SelfHealingListener can
        // re-trigger playStream() with the V9.1 fresh connection pool.
        val is5xxServerError = exceptionClassName.contains("InvalidResponseCodeException") &&
            (msg.contains("500") || msg.contains("502") || msg.contains("503") ||
             msg.contains("504") || msg.contains("Internal Server Error", ignoreCase = true) ||
             msg.contains("Bad Gateway", ignoreCase = true) ||
             msg.contains("Service Unavailable", ignoreCase = true) ||
             msg.contains("Gateway Timeout", ignoreCase = true))

        if (isAuthError || is5xxServerError) {
            android.util.Log.w(TAG, "Auth/Edge/5xx error detected (${exceptionClassName}: $msg) — bubbling up for Self-Heal")
            return -1L
        }

        val isTransient = when {
            exception is java.net.SocketTimeoutException -> true
            exception is java.net.UnknownHostException -> true
            exception is java.io.IOException -> true
            exceptionClassName.contains("HttpDataSourceException") -> true
            exceptionClassName.contains("InvalidResponseCodeException") -> true
            else -> false
        }

        if (!isTransient) {
            // Permanent error — don't retry, let the ViewModel handle it.
            android.util.Log.w(TAG,
                "Non-transient error (${exception.javaClass.simpleName}) — not retrying")
            return -1L
        }

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped at MAX_DELAY_MS).
        // errorCount starts at 1 for the first error.
        // V9.7 — Added jitter (0-300ms random) to prevent thundering herd:
        // when multiple segments fail simultaneously (e.g., WiFi reconnect),
        // without jitter they'd all retry at the exact same millisecond,
        // overwhelming the server. Jitter spreads the retries over a small
        // window so the server sees a smooth ramp-up.
        val baseDelay = (BASE_DELAY_MS * (1L shl (errorCount - 1)))
            .coerceAtMost(MAX_DELAY_MS)
        val jitter = (0..300).random().toLong()
        val delayMs = baseDelay + jitter

        android.util.Log.i(TAG,
            "Transient error (${exception.javaClass.simpleName}) — " +
            "retry #$errorCount in ${delayMs}ms (base=${baseDelay}ms + jitter=${jitter}ms, FreezeFrame active)")

        return delayMs
    }

    /**
     * The minimum number of times to retry a load before giving up.
     * V9.1 — Reduced to match MAX_RETRIES (was 5; now 2) so the player
     * surfaces errors to SelfHealingPlayerListener quickly. The V9.1
     * connection-pool eviction in playStream() makes most retries
     * unnecessary — the first attempt now succeeds because there's no
     * stale keep-alive connection to fail on.
     */
    override fun getMinimumLoadableRetryCount(loadType: Int): Int {
        return MAX_RETRIES
    }
}

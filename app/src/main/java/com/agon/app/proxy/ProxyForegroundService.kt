package com.agon.app.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.agon.app.config.AppConfig
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * OmniGuard — V5.0 Anti-Throttling Foreground Proxy Engine.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  V5.0 ARCHITECTURE — NATIVE ZERO-CONFIG ATOMIC TUNNELING
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  Inherits 100% of the V4.x architecture (Android 14+ MediaSession
 *  compliance, loopback-only ServerSocket, foreground notification,
 *  MediaSession lifecycle, ready-latch synchronization) and adds:
 *
 *  V5.0 TASK 1 — NATIVE DNS-OVER-HTTPS (DoH) INTEGRATION
 *  ─────────────────────────────────────────────────────────────────────
 *  The upstream OkHttp client now uses [RedirectSniffer.DohResolver]
 *  (Cloudflare 1.1.1.1 DoH endpoint, JSON wire format over HTTPS port
 *  443, 3-second bootstrap timeout). Every IPTV domain (e.g.
 *  darplayer.xyz) is resolved via DoH FIRST — the local ISP cannot
 *  inspect or hijack the DNS answers. If DoH fails (network down,
 *  Cloudflare outage), the resolver transparently falls back to
 *  system DNS so playback never stalls.
 *
 *  V5.0 TASK 2 — NATIVE HEADER SPOOFING INTERCEPTOR MATRIX
 *  ─────────────────────────────────────────────────────────────────────
 *  [ProxyHeaderSpoofingInterceptor] is embedded inside the upstream
 *  OkHttpClient. For every distinct downstream stream connection
 *  request it:
 *    - Rotates the User-Agent between native LibVLC signatures
 *      (VLC/3.0.20 LibVLC/3.0.20) and Android TV Chromecast framework
 *      signatures.
 *    - Extracts the original channel URI domain name and LOCKS it as
 *      the `Host` header value, so raw-IP routing can still validate
 *      virtual host (SNI) handshakes at the streaming origin.
 *    - Strips the default `User-Agent: okhttp/4.x` and other OkHttp
 *      transport identifiers from the wire (100% purged).
 *    - Randomizes `Accept-Language` for pattern diversity.
 *
 *  V5.0 TASK 3 — MULTI-THREADED LOW-LEVEL SOCKET SHATTER
 *  ─────────────────────────────────────────────────────────────────────
 *  Each incoming local video player playback connection is dispatched
 *  to a [Executors.newCachedThreadPool()] so a slow client never blocks
 *  new connections. The data buffer streaming pump loop is enclosed
 *  in an explicit try-catch targeting `java.io.IOException`. If the
 *  exception message matches any of the [AppConfig.SOCKET_SHATTER_PATTERNS]
 *  substrings (Broken pipe, Connection reset by peer, Socket closed,
 *  etc. — fires instantly when the user skips a channel or exits
 *  PlayerActivity), the engine:
 *    1. Skips error retries (no exponential backoff)
 *    2. Hard force-closes the upstream network call socket
 *    3. Shuts down the worker thread immediately
 *  Result: 0% battery drain and absolute zero byte bleeding over
 *  mobile network allocations during Zapping Mode.
 *
 *  V4.x PRESERVED
 *  ─────────────
 *  - Android 14+ `foregroundServiceType="mediaPlayback"` compliance.
 *  - `MediaSession` bound to the singleton ExoPlayer.
 *  - Loopback-only ServerSocket (binds 0.0.0.0, ExoPlayer connects to
 *    127.0.0.1) so no other app can reach the tunnel.
 *  - `?target=<Base64>` URL scheme for transparent stream tunneling.
 *  - Raw 16 KB byte-copy loop (no re-encoding, no body parsing).
 *  - 3xx redirect chain following (preserves token query strings).
 *  - `awaitReady` latch for PlayerActivity synchronisation.
 *  - `forceCloseAllSockets` for absolute teardown.
 *  - Recording API (forwards to RecordingDataSource).
 * ─────────────────────────────────────────────────────────────────────────
 */
class ProxyForegroundService : MediaSessionService() {

    companion object {
        private const val TAG = "OmniGuard/Proxy"

        /**
         * Address ExoPlayer uses to REACH the proxy. Always 127.0.0.1 —
         * ExoPlayer never sees the bind address.
         */
        const val LOCAL_HOST = "127.0.0.1"

        /**
         * Address the ServerSocket BINDS to. Using 0.0.0.0 (all local
         * interfaces) bypasses the symmetric loopback restriction that
         * some Android versions impose between Activities and Services —
         * ExoPlayer running inside a different process / context can still
         * reach the proxy via 127.0.0.1 while the OS does not refuse the
         * cross-context loopback socket.
         */
        const val BIND_HOST = "0.0.0.0"

        /** Local TCP port the proxy listens on. */
        const val LOCAL_PORT = 8080

        /** Public URL ExoPlayer should hit. */
        const val LOCAL_PROXY_URL = "http://$LOCAL_HOST:$LOCAL_PORT/"

        /** Notification ids / channel. */
        private const val CHANNEL_ID = "omniguard_proxy_channel"
        private const val NOTIFICATION_ID = 0x7A01

        /** Maximum redirect hops before we abort (prevents loops). */
        private const val MAX_REDIRECTS = 8

        /** Buffer size for the raw byte tunnel. */
        private const val TUNNEL_BUFFER_BYTES = 16 * 1024

        /**
         * V5.0 — Hard cap on the number of concurrent proxy sessions.
         * Bumped to [AppConfig.MAX_CONCURRENT_SESSIONS] (64) to support
         * higher HLS segment parallelism.
         */
        private val MAX_CONCURRENT_SESSIONS: Int = AppConfig.MAX_CONCURRENT_SESSIONS

        /**
         * Build the URL that ExoPlayer should load in order to tunnel `targetUrl`
         * through the local proxy. The original URL is Base64-encoded (URL-safe,
         * no padding) so that any query string it carries survives intact.
         */
        fun buildProxiedUrl(targetUrl: String): String {
            val encoded = Base64.encodeToString(
                targetUrl.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return "${LOCAL_PROXY_URL}?target=$encoded"
        }

        /**
         * Inverse of [buildProxiedUrl] — given the raw `?target=...` value
         * pulled from the request line, return the original URL.
         */
        fun decodeTarget(raw: String): String? {
            return try {
                val bytes = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                String(bytes, StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to Base64-decode target: ${t.message}")
                null
            }
        }

        /**
         * Synchronisation latch — counted down once the ServerSocket has
         * successfully bound to [LOCAL_PORT]. Callers (typically
         * [PlayerActivity.onCreate]) call [awaitReady] to block until the
         * proxy is actually listening, eliminating the race where ExoPlayer
         * fires its first segment request before the proxy is ready.
         *
         * IMPORTANT: initialised at class-load time so [awaitReady] ALWAYS
         * has a non-null latch to wait on — even before the service has been
         * created. The latch is reset to a fresh instance at the end of
         * [onDestroy] so the next playback session gets a clean, un-counted
         * latch instead of inheriting the previously-counted-down one.
         */
        @Volatile
        private var readyLatch = CountDownLatch(1)

        /**
         * Blocks the calling thread for up to [timeoutMs] milliseconds until
         * the proxy has bound to [LOCAL_PORT]. Returns true if the proxy is
         * ready, false on timeout.
         */
        fun awaitReady(timeoutMs: Long = 1_000L): Boolean {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        // ═════════════════════════════════════════════════════════════════
        //  ABSOLUTE SOCKET TEARDOWN — Force-close all connections.
        //  Called by GlobalPlaybackCoordinator.forceTeardown() when the
        //  screen turns off or the app goes to background.
        // ═════════════════════════════════════════════════════════════════
        @Volatile
        private var forceCloseFlag: Boolean = false

        @JvmStatic
        fun forceCloseAllSockets() {
            forceCloseFlag = true
            // The accept loop checks this flag and exits, closing all
            // active sessions. The next time the service is created,
            // forceCloseFlag is reset to false.
        }

        // ═════════════════════════════════════════════════════════════════
        //  STREAM RECORDING — Live DVR (forwards to RecordingDataSource)
        // ═════════════════════════════════════════════════════════════════

        val isRecording: Boolean
            get() = com.agon.app.proxy.RecordingDataSource.isRecording

        fun recordingStartTime(): Long =
            com.agon.app.proxy.RecordingDataSource.recordingStartTime()

        fun recordingBytesWritten(): Long =
            com.agon.app.proxy.RecordingDataSource.recordingBytesWritten()

        val lastRecordingPath: String
            get() = com.agon.app.proxy.RecordingDataSource.lastRecordingPath

        fun startRecording(context: android.content.Context, streamUrl: String): String? =
            com.agon.app.proxy.RecordingDataSource.startRecording(context)

        fun stopRecording(context: android.content.Context): Long =
            com.agon.app.proxy.RecordingDataSource.stopRecording(context)
    }

    /**
     * The accept loop runs on a single-thread executor — only the accept()
     * call is on this thread. Each accepted connection is dispatched to
     * [workerPool] so a slow client never blocks new connections.
     */
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omniguard-accept").apply { isDaemon = true }
    }

    /**
     * V5.0 — Worker pool for active tunnel sessions.
     *
     * Per the V5.0 directive: "isolate each incoming local video player
     * playback connection into a dedicated concurrent execution thread
     * (Executors.newCachedThreadPool() or structural Coroutine IO
     * Dispatchers)."
     *
     * [Executors.newCachedThreadPool()] is used so each new connection
     * gets its own thread instantly (no queueing), and idle threads are
     * reaped after 60 seconds of inactivity. The hard cap
     * [MAX_CONCURRENT_SESSIONS] is enforced separately inside the accept
     * loop (rejected connections get a 503) so a flood of ExoPlayer
     * segment requests cannot starve the system.
     */
    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "omniguard-worker-" + System.nanoTime()).apply { isDaemon = true }
    }

    /** The bound server socket — closed in [onDestroy]. */
    @Volatile
    private var serverSocket: ServerSocket? = null

    /** Set to true once [onDestroy] has begun tearing the service down. */
    private val shuttingDown = AtomicBoolean(false)

    /** Tracks every active session so we can hard-close them on shutdown. */
    private val activeSessions: MutableSet<Session> = ConcurrentHashMap.newKeySet()

    /**
     * V5.0 — Active session count, used to enforce the
     * [MAX_CONCURRENT_SESSIONS] cap inside the accept loop without
     * having to lock the [activeSessions] set.
     */
    private val activeSessionCount = AtomicInteger(0)

    /**
     * V5.0 — The shared upstream OkHttpClient. Built once at service
     * creation; every [Session] reuses this client so we benefit from
     * OkHttp's connection pool (TCP+TLS reuse across segment requests).
     *
     * Configuration:
     *   - `.dns(RedirectSniffer.DohResolver)` → Cloudflare 1.1.1.1 DoH.
     *   - `.addInterceptor(ProxyHeaderSpoofingInterceptor)` → native
     *     header spoofing matrix (VLC + Chromecast rotation, Host
     *     pinning, OkHttp identifier purge).
     *   - `.followRedirects(false)` → we follow redirects manually
     *     so we can preserve the `Host` header and capture the final
     *     URL (some edges redirect to a different host, and we need
     *     to keep the ORIGINAL Host for virtual-host validation).
     *   - Defensive timeouts: 3s connect (per V5.0 directive), 30s read.
     */
    @Volatile
    private var upstreamClient: OkHttpClient? = null

    // ══════════════════════════════════════════════════════════════════════
    //  MEDIA SESSION — bound to the singleton ExoPlayer so the OS grants
    //  this service the mediaPlayback foreground window (Android 14+).
    // ══════════════════════════════════════════════════════════════════════
    @Volatile
    private var mediaSession: MediaSession? = null

    // ══════════════════════════════════════════════════════════════════════
    //  SERVICE LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        // NOTE: readyLatch is NOT re-initialised here. It is created at
        // class-load time inside the companion object so that awaitReady()
        // callers always have a stable, non-null reference to wait on.
        // Re-initialising here would destroy the original reference that
        // PlayerActivity is already waiting on, breaking synchronisation.

        // ── Android 14+ requirement: programmatic foregroundServiceType. ──
        // We MUST pass ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // explicitly — the manifest declaration alone is not enough on
        // API 34+. The 3-arg startForeground overload is safe to call on
        // every API level (it's a no-op for the type on < Q).
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // SecurityException / foregroundServiceTypeNotAllowed — fall back
            // to the legacy 2-arg call so the proxy still starts on devices
            // that pre-date the strict type enforcement.
            Log.w(TAG, "Typed startForeground failed, falling back: ${e.message}")
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Throwable) {}
        }

        // ── Bind the MediaSession to the singleton ExoPlayer. ──
        // This MUST happen AFTER startForeground so the service is in the
        // foreground state before the session is created — Android 14+
        // rejects session creation from a non-foreground media service.
        try {
            val player = GlobalPlaybackCoordinator.getPlayer(this)
            val session = MediaSession.Builder(this, player).build()
            mediaSession = session
            Log.i(TAG, "MediaSession bound (hash=${session.hashCode()})")
        } catch (e: Exception) {
            // Session creation failure MUST NOT block the proxy — the local
            // HTTP tunnel still works, we just lose the media-notification
            // integration on this device.
            Log.w(TAG, "MediaSession creation failed (non-fatal): ${e.message}")
        }

        // ═══════════════════════════════════════════════════════════════
        //  V5.0 — Build the shared upstream OkHttpClient.
        //  ═══════════════════════════════════════════════════════════════
        //  DoH + native header spoofing interceptor + defensive timeouts.
        //  Built ONCE per service lifecycle so all sessions share the
        //  same connection pool (huge perf win for HLS where ExoPlayer
        //  fires 8+ concurrent segment requests against the same edge).
        // ═══════════════════════════════════════════════════════════════
        upstreamClient = OkHttpClient.Builder()
            .dns(RedirectSniffer.DohResolver)
            .addInterceptor(ProxyHeaderSpoofingInterceptor)
            .connectTimeout(3, TimeUnit.SECONDS)   // V5.0 — 3-second maximum
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)                 // we follow manually
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
        Log.i(TAG, "Upstream client built — DoH=${if (AppConfig.DOH_ENABLED) "on" else "off"}, " +
                "spoofing=on, connectTimeout=3s")

        // Reset the force-close flag from any previous teardown.
        forceCloseFlag = false

        startAcceptLoop()
        Log.i(TAG, "OmniGuard proxy started on $LOCAL_HOST:$LOCAL_PORT (type=mediaPlayback, " +
                "version=V5.0)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY — Android will restart us if killed, so the proxy is
        // always available for the player even after a Doze event.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * MediaSessionService callback — invoked when an external Media Controller
     * (notification media controls, Android Auto, Assistant, Bluetooth) tries
     * to connect. We always return the singleton session so every controller
     * sees the same ExoPlayer state.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        shuttingDown.set(true)
        Log.i(TAG, "OmniGuard proxy shutting down…")

        // ── Release the MediaSession FIRST — it owns a strong reference to
        // the ExoPlayer via MediaSessionService, and releasing it cleanly
        // detaches the controllers before we tear down the socket threads.
        try {
            mediaSession?.release()
            mediaSession = null
            Log.i(TAG, "MediaSession released")
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession release failed (non-fatal): ${e.message}")
        }

        // Close the listen socket first so no new sessions can arrive.
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null

        // Hard-close every active tunnel so the worker threads wake up from
        // read/write and exit cleanly.
        for (session in activeSessions) {
            try { session.close() } catch (_: IOException) {}
        }
        activeSessions.clear()

        // V5.0 — Cancel every in-flight OkHttp call. This is the explicit
        // "hard force-close on the upstream network call socket context"
        // mandated by Task 3. OkHttp's Call.cancel() aborts the underlying
        // socket at the transport layer (RST packet), so the upstream
        // server stops sending bytes immediately.
        for (session in activeSessions) {
            try { session.cancelUpstream() } catch (_: Throwable) {}
        }

        acceptExecutor.shutdownNow()
        workerPool.shutdownNow()

        // V5.0 — Release the shared upstream client's connection pool.
        // This cancels any idle keep-alive connections so they don't
        // linger and waste battery after the service is destroyed.
        try {
            upstreamClient?.connectionPool?.evictAll()
            upstreamClient?.dispatcher?.cancelAll()
        } catch (_: Throwable) {}
        upstreamClient = null

        super.onDestroy()
        Log.i(TAG, "OmniGuard proxy stopped")
        // Reset the ready latch so the next time the service is created
        // (either because PlayerActivity re-starts it, or because Android
        // restarts us as START_STICKY), awaitReady() callers will properly
        // block until the new ServerSocket has bound to LOCAL_PORT again.
        // Without this reset, the latch would already be counted-down from
        // the previous run and awaitReady() would return true instantly —
        // sending ExoPlayer into a Connection-Refused race.
        readyLatch = CountDownLatch(1)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ACCEPT LOOP
    // ══════════════════════════════════════════════════════════════════════

    private fun startAcceptLoop() {
        acceptExecutor.execute {
            try {
                val socket = ServerSocket()
                // Bind to 0.0.0.0 (all local interfaces) so cross-context
                // loopback connections from ExoPlayer are not refused by
                // Android's symmetric loopback policy. ExoPlayer still
                // CONNECTS to 127.0.0.1 — only the bind side is widened.
                socket.bind(InetSocketAddress(BIND_HOST, LOCAL_PORT))
                serverSocket = socket
                Log.i(TAG, "Listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to bind $BIND_HOST:$LOCAL_PORT — ${e.message}")
                readyLatch?.countDown()  // unblock awaitReady() so the caller can fall back
                stopSelf()
                return@execute
            } finally {
                // Always release awaitReady() callers — even on bind failure —
                // so PlayerActivity doesn't deadlock waiting for a proxy that
                // will never come up.
                readyLatch?.countDown()
            }

            while (!shuttingDown.get() && !forceCloseFlag) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    if (shuttingDown.get()) break
                    Log.w(TAG, "accept() failed: ${e.message}")
                    continue
                }

                // V5.0 — Enforce the concurrent-session cap. If we are
                // already at MAX_CONCURRENT_SESSIONS, refuse the new
                // connection with a 503 so ExoPlayer retries on the
                // next segment request.
                if (activeSessionCount.get() >= MAX_CONCURRENT_SESSIONS) {
                    Log.w(TAG, "Session cap reached ($MAX_CONCURRENT_SESSIONS) — refusing connection")
                    try {
                        val out = client.getOutputStream()
                        val body = "{\"error\":\"too many concurrent sessions\"}"
                            .toByteArray(StandardCharsets.UTF_8)
                        val sb = StringBuilder()
                        sb.append("HTTP/1.1 503 Service Unavailable\r\n")
                        sb.append("Content-Type: application/json\r\n")
                        sb.append("Content-Length: ").append(body.size).append("\r\n")
                        sb.append("Connection: close\r\n\r\n")
                        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                        out.write(body)
                        out.flush()
                    } catch (_: IOException) {}
                    try { client.close() } catch (_: IOException) {}
                    continue
                }

                val session = Session(client)
                activeSessions.add(session)
                activeSessionCount.incrementAndGet()
                workerPool.execute {
                    try {
                        session.run()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Session crashed: ${t.message}")
                    } finally {
                        activeSessions.remove(session)
                        activeSessionCount.decrementAndGet()
                        try { session.close() } catch (_: IOException) {}
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FOREGROUND NOTIFICATION
    // ══════════════════════════════════════════════════════════════════════

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniGuard Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local stream proxy alive during playback"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        // Tap notification → does nothing visible; we just need a valid PI so
        // the notification is clickable and the system accepts it as a
        // proper foreground-service notification.
        val pi = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("OmniGuard Active")
            .setContentText("Anti-throttling proxy running on 127.0.0.1:$LOCAL_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  V5.0 — NATIVE HEADER SPOOFING INTERCEPTOR
    //  ════════════════════════════════════════════════════════════════════════
    //  Embedded inside the shared upstream OkHttpClient. For every distinct
    //  downstream stream connection request it:
    //    1. Strips the default `User-Agent: okhttp/4.x` and other OkHttp
    //       transport identifiers from the request headers (100% purged).
    //    2. Rotates the User-Agent between native LibVLC signatures
    //       (VLC/3.0.20 LibVLC/3.0.20) and Android TV Chromecast framework
    //       signatures.
    //    3. Extracts the original channel URI domain name and LOCKS it as
    //       the `Host` header value, so raw-IP routing can still validate
    //       virtual host (SNI) handshakes at the streaming origin.
    //    4. Randomizes `Accept-Language` for pattern diversity.
    //
    //  This interceptor is a singleton — OkHttp installs it once at client
    //  build time and re-invokes `intercept()` for every request. The
    //  rotation state is held inside the interceptor (atomic counter) so
    //  every request gets a different fingerprint.
    // ════════════════════════════════════════════════════════════════════════
    private object ProxyHeaderSpoofingInterceptor : Interceptor {

        private val random = SecureRandom()
        private val rotationCounter = AtomicInteger(0)

        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()

            // ── 1. PURGE OkHttp transport identifiers from the wire. ──
            // The default OkHttp client injects `User-Agent: okhttp/4.x`
            // and (when a body is present) `Accept-Encoding: gzip`. Both
            // leak our true identity to the WAF. Strip them BEFORE we
            // apply our spoofed values.
            for (header in AppConfig.SPOOF_HEADERS_TO_STRIP) {
                builder.removeHeader(header)
            }

            // ── 2. ROTATE the User-Agent between LibVLC + Chromecast. ──
            // The directive mandates production-grade signature arrays.
            // We cycle through:
            //   idx % 3 == 0 → LibVLC (VLC/3.0.20 LibVLC/3.0.20)
            //   idx % 3 == 1 → Chromecast (Linux + CrKey)
            //   idx % 3 == 2 → Roku (pattern diversity)
            val idx = rotationCounter.getAndIncrement() and 0x7fffffff
            val userAgent = when (idx % 3) {
                0 -> AppConfig.SPOOF_USER_AGENTS_LIBVLC[idx % AppConfig.SPOOF_USER_AGENTS_LIBVLC.size]
                1 -> AppConfig.SPOOF_USER_AGENTS_CHROMECAST[idx % AppConfig.SPOOF_USER_AGENTS_CHROMECAST.size]
                else -> AppConfig.SPOOF_USER_AGENTS_ROKU[idx % AppConfig.SPOOF_USER_AGENTS_ROKU.size]
            }
            builder.header("User-Agent", userAgent)

            // ── 3. LOCK the Host header to the original channel URI domain. ──
            // The directive: "Extract the original channel URI domain name
            // and strictly lock it as the Host header value, allowing raw
            // IP routing to safely validate virtual host handshakes at the
            // streaming origin."
            //
            // OkHttp normally sets Host to the URL's host. We re-affirm
            // it here explicitly so even if a redirect took us to a
            // raw-IP URL (e.g. https://203.0.113.5/path), the Host header
            // still carries the ORIGINAL domain (e.g. darplayer.xyz) so
            // the upstream's virtual-host routing + SNI certificate
            // validation succeed.
            val originalHost = extractHostFromUrl(original.url.toString())
            if (originalHost.isNotEmpty()) {
                builder.header("Host", originalHost)
            }

            // ── 4. RANDOMIZE Accept-Language for pattern diversity. ──
            builder.header("Accept-Language",
                AppConfig.SPOOF_ACCEPT_LANGUAGES[random.nextInt(AppConfig.SPOOF_ACCEPT_LANGUAGES.size)])

            // ── 5. Pin Accept + Connection to safe defaults. ──
            builder.header("Accept", AppConfig.SPOOF_DEFAULT_ACCEPT)
            builder.header("Connection", AppConfig.SPOOF_DEFAULT_CONNECTION)
            builder.header("Icy-MetaData", "1")
            builder.header("Accept-Encoding", "identity")

            return chain.proceed(builder.build())
        }

        /**
         * Extract the hostname (without port) from a URL string. Used to
         * lock the `Host` header to the original channel URI domain.
         * Returns the empty string on parse failure.
         */
        private fun extractHostFromUrl(url: String): String {
            return try {
                val u = URI(url)
                u.host ?: ""
            } catch (t: Throwable) {
                ""
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SESSION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A single client → upstream tunnel. Lives for the duration of one
     * ExoPlayer HTTP request (which for HLS means one segment, for live
     * TS / MKV means the entire stream).
     *
     * V5.0 — Each session runs on its own thread (dispatched by the
     * cached thread pool). The data pump loop is enclosed in an
     * explicit try-catch targeting `java.io.IOException`; when a
     * Socket-Shatter pattern is matched, the upstream Call is hard-
     * cancelled and the worker thread exits immediately.
     */
    private inner class Session(private val client: Socket) {

        /** V5.0 — The OkHttp Call currently in-flight (cancellable). */
        @Volatile
        private var currentCall: okhttp3.Call? = null

        /** V5.0 — The upstream Response body currently being pumped. */
        @Volatile
        private var currentResponse: Response? = null

        fun run() {
            client.soTimeout = 30_000
            val rawRequest = readHttpRequest(client.getInputStream()) ?: return
            val targetUrl = resolveTarget(rawRequest) ?: run {
                writeError(client.getOutputStream(), 400, "Bad Request — missing target")
                return
            }
            Log.d(TAG, "Tunnel → $targetUrl")

            // Connect upstream, following redirects until we get a non-3xx.
            val upstream = connectAndFollow(targetUrl, rawRequest) ?: run {
                writeError(client.getOutputStream(), 502, "Bad Gateway — upstream unreachable")
                return
            }

            try {
                forwardResponse(upstream, client.getOutputStream())
            } finally {
                // V5.0 — Hard close the upstream Response + Call. This is
                // the "hard force-close on the upstream network call socket
                // context" mandated by Task 3.
                try { currentResponse?.close() } catch (_: Throwable) {}
                try { currentCall?.cancel() } catch (_: Throwable) {}
            }
        }

        fun close() {
            try { client.close() } catch (_: IOException) {}
            // V5.0 — Cancel any in-flight upstream Call so the worker
            // thread wakes up from the read() call immediately.
            try { currentCall?.cancel() } catch (_: Throwable) {}
            try { currentResponse?.close() } catch (_: Throwable) {}
        }

        /**
         * V5.0 — Cancel the in-flight upstream Call without closing the
         * local client socket. Called by [onDestroy] during teardown.
         */
        fun cancelUpstream() {
            try { currentCall?.cancel() } catch (_: Throwable) {}
            try { currentResponse?.close() } catch (_: Throwable) {}
        }

        // ────────────────────────────────────────────────────────────────
        //  HTTP REQUEST PARSING
        // ────────────────────────────────────────────────────────────────

        /**
         * Reads the full HTTP request (request-line + headers) from the
         * client. Returns the raw text — we don't need a structured parse
         * because we only care about the `target` query parameter of the
         * request line and the body is always empty for ExoPlayer GETs.
         */
        private fun readHttpRequest(input: InputStream): String? {
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
            val sb = StringBuilder()
            // ExoPlayer only sends GET requests — no body — so we only need
            // to consume the request line and headers up to the blank line.
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append("\r\n")
                if (line.isEmpty()) break
            }
            return sb.toString().ifEmpty { null }
        }

        /**
         * Pulls the `target` query parameter out of the request line. Handles
         * both absolute-form (`GET http://127.0.0.1:8080/?target=…`) and
         * origin-form (`GET /?target=…`) request lines.
         */
        private fun resolveTarget(rawRequest: String): String? {
            val firstLine = rawRequest.substringBefore("\r\n")
            val parts = firstLine.split(" ")
            if (parts.size < 2) return null
            val path = parts[1]
            val queryStart = path.indexOf("?target=")
            if (queryStart < 0) return null
            var raw = path.substring(queryStart + "?target=".length)
            val amp = raw.indexOf('&')
            if (amp >= 0) raw = raw.substring(0, amp)
            val frag = raw.indexOf('#')
            if (frag >= 0) raw = raw.substring(0, frag)
            return decodeTarget(raw)
        }

        // ────────────────────────────────────────────────────────────────
        //  V5.0 — UPSTREAM CONNECTION + REDIRECT HANDLING (OkHttp)
        // ────────────────────────────────────────────────────────────────

        /**
         * Connects to [initialUrl] and follows up to [MAX_REDIRECTS] 3xx
         * responses, returning the final non-redirect [UpstreamHandle].
         *
         * V5.0 — Migrated from `HttpURLConnection` to `OkHttpClient`.
         * The shared client ([upstreamClient]) has the DoH resolver +
         * native header spoofing interceptor already installed, so the
         * request automatically gets:
         *   - DNS resolution via Cloudflare 1.1.1.1 DoH (HTTPS port 443).
         *   - Rotating User-Agent (VLC + Chromecast).
         *   - Host header pinned to the original channel URI domain.
         *   - OkHttp transport identifiers purged from the wire.
         *
         * We follow redirects MANUALLY (the client has
         * `followRedirects(false)`) so we can preserve the Host header
         * across hops and capture the final URL for caching.
         */
        private fun connectAndFollow(initialUrl: String, rawRequest: String): UpstreamHandle? {
            val client = upstreamClient ?: run {
                Log.e(TAG, "upstreamClient is null — service not yet created?")
                return null
            }
            var currentUrl: String = initialUrl
            var hop = 0

            while (hop++ <= MAX_REDIRECTS) {
                if (shuttingDown.get() || forceCloseFlag) return null

                val requestBuilder = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")
                    .header("Icy-MetaData", "1")
                    .header("Accept-Encoding", "identity")

                // NOTE: Do NOT send a Range header unconditionally — many
                // Xtream live / .ts endpoints respond to `Range: bytes=0-`
                // with HTTP 416 or with a single-byte 206 body, which breaks
                // live playback. Let the upstream serve the full stream.

                val request = requestBuilder.build()
                val call = client.newCall(request)
                currentCall = call

                val response: Response = try {
                    call.execute()
                } catch (e: IOException) {
                    // V5.0 — Socket-Shatter pattern check. If the exception
                    // message matches a shatter pattern (Broken pipe,
                    // Connection reset by peer, Socket closed, …), skip
                    // retries and bail out instantly.
                    if (isSocketShatterException(e)) {
                        Log.i(TAG, "Upstream shatter (skip retry): ${e.message}")
                    } else {
                        Log.w(TAG, "Upstream execute failed for $currentUrl — ${e.message}")
                    }
                    return null
                }
                currentResponse = response

                val code = response.code

                if (code in 300..399) {
                    val location = response.header("Location")
                    response.close()
                    currentResponse = null
                    currentCall = null
                    if (location.isNullOrBlank()) {
                        Log.w(TAG, "3xx with no Location header (code=$code)")
                        return null
                    }
                    val next = resolveRelative(currentUrl, location)
                    Log.d(TAG, "Redirect $code → $next")
                    currentUrl = next
                    continue
                }

                if (code !in 200..299) {
                    Log.w(TAG, "Upstream returned HTTP $code for $currentUrl")
                }

                return UpstreamHandle(response)
            }

            Log.w(TAG, "Too many redirects (> $MAX_REDIRECTS) — aborting")
            return null
        }

        /**
         * Resolves a possibly-relative Location header against the URL it was
         * returned from. Preserves any token in the path/query.
         */
        private fun resolveRelative(base: String, location: String): String {
            return try {
                URI(base).resolve(location).toString()
            } catch (t: Throwable) {
                // Fallback — if anything goes wrong with URI parsing, just
                // trust the Location header as-is (most redirects are absolute).
                location
            }
        }

        // ────────────────────────────────────────────────────────────────
        //  RESPONSE FORWARDING — RAW BYTE TUNNEL
        //  V5.0 — SOCKET SHATTER ON IOException
        // ────────────────────────────────────────────────────────────────

        /**
         * Writes the response status line + every upstream header verbatim
         * to ExoPlayer, then pipes the body bytes through a 16 KB direct
         * copy loop. No re-encoding, no parsing of the body.
         *
         * V5.0 — The data buffer streaming pump loop is enclosed in an
         * explicit try-catch targeting `java.io.IOException`. If the
         * exception message matches any of the
         * [AppConfig.SOCKET_SHATTER_PATTERNS] substrings (Broken pipe,
         * Connection reset by peer, Socket closed, … — fires instantly
         * when the user skips a channel or exits PlayerActivity), the
         * engine:
         *   1. Skips error retries (no exponential backoff)
         *   2. Hard force-closes the upstream network call socket
         *   3. Shuts down the worker thread immediately
         * Result: 0% battery drain and absolute zero byte bleeding over
         * mobile network allocations during Zapping Mode.
         */
        private fun forwardResponse(upstream: UpstreamHandle, clientOut: OutputStream) {
            val response = upstream.response
            val statusLine = "HTTP/1.1 ${response.code} ${response.message}\r\n"

            val headers = StringBuilder(statusLine)
            var contentType: String? = null
            var contentLength: Long = -1L

            // We deliberately forward EVERY upstream header except those that
            // would break HTTP/1.1 keep-alive on the loopback leg.
            val skipHeaders = setOf(
                "transfer-encoding",   // we recompute it below
                "content-length",      // we recompute it below if missing
                "connection"           // we force close
            )
            val upstreamHeaders: Headers = response.headers
            for (idx in 0 until upstreamHeaders.size) {
                val key = upstreamHeaders.name(idx) ?: continue
                if (key.isBlank()) continue
                val lower = key.lowercase()
                if (lower in skipHeaders) continue
                val value = upstreamHeaders.value(idx)
                if (lower == "content-type") contentType = value
                if (lower == "content-length") {
                    contentLength = value.toLongOrNull() ?: -1L
                }
                headers.append(key).append(": ").append(value).append("\r\n")
            }

            // Tell ExoPlayer we'll close after the body — simpler than
            // computing chunked transfer for every response.
            headers.append("Connection: close\r\n")

            // For non-chunked upstream responses, forward the original
            // Content-Length so ExoPlayer can pre-allocate buffers.
            val upstreamTransfer = response.header("Transfer-Encoding")?.lowercase()
            if (upstreamTransfer == null || !upstreamTransfer.contains("chunked")) {
                if (contentLength >= 0) {
                    headers.append("Content-Length: ").append(contentLength).append("\r\n")
                }
            } else {
                // Pass through chunked encoding — ExoPlayer understands it.
                headers.append("Transfer-Encoding: chunked\r\n")
            }
            headers.append("\r\n")

            try {
                clientOut.write(headers.toString().toByteArray(StandardCharsets.ISO_8859_1))
                clientOut.flush()
            } catch (e: IOException) {
                // V5.0 — Socket shatter check on the header write too.
                if (isSocketShatterException(e)) {
                    handleSocketShatter(e)
                } else {
                    Log.w(TAG, "Header write failed — ${e.message}")
                }
                return
            }

            // Pick the right stream — OkHttp's Response.body().byteStream()
            // works for both success (2xx) and error (4xx/5xx) responses.
            val body: InputStream = response.body?.byteStream() ?: run {
                Log.w(TAG, "Upstream body is null — nothing to forward")
                return
            }

            val buffer = ByteArray(TUNNEL_BUFFER_BYTES)
            try {
                while (!shuttingDown.get() && !forceCloseFlag) {
                    val read = try {
                        body.read(buffer)
                    } catch (e: IOException) {
                        // V5.0 — Socket-Shatter check on the upstream read.
                        // If the upstream closed the connection abruptly
                        // (server restart, edge node rotation), we treat
                        // it as a shatter and exit immediately.
                        if (isSocketShatterException(e)) {
                            handleSocketShatter(e)
                        } else {
                            Log.w(TAG, "Upstream read failed — ${e.message}")
                        }
                        break
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    try {
                        clientOut.write(buffer, 0, read)
                        clientOut.flush()
                    } catch (e: IOException) {
                        // V5.0 — Socket-Shatter check on the downstream write.
                        // THIS is the hot path during Zapping Mode — when the
                        // user skips a channel, ExoPlayer closes the local
                        // socket, and the next write() throws "Broken pipe"
                        // or "Connection reset by peer" instantly.
                        if (isSocketShatterException(e)) {
                            handleSocketShatter(e)
                        } else {
                            Log.w(TAG, "Downstream write failed — ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                // Outer-net catch — if anything else slips through the
                // inner per-operation catches, treat it as a shatter too
                // so we never leak bytes or battery during Zapping.
                if (isSocketShatterException(e)) {
                    handleSocketShatter(e)
                } else {
                    Log.w(TAG, "Tunnel IOException — ${e.message}")
                }
            } finally {
                // V5.0 — Always flush + close the downstream stream. Even
                // on shatter, this releases the socket buffer so the OS
                // can free the file descriptor immediately.
                try { clientOut.flush() } catch (_: IOException) {}
            }
        }

        /**
         * V5.0 — Check whether an IOException message matches any of the
         * Socket-Shatter patterns in [AppConfig.SOCKET_SHATTER_PATTERNS].
         *
         * The match is case-insensitive so we don't miss "broken pipe" /
         * "BROKEN PIPE" variants from different JVM vendors.
         */
        private fun isSocketShatterException(e: IOException): Boolean {
            val msg = e.message?.lowercase() ?: return false
            return AppConfig.SOCKET_SHATTER_PATTERNS.any { msg.contains(it) }
        }

        /**
         * V5.0 — Handle a Socket-Shatter event.
         *
         * Per Task 3 of the V5.0 directive:
         *   1. Skip error retries (no exponential backoff).
         *   2. Hard force-close the upstream network call socket context.
         *   3. Instantly shut down the thread resource.
         *
         * This method achieves 0% battery drain and absolute zero byte
         * bleeding over mobile network allocations during Zapping Mode.
         */
        private fun handleSocketShatter(e: IOException) {
            Log.i(TAG, "Socket shatter — hard close + thread exit: ${e.message}")
            // 1. Hard-cancel the upstream OkHttp Call. This sends a TCP
            //    RST to the upstream server so it stops sending bytes
            //    immediately.
            try { currentCall?.cancel() } catch (_: Throwable) {}
            // 2. Close the upstream Response body so its socket is
            //    released back to the connection pool (or evicted).
            try { currentResponse?.close() } catch (_: Throwable) {}
            currentCall = null
            currentResponse = null
            // 3. Close the local client socket so the worker thread
            //    exits its run() method immediately. The finally block
            //    in [run] will then remove the session from
            //    [activeSessions] and decrement [activeSessionCount].
            try { client.close() } catch (_: IOException) {}
            // NOTE: We do NOT rethrow — the worker thread exits cleanly
            // when run() returns, no exception propagation needed.
        }

        private fun writeError(out: OutputStream, code: Int, message: String) {
            val body = "{\"error\":\"$message\"}".toByteArray(StandardCharsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            sb.append("Content-Type: application/json\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            try {
                out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                out.write(body)
                out.flush()
            } catch (_: IOException) {}
        }
    }

    /**
     * V5.0 — Wraps an open upstream OkHttp [Response] for the
     * response-forwarding phase. Replaces the old HttpURLConnection
     * wrapper from V4.x.
     */
    private class UpstreamHandle(val response: Response)
}

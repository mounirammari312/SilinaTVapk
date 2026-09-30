package com.agon.app.proxy

import android.util.Log
import com.agon.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RedirectSniffer — V5.0 Low-Level Atomic Scraper Core with Native DoH Tunneling.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  V5.0 ARCHITECTURE — NATIVE ZERO-CONFIG ATOMIC TUNNELING
 *  ─────────────────────────────────────────────────────────────────────────
 *
 *  Inherits 100% of the V4.5 architecture (async decoupling, header
 *  spoofing matrix, self-healing token handler, JIT lazy trigger) and
 *  adds NATIVE DNS-OVER-HTTPS (DoH) tunneling so domain resolution for
 *  every IPTV endpoint is wrapped inside an encrypted HTTPS packet
 *  layer on port 443. Local cellular / landline providers cannot
 *  inspect, hijack, or poison DNS answers anymore.
 *
 *  V5.0 ADDITIONS over V4.5
 *  ─────────────────────────
 *  1. [DohResolver] — a singleton [Dns] implementation backed by
 *     OkHttp's official [DnsOverHttps] module. Uses Cloudflare's
 *     1.1.1.1 DoH endpoint with hardcoded bootstrap IPs (1.1.1.1 +
 *     1.0.0.1) so the bootstrap itself cannot be DNS-poisoned.
 *
 *  2. [buildDohBootstrapClient] — a defensive OkHttpClient with a
 *     3-second MAXIMUM connect timeout (per the V5.0 directive). If
 *     the DoH endpoint is unreachable within 3 seconds, the resolver
 *     falls back to system DNS so playback never stalls.
 *
 *  3. [buildScrapingClient] — every OkHttpClient constructed inside
 *     [sniffRedirect] now has `.dns(DohResolver)` injected. The
 *     resolver runs BEFORE the system DNS, so for any IPTV domain
 *     (e.g. darplayer.xyz) the resolution happens entirely over
 *     HTTPS port 443 — fully isolated from the local ISP.
 *
 *  V4.5 PRESERVED (unchanged behavior)
 *  ───────────────────────────────────
 *  - [HeaderSpoofingMatrix] — atomic fingerprint rotation per scrape.
 *  - [SelfHealingTokenHandler] — atomic 403/Expired-Token trap.
 *  - [sniffRedirectAsync] — cancellable background scrape on
 *    Dispatchers.IO.
 *  - [cachedFinalUrl] / [cacheResolution] / [invalidate] — cache API.
 *  - [RedirectSnifferInterceptor] — manual redirect chain walker
 *    with cookie harvest and per-hop fingerprint re-application.
 * ─────────────────────────────────────────────────────────────────────────
 */
object RedirectSniffer {
    private const val TAG = "RedirectSniffer"

    data class SniffResult(
        val finalUrl: String,
        val cookies: Map<String, String>,
        val redirectChain: List<String>,
        val success: Boolean,
        val errorMessage: String? = null,
        /** V4.5 — Fingerprint profile used for this scrape (for diagnostics). */
        val profileUsed: String = "unknown",
        val userAgent: String = "VLC/3.0.20 LibVLC/3.0.20",
        /** V4.5 — HTTP status code of the terminal response (0 if unknown). */
        val terminalStatusCode: Int = 0,
        /** V5.0 — Whether the DoH resolver was used for domain resolution. */
        val dohUsed: Boolean = AppConfig.DOH_ENABLED
    ) {
        val edgeUrl: String get() = if (success) finalUrl else redirectChain.firstOrNull() ?: finalUrl
    }

    /**
     * In-process redirect cache — keyed by the ORIGINAL request URL.
     *
     * Once a redirect chain has been resolved, every subsequent segment
     * request from ExoPlayer hits the cache and skips the redirect RTT
     * entirely. Entries expire after [CACHE_TTL_MS] to handle edge-node
     * rotations.
     *
     * V4.5: The cache value now also tracks the fingerprint profile and
     * the upstream authorization token, so [SelfHealingTokenHandler]
     * can decide whether a 403 warrants a full re-scrape or just a
     * token refresh.
     */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private val redirectCache = ConcurrentHashMap<String, CachedResolution>()

    data class CachedResolution(
        val finalUrl: String,
        val cookies: Map<String, String>,
        val resolvedAt: Long,
        val profileUsed: String,
        val userAgent: String,
        val tokenSnapshot: String? = null
    )

    // ── V4.5 Background Scrape Scope ──
    // A private, isolated [Dispatchers.IO] scope. Every background
    // scrape launched here can be cancelled at the socket transport
    // layer without affecting the player or the UI.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ════════════════════════════════════════════════════════════════════════
    //  V5.0 — DNS-OVER-HTTPS RESOLVER (Cloudflare 1.1.1.1)
    //  ════════════════════════════════════════════════════════════════════════
    //  Singleton Dns implementation that routes every domain lookup
    //  through Cloudflare's 1.1.1.1 DoH endpoint via OkHttp's official
    //  DnsOverHttps module. The bootstrap client has a 3-second maximum
    //  connect timeout per the V5.0 directive. If the DoH endpoint is
    //  unreachable, the resolver falls back to system DNS.
    //
    //  The DoH resolver is consulted BEFORE the system DNS for every
    //  IPTV domain (e.g. darplayer.xyz), so domain detection is fully
    //  isolated from local cellular / landline providers — the ISP
    //  sees only an opaque HTTPS connection to 1.1.1.1 on port 443.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * V5.0 — The DoH-backed [Dns] resolver. Built once at class-load
     * time so all subsequent OkHttp clients share the same resolver
     * (and the same bootstrap connection pool).
     *
     * When [AppConfig.DOH_ENABLED] is `false`, this resolves to the
     * system DNS so the rest of the code can stay unconditional.
     */
    val DohResolver: Dns = buildDohResolver()

    /**
     * V5.0 — Construct the DoH resolver.
     *
     * Internally builds a bootstrap OkHttpClient with the mandated
     * 3-second maximum connect timeout, then plugs it into a
     * [DnsOverHttps] instance configured for Cloudflare's 1.1.1.1
     * endpoint. The bootstrap client uses hardcoded IPs for
     * `1.1.1.1` and `1.0.0.1` so the bootstrap itself cannot be
     * DNS-poisoned (if we resolved `1.1.1.1` via system DNS, an ISP
     * could redirect us to a fake DoH server).
     */
    private fun buildDohResolver(): Dns {
        if (!AppConfig.DOH_ENABLED) {
            return Dns.SYSTEM
        }
        return try {
            // ── Bootstrap client — defensive 3-second timeouts. ──
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(AppConfig.DOH_BOOTSTRAP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AppConfig.DOH_BOOTSTRAP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(AppConfig.DOH_BOOTSTRAP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()

            // ── Build the DnsOverHttps resolver. ──
            //  - url:              Cloudflare's DoH endpoint (JSON wire format).
            //  - bootstrapClient:  The defensive 3-second-timeout client.
            //  - bootstrapDnsHosts:Hardcoded IPs for 1.1.1.1 + 1.0.0.1 so the
            //                      DoH endpoint's own hostname resolution
            //                      cannot be poisoned.
            //  - includeIPv6:      false — most IPTV edges are IPv4-only,
            //                      and an AAAA lookup adds 100-300ms of
            //                      latency for nothing.
            val dohUrl: HttpUrl = AppConfig.DOH_CLOUDFLARE_ENDPOINT.toHttpUrlOrNullCompat()
                ?: run {
                    Log.e(TAG, "DoH endpoint URL is malformed — falling back to system DNS")
                    return Dns.SYSTEM
                }
            val resolver = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(dohUrl)
                .includeIPv6(false)
                .bootstrapDnsHosts(
                    AppConfig.DOH_BOOTSTRAP_IPS.map { InetAddress.getByName(it) }
                )
                .build()

            Log.i(TAG, "DoH resolver initialized → ${AppConfig.DOH_CLOUDFLARE_ENDPOINT} " +
                    "(bootstrap timeout=${AppConfig.DOH_BOOTSTRAP_CONNECT_TIMEOUT_MS}ms)")

            // Wrap the DoH resolver in a caching + fallback wrapper.
            // V9.7 — Added in-memory DNS cache (ConcurrentHashMap) so
            // repeated lookups for the same hostname (e.g., during HLS
            // segment fetching where the same edge host is hit 8+ times
            // per second) don't each trigger a full DoH round-trip.
            // Cache entries expire after 5 minutes to handle edge IP
            // rotations (same TTL as redirectCache).
            object : Dns {
                private val dnsCache =
                    java.util.concurrent.ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
                // V9.7 — DNS cache TTL: 5 minutes (matches redirectCache TTL)
                private val DNS_CACHE_TTL_MS = 5 * 60 * 1000L

                override fun lookup(hostname: String): List<InetAddress> {
                    // V9.7 — Check cache first
                    val cached = dnsCache[hostname]
                    if (cached != null) {
                        val (addresses, cachedAt) = cached
                        if (System.currentTimeMillis() - cachedAt < DNS_CACHE_TTL_MS) {
                            return addresses
                        }
                        // Expired — remove and re-resolve
                        dnsCache.remove(hostname)
                    }
                    // Resolve via DoH (with system DNS fallback)
                    val resolved = try {
                        resolver.lookup(hostname)
                    } catch (e: Throwable) {
                        Log.w(TAG, "DoH lookup failed for $hostname — falling back to system DNS: ${e.message}")
                        Dns.SYSTEM.lookup(hostname)
                    }
                    // V9.7 — Cache the result for future lookups
                    if (resolved.isNotEmpty()) {
                        dnsCache[hostname] = resolved to System.currentTimeMillis()
                    }
                    return resolved
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "DoH resolver construction FAILED — using system DNS: ${e.message}")
            Dns.SYSTEM
        }
    }

    /**
     * Cache lookup — exposed so the interceptor (and the player) can skip
     * the redirect phase entirely when the resolution is still fresh.
     *
     * THREAD-SAFE — safe to call from the main thread.
     */
    fun cachedFinalUrl(originalUrl: String): String? {
        val cached = redirectCache[originalUrl] ?: return null
        if (System.currentTimeMillis() - cached.resolvedAt > CACHE_TTL_MS) {
            redirectCache.remove(originalUrl)
            return null
        }
        return cached.finalUrl
    }

    /**
     * V4.5 — Full cache entry (including cookies + profile), or null if
     * expired. Used by [SelfHealingTokenHandler] when deciding whether
     * a 403 should trigger a full re-scrape or just a token rotation.
     */
    internal fun cachedResolution(originalUrl: String): CachedResolution? {
        val cached = redirectCache[originalUrl] ?: return null
        if (System.currentTimeMillis() - cached.resolvedAt > CACHE_TTL_MS) {
            redirectCache.remove(originalUrl)
            return null
        }
        return cached
    }

    /**
     * Update the cache from a freshly-resolved redirect chain.
     *
     * V4.5: Also accepts the fingerprint profile and the harvested
     * authorization token (if any) so the cache entry is self-describing.
     */
    private fun cacheResolution(
        originalUrl: String,
        finalUrl: String,
        cookies: Map<String, String>,
        profileUsed: String = "default",
        userAgent: String = "VLC/3.0.20 LibVLC/3.0.20",
        tokenSnapshot: String? = null
    ) {
        if (originalUrl.isBlank() || finalUrl.isBlank()) return
        redirectCache[originalUrl] = CachedResolution(
            finalUrl = finalUrl,
            cookies = cookies,
            resolvedAt = System.currentTimeMillis(),
            profileUsed = profileUsed,
            userAgent = userAgent,
            tokenSnapshot = tokenSnapshot
        )
    }

    /**
     * V4.5 — Invalidate a single cache entry. Called by
     * [SelfHealingTokenHandler] when an upstream 403 is detected so the
     * next playback re-resolves from scratch.
     */
    fun invalidate(originalUrl: String) {
        redirectCache.remove(originalUrl)
    }

    /**
     * V8.6 — Periodic cache cleanup. Sweeps expired entries from
     * [redirectCache] and [lastRescrapeAt] so they do not grow
     * unboundedly when the user browses thousands of channels without
     * replaying any. Called from [GlobalPlaybackCoordinator.forceTeardown]
     * (when the player is torn down on background/screen-off) and from
     * [GlobalPlaybackCoordinator.release].
     *
     * Without this sweep, expired entries linger until process death —
     * for a 22,000-channel playlist that's ~22 MB of stale metadata.
     */
    fun cleanupExpiredEntries() {
        val now = System.currentTimeMillis()
        val expiredUrls = mutableListOf<String>()
        redirectCache.forEach { (url, entry) ->
            if (now - entry.resolvedAt > CACHE_TTL_MS) expiredUrls.add(url)
        }
        expiredUrls.forEach { redirectCache.remove(it) }
        // lastRescrapeAt has no TTL (it's a cooldown timestamp) but we
        // cap it to the same TTL — anything older than 5 minutes is
        // definitely past the cooldown window and can be discarded.
        val expiredScrapeUrls = mutableListOf<String>()
        lastRescrapeAt.forEach { (url, ts) ->
            if (now - ts.get() > CACHE_TTL_MS) expiredScrapeUrls.add(url)
        }
        expiredScrapeUrls.forEach { lastRescrapeAt.remove(it) }
        if (expiredUrls.isNotEmpty() || expiredScrapeUrls.isNotEmpty()) {
            Log.i(TAG, "Cache cleanup: removed ${expiredUrls.size} expired redirect entries, " +
                    "${expiredScrapeUrls.size} stale rescrape timestamps")
        }
    }

    /**
     * V4.5 — ASYNCHRONOUS background scrape.
     *
     * Launches [sniffRedirect] on a private [Dispatchers.IO] scope.
     * The returned [Job] is bound to the caller's lifecycle — call
     * `job.cancel()` to abort the scrape at the socket transport layer
     * when the user zaps to a different channel.
     *
     * The optional [onResolved] callback fires on [Dispatchers.Main] only
     * if the scrape completes successfully AND the job has not been
     * cancelled. This is the safe path for the player to hot-swap the
     * media URI after a cache-warming scrape.
     *
     * @param originalUrl The raw stream URL (may 302/307 redirect).
     * @param onResolved  Optional main-thread callback with the result.
     * @return            The cancellable [Job] handle.
     */
    fun sniffRedirectAsync(
        originalUrl: String,
        onResolved: ((SniffResult) -> Unit)? = null
    ): Job {
        return ioScope.launch {
            val result = sniffRedirect(originalUrl)
            if (result.success && onResolved != null) {
                withContext(Dispatchers.Main) {
                    onResolved(result)
                }
            }
        }
    }

    /**
     * Backwards-compatible SYNCHRONOUS entry point.
     *
     * V4.5 CONTRACT — this function is BLOCKING and MUST be invoked on a
     * background dispatcher only. It performs a runtime thread-name
     * assertion to refuse main-thread calls. Use [sniffRedirectAsync]
     * for fire-and-forget background scrapes.
     *
     * V5.0 — The internal OkHttpClient now has `.dns(DohResolver)`
     * injected so domain resolution happens over HTTPS port 443 via
     * Cloudflare's 1.1.1.1 DoH endpoint. This is transparent to the
     * caller — the [SniffResult] contract is unchanged, but a new
     * `dohUsed` field indicates whether DoH was active.
     *
     * Internally builds a one-shot OkHttpClient with [RedirectSnifferInterceptor]
     * installed (followRedirects = false — the interceptor does the chain
     * manually so it can capture every hop + token, and applies the
     * [HeaderSpoofingMatrix] fingerprint rotation on every request).
     */
    fun sniffRedirect(originalUrl: String): SniffResult {
        if (originalUrl.isBlank()) return SniffResult(originalUrl, emptyMap(), emptyList(), false, "Empty URL")
        if (Thread.currentThread().name == "main") {
            Log.e(TAG, "FATAL: sniffRedirect called on main thread!")
            return SniffResult(originalUrl, emptyMap(), emptyList(), false, "Called on main thread")
        }

        // ── Fast path: cache hit, no network call. ──
        cachedFinalUrl(originalUrl)?.let { final ->
            return SniffResult(
                finalUrl = final,
                cookies = emptyMap(),
                redirectChain = listOf(final),
                success = true,
                profileUsed = "cache-hit",
                terminalStatusCode = 200
            )
        }

        // ── V4.5 — Acquire a fresh spoofed fingerprint for this scrape. ──
        val profile = HeaderSpoofingMatrix.acquireProfile()
        val collector = RedirectCollector()

        // ═══════════════════════════════════════════════════════════════
        //  V5.0 — Build the scraping client with the DoH resolver.
        //  ═══════════════════════════════════════════════════════════════
        //  `.dns(DohResolver)` is the ONLY V5.0 change to this client.
        //  Everything else (timeouts, followRedirects=false, interceptor
        //  installation) is preserved from V4.5 so the redirect-chain
        //  walking behavior is identical.
        // ═══════════════════════════════════════════════════════════════
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)   // the interceptor handles redirects manually
            .followSslRedirects(false)
            .dns(DohResolver)         // V5.0 — DoH via Cloudflare 1.1.1.1
            .addInterceptor(RedirectSnifferInterceptor(collector, profile))
            .build()

        return try {
            val request = HeaderSpoofingMatrix.applyTo(
                Request.Builder().url(originalUrl).get(),
                profile
            ).build()
            val response = client.newCall(request).execute()
            // Final URL — either the original (no redirect) or the last hop.
            val finalUrl = response.request.url.toString()
            val statusCode = response.code
            response.close()

            // ── V4.5 — Token harvest. ──
            // The interceptor already populated collector.cookies; we also
            // snapshot the dominant auth token (if any) so the cache entry
            // can describe its own authorization state.
            val tokenSnapshot = SelfHealingTokenHandler.extractTokenSnapshot(collector.cookies)

            cacheResolution(originalUrl, finalUrl, collector.cookies, profile.label, profile.userAgent, tokenSnapshot)
            Log.i(TAG, "sniffRedirect OK — profile=${profile.label}, hops=${collector.chain.size}, " +
                    "token=${if (tokenSnapshot != null) "yes" else "no"}, code=$statusCode, " +
                    "doh=${if (AppConfig.DOH_ENABLED) "on" else "off"}")
            SniffResult(
                finalUrl = finalUrl,
                cookies = collector.cookies,
                redirectChain = collector.chain,
                success = true,
                profileUsed = profile.label,
                userAgent = profile.userAgent,
                terminalStatusCode = statusCode
            )
        } catch (e: Exception) {
            Log.w(TAG, "sniffRedirect FAIL (${profile.label}): ${e.message}")
            SniffResult(
                finalUrl = originalUrl,
                cookies = emptyMap(),
                redirectChain = listOf(originalUrl),
                success = false,
                errorMessage = e.message,
                profileUsed = profile.label,
                userAgent = profile.userAgent,
                terminalStatusCode = 0
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  V4.5 — SELF-HEALING TOKEN HANDLER
    //  ════════════════════════════════════════════════════════════════════════
    //  Atomic trap for 403 / Expired-Token responses. When ExoPlayer's
    //  underlying HTTP stack fires a 403 (or a stream-specific
    //  "token expired" status), the player consults this handler before
    //  surfacing the error to the UI.
    //
    //  The handler:
    //    1. Invalidates the stale cache entry (forces a fresh resolution).
    //    2. Triggers a single-hop silent background re-scrape with a
    //       ROTATED fingerprint profile (so the WAF doesn't see the
    //       same fingerprint twice in a row).
    //    3. On success → invokes [onHotSwap] on the main thread with
    //       the new edge URL. The player hot-swaps the media URI
    //       seamlessly without a hard restart.
    //    4. On failure → invokes [onGiveUp] so the player can surface
    //       the error to the ViewModel.
    //
    //  RATE-LIMITED: at most one re-scrape per [RESCRAPE_COOLDOWN_MS]
    //  per URL, to prevent a 403 storm from triggering a tight loop.
    // ════════════════════════════════════════════════════════════════════════
    private const val RESCRAPE_COOLDOWN_MS = 4_000L
    private val lastRescrapeAt = ConcurrentHashMap<String, AtomicLong>()

    object SelfHealingTokenHandler {

        /**
         * Inspect a cookie jar and extract the most likely auth token.
         * Heuristic: prefer cookies whose name contains common auth
         * tokens ("token", "auth", "jwt", "sess", "hdnts").
         */
        fun extractTokenSnapshot(cookies: Map<String, String>): String? {
            if (cookies.isEmpty()) return null
            val authKeys = listOf("token", "auth", "jwt", "sess", "hdnts", "st", "playsession")
            for ((k, v) in cookies) {
                val lower = k.lowercase()
                if (authKeys.any { lower.contains(it) }) {
                    return "$k=$v"
                }
            }
            return null
        }

        /**
         * Returns true if the given HTTP status code or exception class
         * name looks like an authorisation failure that the handler
         * should trap.
         */
        fun isAuthFailure(statusCode: Int, exceptionClassName: String?): Boolean {
            if (statusCode == 401 || statusCode == 403) return true
            // Some streaming edges return 200 with a custom "token expired"
            // payload; the player surfaces those as InvalidResponseCodeException
            // or as a generic HttpDataSourceException with the original code
            // embedded in the message. Catch the common spellings.
            val n = exceptionClassName ?: return false
            return n.contains("InvalidResponseCodeException") && (statusCode == 401 || statusCode == 403)
        }

        /**
         * Atomically attempt a self-healing re-scrape for [originalUrl].
         *
         * Returns the launched [Job] (cancellable), or null if the
         * attempt was rate-limited / refused. The [onHotSwap] callback
         * fires on [Dispatchers.Main] only on a successful re-scrape.
         */
        fun attemptSelfHeal(
            originalUrl: String,
            onHotSwap: (newEdgeUrl: String, userAgent: String) -> Unit,
            onGiveUp: (reason: String) -> Unit
        ): Job? {
            if (originalUrl.isBlank()) return null

            // ── Cooldown gate — refuse if we just tried. ──
            val now = System.currentTimeMillis()
            val last = lastRescrapeAt.computeIfAbsent(originalUrl) { AtomicLong(0L) }
            val prev = last.get()
            if (now - prev < RESCRAPE_COOLDOWN_MS) {
                Log.w(TAG, "SelfHeal refused (cooldown ${now - prev}ms < $RESCRAPE_COOLDOWN_MS ms)")
                return null
            }
            if (!last.compareAndSet(prev, now)) {
                // Another thread beat us — let it handle the heal.
                return null
            }

            // Invalidate stale cache entry so the next sniff re-resolves.
            invalidate(originalUrl)

            Log.i(TAG, "SelfHeal triggered for ${originalUrl.take(60)}…")
            return sniffRedirectAsync(originalUrl) { result ->
                if (result.success) {
                    Log.i(TAG, "SelfHeal OK — hot-swapping to ${result.finalUrl.take(60)}…")
                    onHotSwap(result.finalUrl, result.userAgent)
                } else {
                    Log.w(TAG, "SelfHeal gave up: ${result.errorMessage}")
                    onGiveUp(result.errorMessage ?: "unknown")
                }
            }
        }
    }
}

/**
 * V5.0 — Compatibility shim that converts a String URL into an
 * `okhttp3.HttpUrl` and returns null on parse failure (instead of
 * throwing). Used by [RedirectSniffer.buildDohResolver] so a
 * malformed DoH endpoint URL in [AppConfig] cannot crash the app
 * at class-load time.
 */
private fun String.toHttpUrlOrNullCompat(): okhttp3.HttpUrl? {
    return try {
        // Use the extension function form `String.toHttpUrlOrNull()` —
        // the companion `HttpUrl.get(String)` is deprecated in OkHttp 4.x.
        this.toHttpUrlOrNull()
    } catch (t: Throwable) {
        Log.w("RedirectSniffer", "Malformed DoH URL: $this — ${t.message}")
        null
    }
}

/**
 * Mutable collector — populated by the interceptor as it walks the redirect
 * chain. Read by [RedirectSniffer.sniffRedirect] after the call returns.
 *
 * Marked `public` (not `internal`) so that [RedirectSnifferInterceptor] can
 * expose it as a constructor parameter without breaking Kotlin's visibility
 * rules.
 */
class RedirectCollector {
    val chain: MutableList<String> = ArrayList()
    val cookies: MutableMap<String, String> = LinkedHashMap()
}

/**
 * RedirectSnifferInterceptor — V5.0 the actual OkHttp [Interceptor].
 *
 * Install on any [OkHttpClient] used for media / segment requests to:
 *   - Capture every 3xx hop and expose it via [RedirectCollector].
 *   - Propagate Set-Cookie values (token cookies) across the chain.
 *   - Rewrite relative Location headers against the current request URL.
 *   - Apply the [HeaderSpoofingMatrix] fingerprint to every hop.
 *
 * The interceptor MANUALLY follows redirects (chain.proceed() once per hop)
 * instead of relying on OkHttp's built-in followRedirects, because we need
 * to inspect + cache every intermediate URL. The max-hop cap prevents loops.
 *
 * V4.5 — On every hop, the spoofed fingerprint is RE-APPLIED (some edges
 * reject the second hop if the User-Agent changes mid-chain — but with
 * the spoofing matrix we keep the SAME profile across the whole chain
 * for a single scrape, while rotating ACROSS scrapes). This is the
 * correct tradeoff: stable within a chain, diverse across chains.
 *
 * V5.0 — The interceptor itself is unchanged; the DoH resolver is
 * injected at the OkHttpClient level (`.dns(DohResolver)`) so it
 * applies transparently to every chain.proceed() call.
 */
class RedirectSnifferInterceptor(
    private val collector: RedirectCollector? = null,
    /** V4.5 — Fingerprint profile to apply on every hop. Defaults to a fresh acquire. */
    private val profile: HeaderSpoofingMatrix.Profile = HeaderSpoofingMatrix.acquireProfile()
) : Interceptor {

    private companion object {
        const val MAX_HOPS = 8
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = HeaderSpoofingMatrix.applyTo(
            chain.request().newBuilder(),
            profile
        ).build()
        var currentUrl = request.url.toString()
        collector?.chain?.add(currentUrl)

        var hop = 0
        while (hop++ <= MAX_HOPS) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // Network failure — let the caller handle it.
                throw e
            }

            // ── Cookie harvest — preserve tokens across hops. ──
            response.headers("Set-Cookie").forEach { raw ->
                val name = raw.substringBefore('=', "").trim()
                val value = raw.substringAfter('=', "").substringBefore(';').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    collector?.cookies?.put(name, value)
                }
            }

            if (response.code !in REDIRECT_CODES) {
                return response
            }

            // ── 3xx — extract Location and re-issue against the next hop. ──
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) {
                throw IOException("Redirect ${response.code} without Location header")
            }

            val nextUrl = resolveRelative(currentUrl, location)
            collector?.chain?.add(nextUrl)
            currentUrl = nextUrl

            // Build the next request — preserve method + body, RE-APPLY the
            // spoofed fingerprint headers, and carry any harvested cookies.
            val builder = request.newBuilder()
                .url(nextUrl)
                .method(request.method, request.body)
            // Carry cookies we've collected so far.
            if (collector != null && collector.cookies.isNotEmpty()) {
                builder.header("Cookie", collector.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            request = HeaderSpoofingMatrix.applyTo(builder, profile, preserveUserAgent = true).build()
        }

        throw IOException("Too many redirects (>$MAX_HOPS)")
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
}

// ════════════════════════════════════════════════════════════════════════
//  V4.5 — HEADER SPOOFING MATRIX
//  ════════════════════════════════════════════════════════════════════════
//  Dynamic fingerprint rotation factory. For every distinct scrape
//  request, a new [Profile] is acquired that bundles:
//    - A User-Agent string
//    - Sec-Ch-Ua / Sec-Ch-Ua-Mobile / Sec-Ch-Ua-Platform headers
//    - An Accept-Language header
//    - A randomized X-Forwarded-For IPv4 address
//    - A label (for diagnostics + cache annotation)
//
//  The matrix rotates ACROSS scrapes (not within a chain) so the WAF
//  sees a coherent fingerprint per request but cannot lock onto a
//  single static signature.
//
//  Three profile families are cycled:
//    1. Android TV (Chrome on Google TV)
//    2. Chromecast (Linux + Chromecast firmware UA)
//    3. Desktop (Chrome on Windows + Sec-Ch-Ua full chain)
//  A 4th fallback profile (Roku-style) is mixed in occasionally to
//  break up the pattern.
// ════════════════════════════════════════════════════════════════════════
object HeaderSpoofingMatrix {

    data class Profile(
        val label: String,
        val userAgent: String,
        val secChUa: String,
        val secChUaMobile: String,
        val secChUaPlatform: String,
        val acceptLanguage: String,
        val xForwardedFor: String
    )

    private val random = SecureRandom()
    private val rotationCounter = AtomicInteger(0)

    /** Random Android TV major version (Android 11/12/13). */
    private val androidVersions = listOf(
        "Android 11" to "11",
        "Android 12" to "12",
        "Android 13" to "13"
    )

    /** Random Chrome major version (so the Sec-Ch-Ua header is plausible). */
    private val chromeVersions = listOf(
        Triple("Chromium", "120", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\""),
        Triple("Chromium", "121", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\""),
        Triple("Chromium", "122", "\"Not_A Brand\";v=\"99\", \"Chromium\";v=\"122\", \"Google Chrome\";v=\"122\""),
        Triple("Chromium", "123", "\"Not_A Brand\";v=\"99\", \"Chromium\";v=\"123\", \"Google Chrome\";v=\"123\""),
        Triple("Chromium", "124", "\"Not_A Brand\";v=\"99\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\""),
        Triple("Chromium", "125", "\"Not_A Brand\";v=\"99\", \"Chromium\";v=\"125\", \"Google Chrome\";v=\"125\"")
    )

    /** Accept-Language pools — geographically diverse to break fingerprinting. */
    private val acceptLanguages = listOf(
        "en-US,en;q=0.9",
        "en-GB,en;q=0.9",
        "en-US,en;q=0.9,fr-FR;q=0.8",
        "en-US,en;q=0.9,es-ES;q=0.8",
        "en-US,en;q=0.9,de-DE;q=0.8",
        "en-US,en;q=0.9,pt-BR;q=0.8",
        "en-US,en;q=0.9,ar;q=0.8",
        "en-US,en;q=0.9,it-IT;q=0.8",
        "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
        "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    /**
     * Acquire a fresh spoofed profile for the next scrape request.
     *
     * Rotation order is deterministic (round-robin across families)
     * but the header VALUES inside each family are randomized, so the
     * WAF cannot predict the next fingerprint.
     */
    fun acquireProfile(): Profile {
        val idx = rotationCounter.getAndIncrement() and 0x7fffffff
        return when (idx % 4) {
            0 -> androidTvProfile(idx)
            1 -> chromecastProfile(idx)
            2 -> desktopProfile(idx)
            else -> rokuStyleProfile(idx)
        }
    }

    /**
     * Apply this profile's headers to a [Request.Builder].
     *
     * @param preserveUserAgent When true, do NOT overwrite the
     *   User-Agent header (used for re-issuing requests inside the
     *   interceptor where the UA was already set on the original
     *   request). Sec-Ch-Ua and other fingerprint headers are still
     *   re-applied so they remain consistent across hops.
     */
    fun applyTo(
        builder: Request.Builder,
        profile: Profile,
        preserveUserAgent: Boolean = false
    ): Request.Builder {
        if (!preserveUserAgent) {
            builder.header("User-Agent", profile.userAgent)
        }
        builder.header("Sec-Ch-Ua", profile.secChUa)
        builder.header("Sec-Ch-Ua-Mobile", profile.secChUaMobile)
        builder.header("Sec-Ch-Ua-Platform", profile.secChUaPlatform)
        builder.header("Accept-Language", profile.acceptLanguage)
        builder.header("X-Forwarded-For", profile.xForwardedFor)
        builder.header("Accept", "*/*")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        return builder
    }

    // ── Profile family builders ──

    private fun androidTvProfile(seed: Int): Profile {
        val (androidName, _) = androidVersions[seed % androidVersions.size]
        val (_, chromeMajor, secChUa) = chromeVersions[seed % chromeVersions.size]
        return Profile(
            label = "android-tv-$chromeMajor",
            userAgent = "Mozilla/5.0 (Linux; $androidName; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36",
            secChUa = secChUa,
            secChUaMobile = "?0",
            secChUaPlatform = "\"Android\"",
            acceptLanguage = acceptLanguages[random.nextInt(acceptLanguages.size)],
            xForwardedFor = randomPublicIp()
        )
    }

    private fun chromecastProfile(seed: Int): Profile {
        val (_, chromeMajor, secChUa) = chromeVersions[seed % chromeVersions.size]
        return Profile(
            label = "chromecast-$chromeMajor",
            userAgent = "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeMajor.0.0.0 Safari/537.36 CrKey/$chromeMajor.0.0.0",
            secChUa = secChUa,
            secChUaMobile = "?0",
            secChUaPlatform = "\"Linux\"",
            acceptLanguage = acceptLanguages[random.nextInt(acceptLanguages.size)],
            xForwardedFor = randomPublicIp()
        )
    }

    private fun desktopProfile(seed: Int): Profile {
        val (_, chromeMajor, secChUa) = chromeVersions[seed % chromeVersions.size]
        return Profile(
            label = "desktop-win-$chromeMajor",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36",
            secChUa = secChUa,
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            acceptLanguage = acceptLanguages[random.nextInt(acceptLanguages.size)],
            xForwardedFor = randomPublicIp()
        )
    }

    private fun rokuStyleProfile(seed: Int): Profile {
        return Profile(
            label = "roku-fallback",
            userAgent = "Roku/DVP-9.10 (459.10E04123A)",
            secChUa = "\"Not_A Brand\";v=\"99\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Roku\"",
            acceptLanguage = acceptLanguages[random.nextInt(acceptLanguages.size)],
            xForwardedFor = randomPublicIp()
        )
    }

    /**
     * Generate a random public IPv4 address from a non-reserved /8.
     * Excludes 10.x, 127.x, 169.254.x, 172.16-31.x, 192.168.x, 224-239.x.
     */
    private fun randomPublicIp(): String {
        while (true) {
            val a = random.nextInt(223) + 1   // 1..223
            if (a == 10) continue
            if (a == 127) continue
            if (a == 169) continue
            if (a == 172) {
                val second = random.nextInt(256)
                if (second in 16..31) continue
                return "$a.$second.${random.nextInt(256)}.${random.nextInt(254) + 1}"
            }
            if (a == 192) {
                val second = random.nextInt(256)
                if (second == 168) continue
                return "$a.$second.${random.nextInt(256)}.${random.nextInt(254) + 1}"
            }
            return "$a.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(254) + 1}"
        }
    }
}

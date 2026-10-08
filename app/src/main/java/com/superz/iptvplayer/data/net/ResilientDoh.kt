package com.superz.iptvplayer.data.net

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════
 *  ResilientDoh — the silent DNS-over-HTTPS chain (v1.2.0).
 *
 *  WHY: many ISPs poison/hijack plain DNS, returning wrong IPs or
 *  "domain not found" for IPTV panel servers — the user then needs a VPN
 *  just to sign in. Resolving through encrypted DoH endpoints bypasses
 *  the ISP's DNS entirely: blocked accounts simply open.
 *
 *  CHAIN (invisible, no UI, no settings):
 *      Cloudflare (1.1.1.1 / 1.0.0.1)
 *        → Google (8.8.8.8 / 8.8.4.4)
 *          → System DNS (identical to the previous app behavior)
 *
 *  GUARANTEE — "never worse than today": if both public DoH endpoints
 *  are unreachable in a given network, the chain silently degrades to
 *  the system resolver, i.e. exactly the pre-DoH behavior. The worst
 *  possible case is the current behavior.
 *
 *  PERFORMANCE contract (channel zapping must not slow down):
 *  • Endpoint bootstrap uses literal IPs — zero DNS needed to reach DoH.
 *  • A 30s in-memory TTL cache: after the first resolution of a host,
 *    zaps and segment fetches pay ZERO DNS cost.
 *  • A circuit breaker per endpoint: 3 consecutive failures = 60s
 *    cooldown, so a dead endpoint is probed at most once per minute.
 *  • Literal-IP hosts (very common in M3U links) skip the chain and go
 *    straight to the system resolver (OkHttp semantics preserved).
 *  • DoH lookups happen once per hostname — the video path itself
 *    (segments, connections) is untouched.
 *
 *  THREADING: lookups arrive on OkHttp I/O threads concurrently —
 *  all mutable state is guarded by a single lock.
 * ═══════════════════════════════════════════════════════════════════
 */
class ResilientDoh(
    /** Ordered DoH resolvers (primary first). */
    private val resolvers: List<Dns>,
    /** Final fallback — the system resolver (identical to old behavior). */
    private val lastResort: Dns = Dns.SYSTEM,
    /** Injectable clock for deterministic tests. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000,
    private val cacheTtlMs: Long = 30_000,
    private val cacheMaxEntries: Int = 128
) : Dns {

    /** Cache entry: addresses + the wall time they were resolved at. */
    private class Cached(val addresses: List<InetAddress>, val at: Long)

    private val lock = Any()

    /** TTL cache: hostname → resolved addresses (bounded, 30s freshness). */
    private val cache = LinkedHashMap<String, Cached>(32, 0.75f, false)

    /** Circuit-breaker state, parallel to [resolvers] by index. */
    private val failures = IntArray(resolvers.size)
    private val cooldownUntil = LongArray(resolvers.size)

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // 0) Literal-IP hosts (typical M3U stream URLs like http://1.2.3.4:8080/...)
        //    need no DNS at all — go straight to the system resolver so those
        //    streams keep today's exact latency profile.
        if (isLiteralAddress(hostname)) {
            return lastResort.lookup(hostname)
        }

        // 1) Positive cache — makes repeated lookups (zapping, reconnects,
        //    new connections to the same panel) free.
        synchronized(lock) {
            cache[hostname]?.let { cached ->
                val now = clock()
                if (now - cached.at < cacheTtlMs) {
                    return cached.addresses
                }
                cache.remove(hostname)
            }
        }

        // 2) The DoH chain with per-endpoint circuit breakers.
        for (index in resolvers.indices) {
            val resolver = resolvers[index]
            val cooling = synchronized(lock) { clock() < cooldownUntil[index] }
            if (cooling) continue // breaker open: skip this endpoint silently
            try {
                val result = resolver.lookup(hostname)
                if (result.isNotEmpty()) {
                    synchronized(lock) {
                        failures[index] = 0 // healthy again
                        cachePut(hostname, result)
                    }
                    return result
                }
                // Empty answer (NXDOMAIN is thrown, but be defensive) → treat as failure.
                synchronized(lock) { registerFailure(index) }
            } catch (t: Throwable) {
                if (t is UnknownHostException && t.message == "private hosts not resolved") {
                    // Single-label/LAN hostnames (e.g. "portal", "nas") are rejected
                    // by DoH LOCALLY (no network I/O) — try the next resolver; they
                    // are ultimately resolved by the system resolver.
                    continue
                }
                synchronized(lock) { registerFailure(index) }
                Log.d(TAG, "DoH endpoint #$index failed for $hostname: ${t.message}")
            }
        }

        // 3) System DNS — the guaranteed last resort (pre-DoH behavior).
        return lastResort.lookup(hostname)
    }

    /** Opens/extends the cooldown window once the failure threshold is hit. */
    private fun registerFailure(index: Int) {
        failures[index]++
        if (failures[index] >= failureThreshold) {
            cooldownUntil[index] = clock() + cooldownMs
        }
    }

    private fun cachePut(hostname: String, addresses: List<InetAddress>) {
        cache[hostname] = Cached(ArrayList(addresses), clock())
        // Bound the cache: drop the oldest entries beyond the cap.
        while (cache.size > cacheMaxEntries) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }

    companion object {
        private const val TAG = "ResilientDoh"

        /**
         * Builds the production chain: Cloudflare → Google → System.
         * The bootstrap client connects to the DoH endpoints via their literal
         * IPs (no DNS needed to reach DNS-over-HTTPS) with tight timeouts so a
         * dead endpoint is detected in ~1.2s instead of stalling a lookup.
         */
        fun create(): ResilientDoh {
            val bootstrap = OkHttpClient.Builder()
                .connectTimeout(1_200, TimeUnit.MILLISECONDS)
                .readTimeout(2_500, TimeUnit.MILLISECONDS)
                .callTimeout(3, TimeUnit.SECONDS) // bound the whole DoH query
                .build()

            val cloudflare = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1")
                )
                .build()

            val google = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .build()

            return ResilientDoh(listOf(cloudflare, google))
        }

        /** True when the host is already an IP literal (v4 or v6) — no DNS needed. */
        internal fun isLiteralAddress(host: String): Boolean {
            if (host.contains(':')) return true // IPv6 literal
            val parts = host.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } ?: false
            }
        }
    }
}

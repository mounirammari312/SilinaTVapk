package com.superz.iptvplayer.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v1.4.3 — picks the best playable VOD URL when the panel's LISTED
 * container extension may not match the real file on disk.
 *
 * v1.4.4 — REWRITTEN SEQUENTIAL (was: parallel). Field evidence from the
 * admagnun.net panel: it runs an aggressive per-IP firewall that RESETS
 * whole connections after short request bursts (verified while building
 * this release: a handful of rapid requests → every further connection
 * reset, across every panel IP). The v1.4.3 parallel probe fired 4-5
 * Range GETs at once + the engine right behind them — exactly the burst
 * pattern such firewalls punish, which could leave the ENGINE's real
 * playback connection reset ("movie doesn't start").
 *
 * New contract:
 *  • Candidates are probed ONE AT A TIME, in caller preference order.
 *  • First 2xx answer wins → typical case (listed extension correct)
 *    costs exactly ONE short-lived request.
 *  • 404/410 answers return in milliseconds → alternates tried quickly.
 *  • A network-level failure (reset/timeout) retries that candidate ONCE,
 *    then gives up probing entirely (panel unreachable/angry) → caller's
 *    first candidate is returned as before.
 *  • Probes send "Connection: close" — the panel frees the connection
 *    slot immediately, so the engine's own connection can never collide
 *    with a lingering probe socket on panels that count concurrent
 *    connections against the account.
 *
 * Never throws. Falls back to the FIRST candidate when nothing proves
 * usable — the engine's guards then apply as before.
 */
object VodUrlProbe {

    private const val PROBE_TIMEOUT_MS = 4500L
    private const val NETWORK_FAILURES_BEFORE_GIVE_UP = 2

    /**
     * @param candidates full VOD URLs ordered by preference (listed
     *        extension first, then common containers).
     * @return the best usable URL; the first candidate when no probe
     *         answered usable (slow-but-alive server, offline, …).
     */
    suspend fun best(okHttp: OkHttpClient, candidates: List<String>): String {
        if (candidates.size <= 1) return candidates.firstOrNull() ?: ""
        var netFails = 0
        for (url in candidates) {
            // First attempt + one in-place retry on a network-level fault.
            var status = probe(okHttp, url)
            if (status == null) {
                status = probe(okHttp, url)
                if (status == null) {
                    netFails++
                    if (netFails >= NETWORK_FAILURES_BEFORE_GIVE_UP) break
                    continue
                }
            }
            if (status in 200..299) return url
        }
        return candidates.first()
    }

    /** HTTP status of a 1-byte range GET, or null when unreachable. */
    private suspend fun probe(okHttp: OkHttpClient, url: String): Int? = withContext(Dispatchers.IO) {
        try {
            val probeClient = okHttp.newBuilder()
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("Connection", "close")
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                // Drain a little so a pooled socket isn't half-read when
                // the panel closes it — then let use{} dispose fully.
                try {
                    val stream = response.body?.byteStream()
                    if (stream != null) {
                        stream.read(ByteArray(64))
                        stream.close()
                    }
                } catch (ignore: Exception) { /* socket hygiene only */ }
                response.code
            }
        } catch (e: Exception) {
            null
        }
    }
}

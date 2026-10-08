package com.superz.iptvplayer.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * v1.4.4 — network-level retry with backoff.
 *
 * Field evidence (admagnun.net panel): the panel runs an aggressive
 * firewall that transiently RESETS single connections — an info fetch
 * or a VOD-sync list call can die once and succeed one second later
 * (verified while building this release). Retrying twice with short
 * backoff heals those blips invisibly.
 *
 * ONLY network-level faults retry (IOException family: reset, timeout,
 * mid-body cut). [XtreamException] is definitive (HTTP status, parse,
 * empty body) and propagates immediately.
 */
internal suspend fun <T> withNetRetries(
    attempts: Int = 3,
    backoffsMs: List<Long> = listOf(350L, 900L),
    block: suspend () -> T
): T {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            last = e
            val wait = backoffsMs.getOrNull(attempt) ?: backoffsMs.lastOrNull() ?: 0L
            if (wait > 0) delay(wait)
        }
    }
    throw last ?: IOException("Network error")
}

// ─────────────────────────────────────────────────────────────────
// Xtream Codes / XUI panel API models.
// NOTE: most numeric fields arrive as STRINGS ("1", "1750000000") —
// modelled as String and converted defensively.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class AuthResponse(
    @SerialName("user_info") val userInfo: UserInfo? = null,
    @SerialName("server_info") val serverInfo: ServerInfo? = null
)

@Serializable
data class UserInfo(
    val auth: Int? = null,
    val status: String? = null,
    val message: String? = null,
    val username: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null
) {
    val activeConnectionsInt: Int? get() = activeCons?.trim()?.toIntOrNull()
    val maxConnectionsInt: Int? get() = maxConnections?.trim()?.toIntOrNull()
    val expiryEpoch: Long? get() = expDate?.trim()?.toLongOrNull()
}

@Serializable
data class ServerInfo(
    val url: String? = null,
    val port: String? = null
)

@Serializable
data class XtreamCategory(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String
)

@Serializable
data class XtreamStream(
    val num: Int? = null,
    val name: String = "",
    @SerialName("stream_id") val streamId: Long = 0L,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("direct_source") val directSource: String? = null
)

/** Typed failure surfaced to the login screen. */
class XtreamException(message: String) : Exception(message)

/**
 * Minimal Xtream client over the shared OkHttpClient.
 * All calls run on Dispatchers.IO with a 15s watchdog.
 */
class XtreamClient(private val okHttp: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun authenticate(baseUrl: String, username: String, password: String): AuthResponse =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}")

    suspend fun liveCategories(baseUrl: String, username: String, password: String): List<XtreamCategory> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_live_categories")

    suspend fun liveStreams(baseUrl: String, username: String, password: String): List<XtreamStream> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_live_streams")

    // ── v1.4.0 VOD endpoints (added, existing calls untouched) ──

    suspend fun vodCategories(baseUrl: String, username: String, password: String): List<XtreamCategory> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_vod_categories")

    suspend fun vodStreams(baseUrl: String, username: String, password: String): List<XtreamVodStream> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_vod_streams")

    suspend fun seriesCategories(baseUrl: String, username: String, password: String): List<XtreamCategory> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_series_categories")

    suspend fun seriesList(baseUrl: String, username: String, password: String): List<XtreamSeries> =
        call("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_series")

    /**
     * Raw JSON of get_vod_info — parsed tolerantly by [VodInfoParser].
     *
     * v1.4.6 ROOT-CAUSE FIX (empty movie info pages): the decompiled
     * reference app (VU IPTV) sends this action with **vod_id**, and the
     * user's XUI panel serves NOTHING for stream_id alone — which is
     * exactly why series info (series_id — correct param) always worked
     * while EVERY movie page came back empty. Both params are now sent
     * together: XUI panels read vod_id, classic XC panels read
     * stream_id — each panel picks the one it knows and ignores the
     * other (built by the testable [XtreamClient.vodInfoUrl]).
     */
    suspend fun vodInfoRaw(baseUrl: String, username: String, password: String, streamId: Long): String =
        rawCall(vodInfoUrl(baseUrl, username, password, streamId))

    /** Raw JSON of get_series_info — parsed tolerantly by [VodInfoParser]. */
    suspend fun seriesInfoRaw(baseUrl: String, username: String, password: String, seriesId: Long): String =
        rawCall("${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_series_info&series_id=$seriesId")

    /** Body-as-string variant of [call] for endpoints parsed with org.json.
     *  v1.4.3 — every call is recorded (redacted) into [VodTrace] so the
     *  info-page failure mode is diagnosable from the device.
     *  v1.4.4 — wrapped in [withNetRetries]: a connection RESET by the
     *  panel's firewall is retried (up to 3 attempts) BEFORE it is traced
     *  as a failure — the trace now shows real, persistent faults. */
    private suspend fun rawCall(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            withNetRetries {
                okHttp.newCall(request).execute().use { response ->
                    // Body read can be cut mid-flight (reset) — that is
                    // network-level and retries; HTTP status/blank are
                    // definitive and do not.
                    val body = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        throw IOException("READ_FAIL_${e.javaClass.simpleName}", e)
                    }
                    if (body == null) throw XtreamException("EMPTY_RESPONSE_${response.code}")
                    if (!response.isSuccessful) throw XtreamException("HTTP_${response.code}")
                    if (body.isBlank()) throw XtreamException("EMPTY_RESPONSE")
                    com.superz.iptvplayer.diagnostics.VodTrace.record(url, response.code, null, body)
                    body
                }
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: SocketTimeoutException) {
            com.superz.iptvplayer.diagnostics.VodTrace.record(url, null, "TIMEOUT", null)
            throw XtreamException("TIMEOUT")
        } catch (e: UnknownHostException) {
            com.superz.iptvplayer.diagnostics.VodTrace.record(url, null, "HOST_NOT_FOUND", null)
            throw XtreamException("HOST_NOT_FOUND")
        } catch (e: SSLException) {
            com.superz.iptvplayer.diagnostics.VodTrace.record(url, null, "SSL_ERROR", null)
            throw XtreamException("SSL_ERROR")
        } catch (e: Exception) {
            com.superz.iptvplayer.diagnostics.VodTrace.record(
                url, null, e.message ?: "Network error", null
            )
            throw XtreamException(e.message ?: "Network error")
        }
    }

    private suspend inline fun <reified T> call(url: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            // v1.4.4 — same network-level retry as rawCall: one reset by a
            // burst-triggered firewall no longer empties the whole movie /
            // series grid for a session.
            withNetRetries {
                okHttp.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                        ?: throw XtreamException("EMPTY_RESPONSE_${response.code}")
                    if (!response.isSuccessful) throw XtreamException("HTTP_${response.code}")
                    if (body.isBlank()) throw XtreamException("EMPTY_RESPONSE")
                    if (body.trimStart().startsWith("<")) throw XtreamException("NOT_XTREAM_PANEL")
                    try {
                        json.decodeFromString<T>(body)
                    } catch (e: Exception) {
                        throw XtreamException("INVALID_RESPONSE")
                    }
                }
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw XtreamException("TIMEOUT")
        } catch (e: UnknownHostException) {
            throw XtreamException("HOST_NOT_FOUND")
        } catch (e: SSLException) {
            throw XtreamException("SSL_ERROR")
        } catch (e: Exception) {
            throw XtreamException(e.message ?: "Network error")
        }
    }

    companion object {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /**
         * v1.4.6 — URL contract for get_vod_info: vod_id FIRST (the
         * reference app's param — XUI panels), stream_id second (classic
         * XC panels). Extracted as a pure builder so the contract is
         * unit-testable without a network.
         */
        internal fun vodInfoUrl(baseUrl: String, username: String, password: String, streamId: Long): String =
            "${normalizeBaseUrl(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}" +
                "&action=get_vod_info&vod_id=$streamId&stream_id=$streamId"

        /**
         * Cleans user-entered portal URLs via [PortalUrl]: adds the scheme,
         * strips /player_api.php and /get.php endpoints, drops any query
         * string (with its credentials) and preserves reverse-proxy paths.
         * Falls back to a conservative trim for anything unparseable.
         */
        fun normalizeBaseUrl(raw: String): String {
            PortalUrl.parse(raw)?.let { return it.base }
            var base = raw.trim().trimEnd('/')
            if (base.endsWith("/player_api.php", ignoreCase = true)) {
                base = base.substring(0, base.length - "/player_api.php".length).trimEnd('/')
            }
            if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
                base = "http://$base"
            }
            return base
        }
    }
}

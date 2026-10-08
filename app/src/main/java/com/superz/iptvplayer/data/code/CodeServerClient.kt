package com.superz.iptvplayer.data.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Talks to the account-generation server (empreinte on Render).
 *
 * Flow: user types a 6-digit code → POST /app/generate → the server answers
 * with 3 verified Xtream accounts, exactly like the Telegram bot delivers.
 *
 * The code IS the user's permanent identity on the server — no sign-up.
 *
 * Timeouts are deliberately generous: Render's free tier cold-starts the
 * service in ~30–60s; the login button shows its spinner meanwhile.
 */
object CodeServerClient {

    /** Agreed server — the user's own empreinte deployment on Render.
     *  v2.0.0: FALLBACK — the admin panel can repoint it live
     *  (servers.codeApiUrl) without an app update. */
    private const val SERVER_URL =
        "https://empreinte-zkfk.onrender.com/app/generate"

    /** Live server URL — panel override or the built-in fallback. */
    private fun serverUrl(): String =
        com.superz.iptvplayer.data.remote.OriaRemote.current.servers.codeApiUrl
            .ifBlank { SERVER_URL }

    /** One generated Xtream account, exactly as the server sends it. */
    data class CodeAccount(
        val host: String,
        val user: String,
        val pass: String,
        val expDate: String? = null,
        val connections: Int? = null
    )

    /**
     * Machine-readable failures from the generation service.
     * [retryAfter] is set for RATE_LIMIT (seconds until the limit lifts).
     */
    class CodeServerException(
        val code: String,
        val retryAfter: Int? = null
    ) : Exception(code)

    /**
     * Requests 3 verified accounts for [code].
     * Returns 1..3 accounts (server may serve fewer if the pool is drained).
     * @throws CodeServerException with codes:
     *   INVALID_CODE | RATE_LIMIT | NO_ACCOUNTS | EMPTY | TIMEOUT |
     *   HOST_NOT_FOUND | SSL_ERROR | HTTP_xxx | NETWORK
     */
    suspend fun generateAccounts(
        code: String,
        shared: OkHttpClient
    ): List<CodeAccount> = withContext(Dispatchers.IO) {
        // Generous timeouts: Render free tier cold start can take 30-60s.
        val http = shared.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(110, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = JSONObject().put("code", code).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl())
            .post(body)
            .header("User-Agent", "Oria-IPTV/1.13 (Android)")
            .build()

        val text = try {
            http.newCall(request).execute().use { response ->
                val payload = response.body?.string() ?: ""
                if (response.code == 429) {
                    val retry = runCatching {
                        JSONObject(payload).optInt("retry_after", 0)
                    }.getOrDefault(0)
                    throw CodeServerException("RATE_LIMIT", retry.takeIf { it > 0 })
                }
                if (!response.isSuccessful) {
                    // Pass the server's own error code through when present.
                    val serverCode = runCatching {
                        JSONObject(payload).optString("error", "")
                    }.getOrDefault("")
                    if (serverCode.isNotBlank()) throw CodeServerException(serverCode)
                    throw CodeServerException("HTTP_${response.code}")
                }
                payload
            }
        } catch (e: CodeServerException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CodeServerException("TIMEOUT")
        } catch (e: UnknownHostException) {
            throw CodeServerException("HOST_NOT_FOUND")
        } catch (e: javax.net.ssl.SSLException) {
            throw CodeServerException("SSL_ERROR")
        } catch (e: java.io.IOException) {
            throw CodeServerException("NETWORK")
        }

        parseAccounts(text)
    }

    private fun parseAccounts(text: String): List<CodeAccount> {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw CodeServerException("EMPTY")
        }
        if (root.optBoolean("ok", false) || root.has("accounts")) {
            val arr = root.optJSONArray("accounts")
                ?: throw CodeServerException("EMPTY")
            val out = mutableListOf<CodeAccount>()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val host = a.optString("host", "").trim()
                val user = a.optString("user", "").trim()
                val pass = a.optString("pass", "")
                if (host.isBlank() || user.isBlank() || pass.isBlank()) continue
                out += CodeAccount(
                    host = host,
                    user = user,
                    pass = pass,
                    expDate = a.optString("exp_date", "").ifBlank { null },
                    connections = if (a.has("connections")) {
                        runCatching { a.optInt("connections") }.getOrNull()
                    } else null
                )
            }
            if (out.isNotEmpty()) return out
        }
        throw CodeServerException("EMPTY")
    }
}

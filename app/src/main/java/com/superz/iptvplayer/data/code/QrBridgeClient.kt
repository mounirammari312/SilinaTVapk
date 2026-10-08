package com.superz.iptvplayer.data.code

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QrBridgeClient — the QR login engine ("SMART CONNECT"), copied from the
 * reference app (silinatv-pro-v111, LoginActivity.kt §SMART CONNECT).
 *
 * v1.14.1 — BRIDGE BACKEND SWAPPED: the reference's Supabase project was
 * permanently deleted (NXDOMAIN), so both sides of the bridge now run in
 * the user's OWN Vercel project (silina-qr-page):
 *   phone page → POST /api/qr  → MongoDB (oria_qr.auth_bridge)
 *   TV app     → GET  /api/qr  (read-once) → DELETE /api/qr (sweep)
 * The ENGINE is unchanged: same QR generation (zxing), same 2s poll cadence
 * (driven by the caller), same zero-retention delete after the handshake,
 * same Base64-JSON payload decoding, same 10s OkHttp timeouts. The API even
 * answers in the Supabase REST shape ([{"payload": …}] / []) so the
 * response parsing is byte-identical to the reference's.
 */
object QrBridgeClient {

    // ═══ SMART CONNECT — bridge constants (was: AppConfig's Supabase) ═════
    /**
     * The QR login bridge API — a serverless function in the user's own
     * Vercel project, backed by the user's MongoDB (never the bot's DB).
     * v2.0.0: this is the FALLBACK base — the admin panel can override it
     * live (servers.qrApiUrl) without an app update.
     */
    const val BRIDGE_API_BASE: String = "https://silinatv-qr-page.vercel.app/api/qr"

    /** Vercel-hosted QR landing page base URL (the user's own page).
     *  v2.0.0: fallback — overridable live via servers.qrPageUrl. */
    const val VERCEL_QR_BASE: String = "https://silinatv-qr-page.vercel.app/"

    /** Live bridge API base — panel override or the built-in fallback. */
    private fun bridgeApiBase(): String =
        com.superz.iptvplayer.data.remote.OriaRemote.current.servers.qrApiUrl
            .ifBlank { BRIDGE_API_BASE }

    /** Live QR page base — panel override or the built-in fallback. */
    private fun qrPageBase(): String =
        com.superz.iptvplayer.data.remote.OriaRemote.current.servers.qrPageUrl
            .ifBlank { VERCEL_QR_BASE }

    /** The QR URL the TV shows — the page pre-fills the TV code from ?code=. */
    fun qrUrlFor(tvCode: String): String = "${qrPageBase().trimEnd('/')}/?code=$tvCode"

    /** The poll URL — read-once; the "eq." filter shape is the engine's. */
    fun pollUrlFor(tvCode: String): String = "${bridgeApiBase().trimEnd('/')}?tv_code=eq.$tvCode"

    /** The zero-retention sweep URL (a no-op after a read-once poll). */
    fun deleteUrlFor(tvCode: String): String = "${bridgeApiBase().trimEnd('/')}?tv_code=eq.$tvCode"

    // ═══ The engine, verbatim from the reference's LoginActivity ═════════

    /** zxing QR bitmap generation — reference generateQrBitmap, verbatim. */
    fun generateQrBitmap(text: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bridge poll — reference pollSupabaseForPayload, same logic: GET with
     * 10s timeouts, parse the JSON array, return [payload] of row 0 or null.
     * (The API answers in the Supabase REST shape, so the parsing is
     * byte-identical to the reference; only the URL changed.)
     */
    suspend fun pollBridgeForPayload(tvCode: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(pollUrlFor(tvCode))
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val array = org.json.JSONArray(body)
                if (array.length() > 0) {
                    array.getJSONObject(0).optString("payload", "")
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Zero-retention sweep — reference deleteSupabaseRecord, same logic:
     * a 10s-timeout DELETE for the code; a no-op after the read-once poll.
     */
    suspend fun deleteBridgeRecord(tvCode: String) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(deleteUrlFor(tvCode))
                    .delete()
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
            }
        }
    }

    /** Base64 JSON decode — reference decodeBase64Json, verbatim. */
    fun decodeBase64Json(base64: String): JSONObject? {
        return try {
            val jsonStr = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    // ═══ Pure payload model (ours — feeds the existing login engines) ═════

    /**
     * The decoded bridge payload. The page always sends all keys
     * (empty strings for the inactive mode):
     *   xtream → {mode, profile, host, user, pass, m3u:""}
     *   m3u    → {mode, profile, host:"", user:"", pass:"", m3u}
     */
    data class QrPayload(
        val mode: String,
        val profile: String,
        val host: String,
        val user: String,
        val pass: String,
        val m3u: String
    ) {
        val isXtream: Boolean get() = mode == "xtream"
        val isM3u: Boolean get() = mode == "m3u"

        companion object {
            fun fromJson(json: JSONObject): QrPayload = QrPayload(
                mode = json.optString("mode", ""),
                profile = json.optString("profile", ""),
                host = json.optString("host", ""),
                user = json.optString("user", ""),
                pass = json.optString("pass", ""),
                m3u = json.optString("m3u", "")
            )

            /** Full pipeline: base64 (as delivered by the bridge) → payload. */
            fun fromBase64(base64: String): QrPayload? =
                decodeBase64Json(base64)?.let { fromJson(it) }
        }
    }
}

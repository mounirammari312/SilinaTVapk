package com.superz.iptvplayer.data.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.superz.iptvplayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * v2.0.0 — THE ANONYMOUS INSTALL PULSE.
 *
 * Once per day (and once per fresh process, at most) the app sends:
 *   installId (random UUID generated locally — NOT any device identifier),
 *   appVersion, versionCode, deviceModel, androidVersion, language.
 *
 * No personal data, no accounts, no location — the panel's user counts
 * are aggregate numbers only. Failed sends are silently dropped; the
 * daily gate lives in plain prefs.
 */
object Heartbeat {

    private const val TAG = "Heartbeat"
    private const val PREFS = "oria_remote"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_LAST_SENT = "heartbeat_last_ms"
    private const val DAY_MS = 24 * 3600 * 1000L

    private val http = okhttp3.OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    /** The stable anonymous id of THIS install (created on first call). */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_INSTALL_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        }
        return id
    }

    /** Fire-and-forget; safe to call from Application.onCreate. */
    fun maybeSend(context: Context, scope: CoroutineScope) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_SENT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < DAY_MS) return
        scope.launch(Dispatchers.IO) {
            val ok = send(context)
            if (ok) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_SENT, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "heartbeat sent")
            } else {
                Log.d(TAG, "heartbeat failed — will retry next launch")
            }
        }
    }

    private suspend fun send(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("installId", installId(context))
                .put("appVersion", BuildConfig.VERSION_NAME ?: "?")
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("deviceModel", Build.MODEL?.take(64) ?: "?")
                .put("androidVersion", Build.VERSION.RELEASE?.take(16) ?: "?")
                .put("lang", context.resources.configuration.locales.get(0)?.language ?: "?")
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            http.newCall(
                Request.Builder()
                    .url("${OriaRemote.GATEWAY_BASE}/api/heartbeat")
                    .header("User-Agent", "OriaAndroid/2.0.0")
                    .post(body)
                    .build()
            ).execute().use { resp -> resp.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }
}

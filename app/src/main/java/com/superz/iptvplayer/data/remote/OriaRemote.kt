package com.superz.iptvplayer.data.remote

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v2.0.0 — THE CONFIG GATEWAY.
 *
 * [OriaRemote] is the single owner of the remote-config lifecycle:
 *
 *  1. [bootstrap] is called once from IPTVApp.onCreate — it NEVER blocks
 *     startup: the cached config (if any) is applied synchronously from
 *     prefs, then a fresh fetch runs on IO.
 *  2. [current] is a @Volatile snapshot that non-Compose code reads (URL
 *     builders in QrBridgeClient / CodeServerClient) — always a complete
 *     config, never null.
 *  3. UI-facing bits (accent color, images) are pushed into
 *     com.superz.iptvplayer.ui.theme.OriaBranding, which is Compose state —
 *     every screen that reads it restyles itself when the panel changes
 *     something.
 *
 * Failure discipline: any network/parse error keeps the previous value.
 * A brand-new install with a dead gateway simply stays on [RemoteConfig.DEFAULTS]
 * — which are the exact v1.19.15 constants (gold #D4AF37, the user's own
 * Vercel QR page, the empreinte code server). The panel can only ADD, never
 * break.
 */
object OriaRemote {

    const val TAG = "OriaRemote"

    /**
     * The admin panel's home — the user's live Vercel deployment
     * (project name `oria-admin-nine`; the bare `oria-admin` name was
     * already taken globally by an unrelated project, so Vercel assigned
     * the `-nine` suffix). This is the ONE line to change + rebuild if
     * the panel ever moves.
     */
    const val GATEWAY_BASE = "https://oria-admin-nine.vercel.app"

    private const val CONFIG_URL = "$GATEWAY_BASE/api/config"
    private const val PREFS = "oria_remote"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"


    /** Always a COMPLETE config — starts at built-in defaults. */
    @Volatile
    var current: RemoteConfig = RemoteConfig.DEFAULTS
        private set

    /** Compose-observable counter — bumps on every applied config (cache or network). */
    var configEpoch: Int by androidx.compose.runtime.mutableStateOf(0)
        private set

    /** Set once the first fetch/pref-load completes (Compose can key off it). */
    @Volatile
    var lastAppliedAt: Long = 0L
        private set

    private val http = okhttp3.OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** App context for [UpdateCenter.evaluate] (set in [bootstrap]). */
    private lateinit var appContext: android.content.Context

    /**
     * Apply cached config synchronously, then refresh from the network.
     * Safe to call from Application.onCreate — the network part is launched
     * on the caller's scope.
     *
     * v2.0.4 — THE CACHE IS THE RAW PANEL RESPONSE, VERBATIM. The v2.0.0–
     * v2.0.3 [jsonOf] rebuilt the JSON by hand and SILENTLY DROPPED every
     * field added after v2.0.0 (announcement imageUrl/linkUrl/buttonText,
     * the v2.0.3 backgrounds + appName/appIconUrl). A cache that loses
     * fields produces a DIFFERENT announcement signature than the live
     * config — so on every cold start the cached announcement armed the
     * card, then the fresh fetch re-keyed it against a "seen" signature it
     * had never shown and the card VANISHED: the user's "كل مرة ادخل
     * يضهر يرمش ويختفي". Storing the exact response body makes the
     * round-trip lossless for every field the panel will ever add.
     */
    fun bootstrap(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        // 1) synchronous cache apply (fast prefs read, no disk JSON storm —
        //    happens once per process start)
        val cached = readCache(context)
        if (cached != null) {
            apply(cached)
            Log.d(TAG, "applied cached config (accent=${cached.branding.accentColor})")
        }
        // v2.1.2 — HERMETIC TESTS: Robolectric instantiates IPTVApp from the
        // manifest for every test class, so this live refresh used to race
        // the cache regression test (OriaRemoteCacheTest) and overwrite the
        // prefs mid-assertion — invisible while the fixture mirrored the
        // live panel, visible the moment the panel's announcement image
        // changed. Real devices never carry this fingerprint; the refresh
        // runs exactly as before everywhere else.
        if (android.os.Build.FINGERPRINT.startsWith("robolectric")) return
        // 2) background refresh
        scope.launch(Dispatchers.IO) {
            val raw = fetchRaw()
            if (raw == null) {
                Log.w(TAG, "config fetch failed — keeping previous values")
                return@launch
            }
            try {
                val fetched = RemoteConfig.parse(JSONObject(raw))
                apply(fetched)
                persist(context, raw)
                Log.i(TAG, "remote config applied (accent=${fetched.branding.accentColor} " +
                        "logo=${fetched.branding.logoUrl.take(40)} v=${fetched.update.versionName})")
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "config JSON malformed: ${e.message}")
            }
        }
    }

    /** One-shot fetch of the RAW response body — bootstrap parses + persists it. */
    private suspend fun fetchRaw(): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(
                okhttp3.Request.Builder()
                    .url(CONFIG_URL)
                    .header("User-Agent", "OriaAndroid/2.0.5")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "config HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: IOException) {
            null
        }
    }

    @Synchronized
    private fun apply(config: RemoteConfig) {
        current = config
        lastAppliedAt = System.currentTimeMillis()
        configEpoch += 1
        // push UI-visible bits into Compose state (theme, logo, backgrounds)
        com.superz.iptvplayer.ui.theme.OriaBranding.applyConfig(config)
        // and let the update center re-evaluate the panel's announced version
        // (pending → MainActivity shows OriaUpdateDialog; obsolete → clears).
        if (::appContext.isInitialized) {
            com.superz.iptvplayer.data.remote.UpdateCenter.evaluate(config, appContext)
        }
    }

    /** v2.0.4 — internal for the cache round-trip regression test. */
    internal fun readCache(context: Context): RemoteConfig? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG_JSON, null) ?: return null
        val ts = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (ts <= 0L) null else RemoteConfig.parse(JSONObject(json))
    } catch (e: Exception) {
        null
    }

    /** v2.0.4 — persists the RAW response body verbatim (see [bootstrap]). */
    internal fun persist(context: Context, rawJson: String) = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, rawJson)
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    } catch (e: Exception) {
        Log.w(TAG, "persist failed: ${e.message}")
    }
}

package com.superz.iptvplayer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.superz.iptvplayer.data.db.AppDatabase
import com.superz.iptvplayer.data.net.ResilientDoh
import com.superz.iptvplayer.data.stalker.StalkerMediaHeaders
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Application core: shared singletons for DB, HTTP and background work.
 *
 * One OkHttpClient is shared by the Xtream API, M3U downloader, the
 * pre-connector AND ExoPlayer's OkHttpDataSource — so TCP connections are
 * pooled and reused across the whole app (this is what makes "pre-connect"
 * effective: the warmed socket is already in the pool when playback starts).
 *
 * PORTAL COMPATIBILITY (reference-app behavior): every request — API calls,
 * M3U downloads AND media segments — is sent with a native VLC User-Agent.
 * Many IPTV panels and CDNs reject the default `okhttp/4.x` UA with
 * 403/404, which is exactly why earlier versions failed to load channels
 * while VLC-based reference apps sailed through the same portals.
 */
class IPTVApp : Application(), ImageLoaderFactory {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // v1.2.0 — silent DNS-over-HTTPS chain: Cloudflare → Google → System.
            // Bypasses ISP DNS poisoning of IPTV panel domains (blocked accounts
            // open without a VPN). Zero effect on playback path latency: lookups
            // are cached (30s TTL), literal-IP hosts skip the chain entirely,
            // and dead endpoints are circuit-broken for 60s at a time.
            .dns(ResilientDoh.create())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor { chain ->
                // v1.11.0 — stalker media scope FIRST: hosts registered at
                // create_link resolve time (the tmp play URL + the portal)
                // get the reference's media User-Agent — the reference sends
                // ONLY a UA on media ("VU IPTV Player" default,
                // SharedPreferenceHelper; tmp links carry their own
                // play_token so no Cookie/Referer is needed). Redirect
                // targets of a registered host join the scope, because
                // portals front their CDNs with 302s.
                val stalkerHost = StalkerMediaHeaders.isRegistered(chain.request().url.host)
                val request = if (stalkerHost) {
                    chain.request().newBuilder()
                        .header("User-Agent", StalkerMediaHeaders.MEDIA_USER_AGENT)
                        .header("Accept", "*/*")
                        .build()
                } else {
                    chain.request().newBuilder()
                        .header("User-Agent", VLC_USER_AGENT)
                        .header("Accept", "*/*")
                        .header("Icy-MetaData", "1")
                        .build()
                }
                val response = chain.proceed(request)
                if (stalkerHost) {
                    response.header("Location")?.let { loc ->
                        request.url.resolve(loc)?.let { target ->
                            StalkerMediaHeaders.register(target.host)
                        }
                    }
                }
                response
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The CrashProvider normally initializes diagnostics BEFORE this
        // point; if it somehow did not run, initialize here (idempotent).
        com.superz.iptvplayer.diagnostics.CrashDiagnostics.ensureInitialized(this)
        com.superz.iptvplayer.diagnostics.CrashDiagnostics.breadcrumb("IPTVApp.onCreate")
        // v1.12.4 — the reference's DAILY channel re-sync (BaseActivity.
        // getAllChannel: when the stored LastPlaylistDate isn't today, the
        // whole live list re-downloads at app start). This guarantees the
        // tv_archive flags + category rows are ALWAYS fresh regardless of
        // the user's upgrade path — the Catch-Up screens just read the DB,
        // exactly like the reference's Realm-backed CatchUpActivity.
        com.superz.iptvplayer.player.DailySync.launch(this)

        // ── v2.0.0 — the remote-control foundation (never blocks startup) ──
        // 1) Config gateway: the CACHED panel config applies synchronously
        //    (branding/gold, the 3 remote images, servers, update + announcement
        //    state), then a fresh fetch lands a moment later on IO. A dead
        //    gateway or a brand-new install simply stays on the built-in
        //    defaults — the panel can only ADD, never break.
        com.superz.iptvplayer.data.remote.OriaRemote.bootstrap(this, applicationScope)
        // 2) The anonymous daily install pulse (random local UUID, app version,
        //    device model/OS — no personal data) feeding the panel's user
        //    counters; silently skipped on failure.
        com.superz.iptvplayer.data.remote.Heartbeat.maybeSend(this, applicationScope)
        // 3) FCM push (topic "oria_all") — a no-op until the Firebase constants
        //    in OriaFirebase are filled (v2.0.1, after the user creates their
        //    Firebase project); never throws, never blocks.
        com.superz.iptvplayer.data.remote.OriaFirebase.init(this)
        // 4) v2.1.0 — THE PREMIUM SUBSCRIPTION STATE: loads the persisted
        //    free-trial counters ("oria_premium" prefs) and starts watching
        //    the playlists table — adding / deleting / re-logging an account
        //    (the Xtream exp_date lands through updateAccountInfo) or a sync
        //    re-evaluates the whole date-based subscription live. The gates
        //    (offline viewing, downloads, recording, the 3-account limit,
        //    ad-free) all read this state.
        com.superz.iptvplayer.ui.theme.PremiumAccess.start(this)
    }

    /** Channel logos load through the SAME shared client — so on networks
     *  where the panel domain only resolves via DoH, the logos resolve too
     *  (and inherit the VLC User-Agent spoofing + connection pooling). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .build()

    // ── v1.12.4 — the reference's media disk cache (GioTVApp.simpleCache) ──

    /**
     * The reference builds `SimpleCache(new File(getCacheDir(), "video"),
     * NoOpCacheEvictor, ExoDatabaseProvider)` in GioTVApp.onCreate and wraps
     * every PROGRESSIVE (TS) media source in a CacheDataSource over it
     * (LivePlayActivity.playVideo). On this app's stalker portal the tmp
     * stream URL is STABLE (verified live: `play/live.php?mac=…&stream=…`
     * with an empty play_token — every create_link returns the SAME URL), so
     * the cached TS prefix serves every restart/retry INSTANTLY from disk
     * while the upstream quietly reconnects — that is exactly why the
     * reference's mid-stream recovery is imperceptible (a fleeting spinner)
     * where a cache-less player shows a long black screen + spinner.
     *
     * DEVIATION (storage safety): the reference ships NoOpCacheEvictor — the
     * cache grows without bound on internal storage for as long as the app
     * is installed. We keep a generous 512 MB LRU window instead: several
     * minutes of 8 Mbps TS — far beyond the loader's 65 s buffer window, so
     * the seamless-restart behavior is identical in practice, but a device
     * can never be bricked by a day of TV. FLAG_IGNORE_CACHE_ON_ERROR (=2,
     * the reference's own flag) makes any cache hiccup fall back to network.
     */
    val exoCache: SimpleCache by lazy { buildExoCache() }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildExoCache(): SimpleCache = SimpleCache(
        File(cacheDir, "video"),
        LeastRecentlyUsedCacheEvictor(EXO_CACHE_MAX_BYTES),
        StandaloneDatabaseProvider(this)
    )

    /** True when the active network is unmetered (WiFi / Ethernet) — enables preload. */
    fun isUnmeteredNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        private lateinit var instance: IPTVApp
        fun get(): IPTVApp = instance

        /** Native VLC signature — identical to what the reference app spoofs. */
        const val VLC_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** v1.12.4 — the media disk-cache window (LRU). See [exoCache]. */
        const val EXO_CACHE_MAX_BYTES: Long = 512L * 1024 * 1024
    }
}

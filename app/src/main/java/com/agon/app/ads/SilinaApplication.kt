package com.agon.app.ads

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ════════════════════════════════════════════════════════════════════════
 *  SilinaApplication — V7.3 AdMob Async Bootstrap (ANR-SAFE)
 *  ════════════════════════════════════════════════════════════════════════
 *
 *  Custom [Application] subclass registered in AndroidManifest.xml via
 *  `android:name=".ads.SilinaApplication"`.
 *
 *  ════════════════════════════════════════════════════════════════════════
 *  ANR FIX — DEFERRED INITIALIZATION
 *  ════════════════════════════════════════════════════════════════════════
 *  The previous version called MobileAds.initialize() immediately in
 *  onCreate(). Although the call was dispatched to a background coroutine,
 *  the AdMob SDK's completion callback runs on the MAIN THREAD and performs
 *  heavy work (Play Services connection handshake, adapter scanning, native
 *  ad pool pre-loading). On devices with slow or absent Google Play Services
 *  (common on Android TV boxes in emerging markets), this congests the main
 *  thread for 5+ seconds → ANR (Application Not Responding) dialog.
 *
 *  FIX: The entire MobileAds.initialize() call is now DEFERRED by 3 seconds
 *  (INIT_DELAY_MS). By the time the SDK begins initializing, the splash
 *  screen has already rendered and navigated to the next screen. The ad
 *  system warms up silently in the background — the user never sees a freeze.
 *
 *  Additionally, the native ad pool pre-load (which builds AdLoader instances)
 *  is moved OFF the init callback's main thread onto a background dispatcher.
 *
 *  ════════════════════════════════════════════════════════════════════════
 *  STRICT FUNCTIONAL FREEZING — NETWORK CORE PROTECTION
 *  ════════════════════════════════════════════════════════════════════════
 *  This class performs ONE and ONLY ONE network-stack action: the deferred
 *  asynchronous initialization of the Google Mobile Ads SDK. It does NOT touch:
 *
 *    • The DNS-over-HTTPS (DoH) tunnel bound to Cloudflare's 1.1.1.1 servers
 *    • The Native Header Spoofing Matrix (LibVLC + Chromecast signatures)
 *    • The libffmpegJNI.so decoder engine
 *    • The OmniGuard anti-throttling proxy foreground service
 *  ════════════════════════════════════════════════════════════════════════
 */
class SilinaApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        // ═══════════════════════════════════════════════════════════════
        //  V9.8 — CRASH REPORTER (Firebase Crashlytics alternative)
        //  ═══════════════════════════════════════════════════════════════
        //  Installs a global UncaughtExceptionHandler that captures all
        //  uncaught exceptions and writes them to local crash log files.
        //  No Firebase setup required — works out of the box.
        //  The buyer can later upgrade to Firebase Crashlytics by adding
        //  the firebase-crashlytics dependency and calling
        //  FirebaseCrashlytics.getInstance().recordException() in
        //  CrashReporter's uncaughtException handler.
        // ═══════════════════════════════════════════════════════════════
        try {
            com.agon.app.util.CrashReporter.install(this)
        } catch (e: Exception) {
            Log.w(TAG, "CrashReporter install failed (non-fatal): ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════
        //  V9.4 — FORCE PLAYER REBUILD ON STARTUP
        //  ═══════════════════════════════════════════════════════════════
        //  The ExoPlayer LoadControl (including the backBuffer setting)
        //  is baked into the player at creation time. If the app was
        //  previously running with the OLD LoadControl (retainBackBuffer
        //  = true), the singleton player still has the old config.
        //
        //  rebuildPlayer() releases the old player and creates a new one
        //  with the V9.4 LoadControl (retainBackBuffer = false). This
        //  ensures the replay fix takes effect immediately on app start.
        // ═══════════════════════════════════════════════════════════════
        try {
            com.agon.app.proxy.GlobalPlaybackCoordinator.rebuildPlayer(this)
            Log.i(TAG, "V9.4 player rebuild complete — backBuffer reset enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Player rebuild failed (non-fatal): ${e.message}")
        }

        if (AdConstants.ADS_ENABLED) {
            // ═══════════════════════════════════════════════════════════════
            //  ANR FIX — DEFERRED INITIALIZATION
            //  ═══════════════════════════════════════════════════════════════
            //  Post the MobileAds.initialize() call 3 seconds into the
            //  future. By that time, SplashActivity has already rendered its
            //  animation AND navigated to LoginActivity/ProfilesActivity. The
            //  AdMob SDK warms up silently in the background — zero main-
            //  thread congestion during the critical cold-launch window.
            // ═══════════════════════════════════════════════════════════════
            mainHandler.postDelayed({
                initAdMobSafely()
            }, INIT_DELAY_MS)
        } else {
            Log.i(TAG, "ADS_ENABLED == false — AdMob system fully disabled")
        }
    }

    /**
     * Initializes the AdMob SDK on a background dispatcher. The SDK's own
     * completion callback may run on the main thread, but by the time it
     * fires (3+ seconds after launch), the splash has already navigated
     * away — any main-thread work is invisible to the user.
     */
    private fun initAdMobSafely() {
        appScope.launch {
            try {
                Log.i(TAG, "Starting deferred MobileAds.initialize()…")
                MobileAds.initialize(this@SilinaApplication) { status ->
                    Log.i(TAG, "MobileAds initialized. Adapter count: ${status.adapterStatusMap.size}")
                    AdMobManager.onInitialized(this@SilinaApplication)
                }

                // Tag the emulator as a test device so the official test
                // ad-unit IDs serve test creatives without policy risk.
                val testDeviceIds = listOf<String>(
                    "B3EEABB8EE11C2BE770B684D95219ECB"
                )
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(testDeviceIds)
                        .build()
                )
            } catch (t: Throwable) {
                // SupervisorJob guarantees this throwable does NOT crash
                // the application — the ads layer fails open (no ads).
                Log.e(TAG, "MobileAds initialization failed — ads disabled", t)
            }
        }
    }

    companion object {
        private const val TAG = "SilinaApplication"
        /** Delay before MobileAds.initialize() is called (ANR shield). */
        private const val INIT_DELAY_MS = 3000L
    }
}

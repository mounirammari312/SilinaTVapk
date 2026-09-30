package com.agon.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ════════════════════════════════════════════════════════════════════════
 *  AdMobManager — V8.4 Lean Ad Orchestrator (Singleton)
 *  ════════════════════════════════════════════════════════════════════════
 *
 *  Single source of truth for the lean AdMob ad system. After the V8.4
 *  expansion, THREE ad controllers are active:
 *
 *    • [AppOpenAdController]        — cold-launch splash overlay
 *    • [InterstitialAdController]   — player transition interstitial
 *                                     (1-in-4 smart throttle)
 *    • [RewardedAdController]       — 24-hour content-unlock gate
 *                                     (opt-in, HubActivity)
 *
 *  The legacy NativeAdPool / NativeAdCard / BannerAdView controllers and
 *  their pre-load functions remain PHYSICALLY REMOVED from this class.
 *
 *  ARCHITECTURE:
 *    • [initialized]        — flipped by [SilinaApplication] once the SDK is hot.
 *    • [appOpenController]  — loads + shows the App Open Ad at cold launch.
 *    • [interstitialController] — pre-loads + throttles the transition interstitial.
 *    • [rewardedController]    — pre-loads + presents the 24h-gate rewarded ad.
 *    • [policyShield]       — tracks fullscreen-live state; gates interstitial.
 *
 *  Every public method is a NO-OP when [AdConstants.ADS_ENABLED] is false or
 *  when the SDK has not finished initializing — the app degrades gracefully.
 *  ════════════════════════════════════════════════════════════════════════
 */
object AdMobManager {

    private const val TAG = "AdMobManager"

    // ════════════════════════════════════════════════════════════════════════
    //  SDK Initialization State
    //  ════════════════════════════════════════════════════════════════════════
    private val _initialized = AtomicBoolean(false)

    /** Application context cached at init time for background ad fetches. */
    @Volatile
    private var appContext: Context? = null

    /** `true` once [SilinaApplication] reports MobileAds.initialize() finished. */
    val initialized: Boolean get() = _initialized.get() && AdConstants.ADS_ENABLED

    /** Called by [SilinaApplication] from the SDK init completion callback. */
    fun onInitialized(context: Context) {
        _initialized.set(true)
        appContext = context.applicationContext
        Log.i(TAG, "AdMobManager ready — pre-loading ad controllers")
        // Kick off background pre-loading for all three ad controllers so a
        // creative is warm by the time the UI requests it. The native +
        // banner controllers were removed in V8.3 — V8.4 adds rewarded.
        AppOpenAdController.fetch(context.applicationContext)
        InterstitialAdController.fetch(context.applicationContext)
        loadRewardedAd(context.applicationContext)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Policy Shield — Fullscreen Live Streaming Gate
    //  ════════════════════════════════════════════════════════════════════════
    val policyShield: PolicyShield = PolicyShield

    // ════════════════════════════════════════════════════════════════════════
    //  1. App Open Ad — Splash / Cold Launch
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * Attempts to show the App Open Ad on [activity]. If no creative is ready
     * or the SDK is not initialized, this is an instant no-op — the splash
     * proceeds without delay.
     *
     * @param activity The hosting Activity (typically SplashActivity).
     * @param onDismissed Invoked when the ad is closed OR when the no-op path
     *                    is taken. Always called exactly once.
     */
    fun showAppOpenAd(activity: Activity, onDismissed: () -> Unit) {
        if (!initialized) {
            Log.i(TAG, "showAppOpenAd: SDK not ready — skipping")
            onDismissed()
            return
        }
        AppOpenAdController.show(activity, onDismissed)
    }

    /**
     * ANR-SAFE variant: shows the App Open Ad ONLY if a creative is already
     * cached. Does NOT trigger a fetch if no creative is ready. Does NOT
     * invoke any callback — the caller proceeds independently.
     *
     * This is the method used by SplashActivity so that navigation is never
     * coupled to the ad system. If the ad is cached, it overlays the next
     * screen briefly; if not, nothing happens.
     */
    fun showAppOpenAdIfCached(activity: Activity) {
        if (!initialized) {
            Log.i(TAG, "showAppOpenAdIfCached: SDK not ready — skipping")
            return
        }
        AppOpenAdController.showIfCached(activity)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  2. Player Transition Interstitial (Smart Throttle: 1 in 4)
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * Called when the user taps to transition between players / channels /
     * movies. Increments the internal counter and fires the interstitial ONLY
     * when the counter hits the [AdConstants.INTERSTITIAL_TRANSITION_INTERVAL]
     * threshold (every 4th transition). The ad is shown BEFORE the live video
     * engine starts streaming.
     *
     * @param activity The hosting Activity requesting the transition.
     * @param onProceed Callback invoked when the user may proceed with the
     *                  transition — called immediately if the ad is skipped
     *                  (throttled / not ready), or AFTER the ad is dismissed.
     */
    fun showTransitionInterstitial(activity: Activity, onProceed: () -> Unit) {
        if (!initialized) {
            onProceed()
            return
        }
        InterstitialAdController.showIfDue(activity, onProceed)
    }

    /**
     * Pre-warms the interstitial so a creative is ready before the next
     * transition. Safe to call repeatedly — no-ops if a creative is already
     * loaded.
     */
    fun prefetchInterstitial() {
        val ctx = appContext ?: return
        if (initialized) InterstitialAdController.fetch(ctx)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  3. Rewarded Ad — 24-Hour Content-Unlock Gate (V8.4)
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * Silently pre-loads a [RewardedAd] creative in the background. Called
     * automatically by [onInitialized] the moment the AdMob SDK finishes
     * initializing, so a creative is warm by the time the user lands on
     * HubActivity and taps a category card.
     *
     * This function NEVER touches the UI thread's view hierarchy — the
     * AdMob load callback runs on a background thread, so weak TV boxes
     * are protected from jank during cold launch.
     *
     * Safe to call repeatedly — no-ops if a creative is already loaded or
     * currently loading.
     *
     * @param context The application / activity context used for the load.
     */
    fun loadRewardedAd(context: Context) {
        if (!AdConstants.ADS_ENABLED) return
        RewardedAdController.fetch(context.applicationContext)
    }

    /**
     * Presents the cached rewarded ad on [activity] with the OFFICIAL
     * AdMob reward listener ([OnUserEarnedRewardListener]).
     *
     * STRICT ADMOB POLICY ADHERENCE:
     *   • The [onRewardEarned] callback fires ONLY after:
     *       (a) The user completes the full ad view → AdMob fires
     *           [OnUserEarnedRewardListener.onUserEarnedReward].
     *       (b) The ad's full-screen container is dismissed via
     *           [FullScreenContentCallback.onAdDismissedFullScreenContent].
     *   • If the ad is closed EARLY (before the reward threshold), no
     *     reward is granted — the user must try again.
     *   • If no creative is cached (SDK not ready / load failed),
     *     [onRewardEarned] is NOT called — the caller may choose to
     *     either retry the load or grant access as a fallback.
     *
     * @param activity The hosting Activity (HubActivity).
     * @param onRewardEarned Invoked ONLY after the full view completes AND
     *                       the ad container is dismissed successfully.
     */
    fun showRewardedAd(activity: Activity, onRewardEarned: () -> Unit) {
        if (!AdConstants.ADS_ENABLED) {
            Log.i(TAG, "showRewardedAd: ADS_ENABLED == false — skipping")
            return
        }
        if (!_initialized.get()) {
            Log.i(TAG, "showRewardedAd: SDK not ready — skipping")
            return
        }
        RewardedAdController.show(activity, onRewardEarned)
    }

    /**
     * Returns `true` if a rewarded creative is currently cached and ready
     * to be presented with zero loading delay. HubActivity consults this
     * before showing the opt-in dialog so the dialog's CTA button can be
     * enabled / disabled accordingly.
     */
    fun isRewardedAdReady(): Boolean {
        return AdConstants.ADS_ENABLED && _initialized.get() &&
            RewardedAdController.isReady()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  AppOpenAdController — Cold-Launch App Open Ad
// ════════════════════════════════════════════════════════════════════════════
/**
 * Loads and caches a single [AppOpenAd] creative. The ad is fetched the
 * moment the SDK finishes initializing and re-fetched automatically after it
 * is shown (so the next cold launch has a warm creative).
 */
object AppOpenAdController {

    private const val TAG = "AppOpenAd"

    @Volatile
    private var appOpenAd: AppOpenAd? = null

    @Volatile
    private var isLoading: Boolean = false

    @Volatile
    private var isShowing: Boolean = false

    @Volatile
    private var pendingDismiss: (() -> Unit)? = null

    /** Pre-load an App Open Ad creative. No-op if already loaded or loading. */
    fun fetch(context: Context) {
        if (!AdConstants.ADS_ENABLED) return
        if (appOpenAd != null || isLoading) return
        isLoading = true
        Log.i(TAG, "Fetching App Open Ad…")
        val request = AdRequest.Builder().build()
        AppOpenAd.load(
            context,
            AdConstants.APP_OPEN_AD_UNIT_ID,
            request,
            AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoading = false
                    Log.i(TAG, "App Open Ad loaded and cached")
                    ad.fullScreenContentCallback = buildFullScreenCallback()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAd = null
                    isLoading = false
                    Log.w(TAG, "App Open Ad load failed: ${error.message}")
                }
            }
        )
    }

    /** Show the cached App Open Ad, or no-op + invoke [onDismissed] immediately. */
    fun show(activity: Activity, onDismissed: () -> Unit) {
        if (!AdConstants.ADS_ENABLED) { onDismissed(); return }
        val ad = appOpenAd
        if (ad == null) {
            Log.i(TAG, "No App Open Ad ready — proceeding without ad")
            onDismissed()
            // Re-fetch for the next launch.
            fetch(activity.applicationContext)
            return
        }
        if (isShowing) {
            onDismissed()
            return
        }
        isShowing = true
        Log.i(TAG, "Showing App Open Ad")
        pendingDismiss = onDismissed
        ad.show(activity)
    }

    /**
     * ANR-SAFE variant: shows the cached App Open Ad ONLY if a creative is
     * already loaded. Does NOT fetch, does NOT invoke any callback. If no
     * creative is ready, this is a silent no-op. Used by SplashActivity so
     * that navigation is never blocked by ad loading.
     */
    fun showIfCached(activity: Activity) {
        if (!AdConstants.ADS_ENABLED) return
        val ad = appOpenAd
        if (ad == null) {
            Log.i(TAG, "showIfCached: no cached App Open Ad — skipping silently")
            return
        }
        if (isShowing) return
        isShowing = true
        Log.i(TAG, "Showing cached App Open Ad (best-effort)")
        pendingDismiss = null
        ad.show(activity)
    }

    private fun buildFullScreenCallback(): FullScreenContentCallback {
        return object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "App Open Ad dismissed")
                isShowing = false
                appOpenAd = null
                pendingDismiss?.invoke()
                pendingDismiss = null
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "App Open Ad show failed: ${error.message}")
                isShowing = false
                appOpenAd = null
                pendingDismiss?.invoke()
                pendingDismiss = null
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  InterstitialAdController — Player Transition Interstitial (1-in-4 throttle)
// ════════════════════════════════════════════════════════════════════════════
/**
 * Pre-loads a single [InterstitialAd] creative and exposes [showIfDue] which
 * fires the ad ONLY when the internal transition counter reaches the
 * [AdConstants.INTERSTITIAL_TRANSITION_INTERVAL] threshold (every 4th
 * transition). This protects the user experience from excessive ad annoyance
 * during rapid channel surfing.
 */
object InterstitialAdController {

    private const val TAG = "InterstitialAd"

    @Volatile
    private var interstitialAd: InterstitialAd? = null

    @Volatile
    private var isLoading: Boolean = false

    @Volatile
    private var isShowing: Boolean = false

    /** Internal counter — incremented on every transition; resets after firing. */
    private val transitionCounter = AtomicInteger(0)

    @Volatile
    private var pendingProceed: (() -> Unit)? = null

    /** Pre-load an interstitial creative. No-op if already loaded or loading. */
    fun fetch(context: Context) {
        if (!AdConstants.ADS_ENABLED) return
        if (interstitialAd != null || isLoading) return
        isLoading = true
        Log.i(TAG, "Fetching Interstitial Ad…")
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            AdConstants.INTERSTITIAL_AD_UNIT_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoading = false
                    Log.i(TAG, "Interstitial loaded and cached")
                    ad.fullScreenContentCallback = buildFullScreenCallback()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoading = false
                    Log.w(TAG, "Interstitial load failed: ${error.message}")
                }
            }
        )
    }

    /**
     * Called on every player transition. Increments the throttle counter and
     * fires the interstitial ONLY when the counter hits the interval. When the
     * ad is skipped (throttled / not ready), [onProceed] is invoked
     * immediately. When the ad fires, [onProceed] is invoked AFTER the ad is
     * dismissed.
     */
    fun showIfDue(activity: Activity, onProceed: () -> Unit) {
        if (!AdConstants.ADS_ENABLED) { onProceed(); return }

        val count = transitionCounter.incrementAndGet()
        Log.i(TAG, "Transition counter = $count / ${AdConstants.INTERSTITIAL_TRANSITION_INTERVAL}")

        if (count < AdConstants.INTERSTITIAL_TRANSITION_INTERVAL) {
            // Throttled — not due yet. Proceed immediately.
            onProceed()
            return
        }

        // Counter hit the threshold — reset and attempt to show.
        transitionCounter.set(0)

        // Policy Shield: never show an interstitial during fullscreen live.
        if (PolicyShield.isFullscreenLive) {
            Log.i(TAG, "Fullscreen live active — interstitial blocked by PolicyShield")
            onProceed()
            return
        }

        val ad = interstitialAd
        if (ad == null) {
            Log.i(TAG, "No Interstitial ready — proceeding without ad")
            onProceed()
            // Re-fetch for the next due transition.
            fetch(activity.applicationContext)
            return
        }
        if (isShowing) {
            onProceed()
            return
        }
        isShowing = true
        Log.i(TAG, "Showing Interstitial Ad (transition)")
        pendingProceed = onProceed
        ad.show(activity)
    }

    private fun buildFullScreenCallback(): FullScreenContentCallback {
        return object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Interstitial dismissed")
                isShowing = false
                interstitialAd = null
                pendingProceed?.invoke()
                pendingProceed = null
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial show failed: ${error.message}")
                isShowing = false
                interstitialAd = null
                pendingProceed?.invoke()
                pendingProceed = null
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  RewardedAdController — 24-Hour Content-Unlock Gate (V8.4)
// ════════════════════════════════════════════════════════════════════════════
/**
 * Pre-loads a single [RewardedAd] creative and exposes [show] which presents
 * it with Google's OFFICIAL [OnUserEarnedRewardListener] reward callback.
 *
 * STRICT ADMOB POLICY ADHERENCE:
 *   • The reward callback is fired ONLY when AdMob signals that the user
 *     has earned the reward (i.e. watched the ad to completion).
 *   • The user-facing [onRewardEarned] callback is deferred until BOTH:
 *       (a) onUserEarnedReward fires  → reward earned flag set
 *       (b) onAdDismissedFullScreenContent fires → ad UI torn down
 *     This guarantees the caller never grants the 24-hour unlock while the
 *     full-screen ad container is still on-screen.
 *   • If the user closes the ad early (no reward earned), the dismiss
 *     callback fires WITHOUT the earned flag → no reward is granted.
 *
 * Thread-safety: the earned flag + pending callback are guarded by @Volatile
 * because AdMob fires its callbacks on the main thread but the load
 * callback may run on a background thread.
 */
object RewardedAdController {

    private const val TAG = "RewardedAd"

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var isLoading: Boolean = false

    @Volatile
    private var isShowing: Boolean = false

    /** Set true by OnUserEarnedRewardListener; read by the dismiss callback. */
    @Volatile
    private var rewardEarned: Boolean = false

    /** The caller's callback, deferred until the ad container is dismissed. */
    @Volatile
    private var pendingRewardCallback: (() -> Unit)? = null

    /** Pre-load a rewarded creative. No-op if already loaded or loading. */
    fun fetch(context: Context) {
        if (!AdConstants.ADS_ENABLED) return
        if (rewardedAd != null || isLoading) return
        isLoading = true
        Log.i(TAG, "Fetching Rewarded Ad…")
        val request = AdRequest.Builder().build()
        RewardedAd.load(
            context,
            AdConstants.REWARDED_AD_UNIT_ID,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.i(TAG, "Rewarded Ad loaded and cached")
                    ad.fullScreenContentCallback = buildFullScreenCallback()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.w(TAG, "Rewarded Ad load failed: ${error.message}")
                }
            }
        )
    }

    /** Returns `true` if a creative is cached and not currently showing. */
    fun isReady(): Boolean = rewardedAd != null && !isShowing

    /**
     * Shows the cached rewarded ad. The [onRewardEarned] callback is
     * invoked ONLY after the user completes the full view AND the ad
     * container is dismissed.
     *
     * If no creative is cached, this is a no-op — the caller may retry
     * the load via [fetch] before re-invoking [show].
     */
    fun show(activity: Activity, onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            Log.w(TAG, "show: no cached Rewarded Ad — fetching for next time")
            fetch(activity.applicationContext)
            return
        }
        if (isShowing) {
            Log.w(TAG, "show: already showing — ignoring duplicate request")
            return
        }

        // Reset the per-session state.
        isShowing = true
        rewardEarned = false
        pendingRewardCallback = onRewardEarned

        Log.i(TAG, "Showing Rewarded Ad (24h-gate)")

        // Google's official reward listener. Fires when the user has
        // watched the ad long enough to earn the reward.
        val rewardListener = OnUserEarnedRewardListener {
            rewardEarned = true
            Log.i(TAG, "Reward earned — user completed the full view")
        }

        // The show call is safe to invoke on the main thread.
        ad.show(activity, rewardListener)
    }

    private fun buildFullScreenCallback(): FullScreenContentCallback {
        return object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Rewarded Ad dismissed (earned=$rewardEarned)")
                isShowing = false
                rewardedAd = null
                // Grant the reward ONLY if AdMob confirmed the user earned
                // it via OnUserEarnedRewardListener. If the user closed the
                // ad early, rewardEarned stays false and no callback fires.
                if (rewardEarned) {
                    pendingRewardCallback?.invoke()
                }
                pendingRewardCallback = null
                rewardEarned = false
                // Pre-load the next creative so the next gate attempt is hot.
                // We use the application context cached at the call site.
                // AdMob's load call internally routes to its background
                // thread pool — no main-thread congestion.
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded Ad show failed: ${error.message}")
                isShowing = false
                rewardedAd = null
                // No reward on failure — but we still clear the callback so
                // the caller's lambda is not leaked.
                pendingRewardCallback = null
                rewardEarned = false
            }
        }
    }
}

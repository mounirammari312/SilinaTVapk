package com.agon.app.ads

/**
 * ════════════════════════════════════════════════════════════════════════
 *  AdConstants — V8.4 Lean AdMob Configuration (Interstitial + App Open + Rewarded)
 *  ════════════════════════════════════════════════════════════════════════
 *
 *  Central registry of every AdMob ad-unit identifier used by the Silina TV
 *  application. After the V8.4 expansion, THREE ad formats are active:
 *
 *    1. App Open Ad          — cold-launch splash overlay
 *    2. Interstitial Ad      — player transition (1-in-4 throttle)
 *    3. Rewarded Ad          — 24h content-unlock gate (HubActivity opt-in dialog)
 *
 *  The legacy Native Advanced + Anchored Adaptive Banner formats have been
 *  PHYSICALLY REMOVED from the project (NativeAdCard.kt / BannerAdView.kt /
 *  NativeAdPool.kt deleted) along with their ad-unit identifiers, intervals,
 *  and every loading/storage function. This keeps the APK small, the source
 *  clean, and the AdMob account safe from policy issues on Android TV.
 *
 *  All values are declared as `const val` so the Kotlin compiler inlines them
 *  at every call-site — zero runtime lookup overhead on the hot path.
 *
 *  ════════════════════════════════════════════════════════════════════════
 *  ⚠️  POLICY SHIELD — IDENTIFIER SEPARATION  ⚠️
 *  ════════════════════════════════════════════════════════════════════════
 *  The values below are Google's OFFICIAL TEST ad-unit IDs. They are
 *  guaranteed to serve test creatives 100% of the time and will NEVER
 *  generate real revenue or trigger policy violations.
 *
 *  Before shipping the APK to production (or selling the source), the buyer
 *  MUST replace every test ID with their own real AdMob ad-unit IDs created
 *  in their AdMob console (https://apps.admob.com). Search for the marker:
 *
 *      // TODO: Replace with Real AdMob IDs
 *
 *  and substitute the string literal. No other code change is required —
 *  every ad surface in the app reads from THIS file.
 *  ════════════════════════════════════════════════════════════════════════
 */
object AdConstants {

    // ════════════════════════════════════════════════════════════════════════
    //  AdMob Application ID
    //  ════════════════════════════════════════════════════════════════════════
    //  Registered in AndroidManifest.xml as
    //  <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" />
    //
    //  This is the GLOBAL test application ID. It is shared by both ad
    //  formats and MUST match the value declared in the manifest meta-data
    //  tag, otherwise MobileAds.initialize() throws a runtime crash.
    //
    //  TODO: Replace with Real AdMob IDs — substitute with your production
    //  AdMob application ID (format: ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX)
    //  in both this file AND AndroidManifest.xml.
    // ════════════════════════════════════════════════════════════════════════
    const val ADMOB_APP_ID: String = "ca-app-pub-3940256099942544~3347511713"

    // ════════════════════════════════════════════════════════════════════════
    //  1. App Open Ad — Splash Screen / Cold Launch
    //  ════════════════════════════════════════════════════════════════════════
    //  Shown automatically when the application cold-launches, layered on top
    //  of SplashActivity during the 3-second loading window. Loaded
    //  asynchronously by [AppOpenAdController] and presented the instant the
    //  ad creative is ready. If the ad is not ready by the time the splash
    //  timer expires, the user proceeds to the next screen with no delay —
    //  the app NEVER blocks on an ad.
    //
    //  TODO: Replace with Real AdMob IDs — substitute with your production
    //  App Open ad-unit ID from the AdMob console.
    // ════════════════════════════════════════════════════════════════════════
    const val APP_OPEN_AD_UNIT_ID: String = "ca-app-pub-3940256099942544/9257395921"

    // ════════════════════════════════════════════════════════════════════════
    //  2. Player Transition Interstitial Ad
    //  ════════════════════════════════════════════════════════════════════════
    //  Pre-loaded in the background by [InterstitialAdController] and injected
    //  the instant the user taps to transition between players / channels /
    //  movies — BEFORE the live video engine starts streaming.
    //
    //  SMART THROTTLE: subject to an internal counter so it does NOT fire on
    //  every transition. It appears at a rate of ONCE EVERY 4 TRANSITIONS,
    //  protecting the user experience from excessive annoyance. The counter
    //  is managed by [InterstitialAdController.transitionCounter].
    //
    //  TODO: Replace with Real AdMob IDs — substitute with your production
    //  Interstitial ad-unit ID from the AdMob console.
    // ════════════════════════════════════════════════════════════════════════
    const val INTERSTITIAL_AD_UNIT_ID: String = "ca-app-pub-3940256099942544/1033173712"

    // ════════════════════════════════════════════════════════════════════════
    //  3. Rewarded Ad — 24-Hour Content-Unlock Gate
    //  ════════════════════════════════════════════════════════════════════════
    //  Pre-loaded silently by [RewardedAdController] the moment the SDK
    //  finishes initializing, so a creative is warm by the time the user
    //  lands on HubActivity. Presented via an EXPLICIT opt-in dialog when
    //  the user taps to enter any content category (Live / Movies / Series)
    //  AND the 24-hour reward window has expired.
    //
    //  ADHERENCE TO ADMOB POLICY:
    //    • The reward is OPT-IN — the dialog requires explicit D-Pad tap on
    //      a labeled "شاهد إعلان فيديو…" button before the ad is shown.
    //    • The reward callback fires ONLY after the user completes the
    //      full view (OnUserEarnedRewardListener) AND the ad is dismissed.
    //    • The 24-hour gate is enforced LOCALLY via SharedPreferences — no
    //      server round-trip, no false reward signals.
    //
    //  TODO: Replace with Real AdMob IDs — substitute with your production
    //  Rewarded ad-unit ID from the AdMob console.
    // ════════════════════════════════════════════════════════════════════════
    const val REWARDED_AD_UNIT_ID: String = "ca-app-pub-3940256099942544/5224354917"

    // ════════════════════════════════════════════════════════════════════════
    //  Rewarded Gate — 24-Hour Reward Window
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * Duration (in milliseconds) for which the rewarded-ad grant remains
     * valid after a successful view. Set to 24 hours per the V8.4 directive.
     * Stored as a timestamp in SharedPreferences key
     * [REWARD_TIMESTAMP_PREF_KEY].
     */
    const val REWARDED_GRANT_DURATION_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * SharedPreferences file name used by HubActivity to persist the last
     * successful rewarded-ad timestamp.
     */
    const val REWARD_PREFS_FILE: String = "silina_rewarded_gate"

    /**
     * SharedPreferences key holding the epoch-millis timestamp of the last
     * successful rewarded-ad view. A value of 0 (or absence) means the
     * user has never earned the reward and must watch the ad.
     */
    const val REWARD_TIMESTAMP_PREF_KEY: String = "last_reward_ts"

    // ════════════════════════════════════════════════════════════════════════
    //  Throttle & Injection Parameters
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * The interstitial ad fires ONCE every [INTERSTITIAL_TRANSITION_INTERVAL]
     * player transitions. Set to 4 per the V7.3 / V8.3 directive — protects
     * the user experience from excessive ad annoyance during rapid channel
     * surfing and enforces the rotation rate of "once every 4 transitions".
     */
    const val INTERSTITIAL_TRANSITION_INTERVAL: Int = 4

    /**
     * Master kill-switch for the entire ad system. When `false`, every ad
     * surface short-circuits to a no-op and renders nothing. Useful for
     * building an ad-free premium variant of the APK.
     */
    const val ADS_ENABLED: Boolean = true
}

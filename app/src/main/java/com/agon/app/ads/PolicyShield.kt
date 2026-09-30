package com.agon.app.ads

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ════════════════════════════════════════════════════════════════════════
 *  PolicyShield — V8.3 Ad Policy Firewall (Interstitial-Only)
 *  ════════════════════════════════════════════════════════════════════════
 *
 *  Enforces the hard policy rule mandated by the V7.3 / V8.3 directive:
 *
 *    RULE 1 — "No interstitial during fullscreen live streaming"
 *      The moment the user enters full-screen mode and the live stream
 *      begins playing, the transition interstitial is BLOCKED by
 *      [InterstitialAdController.showIfDue] consulting
 *      [PolicyShield.isFullscreenLive]. This prevents a sudden full-screen
 *      ad from firing over an active live stream — which would be a
 *      policy violation that gets the AdMob account banned.
 *
 *  ════════════════════════════════════════════════════════════════════════
 *  USAGE
 *  ════════════════════════════════════════════════════════════════════════
 *    // PlayerActivity — when expanding to fullscreen live:
 *    PolicyShield.enterFullscreenLive()
 *
 *    // PlayerActivity — when returning to split-screen / exiting:
 *    PolicyShield.exitFullscreenLive()
 *
 *    // InterstitialAdController.showIfDue — gate check:
 *    if (PolicyShield.isFullscreenLive) { /* block ad */ }
 * ════════════════════════════════════════════════════════════════════════
 */
object PolicyShield {

    private const val TAG = "PolicyShield"

    // ════════════════════════════════════════════════════════════════════════
    //  Fullscreen-Live State
    //  ════════════════════════════════════════════════════════════════════════
    private val _isFullscreenLive = MutableStateFlow(false)

    /** `true` while the user is in fullscreen live-streaming mode. */
    val isFullscreenLive: Boolean get() = _isFullscreenLive.value

    /** Called by PlayerActivity when the user expands to fullscreen live. */
    fun enterFullscreenLive() {
        if (_isFullscreenLive.value) return
        Log.i(TAG, "RULE 1 → fullscreen live ENTERED — interstitial blocked")
        _isFullscreenLive.value = true
    }

    /** Called by PlayerActivity when the user leaves fullscreen live. */
    fun exitFullscreenLive() {
        if (!_isFullscreenLive.value) return
        Log.i(TAG, "fullscreen live EXITED — interstitial re-enabled")
        _isFullscreenLive.value = false
    }
}


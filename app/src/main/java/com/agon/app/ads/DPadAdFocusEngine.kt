package com.agon.app.ads

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.util.Log

/**
 * ════════════════════════════════════════════════════════════════════════
 *  DPadAdFocusEngine — V7.3 TV Remote Control Focus Engine
 *  ════════════════════════════════════════════════════════════════════════
 *
 *  Implements the D-Pad focus rule that prevents the user from being
 *  trapped inside full-screen ads when the app runs on Android TV:
 *
 *    RULE — KeyEvent Interception
 *      [dispatchAdKeyEvent] is wired into the host Activity's
 *      dispatchKeyEvent() override. It traps the remote's CENTER (OK) button
 *      and the BACK button, performing an immediate ad dismissal so the user
 *      can exit the ad with zero lag.
 *
 *  ════════════════════════════════════════════════════════════════════════
 *  USAGE (host Activity):
 *
 *    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
 *        if (DPadAdFocusEngine.dispatchAdKeyEvent(event)) return true
 *        return super.dispatchKeyEvent(event)
 *    }
 *  ════════════════════════════════════════════════════════════════════════
 */
object DPadAdFocusEngine {

    private const val TAG = "DPadAdFocus"

    // ════════════════════════════════════════════════════════════════════════
    //  RULE — KeyEvent Interception (CENTER + BACK → dismiss ad)
    //  ════════════════════════════════════════════════════════════════════════
    /**
     * Intercepts the remote's CENTER (OK) button and the BACK button while a
     * full-screen ad is showing, performing an immediate dismissal.
     *
     * Wire this into the host Activity's [Activity.dispatchKeyEvent]:
     *
     *     override fun dispatchKeyEvent(event: KeyEvent): Boolean {
     *         if (DPadAdFocusEngine.dispatchAdKeyEvent(event)) return true
     *         return super.dispatchKeyEvent(event)
     *     }
     *
     * @return `true` if the event was consumed (ad was dismissed / focused),
     *         `false` to let the Activity handle it normally.
     */
    fun dispatchAdKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            // KEYCODE_DPAD_CENTER (remote OK button) — if a close button is
            // focused, perform its click to dismiss the ad immediately.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleCenterPress()
            }
            // KEYCODE_BACK — dismiss the ad immediately.
            KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
            }
            else -> false
        }
    }

    /**
     * Handles the remote's CENTER (OK) press. If the currently-focused view
     * looks like a close button, its click is performed programmatically,
     * dismissing the ad with zero lag.
     */
    private fun handleCenterPress(): Boolean {
        val focused = currentFocusedView ?: return false
        if (isCloseButton(focused)) {
            Log.i(TAG, "D-Pad CENTER on close button → dismissing ad")
            focused.performClick()
            return true
        }
        return false
    }

    /**
     * Handles the remote's BACK press. Finds the close button in the current
     * view tree and clicks it, dismissing the ad immediately.
     */
    private fun handleBackPress(): Boolean {
        val root = currentRootView ?: return false
        val closeBtn = findCloseButton(root)
        if (closeBtn != null) {
            Log.i(TAG, "D-Pad BACK → clicking ad close button")
            closeBtn.performClick()
            return true
        }
        // No close button found — try to simulate a BACK press so the SDK's
        // own back handler dismisses the ad.
        return false
    }

    // ════════════════════════════════════════════════════════════════════════
    //  View-tree helpers
    //  ════════════════════════════════════════════════════════════════════════
    @Volatile
    private var currentActivity: Activity? = null

    /** Registers the foreground Activity so the engine can traverse its view tree. */
    fun bindActivity(activity: Activity) {
        currentActivity = activity
    }

    /** Unregisters the Activity. */
    fun unbindActivity(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    private val currentRootView: View?
        get() = currentActivity?.window?.decorView

    private val currentFocusedView: View?
        get() = currentActivity?.currentFocus

    private fun findCloseButton(root: View): View? {
        if (isCloseButton(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findCloseButton(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isCloseButton(view: View): Boolean {
        if (view !is Button && view !is ImageButton && view !is TextView) return false
        val text = getViewText(view) + (view.contentDescription?.toString() ?: "")
        val lower = text.lowercase()
        return lower.contains("close") || lower.contains("skip") ||
            lower.contains("dismiss") || lower.contains("×") ||
            lower.contains("✕") || lower.trim() == "x"
    }

    /** Extracts the text label from a Button/TextView; returns "" for ImageButton. */
    private fun getViewText(view: View): String {
        return when (view) {
            is TextView -> view.text?.toString() ?: ""
            is Button -> view.text?.toString() ?: ""
            else -> ""
        }
    }
}


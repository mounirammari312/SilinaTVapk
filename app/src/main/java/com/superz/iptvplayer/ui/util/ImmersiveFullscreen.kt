package com.superz.iptvplayer.ui.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * Applies true edge-to-edge immersive sticky fullscreen to an Activity.
 * (Ported verbatim from the reference app — proven on the target device.)
 *
 * - Hides the system status bar and navigation bar (in landscape the nav
 *   bar sits on the SIDE of the screen and used to overlap UI controls).
 * - Lays the app out behind where the bars used to be (true edge-to-edge).
 * - Bars re-appear transiently on swipe, then hide again (IMMERSIVE_STICKY).
 *
 * Call from the Activity's onCreate, BEFORE setContent { ... }, and re-apply
 * on window focus gains (dialogs/IME can bring the bars back on some ROMs).
 */
fun Activity.applyImmersiveFullscreen() {
    // Force the window to extend behind system bars and into display cutouts.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

package com.agon.app.ui.screens

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.agon.app.feed.SilinaFeed
import com.agon.app.ui.util.applyImmersiveFullscreen

/**
 * FeedActivity — Hosts the Silina Feed in PORTRAIT orientation.
 *
 * The rest of the app is locked to landscape, but the feed needs to be
 * 9:16 portrait to deliver the TikTok-style experience. This activity
 * overrides the orientation to portrait on entry and restores it on exit.
 *
 * Immersive fullscreen is applied so the feed fills the entire screen
 * edge-to-edge (no status bar, no nav bar) — exactly like TikTok.
 */
class FeedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force PORTRAIT for the TikTok-style feed experience.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyImmersiveFullscreen()

        setContent {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                color = Color.Black
            ) {
                SilinaFeed(
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The rest of the app is landscape — but since each Activity
        // sets its own orientation in onCreate, we don't need to
        // restore anything globally.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Don't enter PiP from the feed — just let it go to background.
    }
}

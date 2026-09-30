package com.agon.app.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * ExternalPlayerHelper — V9.8
 *
 * Launches an external video player (VLC, MX Player, or any app that
 * can handle video/\* intents) to play the current stream URL.
 *
 * Supported players:
 *   - VLC for Android (org.videolan.vlc)
 *   - MX Player (com.mxtech.videoplayer.ad / .pro)
 *   - BS Player (com.bs.player)
 *   - Any player that responds to ACTION_VIEW with video/\* MIME
 *
 * If no external player is installed, the user is redirected to the
 * Play Store to install VLC (the most universally compatible option).
 */
object ExternalPlayerHelper {

    private const val TAG = "ExternalPlayer"

    /** Known external player package names for explicit launch. */
    private val KNOWN_PLAYERS = listOf(
        "org.videolan.vlc" to "VLC",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "com.bs.player" to "BS Player",
        "com.duokan.video" to "Duokan",
        "com.brother.printer" to null, // placeholder to skip
        "com.google.android.video" to null
    )

    /**
     * Returns a list of installed external video players (name + package).
     * Empty if none are installed.
     */
    fun getInstalledPlayers(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val result = mutableListOf<Pair<String, String>>()
        for ((pkg, name) in KNOWN_PLAYERS) {
            if (name == null) continue
            try {
                pm.getPackageInfo(pkg, 0)
                result.add(pkg to name)
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return result
    }

    /**
     * Launches the given stream URL in an external player.
     *
     * @param context   Activity or Application context
     * @param streamUrl The URL to play (http(s)://, rtsp://, etc.)
     * @param title     Optional title for the media
     * @return true if launch succeeded, false if no player is installed
     */
    fun launchExternal(
        context: Context,
        streamUrl: String,
        title: String = "SilinaTV Stream"
    ): Boolean {
        if (streamUrl.isBlank()) return false

        // Try VLC first (best IPTV compatibility)
        if (tryVlc(context, streamUrl, title)) return true

        // Try MX Player
        if (tryMxPlayer(context, streamUrl, title)) return true

        // Fallback: generic ACTION_VIEW with video/\* MIME
        if (tryGeneric(context, streamUrl, title)) return true

        // No player installed — open Play Store for VLC
        Log.w(TAG, "No external player installed — opening Play Store")
        try {
            val playStore = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=org.videolan.vlc"))
            playStore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(playStore)
        } catch (_: Throwable) {
            // Play Store not installed — open browser
            try {
                val browser = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=org.videolan.vlc"))
                browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browser)
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * Try to launch VLC with the stream URL.
     * VLC accepts a direct URL via its custom intent.
     */
    private fun tryVlc(context: Context, url: String, title: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
                setDataAndType(Uri.parse(url), "video/*")
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched VLC for $url")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Try to launch MX Player with the stream URL.
     */
    private fun tryMxPlayer(context: Context, url: String, title: String): Boolean {
        // Try free version first, then pro
        val mxPackages = listOf(
            "com.mxtech.videoplayer.ad" to "com.mxtech.videoplayer.ActivityScreen",
            "com.mxtech.videoplayer.pro" to "com.mxtech.videoplayer.ActivityScreen"
        )
        for ((pkg, activity) in mxPackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(pkg, activity)
                    setDataAndType(Uri.parse(url), "video/*")
                    putExtra("title", title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Launched MX Player ($pkg) for $url")
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * Generic ACTION_VIEW fallback — lets the user pick any compatible app.
     */
    private fun tryGeneric(context: Context, url: String, title: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Verify there's at least one app that can handle this
            val pm = context.packageManager
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(Intent.createChooser(intent, "Play with...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.i(TAG, "Launched generic player chooser for $url")
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}

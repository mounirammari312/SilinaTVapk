package com.agon.app.cast

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession

/**
 * CastHelper — V9.8
 *
 * Simplifies Google Cast (Chromecast) integration:
 *   - Shows the cast dialog (device picker)
 *   - Loads a stream URL on the selected cast device
 *   - Tracks the cast session state
 *
 * Usage from PlayerActivity:
 *   CastHelper.showCastDialog(activity)
 *   CastHelper.loadStream(activity, streamUrl, streamName)
 */
object CastHelper {

    private const val TAG = "CastHelper"

    /**
     * Shows the Cast device picker dialog.
     */
    fun showCastDialog(activity: Activity) {
        try {
            val castContext = CastContext.getSharedInstance(activity)
            val sessionManager = castContext.sessionManager
            if (sessionManager.currentCastSession != null) {
                sessionManager.endCurrentSession(true)
                Log.i(TAG, "Cast session ended")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cast dialog failed: ${e.message}")
        }
    }

    /**
     * Loads the given stream URL on the currently connected Cast device.
     * If no device is connected, returns false.
     *
     * @return true if the stream was loaded, false if no session is active
     */
    fun loadStream(
        context: Context,
        streamUrl: String,
        streamName: String,
        streamLogo: String? = null
    ): Boolean {
        if (streamUrl.isBlank()) return false
        try {
            val castContext = CastContext.getSharedInstance(context)
            val session = castContext.sessionManager.currentCastSession
            if (session == null) {
                Log.w(TAG, "No cast session — call showCastDialog() first")
                return false
            }

            val remoteMediaClient = session.remoteMediaClient ?: run {
                Log.w(TAG, "RemoteMediaClient is null")
                return false
            }

            // Build media metadata
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
            metadata.putString(MediaMetadata.KEY_TITLE, streamName)

            // Determine MIME type from URL extension
            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegurl"
                streamUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                streamUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                streamUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                streamUrl.contains(".flv", ignoreCase = true) -> "video/x-flv"
                streamUrl.contains(".webm", ignoreCase = true) -> "video/webm"
                else -> "application/x-mpegurl"  // Default to HLS for IPTV
            }

            val mediaInfo = MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(metadata)
                .build()

            // Load the media
            val loadData = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .build()

            remoteMediaClient.load(loadData)
            Log.i(TAG, "Cast load initiated: $streamName")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Cast loadStream failed: ${e.message}")
            return false
        }
    }

    /**
     * Returns true if a Cast session is currently active.
     */
    fun isCasting(context: Context): Boolean {
        return try {
            val castContext = CastContext.getSharedInstance(context)
            castContext.sessionManager.currentCastSession != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Stops casting and disconnects from the Cast device.
     */
    fun stopCasting(context: Context) {
        try {
            val castContext = CastContext.getSharedInstance(context)
            castContext.sessionManager.endCurrentSession(true)
            Log.i(TAG, "Stopped casting")
        } catch (e: Exception) {
            Log.w(TAG, "stopCasting failed: ${e.message}")
        }
    }
}

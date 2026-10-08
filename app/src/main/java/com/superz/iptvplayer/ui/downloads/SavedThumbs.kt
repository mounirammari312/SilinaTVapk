package com.superz.iptvplayer.ui.downloads

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * v1.19.13 — real THUMBNAILS for the Saved Videos library cards.
 *
 * The library's rows were plain text ("مجرد كأسماء بمظهر بدائي" — the user's
 * words); the professional Continue-Watching look needs artwork. Every save
 * IS a local video file, so a frame is extracted once with
 * MediaMetadataRetriever (~10% into the film — past any black studio intro),
 * downscaled to card width, and parked as a JPEG in the app's PRIVATE cache
 * dir (never next to the saves themselves — the library folder stays exactly
 * what the recorder wrote).
 *
 * Contract:
 *  • [thumbnailFor] is idempotent + offline: a cached hit is returned as-is;
 *    a miss is generated once; a failure is cached as "no art" via a
 *    zero-length marker so a hopeless file (truncated moov, live .part) is
 *    not retried on every 2.5 s rescan.
 *  • the cache key folds in the file SIZE bucket (16 MB granularity): a
 *    finalized/replaced save naturally refreshes its art.
 *  • DOWNLOADING (.part) entries are skipped by the caller — their frame
 *    would churn with every growth.
 */
object SavedThumbs {

    private const val TAG = "SavedThumbs"
    private const val DIR = "saved_thumbs"
    private const val MAX_WIDTH_PX = 512
    private const val SIZE_BUCKET_BYTES = 16L * 1024 * 1024
    private const val JPEG_QUALITY = 82

    /** The art's cache file for a library entry, or null when the file has
     *  no extractable art (marker-hit or extraction failure). */
    fun thumbnailFor(context: Context, video: SavedVideo): String? {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val src = File(video.path)
        if (!src.isFile) return null

        val key = cacheKey(video)
        val img = File(dir, "$key.jpg")
        if (img.isFile) return img.absolutePath

        val marker = File(dir, "$key.noart")
        if (marker.isFile) return null

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.path)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                // ~10% in — past the black intro, before spoiler territory
                val atUs = (durationMs * 100).coerceAtLeast(0L)  // 10% in µs
                val frame = retriever.getFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.frameAt(0)
                if (frame == null) {
                    marker.createNewFile()
                    null
                } else {
                    val scaled = scale(frame)
                    img.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                    img.absolutePath
                }
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "no art for ${video.fileName}: ${e.message}")
            runCatching { marker.createNewFile() }
            null
        }
    }

    /** The FIRST frame (OPTION_CLOSEST_SYNC with time 0 can still fail on
     *  truncated files; the plain getter is the last resort). */
    private fun MediaMetadataRetriever.frameAt(ignored: Long): Bitmap? = try {
        getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: getFrameAtTime(0L, MediaMetadataRetriever.OPTION_NEXT_SYNC)
    } catch (_: Throwable) {
        null
    }

    /** Cap width at card scale — full-res frames waste the cache dir. */
    private fun scale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        if (w <= MAX_WIDTH_PX) return bitmap
        val h = bitmap.height * MAX_WIDTH_PX / w
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, MAX_WIDTH_PX, h, true)
        }.getOrDefault(bitmap)
    }

    /** Stable, filesystem-safe: name hash + size bucket (16 MB steps). */
    private fun cacheKey(video: SavedVideo): String {
        val bucket = video.sizeBytes / SIZE_BUCKET_BYTES
        val seed = "${video.fileName}:$bucket"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

package com.agon.app.proxy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ClipBufferManager — Retroactive Clip Engine
 *
 * Maintains a rolling ring buffer of the last ~30 seconds of stream data.
 * Every byte that flows through RecordingDataSource is also written here
 * (when enabled). When the user taps "Clip", the buffer is dumped to a
 * .mp4 file — capturing the last N seconds of footage AFTER it happened.
 *
 * This is the "Retroactive Clip" feature — unique to IPTV because only
 * live streams have a continuous byte flow to capture from. TikTok /
 * Reels cannot do this because their content is pre-recorded.
 *
 * Architecture
 * ============
 *   RecordingDataSource.read() → upstream bytes
 *       ↓ tee
 *   ClipBufferManager.write()  → ring buffer (ArrayDeque<ByteArray>)
 *       ↓ trim to MAX_BUFFER_BYTES
 *   [30s of recent footage always available]
 *
 *   User taps "Clip 15s":
 *       ClipBufferManager.dumpLastSeconds(15) → .mp4 file in Downloads
 *
 * Buffer sizing
 * =============
 * IPTV streams are typically 1.5-3 Mbps. At 2 Mbps (250 KB/s):
 *   30 seconds = ~7.5 MB
 *   15 seconds = ~3.75 MB
 * We keep MAX_BUFFER_BYTES = 10 MB to safely cover 30s even at 2.5 Mbps.
 */
object ClipBufferManager {

    private const val TAG = "ClipBuffer"

    /** Ring buffer max size — ~10MB ≈ 30s at 2.5 Mbps. */
    private const val MAX_BUFFER_BYTES = 10 * 1024 * 1024L

    /** Each chunk = 64KB for efficient allocation/deallocation. */
    private const val CHUNK_SIZE = 64 * 1024

    @Volatile
    private var enabled = false

    /** The ring buffer — ArrayDeque of byte[] chunks. */
    private val chunks = ArrayDeque<ByteArray>()

    /** Total bytes currently in the buffer. */
    private var totalBytes = 0L

    /**
     * Enables or disables the clip buffer. When disabled, the buffer is
     * cleared to free memory. Should be enabled when playback starts and
     * disabled when playback stops.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            synchronized(this) {
                chunks.clear()
                totalBytes = 0L
            }
            Log.i(TAG, "Clip buffer disabled + cleared")
        } else {
            Log.i(TAG, "Clip buffer enabled (max ${MAX_BUFFER_BYTES / 1024}KB)")
        }
    }

    /** Whether the buffer is currently capturing. */
    fun isEnabled(): Boolean = enabled

    /**
     * Writes a byte range to the ring buffer. Called from
     * [RecordingDataSource.read] for every chunk ExoPlayer reads.
     * Silently no-ops if the buffer is disabled.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!enabled || length <= 0) return
        synchronized(this) {
            // Copy the relevant bytes into a new chunk.
            val chunk = buffer.copyOfRange(offset, offset + length)
            chunks.addLast(chunk)
            totalBytes += length

            // Trim old chunks from the front until we're under the max.
            while (totalBytes > MAX_BUFFER_BYTES && chunks.isNotEmpty()) {
                val old = chunks.removeFirst()
                totalBytes -= old.size
            }
        }
    }

    /**
     * Approximate seconds of footage currently in the buffer.
     * Based on 2 Mbps assumption (250 KB/s).
     */
    fun bufferedSeconds(): Int {
        val bytes = totalBytes
        return (bytes / (250L * 1024L)).toInt().coerceAtLeast(0)
    }

    /**
     * Dumps the last [seconds] of footage to a .mp4 file in the user's
     * Downloads directory. Returns the file path on success, null on
     * failure or if the buffer is empty.
     *
     * @param seconds Target duration (approximate — based on 2 Mbps).
     *                Clamped to [10, 30].
     */
    fun dumpLastSeconds(context: Context, seconds: Int): String? {
        val targetSeconds = seconds.coerceIn(10, 30)
        // Approximate bytes for N seconds at 2 Mbps = 250 KB/s.
        val targetBytes = targetSeconds * 250L * 1024L

        val collected: List<ByteArray>
        synchronized(this) {
            if (chunks.isEmpty()) {
                Log.w(TAG, "Buffer is empty — nothing to clip")
                return null
            }
            // Collect the LAST N chunks (most recent) until we reach the
            // target byte count. ArrayDeque in Kotlin doesn't have
            // descendingIterator, so we iterate the chunk list in reverse.
            val all = chunks.toList()
            val picked = mutableListOf<ByteArray>()
            var collectedBytes = 0L
            var i = all.size - 1
            while (i >= 0 && collectedBytes < targetBytes) {
                val chunk = all[i]
                picked.add(0, chunk)
                collectedBytes += chunk.size
                i--
            }
            collected = picked
            Log.i(TAG, "Clipping ${collected.size} chunks, ${collectedBytes / 1024}KB ≈ ${targetSeconds}s")
        }

        // Write the collected chunks to a file.
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "SilinaTV_Clip_$timeStamp.mp4"

        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: return null
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                for (chunk in collected) {
                    out.write(chunk)
                }
                out.flush()
            }
            Log.i(TAG, "Clip saved → ${file.absolutePath} (${file.length() / 1024}KB)")

            // Publish to MediaStore so it's visible in the gallery.
            publishToMediaStore(context, file)

            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save clip: ${e.message}", e)
            null
        }
    }

    /**
     * Publishes the clip file to MediaStore so it appears in the gallery
     * and Downloads app. Best-effort — non-fatal if it fails.
     */
    private fun publishToMediaStore(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inp ->
                            inp.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    val finalize = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, finalize, null, null)
                    Log.i(TAG, "Clip published to MediaStore: $uri")
                }
            } else {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore publish failed (non-fatal): ${e.message}")
        }
    }
}

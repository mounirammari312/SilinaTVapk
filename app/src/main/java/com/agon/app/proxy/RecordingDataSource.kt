package com.agon.app.proxy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * RecordingDataSource
 *
 * Wraps another [DataSource] and:
 *   1. Tees every byte to the active recording file (Live DVR).
 *   2. Feeds every byte to ClipBufferManager (Retroactive Clip).
 *
 * Both features share the same byte stream — recording is a permanent
 * file dump, while the clip buffer is a rolling 30s window in RAM that
 * the user can "snapshot" on demand.
 */
class RecordingDataSource(
    private val upstream: DataSource
) : BaseDataSource(/* isNetwork */ true) {

    companion object {
        private const val TAG = "RecDataSource"

        /** Application context — set by RecordingDataSourceFactory for BandwidthShield. */
        @Volatile
        internal var appContext: Context? = null

        @Volatile
        private var primaryStream: OutputStream? = null

        @Volatile
        private var primaryFile: File? = null

        private val recordingBytes = AtomicLong(0L)

        @Volatile
        private var recordingStartMs: Long = 0L

        @Volatile
        var lastRecordingPath: String = ""
            private set

        val isRecording: Boolean get() = primaryStream != null
        fun recordingStartTime(): Long = recordingStartMs
        fun recordingBytesWritten(): Long = recordingBytes.get()

        fun startRecording(context: Context): String? {
            if (primaryStream != null) {
                Log.w(TAG, "Recording already in progress")
                return null
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "SilinaTV_$timeStamp.mp4"
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: return null
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                primaryStream = FileOutputStream(file)
                primaryFile = file
                lastRecordingPath = file.absolutePath
                Log.i(TAG, "Recording target → $lastRecordingPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open recording file: ${e.message}", e)
                primaryStream = null
                primaryFile = null
                return null
            }
            recordingStartMs = System.currentTimeMillis()
            recordingBytes.set(0L)
            Log.i(TAG, "Recording started: $fileName")
            return fileName
        }

        fun stopRecording(context: Context): Long {
            val stream = primaryStream ?: return -1L
            val file = primaryFile
            val totalBytes = recordingBytes.get()
            try {
                stream.flush()
                stream.close()
                Log.i(TAG, "Recording finalized. Bytes: $totalBytes")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Error closing recording stream: ${e.message}")
            }
            primaryStream = null
            primaryFile = null
            recordingStartMs = 0L
            if (file != null && file.exists() && file.length() > 0) {
                publishToMediaStore(context, file)
            }
            return totalBytes
        }

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
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
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
                        Log.i(TAG, "Published to MediaStore: $uri")
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

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        
        // Inject the correct spoofed User-Agent if one is set by GlobalPlaybackCoordinator.
        val ua = RecordingDataSourceFactory.targetUserAgent
        val finalSpec = if (ua != null) {
            val headers = dataSpec.httpRequestHeaders.toMutableMap()
            headers["User-Agent"] = ua
            dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
        } else {
            dataSpec
        }
        
        val length = upstream.open(finalSpec)
        transferStarted(finalSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            bytesTransferred(bytesRead)

            // Feed the Retroactive Clip ring buffer (always on, low cost).
            try {
                ClipBufferManager.write(buffer, offset, bytesRead)
            } catch (_: Throwable) {}

            // Tee to the recording file if recording is active.
            val stream = primaryStream
            if (stream != null) {
                try {
                    stream.write(buffer, offset, bytesRead)
                    recordingBytes.addAndGet(bytesRead.toLong())
                    if (recordingBytes.get() % (256 * 1024) < bytesRead) {
                        stream.flush()
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Recording write error: ${e.message}")
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  BandwidthShield — track bytes consumed + enforce daily limit.
            //  The shield checks the limit every ~1MB (not every byte) to
            //  avoid performance overhead. If the limit is exceeded, it
            //  fires the limitExceededCallback which pauses ExoPlayer.
            //
            //  V9.7 — RE-ENABLED (was commented out). The shield is fully
            //  functional and wired to GlobalPlaybackCoordinator.forceTeardown()
            //  via BandwidthShield.onBytesRead(). When the daily limit is hit,
            //  playback pauses automatically. Returns false = limit exceeded
            //  → we throw IOException so ExoPlayer aborts the segment fetch.
            // ═══════════════════════════════════════════════════════════════
            try {
                val ctx = appContext
                if (ctx != null) {
                    val allowed = com.agon.app.bandwidth.BandwidthShield.onBytesRead(ctx, bytesRead)
                    if (!allowed) {
                        throw java.io.IOException("Bandwidth daily limit exceeded")
                    }
                }
            } catch (e: java.io.IOException) {
                throw e
            } catch (_: Throwable) {}
        }
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        try { upstream.close() } catch (_: Throwable) {}
        transferEnded()
    }
}

class RecordingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val context: Context? = null
) : DataSource.Factory {
    companion object {
        @Volatile
        var targetUserAgent: String? = null
    }

    override fun createDataSource(): DataSource {
        // Inject the application context so BandwidthShield can access DataStore.
        if (context != null) {
            RecordingDataSource.appContext = context.applicationContext
        }
        return RecordingDataSource(upstreamFactory.createDataSource())
    }
}

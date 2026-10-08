package com.superz.iptvplayer.data.recording

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.16.0 — the recording ENGINE's tee contract, tested against the real
 * RecordingDataSource (copied from the reference) driven by a hand-rolled
 * in-memory upstream DataSource:
 *
 *   1. every byte read through the tee lands in the recording file,
 *      byte-exact, in order;
 *   2. recordingBytesWritten() mirrors the upstream byte count;
 *   3. double start is rejected (null);
 *   4. stop when idle returns -1;
 *   5. a recording started but never fed → 0 bytes → "stopped (0 bytes)"
 *      path (empty file → not published, still closed cleanly);
 *   6. idle tee is a pure passthrough (no recording, bytes just flow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingDataSourceTest {

    /** In-memory upstream — serves [payload] in chunks, ExoPlayer-style. */
    private class FakeUpstream(private val payload: ByteArray) : DataSource {
        private var pos = 0
        private var opened = false
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            opened = true
            uri = dataSpec.uri
            return payload.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!opened || pos >= payload.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, payload.size - pos)
            System.arraycopy(payload, pos, buffer, offset, n)
            pos += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun close() {
            opened = false
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Reset the engine's static recording state between tests.
        RecordingDataSource.stopRecording(context)
    }

    @Test
    fun `tee writes every byte to the recording file byte-exact`() {
        val name = RecordingDataSource.startRecording(context)
        assertNotNull("startRecording must return the file name", name)
        assertTrue(name!!.startsWith("Oria_"))
        assertTrue(name.endsWith(".mp4"))
        assertTrue(RecordingDataSource.isRecording)
        assertTrue("recordingStartTime must be set", RecordingDataSource.recordingStartTime() > 0)

        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val tee = RecordingDataSource(FakeUpstream(payload))
        tee.open(DataSpec(Uri.parse("https://example.com/live.ts")))
        val buf = ByteArray(8192)
        while (tee.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) { /* drain */ }
        tee.close()

        assertEquals(payload.size.toLong(), RecordingDataSource.recordingBytesWritten())

        val file = File(RecordingDataSource.lastRecordingPath)
        assertTrue(file.exists())
        assertEquals(payload.size.toLong(), file.length())
        assertArrayEquals(payload, file.readBytes())

        val bytes = RecordingDataSource.stopRecording(context)
        assertEquals(payload.size.toLong(), bytes)
        assertFalse(RecordingDataSource.isRecording)
        assertEquals(0L, RecordingDataSource.recordingStartTime())
    }

    @Test
    fun `second start while recording is rejected`() {
        assertNotNull(RecordingDataSource.startRecording(context))
        assertNull("second startRecording must return null", RecordingDataSource.startRecording(context))
        // still recording — clean up
        RecordingDataSource.stopRecording(context)
    }

    @Test
    fun `stop when idle returns minus one`() {
        assertFalse(RecordingDataSource.isRecording)
        assertEquals(-1L, RecordingDataSource.stopRecording(context))
    }

    @Test
    fun `started but never fed stops with zero bytes`() {
        assertNotNull(RecordingDataSource.startRecording(context))
        val bytes = RecordingDataSource.stopRecording(context)
        assertEquals(0L, bytes)
        assertFalse(RecordingDataSource.isRecording)
    }

    @Test
    fun `idle tee is a pure passthrough`() {
        // No active recording: reading through the tee must still work and
        // write nothing anywhere (lastRecordingPath stays empty until a
        // recording actually starts).
        val payload = ByteArray(4_096) { (it * 7).toByte() }
        val tee = RecordingDataSource(FakeUpstream(payload))
        tee.open(DataSpec(Uri.parse("https://example.com/idle.ts")))
        val buf = ByteArray(1024)
        var total = 0
        while (true) {
            val n = tee.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        tee.close()
        assertEquals(payload.size, total)
        assertEquals(0L, RecordingDataSource.recordingBytesWritten())
        assertEquals("", RecordingDataSource.lastRecordingPath)
    }
}

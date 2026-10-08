package com.superz.iptvplayer.ui.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.18.1 — SavedVideosContract unit tests: the pure filename/display
 * rules behind the Saved Videos library (the same pattern as
 * VuHomeContractTest — no Android types, no filesystem).
 */
class SavedVideosContractTest {

    // ── isVideoFile ──────────────────────────────────────────────

    @Test
    fun `listable saves`() {
        assertTrue(SavedVideosContract.isVideoFile("Oria_Dune_2021_20261005_183012.mp4"))
        assertTrue(SavedVideosContract.isVideoFile("Oria_فيلم_العروس_20261005_183012.ts"))
        assertTrue(SavedVideosContract.isVideoFile("Oria_Live_Recording_20261005_183012.mkv"))
        // in-flight temp
        assertTrue(SavedVideosContract.isVideoFile("Oria_Dune_2021_20261005_183012.part"))
    }

    @Test
    fun `non-library files are rejected`() {
        // no recorder prefix
        assertFalse(SavedVideosContract.isVideoFile("Dune_2021_20261005_183012.mp4"))
        // the partial marker sidecar itself
        assertFalse(SavedVideosContract.isVideoFile("Oria_Dune_2021_20261005_183012.mp4.partial"))
        // audio sidecars
        assertFalse(SavedVideosContract.isVideoFile("Oria_Dune_2021_20261005_183012_a.part"))
        assertFalse(SavedVideosContract.isVideoFile("Oria_Dune_2021_20261005_183012_audio.ts"))
        // foreign files that happen to sit in Downloads
        assertFalse(SavedVideosContract.isVideoFile("invoice.pdf"))
        assertFalse(SavedVideosContract.isVideoFile("Oria_notes.txt"))
        // case-insensitive extension
        assertTrue(SavedVideosContract.isVideoFile("Oria_X_20261005_183012.MP4"))
    }

    @Test
    fun `audio sidecar detection`() {
        assertTrue(SavedVideosContract.isAudioSidecar("Oria_M_20261005_183012_a.part"))
        assertTrue(SavedVideosContract.isAudioSidecar("Oria_M_20261005_183012_audio.ts"))
        assertFalse(SavedVideosContract.isAudioSidecar("Oria_M_20261005_183012.mp4"))
        assertFalse(SavedVideosContract.isAudioSidecar("Oria_M_20261005_183012.part"))
    }

    // ── displayName ──────────────────────────────────────────────

    @Test
    fun `title with year round-trips`() {
        assertEquals(
            "Dune 2021",
            SavedVideosContract.displayName("Oria_Dune_2021_20261005_183012.mp4")
        )
    }

    @Test
    fun `arabic title round-trips`() {
        assertEquals(
            "فيلم العروس",
            SavedVideosContract.displayName("Oria_فيلم_العروس_20261005_183012.mp4")
        )
    }

    @Test
    fun `sanitizer underscores become spaces`() {
        // buildLabel maps ':' / ' ' etc. to '_'; display restores spaces
        assertEquals(
            "Dune Part Two",
            SavedVideosContract.displayName("Oria_Dune_Part_Two_20261005_183012.mp4")
        )
    }

    @Test
    fun `in-flight part and audio tails strip`() {
        assertEquals(
            "Dune 2021",
            SavedVideosContract.displayName("Oria_Dune_2021_20261005_183012.part")
        )
        assertEquals(
            "Dune 2021",
            SavedVideosContract.displayName("Oria_Dune_2021_20261005_183012_a.part")
        )
    }

    @Test
    fun `timestamp-only label becomes a readable date`() {
        assertEquals(
            "2026-10-05 18:30",
            SavedVideosContract.displayName("Oria_20261005_183012.mp4")
        )
    }

    @Test
    fun `unparseable name degrades to its stem`() {
        // no Oria_ prefix, no stamp — the sanitizer still strips the
        // extension and returns the stem (the library only lists Oria_
        // files, so this is the diagnostic path)
        assertEquals("random", SavedVideosContract.displayName("random.bin"))
    }

    // ── helpers ──────────────────────────────────────────────────

    @Test
    fun `marker names`() {
        assertEquals(
            "Oria_D_20261005_183012.mp4.partial",
            SavedVideosContract.markerNameFor("Oria_D_20261005_183012.mp4")
        )
    }

    @Test
    fun `formatStamp is locale-neutral`() {
        assertEquals("2026-10-05 18:30", SavedVideosContract.formatStamp("20261005", "183012"))
    }

    @Test
    fun `formatSize buckets`() {
        assertEquals("512 B", SavedVideosContract.formatSize(512))
        assertEquals("2 KB", SavedVideosContract.formatSize(2048))
        assertEquals("812.0 MB", SavedVideosContract.formatSize(812L * 1024 * 1024))
        assertEquals("1.39 GB", SavedVideosContract.formatSize(1024L * 1024 * 1024 + 400L * 1024 * 1024))
    }

    @Test
    fun `formatDuration forms`() {
        assertEquals("0:00", SavedVideosContract.formatDuration(0))
        assertEquals("0:59", SavedVideosContract.formatDuration(59_000L))
        assertEquals("1:00", SavedVideosContract.formatDuration(60_000L))
        assertEquals("1:02:03", SavedVideosContract.formatDuration(3_723_000L))
        assertEquals("0:00", SavedVideosContract.formatDuration(-5))
    }

    @Test
    fun `mime hints`() {
        assertEquals("video/mp4", SavedVideosContract.mimeFor("Oria_D_1.mp4"))
        assertEquals("video/mp4", SavedVideosContract.mimeFor("Oria_D_1.M4V"))
        assertEquals("video/mp2t", SavedVideosContract.mimeFor("Oria_D_1.ts"))
        assertEquals("video/mp2t", SavedVideosContract.mimeFor("Oria_D_1.part"))
        assertEquals("video/x-matroska", SavedVideosContract.mimeFor("Oria_D_1.mkv"))
        assertNull(SavedVideosContract.mimeFor("Oria_D_1.avi"))
    }
}

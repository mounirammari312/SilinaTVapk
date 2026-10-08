package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.player.multiscreen.ChainAction
import com.superz.iptvplayer.player.multiscreen.FocusDir
import com.superz.iptvplayer.player.multiscreen.MultiLayout
import com.superz.iptvplayer.player.multiscreen.MultiScreenGrid
import com.superz.iptvplayer.player.multiscreen.MultiScreenPrefs
import com.superz.iptvplayer.player.multiscreen.MultiScreenGrid.CellEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — MULTI-SCREEN contract tests (pure JVM, the
 *  VodSidebarBuilder/BingeNavigation pattern): the grid geometry, the
 *  clamped D-pad navigation, the capability gate, the per-cell URL
 *  chain, the assign-and-advance cursor and the persisted session's
 *  encode/decode round trip.
 * ═══════════════════════════════════════════════════════════════════
 */
class MultiScreenGridTest {

    private fun xtreamPlaylist() = Playlist(
        id = 1L, name = "XC", type = "XTREAM",
        server = "http://panel.tv:8080", username = "u", password = "p"
    )

    private fun m3uPlaylist() = Playlist(id = 2L, name = "M3U", type = "M3U")

    private fun portalPlaylist() = Playlist(id = 3L, name = "Portal", type = "PORTAL")

    private fun channel(
        key: String,
        num: Int = 1,
        directUrl: String? = null,
        streamId: Long? = null,
        stalkerCmd: String? = null
    ) = Channel(
        playlistId = 1L, key = key, num = num, name = "Ch $num",
        logo = null, categoryId = null, streamId = streamId,
        directUrl = directUrl, stalkerCmd = stalkerCmd
    )

    // ── layout geometry ────────────────────────────────────────────

    @Test
    fun `the two grid shapes carry their geometry`() {
        assertEquals(2, MultiLayout.TWO.cells)
        assertEquals(1, MultiLayout.TWO.rows)
        assertEquals(2, MultiLayout.TWO.cols)
        assertEquals(4, MultiLayout.FOUR.cells)
        assertEquals(2, MultiLayout.FOUR.rows)
        assertEquals(2, MultiLayout.FOUR.cols)
    }

    @Test
    fun `layout names round-trip and garbage is refused`() {
        assertEquals(MultiLayout.FOUR, MultiLayout.fromName("FOUR"))
        assertEquals(MultiLayout.TWO, MultiLayout.fromName("TWO"))
        assertNull(MultiLayout.fromName("NINE"))
        assertNull(MultiLayout.fromName(null))
        assertNull(MultiLayout.fromName(""))
    }

    // ── clamped D-pad navigation ───────────────────────────────────

    @Test
    fun `four-grid navigation crosses and clamps at every edge`() {
        val g = MultiLayout.FOUR
        // horizontal crossing (row-major slots: 0|1 over 2|3)
        assertEquals(1, MultiScreenGrid.focusTarget(g, 0, FocusDir.RIGHT))
        assertEquals(0, MultiScreenGrid.focusTarget(g, 1, FocusDir.LEFT))
        assertEquals(3, MultiScreenGrid.focusTarget(g, 2, FocusDir.RIGHT))
        assertEquals(2, MultiScreenGrid.focusTarget(g, 3, FocusDir.LEFT))
        // vertical crossing
        assertEquals(2, MultiScreenGrid.focusTarget(g, 0, FocusDir.DOWN))
        assertEquals(3, MultiScreenGrid.focusTarget(g, 1, FocusDir.DOWN))
        assertEquals(0, MultiScreenGrid.focusTarget(g, 2, FocusDir.UP))
        assertEquals(1, MultiScreenGrid.focusTarget(g, 3, FocusDir.UP))
        // clamped edges (no wrap-around, ever)
        assertNull(MultiScreenGrid.focusTarget(g, 0, FocusDir.LEFT))
        assertNull(MultiScreenGrid.focusTarget(g, 2, FocusDir.LEFT))
        assertNull(MultiScreenGrid.focusTarget(g, 1, FocusDir.RIGHT))
        assertNull(MultiScreenGrid.focusTarget(g, 3, FocusDir.RIGHT))
        assertNull(MultiScreenGrid.focusTarget(g, 0, FocusDir.UP))
        assertNull(MultiScreenGrid.focusTarget(g, 1, FocusDir.UP))
        assertNull(MultiScreenGrid.focusTarget(g, 2, FocusDir.DOWN))
        assertNull(MultiScreenGrid.focusTarget(g, 3, FocusDir.DOWN))
    }

    @Test
    fun `two-grid navigation is one row only`() {
        val g = MultiLayout.TWO
        assertEquals(1, MultiScreenGrid.focusTarget(g, 0, FocusDir.RIGHT))
        assertEquals(0, MultiScreenGrid.focusTarget(g, 1, FocusDir.LEFT))
        assertNull(MultiScreenGrid.focusTarget(g, 0, FocusDir.DOWN))
        assertNull(MultiScreenGrid.focusTarget(g, 1, FocusDir.UP))
    }

    @Test
    fun `out-of-range slots never navigate`() {
        assertNull(MultiScreenGrid.focusTarget(MultiLayout.TWO, 5, FocusDir.RIGHT))
        assertNull(MultiScreenGrid.focusTarget(MultiLayout.TWO, -1, FocusDir.LEFT))
    }

    // ── the capability gate ────────────────────────────────────────

    @Test
    fun `xtream channels with a stream id are capable`() {
        assertTrue(MultiScreenGrid.isMultiScreenCapable(xtreamPlaylist(), channel("531", streamId = 531L)))
    }

    @Test
    fun `xtream channel without credentials-bearing playlist is refused`() {
        // streamId present but M3U playlist → no derivable URL
        assertFalse(MultiScreenGrid.isMultiScreenCapable(m3uPlaylist(), channel("531", streamId = 531L)))
    }

    @Test
    fun `m3u channels with an http direct url are capable`() {
        assertTrue(
            MultiScreenGrid.isMultiScreenCapable(
                m3uPlaylist(), channel("hash1", directUrl = "http://cdn.tv/stream/1.m3u8")
            )
        )
        assertTrue(
            MultiScreenGrid.isMultiScreenCapable(
                m3uPlaylist(), channel("hash2", directUrl = "https://cdn.tv/stream/2.ts")
            )
        )
    }

    @Test
    fun `vlc-only protocol urls are refused`() {
        assertFalse(
            MultiScreenGrid.isMultiScreenCapable(
                m3uPlaylist(), channel("rtsp1", directUrl = "rtsp://server.tv/live")
            )
        )
        assertFalse(
            MultiScreenGrid.isMultiScreenCapable(
                m3uPlaylist(), channel("udp1", directUrl = "udp://@239.1.1.1:5000")
            )
        )
    }

    @Test
    fun `portal channels with a cmd are capable`() {
        assertTrue(
            MultiScreenGrid.isMultiScreenCapable(
                portalPlaylist(), channel("k:531", stalkerCmd = "ffmpeg http://portal/live/531")
            )
        )
        // PORTAL channel without a cmd → nothing to resolve
        assertFalse(MultiScreenGrid.isMultiScreenCapable(portalPlaylist(), channel("k:531")))
    }

    @Test
    fun `vod keys and local saves never join a grid`() {
        val pl = m3uPlaylist()
        for (vodKey in listOf(
            "vodm:46170", "vode:12", "vodc:531", "vodx:1", "vodt:1",
            "saved:/storage/emulated/0/x.mp4"
        )) {
            assertFalse("key $vodKey must be refused", MultiScreenGrid.isMultiScreenCapable(pl, channel(vodKey, directUrl = "http://x/y.mp4")))
        }
    }

    // ── the per-cell URL chain ─────────────────────────────────────

    @Test
    fun `xtream chain is directSource then ts then m3u8, de-duplicated`() {
        val ch = channel(
            "531", streamId = 531L,
            directUrl = "http://panel.tv:8080/live/u/p/531.ts"
        )
        val urls = MultiScreenGrid.candidateUrls(xtreamPlaylist(), ch)
        assertEquals(
            listOf(
                "http://panel.tv:8080/live/u/p/531.ts",
                "http://panel.tv:8080/live/u/p/531.m3u8"
            ),
            urls
        )
    }

    @Test
    fun `xtream chain without directSource starts at the ts variant`() {
        val urls = MultiScreenGrid.candidateUrls(xtreamPlaylist(), channel("531", streamId = 531L))
        assertEquals(
            listOf(
                "http://panel.tv:8080/live/u/p/531.ts",
                "http://panel.tv:8080/live/u/p/531.m3u8"
            ),
            urls
        )
    }

    @Test
    fun `m3u chain is just the direct url`() {
        val urls = MultiScreenGrid.candidateUrls(
            m3uPlaylist(), channel("hash1", directUrl = "http://cdn.tv/s/1.m3u8")
        )
        assertEquals(listOf("http://cdn.tv/s/1.m3u8"), urls)
    }

    @Test
    fun `non-http direct urls never enter the chain`() {
        val urls = MultiScreenGrid.candidateUrls(
            m3uPlaylist(), channel("rtsp1", directUrl = "rtsp://server/live")
        )
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `missing credentials produce no xtream variants`() {
        val broken = xtreamPlaylist().copy(username = "", password = null)
        val urls = MultiScreenGrid.candidateUrls(broken, channel("531", streamId = 531L))
        assertTrue(urls.isEmpty())
    }

    // ── the assign-and-advance cursor ──────────────────────────────

    @Test
    fun `cursor hops to the next empty slot, wrapping around`() {
        val slots = listOf<Channel?>(channel("a"), null, channel("c"), null)
        assertEquals(1, MultiScreenGrid.nextEmptySlot(slots, 0))
        assertEquals(3, MultiScreenGrid.nextEmptySlot(slots, 1))
        // wraps: after the last empty slot, the first empty one again
        assertEquals(1, MultiScreenGrid.nextEmptySlot(slots, 3))
    }

    @Test
    fun `a full grid keeps the cursor where it was`() {
        val slots = listOf(channel("a"), channel("b"), channel("c"), channel("d"))
        assertEquals(2, MultiScreenGrid.nextEmptySlot(slots, 2))
    }

    // ── the persisted session's encode/decode ──────────────────────

    @Test
    fun `slot keys encode ordered and decode back in order`() {
        val keys = listOf("531", "k:12", "hash9", null, "77")
        val enc = MultiScreenPrefs.encodeKeys(keys)
        assertEquals(listOf("531", "k:12", "hash9", "77"), MultiScreenPrefs.decodeKeys(enc))
    }

    @Test
    fun `empty and blank keys are skipped by the encoder`() {
        val enc = MultiScreenPrefs.encodeKeys(listOf(null, "", "  ", "531"))
        assertEquals(listOf("531"), MultiScreenPrefs.decodeKeys(enc))
    }

    @Test
    fun `garbage and null decode to an empty list`() {
        assertTrue(MultiScreenPrefs.decodeKeys(null).isEmpty())
        assertTrue(MultiScreenPrefs.decodeKeys("").isEmpty())
        assertTrue(MultiScreenPrefs.decodeKeys("   ").isEmpty())
    }

    // ── v2.3.1 — THE STAGGERED SPIN-UP BACKOFF ─────────────────────
    // The codec-budget gate behind the field-crash fix: a grid start
    // must NOT demand every player at once; the first cell runs now,
    // each following one a step later; a lone mid-watch swap runs now.

    @Test
    fun `an empty window starts immediately`() {
        assertEquals(0L, MultiScreenGrid.creationBackoffMs(nowMs = 10_000L, recentCreationMs = emptyList()))
    }

    @Test
    fun `a grid start staggers one step per recent creation`() {
        // Four cells assigned within the same instant: 0 / 300 / 600 / 900ms.
        val now = 10_000L
        assertEquals(0L, MultiScreenGrid.creationBackoffMs(now, listOf()))
        assertEquals(300L, MultiScreenGrid.creationBackoffMs(now, listOf(10_000L)))
        assertEquals(600L, MultiScreenGrid.creationBackoffMs(now, listOf(9_999L, 10_000L)))
        assertEquals(900L, MultiScreenGrid.creationBackoffMs(now, listOf(9_998L, 9_999L, 10_000L)))
    }

    @Test
    fun `creations older than the window do not stagger`() {
        val now = 10_000L
        val window = MultiScreenGrid.STAGGER_WINDOW_MS
        assertEquals(
            0L,
            MultiScreenGrid.creationBackoffMs(now, listOf(now - window - 1L, now - window - 2L))
        )
    }

    @Test
    fun `a mid-watch swap after the window closes starts instantly`() {
        // The grid stood up at t=0..900; a picker swap at t=8000 finds an
        // empty window (1.5s window long expired) — instant replacement.
        val gridStart = listOf(0L, 0L, 0L, 0L)
        assertEquals(0L, MultiScreenGrid.creationBackoffMs(nowMs = 8_000L, recentCreationMs = gridStart))
    }

    @Test
    fun `the window edge itself counts as recent`() {
        val now = 10_000L
        val window = MultiScreenGrid.STAGGER_WINDOW_MS
        assertEquals(300L, MultiScreenGrid.creationBackoffMs(now, listOf(now - window)))
    }

    @Test
    fun `the backoff is capped for pathological creation storms`() {
        // 12 "recent" creations (a chain retry storm) must never produce
        // an absurd delay — the cap keeps the worst case bounded.
        val now = 10_000L
        val storm = List(12) { now }
        assertEquals(8 * MultiScreenGrid.STAGGER_STEP_MS, MultiScreenGrid.creationBackoffMs(now, storm))
    }

    @Test
    fun `degenerate step and window disable the stagger safely`() {
        assertEquals(0L, MultiScreenGrid.creationBackoffMs(10_000L, listOf(10_000L), stepMs = 0L))
        assertEquals(0L, MultiScreenGrid.creationBackoffMs(10_000L, listOf(10_000L), windowMs = 0L))
    }

    // ── v2.3.2 — the chain-retry decision (transient failures self-heal,
    //    real failures land on the error card after the second pass) ──

    @Test
    fun `a chain that is not exhausted always advances the url`() {
        assertEquals(ChainAction.ADVANCE_URL, MultiScreenGrid.nextChainAction(urlsExhausted = false, attempt = 1))
        assertEquals(ChainAction.ADVANCE_URL, MultiScreenGrid.nextChainAction(urlsExhausted = false, attempt = 5))
    }

    @Test
    fun `the first exhausted pass earns one automatic retry`() {
        // The v2.3.1 field bug: ONE transient failure (cold CDN edge,
        // momentary 403) permanently errored the cell. Now pass 1
        // exhaustion restarts the chain for a second honest pass.
        assertEquals(ChainAction.RESTART_CHAIN, MultiScreenGrid.nextChainAction(urlsExhausted = true, attempt = 1))
    }

    @Test
    fun `the second exhausted pass lands on the error card`() {
        assertEquals(ChainAction.ERROR, MultiScreenGrid.nextChainAction(urlsExhausted = true, attempt = 2))
        assertEquals(ChainAction.ERROR, MultiScreenGrid.nextChainAction(urlsExhausted = true, attempt = 3))
    }

    @Test
    fun `the retry budget is honored with a custom cap`() {
        assertEquals(ChainAction.RESTART_CHAIN, MultiScreenGrid.nextChainAction(true, 2, maxAttempts = 3))
        assertEquals(ChainAction.ERROR, MultiScreenGrid.nextChainAction(true, 3, maxAttempts = 3))
    }

    @Test
    fun `a degenerate zero-attempt never restarts forever`() {
        // attempt = 0 with cap 0 → already at the budget → ERROR (no
        // infinite restart loop for degenerate callers).
        assertEquals(ChainAction.ERROR, MultiScreenGrid.nextChainAction(true, 0, maxAttempts = 0))
    }

    // ── v2.4.0 — the cell engine ladder (the root fix's completion:
    //    EXO → the guaranteed VLC software rung) ──

    @Test
    fun `a decoder failure promotes the exo cell to the vlc rung`() {
        // The v2.3.x field bug: a codec-farm refusal errored the cell
        // while the SAME channel played through VLC one screen over.
        // Now the refusal promotes the cell to the software rung.
        assertEquals(CellEngine.VLC, MultiScreenGrid.nextCellEngine(CellEngine.EXO, failureWasDecoder = true))
    }

    @Test
    fun `server and timeout failures keep the engine`() {
        // Only the codec farm's refusal proves the ENGINE wrong — a
        // dead CDN edge or a slow first segment is the URL chain's
        // business, not the engine's.
        assertEquals(CellEngine.EXO, MultiScreenGrid.nextCellEngine(CellEngine.EXO, failureWasDecoder = false))
    }

    @Test
    fun `a vlc cell never promotes again`() {
        // One promotion per slot: a VLC that still fails walks the URL
        // chain like any honest failure (no third engine to climb to).
        assertEquals(CellEngine.VLC, MultiScreenGrid.nextCellEngine(CellEngine.VLC, failureWasDecoder = true))
        assertEquals(CellEngine.VLC, MultiScreenGrid.nextCellEngine(CellEngine.VLC, failureWasDecoder = false))
    }

    @Test
    fun `the first-frame budget is engine-aware`() {
        // A software-decoding cell (the VLC rung) starts honestly
        // slower than a hardware one — its timeout guard carries the
        // longer, CPU-honest leash.
        assertEquals(MultiScreenGrid.CELL_TIMEOUT_EXO_MS, MultiScreenGrid.cellTimeoutMs(CellEngine.EXO))
        assertEquals(MultiScreenGrid.CELL_TIMEOUT_VLC_MS, MultiScreenGrid.cellTimeoutMs(CellEngine.VLC))
        assertTrue(
            "the software rung must out-leash the hardware rung",
            MultiScreenGrid.CELL_TIMEOUT_VLC_MS > MultiScreenGrid.CELL_TIMEOUT_EXO_MS
        )
    }

    // ── v2.4.0 — the audio decoder economy (at most ONE exo cell — the
    //    focused one — ever claims an audio decoder instance) ──

    @Test
    fun `only the focused slot claims an audio decoder`() {
        assertTrue(MultiScreenGrid.claimsAudio(slot = 2, focusedSlot = 2))
        assertFalse(MultiScreenGrid.claimsAudio(slot = 0, focusedSlot = 2))
        assertFalse(MultiScreenGrid.claimsAudio(slot = 1, focusedSlot = 2))
        assertFalse(MultiScreenGrid.claimsAudio(slot = 3, focusedSlot = 2))
    }

    @Test
    fun `the economy survives degenerate focus values`() {
        // Empty grid / focus 0 default: slot 0 alone claims audio.
        assertTrue(MultiScreenGrid.claimsAudio(0, 0))
        assertFalse(MultiScreenGrid.claimsAudio(1, 0))
    }
}

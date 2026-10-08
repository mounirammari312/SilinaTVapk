package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.19.13 — the SAVED-VIDEOS engine-chain contract.
 *
 * The loopback playback of an offline save must ALWAYS start on EXO, no
 * matter what stale engine memory the key carries: memory is the zap
 * accelerator for NETWORK streams, and one historical EXO timeout on a
 * local file (the disk-cache thrash era) pinned VLC to "vodm:local:N" —
 * the engine whose session renders NO bottom time bar ("المشغل بلا شريط
 * أحمر أو مؤقت"). VLC stays in the chain only as the codec fallback of
 * last resort. Network channels keep the remembered-attempt reorder.
 */
class StreamUrlsLocalChainTest {

    private val pl = Playlist(id = 1, name = "Test", type = "M3U", m3uUrl = "http://x/y.m3u")

    private fun localChannel(url: String = "http://127.0.0.1:41234/abc012/0") =
        Channel(playlistId = 1, key = "vodm:local:0", num = 1, name = "Saved", directUrl = url)

    @Test
    fun `local chain starts on EXO and ignores stale VLC memory`() {
        val stale = EngineMemory(1, "vodm:local:0", "VLC", null)
        val chain = StreamUrls.buildChain(pl, localChannel(), stale)
        assertEquals(listOf(Engine.EXO, Engine.VLC), chain.map { it.engine })
        assertEquals("http://127.0.0.1:41234/abc012/0", chain[0].url)
        assertEquals(null, chain[0].variant)
    }

    @Test
    fun `local chain is EXO-first with no memory at all`() {
        val chain = StreamUrls.buildChain(pl, localChannel(), null)
        assertEquals(Engine.EXO, chain.first().engine)
        assertEquals(2, chain.size)
    }

    @Test
    fun `non-local protocol keeps its router order`() {
        // rtsp → VLC primary even for a local key (EngineRouter still rules)
        val chain = StreamUrls.buildChain(pl, localChannel("rtsp://127.0.0.1:5554/x"), null)
        assertEquals(listOf(Engine.VLC, Engine.EXO), chain.map { it.engine })
    }

    @Test
    fun `network m3u channel still reorders by remembered engine`() {
        val ch = Channel(
            playlistId = 1, key = "k:abc", num = 1, name = "Live",
            directUrl = "http://cdn.example.com/stream"
        )
        val remembered = EngineMemory(1, "k:abc", "VLC", null)
        val chain = StreamUrls.buildChain(pl, ch, remembered)
        assertEquals(Engine.VLC, chain.first().engine)
    }
}

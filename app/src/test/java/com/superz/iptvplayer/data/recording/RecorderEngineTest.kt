package com.superz.iptvplayer.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.17.0 — RecorderEngine's pure HLS parsing contracts (the standalone
 * recorder that replaced the corrupt-output tee). Playlist model, master
 * variant selection, byte ranges, AES-128 IV derivation and URL resolution.
 */
class RecorderEngineTest {

    // ── Master playlist parsing ───────────────────────────────────────────

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Arabic",DEFAULT=YES,LANGUAGE="ara",URI="audio/128k/playlist.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="eng",URI="audio/64k/playlist.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud"
        720/playlist.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
        1080/playlist.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=3840x2160,CODECS="hvc1.1.6.L120.90",AUDIO="aud"
        2160/playlist.m3u8
    """.trimIndent()

    @Test
    fun `master playlist variants are parsed with resolution heights`() {
        val (variants, _) = RecorderEngine.parseMasterPlaylist(
            master, "https://cdn.example/live/master.m3u8?token=x"
        )!!
        assertEquals(3, variants.size)
        assertEquals(900_000L, variants[0].bandwidth)
        assertEquals(720, variants[0].height)
        assertEquals(3_000_000L, variants[1].bandwidth)
        assertEquals(1080, variants[1].height)
        // RESOLUTION=WIDTHxHEIGHT — the height is the second number
        assertEquals(2160, variants[2].height)
        // relative URIs resolved against the playlist URL
        assertEquals("https://cdn.example/live/720/playlist.m3u8", variants[0].uri)
        assertEquals("https://cdn.example/live/2160/playlist.m3u8", variants[2].uri)
    }

    @Test
    fun `master playlist audio renditions are parsed with the default first`() {
        val (_, audios) = RecorderEngine.parseMasterPlaylist(
            master, "https://cdn.example/live/master.m3u8"
        )!!
        assertEquals(2, audios.size)
        assertTrue(audios[0].isDefault)
        assertEquals("Arabic", audios[0].name)
        assertEquals("https://cdn.example/live/audio/128k/playlist.m3u8", audios[0].uri)
        assertFalse(audios[1].isDefault)
    }

    @Test
    fun `a media playlist returns null from master parsing`() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.ts\n"
        assertNull(RecorderEngine.parseMasterPlaylist(media, "https://x/p.m3u8"))
    }

    // ── Media playlist parsing ────────────────────────────────────────────

    @Test
    fun `vod playlist segments are parsed and flagged vod`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:500
            #EXTINF:9.0,
            seg500.ts
            #EXTINF:10.0,
            seg501.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val info = RecorderEngine.parseMediaPlaylist(text, "https://cdn.example/v/p.m3u8")
        assertTrue(info.isVod)
        assertEquals(10.0, info.targetDurationSec, 0.001)
        assertEquals(500L, info.mediaSequence)
        assertEquals(2, info.segments.size)
        assertEquals(500L, info.segments[0].sequence)
        assertEquals(501L, info.segments[1].sequence)
        assertEquals(9.0, info.segments[0].durationSec, 0.001)
        assertEquals("https://cdn.example/v/seg500.ts", info.segments[0].url)
        assertNull(info.segments[0].key)
    }

    @Test
    fun `live playlist is not vod`() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nlive6.ts\n"
        val info = RecorderEngine.parseMediaPlaylist(text, "https://cdn.example/live.m3u8")
        assertFalse(info.isVod)
        assertEquals(1, info.segments.size)
    }

    @Test
    fun `aes key lines apply to the following segments`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example/key.php?k=1",IV=0X9c7b3f4a11ee3344d1e5f600a2b8c910
            #EXTINF:10,
            enc1.ts
            #EXTINF:10,
            enc2.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:10,
            clear.ts
        """.trimIndent()
        val info = RecorderEngine.parseMediaPlaylist(text, "https://cdn.example/v/p.m3u8")
        assertNotNull(info.segments[0].key)
        assertEquals("AES-128", info.segments[0].key!!.method)
        assertEquals("https://cdn.example/key.php?k=1", info.segments[0].key!!.uri)
        assertEquals("0X9c7b3f4a11ee3344d1e5f600a2b8c910", info.segments[0].key!!.ivHex)
        // the key applies until replaced
        assertEquals(info.segments[0].key!!.uri, info.segments[1].key!!.uri)
        // METHOD=NONE clears it
        assertNull(info.segments[2].key)
        // IV carries the exact declared value
        assertEquals(7L, info.segments[0].sequence)
    }

    @Test
    fun `byterange with explicit offset is parsed`() {
        val info = RecorderEngine.parseMediaPlaylist(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            #EXT-X-BYTERANGE:1000@2000
            seg.ts
            """.trimIndent(),
            "https://cdn.example/v/p.m3u8"
        )
        assertEquals(2000L, info.segments[0].rangeOffset)
        assertEquals(1000L, info.segments[0].rangeLength)
    }

    @Test
    fun `byterange without offset continues after the previous range`() {
        val (len, off) = RecorderEngine.parseByteRange("752@0", -1L)
        assertEquals(752L, len)
        assertEquals(0L, off)
        // second range without @: continues after the first (0+752-1 = 751)
        val (len2, off2) = RecorderEngine.parseByteRange("500", 751L)
        assertEquals(500L, len2)
        assertEquals(752L, off2)
    }

    @Test
    fun `fmp4 playlists are detected by init segment`() {
        val info = RecorderEngine.parseMediaPlaylist(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:10,
            chunk1.m4s
            #EXT-X-ENDLIST
        """.trimIndent(),
            "https://cdn.example/v/p.m3u8"
        )
        assertTrue(info.isFmp4)
        assertEquals("https://cdn.example/v/init.mp4", info.initSegmentUrl)
    }

    // ── Variant choice ────────────────────────────────────────────────────

    private val variants = listOf(
        RecorderEngine.HlsVariant(900_000L, 720, "720.m3u8"),
        RecorderEngine.HlsVariant(3_000_000L, 1080, "1080.m3u8"),
        RecorderEngine.HlsVariant(6_000_000L, 2160, "2160.m3u8")
    )

    @Test
    fun `the variant closest to the watched height wins`() {
        assertEquals("1080.m3u8", RecorderEngine.chooseVariant(variants, 1080).uri)
        assertEquals("720.m3u8", RecorderEngine.chooseVariant(variants, 600).uri)
        assertEquals("720.m3u8", RecorderEngine.chooseVariant(variants, 800).uri)
        assertEquals("2160.m3u8", RecorderEngine.chooseVariant(variants, 2000).uri)
    }

    @Test
    fun `without a preference the best variant wins`() {
        assertEquals("2160.m3u8", RecorderEngine.chooseVariant(variants, null).uri)
    }

    @Test
    fun `variants without height fall back to bandwidth distance`() {
        val noHeights = listOf(
            RecorderEngine.HlsVariant(500_000L, null, "a.m3u8"),
            RecorderEngine.HlsVariant(2_000_000L, null, "b.m3u8")
        )
        assertEquals("b.m3u8", RecorderEngine.chooseVariant(noHeights, 1080).uri)
    }

    // ── URL resolution ────────────────────────────────────────────────────

    @Test
    fun `absolute urls pass through`() {
        assertEquals(
            "https://other.example/seg.ts",
            RecorderEngine.resolveUrl("https://cdn.example/p.m3u8", "https://other.example/seg.ts")
        )
    }

    @Test
    fun `relative urls resolve against the playlist directory`() {
        assertEquals(
            "https://cdn.example/live/seg1.ts",
            RecorderEngine.resolveUrl("https://cdn.example/live/p.m3u8", "seg1.ts")
        )
        // query strings on the base are dropped for the segment dir
        assertEquals(
            "https://cdn.example/live/seg1.ts",
            RecorderEngine.resolveUrl("https://cdn.example/live/p.m3u8?token=abc", "seg1.ts")
        )
    }

    @Test
    fun `root absolute urls resolve against the origin`() {
        assertEquals(
            "https://cdn.example/hls/seg1.ts",
            RecorderEngine.resolveUrl("https://cdn.example/live/deep/p.m3u8", "/hls/seg1.ts")
        )
    }

    @Test
    fun `parent directory traversal is resolved`() {
        assertEquals(
            "https://cdn.example/v2/seg1.ts",
            RecorderEngine.resolveUrl("https://cdn.example/v1/sub/p.m3u8", "../../v2/seg1.ts")
        )
    }

    @Test
    fun `protocol relative urls inherit the scheme`() {
        assertEquals(
            "https://mirror.example/seg.ts",
            RecorderEngine.resolveUrl("https://cdn.example/p.m3u8", "//mirror.example/seg.ts")
        )
    }

    // ── AES IV derivation ─────────────────────────────────────────────────

    @Test
    fun `iv from sequence is 16 byte big endian`() {
        val iv = RecorderEngine.ivFromSequence(1L)
        assertEquals(16, iv.size)
        assertEquals(0, iv[14].toInt())
        assertEquals(1, iv[15].toInt())
        val ivBig = RecorderEngine.ivFromSequence(0x0102030405060708L)
        assertEquals(0x01, ivBig[8].toInt())
        assertEquals(0x08, ivBig[15].toInt())
    }

    // ── v1.18.0 — WATCH = DOWNLOAD: label + progress contracts ───────────

    @Test
    fun `v1 18 0 buildLabel keeps letters digits arabic and caps length`() {
        val label = RecorderEngine.buildLabel("Dune: Part Two! (2024)")
        // Punctuation → underscores, edges trimmed, timestamp suffix.
        assertTrue(label.startsWith("Oria_Dune__Part_Two___2024_"))
        assertTrue(label.matches(Regex("Oria_Dune__Part_Two___2024_\\d{8}_\\d{6}")))
        // The movie part alone is capped at 48 sanitized characters.
        val long = RecorderEngine.buildLabel("A".repeat(80))
        val moviePart = long.removePrefix("Oria_").substringBeforeLast("_20")
        assertTrue(moviePart.length <= 48)
    }

    @Test
    fun `v1 18 0 buildLabel preserves arabic titles`() {
        val label = RecorderEngine.buildLabel("فيلم النمر والفأر")
        assertTrue(label.contains("فيلم_النمر_والفأر"))
    }

    @Test
    fun `v1 18 0 blank title falls back to the timestamp label`() {
        val label = RecorderEngine.buildLabel(null)
        assertTrue(label.matches(Regex("Oria_\\d{8}_\\d{6}")))
        assertTrue(RecorderEngine.buildLabel("   ").matches(Regex("Oria_\\d{8}_\\d{6}")))
    }

    @Test
    fun `v1 18 0 progress starts indeterminate`() {
        assertEquals(-1, RecorderEngine.progress())
    }
}

package com.superz.iptvplayer.data.timemachine

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.18.0 — TimeMachineEngine's contract (the engine copied from the
 * reference's timemachine/TimeMachineEngine.kt): catch-up URL format,
 * stream-id extraction, the flag-agnostic channel test, and the reference's
 * exact hourly slot math driven by the current short-EPG program.
 */
class TimeMachineEngineTest {

    private fun playlist(
        type: String = "XTREAM",
        server: String? = "http://panel.tv:8080",
        user: String? = "u1",
        pass: String? = "p1"
    ) = Playlist(
        id = 1L, name = "pl", type = type, server = server,
        username = user, password = pass, isActive = true
    )

    private fun channel(streamId: Long? = 12L) = Channel(
        playlistId = 1L, key = "k:$streamId", num = 1, name = "Bein 1",
        streamId = streamId
    )

    // ── The reference's URL formats ──────────────────────────────────────

    @Test
    fun `live m3u8 url mirrors streamurls variant format`() {
        assertEquals(
            "http://panel.tv:8080/live/u1/p1/12.m3u8",
            TimeMachineEngine.liveM3u8Url(playlist(), 12L)
        )
    }

    @Test
    fun `live m3u8 url is null without credentials`() {
        assertNull(TimeMachineEngine.liveM3u8Url(playlist(user = null), 12L))
    }

    @Test
    fun `catchup url appends duration and start`() {
        val url = TimeMachineEngine.buildCatchupUrl(
            "http://panel.tv:8080/live/u1/p1/12.m3u8", 1700000000L, 3600L
        )
        assertEquals(
            "http://panel.tv:8080/live/u1/p1/12.m3u8?duration=3600&start=1700000000",
            url
        )
    }

    @Test
    fun `catchup url is null for non numeric stream ids`() {
        assertNull(
            TimeMachineEngine.buildCatchupUrl("http://x/live/u/p/abc.m3u8", 1L, 60L)
        )
    }

    // ── The reference's channel test (flag-agnostic) ─────────────────────

    @Test
    fun `isCatchupChannel accepts live m3u8 urls with numeric ids`() {
        assertTrue(
            TimeMachineEngine.isCatchupChannel("http://panel.tv:8080/live/u1/p1/12.m3u8")
        )
    }

    @Test
    fun `isCatchupChannel rejects ts urls and non live paths`() {
        assertEquals(false, TimeMachineEngine.isCatchupChannel("http://panel.tv/live/u/p/12.ts"))
        assertEquals(false, TimeMachineEngine.isCatchupChannel("http://panel.tv/movie/u/p/12.m3u8"))
    }

    @Test
    fun `getCatchupChannels is flag agnostic and xtream only`() {
        val channels = listOf(channel(12L), channel(null), channel(13L))
        assertEquals(2, TimeMachineEngine.getCatchupChannels(playlist(), channels).size)
        assertTrue(TimeMachineEngine.getCatchupChannels(playlist(type = "PORTAL"), channels).isEmpty())
    }

    // ── The reference's slot math (hourly, backwards, capped) ────────────

    @Test
    fun `recent programs use the current program duration capped at one hour`() {
        // A 90-minute current program → 60-minute slots. The program
        // started 10 minutes ago, so within a 3h window two full slots fit
        // (the third would start before the cutoff).
        val now = System.currentTimeMillis()
        val epg = EpgProgram(
            startMs = now - 10 * 60_000L,      // started 10 min ago
            endMs = now + 80 * 60_000L,        // 90-minute program
            title = "Match"
        )
        val url = "http://panel.tv:8080/live/u1/p1/12.m3u8"
        val past = TimeMachineEngine.getRecentPrograms(url, epg, maxHours = 3)
        assertEquals(2, past.size)
        // Slot labels are "(replay -Nh)" — the reference's exact format.
        assertEquals("Match (replay -1h)", past.first().title)
        assertEquals("Match (replay -2h)", past[1].title)
        // 60-minute slots at 60-minute steps.
        past.forEach { assertEquals(60, it.durationMinutes) }
        assertEquals(past[0].startUnix - 3600, past[1].startUnix)
        // Every slot carries its own catch-up URL with duration=3600.
        past.forEach {
            assertTrue(it.catchupUrl.endsWith("?duration=3600&start=${it.startUnix}"))
        }
    }

    @Test
    fun `shorter programs use their own duration as the slot length`() {
        val now = System.currentTimeMillis()
        val epg = EpgProgram(
            startMs = now - 5 * 60_000L,
            endMs = now + 25 * 60_000L,        // 30-minute program
            title = "News"
        )
        val past = TimeMachineEngine.getRecentPrograms(
            "http://panel.tv:8080/live/u1/p1/12.m3u8", epg, maxHours = 2
        )
        assertEquals(2, past.size)
        past.forEach { assertEquals(30, it.durationMinutes) }
    }

    @Test
    fun `no epg means no programs`() {
        assertTrue(
            TimeMachineEngine.getRecentPrograms(
                "http://panel.tv:8080/live/u1/p1/12.m3u8", null, maxHours = 24
            ).isEmpty()
        )
    }

    @Test
    fun `slots stop at the max hours window`() {
        // Program started 1 minute ago, 1h slots, 5h window: the 5th slot
        // would start exactly AT the cutoff (strictly before it) → 4 slots.
        val now = System.currentTimeMillis()
        val epg = EpgProgram(
            startMs = now - 60_000L, endMs = now + 3_600_000L, title = "Long"
        )
        val past = TimeMachineEngine.getRecentPrograms(
            "http://panel.tv:8080/live/u1/p1/12.m3u8", epg, maxHours = 5
        )
        assertEquals(4, past.size)
        // A 24h window on the same program gives the full 24 slots minus
        // the one-minute head start → 23.
        val day = TimeMachineEngine.getRecentPrograms(
            "http://panel.tv:8080/live/u1/p1/12.m3u8", epg, maxHours = 24
        )
        assertEquals(23, day.size)
    }

    @Test
    fun `time ago label formats hours`() {
        val now = System.currentTimeMillis() / 1000
        val p = TimeMachineEngine.PastProgram(
            title = "t", description = "", startUnix = now - 7200, endUnix = now - 3600,
            durationMinutes = 60, catchupUrl = "u", channelName = "", channelLogo = ""
        )
        assertEquals("2h ago", p.timeAgoLabel)
    }
}

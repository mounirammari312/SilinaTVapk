package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.xtream.VodEpisode
import com.superz.iptvplayer.data.xtream.VodSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.4 — VodSidebarBuilder contract: the player's slide-out list
 * becomes THE RIGHT LIST for what's playing — every season's episodes
 * (in order, the current row preserved) while bingeing, and the DB's
 * movie rows (position numbers, the current row's URL preserved) during
 * a movie — plus the mode routing that decides which list that is.
 */
class VodSidebarBuilderTest {

    private fun ep(
        id: Long,
        season: Int,
        number: Int,
        title: String = "Ep $number",
        directSource: String? = null,
        stalkerCmd: String? = null
    ) = VodEpisode(
        id = id,
        season = season,
        episodeNumber = number,
        title = title,
        containerExtension = "mkv",
        plot = null,
        duration = null,
        thumbnail = null,
        directSource = directSource,
        stalkerCmd = stalkerCmd
    )

    private fun seasons(vararg pairs: Pair<Int, List<VodEpisode>>) =
        pairs.map { (num, eps) -> VodSeason(seasonNumber = num, episodes = eps, cover = null) }

    private fun xtreamUrl(id: Long) = "http://panel/series/u/p/$id.mkv"

    private val xtreamResolve: (VodEpisode) -> Pair<String?, String?> = { e ->
        (e.directSource ?: xtreamUrl(e.id)) to null
    }

    // ── mode routing ───────────────────────────────────────────────

    @Test
    fun `mode routing - episodes, movies, locals and live`() {
        assertEquals(VodSidebarBuilder.Mode.EPISODES, VodSidebarBuilder.modeFor("vode:55", localSession = false))
        assertEquals(VodSidebarBuilder.Mode.MOVIES, VodSidebarBuilder.modeFor("vodm:46170", localSession = false))
        assertEquals(VodSidebarBuilder.Mode.CHANNELS, VodSidebarBuilder.modeFor("vodm:local:2", localSession = false))
        assertEquals(VodSidebarBuilder.Mode.CHANNELS, VodSidebarBuilder.modeFor("k:123", localSession = false))
        assertEquals(VodSidebarBuilder.Mode.CHANNELS, VodSidebarBuilder.modeFor(null, localSession = false))
        assertEquals(VodSidebarBuilder.Mode.CHANNELS, VodSidebarBuilder.modeFor("vodm:local:0", localSession = true))
    }

    @Test
    fun `a local session never leaves CHANNELS even on a streamed key`() {
        // belt + braces: the localSession flag wins over the key family
        assertEquals(VodSidebarBuilder.Mode.CHANNELS, VodSidebarBuilder.modeFor("vodm:9", localSession = true))
    }

    // ── episode channels ───────────────────────────────────────────

    @Test
    fun `every season's episodes land in order with the registration's naming`() {
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 7L,
            seasons = seasons(
                1 to listOf(ep(11, 1, 1), ep(12, 1, 2)),
                2 to listOf(ep(21, 2, 1), ep(22, 2, 2))
            ),
            posterFallback = "poster.jpg",
            resolve = xtreamResolve,
            current = null
        )
        assertEquals(listOf("vode:11", "vode:12", "vode:21", "vode:22"), built.map { it.key })
        assertEquals(listOf(1, 2, 1, 2), built.map { it.num })
        assertEquals("S2E2 · Ep 2", built[3].name)
        assertEquals("poster.jpg", built[0].logo)   // thumbnail fallback = the show poster
        assertEquals(7L, built[0].playlistId)
    }

    @Test
    fun `episode thumbnails win over the poster fallback`() {
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(1 to listOf(ep(5, 1, 5).copy(thumbnail = "still.jpg"))),
            posterFallback = "poster.jpg",
            resolve = xtreamResolve,
            current = null
        )
        assertEquals("still.jpg", built[0].logo)
    }

    @Test
    fun `xtream resolve prefers directSource over the built url`() {
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(1 to listOf(ep(5, 1, 5, directSource = "http://cdn/direct.mp4"))),
            posterFallback = null,
            resolve = xtreamResolve,
            current = null
        )
        assertEquals("http://cdn/direct.mp4", built[0].directUrl)
        assertNull(built[0].stalkerCmd)
    }

    @Test
    fun `portal resolve carries the stalker cmd and no direct url`() {
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(1 to listOf(ep(5, 1, 5, stalkerCmd = "ffmpeg http://s/c"))),
            posterFallback = null,
            resolve = { e -> null to e.stalkerCmd },
            current = null
        )
        assertNull(built[0].directUrl)
        assertEquals("ffmpeg http://s/c", built[0].stalkerCmd)
    }

    @Test
    fun `the playing episode keeps its registered resolved row`() {
        val registered = Channel(
            playlistId = 1L,
            key = "vode:12",
            num = 2,
            name = "S1E2 · Ep 2",
            logo = "poster.jpg",
            categoryId = null,
            directUrl = "http://probed-best.mp4"      // the info page probed this
        )
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(1 to listOf(ep(11, 1, 1), ep(12, 1, 2))),
            posterFallback = "poster.jpg",
            resolve = xtreamResolve,
            current = registered
        )
        assertEquals("http://probed-best.mp4", built[1].directUrl)   // never swapped for the fresh row
        assertEquals("vode:12", built[1].key)
        assertEquals("vode:11", built[0].key)
    }

    @Test
    fun `duplicate episode ids keep the first occurrence`() {
        // defensive: a panel that repeats an id across seasons
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(
                1 to listOf(ep(50, 1, 1)),
                2 to listOf(ep(50, 2, 1))             // same id again
            ),
            posterFallback = null,
            resolve = xtreamResolve,
            current = null
        )
        assertEquals(1, built.size)
        assertEquals("S1E1 · Ep 1", built[0].name)
    }

    // ── movie channels ─────────────────────────────────────────────

    private fun movieRow(
        sid: Long?,
        name: String,
        key: String = "m:${sid ?: 0}",
        num: Int = sid?.toInt() ?: 0
    ) = Movie(
        playlistId = 3L,
        key = key,
        num = num,
        name = name,
        poster = "p$sid.jpg",
        categoryId = "12",
        streamId = sid,
        containerExtension = "mp4",
        stalkerRow = null
    )

    @Test
    fun `movie rows become position-numbered sidebar channels`() {
        val built = VodSidebarBuilder.buildMovieChannels(
            listOf(movieRow(9001, "Alpha"), movieRow(9002, "Beta")),
            current = null
        )
        assertEquals(listOf("vodm:9001", "vodm:9002"), built.map { it.key })
        assertEquals(listOf(1, 2), built.map { it.num })     // position, NOT the DB's global num
        assertEquals("Alpha", built[0].name)
        assertEquals("p9001.jpg", built[0].logo)
        assertEquals(3L, built[0].playlistId)
        assertNull(built[0].directUrl)                        // resolved on click
    }

    @Test
    fun `rows without a stream id are skipped`() {
        val built = VodSidebarBuilder.buildMovieChannels(
            listOf(movieRow(null, "Broken"), movieRow(42, "Fine")),
            current = null
        )
        assertEquals(listOf("vodm:42"), built.map { it.key })
    }

    @Test
    fun `the playing movie keeps its resolved url name and logo`() {
        val playing = Channel(
            playlistId = 3L,
            key = "vodm:9001",
            num = 1,
            name = "Alpha (2024)",
            logo = "hi-res.jpg",
            categoryId = "12",
            streamId = 9001,
            directUrl = "http://probed.mp4"
        )
        val built = VodSidebarBuilder.buildMovieChannels(
            listOf(movieRow(9001, "Alpha"), movieRow(9002, "Beta")),
            current = playing
        )
        assertEquals("http://probed.mp4", built[0].directUrl)
        assertEquals("Alpha (2024)", built[0].name)
        assertEquals("hi-res.jpg", built[0].logo)
        assertNull(built[1].directUrl)
        assertEquals("Beta", built[1].name)
    }

    @Test
    fun `an empty page builds an empty list`() {
        assertTrue(VodSidebarBuilder.buildMovieChannels(emptyList(), current = null).isEmpty())
    }

    @Test
    fun `keys are unique by construction`() {
        val built = VodSidebarBuilder.buildEpisodeChannels(
            playlistId = 1L,
            seasons = seasons(1 to listOf(ep(1, 1, 1), ep(2, 1, 2), ep(3, 1, 3))),
            posterFallback = null,
            resolve = xtreamResolve,
            current = null
        )
        assertEquals(built.size, built.map { it.key }.toSet().size)
    }
}

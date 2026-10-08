package com.superz.iptvplayer.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.18.2 — PlaybackPositionManager unit tests: the resume-watching
 * shelf behind Continue Watching + auto-resume (pure java.io.File —
 * no Android types, the SavedVideosContractTest pattern).
 */
class PlaybackPositionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = tmp.newFile("playback_positions.json")

    private fun movie(
        key: String = "vodm:123",
        pos: Long = 300_000L,
        dur: Long = 7_200_000L,
        ts: Long = 1_000L,
        title: String = "Dune",
        kind: String = PlaybackPositionManager.KIND_MOVIE,
        seriesId: Long? = null,
        path: String? = null
    ) = PlaybackPositionManager.PositionRecord(
        key = key,
        playlistId = 5L,
        title = title,
        posterUrl = "https://x/p.jpg",
        kind = kind,
        contentId = 123L,
        seriesId = seriesId,
        path = path,
        positionMs = pos,
        durationMs = dur,
        updatedAtMs = ts
    )

    // ── save / position / clear ─────────────────────────────────

    @Test
    fun `save then position round trip`() {
        val f = store()
        PlaybackPositionManager.save(f, movie(pos = 482_000L))
        assertEquals(482_000L, PlaybackPositionManager.position(f, "vodm:123"))
        assertEquals(0L, PlaybackPositionManager.position(f, "vodm:999"))
    }

    @Test
    fun `upsert overwrites the same key and merges an unset duration`() {
        val f = store()
        PlaybackPositionManager.save(f, movie(pos = 600_000L, dur = 6_000_000L))
        // a later save with no measured duration keeps the old one
        PlaybackPositionManager.save(f, movie(pos = 700_000L, dur = 0L))
        assertEquals(700_000L, PlaybackPositionManager.position(f, "vodm:123"))
        val shelf = PlaybackPositionManager.recent(f)
        assertEquals(1, shelf.size)
        assertEquals(6_000_000L, shelf.first().durationMs)
    }

    @Test
    fun `clear removes the record`() {
        val f = store()
        PlaybackPositionManager.save(f, movie())
        PlaybackPositionManager.clear(f, "vodm:123")
        assertEquals(0L, PlaybackPositionManager.position(f, "vodm:123"))
        assertTrue(PlaybackPositionManager.recent(f).isEmpty())
    }

    // ── recent shelf semantics ──────────────────────────────────

    @Test
    fun `recent sorts newest first and respects limit`() {
        val f = store()
        PlaybackPositionManager.save(f, movie(key = "vodm:1", ts = 100L))
        PlaybackPositionManager.save(f, movie(key = "vodm:2", ts = 300L))
        PlaybackPositionManager.save(f, movie(key = "vodm:3", ts = 200L))
        val shelf = PlaybackPositionManager.recent(f, limit = 2)
        assertEquals(listOf("vodm:2", "vodm:3"), shelf.map { it.key })
    }

    @Test
    fun `blips below min restore are not offered`() {
        val f = store()
        // 4s watched — below MIN_RESTORE_POSITION_MS (5s)
        PlaybackPositionManager.save(f, movie(pos = 4_000L))
        assertTrue(PlaybackPositionManager.recent(f).isEmpty())
        // …but still restorable? No: the record itself survives, the
        // SHELF hides it; re-watching past 5s brings it back.
        assertEquals(4_000L, PlaybackPositionManager.position(f, "vodm:123"))
    }

    @Test
    fun `watched past the clear fraction leaves the shelf`() {
        val f = store()
        val dur = 10_000_000L
        PlaybackPositionManager.save(f, movie(pos = dur / 2, dur = dur))
        assertEquals(1, PlaybackPositionManager.recent(f).size)
        PlaybackPositionManager.save(f, movie(pos = (dur * 0.96).toLong(), dur = dur))
        assertTrue(PlaybackPositionManager.recent(f).isEmpty())
    }

    @Test
    fun `records without duration still show on the shelf`() {
        val f = store()
        // duration unknown (exotic container) — a resume point is still useful
        PlaybackPositionManager.save(f, movie(pos = 90_000L, dur = 0L))
        assertEquals(1, PlaybackPositionManager.recent(f).size)
    }

    @Test
    fun `shelf caps at MAX_RECORDS keeping the newest`() {
        val f = store()
        repeat(35) { i ->
            PlaybackPositionManager.save(f, movie(key = "vodm:$i", ts = i.toLong()))
        }
        val shelf = PlaybackPositionManager.recent(f)
        assertEquals(PlaybackPositionManager.MAX_RECORDS, shelf.size)
        assertEquals("vodm:34", shelf.first().key)
        assertEquals("vodm:5", shelf.last().key)
    }

    // ── persistence / robustness ────────────────────────────────

    @Test
    fun `records survive a new store instance read`() {
        val f = store()
        PlaybackPositionManager.save(
            f,
            movie(
                key = "local:Oria_Dune_20261005_183012.mp4",
                kind = PlaybackPositionManager.KIND_LOCAL,
                seriesId = null,
                path = "/storage/emulated/0/Android/data/x/files/Downloads/a.mp4",
                title = "Dune (saved)"
            )
        )
        val shelf = PlaybackPositionManager.recent(f)
        val rec = shelf.first()
        assertEquals("local:Oria_Dune_20261005_183012.mp4", rec.key)
        assertEquals(PlaybackPositionManager.KIND_LOCAL, rec.kind)
        assertNull(rec.seriesId)
        assertEquals("/storage/emulated/0/Android/data/x/files/Downloads/a.mp4", rec.path)
        assertEquals("Dune (saved)", rec.title)
    }

    @Test
    fun `episode records keep the series id`() {
        val f = store()
        PlaybackPositionManager.save(
            f,
            movie(key = "vode:77", kind = PlaybackPositionManager.KIND_EPISODE, seriesId = 45L)
        )
        assertEquals(45L, PlaybackPositionManager.recent(f).first().seriesId)
    }

    @Test
    fun `corrupt store file degrades to an empty shelf`() {
        val f = store()
        f.writeText("{not json at all")
        assertTrue(PlaybackPositionManager.recent(f).isEmpty())
        assertEquals(0L, PlaybackPositionManager.position(f, "vodm:1"))
        // and the store recovers on the next save
        PlaybackPositionManager.save(f, movie())
        assertEquals(300_000L, PlaybackPositionManager.position(f, "vodm:123"))
    }

    @Test
    fun `missing store file is an empty shelf`() {
        val f = tmp.newFile("nope.json")
        f.delete()
        assertTrue(PlaybackPositionManager.recent(f).isEmpty())
    }
}

package com.superz.iptvplayer.data.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.12.4 — LIVE-DATA regression: the user's real portal (mag.max-cdn.com,
 * MAC 00:1A:79:B6:39:9A) responses captured 2026-10-04, replayed through our
 * EXACT wire models + parsers. If these pass, the data layer is proven
 * correct against ground truth and any empty-cards bug lives in the
 * DB/flow layer; if they fail, the parse itself is broken.
 *
 * Fixtures (src/test/resources/liveportal):
 *  • allch-slice.json — get_all_channels REDUCED to a representative slice
 *    (40 real archive rows + 160 real non-archive rows, seed-42 sampled
 *    from the full 13 409-row capture; the full 18 MB capture lives in the
 *    build diagnostics, scripts/catchup_v1124_diag.sh regenerates it).
 *  • today-p0/p1    — get_simple_data_table ch_id=368515 today: 38 total
 *                    items, compound file ids, mark_archive=1.
 *  • genres.json    — get_genres: 423 genres incl. 792 (archive channels').
 */
class LivePortalRegressionTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/liveportal/$name")!!
            .readBytes().decodeToString()

    @Test
    fun `live get_all_channels parses and yields the archive channels`() {
        val body = fixture("allch-slice.json")
        val channels = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }.decodeFromString<ChannelEnvelope>(body).js?.data.orEmpty()

        assertEquals(200, channels.size)
        val archive = channels.filter { it.tvArchiveFlag == 1 }
        assertEquals("live census said 40 archive rows in the slice", 40, archive.size)
        // Every archive channel must keep its genre + cmd for the DB rows.
        assertTrue(archive.all { it.genreId != null })
        assertTrue(archive.all { !it.cmd.isNullOrBlank() })
        // Sample channel from the live diagnosis.
        val sample = archive.first { it.idString == "368515" }
        assertEquals("792", sample.genreId)
        assertNotNull(sample.cmd)
    }

    @Test
    fun `live table page p1 parses with file ids and mark_archive`() {
        val (programs, total) = StalkerEpgParser.parseTablePage(fixture("today-p1.json"))
        assertEquals(38, total)
        assertEquals(10, programs.size)
        assertTrue(programs.all { it.markArchive })
        // The compound file id is the /media/<id>.ts input — MUST survive.
        val withFile = programs.filter { !it.fileId.isNullOrBlank() }
        assertTrue("expected compound file ids, got ${programs.map { it.fileId }}", withFile.isNotEmpty())
        assertTrue(withFile.all { it.fileId!!.contains('_') })
        // Time sanity: stop after start, titles present.
        assertTrue(programs.all { it.endMs > it.startMs })
        assertTrue(programs.all { it.title.isNotBlank() })
    }

    @Test
    fun `live table page p0 parses identically`() {
        val (programs, total) = StalkerEpgParser.parseTablePage(fixture("today-p0.json"))
        assertEquals(38, total)
        assertEquals(10, programs.size)
        assertTrue(programs.any { it.fileId?.contains('_') == true })
    }

    @Test
    fun `live genres parse`() {
        val genres = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }.decodeFromString<GenreEnvelope>(fixture("genres.json")).js.orEmpty()
        assertEquals(423, genres.size)
        assertTrue(genres.any { it.idString == "792" })
    }
}

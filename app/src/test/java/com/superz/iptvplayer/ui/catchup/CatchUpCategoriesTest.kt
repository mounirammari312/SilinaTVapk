package com.superz.iptvplayer.ui.catchup

import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.12.4 — the Catch-Up grid's category construction, locked to the
 * reference (CatchUpActivity.getCatchUpCategoryModels +
 * RealmController.getLiveCatchupChannelsByCategory + the live genre
 * payload's own `{"id":"*","title":"All"}` first row).
 */
class CatchUpCategoriesTest {

    private fun ch(key: String, categoryId: String?) = Channel(
        playlistId = 1L,
        key = key,
        num = 1,
        name = "Ch $key",
        categoryId = categoryId
    )

    // ── the "All (N)" card ────────────────────────────────────────

    @Test
    fun `all card counts archive channels outside xxx categories`() {
        val all = listOf(
            ch("a", "10"), ch("b", "10"), ch("c", "20"), ch("d", "999"), ch("e", null)
        )
        val card = CatchUpCategories.allCard(1L, all, xxxCategoryIds = setOf("999"))
        assertNotNull(card)
        assertEquals("All", card!!.first.name)
        assertEquals(CatchUpCategories.ALL_ID, card.first.categoryId)
        assertEquals(4, card.second)   // 999 excluded, null category passes
    }

    @Test
    fun `no all card when nothing visible - reference size 0 filter`() {
        val all = listOf(ch("a", "999"))
        assertNull(CatchUpCategories.allCard(1L, all, setOf("999")))
        assertNull(CatchUpCategories.allCard(1L, emptyList(), emptySet()))
    }

    @Test
    fun `all card with no xxx categories - plain count`() {
        val all = listOf(ch("a", "1"), ch("b", "2"))
        assertEquals(2, CatchUpCategories.allCard(1L, all, emptySet())!!.second)
    }

    // ── channelsFor (showCatchChannels) ──────────────────────────

    @Test
    fun `all selection excludes xxx only`() {
        val all = listOf(
            ch("a", "10"), ch("b", "999"), ch("c", null)
        )
        val out = CatchUpCategories.channelsFor(all, CatchUpCategories.ALL_ID, setOf("999"))
        assertEquals(listOf("a", "c"), out.map { it.key })
    }

    @Test
    fun `exact category selection - equalTo semantics`() {
        val all = listOf(ch("a", "10"), ch("b", "20"), ch("c", "10"))
        val out = CatchUpCategories.channelsFor(all, "10", emptySet())
        assertEquals(listOf("a", "c"), out.map { it.key })
    }

    @Test
    fun `null selection returns everything - grid state`() {
        val all = listOf(ch("a", "10"), ch("b", "999"))
        assertEquals(all, CatchUpCategories.channelsFor(all, null, setOf("999")))
    }

    // ── the v1.12.4 heal gate ─────────────────────────────────────

    @Test
    fun `heal when flags never landed`() {
        // 13409 channels, zero rows carry ANY tv_archive value → heal.
        assertTrue(
            CatchUpCategories.needsResync(
                channelCount = 13409, tvArchiveFlaggedRows = 0,
                archiveChannelCount = 0, categoryCount = 0
            )
        )
    }

    @Test
    fun `no heal when flags exist but portal has no archive channels`() {
        // A portal where every row is flagged 0 — genuinely empty catch-up.
        assertFalse(
            CatchUpCategories.needsResync(
                channelCount = 500, tvArchiveFlaggedRows = 500,
                archiveChannelCount = 0, categoryCount = 0
            )
        )
    }

    @Test
    fun `heal when archive channels exist but category rows are missing`() {
        // 316 archive channels landed but the genres/categories fetch once
        // failed → the JOIN is dead → the grid would show a bare empty
        // state forever. v1.12.4 heals this.
        assertTrue(
            CatchUpCategories.needsResync(
                channelCount = 13409, tvArchiveFlaggedRows = 13409,
                archiveChannelCount = 316, categoryCount = 0
            )
        )
    }

    @Test
    fun `no heal on a healthy state`() {
        assertFalse(
            CatchUpCategories.needsResync(
                channelCount = 13409, tvArchiveFlaggedRows = 13409,
                archiveChannelCount = 316, categoryCount = 12
            )
        )
        assertFalse(
            CatchUpCategories.needsResync(0, 0, 0, 0)
        )
    }

    // ── the assembled grid (card order) ──────────────────────────

    @Test
    fun `grid card order - all first then genres`() {
        val all = listOf(ch("a", "792"), ch("b", "792"), ch("c", "5"))
        val joinCats = listOf(
            Category(1L, "792", "BE TERUGKIJKEN") to 2,
            Category(1L, "5", "NL") to 1
        )
        val cats = listOfNotNull(CatchUpCategories.allCard(1L, all, emptySet())) + joinCats
        assertEquals(3, cats.size)
        assertEquals(CatchUpCategories.ALL_ID, cats.first().first.categoryId)
        assertEquals(3, cats.first().second)
        assertEquals("792", cats[1].first.categoryId)
        assertTrue(cats.drop(1).all { it.first.categoryId != CatchUpCategories.ALL_ID })
    }
}

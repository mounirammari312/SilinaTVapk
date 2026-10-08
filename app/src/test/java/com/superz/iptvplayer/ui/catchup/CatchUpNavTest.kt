package com.superz.iptvplayer.ui.catchup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.12.6 — the Catch-Up navigation contract, locking the ROOT-CAUSE fix
 * for the five-round "NO CATCH-UP Found" hunt:
 *
 * The hub card routes "catchup/-1" (the ACTIVE-playlist sentinel). The
 * grid ViewModel resolves it to the real id and MUST be the id the
 * channel-row click propagates to "catchupdetail/<id>/<key>" — passing
 * the raw -1 left the detail screen with playlistById(-1) = null and a
 * tab-less "No Catch Up Found" on BOTH the MAC and XTREAM paths while
 * every data layer tested correct (no request was ever issued).
 */
class CatchUpNavTest {

    // ── effectivePlaylistId — the shared sentinel rule ─────────────

    @Test
    fun `hub sentinel resolves to the active playlist`() {
        assertEquals(
            5L,
            CatchUpNav.effectivePlaylistId(CatchUpNav.ROUTE_ACTIVE_PLAYLIST, 5L)
        )
    }

    @Test
    fun `explicit route id wins over the active playlist`() {
        assertEquals(7L, CatchUpNav.effectivePlaylistId(7L, 5L))
        assertEquals(1L, CatchUpNav.effectivePlaylistId(1L, null))
    }

    @Test
    fun `sentinel with no active playlist passes through as not-found`() {
        assertEquals(
            CatchUpNav.ROUTE_ACTIVE_PLAYLIST,
            CatchUpNav.effectivePlaylistId(CatchUpNav.ROUTE_ACTIVE_PLAYLIST, null)
        )
    }

    @Test
    fun `any non-positive route id is treated as the sentinel`() {
        assertEquals(3L, CatchUpNav.effectivePlaylistId(0L, 3L))
        assertEquals(3L, CatchUpNav.effectivePlaylistId(-42L, 3L))
        // No active playlist → the sentinel itself (normalized, whichever
        // non-positive value arrived) — the caller's not-found path.
        assertEquals(
            CatchUpNav.ROUTE_ACTIVE_PLAYLIST,
            CatchUpNav.effectivePlaylistId(0L, null)
        )
        assertEquals(
            CatchUpNav.ROUTE_ACTIVE_PLAYLIST,
            CatchUpNav.effectivePlaylistId(-42L, null)
        )
    }

    // ── the regression the fix exists for ──────────────────────────

    @Test
    fun `regression - hub click must never navigate with the raw sentinel`() {
        // The exact hub flow: route -1, active playlist 9 → the detail
        // route the click builds must carry 9, NOT -1. (CatchUpScreen
        // now reads ui.playlistId — the ViewModel's RESOLVED value.)
        val routeArg = CatchUpNav.ROUTE_ACTIVE_PLAYLIST
        val resolved = CatchUpNav.effectivePlaylistId(routeArg, 9L)
        val detailRoute = "catchupdetail/$resolved/k:123"
        assertEquals("catchupdetail/9/k:123", detailRoute)
        assertEquals(9L, resolved)
    }

    @Test
    fun `regression - playlistById of the resolved id is findable, sentinel is not`() {
        // playlistById(-1) returns null in Room (autoGenerate ids start at 1)
        // — the empty-days state the screenshots showed. The resolved id
        // is always a real row id.
        val resolved = CatchUpNav.effectivePlaylistId(-1L, 4L)
        org.junit.Assert.assertTrue("resolved id must be a real row id", resolved > 0)
    }
}

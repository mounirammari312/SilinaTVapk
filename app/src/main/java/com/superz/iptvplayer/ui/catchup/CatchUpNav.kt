package com.superz.iptvplayer.ui.catchup

/**
 * v1.12.6 — the Catch-Up navigation contract, LOCKED after the root-cause
 * hunt of v1.12.0–v1.12.5 ("NO CATCH-UP Found" on every build):
 *
 * The hub's ly_catch_up card routes `"catchup/-1"` — the -1 sentinel means
 * "the ACTIVE playlist" (there is no playlist context on the hub). The
 * Catch-Up grid ViewModel resolves -1 → the real playlist id via
 * `activePlaylist()` and stores it in its UiState. The detail screen is
 * keyed by a REAL playlist id + channel key.
 *
 * The bug that survived five fix rounds: `CatchUpScreen`'s channel-row click
 * re-propagated the RAW ROUTE ARG (-1) instead of the resolved id →
 * `catchupdetail/-1/<key>` → `playlistById(-1)` = null → days = ∅ →
 * "No Catch Up Found" on BOTH the MAC and XTREAM paths — while every data
 * layer (handshake, day tables, parsers, token renewal) tested correct,
 * because the detail screen never issued a single request.
 *
 * [effectivePlaylistId] is the shared resolution rule (pure, unit-tested):
 * an explicit route id wins; the sentinel (or any non-positive value)
 * resolves to the active playlist; with no active playlist the sentinel
 * passes through and the caller renders its not-found state.
 */
object CatchUpNav {

    /** Any route id ≤ 0 means "resolve to the ACTIVE playlist". */
    const val ROUTE_ACTIVE_PLAYLIST: Long = -1L

    /**
     * The playlist a Catch-Up screen must actually use.
     *
     * @param routePlaylistId the route arg (-1 = active-playlist sentinel)
     * @param activePlaylistId the DB's active playlist id (null when none)
     * @return the effective id — still non-positive only when there is
     *         genuinely no playlist (the caller's not-found path)
     */
    fun effectivePlaylistId(
        routePlaylistId: Long,
        activePlaylistId: Long?
    ): Long =
        if (routePlaylistId > 0) routePlaylistId
        else activePlaylistId ?: ROUTE_ACTIVE_PLAYLIST
}

package com.agon.app.ui.focus

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.agon.app.data.model.StreamItem

/**
 * SessionDataPagingSource — In-memory PagingSource backed by SessionData lists.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  PURPOSE
 * ════════════════════════════════════════════════════════════════════════
 *  The DashboardActivity's main grid needs to display 22k+ channels without
 *  loading the entire list into RAM at once. Paging 3 is the right tool for
 *  the job, but the previous implementation backed the Pager with Room's
 *  PagingSource — which only works when the data has been written to Room.
 *
 *  The problem: HubActivity loads data via [PlaylistRepository.loadFastCache]
 *  (CSV cache) or [PlaylistRepository.parseM3U] (network), both of which
 *  populate [com.agon.app.data.model.SessionData] but DON'T reliably write
 *  to Room. When the DashboardActivity's Pager queries Room, the `channels`
 *  table is empty → the grid shows nothing even though SessionData has the
 *  full 22k list.
 *
 *  This custom [PagingSource] solves the problem by paging DIRECTLY over
 *  the in-memory [SessionData] lists. It accepts a pre-filtered snapshot
 *  (the caller filters by group + search query in Kotlin before creating
 *  the source) and slices it into pages of [PAGE_SIZE] items.
 *
 *  PERFORMANCE:
 *    - Filtering happens ONCE per source creation (in Kotlin, on
 *      Dispatchers.Default via the LaunchedEffect that builds the snapshot).
 *    - Paging is O(1) per page — just a subList() call.
 *    - The source is invalidated whenever the underlying filter changes,
 *      so the Pager automatically rebuilds it.
 *
 *  MEMORY:
 *    - Only the visible page (~40 items) is held by Compose at any time.
 *    - The filtered snapshot is a List reference (not a copy), so no
 *      extra memory is allocated beyond what SessionData already holds.
 * ════════════════════════════════════════════════════════════════════════
 */
class SessionDataPagingSource(
    private val items: List<StreamItem>
) : PagingSource<Int, StreamItem>() {

    companion object {
        /** Number of items per page — matches the DashboardActivity grid's page size. */
        const val PAGE_SIZE = 40

        /** Distance ahead of the last visible item at which to prefetch the next page. */
        const val PREFETCH_DISTANCE = 20
    }

    /**
     * Called by the Pager to load a page of items.
     *
     * @param params Contains the `key` (the starting index) and `loadSize`
     *               (how many items to load — usually PAGE_SIZE, but the
     *               first load may request 2x PAGE_SIZE for initial fill).
     * @return A [LoadResult.Page] containing the sliced items + the prev/next keys.
     */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, StreamItem> {
        return try {
            // The starting index for this page. On the first load, params.key
            // is null → start at 0.
            val start = params.key ?: 0

            // The number of items to load. The Pager may request more than
            // PAGE_SIZE on the initial load (initialLoadSize) — we honor it.
            val loadSize = params.loadSize

            // Clamp the end index to the list size so we don't go out of bounds.
            val end = (start + loadSize).coerceAtMost(items.size)

            // Slice the items list — this is O(loadSize), independent of the
            // total list size.
            val pageItems = if (start < items.size) {
                items.subList(start, end)
            } else {
                emptyList()
            }

            // If the list is empty, signal "end of pagination" immediately by
            // returning null prev/next keys.
            if (items.isEmpty()) {
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null
                )
            }

            // Calculate the next key — null if we've reached the end.
            val nextKey = if (end < items.size) end else null

            // Calculate the prev key — null if we're at the start.
            // We subtract loadSize (not PAGE_SIZE) so the previous page
            // matches the size that was actually loaded.
            val prevKey = if (start > 0) (start - loadSize).coerceAtLeast(0) else null

            LoadResult.Page(
                data = pageItems,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            // Any exception (e.g. ConcurrentModificationException if the
            // underlying list is mutated during the slice) is reported as
            // a LoadResult.Error so the Pager can retry.
            LoadResult.Error(e)
        }
    }

    /**
     * Called by the Pager to determine the initial load key after a refresh.
     * We return the key closest to the last accessed index (via
     * [state.anchorPosition]) so the grid doesn't jump back to position 0
     * when the source is invalidated (e.g. when the user types in the
     * search bar).
     */
    override fun getRefreshKey(state: PagingState<Int, StreamItem>): Int? {
        // Find the index closest to the anchor position, rounded down to the
        // nearest page boundary. If the anchor is null (first load), return
        // null so the Pager starts at 0.
        return state.anchorPosition?.let { anchor ->
            // Round down to the nearest PAGE_SIZE boundary so the refresh
            // reloads a full page starting at a clean boundary.
            (anchor / PAGE_SIZE) * PAGE_SIZE
        }
    }
}

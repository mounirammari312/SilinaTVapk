package com.superz.iptvplayer.ui.catchup

import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel

/**
 * v1.12.4 — the Catch-Up grid's category-list construction, extracted as
 * PURE logic so the exact contract is unit-testable.
 *
 * THE REFERENCE (CatchUpActivity.getCatchUpCategoryModels + RealmController
 * .getLiveCatchupChannelsByCategory):
 * ```java
 * for (CategoryModel categoryModel : GioTVApp.live_categories_filter) {
 *     int size = RealmController.with()
 *         .getLiveCatchupChannelsByCategory(categoryModel).size();
 *     if (size > 0) { /* card "Name (count)" */ }
 * }
 * // getLiveCatchupChannelsByCategory:
 * //   all_id  → notEqualTo(category_id, xxx_id) + tv_archive = 1
 * //   fav_id  → is_favorite + tv_archive = 1
 * //   other   → equalTo(category_id, id) + tv_archive = 1
 * ```
 * `GioTVApp.live_categories_filter` is the portal's OWN genre list, and the
 * LIVE genre payload's first row is `{"id": "*", "title": "All"}` — so the
 * reference's catch-up grid ALWAYS carries an "All (N)" card first (N = the
 * archive channels outside the xxx category), then one card per genre that
 * owns archive channels. Our DB JOIN (catchupCategoriesFor) drops the "*"
 * row (no channel carries genre "*"), so v1.12.4 re-adds it here — the
 * reference's card list, exactly.
 */
object CatchUpCategories {

    /** The portal's own "All" genre id (get_genres' first row: id "*"). */
    const val ALL_ID = "*"

    /**
     * The "All" card, or null when there is nothing to show (the reference's
     * `if (size > 0)` filter). Count = archive channels whose category is NOT
     * an xxx category — `notEqualTo(category_id, xxx_id)`; channels with a
     * null category pass, exactly like Realm's null semantics.
     */
    fun allCard(
        playlistId: Long,
        archiveChannels: List<Channel>,
        xxxCategoryIds: Set<String>
    ): Pair<Category, Int>? {
        val visible = archiveChannels.count { it.categoryId !in xxxCategoryIds }
        if (visible <= 0) return null
        return Category(playlistId = playlistId, categoryId = ALL_ID, name = "All") to visible
    }

    /**
     * The channel list for a category selection (showCatchChannels →
     * getLiveCatchupChannelsByCategory):
     *  • [ALL_ID] → every archive channel outside the xxx categories;
     *  • anything else → the exact category's archive channels.
     */
    fun channelsFor(
        all: List<Channel>,
        categoryId: String?,
        xxxCategoryIds: Set<String>
    ): List<Channel> = when (categoryId) {
        null -> all
        ALL_ID -> all.filter { it.categoryId !in xxxCategoryIds }
        else -> all.filter { it.categoryId == categoryId }
    }

    /**
     * v1.12.4 — the re-sync heal gate (the daily app-start sync makes this
     * belt-and-braces, but the screen still self-heals):
     *  • flags never landed — zero rows carry ANY tv_archive value while the
     *    playlist HAS channels (synced by a pre-v1.12.0 build, or a best-
     *    effort sync that died before persist());
     *  • category rows missing — archive channels exist but the categories
     *    JOIN produced nothing (a best-effort genres/categories fetch once
     *    failed → empty categories table → dead grid forever without this).
     */
    fun needsResync(
        channelCount: Int,
        tvArchiveFlaggedRows: Int,
        archiveChannelCount: Int,
        categoryCount: Int
    ): Boolean =
        (tvArchiveFlaggedRows == 0 && channelCount > 0) ||
            (archiveChannelCount > 0 && categoryCount == 0)
}

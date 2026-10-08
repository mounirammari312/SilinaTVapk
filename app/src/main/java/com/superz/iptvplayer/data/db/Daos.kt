package com.superz.iptvplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class CategoryWithCount(
    val categoryId: String,
    val name: String,
    val count: Int
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY isActive DESC, createdAt DESC")
    fun playlistsFlow(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY isActive DESC, createdAt DESC")
    suspend fun playlistsOnce(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun activePlaylistFlow(): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    suspend fun activePlaylist(): Playlist?

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun playlistById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: Playlist): Long

    @Query("UPDATE playlists SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE playlists SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Transaction
    suspend fun activateExclusive(id: Long) {
        deactivateAll()
        activate(id)
    }

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE type = :type AND server = :server AND username = :username LIMIT 1")
    suspend fun findXtream(server: String, username: String, type: String = "XTREAM"): Playlist?

    // v1.5.0 — the reference's getCheckPlaylistName duplicate guard for
    // portal / browser accounts ("This Name is exist in your playlists.").
    @Query("SELECT COUNT(*) FROM playlists WHERE name = :name AND id != :excludeId")
    suspend fun countByName(name: String, excludeId: Long = -1L): Int

    @Query("UPDATE playlists SET channelCount = :count WHERE id = :id")
    suspend fun updateChannelCount(id: Long, count: Int)

    /** v1.6.0 — stamp a successful sync (the "Last Update" source). */
    @Query("UPDATE playlists SET lastSyncAt = :epochSeconds WHERE id = :id")
    suspend fun markSynced(id: Long, epochSeconds: Long)

    @Query("UPDATE playlists SET activeConnections = :active, maxConnections = :max, expiryDate = :expiry WHERE id = :id")
    suspend fun updateAccountInfo(id: Long, active: Int?, max: Int?, expiry: Long?)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}

@Dao
interface ChannelDao {

    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        """
    )
    fun channelsFlow(pid: Long, catId: String?, q: String): Flow<List<Channel>>

    // ── v1.19.7 — HUGE-LIST PAGING (one-shot; see VodDao.moviesPage) ──

    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun channelsPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<Channel>

    @Query(
        """
        SELECT COUNT(*) FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        """
    )
    suspend fun channelsCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT c.* FROM channels c
        INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channel_key
        WHERE c.playlistId = :pid
          AND (:catId IS NULL OR c.categoryId = :catId)
          AND (:q = '' OR c.name LIKE '%' || :q || '%')
        ORDER BY c.num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun favoriteChannelsPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<Channel>

    @Query(
        """
        SELECT COUNT(*) FROM channels c
        INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channel_key
        WHERE c.playlistId = :pid
          AND (:catId IS NULL OR c.categoryId = :catId)
          AND (:q = '' OR c.name LIKE '%' || :q || '%')
        """
    )
    suspend fun favoriteChannelsCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT c.* FROM channels c
        INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channel_key
        WHERE c.playlistId = :pid
          AND (:catId IS NULL OR c.categoryId = :catId)
          AND (:q = '' OR c.name LIKE '%' || :q || '%')
        ORDER BY c.num
        """
    )
    fun favoritesFlow(pid: Long, catId: String?, q: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND channel_key = :key LIMIT 1")
    suspend fun channelByKey(pid: Long, key: String): Channel?

    /** v1.12.0 — the Catch-Up screen's channel list (the reference's
     *  RealmController.getLiveCatchupChannelsByCategory — tv_archive = 1). */
    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :pid AND tv_archive = 1
        ORDER BY num
        """
    )
    fun catchupChannelsFlow(pid: Long): Flow<List<Channel>>

    /** v1.12.4 — how many rows carry ANY tv_archive value (0 or 1). Zero on
     *  a playlist that HAS channels means the flags never landed (synced by
     *  a pre-v1.12.0 build / a failed best-effort genres run) — the
     *  Catch-Up screen's re-sync heal gate. */
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :pid AND tv_archive IS NOT NULL")
    suspend fun tvArchiveFlaggedCount(pid: Long): Int

    /** v1.12.0 — the "All" list with the parental exclusion (the reference's
     *  getLiveChannelsByCategory(all): notEqualTo(category_id, xxx_id)).
     *  Pass a non-empty placeholder list ("-1") to keep every category. */
    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
          AND (:includeAll OR (categoryId IS NULL OR categoryId NOT IN (:excludedCats)))
        ORDER BY num
        """
    )
    fun channelsFlowExcluding(
        pid: Long,
        catId: String?,
        q: String,
        includeAll: Boolean,
        excludedCats: List<String>
    ): Flow<List<Channel>>

    // ── v1.19.7 — HUGE-LIST PAGING: the parental "All" variant ──

    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
          AND (:includeAll OR (categoryId IS NULL OR categoryId NOT IN (:excludedCats)))
        ORDER BY num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun channelsPageExcluding(
        pid: Long,
        catId: String?,
        q: String,
        includeAll: Boolean,
        excludedCats: List<String>,
        limit: Int,
        offset: Int
    ): List<Channel>

    @Query(
        """
        SELECT COUNT(*) FROM channels
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
          AND (:includeAll OR (categoryId IS NULL OR categoryId NOT IN (:excludedCats)))
        """
    )
    suspend fun channelsCountExcluding(
        pid: Long,
        catId: String?,
        q: String,
        includeAll: Boolean,
        excludedCats: List<String>
    ): Int

    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY num")
    suspend fun channelsFor(pid: Long): List<Channel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<Category>)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun clearChannels(pid: Long)

    @Query("DELETE FROM categories WHERE playlistId = :pid")
    suspend fun clearCategories(pid: Long)

    @Query(
        """
        SELECT cat.categoryId AS categoryId, cat.name AS name, COUNT(ch.id) AS count
        FROM categories cat
        LEFT JOIN channels ch ON ch.playlistId = cat.playlistId AND ch.categoryId = cat.categoryId
        WHERE cat.playlistId = :pid
        GROUP BY cat.categoryId, cat.name
        ORDER BY CASE WHEN cat.name = '' THEN 1 ELSE 0 END, cat.name COLLATE NOCASE
        """
    )
    fun categoriesWithCountFlow(pid: Long): Flow<List<CategoryWithCount>>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :pid")
    suspend fun countFor(pid: Long): Int

    /** v1.12.0 — the Catch-Up screen's categories: only those owning at
     *  least one tv-archive channel, with that channel count (the
     *  reference's getCatchUpCategoryModels over
     *  getLiveCatchupChannelsByCategory). */
    @Query(
        """
        SELECT cat.categoryId AS categoryId, cat.name AS name, COUNT(ch.id) AS count
        FROM categories cat
        INNER JOIN channels ch ON ch.playlistId = cat.playlistId
            AND ch.categoryId = cat.categoryId AND ch.tv_archive = 1
        WHERE cat.playlistId = :pid
        GROUP BY cat.categoryId, cat.name
        ORDER BY CASE WHEN cat.name = '' THEN 1 ELSE 0 END, cat.name COLLATE NOCASE
        """
    )
    suspend fun catchupCategoriesFor(pid: Long): List<CategoryWithCount>

    /** v1.12.0 — parental: the adult category ids by NAME (the reference's
     *  isXXX name match, evaluated in SQL — "xxx"/"adult"/"porn", any case). */
    @Query(
        """
        SELECT categoryId FROM categories
        WHERE playlistId = :pid
          AND (instr(LOWER(name), 'xxx') > 0 OR instr(LOWER(name), 'adult') > 0
               OR instr(LOWER(name), 'porn') > 0)
        """
    )
    suspend fun xxxCategoryIds(pid: Long): List<String>

    /** v1.12.5 — the xxx CANDIDATE rows (id + name): the ONE excluded id
     *  (the reference's Constants.xxx_category_id — LAST name match wins)
     *  is picked in memory by ParentalControl.xxxExcludedId, because the
     *  "porn" token applies to Xtream categories only, stalker genres
     *  match "xxx"/"adult" (BaseActivity.getLiveGenre vs getLiveCategory). */
    @Query(
        """
        SELECT * FROM categories
        WHERE playlistId = :pid
          AND (instr(LOWER(name), 'xxx') > 0 OR instr(LOWER(name), 'adult') > 0
               OR instr(LOWER(name), 'porn') > 0)
        ORDER BY name COLLATE NOCASE
        """
    )
    suspend fun xxxCandidateCategories(pid: Long): List<Category>
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE playlistId = :pid AND channelKey = :key")
    suspend fun remove(pid: Long, key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE playlistId = :pid AND channelKey = :key)")
    suspend fun isFavorite(pid: Long, key: String): Boolean

    @Query("SELECT channelKey FROM favorites WHERE playlistId = :pid")
    fun favoriteKeysFlow(pid: Long): Flow<List<String>>

    @Query("DELETE FROM favorites WHERE playlistId = :pid")
    suspend fun clearFor(pid: Long)
}

@Dao
interface EngineMemoryDao {

    @Query("SELECT * FROM engine_memory WHERE playlistId = :pid")
    suspend fun memoryForPlaylist(pid: Long): List<EngineMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(memory: EngineMemory)

    @Query("DELETE FROM engine_memory WHERE playlistId = :pid")
    suspend fun clearFor(pid: Long)
}

// ─────────────────────────────────────────────────────────────────
// v1.4.0 — VOD data access (movies, series, their categories and
// favorites). Mirrors ChannelDao's reactive Flow patterns so the
// browse tabs reuse the same instant-update architecture.
// ─────────────────────────────────────────────────────────────────
@Dao
interface VodDao {

    // ── Movies ──

    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        """
    )
    fun moviesFlow(pid: Long, catId: String?, q: String): Flow<List<Movie>>

    // ── v1.19.7 — HUGE-LIST PAGING (one-shot, invalidation-immune) ──
    // Full-list Flow queries materialize EVERY row (100k+ rows → 30-50MB
    // + main-thread delivery jank + re-emission on every Room write).
    // These one-shot pages load LIMIT/OFFSET slices; combined with the
    // (playlistId, num) index the ORDER BY streams straight off the index.

    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun moviesPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<Movie>

    @Query(
        """
        SELECT COUNT(*) FROM movies
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        """
    )
    suspend fun moviesCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT m.* FROM movies m
        INNER JOIN vod_favorites f
          ON f.playlistId = m.playlistId AND f.contentKey = m.movie_key AND f.contentType = 'MOVIE'
        WHERE m.playlistId = :pid
          AND (:catId IS NULL OR m.categoryId = :catId)
          AND (:q = '' OR m.name LIKE '%' || :q || '%')
        ORDER BY m.num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun favoriteMoviesPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<Movie>

    @Query(
        """
        SELECT COUNT(*) FROM movies m
        INNER JOIN vod_favorites f
          ON f.playlistId = m.playlistId AND f.contentKey = m.movie_key AND f.contentType = 'MOVIE'
        WHERE m.playlistId = :pid
          AND (:catId IS NULL OR m.categoryId = :catId)
          AND (:q = '' OR m.name LIKE '%' || :q || '%')
        """
    )
    suspend fun favoriteMoviesCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT m.* FROM movies m
        INNER JOIN vod_favorites f
          ON f.playlistId = m.playlistId AND f.contentKey = m.movie_key AND f.contentType = 'MOVIE'
        WHERE m.playlistId = :pid
          AND (:catId IS NULL OR m.categoryId = :catId)
          AND (:q = '' OR m.name LIKE '%' || :q || '%')
        ORDER BY m.num
        """
    )
    fun favoriteMoviesFlow(pid: Long, catId: String?, q: String): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE playlistId = :pid AND streamId = :streamId LIMIT 1")
    suspend fun movieByStreamId(pid: Long, streamId: Long): Movie?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<Movie>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodCategories(categories: List<VodCategory>)

    @Query("DELETE FROM movies WHERE playlistId = :pid")
    suspend fun clearMovies(pid: Long)

    @Query("DELETE FROM vod_categories WHERE playlistId = :pid")
    suspend fun clearVodCategories(pid: Long)

    @Query(
        """
        SELECT cat.categoryId AS categoryId, cat.name AS name, COUNT(m.id) AS count
        FROM vod_categories cat
        LEFT JOIN movies m ON m.playlistId = cat.playlistId AND m.categoryId = cat.categoryId
        WHERE cat.playlistId = :pid
        GROUP BY cat.categoryId, cat.name
        ORDER BY CASE WHEN cat.name = '' THEN 1 ELSE 0 END, cat.name COLLATE NOCASE
        """
    )
    fun vodCategoriesWithCountFlow(pid: Long): Flow<List<CategoryWithCount>>

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :pid")
    suspend fun countMovies(pid: Long): Int

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :pid")
    fun countMoviesFlow(pid: Long): Flow<Int>

    // ── Series ──

    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        """
    )
    fun seriesFlow(pid: Long, catId: String?, q: String): Flow<List<SeriesShow>>

    // ── v1.19.7 — HUGE-LIST PAGING (see moviesPage above) ──

    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        ORDER BY num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun seriesPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<SeriesShow>

    @Query(
        """
        SELECT COUNT(*) FROM series
        WHERE playlistId = :pid
          AND (:catId IS NULL OR categoryId = :catId)
          AND (:q = '' OR name LIKE '%' || :q || '%')
        """
    )
    suspend fun seriesCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT s.* FROM series s
        INNER JOIN vod_favorites f
          ON f.playlistId = s.playlistId AND f.contentKey = s.series_key AND f.contentType = 'SERIES'
        WHERE s.playlistId = :pid
          AND (:catId IS NULL OR s.categoryId = :catId)
          AND (:q = '' OR s.name LIKE '%' || :q || '%')
        ORDER BY s.num
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun favoriteSeriesPage(pid: Long, catId: String?, q: String, limit: Int, offset: Int): List<SeriesShow>

    @Query(
        """
        SELECT COUNT(*) FROM series s
        INNER JOIN vod_favorites f
          ON f.playlistId = s.playlistId AND f.contentKey = s.series_key AND f.contentType = 'SERIES'
        WHERE s.playlistId = :pid
          AND (:catId IS NULL OR s.categoryId = :catId)
          AND (:q = '' OR s.name LIKE '%' || :q || '%')
        """
    )
    suspend fun favoriteSeriesCount(pid: Long, catId: String?, q: String): Int

    @Query(
        """
        SELECT s.* FROM series s
        INNER JOIN vod_favorites f
          ON f.playlistId = s.playlistId AND f.contentKey = s.series_key AND f.contentType = 'SERIES'
        WHERE s.playlistId = :pid
          AND (:catId IS NULL OR s.categoryId = :catId)
          AND (:q = '' OR s.name LIKE '%' || :q || '%')
        ORDER BY s.num
        """
    )
    fun favoriteSeriesFlow(pid: Long, catId: String?, q: String): Flow<List<SeriesShow>>

    // v1.4.1 — the series_key fallback serves rows synced by v1.4.0, where
    // seriesId was never persisted (NULL): the key "sr:{id}" still carries
    // the real Xtream id, so info pages keep working WITHOUT a resync.
    @Query("SELECT * FROM series WHERE playlistId = :pid AND (seriesId = :seriesId OR series_key = 'sr:' || :seriesId) LIMIT 1")
    suspend fun seriesBySeriesId(pid: Long, seriesId: Long): SeriesShow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(shows: List<SeriesShow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesCategories(categories: List<SeriesCategory>)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun clearSeries(pid: Long)

    @Query("DELETE FROM series_categories WHERE playlistId = :pid")
    suspend fun clearSeriesCategories(pid: Long)

    @Query(
        """
        SELECT cat.categoryId AS categoryId, cat.name AS name, COUNT(s.id) AS count
        FROM series_categories cat
        LEFT JOIN series s ON s.playlistId = cat.playlistId AND s.categoryId = cat.categoryId
        WHERE cat.playlistId = :pid
        GROUP BY cat.categoryId, cat.name
        ORDER BY CASE WHEN cat.name = '' THEN 1 ELSE 0 END, cat.name COLLATE NOCASE
        """
    )
    fun seriesCategoriesWithCountFlow(pid: Long): Flow<List<CategoryWithCount>>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    suspend fun countSeries(pid: Long): Int

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    fun countSeriesFlow(pid: Long): Flow<Int>

    // ── VOD favorites ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addVodFavorite(favorite: VodFavorite)

    @Query("DELETE FROM vod_favorites WHERE playlistId = :pid AND contentKey = :key")
    suspend fun removeVodFavorite(pid: Long, key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM vod_favorites WHERE playlistId = :pid AND contentKey = :key)")
    suspend fun isVodFavorite(pid: Long, key: String): Boolean

    @Query("SELECT contentKey FROM vod_favorites WHERE playlistId = :pid")
    fun vodFavoriteKeysFlow(pid: Long): Flow<List<String>>

    @Query("DELETE FROM vod_favorites WHERE playlistId = :pid")
    suspend fun clearVodFavorites(pid: Long)
}

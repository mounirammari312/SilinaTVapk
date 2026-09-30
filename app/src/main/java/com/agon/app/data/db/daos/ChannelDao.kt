package com.agon.app.data.db.daos

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agon.app.data.db.entities.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind ORDER BY name COLLATE NOCASE ASC")
    fun observeByKind(playlistId: String, kind: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByKind(playlistId: String, kind: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(playlistId: String): List<ChannelEntity>

    /**
     * Paged reads — used by Jetpack Compose LazyColumn to avoid loading
     * 22k+ channels into memory at once. Backed by the (playlistId, kind)
     * index, so the LIMIT/OFFSET cost stays O(log n + page_size).
     */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind ORDER BY name COLLATE NOCASE ASC")
    fun pagedByKind(playlistId: String, kind: String): PagingSource<Int, ChannelEntity>

    /**
     * Paged + filtered reads — same as [pagedByKind] but with a free-text
     * query applied to the channel name and group columns. Used by the
     * DashboardActivity search bar so the UI never loads the full 22k+
     * list into memory — each page is fetched lazily as the user scrolls.
     *
     * Backed by the (playlistId, name) index + a CASE-INSENSITIVE LIKE.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :playlistId
          AND kind = :kind
          AND (name LIKE '%' || :query || '%' COLLATE NOCASE
               OR `group` LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun pagedSearch(playlistId: String, kind: String, query: String): PagingSource<Int, ChannelEntity>

    /**
     * Paged + group-filtered reads — same as [pagedByKind] but restricted
     * to a single category group. Used when the user picks a group from
     * the side rail so only matching channels are paged in.
     */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind AND `group` = :group ORDER BY name COLLATE NOCASE ASC")
    fun pagedByGroup(playlistId: String, kind: String, group: String): PagingSource<Int, ChannelEntity>

    /**
     * Paged + group-filtered + search — combines both filters for the
     * DashboardActivity's main grid. Used when the user has BOTH a
     * search query AND a group selected.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :playlistId
          AND kind = :kind
          AND `group` = :group
          AND (name LIKE '%' || :query || '%' COLLATE NOCASE
               OR `group` LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun pagedByGroupAndSearch(playlistId: String, kind: String, group: String, query: String): PagingSource<Int, ChannelEntity>

    /**
     * Filter channel list by a free-text query. Uses the (playlistId, name)
     * index for a fast prefix scan, then a CASE-INSENSITIVE LIKE on the rest.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE playlistId = :playlistId
          AND kind = :kind
          AND (name LIKE '%' || :query || '%' COLLATE NOCASE
               OR `group` LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun search(playlistId: String, kind: String, query: String): Flow<List<ChannelEntity>>

    @Query("SELECT DISTINCT `group` FROM channels WHERE playlistId = :playlistId AND kind = :kind AND `group` != '' ORDER BY `group` COLLATE NOCASE ASC")
    suspend fun groups(playlistId: String, kind: String): List<String>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun count(playlistId: String, kind: String): Int

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteByKind(playlistId: String, kind: String)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)

    @Query("DELETE FROM channels")
    suspend fun clear()

    /**
     * Atomic replace: wipe the previous (playlistId, kind) slice and insert
     * the fresh batch in a single transaction. This is what makes
     * `PlaylistRepository.fetchXtreamStreams()` safe to re-run on refresh.
     */
    @Transaction
    suspend fun replaceKindSlice(playlistId: String, kind: String, channels: List<ChannelEntity>) {
        deleteByKind(playlistId, kind)
        insertAll(channels)
    }
}

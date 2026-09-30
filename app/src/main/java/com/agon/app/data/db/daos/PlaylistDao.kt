package com.agon.app.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agon.app.data.model.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(playlists: List<Playlist>)

    @Query("SELECT * FROM playlists ORDER BY lastAccessed DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Playlist?

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): Playlist?

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<Playlist?>

    /**
     * Atomic toggle: deactivate any previously-active row and activate the
     * selected one in a single UPDATE.
     *
     * NOTE: Room stores the boolean `isActive` field of [Playlist] as an
     * INTEGER 0/1 column. We can't pass a Kotlin Boolean in the SET clause,
     * so we use a CASE expression instead.
     */
    @Query("UPDATE playlists SET isActive = CASE WHEN id = :activeId THEN 1 ELSE 0 END, lastAccessed = :now WHERE id = :activeId OR isActive = 1")
    suspend fun setActive(activeId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET lastAccessed = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM playlists")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(playlists: List<Playlist>) {
        clear()
        upsertAll(playlists)
    }
}

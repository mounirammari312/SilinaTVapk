package com.agon.app.data.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agon.app.data.db.entities.MatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(match: MatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(matches: List<MatchEntity>)

    @Query("SELECT * FROM matches ORDER BY kickoffEpochMs ASC")
    fun observeAll(): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches ORDER BY kickoffEpochMs ASC")
    suspend fun getAll(): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE status = :status ORDER BY kickoffEpochMs ASC")
    suspend fun getByStatus(status: String): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE matchedChannelRowId != -1 ORDER BY kickoffEpochMs ASC")
    suspend fun getMatched(): List<MatchEntity>

    @Query("SELECT COUNT(*) FROM matches")
    suspend fun count(): Int

    /**
     * Evict matches whose kickoff + lifetime window is in the past.
     * @param cutoffEpochMs  Anything older than this gets deleted.
     */
    @Query("DELETE FROM matches WHERE kickoffEpochMs != 0 AND kickoffEpochMs < :cutoffEpochMs")
    suspend fun evictOlderThan(cutoffEpochMs: Long)

    @Query("DELETE FROM matches")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(matches: List<MatchEntity>) {
        clear()
        insertAll(matches)
    }
}

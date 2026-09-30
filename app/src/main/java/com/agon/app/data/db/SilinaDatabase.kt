package com.agon.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import android.content.Context
import com.agon.app.data.db.daos.ChannelDao
import com.agon.app.data.db.daos.MatchDao
import com.agon.app.data.db.daos.PlaylistDao
import com.agon.app.data.db.entities.ChannelEntity
import com.agon.app.data.db.entities.MatchEntity
import com.agon.app.data.model.Playlist
import com.agon.app.data.model.PlaylistType

/**
 * SilinaDatabase — Single Room persistence layer for SilinaTV Pro.
 *
 * Replaces every in-memory array (SessionData.allStreams, MatchCacheManager's
 * DataStore JSON, etc.) with a sustainable on-disk SQLite database. The UI now
 * reads channels & matches via Flow / PagingSource — no more "load 22k channels
 * into RAM and pray" anti-pattern that caused OOMs and Compose frame drops.
 *
 * Tables:
 *  - playlists        → one row per stored playlist credential set (the
 *                       [Playlist] @Entity defined in
 *                       com.agon.app.data.model.Playlist).
 *  - channels         → one row per stream item (live/movie/series) with
 *                       composite indexes on (playlistId, kind) and
 *                       (playlistId, group) for instant filter / search.
 *  - matches          → one row per harvested sports match + matched channel
 *
 * Concurrency:
 *  - DB instance is a process-wide singleton (double-checked locking).
 *  - All DAO methods are suspend or return Flow — safe to call from any
 *    Dispatchers.IO coroutine without extra synchronization.
 */
@Database(
    entities = [
        Playlist::class,
        ChannelEntity::class,
        MatchEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(SilinaConverters::class)
abstract class SilinaDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun matchDao(): MatchDao

    companion object {
        @Volatile
        private var INSTANCE: SilinaDatabase? = null

        private const val DB_NAME = "silina_tv.db"

        fun get(context: Context): SilinaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SilinaDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Test-only — used by MatchCacheManager / PlaylistRepository unit tests
         * to inject an in-memory database.
         */
        fun setForTest(db: SilinaDatabase?) {
            INSTANCE = db
        }
    }
}

/** Room type converters for enums and other non-primitive fields. */
class SilinaConverters {
    @TypeConverter
    fun playlistTypeToString(type: PlaylistType): String = type.name

    @TypeConverter
    fun stringToPlaylistType(value: String?): PlaylistType? =
        value?.let { runCatching { PlaylistType.valueOf(it) }.getOrNull() }
}

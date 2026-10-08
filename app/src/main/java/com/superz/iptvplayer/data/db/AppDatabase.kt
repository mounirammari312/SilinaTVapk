package com.superz.iptvplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1.12.0 — schema v6: ONE more additive nullable column —
 * channels.tv_archive (the stalker tv-archive flag, the reference's
 * Channel.archive). Purely additive ALTER: every existing row survives
 * in place; null simply means "no catch-up" until the next sync re-reads
 * the portal's get_all_channels rows.
 */
@Database(
    entities = [
        Playlist::class,
        Category::class,
        Channel::class,
        Favorite::class,
        EngineMemory::class,
        VodCategory::class,
        SeriesCategory::class,
        Movie::class,
        SeriesShow::class,
        VodFavorite::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun engineMemoryDao(): EngineMemoryDao
    abstract fun vodDao(): VodDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // CREATE statements mirror Room's expected schema exactly
                // (types / nullability / indices / unique indices).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `movies` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`movie_key` TEXT NOT NULL, " +
                        "`num` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`poster` TEXT, " +
                        "`categoryId` TEXT, " +
                        "`streamId` INTEGER, " +
                        "`containerExtension` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_playlistId` ON `movies` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_playlistId_categoryId` ON `movies` (`playlistId`, `categoryId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_movies_playlistId_movie_key` ON `movies` (`playlistId`, `movie_key`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`series_key` TEXT NOT NULL, " +
                        "`num` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`poster` TEXT, " +
                        "`categoryId` TEXT, " +
                        "`seriesId` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_playlistId` ON `series` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_playlistId_categoryId` ON `series` (`playlistId`, `categoryId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_series_playlistId_series_key` ON `series` (`playlistId`, `series_key`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod_categories` (" +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "PRIMARY KEY (`playlistId`, `categoryId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_categories` (" +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "PRIMARY KEY (`playlistId`, `categoryId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod_favorites` (" +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, " +
                        "`contentType` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY (`playlistId`, `contentKey`))"
                )
            }
        }

        // v1.5.0 — additive: channels.stalkerCmd (nullable, no default needed).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `stalkerCmd` TEXT")
            }
        }

        // v1.6.0 — additive: playlists.lastSyncAt (NOT NULL, default 0).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `lastSyncAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v1.11.0 — additive: stalker VOD/Series columns (all nullable).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `stalkerRow` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `stalkerRow` TEXT")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `stalkerVodTotal` INTEGER")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `stalkerSeriesTotal` INTEGER")
            }
        }

        // v1.12.0 — additive: channels.tv_archive (nullable).
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `tv_archive` INTEGER")
            }
        }

        // v1.19.7 — HUGE-LIST PAGING: index-backed ORDER BY num for the
        // paged movie/series queries (the channels table already had it).
        // Pure CREATE INDEX — no row is touched; a 100k-row table upgrades
        // in milliseconds and every existing row survives in place.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_playlistId_num` ON `movies` (`playlistId`, `num`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_playlistId_num` ON `series` (`playlistId`, `num`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "iptv_player.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}

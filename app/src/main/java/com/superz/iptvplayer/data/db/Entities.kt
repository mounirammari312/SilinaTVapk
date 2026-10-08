package com.superz.iptvplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A synced subscription: one Xtream account OR one M3U URL. */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,                    // "XTREAM" | "M3U"
    val server: String? = null,          // normalized base url (xtream)
    val username: String? = null,
    val password: String? = null,
    val m3uUrl: String? = null,
    val isActive: Boolean = false,
    val expiryDate: Long? = null,        // epoch seconds (xtream)
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    val channelCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** v1.6.0 — epoch seconds of the last successful data sync; drives the
     *  home cards' "Last Update : …" strips (the reference's
     *  SharedPreferenceLastPlaylistDate, stored per-row instead). */
    val lastSyncAt: Long = 0,
    /** v1.11.0 — PORTAL playlists: the server-reported total_items of the
     *  VOD / Series "All" lists (get_ordered_list page 1). The portal pages
     *  14 rows per request, so the cache holds only the first pages; these
     *  totals keep the hub cards truthful ("64 273 movies") like the
     *  reference's ItemActivity header. Null on every XC/M3U row. */
    val stalkerVodTotal: Int? = null,
    val stalkerSeriesTotal: Int? = null
)

/** A live category (Xtream category_id / M3U group-title). */
@Entity(
    tableName = "categories",
    primaryKeys = ["playlistId", "categoryId"]
)
data class Category(
    val playlistId: Long,
    val categoryId: String,
    val name: String
)

/** A live channel. `key` = streamId (xtream) or URL hash (m3u) — stable across refreshes. */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "channel_key"], unique = true),
        Index(value = ["playlistId", "num"])
    ]
)
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    @ColumnInfo(name = "channel_key") val key: String,
    val num: Int,
    val name: String,
    val logo: String? = null,
    val categoryId: String? = null,
    val streamId: Long? = null,          // Xtream stream id
    val directUrl: String? = null,       // M3U direct stream URL
    // v1.5.0 — stalker portals: the channel's `cmd` (create_link input).
    // Null for every XC/M3U row; purely additive (MIGRATION_2_3).
    val stalkerCmd: String? = null,
    /** v1.12.0 — stalker portals: tv-archive flag (the reference's
     *  Channel.archive / EPGChannel.tv_archive). 1 = catch-up capable
     *  (the Catch-Up screen lists these); 0/unknown = null-equivalent.
     *  Additive (MIGRATION_5_6). */
    @ColumnInfo(name = "tv_archive") val tvArchive: Int? = null
)

/** Survives channel-list refreshes (joined by playlistId + channel_key). */
@Entity(
    tableName = "favorites",
    primaryKeys = ["playlistId", "channelKey"]
)
data class Favorite(
    val playlistId: Long,
    val channelKey: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** Which engine/variant played this channel successfully last time — the zap accelerator. */
@Entity(
    tableName = "engine_memory",
    primaryKeys = ["playlistId", "channelKey"]
)
data class EngineMemory(
    val playlistId: Long,
    val channelKey: String,
    val engine: String,                  // "EXO" | "VLC"
    val variant: String? = null,         // "ts" | "m3u8" (xtream) | null (m3u)
    val updatedAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────
// v1.4.0 — VOD (movies + series). New tables only: the playlists
// table (and every existing table) is UNTOUCHED, so the v1→v2
// migration is purely additive — user data survives in place.
// ─────────────────────────────────────────────────────────────────

/** A VOD movie category (Xtream get_vod_categories). */
@Entity(
    tableName = "vod_categories",
    primaryKeys = ["playlistId", "categoryId"]
)
data class VodCategory(
    val playlistId: Long,
    val categoryId: String,
    val name: String
)

/** A series category (Xtream get_series_categories). */
@Entity(
    tableName = "series_categories",
    primaryKeys = ["playlistId", "categoryId"]
)
data class SeriesCategory(
    val playlistId: Long,
    val categoryId: String,
    val name: String
)

/** A movie. `key` = "m:{streamId}" — stable across refreshes. */
@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "movie_key"], unique = true),
        // v1.19.7 — HUGE-LIST PAGING: makes the paged queries' ORDER BY num
        // stream straight off the index instead of sorting 100k+ rows.
        Index(value = ["playlistId", "num"])
    ]
)
data class Movie(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    @ColumnInfo(name = "movie_key") val key: String,
    val num: Int,
    val name: String,
    val poster: String? = null,
    val categoryId: String? = null,
    val streamId: Long? = null,
    val containerExtension: String? = null,
    /** v1.11.0 — PORTAL playlists: the FULL get_ordered_list row JSON.
     *  Stalker portals have no get_vod_info endpoint — the reference passes
     *  the whole Movie row to its info activity — so the row IS the info
     *  source (plot/cast/year/cmd/tmdb_id all live here). Null on XC rows. */
    val stalkerRow: String? = null
)

/** A series. `key` = "sr:{seriesId}" — stable across refreshes. */
@Entity(
    tableName = "series",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "series_key"], unique = true),
        // v1.19.7 — HUGE-LIST PAGING: index-backed ORDER BY num (see movies).
        Index(value = ["playlistId", "num"])
    ]
)
data class SeriesShow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    @ColumnInfo(name = "series_key") val key: String,
    val num: Int,
    val name: String,
    val poster: String? = null,
    val categoryId: String? = null,
    val seriesId: Long? = null,
    /** v1.11.0 — PORTAL playlists: the FULL get_ordered_list row JSON
     *  (the reference's Movie object: id "46170:46170", cmd, description,
     *  actors, tmdb_id …). Null on XC rows. */
    val stalkerRow: String? = null
)

/** Movie/series favorites (heart on poster cards + info pages). */
@Entity(
    tableName = "vod_favorites",
    primaryKeys = ["playlistId", "contentKey"]
)
data class VodFavorite(
    val playlistId: Long,
    val contentKey: String,               // "m:123" (movie) | "sr:456" (series)
    val contentType: String,              // "MOVIE" | "SERIES"
    val addedAt: Long = System.currentTimeMillis()
)

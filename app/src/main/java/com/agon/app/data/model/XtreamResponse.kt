package com.agon.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Xtream Codes API Response Models
 *
 * Parses JSON from player_api.php endpoint:
 * - /player_api.php?username=X&password=Y (main authentication + categories)
 * - /player_api.php?username=X&password=Y&action=get_live_streams
 * - /player_api.php?username=X&password=Y&action=get_vod_streams
 * - /player_api.php?username=X&password=Y&action=get_series
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  ARCHITECTURE UPDATE (Room Migration)
 * ─────────────────────────────────────────────────────────────────────────
 *  The three "stream" data classes ([XtreamLiveStream], [XtreamVodStream],
 *  [XtreamSeriesStream]) are now ALSO annotated as Room @Entity classes so
 *  the raw API response can be persisted directly without an intermediate
 *  mapping layer. The composite indexes below give the dashboard O(log n)
 *  filtering on category / EPG id / stream id — eliminating the legacy
 *  "filter the entire 22k list in RAM" hot path that caused Compose frame
 *  drops.
 *
 *  The XtreamAuth / Category models are NOT persisted (they are
 *  session-scoped) and stay as plain data classes.
 * ─────────────────────────────────────────────────────────────────────────
 */

// ─── Main Authentication Response (NOT persisted — session-scoped) ──────

data class XtreamAuthResponse(
    @SerializedName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerializedName("server_info") val serverInfo: XtreamServerInfo? = null,
    val auth: Int = 0
)

data class XtreamUserInfo(
    val username: String = "",
    val password: String = "",
    val message: String = "",
    val auth: Int = 0,
    @SerializedName("status") val status: String = "",
    @SerializedName("exp_date") val expDate: String = "",
    @SerializedName("is_trial") val isTrial: String = "",
    @SerializedName("active_cons") val activeCons: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("max_connections") val maxConnections: String = "",
    @SerializedName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList()
)

data class XtreamServerInfo(
    val url: String = "",
    val port: String = "",
    @SerializedName("https_port") val httpsPort: String = "",
    @SerializedName("server_protocol") val serverProtocol: String = "",
    @SerializedName("rtmp_port") val rtmpPort: String = "",
    @SerializedName("timezone") val timezone: String = "",
    @SerializedName("timestamp_now") val timestampNow: Long = 0,
    @SerializedName("time_now") val timeNow: String = ""
)

// ─── Category Models (persisted for offline-first category listing) ─────

@Entity(
    tableName = "xtream_categories",
    indices = [
        Index(value = ["categoryId"], name = "idx_xtream_categories_id"),
        Index(value = ["parentId"], name = "idx_xtream_categories_parent")
    ]
)
data class XtreamCategory(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("category_name") val categoryName: String = "",
    @SerializedName("parent_id") val parentId: Int = 0
)

// ─── Live Stream Model (persisted — indexes for fast filter & EPG lookup)

/**
 * XtreamLiveStream — Room entity for live IPTV channels.
 *
 * Index strategy:
 *  - (categoryId)         → instant category-group filter
 *  - (epgChannelId)       → fast EPG join (used by the EPG pane)
 *  - (streamId, unique)   → primary lookup key for playback URLs
 */
@Entity(
    tableName = "xtream_live_streams",
    indices = [
        Index(value = ["streamId"], name = "idx_xtream_live_stream_id", unique = true),
        Index(value = ["categoryId"], name = "idx_xtream_live_category"),
        Index(value = ["epgChannelId"], name = "idx_xtream_live_epg"),
        Index(value = ["name"], name = "idx_xtream_live_name")
    ]
)
data class XtreamLiveStream(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    @SerializedName("num") val num: Int = 0,
    val name: String = "",
    @SerializedName("stream_type") val streamType: String = "",
    @SerializedName("stream_id") val streamId: Int = 0,
    @SerializedName("stream_icon") val streamIcon: String = "",
    @SerializedName("epg_channel_id") val epgChannelId: String = "",
    @SerializedName("added") val added: String = "",
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("custom_sid") val customSid: String = "",
    @SerializedName("tv_archive") val tvArchive: Int = 0,
    @SerializedName("direct_source") val directSource: String = "",
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int = 0
)

// ─── VOD (Movie) Stream Model (persisted — indexed by category) ─────────

@Entity(
    tableName = "xtream_vod_streams",
    indices = [
        Index(value = ["streamId"], name = "idx_xtream_vod_stream_id", unique = true),
        Index(value = ["categoryId"], name = "idx_xtream_vod_category"),
        Index(value = ["name"], name = "idx_xtream_vod_name")
    ]
)
data class XtreamVodStream(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    @SerializedName("num") val num: Int = 0,
    val name: String = "",
    @SerializedName("stream_type") val streamType: String = "",
    @SerializedName("stream_id") val streamId: Int = 0,
    @SerializedName("stream_icon") val streamIcon: String = "",
    @SerializedName("rating") val rating: String = "",
    @SerializedName("rating_5based") val rating5based: Double = 0.0,
    @SerializedName("added") val added: String = "",
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("container_extension") val containerExtension: String = "",
    @SerializedName("custom_sid") val customSid: String = "",
    @SerializedName("direct_source") val directSource: String = ""
)

// ─── Series Stream Model (persisted — indexed by category) ──────────────

@Entity(
    tableName = "xtream_series_streams",
    indices = [
        Index(value = ["seriesId"], name = "idx_xtream_series_id", unique = true),
        Index(value = ["categoryId"], name = "idx_xtream_series_category"),
        Index(value = ["name"], name = "idx_xtream_series_name")
    ]
)
data class XtreamSeriesStream(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    @SerializedName("num") val num: Int = 0,
    val name: String = "",
    @SerializedName("series_id") val seriesId: Int = 0,
    @SerializedName("cover") val cover: String = "",
    @SerializedName("plot") val plot: String = "",
    @SerializedName("cast") val cast: String = "",
    @SerializedName("director") val director: String = "",
    @SerializedName("genre") val genre: String = "",
    @SerializedName("releaseDate") val releaseDate: String = "",
    @SerializedName("last_modified") val lastModified: String = "",
    @SerializedName("rating") val rating: String = "",
    @SerializedName("rating_5based") val rating5based: Double = 0.0,
    @SerializedName("category_id") val categoryId: String = ""
)

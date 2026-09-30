package com.agon.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ChannelEntity — One row per stream item (live / movie / series / episode).
 *
 * Index strategy (low-time-complexity filtering & search):
 *  - (playlistId, kind)         → instant tab splits (Live / Movies / Series)
 *  - (playlistId, group)        → instant category-group filter
 *  - (playlistId, name)         → fast text search prefix lookups
 *
 * `kind` is the pre-classified bucket ("LIVE" / "MOVIE" / "SERIES") so the
 * UI never has to re-scan the URL to decide what list a row belongs to.
 *
 * NOTE: The legacy [com.agon.app.data.model.Playlist] model is itself
 * a Room @Entity (see Playlist.kt) for the `playlists` table. Channel rows
 * live in their own `channels` table to keep the schema normalised.
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "kind"], name = "idx_channels_playlist_kind"),
        Index(value = ["playlistId", "group"], name = "idx_channels_playlist_group"),
        Index(value = ["playlistId", "name"], name = "idx_channels_playlist_name"),
        Index(value = ["epgChannelId"], name = "idx_channels_epg_id")
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    val playlistId: String,
    /** Discriminator: "LIVE" | "MOVIE" | "SERIES" — pre-computed once at fetch time. */
    val kind: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val epgChannelId: String = "",
    /** Xtream stream id (kept for EPG lookups); 0 if M3U. */
    val streamId: Long = 0L,
    /** Optional container extension for VOD ("mp4", "mkv"); empty otherwise. */
    val containerExtension: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * MatchEntity — One row per harvested sports match + its matched channel.
 *
 * Index strategy:
 *  - (status)        → fast LIVE/UPCOMING split for the dashboard
 *  - (kickoffEpochMs)→ time-window queries (eviction, "today's matches")
 *  - (matchedChannelRowId)→ join back to channels table for tap-to-play
 */
@Entity(
    tableName = "matches",
    indices = [
        Index(value = ["status"], name = "idx_matches_status"),
        Index(value = ["kickoffEpochMs"], name = "idx_matches_kickoff"),
        Index(value = ["matchedChannelRowId"], name = "idx_matches_channel"),
        Index(value = ["homeTeam", "awayTeam"], name = "idx_matches_teams")
    ]
)
data class MatchEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffTime: String,
    /** Epoch ms of kickoff; 0 if unknown (treated as LIVE). */
    val kickoffEpochMs: Long = 0L,
    val broadcaster: String,
    val source: String,
    /** Status discriminator: "LIVE" | "UPCOMING". */
    val status: String,
    /** Foreign-ish key to channels.rowId; -1 when no channel was matched. */
    val matchedChannelRowId: Long = -1L,
    /** Snapshot of the matched channel URL (denormalised for instant play). */
    val matchedChannelUrl: String = "",
    val matchedChannelName: String = "",
    val matchedChannelLogo: String = "",
    val fetchedAt: Long = System.currentTimeMillis()
)

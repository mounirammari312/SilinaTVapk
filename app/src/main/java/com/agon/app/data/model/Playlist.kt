package com.agon.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Data class representing a stored playlist configuration.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  ARCHITECTURE UPDATE (Room Migration)
 * ─────────────────────────────────────────────────────────────────────────
 *  [Playlist] is now ALSO a Room @Entity. The same instance is used as:
 *    1. The kotlinx-serializable model persisted by ProfileRepository in
 *       DataStore (backwards compatibility — keeps existing profiles intact).
 *    2. A Room row in the `playlists` table (forward compatibility — every
 *       new write goes through Room).
 *
 *  Index strategy:
 *   - (isActive)              → instant lookup of the currently-active profile
 *   - (serverUrl, username)   → fast duplicate-detection on login
 *  The legacy `id` field stays as the string PK so existing profiles keep
 *  working without a migration.
 * ─────────────────────────────────────────────────────────────────────────
 */
@Serializable
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["isActive"], name = "idx_playlists_active"),
        Index(value = ["serverUrl", "username"], name = "idx_playlists_server_user")
    ]
)
data class Playlist(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: PlaylistType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis()
)

/**
 * Enum representing the type of playlist
 */
@Serializable
enum class PlaylistType {
    XTREAM_CODES,
    M3U_PLAYLIST
}

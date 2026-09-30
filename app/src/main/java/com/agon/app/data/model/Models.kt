package com.agon.app.data.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "silina_cache")

/**
 * Data class representing a single stream item from an M3U playlist
 *
 * @param name The display name of the stream/channel
 * @param url The streaming URL
 * @param logo The logo/image URL for the channel
 * @param group The category/group the channel belongs to
 */
data class StreamItem(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val epgChannelId: String = ""
)

/**
 * Data class representing a single episode from an Xtream Codes Series.
 * Extracted from get_series_info API response -> episodes -> season_key -> [episode_array].
 *
 * @param id                Episode stream ID (used in playback URL)
 * @param title             Episode display title
 * @param containerExtension Media container format (mkv, mp4, etc.)
 * @param season            Season number (parsed from the season key)
 * @param episodeNum        Episode number within the season
 */
data class EpisodeItem(
    val id: String,
    val title: String,
    val containerExtension: String,
    val season: Int,
    val episodeNum: Int
)

/**
 * Singleton object to hold session data across the application
 * Stores the parsed list of streams from M3U playlists AND Xtream API
 * with pre-calculated classification for Live TV, Movies, and Series
 *
 * Performance: Lists are calculated ONCE when allStreams is set
 * to prevent UI thread blocking on 22k+ channel lists
 */
object SessionData {

    /**
     * Pre-calculated Live TV streams
     * Excludes: /movie/, /series/, .mp4, .mkv patterns
     */
    var liveStreams: List<StreamItem> = emptyList()

    /**
     * Pre-calculated Movie streams
     * Includes: /movie/ path, .mp4 extension, .mkv extension
     */
    var movieStreams: List<StreamItem> = emptyList()

    /**
     * Pre-calculated Series streams
     * Includes: /series/ path
     */
    var seriesStreams: List<StreamItem> = emptyList()

    /**
     * Master list of all parsed stream items from the loaded playlist
     * Setting this value triggers automatic classification into
     * liveStreams, movieStreams, and seriesStreams
     */
    var allStreams: List<StreamItem> = emptyList()
        set(value) {
            field = value
            // Calculated ONCE to prevent UI thread freezing
            liveStreams = value.filter {
                !it.url.contains("/movie/") &&
                !it.url.contains("/series/") &&
                !it.url.endsWith(".mp4") &&
                !it.url.endsWith(".mkv")
            }
            movieStreams = value.filter {
                it.url.contains("/movie/") ||
                it.url.endsWith(".mp4") ||
                it.url.endsWith(".mkv")
            }
            seriesStreams = value.filter {
                it.url.contains("/series/")
            }
        }

    /**
     * Contextual Mini-Surfer list - dynamically injected based on the
     * currently playing category (Live, Movies, or Episodes).
     * Read by PlayerActivity to drive the channel surfer overlay.
     */
    var currentSurferStreams: List<StreamItem> = emptyList()

    /**
     * Name of the currently loaded playlist
     */
    var playlistName: String = ""

    /**
     * Type of the currently loaded playlist (M3U or Xtream)
     */
    var playlistType: PlaylistType = PlaylistType.M3U_PLAYLIST

    /**
     * Active playlist's Room PK (`Playlist.id`).
     *
     * Set by [PlaylistRepository.fetchXtreamStreams] on successful auth so
     * subsequent `fetchVodStreams()` / `fetchSeriesStreams()` calls know
     * which `channels` slice to write to. Empty until the first login.
     */
    var activePlaylistId: String = ""

    /**
     * Xtream API base URL (for building stream URLs)
     */
    var xtreamBaseUrl: String = ""

    /**
     * Xtream API username
     */
    var xtreamUsername: String = ""

    /**
     * Xtream API password
     */
    var xtreamPassword: String = ""

    /**
     * Category name mapping: categoryId -> categoryName
     */
    var categoryMap: Map<String, String> = emptyMap()

    /**
     * Account expiration date as a human-readable string (e.g., "Exp: 12 Nov 2026").
     * Parsed from Xtream's `user_info.exp_date` (UNIX timestamp) during auth.
     * Empty when no expiration is provided (e.g., M3U-only profiles).
     */
    var accountExpiry: String = ""

    /**
     * Clears all session data
     */
    fun clear() {
        allStreams = emptyList()
        liveStreams = emptyList()
        movieStreams = emptyList()
        seriesStreams = emptyList()
        playlistName = ""
        playlistType = PlaylistType.M3U_PLAYLIST
        activePlaylistId = ""
        xtreamBaseUrl = ""
        xtreamUsername = ""
        xtreamPassword = ""
        categoryMap = emptyMap()
        accountExpiry = ""
    }
}

// ─── DataStore Cache for M3U (Performance Enhancement) ──────────────────

@Serializable
data class CachedStream(
    val name: String,
    val url: String,
    val logo: String,
    val group: String
)

@Serializable
data class CachedPlaylist(
    val streams: List<CachedStream>,
    val cachedAt: Long = System.currentTimeMillis(),
    val playlistName: String = "",
    val playlistType: String = "M3U_PLAYLIST"
)

object PlaylistCache {

    private val CACHE_KEY = stringPreferencesKey("cached_playlist")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Save streams to DataStore cache for instant cold-start display
     */
    suspend fun saveToCache(context: Context, streams: List<StreamItem>, name: String, type: PlaylistType) {
        val cached = CachedPlaylist(
            streams = streams.map { CachedStream(it.name, it.url, it.logo, it.group) },
            playlistName = name,
            playlistType = type.name
        )
        context.cacheDataStore.edit { prefs ->
            prefs[CACHE_KEY] = json.encodeToString(cached)
        }
    }

    /**
     * Load cached streams from DataStore
     * @return CachedPlaylist or null if no cache exists
     */
    suspend fun loadFromCache(context: Context): CachedPlaylist? {
        return try {
            val cachedJson = context.cacheDataStore.data.map { prefs ->
                prefs[CACHE_KEY] ?: return@map null
            }.first() ?: return null
            json.decodeFromString<CachedPlaylist>(cachedJson)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clear the cache
     */
    suspend fun clearCache(context: Context) {
        context.cacheDataStore.edit { prefs ->
            prefs.remove(CACHE_KEY)
        }
    }
}
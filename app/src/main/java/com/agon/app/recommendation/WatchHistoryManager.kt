package com.agon.app.recommendation

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * WatchHistoryManager
 *
 * Tracks the user's viewing patterns to power the "Smart Zapping"
 * recommendation engine. For every channel the user watches, we record:
 *   - The channel's group (category)
 *   - The time of day (hour bucket)
 *   - Total watch duration in seconds
 *
 * This data feeds a simple recommendation algorithm that suggests
 * channels the user is likely to enjoy based on their habits.
 *
 * Storage
 * =======
 * Uses a separate DataStore file (`watch_history.preferences_pb`)
 * so it doesn't interfere with the playback positions store.
 */
private val Context.watchHistoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "watch_history"
)

object WatchHistoryManager {

    private const val TAG = "WatchHistory"
    private const val MAX_TRACKED_CHANNELS = 200

    // Keys: "watch_<url>" = total seconds watched
    //       "group_<groupName>" = total seconds watched in that group
    //       "hour_<0-23>_<groupName>" = seconds watched at that hour
    private fun watchKey(url: String) = longPreferencesKey("watch_$url")
    private fun groupKey(group: String) = longPreferencesKey("group_$group")
    private fun hourGroupKey(hour: Int, group: String) = longPreferencesKey("hour_${hour}_$group")
    private fun lastWatchedKey() = longPreferencesKey("last_watched_ts")

    /**
     * Records that the user watched [seconds] of the given stream.
     * Call this periodically (e.g. every 5s) during playback.
     */
    suspend fun recordWatch(context: Context, stream: StreamItem, seconds: Long) {
        if (seconds <= 0 || stream.url.isBlank()) return
        val hour = (System.currentTimeMillis() / 3600000L).toInt() % 24
        val group = stream.group.ifBlank { "Unknown" }

        try {
            context.watchHistoryStore.edit { prefs ->
                // Update total watch time for this channel
                val prevChannel = prefs[watchKey(stream.url)] ?: 0L
                prefs[watchKey(stream.url)] = prevChannel + seconds

                // Update total watch time for this group
                val prevGroup = prefs[groupKey(group)] ?: 0L
                prefs[groupKey(group)] = prevGroup + seconds

                // Update hour×group matrix
                val prevHourGroup = prefs[hourGroupKey(hour, group)] ?: 0L
                prefs[hourGroupKey(hour, group)] = prevHourGroup + seconds

                // Update last watched timestamp
                prefs[lastWatchedKey()] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record watch: ${e.message}")
        }
    }

    /**
     * Returns the user's top 5 most-watched groups (categories).
     */
    suspend fun getTopGroups(context: Context, limit: Int = 5): List<Pair<String, Long>> {
        return try {
            val prefs = context.watchHistoryStore.data.first()
            val groups = mutableMapOf<String, Long>()
            prefs.asMap().forEach { (key, value) ->
                val keyStr = key.name
                if (keyStr.startsWith("group_") && value is Long) {
                    val groupName = keyStr.removePrefix("group_")
                    if (groupName != "Unknown") {
                        groups[groupName] = value
                    }
                }
            }
            groups.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key to it.value }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get top groups: ${e.message}")
            emptyList()
        }
    }

    /**
     * Returns the current hour's preferred group.
     */
    suspend fun getCurrentHourTopGroup(context: Context): String? {
        val hour = (System.currentTimeMillis() / 3600000L).toInt() % 24
        return try {
            val prefs = context.watchHistoryStore.data.first()
            var bestGroup: String? = null
            var bestScore = 0L
            prefs.asMap().forEach { (key, value) ->
                val keyStr = key.name
                if (keyStr.startsWith("hour_${hour}_") && value is Long) {
                    val group = keyStr.removePrefix("hour_${hour}_")
                    if (value > bestScore) {
                        bestScore = value
                        bestGroup = group
                    }
                }
            }
            bestGroup
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates recommendations based on the user's watch history.
     * Returns channels sorted by predicted interest (highest first).
     *
     * Algorithm
     * =========
     * For each channel in SessionData.liveStreams:
     *   score = (group affinity for current hour) × 3
     *         + (overall group affinity) × 1
     *         + (channel watch time) × 0.5
     * Channels the user has never watched get a small boost from their
     * group affinity — so the user discovers new channels in familiar
     * categories.
     */
    suspend fun getRecommendations(context: Context, limit: Int = 10): List<Pair<StreamItem, Double>> {
        val hour = (System.currentTimeMillis() / 3600000L).toInt() % 24
        val prefs = try {
            context.watchHistoryStore.data.first()
        } catch (e: Exception) {
            return emptyList()
        }

        // Build affinity maps
        val groupAffinity = mutableMapOf<String, Long>()
        val hourGroupAffinity = mutableMapOf<String, Long>()
        val channelWatchTime = mutableMapOf<String, Long>()
        prefs.asMap().forEach { (key, value) ->
            val keyStr = key.name
            if (value is Long) {
                when {
                    keyStr.startsWith("group_") -> {
                        groupAffinity[keyStr.removePrefix("group_")] = value
                    }
                    keyStr.startsWith("hour_${hour}_") -> {
                        hourGroupAffinity[keyStr.removePrefix("hour_${hour}_")] = value
                    }
                    keyStr.startsWith("watch_") -> {
                        channelWatchTime[keyStr.removePrefix("watch_")] = value
                    }
                }
            }
        }

        // Score each live stream
        val allStreams = SessionData.liveStreams
        val scored = allStreams.map { stream ->
            val group = stream.group.ifBlank { "Unknown" }
            val hourScore = (hourGroupAffinity[group] ?: 0L).toDouble() * 3.0
            val groupScore = (groupAffinity[group] ?: 0L).toDouble() * 1.0
            val channelScore = (channelWatchTime[stream.url] ?: 0L).toDouble() * 0.5
            val totalScore = hourScore + groupScore + channelScore
            stream to totalScore
        }

        // Sort by score descending, take top N
        val top = scored.sortedByDescending { it.second }.take(limit)
        Log.i(TAG, "Generated ${top.size} recommendations (top score=${top.firstOrNull()?.second ?: 0.0})")
        return top
    }
}

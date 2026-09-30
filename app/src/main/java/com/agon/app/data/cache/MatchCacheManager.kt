package com.agon.app.data.cache

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agon.app.data.db.SilinaDatabase
import com.agon.app.data.db.entities.MatchEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.matchCacheStore: DataStore<Preferences> by preferencesDataStore(
    name = "match_cache"
)

/**
 * MatchCacheManager — Armored Cache-First Layer (Room-backed).
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  ARCHITECTURE UPDATE (Room Migration)
 * ─────────────────────────────────────────────────────────────────────────
 *  Previously this object stored the RAW JSON payload from the Hugging Face
 *  server in DataStore Preferences. Every UI read had to re-parse the JSON
 *  + re-run the fuzzy channel matcher (1.1M Regex ops on 22k channels).
 *
 *  Now the structured [MatchEntity] rows live in the Room `matches` table,
 *  with indexes on (status), (kickoffEpochMs) and (matchedChannelRowId).
 *  The UI reads pre-resolved rows via [observeMatches] (Flow) — zero parse
 *  cost, zero Regex, zero JSON.
 *
 *  Backwards-compat:
 *   - [getCachedJson] / [saveCache] are retained so [MatchHarvesterRepository]
 *     can transparently migrate the legacy JSON cache into Room on first run.
 *   - On any DB failure the legacy DataStore path is used as a fallback.
 *
 *  Cache Policy
 *  ============
 *   - TTL: 30 minutes from the last successful fetch.
 *   - If cache age < 30 min → serve from cache, NO network call.
 *   - If cache age ≥ 30 min → serve from cache immediately, then schedule
 *     a background fetch with a random jitter delay (0–30 seconds) to
 *     avoid thundering-herd on the server.
 *   - If cache is empty (first launch) → show a brief loading spinner
 *     until the first fetch completes.
 */
object MatchCacheManager {

    private const val TAG = "MatchCache"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    private val KEY_JSON = stringPreferencesKey("raw_json")
    private val KEY_FETCH_TIME = longPreferencesKey("fetch_time")

    // ═══════════════════════════════════════════════════════════════════
    //  ROOM API (new hot path — pre-resolved structured rows)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Live Flow of all cached matches, sorted by kickoff time. The UI
     * re-renders automatically when the underlying table changes.
     */
    fun observeMatches(context: Context): Flow<List<MatchEntity>> = try {
        SilinaDatabase.get(context).matchDao().observeAll()
    } catch (e: Exception) {
        Log.w(TAG, "observeMatches failed: ${e.message}")
        flowOf(emptyList())
    }

    /**
     * Snapshot read of all cached matches. Returns an empty list on any error.
     */
    suspend fun getMatches(context: Context): List<MatchEntity> = try {
        SilinaDatabase.get(context).matchDao().getAll()
    } catch (e: Exception) {
        Log.w(TAG, "getMatches failed: ${e.message}")
        emptyList()
    }

    /**
     * Atomically replace the entire matches table with [rows]. Runs inside
     * a single Room transaction so the UI never sees a partial state.
     */
    suspend fun replaceMatches(context: Context, rows: List<MatchEntity>) {
        try {
            SilinaDatabase.get(context).matchDao().replaceAll(rows)
            Log.i(TAG, "Replaced ${rows.size} match rows in Room")
        } catch (e: Exception) {
            Log.w(TAG, "replaceMatches failed: ${e.message}")
        }
    }

    /**
     * Delete matches whose kickoff + lifetime window is in the past.
     */
    suspend fun evictOlderThan(context: Context, cutoffEpochMs: Long) {
        try {
            SilinaDatabase.get(context).matchDao().evictOlderThan(cutoffEpochMs)
        } catch (e: Exception) {
            Log.w(TAG, "evictOlderThan failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LEGACY JSON CACHE (kept for backwards compat + one-shot migration)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reads the cached JSON + its fetch timestamp.
     * Returns null if no cache exists.
     *
     * Used by [MatchHarvesterRepository] as the source for the legacy parse
     * pipeline, and as the migration seed on first run after the Room upgrade.
     */
    suspend fun getCachedJson(context: Context): Pair<String, Long>? {
        return try {
            val prefs = context.matchCacheStore.data.first()
            val json = prefs[KEY_JSON] ?: return null
            val time = prefs[KEY_FETCH_TIME] ?: 0L
            Pair(json, time)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache: ${e.message}")
            null
        }
    }

    /**
     * Saves the raw JSON response + the current timestamp.
     *
     * Kept so the legacy parse pipeline can still run. Structured rows are
     * written separately via [replaceMatches].
     */
    suspend fun saveCache(context: Context, json: String) {
        try {
            context.matchCacheStore.edit { prefs ->
                prefs[KEY_JSON] = json
                prefs[KEY_FETCH_TIME] = System.currentTimeMillis()
            }
            Log.i(TAG, "JSON cache saved (${json.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache: ${e.message}")
        }
    }

    /**
     * Returns true if the cache is still valid (age < 30 min).
     * Returns false if cache is expired or missing.
     */
    suspend fun isCacheValid(context: Context): Boolean {
        val cached = getCachedJson(context) ?: return false
        val age = System.currentTimeMillis() - cached.second
        return age < CACHE_TTL_MS
    }

    /**
     * Returns the cache age in milliseconds (Long.MAX_VALUE if no cache).
     */
    suspend fun cacheAgeMs(context: Context): Long {
        val cached = getCachedJson(context) ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - cached.second
    }
}

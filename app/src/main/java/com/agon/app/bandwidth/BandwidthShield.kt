package com.agon.app.bandwidth

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * BandwidthShield — حماية الباقة من النفاد
 *
 * ════════════════════════════════════════════════════════════════════════
 *  PURPOSE
 * ════════════════════════════════════════════════════════════════════════
 *  Tracks the total bytes consumed by IPTV streaming and enforces a
 *  user-configurable daily bandwidth limit. When the limit is reached,
 *  playback is paused automatically via [GlobalPlaybackCoordinator].
 *
 *  This is the killer feature for users with limited data plans — no
 *  other IPTV app offers bandwidth protection. It targets the same
 *  audience as Yasmine TV (users with weak / capped internet).
 *
 *  ARCHITECTURE:
 *    - [bytesToday] (AtomicLong) — in-memory counter, incremented by
 *      RecordingDataSource on every byte read. Reset to the persisted
 *      value on app launch.
 *    - DataStore — persists the daily total + the date string so the
 *      counter resets at midnight.
 *    - [dailyLimitMb] — user-configurable limit (0 = unlimited).
 *    - [enabled] — master switch.
 *
 *  The counter is checked on every ~1MB of data — not on every byte —
 *  to avoid performance overhead.
 *
 *  V8.6 — The limit-exceeded callback is now WIRED to
 *  GlobalPlaybackCoordinator.forceTeardown() so playback actually pauses
 *  when the daily limit is hit. Previously the callback was a dead field
 *  that no caller ever set, so the limit was tracked but never enforced.
 * ════════════════════════════════════════════════════════════════════════
 */
private val Context.bandwidthStore: DataStore<Preferences> by preferencesDataStore(
    name = "bandwidth_shield"
)

object BandwidthShield {

    private const val TAG = "BandwidthShield"

    /** Check the limit every ~1MB (1,048,576 bytes) to avoid overhead. */
    private const val CHECK_INTERVAL_BYTES = 1_048_576L

    private val KEY_BYTES_TODAY = longPreferencesKey("bytes_today")
    private val KEY_DATE_STRING = stringPreferencesKey("date_string")
    private val KEY_DAILY_LIMIT_MB = intPreferencesKey("daily_limit_mb")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_TOTAL_EVER = longPreferencesKey("total_ever")

    private val bytesToday = AtomicLong(0L)
    private val totalEver = AtomicLong(0L)
    private var lastCheckBytes = 0L
    @Volatile private var dailyLimitMb: Int = 0
    @Volatile private var enabled: Boolean = false
    private var initialized = false

    // V8.6 — Private IO scope (replaces GlobalScope which leaks the
    // coroutine across the entire application lifecycle).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // V8.6 — One-shot guard so the limit-exceeded teardown fires only ONCE
    // per day. Without this, every subsequent byte read after the limit
    // would re-invoke forceTeardown in a tight loop.
    @Volatile private var limitReachedFired: Boolean = false

    suspend fun init(context: Context) {
        if (initialized) return
        val prefs = context.bandwidthStore.data.first()
        val today = todayString()

        val storedDate = prefs[KEY_DATE_STRING] ?: today
        val storedBytes = prefs[KEY_BYTES_TODAY] ?: 0L
        val storedTotal = prefs[KEY_TOTAL_EVER] ?: 0L

        dailyLimitMb = prefs[KEY_DAILY_LIMIT_MB] ?: 0
        enabled = prefs[KEY_ENABLED] ?: false

        if (storedDate == today) {
            bytesToday.set(storedBytes)
            // If we already exceeded the limit today, mark the one-shot
            // so the first byte read doesn't immediately fire teardown.
            val limitBytes = dailyLimitMb.toLong() * 1024 * 1024
            if (dailyLimitMb > 0 && storedBytes >= limitBytes) limitReachedFired = true
        } else {
            bytesToday.set(0L)
            limitReachedFired = false
            context.bandwidthStore.edit {
                it[KEY_BYTES_TODAY] = 0L
                it[KEY_DATE_STRING] = today
            }
        }
        totalEver.set(storedTotal)

        initialized = true
        Log.i(TAG, "BandwidthShield initialized: today=${bytesToday.get() / 1024}KB, " +
                "limit=${if (dailyLimitMb > 0) "${dailyLimitMb}MB" else "unlimited"}, " +
                "enabled=$enabled")
    }

    /**
     * Called by [RecordingDataSource] on every byte read. Returns `false`
     * when the daily limit has been exceeded — in that case the caller
     * (RecordingDataSource) throws an IOException so ExoPlayer's load
     * pipeline aborts the segment fetch immediately.
     *
     * V8.6 — When the limit is exceeded, [GlobalPlaybackCoordinator.forceTeardown]
     * is invoked directly so ExoPlayer is paused and the proxy sockets are
     * closed. No external callback wiring is needed.
     */
    fun onBytesRead(context: Context, bytes: Int): Boolean {
        if (!enabled || dailyLimitMb <= 0) return true

        val newTotal = bytesToday.addAndGet(bytes.toLong())
        totalEver.addAndGet(bytes.toLong())
        lastCheckBytes += bytes

        if (lastCheckBytes >= CHECK_INTERVAL_BYTES) {
            lastCheckBytes = 0L
            val limitBytes = dailyLimitMb.toLong() * 1024 * 1024
            if (newTotal >= limitBytes) {
                Log.w(TAG, "Daily limit exceeded: ${newTotal / 1024 / 1024}MB >= ${dailyLimitMb}MB")
                persistAsync(context)
                // V8.6 — Fire the teardown ONCE per day, not on every byte.
                if (!limitReachedFired) {
                    limitReachedFired = true
                    try {
                        com.agon.app.proxy.GlobalPlaybackCoordinator.forceTeardown()
                        Log.i(TAG, "Limit-exceeded teardown fired — ExoPlayer paused + sockets closed")
                    } catch (e: Exception) {
                        Log.w(TAG, "Limit-exceeded teardown failed (non-fatal): ${e.message}")
                    }
                }
                return false
            }
            persistAsync(context)
        }
        return true
    }

    private fun persistAsync(context: Context) {
        val snapshot = bytesToday.get()
        val total = totalEver.get()
        val today = todayString()
        ioScope.launch {
            try {
                context.bandwidthStore.edit { prefs ->
                    prefs[KEY_BYTES_TODAY] = snapshot
                    prefs[KEY_DATE_STRING] = today
                    prefs[KEY_TOTAL_EVER] = total
                }
            } catch (e: Exception) {
                Log.w(TAG, "Persist failed (non-fatal): ${e.message}")
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun observeBytesToday(context: Context): Flow<Long> =
        context.bandwidthStore.data.map { it[KEY_BYTES_TODAY] ?: 0L }

    fun observeTotalEver(context: Context): Flow<Long> =
        context.bandwidthStore.data.map { it[KEY_TOTAL_EVER] ?: 0L }

    fun observeDailyLimitMb(context: Context): Flow<Int> =
        context.bandwidthStore.data.map { it[KEY_DAILY_LIMIT_MB] ?: 0 }

    fun observeEnabled(context: Context): Flow<Boolean> =
        context.bandwidthStore.data.map { it[KEY_ENABLED] ?: false }

    suspend fun setDailyLimitMb(context: Context, mb: Int) {
        dailyLimitMb = mb
        // V8.6 — Reset the one-shot so a new limit takes effect immediately.
        limitReachedFired = false
        context.bandwidthStore.edit { it[KEY_DAILY_LIMIT_MB] = mb }
    }

    suspend fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        // V8.6 — Reset the one-shot on enable so re-enabling gives a fresh budget.
        limitReachedFired = false
        context.bandwidthStore.edit { it[KEY_ENABLED] = on }
    }

    suspend fun resetToday(context: Context) {
        bytesToday.set(0L)
        lastCheckBytes = 0L
        limitReachedFired = false
        context.bandwidthStore.edit {
            it[KEY_BYTES_TODAY] = 0L
            it[KEY_DATE_STRING] = todayString()
        }
    }

    private fun todayString(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }
}

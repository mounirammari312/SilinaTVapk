package com.agon.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.playbackPositionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_positions"
)

object PlaybackPositionManager {

    @Volatile
    private var instance: PlaybackPositionManager? = null

    fun getInstance(context: Context): PlaybackPositionManager {
        return instance ?: synchronized(this) {
            instance ?: PlaybackPositionManager.also { instance = it }
        }
    }

    private fun sanitizeKey(streamUrl: String): String {
        return streamUrl
            .replace("/", "_")
            .replace(":", "_")
            .replace(".", "_")
            .replace("=", "_")
            .replace("&", "_")
            .replace("?", "_")
    }

    suspend fun savePosition(context: Context, streamUrl: String, positionMs: Long) {
        val key = stringPreferencesKey(sanitizeKey(streamUrl))
        context.playbackPositionsDataStore.edit { preferences ->
            preferences[key] = positionMs.toString()
        }
    }

    suspend fun getPosition(context: Context, streamUrl: String): Long {
        val key = stringPreferencesKey(sanitizeKey(streamUrl))
        return context.playbackPositionsDataStore.data
            .map { preferences -> preferences[key] }
            .first()
            ?.toLongOrNull() ?: 0L
    }

    suspend fun clearPosition(context: Context, streamUrl: String) {
        val key = stringPreferencesKey(sanitizeKey(streamUrl))
        context.playbackPositionsDataStore.edit { preferences ->
            preferences.remove(key)
        }
    }
}
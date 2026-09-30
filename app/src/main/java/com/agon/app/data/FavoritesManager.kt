package com.agon.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent favorites storage using Preferences DataStore.
 *
 * Stores a Set<String> of stream URLs that the user has favorited.
 * Survives app restarts, profile switches, and process death.
 *
 * Usage:
 *   val favs = FavoritesManager.getInstance(context)
 *   val favFlow: Flow<Set<String>> = favs.getFavorites()
 *   favs.toggleFavorite("http://...")
 */
object FavoritesManager {

    private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "silina_favorites")

    private val FAVORITES_KEY = stringSetPreferencesKey("favorite_urls")

    @Volatile
    private var instance: FavoritesManager? = null

    private lateinit var appContext: Context

    /**
     * Initialize with application context. Call once from Application or first Activity.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Get singleton instance. Creates on-demand if not yet initialized.
     */
    fun getInstance(context: Context): FavoritesManager {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    init(context)
                    instance = this
                }
            }
        }
        return this
    }

    /**
     * Observe the current set of favorite stream URLs as a Flow.
     * Collect this in a Composable using `collectAsState()`.
     */
    fun getFavorites(): Flow<Set<String>> {
        return appContext.favoritesDataStore.data.map { prefs ->
            prefs[FAVORITES_KEY] ?: emptySet()
        }
    }

    /**
     * Toggle a stream URL in/out of favorites.
     * If it exists, remove it. If not, add it.
     */
    suspend fun toggleFavorite(url: String) {
        appContext.favoritesDataStore.edit { prefs ->
            val current = prefs[FAVORITES_KEY] ?: emptySet()
            if (url in current) {
                prefs[FAVORITES_KEY] = current - url
            } else {
                prefs[FAVORITES_KEY] = current + url
            }
        }
    }

    /**
     * Check if a specific URL is in favorites. Returns Flow<Boolean>.
     */
    fun isFavorite(url: String): Flow<Boolean> {
        return getFavorites().map { url in it }
    }
}
package com.superz.iptvplayer.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Accounts page state: playlist list, activation, in-place edit, deletion.
 * v1.4.8 — mirrors the reference app's UserListActivity contract: tapping a
 * card selects + connects (activate → loading/sync screen), the row buttons
 * edit / delete the portal, and deletions toast back to the user.
 */
class AccountsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlaylistRepository.get(getApplication<IPTVApp>())

    val playlists: StateFlow<List<Playlist>> =
        repo.playlistsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun select(playlist: Playlist, onSelected: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repo.activate(playlist.id)
            } catch (_: Exception) { /* activation is best-effort */ }
            finally { _busy.value = false }
            onSelected()
        }
    }

    /** Edit button — updates the row in place (children stay attached). */
    fun update(playlist: Playlist) {
        viewModelScope.launch {
            try { repo.update(playlist) } catch (_: Exception) { }
        }
    }

    /** Delete button — reference removes immediately and toasts. */
    fun delete(playlist: Playlist, onRemoved: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.delete(playlist)
                onRemoved()
            } catch (_: Exception) { }
        }
    }
}

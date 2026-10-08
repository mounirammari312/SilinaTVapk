package com.superz.iptvplayer.ui.loading

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.stalker.StalkerException
import com.superz.iptvplayer.data.xtream.XtreamException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Loading screen state — mirrors the reference app's HubActivity sync stage:
 * a weighted progress pipeline (auth → categories → channels) that ends by
 * navigating to the three-cards hub.
 */
class LoadingViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val playlistName: String = "",
        val progress: Int = 0,          // 0..100
        val stage: Stage = Stage.CONNECT,
        val channelsLoaded: Int = 0,
        val done: Boolean = false,
        val error: String? = null
    )

    enum class Stage { CONNECT, CATEGORIES, CHANNELS, MOVIES, SERIES, FINISH }

    private val repo = PlaylistRepository.get(getApplication<IPTVApp>())
    private val db = getApplication<IPTVApp>().database

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // Wait for the ACTIVE playlist (activation happens right before
            // this screen is pushed).
            val pl = db.playlistDao().activePlaylistFlow()
                .filterNotNull().first()
            _ui.value = UiState(playlistName = pl.name, progress = 8, stage = Stage.CONNECT)
            sync(pl)
        }
    }

    private suspend fun sync(pl: Playlist) {
        try {
            val count = repo.syncPlaylist(pl) { loaded ->
                // Weighted progress: parsing/downloading grows up to 90%.
                val pct = when {
                    loaded <= 0 -> 35
                    else -> (35 + (loaded / 80.0) * 55).toInt().coerceAtMost(90)
                }
                _ui.value = _ui.value.copy(
                    progress = pct,
                    stage = if (loaded > 0) Stage.CHANNELS else Stage.CATEGORIES,
                    channelsLoaded = loaded
                )
            }
            // v1.4.0 — movies + series (silent best-effort: a panel without
            // VOD never fails the pipeline; counts land via Room flows).
            _ui.value = _ui.value.copy(progress = 92, stage = Stage.MOVIES)
            repo.syncVod(pl) { stage ->
                _ui.value = _ui.value.copy(
                    progress = if (stage == "MOVIES") 93 else 97,
                    stage = if (stage == "MOVIES") Stage.MOVIES else Stage.SERIES
                )
            }
            _ui.value = _ui.value.copy(
                progress = 100,
                stage = Stage.FINISH,
                channelsLoaded = count,
                done = true
            )
        } catch (e: XtreamException) {
            _ui.value = _ui.value.copy(error = e.message ?: "SYNC_ERROR", progress = 100)
        } catch (e: StalkerException) {
            // v1.5.0 — friendly codes for the portal engine.
            _ui.value = _ui.value.copy(error = stalkerMessage(e), progress = 100)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(error = e.message ?: "SYNC_ERROR", progress = 100)
        }
    }

    /** v1.5.0 — map the stalker engine's codes onto readable text. */
    private fun stalkerMessage(e: StalkerException): String = when (e.message) {
        "PORTAL_NOT_WORKING", "PORTAL_EMPTY", "PORTAL_URL_MISSING", "MAC_MISSING" ->
            getApplication<Application>().getString(com.superz.iptvplayer.R.string.vu_portal_not_working)
        "FILE_NO_URL" ->
            getApplication<Application>().getString(com.superz.iptvplayer.R.string.vu_file_no_url)
        "FILE_NOT_READABLE", "M3U_URL_MISSING" ->
            getApplication<Application>().getString(com.superz.iptvplayer.R.string.vu_file_no_url)
        else -> e.message ?: "SYNC_ERROR"
    }

    fun retry() {
        viewModelScope.launch {
            _ui.value = UiState(playlistName = _ui.value.playlistName, progress = 8)
            val pl = db.playlistDao().activePlaylistFlow().filterNotNull().first()
            sync(pl)
        }
    }
}

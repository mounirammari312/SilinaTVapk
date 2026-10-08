package com.superz.iptvplayer.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.18.1 — Saved Videos library state (user request: a place to find
 * every auto-saved video — complete AND incomplete — to watch offline,
 * continue watching, or delete; entered from the hub's category row).
 *
 * The list is a plain directory scan (SavedLibrary.scan) — no database, no
 * network, works with airplane mode on. The screen re-scans every ~2.5 s
 * while visible, so an in-flight download's percent climbs live and a file
 * that just finalized flips to COMPLETE by itself.
 *
 * v1.19.13 — the scan also enriches each entry for the CARD redesign
 * (the Continue-Watching look): a real THUMBNAIL (SavedThumbs, generated
 * once and cached; DOWNLOADING entries skip it) and the entry's WATCH
 * PROGRESS record ("local:<fileName>" in PlaybackPositionManager — the
 * same store the player's resume bake reads), which drives the card's
 * cyan progress bar + "تبقّى …" strip.
 */
class SavedVideosViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val loading: Boolean = true,
        val videos: List<SavedVideo> = emptyList(),
        /** v1.19.13 — video.path → cached thumbnail JPEG path (no art → absent). */
        val thumbs: Map<String, String> = emptyMap(),
        /** v1.19.13 — video.path → watch-progress record (never-watched → absent). */
        val progress: Map<String, PlaybackPositionManager.PositionRecord> = emptyMap(),
        /** The entry pending the delete-confirmation dialog. */
        val pendingDelete: SavedVideo? = null,
        /** Transient one-shot message (delete done / failed). */
        val message: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        rescan()
    }

    /** Directory scan (idempotent; called on entry + on the 2.5 s loop). */
    fun rescan() {
        viewModelScope.launch {
            val (videos, thumbs, progress) = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val scanned = SavedLibrary.scan(app)
                // Thumbnails: only for settled files (COMPLETE/INCOMPLETE) —
                // a growing .part would churn its art on every rescan.
                val thumbs = scanned
                    .filter { it.state != SavedVideosContract.State.DOWNLOADING }
                    .mapNotNull { v ->
                        SavedThumbs.thumbnailFor(app, v)?.let { v.path to it }
                    }
                    .toMap()
                // Watch progress: the player stores "local:<fileName>".
                val store = PlaybackPositionManager.storeFile(app)
                val progress = scanned
                    .mapNotNull { v ->
                        val rec = PlaybackPositionManager.record(store, "local:${v.fileName}")
                        if (rec != null && rec.durationMs > 0L) v.path to rec else null
                    }
                    .toMap()
                Triple(scanned, thumbs, progress)
            }
            _ui.update {
                // keep the confirm dialog anchored to a file that still
                // exists; a finalized-away entry dismisses itself
                val pending = it.pendingDelete
                    ?.let { p -> videos.firstOrNull { v -> v.path == p.path } }
                it.copy(
                    loading = false, videos = videos, thumbs = thumbs,
                    progress = progress, pendingDelete = pending
                )
            }
        }
    }

    fun askDelete(video: SavedVideo) {
        _ui.update { it.copy(pendingDelete = video) }
    }

    fun cancelDelete() {
        _ui.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val target = _ui.value.pendingDelete ?: return
        _ui.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                SavedLibrary.delete(getApplication(), target)
            }
            val app = getApplication<Application>()
            _ui.update {
                it.copy(
                    message = app.getString(
                        if (ok) com.superz.iptvplayer.R.string.saved_deleted
                        else com.superz.iptvplayer.R.string.saved_delete_failed
                    )
                )
            }
            rescan()
        }
    }

    /** The screen consumes the toast. */
    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }
}

package com.superz.iptvplayer.ui.hub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.stalker.StalkerEpgRepository
import com.superz.iptvplayer.ui.downloads.SavedLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hub state: the active playlist (name, expiry, channel count, last sync)
 * powering the reference home page — the three cards, the "Last Update"
 * strips, and the expiration / logged-in pills. v1.4.0: live movie/series
 * counts from the VOD tables (all three cards are active).
 *
 * v1.6.0: [refresh] — the reference home's ly_update button: re-syncs the
 * active playlist (CustomProgressDlgFragment + getXCPlaylistData flow) and
 * stamps lastSyncAt, so the strips flip to "00mins ago".
 */
class HubViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlaylistRepository(getApplication())

    val playlist: StateFlow<Playlist?> =
        getApplication<IPTVApp>().database.playlistDao().activePlaylistFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * (movieCount, seriesCount) — reactive so the cards fill in after sync.
     * v1.11.0 — PORTAL playlists: the SERVER-reported totals from the
     * playlist row (the portal pages 14 rows per request, so Room holds
     * only the first pages — the hub cards stay truthful like the
     * reference's ItemActivity "Category (64273)" header). XC/M3U keep
     * the Room counts (their lists are fully synced).
     */
    val vodCounts: StateFlow<Pair<Int, Int>> =
        getApplication<IPTVApp>().database.playlistDao().activePlaylistFlow()
            .flatMapLatest { pl ->
                when {
                    pl == null -> flowOf(0 to 0)
                    pl.type == "PORTAL" && (pl.stalkerVodTotal != null || pl.stalkerSeriesTotal != null) ->
                        flowOf((pl.stalkerVodTotal ?: 0) to (pl.stalkerSeriesTotal ?: 0))
                    else -> combine(
                        getApplication<IPTVApp>().database.vodDao().countMoviesFlow(pl.id),
                        getApplication<IPTVApp>().database.vodDao().countSeriesFlow(pl.id)
                    ) { m, s -> m to s }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    /** ly_update: the reload is running (progress dialog visible). */
    val refreshing = MutableStateFlow(false)

    /** ly_update finished OK — the reference toasts "Login Successfully". */
    val refreshSucceeded = MutableStateFlow(false)

    /**
     * v1.18.1 — the 4th hub card's numbers (user request: a Saved Videos
     * entry next to Movies / Series / Live): how many auto-saved /
     * downloaded videos sit in the offline library, and when the newest
     * one landed (the card's "Last Update" strip shows it time-ago). The
     * SAME SavedLibrary.scan the library page uses — one filesystem pass,
     * identical semantics, re-scanned on every hub entry and after each
     * ly_update reload (a save may have finalized while away).
     */
    private val _savedSummary = MutableStateFlow(0L to 0L)   // (count, newestLastModifiedMs)
    val savedSummary: StateFlow<Pair<Long, Long>> = _savedSummary

    fun rescanSaved() {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val list = try { SavedLibrary.scan(getApplication()) } catch (_: Throwable) { emptyList() }
                list.size.toLong() to (list.firstOrNull()?.lastModifiedMs ?: 0L)
            }
            _savedSummary.value = summary
        }
    }

    /**
     * v1.7.0 — the reference's HomeActivity behavior: entering the hub with
     * a PORTAL account kicks the (background) stalker EPG download
     * (startStalkerEpgDownloadService + the 5 h freshness check — that
     * staleness gate lives inside StalkerEpgRepository.bulkEpg's TTL). The
     * ~15 MB payload is then already cached when the user reaches the
     * channel view, so the rows' "now" lines fill instantly. Silent on
     * every failure — the channel view simply re-fetches on demand.
     */
    private var epgWarmed = false

    init {
        rescanSaved()
        viewModelScope.launch {
            playlist.collect { pl ->
                if (pl != null && pl.type == "PORTAL" && !epgWarmed) {
                    epgWarmed = true
                    try {
                        StalkerEpgRepository.get(getApplication()).bulkEpg(pl)
                    } catch (_: Throwable) {
                        // silent — ChannelViewViewModel retries on entry
                    }
                }
            }
        }
    }

    /**
     * ly_update — re-sync the active playlist. Failure is silent apart from
     * the dialog closing (the reference's reload has no error toast either;
     * the data simply stays as-is).
     */
    fun refresh() {
        val pl = playlist.value ?: return
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                repo.syncPlaylist(pl)
                refreshSucceeded.value = true
            } catch (_: Exception) {
                // keep the old data; the strip keeps the previous stamp
            } finally {
                refreshing.value = false
                // a save may have finalized while the app was away — the
                // 4th card's count should reflect it immediately.
                rescanSaved()
            }
        }
    }

    fun consumeRefreshSuccess() {
        refreshSucceeded.value = false
    }
}

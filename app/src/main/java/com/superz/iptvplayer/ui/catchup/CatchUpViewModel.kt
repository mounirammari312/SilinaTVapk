package com.superz.iptvplayer.ui.catchup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.data.epg.EpgRepository
import com.superz.iptvplayer.data.stalker.StalkerEpgRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.12.3 — Catch-Up screen state, replicated 1:1 from the reference's
 * CatchUpActivity:
 *
 *  • Categories = the live categories that own at least one tv-archive
 *    channel (getCatchUpCategoryModels — the DAO's INNER JOIN on
 *    tv_archive = 1, the reference's getLiveCatchupChannelsByCategory
 *    count per category), displayed as the reference's 2-column grid of
 *    "Name (count)" cards.
 *  • Selecting a category shows its catch-up channels (showCatchChannels —
 *    the "CATCH UP/Live" list state, title flips exactly like the
 *    reference's txt_header).
 *  • A channel opens the day-tabbed Catch-Up detail screen
 *    (CatchUpDetailActivity → our CatchUpDetailScreen).
 *
 * v1.12.3 EMPTY-LIST FIX: the flag re-sync runs in the APPLICATION scope
 * (survives the screen closing — completes exactly once), is VISIBLE
 * (UiState.syncing → spinner), the channel list is observed REACTIVELY,
 * and a failed sync surfaces UiState.syncFailed with a Retry action.
 *
 * v1.12.4 — three additions:
 *  1. THE "All" CARD (reference parity): the portal's own genre list starts
 *     with `{"id":"*","title":"All"}` and getCatchUpCategoryModels iterates
 *     it — so the reference's grid ALWAYS shows "All (N)" first (N = archive
 *     channels outside the xxx categories, RealmController's all_id branch).
 *     Our categories JOIN can't produce that row (no channel carries genre
 *     "*"), so CatchUpCategories.allCard re-adds it.
 *  2. THE DAILY app-start re-sync (DailySync — the reference's actual
 *     architecture) keeps the flags fresh globally; the screen's OWN heal
 *     gate now ALSO fires when the category rows are missing (a once-failed
 *     best-effort genres fetch left the categories table empty → dead grid
 *     forever, CatchUpCategories.needsResync).
 *  3. THE EMPTY STATE carries counts (channels / archive / categories) so a
 *     still-empty grid pinpoints the broken layer at a glance instead of
 *     reporting a bare "No Catch Up Found".
 */
class CatchUpViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    data class UiState(
        val playlistId: Long = -1L,
        /** (category, catch channel count) — the grid cards "Name (count)". */
        val categories: List<Pair<Category, Int>> = emptyList(),
        /** null = the category GRID (the reference's is_channel = false). */
        val selectedCategoryId: String? = null,
        val channels: List<Channel> = emptyList(),
        val loading: Boolean = true,
        /** v1.12.3 — visible one-time flag re-sync in progress. */
        val syncing: Boolean = false,
        /** v1.12.3 — the flag re-sync failed (Retry offered). */
        val syncFailed: Boolean = false,
        /** v1.12.3 — channel key → the current program (row sub-line). */
        val nowPrograms: Map<String, EpgProgram> = emptyMap(),
        /** v1.12.4 — the empty state's diagnostic counts (see KDoc above). */
        val totalChannels: Int = 0,
        val archiveChannels: Int = 0,
        val categoryRows: Int = 0
    ) {
        val inChannelMode: Boolean get() = selectedCategoryId != null
    }

    private val appCtx = getApplication<IPTVApp>()
    private val db = appCtx.database

    /** Route arg; -1 (from the hub card) resolves to the ACTIVE playlist. */
    private val routePlaylistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L

    /** The effective playlist (active playlist when the route said -1). */
    private var playlistId: Long = routePlaylistId

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** All tv-archive channels (reactive — reflects re-syncs). */
    private var allChannels: List<Channel> = emptyList()

    /** v1.12.5 — the ONE xxx category excluded from the "All" card /
     *  list (the reference's Constants.xxx_category_id — LAST name match,
     *  never the plural set). */
    private var xxxIds: Set<String> = emptySet()

    /** v1.12.5 — the single-id exclusion set (empty when no xxx category). */
    private suspend fun xxxExcludedSet(): Set<String> = try {
        val pl = db.playlistDao().playlistById(playlistId)
        val id = com.superz.iptvplayer.ui.components.ParentalControl.xxxExcludedId(
            db.channelDao().xxxCandidateCategories(playlistId),
            portal = pl?.type == "PORTAL"
        )
        if (id == null) emptySet() else setOf(id)
    } catch (_: Throwable) {
        emptySet()
    }

    private var observeJob: Job? = null
    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            // v1.12.6 — the sentinel resolution now shares CatchUpNav's
            // unit-tested rule with the detail screen (hub route -1 → the
            // ACTIVE playlist's real id; UiState.playlistId is what the
            // channel-row click must propagate — and now does).
            playlistId = CatchUpNav.effectivePlaylistId(
                routePlaylistId,
                db.playlistDao().activePlaylist()?.id
            )
            if (playlistId <= 0) {
                _ui.update { it.copy(loading = false) }
                return@launch
            }
            _ui.update { it.copy(playlistId = playlistId) }

            val pl = db.playlistDao().playlistById(playlistId)
            val all = db.channelDao().catchupChannelsFlow(playlistId).first()
            val cats = db.channelDao().catchupCategoriesFor(playlistId)
            val flagged = db.channelDao().tvArchiveFlaggedCount(playlistId)
            if (pl != null &&
                (pl.type == "PORTAL" || pl.type == "XTREAM") &&
                CatchUpCategories.needsResync(
                    channelCount = pl.channelCount,
                    tvArchiveFlaggedRows = flagged,
                    archiveChannelCount = all.size,
                    categoryCount = cats.size
                )
            ) {
                // v1.12.3 UPGRADE PATH + v1.12.4 category repair: the flags
                // never landed (pre-v1.12.0 sync) OR the category rows are
                // missing (a once-failed best-effort genres fetch) — ONE
                // re-sync of the live list repopulates both; the portal/
                // panel itself is the source of truth. Runs in the
                // APPLICATION scope (never cancelled by leaving the screen),
                // VISIBLE in the UI, Retry-able on failure.
                resyncFlags(pl)
            } else {
                allChannels = adoptTimeMachineFallback(pl, all)
                publishChannels(allChannels)
            }
        }
    }

    /** The visible, non-cancellable flag re-sync + reactive publish. */
    private fun resyncFlags(pl: Playlist) {
        _ui.update { it.copy(syncing = true, syncFailed = false) }
        appCtx.applicationScope.launch {
            var ok = false
            try {
                PlaylistRepository.get(appCtx).syncPlaylist(pl)
                ok = true
            } catch (_: Throwable) {
                // offline / portal hiccup — surfaced as Retry, never silent
            }
            _ui.update { it.copy(syncing = false, syncFailed = !ok) }
            // Whatever the sync outcome, adopt whatever the DB now holds
            // (v1.18.0: with the TimeMachine fallback when the panel flags
            // NO channel at all — the section is never dead).
            val now = db.channelDao().catchupChannelsFlow(playlistId).first()
            allChannels = adoptTimeMachineFallback(pl, now)
            publishChannels(allChannels)
        }
    }

    /**
     * v1.18.0 — TimeMachine grid fallback (the reference's
     * TimeMachineEngine.getCatchupChannels, flag-agnostic): when the panel
     * flags ZERO channels with tv_archive (many XUI panels don't send the
     * flag at all), the catch-up grid would stay dead forever. Instead:
     * every XTREAM live channel with a numeric stream id is offered — the
     * SERVER decides per rewind whether it actually plays (exactly the
     * reference's dashboard TimeMachine behavior). PORTAL and flagged lists
     * pass through untouched.
     */
    private suspend fun adoptTimeMachineFallback(
        pl: Playlist?,
        flagged: List<Channel>
    ): List<Channel> {
        if (flagged.isNotEmpty() || pl == null || pl.type != "XTREAM") return flagged
        return try {
            val liveList = db.channelDao().channelsFlow(playlistId, null, "").first()
            com.superz.iptvplayer.data.timemachine.TimeMachineEngine
                .getCatchupChannels(pl, liveList)
        } catch (_: Throwable) {
            flagged
        }
    }

    /** Retry entry (the empty screen's action row). */
    fun retrySync() {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId) ?: return@launch
            resyncFlags(pl)
        }
    }

    /** Publishes channels + categories and starts the reactive observer. */
    private suspend fun publishChannels(all: List<Channel>) {
        xxxIds = xxxExcludedSet()
        val joinCats = db.channelDao().catchupCategoriesFor(playlistId)
            .map { row -> Category(playlistId, row.categoryId, row.name) to row.count }
        // v1.12.4 — the reference's "All (N)" first card (get_genres' own
        // "*" row iterated by getCatchUpCategoryModels).
        val cats = listOfNotNull(CatchUpCategories.allCard(playlistId, all, xxxIds)) + joinCats
        _ui.update {
            it.copy(
                loading = false,
                categories = cats,
                channels = CatchUpCategories.channelsFor(all, it.selectedCategoryId, xxxIds),
                totalChannels = db.playlistDao().playlistById(playlistId)?.channelCount ?: all.size,
                archiveChannels = all.size,
                categoryRows = joinCats.size
            )
        }
        startObserver()
        loadNowPrograms(all)
    }

    /**
     * v1.12.3 — reactive: any later DB change (the re-sync's persist(), a
     * manual re-sync elsewhere, the DAILY app-start sync) fills the screen
     * immediately.
     */
    private fun startObserver() {
        if (observeJob?.isActive == true) return
        observeJob = appCtx.applicationScope.launch {
            db.channelDao().catchupChannelsFlow(playlistId).collect { all ->
                if (all.isNotEmpty()) {
                    allChannels = all
                    xxxIds = xxxExcludedSet()
                    val joinCats = db.channelDao().catchupCategoriesFor(playlistId)
                        .map { row -> Category(playlistId, row.categoryId, row.name) to row.count }
                    val cats = listOfNotNull(CatchUpCategories.allCard(playlistId, all, xxxIds)) + joinCats
                    _ui.update { st ->
                        st.copy(
                            categories = cats,
                            channels = CatchUpCategories.channelsFor(all, st.selectedCategoryId, xxxIds),
                            archiveChannels = all.size,
                            categoryRows = joinCats.size
                        )
                    }
                }
            }
        }
    }

    /**
     * v1.12.3 — the channel rows' program sub-line + progress
     * (CatchChannelListRecyclerAdapter: getEpgModel → programme_title +
     * progress). PORTAL: ONE bulk EPG call (5 h cache); XTREAM: chunked
     * get_short_epgs (cached). Failures simply leave "No Information".
     */
    private fun loadNowPrograms(channels: List<Channel>) {
        if (channels.isEmpty()) return
        epgJob?.cancel()
        epgJob = appCtx.applicationScope.launch {
            val pl = try {
                db.playlistDao().playlistById(playlistId)
            } catch (_: Throwable) {
                null
            } ?: return@launch
            val map = try {
                when (pl.type) {
                    "PORTAL" -> {
                        // stalker bulk rows are keyed by channel id → "k:<id>";
                        // the row's program = the entry covering NOW.
                        val nowMs = System.currentTimeMillis()
                        val bulk = StalkerEpgRepository.get(appCtx).bulkEpg(pl, nowMs)
                        bulk.entries.mapNotNull { (id, programs) ->
                            programs.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs }
                                ?.let { p -> "k:$id" to p }
                        }.toMap()
                    }
                    "XTREAM" -> {
                        val ids = channels.mapNotNull { it.streamId }
                        val short = EpgRepository.get(appCtx.okHttp).shortEpg(pl, ids)
                        channels.mapNotNull { ch ->
                            ch.streamId?.let { sid ->
                                short[sid]?.let { p -> ch.key to p }
                            }
                        }.toMap()
                    }
                    else -> emptyMap()
                }
            } catch (_: Throwable) {
                emptyMap<String, EpgProgram>()
            }
            _ui.update { it.copy(nowPrograms = map) }
        }
    }

    /** showCatchChannels — a category's catch-up channels (null = back to grid). */
    fun selectCategory(categoryId: String?) {
        _ui.update { st ->
            st.copy(
                selectedCategoryId = categoryId,
                channels = CatchUpCategories.channelsFor(allChannels, categoryId, xxxIds)
            )
        }
    }

    override fun onCleared() {
        // The re-sync itself KEEPS RUNNING in the application scope — that is
        // the v1.12.3 fix. Only the reactive observers die with the screen.
        observeJob?.cancel()
        epgJob?.cancel()
        super.onCleared()
    }
}

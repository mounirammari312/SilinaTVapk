package com.superz.iptvplayer.ui.channelview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.data.epg.EpgRepository
import com.superz.iptvplayer.data.stalker.StalkerEpgParser
import com.superz.iptvplayer.data.stalker.StalkerEpgRepository
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.player.Engine
import com.superz.iptvplayer.player.PlayAttempt
import com.superz.iptvplayer.player.PlayerSessionManager
import com.superz.iptvplayer.player.SmartPlayer
import com.superz.iptvplayer.player.multiscreen.MultiScreenSession
import com.superz.iptvplayer.ui.components.ParentalControl
import com.superz.iptvplayer.ui.player.SubtitleActivation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.7.0 — the EPG lookup id of a channel: XC channels carry their
 * stream_id; PORTAL (stalker) channels carry the portal's numeric id in
 * their DB key ("k:<id>", written by syncStalker). Shared by the row
 * fetch/lookup (ViewModel) and the row rendering (Screen).
 */
fun Channel.epgLookupId(): Long? =
    streamId ?: key.takeIf { it.startsWith("k:") }?.removePrefix("k:")?.toLongOrNull()

/**
 * Channel-view screen state: the split-view page that shows the mini
 * player + channel list + EPG.
 *
 * OWNS the playback session: the SmartPlayer is created once here and
 * shared with the fullscreen player through [PlayerSessionManager] —
 * expanding to fullscreen continues the SAME live stream.
 */
class ChannelViewViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app), SmartPlayer.Listener {

    data class UiState(
        val playlist: Playlist? = null,
        val categories: List<Category> = emptyList(),
        val selectedCategoryId: String? = null,
        val channels: List<Channel> = emptyList(),
        val channel: Channel? = null,
        val engine: Engine? = null,
        val buffering: Boolean = false,
        val bufferingPercent: Int? = null,
        val isPlaying: Boolean = false,
        val fatal: Boolean = false,
        // EPG — channel rows ("now" line)
        val nowByStreamId: Map<Long, EpgProgram> = emptyMap(),
        // EPG — right panel (full table of the selected channel)
        val epgLoading: Boolean = false,
        val epgPrograms: List<EpgProgram> = emptyList(),
        val epgAvailable: Boolean = true,
        /** v1.12.0 — parental: the XXX category id awaiting the PIN gate
         *  (the reference's showParentalControlDlg on category select).
         *  v1.19.15 — pinGateEntry: THIS gate came from the ENTRY (the screen
         *  was opened onto an adult category), not a ◀ ▶ category switch —
         *  the verify plays the TAPPED channel directly and a dismiss LEAVES
         *  the screen (see onPinVerified / ChannelViewScreen). */
        val pinGateFor: String? = null,
        val pinGateEntry: Boolean = false
    )

    private val appCtx = getApplication<IPTVApp>()
    private val repo = PlaylistRepository.get(appCtx)
    private val db = appCtx.database
    private val epgRepo = EpgRepository.get(appCtx.okHttp)

    /** v1.7.0 — the stalker EPG store (bulk rows + day tables). */
    private val stalkerEpg = StalkerEpgRepository.get(appCtx)

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L
    private val initialKey: String = savedStateHandle.get<String>("channelKey") ?: ""
    private val initialCategoryId: String? =
        savedStateHandle.get<String>("categoryId")?.takeIf { it != "all" }
    private val query: String = savedStateHandle.get<String>("query") ?: ""
    private val favoritesOnly: Boolean = savedStateHandle.get<Boolean>("favoritesOnly") ?: false

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val session: PlayerSessionManager.Session =
        PlayerSessionManager.ensure(appCtx, viewModelScope, appCtx.okHttp)

    /** Active ExoPlayer for view binding (read-only surface re-binding). */
    val activeExoPlayer get() = session.smartPlayer.activeExoForView

    private var playlist: Playlist? = null
    private var channels: List<Channel> = emptyList()
    private var currentIndex = -1

    // Debounce jobs for EPG fetches.
    private var rowsEpgJob: Job? = null
    private var fullEpgJob: Job? = null

    // v2.3.1 — MULTI-SCREEN ENGINE HAND-OFF state.
    private var unregisterEnginePark: (() -> Unit)? = null

    init {
        session.smartPlayer.isPreloadEnabled = appCtx.isUnmeteredNetwork()
        session.dispatcher.activate(this)
        // v2.3.1 — MULTI-SCREEN ENGINE HAND-OFF: the mini player's engine
        // registers with the session registry so the grid can RELEASE it
        // when it truly starts (the codec-budget hand-off — see
        // MultiScreenSession). Resume here is the natural one: tapping
        // any channel row re-plays through the standard path.
        unregisterEnginePark = MultiScreenSession.registerEngineReleaser {
            try {
                session.smartPlayer.release()
            } catch (_: Exception) {
            }
            _ui.update { it.copy(isPlaying = false, buffering = false) }
        }

        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId) ?: return@launch
            playlist = pl
            session.playlist = pl
            session.memoryMap.putAll(repo.engineMemory(pl.id))
            session.smartPlayer.engineMemory = session.memoryMap

            // Category context: entering from a channel card carries the
            // channel's own category — the list opens exactly there.
            // (v1.12.0: categories go into the state FIRST so loadChannels
            //  sees them for the parental "All" exclusion.)
            // v1.19.15 FIX: selectedCategoryId goes in FIRST TOO — before,
            // loadChannels ran while it was still NULL, so EVERY entry loaded
            // the "All" list (adult channels EXCLUDED): the tapped adult
            // channel was not even IN the list (idx fell back to 0 → a WRONG
            // channel played behind the gate), and every other category entry
            // showed "All" instead of the entry category's own channels.
            val categories = db.channelDao().categoriesWithCountFlow(pl.id).first()
                .map { Category(pl.id, it.categoryId, it.name) }
            _ui.update {
                it.copy(
                    playlist = pl,
                    categories = categories,
                    selectedCategoryId = initialCategoryId
                )
            }

            // v1.12.0 — parental: an XXX entry category (a channel card from
            // an adult category) is gated like the reference's first-category
            // check — the PIN dialog covers the list until verified.
            // v1.19.15 FIX: the entry gate now ARMS pendingCategoryId (before,
            // only pinGateFor was set — onPinVerified read a NULL pending id
            // and NEVER ran applyCategory, so the adult section's channel list
            // never loaded after a correct PIN: the gate closed over the stale
            // "All" list and nothing played until the user switched the
            // category away and back — exactly the reported workaround).
            val gatedEntry = initialCategoryId?.let { id ->
                categories.firstOrNull { it.categoryId == id }
                    ?.takeIf { ParentalControl.isXxxName(it.name) }
            }
            if (gatedEntry != null) {
                pendingCategoryId = gatedEntry.categoryId
                entryGatePending = true
            }
            val list = loadChannels(pl)

            channels = list
            session.channels = list
            val idx = list.indexOfFirst { it.key == initialKey }.takeIf { it >= 0 } ?: 0
            currentIndex = idx
            session.currentIndex = idx

            _ui.update {
                it.copy(
                    selectedCategoryId = initialCategoryId,
                    channels = list,
                    channel = list.getOrNull(idx),
                    engine = session.dispatcher.lastEngine,
                    pinGateFor = gatedEntry?.categoryId,
                    pinGateEntry = gatedEntry != null
                )
            }

            // v1.19.15 — a GATED entry defers playback: no adult stream (not
            // even its audio) runs behind the PIN gate; the tapped channel
            // starts only after verify (onPinVerified → applyCategory(playKey)).
            // Every other entry plays the tapped channel immediately as before.
            if (gatedEntry == null) {
                list.getOrNull(idx)?.let { ch ->
                    session.smartPlayer.preloadNext(pl, list.getOrNull(idx + 1))
                    playResolved(pl, ch)
                }
            }
            scheduleRowsEpg(list)
        }
    }

    /**
     * v1.5.0 — play entry point: PORTAL (stalker) channels resolve their
     * time-limited URL via create_link FIRST (StalkerPlayback — cached
     * handshake, so zapping costs one call); every other type passes
     * through untouched into the SAME engine call as before. The resolved
     * channel carries the URL in `directUrl`, which StreamUrls (iron-rule
     * EngineRouter.kt) plays natively for any playlist type.
     */
    private fun playResolved(pl: Playlist, ch: Channel) {
        viewModelScope.launch {
            val resolved = StalkerPlayback.resolveChannel(appCtx, pl, ch)
            session.smartPlayer.play(pl, resolved)
        }
    }

    private suspend fun loadChannels(pl: Playlist): List<Channel> {
        // v1.12.5 — parental: the "All" list EXCLUDES exactly ONE xxx
        // category (the reference's getLiveChannelsByCategory(all):
        // notEqualTo(category_id, Constants.xxx_category_id) — the LAST
        // name match, never the plural set); the xxx categories themselves
        // stay reachable through the PIN gate only.
        val xxxId = try {
            ParentalControl.xxxExcludedId(
                db.channelDao().xxxCandidateCategories(pl.id),
                portal = pl.type == "PORTAL"
            )
        } catch (_: Throwable) {
            null
        }
        return if (favoritesOnly) {
            db.channelDao().favoritesFlow(pl.id, _ui.value.selectedCategoryId, query).first()
        } else if (_ui.value.selectedCategoryId == null && xxxId != null) {
            db.channelDao().channelsFlowExcluding(pl.id, null, query, false, listOf(xxxId)).first()
        } else {
            db.channelDao().channelsFlow(pl.id, _ui.value.selectedCategoryId, query).first()
        }
    }

    // ── View binding (surface hand-over with the fullscreen player) ──

    fun attachExoView(view: androidx.media3.ui.PlayerView) =
        session.smartPlayer.attachViews(view)

    fun attachVlcView(view: org.videolan.libvlc.util.VLCVideoLayout) =
        session.smartPlayer.attachVlcView(view)

    fun detachExoView(view: androidx.media3.ui.PlayerView) =
        session.smartPlayer.detachExoView(view)

    fun detachVlcView(view: org.videolan.libvlc.util.VLCVideoLayout) =
        session.smartPlayer.detachVlcView(view)

    /** Re-register as the engine listener (returning from fullscreen). */
    fun activate() {
        session.dispatcher.activate(this)
        // v1.7.0 — re-assert the subtitle setting (it may have been toggled
        // in Settings while we were covered).
        SubtitleActivation.apply(activeExoPlayer, appCtx)
        // The fullscreen player may have zapped while it was driving —
        // re-sync from the shared session state.
        val idx = session.currentIndex
        if (idx != currentIndex && idx >= 0) {
            currentIndex = idx
            _ui.update { it.copy(channel = channels.getOrNull(idx)) }
        }
    }

    fun deactivate() {
        session.dispatcher.deactivate(this)
    }

    // ── Zap (same player, same place) ───────────────────────────

    fun selectChannel(key: String) {
        val idx = channels.indexOfFirst { it.key == key }
        if (idx >= 0) playAt(idx)
    }

    private fun playAt(idx: Int) {
        val pl = playlist ?: return
        val ch = channels.getOrNull(idx) ?: return
        currentIndex = idx
        session.currentIndex = idx
        _ui.update {
            it.copy(channel = ch, fatal = false, isPlaying = false)
        }
        playResolved(pl, ch)
    }

    fun retry() {
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        _ui.update { it.copy(fatal = false) }
        playResolved(pl, ch)
    }

    /**
     * v2.3.0 — MULTI-SCREEN hand-off: the mini player's grid key was
     * pressed — park THIS stream deterministically before the grid's
     * players take the audio (the system focus loss would pause it a
     * beat later anyway; parking it ourselves keeps the transition
     * silent and instant). The session survives under the setup route.
     */
    fun pauseForMultiScreen() {
        try {
            if (session.smartPlayer.isPlaying) session.smartPlayer.togglePlayPause()
        } catch (_: Exception) {
        }
    }

    // ── Category switching (◀ ▶) — playback never stops ─────────

    /** v1.12.0 — parental: XXX categories unlocked this session. */
    private val unlockedCategories = mutableSetOf<String>()

    /** v1.12.0 — parental: the category awaiting the PIN gate's verdict.
     *  v1.19.15 — armed for the ENTRY gate too (init), not just switches. */
    private var pendingCategoryId: String? = null

    /** v1.19.15 — the armed gate came from the screen ENTRY (true) or a
     *  ◀ ▶ category switch (false); verify behaves differently for each. */
    private var entryGatePending = false

    fun switchCategory(forward: Boolean) {
        val pl = playlist ?: return
        val cats = _ui.value.categories
        if (cats.isEmpty()) return

        // Cycle: All → cat1 → … → catN → All
        val currentId = _ui.value.selectedCategoryId
        val position = if (currentId == null) -1 else cats.indexOfFirst { it.categoryId == currentId }
        val nextPos = if (forward) {
            if (position >= cats.size - 1) -1 else position + 1
        } else {
            if (position <= 0) cats.size - 1 else position - 1
        }
        val nextId = if (nextPos < 0) null else cats[nextPos].categoryId

        if (nextId == currentId) return

        // v1.12.0 — parental gate (the reference's isXXX + showParentalControlDlg
        // on category click): an XXX category asks for the PIN first; a wrong
        // PIN keeps the list where it was ("your_pincode_is_incorrect").
        if (nextId != null) {
            val target = cats.getOrNull(nextPos)
            if (target != null &&
                ParentalControl.isXxxName(target.name) &&
                nextId !in unlockedCategories
            ) {
                pendingCategoryId = nextId
                entryGatePending = false
                _ui.update { it.copy(pinGateFor = nextId, pinGateEntry = false) }
                return
            }
        }
        applyCategory(nextId)
    }

    /** PIN verified → unlock the pending category and open it.
     *
     * v1.19.15 — the ENTRY gate finally WORKS: init arms pendingCategoryId,
     * so a correct PIN actually loads the gated section — and because the
     * user entered by tapping a specific channel card, the verify plays
     * THAT channel directly in the mini player ("فتح القناة مباشرة في
     * المشغل المصغر") instead of leaving a stale "All" list with nothing
     * running. A verified SWITCH keeps the old contract: the list reloads,
     * the current stream keeps rolling until the user taps a row. */
    fun onPinVerified() {
        val target = pendingCategoryId
        val wasEntry = entryGatePending
        entryGatePending = false
        pendingCategoryId = null
        _ui.update { it.copy(pinGateFor = null, pinGateEntry = false) }
        if (target != null) {
            unlockedCategories.add(target)
            applyCategory(target, playKey = if (wasEntry) initialKey else null)
        }
    }

    /** PIN dialog dismissed / wrong → drop the pending switch.
     *  (v1.19.15: an ENTRY gate's dismiss is routed by the SCREEN to onBack()
     *  — leaving the page entirely; this branch serves switches.) */
    fun onPinDismissed() {
        val target = pendingCategoryId
        entryGatePending = false
        pendingCategoryId = null
        _ui.update { it.copy(pinGateFor = null, pinGateEntry = false) }
        // A gated ENTRY category falls back to "All" (the list behind the
        // dialog was already the adult one — swapping keeps the contract).
        if (target != null && _ui.value.selectedCategoryId == target && channels.isNotEmpty()) {
            applyCategory(null)
        }
    }

    /** Loads + publishes the category's channel list.
     *  v1.19.15 — playKey: after the list lands, the channel with that key
     *  PLAYS directly (the verified entry's tapped channel); null (default)
     *  keeps the switching contract — playback never stops on a switch. */
    private fun applyCategory(nextId: String?, playKey: String? = null) {
        val pl = playlist ?: return
        viewModelScope.launch {
            _ui.update { it.copy(selectedCategoryId = nextId) }
            val list = loadChannels(pl)
            channels = list
            session.channels = list
            _ui.update { it.copy(channels = list) }
            scheduleRowsEpg(list)
            if (playKey != null && list.isNotEmpty()) {
                val idx = list.indexOfFirst { it.key == playKey }.takeIf { it >= 0 } ?: 0
                playAt(idx)
            }
        }
    }

    // ── Aspect ratio ────────────────────────────────────────────

    fun cycleAspect(): Int = session.smartPlayer.cycleResizeMode()

    // ── EPG ─────────────────────────────────────────────────────

    /**
     * "Now" lines under the channel rows (debounced, chunked, cached).
     * v1.7.0 — PORTAL playlists use the reference's BULK stalker EPG
     * (get_epg_info, one download for every row, cached 5 h); XC keeps the
     * get_short_epgs path. Both fill the same nowByStreamId map keyed by
     * [Channel.epgLookupId].
     */
    private fun scheduleRowsEpg(list: List<Channel>) {
        rowsEpgJob?.cancel()
        rowsEpgJob = viewModelScope.launch {
            delay(350) // let the list settle first
            val pl = playlist ?: return@launch
            try {
                if (pl.type == "PORTAL") {
                    val ids = list.mapNotNull { it.epgLookupId() }
                    if (ids.isEmpty()) return@launch
                    val nowMs = System.currentTimeMillis()
                    val bulk = stalkerEpg.bulkEpg(pl, nowMs)
                    val nowLines = HashMap<Long, EpgProgram>(ids.size)
                    for (id in ids) {
                        bulk[id]?.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs }
                            ?.let { nowLines[id] = it }
                    }
                    _ui.update { it.copy(nowByStreamId = nowLines) }
                } else {
                    val ids = list.mapNotNull { it.streamId }
                    if (ids.isEmpty()) return@launch
                    val now = epgRepo.shortEpg(pl, ids)
                    _ui.update { it.copy(nowByStreamId = now) }
                }
            } catch (_: Throwable) {
                // Silent: rows simply keep no "now" line.
            }
        }
    }

    /** Full EPG table of the selected channel for the right panel. */
    private fun scheduleFullEpg(channel: Channel) {
        fullEpgJob?.cancel()
        // v1.5.0 — Settings → General → "Show Full EPG" gate (the
        // reference's GeneralSettingMenu default: on).
        if (!com.superz.iptvplayer.ui.settings.VuSettingsPrefs.showFullEpg(appCtx)) {
            _ui.update { it.copy(epgLoading = false, epgPrograms = emptyList(), epgAvailable = false) }
            return
        }
        val epgId = channel.epgLookupId() ?: run {
            _ui.update { it.copy(epgLoading = false, epgPrograms = emptyList(), epgAvailable = false) }
            return
        }
        val isPortal = playlist?.type == "PORTAL"
        _ui.update { it.copy(epgLoading = true) }
        fullEpgJob = viewModelScope.launch {
            val pl = playlist ?: return@launch
            try {
                // v1.7.0 — PORTAL: the reference's get_simple_data_table day
                // table (paginated, today in device tz). XC: unchanged.
                val programs = if (isPortal) {
                    stalkerEpg.dayTable(pl, epgId, StalkerEpgParser.todayMysql())
                } else {
                    epgRepo.fullEpg(pl, epgId)
                }
                _ui.update {
                    it.copy(
                        epgLoading = false,
                        epgPrograms = programs,
                        epgAvailable = programs.isNotEmpty()
                    )
                }
            } catch (_: Throwable) {
                _ui.update { it.copy(epgLoading = false, epgPrograms = emptyList(), epgAvailable = false) }
            }
        }
    }

    // ── SmartPlayer.Listener ────────────────────────────────────

    override fun onChannelChanged(channel: Channel) {
        _ui.update { it.copy(channel = channel, fatal = false, buffering = true) }
        // v1.16.0 — a mini-player zap ends the side-loaded subtitle
        // session-wide (the fresh MediaItem carries no subtitle
        // configuration); the fullscreen player's state resets in its own
        // onChannelChanged, this keeps the shared override honest when the
        // zap happens HERE.
        SubtitleActivation.sideLoadedLanguage = null
        // v1.7.0 — subtitle setting drives the (persistent) Exo track
        // parameters at every zap; harmless when the engine is VLC.
        SubtitleActivation.apply(activeExoPlayer, appCtx)
        scheduleFullEpg(channel)
    }

    override fun onEngineAttempt(engine: Engine, attemptIndex: Int, totalAttempts: Int) {
        _ui.update { it.copy(engine = engine) }
    }

    override fun onBuffering(buffering: Boolean, percent: Int?) {
        _ui.update { it.copy(buffering = buffering, bufferingPercent = percent) }
    }

    override fun onFirstFrame(engine: Engine, ttffMs: Long, attempt: PlayAttempt) {
        _ui.update { it.copy(buffering = false, isPlaying = true, engine = engine) }
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        // Remember the winning (engine, variant) — the zap accelerator.
        val memory = EngineMemory(pl.id, ch.key, engine.name, attempt.variant)
        session.memoryMap[ch.key] = memory
        session.smartPlayer.engineMemory = session.memoryMap
        viewModelScope.launch {
            try {
                repo.rememberEngine(pl.id, ch.key, engine.name, attempt.variant)
            } catch (_: Exception) {
            }
        }
    }

    override fun onPlaying(engine: Engine) {
        _ui.update { it.copy(buffering = false, isPlaying = true, engine = engine) }
        // v1.7.0 — the ExoPlayer exists for sure now: assert subtitles once
        // more (first play starts AFTER onChannelChanged, when the player
        // instance may not have existed yet).
        if (engine == Engine.EXO) SubtitleActivation.apply(activeExoPlayer, appCtx)
        val pl = playlist ?: return
        session.smartPlayer.preloadNext(pl, channels.getOrNull(currentIndex + 1))
        session.smartPlayer.preconnect(
            pl,
            listOf(
                channels.getOrNull(currentIndex + 1),
                channels.getOrNull(currentIndex - 1),
                channels.getOrNull(currentIndex + 2)
            )
        )
    }

    override fun onPlayPauseChanged(isPlaying: Boolean) {
        _ui.update { it.copy(isPlaying = isPlaying) }
    }

    override fun onFatalError() {
        _ui.update { it.copy(fatal = true, buffering = false, isPlaying = false) }
    }

    /**
     * v1.12.2 — the reference's auto-restart (LivePlayActivity:
     * onPlaybackStateChanged(4) → replay / onPlayerError(1002) → fresh
     * getLiveStreamUrl): the playing stream was cut by the CDN — re-resolve
     * the CURRENT channel (fresh create_link for PORTAL) and keep the mini
     * player running. The SmartPlayer's strike budget guards against a
     * dead-portal loop.
     */
    override fun onStreamEnded() {
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        android.util.Log.d("ChannelViewViewModel", "stream ended — auto-restart ${ch.name}")
        _ui.update { it.copy(fatal = false, buffering = true, isPlaying = false) }
        playResolved(pl, ch)
    }

    override fun onCleared() {
        // Leaving the channel-view page = leaving the session: playback stops.
        unregisterEnginePark?.invoke()
        unregisterEnginePark = null
        PlayerSessionManager.release()
        super.onCleared()
    }
}

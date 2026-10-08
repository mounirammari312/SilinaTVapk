package com.superz.iptvplayer.ui.multiscreen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.player.multiscreen.MultiLayout
import com.superz.iptvplayer.player.multiscreen.MultiScreenGrid
import com.superz.iptvplayer.player.multiscreen.MultiScreenManager
import com.superz.iptvplayer.player.multiscreen.MultiScreenPrefs
import com.superz.iptvplayer.player.multiscreen.MultiScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — the MULTI-SCREEN ViewModels (setup + playback).
 *
 *  [MultiScreenSetupViewModel] — the picker: layout choice, the four
 *  slots, the searchable channel list, the persisted last session, and
 *  the seed channel the player hands over (the channel you were
 *  watching lands in slot 1 — you keep watching it while you add the
 *  other screens around it).
 *
 *  [MultiScreenPlayerViewModel] — the grid: owns the [MultiScreenManager]
 *  (created here, released in onCleared), routes focus/fullscreen, and
 *  swaps a focused cell's channel from the slide-in picker (PORTAL
 *  channels re-resolved through create_link exactly like the single
 *  player's zaps).
 * ═══════════════════════════════════════════════════════════════════
 */

/** The setup screen's publishable state. */
data class MultiSetupUi(
    val playlist: Playlist? = null,
    val layout: MultiLayout = MultiLayout.FOUR,
    /** Slot-ordered assignments; null = empty slot. */
    val slots: List<Channel?> = listOf(null, null, null, null),
    /** The slot the next picked channel lands in. */
    val selectedSlot: Int = 0,
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val loaded: Boolean = false
)

class MultiScreenSetupViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val appCtx = getApplication<IPTVApp>()
    private val db = appCtx.database
    private val playlistId: Long =
        savedStateHandle.get<Long>("playlistId") ?: -1L
    private val seedKey: String =
        savedStateHandle.get<String>("channelKey") ?: ""

    private val _ui = MutableStateFlow(MultiSetupUi())
    val ui: StateFlow<MultiSetupUi> = _ui.asStateFlow()

    private val query = MutableStateFlow("")
    private var playlist: Playlist? = null
    private var allChannels: List<Channel> = emptyList()

    init {
        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId)
                ?: db.playlistDao().activePlaylist()
            playlist = pl

            // The FULL list first — the restore resolver and the picker's
            // blank-query state both read it (one DB round-trip).
            allChannels = try {
                db.channelDao().channelsFlow(pl?.id ?: -1L, null, "").first()
            } catch (_: Exception) {
                emptyList()
            }

            // Restore the last session (or defaults), then the player's seed.
            val savedLayout = MultiScreenPrefs.loadLayout(appCtx)
            val savedKeys = MultiScreenPrefs.loadKeys(appCtx)
            var slots: List<Channel?> = List(savedLayout?.cells ?: 4) { null }
            if (savedLayout != null && savedKeys.isNotEmpty()) {
                val byKey = allChannels.associateBy { it.key }
                slots = savedKeys.map { byKey[it] }.let { restored ->
                    List(savedLayout.cells) { i -> restored.getOrNull(i) }
                }
            }
            var layout = savedLayout ?: MultiLayout.FOUR

            if (seedKey.isNotBlank()) {
                val seed = allChannels.firstOrNull { it.key == seedKey }
                if (seed != null && MultiScreenGrid.isMultiScreenCapable(pl, seed)) {
                    // The watched channel lands in slot 1 and keeps playing
                    // there — the multi-screen grows AROUND it.
                    slots = slots.toMutableList().also { it[0] = seed }
                }
            }

            _ui.value = MultiSetupUi(
                playlist = pl,
                layout = layout,
                slots = slots.subList(0, layout.cells).let { List(layout.cells) { i -> it.getOrNull(i) } },
                selectedSlot = 0,
                query = "",
                channels = allChannels,
                loaded = true
            )
            advanceCursorToEmpty()

            // The reactive picker list (every letter re-queries through Room).
            query.collect { q ->
                val list = if (q.isBlank()) allChannels else try {
                    db.channelDao().channelsFlow(pl?.id ?: -1L, null, q).first()
                } catch (_: Exception) {
                    emptyList()
                }
                _ui.update { it.copy(query = q, channels = list) }
            }
        }
    }

    /** Selected slot → the first empty slot (the "assign and advance" cursor). */
    private fun advanceCursorToEmpty() {
        _ui.update { st ->
            st.copy(selectedSlot = MultiScreenGrid.nextEmptySlot(st.slots, st.selectedSlot))
        }
    }

    /** Search field letter → the list. */
    fun onSearch(q: String) {
        query.value = q
    }

    fun setLayout(layout: MultiLayout) {
        _ui.update {
            it.copy(
                layout = layout,
                slots = List(layout.cells) { i -> it.slots.getOrNull(i) },
                selectedSlot = it.selectedSlot.coerceAtMost(layout.cells - 1)
            )
        }
        advanceCursorToEmpty()
    }

    fun selectSlot(slot: Int) {
        _ui.update { it.copy(selectedSlot = slot.coerceIn(0, it.layout.cells - 1)) }
    }

    /**
     * A channel tapped in the list → the selected slot; cursor advances.
     * A channel already placed → the tap REMOVES it (the picker's most
     * forgiving toggle).
     */
    fun pick(channel: Channel) {
        if (!MultiScreenGrid.isMultiScreenCapable(playlist, channel)) return
        _ui.update { st ->
            if (st.slots.any { it?.key == channel.key }) {
                st.copy(slots = st.slots.map { if (it?.key == channel.key) null else it })
            } else {
                val slots = st.slots.toMutableList()
                slots[st.selectedSlot] = channel
                st.copy(slots = slots)
            }
        }
        advanceCursorToEmpty()
    }

    fun clearSlot(slot: Int) {
        _ui.update { st ->
            val slots = st.slots.toMutableList().also { it[slot] = null }
            st.copy(slots = slots, selectedSlot = slot)
        }
    }

    /** Compose + park + persist the config, then navigate. */
    fun start(onReady: () -> Unit) {
        val st = _ui.value
        val channels = st.slots.filterNotNull()
        if (channels.isEmpty()) return
        // v2.3.1 — THE CODEC-BUDGET HAND-OFF fires HERE, at the moment
        // the grid truly starts: every single-screen engine still alive
        // in the back stack (the player's, the channel view's mini
        // player's) is RELEASED before the grid's own players claim
        // their decoders. This is the v2.3.0 crash fix — the box was
        // asked for 2-4 fresh decoders while the parked single player
        // still held one, and answered fatally.
        MultiScreenSession.releaseSingleEngines()
        MultiScreenPrefs.save(appCtx, st.layout, st.slots.map { it?.key })
        MultiScreenSession.start(MultiScreenSession.Config(st.layout, channels))
        onReady()
    }
}

// ═══════════════════════════════════════════════════════════════════
//  THE PLAYBACK SCREEN
// ═══════════════════════════════════════════════════════════════════

/** The playback screen's publishable state. */
data class MultiPlayUi(
    val playlist: Playlist? = null,
    val layout: MultiLayout = MultiLayout.FOUR,
    val cells: List<MultiScreenManager.CellUi> = emptyList(),
    val focusedSlot: Int = 0,
    /** null = grid mode; a slot index = that cell fullscreen. */
    val fullscreenSlot: Int? = null,
    val pickerOpen: Boolean = false,
    /** The slot the picker writes into (the highlighted/aimed cell). */
    val pickerSlot: Int = 0,
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val loaded: Boolean = false
)

class MultiScreenPlayerViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val appCtx = getApplication<IPTVApp>()
    private val db = appCtx.database
    private val playlistId: Long =
        savedStateHandle.get<Long>("playlistId") ?: -1L

    /** THE GRID ENGINE — created here, released in onCleared. */
    val manager = MultiScreenManager(appCtx, appCtx.okHttp, viewModelScope)

    private val _ui = MutableStateFlow(MultiPlayUi())
    val ui: StateFlow<MultiPlayUi> = _ui.asStateFlow()

    private var playlist: Playlist? = null
    private val query = MutableStateFlow("")
    private var resolveJobs = mutableListOf<Job>()

    init {
        // Mirror the manager's cell states into the UI state.
        viewModelScope.launch {
            manager.cells.collect { cells -> _ui.update { it.copy(cells = cells) } }
        }
        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId)
                ?: db.playlistDao().activePlaylist()
            playlist = pl

            // 1) the parked hand-off, 2) the persisted fallback.
            val config = MultiScreenSession.consume() ?: restoreFromPrefs(pl)

            if (config == null || config.channels.isEmpty()) {
                _ui.update { it.copy(loaded = true, playlist = pl) }
                return@launch
            }

            _ui.update { it.copy(layout = config.layout, playlist = pl, loaded = true) }

            // The slide-in picker's reactive list (ITS OWN coroutine — a
            // collect suspends forever, the assignments below must run).
            viewModelScope.launch {
                query.collect { q ->
                    val list = try {
                        db.channelDao().channelsFlow(pl?.id ?: -1L, null, q).first()
                    } catch (_: Exception) {
                        emptyList<Channel>()
                    }
                    _ui.update { it.copy(query = q, channels = list) }
                }
            }

            // Assign every slot (PORTAL channels resolve first).
            config.channels.forEachIndexed { slot, channel -> assignResolved(slot, channel) }
            manager.setFocus(0)
        }
    }

    private suspend fun restoreFromPrefs(pl: Playlist?): MultiScreenSession.Config? {
        val layout = MultiScreenPrefs.loadLayout(appCtx) ?: return null
        val keys = MultiScreenPrefs.loadKeys(appCtx)
        if (keys.isEmpty()) return null
        val all = try {
            db.channelDao().channelsFlow(pl?.id ?: -1L, null, "").first()
        } catch (_: Exception) {
            emptyList<Channel>()
        }
        val byKey = all.associateBy { it.key }
        val channels = keys.mapNotNull { byKey[it] }
        if (channels.isEmpty()) return null
        return MultiScreenSession.Config(layout, channels)
    }

    /**
     * One slot's assignment: PORTAL channels re-resolve via create_link
     * (the single player's zap contract), everything else maps straight
     * to the URL chain. An empty chain parks the cell in ERROR.
     */
    private fun assignResolved(slot: Int, channel: Channel) {
        resolveJobs.add(viewModelScope.launch {
            val pl = playlist
            val urls = if (pl?.type == "PORTAL") {
                try {
                    val resolved = StalkerPlayback.resolveChannel(appCtx, pl, channel)
                    listOfNotNull(resolved.directUrl?.takeIf { it.startsWith("http", true) })
                } catch (_: Exception) {
                    emptyList()
                }
            } else if (pl != null) {
                MultiScreenGrid.candidateUrls(pl, channel)
            } else {
                emptyList()
            }
            manager.assign(slot, channel, urls)
        })
    }

    // ── focus / fullscreen ──────────────────────────────────────────

    fun setFocus(slot: Int) {
        manager.setFocus(slot)
        _ui.update { it.copy(focusedSlot = manager.focusedSlot, pickerSlot = manager.focusedSlot) }
    }

    /** An EMPTY cell was highlighted/aimed — the picker's target moves
     *  with it even though the audio cannot (nothing plays there yet). */
    fun setPickerTarget(slot: Int) {
        if (slot in 0..3) {
            _ui.update { it.copy(pickerSlot = slot) }
        }
    }

    /** A cell tap: unfocused → take the audio; already focused → expand. */
    fun onCellTap(slot: Int) {
        if (ui.value.fullscreenSlot != null) return
        if (slot == manager.focusedSlot) toggleFullscreen(slot) else setFocus(slot)
    }

    fun toggleFullscreen(slot: Int) {
        _ui.update { it.copy(fullscreenSlot = if (it.fullscreenSlot == slot) null else slot) }
    }

    fun exitFullscreen() {
        _ui.update { it.copy(fullscreenSlot = null) }
    }

    // ── picker ──────────────────────────────────────────────────────

    fun openPicker() {
        _ui.update { it.copy(pickerOpen = true) }
    }

    fun closePicker() {
        _ui.update { it.copy(pickerOpen = false) }
    }

    fun onSearch(q: String) {
        query.value = q
    }

    /** A picker tap replaces the PICKER-TARGETED cell's channel (the
     *  highlighted live cell, or the empty slot the user aimed at) —
     *  and the new stream takes the audio with it. */
    fun pick(channel: Channel) {
        if (!MultiScreenGrid.isMultiScreenCapable(playlist, channel)) return
        val slot = _ui.value.pickerSlot
        assignResolved(slot, channel)
        setFocus(slot)
    }

    fun retryCell(slot: Int) {
        manager.retry(slot)
    }

    /** Live layout switch: shrinking releases the dropped cells. */
    fun setLayout(layout: MultiLayout) {
        val current = _ui.value.layout
        if (current == layout) return
        if (layout.cells < current.cells) {
            for (slot in layout.cells until current.cells) manager.releaseSlot(slot)
        }
        _ui.update { it.copy(layout = layout, fullscreenSlot = null) }
    }

    override fun onCleared() {
        resolveJobs.forEach { it.cancel() }
        resolveJobs.clear()
        manager.releaseAll()
        super.onCleared()
    }
}

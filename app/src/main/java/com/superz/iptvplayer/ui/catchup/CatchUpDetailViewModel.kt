package com.superz.iptvplayer.ui.catchup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.data.epg.EpgRepository
import com.superz.iptvplayer.data.epg.XcCatchUp
import com.superz.iptvplayer.data.stalker.StalkerEpgParser
import com.superz.iptvplayer.data.stalker.StalkerEpgRepository
import com.superz.iptvplayer.data.timemachine.TimeMachineEngine
import com.superz.iptvplayer.player.VodPlayRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.12.0 — the day-tabbed Catch-Up detail / EPG Timeline screen,
 * replicated from the reference's CatchUpDetailActivity +
 * StalkerCatchUpDetailFragment:
 *
 *  • THREE day tabs — today, yesterday, the day before ("dd MMM yyyy"
 *    labels, oldest first, TODAY selected on open — setUpViewPager's
 *    tabLayout.getTabAt(count-1).select()).
 *  • Each tab = the channel's get_simple_data_table day table (paged by
 *    10, the reference's CatchUp pagination math — StalkerEpgRepository.
 *    dayTable, cached 10 min per channel+date).
 *  • A program row shows the clock icon when mark_archive = 1
 *    (item_catch_detail's image_clock).
 *  • Clicking a program with a file id arms the VOD play hand-off: one
 *    synthetic channel per archived program ("vodc:<fileId>") — the
 *    fullscreen player zaps through the day's programs and each zap
 *    resolves a FRESH tv_archive create_link (StalkerPlayback.resolveVod)
 *    exactly like the reference's per-open get_catch_url.
 *
 * The SAME screen doubles as the full EPG Timeline for ANY channel (from
 * the channel view): non-archive channels simply render the timeline
 * without playable rows.
 */
class CatchUpDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    data class UiState(
        val playlist: Playlist? = null,
        val channel: Channel? = null,
        /** (mysql date, "dd MMM yyyy" label) — oldest first. */
        val days: List<Pair<String, String>> = emptyList(),
        val selectedDay: String = "",
        val programs: List<EpgProgram> = emptyList(),
        val loading: Boolean = true,
        /** Set when a play hand-off is armed — consumed by the screen. */
        val playKey: String? = null
    ) {
        val catchUpCapable: Boolean get() = channel?.tvArchive == 1
    }

    private val appCtx = getApplication<IPTVApp>()
    private val db = appCtx.database
    private val stalkerEpg = StalkerEpgRepository.get(appCtx)
    private val xcEpg = EpgRepository.get(appCtx.okHttp)

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L
    private val channelKey: String = savedStateHandle.get<String>("channelKey") ?: ""

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    /** v1.12.1 — XC mode: the whole table cached after the first fetch
     *  (XCCatchUpDetailActivity fetches once, then splits locally). */
    private var xcAll: List<EpgProgram> = emptyList()

    init {
        viewModelScope.launch {
            // v1.12.6 — the defensive twin of the CatchUpScreen click fix:
            // a "catchupdetail/-1/<key>" route (or any non-positive id) now
            // resolves to the ACTIVE playlist exactly like the grid screen,
            // instead of dying on playlistById(-1) → null → the tab-less
            // "No Catch Up Found" that mimicked a data failure.
            val pid = CatchUpNav.effectivePlaylistId(
                playlistId,
                db.playlistDao().activePlaylist()?.id
            )
            val pl = db.playlistDao().playlistById(pid)
            val ch = db.channelDao().channelByKey(pid, channelKey)
            if (pl == null || ch == null) {
                _ui.update { it.copy(loading = false) }
                return@launch
            }
            if (pl.type == "XTREAM") {
                loadXc(pl, ch)
            } else {
                val days = StalkerEpgParser.catchUpDays()
                _ui.update {
                    it.copy(
                        playlist = pl,
                        channel = ch,
                        days = days,
                        selectedDay = days.last().first    // TODAY selected
                    )
                }
                loadDay(days.last().first)
            }
        }
    }

    /**
     * v1.12.1 — the XC flow, replicated from the reference's
     * XCCatchUpDetailActivity: ONE get_simple_data_table call for the
     * stream (get_full_epg) → getCatchupModels splits the rows into the
     * today / -1d / -2d buckets → setUpViewPager adds ONLY the tabs that
     * have programs, oldest first, and selects the LAST one (today).
     *
     * v1.18.0 — TIME MACHINE FALLBACK: many XUI panels answer
     * get_simple_data_table with an EMPTY table — the screen used to die
     * with "No Catch Up Found" forever. Now the reference's
     * TimeMachineEngine generates the last 24h of re-watchable slots from
     * the channel's SHORT EPG (hourly slots, each carrying a ready-made
     * catch-up URL: live/…/ID.m3u8?duration=X&start=Y). The rows land in
     * the natural day buckets (their timestamps are real) and play through
     * [playTimeMachine].
     */
    private fun loadXc(pl: Playlist, ch: Channel) {
        val streamId = ch.streamId ?: return run {
            _ui.update { it.copy(playlist = pl, channel = ch, loading = false) }
        }
        _ui.update { it.copy(playlist = pl, channel = ch, loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var programs = try {
                xcEpg.fullEpg(pl, streamId)
            } catch (_: Throwable) {
                emptyList()
            }
            if (programs.isEmpty()) {
                programs = timeMachinePrograms(pl, ch, streamId)
            }
            xcAll = programs
            val buckets = XcCatchUp.dayBuckets(programs)
            val days = XcCatchUp.dayKeys()
                .filter { buckets[it]?.isNotEmpty() == true }
                .map { it to XcCatchUp.dayLabel(it) }
            val selected = days.lastOrNull()?.first ?: ""
            _ui.update {
                it.copy(
                    loading = false,
                    days = days,
                    selectedDay = selected,
                    programs = buckets[selected] ?: emptyList()
                )
            }
        }
    }

    /**
     * v1.18.0 — the TimeMachine rows: short EPG (the CURRENT program)
     * → hourly catch-up slots → EpgProgram rows carrying their own
     * catch-up URL (fileId set so the row is clickable; markArchive so the
     * clock icon shows — the reference's TimeMachine cards look the same).
     */
    private suspend fun timeMachinePrograms(
        pl: Playlist,
        ch: Channel,
        streamId: Long
    ): List<EpgProgram> {
        val liveUrl = TimeMachineEngine.liveM3u8Url(pl, streamId) ?: return emptyList()
        val short = try {
            xcEpg.shortEpg(pl, listOf(streamId))
        } catch (_: Throwable) {
            emptyMap()
        }
        val past = TimeMachineEngine.getRecentPrograms(
            liveUrl, short[streamId], maxHours = 24,
            channelName = ch.name, channelLogo = ch.logo ?: ""
        )
        return past.map { p ->
            EpgProgram(
                startMs = p.startUnix * 1000L,
                endMs = p.endUnix * 1000L,
                title = p.title,
                description = p.description.takeIf { it.isNotBlank() },
                fileId = "tm:${p.startUnix}",
                markArchive = true,
                catchupUrl = p.catchupUrl
            )
        }
    }

    /** Tab select → the day's table (the fragment's getCurrentEpgModels). */
    fun selectDay(date: String) {
        if (date == _ui.value.selectedDay) return
        // XC mode: the table is already local — just re-slice it
        // (the reference's ViewPagerAdapter holds three ready lists).
        if (_ui.value.playlist?.type == "XTREAM") {
            _ui.update {
                it.copy(
                    selectedDay = date,
                    programs = xcAll.filter { XcCatchUp.dayKeyOf(it.startMs) == date }
                )
            }
            return
        }
        _ui.update { it.copy(selectedDay = date, programs = emptyList()) }
        loadDay(date)
    }

    private fun loadDay(date: String) {
        val pl = _ui.value.playlist ?: return
        val ch = _ui.value.channel ?: return
        val chId = ch.key.removePrefix("k:").toLongOrNull() ?: return
        loadJob?.cancel()
        _ui.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val programs = try {
                stalkerEpg.dayTable(pl, chId, date)
            } catch (_: Throwable) {
                emptyList()
            }
            _ui.update { it.copy(loading = false, programs = programs) }
        }
    }

    /**
     * Arms the play hand-off for one archived program (CatchUpPlayActivity):
     * registers the day's archived programs as the zap list in the
     * VodPlayRegistry — the fullscreen player picks the request up from
     * the SAME route/registry the movies use, and every zap resolves a
     * fresh tv_archive create_link (StalkerPlayback.resolveVod's vodc:
     * branch). The click's own key is reported back via [onArmed].
     *
     * v1.12.1 — XC playlists: the reference's CatchUpPlayActivity XC branch
     * — the timeshift URL is built STRAIGHT from the program row
     * (CatchUpEpg.getUrl: host/timeshift/user/pass/59/startForUrl/stream.ts)
     * and handed to ExoPlayer directly (no create_link round-trip). Each
     * row's synthetic channel carries that directUrl — zaps replay the
     * day's programs the same way.
     */
    fun playProgram(program: EpgProgram, onArmed: (String) -> Unit) {
        val pl = _ui.value.playlist ?: return
        val ch = _ui.value.channel ?: return

        // v1.18.0 — TimeMachine rows carry their OWN ready-made catch-up
        // URL (no timeshift math, no create_link): play them directly.
        if (program.catchupUrl != null) {
            playTimeMachine(pl, ch, program, onArmed)
            return
        }

        val fileId = program.fileId ?: return

        if (pl.type == "XTREAM") {
            playProgramXc(pl, ch, program, onArmed)
            return
        }

        val key = "vodc:$fileId"

        // Zap list = the day's programs that carry a file id, keyed stably
        // (deduped — a repeated id must not produce duplicate zap keys).
        val zap = _ui.value.programs.mapNotNull { p ->
            p.fileId?.let { id -> "vodc:$id" to p }
        }
            .distinctBy { it.first }
            .mapIndexed { idx, (k, p) ->
                Channel(
                    playlistId = pl.id,
                    key = k,
                    num = idx + 1,
                    name = p.title,
                    logo = ch.logo,
                    categoryId = ch.categoryId
                )
            }
        val startIndex = zap.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        VodPlayRegistry.put(
            "${pl.id}:$key",
            VodPlayRegistry.Request(
                playlist = pl,
                channels = if (zap.isEmpty()) listOf(
                    Channel(
                        playlistId = pl.id,
                        key = key,
                        num = 1,
                        name = program.title,
                        logo = ch.logo,
                        categoryId = ch.categoryId
                    )
                ) else zap,
                startIndex = startIndex,
                favoriteKey = key,
                favoriteType = "MOVIE"
            )
        )
        _ui.update { it.copy(playKey = key) }
        onArmed(key)
    }

    /**
     * v1.18.0 — the TimeMachine play arm (the reference's DashboardActivity
     * opens PastProgram.catchupUrl in its mini player): one synthetic
     * "vodt:" channel per slot with the catch-up URL as its directUrl —
     * the locked engine plays it through its normal chain (an HLS URL with
     * query params). Zap list = the day's TimeMachine slots, each carrying
     * its own URL (v1.12.1's playProgramXc pattern).
     */
    private fun playTimeMachine(
        pl: Playlist,
        ch: Channel,
        program: EpgProgram,
        onArmed: (String) -> Unit
    ) {
        val url = program.catchupUrl ?: return
        val streamId = ch.streamId
        val key = "vodt:${streamId ?: 0}:${program.startMs}"

        val dayKey = XcCatchUp.dayKeyOf(program.startMs)
        val zap = xcAll
            .filter { it.catchupUrl != null && XcCatchUp.dayKeyOf(it.startMs) == dayKey }
            .mapIndexed { idx, p ->
                Channel(
                    playlistId = pl.id,
                    key = "vodt:${streamId ?: 0}:${p.startMs}",
                    num = idx + 1,
                    name = p.title,
                    logo = ch.logo,
                    categoryId = ch.categoryId,
                    directUrl = p.catchupUrl
                )
            }
        val startIndex = zap.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        VodPlayRegistry.put(
            "${pl.id}:$key",
            VodPlayRegistry.Request(
                playlist = pl,
                channels = if (zap.isEmpty()) listOf(
                    Channel(
                        playlistId = pl.id,
                        key = key,
                        num = 1,
                        name = program.title,
                        logo = ch.logo,
                        categoryId = ch.categoryId,
                        directUrl = url
                    )
                ) else zap,
                startIndex = startIndex,
                favoriteKey = key,
                favoriteType = "MOVIE"
            )
        )
        _ui.update { it.copy(playKey = key) }
        onArmed(key)
    }

    /**
     * The XC play arm (CatchUpPlayActivity's else-branch): one synthetic
     * "vodx:" channel per program with the timeshift URL as its directUrl —
     * StreamUrls.variants plays directUrl URLs as-is through the normal
     * engine chain (Exo first, the reference's ProgressiveMediaSource).
     */
    private fun playProgramXc(
        pl: Playlist,
        ch: Channel,
        program: EpgProgram,
        onArmed: (String) -> Unit
    ) {
        val streamId = ch.streamId ?: return
        val startForUrl = XcCatchUp.startForUrl(program.startRaw)
            .ifEmpty { XcCatchUp.startForUrlFromEpoch(program.startMs) }
        if (startForUrl.isEmpty()) return
        val url = XcCatchUp.timeshiftUrl(
            pl.server, pl.username, pl.password, streamId, startForUrl
        )

        // Stable unique key per program row (its start).
        val key = "vodx:$streamId:${program.startMs}"

        // Zap list = the day's programs (the reference's fragment passes the
        // day's list; each row builds its own timeshift URL on zap).
        val dayKey = XcCatchUp.dayKeyOf(program.startMs)
        val zap = xcAll
            .filter { XcCatchUp.dayKeyOf(it.startMs) == dayKey }
            .mapIndexed { idx, p ->
                val sFu = XcCatchUp.startForUrl(p.startRaw)
                    .ifEmpty { XcCatchUp.startForUrlFromEpoch(p.startMs) }
                Channel(
                    playlistId = pl.id,
                    key = "vodx:$streamId:${p.startMs}",
                    num = idx + 1,
                    name = p.title,
                    logo = ch.logo,
                    categoryId = ch.categoryId,
                    directUrl = if (sFu.isEmpty()) null
                    else XcCatchUp.timeshiftUrl(pl.server, pl.username, pl.password, streamId, sFu)
                )
            }
            .filter { it.directUrl != null }
        val startIndex = zap.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        VodPlayRegistry.put(
            "${pl.id}:$key",
            VodPlayRegistry.Request(
                playlist = pl,
                channels = if (zap.isEmpty()) listOf(
                    Channel(
                        playlistId = pl.id,
                        key = key,
                        num = 1,
                        name = program.title,
                        logo = ch.logo,
                        categoryId = ch.categoryId,
                        directUrl = url
                    )
                ) else zap,
                startIndex = startIndex,
                favoriteKey = key,
                favoriteType = "MOVIE"
            )
        )
        _ui.update { it.copy(playKey = key) }
        onArmed(key)
    }

    /** The screen consumed the play key. */
    fun consumePlayKey() {
        _ui.update { it.copy(playKey = null) }
    }
}

package com.superz.iptvplayer.ui.vod

import android.app.Application
import com.superz.iptvplayer.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.db.SeriesShow
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import com.superz.iptvplayer.data.xtream.CastPhotoResolver
import com.superz.iptvplayer.data.xtream.VodEpisode
import com.superz.iptvplayer.data.xtream.VodSeriesInfo
import com.superz.iptvplayer.data.xtream.VodUrlProbe
import com.superz.iptvplayer.data.recording.RecorderEngine
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.data.stalker.StalkerVodRefs
import com.superz.iptvplayer.player.VodPlayRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Series info page state (v1.4.0): the cached series row + the lazily-
 * fetched get_series_info details (seasons + episodes, tolerant parsing,
 * 10-min cache). Season switching is pure UI state — no refetch.
 *
 * Episode playback registers the WHOLE season as the player's zap list:
 * next/prev in the fullscreen player moves between episodes, exactly
 * like zapping between live channels — same player, same locked engine.
 */
class SeriesInfoViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    data class UiState(
        val playlist: Playlist? = null,
        val show: SeriesShow? = null,
        val info: VodSeriesInfo? = null,
        val loading: Boolean = true,
        val isFavorite: Boolean = false,
        val selectedSeason: Int = 1,
        val castNames: List<String> = emptyList(),
        val castPhotos: Map<String, String> = emptyMap(),
        /** v1.4.5 — reference parity: TMDB /tv/{id}/credits members when
         *  the series info carries tmdb_id (rare on Xtream panels); the
         *  cast row prefers this over the Wikipedia fallback. */
        val tmdbCast: List<com.superz.iptvplayer.data.xtream.TmdbCastResolver.Member> = emptyList(),
        /** v1.4.4 — episode whose playback URL is being probed right now
         *  ("preparing" spinner on its card; null when idle). */
        val probingEpisodeId: Long? = null,
        /** v1.19.0 — CONTINUE WATCHING: the newest in-progress episode of
         *  THIS series (from the playback-position shelf): its card gets
         *  the cyan progress bar + "متابعة" chip and the strip scrolls to
         *  it, so the continue-watching card → series page flow lands the
         *  user on the exact episode to resume (null = nothing in
         *  progress). [resumePositionMs]/[resumeDurationMs] drive the bar. */
        val resumeEpisodeId: Long? = null,
        val resumePositionMs: Long = 0L,
        val resumeDurationMs: Long = 0L,
        // ── v1.19.9 — THE DOWNLOAD BUTTON (moved from the player's top
        // bar to this page — user directive; per EPISODE, the card's own
        // play affordance). One episode downloads at a time (the engine's
        // iron rule): [downloadEpisodeId] is the card currently pulling. ──
        val downloadEpisodeId: Long? = null,
        val downloadResolvingId: Long? = null,
        // v2.1.0 — THE PREMIUM GATE: true when a free user's download
        // trial is already spent — the screen answers with the upsell
        // dialog (user: "تحميل فيديوهات الأفلام والمسلسلات… مرة واحدة
        // فقط و بعدها يطلب منه تفعيل اشتراك").
        val premiumGate: Boolean = false,
        val downloadPercent: Int = -1,
        val downloadBytesMb: Float = 0f,
        val downloadFinalizing: Boolean = false,
        val downloadMessage: String? = null
    ) {
        val seasons: List<Int>
            get() = info?.seasons?.map { it.seasonNumber } ?: emptyList()

        val episodes: List<VodEpisode>
            get() = info?.seasons
                ?.firstOrNull { it.seasonNumber == selectedSeason }
                ?.episodes ?: emptyList()

        /** v1.4.4 — artwork of the selected season (episode-card fallback). */
        val selectedSeasonCover: String?
            get() = info?.seasons
                ?.firstOrNull { it.seasonNumber == selectedSeason }?.cover
    }

    private val appCtx = getApplication<IPTVApp>()
    private val repo = PlaylistRepository.get(appCtx)
    private val db = appCtx.database

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L
    private val seriesId: Long = savedStateHandle.get<Long>("seriesId") ?: -1L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** v1.19.9 — mirrors SmartPlayer's private USER_AGENT (the recorder
     *  must speak exactly like the engine's own sources; duplicated the
     *  same way PlayerViewModel does — keep the two in sync). */
    private val playerUa = "IPTVPlayer/1.0 (Android; Media3+LibVLC)"

    /** v1.19.9 — 1s progress poller while an episode of THIS series
     *  downloads. */
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId)
            val show = db.vodDao().seriesBySeriesId(playlistId, seriesId)
            var state = UiState(playlist = pl, show = show)
            val favKeys = pl?.let { db.vodDao().vodFavoriteKeysFlow(it.id).first().toSet() } ?: emptySet()
            state = state.copy(
                isFavorite = show?.let { favKeys.contains(it.key) } ?: false
            )
            _ui.value = state

            // v1.19.0 — CONTINUE WATCHING: the newest in-progress episode
            // record of THIS series (kind EPISODE, matching seriesId).
            // Read once at entry — the page is a fresh ViewModel per nav,
            // so returning from the player re-reads the fresh position.
            try {
                val shelf = PlaybackPositionManager.recent(
                    PlaybackPositionManager.storeFile(appCtx)
                )
                val rec = shelf.firstOrNull {
                    it.kind == PlaybackPositionManager.KIND_EPISODE &&
                        it.seriesId == seriesId
                }
                if (rec != null) {
                    _ui.update {
                        it.copy(
                            resumeEpisodeId = rec.contentId,
                            resumePositionMs = rec.positionMs,
                            resumeDurationMs = rec.durationMs
                        )
                    }
                }
            } catch (_: Throwable) {
            }

            val info = pl?.let { repo.seriesInfo(it, seriesId) }
            // v1.19.0 — CONTINUE WATCHING: land on the season that holds
            // the in-progress episode (not blindly season 1), so the
            // progress-bar card is on screen right away.
            val resumeSeason = info?.seasons?.firstOrNull { season ->
                season.episodes.any { it.id == _ui.value.resumeEpisodeId }
            }?.seasonNumber
            _ui.update {
                it.copy(
                    info = info,
                    loading = false,
                    selectedSeason = resumeSeason
                        ?: info?.seasons?.firstOrNull()?.seasonNumber
                        ?: 1,
                    castNames = MovieInfoViewModel.splitCast(info?.cast)
                )
            }

            // v1.4.5 — reference parity: TMDB tv credits when the panel
            // provides a series tmdb_id; Wikipedia fallback otherwise.
            val tmdbMembers = com.superz.iptvplayer.data.xtream.TmdbCastResolver
                .resolveCredits(info?.tmdbId, com.superz.iptvplayer.data.xtream.TmdbCastResolver.Kind.TV)
            if (tmdbMembers.isNotEmpty()) {
                _ui.update {
                    it.copy(
                        castNames = tmdbMembers.map { m -> m.name },
                        tmdbCast = tmdbMembers
                    )
                }
            } else {
                // v1.4.3 — cast headshots (Wikipedia), async + silent.
                val names = MovieInfoViewModel.splitCast(info?.cast)
                if (names.isNotEmpty()) {
                    val photos = CastPhotoResolver.resolve(names)
                    _ui.update { it.copy(castPhotos = photos) }
                }
            }

            // v1.19.9 — re-attach to OUR OWN download: the user pressed ⬇
            // on an episode here, navigated away, and came back while the
            // engine is still pulling it. The card shows the running
            // progress instead of offering a second download. (sourceKey
            // "vode:{id}" → find which episode it belongs to.)
            val activeKey = RecorderEngine.sourceKey
            if (RecorderEngine.isRecording && !RecorderEngine.playerOwned &&
                activeKey?.startsWith("vode:") == true
            ) {
                val activeEpisodeId = activeKey.removePrefix("vode:").toLongOrNull()
                if (activeEpisodeId != null &&
                    info?.seasons?.any { s -> s.episodes.any { it.id == activeEpisodeId } } == true
                ) {
                    _ui.update { it.copy(downloadEpisodeId = activeEpisodeId) }
                    startDownloadTicker()
                }
            }
        }
    }

    fun selectSeason(season: Int) {
        _ui.update { it.copy(selectedSeason = season) }
    }

    fun toggleFavorite() {
        val pl = _ui.value.playlist ?: return
        val show = _ui.value.show ?: return
        viewModelScope.launch {
            val nowFav = repo.toggleVodFavorite(pl.id, show.key, "SERIES")
            _ui.update { it.copy(isFavorite = nowFav) }
        }
    }

    /**
     * v1.4.4 — arms episode playback AFTER gently probing the tapped
     * episode's real URL (same contract as the movie page): the panel's
     * listed extension can be stale (404), and a verified URL + warm
     * route means the LOCKED engine starts on bytes that actually exist.
     * The probe is SEQUENTIAL and short — one request in the common case.
     *
     * The current season's episodes still become the player's zap list
     * (next/prev = next/prev episode); the tapped episode gets the
     * probed URL, the rest keep their panel-listed extension.
     *
     * v1.11.0 — PORTAL (stalker) episodes arm INSTANTLY, no probing: the
     * tmp link is created per open by create_link, so each episode
     * channel registers its ref (season cmd + series number / HTML item
     * ids) and the fullscreen player's play entry resolves it on every
     * zap (SeriesPlayActivity's exact flow).
     */
    fun prepareEpisodePlayback(episodeId: Long, onReady: (String) -> Unit) {
        if (_ui.value.probingEpisodeId != null) return
        val pl = _ui.value.playlist ?: return
        val show = _ui.value.show ?: return
        val episodes = _ui.value.episodes
        val info = _ui.value.info
        val episode = episodes.firstOrNull { it.id == episodeId } ?: return
        val startIndex = episodes.indexOfFirst { it.id == episodeId }.coerceAtLeast(0)

        if (pl.type == "PORTAL") {
            val channels = episodes.map { ep ->
                Channel(
                    playlistId = pl.id,
                    key = "vode:${ep.id}",
                    num = ep.episodeNumber,
                    name = "S${ep.season}E${ep.episodeNumber} · ${ep.title}",
                    logo = ep.thumbnail ?: show.poster,
                    categoryId = show.categoryId,
                    directUrl = null,
                    stalkerCmd = ep.stalkerCmd
                )
            }
            episodes.forEach { ep ->
                StalkerVodRefs.put(
                    "${pl.id}:vode:${ep.id}",
                    StalkerVodRefs.Ref(
                        cmd = ep.stalkerCmd,
                        seriesNum = ep.stalkerSeriesNum,
                        htmlItem = ep.stalkerItem
                    )
                )
            }
            val channelKey = "vode:$episodeId"
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = channels,
                    startIndex = startIndex,
                    favoriteKey = show.key,
                    favoriteType = "SERIES",
                    // v1.18.0 — subtitle hints: the SERIES name (episodes
                    // compose "<Series> S01E05" at subtitle time) + year +
                    // the panel's imdb id when it has one.
                    subtitleMeta = seriesSubtitleMeta(show, info)
                )
            )
            onReady(channelKey)
            return
        }

        _ui.update { it.copy(probingEpisodeId = episodeId) }
        viewModelScope.launch {
            // Candidates: panel direct_source (when absolute) first, then
            // the listed extension, then common containers.
            val exts = sequenceOf(
                episode.containerExtension,
                "mp4", "mkv", "ts", "avi"
            )
                .mapNotNull { it }
                .map { it.trim().lowercase().filter { c -> c.isLetterOrDigit() } }
                .filter { it.length in 1..5 }
                .distinct()
            val candidates = buildList {
                episode.directSource?.let { add(it) }
                exts.forEach { ext -> repo.episodeUrl(pl, episode.id, ext)?.let { add(it) } }
            }.distinct()

            val url = when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates.first()
                else -> VodUrlProbe.best(appCtx.okHttp, candidates)
            }
            _ui.update { it.copy(probingEpisodeId = null) }
            if (url == null) return@launch

            val channels = episodes.map { ep ->
                val epUrl = when {
                    ep.id == episodeId -> url
                    ep.directSource != null -> ep.directSource
                    else -> repo.episodeUrl(pl, ep.id, ep.containerExtension)
                } ?: return@launch
                Channel(
                    playlistId = pl.id,
                    key = "vode:${ep.id}",
                    num = ep.episodeNumber,
                    name = "S${ep.season}E${ep.episodeNumber} · ${ep.title}",
                    logo = ep.thumbnail ?: show.poster,
                    categoryId = show.categoryId,
                    directUrl = epUrl
                )
            }

            val channelKey = "vode:$episodeId"
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = channels,
                    startIndex = startIndex,
                    favoriteKey = show.key,
                    favoriteType = "SERIES",
                    subtitleMeta = seriesSubtitleMeta(show, info)
                )
            )
            onReady(channelKey)
        }
    }

    /** v1.18.0 — the series' subtitle hints (name / year / imdb id). */
    private fun seriesSubtitleMeta(
        show: com.superz.iptvplayer.data.db.SeriesShow,
        info: com.superz.iptvplayer.data.xtream.VodSeriesInfo?
    ): VodPlayRegistry.SubtitleMeta = VodPlayRegistry.SubtitleMeta(
        imdbId = info?.imdbId,
        year = com.superz.iptvplayer.data.subtitles.SubtitleRepository.extractYear(
            info?.releaseDate ?: show.name
        ),
        cleanName = info?.name ?: show.name
    )

    // ═══ v1.19.9 — THE PER-EPISODE DOWNLOAD BUTTON (moved from the
    // player's top bar to the episode cards — user directive: movies &
    // series download from the INFO page). The SAME standalone
    // RecorderEngine the player used, armed HERE as a background download
    // (playerOwned = false): it survives leaving the page, opening the
    // player over it, or never playing at all. ═══

    /** The ⬇ chip on an episode card: idle/resolving → start that
     *  episode's download; running → stop (finalize a valid partial). */
    fun toggleEpisodeDownload(episodeId: Long) {
        when {
            _ui.value.downloadEpisodeId == episodeId &&
                RecorderEngine.isRecording -> RecorderEngine.stop()
            _ui.value.downloadResolvingId == episodeId -> Unit   // working on it
            // v2.1.0 — DOWNLOADING is a premium feature: the free plan's
            // one trial passes, the second press opens the gate. The busy
            // guard comes FIRST so a refused start never burns the trial.
            else -> {
                if (RecorderEngine.isRecording) {
                    flashDownloadMessage(appCtx.getString(R.string.download_busy))
                } else if (com.superz.iptvplayer.ui.theme.PremiumAccess.request(
                        com.superz.iptvplayer.ui.theme.PremiumFeature.DOWNLOAD
                    )
                ) {
                    startEpisodeDownload(episodeId)
                } else {
                    _ui.update { it.copy(premiumGate = true) }
                }
            }
        }
    }

    /** v2.1.0 — the upsell dialog's dismiss/activate hand-off. */
    fun consumePremiumGate() = _ui.update { it.copy(premiumGate = false) }

    private fun startEpisodeDownload(episodeId: Long) {
        val pl = _ui.value.playlist ?: return
        val show = _ui.value.show ?: return
        val episode = _ui.value.episodes.firstOrNull { it.id == episodeId } ?: return
        // One download at a time (the engine's iron rule): if the busy one
        // is OURS the init block has already re-attached the UI to it.
        if (RecorderEngine.isRecording) {
            flashDownloadMessage(appCtx.getString(R.string.download_busy))
            return
        }
        _ui.update { it.copy(downloadResolvingId = episodeId) }
        viewModelScope.launch {
            val url: String? = if (pl.type == "PORTAL") {
                // Stalker episodes need the SAME ref + create_link
                // round-trip the play path performs (a fresh, time-limited
                // tmp link — cmd = season cmd, series = episode number).
                StalkerVodRefs.put(
                    "${pl.id}:vode:${episode.id}",
                    StalkerVodRefs.Ref(
                        cmd = episode.stalkerCmd,
                        seriesNum = episode.stalkerSeriesNum,
                        htmlItem = episode.stalkerItem
                    )
                )
                val channel = Channel(
                    playlistId = pl.id,
                    key = "vode:${episode.id}",
                    num = episode.episodeNumber,
                    name = "S${episode.season}E${episode.episodeNumber} · ${episode.title}",
                    logo = episode.thumbnail ?: show.poster,
                    categoryId = show.categoryId,
                    directUrl = null,
                    stalkerCmd = episode.stalkerCmd
                )
                StalkerPlayback.resolveVod(appCtx, pl, channel).directUrl
            } else {
                // XC: the tapped episode's candidates — panel direct_source
                // (when absolute) first, then the listed extension, then
                // the common containers, probed exactly like the play path.
                val exts = sequenceOf(
                    episode.containerExtension,
                    "mp4", "mkv", "ts", "avi"
                )
                    .mapNotNull { it }
                    .map { it.trim().lowercase().filter { c -> c.isLetterOrDigit() } }
                    .filter { it.length in 1..5 }
                    .distinct()
                val candidates = buildList {
                    episode.directSource?.let { add(it) }
                    exts.forEach { ext -> repo.episodeUrl(pl, episode.id, ext)?.let { add(it) } }
                }.distinct()
                when {
                    candidates.isEmpty() -> null
                    candidates.size == 1 -> candidates.first()
                    else -> VodUrlProbe.best(appCtx.okHttp, candidates)
                }
            }
            _ui.update { it.copy(downloadResolvingId = null) }
            if (url == null) {
                flashDownloadMessage(
                    appCtx.getString(
                        R.string.download_failed,
                        appCtx.getString(R.string.recording_no_url)
                    )
                )
                return@launch
            }
            val title = "S${episode.season}E${episode.episodeNumber} · ${episode.title}"
            val started = RecorderEngine.start(
                appCtx, url, playerUa, null, null, title,
                playerOwned = false,
                sourceKey = "vode:${episode.id}"
            )
            if (!started) {
                flashDownloadMessage(appCtx.getString(R.string.download_busy))
                return@launch
            }
            _ui.update { it.copy(downloadEpisodeId = episode.id) }
            startDownloadTicker()
        }
    }

    /**
     * 1s poll of the engine statics → the downloading card's percent/MB
     * readout; when the engine stands down (finished, failed, or the
     * user stopped it — stop() finalizes a valid partial MP4) the loop
     * ends and the "saved to your library" message lands. Runs in the
     * ViewModel scope: leaving the page stops the POLLING only — the
     * download itself continues on the engine's own scope.
     */
    private fun startDownloadTicker() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            while (true) {
                if (!RecorderEngine.isRecording) {
                    val savedMb = RecorderEngine.recordingBytesWritten() / (1024.0 * 1024.0)
                    val path = RecorderEngine.lastRecordingPath
                    val msg = if (savedMb > 0.01) {
                        appCtx.getString(
                            R.string.download_saved,
                            String.format(java.util.Locale.US, "%.1f MB — %s", savedMb, path)
                        )
                    } else {
                        appCtx.getString(R.string.download_failed, "")
                    }
                    _ui.update {
                        it.copy(
                            downloadEpisodeId = null,
                            downloadPercent = -1,
                            downloadBytesMb = 0f,
                            downloadFinalizing = false,
                            downloadMessage = msg
                        )
                    }
                    flashDownloadMessage(null)   // arm the auto-clear
                    break
                }
                _ui.update {
                    it.copy(
                        downloadPercent = RecorderEngine.progress(),
                        downloadBytesMb = RecorderEngine.recordingBytesWritten() / (1024f * 1024f),
                        downloadFinalizing = RecorderEngine.isFinalizing
                    )
                }
                delay(1_000L)
            }
        }
    }

    /** Transient message at the page bottom (auto-clears after ~6s). */
    private fun flashDownloadMessage(message: String?) {
        _ui.update { it.copy(downloadMessage = message) }
        viewModelScope.launch {
            delay(6_000L)
            _ui.update { it.copy(downloadMessage = null) }
        }
    }
}

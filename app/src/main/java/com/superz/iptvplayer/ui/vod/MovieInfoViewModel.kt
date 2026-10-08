package com.superz.iptvplayer.ui.vod

import android.app.Application
import com.superz.iptvplayer.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.xtream.CastPhotoResolver
import com.superz.iptvplayer.data.xtream.TmdbCastResolver
import com.superz.iptvplayer.data.xtream.VodMovieInfo
import com.superz.iptvplayer.data.xtream.VodUrlProbe
import com.superz.iptvplayer.data.recording.RecorderEngine
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.data.stalker.StalkerVodRefs
import com.superz.iptvplayer.data.subtitles.SubtitleRepository
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
 * Movie info page state (v1.4.0, redesigned v1.4.3): the cached Movie row
 * (name/poster/container) + the lazily-fetched get_vod_info details +
 * the cast list (split from info.cast) with async headshot enrichment.
 *
 * Playback (v1.4.3): before handing off to the FULLSCREEN PLAYER, the
 * URL candidates (listed extension + mp4/mkv/avi) are probed in parallel
 * with 1-byte Range GETs — a panel whose listed extension is stale 404s
 * instantly, and the first candidate that answers 200/206 wins. This
 * fixes "most movies don't start" WITHOUT touching the locked engine:
 * the engine receives a URL that actually serves bytes, plus an already
 * warm pooled socket.
 */
class MovieInfoViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    /** The download button's state machine (v1.19.9 — the button moved
     *  from the player's top bar to THIS page, next to Play):
     *  IDLE → RESOLVING (URL probe) → RUNNING (engine pulling, percent
     *  polled) → back to IDLE with a transient message when it lands in
     *  the Saved Videos library (or fails / is refused because another
     *  download is already running — the engine's one-at-a-time rule). */
    enum class DownloadPhase { IDLE, RESOLVING, RUNNING }

    data class UiState(
        val playlist: Playlist? = null,
        val movie: Movie? = null,
        val info: VodMovieInfo? = null,
        val loading: Boolean = true,
        val isFavorite: Boolean = false,
        val probing: Boolean = false,
        val castNames: List<String> = emptyList(),
        val castPhotos: Map<String, String> = emptyMap(),
        /** v1.4.5 — reference parity: full TMDB credits (name + character
         *  + headshot) when the panel's info carries tmdb_id; the row
         *  prefers this over the name/Wikipedia fallback. */
        val tmdbCast: List<TmdbCastResolver.Member> = emptyList(),
        // ── v1.19.9 — the DOWNLOAD button (moved from the player) ──
        val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
        val downloadPercent: Int = -1,
        val downloadBytesMb: Float = 0f,
        val downloadFinalizing: Boolean = false,
        val downloadMessage: String? = null,
        // v2.1.0 — THE PREMIUM GATE: true when a free user's download
        // trial is already spent — the screen answers with the upsell
        // dialog (user: "تحميل فيديوهات الأفلام والمسلسلات… مرة واحدة
        // فقط و بعدها يطلب منه تفعيل اشتراك").
        val premiumGate: Boolean = false
    )

    private val appCtx = getApplication<IPTVApp>()
    private val repo = PlaylistRepository.get(appCtx)
    private val db = appCtx.database

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L
    private val streamId: Long = savedStateHandle.get<Long>("streamId") ?: -1L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** v1.19.9 — mirrors SmartPlayer's private USER_AGENT (the recorder
     *  must speak exactly like the engine's own sources; duplicated the
     *  same way PlayerViewModel does — keep the two in sync). */
    private val playerUa = "IPTVPlayer/1.0 (Android; Media3+LibVLC)"

    /** v1.19.9 — 1s progress poller while THIS movie's download runs. */
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            val pl = db.playlistDao().playlistById(playlistId)
            val movie = db.vodDao().movieByStreamId(playlistId, streamId)
            var state = UiState(playlist = pl, movie = movie)
            val favKeys = pl?.let { db.vodDao().vodFavoriteKeysFlow(it.id).first().toSet() } ?: emptySet()
            state = state.copy(
                isFavorite = movie?.let { favKeys.contains(it.key) } ?: false
            )
            _ui.value = state

            // Lazy info fetch — cached 10 min in the repository.
            val info = pl?.let { repo.movieInfo(it, streamId) }
            _ui.update {
                it.copy(
                    info = info,
                    loading = false,
                    castNames = splitCast(info?.cast)
                )
            }

            // v1.4.5 — reference parity (VU IPTV): when the panel knows the
            // movie's TMDB id, pull REAL cast credits (photos + character
            // names) from TMDB exactly like the reference app does; only
            // when that id is missing/empty do we fall back to the v1.4.3
            // Wikipedia name-enrichment path.
            val tmdbMembers = TmdbCastResolver.resolveCredits(
                info?.tmdbId, TmdbCastResolver.Kind.MOVIE
            )
            if (tmdbMembers.isNotEmpty()) {
                _ui.update {
                    it.copy(
                        castNames = tmdbMembers.map { m -> m.name },
                        tmdbCast = tmdbMembers
                    )
                }
            } else {
                // v1.4.3 — cast headshots (Wikipedia), async + silent.
                val names = splitCast(info?.cast)
                if (names.isNotEmpty()) {
                    val photos = CastPhotoResolver.resolve(names)
                    _ui.update { it.copy(castPhotos = photos) }
                }
            }

            // v1.19.9 — re-attach to OUR OWN download: the user pressed ⬇
            // here, navigated away, and came back while the engine is
            // still pulling (or finalizing) this exact movie. The button
            // must show the running progress, not offer a second download.
            if (RecorderEngine.isRecording &&
                RecorderEngine.sourceKey == "vodm:$streamId" &&
                !RecorderEngine.playerOwned
            ) {
                _ui.update { it.copy(downloadPhase = DownloadPhase.RUNNING) }
                startDownloadTicker()
            }
        }
    }

    fun toggleFavorite() {
        val pl = _ui.value.playlist ?: return
        val movie = _ui.value.movie ?: return
        viewModelScope.launch {
            val nowFav = repo.toggleVodFavorite(pl.id, movie.key, "MOVIE")
            _ui.update { it.copy(isFavorite = nowFav) }
        }
    }

    /**
     * The XC (Xtream/M3U) URL resolver shared by Play and Download
     * (v1.19.9 extract — the exact v1.4.3 candidate/probe chain):
     * listed extension first, then the common containers, probed in
     * parallel with 1-byte Range GETs — the first candidate answering
     * 200/206 wins. Null when nothing servable was found.
     */
    private suspend fun resolveMovieUrl(): String? {
        val pl = _ui.value.playlist ?: return null
        val movie = _ui.value.movie ?: return null
        val info = _ui.value.info
        val exts = sequenceOf(
            info?.containerExtension,
            movie.containerExtension,
            "mp4", "mkv", "avi"
        )
            .mapNotNull { it }
            .map { it.trim().lowercase().filter { c -> c.isLetterOrDigit() } }
            .filter { it.length in 1..5 }
            .distinct()
            .toList()
        val candidates = exts
            .mapNotNull { ext -> repo.movieUrl(pl, streamId, ext) }
            .distinct()

        return when {
            candidates.isEmpty() ->
                repo.movieUrl(pl, streamId, info?.containerExtension ?: movie.containerExtension)
            candidates.size == 1 -> candidates.first()
            else -> VodUrlProbe.best(appCtx.okHttp, candidates)
        }
    }

    /**
     * v1.4.3 — arms playback AFTER probing the URL candidates and calls
     * [onReady] with the synthetic channel key ("vodm:{streamId}") the
     * player route should carry. The play button shows a preparing state
     * while probing. Does nothing when a probe round is already running
     * or the URL can't be built.
     *
     * v1.11.0 — PORTAL (stalker) movies arm INSTANTLY, no probing: stalker
     * play URLs are created per open (create_link returns a time-limited
     * tmp link), so there is nothing to probe — the ref (cmd / HTML item
     * ids) is registered and the fullscreen player's play entry resolves
     * it (MoviePlayerActivity's exact flow).
     */
    fun preparePlayback(onReady: (String) -> Unit) {
        if (_ui.value.probing) return
        val pl = _ui.value.playlist ?: return
        val movie = _ui.value.movie ?: return
        val info = _ui.value.info
        if (pl.type == "PORTAL") {
            val channelKey = "vodm:$streamId"
            StalkerVodRefs.put(
                "${pl.id}:$channelKey",
                StalkerVodRefs.Ref(
                    cmd = info?.stalkerCmd,
                    seriesNum = null,
                    htmlItem = info?.stalkerItem
                )
            )
            val channel = Channel(
                playlistId = pl.id,
                key = channelKey,
                num = movie.num,
                name = info?.name ?: movie.name,
                logo = info?.poster ?: movie.poster,
                categoryId = movie.categoryId,
                streamId = streamId,
                directUrl = null,
                stalkerCmd = info?.stalkerCmd
            )
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = listOf(channel),
                    startIndex = 0,
                    favoriteKey = movie.key,
                    favoriteType = "MOVIE",
                    // v1.18.0 — subtitle hints: the panel's imdb id (exact
                    // match layer) + the year (remake disambiguation).
                    subtitleMeta = VodPlayRegistry.SubtitleMeta(
                        imdbId = info?.imdbId,
                        year = SubtitleRepository.extractYear(
                            info?.releaseDate ?: movie.name
                        ),
                        cleanName = info?.name ?: movie.name
                    )
                )
            )
            onReady(channelKey)
            return
        }
        _ui.update { it.copy(probing = true) }
        viewModelScope.launch {
            val url = resolveMovieUrl()
            _ui.update { it.copy(probing = false) }
            if (url == null) return@launch

            val channelKey = "vodm:$streamId"
            val channel = Channel(
                playlistId = pl.id,
                key = channelKey,
                num = movie.num,
                name = info?.name ?: movie.name,
                logo = info?.poster ?: movie.poster,
                categoryId = movie.categoryId,
                streamId = streamId,
                directUrl = url
            )
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = listOf(channel),
                    startIndex = 0,
                    favoriteKey = movie.key,
                    favoriteType = "MOVIE",
                    // v1.18.0 — subtitle hints for the probed-URL branch too.
                    subtitleMeta = VodPlayRegistry.SubtitleMeta(
                        imdbId = info?.imdbId,
                        year = SubtitleRepository.extractYear(
                            info?.releaseDate ?: movie.name
                        ),
                        cleanName = info?.name ?: movie.name
                    )
                )
            )
            onReady(channelKey)
        }
    }

    // ═══ v1.19.9 — THE DOWNLOAD BUTTON (moved from the player's top bar
    // to this page, next to Play — user directive). The SAME standalone
    // RecorderEngine the player used, armed HERE as a background
    // download (playerOwned = false): it keeps running when the user
    // leaves this page, opens the player over it, or never plays at all.
    // Progress is polled from the engine statics exactly like the player's
    // own pill did. ═══

    /** The ⬇ button: idle/resolving → start; running → stop (finalize). */
    fun toggleDownload() {
        when (_ui.value.downloadPhase) {
            DownloadPhase.RUNNING -> RecorderEngine.stop()
            DownloadPhase.RESOLVING -> Unit   // already working on it
            // v2.1.0 — DOWNLOADING is a premium feature: the free plan's
            // one trial passes, the second press opens the gate. The busy
            // guard comes FIRST so a refused start (engine taken by a live
            // REC, say) never burns the one free use.
            DownloadPhase.IDLE -> {
                if (RecorderEngine.isRecording) {
                    flashDownloadMessage(appCtx.getString(R.string.download_busy))
                } else if (com.superz.iptvplayer.ui.theme.PremiumAccess.request(
                        com.superz.iptvplayer.ui.theme.PremiumFeature.DOWNLOAD
                    )
                ) {
                    startDownload()
                } else {
                    _ui.update { it.copy(premiumGate = true) }
                }
            }
        }
    }

    /** v2.1.0 — the upsell dialog's dismiss/activate hand-off. */
    fun consumePremiumGate() = _ui.update { it.copy(premiumGate = false) }

    private fun startDownload() {
        val pl = _ui.value.playlist ?: return
        val movie = _ui.value.movie ?: return
        val info = _ui.value.info
        // One download at a time (the engine's iron rule): if the busy one
        // is OURS the init block has already re-attached the UI to it.
        if (RecorderEngine.isRecording) {
            flashDownloadMessage(appCtx.getString(R.string.download_busy))
            return
        }
        _ui.update { it.copy(downloadPhase = DownloadPhase.RESOLVING) }
        viewModelScope.launch {
            val url: String? = if (pl.type == "PORTAL") {
                // Stalker movies need the SAME ref + create_link round-trip
                // the play path performs (a fresh, time-limited tmp link).
                val channelKey = "vodm:$streamId"
                StalkerVodRefs.put(
                    "${pl.id}:$channelKey",
                    StalkerVodRefs.Ref(
                        cmd = info?.stalkerCmd,
                        seriesNum = null,
                        htmlItem = info?.stalkerItem
                    )
                )
                val channel = Channel(
                    playlistId = pl.id,
                    key = channelKey,
                    num = movie.num,
                    name = info?.name ?: movie.name,
                    logo = info?.poster ?: movie.poster,
                    categoryId = movie.categoryId,
                    streamId = streamId,
                    directUrl = null,
                    stalkerCmd = info?.stalkerCmd
                )
                StalkerPlayback.resolveVod(appCtx, pl, channel).directUrl
            } else {
                resolveMovieUrl()
            }
            if (url == null) {
                _ui.update { it.copy(downloadPhase = DownloadPhase.IDLE) }
                flashDownloadMessage(
                    appCtx.getString(R.string.download_failed, appCtx.getString(R.string.recording_no_url))
                )
                return@launch
            }
            val title = info?.name ?: movie.name
            val started = RecorderEngine.start(
                appCtx, url, playerUa, null, null, title,
                playerOwned = false,
                sourceKey = "vodm:$streamId"
            )
            if (!started) {
                _ui.update { it.copy(downloadPhase = DownloadPhase.IDLE) }
                flashDownloadMessage(appCtx.getString(R.string.download_busy))
                return@launch
            }
            _ui.update { it.copy(downloadPhase = DownloadPhase.RUNNING) }
            startDownloadTicker()
        }
    }

    /**
     * 1s poll of the engine statics → the button's percent/MB readout.
     * When the engine stands down (finished, failed, or the user stopped
     * it — stop() finalizes a valid partial MP4, exactly like the player's
     * old stop), the loop ends and the "saved to your library" message
     * lands. Runs in the ViewModel scope: leaving the page just stops the
     * POLLING — the download itself continues on the engine's own scope.
     */
    private fun startDownloadTicker() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            while (true) {
                if (!RecorderEngine.isRecording) {
                    // Done (saved or failed) — the engine's statics carry the
                    // outcome; the label tells the saved filename.
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
                            downloadPhase = DownloadPhase.IDLE,
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

    /** Transient message under the buttons (auto-clears after ~6s). */
    private fun flashDownloadMessage(message: String?) {
        _ui.update { it.copy(downloadMessage = message) }
        viewModelScope.launch {
            delay(6_000L)
            _ui.update { it.copy(downloadMessage = null) }
        }
    }

    companion object {
        /** Splits a panel cast string into display names (≤10). */
        fun splitCast(cast: String?): List<String> = cast
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.length > 1 }
            ?.distinct()
            ?.take(10)
            ?: emptyList()
    }
}

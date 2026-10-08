package com.superz.iptvplayer.ui.player

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import com.superz.iptvplayer.data.playback.ResumeBake
import com.superz.iptvplayer.data.recording.RecorderEngine
import com.superz.iptvplayer.data.stalker.StalkerPlayback
import com.superz.iptvplayer.data.stalker.StalkerVodRefs
import com.superz.iptvplayer.data.subtitles.SubtitleRepository
import com.superz.iptvplayer.data.xtream.VodUrlProbe
import com.superz.iptvplayer.player.BingeNavigation
import com.superz.iptvplayer.player.Engine
import com.superz.iptvplayer.player.PlayAttempt
import com.superz.iptvplayer.player.PlayerSessionManager
import com.superz.iptvplayer.player.SmartPlayer
import com.superz.iptvplayer.player.VodPlayRegistry
import com.superz.iptvplayer.player.VodSidebarBuilder
import com.superz.iptvplayer.player.multiscreen.MultiScreenSession
import com.superz.iptvplayer.ui.downloads.LocalMediaServer
import com.superz.iptvplayer.ui.downloads.SavedLibrary
import com.superz.iptvplayer.ui.downloads.SavedVideo
import com.superz.iptvplayer.ui.settings.VuSettingsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import org.videolan.libvlc.util.VLCVideoLayout
import androidx.media3.ui.PlayerView

/**
 * Player screen state: owns the SmartPlayer lifecycle, the zap order
 * (the same filtered list the user was browsing) and engine memory.
 *
 * v1.3.0: when opened from the channel view (the normal path), this screen
 * INHERITS the live session — the same SmartPlayer instance keeps playing,
 * only the video surface re-binds. Zero restart, zero re-buffering.
 * The legacy bootstrap path (direct entry / process-death restore) still
 * creates + owns the session itself.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app), SmartPlayer.Listener {

    data class PlayerUiState(
        val playlist: Playlist? = null,
        val channel: Channel? = null,
        val channels: List<Channel> = emptyList(),
        val engine: Engine? = null,
        val attemptIndex: Int = 0,
        val totalAttempts: Int = 0,
        val buffering: Boolean = false,
        val bufferingPercent: Int? = null,
        val isPlaying: Boolean = false,
        val fatal: Boolean = false,
        val ttffMs: Long? = null,
        val isFavorite: Boolean = false,
        val favoriteKeys: Set<String> = emptySet(),
        // v1.15.0 — the reference's subtitle feature state (PlayerViewModel
        // copied): active language, loading flag, transient toast message.
        val activeSubtitleCode: String? = null,
        val activeSubtitleLabel: String? = null,
        val isSubtitleLoading: Boolean = false,
        val subtitleToast: String? = null,
        // v1.16.0 — the reference's recording feature state: recording
        // flag + transient toast message (duration/size live in the engine
        // statics; the UI pill ticks them directly, like the reference's
        // PlayerActivity reads ProxyForegroundService statics).
        val isRecording: Boolean = false,
        val recordingToast: String? = null,
        // v1.18.0 — WATCH = DOWNLOAD: the current content is a movie or a
        // series episode (vodm:/vode:/…): the action button becomes
        // Download (⬇ + percent pill) instead of REC, and the auto-save
        // setting may kick in on the first frame.
        val isVod: Boolean = false,
        // v1.18.1 (user feedback) — the auto-save setting moved OUT of
        // Settings → General INTO the player's own top bar (a floppy-disk
        // toggle, cyan when ON). Reflects the persisted preference and
        // updates live via [toggleAutoSave].
        val autoSaveOn: Boolean = false,
        // v1.18.2 — RESUME WATCHING: transient confirmation pill the
        // moment the player jumps to the saved position (the recording-
        // toast pattern — auto-dismisses after 3.5s).
        val resumeToast: String? = null,
        // v1.19.0 — BINGE-WATCHING (the reference's nextEpisodeCountdown
        // trio): when a series episode enters its last 60 seconds, the
        // offered next episode's name (null = no offer) and the live
        // countdown in seconds (drives the overlay's chip; hits 0 →
        // auto-advance). -1/hidden when there is no offer.
        val nextEpisodeName: String? = null,
        val nextEpisodeCountdown: Int = -1,
        // v2.2.3 — SKIP INTRO (the Netflix-style binge pair's first half):
        // true while the episode's playhead sits inside the intro window
        // (4s settle floor → 90s target) on a long-enough episode — the
        // bottom-end plan-card pill offers the jump; the 1s binge loop
        // publishes it, a tap routes [skipIntro].
        val skipIntroVisible: Boolean = false,
        // v2.2.4 — THE CONTENT SIDEBAR (user directive: the slide-out list
        // must show THE RIGHT LIST): what the sidebar represents
        // (channels / episodes / movies), the movies-list rows (null =
        // read [channels] — live, catch-up, episodes and the local
        // library), and the lazy loads' transient flags. vodResolving =
        // a movie tapped in the list is being URL-resolved before it
        // plays (the plan-card "Preparing…" chip).
        val sidebarMode: VodSidebarBuilder.Mode = VodSidebarBuilder.Mode.CHANNELS,
        val sidebarChannels: List<Channel>? = null,
        val sidebarLoading: Boolean = false,
        val vodResolving: Boolean = false,
        // v1.19.9 — THE TIME BAR (the reference's NetflixBottomScrubber):
        // the live playback clock, published every 500ms while the EXO
        // engine owns the timeline. v1.19.10 — CHANNELS TOO: a live window
        // publishes its own (position, duration) like the reference's
        // position ticker; durationMs ≤ 0 = an empty rail (non-DVR live,
        // VLC sessions, startup).
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        // v2.1.0 — THE PREMIUM GATE: the feature whose free trial is spent
        // (null = no gate showing). Set when a free user tries a second
        // use of OFFLINE_VIEW (saved: playback), DOWNLOAD (a VOD ⬇ /
        // auto-save) or RECORD (a live REC); the screen answers with the
        // PremiumUpsellDialog. Premium never sets this — [PremiumAccess]
        // passes it straight through.
        val premiumGate: com.superz.iptvplayer.ui.theme.PremiumFeature? = null
    )

    private val appCtx = getApplication<IPTVApp>()
    private val repo = PlaylistRepository.get(appCtx)
    private val db = appCtx.database

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L
    private val initialKey: String = savedStateHandle.get<String>("channelKey") ?: ""
    private val categoryId: String? = savedStateHandle.get<String>("categoryId")?.takeIf { it != "all" }
    private val query: String = savedStateHandle.get<String>("query") ?: ""
    private val favoritesOnly: Boolean = savedStateHandle.get<Boolean>("favoritesOnly") ?: false

    // ── v1.4.0 VOD playback (movie / episode) ──
    // Non-null when this screen was opened from a VOD info page: the
    // favorite button then toggles the movie/series heart instead of the
    // live-channel star, and the zap list is the synthetic VOD channel list.
    private var vodFavoriteKey: String? = null
    private var vodFavoriteType: String = "MOVIE"

    // ── v1.18.0 — the VOD item's subtitle hints (imdb id / year / clean
    // title), handed over by the info page through VodPlayRegistry. Drives
    // SubtitleRepository's wrong-movie fix (layers 1-3).
    private var vodSubtitleMeta: VodPlayRegistry.SubtitleMeta? = null

    // ── v1.18.1 — WATCH = DOWNLOAD, the setting now lives IN THE PLAYER
    // (user feedback: “the user picks any movie/series they like and saves
    // it” — the General-settings row was removed). Semantics:
    //   • the persisted pref ("general_autosave") is read at entry and
    //     flipped by the in-player toggle button (toggleAutoSave);
    //   • pref ON → every movie/episode whose first frame fires arms the
    //     standalone RecorderEngine, as long as nothing else is recording
    //     (one download at a time; a running download survives zaps —
    //     DVR semantics);
    //   • a MANUAL stop (the ⬇/Stop button) sets [autoSaveBlocked]: the
    //     user rejected THIS download — no silent re-arm on the next zap
    //     (their will is final) until they flip the toggle again;
    //   • flipping the toggle OFF stops a download that auto-save itself
    //     started ([autoSaveActive]) but never one the user pressed ⬇ on.
    private var autoSaveBlocked = false
    private var autoSaveActive = false

    // ── v1.18.1 — Saved Videos offline playback (the library's Watch
    // button). True while THIS screen plays files served by the loopback
    // LocalMediaServer; onCleared releases the server with it.
    private var localSession = false

    // v1.18.2 — the saved-library snapshot behind the "vodm:local:{i}"
    // zap list (index → file name + absolute path). RESUME WATCHING keys
    // local entries by FILE NAME (stable) instead of the grid index
    // (shifts when saves are added/deleted), so this snapshot is the
    // index→name resolver at save time.
    private var savedLocal: List<SavedVideo> = emptyList()

    // ═══ v1.18.2 — RESUME WATCHING (the reference's binge-watching trio:
    // startAutoResume / startPositionSaveLoop / savePositionNow) ═══
    //   • every VOD playback (streamed movie/episode AND offline saved
    //     file) writes its position to PlaybackPositionManager every 5s
    //     while playing (reference cadence) + on pause + a final flush
    //     when the screen closes;
    //   • re-opening the SAME content seeks back to the saved position
    //     (>= 5s, the reference's MIN_RESTORE_POSITION_MS) with a brief
    //     "استئناف من 12:34" pill;
    //   • watching past 95% clears the record (the reference's
    //     ClearPosition-on-next-episode semantics — a finished movie
    //     restarts clean and leaves the Continue-Watching shelf);
    //   • EXO engine only: the position is read peripherally through
    //     [activeExoPlayer] (SmartPlayer's PUBLIC accessor — zero engine
    //     code touched). A VLC-engine session simply tracks nothing.
    private val positionFile: File =
        PlaybackPositionManager.storeFile(appCtx)
    private var savePositionJob: Job? = null
    /** The channel key whose resume point is armed (seek once per key). */
    private var resumeArmedKey: String? = null
    /** In-memory position of the CURRENT channel (engine-restart re-seek). */
    private var lastKnownPositionMs = 0L

    /** v1.19.8 — the channel whose resume offset PROVED unservable (the
     *  baked open was abandoned by the chain): never re-seek this key
     *  again this session — a retry/re-open would recreate the exact
     *  stall the chain just recovered from. The save loop still
     *  tracks fresh progress from the new position. */
    private var resumeSeekBrokenKey: String? = null

    // ── v2.3.1 — MULTI-SCREEN ENGINE HAND-OFF state ──
    /** True once the grid started and THIS engine was released for it. */
    private var releasedForMultiScreen = false
    /** The session-registry unregister token (invoked in onCleared). */
    private var unregisterEnginePark: (() -> Unit)? = null

    // ── v1.19.0 — BINGE-WATCHING (the reference's startBingeWatchLoop /
    // playNextEpisode pair, adapted to Oria's zap-list world: the next
    // episode is the next vode: sibling in THIS screen's channel list).
    // A 1s-cadence loop mirrors the 5s save loop; entering the last 60s
    // of an episode publishes the offer, hitting 0 auto-zaps forward
    // (the finished episode's record is cleared first — the reference's
    // ClearPosition event on next-episode). ──
    private var bingeJob: Job? = null

    // ── v1.19.9 — THE TIME BAR clock: 500ms ticker publishing the
    // ExoPlayer's (position, duration) into UiState for the bottom
    // scrubber. Runs on the main dispatcher (ExoPlayer read contract),
    // from the first onPlaying until the player instance is gone or the
    // screen clears; a zap resets the values so a new content never
    // inherits the old timeline. ──
    private var timeBarJob: Job? = null

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    // ── Session (v1.3.0) ──
    // ensure() returns the LIVE session when the channel view already
    // started one (normal path) or creates a fresh one (fallback path).
    private val session: PlayerSessionManager.Session =
        PlayerSessionManager.ensure(appCtx, viewModelScope, appCtx.okHttp)

    /** True when the channel view below us still owns the session. */
    private val inherited: Boolean = session.started

    val smartPlayer: SmartPlayer = session.smartPlayer

    /** Active ExoPlayer for view binding (exposed read-only). */
    val activeExoPlayer get() = smartPlayer.activeExoForView

    private var playlist: Playlist? = null
    private var channels: List<Channel> = emptyList()
    private var currentIndex = -1
    private var memoryMap: MutableMap<String, EngineMemory> = mutableMapOf()

    init {
        smartPlayer.isPreloadEnabled = appCtx.isUnmeteredNetwork()
        // v2.3.1 — MULTI-SCREEN ENGINE HAND-OFF: register THIS screen's
        // engine with the session registry so the grid can RELEASE it
        // the moment it truly starts (the v2.3.0 field crash: the parked
        // single player held a decoder while the grid demanded 2-4 more
        // and the box's codec farm answered fatally). The unregister
        // token is invoked in onCleared — a dead screen never releases
        // a recycled engine.
        unregisterEnginePark = MultiScreenSession.registerEngineReleaser {
            releaseEngineForMultiScreen()
        }
        // v1.18.1 — the auto-save preference (now toggled in-player) seeds
        // the toggle button's state at entry.
        _ui.update { it.copy(autoSaveOn = VuSettingsPrefs.autoSave(appCtx)) }
        if (inherited) {
            // ── Continuation: the stream is ALREADY playing — seed the UI
            // from the shared session state and take over the listener.
            playlist = session.playlist
            channels = session.channels
            currentIndex = session.currentIndex
            memoryMap.putAll(session.memoryMap)
            smartPlayer.engineMemory = session.memoryMap
            session.dispatcher.activate(this)
            viewModelScope.launch {
                val pl = playlist
                val favKeys = if (pl != null) {
                    db.favoriteDao().favoriteKeysFlow(pl.id).first().toSet()
                } else emptySet()
                _ui.update {
                    it.copy(
                        playlist = pl,
                        channels = channels,
                        channel = channels.getOrNull(currentIndex),
                        engine = session.dispatcher.lastEngine,
                        isPlaying = session.dispatcher.isPlaying,
                        favoriteKeys = favKeys,
                        isFavorite = channels.getOrNull(currentIndex)
                            ?.let { c -> favKeys.contains(c.key) } ?: false
                    )
                }
            }
        } else {
            // v1.4.1 fix — the bootstrap path (VOD playback / direct entry)
            // MUST activate itself as the session's listener BEFORE starting
            // playback. In v1.4.0 the stream was started with the dispatcher
            // still pointing at null: engine events (onEngineAttempt,
            // onBuffering, onFirstFrame…) never reached this ViewModel, the
            // engine state stayed null and the screen composed NO video
            // surface at all — black player with working audio.
            session.dispatcher.activate(this)
            viewModelScope.launch {
                // v1.18.1 — SAVED VIDEOS offline entry FIRST: the library's
                // Watch button routes here with "saved:<absolute file path>"
                // (the hub's 4th card → Saved Videos → Watch). The WHOLE
                // library becomes the zap list (next/prev hop between saved
                // videos), every file is served over the loopback
                // LocalMediaServer, and the LOCKED engine plays the
                // http://127.0.0.1 URL like any network stream — zero
                // engine changes, works in airplane mode. The playlist is
                // the route's (hub pattern: -1 → the ACTIVE playlist).
                if (initialKey.startsWith("saved:")) {
                    // v2.1.0 — OFFLINE VIEWING is a premium feature: the
                    // free plan's single trial plays ONCE, then the gate
                    // dialog asks for the subscription (user: "ميزة
                    // المشاهدة بدون انترنت… مرة واحدة فقط"). Gating HERE —
                    // in the player's saved: bootstrap — covers every
                    // entry point: the Saved Videos library, the browse
                    // page's continue-watching card, any future one.
                    if (!com.superz.iptvplayer.ui.theme.PremiumAccess.request(
                            com.superz.iptvplayer.ui.theme.PremiumFeature.OFFLINE_VIEW
                        )
                    ) {
                        _ui.update {
                            it.copy(
                                premiumGate = com.superz.iptvplayer.ui.theme.PremiumFeature.OFFLINE_VIEW,
                                fatal = false
                            )
                        }
                        return@launch
                    }
                    val targetPath = initialKey.removePrefix("saved:")
                    val pl = db.playlistDao().playlistById(playlistId)
                        ?: db.playlistDao().activePlaylist()
                        ?: return@launch
                    val saved = SavedLibrary.scan(appCtx)
                    val base = if (saved.isNotEmpty()) LocalMediaServer.acquire() else ""
                    val urls = if (base.isNotBlank()) {
                        LocalMediaServer.register(saved.map { java.io.File(it.path) })
                    } else emptyList()
                    if (urls.isEmpty()) {
                        // empty library (deleted since the list was shown) or
                        // the server could not bind — the standard fatal card.
                        _ui.update { it.copy(fatal = true) }
                        return@launch
                    }
                    localSession = true
                    // v1.18.2 — resume-watching key resolver: "vodm:local:{i}"
                    // → the FILE NAME (stable across library changes).
                    savedLocal = saved
                    playlist = pl
                    session.playlist = pl
                    val chans = saved.mapIndexed { i, v ->
                        // "vodm:" prefix → the engine's own VOD semantics
                        // (no live auto-restart at the file's natural end,
                        // Download-style button); "local:" separates it from
                        // every online vodm:{streamId} key.
                        Channel(
                            playlistId = pl.id,
                            key = "vodm:local:$i",
                            num = i + 1,
                            name = v.title,
                            logo = null,
                            categoryId = null,
                            streamId = null,
                            directUrl = urls[i]
                        )
                    }
                    channels = chans
                    session.channels = chans
                    memoryMap.putAll(repo.engineMemory(pl.id).toMutableMap())
                    session.memoryMap.putAll(memoryMap)
                    smartPlayer.engineMemory = session.memoryMap
                    val idx = saved.indexOfFirst { it.path == targetPath }
                        .takeIf { it >= 0 } ?: 0
                    vodFavoriteKey = chans.getOrNull(idx)?.key
                    vodFavoriteType = "MOVIE"
                    vodSubtitleMeta = null
                    currentIndex = idx
                    session.currentIndex = idx
                    // The same generous VOD first-frame budgets as the movie
                    // player — a big local .mp4 can legitimately take a while
                    // to its moov atom on slow USB storage.
                    smartPlayer.firstAttemptTimeoutMs = 15_000L
                    smartPlayer.midAttemptTimeoutMs = 15_000L
                    smartPlayer.lastAttemptTimeoutMs = 25_000L
                    val favKeys = db.vodDao().vodFavoriteKeysFlow(pl.id).first().toSet()
                    _ui.update {
                        it.copy(
                            playlist = pl,
                            channels = chans,
                            channel = chans.getOrNull(idx),
                            favoriteKeys = favKeys,
                            isFavorite = vodFavoriteKey?.let { k -> favKeys.contains(k) } ?: false,
                            isVod = true
                        )
                    }
                    chans.getOrNull(idx)?.let { ch -> playResolved(pl, ch) }
                    return@launch
                }

                // v1.4.0 — VOD entry: a movie/episode registered by an info
                // page. Synthetic channel(s) carry the direct Xtream URL, so
                // the locked engine plays them through its normal chain.
                val vod = VodPlayRegistry.consume("$playlistId:$initialKey")
                if (vod != null) {
                    val pl = vod.playlist
                    playlist = pl
                    session.playlist = pl
                    channels = vod.channels
                    session.channels = vod.channels
                    memoryMap.putAll(repo.engineMemory(pl.id).toMutableMap())
                    session.memoryMap.putAll(memoryMap)
                    smartPlayer.engineMemory = session.memoryMap
                    vodFavoriteKey = vod.favoriteKey
                    vodFavoriteType = vod.favoriteType
                    vodSubtitleMeta = vod.subtitleMeta
                    val idx = vod.startIndex.coerceIn(0, (vod.channels.size - 1).coerceAtLeast(0))
                    currentIndex = idx
                    session.currentIndex = idx
                    // v1.4.2 — VOD timeout budgets: a big movie file can
                    // legitimately need 10-20s to its first frame (moov-atom
                    // fetch on non-faststart MP4s, slow panel disk). The LIVE
                    // defaults (4.5s/9s) killed those attempts — most movies
                    // never started. Episodes start fast anyway; these longer
                    // budgets only live on this VOD session, which is released
                    // when the player closes (live sessions keep the defaults).
                    smartPlayer.firstAttemptTimeoutMs = 15_000L
                    smartPlayer.midAttemptTimeoutMs = 15_000L
                    smartPlayer.lastAttemptTimeoutMs = 25_000L
                    val favKeys = db.vodDao().vodFavoriteKeysFlow(pl.id).first().toSet()
                    _ui.update {
                        it.copy(
                            playlist = pl,
                            channels = vod.channels,
                            channel = vod.channels.getOrNull(idx),
                            favoriteKeys = favKeys,
                            isFavorite = favKeys.contains(vod.favoriteKey),
                            isVod = vod.channels.getOrNull(idx)
                                ?.let { c -> isVodKey(c.key) } ?: false,
                            // v2.2.4 — the sidebar knows what it represents
                            // from the very first frame (lazy loads enrich
                            // the list the moment the sidebar opens).
                            sidebarMode = VodSidebarBuilder.modeFor(
                                vod.channels.getOrNull(idx)?.key, localSession = false
                            )
                        )
                    }
                    vod.channels.getOrNull(idx)?.let { ch ->
                        playResolved(pl, ch)
                    }
                    return@launch
                }

                // v1.19.8 — a VOD route whose registry hand-off is GONE
                // (process death: the nav controller restores this route,
                // but the in-memory registry died with the process) must
                // NOT fall through to the live list below — that legacy
                // bootstrap played channel #1 (a random live stream)
                // instead of the requested movie/episode, which then sat
                // buffering through its engine chain while the user
                // wondered where their video went. The honest error card
                // wins: the user re-taps the card and the fresh
                // registration (BrowseViewModel.prepareResume) plays.
                if (initialKey.startsWith("vodm:") || initialKey.startsWith("vode:") ||
                    initialKey.startsWith("vodc:") || initialKey.startsWith("vodx:") ||
                    initialKey.startsWith("vodt:")
                ) {
                    _ui.update { it.copy(fatal = true) }
                    return@launch
                }

                val pl = db.playlistDao().playlistById(playlistId) ?: return@launch
                playlist = pl
                session.playlist = pl
                val list = if (favoritesOnly) {
                    db.channelDao().favoritesFlow(pl.id, categoryId, query).first()
                } else {
                    // v1.12.5 — parental: the "All" zap list EXCLUDES exactly
                    // ONE xxx category (the reference's
                    // getLiveChannelsByCategory(all): notEqualTo(category_id,
                    // Constants.xxx_category_id) — the LAST name match); the
                    // gated categories pass through their OWN id when entered
                    // with a PIN.
                    val xxxId = if (categoryId == null) {
                        try {
                            com.superz.iptvplayer.ui.components.ParentalControl.xxxExcludedId(
                                db.channelDao().xxxCandidateCategories(pl.id),
                                portal = pl.type == "PORTAL"
                            )
                        } catch (_: Throwable) {
                            null
                        }
                    } else null
                    if (xxxId != null) {
                        db.channelDao().channelsFlowExcluding(pl.id, null, query, false, listOf(xxxId)).first()
                    } else {
                        db.channelDao().channelsFlow(pl.id, categoryId, query).first()
                    }
                }
                channels = list
                session.channels = list
                memoryMap.putAll(repo.engineMemory(pl.id).toMutableMap())
                session.memoryMap.putAll(memoryMap)
                smartPlayer.engineMemory = session.memoryMap

                val favKeys = db.favoriteDao().favoriteKeysFlow(pl.id).first().toSet()
                val idx = list.indexOfFirst { it.key == initialKey }.takeIf { it >= 0 } ?: 0
                currentIndex = idx
                session.currentIndex = idx
                _ui.update {
                    it.copy(
                        playlist = pl,
                        channels = list,
                        channel = list.getOrNull(idx),
                        favoriteKeys = favKeys,
                        isFavorite = list.getOrNull(idx)?.let { c -> favKeys.contains(c.key) } ?: false
                    )
                }
                list.getOrNull(idx)?.let { ch ->
                    smartPlayer.preloadNext(pl, list.getOrNull(idx + 1))
                    playResolved(pl, ch)
                }
            }
        }
    }

    // ── View binding ────────────────────────────────────────────

    fun attachExoView(view: PlayerView) = smartPlayer.attachViews(view)
    fun attachVlcView(view: VLCVideoLayout) = smartPlayer.attachVlcView(view)
    fun detachExoView(view: PlayerView) = smartPlayer.detachExoView(view)
    fun detachVlcView(view: VLCVideoLayout) = smartPlayer.detachVlcView(view)

    // ── Zap actions ─────────────────────────────────────────────

    fun selectChannel(key: String) {
        val idx = channels.indexOfFirst { it.key == key }
        if (idx >= 0) playAt(idx)
    }

    fun nextChannel() {
        if (channels.isEmpty()) return
        playAt((currentIndex + 1).mod(channels.size))
    }

    fun prevChannel() {
        if (channels.isEmpty()) return
        playAt((currentIndex - 1).mod(channels.size))
    }

    private fun playAt(idx: Int) {
        val pl = playlist ?: return
        val ch = channels.getOrNull(idx) ?: return
        currentIndex = idx
        session.currentIndex = idx
        session.channels = channels
        // v1.19.9 — a fresh content starts with a BLANK clock: the time
        // bar must never inherit the previous video's timeline while the
        // new one buffers (the ticker republishes real values on the
        // first frame).
        _ui.update {
            it.copy(
                channel = ch,
                fatal = false,
                ttffMs = null,
                positionMs = 0L,
                durationMs = 0L,
                isFavorite = it.favoriteKeys.contains(ch.key),
                isVod = isVodKey(ch.key)
            )
        }
        playResolved(pl, ch)
    }

    /** v1.18.0 — synthetic VOD channel keys (movies / episodes / catch-up). */
    private fun isVodKey(key: String?): Boolean =
        key != null && (key.startsWith("vodm:") || key.startsWith("vode:") ||
            key.startsWith("vodc:") || key.startsWith("vodx:") || key.startsWith("vodt:"))

    /** v1.18.0 — auto-save targets exactly movies and series episodes. */
    private fun isAutoSaveKey(key: String?): Boolean =
        key != null && (key.startsWith("vodm:") || key.startsWith("vode:"))

    /**
     * v1.11.0 — the ONE play entry for this screen: PORTAL (stalker)
     * vodm:/vode: channels get their time-limited URL resolved via
     * create_link FIRST (StalkerPlayback.resolveVod — cached handshake,
     * one call per zap, exactly the reference's per-open create_link);
     * every other channel type passes through untouched into the SAME
     * locked-engine call as before.
     * v1.12.0 — vodc: (catch-up programs) joins the same resolution path
     * (tv_archive create_link per open, the reference's CatchUpPlayActivity).
     * v1.12.2 — BUG FIX: LIVE PORTAL channels (k:) now resolve here too
     * (StalkerPlayback.resolveChannel handles them — fresh create_link per
     * zap, ChannelViewViewModel's exact flow). Before, a zap in THIS screen
     * passed the raw DB channel (directUrl=null) into SmartPlayer → empty
     * chain → instant fatal. resolveChannel routes vodm:/vode:/vodc:
     * internally, so this single branch covers every PORTAL channel type.
     */
    private fun playResolved(pl: Playlist, ch: Channel) {
        // v1.19.12 — THE OPEN-TIME RESUME BAKE: decide the parked/rolling
        // verdict BEFORE the media even prepares (a fast IO read of the
        // stored record — ResumeBake.decide), then hand it to SmartPlayer,
        // which prepares the source AT the stop point and parks there.
        // This replaces the v1.19.10 post-hoc park (pause after the first
        // frame + a supervised mid-flight seek) whose event races made the
        // parked-open's play button untrustworthy. Saved Videos entries
        // ("vodm:local:") bake exactly like streamed movies — the loopback
        // URL itself is already resolved.
        viewModelScope.launch {
            val bake = bakeResume(ch)
            if (bake.parked) {
                lastKnownPositionMs = bake.resumeAtMs
                showResumeToast(bake.resumeAtMs)
            }
            if (ch.key.startsWith("vodm:local:")) {
                // v1.18.1 — directUrl is ALREADY a loopback URL
                // (LocalMediaServer) — no stalker resolve, no URL probing.
                smartPlayer.play(pl, ch, bake.resumeAtMs, bake.parked)
                return@launch
            }
            if (pl.type == "PORTAL") {
                val resolved = StalkerPlayback.resolveChannel(appCtx, pl, ch)
                smartPlayer.play(pl, resolved, bake.resumeAtMs, bake.parked)
            } else {
                smartPlayer.play(pl, ch, bake.resumeAtMs, bake.parked)
            }
        }
    }

    // ═══ v2.2.4 — THE CONTENT SIDEBAR ═══════════════════════════════
    // The slide-out list must show THE RIGHT LIST for what's playing
    // (user: “يجب أن تظهر الحلقات… و نفس الشيء للأفلام يجب أن تظهر قائمة
    // الأفلام كي لا يبقى المستخدم يخرج من المشغل ليختار فيلم أو حلقة أو
    // مسلسل”) — and it must never CRASH again (the v2.2.3 bug: the sidebar
    // keyed its LazyColumn rows by the synthetic channels' Room id, all 0
    // for episodes → “Key 0 was already used” the moment a series player
    // opened it; the rows now key by Channel.key, unique by construction).
    //
    //  • EPISODES session → the FULL series (every season, in order)
    //    lazily replaces the zap list the first time the sidebar opens —
    //    next/prev AND the auto-next-episode binge then walk the whole
    //    series (S01E12 → S02E01, the platforms' behavior). The info
    //    page's current-season registration stays as the instant
    //    fallback if the (cached) fetch fails.
    //  • MOVIES session → the playlist's movie list (a DB page, search
    //    re-queries the WHOLE library). The movie zap list stays the
    //    single playing row (a movie session's contract) — the list
    //    lives in UiState.sidebarChannels; a tap resolves the URL
    //    (probe chain) and swaps the session to that movie.
    private var seriesSidebarLoaded = false
    private var vodSidebarJob: Job? = null
    private var vodSearchJob: Job? = null
    private var vodResolveJob: Job? = null

    /** The sidebar's last search text (re-open keeps the filtered list). */
    private var sidebarQueryLast: String = ""

    /** The screen calls this the moment the sidebar opens (lazy loads). */
    fun onSidebarOpened() {
        val key = channels.getOrNull(currentIndex)?.key ?: return
        when (VodSidebarBuilder.modeFor(key, localSession)) {
            VodSidebarBuilder.Mode.EPISODES -> loadFullSeries()
            VodSidebarBuilder.Mode.MOVIES -> loadMoviesList(sidebarQueryLast)
            VodSidebarBuilder.Mode.CHANNELS -> Unit
        }
    }

    /** The sidebar's search field — re-queries the DB in MOVIES mode. */
    fun onSidebarSearch(q: String) {
        sidebarQueryLast = q
        if (_ui.value.sidebarMode != VodSidebarBuilder.Mode.MOVIES) return
        vodSearchJob?.cancel()
        vodSearchJob = viewModelScope.launch {
            delay(350)   // debounce — typed queries arrive as a burst
            loadMoviesList(q)
        }
    }

    /**
     * The full series as the zap list. Guarded: the playing episode MUST
     * be present in the built list (the cached info returns the same ids
     * the info page registered) — otherwise the current index would
     * point at the wrong channel and the save/favorite logic would act
     * on the wrong key, so the registered current-season list survives.
     */
    private fun loadFullSeries() {
        if (seriesSidebarLoaded) return
        val pl = playlist ?: return
        val seriesId = vodFavoriteKey?.removePrefix("sr:")?.toLongOrNull() ?: return
        seriesSidebarLoaded = true
        vodSidebarJob?.cancel()
        vodSidebarJob = viewModelScope.launch {
            _ui.update { it.copy(sidebarLoading = true, sidebarMode = VodSidebarBuilder.Mode.EPISODES) }
            val info = try {
                repo.seriesInfo(pl, seriesId)   // cached — the info page just fetched it
            } catch (_: Exception) { null }
            val seasons = info?.seasons.orEmpty()
            val current = channels.getOrNull(currentIndex)
            val currentKey = current?.key
            if (seasons.isEmpty()) {
                // keep the registered current-season episodes — still the
                // episodes list, just one season of it
                _ui.update { it.copy(sidebarLoading = false) }
                return@launch
            }
            val posterFallback = current?.logo
            val built = VodSidebarBuilder.buildEpisodeChannels(
                playlistId = pl.id,
                seasons = seasons,
                posterFallback = posterFallback,
                resolve = { ep ->
                    if (pl.type == "PORTAL") {
                        // refs armed NOW so playResolved's create_link finds them
                        StalkerVodRefs.put(
                            "${pl.id}:vode:${ep.id}",
                            StalkerVodRefs.Ref(
                                cmd = ep.stalkerCmd,
                                seriesNum = ep.stalkerSeriesNum,
                                htmlItem = ep.stalkerItem
                            )
                        )
                        null to ep.stalkerCmd
                    } else {
                        (ep.directSource
                            ?: repo.episodeUrl(pl, ep.id, ep.containerExtension)) to null
                    }
                },
                current = current
            )
            if (built.size <= 1 || currentKey == null || built.none { it.key == currentKey }) {
                _ui.update { it.copy(sidebarLoading = false) }
                return@launch
            }
            channels = built
            session.channels = built
            currentIndex = built.indexOfFirst { it.key == currentKey }.also { idx ->
                session.currentIndex = idx
            }
            _ui.update {
                it.copy(channels = built, sidebarLoading = false)
            }
        }
    }

    /** The movies list page (DB) — [q] filters the WHOLE library (LIKE). */
    private fun loadMoviesList(q: String) {
        val pl = playlist ?: return
        vodSidebarJob?.cancel()
        vodSidebarJob = viewModelScope.launch {
            val firstLoad = _ui.value.sidebarChannels == null
            if (firstLoad) {
                _ui.update {
                    it.copy(sidebarLoading = true, sidebarMode = VodSidebarBuilder.Mode.MOVIES)
                }
            }
            val rows = try {
                db.vodDao().moviesPage(
                    pl.id, null, q.trim(), VodSidebarBuilder.MOVIES_SIDEBAR_LIMIT, 0
                )
            } catch (_: Exception) { emptyList() }
            val list = VodSidebarBuilder.buildMovieChannels(rows, channels.getOrNull(currentIndex))
            _ui.update { it.copy(sidebarChannels = list, sidebarLoading = false) }
        }
    }

    /**
     * A movie tapped in the sidebar: resolve its playback URL (the SAME
     * probe chain the info page / Continue-Watching use), then swap this
     * movie session onto it — a one-row zap list, exactly the registry's
     * movie contract (favorite + resume + subtitle hints re-pointed).
     * While resolving, the plan-card “Preparing…” chip shows; a failed
     * resolve plays the unresolved row through the honest fatal card.
     */
    fun selectVodMovie(key: String) {
        val pl = playlist ?: return
        if (key == channels.getOrNull(currentIndex)?.key) return   // already playing
        vodResolveJob?.cancel()
        vodResolveJob = viewModelScope.launch {
            _ui.update { it.copy(vodResolving = true) }
            try {
                val sid = key.removePrefix("vodm:").toLongOrNull() ?: return@launch
                val movie = try {
                    db.vodDao().movieByStreamId(pl.id, sid)
                } catch (_: Exception) { null }
                val info = try { repo.movieInfo(pl, sid) } catch (_: Exception) { null }
                val name = info?.name ?: movie?.name ?: key
                val ch: Channel
                if (pl.type == "PORTAL") {
                    StalkerVodRefs.put(
                        "${pl.id}:$key",
                        StalkerVodRefs.Ref(
                            cmd = info?.stalkerCmd,
                            seriesNum = null,
                            htmlItem = info?.stalkerItem
                        )
                    )
                    ch = Channel(
                        playlistId = pl.id,
                        key = key,
                        num = movie?.num ?: 0,
                        name = name,
                        logo = info?.poster ?: movie?.poster,
                        categoryId = movie?.categoryId,
                        streamId = sid,
                        directUrl = null,
                        stalkerCmd = info?.stalkerCmd
                    )
                } else {
                    // Xtream — BrowseViewModel.prepareMovieResume's exact chain
                    val exts = sequenceOf(
                        info?.containerExtension,
                        movie?.containerExtension,
                        "mp4", "mkv", "avi"
                    )
                        .mapNotNull { it }
                        .map { it.trim().lowercase().filter { c -> c.isLetterOrDigit() } }
                        .filter { it.length in 1..5 }
                        .distinct()
                        .toList()
                    val candidates = exts
                        .mapNotNull { ext -> repo.movieUrl(pl, sid, ext) }
                        .distinct()
                    val url = when {
                        candidates.isEmpty() ->
                            repo.movieUrl(pl, sid, info?.containerExtension ?: movie?.containerExtension)
                        candidates.size == 1 -> candidates.first()
                        else -> VodUrlProbe.best(appCtx.okHttp, candidates)
                    }
                    ch = Channel(
                        playlistId = pl.id,
                        key = key,
                        num = movie?.num ?: 0,
                        name = name,
                        logo = info?.poster ?: movie?.poster,
                        categoryId = movie?.categoryId,
                        streamId = sid,
                        directUrl = url
                    )
                }
                // the session becomes THIS movie (the registry's one-row contract)
                channels = listOf(ch)
                session.channels = channels
                currentIndex = 0
                session.currentIndex = 0
                vodFavoriteKey = movie?.key ?: "m:$sid"
                vodFavoriteType = "MOVIE"
                vodSubtitleMeta = VodPlayRegistry.SubtitleMeta(
                    imdbId = info?.imdbId,
                    year = SubtitleRepository.extractYear(
                        info?.releaseDate ?: movie?.name
                    ),
                    cleanName = name
                )
                val favKeys = db.vodDao().vodFavoriteKeysFlow(pl.id).first().toSet()
                _ui.update {
                    it.copy(
                        channel = ch,
                        channels = channels,
                        fatal = false,
                        ttffMs = null,
                        positionMs = 0L,
                        durationMs = 0L,
                        isVod = true,
                        favoriteKeys = favKeys,
                        isFavorite = favKeys.contains(vodFavoriteKey),
                        nextEpisodeName = null,
                        nextEpisodeCountdown = -1
                    )
                }
                playResolved(pl, ch)
            } finally {
                _ui.update { it.copy(vodResolving = false) }
            }
        }
    }

    /**
     * v1.19.12 — the bake verdict for the channel about to open:
     *  • a MID-WATCH recovery (engine chain re-resolve of the SAME key —
     *    onStreamEnded) re-bakes the last known position ROLLING, so the
     *    recovery is invisible;
     *  • a fresh open of a movie/episode with a valid stored stop point
     *    bakes it PARKED (the Continue-Watching contract);
     *  • everything else (no record, watched to the end, below the
     *    restore minimum, or broken this session) opens FRESH.
     */
    private suspend fun bakeResume(ch: Channel): ResumeBake.Decision {
        val restartAtMs = if (resumeArmedKey == ch.key) lastKnownPositionMs else 0L
        if (!isAutoSaveKey(ch.key)) return ResumeBake.Decision.FRESH
        val storeKey = positionStoreKey(ch.key) ?: return ResumeBake.Decision.FRESH
        val rec = withContext(Dispatchers.IO) {
            PlaybackPositionManager.record(positionFile, storeKey)
        }
        return ResumeBake.decide(
            record = rec,
            brokenThisSession = resumeSeekBrokenKey == ch.key,
            restartAtMs = restartAtMs
        )
    }

    /** v1.19.12 — the chain abandoned a baked open (the stored offset was
     *  unservable on the first engine/variant): mark the key broken for
     *  this session so a retry/re-open goes fresh. */
    override fun onResumeSeekFailed(channelKey: String) {
        resumeSeekBrokenKey = channelKey
    }

    fun retry() {
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        _ui.update { it.copy(fatal = false, ttffMs = null, positionMs = 0L, durationMs = 0L) }
        playResolved(pl, ch)
    }

    fun togglePlayPause() {
        // v1.19.12 — with the open-time bake there is exactly ONE programmatic
        // pause-flip (the bake, applied before the media prepares) and exactly
        // one thing that can flip playWhenReady afterwards: this press. No
        // flags to clear, no supervision to stand down — the live toggle is
        // the whole contract.
        //
        // v2.3.1 — MULTI-SCREEN RETURN: the engine was released when the
        // grid started (the codec-budget hand-off); the play press is the
        // professional re-entry — the channel RE-STARTS through the
        // standard play path (the resume bake restores a VOD position,
        // live re-joins the edge), exactly like returning from any
        // multiview in TiviMate / IPTV Smarters.
        if (releasedForMultiScreen) {
            releasedForMultiScreen = false
            val pl = playlist
            val ch = channels.getOrNull(currentIndex)
            if (pl != null && ch != null) {
                _ui.update { it.copy(fatal = false, buffering = true) }
                playAt(currentIndex)
            }
            return
        }
        smartPlayer.togglePlayPause()
    }

    /**
     * v2.3.0 — MULTI-SCREEN hand-off: the golden grid key was pressed —
     * park THIS stream deterministically before the grid's own players
     * take the audio (the system audio-focus loss would pause it anyway
     * a beat later; parking it ourselves keeps the transition silent
     * and instant). The screen stays alive under the setup route; the
     * user's play press resumes it exactly like any manual pause.
     *
     * v2.3.1 — the parking stays a PAUSE (the user may cancel the setup
     * and come straight back); the full RELEASE happens only when the
     * grid truly starts, through [releaseEngineForMultiScreen].
     */
    fun pauseForMultiScreen() {
        try {
            if (smartPlayer.isPlaying) smartPlayer.togglePlayPause()
        } catch (_: Exception) {
        }
    }

    /**
     * v2.3.1 — THE GRID IS STARTING: return this screen's decoder,
     * audio focus and GPU surfaces to the box (the codec-budget
     * hand-off behind the v2.3.0 crash fix). The UI falls to the paused
     * face; the next play press re-enters through [togglePlayPause]'s
     * released branch (VOD resumes from its bake, live re-joins the
     * edge). Invoked from the MultiScreenSession registry — never call
     * directly.
     */
    private fun releaseEngineForMultiScreen() {
        if (releasedForMultiScreen) return
        releasedForMultiScreen = true
        try {
            smartPlayer.release()
        } catch (_: Exception) {
        }
        _ui.update { it.copy(isPlaying = false, buffering = false) }
    }

    // ═══ v1.19.9 — THE TIME BAR (the reference's NetflixBottomScrubber) ═══

    /**
     * The 500ms clock behind the bottom time bar: publishes the active
     * ExoPlayer's (position, duration) into UiState. It runs REGARDLESS of
     * play/pause (a paused bar must keep showing its frozen position and
     * stay live for the next frame) and simply ends when the EXO player
     * instance goes away (VLC takeover / teardown).
     *
     * v1.19.10 — CHANNELS GET THE BAR TOO (the reference's player renders
     * its scrubber on live content): a live window publishes its OWN
     * (position, duration) exactly like the reference's position ticker —
     * a TIME_UNSET duration coerces to 0 (an empty rail + "00:00", the
     * reference's own rendering for non-DVR live) instead of suppressing
     * the whole clock. VOD values are unchanged (duration > 0 → coerced
     * position as before).
     */
    private fun startTimeBarLoop() {
        timeBarJob?.cancel()
        timeBarJob = viewModelScope.launch {
            while (isActive) {
                val player = activeExoPlayer ?: break
                try {
                    val dur = player.duration.coerceAtLeast(0L)
                    val pos = if (dur > 0L) player.currentPosition.coerceIn(0L, dur)
                    else player.currentPosition.coerceAtLeast(0L)
                    _ui.update { it.copy(positionMs = pos, durationMs = dur) }
                } catch (_: Throwable) {
                    // the player was released mid-read — the next pass breaks
                }
                delay(500L)
            }
        }
    }

    /**
     * A MANUAL seek from the time bar (tap or drag-commit). The user's
     * finger is the highest authority in the room:
     *  • EXO + a seekable timeline only (VLC has no peripheral seek here);
     *  • v1.19.10 — CHANNELS TOO: a live window's own timeline is
     *    scrubbable exactly like the reference's scrubber-on-live (the
     *    position clamps into the window; a non-DVR window has no
     *    duration and the call is a clean no-op);
     *  • the position is optimistically published so the bar jumps the
     *    instant the finger lifts (the ticker confirms/replaces it);
     *  • v1.19.12 — the scrub updates lastKnownPositionMs, so a later
     *    engine restart re-seeks HERE (the user's chosen spot), never
     *    back to the old stored stop point;
     *  • the 5s save loop needs nothing — it reads the player directly, so
     *    the record naturally moves to the new position on the next tick.
     */
    fun seekTo(positionMs: Long) {
        if (_ui.value.engine != Engine.EXO) return
        val player = activeExoPlayer ?: return
        val dur: Long
        try {
            dur = player.duration
        } catch (_: Throwable) {
            return
        }
        if (dur <= 0L) return
        val target = positionMs.coerceIn(0L, dur)
        try {
            player.seekTo(target)
        } catch (_: Throwable) {
            return
        }
        lastKnownPositionMs = target
        _ui.update { it.copy(positionMs = target) }
    }

    fun toggleFavorite() {
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        // v1.4.0 — VOD mode: the heart toggles the movie/series favorite.
        val vodKey = vodFavoriteKey
        if (vodKey != null) {
            viewModelScope.launch {
                val nowFav = repo.toggleVodFavorite(pl.id, vodKey, vodFavoriteType)
                val keys = db.vodDao().vodFavoriteKeysFlow(pl.id).first().toSet()
                _ui.update { it.copy(favoriteKeys = keys, isFavorite = nowFav) }
            }
            return
        }
        viewModelScope.launch {
            val nowFav = repo.toggleFavorite(pl.id, ch.key)
            val keys = db.favoriteDao().favoriteKeysFlow(pl.id).first().toSet()
            memoryReloadSafe(pl.id)
            _ui.update { it.copy(favoriteKeys = keys, isFavorite = nowFav) }
        }
    }

    private suspend fun memoryReloadSafe(pid: Long) {
        try {
            val fresh = repo.engineMemory(pid)
            smartPlayer.engineMemory = fresh
            session.memoryMap.putAll(fresh)
        } catch (e: Exception) { /* ignore */ }
    }

    // ── Aspect + audio ──────────────────────────────────────────

    fun cycleAspect(): Int = smartPlayer.cycleResizeMode()

    fun audioTracks(): List<SmartPlayer.AudioTrackInfo> = smartPlayer.audioTracks()

    fun selectAudioTrack(track: SmartPlayer.AudioTrackInfo): Boolean =
        smartPlayer.selectAudioTrack(track)

    // ── Subtitles (v1.15.0 — the reference's feature, engine copied) ─────

    /**
     * Resolves and downloads a subtitle for the given language code, then
     * side-loads it into the active ExoPlayer — the reference's
     * requestSubtitle + InjectSubtitle handler, same logic.
     *
     * Iron-rule compliance: SmartPlayer.kt is untouched. The media-item
     * rebuild happens on the PUBLIC activeExoPlayer instance (the same
     * external-parameter pattern SubtitleActivation established in
     * v1.7.0). The stream URI and position are preserved, so playback
     * continues seamlessly; the engine's attempt timeout is already
     * cancelled (success was notified before the user could tap), and the
     * state listener only sees a brief BUFFERING→READY blink.
     */
    fun requestSubtitle(languageCode: String, languageLabel: String) {
        val player = activeExoPlayer
        val channel = channels.getOrNull(currentIndex)
        if (player == null || _ui.value.engine != Engine.EXO) {
            subtitleToast("Subtitles are unavailable on the current engine")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isSubtitleLoading = true) }
            val channelName = channel?.name ?: ""

            // v1.18.0 — the composed query: episodes carry only
            // "S01E05 · Title" in the channel name, so the SERIES name from
            // the info page is prepended (parseEpisodeMarker then extracts
            // series + season + episode correctly). Movies pass their name
            // as before; live channels have no meta (old path).
            val meta = vodSubtitleMeta
            val movieName = if (channel?.key?.startsWith("vode:") == true &&
                meta?.cleanName != null && channelName.isNotBlank()
            ) "${meta.cleanName} $channelName" else channelName

            // Step 1: Resolve the subtitle candidates (Cinemeta →
            // OpenSubtitles → Stremio; v1.17.0 returns EVERY usable link
            // so the download can rotate past the OpenSubtitles rate-limit
            // captcha that used to kill "most movies"). v1.18.0 adds the
            // panel's imdb id + the year so the search resolves the RIGHT
            // movie (layer 1/2) and reports which one it picked (layer 3).
            val lookup = SubtitleRepository.fetchSubtitleCandidates(
                movieName, languageCode,
                imdbId = meta?.imdbId,
                year = meta?.year
            )
            val candidates = lookup.candidates
            val errMsg = lookup.error

            if (candidates.isNullOrEmpty()) {
                _ui.update {
                    it.copy(
                        isSubtitleLoading = false,
                        subtitleToast = errMsg ?: "No $languageLabel subtitles found."
                    )
                }
                delay(4000)
                _ui.update { it.copy(subtitleToast = null) }
                return@launch
            }

            // Step 2: Download the best candidate (.gz → srt, transcoded to
            // UTF-8 — the v1.17.0 fix for Arabic CP1256 "؟؟؟؟" rendering).
            val localUri = SubtitleRepository.downloadBestSubtitle(candidates, languageCode, appCtx)
            _ui.update { it.copy(isSubtitleLoading = false) }

            if (localUri != null) {
                injectSubtitle(player, localUri, languageCode, languageLabel)
                // v1.18.0 LAYER 3 — transparency: the toast names the movie
                // the engine actually matched ("Subtitles: Arabic — Dune
                // (2021)") so a wrong match is visible at a glance.
                val resolved = lookup.resolvedTitle
                val toast = if (resolved != null) {
                    "Subtitles: $languageLabel — $resolved"
                } else {
                    "Subtitles: $languageLabel"
                }
                _ui.update {
                    it.copy(
                        activeSubtitleCode = languageCode,
                        activeSubtitleLabel = languageLabel,
                        subtitleToast = toast
                    )
                }
                delay(3000)
                _ui.update { it.copy(subtitleToast = null) }
            } else {
                _ui.update { it.copy(subtitleToast = "Failed to download $languageLabel subtitle.") }
                delay(4000)
                _ui.update { it.copy(subtitleToast = null) }
            }
        }
    }

    /** Tells the player to remove the side-loaded subtitle (reference ClearSubtitle). */
    fun clearSubtitle() {
        val player = activeExoPlayer ?: return
        val currentItem = player.currentMediaItem ?: return
        val streamUri = currentItem.localConfiguration?.uri ?: return
        val builder = MediaItem.Builder().setUri(streamUri)
        currentItem.liveConfiguration?.let { builder.setLiveConfiguration(it) }
        // v1.16.0 — the side-load session is over: reset the override BEFORE
        // the swap so any onPlaying fired by the re-prepare applies the
        // GENERAL setting again, and route through the tee if recording.
        SubtitleActivation.sideLoadedLanguage = null
        swapCurrentItem(player, builder.build())
        SubtitleActivation.apply(player, appCtx)
        _ui.update { it.copy(activeSubtitleCode = null, activeSubtitleLabel = null) }
    }

    /**
     * The reference's InjectSubtitle event handler, same logic: rebuild the
     * current MediaItem with the side-loaded .srt as a subtitle
     * configuration, keep the playback position, prefer the language, and
     * re-prepare.
     */
    private fun injectSubtitle(player: ExoPlayer, uri: Uri, code: String, label: String) {
        val currentItem = player.currentMediaItem ?: return
        val streamUri = currentItem.localConfiguration?.uri ?: return
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setLanguage(code)
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()
        val builder = MediaItem.Builder()
            .setUri(streamUri)
            .setSubtitleConfigurations(listOf(subtitleConfig))
        currentItem.liveConfiguration?.let { builder.setLiveConfiguration(it) }
        // v1.16.0 FIX (part 1) — register the side-load BEFORE the swap so
        // every SubtitleActivation.apply() fired by the re-prepare (the
        // engine's onPlaying listener fires on the BUFFERING→READY blink)
        // keeps text ENABLED with this language on top instead of disabling
        // the text track type (the general toggle defaults to OFF).
        SubtitleActivation.sideLoadedLanguage = code
        // v1.16.0 FIX (part 2) — a disabled track TYPE can never be selected:
        // explicitly re-enable text here too (belt and braces — the general
        // toggle may have disabled it at stream start), then prefer the
        // side-loaded language.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(code)
            .build()
        swapCurrentItem(player, builder.build())
    }

    /** Transient player toast (the reference's subtitleOverlay pattern). */
    private fun subtitleToast(message: String) {
        _ui.update { it.copy(subtitleToast = message) }
        viewModelScope.launch {
            delay(3500)
            _ui.update { it.copy(subtitleToast = null) }
        }
    }

    // ── Recording (v1.17.0 — standalone RecorderEngine, playback untouched) ──

    /**
     * Mirrors SmartPlayer's private USER_AGENT (engine file untouched — the
     * value is duplicated here deliberately so the recorder speaks EXACTLY
     * like the engine's own sources; keep the two in sync).
     */
    private val playerUa = "IPTVPlayer/1.0 (Android; Media3+LibVLC)"

    /** The reference's REC button: toggle recording on/off. */
    fun toggleRecording() {
        if (_ui.value.isRecording) {
            // v1.18.1 — a MANUAL stop is the user rejecting this download:
            // block any auto re-arm until they flip the toggle again.
            autoSaveBlocked = true
            autoSaveActive = false
            stopRecording()
        } else {
            startRecording(auto = false)
        }
    }

    /** v2.1.0 — the upsell dialog's dismiss/activate hand-off. */
    fun consumePremiumGate() = _ui.update { it.copy(premiumGate = null) }

    /**
     * v1.18.1 — the auto-save SETTING, moved from Settings → General into
     * the player's top bar (user directive: pick any movie/series you like
     * and save it, without leaving the player). Flipping ON:
     *   • clears the manual-stop latch (fresh intent → re-arm allowed) and
     *     immediately starts saving the CURRENT movie/episode if one is
     *     playing on the EXO engine (the standalone engine downloads the
     *     WHOLE file even mid-watch, so nothing is lost by starting late);
     *   • stays ON for the rest of the session — the next movie/episode's
     *     first frame arms it too (maybeStartAutoSave).
     * Flipping OFF stops a download that AUTO-SAVE itself started, but
     * never one the user pressed ⬇ on (that download is their own act).
     */
    fun toggleAutoSave() {
        val newState = !VuSettingsPrefs.autoSave(appCtx)
        VuSettingsPrefs.putBool(appCtx, "general_autosave", newState)
        _ui.update { it.copy(autoSaveOn = newState) }
        if (newState) {
            autoSaveBlocked = false
            val ch = channels.getOrNull(currentIndex)
            if (isAutoSaveKey(ch?.key) && !_ui.value.isRecording) {
                if (startRecording(auto = true)) {
                    autoSaveActive = true
                    recordingToast(appCtx.getString(R.string.autosave_started), dismissAfterMs = 5_000L)
                }
            } else {
                recordingToast(
                    appCtx.getString(R.string.autosave_toggle_on),
                    dismissAfterMs = 4_000L
                )
            }
        } else {
            if (_ui.value.isRecording && autoSaveActive) {
                stopRecording()
            }
            recordingToast(
                appCtx.getString(R.string.autosave_toggle_off),
                dismissAfterMs = 3_500L
            )
        }
    }

    /**
     * v1.18.0 — WATCH = DOWNLOAD (v1.18.1: the toggle lives in the player
     * top bar, not Settings → General). With the preference ON, the first
     * frame of a movie/episode arms the SAME standalone RecorderEngine as
     * the ⬇ button: while the user watches, the full VOD download runs in
     * parallel (usually finishing long before the credits) and the file
     * lands in the Saved Videos library named after the movie.
     *   • leaving the player screen STOPS it (onCleared — nothing keeps
     *     downloading in the background);
     *   • a manual stop (⬇/Stop) blocks re-arm until the toggle is flipped
     *     ON again (the user's will is final).
     *   • one download at a time: while one runs, later first frames skip
     *     (the running download survives zaps — DVR semantics).
     * Live channels are never auto-saved (an endless stream would fill
     * the storage; the REC button stays their tool).
     */
    private fun maybeStartAutoSave(channelKey: String?) {
        if (!VuSettingsPrefs.autoSave(appCtx)) return
        if (autoSaveBlocked) return
        if (!isAutoSaveKey(channelKey)) return
        if (_ui.value.isRecording) return
        if (startRecording(auto = true)) {
            autoSaveActive = true
            recordingToast(appCtx.getString(R.string.autosave_started), dismissAfterMs = 5_000L)
        }
    }

    /**
     * v1.17.0 — starts the STANDALONE RecorderEngine. The v1.16.0 approach
     * (teeing the player's own reads) produced structurally corrupt files
     * for HLS streams — the m3u8 manifest TEXT and segments from adaptive
     * quality switches interleaved in one file, which played as a black
     * screen. The recorder now downloads the stream itself (VOD at full
     * speed, live by playlist polling) and remuxes to a real MP4 — with
     * ZERO interference with playback: no media-source swap, no blink.
     * Recording survives zaps, engine fallbacks and stream cuts (each of
     * those only rebuilds the playback source).
     *
     * Iron rule: SmartPlayer.kt untouched; the URL and watched-height are
     * read from the PUBLIC active ExoPlayer.
     */
    private fun startRecording(auto: Boolean): Boolean {
        val player = activeExoPlayer
        if (player == null || _ui.value.engine != Engine.EXO) {
            if (!auto) recordingToast(appCtx.getString(R.string.recording_unavailable))
            return false
        }
        val streamUri = player.currentMediaItem?.localConfiguration?.uri
            ?: run {
                if (!auto) recordingToast(appCtx.getString(R.string.recording_no_url))
                return false
            }
        val url = streamUri.toString()

        // v1.18.1 — offline library guard: a loopback URL IS the local file
        // — "downloading" it would duplicate the save (the recorder would
        // re-pull the whole file from our own server into a second copy).
        // Refuse instead; the Saved Videos library already IS the offline
        // copy.
        if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
            if (!auto) recordingToast(appCtx.getString(R.string.recording_unavailable))
            return false
        }

        if (url.isBlank()) {
            if (!auto) recordingToast(appCtx.getString(R.string.recording_no_url))
            return false
        }
        // v2.1.0 — RECORDING / DOWNLOADING are premium features: the free
        // plan gets ONE use of each (user: "تسجيل القنوات… تحميل فيديوهات
        // الأفلام والمسلسلات… مرة واحدة فقط"). The feature is chosen by
        // what is on screen: a VOD item (vodm:/vode: — the ⬇ button or the
        // auto-save setting) burns the DOWNLOAD trial; a live channel (REC)
        // burns RECORD. Stopping is NEVER gated. The gate sits AFTER the
        // technical guards above so a technical failure never burns the
        // one free use — only a start that would really record does.
        val gateFeature = if (isAutoSaveKey(channels.getOrNull(currentIndex)?.key)) {
            com.superz.iptvplayer.ui.theme.PremiumFeature.DOWNLOAD
        } else {
            com.superz.iptvplayer.ui.theme.PremiumFeature.RECORD
        }
        if (!com.superz.iptvplayer.ui.theme.PremiumAccess.request(gateFeature)) {
            _ui.update { it.copy(premiumGate = gateFeature) }
            return false
        }
        // The variant the user is actually watching (master-playlist
        // variant choice); null → best variant.
        val watchedHeight = player.videoFormat?.height
        // v1.18.0 — the output file carries the movie/channel name
        // ("Oria_<Name>_<stamp>.mp4") so downloads are recognizable.
        val title = channels.getOrNull(currentIndex)?.name
        val started = RecorderEngine.start(appCtx, url, playerUa, watchedHeight, ::onRecordingResult, title)
        if (!started) {
            if (!auto) recordingToast(appCtx.getString(R.string.recording_failed))
            return false
        }
        _ui.update { it.copy(isRecording = true) }
        if (!auto) {
            recordingToast(appCtx.getString(R.string.recording_started, RecorderEngine.currentLabel))
        }
        return true
    }

    /** RecorderEngine result → UI state + the "saved" toast. */
    private fun onRecordingResult(result: RecorderEngine.RecResult) {
        autoSaveActive = false
        _ui.update { it.copy(isRecording = false) }
        val msg = when {
            result.error -> appCtx.getString(
                R.string.recording_failed_with_reason, result.errorMessage ?: ""
            )
            result.bytes > 0 -> appCtx.getString(
                R.string.recording_saved,
                (result.bytes / 1024).toInt(),
                result.path
            )
            else -> appCtx.getString(R.string.recording_empty)
        }
        recordingToast(msg, dismissAfterMs = 6_000L)
    }

    /**
     * Signals the recorder to finalize (remux + publish). The state flips
     * to stopped immediately; the "saved" toast lands via [onRecordingResult]
     * when the file is ready.
     */
    fun stopRecording() {
        if (!_ui.value.isRecording) return
        _ui.update { it.copy(isRecording = false) }
        RecorderEngine.stop()
    }

    /**
     * Swaps the current playback onto the given rebuilt MediaItem,
     * preserving the playback position. (v1.17.0: the recording tee routing
     * is gone — the standalone recorder does not care what source the
     * player uses.)
     */
    private fun swapCurrentItem(player: ExoPlayer, newItem: MediaItem) {
        val pos = player.currentPosition
        player.setMediaItem(newItem, pos)
        player.prepare()
        if (player.isPlaying) player.play()
    }

    /** Transient recording toast (same pill the subtitle feature uses). */
    private fun recordingToast(message: String, dismissAfterMs: Long = 3_500L) {
        _ui.update { it.copy(recordingToast = message) }
        viewModelScope.launch {
            delay(dismissAfterMs)
            _ui.update { it.copy(recordingToast = null) }
        }
    }

    // ═══ v1.18.2 — RESUME WATCHING (the reference PlayerViewModel's
    // binge-watching trio, peripheral edition: SmartPlayer's PUBLIC
    // activeExoForView accessor is the ONLY player touchpoint) ═══

    /**
     * The reference's startPositionSaveLoop: while the channel plays,
     * write the position every 5 seconds. Position reads happen on the
     * MAIN dispatcher (ExoPlayer contract); only the file write hops to
     * IO. Watching past 95% CLEARS the record instead of saving it.
     */
    private fun startPositionSaveLoop() {
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                if (!_ui.value.isPlaying) break
                val ch = channels.getOrNull(currentIndex) ?: break
                val storeKey = positionStoreKey(ch.key) ?: continue
                val player = activeExoPlayer ?: break
                val dur: Long
                val pos: Long
                try {
                    if (!player.isPlaying) continue   // VLC fallback active / stalled player
                    dur = player.duration
                    pos = player.currentPosition
                } catch (_: Throwable) {
                    continue
                }
                if (dur !in PlaybackPositionManager.MIN_VOD_DURATION_MS..PlaybackPositionManager.MAX_VOD_DURATION_MS) {
                    continue
                }
                lastKnownPositionMs = pos
                if (pos >= (dur * PlaybackPositionManager.WATCHED_CLEAR_FRACTION).toLong()) {
                    withContext(Dispatchers.IO) {
                        PlaybackPositionManager.clear(positionFile, storeKey)
                    }
                } else {
                    val record = buildPositionRecord(ch, storeKey, pos, dur) ?: return@launch
                    withContext(Dispatchers.IO) {
                        PlaybackPositionManager.save(positionFile, record)
                    }
                }
            }
        }
    }

    /**
     * The reference's savePositionNow: one immediate flush on PAUSE —
     * the user's exact stop point, not the last 5-second tick. Guarded
     * by the armed key so a zap-storm cannot write the NEW channel's
     * fresh position under the OLD channel's record.
     */
    private fun savePositionNow() {
        val ch = channels.getOrNull(currentIndex) ?: return
        val storeKey = positionStoreKey(ch.key) ?: return
        if (resumeArmedKey != ch.key) return
        // v1.19.12 — the old resume-phase guard is retired with the bake:
        // the position at open IS the stored stop point (baked before
        // prepare), so an early flush rewrites the same value it read —
        // there is no "startup blip" window left to clobber it with.
        val player = activeExoPlayer ?: return
        try {
            val dur = player.duration
            val pos = player.currentPosition
            if (dur !in PlaybackPositionManager.MIN_VOD_DURATION_MS..PlaybackPositionManager.MAX_VOD_DURATION_MS) return
            if (pos <= 0L) return
            lastKnownPositionMs = pos
            if (pos >= (dur * PlaybackPositionManager.WATCHED_CLEAR_FRACTION).toLong()) {
                GlobalScope.launch(Dispatchers.IO) {
                    PlaybackPositionManager.clear(positionFile, storeKey)
                }
            } else {
                val record = buildPositionRecord(ch, storeKey, pos, dur) ?: return
                GlobalScope.launch(Dispatchers.IO) {
                    PlaybackPositionManager.save(positionFile, record)
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ═══ v1.19.0 — BINGE-WATCHING: auto-next-episode ═══

    /**
     * The reference's startBingeWatchLoop, adapted: while an EPISODE
     * plays on the EXO engine, watch the clock. Entering the last 60
     * seconds publishes the next sibling's name + a live countdown
     * (the overlay's data); hitting 0 (or the user's Play Now) zaps
     * forward — the finished episode's record is cleared first so the
     * Continue-Watching shelf instantly rolls to the new episode.
     *
     * v2.2.3 — the loop now also carries the SKIP INTRO signal (the
     * Netflix-style binge pair's first half): every tick publishes
     * whether the playhead sits inside the intro window, INDEPENDENT of
     * the next-episode offer (the last episode of a season still gets
     * the skip button). A null next sibling no longer ends the loop —
     * it only skips the countdown half.
     */
    private fun startBingeWatchLoop() {
        bingeJob?.cancel()
        bingeJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (!_ui.value.isPlaying) break
                val player = activeExoPlayer ?: break
                val ch = channels.getOrNull(currentIndex) ?: break
                if (!BingeNavigation.isEpisodeKey(ch.key)) break
                val next = BingeNavigation.nextEpisode(channels, currentIndex)
                val dur: Long
                val pos: Long
                try {
                    if (!player.isPlaying) continue
                    dur = player.duration
                    pos = player.currentPosition
                } catch (_: Throwable) {
                    continue
                }
                if (dur !in PlaybackPositionManager.MIN_VOD_DURATION_MS..PlaybackPositionManager.MAX_VOD_DURATION_MS) {
                    continue
                }
                // SKIP INTRO — the window signal rides the same 1s tick.
                val showSkip = BingeNavigation.shouldShowSkipIntro(pos, dur)
                if (_ui.value.skipIntroVisible != showSkip) {
                    _ui.update { it.copy(skipIntroVisible = showSkip) }
                }
                if (next == null) {
                    // no sibling — the countdown half is off, but the
                    // skip-intro signal keeps publishing above.
                    if (_ui.value.nextEpisodeName != null) {
                        _ui.update { it.copy(nextEpisodeName = null, nextEpisodeCountdown = -1) }
                    }
                    continue
                }
                val remaining = dur - pos
                if (!BingeNavigation.shouldShowCountdown(remaining, hasNext = true)) {
                    if (_ui.value.nextEpisodeName != null) {
                        _ui.update { it.copy(nextEpisodeName = null, nextEpisodeCountdown = -1) }
                    }
                    continue
                }
                val secs = (remaining / 1000L).toInt()
                if (_ui.value.nextEpisodeName != next.name) {
                    _ui.update { it.copy(nextEpisodeName = next.name) }
                }
                _ui.update { it.copy(nextEpisodeCountdown = secs) }
                if (secs <= 0) {
                    // countdown expired — auto-advance (the reference's
                    // inline switch). The CURRENT record clears because
                    // the episode is finished.
                    playNextEpisode()
                    break
                }
            }
        }
    }

    /**
     * v2.2.3 — SKIP INTRO: the Netflix-style binge pair's first half.
     * A tap on the plan-card pill jumps the playhead straight past the
     * titles (the 90s intro budget, clamped to the content) through the
     * SAME [seekTo] contract the time bar uses — engine guard, duration
     * guard and the resume bookkeeping (lastKnownPositionMs) all apply,
     * so a mid-watch engine restart lands HERE, never back at 0.
     */
    fun skipIntro() {
        val player = activeExoPlayer ?: return
        val dur: Long
        try {
            dur = player.duration
        } catch (_: Throwable) {
            return
        }
        if (dur <= 0L) return
        seekTo(BingeNavigation.skipIntroSeekTargetMs(dur))
        if (_ui.value.skipIntroVisible) {
            _ui.update { it.copy(skipIntroVisible = false) }
        }
    }

    /**
     * The reference's playNextEpisode (the overlay's button AND the
     * countdown expiry): clear the finished episode's resume record,
     * then zap to the next sibling — [playAt] routes through the normal
     * resolve chain, so PORTAL episodes get their fresh create_link.
     */
    fun playNextEpisode() {
        val ch = channels.getOrNull(currentIndex) ?: return
        if (!BingeNavigation.isEpisodeKey(ch.key)) return
        val next = BingeNavigation.nextEpisode(channels, currentIndex) ?: return
        val storeKey = positionStoreKey(ch.key)
        if (storeKey != null) {
            viewModelScope.launch(Dispatchers.IO) {
                PlaybackPositionManager.clear(positionFile, storeKey)
            }
        }
        _ui.update { it.copy(nextEpisodeName = null, nextEpisodeCountdown = -1) }
        bingeJob?.cancel()
        nextChannel()
    }

    /**
     * The stable resume key: streamed movies/episodes keep their channel
     * key (vodm:{streamId} / vode:{episodeId} — stable across sessions);
     * saved files resolve through the bootstrap snapshot to their FILE
     * NAME (the "vodm:local:{i}" index shifts when saves are added or
     * deleted). Null → this channel does not participate (catch-up keys
     * and out-of-range local indexes).
     */
    private fun positionStoreKey(channelKey: String): String? {
        return when {
            channelKey.startsWith("vodm:local:") -> {
                val idx = channelKey.removePrefix("vodm:local:").toIntOrNull() ?: return null
                savedLocal.getOrNull(idx)?.fileName?.let { "local:$it" }
            }
            isAutoSaveKey(channelKey) -> channelKey
            else -> null
        }
    }

    /** Assembles the full shelf record (drives Continue Watching too). */
    private fun buildPositionRecord(
        ch: Channel,
        storeKey: String,
        pos: Long,
        dur: Long
    ): PlaybackPositionManager.PositionRecord? {
        val pl = playlist ?: return null
        val localIdx = ch.key.takeIf { it.startsWith("vodm:local:") }
            ?.removePrefix("vodm:local:")?.toIntOrNull()
        val savedEntry = localIdx?.let { savedLocal.getOrNull(it) }
        val kind = when {
            savedEntry != null -> PlaybackPositionManager.KIND_LOCAL
            ch.key.startsWith("vode:") -> PlaybackPositionManager.KIND_EPISODE
            else -> PlaybackPositionManager.KIND_MOVIE
        }
        val seriesId = if (kind == PlaybackPositionManager.KIND_EPISODE && !localSession) {
            vodFavoriteKey?.takeIf { it.startsWith("sr:") }
                ?.removePrefix("sr:")?.toLongOrNull()
        } else null
        return PlaybackPositionManager.PositionRecord(
            key = storeKey,
            playlistId = pl.id,
            title = ch.name,
            posterUrl = ch.logo,
            kind = kind,
            contentId = ch.key.substringAfter(':').toLongOrNull() ?: 0L,
            seriesId = seriesId,
            path = savedEntry?.path,
            positionMs = pos,
            durationMs = dur,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** v1.19.10 — "استئناف من 12:34 — اضغط زر التشغيل للمتابعة": the
     *  OPEN-PAUSED resume's pill — the video opens PARKED at its stop
     *  point and the user's play press starts it. */
    private fun showResumeToast(positionMs: Long) {
        val msg = appCtx.getString(R.string.resume_paused_hint, formatClock(positionMs))
        _ui.update { it.copy(resumeToast = msg) }
        viewModelScope.launch {
            delay(3_500L)
            _ui.update { it.copy(resumeToast = null) }
        }
    }

    /** 754000 → "12:34"; 3725000 → "1:02:05". */
    private fun formatClock(ms: Long): String {
        val totalSec = ms / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ── SmartPlayer.Listener ────────────────────────────────────

    override fun onChannelChanged(channel: Channel) {
        // v1.17.0 — the side-loaded subtitle belongs to the PREVIOUS
        // stream; a zap starts clean (the fresh MediaItem has none). The
        // standalone recording is NOT stopped: it downloads its own stream,
        // so the user can zap while a recording keeps running (DVR-style) —
        // the REC pill and stop button stay available.
        SubtitleActivation.sideLoadedLanguage = null
        // v1.18.2 — RESUME WATCHING: a zap ends the previous channel's
        // position session (its last 5s tick stands — the reference's
        // onStreamChanged resets positionRestored the same way); the new
        // channel's bake decision happens in playResolved, before the
        // next open even starts.
        savePositionJob?.cancel()
        resumeArmedKey = null
        lastKnownPositionMs = 0L
        // v1.19.0 — BINGE-WATCHING: the offer belonged to the previous
        // episode; the new channel re-arms the loop in onPlaying (the
        // reference's onStreamChanged resets nextEpisodeCountdown = -1).
        bingeJob?.cancel()
        _ui.update {
            it.copy(
                channel = channel, fatal = false, ttffMs = null, buffering = true,
                activeSubtitleCode = null, activeSubtitleLabel = null,
                isVod = isVodKey(channel.key),
                nextEpisodeName = null, nextEpisodeCountdown = -1,
                // v2.2.3 — the skip-intro pill belonged to the previous
                // episode's titles; the new channel re-arms the loop in
                // onPlaying (same reset contract as the countdown trio).
                skipIntroVisible = false
            )
        }
        // v1.7.0 — Settings → General → "Active Subtitle" drives the Exo
        // track selection (persistent on the instance; no-op on VLC).
        SubtitleActivation.apply(activeExoPlayer, appCtx)
    }

    override fun onEngineAttempt(engine: Engine, attemptIndex: Int, totalAttempts: Int) {
        _ui.update { it.copy(engine = engine, attemptIndex = attemptIndex, totalAttempts = totalAttempts) }
        // v1.17.0 — no recording hook: the standalone recorder downloads
        // its own copy of the stream; an engine fallback to VLC (or a
        // re-attempt through Exo) does not affect it.
    }

    override fun onBuffering(buffering: Boolean, percent: Int?) {
        _ui.update { it.copy(buffering = buffering, bufferingPercent = percent) }
    }

    override fun onFirstFrame(engine: Engine, ttffMs: Long, attempt: PlayAttempt) {
        _ui.update { it.copy(ttffMs = ttffMs, buffering = false) }
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        // v1.18.0 — WATCH = DOWNLOAD: the playback is confirmed alive (a
        // real URL in the player, EXO engine) — the natural moment to arm
        // the auto-save for a movie/episode.
        maybeStartAutoSave(ch.key)
        // Remember the winning (engine, variant) — next zap skips failed attempts
        val memory = EngineMemory(pl.id, ch.key, engine.name, attempt.variant)
        memoryMap[ch.key] = memory
        session.memoryMap[ch.key] = memory
        smartPlayer.engineMemory = session.memoryMap
        viewModelScope.launch {
            try { repo.rememberEngine(pl.id, ch.key, engine.name, attempt.variant) } catch (e: Exception) {}
        }
    }

    override fun onPlaying(engine: Engine) {
        // v1.19.12 — publish the player's LIVE state, never an optimistic
        // one: a baked PARKED open (Continue-Watching) renders its first
        // frame with playWhenReady=false, so the icon must wear the play
        // form from the very first frame — and the user's press is the
        // only thing that flips it afterwards. VLC's Playing event IS the
        // live state (true by definition).
        val livePlaying = if (engine == Engine.EXO) activeExoPlayer?.isPlaying == true else true
        _ui.update { it.copy(buffering = false, isPlaying = livePlaying, engine = engine) }
        // v1.7.0 — the ExoPlayer surely exists here: assert the subtitle
        // preference (first play may precede the player's creation).
        if (engine == Engine.EXO) SubtitleActivation.apply(activeExoPlayer, appCtx)
        // v1.19.9 — the time-bar clock starts with the first confirmed play
        // (a parked open keeps the bar alive at its frozen stop point too —
        // the user may scrub from parked exactly like from playing).
        if (engine == Engine.EXO) startTimeBarLoop()
        // v1.19.12 — RESUME WATCHING's remaining lifecycle, armed at the
        // first confirmed playback: (a) arm the key for the save loop /
        // pause-flush / mid-watch restart bake; (b) a chain RETRY of the
        // SAME key re-seeks to the last known position (the retry's
        // resetPosition restart left us at 0 — the recovery must be
        // invisible); (c) start the 5s save loop + the binge loop (they
        // self-idle while parked and re-arm on the play press).
        val ch = channels.getOrNull(currentIndex)
        if (ch != null && isAutoSaveKey(ch.key)) {
            if (resumeArmedKey == ch.key) {
                maybeRestartSeek()
            } else {
                resumeArmedKey = ch.key
            }
        }
        if (ch != null && isVodKey(ch.key)) {
            startPositionSaveLoop()
            startBingeWatchLoop()
        }
        // Post-success optimization: preload/preconnect the neighbours
        val pl = playlist ?: return
        smartPlayer.preloadNext(pl, channels.getOrNull(currentIndex + 1))
        smartPlayer.preconnect(
            pl,
            listOf(
                channels.getOrNull(currentIndex + 1),
                channels.getOrNull(currentIndex - 1),
                channels.getOrNull(currentIndex + 2)
            )
        )
    }

    /**
     * v1.19.12 — the mid-watch chain-retry recovery: the engine chain
     * re-prepared the SAME channel (resetPosition) after a CDN cut, so
     * playback restarted from 0 — re-seek to the last known position so
     * the recovery is invisible. No-op for every other state: a baked
     * parked open sits AT its target (>= the restore minimum), a fresh
     * watch has no last known position, and VLC sessions have no
     * peripheral player to seek.
     */
    private fun maybeRestartSeek() {
        if (lastKnownPositionMs < PlaybackPositionManager.MIN_RESTORE_POSITION_MS) return
        val player = activeExoPlayer ?: return
        try {
            if (player.currentPosition < PlaybackPositionManager.MIN_RESTORE_POSITION_MS) {
                player.seekTo(lastKnownPositionMs)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onPlayPauseChanged(isPlaying: Boolean) {
        _ui.update { it.copy(isPlaying = isPlaying) }
        // v1.18.2 — the reference's pause transition: playing → the save
        // loop keeps running; paused → one immediate flush (their
        // startPositionSaveLoop / savePositionNow pair).
        if (isPlaying) {
            if (isVodKey(_ui.value.channel?.key)) {
                startPositionSaveLoop()
                startBingeWatchLoop()
            }
        } else {
            savePositionNow()
        }
    }

    override fun onFatalError() {
        _ui.update { it.copy(fatal = true, buffering = false, isPlaying = false) }
        // v1.17.0 — no recording hook: the standalone recorder owns its own
        // downloads and finalizes by itself if the source dies.
    }

    /**
     * v1.12.2 — the reference's auto-restart (LivePlayActivity:
     * onPlaybackStateChanged(4) → replay / onPlayerError(1002) → fresh
     * getLiveStreamUrl). The playing stream was cut by the CDN — re-resolve
     * the CURRENT channel (fresh create_link for PORTAL, direct replay for
     * XC/M3U) and keep watching. The SmartPlayer's strike budget guards
     * against a dead-portal loop.
     */
    override fun onStreamEnded() {
        val pl = playlist ?: return
        val ch = channels.getOrNull(currentIndex) ?: return
        Log.d("PlayerViewModel", "stream ended — auto-restart ${ch.name}")
        // v1.17.0 — no recording hook: the auto-restart only rebuilds the
        // PLAYBACK source; the standalone recorder is unaffected.
        _ui.update { it.copy(fatal = false, buffering = true, isPlaying = false) }
        playResolved(pl, ch)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // v1.18.2 — RESUME WATCHING final flush: the user is leaving the
        // player MID-WATCH — capture the exact stop point BEFORE any
        // teardown stops the player. viewModelScope is already cancelled
        // at this point, so the file write rides GlobalScope+IO (the
        // RecorderEngine finalize pattern). The ExoPlayer position read
        // must stay on the main thread — only the write hops to IO.
        try {
            val ch = channels.getOrNull(currentIndex)
            // v1.19.12 — the resume-phase guard is retired: with the bake,
            // the position at open IS the stored stop point, so this flush
            // (which itself requires the player to be ROLLING) can never
            // write a startup blip over a real stop point.
            if (ch != null && resumeArmedKey == ch.key) {
                val storeKey = positionStoreKey(ch.key)
                val player = activeExoPlayer
                if (storeKey != null && player != null && player.isPlaying) {
                    val dur = player.duration
                    val pos = player.currentPosition
                    if (dur in PlaybackPositionManager.MIN_VOD_DURATION_MS..PlaybackPositionManager.MAX_VOD_DURATION_MS && pos > 0L) {
                        if (pos >= (dur * PlaybackPositionManager.WATCHED_CLEAR_FRACTION).toLong()) {
                            GlobalScope.launch(Dispatchers.IO) {
                                PlaybackPositionManager.clear(positionFile, storeKey)
                            }
                        } else {
                            val record = buildPositionRecord(ch, storeKey, pos, dur)
                            if (record != null) {
                                GlobalScope.launch(Dispatchers.IO) {
                                    PlaybackPositionManager.save(positionFile, record)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // v1.17.0 — leaving the player screen scopes the recording to this
        // session: signal stop now. The recorder itself runs on
        // GlobalScope+IO, so the finalize (remux + MediaStore publish)
        // completes even after this ViewModel is gone; only the result
        // callback (the toast) is dropped with the UI.
        // v1.19.9 — OWNERSHIP: only a PLAYER-armed download is stopped
        // here. One armed by a VOD info page (the download button next to
        // Play) is a deliberate background download — it keeps running
        // with its own progress UI when the player opens and closes over
        // it, exactly like the reference's download managers.
        if (RecorderEngine.playerOwned) {
            RecorderEngine.onResult = null
            if (_ui.value.isRecording) {
                _ui.update { it.copy(isRecording = false) }
                RecorderEngine.stop()
            }
        }
        // v1.18.1 — offline library session: the loopback media server was
        // acquired for THIS playback — shut it down with the screen (the
        // last session out closes the socket).
        if (localSession) {
            localSession = false
            LocalMediaServer.release()
        }
        session.dispatcher.deactivate(this)
        // v2.3.1 — the multi-screen registry must never hold a dead
        // screen's releaser.
        unregisterEnginePark?.invoke()
        unregisterEnginePark = null
        if (!inherited) {
            // We were the session's only driver (direct entry / process-death
            // restore) — tear it down completely.
            PlayerSessionManager.release()
        }
        // Inherited: the channel view below still owns the session — the
        // stream KEEPS PLAYING in the mini player when the user returns.
        super.onCleared()
    }
}

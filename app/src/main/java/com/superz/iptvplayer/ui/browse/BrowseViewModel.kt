package com.superz.iptvplayer.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.CategoryWithCount
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.db.SeriesShow
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import com.superz.iptvplayer.data.stalker.StalkerVodRefs
import com.superz.iptvplayer.data.subtitles.SubtitleRepository
import com.superz.iptvplayer.data.xtream.VodUrlProbe
import com.superz.iptvplayer.player.VodPlayRegistry
import com.superz.iptvplayer.ui.components.ParentalControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The three content sections of the browse screen (reference top pills). */
enum class BrowseTab { LIVE, MOVIES, SERIES }

/**
 * Browse state: active playlist → category rail + filtered content list.
 * v1.4.0: three tabs (LIVE / MOVIES / SERIES) share the SAME screen, the
 * same category rail, the same search box and the same favorites filter —
 * each backed by its own reactive Room Flow, so switching is instant and
 * content updates live after a sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    data class UiState(
        val playlist: Playlist? = null,
        val tab: BrowseTab = BrowseTab.LIVE,
        val categories: List<CategoryWithCount> = emptyList(),
        val selectedCategoryId: String? = null,
        val query: String = "",
        val favoritesOnly: Boolean = false,
        val channels: List<Channel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val seriesList: List<SeriesShow> = emptyList(),
        val favoriteKeys: Set<String> = emptySet(),
        val vodFavoriteKeys: Set<String> = emptySet(),
        val syncing: Boolean = false,
        /** v1.11.0 — a stalker (PORTAL) VOD/Series page fetch is running:
         *  the grid shows its end-of-list spinner while rows land in Room. */
        val vodLoadingMore: Boolean = false,
        /** v1.11.0 — PORTAL: the server total_items of the current section
         *  (the reference's "Category (64273)" header truth). */
        val vodTotal: Int? = null,
        /** v1.19.7 — HUGE-LIST PAGING: the FIRST page of the current
         *  (tab, category, query, favorites) key is still loading — the
         *  empty grid shows a spinner instead of the empty message. */
        val contentLoading: Boolean = false,
        /** v1.19.7 — HUGE-LIST PAGING: the accumulator holds every row the
         *  filters can produce (and the panel has no more server pages) —
         *  the end-of-grid spinner disappears. */
        val contentEndReached: Boolean = false,
        /** v1.19.7 — RESUME DIRECT PLAY: a Continue-Watching card's URL is
         *  being resolved (probing candidates) — the screen shows a small
         *  centered spinner overlay and ignores further resume taps. */
        val resuming: Boolean = false
    )

    private val appCtx = getApplication<IPTVApp>()
    private val repo = PlaylistRepository.get(appCtx)
    private val db = appCtx.database
    private val channelDao = db.channelDao()
    private val playlistDao = db.playlistDao()
    private val favoriteDao = db.favoriteDao()
    private val vodDao = db.vodDao()

    private val _tab = MutableStateFlow(
        when (savedStateHandle.get<String>("tab")?.uppercase()) {
            "MOVIES" -> BrowseTab.MOVIES
            "SERIES" -> BrowseTab.SERIES
            else -> BrowseTab.LIVE
        }
    )
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    private val _favoritesOnly = MutableStateFlow(false)
    private val _syncing = MutableStateFlow(false)

    // ── v1.11.0 — Stalker (PORTAL) live paging (ItemActivity's infinite
    // scroll): the server pages 14 rows per request, so the grid's Room
    // flow is CONTINUED page-by-page as the user scrolls. Page state lives
    // in the repository (it knows the sync's initial pages too). ──
    private val _vodLoadingMore = MutableStateFlow(false)
    // v1.19.7 — stalkerLoadJob folded into pagingJob (the generic
    // loadMore() now owns both the DB pages and the server page fetches).
    private var stalkerSearchJob: kotlinx.coroutines.Job? = null

    val activePlaylist: StateFlow<Playlist?> = playlistDao.activePlaylistFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val queryAndFav = combine(_query, _favoritesOnly) { q, f -> q to f }

    // ── Category rail (per tab) ──
    private val categoriesFlow = combine(playlistDao.activePlaylistFlow(), _tab) { pl, tab ->
        pl to tab
    }.flatMapLatest { (pl, tab) ->
        if (pl == null) flowOf(emptyList())
        else when (tab) {
            BrowseTab.LIVE -> channelDao.categoriesWithCountFlow(pl.id)
            BrowseTab.MOVIES -> vodDao.vodCategoriesWithCountFlow(pl.id)
            BrowseTab.SERIES -> vodDao.seriesCategoriesWithCountFlow(pl.id)
        }
    }

    // ══ v1.19.7 — HUGE-LIST PAGING: the paged content accumulator ═══════
    //
    // The old design streamed the FULL filtered list into UiState via
    // reactive Flows (moviesFlow/seriesFlow/channelsFlow). On a huge
    // account (100k+ movies / channels / series) that meant:
    //   • one Room query materializing EVERY row (30–50MB of objects);
    //   • main-thread delivery of a 100k-element list → jank/ANR;
    //   • full re-emission on EVERY Room write (favorites, sync batches)
    //     → the emission storms that made the cards flicker or never land;
    //   • flatMapLatest restarts cancelling an in-flight 100k query each
    //     time the playlist row was touched.
    //
    // The new design loads ONE-SHOT pages (suspend, LIMIT/OFFSET, backed
    // by the (playlistId, num) index added in schema v7) into a plain
    // StateFlow accumulator: the first page (240 items) renders in
    // milliseconds, loadMore() appends as the grid approaches its end,
    // one-shot queries are immune to Room invalidation storms, and memory
    // grows only as far as the user actually scrolls. PORTAL (stalker)
    // server paging chains transparently: when the DB pages are exhausted
    // but the panel total is higher, the next server page is fetched into
    // Room and DB paging continues.
    private val _content = MutableStateFlow(Content(emptyList(), emptyList(), emptyList()))
    private val _contentLoading = MutableStateFlow(false)
    private val _contentEnd = MutableStateFlow(false)
    // v1.19.7 — RESUME DIRECT PLAY flag (declared with the other paging
    // state so the combined flags flow below can reference it).
    private val _resuming = MutableStateFlow(false)
    private var pagingJob: Job? = null
    private var currentKey: ContentKey? = null

    private val contentKeyFlow = combine(
        playlistDao.activePlaylistFlow(),
        combine(_selectedCategory, queryAndFav, _tab) { cat, qf, tab ->
            Triple(cat, qf.first, qf.second) to tab
        }
    ) { pl, filters ->
        val (cat, q, favOnly) = filters.first
        ContentKey(pl, filters.second, cat, q, favOnly)
    }

    private data class ContentKey(
        val playlist: Playlist?,
        val tab: BrowseTab,
        val catId: String?,
        val query: String,
        val favoritesOnly: Boolean
    )

    private data class Content(
        val channels: List<Channel>,
        val movies: List<Movie>,
        val series: List<SeriesShow>
    )

    init {
        // Filter/tab/playlist change → reset the accumulator + first page.
        viewModelScope.launch {
            contentKeyFlow.collect { key -> resetContent(key) }
        }
        // A completed re-sync refreshed the tables → reload the current
        // key's first pages so the grid reflects the new data (one-shot
        // pages do NOT auto-refresh on Room invalidation — by design).
        viewModelScope.launch {
            var wasSyncing = false
            _syncing.collect { syncing ->
                if (wasSyncing && !syncing) currentKey?.let { resetContent(it) }
                wasSyncing = syncing
            }
        }
    }

    /** 240 items ≈ 48 grid rows — fills several 4K screens instantly. */
    private val pageSize: Int get() = PAGE_SIZE

    private fun resetContent(key: ContentKey) {
        pagingJob?.cancel()
        currentKey = key
        _content.value = Content(emptyList(), emptyList(), emptyList())
        _contentLoading.value = true
        _contentEnd.value = false
        if (key.playlist == null) {
            _contentLoading.value = false
            _contentEnd.value = true
            return
        }
        pagingJob = viewModelScope.launch {
            try {
                loadPageInternal(key, 0)
            } finally {
                _contentLoading.value = false
            }
        }
    }

    /**
     * The end-of-grid trigger (ALL playlist types now): appends the next
     * DB page; when the DB is exhausted and this is a PORTAL VOD/Series
     * section whose panel total is higher, fetches the next server page
     * into Room and continues. Safe to call from every scroll tick — the
     * active-job and end-of-list guards make it a no-op when idle.
     */
    fun loadMore() {
        val key = currentKey ?: return
        val pl = key.playlist ?: return
        if (pagingJob?.isActive == true) return
        if (!_contentEnd.value) {
            pagingJob = viewModelScope.launch {
                loadPageInternal(key, contentSize(key.tab))
            }
            return
        }
        // DB exhausted → stalker server paging (the reference's scroll).
        // Non-blank queries flow through the same path: setQuery's 400ms
        // debounce calls loadMore() and the server search pages continue
        // as the grid scrolls (exactly the old maybeLoadMore behavior).
        if (pl.type != "PORTAL" || key.tab == BrowseTab.LIVE || key.favoritesOnly) return
        if (_vodLoadingMore.value) return
        _vodLoadingMore.value = true
        pagingJob = viewModelScope.launch {
            try {
                val fetched = repo.stalkerVodLoadMore(
                    pl,
                    key.tab == BrowseTab.SERIES,
                    key.catId ?: "",
                    key.query
                )
                if (fetched) {
                    _contentEnd.value = false
                    loadPageInternal(key, contentSize(key.tab))
                }
            } catch (_: Exception) {
                // silent — the grid keeps what it has
            } finally {
                _vodLoadingMore.value = false
            }
        }
    }

    private fun contentSize(tab: BrowseTab): Int = when (tab) {
        BrowseTab.LIVE -> _content.value.channels.size
        BrowseTab.MOVIES -> _content.value.movies.size
        BrowseTab.SERIES -> _content.value.series.size
    }

    /** Compatibility shims for the screen's existing call sites. */
    fun maybeLoadMore() = loadMore()
    fun ensureFirstPage() = loadMore()

    private suspend fun loadPageInternal(key: ContentKey, offset: Int) {
        val pl = key.playlist ?: return
        when (key.tab) {
            BrowseTab.LIVE -> {
                // v1.12.5 — parental: the "All" grid EXCLUDES exactly ONE xxx
                // category (the reference's getLiveChannelsByCategory(all)):
                // notEqualTo(category_id, Constants.xxx_category_id) — the
                // LAST name match); the xxx categories themselves stay
                // reachable through the PIN-gated category.
                val xxxId = if (key.catId == null && !key.favoritesOnly) {
                    try {
                        ParentalControl.xxxExcludedId(
                            channelDao.xxxCandidateCategories(pl.id),
                            portal = pl.type == "PORTAL"
                        )
                    } catch (_: Throwable) {
                        null
                    }
                } else null
                val page: List<Channel>
                val total: Int
                when {
                    key.favoritesOnly -> {
                        page = channelDao.favoriteChannelsPage(pl.id, key.catId, key.query, pageSize, offset)
                        total = channelDao.favoriteChannelsCount(pl.id, key.catId, key.query)
                    }
                    key.catId == null && xxxId != null -> {
                        page = channelDao.channelsPageExcluding(pl.id, null, key.query, false, listOf(xxxId), pageSize, offset)
                        total = channelDao.channelsCountExcluding(pl.id, null, key.query, false, listOf(xxxId))
                    }
                    else -> {
                        page = channelDao.channelsPage(pl.id, key.catId, key.query, pageSize, offset)
                        total = channelDao.channelsCount(pl.id, key.catId, key.query)
                    }
                }
                appendPage(key, page, total, offset) { c -> c.copy(channels = c.channels + page) }
            }
            BrowseTab.MOVIES -> {
                val page: List<Movie>
                val total: Int
                if (key.favoritesOnly) {
                    page = vodDao.favoriteMoviesPage(pl.id, key.catId, key.query, pageSize, offset)
                    total = vodDao.favoriteMoviesCount(pl.id, key.catId, key.query)
                } else {
                    page = vodDao.moviesPage(pl.id, key.catId, key.query, pageSize, offset)
                    total = vodDao.moviesCount(pl.id, key.catId, key.query)
                }
                appendPage(key, page, total, offset) { c -> c.copy(movies = c.movies + page) }
            }
            BrowseTab.SERIES -> {
                val page: List<SeriesShow>
                val total: Int
                if (key.favoritesOnly) {
                    page = vodDao.favoriteSeriesPage(pl.id, key.catId, key.query, pageSize, offset)
                    total = vodDao.favoriteSeriesCount(pl.id, key.catId, key.query)
                } else {
                    page = vodDao.seriesPage(pl.id, key.catId, key.query, pageSize, offset)
                    total = vodDao.seriesCount(pl.id, key.catId, key.query)
                }
                appendPage(key, page, total, offset) { c -> c.copy(series = c.series + page) }
            }
        }
    }

    /**
     * Folds a loaded page into the accumulator and updates the end flag.
     * End = the count says we have everything, or the page came back short
     * (a deleted row can make the count drift by one — the short-page rule
     * is the safety net that never strands the grid before the true end).
     */
    private fun appendPage(
        key: ContentKey,
        page: List<*>,
        total: Int,
        offset: Int,
        fold: (Content) -> Content
    ) {
        _content.value = fold(_content.value)
        val size = contentSize(key.tab)
        _contentEnd.value = page.size < pageSize || size >= total
        // PORTAL: the DB may only hold the pages fetched so far — the panel
        // total (not the DB count) is the real finish line; leave the end
        // open so loadMore() chains the next server page.
        val pl = key.playlist
        if (pl?.type == "PORTAL" && key.tab != BrowseTab.LIVE && !key.favoritesOnly) {
            val serverTotal = when (key.tab) {
                BrowseTab.SERIES -> pl.stalkerSeriesTotal
                else -> pl.stalkerVodTotal
            }
            if (serverTotal != null && size < serverTotal && page.isNotEmpty()) {
                _contentEnd.value = false
            }
        }
        if (offset == 0 && page.isEmpty()) {
            // first page came back empty: nothing in the DB for this key —
            // for PORTAL VOD/Series loadMore() will fetch the server's page 1
            // (the screen's empty-state trigger + scroll guard both call it).
            _contentEnd.value = pl?.type == "PORTAL" && key.tab != BrowseTab.LIVE && !key.favoritesOnly
        }
    }

    companion object {
        /** One grid page: 240 posters ≈ 48 rows × 5 columns. */
        const val PAGE_SIZE = 240
    }

    private val favKeysFlow = playlistDao.activePlaylistFlow().flatMapLatest { pl ->
        if (pl == null) flowOf(emptyList()) else favoriteDao.favoriteKeysFlow(pl.id)
    }

    private val vodFavKeysFlow = playlistDao.activePlaylistFlow().flatMapLatest { pl ->
        if (pl == null) flowOf(emptyList()) else vodDao.vodFavoriteKeysFlow(pl.id)
    }

    private data class Filters(
        val selectedCategoryId: String?,
        val query: String,
        val favoritesOnly: Boolean,
        val syncing: Boolean,
        val tab: BrowseTab
    )

    private val filtersFlow = combine(
        _selectedCategory, _query, _favoritesOnly, _syncing, _tab
    ) { c, q, f, s, t -> Filters(c, q, f, s, t) }

    /** v1.11.0 — (page-fetch running, playlist) for the stalker extras. */
    private val vodExtrasFlow = combine(
        _vodLoadingMore, playlistDao.activePlaylistFlow()
    ) { lm, pl -> lm to pl }

    // v1.19.7 — paging flags (first-page spinner + resume-play overlay).
    private val pagingFlagsFlow = combine(_contentLoading, _contentEnd, _resuming) { cl, ce, r ->
        Triple(cl, ce, r)
    }

    val ui: StateFlow<UiState> = combine(
        playlistDao.activePlaylistFlow(),
        categoriesFlow,
        _content,
        favKeysFlow,
        combine(vodFavKeysFlow, filtersFlow, vodExtrasFlow, pagingFlagsFlow) { v, f, extra, flags ->
            Triple(v, f, extra) to flags
        }
    ) { pl, cats, content, favs, extrasAndFlags ->
        val (vodFavs, filters, extra) = extrasAndFlags.first
        val (contentLoading, contentEndReached, resuming) = extrasAndFlags.second
        val (loadingMore, plRow) = extra
        UiState(
            playlist = pl,
            tab = filters.tab,
            categories = cats,
            selectedCategoryId = filters.selectedCategoryId,
            query = filters.query,
            favoritesOnly = filters.favoritesOnly,
            channels = content.channels,
            movies = content.movies,
            seriesList = content.series,
            favoriteKeys = favs.toSet(),
            vodFavoriteKeys = vodFavs.toSet(),
            syncing = filters.syncing,
            vodLoadingMore = loadingMore,
            // the hub-truth totals from the playlist row (server-reported)
            vodTotal = when {
                plRow?.type != "PORTAL" || filters.tab == BrowseTab.LIVE -> null
                filters.tab == BrowseTab.SERIES -> plRow?.stalkerSeriesTotal
                else -> plRow?.stalkerVodTotal
            },
            contentLoading = contentLoading,
            contentEndReached = contentEndReached,
            resuming = resuming
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ══ v1.19.7 — RESUME DIRECT PLAY ═══════════════════════════════════
    // A Continue-Watching card used to route to the movie/series INFO
    // page, forcing a second tap on Play (“يذهب إلى صفحة معلومات الفيلم
    // وهو من المفروض يذهب مباشرة للمشغل ليواصل المشاهدة”). Now the card
    // resolves the SAME playback request the info page's own Play button
    // builds (identical probing, identical VodPlayRegistry hand-off, zero
    // engine changes) and jumps straight into the fullscreen player —
    // whose existing auto-resume logic seeks to the stored position under
    // the same "vodm:{id}" / "vode:{id}" key. Info pages remain reachable
    // from the normal poster grid for full metadata browsing.

    private var resumeJob: Job? = null

    /**
     * Resolves a Continue-Watching record into a ready playback request
     * and hands the channel key to [onReady] (the screen navigates to the
     * fullscreen player route). LOCAL saves are handled by the screen
     * itself (offline player route); call this for MOVIE / EPISODE kinds.
     * On failure the toast carries a human message — the info page route
     * is still available from the poster grid.
     */
    fun prepareResume(
        record: PlaybackPositionManager.PositionRecord,
        onReady: (playlistId: Long, channelKey: String) -> Unit
    ) {
        if (_resuming.value) return
        _resuming.value = true
        resumeJob = viewModelScope.launch {
            try {
                val pl = playlistDao.playlistById(record.playlistId)
                if (pl == null) {
                    _toast.value = appCtx.getString(R.string.resume_play_failed)
                    return@launch
                }
                val key = when (record.kind) {
                    PlaybackPositionManager.KIND_EPISODE ->
                        prepareEpisodeResume(pl, record)
                    else -> prepareMovieResume(pl, record)
                }
                if (key != null) onReady(pl.id, key)
                else _toast.value = appCtx.getString(R.string.resume_play_failed)
            } catch (e: Exception) {
                _toast.value = appCtx.getString(R.string.resume_play_failed)
            } finally {
                _resuming.value = false
            }
        }
    }

    /** Mirrors MovieInfoViewModel.preparePlayback's registration 1:1. */
    private suspend fun prepareMovieResume(
        pl: Playlist,
        record: PlaybackPositionManager.PositionRecord
    ): String? {
        val streamId = record.contentId
        val movie = vodDao.movieByStreamId(pl.id, streamId) ?: return null
        val info = try { repo.movieInfo(pl, streamId) } catch (_: Exception) { null }
        val channelKey = "vodm:$streamId"
        if (pl.type == "PORTAL") {
            StalkerVodRefs.put(
                "${pl.id}:$channelKey",
                StalkerVodRefs.Ref(
                    cmd = info?.stalkerCmd,
                    seriesNum = null,
                    htmlItem = info?.stalkerItem
                )
            )
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = listOf(
                        Channel(
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
                    ),
                    startIndex = 0,
                    favoriteKey = movie.key,
                    favoriteType = "MOVIE",
                    subtitleMeta = VodPlayRegistry.SubtitleMeta(
                        imdbId = info?.imdbId,
                        year = SubtitleRepository.extractYear(
                            info?.releaseDate ?: movie.name
                        ),
                        cleanName = info?.name ?: movie.name
                    )
                )
            )
            return channelKey
        }
        // Xtream: probe the container candidates exactly like the info page.
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
        val url = when {
            candidates.isEmpty() ->
                repo.movieUrl(pl, streamId, info?.containerExtension ?: movie.containerExtension)
            candidates.size == 1 -> candidates.first()
            else -> VodUrlProbe.best(appCtx.okHttp, candidates)
        } ?: return null
        VodPlayRegistry.put(
            "${pl.id}:$channelKey",
            VodPlayRegistry.Request(
                playlist = pl,
                channels = listOf(
                    Channel(
                        playlistId = pl.id,
                        key = channelKey,
                        num = movie.num,
                        name = info?.name ?: movie.name,
                        logo = info?.poster ?: movie.poster,
                        categoryId = movie.categoryId,
                        streamId = streamId,
                        directUrl = url
                    )
                ),
                startIndex = 0,
                favoriteKey = movie.key,
                favoriteType = "MOVIE",
                subtitleMeta = VodPlayRegistry.SubtitleMeta(
                    imdbId = info?.imdbId,
                    year = SubtitleRepository.extractYear(
                        info?.releaseDate ?: movie.name
                    ),
                    cleanName = info?.name ?: movie.name
                )
            )
        )
        return channelKey
    }

    /** Mirrors SeriesInfoViewModel.prepareEpisodePlayback's registration
     *  1:1 — the episode's whole season becomes the zap list. */
    private suspend fun prepareEpisodeResume(
        pl: Playlist,
        record: PlaybackPositionManager.PositionRecord
    ): String? {
        val seriesId = record.seriesId ?: return null
        val show = vodDao.seriesBySeriesId(pl.id, seriesId) ?: return null
        val info = try { repo.seriesInfo(pl, seriesId) } catch (_: Exception) { null }
        val episode = info?.seasons
            ?.flatMap { it.episodes }
            ?.firstOrNull { it.id == record.contentId }
            ?: return null
        val season = info.seasons.firstOrNull { it.seasonNumber == episode.season }
        val episodes = season?.episodes ?: listOf(episode)
        val startIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val channelKey = "vode:${episode.id}"

        if (pl.type == "PORTAL") {
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
            VodPlayRegistry.put(
                "${pl.id}:$channelKey",
                VodPlayRegistry.Request(
                    playlist = pl,
                    channels = episodes.map { ep ->
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
                    },
                    startIndex = startIndex,
                    favoriteKey = show.key,
                    favoriteType = "SERIES",
                    subtitleMeta = VodPlayRegistry.SubtitleMeta(
                        imdbId = info.imdbId,
                        year = SubtitleRepository.extractYear(show.name),
                        cleanName = show.name
                    )
                )
            )
            return channelKey
        }
        // Xtream: candidates = direct_source first, then the extensions.
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
        } ?: return null
        VodPlayRegistry.put(
            "${pl.id}:$channelKey",
            VodPlayRegistry.Request(
                playlist = pl,
                channels = episodes.map { ep ->
                    val epUrl = when {
                        ep.id == episode.id -> url
                        ep.directSource != null -> ep.directSource
                        else -> repo.episodeUrl(pl, ep.id, ep.containerExtension)
                    } ?: return null
                    Channel(
                        playlistId = pl.id,
                        key = "vode:${ep.id}",
                        num = ep.episodeNumber,
                        name = "S${ep.season}E${ep.episodeNumber} · ${ep.title}",
                        logo = ep.thumbnail ?: show.poster,
                        categoryId = show.categoryId,
                        directUrl = epUrl
                    )
                },
                startIndex = startIndex,
                favoriteKey = show.key,
                favoriteType = "SERIES",
                subtitleMeta = VodPlayRegistry.SubtitleMeta(
                    imdbId = info?.imdbId,
                    year = SubtitleRepository.extractYear(show.name),
                    cleanName = show.name
                )
            )
        )
        return channelKey
    }

    /** v1.12.0 — parental: the gated XXX category NAME (dialog shown when
     *  non-null). Separate from [ui] (a combined flow — can't be updated
     *  in place). */
    private val _pinGate = MutableStateFlow<String?>(null)
    val pinGate: StateFlow<String?> = _pinGate.asStateFlow()

    // ── v1.18.2 — RESUME WATCHING shelf (Continue Watching row) ──
    // Loaded once at screen entry (a fresh ViewModel per navigation —
    // returning from the player re-reads the shelf): newest-first,
    // ACTIVE-playlist records only (another account's movie cannot be
    // re-opened from this session), LOCAL records whose file still
    // exists (a deleted save is dropped, not fatal).
    private val _continueWatching = MutableStateFlow<List<PlaybackPositionManager.PositionRecord>>(emptyList())
    val continueWatching: StateFlow<List<PlaybackPositionManager.PositionRecord>> =
        _continueWatching.asStateFlow()

    init {
        viewModelScope.launch {
            val activeId = playlistDao.activePlaylistFlow().first()?.id ?: return@launch
            val file = PlaybackPositionManager.storeFile(appCtx)
            val shelf = withContext(Dispatchers.IO) {
                PlaybackPositionManager.recent(file)
            }
            _continueWatching.value = shelf.filter { rec ->
                rec.playlistId == activeId &&
                    (rec.kind != PlaybackPositionManager.KIND_LOCAL ||
                        rec.path?.let { File(it).exists() } == true)
            }
        }
    }

    /** v1.12.0 — parental: XXX categories unlocked this session. */
    private val unlockedCategories = mutableSetOf<String>()

    /** v1.12.0 — parental: the category id awaiting the PIN gate's verdict. */
    private var pendingCategoryId: String? = null

    // ── Actions ──

    fun selectTab(tab: BrowseTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        // Category ids are per-section — reset the rail selection.
        _selectedCategory.value = null
    }

    /**
     * v1.12.0 — category select WITH the parental gate (the reference's
     * isXXX + showParentalControlDlg): an XXX-named category (live, VOD or
     * Series — ItemActivity gates all three) asks for the PIN first.
     */
    fun selectCategory(categoryId: String?) {
        if (categoryId != null && categoryId !in unlockedCategories) {
            val target = ui.value.categories.firstOrNull { it.categoryId == categoryId }
            if (target != null &&
                com.superz.iptvplayer.ui.components.ParentalControl.isXxxName(target.name)
            ) {
                pendingCategoryId = categoryId
                _pinGate.value = target.name
                return
            }
        }
        _selectedCategory.value = categoryId
    }

    /** PIN verified → unlock and open the pending category. */
    fun onPinVerified() {
        val target = pendingCategoryId
        pendingCategoryId = null
        _pinGate.value = null
        if (target != null) {
            unlockedCategories.add(target)
            _selectedCategory.value = target
        }
    }

    /** PIN dialog dismissed → drop the pending selection. */
    fun onPinDismissed() {
        pendingCategoryId = null
        _pinGate.value = null
    }

    fun setQuery(query: String) {
        _query.value = query
        // v1.11.0 — PORTAL: server-side search (the reference's `search`
        // query param). Debounced: a settled term starts a FRESH page
        // sequence (a new repository tracker key) while the Room LIKE flow
        // filters the cached rows meanwhile.
        stalkerSearchJob?.cancel()
        val pl = activePlaylist.value ?: return
        if (pl.type != "PORTAL" || _tab.value == BrowseTab.LIVE) return
        if (query.trim().isEmpty()) return
        stalkerSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            loadMore()
        }
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    /** Re-sync the active playlist from the server (channels + VOD). */
    fun refresh() {
        val pl = ui.value.playlist ?: return
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                repo.syncPlaylist(pl)
                repo.syncVod(pl)
            } catch (e: Exception) {
                _toast.value = e.message
            } finally {
                _syncing.value = false
            }
        }
    }

    fun toggleFavorite(channel: Channel) {
        val pl = ui.value.playlist ?: return
        viewModelScope.launch {
            repo.toggleFavorite(pl.id, channel.key)
        }
    }

    fun toggleMovieFavorite(movie: Movie) {
        val pl = ui.value.playlist ?: return
        viewModelScope.launch {
            repo.toggleVodFavorite(pl.id, movie.key, "MOVIE")
        }
    }

    fun toggleSeriesFavorite(show: SeriesShow) {
        val pl = ui.value.playlist ?: return
        viewModelScope.launch {
            repo.toggleVodFavorite(pl.id, show.key, "SERIES")
        }
    }

    // ── v1.11.0 — Stalker (PORTAL) live paging (ItemActivity's scroll) ──
    // v1.19.7 — the old dedicated maybeLoadMore()/ensureFirstPage() were
    // FOLDED into the generic loadMore() above (its DB-exhausted branch
    // chains the stalker server pages); the search debounce calls it too.

    private fun stalkerKey(): String =
        "${ui.value.tab}|${ui.value.selectedCategoryId ?: ""}|${ui.value.query.trim().lowercase()}"

    fun consumeToast() { _toast.value = null }
}

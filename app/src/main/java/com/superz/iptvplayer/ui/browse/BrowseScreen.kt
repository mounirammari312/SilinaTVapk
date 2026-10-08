package com.superz.iptvplayer.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.db.SeriesShow
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.components.EmptyState
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.VuSdp
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground
import com.superz.iptvplayer.ui.theme.vuGoldFace
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuGoldenGlow
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * Content browser — v1.4.7: an EXACT design copy of the reference app's
 * channel-grid main interface (activity_item.xml, decompiled):
 *
 *   • Root: horizontal gradient #1a1251 → #3d1f39 (drawable/background)
 *   • Top bar: back chevron + "Back" (12sdp) | app logo 35×35sdp |
 *     LIVE TV / Movies / Series pills (btn_item_type: purple gradient when
 *     selected, #33707070 otherwise, radius 13sdp, icon 10sdp + text 9sdp,
 *     padding 4sdp/10sdp) | search 20×20 + favorites 20×20
 *   • Left panel (0 → 30% of the width, main_left_bg = #33707070 with TOP
 *     corners rounded 15sdp): search bar (25sdp tall, radius 13sdp) +
 *     category rows (name 9sdp + count 9sdp — v1.4.8: every section shows
 *     its OWN count, item_category.xml txt_count; selected = purple
 *     gradient radius 10sdp — item_category_selected_bg)
 *   • Right: 5-column grid (GridLayoutManager(this, 5)); channel cell =
 *     full-bleed rounded 5sdp logo + bottom black-gradient name strip
 *     (23sdp tall, 7sdp text, 2 lines) + favorite chip 20×20sdp
 *     (round_black_65, radius 3sdp); movie/series cell = same shape with
 *     24sdp name strip (item_movie_grid.xml)
 *
 * Only the DESIGN is copied — the engine, ViewModels and data flow are the
 * project's own (v1.4.0–v1.4.6 contracts unchanged).
 */
@Composable
fun BrowseScreen(
    onOpenPlayer: (playlistId: Long, channelKey: String, categoryId: String?, query: String, favoritesOnly: Boolean) -> Unit,
    onOpenMovie: (playlistId: Long, streamId: Long) -> Unit,
    onOpenSeries: (playlistId: Long, seriesId: Long) -> Unit,
    onBack: () -> Unit,
    // v1.18.2 — the Saved Videos action pill (top bar, next to the
    // Series pill — user request: “زر يفتح مكتبة المحفوظات الموجودة
    // فعليا… بجانب زر المسلسلات بايقونة احترافية واسم واضح”).
    onOpenSaved: () -> Unit = {},
    // v1.18.2 — a Continue-Watching card of a SAVED (offline) file →
    // straight into the offline player route.
    onPlaySaved: (path: String) -> Unit = {},
    // v2.0.5 — the PREMIUM pill of the channels grid (user request:
    // "يجب ايضا ان يكون زر بريميوم في شاشة شبكة القنوات") — same route
    // as every other premium entry in the app.
    onOpenPremium: () -> Unit = {},
    // v1.19.7 — RESUME DIRECT PLAY: a Continue-Watching card jumps
    // straight into the fullscreen player (the SAME route/registry the
    // info pages use) instead of the info page — playback auto-resumes
    // at the stored position.
    onPlayVod: (playlistId: Long, channelKey: String) -> Unit = { _, _ -> },
    // v2.3.0 — MULTI-SCREEN: the LIVE tab's grid pill → the setup (no
    // seed — the last session's picks restore automatically).
    onOpenMultiScreen: (playlistId: Long, channelKey: String) -> Unit = { _, _ -> },
    viewModel: BrowseViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // v1.18.2 — the resume shelf (Continue-Watching row, first row of
    // the grid when no category/search/favorites filter narrows it).
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val s = rememberVuSdp()
    val context = LocalContext.current
    var searchOpen by remember { mutableStateOf(false) }
    // v2.0.5 — the premium gate for the channels-grid pill (panel switch).
    val premiumLive = OriaBranding.premiumEnabled
    // v2.1.0 — a PREMIUM member pressing the grid's premium pill reads his
    // subscription card (account + dates + enabled features) instead of
    // the upgrade page (user: "باقي ازرار premium في الصفحة الرئيسية و
    // صفحة شبكة بطاقات القنوات عند النقر عليها تعرض معلومات حسابه و
    // الميزات المفعلة").
    var showPremiumInfo by remember { mutableStateOf(false) }

    // v1.12.0 — parental: the gated XXX category (name shown in the dialog).
    val pinGate by viewModel.pinGate.collectAsStateWithLifecycle()

    val toastMessage = viewModel.toast.collectAsStateWithLifecycle().value
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    fun openChannel(channelKey: String) {
        ui.playlist?.let { pl ->
            onOpenPlayer(pl.id, channelKey, ui.selectedCategoryId, ui.query, ui.favoritesOnly)
        }
    }

    val contentCount = when (ui.tab) {
        BrowseTab.LIVE -> ui.channels.size
        BrowseTab.MOVIES -> ui.movies.size
        BrowseTab.SERIES -> ui.seriesList.size
    }

    // v1.11.0 — Stalker (PORTAL) live paging: the grid state drives the
    // end-of-list trigger (the reference's ItemActivity scroll listener);
    // v1.19.7 — now ALL playlist types: the ViewModel appends the next DB
    // page (240 rows) as the grid approaches its end; for PORTAL VOD/Series
    // the DB-exhausted branch fetches the next server page into Room.
    val gridState = rememberLazyGridState()
    val stalkerPaging =
        ui.playlist?.type == "PORTAL" && ui.tab != BrowseTab.LIVE && !ui.favoritesOnly
    androidx.compose.runtime.LaunchedEffect(ui.playlist?.id, ui.tab, ui.selectedCategoryId, ui.query) {
        // Category browsing with an empty cache fetches page 1 immediately;
        // non-blank queries go through the debounced server-search path.
        // v1.19.7 — snapshotFlow reading `ui` INSIDE the lambda (observable
        // State reads, not frozen outer vals): fires when the EMPTY first
        // DB page actually lands, so switching from a populated category to
        // an unbrowsed one still triggers the server page-1 fetch.
        androidx.compose.runtime.snapshotFlow {
            val count = when (ui.tab) {
                BrowseTab.LIVE -> ui.channels.size
                BrowseTab.MOVIES -> ui.movies.size
                BrowseTab.SERIES -> ui.seriesList.size
            }
            count == 0 &&
                ui.playlist?.type == "PORTAL" &&
                ui.tab != BrowseTab.LIVE &&
                !ui.favoritesOnly &&
                ui.query.isBlank() &&
                ui.contentEndReached
        }.collect { emptyAndEnded ->
            if (emptyAndEnded) viewModel.loadMore()
        }
    }
    androidx.compose.runtime.LaunchedEffect(ui.playlist?.id, ui.tab, ui.selectedCategoryId, ui.query, ui.favoritesOnly) {
        // v1.19.7 — the generic end-of-grid paging trigger (DB pages for
        // every playlist type; stalker server pages chain after them).
        androidx.compose.runtime.snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, count) ->
            if (count > 0 && last >= count - 15) viewModel.loadMore()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()
    ) {
        val leftPanelWidth = maxWidth * 0.30f   // vertical_line guideline = 0.30
        // v1.18.2 — the content grid's cell width (grid box = the 70% zone
        // minus its 5sdp/10sdp start/end paddings; 5 columns with 4×5sdp
        // gaps). Hoisted to THIS level: the scope's maxWidth only resolves
        // in the direct content lambda, and the Continue-Watching cards
        // reuse the grid's own rhythm.
        val gridCellWidth = (maxWidth - leftPanelWidth - s.d(15) - s.d(20)) / 5

        Column(Modifier.fillMaxSize()) {

            // ══════════ TOP BAR (activity_item: ly_back + PremiumLL + ly_buttons
            // + et_item_search + btn_search + btn_menu) ══════════
            // PremiumLL (crown logo + PREMIUM) defines the bar height; ly_buttons
            // hangs from the 30% vertical_line guideline; everything else is
            // vertically centered on PremiumLL (or bottom-aligned to it).
            Box(Modifier.fillMaxWidth()) {
                var premiumH by remember { mutableStateOf(Dp.Unspecified) }
                val premiumHeight = premiumH.takeUnless { it == Dp.Unspecified } ?: s.d(47)

                // ly_back + PremiumLL — start-anchored, 15sdp top margin
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = s.d(15))              // PremiumLL marginTop 15sdp
                ) {
                    VuBackButton(onClick = onBack)
                    VuPremiumLogo(
                        modifier = Modifier.padding(start = s.d(10)),
                        onHeight = { premiumH = it }
                    )
                }

                // ly_buttons — LIVE TV | Movies | Series, anchored at the 30%
                // guideline (start_toStartOf vertical_line), centered on PremiumLL.
                // v2.4.1 — HIDDEN while the top search field is open: the
                // end-anchored cluster grows leftward by the field's width
                // the moment it opens, and the two absolutely-anchored
                // boxes used to fight for the same pixels (the field report:
                // “opening the search deforms every button in the row”).
                // Search is a modal moment — the tabs return on close.
                if (!searchOpen) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = s.d(15), start = leftPanelWidth)
                        .height(premiumHeight)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(s.d(9)) // marginStart 9sdp between pills
                    ) {
                        VuTabPill(
                            label = stringResource(R.string.hub_live_tv),
                            iconRes = R.drawable.vu_ic_live_tv,
                            selected = ui.tab == BrowseTab.LIVE,
                            onClick = { viewModel.selectTab(BrowseTab.LIVE) }
                        )
                        VuTabPill(
                            label = stringResource(R.string.hub_movies),
                            iconRes = R.drawable.vu_ic_movies,
                            selected = ui.tab == BrowseTab.MOVIES,
                            onClick = { viewModel.selectTab(BrowseTab.MOVIES) }
                        )
                        VuTabPill(
                            label = stringResource(R.string.hub_series),
                            iconRes = R.drawable.vu_ic_series,
                            selected = ui.tab == BrowseTab.SERIES,
                            onClick = { viewModel.selectTab(BrowseTab.SERIES) }
                        )
                        // v1.18.2 — the SAVED VIDEOS action pill (user
                        // request): the same pill idiom as the three
                        // tabs but an ACTION, not a tab — it never holds
                        // the selected gradient.
                        // v1.19.0 — the user's feedback: “المحفوظات” was
                        // renamed to “مشاهدة بلا أنترنت” and the floppy
                        // became the offline-watch glyph (download arrow
                        // + tray + play badge) — the pill now SAYS what
                        // it does: watch without internet.
                        VuTabPill(
                            label = stringResource(R.string.hub_saved_videos),
                            iconRes = R.drawable.vu_ic_offline,
                            selected = false,
                            onClick = onOpenSaved
                        )
                        // (v2.3.0 once placed the MULTI-SCREEN pill here —
                        // v2.4.1 REMOVED it: the row had grown past its
                        // designed width and the wide labeled pill stacked
                        // itself over the end-anchored search/star cluster
                        // (the field report). Multi-screen now lives as a
                        // COMPACT icon action in that end cluster —
                        // see VuTopMultiScreenButton below.)
                        // v2.0.5 — the PREMIUM pill closes the tab row: the
                        // crown + a breathing golden halo make it the one
                        // distinctly golden control in the bar (user request:
                        // "زر بريميوم في شاشة شبكة القنوات").
                        if (premiumLive) {
                            VuPremiumTabPill(
                                label = stringResource(R.string.premium_short),
                                onClick = {
                                    if (com.superz.iptvplayer.ui.theme.PremiumAccess.active) {
                                        showPremiumInfo = true
                                    } else {
                                        onOpenPremium()
                                    }
                                }
                            )
                        }
                    }
                }
                }   // v2.4.1 — (the tab row hides while searchOpen)

                // et_item_search (100×20sdp, hidden until searchOpen) + btn_search
                // (image_search 20×20sdp, scale 0.9→1.0 focused) + favorites star in
                // btn_menu's slot — end-anchored, centered on PremiumLL.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = s.d(15))
                        .height(premiumHeight)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchOpen) {
                            VuTopSearchField(
                                value = ui.query,
                                onValueChange = { viewModel.setQuery(it) },
                                modifier = Modifier
                                    .padding(end = s.d(5))      // marginEnd 5sdp
                                    .size(width = s.d(100), height = s.d(20))
                            )
                        }
                        // btn_search — the reference's image_search PNG, 20×20sdp
                        VuTopSearchButton(
                            onClick = { searchOpen = !searchOpen },
                            modifier = Modifier.padding(end = s.d(10))   // marginEnd 10sdp
                        )
                        // v2.4.1 — MULTI-SCREEN as a compact icon action on
                        // the LIVE tab: the 2×2 grid glyph in the same 20sdp
                        // button idiom as search — an ACTION, not a tab, so
                        // it left the tab row (which it had overcrowded onto
                        // this cluster) and joined it here, one D-pad stop
                        // before the favorites star.
                        if (ui.tab == BrowseTab.LIVE) {
                            VuTopMultiScreenButton(
                                onClick = { ui.playlist?.let { pl -> onOpenMultiScreen(pl.id, "") } },
                                modifier = Modifier.padding(end = s.d(10))
                            )
                        }
                        // Favorites star — occupies btn_menu's slot at the far end
                        // (marginEnd 15sdp); sized like the reference's 20sdp buttons.
                        IconButton(
                            onClick = { viewModel.toggleFavoritesOnly() },
                            modifier = Modifier
                                .padding(end = s.d(15))
                                .size(s.d(20))
                        ) {
                            Icon(
                                if (ui.favoritesOnly) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(R.string.favorites),
                                tint = VuPalette.White,
                                modifier = Modifier.size(s.d(20))
                            )
                        }
                    }
                }
            }

            // ══════════ BODY: left panel (30%) + grid ══════════
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = s.d(5))          // left_lay marginTop 5sdp
            ) {
                // ── left_lay: #33707070, TOP corners rounded 15sdp, marginStart 10sdp ──
                Column(
                    modifier = Modifier
                        .padding(start = s.d(10))
                        .width(leftPanelWidth - s.d(10))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = s.d(15), topEnd = s.d(15)))
                        .background(VuPalette.PanelOverlay)
                ) {
                    // ly_search: 25sdp tall, #33707070 radius 13sdp, margin 10sdp
                    VuSearchField(
                        value = ui.query,
                        onValueChange = { viewModel.setQuery(it) },
                        hint = stringResource(R.string.vu_search_categories),
                        modifier = Modifier
                            .padding(s.d(10))
                            .fillMaxWidth()
                            .height(s.d(25))
                    )
                    // recyclerCategory: margins 7sdp horizontal, 5sdp top.
                    // v1.4.8 (user request): EVERY section row carries its own
                    // channel count (item_category.xml txt_count — the reference
                    // binds per-category counts, not one aggregate number).
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = s.d(7), end = s.d(7), top = s.d(5), bottom = s.d(10)
                        )
                    ) {
                        item {
                            VuCategoryRow(
                                name = stringResource(R.string.all_channels),
                                count = ui.categories.sumOf { it.count },
                                selected = ui.selectedCategoryId == null,
                                onClick = { viewModel.selectCategory(null) }
                            )
                        }
                        listItems(ui.categories, key = { it.categoryId }) { cat ->
                            VuCategoryRow(
                                name = cat.name,
                                count = cat.count,
                                selected = ui.selectedCategoryId == cat.categoryId,
                                onClick = {
                                    viewModel.selectCategory(
                                        if (ui.selectedCategoryId == cat.categoryId) null else cat.categoryId
                                    )
                                }
                            )
                        }
                    }
                }

                // ── recycler_items / recycler_vod / recycler_series: 5-column grid,
                //    marginStart 5sdp, marginEnd 10sdp ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = s.d(5), end = s.d(10))
                ) {
                    // v1.18.2 — the Continue-Watching row (user request:
                    // “بطاقات المحتوى تظهر في أول صف في شبكة القنوات”).
                    // Movies + saved files ride the MOVIES tab; episodes
                    // ride the SERIES tab; LIVE keeps a clean channel
                    // grid (resume is a VOD concept). Only the unfiltered
                    // view shows it — a category/search/favorites filter
                    // means the user is hunting for something specific.
                    val resumeRecords = continueWatching.filter { rec ->
                        when (ui.tab) {
                            BrowseTab.MOVIES ->
                                rec.kind == PlaybackPositionManager.KIND_MOVIE ||
                                    rec.kind == PlaybackPositionManager.KIND_LOCAL
                            BrowseTab.SERIES ->
                                rec.kind == PlaybackPositionManager.KIND_EPISODE
                            else -> false
                        }
                    }
                    val showResumeRow = ui.tab != BrowseTab.LIVE &&
                        ui.selectedCategoryId == null &&
                        !ui.favoritesOnly &&
                        ui.query.isBlank() &&
                        resumeRecords.isNotEmpty()
                    // the grid's own cell width, so the resume cards sit in
                    // the same rhythm as the poster cells below them
                    val resumeCellWidth = gridCellWidth
                    when {
                        ui.playlist == null -> EmptyState(stringResource(R.string.no_playlists))
                        // v1.11.0 — PORTAL playlists gained the VOD/Series
                        // sections (the stalker engine); M3U stays live-only.
                        ui.playlist?.type != "XTREAM" && ui.playlist?.type != "PORTAL" && ui.tab != BrowseTab.LIVE ->
                            EmptyState(stringResource(R.string.vod_needs_xtream))
                        contentCount == 0 -> {
                            // v1.19.7 — HUGE-LIST PAGING: the FIRST page of
                            // any playlist type may still be loading (one-shot
                            // query) — spinner, not the empty message. PORTAL
                            // keeps its unbrowsed-category fetch spinner too.
                            if (ui.contentLoading || (stalkerPaging && (ui.vodLoadingMore || ui.vodTotal == null))) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = VuPalette.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(s.d(30))
                                    )
                                }
                            } else {
                                val msg = when {
                                    ui.favoritesOnly -> stringResource(
                                        if (ui.tab == BrowseTab.LIVE) R.string.no_favorites else R.string.no_vod_favorites
                                    )
                                    ui.query.isNotBlank() -> stringResource(R.string.no_results, ui.query)
                                    else -> stringResource(
                                        when (ui.tab) {
                                            BrowseTab.LIVE -> if ((ui.playlist?.channelCount ?: 0) == 0) R.string.no_channels_sync else R.string.no_channels
                                            BrowseTab.MOVIES -> R.string.no_movies
                                            BrowseTab.SERIES -> R.string.no_series
                                        }
                                    )
                                }
                                EmptyState(msg)
                            }
                        }
                        else -> {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(5),
                                horizontalArrangement = Arrangement.spacedBy(s.d(5)),
                                verticalArrangement = Arrangement.spacedBy(s.d(5)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = s.d(5), bottom = s.d(10)
                                )
                            ) {
                                if (showResumeRow) {
                                    item(span = { GridItemSpan(5) }, key = "continue_watching") {
                                        VuContinueWatchingRow(
                                            records = resumeRecords,
                                            cellWidth = resumeCellWidth,
                                            onResume = { rec ->
                                                viewModel.prepareResume(rec) { pid, key ->
                                                    onPlayVod(pid, key)
                                                }
                                            },
                                            onPlaySaved = onPlaySaved
                                        )
                                    }
                                }
                                when (ui.tab) {
                                    BrowseTab.LIVE -> gridItems(ui.channels, key = { it.id }) { channel ->
                                        VuChannelGridCell(
                                            channel = channel,
                                            isFavorite = ui.favoriteKeys.contains(channel.key),
                                            onClick = { openChannel(channel.key) },
                                            onLongClick = { viewModel.toggleFavorite(channel) }
                                        )
                                    }
                                    BrowseTab.MOVIES -> gridItems(ui.movies, key = { it.key }) { movie ->
                                        VuPosterGridCell(
                                            name = movie.name,
                                            posterUrl = movie.poster,
                                            isFavorite = ui.vodFavoriteKeys.contains(movie.key),
                                            onClick = {
                                                ui.playlist?.let { onOpenMovie(it.id, movie.streamId ?: 0L) }
                                            },
                                            onHeartClick = { viewModel.toggleMovieFavorite(movie) }
                                        )
                                    }
                                    BrowseTab.SERIES -> gridItems(ui.seriesList, key = { it.key }) { show ->
                                        VuPosterGridCell(
                                            name = show.name,
                                            posterUrl = show.poster,
                                            isFavorite = ui.vodFavoriteKeys.contains(show.key),
                                            onClick = {
                                                // v1.4.1 fix — rows synced by v1.4.0 have seriesId
                                                // NULL; the key "sr:{id}" always carries the real
                                                // Xtream id, so derive it instead of passing 0.
                                                val sid = show.seriesId
                                                    ?: show.key.removePrefix("sr:").toLongOrNull()
                                                    ?: 0L
                                                ui.playlist?.let { onOpenSeries(it.id, sid) }
                                            },
                                            onHeartClick = { viewModel.toggleSeriesFavorite(show) }
                                        )
                                    }
                                }
                                // v1.11.0 — PORTAL: the end-of-list page spinner
                                // (the reference's progressBar while a page loads).
                                // v1.19.7 — extended: also the generic DB-page
                                // spinner while a huge list's next 240 rows load.
                                if (ui.vodLoadingMore || (!ui.contentLoading && !ui.contentEndReached)) {
                                    item(span = { GridItemSpan(5) }, key = "stalker_more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = s.d(8)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = VuPalette.White,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(s.d(24))
                                            )
                                        }
                                    }
                                }
                            }
                            // progress_bar — centered 30sdp (reference)
                            if (ui.syncing) {
                                CircularProgressIndicator(
                                    color = VuPalette.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(s.d(30))
                                )
                            }
                        }
                    }
                }
            }
        }

        // ═══ v1.12.0 — PARENTAL PIN GATE (the reference's
        // showParentalControlDlg on an XXX category click) ═══
        pinGate?.let { catName ->
            com.superz.iptvplayer.ui.components.VuPinGateDialog(
                categoryName = catName,
                onResult = { ok ->
                    if (ok) viewModel.onPinVerified() else viewModel.onPinDismissed()
                }
            )
        }

        // ═══ v2.1.0 — the premium member's own subscription card ═══
        if (showPremiumInfo) {
            com.superz.iptvplayer.ui.components.PremiumInfoDialog(
                onDismiss = { showPremiumInfo = false }
            )
        }

        // ═══ v1.19.7 — RESUME DIRECT PLAY: while a Continue-Watching card's
        // playback URL is being resolved (candidate probing can take a
        // moment on slow panels), a small centered spinner overlay keeps
        // the tap acknowledged and blocks double-launches.
        // v1.19.13 — the overlay CONSUMES taps: a background-only Box is
        // not hit-testable in Compose, so taps fell through to the cards
        // behind it — the "double-launch block" it promised never worked. ═══
        if (ui.resuming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume while resolving */ },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = VuPalette.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(s.d(30))
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Reference widgets (activity_item.xml + item_*.xml, sizes verbatim)
// ═══════════════════════════════════════════════════════════════════

/**
 * btn_item_type pill: radius 13sdp; default → #33707070. The reference's
 * REAL vector artwork (ic_live_tv_icon / ic_movies_small / ic_series) at
 * 10×10sdp + text 9sdp, padding 4sdp vertical / 10sdp horizontal; the pill
 * renders at scale 0.9 and grows to 1.0 when focused
 * (ItemActivity.onFocusChange).
 *
 * v1.19.2 — the user's request: the purple selected/focused gradient made
 * way for the CODE-BUTTON golden identity — warm bronze glass + GOLD
 * icon/text + the animated golden edge (the orbiting light beads) at rest;
 * selected OR focused = the SOLID GOLD face with dark-bronze content.
 * v1.19.3 — that face became vuGoldFace() (TRUE gold, never yellow).
 *
 * v2.2.0 — THE PLAN-CARD CONTRACT (user directive: “نفس تصميم بطاقات الخطط
 * حرفياً — نفس الإطار الذهبي المتحرك، نفس الخلفية الزجاجية الداكنة، نفس
 * التأثير الذهبي عند التركيز، ونص ذهبي”): the LIVE TV / Movies / Series
 * pills now wear the plan cards' exact skin — the animated golden ring
 * always on (0.8sdp rest → 1.6sdp lit), the DARK GLASS resting face
 * (PanelOverlay 60%), the warm-bronze GLASS lit face (NOT solid gold) and
 * GOLD content on both faces. The SELECTED tab holds the lit face so the
 * active section stays obvious; the reference's focus scale survives as
 * the D-pad affordance.
 */
@Composable
private fun VuTabPill(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val contentTint = VuGold.Text          // gold on BOTH faces — the plan-card text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                val sc = if (focused) 1f else 0.9f     // scale 0.9 → 1.0 focused
                scaleX = sc
                scaleY = sc
            }
            .vuPlanCardStyle(cornerRadius = s.d(13), focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = s.d(10), vertical = s.d(4))
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            colorFilter = ColorFilter.tint(contentTint),
            modifier = Modifier.size(s.d(10))
        )
        Spacer(Modifier.width(s.d(5)))
        Text(
            label,
            color = contentTint,
            fontSize = s.t(9),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * v2.0.5 — THE PREMIUM ACTION PILL of the channels grid (user request:
 * "يجب ايضا ان يكون زر بريميوم في شاشة شبكة القنوات").
 *
 * The exact pill idiom of the tab row it closes (LIVE TV | Movies | Series
 * | Saved | PREMIUM) — same 13sdp radius, same 0.9→1.0 focus scale, same
 * glass/gold face flip — with two deliberate differences that mark it as
 * THE upgrade entry, the way professional subscription apps badge theirs:
 *
 *   • the PROFESSIONAL CROWN ([VuCrownIcon] — full metallic gold on the
 *     glass face, bronze relief on the focused solid-gold face; NEVER
 *     through Material `Icon()`, whose default tint flattened it to the
 *     black silhouette the user rejected);
 *   • a breathing golden halo behind the face ([vuGoldenGlow]) — the one
 *     softly-lit control in the row, distinct from the quiet nav pills
 *     without shouting over them.
 *
 * An ACTION, not a tab: it never holds a selected state.
 */
@Composable
private fun VuPremiumTabPill(
    label: String,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val contentTint = if (focused) VuGold.OnGold else VuGold.Text
    Box {
        // breathing halo — the premium identity, behind the face
        Box(
            modifier = Modifier
                .matchParentSize()
                .vuGoldenGlow(maxAlpha = if (focused) 0.50f else 0.34f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .graphicsLayer {
                    val sc = if (focused) 1f else 0.9f   // scale 0.9 → 1.0 focused
                    scaleX = sc
                    scaleY = sc
                }
                .vuGoldenBorder(cornerRadius = s.d(13), strokeWidth = s.d(1.2f), focused = focused)
                .clip(RoundedCornerShape(s.d(13)))
                .then(
                    if (focused) Modifier.vuGoldFace()
                    else Modifier.background(Color(0x66301F08))   // warm bronze glass
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable { onClick() }
                .padding(horizontal = s.d(10), vertical = s.d(4))
        ) {
            VuCrownIcon(
                onGold = focused,
                modifier = Modifier.size(s.d(11))
            )
            Spacer(Modifier.width(s.d(5)))
            Text(
                label,
                color = contentTint,
                fontSize = s.t(9),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * btn_search — the reference's image_search PNG, 20×20sdp, white tint,
 * scale 0.9 → 1.0 when focused (ItemActivity.onFocusChange), transparent
 * background (the reference gives it no visible focus plate).
 */
@Composable
private fun VuTopSearchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Image(
        painter = painterResource(R.drawable.vu_image_search),
        contentDescription = stringResource(R.string.search_hint),
        colorFilter = ColorFilter.tint(VuPalette.White),
        modifier = modifier
            .size(s.d(20))
            .graphicsLayer {
                val sc = if (focused) 1f else 0.9f
                scaleX = sc
                scaleY = sc
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    )
}

/**
 * v2.4.1 — the MULTI-SCREEN entry, RELOCATED (the top-bar fix). v2.3.0
 * had placed a WIDE LABELED pill inside the tab row; the row — LIVE TV |
 * Movies | Series | Saved | PREMIUM — had no room left, and the pill
 * stacked itself over the end-anchored search/star cluster (the field
 * report: "يتكدس فوق زر البحث و زر النجمة"). The action now carries the
 * SAME compact 20×20sdp icon idiom as btn_search (white tint, scale
 * 0.9 → 1.0 focused), one D-pad stop before the favorites star — an
 * action among actions, invisible footprint.
 */
@Composable
private fun VuTopMultiScreenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Image(
        painter = painterResource(R.drawable.vu_ic_multiscreen),
        contentDescription = stringResource(R.string.multiscreen_entry_cd),
        colorFilter = ColorFilter.tint(VuPalette.White),
        modifier = modifier
            .size(s.d(20))
            .graphicsLayer {
                val sc = if (focused) 1f else 0.9f
                scaleX = sc
                scaleY = sc
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    )
}

/**
 * et_item_search — 100×20sdp (btn_search's height), btn_item_type background
 * (#33707070 r13sdp, gradient when focused), text 10sdp, padding 5sdp.
 * v1.19.2 — the focus plate joins the golden identity: warm bronze glass
 * + the animated golden edge instead of the purple gradient.
 */
@Composable
private fun VuTopSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .vuGoldenBorder(cornerRadius = s.d(13), strokeWidth = s.d(1.2f), focused = focused)
            .clip(RoundedCornerShape(s.d(13)))
            .then(
                if (focused) Modifier.background(Color(0x8A38270B))   // lit bronze glass
                else Modifier.background(Color(0x66301F08))            // warm bronze glass
            )
            .onFocusChanged { focused = it.isFocused }
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = VuPalette.White,
                fontSize = s.t(10)
            ),
            cursorBrush = Brush.verticalGradient(listOf(VuPalette.White, VuPalette.White)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = s.d(5))
        )
    }
}

/**
 * ly_search — round_search_bg: #33707070 radius 13sdp,
 * 25sdp tall, search icon 10sdp (marginStart 10sdp), 8sdp text.
 */
@Composable
internal fun VuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(s.d(13)))
            .background(VuPalette.PanelOverlay)
    ) {
        Image(
            painter = painterResource(R.drawable.vu_image_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(VuPalette.White),
            modifier = Modifier
                .padding(start = s.d(10))
                .size(s.d(10))
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = VuPalette.White,
                fontSize = s.t(8)
            ),
            cursorBrush = Brush.verticalGradient(listOf(VuPalette.White, VuPalette.White)),
            modifier = Modifier
                .weight(1f)
                .padding(start = s.d(5), end = s.d(10))
        ) { inner ->
            if (value.isEmpty()) {
                Text(
                    hint,
                    color = VuPalette.White,
                    fontSize = s.t(8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            inner()
        }
    }
}

/**
 * item_category.xml: padding 5sdp, name 9sdp (single line, weight) +
 * count 9sdp at the end. Selected → item_category_selected_bg (purple
 * gradient, radius 10sdp); default → transparent.
 *
 * v1.19.3 — the user's request (“جعل أقسام القائمة الجانبية (الفئات)
 * ذهبية”): the sidebar's sections carry the golden identity.
 *
 * v2.2.0 — THE PLAN-CARD CONTRACT, list-row edition (user directive: the
 * sidebar's section-switching rows wear the plan cards' design): every
 * row is a little plan card — DARK GLASS resting face + a static gold
 * hairline + GOLD name/count; the focused or selected row lights up with
 * the warm-bronze glass face + the ANIMATED golden ring (the full plan-card
 * treatment). The animated border composes ONLY on lit rows — a 30-row
 * category sidebar never spins 30 infinite transitions on a weak TV box.
 */
@Composable
private fun VuCategoryRow(
    name: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    // gold content on BOTH faces — the plan-card text contract
    val nameColor = VuGold.Text
    val countColor = VuGold.Text.copy(alpha = 0.62f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .vuPlanCardRowStyle(cornerRadius = s.d(10), focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(s.d(5))
    ) {
        Text(
            name,
            color = nameColor,
            fontSize = s.t(9),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        count?.let {
            Spacer(Modifier.width(s.d(5)))
            Text(
                "$it",
                color = countColor,
                fontSize = s.t(9),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * item_channel_grid.xml: padding 2sdp, marginTop 5sdp — RoundedImageView
 * full-bleed (radius 5sdp) + black_bottom_gradient name strip (23sdp tall,
 * bottom corners 5sdp, text 7sdp, 2 lines) + fav chip 20×20sdp
 * (round_black_65 radius 3sdp, padding 5sdp, margin 5sdp).
 */
@Composable
private fun VuChannelGridCell(
    channel: Channel,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val s = rememberVuSdp()
    Box(
        modifier = Modifier
            .padding(top = s.d(5))
            .padding(s.d(2))
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(s.d(5)))
            .clickable { onClick() }   // reference: tap = play; the fav chip toggles favorite
    ) {
        ChannelLogo(
            logoUrl = channel.logo,
            name = channel.name,
            sizeDp = 0,
            cornerDp = 0,
            modifier = Modifier.fillMaxSize()
        )
        // black_bottom_gradient: black → #a6000000 → transparent (90° = bottom-up)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(s.d(23))
                .background(
                    Brush.verticalGradient(
                        0.0f to VuPalette.Black65,
                        1.0f to Color.Transparent
                    )
                )
        )
        // txt_name — 7sdp white, bottom|center_horizontal, 2 lines, padding 3sdp
        Text(
            channel.name,
            color = VuPalette.White,
            fontSize = s.t(7),
            lineHeight = s.t(8),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = s.d(3), vertical = s.d(3))
        )
        // image_fav — 20×20sdp round_black_65 (radius 3sdp), margin 5sdp
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(s.d(5))
                .size(s.d(20))
                .clip(RoundedCornerShape(s.d(3)))
                .background(VuPalette.Black65)
                .clickable { onLongClick() }
        ) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isFavorite) VuPalette.Cyan else VuPalette.White,
                modifier = Modifier.size(s.d(10))
            )
        }
    }
}

/**
 * item_movie_grid.xml — identical to the channel cell but the name strip is
 * 24sdp tall (portrait poster 2:3 fills the cell).
 */
@Composable
private fun VuPosterGridCell(
    name: String,
    posterUrl: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onHeartClick: () -> Unit
) {
    val s = rememberVuSdp()
    Box(
        modifier = Modifier
            .padding(top = s.d(5))
            .padding(s.d(2))
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(s.d(5)))
            .clickable { onClick() }
    ) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VuPalette.PanelOverlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = VuPalette.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(s.d(22))
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(s.d(24))
                .background(
                    Brush.verticalGradient(
                        0.0f to VuPalette.Black65,
                        1.0f to Color.Transparent
                    )
                )
        )
        Text(
            name,
            color = VuPalette.White,
            fontSize = s.t(7),
            lineHeight = s.t(8),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = s.d(3), vertical = s.d(3))
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(s.d(5))
                .size(s.d(20))
                .clip(RoundedCornerShape(s.d(3)))
                .background(VuPalette.Black65)
                .clickable { onHeartClick() }
        ) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isFavorite) VuPalette.Cyan else VuPalette.White,
                modifier = Modifier.size(s.d(10))
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// v1.18.2 — Continue Watching (first row of the content grid)
// ═══════════════════════════════════════════════════════════════════

/**
 * v1.19.0 — the Continue-Watching shelf, REDESIGNED per the user's
 * feedback (“بطاقات استئناف المشاهدة تظهر بطاقات عادية… يجب أن تكون
 * مميزة… حجمها يكون 16:9”): landscape 16:9 banners, TWO grid cells
 * wide, that CANNOT be mistaken for the portrait poster grid below —
 * a cyan “استئناف المشاهدة” chip on every card, a glass play
 * affordance with a cyan focus ring, the title + “تبقى mm:ss” strip,
 * and a thick cyan progress bar on the bottom edge. LOCAL (offline)
 * saves keep the amber “بلا أنترنت” badge.
 */
@Composable
private fun VuContinueWatchingRow(
    records: List<PlaybackPositionManager.PositionRecord>,
    cellWidth: Dp,
    onResume: (PlaybackPositionManager.PositionRecord) -> Unit,
    onPlaySaved: (path: String) -> Unit
) {
    val s = rememberVuSdp()
    // 16:9 banner = two grid cells + the grid's own 5sdp gap
    val cardWidth = cellWidth * 2 + s.d(5)
    Column(modifier = Modifier.fillMaxWidth()) {
        // shelf label — the reference's row-label idiom (9-10sdp, bold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = s.d(3), top = s.d(2), bottom = s.d(6))
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = VuPalette.Cyan,
                modifier = Modifier.size(s.d(12))
            )
            Spacer(Modifier.width(s.d(5)))
            Text(
                stringResource(R.string.continue_watching),
                color = VuPalette.White,
                fontSize = s.t(10),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(s.d(8)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = s.d(2), end = s.d(2), bottom = s.d(3)
            )
        ) {
            listItems(records, key = { it.key }) { rec ->
                VuContinueCard(
                    record = rec,
                    cardWidth = cardWidth,
                    onClick = {
                        when (rec.kind) {
                            PlaybackPositionManager.KIND_LOCAL ->
                                rec.path?.let { onPlaySaved(it) }
                            // v1.19.7 — RESUME DIRECT PLAY: straight into the
                            // fullscreen player at the stored position (the
                            // ViewModel resolves the SAME request the info
                            // page's Play button builds). No info-page detour.
                            else -> onResume(rec)
                        }
                    }
                )
            }
        }
    }
}

/**
 * One RESUME banner (16:9): poster center-cropped into the landscape
 * frame, dark vignette for legibility, cyan “استئناف المشاهدة” chip
 * top-start, glass play affordance center, title + remaining-time strip
 * above the cyan progress bar. Focused: scale 1.04 + cyan ring — the
 * shelf reads as a distinct “jump back in” rail, never as row #1 of
 * the poster grid.
 */
@Composable
private fun VuContinueCard(
    record: PlaybackPositionManager.PositionRecord,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    val progress = if (record.durationMs > 0L) {
        (record.positionMs.toFloat() / record.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingSec = ((record.durationMs - record.positionMs).coerceAtLeast(0L)) / 1000L
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        label = "resumeScale"
    )
    val corner = RoundedCornerShape(s.d(6))

    Box(
        modifier = Modifier
            .padding(top = s.d(5))
            .padding(s.d(2))
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(corner)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
    ) {
        // ── backdrop: poster center-crop into the landscape frame ──
        if (!record.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = record.posterUrl,
                contentDescription = record.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                VuPalette.PanelOverlay,
                                Color(0xFF241A3F)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = VuPalette.White.copy(alpha = 0.30f),
                    modifier = Modifier.size(s.d(30))
                )
            }
        }

        // ── bottom vignette — text legibility over any artwork ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1.0f to Color(0xF2000000)
                    )
                )
        )

        // ── the cyan “استئناف المشاهدة” chip — EVERY card self-describes ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(s.d(5))
                .clip(RoundedCornerShape(s.d(3)))
                .background(VuPalette.Cyan.copy(alpha = 0.90f))
                .padding(horizontal = s.d(5), vertical = s.d(2.5f))
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF03222A),
                modifier = Modifier.size(s.d(8))
            )
            Spacer(Modifier.width(s.d(3)))
            Text(
                stringResource(R.string.continue_watching),
                color = Color(0xFF03222A),
                fontSize = s.t(7),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // ── offline badge — SAVED files only (gold, top-end; v1.19.6:
        //  joined the gold identity — was reference-yellow lemon) ──
        if (record.kind == PlaybackPositionManager.KIND_LOCAL) {
            Text(
                stringResource(R.string.saved_offline_tag),
                color = Color(0xFF1F1600),
                fontSize = s.t(7),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(s.d(5))
                    .clip(RoundedCornerShape(s.d(3)))
                    .background(VuGold.Gold)
                    .padding(horizontal = s.d(5), vertical = s.d(2.5f))
            )
        }

        // ── glass play affordance — cyan ring when focused ──
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .size(s.d(32))
                .clip(RoundedCornerShape(s.d(16)))
                .background(VuPalette.Black65)
                .then(
                    if (focused) Modifier.border(s.d(1.5f), VuPalette.Cyan, RoundedCornerShape(s.d(16)))
                    else Modifier.border(s.d(1), VuPalette.White.copy(alpha = 0.35f), RoundedCornerShape(s.d(16)))
                )
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.continue_watching),
                tint = VuPalette.White,
                modifier = Modifier.size(s.d(16))
            )
        }

        // ── title + remaining time strip ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = s.d(6), end = s.d(6), bottom = s.d(7))
        ) {
            Text(
                record.title,
                color = VuPalette.White,
                fontSize = s.t(8),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (record.durationMs > 0L) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(s.d(4))
                            .clip(RoundedCornerShape(s.d(2)))
                            .background(VuPalette.Cyan)
                    )
                    Spacer(Modifier.width(s.d(3)))
                    Text(
                        stringResource(R.string.remaining_time, formatRemaining(remainingSec)),
                        color = VuPalette.Cyan,
                        fontSize = s.t(7),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // ── thick cyan progress bar — the bottom edge IS the progress ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(s.d(3))
                .background(VuPalette.Black65)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(VuPalette.Cyan)
            )
        }

        // ── focus ring — the shelf's cards glow cyan, not purple ──
        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(s.d(1.5f), VuPalette.Cyan, corner)
            )
        }
    }
}

/** 3725 → "1:02:05"; 754 → "12:34". */
private fun formatRemaining(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

package com.superz.iptvplayer.ui.catchup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.VuTopBarAnchor
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** text_tint_color (#9f94a4) — the reference's secondary text. */
private val TextTint = Color(0xFF9F94A4)

/** black_30 (#4d000000) — the unselected channel logo's hover overlay. */
private val HoverOverlay = Color(0x4D000000)

/**
 * v1.12.3 — the Catch-Up home, REPLICATED 1:1 from the reference's
 * CatchUpActivity + activity_catch_up.xml + item_catch_up.xml +
 * item_catch_channel.xml (user request: "the design is completely different
 * from the reference — copy it exactly"):
 *
 *  header: ly_back + PremiumLL logo + CENTERED txt_header + tx_system_time
 *  (the shared reference top bar every replicated page carries).
 *
 *  GRID state (is_channel = false): a 2-column GridLayoutManager of category
 *  cards — TV icon 20sdp + 1.5sdp divider + "Name (count)" 12sdp + chevron
 *  15sdp, item_user_bg (#33707070, 5sdp corners) → focused/selected takes
 *  the reference's item_user_focused_bg gradient (#6C5AE0 → #9850ED) with
 *  white elements. Only categories owning tv-archive channels appear
 *  (getCatchUpCategoryModels' size > 0 filter).
 *
 *  LIST state (showCatchChannels): clicking a card swaps the SAME space to
 *  the vertical channel list — num 8sdp + logo 50×30sdp (black_30 hover
 *  when unselected) + name 10sdp + current program 8sdp + thin live
 *  progress line + right chevron — and the title flips to "CATCH UP/Live"
 *  exactly like the reference's txt_header.setText(catch_up_live).
 *
 *  Back: LIST → GRID (the reference's dispatchKeyEvent keyCode 4 branch);
 *  GRID → exit.
 *
 *  v1.12.3 empty-fix states: a VISIBLE "Preparing Catch-Up data…" surface
 *  while the flag re-sync runs in the application scope, and a Retry row
 *  when it failed (the pre-v1.12.0 upgrade path — see CatchUpViewModel).
 */
@Composable
fun CatchUpScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onOpenChannel: (playlistId: Long, channelKey: String) -> Unit,
    viewModel: CatchUpViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val s = rememberVuSdp()

    // dispatchKeyEvent(4): LIST → GRID; GRID → finish().
    BackHandler(enabled = ui.inChannelMode) { viewModel.selectCategory(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()   // v2.2.0 — global background aware
    ) {
        // ══ Top bar: ly_back + PremiumLL + txt_header + tx_system_time ══
        var premiumH by remember { mutableStateOf(androidx.compose.ui.unit.Dp.Unspecified) }
        val premiumHeight = premiumH.takeUnless { it == androidx.compose.ui.unit.Dp.Unspecified }
            ?: s.d(VuTopBarAnchor.LOGO_HEIGHT_FALLBACK_SDP)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
        ) {
            com.superz.iptvplayer.ui.theme.VuBackButton(onClick = onBack)
            VuPremiumLogo(
                modifier = Modifier.padding(start = s.d(10)),
                onHeight = { premiumH = it }
            )
        }
        // txt_header — 13sdp, centered on the screen, vertically centered on
        // the PremiumLL band; flips CATCH UP ↔ CATCH UP/Live with the mode.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                .height(premiumHeight)
        ) {
            Text(
                stringResource(
                    if (ui.inChannelMode) R.string.vu_catch_up_live else R.string.vu_catch_up
                ),
                color = VuPalette.White,
                fontSize = s.t(13),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        VuCatchClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                .height(premiumHeight)
                .padding(end = s.d(10))
        )

        // ══ recycler_category — margin 15sdp, below the PremiumLL band ══
        val bodyTop = s.d(VuTopBarAnchor.premiumBandBottomSdp(null)) + s.d(10)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = bodyTop, start = s.d(15), end = s.d(15), bottom = s.d(15))
        ) {
            when {
                // Brief first-read window — render nothing until the DB spoke.
                ui.loading -> Box(Modifier.fillMaxSize())
                // v1.12.3 — the VISIBLE flag re-sync (application scope).
                ui.syncing && ui.categories.isEmpty() -> VuCatchSyncing()
                // v1.12.3 — the failed re-sync surface with Retry.
                ui.syncFailed && ui.categories.isEmpty() -> VuCatchSyncFailed(
                    onRetry = { viewModel.retrySync() }
                )
                // GRID state — the 2-column category cards (the "All (N)"
                // card first, exactly the reference's genre list).
                !ui.inChannelMode -> {
                    if (ui.categories.isEmpty()) {
                        VuCatchEmpty(
                            totalChannels = ui.totalChannels,
                            archiveChannels = ui.archiveChannels,
                            categoryRows = ui.categoryRows
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(s.d(5)),
                            horizontalArrangement = Arrangement.spacedBy(s.d(10)),
                            verticalArrangement = Arrangement.spacedBy(s.d(10)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(ui.categories, key = { it.first.categoryId }) { (cat, count) ->
                                VuCatchCategoryCard(
                                    label = "${cat.name} ($count)",
                                    onClick = { viewModel.selectCategory(cat.categoryId) }
                                )
                            }
                        }
                    }
                }
                // LIST state — showCatchChannels (the channel rows).
                else -> {
                    if (ui.channels.isEmpty()) {
                        VuCatchEmpty(
                            totalChannels = ui.totalChannels,
                            archiveChannels = ui.archiveChannels,
                            categoryRows = ui.categoryRows
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = s.d(5))
                        ) {
                            items(ui.channels, key = { it.key }) { ch ->
                                VuCatchChannelRow(
                                    channel = ch,
                                    program = ui.nowPrograms[ch.key],
                                    // v1.12.6 — THE Catch-Up root cause fix: pass
                                    // the RESOLVED playlist id (ui.playlistId),
                                    // NEVER the raw route arg. The hub card routes
                                    // "catchup/-1" (the ACTIVE playlist); the
                                    // ViewModel resolves -1 to the real id, but
                                    // this click previously re-propagated the -1 →
                                    // "catchupdetail/-1/<key>" → playlistById(-1) =
                                    // null → days=∅ → "No Catch Up Found" on BOTH
                                    // MAC and XTREAM, while every data layer tested
                                    // correct (the detail screen never fetched).
                                    onClick = { onOpenChannel(ui.playlistId, ch.key) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── item_catch_up.xml — the category card ──────────────────────────

/**
 * The 2-column grid card: item_user_bg (#33707070, 5sdp corners, 10sdp
 * padding) → focused/selected = item_user_focused_bg (the button gradient
 * #6C5AE0 → #9850ED) with white elements. Children: image_catch_up (TV icon
 * 20×20sdp, text_color tint) + divider_view (1.5sdp, 7sdp gap) + txt_name
 * ("Name (count)", 12sdp) + image_down (chevron 15×15sdp).
 */
@Composable
private fun VuCatchCategoryCard(
    label: String,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val highlight = focused
    val contentColor = if (highlight) VuPalette.White else VuPalette.White

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s.d(5)))
            .then(
                if (highlight) Modifier.background(VuPalette.PurpleBrush)
                else Modifier.background(VuPalette.PanelOverlay)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(s.d(10))
    ) {
        // image_catch_up — the TV icon, 20×20sdp.
        Image(
            painter = painterResource(R.drawable.vu_ic_live_tv),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(s.d(20))
        )
        // divider_view — 1.5sdp wide, the icon's height, 7sdp start margin.
        Box(
            modifier = Modifier
                .padding(start = s.d(7))
                .width(s.d(1.5f))
                .height(s.d(20))
                .background(contentColor)
        )
        // txt_name — "Name (count)", 12sdp, 10sdp start margin.
        Text(
            label,
            color = contentColor,
            fontSize = s.t(12),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = s.d(10), end = s.d(10))
        )
        // image_down — the chevron, 15×15sdp (NO rotation — points down).
        Image(
            painter = painterResource(R.drawable.vu_image_down),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(s.d(15))
        )
    }
}

// ── item_catch_channel.xml — the channel row ───────────────────────

/**
 * The channel list row: txt_num (8sdp tint) + image_channel (50×30sdp white
 * bg + black_30 hover when unselected) + txt_name (10sdp) +
 * txt_program_name (8sdp, "No Information" fallback) + progress_live (thin
 * line, #6C5AE0 progress) + image_right (chevron 15×30sdp, rotation −90 →
 * points right). Selected/focused row: white texts + hover gone.
 */
@Composable
private fun VuCatchChannelRow(
    channel: Channel,
    program: EpgProgram?,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val highlight = focused
    val tintColor = if (highlight) VuPalette.White else TextTint

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(vertical = s.d(7))
    ) {
        // txt_num — 8sdp, gravity end.
        Text(
            channel.num.toString(),
            color = tintColor,
            fontSize = s.t(8),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(s.d(30))
                .padding(end = s.d(7))
        )
        // image_channel (50×30sdp, white bg) + hover_view (black_30).
        Box(
            modifier = Modifier
                .padding(start = s.d(5))
                .size(width = s.d(50), height = s.d(30))
                .background(VuPalette.White)
        ) {
            ChannelLogo(
                logoUrl = channel.logo,
                name = channel.name,
                sizeDp = 0,
                cornerDp = 0,
                modifier = Modifier
                    .size(width = s.d(50), height = s.d(30))
                    .align(Alignment.Center)
            )
            if (!highlight) {
                Box(
                    modifier = Modifier
                        .size(width = s.d(50), height = s.d(30))
                        .background(HoverOverlay)
                )
            }
        }
        // txt_name + txt_program_name + progress_live.
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .padding(start = s.d(7), end = s.d(7))
        ) {
            Text(
                channel.name,
                color = tintColor,
                fontSize = s.t(10),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                program?.title ?: stringResource(R.string.no_information),
                color = tintColor,
                fontSize = s.t(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // progress_live — 1sdp line, progress = elapsed/total of the
            // current program (the reference's epgModel.getProgress()).
            val progress = program?.let { p ->
                val total = (p.endMs - p.startMs).coerceAtLeast(1L)
                (((nowMs - p.startMs).coerceIn(0L, total)) / total.toFloat())
            } ?: 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = s.d(2))
                    .height(s.d(1.5f))
                    .background(TextTint.copy(alpha = 0.35f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(s.d(1.5f))
                        .background(VuPalette.PurpleStart)
                )
            }
        }
        // image_right — chevron 15×30sdp, rotation −90 (points right).
        Image(
            painter = painterResource(R.drawable.vu_image_down),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tintColor),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(end = s.d(7))
                .size(width = s.d(15), height = s.d(30))
                .graphicsLayer { rotationZ = -90f }
        )
    }
}

// ── The v1.12.3 states ─────────────────────────────────────────────

/** The visible re-sync surface (application-scope sync in flight). */
@Composable
private fun VuCatchSyncing() {
    val s = rememberVuSdp()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(
            color = VuPalette.Cyan,
            strokeWidth = s.d(2),
            modifier = Modifier.size(s.d(25))
        )
        Text(
            stringResource(R.string.vu_catch_up_syncing),
            color = VuPalette.White,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = s.d(10))
        )
    }
}

/** The failed re-sync surface — "No Catch Up Found" + Retry. */
@Composable
private fun VuCatchSyncFailed(onRetry: () -> Unit) {
    val s = rememberVuSdp()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            stringResource(R.string.vu_no_catchup_found),
            color = VuPalette.White,
            fontSize = s.t(12),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .padding(top = s.d(10))
                .clip(RoundedCornerShape(s.d(5)))
                .then(
                    if (focused) Modifier.background(VuPalette.PurpleBrush)
                    else Modifier.background(VuPalette.PanelOverlay)
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(onClick = onRetry)
                .padding(horizontal = s.d(15), vertical = s.d(7))
        ) {
            Text(
                stringResource(R.string.retry),
                color = VuPalette.White,
                fontSize = s.t(10),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** text_view_default — "No Catch Up Found", centered. v1.12.4: a subtle
 *  counts line under it (channels / archive / categories) — when the grid
 *  is still empty this pinpoints the broken layer at a glance (flags?
 *  category rows? playlist?) instead of a bare dead end. */
@Composable
private fun VuCatchEmpty(
    totalChannels: Int,
    archiveChannels: Int,
    categoryRows: Int
) {
    val s = rememberVuSdp()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.vu_no_catchup_found),
                color = VuPalette.White,
                fontSize = s.t(12),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (totalChannels > 0) {
                Text(
                    stringResource(
                        R.string.vu_catch_up_counts,
                        totalChannels, archiveChannels, categoryRows
                    ),
                    color = TextTint,
                    fontSize = s.t(9),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = s.d(6))
                )
            }
        }
    }
}

/** tx_system_time — "HH:mm | MMM d yyyy" 10sdp, ticks on the minute. */
@Composable
private fun VuCatchClock(modifier: Modifier = Modifier) {
    val s = rememberVuSdp()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L)
        }
    }
    val locale = Locale.getDefault()
    val fmt = remember(locale) {
        SimpleDateFormat("HH:mm | MMM d yyyy", locale)
    }
    Text(
        fmt.format(Date(nowMs)),
        color = VuPalette.White,
        fontSize = s.t(10),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
    )
}

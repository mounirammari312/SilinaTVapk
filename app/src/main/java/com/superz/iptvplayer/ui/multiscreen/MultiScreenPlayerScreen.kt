package com.superz.iptvplayer.ui.multiscreen

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.player.multiscreen.MultiLayout
import com.superz.iptvplayer.player.multiscreen.MultiScreenGrid
import com.superz.iptvplayer.player.multiscreen.MultiScreenManager
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — MULTI-SCREEN PLAYBACK (شاشة عرض الشاشات المتعددة).
 *
 *  The professional multi-view, in the plan-card family:
 *
 *   • THE GRID — every cell a texture PlayerView (the app's own
 *     one-active-surface architecture, multiplied): the focused cell
 *     wears the ANIMATED GOLDEN RING and owns the audio (its speaker
 *     glyph lights gold); the others carry a static hairline and a
 *     dimmed mute glyph;
 *   • AUDIO FOLLOWS FOCUS — D-pad arrows move Compose focus between
 *     cells and the sound swaps instantly with the highlight (the
 *     TiviMate contract: your eyes and your ears always agree);
 *   • ONE TAP EXPANDS — tapping/OK-ing the ALREADY-focused cell takes
 *     it fullscreen (the back-to-screens pill + Back return); tapping
 *     an unfocused cell just takes the audio;
 *   • THE TOP BAR (auto-hides like the player's): layout switch
 *     (2 ↔ 4 screens, live — shrinking releases the dropped cells),
 *     the channel picker for the highlighted screen, and close;
 *   • THE SLIDE-IN PICKER — the setup screen's searchable list, in
 *     place: pick a channel → the highlighted screen becomes it
 *     (PORTAL channels re-resolved via create_link, exactly like a
 *     zap in the single player);
 *   • EMPTY CELLS — a "+" invitation that opens the picker aimed at
 *     that slot: the grid can be filled mid-watch, never a restart;
 *   • PER-CELL OVERLAYS — gold spinner while buffering, a glass error
 *     card with a retry key, the slot's number badge and channel chip.
 * ═══════════════════════════════════════════════════════════════════
 */
@Composable
fun MultiScreenPlayerScreen(
    playlistId: Long,
    onBack: () -> Unit,
    viewModel: MultiScreenPlayerViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val localView = LocalView.current

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }

    // Keep the screen awake while the grid plays.
    DisposableEffect(Unit) {
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = false }
    }

    // Back stack: picker → fullscreen → leave.
    BackHandler(enabled = ui.pickerOpen) { viewModel.closePicker() }
    BackHandler(enabled = !ui.pickerOpen && ui.fullscreenSlot != null) { viewModel.exitFullscreen() }
    BackHandler(enabled = !ui.pickerOpen && ui.fullscreenSlot == null) { onBack() }

    // Controls auto-hide (the player's 4s contract, gentler for a grid);
    // any focus move / tap resets the countdown.
    fun pokeControls() {
        controlsVisible = true
        controlsTick++
    }
    LaunchedEffect(controlsTick, ui.pickerOpen) {
        if (ui.pickerOpen) {
            controlsVisible = true
        } else {
            delay(5000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!ui.loaded) {
            // The config is still resolving (session/prefs + playlist).
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VuGold.Gold, strokeWidth = 3.dp)
            }
        } else if (ui.cells.isEmpty()) {
            // Deep-link without a config — nothing to play.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.multiscreen_need_one),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            val fullscreenSlot = ui.fullscreenSlot
            if (fullscreenSlot == null) {
                // ── THE GRID ──
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                ) {
                    repeat(ui.layout.rows) { r ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            repeat(ui.layout.cols) { c ->
                                val slot = r * ui.layout.cols + c
                                MultiCell(
                                    slot = slot,
                                    cellUi = ui.cells.firstOrNull { it.slot == slot },
                                    focused = slot == ui.focusedSlot,
                                    isFullscreen = false,
                                    playerProvider = { viewModel.manager.playerFor(slot) },
                                    exoAttacher = { view -> viewModel.manager.attachExoView(slot, view) },
                                    exoDetacher = { view -> viewModel.manager.detachExoView(slot, view) },
                                    vlcBinder = { layout -> viewModel.manager.bindVlcView(slot, layout) },
                                    vlcUnbinder = { layout -> viewModel.manager.unbindVlcView(slot, layout) },
                                    onFocused = {
                                        viewModel.setFocus(slot)
                                        pokeControls()
                                    },
                                    onTargeted = { viewModel.setPickerTarget(slot) },
                                    onTap = {
                                        viewModel.onCellTap(slot)
                                        pokeControls()
                                    },
                                    onRetry = { viewModel.retryCell(slot) },
                                    onAddTapped = {
                                        viewModel.setPickerTarget(slot)
                                        viewModel.openPicker()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            } else {
                // ── FULLSCREEN CELL ──
                MultiCell(
                    slot = fullscreenSlot,
                    cellUi = ui.cells.firstOrNull { it.slot == fullscreenSlot },
                    focused = true,
                    isFullscreen = true,
                    playerProvider = { viewModel.manager.playerFor(fullscreenSlot) },
                    exoAttacher = { view -> viewModel.manager.attachExoView(fullscreenSlot, view) },
                    exoDetacher = { view -> viewModel.manager.detachExoView(fullscreenSlot, view) },
                    vlcBinder = { layout -> viewModel.manager.bindVlcView(fullscreenSlot, layout) },
                    vlcUnbinder = { layout -> viewModel.manager.unbindVlcView(fullscreenSlot, layout) },
                    onFocused = { },
                    onTargeted = { },
                    onTap = { viewModel.exitFullscreen() },
                    onRetry = { viewModel.retryCell(fullscreenSlot) },
                    onAddTapped = { },
                    modifier = Modifier.fillMaxSize()
                )
                // The back-to-screens pill (top-start, always visible).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 12.dp)
                ) {
                    var pillFocused by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .vuPlanCardStyle(cornerRadius = 20.dp, focused = pillFocused)
                            .onFocusChanged { pillFocused = it.isFocused }
                            .clickable { viewModel.exitFullscreen() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.GridView,
                            contentDescription = null,
                            tint = VuGold.Text,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(R.string.multiscreen_back_grid),
                            color = VuGold.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── TOP BAR (grid mode, auto-hiding) ──
            AnimatedVisibility(
                visible = controlsVisible && !ui.pickerOpen && fullscreenSlot == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    TopGlassButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        description = stringResource(R.string.cancel),
                        size = 34.dp
                    ) { onBack() }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.multiscreen_title),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${ui.cells.size}",
                            color = VuGold.Text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Layout switch — 2 ↔ 4 screens, live.
                    TopGlassButton(
                        icon = Icons.Filled.GridView,
                        description = stringResource(
                            if (ui.layout == MultiLayout.TWO) R.string.multiscreen_four
                            else R.string.multiscreen_two
                        ),
                        size = 34.dp
                    ) {
                        viewModel.setLayout(
                            if (ui.layout == MultiLayout.TWO) MultiLayout.FOUR else MultiLayout.TWO
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // The channel picker for the highlighted screen.
                    TopGlassButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        description = stringResource(R.string.channel_list),
                        size = 34.dp
                    ) { viewModel.openPicker() }
                    Spacer(Modifier.width(6.dp))
                    TopGlassButton(
                        icon = Icons.Filled.Close,
                        description = stringResource(R.string.cancel),
                        size = 34.dp
                    ) { onBack() }
                }
            }
        }

        // ── THE SLIDE-IN CHANNEL PICKER ──
        AnimatedVisibility(
            visible = ui.pickerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            MultiPickerPanel(
                slot = ui.pickerSlot,
                query = ui.query,
                channels = ui.channels,
                slotsInUse = ui.cells.map { it.slot to it.channel.key },
                playlist = ui.playlist,
                onSearch = { viewModel.onSearch(it) },
                onPick = {
                    viewModel.pick(it)
                    viewModel.closePicker()
                },
                onClose = { viewModel.closePicker() }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  The cell
// ═══════════════════════════════════════════════════════════════

/**
 * ONE GRID CELL — the video surface + its overlays. Focusable (D-pad
 * owns it: arrows move focus cell-to-cell, the audio follows); tap on
 * the focused cell expands it, tap on an unfocused one takes the
 * audio. An EMPTY cell is a "+" invitation that opens the picker aimed
 * at this slot. v2.4.0 — the cell renders its surface BY ENGINE: an
 * EXO cell binds a texture PlayerView, a VLC cell binds a
 * VLCVideoLayout to the cell's own MediaPlayer (the software rung).
 */
@Composable
private fun MultiCell(
    slot: Int,
    cellUi: MultiScreenManager.CellUi?,
    focused: Boolean,
    isFullscreen: Boolean,
    playerProvider: () -> Player?,
    exoAttacher: (PlayerView) -> Unit,
    exoDetacher: (PlayerView) -> Unit,
    vlcBinder: (VLCVideoLayout) -> Unit,
    vlcUnbinder: (VLCVideoLayout) -> Unit,
    onFocused: () -> Unit,
    onTargeted: () -> Unit,
    onTap: () -> Unit,
    onRetry: () -> Unit,
    onAddTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    var composeFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .vuPlanCardStyle(
                cornerRadius = if (isFullscreen) 0.dp else 10.dp,
                focused = focused || composeFocused
            )
            .onFocusChanged { state ->
                composeFocused = state.isFocused
                if (state.isFocused) {
                    onFocused()
                    onTargeted()
                }
            }
            .focusable()
            .clickable { if (cellUi != null) onTap() else onAddTapped() }
    ) {
        if (cellUi == null) {
            // ── The empty slot's invitation ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.multiscreen_empty_slot),
                    tint = VuGold.Text.copy(alpha = 0.65f),
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    stringResource(R.string.multiscreen_empty_slot),
                    color = VuGold.Text.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // ── The video surface, BY ENGINE (v2.4.0) ──
            // EXO cell → the texture PlayerView (the app's one-active-
            // surface architecture); VLC cell → a VLCVideoLayout bound
            // to the cell's own libVLC MediaPlayer (the software rung —
            // the same view family the single player's VLC engine
            // renders into). The key carries the engine so a promotion
            // swaps the surface tree cleanly.
            androidx.compose.runtime.key(cellUi.channel.key, cellUi.engine) {
                when (cellUi.engine) {
                    MultiScreenGrid.CellEngine.EXO -> AndroidView(
                        factory = { ctx ->
                            val view = LayoutInflater.from(ctx)
                                .inflate(R.layout.player_view_texture, null) as PlayerView
                            view.useController = false
                            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            // v2.4.1 — the surface is registered ENGINE-SIDE
                            // the instant it exists: every player this slot
                            // later births is bound to it at creation (the
                            // manager's birth-bind — the deterministic fix
                            // for the audio-yes-picture-no field report).
                            exoAttacher(view)
                            view
                        },
                        update = { it.player = playerProvider() },
                        onRelease = {
                            exoDetacher(it)
                            it.player = null
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    MultiScreenGrid.CellEngine.VLC -> AndroidView(
                        factory = { ctx -> VLCVideoLayout(ctx) },
                        update = { layout -> vlcBinder(layout) },
                        onRelease = { layout -> vlcUnbinder(layout) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── The channel chip (top-start): number + name on glass ──
            if (!isFullscreen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.60f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${cellUi.channel.num}",
                        color = VuGold.Text,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        cellUi.channel.name,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(110.dp)
                    )
                }
            }

            // ── The audio glyph (bottom-end): gold speaker on the cell
            //    that owns the sound; dimmed mute on the silent ones. ──
            Icon(
                if (focused) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                contentDescription = null,
                tint = if (focused) VuGold.Text else TextSecondary.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(14.dp)
            )

            // ── Buffering: the gold spinner, centered ──
            if (cellUi.status == MultiScreenManager.CellStatus.LOADING ||
                cellUi.status == MultiScreenManager.CellStatus.BUFFERING
            ) {
                CircularProgressIndicator(
                    color = VuGold.Gold,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp)
                )
            }

            // ── Error: the glass card + the retry key ──
            if (cellUi.status == MultiScreenManager.CellStatus.ERROR) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.multiscreen_cell_error),
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // v2.3.2 — the diagnostic line: WHICH wall the cell hit
                    // (decoder / server / timeout) — the field user sees the
                    // real story instead of one generic "unavailable".
                    val reasonRes = when (cellUi.failureKind) {
                        MultiScreenManager.FailureKind.DECODER -> R.string.multiscreen_fail_decoder
                        MultiScreenManager.FailureKind.TIMEOUT -> R.string.multiscreen_fail_timeout
                        else -> R.string.multiscreen_fail_server
                    }
                    if (cellUi.failureKind != MultiScreenManager.FailureKind.NONE) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(reasonRes),
                            color = TextSecondary,
                            fontSize = 8.sp,
                            maxLines = 2
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.retry),
                        color = VuGold.Text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRetry() }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // ── Fullscreen: the expand affordance on the focused cell ──
            if (focused && !isFullscreen) {
                Icon(
                    Icons.Filled.WebAsset,
                    contentDescription = stringResource(R.string.multiscreen_fullscreen_cd),
                    tint = VuGold.Text.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(15.dp)
                )
            }
        }

        // ── The slot number badge (grid mode) — solid gold, always on ──
        if (!isFullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 6.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(VuGold.Gold),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${slot + 1}",
                    color = VuGold.OnGold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  The slide-in picker panel
// ═══════════════════════════════════════════════════════════════

/** The right slide-in picker — the player sidebar's glass idiom. */
@Composable
private fun MultiPickerPanel(
    slot: Int,
    query: String,
    channels: List<Channel>,
    slotsInUse: List<Pair<Int, String>>,
    playlist: com.superz.iptvplayer.data.db.Playlist?,
    onSearch: (String) -> Unit,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        Spacer(Modifier.weight(0.55f))
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .background(Color(0xF2050608))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Header: title + close
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.multiscreen_title),
                        color = VuGold.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.multiscreen_change_channel, slot + 1),
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
                var closeFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .vuPlanCardStyle(cornerRadius = 17.dp, focused = closeFocused)
                        .onFocusChanged { closeFocused = it.isFocused }
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = VuGold.Text,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PickerSearchField(value = query, onValueChange = onSearch)
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(channels, key = { it.key }) { channel ->
                    val assignedSlot = slotsInUse
                        .firstOrNull { it.second == channel.key }?.first
                    MultiPickRow(
                        channel = channel,
                        capable = MultiScreenGrid.isMultiScreenCapable(playlist, channel),
                        assignedSlot = assignedSlot,
                        onClick = { onPick(channel) }
                    )
                }
                if (channels.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_results, query),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  The top-bar glass button
// ═══════════════════════════════════════════════════════════════

/** The plan-card circular icon button (the player's GlassIconButton). */
@Composable
private fun TopGlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .vuPlanCardStyle(cornerRadius = size / 2, focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = VuGold.Text,
            modifier = Modifier.size((size.value * 0.5f).dp)
        )
    }
}

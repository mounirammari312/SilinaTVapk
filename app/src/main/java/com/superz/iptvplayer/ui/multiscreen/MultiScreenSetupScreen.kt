package com.superz.iptvplayer.ui.multiscreen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.player.multiscreen.MultiLayout
import com.superz.iptvplayer.player.multiscreen.MultiScreenGrid
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.theme.BackgroundDark
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.1 — MULTI-SCREEN SETUP (شاشة إعداد الشاشات المتعددة),
 *  REDESIGNED on the user's field report (v2.3.0):
 *
 *   ① THE MODE BUTTONS were oversized cards with big diagrams — now a
 *      single slim SEGMENTED CONTROL (two compact pills, 40dp), the
 *      professional pattern (YouTube TV's 2×2 / 1×2 toggle);
 *   ② THE CHANNEL LIST took over half the screen and squeezed the
 *      panel — now 40% (search + rows still breathe at TV scale) with
 *      the setup panel at 60%;
 *   ③ THE START BUTTON was pushed BELOW THE FOLD by the 16:9 slot grid
 *      (in 4-screen mode it vanished entirely — the user literally
 *      could not start) — now a STICKY BOTTOM BAR, ALWAYS on screen in
 *      BOTH layouts: the live count chip ("2/4") + the big gold START
 *      pill (dimmed + the need-one hint until the first channel is
 *      picked).
 *
 *  The slot preview cells flex to the available height (no fixed
 *  aspect ratio) — the layout can never overflow, in any mode, on any
 *  screen. The pick-and-place flow itself is unchanged from v2.3.0:
 *  list tap → cursor slot → cursor hops to the next empty slot; a
 *  second tap removes; the cursor slot wears the golden ring.
 * ═══════════════════════════════════════════════════════════════════
 */
@Composable
fun MultiScreenSetupScreen(
    playlistId: Long,
    seedChannelKey: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    viewModel: MultiScreenSetupViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // ── Header: back + title + the audio-follows hint (compact) ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            SetupBackButton { onBack() }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.multiscreen_title),
                    color = VuGold.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.multiscreen_pick_hint),
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        // ── THE BODY: setup panel (60%) | channel picker (40%) ──
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Setup panel — in RTL this sits on the RIGHT (the natural
            // "controls" side, mirroring the screenshot's reading order).
            Column(
                modifier = Modifier
                    .weight(0.60f)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp)
            ) {
                // ① THE SEGMENTED MODE CONTROL — two compact pills.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModePill(
                        layout = MultiLayout.TWO,
                        label = stringResource(R.string.multiscreen_two),
                        selected = ui.layout == MultiLayout.TWO,
                        onClick = { viewModel.setLayout(MultiLayout.TWO) },
                        modifier = Modifier.weight(1f)
                    )
                    ModePill(
                        layout = MultiLayout.FOUR,
                        label = stringResource(R.string.multiscreen_four),
                        selected = ui.layout == MultiLayout.FOUR,
                        onClick = { viewModel.setLayout(MultiLayout.FOUR) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                // The slot preview — FLEXIBLE cells (weight rows): the
                // grid fills whatever height the screen actually has,
                // so it can never push the start bar off the fold.
                SlotGridPreview(
                    layout = ui.layout,
                    slots = ui.slots,
                    cursorSlot = ui.selectedSlot,
                    onSelectSlot = { viewModel.selectSlot(it) },
                    onClearSlot = { viewModel.clearSlot(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            // ── The searchable channel picker (40%) ──
            Column(
                modifier = Modifier
                    .weight(0.40f)
                    .fillMaxHeight()
                    .padding(end = 14.dp)
            ) {
                PickerSearchField(
                    value = ui.query,
                    onValueChange = { viewModel.onSearch(it) }
                )
                Spacer(Modifier.height(6.dp))
                if (!ui.loaded) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VuGold.Gold, strokeWidth = 3.dp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
                    ) {
                        items(ui.channels, key = { it.key }) { channel ->
                            val slotOf = ui.slots.indexOfFirst { it?.key == channel.key }
                            MultiPickRow(
                                channel = channel,
                                capable = MultiScreenGrid.isMultiScreenCapable(ui.playlist, channel),
                                assignedSlot = if (slotOf >= 0) slotOf else null,
                                onClick = { viewModel.pick(channel) }
                            )
                        }
                        if (ui.channels.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.no_results, ui.query),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── ③ THE STICKY START BAR — ALWAYS visible, both layouts ──
        StartBar(
            assigned = ui.slots.count { it != null },
            cells = ui.layout.cells,
            onStart = { viewModel.start(onStart) }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// The family widgets
// ═══════════════════════════════════════════════════════════════

/** The circular glass back button (the family's GlassIconButton). */
@Composable
private fun SetupBackButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(42.dp)
            .vuPlanCardStyle(cornerRadius = 21.dp, focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cancel),
            tint = VuGold.Text,
            modifier = Modifier.size(21.dp)
        )
    }
}

/**
 * ① THE COMPACT MODE PILL — the v2.3.1 answer to "أزرار اختيار
 * الأوضاع كبير وأخذت مساحة بلا فائدة": one slim 40dp glass pill with
 * a TINY grid glyph (the shape itself, 12×8dp cells) instead of the
 * v2.3.0 feature-sized card. Selected = the warm-bronze lit face +
 * gold text; resting = dark glass + hairline.
 */
@Composable
private fun ModePill(
    layout: MultiLayout,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(40.dp)
            .vuPlanCardStyle(cornerRadius = 20.dp, focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 10.dp)
    ) {
        // The tiny diagram — the grid shape itself, 12×8dp mini-cells.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(layout.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(layout.cols) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (selected) VuGold.Gold.copy(alpha = 0.90f)
                                    else VuGold.Gold.copy(alpha = 0.35f)
                                )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (selected) VuGold.Text else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * One slot of the preview grid — the real geometry, the real content,
 * FLEXIBLE height (v2.3.1: no fixed aspect ratio — the cell stretches
 * to whatever the screen can afford, so the layout never overflows).
 */
@Composable
private fun SlotCellPreview(
    slot: Int,
    channel: Channel?,
    isCursor: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onClear: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .padding(2.dp)
            .vuPlanCardStyle(cornerRadius = 10.dp, focused = focused, selected = isCursor)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onSelect() }
    ) {
        if (channel == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.multiscreen_empty_slot),
                    tint = VuGold.Text.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    stringResource(R.string.multiscreen_empty_slot),
                    color = VuGold.Text.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                ChannelLogo(logoUrl = channel.logo, name = channel.name, sizeDp = 34)
                Spacer(Modifier.width(8.dp))
                Text(
                    channel.name,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // The slot's clear key (removes the channel, keeps the grid)
                var clearFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .vuPlanCardStyle(cornerRadius = 13.dp, focused = clearFocused)
                        .onFocusChanged { clearFocused = it.isFocused }
                        .clickable { onClear() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.delete),
                        tint = VuGold.Text,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        // The slot number badge — solid gold, always readable.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
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

/** The preview grid in the real layout geometry — rows share the height. */
@Composable
private fun SlotGridPreview(
    layout: MultiLayout,
    slots: List<Channel?>,
    cursorSlot: Int,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        var slot = 0
        repeat(layout.rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                repeat(layout.cols) {
                    val s = slot
                    SlotCellPreview(
                        slot = s,
                        channel = slots.getOrNull(s),
                        isCursor = s == cursorSlot,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelectSlot(s) },
                        onClear = { onClearSlot(s) }
                    )
                    slot++
                }
            }
        }
    }
}

/**
 * ③ THE STICKY START BAR — the v2.3.1 answer to "أزرار متخفية": the
 * primary action lives in its own always-visible bottom bar (the
 * professional setup pages' contract — the CTA can never scroll or
 * overflow away). The left chip is the live count ("2/4"); until the
 * first channel is picked it shows the need-one hint instead and the
 * gold pill rests dimmed and unclickable.
 */
@Composable
private fun StartBar(
    assigned: Int,
    cells: Int,
    onStart: () -> Unit
) {
    val ready = assigned > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF2050608))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // The count chip (or the need-one hint before the first pick).
        Box(
            modifier = Modifier
                .vuPlanCardStyle(cornerRadius = 14.dp, focused = false, selected = ready)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (ready) "$assigned / $cells" else stringResource(R.string.multiscreen_need_one),
                color = if (ready) VuGold.Text else TextSecondary.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        // THE big gold START pill — the plan-card contract, PlayArrow glyph.
        var focused by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .vuPlanCardStyle(cornerRadius = 25.dp, focused = focused && ready)
                .onFocusChanged { focused = it.isFocused }
                .clickable(enabled = ready) { onStart() }
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (ready) VuGold.Text else VuGold.Text.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.multiscreen_start),
                color = if (ready) VuGold.Text else VuGold.Text.copy(alpha = 0.45f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** The picker's search field — the login forms' pill-field contract. */
@Composable
internal fun PickerSearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .then(
                if (focused) Modifier.vuPlanCardStyle(cornerRadius = 12.dp, focused = true)
                else Modifier
                    .clip(shape)
                    .background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA))
                    .border(0.8.dp, VuGold.Gold.copy(alpha = 0.30f), shape)
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = VuGold.Text.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(VuGold.Text),
                keyboardOptions = KeyboardOptions(),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
            )
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.search_hint),
                    color = VuGold.Text.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * One picker row — the ChannelRow's plan-card idiom with the
 * multi-screen's own semantics: the number badge, the logo, the name,
 * and on the end either the "الشاشة N" tag (placed) or the "+" hint
 * (next landing slot); incapable channels dim with a "غير مدعومة"
 * chip and never react. Shared by the setup screen and the playback
 * screen's slide-in picker.
 */
@Composable
internal fun MultiPickRow(
    channel: Channel,
    capable: Boolean,
    assignedSlot: Int?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .vuPlanCardRowStyle(cornerRadius = 10.dp, focused = focused, selected = assignedSlot != null)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = capable) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // Number badge — solid gold when placed (the golden marker)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (assignedSlot != null) androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(VuGold.Gold, VuGold.Rich)
                    )
                    else SolidColor(Color(0x1AFFFFFF))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${channel.num}",
                color = if (assignedSlot != null) VuGold.OnGold else TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(9.dp))
        ChannelLogo(logoUrl = channel.logo, name = channel.name, sizeDp = 34)
        Spacer(Modifier.width(9.dp))
        Text(
            channel.name,
            color = if (!capable) TextSecondary.copy(alpha = 0.45f)
            else if (assignedSlot != null) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (assignedSlot != null) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!capable) {
            Text(
                stringResource(R.string.multiscreen_not_supported),
                color = TextSecondary.copy(alpha = 0.55f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        } else if (assignedSlot != null) {
            Text(
                stringResource(R.string.multiscreen_assigned_tag, assignedSlot + 1),
                color = VuGold.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = VuGold.Text.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

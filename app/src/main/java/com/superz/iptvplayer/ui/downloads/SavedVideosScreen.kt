package com.superz.iptvplayer.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.playback.PlaybackPositionManager
import com.superz.iptvplayer.ui.theme.AccentCyan
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuTopBarAnchor
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/** Amber — the "incomplete save" state color (between cyan and red). */
private val WarnAmber = Color(0xFFFFB300)

/**
 * v1.18.1 — the Saved Videos library (user request): ONE page holding every
 * auto-saved / manually downloaded video — COMPLETE and INCOMPLETE — for
 * OFFLINE browsing (airplane mode works: a plain directory scan), watching,
 * continuing watching (the local player resumes at the last position), and
 * deleting. Reached from the hub's category row (a 4th card next to
 * Movies / Series / Live).
 *
 * v1.19.13 — REDESIGNED per the user's feedback (“تضهر مجرد كاسماء بمضهر
 * بدائي… من المفروض تضهر بشكل احترافي كبطاقة فيديو مواصلة المشاهدة”):
 * the primitive text rows are gone; the library is now a GRID of 16:9 media
 * cards in the exact Continue-Watching language — a real THUMBNAIL frame
 * extracted from the file itself (SavedThumbs), the bottom vignette, a
 * state chip (gold “بلا أنترنت” / amber “غير مكتمل” / amber “حفظ NN%”), the
 * glass play affordance with its cyan focus ring, the title + remaining-
 * time strip, and the thick cyan watch-progress bar on the bottom edge.
 * Tap = watch, long-press (or the corner trash) = delete. The screen keeps
 * the shared Vu top bar and re-scans every 2.5 s while visible.
 */
@Composable
fun SavedVideosScreen(
    onBack: () -> Unit,
    onPlay: (filePath: String) -> Unit,
    viewModel: SavedVideosViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s = rememberVuSdp()

    // The 2.5 s rescan loop — in-flight percents climb, finalized files
    // flip to COMPLETE, deleted files vanish, all without pulling anything.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.rescan()
            delay(2_500L)
        }
    }

    // Transient message toast — auto-dismiss.
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            delay(2_500L)
            viewModel.consumeMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()   // v2.2.0 — global background aware
    ) {
        // ══ Top bar: ly_back + PremiumLL + centered title + clock ══
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            com.superz.iptvplayer.ui.theme.VuBackButton(onClick = onBack)
            com.superz.iptvplayer.ui.theme.VuPremiumLogo(
                modifier = Modifier.padding(start = s.d(10))
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                .height(s.d(VuTopBarAnchor.LOGO_HEIGHT_FALLBACK_SDP))
        ) {
            Text(
                stringResource(R.string.saved_videos_title),
                color = VuPalette.White,
                fontSize = s.t(13),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        SavedClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP), end = s.d(10))
        )

        // ══ Body — the 16:9 card grid ══
        val bodyTop = s.d(VuTopBarAnchor.premiumBandBottomSdp(null)) + s.d(10)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = bodyTop, start = s.d(15), end = s.d(15), bottom = s.d(15))
        ) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize())
                ui.videos.isEmpty() -> SavedEmpty()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(s.d(160)),
                    horizontalArrangement = Arrangement.spacedBy(s.d(10)),
                    verticalArrangement = Arrangement.spacedBy(s.d(12)),
                    contentPadding = PaddingValues(vertical = s.d(5))
                ) {
                    gridItems(ui.videos, key = { it.path }) { video ->
                        SavedVideoCard(
                            video = video,
                            thumbPath = ui.thumbs[video.path],
                            record = ui.progress[video.path],
                            onPlay = { onPlay(video.path) },
                            onDelete = { viewModel.askDelete(video) }
                        )
                    }
                }
            }
        }

        // ══ Transient toast (bottom center) ══
        AnimatedVisibility(
            visible = ui.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = s.d(18))
        ) {
            ui.message?.let { msg ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, GlassSurface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(msg, color = TextPrimary, fontSize = 12.sp, maxLines = 2)
                }
            }
        }

        // ══ Delete confirmation (glass dialog on a CONSUMING scrim) ══
        ui.pendingDelete?.let { target ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { viewModel.cancelDelete() }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassSurface.copy(alpha = 0.95f))
                    .border(1.dp, AccentCyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .width(s.d(300))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = WarnAmber,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.saved_delete_confirm_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        target.title,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        SavedPill(
                            label = stringResource(R.string.delete),
                            color = WarnAmber,
                            onClick = { viewModel.confirmDelete() }
                        )
                        Spacer(Modifier.width(10.dp))
                        SavedPill(
                            label = stringResource(R.string.cancel),
                            color = TextSecondary,
                            onClick = { viewModel.cancelDelete() }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// v1.19.13 — The 16:9 media card (the Continue-Watching language)
// ─────────────────────────────────────────────────────────────────

/**
 * One saved-video card — the exact idiom of BrowseScreen's VuContinueCard:
 * thumbnail center-cropped into the landscape frame, dark vignette for
 * legibility, a state chip top-start (gold “بلا أنترنت” for complete saves
 * — the offline identity the Continue shelf uses for local entries; amber
 * “غير مكتمل” / “حفظ NN%” for the others), a glass play affordance with
 * its cyan focus ring, the title + remaining-time strip above the cyan
 * watch-progress bar. Tap plays (resumes at the stored stop point — the
 * player's open-time bake), long-press or the corner trash deletes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedVideoCard(
    video: SavedVideo,
    thumbPath: String?,
    record: PlaybackPositionManager.PositionRecord?,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        label = "savedCardScale"
    )
    val corner = RoundedCornerShape(s.d(6))

    val progress = if (record != null && record.durationMs > 0L) {
        (record.positionMs.toFloat() / record.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingSec = record?.let {
        (it.durationMs - it.positionMs).coerceAtLeast(0L) / 1000L
    } ?: 0L

    Box(
        modifier = Modifier
            .padding(top = s.d(5))
            .padding(s.d(2))
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(corner)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onPlay, onLongClick = onDelete)
    ) {
        // ── backdrop: the file's own frame, center-cropped ──
        if (thumbPath != null) {
            AsyncImage(
                model = thumbPath,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(VuPalette.PanelOverlay, Color(0xFF241A3F))
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

        // ── the state chip — top-start, every card self-describes ──
        when (video.state) {
            SavedVideosContract.State.COMPLETE ->
                StateChip(
                    modifier = Modifier.align(Alignment.TopStart),
                    text = stringResource(R.string.saved_offline_tag),
                    bg = VuGold.Gold
                )
            SavedVideosContract.State.INCOMPLETE ->
                StateChip(
                    modifier = Modifier.align(Alignment.TopStart),
                    text = stringResource(R.string.saved_chip_partial),
                    bg = WarnAmber
                )
            SavedVideosContract.State.DOWNLOADING -> {
                val pct = video.activePercent.coerceAtLeast(0)
                StateChip(
                    modifier = Modifier.align(Alignment.TopStart),
                    text = stringResource(R.string.saved_chip_saving, pct),
                    bg = WarnAmber
                )
            }
        }

        // ── the corner trash — delete without the long-press ──
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(s.d(5))
                .size(s.d(20))
                .clip(RoundedCornerShape(s.d(3)))
                .background(VuPalette.Black65)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDelete() }
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = VuPalette.White,
                modifier = Modifier.size(s.d(10))
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
                contentDescription = stringResource(R.string.saved_watch),
                tint = VuPalette.White,
                modifier = Modifier.size(s.d(16))
            )
        }

        // ── title + meta strip ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = s.d(6), end = s.d(6), bottom = s.d(7))
        ) {
            Text(
                video.title,
                color = VuPalette.White,
                fontSize = s.t(8),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record != null && record.durationMs > 0L) {
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
                    Spacer(Modifier.width(s.d(5)))
                    Text(
                        SavedVideosContract.formatSize(video.sizeBytes),
                        color = VuPalette.TintGrey,
                        fontSize = s.t(7),
                        maxLines = 1
                    )
                } else {
                    Text(
                        "${SavedVideosContract.formatSize(video.sizeBytes)}  •  " +
                            SavedVideosContract.formatDate(video.lastModifiedMs),
                        color = VuPalette.TintGrey,
                        fontSize = s.t(7),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── thick cyan progress bar — the bottom edge IS the progress ──
        if (record != null && record.durationMs > 0L) {
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
        }

        // ── focus ring — the library's cards glow cyan ──
        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(s.d(1.5f), VuPalette.Cyan, corner)
            )
        }
    }
}

/** The small corner chip: colored face + dark text (the offline-tag idiom).
 *  The caller supplies the BoxScope alignment (top-start here). */
@Composable
private fun StateChip(modifier: Modifier, text: String, bg: Color) {
    val s = rememberVuSdp()
    Text(
        text,
        color = Color(0xFF1F1600),
        fontSize = s.t(7),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .padding(s.d(5))
            .clip(RoundedCornerShape(s.d(3)))
            .background(bg)
            .padding(horizontal = s.d(5), vertical = s.d(2.5f))
    )
}

// ─────────────────────────────────────────────────────────────────
// Shared bits
// ─────────────────────────────────────────────────────────────────

/** Small glass pill button (the delete dialog's action idiom). */
@Composable
private fun SavedPill(label: String, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassSurface.copy(alpha = 0.85f))
            .border(1.dp, color.copy(alpha = if (focused) 0.8f else 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.White else color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Empty library — what to do next, in the user's language. */
@Composable
private fun SavedEmpty() {
    val s = rememberVuSdp()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Icon(
            Icons.Filled.Save,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(46.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.saved_empty_title),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.saved_empty_message),
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 30.dp)
        )
    }
}

/** tx_system_time — "HH:mm | MMM d yyyy", ticks on the minute. */
@Composable
private fun SavedClock(modifier: Modifier = Modifier) {
    val s = rememberVuSdp()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L)
        }
    }
    // v2.1.3 — the app's LIVE locale (ChannelViewScreen's pattern), not
    // Locale.getDefault(): the saved-videos clock must speak the UI's
    // language, which the configuration carries after a Settings switch.
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { SimpleDateFormat("HH:mm | MMM d yyyy", locale) }
    Text(
        fmt.format(Date(nowMs)),
        color = VuPalette.White,
        fontSize = s.t(10),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
    )
}

/** 3725 → "1:02:05"; 754 → "12:34". */
private fun formatRemaining(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

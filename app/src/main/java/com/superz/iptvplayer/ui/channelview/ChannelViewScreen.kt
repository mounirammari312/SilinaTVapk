package com.superz.iptvplayer.ui.channelview

import android.app.PictureInPictureParams
import android.util.Rational
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.data.epg.toEpgTimeLabel
import com.superz.iptvplayer.player.Engine
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuCategoryChevron
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.VuSdp
import com.superz.iptvplayer.ui.theme.VuSplit
import com.superz.iptvplayer.ui.theme.VuTopBarAnchor
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground
import com.superz.iptvplayer.ui.theme.vuGoldFace
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Channel view — v1.4.7: an EXACT design copy of the reference app's split
 * page with the mini player (activity_live_play.xml, decompiled).
 * v1.4.8 tweaks (user requests):
 *   • the mini-player video FILLS its frame (Exo RESIZE_MODE_ZOOM — no
 *     letterbox gaps inside the ly_surface border)
 *   • the selected/focused channel row uses the channel-grid page's filled
 *     purple-gradient pill (radius 5sdp) instead of a white outline stroke
 *
 * Reference contract (activity_live_play.xml, decompiled):
 *
 *   • Root gradient #1a1251 → #3d1f39 (drawable/background)
 *   • Guidelines (of the FULL screen): vertical 35% / 78%, horizontal 18% / 65%
 *   • Top bar: back chevron 12×25 + "Back" 12sdp | logo 35×35sdp (marginTop
 *     15sdp) | clock "06:30 PM | May 17 2022" 10sdp at the right
 *   • Left panel (10sdp → 35%−10sdp, below the top bar): #33707070 with TOP
 *     corners 15sdp (main_left_bg); category bar = live_category_bg purple
 *     pill 25sdp tall radius 12sdp with two chevrons 10×20sdp and centered
 *     10sdp label; channel rows (item_channel_list): padding 5sdp, num 7sdp,
 *     logo 40×25sdp, name 9sdp (2 lines), now-program 7sdp
 *   • Mini player (35%→78% x, 18%→65% y): ly_surface_bg = #33707070 +
 *     0.5dp white stroke, radius 2sdp, padding 2sdp; centered 20sdp progress
 *     while buffering; bottom-right buttons 25/25/30sdp
 *     (aspect / PiP / fullscreen — ic_circle_gray_bg style)
 *   • Info column (78%+10sdp → right, aligned to the player): red "Live TV"
 *     badge (color_red, radius 12sdp, 7sdp text, padding 3sdp/10sdp) +
 *     channel name 9sdp + program name 6sdp + program time 7sdp — all centered
 *   • EPG list (35% → right−15sdp, below the player): item_epg_list rows —
 *     "time | channel | title" 9sdp + description 7sdp (3 lines); the CURRENT
 *     row in #03dffe (button_vpn_end_color), the rest #888888 (lb_grey);
 *     "Unavailable -" in yellow when the channel has no EPG
 *
 * ONLY the design is copied. The playback engine integration (EXO/VLC
 * AndroidView attach/detach, tap-to-fullscreen, PiP, retry) is the
 * project's own and is kept VERBATIM from the previous versions.
 *
 * v1.19.4 (user request: “طبق الذهبي على شاشة أين يوجد المشغل المصغر على
 * أزرار القوائم الجانبية نفس الألوان شاشة القنوات كي يكون التطبيق نفس
 * الهوية”) — the whole LEFT SIDEBAR joins the app's golden identity,
 * the exact BrowseScreen contract:
 *   • category bar — the TRUE solid-gold face (vuGoldFace: VERTICAL
 *     saturated metallic sweep + cylinder falloff — never the cream-sided
 *     yellow wash) + the animated golden edge + dark-bronze OnGold label
 *     and chevrons; it is the current-selection marker, like the selected
 *     category row on the channels screen;
 *   • channel rows — rest = transparent + GOLD text; focused = lit bronze
 *     glass + the animated golden edge; selected/playing = the TRUE
 *     solid-gold face + OnGold content;
 *   • the EPG-timeline pill — the tab-pill contract: bronze glass + gold
 *     content at rest, solid gold on focus.
 * The mini player's frame, the red Live badge and the EPG “now” cyan row
 * keep their reference colors (video/semantic, not sidebar chrome).
 */
@OptIn(UnstableApi::class)
@Composable
fun ChannelViewScreen(
    playlistId: Long,
    channelKey: String,
    categoryId: String?,
    query: String,
    favoritesOnly: Boolean,
    inPip: Boolean = false,
    onBack: () -> Unit,
    onOpenFullscreen: (playlistId: Long, channelKey: String, categoryId: String?, query: String, favoritesOnly: Boolean) -> Unit,
    /** v1.12.0 — opens the day-tabbed EPG Timeline / Catch-Up screen for
     *  the SELECTED channel (PORTAL playlists). */
    onOpenTimeline: (playlistId: Long, channelKey: String) -> Unit = { _, _ -> },
    /** v2.3.0 — MULTI-SCREEN: the mini player's grid key → the setup with
     *  the SELECTED channel pre-seeded in slot 1. */
    onOpenMultiScreen: (playlistId: Long, channelKey: String) -> Unit = { _, _ -> },
    viewModel: ChannelViewViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s = rememberVuSdp()
    val localView = LocalView.current
    val activity = LocalContext.current as? ComponentActivity

    // Keep the screen on while watching (same contract as the fullscreen player).
    DisposableEffect(Unit) {
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = false }
    }

    // Returning from the fullscreen player: re-register as the engine
    // listener (the fullscreen ViewModel deactivated on its way out).
    DisposableEffect(Unit) {
        viewModel.activate()
        onDispose { viewModel.deactivate() }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // v2.2.0 — the mini player page rides the GLOBAL background
                // (panel slot branding.bgGlobalUrl; stock gradient when blank).
                if (inPip) Modifier.background(Color.Black)
                else Modifier.vuBackground()
            )
    ) {
        if (inPip) {
            // ── Picture-in-Picture: only the video, filling the window ──
            VuMiniPlayer(
                ui = ui,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
                buttonsVisible = false,
                onTap = { /* system handles PiP gestures */ },
                onPiP = {},
                onAspect = {},
                onFullscreen = {}
            )
        } else {
            // ── The reference's ConstraintLayout guidelines, reproduced with
            //    absolute offsets from the FULL screen box (activity_live_play) ──
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val playerLeft = maxWidth * VuSplit.VERTICAL_LINE      // 35%
                val playerRight = maxWidth * VuSplit.VERTICAL_LINE2   // 78%
                val playerTop = maxHeight * VuSplit.HORIZONTAL_LINE1  // 18%
                val playerBottom = maxHeight * VuSplit.HORIZONTAL_LINE2 // 65%

                // ══════════ TOP BAR: ly_back + PremiumLL + tx_system_time ══════════
                // Reference anchoring (activity_live_play.xml): PremiumLL (crown +
                // PREMIUM badge) is THE anchor. ly_back and tx_system_time are
                // vertically CENTERED on it (top/bottom constraints to PremiumLL),
                // and left_lay sits toBottomOf PremiumLL + marginTop 5sdp.
                // We measure the logo ITSELF — its height is intrinsic (35sdp crown
                // + 1dp + badge), so it can NEVER feed back into its own container.
                // (v1.4.9 measured the wrapping Box, but the clock child's height
                // depended on that same measurement → the bar grew +15sdp per pass
                // forever, sinking the channel panel off-screen.)
                var premiumH by remember { mutableStateOf(Dp.Unspecified) }
                val premiumHeight = premiumH.takeUnless { it == Dp.Unspecified }
                    ?: s.d(VuTopBarAnchor.LOGO_HEIGHT_FALLBACK_SDP)
                val premiumBottom = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP) + premiumHeight   // PremiumLL top margin + height

                // ly_back + PremiumLL — start-anchored, 15sdp top margin. The Row
                // wraps the (taller) logo, so ly_back centers on the PremiumLL band
                // exactly like the XML's top/bottom constraints.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP)) // PremiumLL marginTop
                ) {
                    VuBackButton(onClick = onBack)
                    VuPremiumLogo(
                        modifier = Modifier.padding(start = s.d(10)),  // PremiumLL marginStart 10sdp
                        onHeight = { premiumH = it }
                    )
                }

                // tx_system_time — "06:30 PM | May 17 2022" 10sdp, vertically
                // centered on the PremiumLL band (top/bottom constraints), marginEnd 10sdp
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                        .height(premiumHeight)
                        .padding(end = s.d(10))
                ) {
                    VuLiveClock()
                }

                // ══════════ LEFT PANEL: left_lay (main_left_bg) ══════════
                Column(
                    modifier = Modifier
                        .offset(
                            x = s.d(10),
                            y = premiumBottom + s.d(VuTopBarAnchor.LEFT_PANEL_MARGIN_TOP_SDP)
                        ) // left_lay: toBottomOf PremiumLL + marginTop 5sdp
                        .width(playerLeft - s.d(10) - s.d(10))           // end = vertical_line − marginEnd 10sdp
                        .height(maxHeight - premiumBottom - s.d(VuTopBarAnchor.LEFT_PANEL_MARGIN_TOP_SDP))
                        .clip(RoundedCornerShape(topStart = s.d(15), topEnd = s.d(15)))
                        .background(VuPalette.PanelOverlay)
                ) {
                    // ly_category — live_category_bg pill: 25sdp tall, margin 5sdp,
                    // chevrons 10×20sdp, centered label 10sdp
                    VuCategoryBar(
                        ui = ui,
                        onPrev = { viewModel.switchCategory(false) },
                        onNext = { viewModel.switchCategory(true) },
                        modifier = Modifier
                            .padding(s.d(5))
                            .fillMaxWidth()
                            .height(s.d(25))
                    )
                    // recycler_channels — margins 7sdp horizontal, 5sdp top
                    VuChannelList(
                        ui = ui,
                        onSelect = { viewModel.selectChannel(it) },
                        modifier = Modifier
                            .padding(horizontal = s.d(7))
                            .weight(1f)
                    )
                }

                // ══════════ MINI PLAYER: ly_surface ══════════
                VuMiniPlayer(
                    ui = ui,
                    viewModel = viewModel,
                    modifier = Modifier
                        .offset(x = playerLeft, y = playerTop)
                        .width(playerRight - playerLeft)
                        .height(playerBottom - playerTop),
                    buttonsVisible = true,
                    onTap = {
                        val ch = ui.channel
                        onOpenFullscreen(
                            playlistId,
                            ch?.key ?: channelKey,
                            categoryId,
                            query,
                            favoritesOnly
                        )
                    },
                    onPiP = { enterPiP(activity) },
                    onAspect = { viewModel.cycleAspect() },
                    onFullscreen = {
                        val ch = ui.channel
                        onOpenFullscreen(
                            playlistId,
                            ch?.key ?: channelKey,
                            categoryId,
                            query,
                            favoritesOnly
                        )
                    },
                    // v2.3.0 — MULTI-SCREEN: park this stream, hand the
                    // selected channel to the setup (slot 1).
                    onMultiScreen = {
                        viewModel.pauseForMultiScreen()
                        onOpenMultiScreen(playlistId, ui.channel?.key ?: channelKey)
                    }
                )

                // ══════════ INFO COLUMN: ly_live_info (right of the player) ══════════
                VuLiveInfo(
                    ui = ui,
                    modifier = Modifier
                        .offset(x = playerRight + s.d(10), y = playerTop)  // marginStart 10sdp
                        .width(maxWidth - playerRight - s.d(10) - s.d(10))    // marginEnd 10sdp
                        .height(playerBottom - playerTop)
                )

                // ══════════ EPG LIST: recyclerEpg (below the player) ══════════
                Column(
                    modifier = Modifier
                        .offset(x = playerLeft, y = playerBottom + s.d(10)) // marginTop 10sdp
                        .width(maxWidth - playerLeft - s.d(15))             // marginEnd 15sdp
                        .height(maxHeight - playerBottom - s.d(10) - s.d(10)) // marginBottom 10sdp
                ) {
                    // v1.12.0 — the timeline entry (PORTAL + v1.12.1 XTREAM):
                    // opens the day-tabbed EPG Timeline / Catch-Up screen for
                    // the selected channel. Right-aligned, above the program list.
                    if (ui.playlist?.type == "PORTAL" || ui.playlist?.type == "XTREAM") {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = s.d(4), bottom = s.d(4))
                        ) {
                            var tlFocused by remember { mutableStateOf(false) }
                            // v1.19.4 — the tab-pill contract (BrowseScreen's
                            // VuTabPill): warm bronze glass + GOLD content + the
                            // animated golden edge at rest; TRUE solid gold +
                            // dark-bronze content when focused.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .vuGoldenBorder(cornerRadius = s.d(10), strokeWidth = s.d(1.2f), focused = tlFocused)
                                    .clip(RoundedCornerShape(s.d(10)))
                                    .then(
                                        if (tlFocused) Modifier.vuGoldFace()
                                        else Modifier.background(Color(0x66301F08))   // warm bronze glass
                                    )
                                    .onFocusChanged { tlFocused = it.isFocused }
                                    .focusable()
                                    .clickable {
                                        val ch = ui.channel
                                        if (ch != null) onOpenTimeline(playlistId, ch.key)
                                    }
                                    .padding(horizontal = s.d(8), vertical = s.d(4))
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.vu_image_epg_timeline),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(
                                        if (tlFocused) VuGold.OnGold else VuGold.Text
                                    ),
                                    modifier = Modifier.size(width = s.d(10), height = s.d(10))
                                )
                                Spacer(Modifier.width(s.d(5)))
                                Text(
                                    stringResource(R.string.vu_epg_timeline_title),
                                    color = if (tlFocused) VuGold.OnGold else VuGold.Text,
                                    fontSize = s.t(7),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    VuEpgList(
                        ui = ui,
                        modifier = Modifier
                            .weight(1f)
                    )
                }

                // ══════════ v1.12.0 — PARENTAL PIN GATE ══════════
                // v1.19.15 — an ENTRY gate's dismiss (wrong-PIN give-up, scrim
                // tap, back key) LEAVES the page: the user declined the adult
                // section they were entering, so returning to the caller beats
                // a dead page — and nothing playback-related can leak past the
                // gate (a dismissed entry never started a stream, and leaving
                // releases the session). A SWITCH gate's dismiss keeps the old
                // contract: drop the pending switch, stay where you were.
                ui.pinGateFor?.let { gateId ->
                    val catName = ui.categories
                        .firstOrNull { it.categoryId == gateId }?.name ?: ""
                    com.superz.iptvplayer.ui.components.VuPinGateDialog(
                        categoryName = catName,
                        onResult = { ok ->
                            if (ok) viewModel.onPinVerified()
                            else if (ui.pinGateEntry) onBack()
                            else viewModel.onPinDismissed()
                        }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Reference widgets (activity_live_play.xml + item_*.xml, verbatim sizes)
// ═══════════════════════════════════════════════════════════════════

/** tx_system_time — "06:30 PM | May 17 2022" (10sdp, ticks on the minute).
 *  v1.5.0 — the hour cycle follows Settings → Time Format (the reference's
 *  TimeFormatDlgFragment; default 24h, the dialog's checked radio). */
@Composable
private fun VuLiveClock(modifier: Modifier = Modifier) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L)
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val twelveHour = remember {
        com.superz.iptvplayer.ui.settings.VuSettingsPrefs.timeFormat(context) == "12"
    }
    val timeFmt = remember(locale, twelveHour) {
        SimpleDateFormat(if (twelveHour) "hh:mm a" else "HH:mm", locale)
    }
    val dateFmt = remember(locale) { SimpleDateFormat("MMM d yyyy", locale) }
    Text(
        "${timeFmt.format(Date(nowMs))} | ${dateFmt.format(Date(nowMs))}",
        color = VuPalette.White,
        fontSize = s.t(10),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * ly_category — v1.19.4: the sidebar's category selector became the
 * app's golden current-selection marker.
 *
 * v2.2.0 — THE PLAN-CARD CONTRACT (user directive: the mini player
 * page's section-toggle button + its two sidebar chevrons wear the plan
 * cards' design "حرفياً"): the bar is now a resting PLAN CARD — dark glass
 * face + the animated golden ring always on + GOLD label — while the two
 * chevrons are plan-card CHIPS (dark glass rest, warm-bronze glass + 1.6dp
 * ring on focus, gold arrows). The solid-gold current-selection marker
 * moved aside: the label still names the live section, but in the plans'
 * language. prev/next keep their physical directions (VuCategoryChevron).
 */
@Composable
private fun VuCategoryBar(
    ui: ChannelViewViewModel.UiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    val label = if (ui.selectedCategoryId == null) {
        stringResource(R.string.all_channels)
    } else {
        ui.categories.firstOrNull { it.categoryId == ui.selectedCategoryId }?.name
            ?: stringResource(R.string.untitled_category)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .vuPlanCardStyle(cornerRadius = s.d(12), focused = false)   // resting plan card
    ) {
        // btn_left: image_down chevron (marginStart 7sdp) — a plan-card chip
        Box(
            modifier = Modifier
                .padding(start = s.d(7))
                .size(width = s.d(16), height = s.d(20))
        ) {
            VuCategoryChevron(
                pointsLeft = true,
                contentDescription = stringResource(R.string.cd_prev_category),
                onClick = onPrev,
                tint = VuGold.Text
            )
        }
        // txt_category — centered 10sdp, GOLD on the dark glass
        Text(
            label,
            color = VuGold.Text,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // btn_right: chevron (marginEnd 5sdp) — a plan-card chip
        Box(
            modifier = Modifier
                .padding(end = s.d(5))
                .size(width = s.d(16), height = s.d(20))
        ) {
            VuCategoryChevron(
                pointsLeft = false,
                contentDescription = stringResource(R.string.cd_next_category),
                onClick = onNext,
                tint = VuGold.Text
            )
        }
    }
}

/** recycler_channels + item_channel_list.xml rows. */
@Composable
private fun VuChannelList(
    ui: ChannelViewViewModel.UiState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    val listState = rememberLazyListState()

    // Keep the playing channel visible (instant, no animation — large lists).
    LaunchedEffect(ui.channel?.key, ui.channels.size) {
        val idx = ui.channels.indexOfFirst { it.key == ui.channel?.key }
        if (idx > 0) listState.scrollToItem(idx)
    }

    LazyColumn(
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = s.d(5), bottom = s.d(10)),
        modifier = modifier
    ) {
        itemsIndexed(ui.channels, key = { _, ch -> ch.key }) { _, ch ->
            VuChannelRow(
                channel = ch,
                selected = ch.key == ui.channel?.key,
                nowTitle = ch.epgLookupId()?.let { ui.nowByStreamId[it]?.title },
                onClick = { onSelect(ch.key) }
            )
        }
    }
}

/**
 * item_channel_list.xml: padding 5sdp — num 7sdp (right-aligned), logo
 * 40×25sdp (marginStart 5sdp), name 9sdp max 2 lines (marginStart 7sdp),
 * program 7sdp single line.
 *
 * v1.4.8 (user request): the selected/playing row — and the row focused
 * with the D-pad — is marked with a highlight pill.
 *
 * v1.19.4 — the channels-screen golden contract replaces the purple pill.
 *
 * v2.2.0 — THE PLAN-CARD CONTRACT, list-row edition (user directive: the
 * channel-switch rows of the mini player page wear the plan cards'
 * design): every row is a little plan card — DARK GLASS resting face +
 * static gold hairline + GOLD text; the focused or selected/playing row
 * lights up with the warm-bronze glass face + the ANIMATED golden ring
 * (1.6dp). The animated border composes ONLY on lit rows — a channel
 * list can hold hundreds of rows, and a weak TV box must never spin them
 * all at once.
 */
@Composable
private fun VuChannelRow(
    channel: Channel,
    selected: Boolean,
    nowTitle: String?,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    // gold content on BOTH faces — the plan-card text contract
    val nameColor = VuGold.Text
    val programColor = VuGold.Text.copy(alpha = 0.62f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(s.d(5))
            .vuPlanCardRowStyle(cornerRadius = s.d(5), focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        // txt_num — 7sdp, gravity end
        Text(
            channel.num.toString(),
            color = VuGold.Text,
            fontSize = s.t(7),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(s.d(30))
        )
        // image_channel — 40×25sdp (marginStart 5sdp)
        ChannelLogo(
            logoUrl = channel.logo,
            name = channel.name,
            sizeDp = 0,
            cornerDp = 0,
            modifier = Modifier
                .padding(start = s.d(5))
                .width(s.d(40))
                .height(s.d(25))
        )
        // txt_name (9sdp, 2 lines) + txt_program_name (7sdp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = s.d(7))
        ) {
            Text(
                channel.name,
                color = nameColor,
                fontSize = s.t(9),
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                nowTitle ?: stringResource(R.string.no_info),
                color = programColor,
                fontSize = s.t(7),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * ly_surface — the mini player box: #33707070 glass + padding 2sdp.
 * The engine surface + gestures below are the project's own playback
 * integration, kept VERBATIM.
 *
 * v1.4.8 (user request): the video now FILLS the frame edge-to-edge —
 * the Exo PlayerView is switched to RESIZE_MODE_ZOOM (aspect kept,
 * overflow cropped) so no empty bars remain inside the bordered frame.
 * The fullscreen player keeps its own layout XML's "fit" mode untouched.
 *
 * v1.19.8 (user request: "اطاره بالابيض من المفروض أن يكون بالأثير الذهبي"):
 * the frame IS the golden aura now — the app's signature animated
 * golden border (static base ring + the two orbiting light beads)
 * replaces the old 0.5dp white stroke. The border composes BEFORE
 * .clip() so the ring rides the outline unclipped, exactly like every
 * other golden element in the app.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VuMiniPlayer(
    ui: ChannelViewViewModel.UiState,
    viewModel: ChannelViewViewModel,
    modifier: Modifier = Modifier,
    buttonsVisible: Boolean,
    onTap: () -> Unit,
    onPiP: () -> Unit,
    onAspect: () -> Unit,
    onFullscreen: () -> Unit,
    onMultiScreen: () -> Unit = {}
) {
    val s = rememberVuSdp()
    Box(
        modifier = modifier
            // v1.19.8 — the GOLDEN AURA frame (was: 0.5dp white stroke).
            // The animated border draws over the outline's own edges, so
            // it must come BEFORE .clip() in the chain.
            .vuGoldenBorder(cornerRadius = s.d(2), strokeWidth = s.d(1.0f))
            .clip(RoundedCornerShape(s.d(2)))
            .background(VuPalette.PanelOverlay)
            .padding(s.d(2))
    ) {
        // ── The ONE active video surface (engine-selected) ──
        // Same architecture as the fullscreen player: texture PlayerView for
        // EXO (renders in the UI layer → overlays always on top), official
        // VLCVideoLayout composed only while the VLC engine runs.
        when (ui.engine) {
            Engine.EXO -> AndroidView(
                factory = { ctx ->
                    val view = LayoutInflater.from(ctx)
                        .inflate(R.layout.player_view_texture, null) as PlayerView
                    view.useController = false
                    // Fill the ly_surface frame — no letterbox gaps (v1.4.8).
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    viewModel.attachExoView(view)
                    view
                },
                update = { it.player = viewModel.activeExoPlayer },
                onRelease = { viewModel.detachExoView(it) },
                modifier = Modifier.fillMaxSize()
            )
            Engine.VLC -> AndroidView(
                factory = { ctx ->
                    org.videolan.libvlc.util.VLCVideoLayout(ctx)
                        .also { viewModel.attachVlcView(it) }
                },
                onRelease = { viewModel.detachVlcView(it) },
                modifier = Modifier.fillMaxSize()
            )
            null -> Unit
        }

        // ── Tap layer: expand to fullscreen ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
        )

        // ── progress_bar — centered 20×20sdp while buffering (v1.19.8:
        //    gold, matching the fullscreen player's spinner) ──
        if (ui.buffering && !ui.fatal) {
            CircularProgressIndicator(
                color = VuGold.Gold,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(s.d(20))
            )
        }

        // ── Fatal error (project-owned, kept) ──
        if (ui.fatal) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(s.d(2)))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = s.d(14), vertical = s.d(10))
            ) {
                Text(
                    stringResource(R.string.playback_error),
                    color = VuPalette.White,
                    fontSize = s.t(12),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(s.d(4)))
                Text(
                    stringResource(R.string.retry),
                    color = VuPalette.Cyan,
                    fontSize = s.t(11),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(s.d(8)))
                        .clickable { viewModel.retry() }
                        .padding(horizontal = s.d(10), vertical = s.d(4))
                )
            }
        }

        // ── ChannelDetails: bottom-right buttons — imageVideoFit 25×25sdp
        //    (ic_circle_gray_bg: transparent → lb_grey #888888 oval on focus),
        //    btn_pip 25×25sdp + btn_full 30×30sdp (TRANSPARENT backgrounds,
        //    ic_pip / ic_full_screen tinted #dadada), padding 5dp, marginEnd 5sdp.
        //    v2.3.0 — the MULTI-SCREEN key joins the row (vu_ic_multiscreen,
        //    25×25sdp, the same transparent/tinted idiom). ──
        if (buttonsVisible) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(s.d(5)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(s.d(5))
            ) {
                VuPlayerIconButton(
                    iconRes = R.drawable.vu_ic_multiscreen,
                    desc = stringResource(R.string.multiscreen_entry_cd),
                    size = s.d(25),
                    iconSize = s.d(15),
                    circleBackground = false,
                    onClick = onMultiScreen
                )
                VuPlayerIconButton(
                    iconRes = R.drawable.vu_ic_video_size,
                    desc = stringResource(R.string.cd_aspect),
                    size = s.d(25),
                    iconSize = s.d(15),
                    circleBackground = true,
                    onClick = onAspect
                )
                VuPlayerIconButton(
                    iconRes = R.drawable.vu_ic_pip,
                    desc = stringResource(R.string.cd_pip),
                    size = s.d(25),
                    iconSize = s.d(15),
                    circleBackground = false,
                    onClick = onPiP
                )
                VuPlayerIconButton(
                    iconRes = R.drawable.vu_ic_full_screen,
                    desc = stringResource(R.string.cd_fullscreen),
                    size = s.d(30),
                    iconSize = s.d(20),
                    circleBackground = false,
                    onClick = onFullscreen
                )
            }
        }
    }
}

/**
 * imageVideoFit / btn_pip / btn_full — the reference's exact buttons: 5dp
 * padding inside the sdp-sized box, fitCenter glyph. imageVideoFit carries
 * ic_circle_gray_bg (transparent default → lb_grey #888888 oval when
 * focused/pressed); btn_pip and btn_full have TRANSPARENT backgrounds and
 * icons tinted #dadada.
 */
@Composable
private fun VuPlayerIconButton(
    iconRes: Int,
    desc: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    circleBackground: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(
                if (circleBackground) {
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (focused) Modifier.background(VuPalette.Grey)
                            else Modifier.background(Color.Transparent)
                        )
                } else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(5.dp)                     // literal 5dp in the XML
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(iconRes),
            contentDescription = desc,
            colorFilter = if (circleBackground) null
            else androidx.compose.ui.graphics.ColorFilter.tint(VuPalette.IconGrey),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * ly_live_info — right of the player, aligned to its top/bottom:
 * red "Live TV" badge (radius 12sdp, padding 3sdp/10sdp, 7sdp text) +
 * channel name 9sdp + program name 6sdp + program time 7sdp — all centered.
 */
@Composable
private fun VuLiveInfo(
    ui: ChannelViewViewModel.UiState,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = modifier
    ) {
        // str_live — "Live TV" red pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(s.d(12)))
                .background(VuPalette.LiveRed)
                .padding(horizontal = s.d(10), vertical = s.d(3))
        ) {
            Text(
                stringResource(R.string.live_badge),
                color = VuPalette.White,
                fontSize = s.t(7),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        // txt_channel_name — 9sdp centered (marginTop 3sdp)
        Text(
            ui.channel?.name ?: "—",
            color = VuPalette.White,
            fontSize = s.t(9),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = s.d(3))
        )
        // txt_program_name — 6sdp centered
        val now = ui.channel?.epgLookupId()?.let { ui.nowByStreamId[it] }
        Text(
            now?.title ?: stringResource(R.string.no_info),
            color = VuPalette.White,
            fontSize = s.t(6),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        // txt_program_time — 7sdp centered (marginTop 2sdp)
        Text(
            now?.let { "${it.startMs.toEpgTimeLabel()} - ${it.endMs.toEpgTimeLabel()}" }
                ?: stringResource(R.string.no_info),
            color = VuPalette.White,
            fontSize = s.t(7),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = s.d(2))
        )
    }
}

/**
 * recyclerEpg + item_epg_list.xml rows: line 1 = "time | channel | title"
 * (9sdp), line 2 = description (7sdp, 3 lines, marginTop 2sdp). The CURRENT
 * program row is #03dffe (button_vpn_end_color — the reference colors row 0
 * this way); the rest are #888888 (lb_grey).
 */
@Composable
private fun VuEpgList(
    ui: ChannelViewViewModel.UiState,
    modifier: Modifier = Modifier
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.epgPrograms) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    when {
        // LLEPGNotAvailable — yellow "Unavailable -" + the message
        !ui.epgAvailable -> VuEpgUnavailable()
        ui.epgLoading -> Box(modifier)   // silent while fetching (reference shows nothing)
        else -> {
            val programs = ui.epgPrograms
                .filter { it.endMs > nowMs - 3_600_000L }
                .take(20)
            if (programs.isEmpty()) {
                VuEpgUnavailable()
            } else {
                val currentIdx = programs.indexOfFirst { it.isNow(nowMs) }
                LazyColumn(modifier = modifier) {
                    itemsIndexed(programs, key = { i, p -> "epg-${p.startMs}-$i" }) { i, program ->
                        VuEpgRow(
                            program = program,
                            channelName = ui.channel?.name ?: "",
                            isCurrent = i == currentIdx,
                            nowMs = nowMs
                        )
                    }
                }
            }
        }
    }
}

/** One item_epg_list row. */
@Composable
private fun VuEpgRow(
    program: EpgProgram,
    channelName: String,
    isCurrent: Boolean,
    nowMs: Long
) {
    val s = rememberVuSdp()
    val textColor = if (isCurrent) VuPalette.Cyan else VuPalette.Grey
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = s.d(3))
    ) {
        // txt_info — "09:30pm - 10:30pm | Channel | Title" (9sdp)
        Text(
            "${program.startMs.toEpgTimeLabel()} - ${program.endMs.toEpgTimeLabel()} | $channelName | ${program.title}",
            color = textColor,
            fontSize = s.t(9),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // txt_description — 7sdp, 3 lines (marginTop 2sdp)
        Text(
            program.description ?: stringResource(R.string.no_info),
            color = textColor,
            fontSize = s.t(7),
            lineHeight = s.t(9),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = s.d(2))
        )
    }
}

/** LLEPGNotAvailable — "Unavailable -" gold 8sdp (v1.19.6: joined the gold
 *  identity — was reference-yellow, one of the "yellowest" spots on this
 *  screen per the user's screenshot) + message 7sdp white. */
@Composable
private fun VuEpgUnavailable() {
    val s = rememberVuSdp()
    Column(
        modifier = Modifier.padding(start = s.d(10), top = s.d(10))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.vu_unavailable),
                color = VuGold.Text,
                fontSize = s.t(8),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(s.d(5)))
            Text(
                stringResource(R.string.epg_unavailable),
                color = VuPalette.White,
                fontSize = s.t(7),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── PiP entry (activity-level feature; manifest-safe, engine untouched) ──

private fun enterPiP(activity: ComponentActivity?) {
    if (activity == null) return
    try {
        val pipOk = activity.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
        )
        if (!pipOk) return
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        )
    } catch (_: Throwable) {
        // PiP refused (manufacturer restrictions) — silently ignored.
    }
}

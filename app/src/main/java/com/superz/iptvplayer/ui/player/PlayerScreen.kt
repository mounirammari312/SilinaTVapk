package com.superz.iptvplayer.ui.player

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import com.superz.iptvplayer.data.recording.RecorderEngine
import com.superz.iptvplayer.player.Engine
import com.superz.iptvplayer.player.SmartPlayer
import com.superz.iptvplayer.player.VodSidebarBuilder
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.components.ChannelRow
import com.superz.iptvplayer.ui.components.PremiumUpsellDialog
import com.superz.iptvplayer.ui.login.LoginField
import com.superz.iptvplayer.ui.theme.ErrorRed
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Fullscreen player: hybrid engine surface + instant zap overlay +
 * auto-hiding glass controls + slide-out channel sidebar.
 *
 * v1.15.0 — ICON REORGANIZATION (the reference player's organization, our
 * design language):
 *   • TOP bar: feature icons — channel list, SUBTITLES (the copied
 *     reference feature), favorites.
 *   • BOTTOM bar: ONLY channel switching (prev/next) + play/pause —
 *     centered — plus the settings gear at the right edge.
 *   • Settings gear opens a right slide-in panel: Screen Size
 *     (Fit/Fill/Zoom — was the old aspect icon) + Audio Tracks (was the
 *     old audio icon).
 *   • The channel logo in the zap overlay floats PLAIN (no rectangular
 *     glass card behind it) — the reference's clean look.
 *
 * v1.15.0 — SUBTITLE FEATURE (engine copied 100% from the reference):
 * the CC icon opens a right slide-in language picker (Off + 15 languages);
 * the ViewModel resolves the subtitle online (Cinemeta → OpenSubtitles →
 * Stremio) and side-loads the local .srt into the active ExoPlayer.
 *
 * v1.16.0 — SUBTITLE RENDERING FIX: the side-loaded language now overrides
 * the general "Active Subtitle" toggle (SubtitleActivation) and forces the
 * text track type ENABLED — before, the toggle's OFF default disabled text
 * tracks entirely and the .srt never rendered while the UI said "on".
 *
 * v1.16.0 — RECORDING (engine copied 100% from the reference): the REC icon
 * in the top bar tees every played byte into an .mp4 in Downloads
 * (RecordingDataSource); a pulsing REC pill shows elapsed time + size; the
 * recording auto-finalizes on zap / engine fallback / stream cut / exit.
 *
 * v1.19.10 — THE PLAYER CONTROLS REORGANIZED (user directive, the
 * reference app's button sizes):
 *   • every top-bar button adopts the REFERENCE SIZE (32dp circle /
 *   16dp icon — PlayerActivity's control boxes);
 *   • the two zap buttons moved onto the player's SIDE edge, laid out
 *   HORIZONTALLY and labeled "+CH" / "CH-" like a remote's channel
 *   keys — their purpose is self-evident now (size UNCHANGED at 42dp,
 *   per directive);
 *   • the bottom band became ONE row, lowered to the very bottom: the
 *   golden play/pause button (size UNCHANGED, per directive) FIRST,
 *   before the start of the time bar's timeline, then the bar filling
 *   the rest;
 *   • the TIME readout and the SETTINGS gear moved UP into the top
 *   button row (the gear in the reference's exact 40dp/8dp-corner/
 *   18dp-icon size);
 *   • the time bar now shows for CHANNELS too (the reference's player
 *   renders its scrubber on live content — the live window's own
 *   timeline), and the indicator color RETURNED TO RED — the reference's
 *   exact NetflixRed #E50914 — on movies, series AND live alike.
 *
 * IRON RULE untouched: the hybrid engine (SmartPlayer) is not modified —
 * everything above lives in the UI/ViewModel layer.
 */

/** The reference's subtitle language list (PlayerActivity, verbatim). */
private data class SubtitleLanguage(val code: String, val label: String, val flag: String)

private val SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("ar", "Arabic", "\uD83C\uDDF8\uD83C\uDDE6"),
    SubtitleLanguage("en", "English", "\uD83C\uDDEC\uD83C\uDDE7"),
    SubtitleLanguage("fr", "French", "\uD83C\uDDEB\uD83C\uDDF7"),
    SubtitleLanguage("es", "Spanish", "\uD83C\uDDEA\uD83C\uDDE8"),
    SubtitleLanguage("de", "German", "\uD83C\uDDE9\uD83C\uDDEA"),
    SubtitleLanguage("tr", "Turkish", "\uD83C\uDDF9\uD83C\uDDF7"),
    SubtitleLanguage("pt", "Portuguese", "\uD83C\uDDE7\uD83C\uDDF7"),
    SubtitleLanguage("nl", "Dutch", "\uD83C\uDDF3\uD83C\uDDF1"),
    SubtitleLanguage("it", "Italian", "\uD83C\uDDEE\uD83C\uDDF9"),
    SubtitleLanguage("ru", "Russian", "\uD83C\uDDF7\uD83C\uDDFA"),
    SubtitleLanguage("hi", "Hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
    SubtitleLanguage("zh", "Chinese", "\uD83C\uDDE8\uD83C\uDDF3"),
    SubtitleLanguage("ja", "Japanese", "\uD83C\uDDEF\uD83C\uDDF5"),
    SubtitleLanguage("ko", "Korean", "\uD83C\uDDF0\uD83C\uDDF4"),
    SubtitleLanguage("id", "Indonesian", "\uD83C\uDDEE\uD83C\uDDE9")
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    playlistId: Long,
    channelKey: String,
    categoryId: String?,
    query: String,
    favoritesOnly: Boolean,
    onBack: () -> Unit,
    onOpenPremium: () -> Unit = {},
    // v2.3.0 — MULTI-SCREEN: the golden grid button's route — the
    // watching channel rides along and lands in slot 1 of the setup.
    onOpenMultiScreen: (channelKey: String) -> Unit = {},
    viewModel: PlayerViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val localView = LocalView.current

    var controlsVisible by remember { mutableStateOf(true) }
    var zapOverlayVisible by remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var sidebarQuery by remember { mutableStateOf("") }
    // v1.15.0 — the two right slide-in panels + the screen-size selection.
    var settingsOpen by remember { mutableStateOf(false) }
    var subtitleOpen by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // v1.19.9 — THE TIME BAR (the reference's NetflixBottomScrubber, in
    // VuGold): while the finger is down the bar tracks the FINGER, not
    // the player; commit rides viewModel.seekTo().
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }

    // Keep screen on while playing. Orientation is locked app-wide in the
    // manifest (landscape 16:9, like the reference app) — no per-screen
    // orientation override: forcing it here previously triggered an activity
    // recreation mid-playback, which destroyed the video surface while audio
    // kept playing (the "black player with sound" bug).
    DisposableEffect(Unit) {
        localView.keepScreenOn = true
        onDispose {
            localView.keepScreenOn = false
        }
    }

    // Zap overlay: show instantly on channel change, auto-hide after 3.5s
    LaunchedEffect(ui.channel?.key) {
        if (ui.channel != null) {
            zapOverlayVisible = true
            delay(3500)
            zapOverlayVisible = false
        }
    }

    // Controls auto-hide — paused while any overlay panel is open, while
    // the user is SCRUBBING the time bar (hiding the bar under the finger
    // mid-drag is the classic amateur bug), and v1.19.10 while the video
    // itself is PAUSED (the reference's own gate: a parked player — the
    // new open-parked resume, or any manual pause — keeps its controls on
    // screen instead of hiding the play button the user is about to need).
    LaunchedEffect(controlsVisible, ui.isPlaying, sidebarOpen, settingsOpen, subtitleOpen, isScrubbing) {
        if (controlsVisible && ui.isPlaying && !sidebarOpen && !settingsOpen && !subtitleOpen && !isScrubbing) {
            delay(4000)
            controlsVisible = false
        }
    }

    // v2.2.4 — THE CONTENT SIDEBAR's lazy trigger: the moment the list
    // opens, the ViewModel loads what the list REPRESENTS (the full
    // series while bingeing, the movies page during a movie). Until the
    // load lands, the registered list shows — episodes of the current
    // season, or the playing movie's own row.
    LaunchedEffect(sidebarOpen) {
        if (sidebarOpen) viewModel.onSidebarOpened()
    }

    // Back stack: subtitle panel → settings panel → sidebar → leave
    BackHandler(enabled = subtitleOpen) { subtitleOpen = false }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = sidebarOpen) { sidebarOpen = false }
    BackHandler(enabled = !sidebarOpen && !settingsOpen && !subtitleOpen) { onBack() }

    fun applyResizeMode(mode: Int) {
        resizeMode = mode
        // Applied on the PlayerView the UI layer owns — SmartPlayer is not
        // touched (iron rule); its cycleResizeMode() stays for the channel
        // view's own control bar.
        playerView?.resizeMode = mode
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Layer 0: the ONE active video surface ──
        // EXACTLY ONE surface is composed at any moment, chosen by the active
        // engine. The old design stacked a full-screen VLCVideoLayout ON TOP
        // of the ExoPlayer PlayerView — VLCVideoLayout internally contains an
        // opaque SurfaceView whose empty (black) hardware plane composites
        // ABOVE ExoPlayer's video surface: black screen + audio only, with a
        // brief flash of video when the surfaces were torn down on exit.
        //   • EXO → PlayerView with a TEXTURE_VIEW surface (reference-app
        //     architecture): renders inside the app's UI layer, so every
        //     Compose overlay draws on top — zero z-order conflicts.
        //   • VLC → VLCVideoLayout (VLC's official container), composed ONLY
        //     while the VLC engine is actually running — never stacked over
        //     ExoPlayer's video.
        when (ui.engine) {
            Engine.EXO -> AndroidView(
                factory = { ctx ->
                    val view = LayoutInflater.from(ctx)
                        .inflate(R.layout.player_view_texture, null) as PlayerView
                    view.useController = false
                    view.resizeMode = resizeMode
                    playerView = view
                    viewModel.attachExoView(view)
                    view
                },
                update = { it.player = viewModel.activeExoPlayer },
                onRelease = {
                    viewModel.detachExoView(it)
                    if (playerView === it) playerView = null
                },
                modifier = Modifier.fillMaxSize()
            )
            Engine.VLC -> AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { viewModel.attachVlcView(it) }
                },
                onRelease = { viewModel.detachVlcView(it) },
                modifier = Modifier.fillMaxSize()
            )
            null -> Unit
        }

        // ── Tap layer: close an open panel first, else toggle controls ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settingsOpen, subtitleOpen) {
                    detectTapGestures(
                        onTap = {
                            when {
                                settingsOpen -> settingsOpen = false
                                subtitleOpen -> subtitleOpen = false
                                else -> controlsVisible = !controlsVisible
                            }
                        }
                    )
                }
        )

        // ── Zap badge — compact channel identity, ONLY while the controls
        //    are hidden (v1.19.8: when the top bar is visible it already
        //    carries the name — exactly ONE channel-name display exists at
        //    any moment; the old big gold-framed box stacked a second copy
        //    of the same name plus engine/ttff debug lines on top of it) ──
        AnimatedVisibility(
            visible = zapOverlayVisible && !controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 16.dp)
        ) {
            ZapOverlay(channel = ui.channel)
        }

        // ── Buffering indicator ──
        if (ui.buffering && !ui.fatal) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(color = VuGold.Gold, strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    engineLabel(ui.engine),
                    color = VuGold.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                ui.bufferingPercent?.let { pct ->
                    Spacer(Modifier.height(2.dp))
                    Text("$pct%", color = TextSecondary, fontSize = 11.sp)
                }
                if (ui.attemptIndex > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.trying_backup),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── Fatal error ──
        if (ui.fatal) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassSurface.copy(alpha = 0.85f))
                    .border(1.dp, ErrorRed.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                Text(
                    stringResource(R.string.playback_error),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${ui.channel?.name ?: ""}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    GlassPillButton(
                        label = stringResource(R.string.retry),
                        highlight = true,
                        onClick = { viewModel.retry() }
                    )
                    Spacer(Modifier.width(10.dp))
                    GlassPillButton(
                        label = stringResource(R.string.channel_list),
                        onClick = { sidebarOpen = true }
                    )
                }
            }
        }

        // ── Top bar: channel identity + FEATURE icons (list, subtitles,
        //    favorites) — the reference's organization, our glass style ──
        AnimatedVisibility(
            visible = controlsVisible && !sidebarOpen,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // v1.19.10 — the reference's 32dp control box (16dp glyph)
                GlassIconButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.cancel),
                    size = 32.dp
                ) { onBack() }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ui.channel?.name ?: ui.playlist?.name ?: stringResource(R.string.app_name),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Second line — the ACTIVE SUBTITLE only (v1.19.8: no
                    // subtitle → no second line. The old engine-label
                    // fallback duplicated the loading state's own "EXO/VLC"
                    // text and cluttered the header — the reference players
                    // keep their top bar clean).
                    val subLabel = ui.activeSubtitleLabel
                    if (subLabel != null) {
                        val subFlag = SUBTITLE_LANGUAGES.firstOrNull { it.code == ui.activeSubtitleCode }?.flag
                        Text(
                            "${subFlag ?: ""} $subLabel",
                            color = VuGold.Text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Channel list (the reference keeps its list icon top-right)
                GlassIconButton(
                    Icons.AutoMirrored.Filled.List,
                    stringResource(R.string.channel_list),
                    size = 32.dp
                ) { sidebarOpen = true }
                Spacer(Modifier.width(6.dp))
                // v2.3.0 — MULTI-SCREEN (الشاشات المتعددة): the golden grid
                // key, LIVE channels only — one tap parks this stream and
                // opens the pick-and-place setup with THIS channel already
                // seated in slot 1 (the grid grows around what is playing).
                if (!ui.isVod && !channelKey.startsWith("saved:")) {
                    GlassIconButton(
                        Icons.Filled.GridView,
                        stringResource(R.string.multiscreen_entry_cd),
                        size = 32.dp,
                        tint = VuGold.Text.copy(alpha = 0.55f)
                    ) {
                        viewModel.pauseForMultiScreen()
                        onOpenMultiScreen(ui.channel?.key ?: channelKey)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // Subtitles — cyan when active, spinner while resolving
                if (ui.isSubtitleLoading) {
                    // v2.2.3 — the resolving spinner rides the plan-card
                    // circular chip (dark glass + gold hairline ring).
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .vuPlanCardStyle(cornerRadius = 16.dp, focused = false),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = VuGold.Gold,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    GlassIconButton(
                        Icons.Filled.ClosedCaption,
                        stringResource(R.string.subtitles),
                        size = 32.dp,
                        // v2.2.3 — plan-card dimmed-gold rest (was grey),
                        // full gold while a subtitle track is active.
                        tint = if (ui.activeSubtitleCode != null) VuGold.Text
                        else VuGold.Text.copy(alpha = 0.55f)
                    ) { subtitleOpen = true }
                }
                Spacer(Modifier.width(6.dp))
                // v1.18.1 — the AUTO-SAVE toggle (user directive: the setting
                // moved from Settings → General INTO the player, so the user
                // picks any movie/series they like and saves it without
                // leaving the playback). Floppy icon; cyan + filled = ON.
                // Toggling ON mid-watch starts saving the CURRENT movie
                // immediately (the engine downloads the whole file anyway);
                // OFF stops an auto-started save. Live channels keep REC.
                GlassIconButton(
                    icon = if (ui.autoSaveOn) Icons.Filled.Save else Icons.Outlined.Save,
                    contentDescription = stringResource(R.string.vu_autosave),
                    size = 32.dp,
                    // v2.2.3 — plan-card dimmed-gold rest, full gold = ON.
                    tint = if (ui.autoSaveOn) VuGold.Text
                    else VuGold.Text.copy(alpha = 0.55f)
                ) { viewModel.toggleAutoSave() }
                Spacer(Modifier.width(6.dp))
                // Recording — the reference's REC circle metaphor (red dot
                // idle → stop square while recording), our glass idiom.
                // v1.16.0: engine copied, design ours.
                // v1.18.0 — WATCH = DOWNLOAD: on a movie/episode the button
                // became DOWNLOAD; live channels kept the red REC metaphor.
                // v1.19.9 (user directive) — the VOD DOWNLOAD button MOVED
                // OUT of the player: it now lives on the movie/series INFO
                // page, next to Play (RecorderEngine is the same engine;
                // the button is just no longer HERE). LIVE channels keep
                // their REC button — a broadcast can only be captured while
                // it plays.
                if (!ui.isVod) {
                    GlassIconButton(
                        icon = if (ui.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(R.string.recording),
                        size = 32.dp,
                        // v2.2.3 — the red REC metaphor survives (a state
                        // color, like the account tiers); rest = dimmed gold.
                        tint = if (ui.isRecording) ErrorRed
                        else VuGold.Text.copy(alpha = 0.55f)
                    ) { viewModel.toggleRecording() }
                    Spacer(Modifier.width(6.dp))
                }
                // Favorites — moved up from the bottom bar (user directive)
                GlassIconButton(
                    if (ui.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                    stringResource(R.string.favorites),
                    size = 32.dp,
                    // v2.2.3 — plan-card dimmed-gold rest, full gold = saved.
                    tint = if (ui.isFavorite) VuGold.Text
                    else VuGold.Text.copy(alpha = 0.55f)
                ) { viewModel.toggleFavorite() }
                Spacer(Modifier.width(6.dp))
                // v1.19.10 — the SETTINGS gear moved UP from the bottom row
                // (user directive) into the top button row — the reference's
                // exact 40dp settings box (8dp corners / 18dp icon); it opens
                // the same screen-size + audio-tracks panel as before.
                // v1.19.11 (user directive) — the TIME READOUT that used to
                // sit here RETURNED to its designated place down WITH the red
                // bar (the reference's scrubber row); only the gear keeps its
                // top-row home.
                SettingsBoxButton(highlight = settingsOpen) { settingsOpen = true }
            }
        }

        // ── REC / DOWNLOAD status pill (top-right, below the bar). v1.18.1
        //    (user feedback): the pill now FOLLOWS the controls visibility —
        //    it hides with the top/bottom bars after the 4s auto-hide and
        //    returns on tap, exactly like every other player icon (before,
        //    it stayed pinned on screen for the whole recording and covered
        //    the movie). VOD shows a gold download pill with the PERCENT;
        //    live keeps the pulsing red dot + timer.
        //    v1.19.9 — the pill is also the mirror of a download an INFO
        //    PAGE started (RecorderEngine.isRecording with the player's own
        //    isRecording still false): watching a movie while it downloads
        //    in the background keeps the user informed. ──
        AnimatedVisibility(
            visible = (ui.isRecording || RecorderEngine.isRecording) && controlsVisible && !sidebarOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            val pulse = rememberInfiniteTransition(label = "recPulse")
            val pulseAlpha by pulse.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "recPulseAlpha"
            )
            // 1s ticker so the readout recomposes (the reference polls the
            // engine statics the same way).
            var tick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    tick++
                }
            }
            val savingLabel = stringResource(R.string.recording_finalizing)
            // v1.19.9 — the REC timer branch belongs ONLY to the player's
            // OWN live recording (ui.isRecording && !ui.isVod). Every other
            // active engine download — the player's auto-save on this VOD,
            // or one an info page started in the background — shows the
            // percent/size readout.
            val liveRec = ui.isRecording && !ui.isVod
            val readout = remember(tick, ui.isVod, ui.isRecording, RecorderEngine.isRecording) {
                if (RecorderEngine.isFinalizing) {
                    savingLabel
                } else if (liveRec) {
                    val startMs = RecorderEngine.recordingStartTime()
                    val elapsedS = if (startMs > 0) {
                        ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
                    } else 0L
                    val mm = (elapsedS / 60).toInt()
                    val ss = (elapsedS % 60).toInt()
                    val mb = RecorderEngine.recordingBytesWritten() / (1024.0 * 1024.0)
                    String.format(java.util.Locale.US, "REC %02d:%02d  •  %.1f MB", mm, ss, mb)
                } else {
                    // WATCH = DOWNLOAD: the percent the standalone engine
                    // has pulled so far (unknown total → size only).
                    val pct = RecorderEngine.progress()
                    val mb = RecorderEngine.recordingBytesWritten() / (1024.0 * 1024.0)
                    if (pct >= 0) {
                        String.format(java.util.Locale.US, "\u2B07 %d%%  •  %.1f MB", pct, mb)
                    } else {
                        String.format(java.util.Locale.US, "\u2B07 %.1f MB", mb)
                    }
                }
            }
            val pillColor = if (liveRec) ErrorRed else VuGold.Gold
            Box(
                modifier = Modifier
                    .padding(top = 56.dp, end = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, pillColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(pillColor.copy(alpha = pulseAlpha))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        readout,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Bottom band: v1.19.10 — ONE LTR row, lowered to the very
        //    bottom (user directive): the golden play/pause button FIRST,
        //    before the start of the bar's timeline, then the time bar
        //    filling the rest. The bar now shows for CHANNELS TOO — the
        //    reference's player renders its scrubber on live content (the
        //    live window's own position/duration timeline); a VLC session
        //    keeps no bar (no peripheral clock). The play button keeps its
        //    42dp size (user directive: UNCHANGED).
        //    v1.19.11 (user directive) — the TIME READOUT is back in its
        //    DESIGNATED place, WITH the red bar exactly like the reference's
        //    scrubber row: position text at the timeline's zero point, the
        //    bar filling the middle, duration text at its end. ──
        AnimatedVisibility(
            visible = controlsVisible && !sidebarOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    GlassIconButton(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(R.string.ok),
                        golden = true
                    ) { viewModel.togglePlayPause() }
                    Spacer(Modifier.width(10.dp))
                    if (ui.engine == Engine.EXO) {
                        // The reference's scrubber texts flanking the bar:
                        // position (white, Medium, 10sp) at the timeline's
                        // zero point and duration (white-70%, 10sp) at its
                        // end. While the finger scrubs, the position text
                        // tracks the FINGER (the bar's own contract); a live
                        // window reads its own timeline, exactly like the
                        // reference's texts on live content.
                        Text(
                            formatTime(if (isScrubbing) scrubPosition else ui.positionMs),
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        VuTimeBar(
                            position = if (isScrubbing) scrubPosition else ui.positionMs,
                            duration = ui.durationMs,
                            isScrubbing = isScrubbing,
                            onScrubStart = {
                                isScrubbing = true
                                scrubPosition = ui.positionMs
                            },
                            onScrubPositionChange = { scrubPosition = it },
                            onScrubCommit = {
                                viewModel.seekTo(it)
                                isScrubbing = false
                            },
                            onScrubCancel = { isScrubbing = false },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatTime(ui.durationMs),
                            color = TextPrimary.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // ── Channel keys: v1.19.10 — the two zap buttons moved OUT of the
        //    bottom band onto the player's SIDE edge (user directive),
        //    labeled like a remote's channel keys (+CH / CH-) so their
        //    purpose is self-evident. Size UNCHANGED (42dp, per directive).
        //    v1.19.11 (user directive) — the two keys now stack VERTICALLY
        //    with a clear gap between them (a remote's channel column:
        //    +CH above, CH- below), and they belong to LIVE CHANNELS ONLY —
        //    a movie or series shows NO channel keys (channel switching is
        //    meaningless there). They keep the START edge — the same side
        //    the channel list slides in from — vertically centered, and
        //    hide whenever any overlay panel owns the screen. ──
        AnimatedVisibility(
            visible = controlsVisible && !sidebarOpen && !settingsOpen && !subtitleOpen && !ui.isVod,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZapChButton("+CH", stringResource(R.string.cd_channel_next)) { viewModel.nextChannel() }
                ZapChButton("CH-", stringResource(R.string.cd_channel_prev)) { viewModel.prevChannel() }
            }
        }

        // ── Subtitle + recording + resume toast (the reference's
        //    subtitleOverlay pattern — one shared pill, subtitle
        //    messages first). v1.19.10: the bottom band is ONE row again
        //    (≈54dp), so the pill's band drops with it. ──
        AnimatedVisibility(
            visible = ui.subtitleToast != null || ui.recordingToast != null || ui.resumeToast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp, start = 24.dp, end = 24.dp)
        ) {
            (ui.subtitleToast ?: ui.recordingToast ?: ui.resumeToast)?.let { msg ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(
                            1.dp,
                            if (ui.recordingToast != null && ui.subtitleToast == null) ErrorRed.copy(alpha = 0.5f) else GlassSurface,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        msg,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── v1.19.0 — Next-episode binge card (the reference's
        //    PlayerActivity binge overlay): appears when a series episode
        //    enters its last 60s; the countdown chip and the play button
        //    BOTH advance immediately, letting the stream end naturally
        //    does too. Sits above the toast pill's band so the two never
        //    stack. v1.19.10: lowered with the slimmer bottom band.
        //    v2.2.3 — THE PLAN-CARD CONTRACT (Netflix-style binge watch):
        //    the card itself + both chips wear the family skin — dark
        //    glass + animated golden ring + gold content. ──
        AnimatedVisibility(
            visible = ui.nextEpisodeName != null && ui.nextEpisodeCountdown > 0 &&
                !ui.fatal && ui.engine != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 144.dp)
        ) {
            ui.nextEpisodeName?.let { nextName ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .vuPlanCardStyle(cornerRadius = 10.dp, focused = false)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            stringResource(R.string.next_episode),
                            color = VuGold.Text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            nextName,
                            color = TextPrimary.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .widthIn(max = 280.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    // Countdown chip — click = jump NOW. v2.2.3: the
                    // plan-card pill contract (dark glass + ring at rest,
                    // warm-bronze lit face, gold digits on both).
                    var cdFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .vuPlanCardStyle(cornerRadius = 6.dp, focused = cdFocused)
                            .onFocusChanged { cdFocused = it.isFocused }
                            .focusable()
                            .clickable { viewModel.playNextEpisode() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${ui.nextEpisodeCountdown}s",
                            color = VuGold.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Play-now button — v2.2.3: the circular plan-card
                    // chip (animated ring + dark glass, gold glyph).
                    var pnFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .vuPlanCardStyle(cornerRadius = 17.dp, focused = pnFocused)
                            .onFocusChanged { pnFocused = it.isFocused }
                            .focusable()
                            .clickable { viewModel.playNextEpisode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.play_now),
                            tint = VuGold.Text,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ── v2.2.3 — SKIP INTRO (the Netflix-style binge pair's first
        //    half, user directive: "ميزة تخطي المقدمة والتشغيل التلقائي"):
        //    while an episode's playhead sits inside the intro window
        //    (4s settle floor → 90s target) the bottom-end pill offers
        //    the jump. Netflix placement: pinned above the controls'
        //    band on the END edge, visible on its OWN window — it stays
        //    on screen even after the 4s controls auto-hide, exactly like
        //    the big platforms' players; the panels and fatal state own
        //    the screen instead. The pill is the plan-card contract
        //    (animated ring, dark glass, gold FastForward glyph + label)
        //    so the whole binge language — skip intro, next episode,
        //    every player button — reads as ONE family. ──
        AnimatedVisibility(
            visible = ui.skipIntroVisible && !ui.fatal && ui.engine != null &&
                !sidebarOpen && !settingsOpen && !subtitleOpen,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 84.dp)
        ) {
            SkipIntroPill(onSkip = { viewModel.skipIntro() })
        }

        // ── v2.2.4 — the PREPARING chip: a movie tapped in the sidebar
        //    resolves its URL before playback (the info page's probe
        //    chain — container candidates + best-URL probe); the plan-card
        //    chip with the gold spinner keeps the wait honest. ──
        AnimatedVisibility(
            visible = ui.vodResolving && !ui.fatal,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp)
        ) {
            VodPreparingChip()
        }

        // ── Channel sidebar (left slide-out) — v2.2.4: THE CONTENT
        //    SIDEBAR. The list reads what's playing: the live zap list,
        //    the series' episodes, or the playlist's movies (the lazy
        //    loads fill it the moment it opens). ──
        AnimatedVisibility(
            visible = sidebarOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(300.dp)
        ) {
            ChannelSidebar(
                channels = ui.sidebarChannels ?: ui.channels,
                mode = ui.sidebarMode,
                loading = ui.sidebarLoading,
                favoriteKeys = ui.favoriteKeys,
                currentKey = ui.channel?.key,
                query = sidebarQuery,
                onQueryChange = { q ->
                    sidebarQuery = q
                    // movies: the DB re-queries the WHOLE library
                    viewModel.onSidebarSearch(q)
                },
                onSelect = { key ->
                    if (ui.sidebarMode == VodSidebarBuilder.Mode.MOVIES) {
                        viewModel.selectVodMovie(key)
                    } else {
                        viewModel.selectChannel(key)
                    }
                    sidebarOpen = false
                },
                onToggleFavorite = { viewModel.toggleFavorite() },
                onBack = { sidebarOpen = false }
            )
        }

        // ── Settings panel (right slide-in): screen size + audio tracks ──
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(300.dp)
        ) {
            val audioTracks = remember(settingsOpen) {
                if (settingsOpen) viewModel.audioTracks() else emptyList()
            }
            SidePanel(
                title = stringResource(R.string.player_settings),
                onClose = { settingsOpen = false }
            ) {
                item { PanelSectionLabel(stringResource(R.string.screen_size)) }
                item {
                    PanelOptionRow(
                        label = stringResource(R.string.aspect_fit),
                        selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT
                    ) { applyResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); settingsOpen = false }
                }
                item {
                    PanelOptionRow(
                        label = stringResource(R.string.aspect_fill),
                        selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL
                    ) { applyResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL); settingsOpen = false }
                }
                item {
                    PanelOptionRow(
                        label = stringResource(R.string.aspect_zoom),
                        selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    ) { applyResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); settingsOpen = false }
                }
                item { PanelSectionLabel(stringResource(R.string.audio_tracks)) }
                if (audioTracks.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_audio_tracks),
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    audioTracks.forEach { track ->
                        item {
                            PanelOptionRow(
                                label = track.name,
                                selected = track.selected
                            ) {
                                viewModel.selectAudioTrack(track)
                            }
                        }
                    }
                }
            }
        }

        // ── Subtitle panel (right slide-in): Off + 15 languages ──
        AnimatedVisibility(
            visible = subtitleOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(300.dp)
        ) {
            SidePanel(
                title = stringResource(R.string.subtitles),
                onClose = { subtitleOpen = false }
            ) {
                item {
                    PanelOptionRow(
                        label = stringResource(R.string.subtitle_off),
                        selected = ui.activeSubtitleCode == null
                    ) {
                        viewModel.clearSubtitle()
                        subtitleOpen = false
                    }
                }
                SUBTITLE_LANGUAGES.forEach { lang ->
                    item {
                        PanelOptionRow(
                            label = "${lang.flag} ${lang.label}",
                            selected = ui.activeSubtitleCode == lang.code
                        ) {
                            viewModel.requestSubtitle(lang.code, lang.label)
                            subtitleOpen = false
                        }
                    }
                }
            }
        }

        // ── v2.1.0 — THE PREMIUM UPSELL GATE: a free user's SECOND use of
        //    offline viewing / downloading / recording lands here (the
        //    first use is the free trial). The gold CTA routes to the
        //    premium page; dismiss returns to the player untouched. ──
        ui.premiumGate?.let { feature ->
            PremiumUpsellDialog(
                feature = feature,
                onActivate = {
                    viewModel.consumePremiumGate()
                    onOpenPremium()
                },
                onDismiss = { viewModel.consumePremiumGate() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Components
// ─────────────────────────────────────────────────────────────────

@Composable
private fun engineLabel(engine: Engine?): String = when (engine) {
    Engine.EXO -> stringResource(R.string.engine_exo)
    Engine.VLC -> stringResource(R.string.engine_vlc)
    null -> ""
}

/**
 * v1.19.8 — COMPACT CHANNEL BADGE (the "big frame" fix). The old zap
 * overlay was a tall card: a 36dp logo, THREE stacked text lines
 * (number, name, "EXO • 812ms" debug), the full animated golden halo —
 * it dominated the upper-left quadrant and repeated the top bar's own
 * title while the stream loaded. The professional players show a quiet
 * single line instead: small plain logo, gold channel number, white
 * name on dark glass with a THIN static gold hairline — the golden
 * identity without the weight, and zero debug telemetry.
 */
@Composable
private fun ZapOverlay(channel: Channel?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.75.dp, VuGold.Gold.copy(alpha = 0.50f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        ChannelLogo(logoUrl = channel?.logo, name = channel?.name ?: "?", sizeDp = 22, plain = true)
        Spacer(Modifier.width(8.dp))
        Text(
            "#${channel?.num ?: "-"}",
            color = VuGold.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            channel?.name ?: "",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 300.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  v1.19.9 — THE TIME BAR (a faithful port of the reference app's
//  NetflixBottomScrubber geometry + gestures).
//
//  v1.19.10 — TWO user directives reshaped it:
//    • the position/duration texts moved UP into the top bar (the bar is
//      now ONLY the track, sharing the bottom row with the play/pause
//      button that sits before its zero point);
//    • the indicator color RETURNED TO RED — the reference's exact
//      NetflixRed (#E50914), a flat solid fill and a red thumb that
//      turns white only while the finger is down (“مثل التطبيق المرجعي
//      تماما، حتى في الأفلام والمسلسلات”) — on movies, series AND the
//      live channel window alike.
//  The bar stays locked to LTR (CompositionLocalProvider): a video
//  timeline is a media convention like play/pause glyphs — and the
//  ported tap/drag math (offset.x / trackWidth) then stays 1:1 with the
//  reference instead of needing RTL mirroring.
// ═══════════════════════════════════════════════════════════════════════

/** v1.19.10 — the reference app's NetflixRed (#E50914): the time
 *  indicator's color, RETURNED TO RED by user directive ("مثل التطبيق
 *  المرجعي تماما") on movies, series AND the live channel window. */
private val TimelineRed = Color(0xFFE50914)

@Composable
private fun VuTimeBar(
    position: Long,
    duration: Long,
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        var trackWidth by remember { mutableIntStateOf(1) }
        Box(
            modifier = modifier
                .height(36.dp)
                .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    detectTapGestures(onPress = { offset ->
                        onScrubStart()
                        val prog = (offset.x / trackWidth).coerceIn(0f, 1f)
                        val pos = (prog * duration).toLong()
                        onScrubPositionChange(pos)
                        tryAwaitRelease(); onScrubCommit(pos)
                    })
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onScrubStart()
                            val prog = (offset.x / trackWidth).coerceIn(0f, 1f)
                            onScrubPositionChange((prog * duration).toLong())
                        },
                        onDragEnd = { onScrubCommit(position) },
                        onDragCancel = { onScrubCancel() },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val prog = (change.position.x / trackWidth).coerceIn(0f, 1f)
                            onScrubPositionChange((prog * duration).toLong())
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // The glass rail (the reference's white-30% track)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                // The fill — the reference's NetflixRed, RETURNED TO RED by
                // user directive: flat, solid, exactly #E50914
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TimelineRed)
                )
                // The thumb — 16dp riding the fill's tip (the reference's
                // exact -8dp centering trick): RED at rest, WHITE while the
                // finger is down — the reference's exact behavior
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-8).dp)
                        .fillMaxWidth(progress)
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (isScrubbing) Color.White else TimelineRed)
                            .align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

/** The reference's formatTime (verbatim): H:MM:SS or M:SS, always 2-digit. */
private fun formatTime(ms: Long): String {
    val s = (ms / 1000) % 60
    val m = (ms / 60000) % 60
    val h = ms / 3600000
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Circular glass icon button.
 *
 * v2.2.3 — THE PLAN-CARD CONTRACT (user directive: "اِصل نفس تصميم الأزرار
 * مثل عقد بطاقة الخطة لباقي الصفحات… المشغل الكامل"): every circular
 * control in the player — back, list, subtitles, auto-save, REC, favorite,
 * the golden play/pause — wears the plan cards' exact skin through the
 * shared [vuPlanCardStyle] (a circular cornerRadius = size/2): animated
 * golden ring (0.8dp rest → 1.6dp lit), DARK GLASS resting face
 * (VuGold.PLAN_GLASS_ALPHA — raised this round), warm-bronze GLASS lit
 * face, GOLD icon on both faces. The old neutral glass (white-35% border,
 * GlassSurface-65%) and the solid-gold hero variant are retired; semantic
 * icon tints (the red REC dot) still override where the state carries
 * meaning, exactly like the account cards keep their tier colors.
 */
@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = VuGold.Text,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    golden: Boolean = false,
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
            contentDescription,
            tint = tint,
            modifier = Modifier.size((size.value * 0.5f).dp)
        )
    }
}

/**
 * v1.19.10 — a remote-style CHANNEL KEY (user directive): the zap buttons
 * sit on the player's SIDE edge, labeled "+CH" / "CH-" so the user knows
 * at a glance that they switch channels — like the keys on a TV remote.
 * v1.19.11 (user directive) — the two keys stack VERTICALLY (a remote's
 * channel column, a clear gap between them) and render on LIVE channels
 * only — movies and series show no channel keys. The size stays EXACTLY
 * the old bottom-bar zap size (42dp — user directive: UNCHANGED).
 *
 * v2.2.3 — THE PLAN-CARD CONTRACT: the two keys join the family —
 * animated golden ring + dark glass rest + warm-bronze lit face + GOLD
 * label, the shared [vuPlanCardStyle] with a circular radius.
 */
@Composable
private fun ZapChButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(42.dp)
            .vuPlanCardStyle(cornerRadius = 21.dp, focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = VuGold.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
    }
}

/**
 * v1.19.10 — the SETTINGS gear in its NEW home (the top button row, by
 * user directive), in the reference app's EXACT size: a 40dp box with 8dp
 * corners and an 18dp icon (the reference's NetflixBottomScrubber-end
 * settings button). v2.2.3 — THE PLAN-CARD CONTRACT: the gear wears the
 * family skin ([vuPlanCardStyle], 8dp corners); `highlight` (its panel
 * open) holds the warm-bronze LIT face — the open state reads like a
 * selected plan card.
 */
@Composable
private fun SettingsBoxButton(
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .vuPlanCardStyle(cornerRadius = 8.dp, focused = focused, selected = highlight)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.player_settings),
            tint = VuGold.Text,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Pill button. v2.2.3 — THE PLAN-CARD CONTRACT: the pill joins the family
 * through the shared [vuPlanCardStyle] (animated golden ring, dark glass
 * rest, warm-bronze lit face, GOLD label); `highlight` holds the lit face
 * (the fatal-error dialog's RETRY reads as the selected plan card).
 */
@Composable
private fun GlassPillButton(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .vuPlanCardStyle(cornerRadius = 20.dp, focused = focused, selected = highlight)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = VuGold.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * v2.2.3 — SKIP INTRO (the Netflix-style binge pair's first half, user
 * directive: "ميزة تخطي المقدمة والتشغيل التلقائي"): the plan-card pill
 * pinned to the player's bottom-END edge while an episode's playhead sits
 * inside the intro window — FastForward glyph + the localized label, gold
 * on the dark-glass face, the warm-bronze lit face + thicker ring while
 * focused (remote OK works — [focusable] precedes [clickable]). A tap
 * routes [PlayerViewModel.skipIntro], which seeks through the SAME
 * contract the time bar uses.
 */
@Composable
private fun SkipIntroPill(
    onSkip: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .vuPlanCardStyle(cornerRadius = 10.dp, focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onSkip() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Icon(
            Icons.Filled.FastForward,
            contentDescription = null,
            tint = VuGold.Text,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.skip_intro),
            color = VuGold.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A right slide-in panel — our glass idiom (the reference's settings/
 * subtitle menu layout, restyled). */
@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .border(1.dp, GlassSurface.copy(alpha = 0.6f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                title,
                color = VuGold.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, title, size = 30.dp) { onClose() }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GlassSurface.copy(alpha = 0.5f))
        )
        LazyColumn(
            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}

@Composable
private fun PanelSectionLabel(label: String) {
    Text(
        label,
        color = VuGold.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * One option row inside a slide-in panel. v2.2.3 — THE PLAN-CARD ROW
 * CONTRACT: the settings/subtitle options read as a column of little
 * plan cards ([vuPlanCardRowStyle] — dark glass + static gold hairline
 * at rest; the animated ring + warm-bronze face while selected/lit),
 * gold label on the lit face.
 */
@Composable
private fun PanelOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .vuPlanCardRowStyle(cornerRadius = 8.dp, focused = focused, selected = selected)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            if (selected) "• $label" else label,
            color = if (selected || focused) VuGold.Text else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChannelSidebar(
    channels: List<Channel>,
    mode: VodSidebarBuilder.Mode,
    loading: Boolean,
    favoriteKeys: Set<String>,
    currentKey: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit
) {
    val filtered = remember(channels, query) {
        if (query.isBlank()) channels
        else channels.filter { it.name.contains(query, ignoreCase = true) }
    }
    // v2.2.4 — the title reads what the list IS (the reference players'
    // side lists label themselves; a series player saying "channels"
    // while listing episodes is the amateur tell).
    val titleRes = when (mode) {
        VodSidebarBuilder.Mode.EPISODES -> R.string.episodes_header
        VodSidebarBuilder.Mode.MOVIES -> R.string.movies_header
        VodSidebarBuilder.Mode.CHANNELS -> R.string.channel_list
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel), tint = TextPrimary)
            }
            Text(
                stringResource(titleRes),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (currentKey != null && favoriteKeys.contains(currentKey)) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.favorites),
                    tint = VuGold.Text
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            LoginField(
                value = query,
                onValueChange = onQueryChange,
                hint = stringResource(R.string.search_hint),
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            )
        }
        // v2.2.4 — the lazy load's honest face: a centered gold spinner
        // while the full series / the movies page is being fetched.
        if (loading && channels.size <= 1) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = VuGold.Gold,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp),
                modifier = Modifier.weight(1f)
            ) {
                // v2.2.4 — THE CRASH FIX: rows key by Channel.KEY, never by
                // the Room id. The info pages' synthetic VOD channels all
                // carry the default id=0 — an episode list meant N rows
                // keyed "0" → "Key was already used" → instant crash (ONLY
                // on series: a movie registers one row, live channels come
                // from the DB with real ids). Keys are unique by
                // construction: vode:{episodeId} / vodm:{streamId} / the
                // live channel_key unique index.
                items(filtered, key = { it.key }) { channel ->
                    ChannelRow(
                        channel = channel,
                        isFavorite = favoriteKeys.contains(channel.key),
                        selected = channel.key == currentKey,
                        compact = true,
                        onClick = { onSelect(channel.key) },
                        onLongClick = { onSelect(channel.key) }
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                when (mode) {
                                    VodSidebarBuilder.Mode.MOVIES -> R.string.no_movies
                                    else -> R.string.no_episodes
                                }
                            ),
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

/** v2.2.4 — the movie-resolve wait chip (the plan-card family's face). */
@Composable
private fun VodPreparingChip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .vuPlanCardStyle(cornerRadius = 10.dp, focused = false)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        CircularProgressIndicator(
            color = VuGold.Gold,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.vod_preparing),
            color = VuGold.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

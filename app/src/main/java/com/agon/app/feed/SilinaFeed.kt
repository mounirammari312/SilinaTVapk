package com.agon.app.feed

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.agon.app.proxy.GlobalPlaybackCoordinator
import com.agon.app.recommendation.WatchHistoryManager
import com.agon.app.timemachine.TimeMachineEngine
import com.agon.app.ui.screens.PlayerActivity
import com.agon.app.ui.util.LogoPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Silina Feed — TikTok-style vertical feed.
 *
 * Design goals (matching TikTok UX):
 *   1. PORTRAIT 9:16 — hosted in FeedActivity which forces portrait.
 *   2. SOUND ON — the active card plays with full audio. Mute toggle
 *      available via a volume button.
 *   3. INSTANT SWITCHING — snap fling behavior so each swipe lands
 *      perfectly on the next card. ExoPlayer reuses the same instance
 *      and calls setMediaItem + prepare on every card change → no
 *      new player creation overhead.
 *   4. PRELOADING — the next card's URL is pre-warmed via ExoPlayer's
 *      load control so the first frame appears in <500ms.
 *
 * Card types (mixed in a single feed):
 *   1. ChannelPreviewCard — a live channel with sound
 *   2. TimeMachineCard — a past program that can be re-watched
 *   3. RecommendationCard — a channel the algorithm thinks you'll like
 *   4. SportsMomentCard — an auto-clipped sports moment
 */
@Composable
fun SilinaFeed(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedState = remember { mutableStateOf<List<FeedCard>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }

    // ═══ AUTO-HIDE OVERLAY (TikTok-style) ═══
    // Icons auto-hide after 3s. Tapping the screen toggles them.
    var showOverlay by remember { mutableStateOf(true) }
    LaunchedEffect(showOverlay) {
        if (showOverlay) {
            delay(3000)
            showOverlay = false
        }
    }

    // Build the feed on first composition.
    LaunchedEffect(Unit) {
        scope.launch {
            val cards = buildFeed(context)
            feedState.value = cards
            isLoading.value = false
        }
    }

    // TikTok-style snap list — each item fills the screen, fling snaps
    // to the nearest item.
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState)
    val currentIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // ═══════════════════════════════════════════════════════════════════
    //  SINGLETON EXOPLAYER — fetched from GlobalPlaybackCoordinator.
    //
    //  The Feed NO LONGER creates its own ExoPlayer. It shares the
    //  singleton with PlayerActivity so the resume-bridge works:
    //  when the user taps a card to expand to full-screen PlayerActivity,
    //  the player is ALREADY buffering the URL — PlayerActivity just
    //  calls coordinator.resume() and the video continues without a
    //  black screen.
    //
    //  The coordinator's LoadControl (2.5s/30s/1.5s/2s + 30s back-buffer)
    //  is a superset of the old Feed-specific config (3s/20s/100ms/300ms)
    //  — it absorbs network hiccups better and supports the Retroactive
    //  Clip ring buffer.
    // ═══════════════════════════════════════════════════════════════════
    val exoPlayer = remember {
        GlobalPlaybackCoordinator.getPlayer(context).apply {
            playWhenReady = true
            volume = 1f // SOUND ON — like TikTok
            repeatMode = Player.REPEAT_MODE_ONE // loop the current card
        }
    }
    // NOTE: We do NOT release the player on dispose — it is the singleton
    // owned by GlobalPlaybackCoordinator and must survive FeedActivity
    // destruction so PlayerActivity can resume() the buffered stream.
    DisposableEffect(Unit) {
        onDispose {
            // Just pause — the singleton lives on.
            exoPlayer.pause()
        }
    }

    // ═══ LIFECYCLE: pause feed audio when activity goes to background ═══
    // This prevents "two audio streams" when the user opens PlayerActivity
    // from a feed card tap. When the user returns, playback resumes.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (feedState.value.isNotEmpty()) exoPlayer.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Mute/unmute toggle.
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // When the visible card changes, route the new URL through the
    // GlobalPlaybackCoordinator. The coordinator handles:
    //   - Job cancellation (previous scrape destroyed — only the LAST
    //     swiped card passes through)
    //   - Isolated IO scrape (RedirectSniffer)
    //   - Single prepare() on the resolved Edge URL
    // The Feed's ExoPlayer reference (singleton) is the same instance
    // PlayerActivity will use, so the buffer carries over on tap.
    LaunchedEffect(currentIndex, feedState.value) {
        val cards = feedState.value
        if (currentIndex in cards.indices) {
            val card = cards[currentIndex]
            when (card) {
                is FeedCard.ChannelPreview -> {
                    GlobalPlaybackCoordinator.playStream(card.stream.url)
                }
                is FeedCard.TimeMachine -> {
                    GlobalPlaybackCoordinator.playStream(card.program.catchupUrl)
                }
                is FeedCard.Recommendation -> {
                    GlobalPlaybackCoordinator.playStream(card.stream.url)
                }
                is FeedCard.SportsMoment -> {
                    exoPlayer.stop()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  D-PAD / REMOTE CONTROL SUPPORT
    //
    //  SilinaFeed is designed for phones, tablets, AND Android TV. The
    //  TikTok-style swipe gesture is preserved for touch, but D-Pad users
    //  get equivalent navigation:
    //    DPAD_UP    → scroll to the PREVIOUS card (with snap)
    //    DPAD_DOWN  → scroll to the NEXT card (with snap)
    //    DPAD_CENTER/ENTER → tap the active card (open PlayerActivity)
    //    DPAD_LEFT  → toggle overlay (Close/Mute buttons)
    //    KEYCODE_BACK → if overlay visible, hide it; else close the feed
    // ═══════════════════════════════════════════════════════════════════
    val coroutineScope = rememberCoroutineScope()

    // BackHandler — dismiss the overlay first, then close the feed.
    androidx.activity.compose.BackHandler(enabled = true) {
        if (showOverlay) {
            showOverlay = false
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (feedState.value.isNotEmpty() && currentIndex > 0) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(currentIndex - 1)
                            }
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (feedState.value.isNotEmpty() && currentIndex < feedState.value.size - 1) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(currentIndex + 1)
                            }
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        // Toggle overlay if visible, else "tap" the active card
                        // (open the full player).
                        if (feedState.value.isNotEmpty() && currentIndex in feedState.value.indices) {
                            val card = feedState.value[currentIndex]
                            val url = when (card) {
                                is FeedCard.ChannelPreview -> card.stream.url
                                is FeedCard.TimeMachine -> card.program.catchupUrl
                                is FeedCard.Recommendation -> card.stream.url
                                is FeedCard.SportsMoment -> null
                            }
                            val name = when (card) {
                                is FeedCard.ChannelPreview -> card.stream.name
                                is FeedCard.TimeMachine -> card.program.title
                                is FeedCard.Recommendation -> card.stream.name
                                is FeedCard.SportsMoment -> card.moment.matchName
                            }
                            if (url != null) {
                                exoPlayer.pause()
                                context.startActivity(
                                    Intent(context, PlayerActivity::class.java).apply {
                                        putExtra("STREAM_URL", url)
                                        putExtra("STREAM_NAME", name)
                                        putExtra("FROM_FEED", true)
                                        putExtra("FEED_URL", url)
                                    }
                                )
                            }
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Toggle the overlay (Close/Mute buttons) on any
                        // horizontal D-Pad press — gives remote users a way
                        // to reach the close/mute buttons without a touch.
                        showOverlay = !showOverlay
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (isLoading.value) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF7C6FFF))
                    Spacer(Modifier.height(12.dp))
                    Text("Building your feed...", color = Color.White, fontSize = 13.sp)
                }
            }
        } else if (feedState.value.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No content yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Watch some channels to populate your feed", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(0.dp)
            ) {
                items(feedState.value.size) { index ->
                    val card = feedState.value[index]
                    val isThisActive = index == currentIndex
                    FeedCardItem(
                        card = card,
                        isActive = isThisActive,
                        // showOverlay (the auto-hiding icons: ✕ close, 🔊 mute,
                        // play button, "tap to watch" hint) only applies to the
                        // ACTIVE card. Non-active cards ignore this flag and
                        // always show their info (badge + title + subtitle).
                        showOverlay = showOverlay,
                        isInteractive = isThisActive,
                        exoPlayer = exoPlayer,
                        onTap = {
                            // ═══ FIX: pause feed audio before opening PlayerActivity ═══
                            // This prevents two audio streams playing simultaneously.
                            // The singleton player is NOT released — PlayerActivity will
                            // resume() the buffered stream via the Resume Bridge.
                            exoPlayer.pause()
                            val url = when (card) {
                                is FeedCard.ChannelPreview -> card.stream.url
                                is FeedCard.TimeMachine -> card.program.catchupUrl
                                is FeedCard.Recommendation -> card.stream.url
                                is FeedCard.SportsMoment -> null
                            }
                            val name = when (card) {
                                is FeedCard.ChannelPreview -> card.stream.name
                                is FeedCard.TimeMachine -> card.program.title
                                is FeedCard.Recommendation -> card.stream.name
                                is FeedCard.SportsMoment -> card.moment.matchName
                            }
                            if (url != null) {
                                context.startActivity(
                                    Intent(context, PlayerActivity::class.java).apply {
                                        putExtra("STREAM_URL", url)
                                        putExtra("STREAM_NAME", name)
                                        // RESUME BRIDGE: tell PlayerActivity that this
                                        // launch came from the Feed and the player is
                                        // already buffering this exact URL. PlayerActivity
                                        // will call coordinator.resume() instead of
                                        // playStream() — zero black screen, zero scrape.
                                        putExtra("FROM_FEED", true)
                                        putExtra("FEED_URL", url)
                                    }
                                )
                            }
                        },
                        onScreenTap = {
                            // Toggle overlay visibility (TikTok-style)
                            showOverlay = !showOverlay
                        }
                    )
                }
            }
        }

        // ── Top overlay: Close + Mute buttons (auto-hiding) ──
        if (showOverlay) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button — focusable for D-Pad/remote users
                var closeFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (closeFocused) Color.White.copy(alpha = 0.35f)
                            else Color.Black.copy(alpha = 0.5f)
                        )
                        .border(
                            1.5.dp,
                            if (closeFocused) Color.White else Color.Transparent,
                            CircleShape
                        )
                        .focusable()
                        .onFocusChanged { closeFocused = it.isFocused }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                // Mute toggle — focusable for D-Pad/remote users
                var muteFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (muteFocused) Color.White.copy(alpha = 0.35f)
                            else Color.Black.copy(alpha = 0.5f)
                        )
                        .border(
                            1.5.dp,
                            if (muteFocused) Color.White else Color.Transparent,
                            CircleShape
                        )
                        .focusable()
                        .onFocusChanged { muteFocused = it.isFocused }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isMuted = !isMuted }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single full-screen card in the Silina Feed.
 * Each card fills the entire 9:16 screen — just like TikTok.
 *
 * - Active cards show the live video via ExoPlayer.
 * - Non-active cards show the channel logo as a placeholder (not black).
 * - Card info (badges, title, subtitle) auto-hides via [showOverlay].
 * - Single tap toggles overlay visibility (TikTok-style).
 * - Double tap opens the full player.
 */
@Composable
private fun FeedCardItem(
    card: FeedCard,
    isActive: Boolean,
    showOverlay: Boolean,
    isInteractive: Boolean,
    exoPlayer: ExoPlayer,
    onTap: () -> Unit,
    onScreenTap: () -> Unit
) {
    // Extract logo + title for placeholder display on non-active cards.
    val logoUrl = when (card) {
        is FeedCard.ChannelPreview -> card.stream.logo
        is FeedCard.Recommendation -> card.stream.logo
        is FeedCard.TimeMachine -> card.program.channelLogo
        is FeedCard.SportsMoment -> ""
    }
    val title = when (card) {
        is FeedCard.ChannelPreview -> card.stream.name
        is FeedCard.TimeMachine -> card.program.title
        is FeedCard.Recommendation -> card.stream.name
        is FeedCard.SportsMoment -> card.moment.matchName
    }

    // Determine whether to show the interactive overlay (icons + hint).
    // Only the ACTIVE card shows the interactive overlay, and only when
    // showOverlay=true (auto-hides after 3s, toggled by tap).
    // Non-active cards never show the interactive overlay — but they
    // DO always show their info (badge + title + subtitle).
    val showInteractive = isActive && showOverlay

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                // Only the ACTIVE card responds to taps. Non-active cards
                // ignore all touch input — the user can't accidentally
                // toggle the overlay or open the player by tapping a
                // non-active card.
                if (isInteractive) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onScreenTap() },
                            onDoubleTap = { onTap() }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // ═══ VIDEO or PLACEHOLDER ═══
        val hasVideo = card !is FeedCard.SportsMoment
        if (hasVideo && isActive) {
            // Active card: show live video
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setBackgroundColor(android.graphics.Color.BLACK)
                        player = exoPlayer
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Non-active card: show channel logo as placeholder (NOT black)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (logoUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = title,
                        modifier = Modifier.size(120.dp)
                    )
                } else {
                    // No logo available — show the DOH brand logo
                    LogoPlaceholder(Modifier.size(120.dp))
                }
            }
        }

        // Bottom gradient for text readability — ALWAYS visible on every card
        // so the info text is readable on non-active cards too.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        // ═══ Right-side action buttons — ONLY on the active card when interactive ═══
        if (showInteractive) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Channel logo / avatar
                if (logoUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                }

                // Play icon (tap to watch full)
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // ═══ Bottom-left: card info — ALWAYS visible on every card ═══
        // (badge + title + subtitle). The "double-tap to watch" hint is
        // only shown on the active card when interactive.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 72.dp, bottom = 60.dp)
        ) {
            // Card type badge
            val (badgeText, badgeColor) = when (card) {
                is FeedCard.ChannelPreview -> "LIVE" to Color(0xFFFF5252)
                is FeedCard.TimeMachine -> "REPLAY" to Color(0xFFFFC107)
                is FeedCard.Recommendation -> "FOR YOU" to Color(0xFF7C6FFF)
                is FeedCard.SportsMoment -> "MOMENT" to Color(0xFF00E676)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))

            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = when (card) {
                is FeedCard.ChannelPreview -> card.stream.group
                is FeedCard.TimeMachine -> card.program.timeAgoLabel
                is FeedCard.Recommendation -> "${card.matchPercent}% match • ${card.stream.group}"
                is FeedCard.SportsMoment -> card.moment.title
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // The "double-tap to watch" hint is interactive UI — only on
            // the active card when the interactive overlay is visible.
            if (showInteractive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Double-tap to watch in full player →",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Feed card types.
 */
sealed class FeedCard {
    data class ChannelPreview(val stream: StreamItem) : FeedCard()
    data class TimeMachine(val program: TimeMachineEngine.PastProgram) : FeedCard()
    data class Recommendation(val stream: StreamItem, val matchPercent: Int) : FeedCard()
    data class SportsMoment(val moment: com.agon.app.sports.SportsMomentsEngine.SportsMoment) : FeedCard()
}

/**
 * Builds the feed by mixing all card types.
 */
private suspend fun buildFeed(context: android.content.Context): List<FeedCard> = withContext(Dispatchers.IO) {
    val cards = mutableListOf<FeedCard>()

    // 1. Add recommendations (top 5)
    try {
        val recs = WatchHistoryManager.getRecommendations(context, limit = 5)
        recs.forEach { (stream, score) ->
            val percent = (50 + (score / 100).toInt()).coerceIn(50, 99)
            cards.add(FeedCard.Recommendation(stream, percent))
        }
    } catch (_: Exception) {}

    // 2. Add channel previews (top 10 live channels)
    try {
        val liveStreams = SessionData.liveStreams.take(10)
        for (stream in liveStreams) {
            cards.add(FeedCard.ChannelPreview(stream))
        }
    } catch (_: Exception) {}

    // 3. Add Time Machine cards
    try {
        val catchupChannels = TimeMachineEngine.getCatchupChannels()
        if (catchupChannels.isNotEmpty()) {
            val first = catchupChannels.first()
            val programs = TimeMachineEngine.getRecentPrograms(first.url, maxHours = 6)
            programs.take(3).forEach { program ->
                cards.add(FeedCard.TimeMachine(program.copy(channelName = first.name, channelLogo = first.logo)))
            }
        }
    } catch (_: Exception) {}

    // 4. Add sports moments
    try {
        val moments = com.agon.app.sports.SportsMomentsEngine.moments.value
        moments.take(3).forEach { moment ->
            cards.add(FeedCard.SportsMoment(moment))
        }
    } catch (_: Exception) {}

    // Shuffle for variety.
    cards.shuffle()
    cards
}

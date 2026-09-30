package com.agon.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.agon.app.data.model.StreamItem
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.GlassSurface
import com.agon.app.ui.util.LogoPlaceholder
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════
//  HeroSlider — V4.3 Cinematic Featured Content Carousel
// ═══════════════════════════════════════════════════════════════════════
//
//  A Netflix-style auto-rotating hero banner that showcases featured
//  channels at the top of the dashboard. Features:
//
//    • Auto-rotation every 6 seconds (pauses on focus/hover)
//    • Cinematic backdrop from channel logo (cropped to fill)
//    • Gradient overlay for text readability
//    • Channel name + group label + "Watch Now" button
//    • Dot indicators (clickable for manual navigation)
//    • D-Pad focus support (focusable + focusRequester on the card)
//    • Smooth fade transition between slides
//    • Respects the existing theme (AccentIndigo → AccentCyan gradient)
//
//  The slider pulls 5 featured items from the current category's
//  stream list — preferring channels with logos for visual appeal.
//  When the user taps a hero card, it launches the mini-player
//  inline (same behavior as tapping a grid card).
//
//  Insertion: placed at the top of the Content Area Column in
//  CompactDashboardScreen, above the main grid.
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun HeroSlider(
    streams: List<StreamItem>,
    onStreamClick: (StreamItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // ── Guard: need at least 1 stream with content ──
    if (streams.isEmpty()) return

    // Pick up to 5 featured items — prefer streams with logos
    val featured = remember(streams) {
        val withLogos = streams.filter { it.logo.isNotEmpty() }
        val source = if (withLogos.size >= 3) withLogos else streams
        source.take(5)
    }
    if (featured.isEmpty()) return

    // ── Auto-rotation state ──
    var currentIndex by remember { mutableIntStateOf(0) }
    var userInteracting by remember { androidx.compose.runtime.mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Auto-rotate every 6 seconds — pause when user is interacting
    LaunchedEffect(featured.size, userInteracting) {
        if (!userInteracting && featured.size > 1) {
            while (true) {
                delay(6000)
                if (!userInteracting) {
                    currentIndex = (currentIndex + 1) % featured.size
                }
            }
        }
    }

    val currentStream = featured[currentIndex]
    var cardFocused by remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurface.copy(alpha = 0.6f))
            .border(
                width = if (cardFocused) 2.dp else 0.5.dp,
                color = if (cardFocused) AccentCyan else Color.White.copy(alpha = 0.3f),
                shape = RoundedCornerShape(14.dp)
            )
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged {
                cardFocused = it.isFocused
                userInteracting = it.isFocused
            }
            .clickable { onStreamClick(currentStream) }
    ) {
        // ── Backdrop: channel logo cropped to fill ──
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(currentStream.logo)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            error = {
                // Fallback: DOH brand logo if the backdrop fails to load
                LogoPlaceholder(Modifier.fillMaxSize())
            },
            loading = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(AccentIndigo.copy(alpha = 0.4f), AccentCyan.copy(alpha = 0.3f))
                            )
                        )
                )
            }
        )

        // ── Gradient overlay for text readability ──
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.2f)
                        )
                    )
                )
        )

        // ── Content: channel name + group + Watch Now button ──
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: Text content
            Column(modifier = Modifier.weight(1f)) {
                // Group label (small, accent color)
                Text(
                    text = currentStream.group.uppercase(),
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))

                // Channel name (large, white)
                Text(
                    text = currentStream.name,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
                Spacer(Modifier.height(10.dp))

                // Watch Now button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(AccentIndigo, AccentCyan)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Watch Now",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // RIGHT: nothing (let the backdrop show through)
            Spacer(Modifier.weight(0.3f))
        }

        // ── Dot indicators (bottom-center) ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            featured.forEachIndexed { index, _ ->
                val isActive = index == currentIndex
                Box(
                    modifier = Modifier
                        .size(if (isActive) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) AccentCyan else Color.White.copy(alpha = 0.4f)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            currentIndex = index
                            userInteracting = true
                        }
                )
            }
        }
    }
}

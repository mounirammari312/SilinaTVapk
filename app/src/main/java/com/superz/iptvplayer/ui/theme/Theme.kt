package com.superz.iptvplayer.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── Spring specs (physics-based, natural motion) ──
val PremiumSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)

val SmoothSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

val FastSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh
)

// ── Glassmorphic card ──
fun Modifier.glassmorphicCard(
    cornerRadius: Int = 16,
    focused: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(GlassSurface.copy(alpha = 0.60f))
    .border(
        width = if (focused) 1.5.dp else 1.dp,
        color = if (focused) AccentIndigoLight else Color.White.copy(alpha = 0.6f),
        shape = RoundedCornerShape(cornerRadius.dp)
    )
    .shadow(
        elevation = if (focused) 12.dp else 4.dp,
        shape = RoundedCornerShape(cornerRadius.dp),
        ambientColor = if (focused) AccentIndigo.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f),
        spotColor = if (focused) AccentIndigo.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.3f)
    )

// ── Glassmorphic pill (tabs / capsule buttons) ──
fun Modifier.glassmorphicPill(
    focused: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(50))
    .background(GlassSurface.copy(alpha = if (focused) 0.75f else 0.50f))
    .border(
        width = if (focused) 1.5.dp else 1.dp,
        color = if (focused) AccentIndigoLight else Color.White.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50)
    )

// ── Deep Space background ──
fun Modifier.deepSpaceBackground(
    withCinematicGlow: Boolean = true
): Modifier = this
    .background(DeepSpaceBackgroundBrush)
    .then(
        if (withCinematicGlow) Modifier.background(CinematicGlowBrush)
        else Modifier
    )

private val DarkScheme = darkColorScheme(
    primary = AccentIndigo,
    onPrimary = Color.White,
    primaryContainer = AccentIndigoDark,
    onPrimaryContainer = Color.White,
    secondary = AccentCyan,
    onSecondary = Color(0xFF050608),
    background = BackgroundDarkTop,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.White,
    outline = DividerDark
)

@Composable
fun IPTVPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = IPTVTypography,
        content = content
    )
}

package com.superz.iptvplayer.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// DEEP SPACE PALETTE — adapted from the reference design system
// Background: deep cosmic vertical gradient + cinematic glow
// Accents: Electric Indigo + Aurora Cyan
// ═══════════════════════════════════════════════════════════════

// ── Primary Brand: Electric Indigo ──
val AccentIndigo      = Color(0xFF7C6FFF)
val AccentIndigoLight = Color(0xFF9D8CFF)
val AccentIndigoDark  = Color(0xFF5A47E0)

// ── Secondary: Aurora Cyan ──
val AccentCyan      = Color(0xFF4FF0E0)
val AccentCyanLight = Color(0xFF8FF7EE)

// ── Background: Deep Space ──
val BackgroundDarkTop = Color(0xFF0A0B14)
val BackgroundDark    = Color(0xFF050608)
val SurfaceDark       = Color(0xFF0E0F1A)

// ── Glassmorphic surfaces ──
val GlassSurface    = Color(0xFF12131F)
val GlassSurfaceAlt = Color(0xFF1A1B2E)
val DividerDark     = Color(0xFF232536)

// ── Text ──
val TextPrimary   = Color(0xFFEEF1FF)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted     = Color(0xFF64748B)

// ── Misc ──
val ErrorRed  = Color(0xFFFF5252)
val LiveRed   = Color(0xFFFF3B5C)
val SuccessGreen = Color(0xFF4FF0E0)

// ── Brushes ──
val DeepSpaceBackgroundBrush = Brush.verticalGradient(
    colors = listOf(BackgroundDarkTop, Color(0xFF070810), BackgroundDark)
)

val CinematicGlowBrush = Brush.radialGradient(
    colors = listOf(
        AccentIndigo.copy(alpha = 0.14f),
        AccentCyan.copy(alpha = 0.10f),
        Color.Transparent
    )
)

val AccentGradientBrush = Brush.horizontalGradient(
    colors = listOf(AccentIndigo, AccentCyan)
)

val AccentGradientBrushV = Brush.verticalGradient(
    colors = listOf(AccentIndigo, AccentCyan)
)

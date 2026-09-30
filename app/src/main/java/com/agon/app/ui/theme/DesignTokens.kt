package com.agon.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════
// DESIGN TOKENS — Deep Space 2026
// Centralized modifiers + spring specs so every screen reads from the
// same source of truth. No hardcoded colors anywhere else.
// ═══════════════════════════════════════════════════════════════════

// ── Spring Specs (physics-based animations, more natural than tween) ──
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

// ── Glassmorphic Card Modifier ──
// Background = glass tint @ 60% alpha, frost overlay @ 4%, soft shadow,
// subtle border. Pass `focused = true` to add the indigo glow border.
// V4.1: Unfocused border changed from dark SlateBorder to dim white
// (Color.White alpha 0.3f) so cards/buttons are visible on dark bg.
fun Modifier.glassmorphicCard(
    cornerRadius: Int = 16,
    focused: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(GlassSurface.copy(alpha = 0.60f))
    .background(GlassOverlay.copy(alpha = 0.04f))
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

// ── Glassmorphic Pill Modifier (for top bar / tabs / capsule buttons) ──
// V4.1: Unfocused border changed from dark SlateBorder to dim white.
fun Modifier.glassmorphicPill(
    focused: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(50))
    .background(GlassSurface.copy(alpha = if (focused) 0.75f else 0.50f))
    .background(GlassOverlay.copy(alpha = 0.05f))
    .border(
        width = if (focused) 1.5.dp else 1.dp,
        color = if (focused) AccentIndigoLight else Color.White.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50)
    )

// ── Glassmorphic Panel Modifier (for EPG panels, dialogs) ──
// V4.1: Unfocused border changed from dark SlateBorder to dim white.
fun Modifier.glassmorphicPanel(
    cornerRadius: Int = 14
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(GlassSurface.copy(alpha = 0.55f))
    .background(GlassOverlay.copy(alpha = 0.03f))
    .border(
        width = 0.5.dp,
        color = Color.White.copy(alpha = 0.25f),
        shape = RoundedCornerShape(cornerRadius.dp)
    )

// ── Deep Space Background Modifier ──
// Vertical gradient (#0A0B14 → #050608) + optional cinematic radial glow.
fun Modifier.deepSpaceBackground(
    withCinematicGlow: Boolean = true
): Modifier = this
    .background(DeepSpaceBackgroundBrush)
    .then(
        if (withCinematicGlow) Modifier.background(CinematicGlowBrush)
        else Modifier
    )

// ── Focus Glow Modifier ──
// Adds a soft radial glow behind a focused element (used by stream cards).
fun Modifier.focusGlow(
    focused: Boolean
): Modifier = this.then(
    if (focused) Modifier.background(CardFocusGlowBrush)
    else Modifier
)

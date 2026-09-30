package com.agon.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════
// DEEP SPACE 2026 PALETTE — Tier-1 Premium TV
// Inspired by Apple TV+ / Disney+ 2026.
// Background: deep cosmic vertical gradient (#0A0B14 → #050608) with a
// subtle cinematic glow. Accents: Electric Indigo + Aurora Cyan with
// neon glow. Cards: glassmorphic surfaces that lift off the background.
// ═══════════════════════════════════════════════════════════════════

// ── Primary Brand: Electric Indigo (with neon undertone) ──
val PrimaryColor   = Color(0xFF7C6FFF)   // Electric Indigo — primary accent
val PrimaryDark    = Color(0xFF5A47E0)   // Pressed / selected deep
val PrimaryLight   = Color(0xFF9D8CFF)   // Highlight / focus (with glow)

// ── Secondary: Aurora Cyan (softer than pure cyan) ──
val SecondaryColor  = Color(0xFF4FF0E0)   // Aurora Cyan
val SecondaryDark   = Color(0xFF1FB8A8)   // Pressed
val SecondaryLight  = Color(0xFF8FF7EE)   // Highlight

// Accent Colors (legacy compat)
val AccentColor  = Color(0xFFBB86FC)
val AccentVariant = Color(0xFFCF9FFF)

// ── Background: Deep Space (vertical gradient, top → bottom) ──
val BackgroundLight = Color(0xFFFFFFFF)
val BackgroundDark  = Color(0xFF050608)   // Bottom of the deep space gradient
val BackgroundDarkTop = Color(0xFF0A0B14) // Top of the deep space gradient
val SurfaceLight    = Color(0xFFFFFFFF)
val SurfaceDark     = Color(0xFF0E0F1A)   // Slight lift, never flat gray

// ── Glassmorphic surface colors ──
val GlassSurface       = Color(0xFF12131F)   // Glass base tint
val GlassSurfaceAlt    = Color(0xFF1A1B2E)   // Glass alt (focused)
val GlassOverlay       = Color(0xFFFFFFFF)    // Used with low alpha for frost

// Error Colors
val ErrorLight = Color(0xFFFF5252)
val ErrorDark  = Color(0xFFCF6679)

// Text Colors
val OnPrimary         = Color(0xFFFFFFFF)
val OnSecondary       = Color(0xFF050608)
val OnBackgroundLight = Color(0xFF1C1B1F)
val OnBackgroundDark  = Color(0xFFEEF1FF)   // Slightly cool-tinted bright text
val OnSurfaceLight    = Color(0xFF1C1B1F)
val OnSurfaceDark     = Color(0xFFEEF1FF)

// Legacy compatibility aliases (kept so existing imports still resolve)
val Purple40    = PrimaryColor
val PurpleGrey40 = Color(0xFF625b71)
val Pink40      = Color(0xFF7D5260)
val Purple80    = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80      = Color(0xFFEFB8C8)

// ── Card / Divider / Icon tints ──
val CardBackgroundLight = Color(0xFFF5F5F5)
val CardBackgroundDark  = Color(0xFF12131F)
val DividerLight        = Color(0xFFE0E0E0)
val DividerDark         = Color(0xFF232536)   // Subtle border, never heavy
val IconTintLight       = PrimaryColor
val IconTintDark        = PrimaryLight

// ═══════════════════════════════════════════════════════════════════
// Premium Metallic Gold Palette (legacy compat — kept so existing
// imports still compile. The 2026 accent is the Indigo/Cyan pair.)
// ═══════════════════════════════════════════════════════════════════
val GoldLight  = Color(0xFFD4B85A)
val GoldBase   = Color(0xFFB8922A)
val GoldMid    = Color(0xFF9A7520)
val GoldDark   = Color(0xFF705015)
val GoldDarker = Color(0xFF3D2B0D)
val GoldText   = Color(0xFF1A1408)

val MetallicGoldBorderBrush = Brush.verticalGradient(
    colors = listOf(GoldLight, GoldBase, GoldMid, GoldDark, GoldMid, GoldBase, GoldLight)
)
val MetallicGoldFillBrush = Brush.verticalGradient(
    colors = listOf(GoldLight, GoldBase, GoldMid, GoldDark, GoldDarker),
    startY = 0f,
    endY = Float.POSITIVE_INFINITY
)
val MetallicGoldGlowBrush = Brush.verticalGradient(
    colors = listOf(GoldBase, GoldDark, GoldDarker)
)

// ═══════════════════════════════════════════════════════════════════
// 2026 ACCENT PALETTE (Electric Indigo / Aurora Cyan / Slate)
// Single source of truth for TopNavTab, TopNavIcon, GroupItem,
// CompactStreamCard, MiniPlayer, SettingsScreen, LoginActivity.
// ═══════════════════════════════════════════════════════════════════
val AccentIndigo       = PrimaryColor       // 0xFF7C6FFF — Electric Indigo
val AccentIndigoLight  = PrimaryLight       // 0xFF9D8CFF — focus highlight
val AccentIndigoDark   = PrimaryDark        // 0xFF5A47E0 — pressed
val AccentCyan         = SecondaryColor     // 0xFF4FF0E0 — Aurora Cyan
val AccentCyanLight    = SecondaryLight     // 0xFF8FF7EE — highlight

// Slate surfaces — kept for backwards compat with existing code that
// imports them directly. Aliased to the new glassmorphic palette so
// the entire app updates uniformly.
val SlateSurface       = GlassSurface       // 0xFF12131F
val SlateSurfaceAlt    = GlassSurfaceAlt    // 0xFF1A1B2E
val SlateBorder        = DividerDark        // 0xFF232536
val SlateBorderLight   = Color(0xFF363850)  // Slightly visible border
val SlateTextPrimary   = OnBackgroundDark   // 0xFFEEF1FF
val SlateTextSecondary = Color(0xFF94A3B8)
val SlateTextMuted     = Color(0xFF64748B)

// ── Deep Space background gradient (vertical, top → bottom) ──
val DeepSpaceBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        BackgroundDarkTop,   // 0xFF0A0B14 — top
        Color(0xFF070810),   // mid
        BackgroundDark       // 0xFF050608 — bottom
    )
)

// ── Cinematic radial glow (overlay on top of Deep Space background) ──
// Used by HubActivity root container — very low alpha so it reads as a
// subtle ambient glow, not a flat color.
val CinematicGlowBrush = Brush.radialGradient(
    colors = listOf(
        AccentIndigo.copy(alpha = 0.14f),
        AccentCyan.copy(alpha = 0.10f),
        Color.Transparent
    )
)

// ── Accent gradient brushes (2026 horizontal indigo→cyan) ──
val AccentGradientBrush = Brush.horizontalGradient(
    colors = listOf(AccentIndigo, AccentCyan)
)
val AccentBorderBrush = Brush.horizontalGradient(
    colors = listOf(AccentIndigoLight, AccentCyanLight)
)

// ── Card focus glow (used by stream cards + category cards) ──
val CardFocusGlowBrush = Brush.radialGradient(
    colors = listOf(
        AccentIndigo.copy(alpha = 0.22f),
        Color.Transparent
    )
)

// ═══════════════════════════════════════════════════════════════════
// NEON ACCENTS — used by the EPG panel badge + capsule button glow.
// ═══════════════════════════════════════════════════════════════════
val NeonGreen      = Color(0xFF39FF14)   // Classic neon green — EPG "LIVE" badge
val NeonGreenSoft  = Color(0xFF4DFF35)   // Softer variant for text readability

// Live indicator pulse color (red, for the pulsing dot in EPG panel)
val LiveRed        = Color(0xFFFF3B5C)

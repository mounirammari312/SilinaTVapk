package com.superz.iptvplayer.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v2.0.4 — THE PREMIUM CROWN.
 *
 * The user's directive: "وضعت ايقونة على شكل نجمة هذا ليس احترافي بتاتاً —
 * يجب ان تكون ايقونة بريميوم مثل ايقونات التطبيقات الاحترافية" (a star is
 * not professional; premium needs the crown professional apps use).
 *
 * A hand-drawn metallic crown as a single [ImageVector] (24×24 viewport):
 *
 *   • 3-peak body with rounded joins, filled with a vertical CHAMPAGNE →
 *     RICH-GOLD → BRONZE ramp — the same metallic language as the golden
 *     code pill family (VuGold), so the icon sits natively inside every
 *     golden surface;
 *   • three bright orbs capping the peaks (the classic jewel tips);
 *   • a deeper-gold base band with three gem dots;
 *   • hairline deep-bronze outlines everywhere — crisp from 12sdp (hub
 *     icon row) to 56sdp (the premium page hero).
 *
 * TWO palettes, one geometry (see [buildOriaCrown]):
 *  • [OriaCrown] — full metallic gold, for DARK faces (unfocused golden
 *    pills, page headers on the app background);
 *  • [OriaCrownOnGold] — deep-bronze relief, for SOLID-GOLD faces (the
 *    focused pill state, where a gold-on-gold icon would dissolve).
 *
 * Pure Compose vector — no XML resource, scales losslessly, and renders
 * identically on every API level the app supports.
 */
val OriaCrown: ImageVector by lazy { buildOriaCrown(onGold = false) }

/** The crown recolored for solid-gold faces (see [OriaCrown]). */
val OriaCrownOnGold: ImageVector by lazy { buildOriaCrown(onGold = true) }

/**
 * v2.0.5 — the crown's metallic palette, extracted so the regression
 * test can assert the icon is GOLD, never black (the v2.0.4 field
 * report). Internal for [OriaCrownTest].
 */
internal data class CrownPalette(
    val bodyTop: Color,      // champagne / bronze-relief top of the body ramp
    val bodyMid: Color,      // rich gold / deep relief
    val bodyFoot: Color,     // bronze foot of the body ramp
    val bandTop: Color,      // base-band ramp top
    val bandFoot: Color,     // base-band ramp foot
    val outline: Color,      // hairline outline of body + band
    val jewel: Color         // the three peak orbs + band gems
)

/** Full metallic gold — the DARK-face palette (unfocused pills, headers). */
internal val CrownMetallic = CrownPalette(
    bodyTop = Color(0xFFFBE7A1), bodyMid = Color(0xFFE8BC55), bodyFoot = Color(0xFF9A6A1E),
    bandTop = Color(0xFFDCA94A), bandFoot = Color(0xFF7A5416),
    outline = Color(0xFF6B4A12), jewel = Color(0xFFFFF6CE)
)

/** Deep-bronze relief — the SOLID-GOLD-face palette (focused pill state). */
internal val CrownBronzeRelief = CrownPalette(
    bodyTop = Color(0xFF8A5F17), bodyMid = Color(0xFF5C3D0E), bodyFoot = Color(0xFF3A2708),
    bandTop = Color(0xFF9A6A1E), bandFoot = Color(0xFF4A320C),
    outline = Color(0xFF2E1F06), jewel = Color(0xFFB98A25)
)

private fun buildOriaCrown(onGold: Boolean): ImageVector {
    val p = if (onGold) CrownBronzeRelief else CrownMetallic
    val midStop = if (onGold) 0.55f else 0.5f
    return ImageVector.Builder(
        name = if (onGold) "OriaCrownOnGold" else "OriaCrown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
    // ── crown body: 3-peak silhouette, metallic vertical ramp across the
    //    body's own extent (viewport units) — champagne tips, bronze foot ──
    path(
        fill = Brush.linearGradient(
            colorStops = arrayOf(0f to p.bodyTop, midStop to p.bodyMid, 1f to p.bodyFoot),
            start = Offset(0f, 3f), end = Offset(0f, 17f)
        ),
        stroke = SolidColor(p.outline),
        strokeLineWidth = 0.55f,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(3.2f, 17.0f)
        lineTo(3.2f, 6.4f)
        lineTo(8.0f, 10.6f)
        lineTo(12.0f, 3.4f)
        lineTo(16.0f, 10.6f)
        lineTo(20.8f, 6.4f)
        lineTo(20.8f, 17.0f)
        close()
    }
    // ── base band: deeper gold, a touch wider than the body's foot ──
    path(
        fill = Brush.linearGradient(
            colorStops = arrayOf(0f to p.bandTop, 1f to p.bandFoot),
            start = Offset(0f, 17.5f), end = Offset(0f, 20f)
        ),
        stroke = SolidColor(p.outline),
        strokeLineWidth = 0.5f,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(4.1f, 17.7f)
        lineTo(19.9f, 17.7f)
        quadTo(20.8f, 17.7f, 20.8f, 18.6f)
        lineTo(20.8f, 19.0f)
        quadTo(20.8f, 19.9f, 19.9f, 19.9f)
        lineTo(4.1f, 19.9f)
        quadTo(3.2f, 19.9f, 3.2f, 19.0f)
        lineTo(3.2f, 18.6f)
        quadTo(3.2f, 17.7f, 4.1f, 17.7f)
        close()
    }
    // ── the three jewel tips capping the peaks ──
    path(
        fill = SolidColor(p.jewel),
        stroke = SolidColor(if (onGold) CrownBronzeRelief.bodyFoot else CrownMetallic.bodyFoot),
        strokeLineWidth = 0.45f,
        strokeLineJoin = StrokeJoin.Round
    ) {
        circle(3.4f, 5.9f, 1.45f)
        circle(12.0f, 3.0f, 1.65f)
        circle(20.6f, 5.9f, 1.45f)
    }
    // ── gem dots on the band ──
    path(
        fill = SolidColor(if (onGold) CrownBronzeRelief.bandFoot else CrownMetallic.jewel),
        stroke = null
    ) {
        circle(7.4f, 18.8f, 0.62f)
        circle(12.0f, 18.8f, 0.62f)
        circle(16.6f, 18.8f, 0.62f)
    }
    }.build()
}

/** Circle via four cubic segments (kappa ≈ 0.5523·r) — no arcTo API quirks. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    val k = 0.552284749831f * r
    moveTo(cx + r, cy)
    curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    close()
}

/**
 * v2.0.5 — THE CANONICAL CROWN RENDERER.
 *
 * ROOT CAUSE of the user's v2.0.4 report (“ايقونة بريميوم سوداء… غير ضاهرة
 * نهائيا”): every crown was drawn through Material's `Icon()`, whose
 * DEFAULT `tint = LocalContentColor.current` wraps the painter in a
 * `ColorFilter.tint` that REPLACES every fill/stroke the vector carries —
 * the champagne→gold→bronze ramp never rendered and the crown collapsed
 * into one flat dark silhouette (“pitch black”).
 *
 * THE CONTRACT: the crown must ALWAYS be drawn through [VuCrownIcon] (or a
 * raw `Image` + `rememberVectorPainter`) — NEVER through `Icon()` without
 * `tint = Color.Unspecified`. This renderer attaches NO color filter, so
 * the vector's own metal is what the user sees, on every face it touches.
 */
@Composable
fun VuCrownIcon(
    onGold: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    Image(
        painter = rememberVectorPainter(if (onGold) OriaCrownOnGold else OriaCrown),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
        // NO colorFilter — the crown carries its own metallic palette.
    )
}

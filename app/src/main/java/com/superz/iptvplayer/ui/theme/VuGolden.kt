package com.superz.iptvplayer.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v1.19.1 — THE GOLDEN EFFECT, take two.
 *
 *  v1.19.0 drew the ring as a CONIC (angular) gradient rotating about
 *  the button's CENTER — on a wide pill that reads as bright wedges
 *  spinning around the middle: the user called it “مروحة هليكوبتر”.
 *  A real premium halo is parametrized by ARC LENGTH ALONG THE BORDER,
 *  never by angle about the center.
 *
 *  Three synchronized layers, none of them a blink:
 *
 *   1. vuGoldenBorder — a static polished-gold base ring + TWO soft
 *      light beads that GLIDE ALONG the outline (top edge → around the
 *      corner → down the side → back), one full orbit every ~3.4s.
 *      Built with a cached Path + dash pattern whose period equals the
 *      exact rounded-rect perimeter, so the bead wraps the corners
 *      seamlessly and can never look like a rotor about the center.
 *   2. vuGoldenGlow — a breathing radial halo (gold luminance rising
 *      and falling over ~2.4s) painted BEHIND the surface; on the
 *      translucent glass faces it reads as warm light glowing from
 *      within the pill.
 *   3. vuGoldShimmer — a narrow diagonal highlight band traversing the
 *      surface every ~2.9s (the specular glint that moves across real
 *      gold when you tilt it).
 *
 *  v1.19.3 — TRUE GOLD, not yellow (vertical light + cylinder falloff;
 *  see vuGoldFace). v1.19.6 — DEEP METAL, every stop re-anchored on
 *  classic METALLIC GOLD #D4AF37 with a champagne specular #EFC75A and
 *  a bronze floor #8A6415; hue stays 41–46° the whole way down.
 *
 *  v2.0.0 — THE PANEL-DRIVEN ACCENT. Every member of the family is now
 *  Compose snapshot state behind the [VuGold] object, and [VuGold.applyAccent]
 *  re-derives the whole family from ANY base color the admin panel saves
 *  (the deltas — each member's own hue/sat/lightness offset from the gold
 *  anchor — are computed FROM the hand-tuned constants, so a custom accent
 *  inherits exactly the metallic structure that survived six user-review
 *  iterations). Asking for the default #D4AF37 restores the hand-tuned
 *  values byte-for-byte: zero drift for every install that never touches
 *  the panel. All 131 usage sites read through the object, so nothing
 *  anywhere else had to change.
 * ═══════════════════════════════════════════════════════════════════
 */
object VuGold {

    // ── the hand-tuned v1.19.6 family (immutable originals) ──────────
    private val HAND_PALE = Color(0xFFFFEDA6)    // champagne-cream specular
    private val HAND_GOLD = Color(0xFFD4AF37)    // THE classic metallic gold
    private val HAND_RICH = Color(0xFFBE912F)    // aged-gold body
    private val HAND_DEEP = Color(0xFF8A6415)    // bronze floor
    private val HAND_TEXT = Color(0xFFF0C24E)    // deep amber text on dark
    private val HAND_ONGOLD = Color(0xFF3A2506)  // dark bronze on solid gold
    private val HAND_BRIGHT = Color(0xFFEFC75A)  // champagne specular, saturated
    private val HAND_EMBER = Color(0xFFA3771F)   // aged gold lower body
    private val HAND_SHEEN = Color(0xFFDFAF42)   // shoulder under the specular

    /** The anchor the whole family is derived from. */
    val DEFAULT_GOLD: Color = HAND_GOLD

    // ── live state (panel-overridable) ───────────────────────────────

    /** Highlight — hot core of the orbiting beads and the shimmer glint.
     *  TINY specular touches only (wide cream zones are what washed
     *  v1.19.1 out to plain yellow). */
    var Pale: Color by mutableStateOf(HAND_PALE)
        private set

    /** Primary gold — the color the eye names “gold” on sight. */
    var Gold: Color by mutableStateOf(HAND_GOLD)
        private set

    /** Mid metallic amber — aged-gold body. */
    var Rich: Color by mutableStateOf(HAND_RICH)
        private set

    /** Shadowed gold — the bronze floor of the sweep. */
    var Deep: Color by mutableStateOf(HAND_DEEP)
        private set

    /** Text/icon gold for dark backgrounds — deep amber, never lemon. */
    var Text: Color by mutableStateOf(HAND_TEXT)
        private set

    /** Focused-face text — dark bronze on the solid-gold face. */
    var OnGold: Color by mutableStateOf(HAND_ONGOLD)
        private set

    /** Specular top of the metal — champagne gold, saturated, never cream. */
    var Bright: Color by mutableStateOf(HAND_BRIGHT)
        private set

    /** Deep amber — the lower body of the solid-gold sweep. */
    var Ember: Color by mutableStateOf(HAND_EMBER)
        private set

    /** The shoulder between the champagne specular and the metal body. */
    var Sheen: Color by mutableStateOf(HAND_SHEEN)
        private set

    /**
     * The focused face's TRUE solid-gold gradient — vertical metallic
     * sweep (narrow champagne glint at the top, gold body, long fall to
     * bronze) — rebuilt whenever the accent changes.
     */
    var FaceBrush: Brush by mutableStateOf(handFaceBrush())
        private set

    /** The border-bead layer stack (hot core → wide soft skirt). */
    internal var BeadLayers: List<BeadLayer> by mutableStateOf(handBeadLayers())
        private set

    /** The cylinder falloff — dark bronze at the horizontal extremes. */
    internal var CylinderBrush: Brush by mutableStateOf(handCylinderBrush())
        private set

    /** One full orbit of the border light, ms.
     *  v2.2.1 — 3400 → 4400 (user: "تقلل من سرعة الحلقة الذهبية قليلا" —
     *  the orbiting beads calm down ~29%, a slower, more premium glide). */
    const val HALO_PERIOD_MS = 4_400

    /** One breath of the glow, ms. */
    const val GLOW_PERIOD_MS = 2_400

    /** One traversal of the shimmer band, ms. */
    const val SHIMMER_PERIOD_MS = 2_900

    /** v2.2.1 — THE DARK-GLASS LEVEL of the plan-card family (user:
     *  "ترفع درجة الزجاج الداكن قليلا"): every resting plan-card face —
     *  the subscribe page's plan cards, the contact CTA and every button
     *  wearing [vuPlanCardStyle]/[vuPlanCardRowStyle] — was
     *  PanelOverlay.copy(alpha = 0.6f); it now reads 0.74f: a touch more
     *  opaque, a touch deeper, still translucent glass (the stock
     *  background breathes through). One constant, one design.
     *  v2.2.3 — raised again (user: "قم برفع دكانة الخلفية الزجاجية
     *  قليلا"): 0.74 → 0.80 — the family now lands on the player and the
     *  login forms, and the deeper glass keeps the gold ring and the
     *  warm-bronze lit face readable over LIVE video and the login
     *  background alike. */
    const val PLAN_GLASS_ALPHA = 0.80f

    // ── derivation ───────────────────────────────────────────────────

    /** Per-member offsets measured from the hand-tuned family itself. */
    private data class Off(val dh: Float, val ds: Float, val dl: Float)
    private val OFF_PALE = off(HAND_PALE)
    private val OFF_RICH = off(HAND_RICH)
    private val OFF_DEEP = off(HAND_DEEP)
    private val OFF_TEXT = off(HAND_TEXT)
    private val OFF_ONGOLD = off(HAND_ONGOLD)
    private val OFF_BRIGHT = off(HAND_BRIGHT)
    private val OFF_EMBER = off(HAND_EMBER)
    private val OFF_SHEEN = off(HAND_SHEEN)

    private fun off(c: Color): Off {
        val b = hslOf(HAND_GOLD)
        val m = hslOf(c)
        return Off(m.h - b.h, m.s - b.s, m.l - b.l)
    }

    /**
     * v2.0.0 — repaint the whole metallic family from one base color.
     * The DEFAULT gold restores the hand-tuned constants exactly; any
     * other color inherits their relative hue/sat/lightness structure.
     */
    fun applyAccent(base: Color) {
        if (base == HAND_GOLD) {
            Pale = HAND_PALE; Gold = HAND_GOLD; Rich = HAND_RICH
            Deep = HAND_DEEP; Text = HAND_TEXT; OnGold = HAND_ONGOLD
            Bright = HAND_BRIGHT; Ember = HAND_EMBER; Sheen = HAND_SHEEN
        } else {
            val b = hslOf(base)
            Pale = shifted(b, OFF_PALE)
            Gold = base
            Rich = shifted(b, OFF_RICH)
            Deep = shifted(b, OFF_DEEP)
            Text = shifted(b, OFF_TEXT)
            OnGold = shifted(b, OFF_ONGOLD)
            Bright = shifted(b, OFF_BRIGHT)
            Ember = shifted(b, OFF_EMBER)
            Sheen = shifted(b, OFF_SHEEN)
        }
        FaceBrush = Brush.verticalGradient(
            0.00f to Bright,   // champagne specular — saturated, never cream
            0.10f to Sheen,    // fast shoulder — the glint stays NARROW
            0.35f to Gold,     // the body
            0.65f to Rich,     // aged-gold lower body
            0.88f to Ember,    // deep amber
            1.00f to Deep      // bronze floor
        )
        BeadLayers = listOf(
            BeadLayer(0.035f, Pale, 0.95f, 1.25f),    // hot core
            BeadLayer(0.075f, Gold, 0.55f, 1.05f),
            BeadLayer(0.115f, Gold, 0.32f, 1.00f),
            BeadLayer(0.165f, Rich, 0.17f, 0.95f),
            BeadLayer(0.230f, Rich, 0.085f, 0.90f)    // wide soft skirt
        )
        CylinderBrush = Brush.horizontalGradient(
            0.00f to Deep.copy(alpha = 0.34f),
            0.16f to Color.Transparent,
            0.84f to Color.Transparent,
            1.00f to Deep.copy(alpha = 0.34f)
        )
    }

    private fun shifted(base: Hsl, o: Off): Color =
        hslColor(
            (base.h + o.dh + 360f) % 360f,
            (base.s + o.ds).coerceIn(0f, 1f),
            (base.l + o.dl).coerceIn(0f, 1f)
        )

    private fun handFaceBrush(): Brush = Brush.verticalGradient(
        0.00f to HAND_BRIGHT,
        0.10f to HAND_SHEEN,
        0.35f to HAND_GOLD,
        0.65f to HAND_RICH,
        0.88f to HAND_EMBER,
        1.00f to HAND_DEEP
    )

    private fun handBeadLayers(): List<BeadLayer> = listOf(
        BeadLayer(0.035f, HAND_PALE, 0.95f, 1.25f),   // hot core
        BeadLayer(0.075f, HAND_GOLD, 0.55f, 1.05f),
        BeadLayer(0.115f, HAND_GOLD, 0.32f, 1.00f),
        BeadLayer(0.165f, HAND_RICH, 0.17f, 0.95f),
        BeadLayer(0.230f, HAND_RICH, 0.085f, 0.90f)   // wide soft skirt
    )

    private fun handCylinderBrush(): Brush = Brush.horizontalGradient(
        0.00f to HAND_DEEP.copy(alpha = 0.34f),
        0.16f to Color.Transparent,
        0.84f to Color.Transparent,
        1.00f to HAND_DEEP.copy(alpha = 0.34f)
    )
}

/** HSL triple: h in [0,360), s/l in [0,1]. */
internal data class Hsl(val h: Float, val s: Float, val l: Float)

/** Color → HSL (standard formula, hue-first for a red-max base). */
internal fun hslOf(c: Color): Hsl {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Hsl(0f, 0f, l)
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return Hsl(h, s, l)
}

/** HSL → opaque Color. */
internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val s2 = s.coerceIn(0f, 1f)
    val l2 = l.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * l2 - 1f)) * s2
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l2 - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}

/**
 * One layer of the orbiting light bead: dash length as a FRACTION of
 * the border perimeter, its color/alpha/stroke-scale. Five stacked
 * symmetric layers (short+bright → long+dim) build a soft glow with a
 * hot core — the opposite of a hard "dashed line".
 */
internal class BeadLayer(val span: Float, val color: Color, val alpha: Float, val widthScale: Float)

/**
 * Per-geometry cache — the border Path is rebuilt only when size /
 * radius / stroke actually change, never per animation frame.
 */
private class GoldenRingCache {
    var w = -1f
    var h = -1f
    var radius = -1f
    var stroke = -1f
    var perimeter = 0f
    val path: Path = Path()

    fun ensure(w: Float, h: Float, radiusPx: Float, strokePx: Float) {
        if (w == this.w && h == this.h && radiusPx == this.radius && strokePx == this.stroke) return
        this.w = w
        this.h = h
        this.radius = radiusPx
        this.stroke = strokePx
        val half = strokePx / 2f
        // Keep the whole stroke inside the bounds; pill shapes clamp to a stadium.
        val maxR = (minOf(w, h) / 2f - half).coerceAtLeast(0f)
        val r = radiusPx.coerceIn(0f, maxR)
        path.reset()
        // the 5-arg RoundRect factory rounds ALL FOUR corners uniformly
        path.addRoundRect(RoundRect(half, half, w - half, h - half, CornerRadius(r, r)))
        // EXACT rounded-rect perimeter: 2·(W−2r) + 2·(H−2r) + 2πr.
        // The dash pattern period equals this, so the bead wraps the
        // closed loop seamlessly (pattern period == path length).
        val iw = (w - strokePx).coerceAtLeast(0f)
        val ih = (h - strokePx).coerceAtLeast(0f)
        perimeter = 2f * (iw - 2f * r) + 2f * (ih - 2f * r) + 2f * PI.toFloat() * r
    }
}

/**
 * v1.19.12 — THE ROBOLECTRIC GATE. Robolectric's paused looper drains
 * the message queue synchronously; a Compose infinite animation
 * self-reposts frame callbacks FOREVER, so that drain becomes a
 * CPU-livelock (the Recomposer's withFrameNanos loop) — the launch
 * smoke test's ActivityController.setup() never returns. Under the JVM
 * test environment the golden layers render their STATIC pass only;
 * real devices animate exactly as before. Detected once, off the
 * composition path (Build.FINGERPRINT is "robolectric" only in tests).
 */
internal val VuGoldAnimates: Boolean =
    !(android.os.Build.FINGERPRINT ?: "").startsWith("robolectric")

/**
 * v1.19.12 — the shared animated-float source for the golden layers:
 * the infinite transition on real devices, a frozen value under
 * Robolectric (see [VuGoldAnimates]). The frozen value sits mid-cycle
 * so the static pass still shows a lit ring / a soft glow / a visible
 * glint band.
 */
@Composable
private fun infiniteGoldFloat(
    label: String,
    periodMs: Int,
    easing: androidx.compose.animation.core.Easing = LinearEasing,
    repeatMode: RepeatMode = RepeatMode.Restart,
    frozen: Float
): State<Float> =
    if (VuGoldAnimates) {
        rememberInfiniteTransition(label = label).animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = easing),
                repeatMode = repeatMode
            ),
            label = label
        )
    } else {
        remember { mutableStateOf(frozen) }
    }

/**
 * Layer 1 — the golden halo: a subtle always-on polished-gold base
 * ring, plus two soft light beads orbiting ALONG the border itself
 * (arc-length parametrized — they glide over the top edge, round the
 * corner, down the side and back; they are never wedges spinning
 * about the button's center). The second bead rides the antipode at
 * reduced intensity so the ring feels alive from every angle.
 *
 * v1.19.2 — drawn OVER the content's edges (drawWithContent, after
 * drawContent()): the ring stays visible even on OPAQUE faces (the
 * hub cards' solid gradients, the focused solid-gold buttons) where
 * the old drawBehind layer vanished underneath the background. Apply
 * BEFORE .clip() in the chain so the ring itself is not clipped.
 *
 * v2.0.0 — reads the live [VuGold] family, so a panel accent change
 * repaints every golden border in the app.
 */
@Composable
fun Modifier.vuGoldenBorder(
    cornerRadius: Dp,
    strokeWidth: Dp = 1.6.dp,
    focused: Boolean = false
): Modifier {
    val travel by infiniteGoldFloat(label = "haloTravel", periodMs = VuGold.HALO_PERIOD_MS, frozen = 0.35f)
    val width = if (focused) strokeWidth * 1.4f else strokeWidth
    // Composition-time read of the live family — an accent change
    // recomposes this modifier and redraws the ring in the new metal.
    val beadLayers = VuGold.BeadLayers
    val ringBase = VuGold.Deep
    val ringMid = VuGold.Gold
    val cache = remember { GoldenRingCache() }
    return this.drawWithContent {
        drawContent()   // the face (and children) first — the ring rides ON TOP
        val strokePx = width.toPx()
        cache.ensure(size.width, size.height, cornerRadius.toPx(), strokePx)
        val p = cache.perimeter
        if (p <= 8f) return@drawWithContent

        // ── (a) static base ring — the "gold-plated wire" that is always
        // visible, so the border is defined even where the beads aren't.
        val ringAlpha = if (focused) 0.60f else 0.40f
        drawPath(
            path = cache.path,
            brush = Brush.verticalGradient(
                0.0f to ringBase.copy(alpha = ringAlpha),
                0.5f to ringMid.copy(alpha = ringAlpha * 0.75f),
                1.0f to ringBase.copy(alpha = ringAlpha)
            ),
            style = Stroke(width = strokePx)
        )

        // ── (b) the two orbiting beads. Each layer is ONE dash of a dash
        // pattern whose period equals the perimeter (dash + gap = P), so
        // exactly one instance exists and it crosses the seam smoothly.
        // The dash sits centered on the bead's position: whichever sign
        // convention the platform uses for the dash phase, the layered
        // result renders as a symmetric glow gliding around the loop.
        for (bead in 0 until 2) {
            val center = (((travel + if (bead == 0) 0f else 0.5f) % 1f) + 1f) % 1f * p
            val dim = if (bead == 0) 1f else 0.55f
            for (layer in beadLayers) {
                val dash = layer.span * p
                val start = center - dash / 2f
                val phase = ((p - start) % p + p) % p
                drawPath(
                    path = cache.path,
                    color = layer.color.copy(alpha = (layer.alpha * dim).coerceIn(0f, 1f)),
                    style = Stroke(
                        width = strokePx * layer.widthScale,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(dash, p - dash),
                            phase
                        )
                    )
                )
            }
        }
    }
}

/**
 * Layer 2 — the breathing golden halo, painted BEHIND everything on the
 * modifier's bounds. On a translucent surface it glows through the
 * glass; on an opaque one it frames the edges.
 */
@Composable
fun Modifier.vuGoldenGlow(maxAlpha: Float = 0.34f): Modifier {
    val breath by infiniteGoldFloat(
        label = "glowBreath",
        periodMs = VuGold.GLOW_PERIOD_MS,
        easing = androidx.compose.animation.core.FastOutSlowInEasing,
        repeatMode = RepeatMode.Reverse,
        frozen = 0.5f
    )
    val glowCore = VuGold.Gold        // composition-time read (live accent)
    val glowHalo = VuGold.Rich
    return this.drawBehind {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = maxOf(size.width, size.height) * 0.72f
        val alpha = maxAlpha * (0.55f + 0.45f * breath)
        drawRect(
            brush = Brush.radialGradient(
                0.0f to glowCore.copy(alpha = alpha),
                0.62f to glowHalo.copy(alpha = alpha * 0.45f),
                1.0f to Color.Transparent,
                center = center,
                radius = radius
            ),
            size = size
        )
    }
}

/**
 * Layer 3 — the specular glint: a soft diagonal band of pale gold that
 * travels across the surface (drawn OVER the content, inside bounds).
 * Apply to the CLIPPED face so the band never spills outside.
 */
@Composable
fun Modifier.vuGoldShimmer(): Modifier {
    val progress by infiniteGoldFloat(label = "shimmerProgress", periodMs = VuGold.SHIMMER_PERIOD_MS, frozen = 0.25f)
    val glint = VuGold.Pale          // composition-time read (live accent)
    return this.drawWithContent {
        drawContent()
        val band = size.width * 0.42f
        val x = -band + (size.width + 2f * band) * progress
        // slight pause between traversals: fade near the ends
        val edge = (progress * (1f - progress)) * 4f   // 0 at both ends, 1 mid
        val intensity = (0.35f + 0.65f * edge.coerceIn(0f, 1f))
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    glint.copy(alpha = 0.20f * intensity),
                    Color.Transparent
                ),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height)
            ),
            size = size
        )
    }
}

/**
 * THE TRUE SOLID-GOLD FACE — v1.19.3 geometry, v1.19.6 values, v2.0.0
 * live-accent brushes.
 *
 * Two stacked passes behind the content:
 *  (a) the METALLIC SWEEP — VuGold.FaceBrush, VERTICAL: saturated
 *      specular at the top falling through the body to the bronze
 *      floor. Metal is lit from above.
 *  (b) the CYLINDER — VuGold.CylinderBrush: dark falloff at the
 *      left/right extremes only. The curvature illusion that separates
 *      SHAPED metal from flat paint.
 *
 * Use in place of `Modifier.background(VuGold.FaceBrush)`, AFTER the
 * `.clip(shape)` in the chain (it draws the whole bounded rect, so it
 * must be clipped by the same shape the old background was).
 */
fun Modifier.vuGoldFace(): Modifier = this.drawBehind {
    // (a) the metallic sweep — light from above, saturated all the way down.
    drawRect(brush = VuGold.FaceBrush)
    // (b) the cylinder — the surface curving away at the horizontal extremes.
    drawRect(brush = VuGold.CylinderBrush)
}

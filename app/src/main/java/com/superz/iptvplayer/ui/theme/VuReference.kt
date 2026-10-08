package com.superz.iptvplayer.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superz.iptvplayer.R
import coil.compose.rememberAsyncImagePainter

/**
 * v1.4.7 — VU IPTV Player (the decompiled reference app) design tokens,
 * extracted VERBATIM from its resources:
 *
 *   drawable/background.xml          → gradient #1a1251 → #3d1f39 (angle 0 = L→R)
 *   drawable/live_category_bg.xml    → gradient @button_start_color → @button_end_color, radius 12sdp
 *   drawable/btn_item_type.xml       → same gradient (focused/selected), radius 13sdp
 *   drawable/main_left_bg.xml        → solid @button_trans_color, top corners 15sdp
 *   drawable/ly_surface_bg.xml       → solid @button_trans_color + 0.5dp white stroke, radius 2sdp
 *   drawable/round_search_bg.xml     → solid @button_trans_color, radius 13sdp
 *   drawable/item_category_selected_bg.xml → gradient, radius 10sdp
 *   drawable/round_white_corner.xml  → 1.5dp white stroke, radius 5sdp
 *   values/colors.xml:
 *     text_color            #ffffff
 *     button_start_color    #6c5ae0
 *     button_end_color      #9850ed
 *     button_trans_color    #33707070
 *     color_red             #e32735
 *     yellow                #f6dd00
 *     lb_grey               #888888
 *     button_vpn_end_color  #03dffe   (the CURRENT epg row color)
 *     black_65              #a6000000
 *
 * Layout constants mirror the reference's sdp dimens 1:1 (sdp ≈ dp on the
 * sw~411dp phones the reference targets in landscape).
 */
object VuPalette {
    /** drawable/background — the app-wide dark indigo → magenta gradient. */
    val BackgroundBrush = Brush.horizontalGradient(listOf(Color(0xFF1A1251), Color(0xFF3D1F39)))

    /** drawable/live_category_bg / btn_item_type selected — purple pill gradient. */
    val PurpleBrush = Brush.horizontalGradient(listOf(Color(0xFF6C5AE0), Color(0xFF9850ED)))

    val PurpleStart = Color(0xFF6C5AE0)
    val PurpleEnd = Color(0xFF9850ED)

    /** button_trans_color — translucent grey of every panel/overlay surface. */
    val PanelOverlay = Color(0x33707070)

    /** color_red — the LIVE badge. */
    val LiveRed = Color(0xFFE32735)

    /** yellow — the "Unavailable" epg state. */
    val Yellow = Color(0xFFF6DD00)

    /** lb_grey — inactive epg rows / muted labels. */
    val Grey = Color(0xFF888888)

    /** button_vpn_end_color — the CURRENT (row 0) epg row text color. */
    val Cyan = Color(0xFF03DFFE)

    /** button_vpn_start_color / button_vpn_end_color — the cyan→blue gradient
     *  of the user-list header pill (round_user_list_child_bg). */
    val VpnBrush = Brush.horizontalGradient(listOf(Color(0xFF007FFE), Color(0xFF03DFFE)))

    /** text_tint_color — the Add User pill's unfocused icon/text color. */
    val TintGrey = Color(0xFF9F94A4)

    /** ic_edit / ic_delete app:tint — the account row's action icons. */
    val IconGrey = Color(0xFFDADADA)

    /** black_65 — the grid favorite chip background. */
    val Black65 = Color(0xA6000000)

    /** text_color — plain white. */
    val White = Color(0xFFFFFFFF)

    /** v1.4.11 — colorPrimary: the intro pager's selected dot. */
    val SelectedDot = Color(0xFF6200EE)

    /** v1.4.11 — colorDesSecond: the intro slides' description text. */
    val DesSecond = Color(0xE6E9E9E9)

    /** v2.0.6 — NEON GREEN (user directive: the Free account tier label
     *  is the literal English word "Free" — never localized, on every
     *  card face). Classic neon #39FF14: legible on both the resting
     *  glass and the focused solid-gold account-card faces. */
    val NeonGreen = Color(0xFF39FF14)

    /** v1.4.11 — continue_back / continue_back_spinner: the REVERSED
     *  button gradient (#9850ed → #6c5ae0, angle 0). */
    val ContinueBrush = Brush.horizontalGradient(listOf(Color(0xFF9850ED), Color(0xFF6C5AE0)))
}

// ── Guideline percentages from activity_live_play.xml (of the FULL screen) ──
object VuSplit {
    const val VERTICAL_LINE = 0.35f   // player left edge / left panel right edge
    const val VERTICAL_LINE2 = 0.78f  // player right edge / info column left edge
    const val HORIZONTAL_LINE1 = 0.18f // player top edge
    const val HORIZONTAL_LINE2 = 0.65f // player bottom edge / epg top edge
}

// ── Top-bar anchoring, from activity_live_play.xml ──
// PremiumLL (crown + PREMIUM badge) is THE anchor element:
//   • ly_back and tx_system_time are vertically centered on it
//     (top/bottom constraints to PremiumLL),
//   • left_lay (the channel panel) sits toBottomOf PremiumLL + 5sdp.
// The logo's height is INTRINSIC (35sdp crown + 1dp + badge), so it is
// measured DIRECTLY and can never feed back into its own container.
// (v1.4.9 measured the wrapping Box instead — but the clock child's
// height depended on that same measurement, so the bar grew +15sdp per
// pass forever and sank the channel panel off the screen.)
object VuTopBarAnchor {
    const val PREMIUM_MARGIN_TOP_SDP = 15      // PremiumLL marginTop
    const val LEFT_PANEL_MARGIN_TOP_SDP = 5    // left_lay marginTop (toBottomOf PremiumLL)
    const val LOGO_HEIGHT_FALLBACK_SDP = 47    // 35sdp crown + 1dp + badge, first frame

    /** y of the PremiumLL band's bottom edge (sdp), given the logo's OWN height. */
    fun premiumBandBottomSdp(logoHeightSdp: Int?): Int =
        PREMIUM_MARGIN_TOP_SDP + (logoHeightSdp ?: LOGO_HEIGHT_FALLBACK_SDP)

    /** left_lay top edge (sdp): PremiumLL bottom + 5sdp. */
    fun leftPanelTopSdp(logoHeightSdp: Int?): Int =
        premiumBandBottomSdp(logoHeightSdp) + LEFT_PANEL_MARGIN_TOP_SDP

    /** left_lay height (sdp): fills to the bottom of the screen. */
    fun leftPanelHeightSdp(screenHeightSdp: Int, logoHeightSdp: Int?): Int =
        (screenHeightSdp - leftPanelTopSdp(logoHeightSdp)).coerceAtLeast(0)
}

// ═══════════════════════════════════════════════════════════════════
// v1.4.9 — sdp dimension layer.
//
// The reference app sizes EVERYTHING with the intuit sdp library: every
// _Nsdp token resolves per screen bucket — values-sw330dp → N×1.1dp,
// sw360 → N×1.2, … sw540 → N×1.8, … sw1080dp → N×3.6 — i.e. the value
// scales as smallestScreenWidthDp / 300 in 30dp steps (capped at sw1080;
// below sw330 the baseline 1.0 bucket applies). On the user's TV
// (2400×1080, sw = 540dp) every reference icon/text/pill renders at
// 1.8× the plain-dp size this app used until v1.4.8 — the root cause of
// the "icons and buttons are smaller" feedback. VuSdp reproduces the
// library's bucket math exactly, so every element now renders at the
// SAME pixel size as the reference on every device.
// ═══════════════════════════════════════════════════════════════════

/**
 * Reference sdp scaler: [d] converts an sdp token to Dp, [t] converts an
 * sdp textSize to TextUnit. Text in the reference is sized in sdp (NOT ssp),
 * so it ignores the system font scale — [t] divides by fontScale to
 * reproduce that visual size exactly.
 */
@Immutable
class VuSdp(internal val factor: Float, private val fontScale: Float) {
    fun d(v: Float): Dp = (v * factor).dp
    fun d(v: Int): Dp = (v * factor).dp
    fun t(v: Float): TextUnit = (v * factor / fontScale.coerceAtLeast(0.1f)).sp
    fun t(v: Int): TextUnit = t(v.toFloat())

    /** v1.4.11 — ssp semantics: the intro/login screens size SOME texts in
     *  _Nssp (scales WITH the system font scale, unlike sdp) — _10ssp on the
     *  intro button, _14ssp/_9ssp slide title/description, _13ssp spinner,
     *  _16ssp dialog title. This converts an ssp token (bucket-scaled, but
     *  font-scale driven like plain sp). */
    fun ts(v: Float): TextUnit = (v * factor).sp
    fun ts(v: Int): TextUnit = ts(v.toFloat())
}

/** The sdp library's bucket formula, verbatim (internal: unit-tested). */
internal fun bucketFactor(swDp: Int): Float {
    val bucket = (swDp / 30) * 30          // values-sw{bucket}dp selection
    return if (bucket < 330) 1f            // default values/ bucket below sw330
    else minOf(bucket, 1080) / 300f        // linear sw/300, capped at sw1080dp
}

@Composable
fun rememberVuSdp(): VuSdp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val sw = context.resources.configuration.smallestScreenWidthDp
        VuSdp(bucketFactor(sw), density.fontScale)
    }
}

/**
 * The reference app background applied to a modifier.
 *
 * v2.2.0 — THE GLOBAL BACKGROUND (user directive: "لماذا لا تكون صورة واحدة
 * فقط تشمل كل هذه الصفحات… صورة واحدة تغيّر كل الصفحات التي سنضيفها و
 * الصفحات الموجودة"): the shared stock gradient now carries the panel's
 * ONE global background image ([OriaBranding.bgGlobalUrl]) whenever the
 * admin sets it — so EVERY page that paints [vuBackground] (home hub,
 * channels grid, mini player, settings, language, catch-up, saved videos —
 * and any page added in the future) shows that single image, full-bleed
 * under the legibility scrim, with zero per-page wiring. Empty slot →
 * the stock gradient, exactly as before.
 */
@Composable
fun Modifier.vuBackground(): Modifier =
    vuRemoteBackgroundOver(OriaBranding.bgGlobalUrl, background(VuPalette.BackgroundBrush))

/**
 * v2.2.0 — the Deep Space pages' share of the global background: Movie /
 * Series info, the VOD trace and the playlists picker paint the deep-space
 * gradient; when the panel's global image is set it rides on top with the
 * same scrim + graceful fallback as every other page.
 */
@Composable
fun Modifier.vuGlobalDeepSpaceBackground(withCinematicGlow: Boolean = true): Modifier =
    vuRemoteBackgroundOver(OriaBranding.bgGlobalUrl, deepSpaceBackground(withCinematicGlow))

/**
 * v2.0.0 — THE PANEL-CONTROLLED BACKGROUND.
 *
 * Remote variant of [vuBackground]: when [url] is set the image is drawn
 * full-bleed over the stock gradient (Crop), with a dark scrim on top so
 * every white/gold element keeps its contrast on any photo. While the
 * image loads — or if its URL ever dies — the stock gradient simply
 * shows through: the page never has a hole.
 *
 * v2.0.3 — generalized into [vuRemoteBackgroundOver] so pages with a
 * DIFFERENT stock fallback (the loading screen's Deep Space gradient)
 * can share the same remote-image pipeline.
 */
@Composable
fun Modifier.vuRemoteBackgroundOver(url: String, base: Modifier): Modifier {
    if (url.isBlank()) return base
    val resolved = remember(url) { resolveRemoteUrl(url) }
        ?: return base
    val painter = rememberAsyncImagePainter(
        model = resolved,
        contentScale = ContentScale.Crop
    )
    return this
        .then(base)
        .paint(painter)
        .background(Color(0xB3000000))   // legibility scrim over any photo
}

/** The stock remote background over the reference gradient. */
@Composable
fun Modifier.vuRemoteBackground(url: String): Modifier =
    vuRemoteBackgroundOver(url, background(VuPalette.BackgroundBrush))

/**
 * v2.0.0 — the app-opening / connection-method picker page background
 * (panel slot branding.bgEntryUrl; falls back to the stock gradient).
 * v2.2.0 — the GLOBAL background wins when the panel sets one; the
 * per-page slot remains the fallback for a blank global slot.
 */
@Composable
fun Modifier.vuEntryBackground(): Modifier =
    vuRemoteBackground(OriaBranding.bgGlobalUrl.ifBlank { OriaBranding.bgEntryUrl })

/**
 * v2.0.0 — the login form page background
 * (panel slot branding.bgLoginUrl; falls back to the stock gradient).
 * v2.2.0 — the GLOBAL background wins when the panel sets one.
 */
@Composable
fun Modifier.vuLoginBackground(): Modifier =
    vuRemoteBackground(OriaBranding.bgGlobalUrl.ifBlank { OriaBranding.bgLoginUrl })

/**
 * v2.0.3 — the accounts list page background (panel slot
 * branding.bgAccountsUrl; falls back to the stock reference gradient —
 * exactly what the page painted before the slot existed).
 * v2.2.0 — the GLOBAL background wins when the panel sets one.
 */
@Composable
fun Modifier.vuAccountsBackground(): Modifier =
    vuRemoteBackgroundOver(
        OriaBranding.bgGlobalUrl.ifBlank { OriaBranding.bgAccountsUrl },
        background(VuPalette.BackgroundBrush)
    )

/**
 * v2.0.3 — the loading/sync screen background (panel slot
 * branding.bgLoadingUrl; falls back to the page's own Deep Space
 * gradient + cinematic glow — the pre-v2.0.3 look, byte-for-byte).
 * v2.2.0 — the GLOBAL background wins when the panel sets one.
 */
@Composable
fun Modifier.vuLoadingBackground(): Modifier =
    vuRemoteBackgroundOver(
        OriaBranding.bgGlobalUrl.ifBlank { OriaBranding.bgLoadingUrl },
        deepSpaceBackground()
    )

// ═══════════════════════════════════════════════════════════════
// v2.2.0 — THE PLAN-CARD BUTTON CONTRACT (user directive: the focus
// buttons of the channels grid, the mini player page and the movie info
// page must carry the plan cards' design "حرفياً" — the same animated
// golden border, the same dark glass resting face, the same warm-bronze
// focused face, gold content). PremiumScreen's PremiumPlanCard /
// PremiumContactCta own the original; every modifier below reproduces
// their exact stack so the family reads as ONE design language:
//
//   .vuGoldenBorder(radius, 1.6dp lit / 0.8dp rest, focused)
//   .clip(shape)
//   .background(0x8C301F08 lit · warm bronze glass)   ← focus
//   .background(PanelOverlay 60% · dark glass)        ← rest
//
// [vuPlanCardStyle] is the one-line form for buttons (tabs, bars,
// chevron chips, play/download pills); [vuPlanCardRowStyle] is the
// list-row edition (categories, channels) where the animated ring
// composes ONLY while the row is lit — a 500-row channel list must
// never spin 500 infinite transitions on a weak TV box — and the
// resting rows carry a cheap static gold hairline instead.
// ═══════════════════════════════════════════════════════════════

/**
 * The plan-card contract for BUTTON-shaped controls: animated golden
 * ring (always on — buttons are few per screen), dark glass at rest,
 * warm-bronze glass while focused or selected, gold content.
 * Apply BEFORE any content padding; pair with VuGold.Text content.
 */
@Composable
fun Modifier.vuPlanCardStyle(
    cornerRadius: Dp,
    focused: Boolean,
    selected: Boolean = false
): Modifier {
    val lit = focused || selected
    return this
        .vuGoldenBorder(
            cornerRadius = cornerRadius,
            strokeWidth = if (lit) 1.6.dp else 0.8.dp,
            focused = focused
        )
        .clip(RoundedCornerShape(cornerRadius))
        .then(
            if (lit) Modifier.background(Color(0x8C301F08))                             // warm bronze glass, lit
            else Modifier.background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA)) // dark glass (v2.2.1 — raised)
        )
}

/**
 * The plan-card contract for LIST ROWS (category sidebar, channel list):
 * same faces and ring while lit (focused or selected); at rest the row
 * wears the dark glass + a STATIC gold hairline (no animation cost) so a
 * long list still reads as a column of little plan cards on weak boxes.
 */
@Composable
fun Modifier.vuPlanCardRowStyle(
    cornerRadius: Dp,
    focused: Boolean,
    selected: Boolean = false
): Modifier {
    val lit = focused || selected
    return this
        .then(
            if (lit) Modifier.vuGoldenBorder(
                cornerRadius = cornerRadius,
                strokeWidth = 1.6.dp,
                focused = focused
            ) else Modifier.border(
                width = 0.8.dp,
                color = VuGold.Gold.copy(alpha = 0.30f),
                shape = RoundedCornerShape(cornerRadius)
            )
        )
        .clip(RoundedCornerShape(cornerRadius))
        .then(
            if (lit) Modifier.background(Color(0x8C301F08))                             // warm bronze glass, lit
            else Modifier.background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA)) // dark glass (v2.2.1 — raised)
        )
}

// ═══════════════════════════════════════════════════════════════════
// Shared reference widgets (identical on every replicated page)
// ═══════════════════════════════════════════════════════════════════

/**
 * PremiumLL — the logo block every page carries at the top-start.
 *
 * v2.0.6 — THE CROWN-BESIDE-THE-LOGO identity (user directive: "نزيل كلمة
 * premium التي تضهر تحت الشعار و نجعل الشعار ان تضع بجانه التاج الذهبي صغير
 * كي يضهر بشكل فخم"):
 *
 *   • the stock "Premium"/"غالي" 8sdp badge under the logo is GONE — the
 *     only text under the logo now is the white-label APP NAME, and only
 *     when the panel actually sets one (branding.appName);
 *   • a SMALL metallic-gold crown ([VuCrownIcon], 40% of the logo size) is
 *     pinned to the logo's top-end corner — half overlapping it, half
 *     beside it, the way professional subscription apps badge their mark —
 *     over a soft breathing golden halo.
 *   • v2.1.1 — THE CROWN IS THE LIVE SUBSCRIPTION STATE (user directive:
 *     "يجب عندما يكون الحساب عادي ليس premium ان لا تضهر ايقونة التاج على
 *     الشعار… تضهر فقط للحساب premium عند تفعيله و تختفي عند انتهاء اشتراك
 *     premium"): the gate is [PremiumAccess.active] — the same date-verified
 *     state that opens every v2.1.0 gate — NOT the panel's branding flag.
 *     A FREE account never wears the crown; the crown appears the moment a
 *     premium-host account with a future exp_date logs in, and disappears
 *     the day that subscription ends — on EVERY page at once (all screens
 *     source this one composable). The user always knows, at a glance,
 *     whether the account they hold is premium — the professional
 *     architecture (the crowned mark = active membership, nothing else);
 *   • geometry contract preserved: the block's measured height (onHeight)
 *     is the logo's own height when no app-name badge is set, and the
 *     corner crown draws OUTSIDE the box without growing it (no clip on
 *     this path), so every onHeight consumer keeps centering exactly as
 *     before. The first-frame fallback estimate (LOGO_HEIGHT_FALLBACK_SDP
 *     47) simply over-estimates for one frame — corrected on the first
 *     measure.
 *
 * @param onHeight measured height of the block in dp — every element that
 * the reference vertically centers on PremiumLL uses it.
 */
@Composable
fun VuPremiumLogo(
    modifier: Modifier = Modifier,
    logoSizeSdp: Int = 35,
    onHeight: (Dp) -> Unit = {}
) {
    val s = rememberVuSdp()
    val density = LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
    ) {
        // The logo + its corner crown. v2.0.0 — the panel can swap the logo
        // remotely (OriaBranding.logoUrl); blank URL, load-in-progress or a
        // dead link all fall back to the built-in oria_logo.png artwork.
        Box {
            OriaLogoImage(
                modifier = Modifier.size(s.d(logoSizeSdp)),
                remoteUrl = OriaBranding.logoUrl
            )
            // v2.0.6/v2.1.1 — the small golden crown beside the logo, pinned
            // over its top-end corner (mirrors with layout direction). Drawn
            // via VuCrownIcon so the metallic ramp survives (never Icon()).
            // v2.1.1 — gated on PremiumAccess.active: the account's LIVE
            // subscription state (premium-host account + unexpired Xtream
            // exp_date). Compose snapshot state → the crown flips on every
            // page the moment premium activates or expires.
            if (PremiumAccess.active) {
                val crown = (logoSizeSdp * 0.40f).toInt()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // peek past the corner: half its width outward, a
                        // touch above the top edge — a badge "beside" the mark
                        .offset(x = s.d(crown * 0.55f), y = -s.d(crown * 0.16f))
                        .vuGoldenGlow(maxAlpha = 0.38f)
                        .size(s.d(crown))
                ) {
                    VuCrownIcon(modifier = Modifier.fillMaxSize())
                }
            }
        }
        // v2.0.3/v2.0.6 — the badge under the logo is the white-label APP
        // NAME only, and only when the panel sets one. The reference's
        // verbatim "Premium" stock badge was REMOVED (user directive).
        if (OriaBranding.appName.isNotBlank()) {
            Text(
                OriaBranding.appName,
                color = VuPalette.White,
                fontSize = s.t(8),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp)   // literal 1dp in the XMLs
            )
        }
    }
}

/**
 * v2.0.0 — THE BRAND LOGO, one source everywhere: a remote image when the
 * panel sets one, the built-in oria_logo.png otherwise (and as the
 * placeholder/error fallback while loading). Remote URLs are resolved
 * against the panel's gateway when they carry the relative /api/asset form.
 */
@Composable
fun OriaLogoImage(
    modifier: Modifier = Modifier,
    remoteUrl: String = OriaBranding.logoUrl
) {
    val fallback = painterResource(R.drawable.oria_logo)
    val resolved = remember(remoteUrl) { resolveRemoteUrl(remoteUrl) }
    if (resolved == null) {
        Image(painter = fallback, contentDescription = null, modifier = modifier)
    } else {
        val painter = rememberAsyncImagePainter(
            model = resolved,
            placeholder = fallback,
            error = fallback,
            fallback = fallback
        )
        Image(painter = painter, contentDescription = null, modifier = modifier)
    }
}

/** Resolve a panel URL: relative /api/asset links get the gateway prefix. */
internal fun resolveRemoteUrl(url: String): String? =
    when {
        url.isBlank() -> null
        url.startsWith("/api/asset") -> com.superz.iptvplayer.data.remote.OriaRemote.GATEWAY_BASE + url
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> null
    }

/**
 * ly_back — the reference's back button: image_down chevron PNG drawn
 * fitCenter in a 12×25sdp box and rotated 90° clockwise (Android ImageView
 * rotation semantics; −90° in RTL so it points toward "back"), white tint,
 * + "Back" 12sdp roboto_medium (marginStart 5sdp). The whole row renders at
 * scale 0.9 and grows to 1.0 when focused (the reference's focus listener).
 */
@Composable
fun VuBackButton(onClick: () -> Unit) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = s.d(15))                 // marginStart 15sdp
            .graphicsLayer {
                val sc = if (focused) 1f else 0.9f     // scale 0.9 → 1.0 focused
                scaleX = sc
                scaleY = sc
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(R.drawable.vu_image_down),
            contentDescription = stringResource(R.string.cd_back),
            colorFilter = ColorFilter.tint(VuPalette.White),   // tint text_color
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = s.d(12), height = s.d(25))
                .graphicsLayer { rotationZ = if (rtl) -90f else 90f }
        )
        Spacer(Modifier.width(s.d(5)))                  // marginStart 5sdp
        Text(
            stringResource(R.string.vu_back),
            color = VuPalette.White,
            fontSize = s.t(12),
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * v2.0.6 — THE HOME BUTTON (user directive: the accounts header's Back
 * exited the whole app — "نجعله ب ايقونة home و عند النقر عليه يدخلك الى
 * صفحة تسجيل الدخول"). An icon-only control wearing the reference's
 * exact button language: white glyph, the 0.9 → 1.0 focus scale, and the
 * same 15sdp start margin [VuBackButton] carries — so the header's
 * [ly_back → home] swap is a drop-in.
 */
@Composable
fun VuHomeButton(onClick: () -> Unit) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = s.d(15))                 // marginStart 15sdp (ly_back)
            .graphicsLayer {
                val sc = if (focused) 1f else 0.9f     // scale 0.9 → 1.0 focused
                scaleX = sc
                scaleY = sc
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .size(s.d(25))                             // the chevron box's height band
    ) {
        Icon(
            Icons.Filled.Home,
            contentDescription = stringResource(R.string.cd_home),
            tint = VuPalette.White,
            modifier = Modifier.size(s.d(22))
        )
    }
}

/**
 * btn_left / btn_right (activity_live_play's category bar) — the same
 * image_down chevron, rotation fixed at +90 (points LEFT) or −90 (points
 * RIGHT) exactly like the reference's static XML rotations (NOT
 * RTL-mirrored — prev/next keep their physical direction).
 *
 * v1.19.4 — optional tint (default white, so every other caller is
 * untouched); the channel-view category bar passes VuGold.OnGold so the
 * chevrons sit as dark bronze on its solid-gold face.
 *
 * v2.2.0 — THE PLAN-CARD CHIP (user directive: the mini player page's two
 * sidebar buttons carry the plan cards' design): the bare 10×20 glyph now
 * rides inside a 16×20sdp plan-card chip — dark glass at rest, warm-bronze
 * glass + the animated golden ring (1.6dp) while focused, GOLD chevron on
 * both faces. The physical direction survives; only the skin changed.
 */
@Composable
fun VuCategoryChevron(
    pointsLeft: Boolean,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = VuPalette.White
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = s.d(16), height = s.d(20))
            .vuPlanCardStyle(cornerRadius = s.d(8), focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(R.drawable.vu_image_down),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(if (focused) VuGold.Text else tint),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .graphicsLayer { rotationZ = if (pointsLeft) 90f else -90f }
        )
    }
}

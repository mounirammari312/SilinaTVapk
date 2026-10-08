package com.superz.iptvplayer.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.components.PremiumUpsellDialog
import com.superz.iptvplayer.ui.components.VuPremiumBannerPill
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuEntryBackground
import com.superz.iptvplayer.ui.theme.vuGoldFace
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * activity_add_portal.xml + AddPortalActivity — the connection-method
 * selection page:
 *
 *  • ly_back top-left (marginTop 25sdp);
 *  • PremiumLL (100×100sdp logo) centered horizontally, vertically centered
 *    in the space between the screen top and the PREMIUM BANNER;
 *  • v2.0.3 — the golden PREMIUM BANNER in its own 280×34sdp slot between
 *    the logo and the option grid (below the logo, above row 1) when the
 *    panel enables premium — its own geometry, never overlapped;
 *  • v2.0.7 — overlap hotfix: the v2.0.6 count-aware top row placed the
 *    xtream pill one full column too far left, ON TOP of the golden code
 *    pill; every column offset now comes from the pure, unit-tested
 *    [VuPortalGrid] (stride sideMargin + i*(w+gap)) — count = 3 is
 *    pixel-identical to the v2.0.5 thirds; (v2.0.6: panel-controlled
 *    banner/code-pill visibility + labels — hidden code → two halves).
 *  • v1.14.0 — TWO ROWS × THREE COLUMNS of option pills (the v1.13.0 layout
 *    was a 2×2 grid + a standalone centered code pill; the code button now
 *    lives in the middle of the top row, and the new QR login button in the
 *    middle of the bottom row — the two "no-typing" logins are both centered):
 *
 *      top row:    [ m3u (default focus) | 6-digit code | xtream ]
 *      bottom row: [ browse              |     QR      |  MAC   ]
 *
 *    Column widths are computed from the screen width (equal thirds minus
 *    margins/gaps) so three pills always fit, landscape phone → TV. Pills
 *    keep the btn_select_type design verbatim (#33707070 radius 15sdp →
 *    purple gradient on focus, ic_right arrow, 30sdp tall).
 *  • QR opens the SMART CONNECT bridge dialog directly (no form).
 */
@Composable
fun AddPortalScreen(
    onBack: () -> Unit,
    onSelectType: (VuLoginFlow.PortalType) -> Unit,
    onQrSuccess: () -> Unit = {},
    onPremium: () -> Unit = {},
    /** v2.1.3 — the Account button at the top of the login page → the
     *  accounts page (user: “واضف زر Account في واجهة تسجيل الدخول في
     *  الاعلى الشاشة يوجه الى صفحة الحسابات بنفس ايقونة زر List of
     *  User”). */
    onAccounts: () -> Unit = {},
    loginViewModel: LoginViewModel = viewModel()
) {
    val s = rememberVuSdp()
    val m3uFocus = remember { FocusRequester() }
    var showQr by remember { mutableStateOf(false) }
    // v2.0.2 — the Phase-2 entry appears only when the panel enables it
    // (premium.enabled AND a host) — Compose state, so it can fade in when
    // the config fetch lands after the first frame.
    val premiumLive = com.superz.iptvplayer.ui.theme.OriaBranding.premiumEnabled
    // v2.1.0 — the free 3-account ceiling: picking ANY add method (or the
    // QR login) at the limit opens the upsell instead of the form; the
    // repository re-checks on the actual save (the backstop). And once
    // premium is ACTIVE the Upgrade banner disappears entirely (user:
    // "عندما يكون المستخدم قد فعل premium من المفروض أن تختفي ازرار
    // Upgrade to premium الموجودة في صفحة تسجيل الدخول").
    val accountGateVisible = com.superz.iptvplayer.ui.theme.PremiumAccess.accountLimitReached()
    var showAccountGate by remember { mutableStateOf(false) }
    val trySelectType: (VuLoginFlow.PortalType) -> Unit = { type ->
        if (accountGateVisible) showAccountGate = true else onSelectType(type)
    }
    // v2.0.6 — the panel's appearance tab now controls BOTH headline buttons
    // of this page: the Upgrade-to-Premium banner (visibility + label) and
    // the 6-digit CODE pill (visibility + label). Defaults keep the v2.0.5
    // look byte-for-byte for panels that never saved the new fields.
    val ctaVisible = com.superz.iptvplayer.ui.theme.OriaBranding.premiumCtaVisible
    val ctaText = com.superz.iptvplayer.ui.theme.OriaBranding.premiumCtaText
    val codeVisible = com.superz.iptvplayer.ui.theme.OriaBranding.codeLoginVisible
    val codeText = com.superz.iptvplayer.ui.theme.OriaBranding.codeLoginText

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .vuEntryBackground()
    ) {
        val screenH = maxHeight

        // Option grid geometry (v1.14.0 — 2 rows × 3 columns):
        // the bottom row anchors the grid (marginBottom 30sdp, like the
        // reference's portal row); 20sdp row gap; columns are equal thirds
        // of the screen width minus 10sdp side margins and 20sdp gaps.
        // v2.0.6 — the TOP ROW is count-aware: when the panel hides the
        // 6-digit code pill its two remaining pills re-flow to equal HALVES
        // (same side margins, same gap) — the row never shows a hole.
        val row2Top = screenH - s.d(30) - s.d(30)      // marginBottom + height
        val row1Top = row2Top - s.d(20) - s.d(30)      // row gap + height
        // v2.0.3 — the premium banner gets its OWN slot between the logo and
        // the grid (bannerH 34sdp + 12sdp gap above row 1). The v2.0.2 build
        // parked it at top-center where the logo Box (fillMaxWidth × the
        // whole space above the grid, drawn AFTER it) overlapped and hid it —
        // the user's "زر بريميوم يظهر وراء الشعار" report.
        val bannerH = s.d(34)
        val bannerGap = s.d(12)
        val premiumTop = row1Top - bannerGap - bannerH
        val sideMargin = s.d(10)
        val colGap = s.d(20)
        // v2.0.7 — ALL column math now flows through the pure, unit-tested
        // [VuPortalGrid] (the PremiumLayout pattern). The v2.0.6 inline
        // formula for xtream's X — sideMargin + row1W + colGap * (count-1) —
        // is only correct for TWO pills; with all three visible it shifted
        // xtream a full column LEFT, on top of the golden code pill (both
        // faces translucent → the user's jumbled "Login With Xtream Codes
        // API" screenshot, 2026-10-07). The stride formula is
        // sideMargin + i * (row1W + colGap); count = 3 reproduces the
        // v1.14.0/v2.0.5 col1/col2/col3 thirds exactly.
        val row1Count = if (codeVisible) 3 else 2
        val row1W = VuPortalGrid.rowW(maxWidth.value, sideMargin.value, colGap.value, row1Count).dp
        val row1X = VuPortalGrid.rowX(maxWidth.value, sideMargin.value, colGap.value, row1Count)
        val colW = VuPortalGrid.rowW(maxWidth.value, sideMargin.value, colGap.value, 3).dp
        val colX = VuPortalGrid.rowX(maxWidth.value, sideMargin.value, colGap.value, 3)
        val col1 = colX[0].dp
        val col2 = colX[1].dp
        val col3 = colX[2].dp
        val pillH = s.d(30)

        // ly_back — marginTop 25sdp.
        Box(modifier = Modifier.offset(y = s.d(25))) {
            VuBackButton(onClick = onBack)
        }

        // v2.1.3 — THE ACCOUNT BUTTON (user: “واضف زر Account في واجهة
        // تسجيل الدخول في الاعلى الشاشة يوجه الى صفحة الحسابات بنفس
        // ايقونة زر List of User”): the old accounts-page “List of User”
        // pill reborn at the login page's TOP-END, same slot height as
        // ly_back (25sdp), same golden identity — vu_icon_user glyph + the
        // gold label — carrying the SAME contract as the accounts page's
        // Add-User pill (warm bronze glass resting face + animated golden
        // hairline; solid-gold face with dark-bronze content on focus).
        // It routes straight to the accounts page — the Home round-trip
        // (accounts → login) gets its direct way back.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = s.d(25))
                .padding(end = s.d(15))
        ) {
            VuAccountPill(onClick = onAccounts)
        }

        // PremiumLL — horizontally centered, vertically centered in the space
        // ABOVE the premium banner (bounded Box, no measurement feedback —
        // the v1.4.10 lesson). The logo can never collide with the banner:
        // the logo Box stops at premiumTop.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(premiumTop),
            contentAlignment = Alignment.Center
        ) {
            VuPremiumLogo(logoSizeSdp = 100)
        }

        // v2.0.3 — THE PREMIUM BANNER, in its own dedicated slot: below the
        // logo, above the option grid — never overlapped by anything (it is
        // drawn AFTER the logo Box, so it also wins z-order). v2.0.4 — the
        // shared [VuPremiumBannerPill] (the PROFESSIONAL CROWN replaced the
        // star the user rejected), so every premium entry in the app wears
        // the exact same golden identity.

        if (premiumLive && ctaVisible &&
            !com.superz.iptvplayer.ui.theme.PremiumAccess.active
        ) {
            Box(
                modifier = Modifier
                    .offset(y = premiumTop)
                    .width(s.d(280))
                    .height(bannerH)
                    .align(Alignment.TopCenter)
            ) {
                VuPremiumBannerPill(
                    label = ctaText.ifBlank { stringResource(R.string.premium_cta) },
                    onClick = onPremium
                )
            }
        }

        // ── TOP ROW ── [ m3u | 6-digit code | xtream ]
        // ly_m3u — left column (default focus), the v1.13.0 code pill now
        // centered in the MIDDLE column, ly_xc in the right column.
        // v2.0.6 — when the panel hides the code pill the row re-flows to
        // TWO equal halves (m3u | xtream) — same margins, same gap, no hole.
        VuOptionPill(
            iconRes = R.drawable.vu_image_m3u_icon,
            iconTint = VuGold.Text,
            label = stringResource(R.string.vu_enter_m3u_url),
            textColor = VuGold.Text,
            style = VuOptionStyle.CENTERED_ICON,
            focusRequester = m3uFocus,
            onClick = { trySelectType(VuLoginFlow.PortalType.M3U) },
            modifier = Modifier
                .offset(x = col1, y = row1Top)
                .size(width = row1W, height = pillH)
        )
        // v1.13.0 — Login with a 6-digit code (moved from its standalone
        // centered band into the middle of the top row, per the 2×3 grid).
        // v1.19.0 — THE GOLDEN PILL (user request: a professional animated
        // golden effect that draws the eye): rotating conic gold ring +
        // breathing halo + travelling shimmer; focused = solid-gold face.
        // v2.0.6 — panel-controlled (appearance tab): hide/show + custom
        // label; hidden → the row's two remaining pills widen to halves.
        if (codeVisible) {
            VuGoldenCodePill(
                label = codeText.ifBlank { stringResource(R.string.vu_login_code) },
                onClick = { trySelectType(VuLoginFlow.PortalType.CODE) },
                modifier = Modifier
                    .offset(x = row1X[1].dp, y = row1Top)   // row1Count is 3 here
                    .size(width = row1W, height = pillH)
            )
        }
        VuOptionPill(
            iconRes = R.drawable.vu_image_surface,
            iconTint = VuGold.Text,
            label = stringResource(R.string.vu_login_xtream_short),
            textColor = VuGold.Text,
            style = VuOptionStyle.CENTERED_ICON,
            onClick = { trySelectType(VuLoginFlow.PortalType.XC) },
            modifier = Modifier
                .offset(x = row1X[row1Count - 1].dp, y = row1Top)
                .size(width = row1W, height = pillH)
        )

        // ── BOTTOM ROW ── [ browse | QR | MAC ]
        // ly_browser — left column; the new QR bridge pill in the MIDDLE;
        // ly_portal (Stalker MAC) in the right column.
        VuOptionPill(
            iconRes = R.drawable.vu_image_surface,
            iconTint = VuGold.Text,
            label = stringResource(R.string.vu_browse_playlist),
            textColor = VuGold.Text,
            style = VuOptionStyle.CENTERED_ICON,
            onClick = { trySelectType(VuLoginFlow.PortalType.BROWSER) },
            modifier = Modifier
                .offset(x = col1, y = row2Top)
                .size(width = colW, height = pillH)
        )
        // v1.14.0 — QR login: opens the SMART CONNECT bridge dialog (QR +
        // 6-digit TV code + bridge polling), engine copied from the
        // reference app, dialog in OUR design language.
        VuOptionPill(
            iconVector = Icons.Default.QrCodeScanner,
            iconRes = R.drawable.vu_image_layer,
            iconTint = VuGold.Text,
            label = stringResource(R.string.vu_login_qr),
            textColor = VuGold.Text,
            style = VuOptionStyle.CENTERED_ICON,
            onClick = { if (accountGateVisible) showAccountGate = true else showQr = true },
            modifier = Modifier
                .offset(x = col2, y = row2Top)
                .size(width = colW, height = pillH)
        )
        VuOptionPill(
            iconRes = R.drawable.vu_image_layer,
            iconTint = VuGold.Text,
            label = stringResource(R.string.vu_connect_mac),
            textColor = VuGold.Text,
            style = VuOptionStyle.CENTERED_ICON,
            onClick = { trySelectType(VuLoginFlow.PortalType.PORTAL) },
            modifier = Modifier
                .offset(x = col3, y = row2Top)
                .size(width = colW, height = pillH)
        )
    }

    // The QR bridge dialog — full-screen layer over the method grid.
    if (showQr) {
        VuQrLoginFeature(
            loginViewModel = loginViewModel,
            onFinished = onQrSuccess,
            onDismiss = { showQr = false }
        )
    }

    // v2.1.0 — the ACCOUNT-LIMIT upsell (free plan = 3 accounts; premium
    // unlimited). The gold CTA routes to the premium page.
    if (showAccountGate) {
        PremiumUpsellDialog(
            feature = null,
            onActivate = {
                showAccountGate = false
                onPremium()
            },
            onDismiss = { showAccountGate = false }
        )
    }

    // ly_m3u's <requestFocus/> — the default D-pad focus.
    LaunchedEffect(Unit) {
        runCatching { m3uFocus.requestFocus() }
    }
}

/**
 * v2.1.3 — THE ACCOUNT PILL at the login page's top-end (user: “واضف زر
 * Account في واجهة تسجيل الدخول في الاعلى الشاشة يوجه الى صفحة الحسابات
 * بنفس ايقونة زر List of User”). The retired accounts-page “List of User”
 * pill's identity, reborn: the vu_icon_user glyph + a gold label inside the
 * Add-User pill's exact contract — warm-bronze glass resting face with the
 * animated golden hairline ring; solid-gold face with dark-bronze content
 * on focus. D-pad focusable, width wraps its content.
 */
@Composable
private fun VuAccountPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) VuGold.OnGold else VuGold.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(s.d(23))
            .vuGoldenBorder(cornerRadius = s.d(13), strokeWidth = s.d(1.2f), focused = focused)
            .clip(RoundedCornerShape(s.d(13)))
            .then(
                if (focused) Modifier.vuGoldFace()
                else Modifier.background(Color(0x66301F08))   // warm bronze glass
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = s.d(13), end = s.d(14))
    ) {
        Image(
            painter = painterResource(R.drawable.vu_icon_user),
            contentDescription = stringResource(R.string.vu_account_button),
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(s.d(13))
        )
        Spacer(Modifier.width(s.d(6)))
        Text(
            stringResource(R.string.vu_account_button),
            color = tint,
            fontSize = s.t(9),
            maxLines = 1
        )
    }
}

/**
 * v2.0.7 — the option grid's PURE column math (the PremiumLayout
 * pattern: every layout bug earns a provable-on-paper geometry object).
 *
 * THE regression this object exists for: v2.0.6 generalized the top row
 * to N pills but placed the LAST pill (xtream) at
 * `sideMargin + row1W + colGap * (count - 1)` — a formula that is only
 * correct for count = 2. With all three pills visible (count = 3) the
 * xtream pill shifted a full column left and landed ON TOP of the golden
 * 6-digit-code pill; both faces are translucent, so the user saw ONE
 * jumbled pill reading "Login With Xtream Codes API" (screenshot
 * 2026-10-07, Screenshot_20261007_015500.jpg).
 *
 * The stride formula below — `sideMargin + i * (w + gap)` for pill i —
 * makes overlap impossible by construction (each next X is a full
 * (w + gap) hop from the previous one) and lands the last pill's right
 * edge exactly on the screen's right margin. VuPortalGridTest proves
 * both invariants for every realistic width × count combination, and
 * that count = 3 reproduces the v1.14.0/v2.0.5 thirds byte-for-byte.
 */
internal object VuPortalGrid {

    /**
     * Width of each of [count] equal pills across [screenW]: side margins
     * on both ends, one [colGap] between neighbours. count = 3 is the
     * v1.14.0 thirds formula verbatim.
     */
    fun rowW(screenW: Float, sideMargin: Float, colGap: Float, count: Int): Float {
        require(count >= 1) { "pill count must be >= 1, was $count" }
        return (screenW - sideMargin * 2 - colGap * (count - 1)) / count
    }

    /**
     * X offsets of the row's pills in draw order — pill i starts at
     * `sideMargin + i * (rowW + colGap)`. Never overlapping, never
     * overflowing: VuPortalGridTest pins both invariants.
     */
    fun rowX(screenW: Float, sideMargin: Float, colGap: Float, count: Int): FloatArray {
        val w = rowW(screenW, sideMargin, colGap, count)
        return FloatArray(count) { i -> sideMargin + i * (w + colGap) }
    }
}

/** The reference's two pill inner layouts. */
private sealed interface VuOptionStyle {
    /** icon 15sdp to the LEFT of the centered label (m3u, browser). */
    data object CENTERED_ICON : VuOptionStyle

    /** icon at the pill's start, label after it (xc, portal). */
    data class START_ICON(val startMargin: Int, val labelGap: Int) : VuOptionStyle
}

/**
 * v1.19.0 — THE GOLDEN CODE PILL (user request: “اضف تأثير متحرك احترافي
 * و متطور حول زر تسجيل الدخول بكود من 6 ارقام… ذهبي حقيقي ليس كتأثيرات
 * البدائية”). The btn_select_type geometry (radius 15sdp, 30sdp tall,
 * ic_right arrow at the end) survives.
 *
 * v2.2.1 — THE PLAN-CARD CONTRACT, verbatim (user directive: “واصل
 * تطبيقه على صفحة تسجيل الدخول”): the pill sheds its bespoke
 * halo/shimmer/solid-gold layers and wears the plan cards' exact skin —
 * the shared [vuPlanCardStyle]: animated golden ring (0.8sdp rest →
 * 1.6sdp lit), DARK GLASS resting face, warm-bronze GLASS lit face,
 * GOLD text + pin glyph on both faces — so the whole method grid reads
 * as ONE family of little plan cards.
 */
@Composable
private fun VuGoldenCodePill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = modifier
            .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Pin,
                    contentDescription = null,
                    tint = VuGold.Text,
                    modifier = Modifier
                        .padding(end = s.d(15))
                        .size(s.d(17))
                )
                Text(
                    label,
                    color = VuGold.Text,
                    fontSize = s.t(10),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
        // ic_right — gold-tinted, auto-mirrored in RTL
        Image(
            painter = painterResource(R.drawable.vu_ic_right),
            contentDescription = null,
            colorFilter = ColorFilter.tint(VuGold.Text),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = s.d(10))
                .size(s.d(20))
                .graphicsLayer { scaleX = if (rtl) -1f else 1f }
        )
    }
}

/**
 * One option pill — btn_select_type geometry (radius 15sdp, 30sdp tall,
 * ic_right 20sdp arrow at the end, auto-mirrored in RTL) + optional
 * [iconVector] (material icon) rendered INSTEAD of the drawable.
 *
 * v2.2.1 — THE PLAN-CARD CONTRACT (user directive: “واصل تطبيقه على صفحة
 * تسجيل الدخول”): every method pill — M3U / Xtream / Browse / QR / MAC —
 * wears the plan cards' exact skin through the shared [vuPlanCardStyle]:
 * animated golden ring (0.8sdp rest → 1.6sdp lit), DARK GLASS resting
 * face, warm-bronze GLASS lit face, GOLD icon + text + arrow on both
 * faces. The reference's grey #33707070 face and purple focus gradient
 * are retired; the whole grid reads as one family of little plan cards.
 */
@Composable
private fun VuOptionPill(
    iconRes: Int,
    iconTint: Color,
    label: String,
    textColor: Color,
    style: VuOptionStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    arrowMarginEnd: Int = 10,
    focusRequester: FocusRequester? = null,
    iconVector: ImageVector? = null
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = modifier
            .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
            .then(
                focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        when (style) {
            VuOptionStyle.CENTERED_ICON -> {
                // txt centered; image 15sdp to the left of the label
                // (end_toStartOf + marginEnd 15sdp in the XML).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (iconVector != null) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier
                                    .padding(end = s.d(15))
                                    .size(s.d(17))
                            )
                        } else {
                            Image(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(iconTint),
                                modifier = Modifier
                                    .padding(end = s.d(15))
                                    .size(s.d(17))
                            )
                        }
                        OptionText(label, textColor, subLabel)
                    }
                }
            }

            is VuOptionStyle.START_ICON -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = s.d(style.startMargin))
                ) {
                    if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(s.d(17))
                        )
                    } else {
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(iconTint),
                            modifier = Modifier.size(s.d(17))
                        )
                    }
                    Spacer(Modifier.width(s.d(style.labelGap)))
                    OptionText(label, textColor, subLabel)
                }
            }
        }

        // ic_right — 20sdp at the end (auto-mirrored in RTL), GOLD like
        // every plan-card glyph.
        Image(
            painter = painterResource(R.drawable.vu_ic_right),
            contentDescription = null,
            colorFilter = ColorFilter.tint(VuGold.Text),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = s.d(arrowMarginEnd))
                .size(s.d(20))
                .graphicsLayer { scaleX = if (rtl) -1f else 1f }
        )
    }
}

/** txt_* — 10sdp (portal adds the 8sdp sub-label beside it). */
@Composable
private fun OptionText(label: String, color: Color, subLabel: String?) {
    val s = rememberVuSdp()
    if (subLabel == null) {
        Text(
            label,
            color = color,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                color = color,
                fontSize = s.t(10),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.width(s.d(3)))
            Text(
                subLabel,
                color = color,
                fontSize = s.t(8),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

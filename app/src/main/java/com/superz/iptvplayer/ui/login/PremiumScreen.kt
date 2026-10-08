package com.superz.iptvplayer.ui.login

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.remote.OriaRemote
import com.superz.iptvplayer.data.remote.RemoteConfig
import com.superz.iptvplayer.ui.theme.AppLang
import com.superz.iptvplayer.ui.theme.ErrorRed
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuGoldFace
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuGoldShimmer
import com.superz.iptvplayer.ui.theme.vuLoginBackground
import java.net.URLEncoder
import kotlin.math.ceil

/**
 * v2.0.3 — THE PREMIUM SCREEN, rebuilt as a NATIVE app page (the user's
 * directive: "تصميم صفحة بريميوم لا يجب أن تكون بالتمرير وكأنه موقع —
 * صفحات احترافية لأن هذا أهم جزء في التطبيق").
 *
 * The v2.0.2 build had three defects, all structural:
 *  1. the whole page lived in ONE verticalScroll Column — it scrolled like
 *     a web page, exactly what the user rejected;
 *  2. that fillMaxSize scrollable Column was drawn ON TOP of the ly_back
 *     button, so every tap on "رجوع" was swallowed — back never worked;
 *  3. there was no BackHandler, so the hardware/gesture back did nothing
 *     either.
 *
 * The v2.0.3 anatomy — a FIXED-viewport page, nothing scrolls:
 *
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │ [← رجوع]              ♛ بريميوم                            │ ← top bar:
 *  ├──────────────────────────────────────────┬───────────────────┤   back INSIDE
 *  │   hero line: crown + title + subtitle    │ [تواصل معنا…]     │ ← v2.1.2 contact
 *  │   ── ♛ كل ما ستحصل عليه ──               │  لديك حساب؟       │   CTA: the golden
 *  │   [offline][downloads][record][∞][ad-    │  [username pill]  │   plan-card face,
 *  │    free]  ← v2.1.1 benefits deck         │  [password pill]  │   riding ABOVE the
 *  │   الباقات: [plan][plan][plan][plan]       │  host (locked)    │   login card
 *  │                                          │  [تسجيل الدخول]   │ ← gold hero
 *  └──────────────────────────────────────────┴───────────────────┘
 *
 *  v2.1.1 — THE BENEFITS DECK (user directive: “في صفحة الاشتراك premium
 *  يجب ان نبتكر طريقة احترافيه لتعرض على المستخدم كل الميزات التي
 *  سيتحصل عليها عند الاشتراك”): the “Everything in Premium” strip — the
 *  crowned gold section header between hairline rules, the five
 *  subscription features as golden coin-icon chips (offline viewing,
 *  downloads, recording, unlimited accounts, ad-free — exactly the v2.1.0
 *  gate set), and one microcopy line. The plan cards slim 118→88dp to host
 *  it with NO scroll: the page's height budget on a landscape phone was
 *  already ~99% spent, so every section was re-budgeted on paper (see
 *  PremiumLayout) — the deck costs ~51dp, the hero compressed to one line
 *  (~30dp), the cards and the bar handed back the rest.
 *
 *  v2.1.2 — THE CONTACT CTA MOVES HOME (user directive: the WhatsApp
 *  pill under the plans clipped off-screen once the v2.1.1 deck spent the
 *  start pane's height — “زر واتساب متخفي صار غير ضاهر”): it now rides in
 *  the login pane's free air, directly ABOVE the registration card —
 *  34sdp tall and 92% of the login column's width (“مع تصغير حجمه قليلا”),
 *  the card sinking by half the CTA+gap so the pair centres as one group
 *  (“نزل بطاقة التسجيل الى اسفل قليلا”) — wearing the PLAN-CARD contract
 *  (animated golden ring + dark-glass / warm-bronze focus faces + gold
 *  text: “نفس تصميم و الوان بطاقات الخطط نفس التأثير الذهبي”), with the
 *  trial-activation copy “تواصل معنا لتفعيل التجربة المجانية”. The green
 *  WhatsApp brand colors and the start-pane CTA slot are retired
 *  ([PremiumLayout.loginStackFits] carries the paper proof).
 *
 *  • WIDE screens (≥ 640dp — landscape phones, tablets, TV): two panes —
 *    branding/deck/plans on the start side, the login card in a fixed 360dp
 *    column on the end side, both vertically centered.
 *  • NARROW screens (portrait phones): the same blocks stacked, sized by
 *    [PremiumLayout] so the total fits the viewport — never a scroll.
 *  • Plan cards wrap to a second row (≤ 4 per row) and shrink before they
 *    would ever overflow — pure math, unit-tested in PremiumLayoutTest.
 *
 * The engine underneath is unchanged: [LoginViewModel.loginXtream] against
 * the LOCKED panel host, WhatsApp deep links via [PremiumCopy].
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    loginViewModel: LoginViewModel = viewModel()
) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    val ui by loginViewModel.ui.collectAsStateWithLifecycle()

    // The remote config's premium block — read fresh on every config epoch
    // (the panel can flip plans/host at any time; the screen follows).
    val premium = remember(OriaRemote.configEpoch) {
        OriaRemote.current.premium
    }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Engine success → the same accounts hand-off every login uses.
    LaunchedEffect(ui.success) {
        if (ui.success) onLoggedIn()
    }

    // v2.1.3 — the app's own localized fallback template (the message must
    // follow the APP's language — user directive — even when the panel
    // never saved any template field).
    val appDefaultTemplate = stringResource(R.string.premium_whatsapp_message)

    fun openWhatsapp(planName: String?) {
        // v2.1.3 — the template is picked by the app's LIVE language
        // (AppLang), not Locale.getDefault(): Arabic users get the Arabic
        // template, English users the English one.
        val langTag = AppLang.currentTag(context)
        val template = PremiumCopy.templateFor(
            langTag = langTag,
            templateAr = premium.messageTemplateAr,
            templateEn = premium.messageTemplateEn,
            legacyTemplate = premium.messageTemplate,
            appDefault = appDefaultTemplate
        )
        val url = PremiumCopy.whatsappUrl(premium.whatsapp, template, planName)
            ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // v2.0.3 — the hardware/gesture back now returns exactly like the
    // on-screen back button (the v2.0.2 build had no BackHandler at all).
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .vuLoginBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ══════════ TOP BAR — the back button LIVES INSIDE the layout ══════════
            // (v2.0.3 root fix: the old screen overlaid a fillMaxSize scrollable
            // Column on top of the back button, which swallowed every tap.)
            // v2.1.1 — 52+10 → 46+8: the v2.1.1 benefits deck needed height
            // budget; the bar's content (back / crown+title) fits 46 with air.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(s.d(46))
                    .padding(top = s.d(8))
            ) {
                Box(modifier = Modifier.padding(start = s.d(10))) {
                    VuBackButton(onClick = onBack)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    // v2.0.4 — the PROFESSIONAL CROWN (the star was rejected:
                    // "مثل ايقونات التطبيقات الاحترافية").
                    // v2.0.5 — VuCrownIcon: Icon()'s default tint was
                    // flattening the crown to black; this keeps its metal.
                    VuCrownIcon(
                        modifier = Modifier.size(s.d(17))
                    )
                    Spacer(Modifier.width(s.d(8)))
                    Text(
                        stringResource(R.string.premium_title),
                        color = VuGold.Text,
                        fontSize = s.t(14),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ══════════ CONTENT — adaptive, FIXED viewport, no page scroll ══════════
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = s.d(16), vertical = s.d(4))
            ) {
                // Capture the constraints up front — Row/Column content
                // lambdas carry their own scopes, so the BoxWithConstraints
                // receivers must NOT be used implicitly inside them.
                val boxW = maxWidth.value
                val wide = PremiumLayout.isWide(boxW)

                if (wide) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(s.d(20)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // ── start pane: hero + benefits deck + plans + CTA ──
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            PremiumHero()
                            Spacer(Modifier.height(s.d(6)))
                            // v2.1.1 — THE BENEFITS DECK: every feature the
                            // subscription unlocks, one golden strip.
                            PremiumBenefitsDeck(
                                availW = boxW - PremiumLayout.LOGIN_COL_W - 20f
                            )
                            Spacer(Modifier.height(s.d(6)))
                            PremiumPlans(
                                plans = premium.plans,
                                availW = boxW - PremiumLayout.LOGIN_COL_W - 20f,
                                onPlanTap = { openWhatsapp(it) }
                            )
                        }
                        // ── end pane: the contact CTA + the login card, one
                        //    centered stack — the CTA rides the pane's free air
                        //    above the card, the card sinks half the CTA+gap ──
                        Box(
                            modifier = Modifier
                                .width(PremiumLayout.LOGIN_COL_W.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (premium.whatsapp.isNotBlank()) {
                                    PremiumContactCta(
                                        onWhatsapp = { openWhatsapp(null) }
                                    )
                                    Spacer(Modifier.height(s.d(PremiumLayout.CONTACT_CTA_GAP)))
                                }
                                PremiumLoginCard(
                                    premium = premium,
                                    ui = ui,
                                    username = username,
                                    password = password,
                                    passwordVisible = passwordVisible,
                                    onUsername = { username = it },
                                    onPassword = { password = it },
                                    onTogglePassword = { passwordVisible = !passwordVisible },
                                    onLogin = {
                                        loginViewModel.loginXtream(
                                            name = "Premium",
                                            server = premium.host,
                                            username = username,
                                            password = password
                                        )
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // ── narrow: the same blocks, stacked and budgeted ──
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        PremiumHero()
                        Spacer(Modifier.height(s.d(6)))
                        // v2.1.1 — the benefits deck wraps its chips over more
                        // rows on narrow widths (PremiumLayout.deckPerRow).
                        PremiumBenefitsDeck(availW = boxW)
                        Spacer(Modifier.height(s.d(6)))
                        PremiumPlans(
                            plans = premium.plans,
                            availW = boxW,
                            onPlanTap = { openWhatsapp(it) }
                        )
                        // v2.1.2 — the contact CTA rides above the login
                        // card (the pane's own group), 92% of its width.
                        if (premium.whatsapp.isNotBlank()) {
                            PremiumContactCta(
                                onWhatsapp = { openWhatsapp(null) }
                            )
                            Spacer(Modifier.height(s.d(PremiumLayout.CONTACT_CTA_GAP)))
                        }
                        Spacer(Modifier.height(s.d(14)))
                        PremiumLoginCard(
                            premium = premium,
                            ui = ui,
                            username = username,
                            password = password,
                            passwordVisible = passwordVisible,
                            onUsername = { username = it },
                            onPassword = { password = it },
                            onTogglePassword = { passwordVisible = !passwordVisible },
                            onLogin = {
                                loginViewModel.loginXtream(
                                    name = "Premium",
                                    server = premium.host,
                                    username = username,
                                    password = password
                                )
                            },
                            modifier = Modifier.widthIn(max = 380.dp)
                        )
                    }
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════
 *  v2.0.3 — PURE LAYOUT MATH (unit-tested: PremiumLayoutTest)
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * Every sizing decision of the premium page, as pure Float math (dp
 * values) so it is fully unit-testable with no Android types:
 *
 *  • [isWide] — two panes from 640dp up (landscape phones / tablets / TV);
 *  • [perRow] — at most 4 plan cards per row, and fewer when the available
 *    width cannot host 4 × 72dp cards, so cards SHRINK or WRAP but never
 *    overflow and never force a scroll;
 *  • [planCardW]/[planCardH] — the card geometry for the row count.
 *    v2.1.1: 118/100 → 88/84 — the cards handed ~30dp back to host the
 *    benefits deck (the deck costs ~51dp; the hero's compression and the
 *    top bar's 52+10→46+8 paid the rest) with the page still scroll-free;
 *  • [deckPerRow] — v2.1.1: how many of the five feature chips share one
 *    row of the benefits deck: all 5 side by side from 460dp of width
 *    (landscape start panes / tablets), 3 per row from 240dp, else 2 — the
 *    chips wrap instead of ever squeezing or overflowing;
 *  • [loginStackFits] — v2.1.2: the contact CTA + login-card stack of the
 *    login pane fits its height at any sdp render scale — the CTA moved out
 *    of the height-exhausted start pane (the clipped-button regression).
 */
object PremiumLayout {
    const val WIDE_MIN_W = 640f
    const val SHORT_H = 420f
    const val LOGIN_COL_W = 360f
    const val PLAN_GAP = 12f
    const val PLAN_MIN_W = 72f
    const val PLAN_MAX_W = 170f
    const val PER_ROW_MAX = 4
    const val DECK_ROW_5_MIN_W = 460f
    const val DECK_ROW_3_MIN_W = 240f

    fun isWide(screenW: Float): Boolean = screenW >= WIDE_MIN_W

    /** How many plan cards fit one row — wraps instead of overflowing. */
    fun perRow(availW: Float, planCount: Int): Int {
        val fit = ((availW + PLAN_GAP) / (PLAN_MIN_W + PLAN_GAP)).toInt().coerceAtLeast(1)
        return planCount.coerceAtMost(minOf(fit, PER_ROW_MAX)).coerceAtLeast(1)
    }

    fun rows(planCount: Int, perRow: Int): Int =
        ceil(planCount.toFloat() / perRow.toFloat()).toInt().coerceAtLeast(1)

    fun planCardW(availW: Float, perRow: Int): Float =
        ((availW - PLAN_GAP * (perRow - 1)) / perRow).coerceIn(PLAN_MIN_W, PLAN_MAX_W)

    /** v2.1.1 — slimmer cards (was 118/100): badge + name + price +
     *     duration still ride with air, and the freed height hosts the deck. */
    fun planCardH(rowCount: Int): Float = if (rowCount <= 1) 88f else 84f

    /**
     * v2.1.1 — benefits-deck wrapping: 5 chips in one row on wide panes,
     * 3 from 240dp, 2 below that. Never more than the five features exist,
     * never fewer than 2 per row (a lone chip is not a strip).
     */
    fun deckPerRow(availW: Float): Int = when {
        availW >= DECK_ROW_5_MIN_W -> 5
        availW >= DECK_ROW_3_MIN_W -> 3
        else -> 2
    }

    // ── v2.1.2 — the contact CTA above the login card ──

    /** The contact CTA's height (sdp) — a touch slimmer than the retired 40dp green pill. */
    const val CONTACT_CTA_H = 34f

    /** The air between the contact CTA and the login card (sdp). */
    const val CONTACT_CTA_GAP = 10f

    /** The CTA's width as a fraction of the login column — visibly "a little smaller" than the card. */
    const val CONTACT_CTA_W_FRAC = 0.92f

    /** The login pane's stack height (dp): the CTA + gap (scaled) + the login card. */
    fun loginStackH(cardH: Float, scale: Float = 1f): Float =
        (CONTACT_CTA_H + CONTACT_CTA_GAP) * scale + cardH

    /**
     * v2.1.2 — TRUE when the contact-CTA + gap + login-card stack fits the
     * pane without scroll. [scale] is the sdp render factor (1.0 on phones,
     * 1.8 on the user's 2400×1080 TV) — the CTA/gap tokens are sdp, the
     * pane/card heights arrive already in dp.
     */
    fun loginStackFits(paneH: Float, cardH: Float, scale: Float = 1f): Boolean =
        paneH >= loginStackH(cardH, scale)
}

/* ═══════════════════════════════════════════════════════════════════
 *  The page's blocks
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * v2.0.4/v2.1.1 — the brand hero: ONE professional line — the crown, the
 * gold title, the value-proposition subtitle side by side (~30dp tall).
 * The v2.0.3–v2.1.0 tall centered column (crown 56dp over the title) was
 * compressed to buy the benefits deck its height: the top bar already
 * carries the crowned title, so the page's vertical prime real estate now
 * goes to WHAT THE SUBSCRIPTION BUYS, not to restating the brand — exactly
 * how the big subscription pages budget their paywalls.
 */
@Composable
private fun PremiumHero() {
    val s = rememberVuSdp()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.d(10))
    ) {
        VuCrownIcon(
            modifier = Modifier.size(s.d(30))
        )
        Column {
            Text(
                stringResource(R.string.premium_title),
                color = VuGold.Text,
                fontSize = s.t(13),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.premium_subtitle),
                color = VuPalette.White.copy(alpha = 0.70f),
                fontSize = s.t(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * v2.1.1 — THE BENEFITS DECK (user: “في صفحة الاشتراك premium يجب ان نبتكر
 * طريقة احترافيه لتعرض على المستخدم كل الميزات التي سيتحصل عليها عند
 * الاشتراك”): the professional answer the big subscription pages give —
 * the “Everything in Premium” strip.
 *
 *  • the crowned gold section header between two hairline rules — the
 *    editorial divider idiom of the professional paywalls;
 *  • the five subscription features as chips: a golden coin badge (the
 *    feature's icon in metallic gold on warm bronze glass) + the feature's
 *    name — EXACTLY the v2.1.0 gate set, so the deck can never promise
 *    something the app does not unlock: offline viewing, downloading
 *    movies & series, recording channels, unlimited accounts, ad-free;
 *  • chips wrap to more rows on narrow widths ([PremiumLayout.deckPerRow] —
 *    pure math, unit-tested), separated by gold dot leaders on wide ones;
 *  • one microcopy line under the strip — the subscription promise.
 *
 * Informational by design (nothing is clickable — the plan cards and the
 * gold sign-in carry the actions), so D-pad focus flows straight from the
 * deck down to the plans.
 */
@Composable
private fun PremiumBenefitsDeck(availW: Float) {
    val s = rememberVuSdp()
    val features = remember { PremiumDeckFeatures }
    val perRow = PremiumLayout.deckPerRow(availW)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // ── the crowned section header between hairline rules ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(s.d(8)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(s.d(0.7f))
                    .background(VuGold.Gold.copy(alpha = 0.35f))
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s.d(5))
            ) {
                VuCrownIcon(modifier = Modifier.size(s.d(11)))
                Text(
                    stringResource(R.string.premium_features_title),
                    color = VuGold.Text,
                    fontSize = s.t(10),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(s.d(0.7f))
                    .background(VuGold.Gold.copy(alpha = 0.35f))
            )
        }
        Spacer(Modifier.height(s.d(6)))
        // ── the feature chips, wrapped by the layout math ──
        features.chunked(perRow).forEachIndexed { rowIndex, rowItems ->
            if (rowIndex > 0) Spacer(Modifier.height(s.d(5)))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s.d(7))
            ) {
                rowItems.forEachIndexed { i, f ->
                    if (i > 0) {
                        // the gold dot leader between chips of one row
                        Box(
                            Modifier
                                .size(s.d(2.5f))
                                .clip(CircleShape)
                                .background(VuGold.Gold.copy(alpha = 0.6f))
                        )
                    }
                    PremiumBenefitChip(feature = f)
                }
            }
        }
        Spacer(Modifier.height(s.d(5)))
        // ── the subscription promise, one line ──
        Text(
            stringResource(R.string.premium_deck_sub),
            color = VuPalette.TintGrey,
            fontSize = s.t(8),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One deck entry: the feature's name + its icon (declared once, immutable). */
private val PremiumDeckFeatures = listOf(
    R.string.premium_feat_offline to Icons.Filled.WifiOff,
    R.string.premium_feat_download to Icons.Filled.FileDownload,
    R.string.premium_feat_record to Icons.Filled.FiberManualRecord,
    R.string.premium_feat_accounts to Icons.Filled.AllInclusive,
    R.string.premium_feat_noads to Icons.Filled.Block
)

/**
 * One feature chip: the golden coin — a circular warm-bronze glass badge
 * carrying the feature's icon in metallic gold — followed by the feature's
 * name in white. Height ~20dp: the whole five-feature deck costs less than
 * one plan card, which is what lets the page stay scroll-free.
 */
@Composable
private fun PremiumBenefitChip(feature: Pair<Int, ImageVector>) {
    val s = rememberVuSdp()
    val (titleRes, icon) = feature
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.d(5))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(s.d(17))
                .clip(CircleShape)
                .background(Color(0x66301F08))     // warm bronze glass coin
                .border(s.d(0.6f), VuGold.Gold.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = VuGold.Text,
                modifier = Modifier.size(s.d(10))
            )
        }
        Text(
            stringResource(titleRes),
            color = VuPalette.White,
            fontSize = s.t(8),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The plans strip — panel-authored plan cards laid out by [PremiumLayout]:
 * one row when they fit, wrapped rows (≤ 4 per row) when they don't.
 * Tapping a card opens WhatsApp with the plan's name in the message.
 */
@Composable
private fun PremiumPlans(
    plans: List<RemoteConfig.Premium.Plan>,
    availW: Float,
    onPlanTap: (String) -> Unit
) {
    if (plans.isEmpty()) return
    val s = rememberVuSdp()
    val perRow = PremiumLayout.perRow(availW, plans.size)
    val rowCount = PremiumLayout.rows(plans.size, perRow)
    val cardW = PremiumLayout.planCardW(availW, perRow)
    val cardH = PremiumLayout.planCardH(rowCount)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.premium_plans),
            color = VuPalette.White,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(s.d(8)))
        plans.chunked(perRow).forEachIndexed { rowIndex, rowPlans ->
            if (rowIndex > 0) Spacer(Modifier.height(s.d(10)))
            Row(horizontalArrangement = Arrangement.spacedBy(PremiumLayout.PLAN_GAP.dp)) {
                rowPlans.forEach { plan ->
                    PremiumPlanCard(
                        plan = plan,
                        cardW = cardW,
                        cardH = cardH,
                        onClick = { onPlanTap(plan.name) }
                    )
                }
            }
        }
    }
}

/** One plan card — glass surface, gold price, the popular one wears the badge. */
@Composable
private fun PremiumPlanCard(
    plan: RemoteConfig.Premium.Plan,
    cardW: Float,
    cardH: Float,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(12))
    Box(
        modifier = Modifier
            .width(cardW.dp)
            .height(cardH.dp)
            .vuGoldenBorder(cornerRadius = s.d(12), strokeWidth = if (focused) 1.6.dp else 0.8.dp, focused = focused)
            .clip(shape)
            .then(
                if (focused) Modifier.background(Color(0x8C301F08))   // warm bronze glass, lit
                else Modifier.background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA))  // v2.2.1 — raised dark glass
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            if (plan.popular) {
                Spacer(Modifier.height(s.d(8)))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(VuGold.Gold)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        stringResource(R.string.premium_popular),
                        color = VuGold.OnGold,
                        fontSize = s.t(6),
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                Spacer(Modifier.height(s.d(12)))
            }
            Text(
                plan.name,
                color = VuPalette.White,
                fontSize = s.t(10),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(s.d(3)))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    plan.price,
                    color = VuGold.Text,
                    fontSize = s.t(14),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (plan.currency.isNotBlank()) {
                    Text(
                        " ${plan.currency}",
                        color = VuGold.Text.copy(alpha = 0.8f),
                        fontSize = s.t(8),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(s.d(3)))
            Text(
                premiumDuration(plan.durationDays),
                color = VuPalette.TintGrey,
                fontSize = s.t(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "30 days" / "a month" — the only place duration text is shaped. */
@Composable
private fun premiumDuration(days: Int): String =
    if (days == 30) stringResource(R.string.premium_month)
    else stringResource(R.string.premium_days, days)

/**
 * v2.1.2 — THE CONTACT CTA (user directive: the green WhatsApp pill under
 * the plans clipped off-screen once the v2.1.1 benefits deck spent the
 * start pane's height — “زر واتساب متخفي صار غير ضاهر… يوجد مكان فارغ فوق
 * بطاقة التسجيل يمكنك و ضعه هناك"). It rides in the login pane's free air,
 * directly ABOVE the registration card, sized and styled to order:
 *
 *  • sizing — 34sdp tall (a touch slimmer than the retired 40sdp pill:
 *    “مع تصغير حجمه قليلا”) and 92% of the login column's width, the pair
 *    (CTA + card) centering as one group so the card sinks by exactly
 *    half the CTA+gap — “نزل بطاقة التسجيل الى اسفل قليلا”;
 *  • styling — the PLAN-CARD contract, verbatim: the animated golden ring
 *    border, the resting dark glass / focused warm-bronze faces, the gold
 *    text and glyph — “نفس تصميم و الوان بطاقات الخطط نفس اللون نفس
 *    التأثير الذهبي”;
 *  • copy — “تواصل معنا لتفعيل التجربة المجانية”: the trial-activation
 *    message, not an order button.
 */
@Composable
private fun PremiumContactCta(
    modifier: Modifier = Modifier,
    onWhatsapp: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(12))
    Box(
        modifier = modifier
            .fillMaxWidth(PremiumLayout.CONTACT_CTA_W_FRAC)
            .height(s.d(PremiumLayout.CONTACT_CTA_H))
            .vuGoldenBorder(
                cornerRadius = s.d(12),
                strokeWidth = if (focused) 1.6.dp else 0.8.dp,
                focused = focused
            )
            .clip(shape)
            .then(
                if (focused) Modifier.background(Color(0x8C301F08))   // warm bronze glass, lit
                else Modifier.background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA))  // v2.2.1 — raised dark glass
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onWhatsapp() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(s.d(7))
        ) {
            Icon(
                Icons.Default.Chat,
                contentDescription = null,
                tint = VuGold.Text,
                modifier = Modifier.size(s.d(13))
            )
            // v2.2.0 — THE NEON-GREEN HIGHLIGHT (user directive: "كلمة
            // (المجانية) يجب ان تكون بالاخضر نيون"): the trial's magic
            // word pops in VuPalette.NeonGreen — the same electric green
            // as the Free tier chip — inside the gold sentence.
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = VuGold.Text)) {
                        append(stringResource(R.string.premium_whatsapp_trial_pre))
                    }
                    withStyle(SpanStyle(color = VuPalette.NeonGreen)) {
                        append(stringResource(R.string.premium_whatsapp_trial_accent))
                    }
                    withStyle(SpanStyle(color = VuGold.Text)) {
                        append(stringResource(R.string.premium_whatsapp_trial_post))
                    }
                },
                fontSize = s.t(10),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The premium login block — username/password against the LOCKED host. */
@Composable
private fun PremiumLoginCard(
    premium: RemoteConfig.Premium,
    ui: LoginViewModel.UiState,
    username: String,
    password: String,
    passwordVisible: Boolean,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var loginFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s.d(12)))
            .background(VuPalette.PanelOverlay.copy(alpha = 0.55f))
            .border(s.d(0.7f), VuGold.Gold.copy(alpha = 0.35f), RoundedCornerShape(s.d(12)))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.premium_have_account),
                color = VuGold.Text,
                fontSize = s.t(10),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            PremiumField(
                value = username,
                onValueChange = onUsername,
                hint = stringResource(R.string.premium_username),
                enabled = !ui.loading
            )
            PremiumPasswordField(
                value = password,
                onValueChange = onPassword,
                visible = passwordVisible,
                onToggle = onTogglePassword,
                enabled = !ui.loading
            )

            // v2.2.0 — the host line is GONE (user directive: "تزيل رابط
            // السارفور الذي موجود في صفحة البريميوم في بطاقة لدي حساب
            // بريميوم لا يجب ان يكون رابط السارفور ضاهر للمستخدم"): the
            // premium server URL is the OPERATOR's business, never the
            // user's concern — the login validates against it silently
            // (the panel owns it); nothing about it is rendered here.

            // ── the gold login button ──
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .vuGoldenBorder(cornerRadius = 20.dp, focused = loginFocused)
                    .clip(RoundedCornerShape(20.dp))
                    .vuGoldFace()
                    .vuGoldShimmer()
                    .onFocusChanged { loginFocused = it.isFocused }
                    .focusable()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !ui.loading
                    ) { onLogin() }
            ) {
                if (ui.loading) {
                    CircularProgressIndicator(
                        color = VuGold.OnGold,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.premium_login),
                        color = VuGold.OnGold,
                        fontSize = s.t(11),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // the engine's error line, same voice as the login form.
            ui.error?.let { err ->
                Text(
                    vuLoginErrorText(err, ui.errorDetail),
                    color = ErrorRed,
                    fontSize = s.t(9),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                )
            }
        }
    }
}

/** The login form's field, in the app's pill design. */
@Composable
private fun PremiumField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(15))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = s.d(10))
            .height(s.d(30))
            .clip(shape)
            .background(VuPalette.PanelOverlay)
            .then(if (focused) Modifier.border(0.5.dp, VuPalette.White, shape) else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .padding(start = s.d(15), end = s.d(10)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = VuPalette.White, fontSize = s.t(10)),
            cursorBrush = SolidColor(VuPalette.White),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        if (value.isEmpty()) {
            Text(hint, color = VuPalette.TintGrey, fontSize = s.t(10))
        }
    }
}

/** The password pill with the eye toggle (the login form's exact design). */
@Composable
private fun PremiumPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(15))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = s.d(10))
            .height(s.d(30))
            .clip(shape)
            .background(VuPalette.PanelOverlay)
            .then(if (focused) Modifier.border(0.5.dp, VuPalette.White, shape) else Modifier)
            .padding(start = s.d(15)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = VuPalette.White, fontSize = s.t(10)),
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            cursorBrush = SolidColor(VuPalette.White),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        if (value.isEmpty()) {
            Text(
                stringResource(R.string.premium_password),
                color = VuPalette.TintGrey,
                fontSize = s.t(10)
            )
        }
        androidx.compose.foundation.Image(
            painter = painterResource(
                if (visible) R.drawable.vu_ic_eye_show else R.drawable.vu_ic_eye_hide
            ),
            contentDescription = stringResource(R.string.premium_password),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(VuPalette.White),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = s.d(10))
                .size(s.d(18))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggle() }
        )
    }
}

/**
 * Pure, unit-testable premium helpers — no Android types inside.
 * (PremiumCopyTest covers the WhatsApp URL contract.)
 */
object PremiumCopy {

    /**
     * v2.1.3 — THE LANGUAGE-AWARE TEMPLATE (user: the WhatsApp message must
     * follow the app's language — Arabic for Arabic users, English for
     * English users). The fallback chain, on paper:
     *
     *   Arabic app  → templateAr → legacyTemplate → appDefault
     *   English app → templateEn → appDefault
     *
     * The legacy single field only ever feeds the ARABIC side — it defaults
     * to the Arabic template on every panel built before v2.1.3, so letting
     * it feed the English side would reproduce the exact bug being fixed
     * (an Arabic message on an English app). A seller who customizes the
     * English message uses messageTemplateEn.
     */
    fun templateFor(
        langTag: String,
        templateAr: String,
        templateEn: String,
        legacyTemplate: String,
        appDefault: String
    ): String = when (langTag) {
        "ar" -> templateAr.ifBlank { legacyTemplate }.ifBlank { appDefault }
        else -> templateEn.ifBlank { appDefault }
    }

    /**
     * The wa.me deep link for a subscription request: the panel's template
     * with {plan} replaced (or the plan mention dropped when null/blank),
     * URL-encoded. null when the number is unusable — callers no-op.
     */
    fun whatsappUrl(number: String, template: String, planName: String?): String? {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.length < 5) return null
        val message = template
            .replace("{plan}", planName?.trim().orEmpty())
            .replace("  ", " ")
            .trim()
        // URLEncoder is form-encoded (space → '+'); a query string wants %20.
        val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        return "https://wa.me/${digits.removePrefix("+")}?text=$encoded"
    }
}

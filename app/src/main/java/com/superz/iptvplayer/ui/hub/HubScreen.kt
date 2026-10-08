package com.superz.iptvplayer.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.PremiumSpring
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground
import com.superz.iptvplayer.ui.theme.vuGoldenBorder

/**
 * HOME — the reference app's HomeActivity (activity_home.xml, recovered from
 * the xxhdpi split APK) replicated page-for-page, with THIS app's cinematic
 * category cards kept by explicit user decision:
 *
 *   PremiumLL (crown, 20/25sdp) … 40sdp icon column row (ly_settings,
 *   ly_change_user, ly_user_info, ly_notifications, ly_update, ly_search —
 *   scale 0.85→1.0 on focus/press) … three cards at the reference's exact
 *   geometry (LIVE 180×180sdp, MOVIES/SERIES 130×130sdp, 15/25sdp gaps,
 *   3sdp top offset) each carrying the reference's "Last Update :" strip …
 *   ly_expiration (150×30sdp) + ly_logged (130×30sdp) pills under the cards
 *   … ly_catch_up at the bottom-end. ly_install (premium upsell) is GONE —
 *   this app always renders the reference's "purchased" variant.
 *
 * v1.6.0 fixes the SERIES card clipping (v1.4.x squeezed ~135dp of content
 * into a 130dp card): content is now sized per card class (see
 * [CardContentSpec]) with verified slack, so the count subtitle can never
 * be cut in half again.
 */
@Composable
fun HubScreen(
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** v1.12.0 — ly_catch_up → the Catch-Up screen (PORTAL playlists:
     *  the stalker tv-archive engine; XC/M3U keep the pending toast). */
    onOpenCatchUp: () -> Unit = {},
    /** v1.18.1 — the 4th card (user request): the Saved Videos library
     *  — every auto-saved / downloaded video, offline, in one page. */
    onOpenSaved: () -> Unit = {},
    /** v2.0.4 — the premium crown in the top-end icon row (the user's
     *  directive: premium entries on the app's other pages). */
    onOpenPremium: () -> Unit = {},
    viewModel: HubViewModel = viewModel()
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val vodCounts by viewModel.vodCounts.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshSucceeded by viewModel.refreshSucceeded.collectAsStateWithLifecycle()
    // v1.18.1 — the Saved Videos card's numbers: (count, newest save ms).
    val savedSummary by viewModel.savedSummary.collectAsStateWithLifecycle()
    val s = rememberVuSdp()
    val context = LocalContext.current
    // v2.0.4 — the premium entry appears only when the panel enables it
    // (premium.enabled AND a host) — the same gate every entry shares.
    val premiumLive = OriaBranding.premiumEnabled

    // ly_user_info → AccountInfoDlgFragment
    var showAccount by remember { mutableStateOf(false) }
    // v2.1.0 — a PREMIUM member pressing the crown reads his subscription
    // card (account + dates + the enabled features) instead of the
    // upgrade page (user: "باقي ازرار premium… عند النقر عليها تعرض
    // معلومات حسابه و الميزات المفعلة").
    var showPremiumInfo by remember { mutableStateOf(false) }

    // PremiumLL band height — measured on the logo ITSELF (loop-free), with
    // the reference block's own fallback until the first frame lands.
    var logoHeight by remember { mutableStateOf<Dp?>(null) }
    val logoH = logoHeight ?: s.d(47)

    // initView()'s ly_live.requestFocus() — D-pad users land on LIVE.
    val liveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { liveFocus.requestFocus() } }

    // doNextTask(true): "Login Successfully" after an ly_update reload.
    LaunchedToast(
        message = stringResource(R.string.vu_login_successfully),
        trigger = refreshSucceeded,
        onConsumed = viewModel::consumeRefreshSuccess
    )

    val pl = playlist
    // v2.1.3 — THE APP'S LIVE LOCALE for every home-page date/digit strip
    // (same fix class as the premium card): Locale.getDefault() stays the
    // DEVICE locale after Settings switches the app's language, which put
    // Arabic month names on the English UI. LocalConfiguration carries the
    // attachBaseContext override — the locale the app is ACTUALLY running.
    val appLocale = LocalConfiguration.current.locales[0]
    val lastUpdate = VuHomeContract.reloadedTimeAgo(
        System.currentTimeMillis(), pl?.lastSyncAt ?: 0L, appLocale
    )
    // v1.18.1 — the saved library's own "last update": the newest save's
    // time-ago (0 → the strip's bare label, same as a fresh playlist).
    val savedUpdate = VuHomeContract.reloadedTimeAgo(
        System.currentTimeMillis(), savedSummary.second / 1000L, appLocale
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()
    ) {
        // v1.18.1 — the 4th card joins the reference row when the screen
        // has the room (start 25 + 180 + 15 + 130 + 25 + 130 + 15 + 130 +
        // 10 breathing = 660sdp). Denser screens — where the reference's
        // three cards already span the width — get the entry as a
        // bottom-start scalable row instead (ly_catch_up's mirror), so the
        // library stays reachable on every device.
        val savedFitsRow = maxWidth >= s.d(660)
        // ── PremiumLL — crown + PREMIUM, marginTop 20sdp / marginStart 25sdp ──
        VuPremiumLogo(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = s.d(20), start = s.d(25), end = s.d(25)),
            onHeight = { logoHeight = it }
        )

        // ── Top-end icon row — 40sdp columns centered on the PremiumLL band.
        //    End-anchor chain (end→start): settings(25sdp) · change_user(5) ·
        //    user_info(5) · notifications(5) · update(5) · search(10). ──
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    // band center (20 + logoH/2) minus half the 40sdp items;
                    // coerced ≥ 0 for degenerate logo measurements.
                    top = (logoH / 2 - s.d(20)).coerceAtLeast(0.dp),
                    end = s.d(25)
                ),
            horizontalArrangement = Arrangement.spacedBy(s.d(5))
        ) {
            // v2.0.4 — THE PREMIUM CROWN leads the icon row: the professional
            // crown (full-color gold, untinted) in the same 40sdp column +
            // focus-scale treatment as its siblings — the hub's premium entry
            // (the user's "أضف زر بريميوم في صفحات أخرى مثل التطبيقات
            // الاحترافية").
            if (premiumLive) {
                VuPremiumCrownButton(
                    label = stringResource(R.string.premium_title),
                    onClick = {
                        if (com.superz.iptvplayer.ui.theme.PremiumAccess.active) {
                            showPremiumInfo = true
                        } else {
                            onOpenPremium()
                        }
                    }
                )
            }
            // ly_search — image_search 25sdp + "Search" (its 10sdp gap is
            // search marginEnd 5 + the row's 5sdp spacing).
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_search,
                label = stringResource(R.string.vu_search),
                iconSizeSdp = 25,
                onClick = {
                    toast(context, context.getString(R.string.vu_engine_pending))
                },
                modifier = Modifier.padding(end = s.d(5))
            )
            // ly_update — ImgCast 25sdp + "Update": reload the playlist.
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_update,
                label = stringResource(R.string.vu_update),
                iconSizeSdp = 25,
                onClick = viewModel::refresh
            )
            // ly_notifications — verbatim dead button (no onClick case in
            // the reference's switch; focus scale only).
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_nofitication,
                label = stringResource(R.string.vu_notification),
                iconSizeSdp = 20,
                onClick = {}
            )
            // ly_user_info → AccountInfoDlgFragment.
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_user_info,
                label = stringResource(R.string.vu_user_info),
                iconSizeSdp = 20,
                onClick = { showAccount = true }
            )
            // ly_change_user → UserListActivity(is_home).
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_change_user,
                label = stringResource(R.string.vu_change_user),
                iconSizeSdp = 20,
                onClick = onSwitchAccount
            )
            // ly_settings → SettingActivity.
            VuHomeIconButton(
                iconRes = R.drawable.vu_image_settings,
                label = stringResource(R.string.vu_home_settings),
                iconSizeSdp = 20,
                onClick = onOpenSettings
            )
        }

        // ── The three cards — reference geometry, this app's card design ──
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = s.d(20) + logoH, start = s.d(25))
        ) {
            CategoryCard(
                title = stringResource(R.string.hub_live_tv),
                subtitle = stringResource(R.string.channels_count, pl?.channelCount ?: 0),
                icon = Icons.Filled.Tv,
                gradient = Brush.linearGradient(
                    listOf(Color(0xFF00BCD4), Color(0xFF191970))
                ),
                lastUpdate = lastUpdate,
                spec = CardContentSpec.big(s),
                modifier = Modifier.size(s.d(180), s.d(180)),
                focusRequester = liveFocus,
                onClick = onOpenLive
            )
            CategoryCard(
                title = stringResource(R.string.hub_movies),
                subtitle = stringResource(R.string.movies_count, vodCounts.first),
                icon = Icons.Filled.Movie,
                gradient = Brush.linearGradient(
                    listOf(Color(0xFFFF6F00), Color(0xFF7B1FA2))
                ),
                lastUpdate = lastUpdate,
                spec = CardContentSpec.compact(s),
                modifier = Modifier
                    .padding(top = s.d(3), start = s.d(15))
                    .size(s.d(130), s.d(130)),
                onClick = onOpenMovies
            )
            CategoryCard(
                title = stringResource(R.string.hub_series),
                subtitle = stringResource(R.string.series_count, vodCounts.second),
                icon = Icons.Filled.VideoLibrary,
                gradient = Brush.linearGradient(
                    listOf(Color(0xFF43A047), Color(0xFF1B5E20))
                ),
                lastUpdate = lastUpdate,
                spec = CardContentSpec.compact(s),
                modifier = Modifier
                    .padding(top = s.d(3), start = s.d(25))
                    .size(s.d(130), s.d(130)),
                onClick = onOpenSeries
            )
            // ── v1.18.1 — the 4th card (user request: “in the Movies /
            //    Series / Live row”) — the SAVED VIDEOS library entry.
            //    Same compact geometry, the app's Electric-Indigo brand
            //    gradient, the floppy icon that matches the player's
            //    auto-save toggle, and the library's own count + newest
            //    save time-ago in the strip. ──
            if (savedFitsRow) {
                CategoryCard(
                    title = stringResource(R.string.hub_saved_videos),
                    // v1.18.2 — the count + the OFFLINE promise in one line
                    // (user request: “أريد أن تضع في البطاقة كي يفهم
                    // المستخدم (بلا أنترنت)”).
                    subtitle = stringResource(
                        R.string.saved_card_subtitle, savedSummary.first.toInt()
                    ),
                    // v1.19.0 — the floppy became the offline-watch glyph
                    // (download + play); the title itself now says
                    // “مشاهدة بلا أنترنت” (user request).
                    icon = Icons.Filled.Save,
                    iconRes = R.drawable.vu_ic_offline,
                    gradient = Brush.linearGradient(
                        listOf(Color(0xFF7C6FFF), Color(0xFF1A1251))
                    ),
                    lastUpdate = savedUpdate,
                    spec = CardContentSpec.compact(s),
                    modifier = Modifier
                        .padding(top = s.d(3), start = s.d(15))
                        .size(s.d(130), s.d(130)),
                    onClick = onOpenSaved
                )
            }
        }

        // ── ly_expiration (150×30sdp) + ly_logged (130×30sdp) — bottoms sit
        //    7sdp above the LIVE card's bottom edge; starts align with the
        //    MOVIES (220sdp) and SERIES (375sdp) card edges. ──
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    top = s.d(20) + logoH + s.d(143),
                    start = s.d(220)
                )
        ) {
            VuInfoPill(
                iconRes = R.drawable.vu_ic_expiration,
                text = stringResource(R.string.vu_expiration) + " " +
                    VuHomeContract.homeExpirationValue(
                        pl?.type ?: "", pl?.expiryDate,
                        stringResource(R.string.vu_unlimited),
                        LocalConfiguration.current.locales[0]
                    ),
                widthSdp = 150,
                modifier = Modifier
            )
            VuInfoPill(
                iconRes = R.drawable.vu_ic_logged_icon,
                text = stringResource(R.string.vu_logged_in) + " " +
                    VuHomeContract.homeLoggedInValue(
                        pl?.type ?: "", pl?.username, pl?.name ?: ""
                    ),
                widthSdp = 130,
                modifier = Modifier.padding(start = s.d(5))
            )
        }

        // ── ly_catch_up — bottom-end, marginBottom 22sdp / marginEnd 15sdp.
        //    v1.12.0 — FUNCTIONAL: opens the Catch-Up screen (PORTAL only —
        //    the stalker tv-archive engine; XC/M3U keep the pending toast). ──
        VuScalableRow(
            restScale = 0.85f,
            onClick = {
                // v1.12.1 — PORTAL (stalker tv-archive) AND XTREAM
                // (get_live_streams' tv_archive → the timeshift engine);
                // M3U keeps the pending toast.
                if (pl?.type == "PORTAL" || pl?.type == "XTREAM") onOpenCatchUp()
                else toast(context, context.getString(R.string.vu_engine_pending))
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = s.d(15), bottom = s.d(22))
        ) {
            Image(
                painter = painterResource(R.drawable.vu_image_catch_up),
                contentDescription = stringResource(R.string.vu_catch_up),
                colorFilter = ColorFilter.tint(VuPalette.White),
                modifier = Modifier.size(width = s.d(12), height = s.d(15))
            )
            Text(
                stringResource(R.string.vu_catch_up),
                color = VuPalette.White,
                fontSize = s.t(10),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(start = s.d(8))
            )
        }

        // ── v1.18.1 fallback — narrow screens: the Saved Videos entry as
        //    a bottom-start scalable row (ly_catch_up's mirror at the
        //    bottom-end). v1.19.0: the offline glyph + the renamed label
        //    (identical to the hub card). ──
        if (!savedFitsRow) {
            VuScalableRow(
                restScale = 0.85f,
                onClick = onOpenSaved,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = s.d(15), bottom = s.d(22))
            ) {
                Image(
                    painter = painterResource(R.drawable.vu_ic_offline),
                    contentDescription = stringResource(R.string.hub_saved_videos),
                    colorFilter = ColorFilter.tint(VuPalette.White),
                    modifier = Modifier.size(s.d(15))
                )
                Text(
                    stringResource(R.string.hub_saved_videos),
                    color = VuPalette.White,
                    fontSize = s.t(10),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = s.d(8))
                )
            }
        }

        // ly_install ("FREE PREMIUM UPGRADE") — GONE: the reference hides it
        // when purchased, and this app always renders the purchased variant.

        // ── Dialogs ──
        if (showAccount) {
            VuAccountDialog(
                type = pl?.type ?: "",
                username = pl?.username,
                playlistName = pl?.name ?: "",
                expiryDate = pl?.expiryDate,
                onClose = { showAccount = false },
                onLogOut = onSwitchAccount
            )
        }
        if (refreshing) {
            VuRefreshDialog()
        }

        // v2.1.0 — the premium member's own subscription card.
        if (showPremiumInfo) {
            com.superz.iptvplayer.ui.components.PremiumInfoDialog(
                onDismiss = { showPremiumInfo = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Shared bits
// ─────────────────────────────────────────────────────────────────

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

/** One-shot toast helper (fires once per trigger raise). */
@Composable
private fun LaunchedToast(message: String, trigger: Boolean, onConsumed: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(trigger) {
        if (trigger) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            onConsumed()
        }
    }
}

/**
 * The reference's focus contract: every home control renders at a rest
 * scale (0.85 / 0.9) and springs to 1.0 when focused OR pressed
 * (onFocusChange + the touch fallback in one place).
 */
@Composable
private fun VuScalableRow(
    restScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed || focused) 1f else restScale,
        animationSpec = PremiumSpring,
        label = "vuScale"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}

/**
 * v2.0.4 — THE PREMIUM CROWN BUTTON for the hub's top-end icon row: the
 * professional metallic crown ([OriaCrown], full-color gold — untinted, it
 * carries its own metal) in the exact same 40sdp column + 0.85→1.0 focus
 * scale treatment as its icon siblings, with the label under it. The hub
 * is the app's HOME — professional subscription apps keep their upgrade
 * entry visible here, not buried in settings.
 */
@Composable
private fun VuPremiumCrownButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || focused) 1f else 0.85f,
        animationSpec = PremiumSpring,
        label = "crownScale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(s.d(40))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        // v2.0.5 — VuCrownIcon (NOT Icon()): Icon()'s default tint
        // flattened the metallic crown to a black silhouette; the crown
        // must keep its own gold.
        VuCrownIcon(
            contentDescription = label,
            modifier = Modifier.size(s.d(24))
        )
        Spacer(Modifier.weight(1f))
        Text(
            label,
            color = VuPalette.White,
            fontSize = s.t(9),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = s.d(3))
        )
    }
}

/**
 * One 40sdp top-bar column (ly_settings & co.): icon (20sdp — 25sdp for
 * ly_update/ly_search) flush at the top, 9sdp white label pinned to the
 * bottom (marginTop 3sdp), white icon tint, 0.85→1.0 focus scale.
 */
@Composable
private fun VuHomeIconButton(
    iconRes: Int,
    label: String,
    iconSizeSdp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || focused) 1f else 0.85f,
        animationSpec = PremiumSpring,
        label = "iconScale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(s.d(40))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            colorFilter = ColorFilter.tint(VuPalette.White),   // app:tint text_color
            modifier = Modifier.size(s.d(iconSizeSdp))
        )
        Spacer(Modifier.weight(1f))
        Text(
            label,
            color = VuPalette.White,
            fontSize = s.t(9),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = s.d(3))
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// The category card — THIS app's design (gradient body, white icon,
// bold title, count subtitle, press spring) at the reference's geometry,
// plus the reference's "Last Update :" strip inside the card bottom.
// ─────────────────────────────────────────────────────────────────

/**
 * Content sizing per card class — the v1.6.0 clipping fix. The v1.4.x card
 * always drew 42dp icon + 20sp title + 11sp subtitle (≈135dp of content),
 * so the 130dp SERIES card sliced the count text in half. Big (LIVE,
 * 180sdp card / 150sdp body) keeps the classic proportions; compact
 * (MOVIES/SERIES, 130sdp card / 107sdp body) scales everything down with
 * ~7sdp of verified slack under the tallest default line heights.
 */
private data class CardContentSpec(
    val padOuter: Dp,
    val padInner: Dp,
    val iconSize: Dp,
    val titleSize: TextUnit,
    val titleSpacing: TextUnit,
    val subtitleSize: TextUnit,
    val spacerIconTitle: Dp,
    val spacerTitleSubtitle: Dp,
    val stripHeight: Dp,
    val stripTextSize: TextUnit,
    val cornerRadius: Dp
) {
    companion object {
        fun big(s: com.superz.iptvplayer.ui.theme.VuSdp) = CardContentSpec(
            padOuter = s.d(14), padInner = s.d(6),
            iconSize = s.d(46), titleSize = s.t(20), titleSpacing = s.t(2),
            subtitleSize = s.t(11),
            spacerIconTitle = s.d(10), spacerTitleSubtitle = s.d(4),
            stripHeight = s.d(30), stripTextSize = s.t(9), cornerRadius = s.d(22)
        )

        fun compact(s: com.superz.iptvplayer.ui.theme.VuSdp) = CardContentSpec(
            padOuter = s.d(10), padInner = s.d(3),
            iconSize = s.d(30), titleSize = s.t(15), titleSpacing = s.t(1),
            subtitleSize = s.t(10),
            spacerIconTitle = s.d(8), spacerTitleSubtitle = s.d(3),
            stripHeight = s.d(23), stripTextSize = s.t(7), cornerRadius = s.d(22)
        )
    }
}

@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    lastUpdate: String,
    spec: CardContentSpec,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    // v1.19.0 — optional drawable icon (painter) rendered INSTEAD of the
    // ImageVector — the offline library's custom glyph shares the exact
    // artwork of the browse pill's icon (one visual identity).
    iconRes: Int? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    // 0.9 rest scale — the reference's card focus contract.
    val scale by animateFloatAsState(
        targetValue = if (pressed || focused) 1f else 0.9f,
        animationSpec = PremiumSpring,
        label = "catScale"
    )
    val s = rememberVuSdp()

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // v1.19.2 — the golden animated edge (user request: “قم فقط
            // بإضافة التأثير الذهبي المتحرك على حواف البطاقات” — colors
            // untouched): the static gold wire + the two orbiting light
            // beads ride ON TOP of the card's opaque gradient, along the
            // rounded outline; the ring thickens slightly on focus.
            .vuGoldenBorder(
                cornerRadius = spec.cornerRadius,
                strokeWidth = s.d(1.5f),
                focused = focused
            )
            .clip(RoundedCornerShape(spec.cornerRadius))
            .background(gradient)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        // Card body — icon + title + count (our design), centered in the
        // area ABOVE the update strip (the reference's ly_live_info zone).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = spec.stripHeight)
                .padding(spec.padOuter)
                .padding(spec.padInner),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(spec.iconSize)
                )
            } else {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(spec.iconSize)
                )
            }
            Spacer(Modifier.height(spec.spacerIconTitle))
            Text(
                title,
                color = Color.White,
                fontSize = spec.titleSize,
                fontWeight = FontWeight.Black,
                letterSpacing = spec.titleSpacing,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(spec.spacerTitleSubtitle))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = spec.subtitleSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // "Last Update :" strip — the reference's ly_live_update row
        // (height 30sdp live / 23sdp vod+series, 9sdp / 7sdp text) on the
        // translucent band across the card's bottom.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(spec.stripHeight)
                .clip(RoundedCornerShape(bottomStart = spec.cornerRadius, bottomEnd = spec.cornerRadius))
                .background(VuPalette.PanelOverlay)
        ) {
            Text(
                stringResource(R.string.vu_last_update),
                color = VuPalette.White,
                fontSize = spec.stripTextSize,
                maxLines = 1
            )
            if (lastUpdate.isNotEmpty()) {
                Text(
                    lastUpdate,
                    color = VuPalette.White,
                    fontSize = spec.stripTextSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = s.d(3))
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// ly_expiration / ly_logged — 30sdp translucent pills (home_main_bg,
// radius 15sdp) with a 15sdp icon and a 9sdp single-line value.
// ─────────────────────────────────────────────────────────────────

@Composable
private fun VuInfoPill(
    iconRes: Int,
    text: String,
    widthSdp: Int,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || focused) 1f else 0.9f,
        animationSpec = PremiumSpring,
        label = "pillScale"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .size(width = s.d(widthSdp), height = s.d(30))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(s.d(15)))
            .background(VuPalette.PanelOverlay)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {}    // the reference registers these listeners
            )                   // but has no click case — verbatim dead pills
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .padding(start = s.d(15))
                .size(s.d(15))
        )
        Text(
            text,
            color = VuPalette.White,
            fontSize = s.t(9),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = s.d(5), end = s.d(10))
                .weight(1f, fill = false)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// AccountInfoDlgFragment — 350×200sdp window, "Account Info" header,
// close button, the three visible rows (User Info / Account Status /
// Expiry Date, labels grey + values white from the 50% line) and the
// "Log Out" button (130×25sdp) that hands off to the accounts page.
// ─────────────────────────────────────────────────────────────────

@Composable
private fun VuAccountDialog(
    type: String,
    username: String?,
    playlistName: String,
    expiryDate: Long?,
    onClose: () -> Unit,
    onLogOut: () -> Unit
) {
    val s = rememberVuSdp()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = s.d(350), height = s.d(200))
                .clip(RoundedCornerShape(10.dp))
                .background(DialogBoxBg)
        ) {
            Text(
                stringResource(R.string.vu_account),
                color = VuPalette.White,
                fontSize = s.t(15),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = s.d(15))
            )
            Image(
                painter = painterResource(R.drawable.vu_image_close),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = s.d(10), top = s.d(15))
                    .size(s.d(25))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = s.d(45), start = s.d(20), end = s.d(20))
            ) {
                VuAccountRow(
                    stringResource(R.string.vu_user_info),
                    VuHomeContract.accountUserValue(type, username)
                )
                VuAccountRow(
                    stringResource(R.string.vu_account_status),
                    VuHomeContract.ACCOUNT_STATUS_ACTIVE
                )
                VuAccountRow(
                    stringResource(R.string.vu_expiry_date),
                    VuHomeContract.accountExpiryValue(
                        type, expiryDate, LocalConfiguration.current.locales[0]
                    )
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = s.d(15))
                    .size(width = s.d(130), height = s.d(25))
                    .clip(RoundedCornerShape(s.d(15)))
                    .background(VuPalette.PurpleBrush)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogOut
                    )
            ) {
                Text(
                    stringResource(R.string.vu_log_out),
                    color = VuPalette.White,
                    fontSize = s.t(10),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun VuAccountRow(label: String, value: String) {
    val s = rememberVuSdp()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = s.d(5))
    ) {
        // str_* label — lb_grey, start-anchored.
        Text(
            label,
            color = VuPalette.Grey,
            fontSize = s.t(12),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        // txt_* value — white, starting at the 50% guideline.
        Text(
            value,
            color = VuPalette.White,
            fontSize = s.t(12),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// CustomProgressDlgFragment — the ly_update reload's progress dialog
// (its layout was stripped from the reference APK; reconstructed in the
// same dialog language: 350×200sdp window, spinner, "Please Wait…",
// the two download-description lines).
// ─────────────────────────────────────────────────────────────────

@Composable
private fun VuRefreshDialog() {
    val s = rememberVuSdp()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimColor)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = s.d(350), height = s.d(200))
                .clip(RoundedCornerShape(10.dp))
                .background(DialogBoxBg)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(
                    color = VuPalette.White,
                    strokeWidth = s.d(3),
                    modifier = Modifier.size(s.d(35))
                )
                Text(
                    stringResource(R.string.vu_text_please_wait),
                    color = VuPalette.White,
                    fontSize = s.t(15),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = s.d(10))
                )
                Text(
                    stringResource(R.string.vu_text_download_desc),
                    color = VuPalette.Grey,
                    fontSize = s.t(10),
                    modifier = Modifier.padding(top = s.d(8))
                )
                Text(
                    stringResource(R.string.vu_text_download_desc_2),
                    color = VuPalette.Grey,
                    fontSize = s.t(10)
                )
            }
        }
    }
}

/** black_65 — every reference dialog's scrim. */
private val ScrimColor = Color(0xA6000000)

/** colorBoxBg — the reference dialog window fill (#2b2b37). */
private val DialogBoxBg = Color(0xFF2B2B37)

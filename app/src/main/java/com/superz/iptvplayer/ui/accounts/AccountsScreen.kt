package com.superz.iptvplayer.ui.accounts

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.ui.components.VuPremiumBannerPill
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.OriaDateFmt
import com.superz.iptvplayer.data.remote.OriaRemote
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuHomeButton
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.VuSdp
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuAccountsBackground
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle
import kotlinx.coroutines.delay

/**
 * Accounts page — v1.4.9: an EXACT design copy of the reference app's
 * user-list page (activity_user_list.xml + item_user.xml + UserListActivity),
 * with the reference's REAL artwork and dimension system:
 *
 *   • sdp scaling (VuSdp): the reference sizes every element with the
 *     intuit sdp library — values scale with the screen's smallest width
 *     (sw/300 in 30dp buckets, capped at 3.6×). On the user's 2400×1080
 *     TV that is 1.8×, which v1.4.8's plain-dp sizes did not reproduce —
 *     the root cause of the "icons and buttons are smaller" feedback.
 *   • v2.0.6 — the crowned-logo PremiumLL block (no badge text under
 *     the logo anymore — see VuReference.VuPremiumLogo) and the header's
 *     ly_back became a HOME icon into the login page ([VuHomeButton]);
 *     the dead "List of User" pill was REMOVED (user directive — it had
 *     no job and wasted the centered space).
 *   • ly_back→home: icon-only, white glyph, 0.9 → 1.0 focus scale (same
 *     language as the reference's buttons).
 *   • ly_add_user 90×23sdp (marginEnd 15sdp): #33707070 r15sdp → purple
 *     gradient when focused; icon+text #9f94a4 → white when focused.
 *   • recycler_user: margins 20sdp horizontal / 15sdp below the bar /
 *     10dp bottom, GridLayoutManager(3).
 *   • item_user cards: margin 10sdp, #33707070 r5sdp → purple gradient
 *     (item_user_focused_bg) when focused/selected, vertical padding 10sdp
 *     — browser-window avatar (image_user) 30×30sdp (marginStart 15sdp) |
 *     1.5dp white divider (marginStart 10sdp) | info column (margins 10sdp):
 *     TIER 10sdp bold ([VuAccountTier]: crown+"Premium" gold / "Free" neon
 *     green, replacing the localized name) + btn_edit 18×16sdp +
 *     btn_delete 18×16sdp (ic_edit/ic_delete vectors, #dadada) |
 *     url 8sdp (marginTop 3sdp) | username 8sdp.
 *   • v1.12.7 — TWO card fixes (the "distorted card" report):
 *     (1) TEXT BINDING, reference-verbatim (UserRecyclerAdapter):
 *     txt_url = PlayListModel.domain → PORTAL shows the portal URL,
 *     XTREAM the server; txt_username = username → PORTAL shows the MAC,
 *     XTREAM the username, M3U/BROWSER store none → empty row. Until now a
 *     PORTAL account wrongly showed "—" / "PORTAL" (m3uUrl/type).
 *     (2) LINE HEIGHT, the actual distortion: every card Text now sets an
 *     EXPLICIT lineHeight in the same fontScale-canceled unit as its
 *     fontSize (s.t). Without it the Texts inherited the theme bodyLarge
 *     22sp lineHeight — an sp value that, unlike s.t(), STILL SCALES with
 *     the system font scale. On the user's TV (fontScale ≈ 3) each 8sdp
 *     line rendered inside a 66px box around its 29px glyphs: the info
 *     column ballooned 137→209px, the card 219→281px, and the three rows
 *     spread apart (name top / url middle / username bottom). The
 *     reference's TextViews are immune (dp-sized, no inherited lineHeight)
 *     — with lineHeight = 1.2–1.25×fontSize in canceled sp our rows pack
 *     exactly like the reference's natural Roboto metrics and the card
 *     returns to its designed ~59sdp height.
 *
 * Behaviors (UserListActivity mapped to this project): tap a card →
 * activate + loading; edit → VU-styled in-place edit dialog; delete →
 * immediate removal + toast; Add User → the login screen; home → the
 * login/method page (v2.0.6 — the old back finished the app).
 */
@Composable
fun AccountsScreen(
    onAddUser: () -> Unit,
    onAccountSelected: () -> Unit,
    onEditAccount: (Playlist) -> Unit = {},
    /** v2.0.6 — the header's home button: into the login page (the old
     *  back button finished the whole app — user directive). */
    onHome: () -> Unit = {},
    /** v2.0.4 — the premium banner's route (the shared golden pill). */
    onPremium: () -> Unit = {},
    viewModel: AccountsViewModel = viewModel()
) {
    val s = rememberVuSdp()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // v2.0.4 — the premium entry appears only when the panel enables it
    // (premium.enabled AND a host) — the same gate every entry shares.
    val premiumLive = OriaBranding.premiumEnabled
    // v2.0.6 — the live premium host, read fresh per config epoch: an
    // account whose server IS this host is a PREMIUM account (that is
    // exactly how the premium page creates them — loginXtream against
    // the locked host). Everyone else is FREE.
    val premiumHost = remember(OriaRemote.configEpoch) { OriaRemote.current.premium.host }

    val gridState = rememberLazyGridState()
    val selectedIdx = playlists.indexOfFirst { it.isActive }
    val selectedFocus = remember { FocusRequester() }

    // The reference scrolls to + focuses the selected portal on entry
    // (recycler_user.requestFocus() + scrollToPosition(selected_position)).
    LaunchedEffect(playlists.size, selectedIdx) {
        if (selectedIdx >= 0) {
            gridState.scrollToItem(selectedIdx)
            delay(50)   // let the target card compose after the scroll
            runCatching { selectedFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // v2.0.3 — the accounts page background is panel-controlled
            // (branding.bgAccountsUrl); blank URL paints the stock gradient
            // exactly like the old vuBackground() did.
            .vuAccountsBackground()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ══════════ TOP BAR (home + PremiumLL + ly_add_user) ══════════
            // v2.0.6 — the header lost its dead "List of User" pill (user
            // directive: it had no job — remove it to free the space) and
            // ly_back became a HOME icon into the login page. PremiumLL
            // (logo + corner crown) still defines the bar's height; the
            // Add User pill is vertically centered on it, end-anchored.
            //
            // v2.2.1 — THE UPGRADE BANNER RIDES THE TOP BAR TOO (user:
            // "ترفع زر upgrade to premium الموجود في صفحة بطاقات الحسابات
            // الى اعلى قليلا ليكن بنفس مستوى الازرار العلوية"): centered
            // in the freed "List of User" air, vertically centered on the
            // logo band — the SAME level as the home/logo/Add User
            // controls. BoxWithConstraints caps its width so it can never
            // collide with them on narrow screens (it shrinks + ellipsizes
            // instead); the banner's old dedicated row below the bar is
            // gone, handing the grid its height back.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                var premiumH by remember { mutableStateOf(Dp.Unspecified) }

                // home + PremiumLL — anchored to the start, top padding 15sdp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = s.d(15))              // PremiumLL marginTop 15sdp
                ) {
                    // v2.0.6 — ly_back → the home button (icon-only, into
                    // the login/method page — the old back finished the app)
                    VuHomeButton(onClick = onHome)
                    // PremiumLL — the crowned logo block (marginStart 10sdp)
                    VuPremiumLogo(
                        modifier = Modifier.padding(start = s.d(10)),
                        onHeight = { premiumH = it }
                    )
                }

                // Vertical center of the PremiumLL zone (15sdp top offset +
                // (premiumH − 23sdp) / 2). Until measured, estimate 47sdp.
                val pillTop = s.d(15) +
                    ((premiumH.takeUnless { it == Dp.Unspecified } ?: s.d(47)) - s.d(23)) / 2

                // ly_add_user — 90×23sdp at the end (marginEnd 15sdp).
                VuAddUserPill(
                    onClick = onAddUser,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = pillTop, end = s.d(15))
                )

                // ══════════ v2.0.4/v2.0.6/v2.2.1 — THE PREMIUM BANNER ══════════
                // The user's directive: premium entries on the app's other
                // pages "مثل التطبيقات الاحترافية" — the SAME shared crown
                // pill as the method-picker page, one identity everywhere,
                // one panel-controlled label. v2.1.0 — it vanishes once
                // premium is ACTIVE. v2.2.1 — it rides the top bar's centered
                // air, level with the header's own buttons.
                if (premiumLive && OriaBranding.premiumCtaVisible &&
                    !com.superz.iptvplayer.ui.theme.PremiumAccess.active
                ) {
                    val premiumHeight = premiumH.takeUnless { it == Dp.Unspecified } ?: s.d(47)
                    val bannerTop = s.d(15) +
                        ((premiumHeight - s.d(30)) / 2).coerceAtLeast(0.dp)
                    val bannerW = minOf(s.d(280), maxWidth - s.d(200))
                        .coerceAtLeast(s.d(100))
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = bannerTop)
                            .width(bannerW)
                            .height(s.d(30))
                    ) {
                        VuPremiumBannerPill(
                            label = OriaBranding.premiumCtaText
                                .ifBlank { stringResource(R.string.premium_cta) },
                            onClick = onPremium
                        )
                    }
                }
            }

            // ══════════ recycler_user — 3-column grid ══════════
            Box(Modifier.fillMaxSize()) {
                if (playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.no_playlists),
                        color = VuPalette.White,
                        fontSize = s.t(9),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = s.d(40))
                    )
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        contentPadding = PaddingValues(
                            start = s.d(20), end = s.d(20), top = s.d(15), bottom = 10.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        gridItems(playlists, key = { it.id }) { pl ->
                            VuUserCard(
                                playlist = pl,
                                // v2.0.6/v2.1.1 — the tier: host match × the
                                // Xtream exp_date (premium / expired / free) —
                                // the professional account-state architecture.
                                tier = VuAccountTier.tierOf(
                                    pl.server, premiumHost, pl.expiryDate,
                                    System.currentTimeMillis() / 1000L
                                ),
                                selected = pl.isActive,
                                enabled = !busy,
                                focusRequester = if (pl.isActive) selectedFocus else null,
                                onClick = { viewModel.select(pl, onAccountSelected) },
                                // v1.4.11 — the reference's flow: edit opens
                                // the login form (EditPortalActivity) filled
                                // from the DB, replacing the in-place dialog.
                                onEdit = { onEditAccount(pl) },
                                onDelete = {
                                    viewModel.delete(pl) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.vu_portal_removed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                }

                // Activation spinner (select → activate → loading hand-off)
                if (busy) {
                    CircularProgressIndicator(
                        color = VuPalette.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(s.d(30))
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Reference widgets (activity_user_list.xml + item_user.xml, verbatim —
// sdp-scaled, real artwork; VuHomeButton/VuPremiumLogo live in VuReference.kt)
// ═══════════════════════════════════════════════════════════════════

/**
 * ly_add_user — 90×23sdp: the reference's btn_select_type geometry
 * (radius 15sdp; icon_user 13×13sdp marginStart 15sdp + "Add User" 9sdp).
 *
 * v1.19.2 — the golden identity. v1.19.3 — TRUE gold (vuGoldFace).
 *
 * v2.2.1 — THE PLAN-CARD CONTRACT (user directive: “واصل تطبيقه على صفحة
 * بطاقات الحسابات”): the pill wears the plan cards' exact skin through
 * the shared [vuPlanCardStyle] — animated golden ring (0.8sdp rest →
 * 1.6sdp lit), DARK GLASS resting face, warm-bronze GLASS lit face, GOLD
 * icon + text on both faces. The solid-gold focus face is retired; the
 * Add User control reads as a little plan card, same as every button in
 * the app's new family.
 */
@Composable
private fun VuAddUserPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val tint = VuGold.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .size(width = s.d(90), height = s.d(23))
            .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(R.drawable.vu_icon_user),
            contentDescription = stringResource(R.string.vu_add_user),
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .padding(start = s.d(15))
                .size(s.d(13))
        )
        Spacer(Modifier.width(s.d(5)))
        Text(
            stringResource(R.string.vu_add_user),
            color = tint,
            fontSize = s.t(9),
            maxLines = 1
        )
    }
}

/**
 * item_user.xml — one account card: browser-window avatar (image_user)
 * 30×30sdp (marginStart 15sdp), 1.5dp divider (marginStart 10sdp,
 * avatar-height), info column (margins 10sdp): tier row + btn_edit
 * 18×16sdp + btn_delete 18×16sdp, url 8sdp (marginTop 3sdp), username 8sdp.
 * Outer margin 10sdp; vertical padding 10sdp.
 *
 * v2.0.6/v2.1.1 — THE TIER ARCHITECTURE (crown + "Premium" gold / "Free"
 * neon green / "Expired" grey + the subscription date lines) rides in the
 * CONTENT — the professional account-state system survives every face.
 *
 * v2.2.1 — THE PLAN-CARD CONTRACT, list-row edition (user directive:
 * “واصل تطبيقه على صفحة بطاقات الحسابات”): every card is a little plan
 * card — DARK GLASS resting face + static gold hairline + gold-ish
 * content; the focused or selected/active card lights up with the
 * warm-bronze glass face + the ANIMATED golden ring (1.6dp), exactly the
 * playing-channel row of the mini player page. The old tier-specific
 * resting tints and the solid-gold highlight face are retired; the tier
 * still reads instantly from the crown + label + date line. The animated
 * border composes ONLY on lit cards — a long list stays cheap on TV boxes.
 */
@Composable
private fun VuUserCard(
    playlist: Playlist,
    tier: VuAccountTier.Tier,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val highlighted = selected || focused
    val premium = tier == VuAccountTier.Tier.PREMIUM
    val expired = tier == VuAccountTier.Tier.EXPIRED
    // content colors — gold on BOTH faces (the plan-card text contract);
    // the tier label keeps its own identity color.
    val subColor = VuGold.Text.copy(alpha = 0.88f)
    val dividerColor = VuGold.Gold.copy(alpha = if (highlighted) 0.55f else 0.40f)
    val actionTint = VuGold.Text.copy(alpha = 0.80f)

    // v1.12.7 — UserRecyclerAdapter.onBindViewHolder, verbatim:
    // txt_url = domain (PORTAL = the real portal URL, XTREAM = the server,
    // M3U/BROWSER = the playlist URL); txt_username = username (PORTAL = the
    // MAC, XTREAM = the username, M3U/BROWSER store none → empty row).
    val isXcOrPortal = playlist.type == "XTREAM" || playlist.type == "PORTAL"
    val urlText = if (isXcOrPortal) (playlist.server ?: "") else (playlist.m3uUrl ?: "")
    val usernameText = if (isXcOrPortal) (playlist.username ?: "") else ""

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(s.d(10))                       // android:layout_margin 10sdp
            .fillMaxWidth()
            // v2.2.1 — the plan-card row contract: dark glass + static gold
            // hairline at rest; the lit warm-bronze face + animated ring
            // while focused or selected (the ACTIVE account glows like the
            // playing channel row).
            .vuPlanCardRowStyle(cornerRadius = s.d(5), focused = focused, selected = selected)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = s.d(10))
    ) {
        // image_user — 30×30sdp browser-window avatar (marginStart 15sdp)
        Image(
            painter = painterResource(R.drawable.vu_image_user),
            contentDescription = null,
            modifier = Modifier
                .padding(start = s.d(15))
                .size(s.d(30))
        )
        // divider_view — 1.5dp rule, height of the avatar
        Box(
            modifier = Modifier
                .padding(start = s.d(10))
                .width(1.5.dp)
                .height(s.d(30))
                .background(dividerColor)
        )
        // ly_info
        Column(
            modifier = Modifier
                .padding(start = s.d(10), end = s.d(10))
                .weight(1f)
        ) {
            // ly_first — the TIER identity + edit/delete action buttons.
            // v2.0.6 — the localized account name (“حساب N”) is REPLACED by
            // the tier: crown + "Premium" (gold) or "Free" (neon green).
            // v2.1.1 — the third state: crown + "Expired" (grey) — the
            // premium-host account whose subscription ended.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (premium || expired) {
                    // the professional crown — full metal on BOTH faces now
                    // (the lit face is bronze GLASS, not solid gold — never
                    // Icon()). EXPIRED rests it at 70% — present but dimmed.
                    VuCrownIcon(
                        onGold = false,
                        modifier = Modifier
                            .padding(end = s.d(4))
                            .graphicsLayer { alpha = if (expired) 0.70f else 1f }
                            .size(s.d(13))
                    )
                    Text(
                        if (premium) VuAccountTier.PREMIUM_LABEL
                        else VuAccountTier.EXPIRED_LABEL,
                        color = if (premium) VuGold.Text
                        else VuPalette.TintGrey,   // expired — the muted end state
                        fontSize = s.t(10),
                        lineHeight = s.t(12),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // "Free" — the literal English word in NEON GREEN on a
                    // small dark glass chip: legible on BOTH card faces.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(s.d(4)))
                            .background(VuPalette.Black65)
                            .padding(horizontal = s.d(6), vertical = s.d(2))
                    ) {
                        Text(
                            VuAccountTier.FREE_LABEL,
                            color = VuPalette.NeonGreen,
                            fontSize = s.t(10),
                            lineHeight = s.t(12),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                // btn_edit — 18×16sdp, ic_edit (marginEnd 5sdp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = s.d(5))
                        .size(width = s.d(18), height = s.d(16))
                        .clickable(enabled = enabled, onClick = onEdit)
                ) {
                    Image(
                        painter = painterResource(R.drawable.vu_ic_edit),
                        contentDescription = stringResource(R.string.cd_edit_user),
                        colorFilter = ColorFilter.tint(actionTint),
                        modifier = Modifier
                            .padding(s.d(1))
                            .size(width = s.d(16), height = s.d(14))
                    )
                }
                // btn_delete — 18×16sdp, ic_delete
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = s.d(18), height = s.d(16))
                        .clickable(enabled = enabled, onClick = onDelete)
                ) {
                    Image(
                        painter = painterResource(R.drawable.vu_ic_delete),
                        contentDescription = stringResource(R.string.cd_delete_user),
                        colorFilter = ColorFilter.tint(actionTint),
                        modifier = Modifier
                            .padding(s.d(1))
                            .size(width = s.d(16), height = s.d(14))
                    )
                }
            }
            // txt_url — 8sdp (marginTop 3sdp): PORTAL = portal URL,
            // XTREAM = server, M3U/BROWSER = playlist URL (reference domain).
            Text(
                urlText,
                color = subColor,
                fontSize = s.t(8),
                lineHeight = s.t(10),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = s.d(3))
            )
            // txt_username — 8sdp: PORTAL = MAC, XTREAM = username,
            // M3U/BROWSER = empty (the reference stores no username for them).
            Text(
                usernameText,
                color = subColor,
                fontSize = s.t(8),
                lineHeight = s.t(10),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // v2.1.1 — the subscription date line every professional account
            // card carries: “Ends 12 Nov 2026” (gold) while the subscription
            // lives, “Ended 3 Oct 2026” (grey) once it has passed. Only on
            // premium-host cards with a known Xtream exp_date.
            // v2.1.3 — the date now follows the APP's language (user:
            // “يضهر تاريخ في الحساب باللغة العربية” while running English):
            // OriaDateFmt builds it from R.array.oria_months — the UI's own
            // localized month names — instead of SimpleDateFormat's
            // Locale.getDefault(), which stays the DEVICE locale (Arabic)
            // even after Settings switches the app to English.
            if ((premium || expired) && playlist.expiryDate != null) {
                val months = stringArrayResource(R.array.oria_months).toList()
                val expiryText = OriaDateFmt.format(playlist.expiryDate!! * 1000L, months)
                Text(
                    stringResource(
                        if (premium) R.string.premium_ends_on else R.string.premium_ended_on,
                        expiryText
                    ),
                    color = if (premium) VuGold.Text.copy(alpha = 0.80f)
                    else VuPalette.TintGrey,
                    fontSize = s.t(7),
                    lineHeight = s.t(9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = s.d(2))
                )
            }
        }
    }
}

/**
 * v2.0.6 — THE ACCOUNT TIER (pure, unit-tested in VuAccountTierTest).
 *
 * An account is PREMIUM exactly when its stored server IS the live config's
 * premium host — because that is precisely how the premium page creates
 * accounts (PremiumScreen → loginXtream(server = premium.host)): the panel
 * owns the tier, the app just reads its own playlist rows against it. Every
 * other account is FREE. URL comparison normalizes the scheme, trailing
 * slashes and case so "http://darplayer.xyz:8080/" (the panel's host) and
 * the playlist's stored form are always the same string underneath.
 *
 * v2.1.1 — THE TIER IS DATE-AWARE (user directive: "نفس معمارية التطبيقات
 * الاحترافية كي تضهر الحساب premium و الحساب غير premium و العبارات التي
 * تستخدمها التطبيقات الاحترافية"): a premium-host account whose Xtream
 * exp_date has passed is no longer "Premium" — it renders as EXPIRED, the
 * professional subscription-manager state (Spotify: "Your plan ended",
 * App Store: "Expired"). Same rule as [PremiumPolicy.status] in the small:
 * a null exp_date on the locked host keeps the account premium (the panel's
 * own server would not hand out a dead line).
 */
object VuAccountTier {

    /** The literal English tier words — NEVER localized (user directive:
     *  "Free" stays "Free" even when the app runs in Arabic; same contract
     *  for the crowned "Premium" card and the v2.1.1 "Expired" state). */
    const val FREE_LABEL = "Free"
    const val PREMIUM_LABEL = "Premium"
    const val EXPIRED_LABEL = "Expired"

    /** v2.1.1 — the three professional account states. */
    enum class Tier { FREE, PREMIUM, EXPIRED }

    /**
     * v2.1.1 — the full tier decision: host match × Xtream exp_date.
     * [expiryDate] is epoch SECONDS (the playlist's stored Xtream field);
     * [nowEpochSeconds] is injectable for the paper tests.
     */
    fun tierOf(
        server: String?,
        premiumHost: String,
        expiryDate: Long?,
        nowEpochSeconds: Long
    ): Tier {
        if (!isPremium(server, premiumHost)) return Tier.FREE
        if (expiryDate == null) return Tier.PREMIUM           // open-ended line
        return if (expiryDate > nowEpochSeconds) Tier.PREMIUM else Tier.EXPIRED
    }

    /** True when the playlist's server is the panel's premium host. */
    fun isPremium(server: String?, premiumHost: String): Boolean {
        if (premiumHost.isBlank() || server.isNullOrBlank()) return false
        return normalize(server) == normalize(premiumHost)
    }

    private fun normalize(url: String): String =
        url.trim()
            .lowercase()          // BEFORE the prefix strips — schemes arrive
            .removePrefix("https://")   // in every case spelling (HTTP://…)
            .removePrefix("http://")
            .trimEnd('/')
}

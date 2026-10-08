package com.superz.iptvplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.OriaDateFmt
import com.superz.iptvplayer.ui.theme.PremiumAccess
import com.superz.iptvplayer.ui.theme.PremiumFeature
import com.superz.iptvplayer.ui.theme.PremiumPolicy
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuGoldenBorder

/**
 * v2.1.0 — THE PREMIUM GATE DIALOGS (user, 2026-10-07: premium becomes a
 * real date-based subscription with gated features).
 *
 * One shared look, the app's own idiom (the OriaAnnouncementOverlay
 * pattern: a true modal scrim + a dark glass panel with the golden
 * identity):
 *
 *  • [PremiumUpsellDialog] — what a FREE user sees after burning the one
 *    free use of a gated feature, or when trying to add a 4th account:
 *    the crown, the exact rule ("one free use, then subscribe"), the
 *    feature checklist, and the gold ACTIVATE CTA;
 *  • [PremiumInfoDialog] — what a PREMIUM user sees pressing the crown
 *    button / the channels-grid premium pill (user: "عند النقر عليها
 *    تعرض معلومات حسابه و الميزات المفعلة"): the account, the start and
 *    expiry dates straight off the Xtream account, the days left, and
 *    the enabled-feature checklist.
 *
 * v2.1.3 — TWO STRUCTURAL FIXES (user: "تنيم البطاقة و تصميمها ليس
 * احترافي وفيها مشكل المعلومات في اسفلها مقصوص ولا تضهر باقي
 * المعلومات"):
 *
 *  • THE CLIPPED BOTTOM — the dialogs were fixed, non-scrollable stacks:
 *    on a large-font device the content simply ran past the viewport and
 *    the tail (features + the Close pill) was cut off. Both cards are now
 *  height-capped at 92% of the dialog viewport and VERTICALLY SCROLLABLE
 *  inside the golden frame — every line is reachable at any font scale,
 *  and short content still centers exactly as before.
 *  • THE DATES — they now speak the APP's language (OriaDateFmt over
 *  R.array.oria_months) instead of SimpleDateFormat's Locale.getDefault()
 *  (the device locale, which kept the month names Arabic while the UI ran
 *  English — the user's report).
 *  • [PremiumInfoDialog] redesigned as the professional member card:
 *  crowned header + account chip, an inset facts panel with leading
 *  glyph rows (account / start / expiry), the days-left gold strip, the
 *  feature checklist and the Close pill — the subscription-card layout
 *  of the professional stores.
 */
@Composable
fun PremiumUpsellDialog(
    feature: PremiumFeature?,
    onActivate: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = rememberVuSdp()
    val featureName = feature?.let { stringResource(it.labelRes()) }

    // A real floating window (usePlatformDefaultWidth = false → full
    // screen) — works over ANY screen root (Box, Column, LazyColumn),
    // D-pad friendly, Back dismisses.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(VuPalette.Black65)
                .pointerInput(Unit) {
                    // tap on the scrim = dismiss (D-pad Back still works)
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
        // v2.1.3 — never taller than the viewport, scrollable within:
        // the tail (checklist + CTA) can no longer be clipped off-screen.
        val maxCardH = maxHeight * 0.92f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(s.d(10))
                .widthIn(max = s.d(330))
                .fillMaxWidth()
                .heightIn(max = maxCardH)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(s.d(16)))
                .background(Color(0xF21C1C26))
                .vuGoldenBorder(cornerRadius = s.d(16), focused = false)
                .padding(horizontal = s.d(18), vertical = s.d(16))
        ) {
            VuCrownIcon(
                onGold = false,
                modifier = Modifier.size(s.d(26))
            )
            Spacer(Modifier.height(s.d(8)))
            Text(
                stringResource(
                    if (feature == null) R.string.premium_gate_accounts_title
                    else R.string.premium_gate_title
                ),
                color = VuGold.Text,
                fontSize = s.t(13),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(s.d(6)))
            Text(
                stringResource(
                    if (feature == null) R.string.premium_gate_accounts_body
                    else R.string.premium_gate_trial_body,
                    if (feature == null) "${PremiumPolicy.FREE_ACCOUNT_LIMIT}"
                    else (featureName ?: "")
                ),
                color = VuPalette.White.copy(alpha = 0.85f),
                fontSize = s.t(9),
                lineHeight = s.t(13),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(s.d(12)))
            PremiumFeatureList()
            Spacer(Modifier.height(s.d(14)))
            // the gold ACTIVATE CTA — the shared crowned pill
            Box(
                modifier = Modifier
                    .width(s.d(230))
                    .height(s.d(32))
            ) {
                VuPremiumBannerPill(
                    label = stringResource(R.string.premium_gate_cta),
                    onClick = onActivate
                )
            }
            Spacer(Modifier.height(s.d(8)))
            Text(
                stringResource(R.string.premium_dismiss),
                color = VuPalette.TintGrey,
                fontSize = s.t(9),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(s.d(8)))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = s.d(14), vertical = s.d(6))
            )
        }
        }
    }
}

/**
 * The premium member's own card — the crown button / the grid pill opens
 * it once premium is ACTIVE (user: "تعرض معلومات حسابه و الميزات
 * المفعلة"). Dates come straight from the premium Xtream account: start =
 * when it was added, end = its exp_date, remaining = the computed days.
 *
 * v2.1.3 — THE PROFESSIONAL MEMBER CARD (user: "تنيم البطاقة و تصميمها
 * ليس احترافي وفيها مشكل المعلومات في اسفلها مقصوص"):
 *
 *   ┌────────────────────────────────────────┐
 *   │              ♛  (crown)                │
 *   │         Your Premium Account           │  gold title
 *   │        ┌──────────────────────┐        │
 *   │        │  mounir_premium      │        │  account chip (dark glass)
 *   │        └──────────────────────┘        │
 *   │  ┌──────────────────────────────────┐  │
 *   │  │ 👤 Account     mounir_premium    │  │  inset facts panel,
 *   │  │ 📅 Start date  12 October 2026   │  │  leading-glyph rows with
 *   │  │ ⌛ Expiry date 12 November 2026  │  │  hairline separators
 *   │  └──────────────────────────────────┘  │
 *   │  ┌──────────────────────────────────┐  │
 *   │  │      ⏳  29 days left            │  │  gold strip — the live
 *   │  └──────────────────────────────────┘  │  headline of the card
 *   │  Enabled features                      │
 *   │  ✓ … (the five-feature checklist)      │
 *   │            [ Close pill ]              │
 *   └────────────────────────────────────────┘
 *
 * The whole column scrolls inside the golden frame and never exceeds 92%
 * of the viewport — the bottom lines stay on-screen at any font scale.
 */
@Composable
fun PremiumInfoDialog(onDismiss: () -> Unit) {
    val s = rememberVuSdp()
    // v2.1.3 — the dates follow the app's language, not the device's.
    val months = stringArrayResource(R.array.oria_months).toList()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(VuPalette.Black65)
                .pointerInput(Unit) {
                    // tap on the scrim = dismiss (D-pad Back still works)
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
        // v2.1.3 — the clipping fix: the card is capped at 92% of the
        // dialog viewport and scrolls inside its golden frame.
        val maxCardH = maxHeight * 0.92f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(s.d(10))
                .widthIn(max = s.d(340))
                .fillMaxWidth()
                .heightIn(max = maxCardH)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(s.d(16)))
                .background(Color(0xF21C1C26))
                .vuGoldenBorder(cornerRadius = s.d(16), focused = false)
                .padding(horizontal = s.d(18), vertical = s.d(16))
        ) {
            // ── the crowned header ──
            VuCrownIcon(
                onGold = false,
                modifier = Modifier.size(s.d(26))
            )
            Spacer(Modifier.height(s.d(8)))
            Text(
                stringResource(R.string.premium_info_title),
                color = VuGold.Text,
                fontSize = s.t(13),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(s.d(12)))

            // ── the account chip — the subscription's identity, verbatim ──
            PremiumAccess.sourceAccountName?.let { name ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(s.d(14)))
                        .background(VuPalette.Black65)
                        .vuGoldenBorder(cornerRadius = s.d(14), strokeWidth = s.d(0.8f), focused = false)
                        .padding(horizontal = s.d(14), vertical = s.d(5))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = VuGold.Text,
                            modifier = Modifier.size(s.d(11))
                        )
                        Spacer(Modifier.width(s.d(6)))
                        Text(
                            name,
                            color = VuPalette.White,
                            fontSize = s.t(9),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(s.d(10)))
            }

            // ── the facts panel — leading-glyph rows, hairline-separated ──
            val startValue = PremiumAccess.memberSince
                ?.let { OriaDateFmt.format(it, months) }
                ?: "—"
            val endValue = PremiumAccess.expiryDate
                ?.let { OriaDateFmt.format(it * 1000L, months) }
                ?: stringResource(R.string.premium_info_no_expiry)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(s.d(12)))
                    .background(Color(0x33101418))
                    .padding(horizontal = s.d(12), vertical = s.d(4))
            ) {
                Column {
                    PremiumInfoRow(
                        icon = Icons.Outlined.Person,
                        label = stringResource(R.string.premium_info_account),
                        value = PremiumAccess.sourceAccountName ?: "—"
                    )
                    PremiumInfoRow(
                        icon = Icons.Outlined.Event,
                        label = stringResource(R.string.premium_info_start),
                        value = startValue
                    )
                    PremiumInfoRow(
                        icon = Icons.Outlined.EventBusy,
                        label = stringResource(R.string.premium_info_end),
                        value = endValue,
                        last = true
                    )
                }
            }

            // ── days left — the subscription's live headline, gold ──
            PremiumAccess.daysLeft?.let { d ->
                Spacer(Modifier.height(s.d(10)))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(s.d(10)))
                        .background(Color(0x66301F08))          // warm bronze glass
                        .vuGoldenBorder(cornerRadius = s.d(10), strokeWidth = s.d(0.8f), focused = false)
                        .padding(vertical = s.d(6)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = VuGold.Text,
                            modifier = Modifier.size(s.d(12))
                        )
                        Spacer(Modifier.width(s.d(7)))
                        Text(
                            stringResource(R.string.premium_info_days_left, d),
                            color = VuGold.Text,
                            fontSize = s.t(11),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── the enabled-feature checklist ──
            Spacer(Modifier.height(s.d(12)))
            Text(
                stringResource(R.string.premium_info_features),
                color = VuPalette.White.copy(alpha = 0.85f),
                fontSize = s.t(9),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(s.d(6)))
            PremiumFeatureList()
            Spacer(Modifier.height(s.d(14)))
            Box(
                modifier = Modifier
                    .width(s.d(180))
                    .height(s.d(30))
            ) {
                VuPremiumBannerPill(
                    label = stringResource(R.string.premium_done),
                    onClick = onDismiss
                )
            }
        }
        }
    }
}

/**
 * One facts row of the member card: a leading glyph in a small round
 * gold-glass chip, the muted label, the bold value — hairline-separated
 * from the next row ([last] suppresses the separator).
 */
@Composable
private fun PremiumInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    last: Boolean = false
) {
    val s = rememberVuSdp()
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = s.d(6))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(s.d(20))
                    .clip(RoundedCornerShape(s.d(10)))
                    .background(Color(0x66301F08))
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = VuGold.Text,
                    modifier = Modifier.size(s.d(11))
                )
            }
            Spacer(Modifier.width(s.d(8)))
            Text(
                label,
                color = VuPalette.TintGrey,
                fontSize = s.t(9),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                color = VuPalette.White,
                fontSize = s.t(9),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = s.d(28))
                    .height(s.d(0.5f))
                    .background(VuPalette.White.copy(alpha = 0.08f))
            )
        }
    }
}

/** The five subscription features — same checklist in both dialogs. */
@Composable
private fun PremiumFeatureList() {
    val s = rememberVuSdp()
    val items = listOf(
        R.string.premium_feat_offline,
        R.string.premium_feat_download,
        R.string.premium_feat_record,
        R.string.premium_feat_accounts,
        R.string.premium_feat_noads
    )
    Column(verticalArrangement = Arrangement.spacedBy(s.d(4))) {
        items.forEach { res ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = VuGold.Text,
                    modifier = Modifier.size(s.d(12))
                )
                Spacer(Modifier.width(s.d(8)))
                Text(
                    stringResource(res),
                    color = VuPalette.White.copy(alpha = 0.9f),
                    fontSize = s.t(9),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** The gated feature's display name (upsell body "…this feature"). */
private fun PremiumFeature.labelRes(): Int = when (this) {
    PremiumFeature.OFFLINE_VIEW -> R.string.premium_feat_offline
    PremiumFeature.DOWNLOAD -> R.string.premium_feat_download
    PremiumFeature.RECORD -> R.string.premium_feat_record
}

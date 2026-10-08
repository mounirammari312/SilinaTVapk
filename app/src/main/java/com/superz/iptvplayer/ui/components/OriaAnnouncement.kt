package com.superz.iptvplayer.ui.components

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.resolveRemoteUrl
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuGoldFace

/**
 * v2.0.2 — THE PANEL'S IN-APP ANNOUNCEMENT, redesigned as a professional
 * native ad card (the user's directive: "مثل اعلانات جوجل الاحترافية —
 * صورة قابلة للنقر مع زر رابط و إغلاق").
 *
 * v2.0.3 — THE FIT FIX (user report: "زر زيارة دائماً متخفي بسبب الحجم").
 * Root cause: the scrim Box only had fillMaxWidth (no height), so the tall
 * card sat pinned to the top of its parent instead of centering, and with a
 * 16:9 image + header + body + CTA the total height exceeded the screen —
 * the gold CTA pill landed BELOW the visible area, especially in landscape.
 * The card is now sized by [AnnouncementFit] — a pure, unit-tested budget
 * that caps the media and body heights as a fraction of the ACTUAL screen
 * height so that header + media + body + CTA ALWAYS fit with the CTA
 * on-screen, on every device from landscape phones to 4K TVs:
 *
 *  ┌────────────────────────────────────────┐
 *  │ [إعلان]  العنوان ................  ✕   │  ← badge + advertiser + close
 *  │ ┌────────────────────────────────────┐ │
 *  │ │   MEDIA (≤32% of screen height,   │ │  ← tappable, opens linkUrl
 *  │ │   16:9 when there is room)        │ │
 *  │ └────────────────────────────────────┘ │
 *  │ body text (internal scroll, capped)    │
 *  │ ┌────────────────────────────────────┐ │
 *  │ │        CTA pill (زيارة) — FIXED   │ │  ← ALWAYS fully visible
 *  │ └────────────────────────────────────┘ │
 *  └────────────────────────────────────────┘
 *
 *  • TEXT-ONLY mode (no image): the same card minus the media block —
 *    an elegant typographic card, NOT the old centered logo dialog.
 *  • NO LINK: the CTA pill degrades to the plain OK/dismiss button.
 *  • v2.0.4 — SHOWS ONCE PER APP ENTRY (process start), and NOTHING can
 *    auto-hide it: only the ✕ / CTA dismisses, for the whole session.
 *    The v2.0.2–v2.0.3 "once per content signature" design keyed the
 *    visible flag on the signature with a persisted seen-marker — so a
 *    cached config whose signature differed from the live one (the
 *    hand-built cache JSON dropped the v2.0.2+ fields!) ARMED the card on
 *    every cold start and the fresh fetch instantly re-keyed it away:
 *    the card appeared, blinked, and vanished before anyone could read
 *    it (the user's "كل مرة ادخل يضهر يرمش ويختفي"). The new policy is
 *    [AnnouncementPolicy] — pure, unit-tested, with NO input combination
 *    that hides a showing card.
 *  • A mid-session config refresh only SWAPS the card's content live
 *    (title/body/image are Compose state) — never closes it.
 *  • D-pad friendly: the CTA takes the initial focus; the close ✕ is a
 *    real focusable target (phones + TV boxes).
 */
@Composable
fun OriaAnnouncementOverlay() {
    // v2.1.0 — PREMIUM = AD-FREE (user: "اخفاء الاعلانات اي حساب premium
    // لا يضهر الاعلان في حسابه"): an active subscription never arms AND
    // never renders the card — not on entry, not on a mid-session refresh,
    // not even one already showing when the user logs into premium.
    if (com.superz.iptvplayer.ui.theme.PremiumAccess.active) return

    val context = LocalContext.current
    val enabled = OriaBranding.announcementEnabled
    val signature = OriaBranding.announcementSignature
    val title = OriaBranding.announcementTitle
    val body = OriaBranding.announcementBody
    val imageUrl = OriaBranding.announcementImageUrl
    val linkUrl = OriaBranding.announcementLinkUrl
    val buttonText = OriaBranding.announcementButtonText

    // v2.0.4 — session-scoped arming: arms ONCE per app entry; a config
    // refresh can re-arm ONLY a content change that arrives while the
    // card is closed. A showing card is NEVER re-evaluated away. A blank
    // title never arms (the card would render nothing and swallow the
    // visible flag forever — the render gate and the arm gate agree).
    var visible by remember { mutableStateOf(false) }
    var armedSignature by remember { mutableStateOf("") }
    LaunchedEffect(enabled, signature, title) {
        if (title.isBlank()) return@LaunchedEffect
        if (AnnouncementPolicy.arms(
                enabled = enabled,
                signature = signature,
                armedSignature = armedSignature,
                isShowing = visible
            )
        ) {
            armedSignature = signature
            visible = true
        }
    }
    if (!visible || title.isBlank()) return

    val s = rememberVuSdp()
    val ctaFocus = remember { FocusRequester() }
    // Resolve the media URL once, up front — the fit budget needs to know
    // whether this is an image card or a text-only card BEFORE layout.
    val resolvedImage = remember(imageUrl) { resolveRemoteUrl(imageUrl) }

    /** Opens the panel-authored link; a dead/missing browser never crashes the app. */
    fun openLink() {
        val resolved = linkUrl.takeIf { it.isNotBlank() } ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(resolved))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // v2.0.3 — fillMaxSize: a true modal scrim that COVERS the screen, with
    // the card centered in it. The old fillMaxWidth-only Box wrapped its
    // content height and sat at the parent's top edge — the card then grew
    // downward past the screen bottom and the CTA vanished with it.
    // BoxWithConstraints sits on the FULL screen size (before the card's own
    // margins) so the fit budget works with true screen dimensions.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VuPalette.Black65),
        contentAlignment = Alignment.Center
    ) {
        // The height budget — pure math, unit-tested (AnnouncementFitTest).
        val fit = AnnouncementFit.fit(
            screenW = maxWidth.value,
            screenH = maxHeight.value,
            hasImage = resolvedImage != null,
            hasBody = body.isNotBlank()
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1C26))
                .vuAnnouncementBorder()
        ) {
                // ── header row: Ad badge + advertiser name + close ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp)
                ) {
                    // Google-style "Ad" chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x26FFFFFF))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(R.string.announcement_badge),
                            color = VuPalette.White.copy(alpha = 0.75f),
                            fontSize = s.t(7),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        color = VuGold.Text,
                        fontSize = s.t(11),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // the close ✕ — a real circular button, always visible
                    var closeFocused by remember { mutableStateOf(false) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (closeFocused) Color(0x33FFFFFF) else Color.Transparent)
                            .onFocusChanged { closeFocused = it.isFocused }
                            .focusable()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                visible = false
                            }
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = VuPalette.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // ── media block — the tappable ad image (height BUDGETED,
                //    never taller than 32% of the screen) ──
                if (resolvedImage != null) {
                    Spacer(Modifier.height(8.dp))
                    var mediaFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(fit.mediaH.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF14141C))
                            .vuGoldenBorder(
                                cornerRadius = 10.dp,
                                strokeWidth = if (mediaFocused) 2.dp else 1.dp,
                                focused = mediaFocused
                            )
                            .onFocusChanged { mediaFocused = it.isFocused }
                            .then(
                                if (linkUrl.isNotBlank()) Modifier
                                    .focusable()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { openLink() }
                                else Modifier
                            )
                    ) {
                        val painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(resolvedImage)
                                .crossfade(220)
                                .build(),
                            contentScale = ContentScale.Crop
                        )
                        Image(
                            painter = painter,
                            contentDescription = stringResource(R.string.announcement_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // loading/error fallback — the brand mark on a quiet surface,
                        // so the card never shows a hole while the asset loads.
                        when (painter.state) {
                            is coil.compose.AsyncImagePainter.State.Loading,
                            is coil.compose.AsyncImagePainter.State.Error ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF14141C))
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.oria_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(46.dp)
                                    )
                                }
                            else -> Unit
                        }
                    }
                }

                // ── body text (capped, scrolls INTERNALLY only) ──
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .fillMaxWidth()
                            .heightIn(max = fit.bodyMaxH.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x14FFFFFF))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            body,
                            color = VuPalette.White,
                            fontSize = s.t(9),
                            lineHeight = s.t(13),
                            textAlign = TextAlign.Start
                        )
                    }
                }

                // ── CTA pill — the gold link button. FIXED height, inside the
                //    fit budget, therefore ALWAYS fully visible on-screen. ──
                Spacer(Modifier.height(14.dp))
                val ctaLabel = buttonText.ifBlank {
                    if (linkUrl.isNotBlank()) stringResource(R.string.announcement_visit)
                    else stringResource(R.string.announcement_ok)
                }
                var ctaFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                        .height(42.dp)
                        .vuGoldenBorder(cornerRadius = 21.dp, focused = ctaFocused)
                        .clip(RoundedCornerShape(21.dp))
                        .vuGoldFace()
                        .focusRequester(ctaFocus)
                        .onFocusChanged { ctaFocused = it.isFocused }
                        .focusable()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            openLink()
                            visible = false
                        }
                ) {
                    Text(
                        ctaLabel,
                        color = VuGold.OnGold,
                        fontSize = s.t(11),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    // The announcement is dismissible ONLY through the ✕ / CTA pill —
    // for the whole session (v2.0.4: no signature bookkeeping at all);
    // the back key waits politely like the reference's dialogs.
    BackHandler(enabled = true) { /* swallow — use the buttons */ }
}

/**
 * v2.0.4 — THE SHOW POLICY, PURE so it is unit-testable (no Compose types).
 *
 * Contract (asserted by [AnnouncementPolicyTest]):
 *  • arms when enabled + a real signature arrives while the card is CLOSED
 *    and this entry has not armed that signature yet;
 *  • NEVER arms while a card is showing — a config refresh cannot close,
 *    re-key or re-open an open card (the v2.0.3 flicker bug);
 *  • there is NO state combination that hides a showing card — hiding is
 *    exclusively the user's ✕ / CTA action, scoped to the session.
 */
object AnnouncementPolicy {
    fun arms(
        enabled: Boolean,
        signature: String,
        armedSignature: String,
        isShowing: Boolean
    ): Boolean =
        !isShowing && enabled && signature.isNotBlank() && signature != armedSignature
}

/**
 * v2.0.3 — the ad card's height budget, PURE so it is unit-testable
 * (no Compose/Android types). All values are dp-as-Float.
 *
 * Contract (asserted by AnnouncementFitTest):
 *  • header + media + body-cap + CTA + spacings ≤ screenHeight − 16
 *    on every input ≥ 200dp tall — the CTA can never be pushed off-screen.
 *  • media ≤ 32% of screen height (landscape phones) and ≤ its 16:9
 *    aspect width; body cap ≤ 18% / 200dp.
 */
object AnnouncementFit {
    const val CARD_MAX_W = 420f
    const val CTA_H = 42f
    private const val HEADER_H = 46f
    private const val SPACINGS = 44f      // 8+10+14 spacings + 12 bottom padding
    private const val MARGIN = 16f        // scrim margin around the card

    data class Fit(val mediaH: Float, val bodyMaxH: Float)

    fun fit(screenW: Float, screenH: Float, hasImage: Boolean, hasBody: Boolean): Fit {
        val cardW = (screenW - MARGIN).coerceAtMost(CARD_MAX_W).coerceAtLeast(200f)
        val cardMaxH = (screenH - MARGIN).coerceAtLeast(140f)
        val fixed = HEADER_H + CTA_H + SPACINGS

        // What the blocks WANT at most.
        val mediaWant = if (hasImage) {
            val byAspect = (cardW - 24f) * 9f / 16f
            minOf(byAspect, screenH * 0.32f)
        } else 0f
        val bodyWant = if (hasBody) minOf(200f, screenH * 0.18f) else 0f

        // What the card can actually afford, under a STRICT priority: the
        // fixed chrome (header + CTA + spacings) is inviolable — that is
        // the user's bug — then a media zone of at most 60% of what's left,
        // then the body.
        val budget = (cardMaxH - fixed).coerceAtLeast(0f)
        var mediaH = minOf(mediaWant, budget * 0.6f)
        var bodyMaxH = minOf(bodyWant, budget - mediaH)

        // Final guard (screens below ~172dp tall; kept for absolute safety):
        // sacrifice the BODY first, then the media — NEVER the CTA pill.
        val over = fixed + mediaH + bodyMaxH - cardMaxH
        if (over > 0f) {
            val cutBody = minOf(bodyMaxH, over)
            bodyMaxH -= cutBody
            mediaH = (mediaH - (over - cutBody)).coerceAtLeast(0f)
        }
        return Fit(mediaH = mediaH, bodyMaxH = bodyMaxH)
    }
}

/** A quiet metallic hairline around the ad card (subtle, never loud). */
private fun Modifier.vuAnnouncementBorder(): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = androidx.compose.ui.graphics.Color(0x2EF0C24E),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
)

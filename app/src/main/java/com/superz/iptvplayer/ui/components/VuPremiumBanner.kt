package com.superz.iptvplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.superz.iptvplayer.ui.theme.VuCrownIcon
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * v2.0.4 — THE SHARED PREMIUM ENTRY BANNER.
 *
 * Born in AddPortalScreen (v2.0.2) as a private pill, promoted here because
 * the user asked for premium entries on MORE pages "مثل التطبيقات
 * الاحترافية" — one component, one look, everywhere. It appears on the
 * login/method page, the accounts page and the settings page (its own
 * caller-sized Box slot), carrying the PROFESSIONAL CROWN that survived
 * every review since v2.0.4.
 *
 * v2.2.1 — THE PLAN-CARD CONTRACT (user directive: “واصل تطبيقه على صفحة
 * تسجيل الدخول و صفحة بطاقات الحسابات”): the banner sheds its bespoke
 * halo/shimmer/solid-gold layers and wears the plan cards' exact skin
 * through the shared [vuPlanCardStyle] — the animated golden ring
 * (0.8sdp rest → 1.6sdp lit), the DARK GLASS resting face, the
 * warm-bronze GLASS lit face — with the crown's full metallic gold and a
 * GOLD label on BOTH faces, so the upgrade entry reads as a little plan
 * card exactly like every button of the app's new family.
 *
 * Sizing contract: the caller wraps it in a Box with explicit width/height
 * (AddPortal: 280×34sdp; Accounts' v2.2.1 top-bar slot: ≤280×30sdp;
 * Settings: 280×30sdp) — the pill fills its container.
 */
@Composable
fun VuPremiumBannerPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        // face — the plan cards' exact skin: ring + clip + glass faces
        Box(
            modifier = Modifier
                .fillMaxSize()
                .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v2.0.5 — drawn through VuCrownIcon: Icon()'s default
                    // tint was flattening the crown to a black silhouette
                    // (the user's report). The crown keeps its own metal —
                    // full gold on BOTH faces now (the lit face is bronze
                    // GLASS, not solid gold).
                    VuCrownIcon(
                        onGold = false,
                        modifier = Modifier
                            .padding(end = s.d(12))
                            .size(s.d(16))
                    )
                    Text(
                        label,
                        color = VuGold.Text,
                        fontSize = s.t(10),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

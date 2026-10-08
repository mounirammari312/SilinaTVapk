package com.superz.iptvplayer.ui.loading

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.ErrorRed
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuGoldFace
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuLoadingBackground

/**
 * Loading screen — v2.1.2 THE HORIZONTAL SYNC LINE (user directive:
 * “اجعل المحتوى افقى وبدل الداائرة اجعله شريط مستقيم… بهذه الطريقة يتم
 * فسح مجال لزر الذي يكون متخفي حين لا ينجح التحميل لكي يضهر لانه حاليا
 * لا يضهر يكون متخفي”): the tall vertical column — the pulsing logo over
 * the 120dp progress ring — is retired. The page centers ONE compact
 * horizontal progress line: the live percentage counter beside a straight
 * metallic-gold bar, under the brand mark, with the stage dots, the status
 * line and the account name stacked beneath it:
 *
 *              [ brand mark 56dp, pulsing ]
 *                    App Name
 *     62%  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *           ● ● ● ● ○ ○
 *           Loading channels… 1,234
 *           Account: darplayer
 *           ⚠ engine error
 *           [↻ Retry]  [← Accounts]   ← ALWAYS inside the viewport
 *
 * The paper budget is [LoadingLayout] (pure math, unit-tested): the old
 * vertical stack needed ~416dp worst-case and pushed the error actions
 * off a ~411dp landscape viewport — the retry button the user reported
 * as hidden. The horizontal line needs ~254dp worst-case. The bar crawls
 * smoothly between the pipeline's coarse progress steps (tween 300ms)
 * wearing the app's gold ramp (aged bronze → gold → champagne head);
 * the v1.19.5 gold RETRY hero + neutral glass secondary stay exactly as
 * designed — ErrorRed stays, it is semantic.
 */
@Composable
fun LoadingScreen(
    onDone: () -> Unit,
    onBackToAccounts: () -> Unit,
    viewModel: LoadingViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // Auto-advance to the hub once the sync pipeline completes.
    LaunchedEffect(ui.done) {
        if (ui.done) {
            kotlinx.coroutines.delay(650) // let the 100% state breathe
            onDone()
        }
    }

    val pulseScale by animateFloatAsState(
        targetValue = if (ui.progress in 1..99) 1.05f else 1f,
        animationSpec = tween(800),
        label = "brandPulse"
    )

    // v2.1.2 — the bar crawls smoothly between the pipeline's coarse
    // progress steps (35 → 90 → 93 → 97 → 100); it never jumps.
    val barFraction by animateFloatAsState(
        targetValue = (ui.progress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "syncBar"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // v2.0.3 — the loading screen background is panel-controlled
            // (branding.bgLoadingUrl); blank URL paints the stock Deep Space
            // gradient + cinematic glow exactly as before.
            .vuLoadingBackground(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 48.dp)
                // v2.1.2 — capped so the line stays elegant on tablets/TV
                // instead of stretching edge to edge.
                .widthIn(max = 560.dp)
        ) {
            // ── Pulsing brand mark (v1.8.0 Oria fox artwork; v2.0.0
            //    remote-swappable via the panel — OriaLogoImage falls back
            //    to the built-in artwork while loading/on error).
            //    v2.1.2: 84 → 56dp — the horizontal line buys the height
            //    back (see LoadingLayout.LOGO_H). ──
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                com.superz.iptvplayer.ui.theme.OriaLogoImage(
                    modifier = Modifier.fillMaxSize()
                )
            }

            // v2.0.3 — the white-label APP NAME under the brand mark when
            // the panel sets one (branding.appName): a custom-branded
            // install names itself on its very first screen.
            if (OriaBranding.appName.isNotBlank()) {
                Spacer(Modifier.height(LoadingLayout.GAP.dp))
                Text(
                    OriaBranding.appName,
                    color = VuGold.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(LoadingLayout.GAP.dp))

            // ── v2.1.2 — THE HORIZONTAL SYNC LINE: counter + straight bar ──
            // The live percentage rides at a FIXED width (the bar's edge
            // never wobbles as the digits change), the gold bar fills the
            // rest of the line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "${ui.progress}%",
                    color = VuGold.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp)
                )
                VuSyncBar(
                    fraction = barFraction,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(LoadingLayout.GAP.dp))

            // ── Stage dots: connect → categories → channels → movies →
            //    series → finish (one compact row under the line) ──
            val stages = LoadingViewModel.Stage.entries
            val activeIndex = stages.indexOf(ui.stage)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                stages.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (idx <= activeIndex) VuGold.Gold
                                else GlassSurface
                            )
                    )
                }
            }

            Spacer(Modifier.height(LoadingLayout.GAP.dp))

            // ── Status line ──
            val status = when {
                ui.error != null -> stringResource(R.string.sync_failed)
                ui.done -> stringResource(R.string.sync_done, ui.channelsLoaded)
                ui.stage == LoadingViewModel.Stage.CONNECT -> stringResource(R.string.sync_connect)
                ui.stage == LoadingViewModel.Stage.CATEGORIES -> stringResource(R.string.sync_categories, ui.channelsLoaded)
                ui.stage == LoadingViewModel.Stage.CHANNELS -> stringResource(R.string.sync_channels, ui.channelsLoaded)
                ui.stage == LoadingViewModel.Stage.MOVIES -> stringResource(R.string.sync_movies)
                ui.stage == LoadingViewModel.Stage.SERIES -> stringResource(R.string.sync_series)
                else -> stringResource(R.string.sync_finish)
            }
            Text(
                status,
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            if (ui.playlistName.isNotEmpty()) {
                Spacer(Modifier.height(LoadingLayout.GAP.dp))
                Text(
                    stringResource(R.string.sync_account, ui.playlistName),
                    color = VuGold.Text.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            // ── Error actions — v2.1.2: ALWAYS inside the viewport. The old
            //    ring stack spent ~416dp and clipped this row off a ~411dp
            //    landscape screen (the "hidden retry button" report); the
            //    horizontal line costs ~254dp worst-case (LoadingLayout). ──
            if (ui.error != null) {
                Spacer(Modifier.height(LoadingLayout.GAP.dp))
                Text(
                    ui.error ?: "",
                    color = ErrorRed.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(LoadingLayout.GAP.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassActionButton(
                        label = stringResource(R.string.retry),
                        highlight = true,
                        onClick = { viewModel.retry() }
                    )
                    GlassActionButton(
                        label = stringResource(R.string.back_to_accounts),
                        highlight = false,
                        onClick = onBackToAccounts
                    )
                }
            }
        }
    }
}

/**
 * v2.1.2 — the straight metallic-gold sync bar: an 8dp glass track
 * carrying the gold ramp (aged bronze → gold → champagne head) that
 * fills to [fraction] of the width. Rounded ends; the bright champagne
 * head IS the ramp's leading edge — no extra chrome to maintain.
 */
@Composable
private fun VuSyncBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(GlassSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(VuGold.Rich, VuGold.Gold, VuGold.Pale)
                    )
                )
        )
    }
}

@Composable
private fun GlassActionButton(
    label: String,
    highlight: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // v1.19.5 — highlight = the GOLDEN HERO (the code-button contract):
    // TRUE solid-gold face + the animated golden edge + dark-bronze label;
    // the secondary action keeps the neutral glass idiom.
    Box(
        modifier = Modifier
            .then(
                if (highlight) Modifier.vuGoldenBorder(
                    cornerRadius = 20.dp,
                    strokeWidth = 1.4.dp,
                    focused = focused
                ) else Modifier
            )
            .clip(RoundedCornerShape(50))
            .then(
                if (highlight) Modifier.vuGoldFace()
                else Modifier.background(GlassSurface.copy(alpha = 0.8f))
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (highlight) VuGold.OnGold else TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/* ═══════════════════════════════════════════════════════════════════
 *  v2.1.2 — PURE LAYOUT MATH (unit-tested: LoadingLayoutTest)
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * The loading page's paper height budget, as pure Float math (dp) so it
 * is fully unit-testable with no Android types:
 *
 *  • the page NEVER scrolls — the worst-case stack (brand mark + app
 *    name + the horizontal progress line + dots + status + account +
 *    error + BOTH action buttons) must fit the shortest viewport the app
 *    ships on (a ~411dp landscape phone);
 *  • the v2.1.1-and-earlier vertical stack (the 120dp ring over the 84dp
 *    logo with the big spacers) needed ~416dp worst-case — on a ~411dp
 *    viewport the centered column overtopped the screen and the error
 *    actions rendered OFF-SCREEN: the retry button the user reported as
 *    “متخفي”. The horizontal line needs ~254dp worst-case.
 */
object LoadingLayout {
    /** The pulsing brand mark (was 84 — the horizontal line buys it back). */
    const val LOGO_H = 56f

    /** The white-label app-name line (panel-set). */
    const val NAME_H = 18f

    /** The % counter + the straight gold bar — the page's tallest line. */
    const val PROGRESS_ROW_H = 28f

    /** The six stage dots. */
    const val DOTS_H = 8f

    /** The status line. */
    const val STATUS_H = 16f

    /** The account line. */
    const val ACCOUNT_H = 12f

    /** The engine's error line. */
    const val ERROR_H = 12f

    /** The retry / back button row. */
    const val ACTIONS_H = 34f

    /** The uniform air between blocks — the compact horizontal rhythm. */
    const val GAP = 10f

    /** The worst-case vertical stack — [appName] shown, [error] actions shown. */
    fun worstCaseH(appName: Boolean, error: Boolean): Float {
        val blocks = mutableListOf(LOGO_H, PROGRESS_ROW_H, DOTS_H, STATUS_H, ACCOUNT_H)
        if (appName) blocks += NAME_H
        if (error) {
            blocks += ERROR_H
            blocks += ACTIONS_H
        }
        return blocks.sum() + GAP * (blocks.size - 1)
    }

    /** THE invariant: the worst case fits the viewport — no scroll, and the
     *  retry actions can never be clipped off-screen again. */
    fun fits(viewportH: Float, appName: Boolean, error: Boolean): Boolean =
        viewportH >= worstCaseH(appName, error)
}

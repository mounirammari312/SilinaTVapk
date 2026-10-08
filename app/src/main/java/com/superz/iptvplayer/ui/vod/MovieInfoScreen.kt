package com.superz.iptvplayer.ui.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.components.PremiumUpsellDialog
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.GlassSurfaceAlt
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuGlobalDeepSpaceBackground
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * Movie info page (v1.4.0, redesigned v1.4.3) — reference layout, Deep
 * Space palette: top bar (back + brand), hero row (portrait poster +
 * rating + info fields + play/favorite actions), then a SCROLLABLE body
 * with the full plot and the cast row with headshots (the user asked
 * for the page to scroll instead of being squeezed into fixed sizes).
 *
 * While details load, a soft blinking indicator (agreed EPG-style UX).
 * v1.4.6 — the diagnostics chip + long-press trace entry were REMOVED
 * at the user's request; the VodTrace ring buffer keeps recording
 * silently in the background (no UI, no user-facing surface).
 *
 * v1.19.5 (user request: “واصل للمشغل الكبير و صفحات معلومات الأفلام و
 * المسلسلات… نفس الهوية الذهبية”) — the page joins the app's golden
 * identity: the PLAY button becomes the golden hero (TRUE solid-gold
 * face + the animated golden edge + dark-bronze content), the brand
 * chip, poster fallback, favorite star and loading dot go gold. The
 * login forms keep their own design (user directive).
 */
@Composable
fun MovieInfoScreen(
    playlistId: Long,
    streamId: Long,
    onPlay: (channelKey: String) -> Unit,
    onBack: () -> Unit,
    onOpenPremium: () -> Unit = {},
    viewModel: MovieInfoViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuGlobalDeepSpaceBackground(withCinematicGlow = false)
    ) {
        // ── Top bar: back + brand ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = TextSecondary
                )
            }
            // v2.2.1 — THE BRAND CHIP WEARS THE APP'S OWN LOGO (user:
            // “في صفحات المعلومات المسلسلات و الافلام تضهر في الاعلى علامة
            // TV بدل شعار التطبيق الصغير يجب ان يكون شعار صغير بدل TV”):
            // the literal "TV" glyph is retired; the same 30dp glass chip
            // now carries the small brand mark — the panel's remote logo
            // when white-labeled, the built-in oria_logo otherwise.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x66301F08)),   // warm bronze glass
                contentAlignment = Alignment.Center
            ) {
                com.superz.iptvplayer.ui.theme.OriaLogoImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                ui.info?.name ?: ui.movie?.name ?: stringResource(R.string.hub_movies),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Soft blink while details load (agreed UX)
            if (ui.loading) BlinkDot()
        }

        // ── Scrollable body (v1.4.3 — user request: the page scrolls) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp)
        ) {
            // ── Hero row: poster + fields + actions ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                // Poster (portrait 2:3, reference position: left)
                val poster = ui.info?.poster ?: ui.movie?.poster
                Box(
                    modifier = Modifier
                        .height(200.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, GlassSurfaceAlt, RoundedCornerShape(14.dp))
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            VuGold.Rich.copy(alpha = 0.32f),
                                            VuGold.Gold.copy(alpha = 0.12f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (ui.info?.name ?: ui.movie?.name ?: "?")
                                    .trim().firstOrNull()?.uppercase() ?: "?",
                                color = TextPrimary,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.width(22.dp))

                // Fields column (rating + director + release + duration + genre;
                // cast moved to its own photo row below — reference layout)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Spacer(Modifier.height(2.dp))
                    RatingField(stringResource(R.string.field_rating), ui.info?.rating)
                    InfoField(stringResource(R.string.field_director), ui.info?.director)
                    InfoField(stringResource(R.string.field_release), ui.info?.releaseDate)
                    InfoField(stringResource(R.string.field_duration), ui.info?.duration)
                    InfoField(stringResource(R.string.field_genre), ui.info?.genre, maxLines = 2)

                    Spacer(Modifier.height(14.dp))

                    // ── Actions: big play pill + favorite circle ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Play button — v2.2.0: THE PLAN-CARD HERO (user
                        // directive: “زر تشغيل الفيلم… نفس تصميم بطاقات الخطط
                        // حرفياً”): the animated golden ring (0.8dp rest →
                        // 1.6dp focus) + the DARK GLASS resting face + the
                        // warm-bronze GLASS focused face + GOLD content —
                        // exactly the subscribe page's plan cards; probing
                        // state v1.4.3 (spinner + softer label) survives.
                        var playFocused by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .vuPlanCardStyle(cornerRadius = 23.dp, focused = playFocused)
                                .onFocusChanged { playFocused = it.isFocused }
                                .clickable(enabled = !ui.probing) {
                                    viewModel.preparePlayback(onPlay)
                                }
                                .padding(horizontal = 26.dp, vertical = 12.dp)
                                .alpha(if (ui.probing) 0.7f else 1f)
                        ) {
                            if (ui.probing) {
                                CircularProgressIndicator(
                                    color = VuGold.Text,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = VuGold.Text,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (ui.probing) R.string.preparing_playback
                                    else R.string.play_movie
                                ),
                                color = VuGold.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // ── v1.19.9 — THE DOWNLOAD BUTTON (moved from the
                        //    player's top bar to HERE, next to Play — user
                        //    directive). v2.2.0 — it joins the plan-card
                        //    family so the action pair reads as ONE design:
                        //    dark glass at rest, the lit warm-bronze face
                        //    while RUNNING (the engine pulls the file) with
                        //    gold content throughout. Press again = stop
                        //    (a valid partial MP4 is finalized into the
                        //    Saved Videos library). ──
                        var dlFocused by remember { mutableStateOf(false) }
                        val running = ui.downloadPhase == MovieInfoViewModel.DownloadPhase.RUNNING
                        val resolving = ui.downloadPhase == MovieInfoViewModel.DownloadPhase.RESOLVING
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .vuPlanCardStyle(cornerRadius = 23.dp, focused = dlFocused, selected = running)
                                .onFocusChanged { dlFocused = it.isFocused }
                                .clickable(enabled = !resolving) { viewModel.toggleDownload() }
                                .padding(horizontal = 22.dp, vertical = 12.dp)
                                .alpha(if (resolving) 0.7f else 1f)
                        ) {
                            when {
                                resolving || (running && ui.downloadFinalizing) -> CircularProgressIndicator(
                                    color = VuGold.Text,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                running -> Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = null,
                                    tint = VuGold.Text,
                                    modifier = Modifier.size(20.dp)
                                )
                                else -> Icon(
                                    Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = VuGold.Text,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    resolving -> stringResource(R.string.preparing_playback)
                                    running && ui.downloadFinalizing -> stringResource(R.string.download_finalizing)
                                    running -> {
                                        val pct = ui.downloadPercent
                                        val mb = ui.downloadBytesMb
                                        if (pct >= 0) {
                                            String.format(java.util.Locale.US, "\u2B07 %d%%  •  %.1f MB", pct, mb)
                                        } else {
                                            String.format(java.util.Locale.US, "\u2B07 %.1f MB", mb)
                                        }
                                    }
                                    else -> stringResource(R.string.download_button)
                                },
                                color = VuGold.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        // v2.2.1 — Favorite circle — THE PLAN-CARD CONTRACT
                        // (user directive: “واصل تطبيقه…” — the movie page's
                        // action pair already wears it; the heart joins):
                        // the 46dp circle wears the plan cards' skin (dark
                        // glass rest / warm-bronze glass lit + the animated
                        // ring) with the heart in GOLD — filled when
                        // favorited, softly dimmed when not.
                        var favFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .vuPlanCardStyle(cornerRadius = 23.dp, focused = favFocused)
                                .onFocusChanged { favFocused = it.isFocused }
                                .clickable { viewModel.toggleFavorite() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (ui.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorites),
                                tint = if (ui.isFavorite) VuGold.Text
                                else VuGold.Text.copy(alpha = 0.62f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ── Story section (full plot — no line clamp, page scrolls) ──
            val plot = ui.info?.plot
            SectionHeader(stringResource(R.string.plot_section))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassSurface.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    plot ?: stringResource(R.string.no_plot),
                    color = if (plot != null) TextSecondary else TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            // ── Cast section (reference: headshot row) ──
            if (ui.tmdbCast.isNotEmpty() || ui.castNames.isNotEmpty()) {
                SectionHeader(stringResource(R.string.cast_section))
                if (ui.tmdbCast.isNotEmpty()) {
                    // v1.4.5 — reference parity: real TMDB credits
                    // (photos + character names) via the panel's tmdb_id.
                    TmdbCastRow(
                        members = ui.tmdbCast,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                } else {
                    CastRow(
                        names = ui.castNames,
                        photos = ui.castPhotos,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // ── v1.19.9 — the download button's transient message pill
        //    (saved to library / busy / failed — the player's toast
        //    pattern, bottom-center, auto-clears after ~6s) ──
        AnimatedVisibility(
            visible = ui.downloadMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ui.downloadMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, VuGold.Gold.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        msg,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── v2.1.0 — THE PREMIUM UPSELL GATE: a free user's SECOND
        //    download attempt lands here (the first is the free trial). ──
        if (ui.premiumGate) {
            PremiumUpsellDialog(
                feature = com.superz.iptvplayer.ui.theme.PremiumFeature.DOWNLOAD,
                onActivate = {
                    viewModel.consumePremiumGate()
                    onOpenPremium()
                },
                onDismiss = { viewModel.consumePremiumGate() }
            )
        }
    }
}

/** One info field row: muted label + light value (reference styling). */
@Composable
internal fun InfoField(label: String, value: String?, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value ?: stringResource(R.string.no_info),
            color = if (value != null) TextSecondary else TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Soft blinking loading dot (agreed EPG-style indicator) — gold, v1.19.5. */
@Composable
internal fun BlinkDot() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(600),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(VuGold.Gold)
    )
}

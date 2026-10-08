package com.superz.iptvplayer.ui.vod

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
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
import com.superz.iptvplayer.data.xtream.VodEpisode
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.GlassSurfaceAlt
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuGlobalDeepSpaceBackground
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle

/**
 * Series info page (v1.4.0, redesigned v1.4.3) — reference design in a
 * SCROLLABLE body (user request: nothing squeezed into fixed sizes):
 * hero row (poster + rating + fields + episodes chip + favorite),
 * season pills (hidden for single-season shows — agreed), horizontal
 * episode strip (16:9 thumbnails + translucent title bar), full story
 * section, cast row with headshots.
 *
 * Tapping an episode opens the FULLSCREEN player with the season as
 * the zap list. v1.4.6 — the long-press trace entry was REMOVED at the
 * user's request (VodTrace keeps recording silently in the background).
 *
 * v1.19.5 — the page joins the golden identity (user request): brand
 * chip, poster/episode fallbacks, episodes chip, favorite star, season
 * pills (tab-pill contract: bronze glass + gold at rest → TRUE solid
 * gold + OnGold when active/focused) and the resume bar/chip all go
 * gold. Login forms stay as designed (user directive).
 */
@Composable
fun SeriesInfoScreen(
    playlistId: Long,
    seriesId: Long,
    onPlayEpisode: (channelKey: String) -> Unit,
    onBack: () -> Unit,
    onOpenPremium: () -> Unit = {},
    viewModel: SeriesInfoViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuGlobalDeepSpaceBackground(withCinematicGlow = false)
    ) {
        // ── Top bar: back + brand + title ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                ui.info?.name ?: ui.show?.name ?: stringResource(R.string.hub_series),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
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
            // ── Hero row: poster + fields + chip + favorite ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                val poster = ui.info?.poster ?: ui.show?.poster
                Box(
                    modifier = Modifier
                        .height(160.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, GlassSurfaceAlt, RoundedCornerShape(12.dp))
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
                                (ui.info?.name ?: ui.show?.name ?: "?")
                                    .trim().firstOrNull()?.uppercase() ?: "?",
                                color = TextPrimary,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.width(22.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    RatingField(stringResource(R.string.field_rating), ui.info?.rating)
                    InfoField(stringResource(R.string.field_director), ui.info?.director)
                    InfoField(stringResource(R.string.field_release), ui.info?.releaseDate)
                    InfoField(stringResource(R.string.field_genre), ui.info?.genre, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Episode count chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GlassSurface.copy(alpha = 0.6f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.episodes_count, ui.episodes.size),
                                color = VuGold.Text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // v2.2.1 — Favorite circle — THE PLAN-CARD CONTRACT
                        // (user directive: “واصل تطبيقه على صفحة معلومات
                        // المسلسلات”): the 38dp circle wears the plan cards'
                        // skin (dark glass rest / warm-bronze glass lit + the
                        // animated ring) with the heart in GOLD — filled when
                        // favorited, softly dimmed when not.
                        var favFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .vuPlanCardStyle(cornerRadius = 19.dp, focused = favFocused)
                                .onFocusChanged { favFocused = it.isFocused }
                                .clickable { viewModel.toggleFavorite() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (ui.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorites),
                                tint = if (ui.isFavorite) VuGold.Text
                                else VuGold.Text.copy(alpha = 0.62f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ── Season pills (hidden when a single season — agreed) ──
            val seasons = ui.seasons
            if (seasons.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    seasons.forEach { season ->
                        SeasonPill(
                            label = stringResource(R.string.season_number, season),
                            active = season == ui.selectedSeason,
                            onClick = { viewModel.selectSeason(season) }
                        )
                    }
                }
            }

            // ── Episode strip (reference: horizontal cards, thumbnail + title bar) ──
            if (ui.episodes.isEmpty() && !ui.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassSurface.copy(alpha = 0.35f))
                        .padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_episodes),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else if (ui.episodes.isNotEmpty()) {
                SectionHeader(stringResource(R.string.episodes_header))
                // v1.19.0 — CONTINUE WATCHING: scroll the strip to the
                // in-progress episode so the user lands on the exact card
                // to resume (the reference's binge continuity).
                val stripState = rememberLazyListState()
                val resumeIndex = ui.resumeEpisodeId?.let { rid ->
                    ui.episodes.indexOfFirst { it.id == rid }.takeIf { it >= 0 }
                }
                LaunchedEffect(ui.selectedSeason, ui.episodes.size, ui.resumeEpisodeId) {
                    resumeIndex?.let { stripState.scrollToItem(it.coerceAtMost(ui.episodes.lastIndex)) }
                }
                LazyRow(
                    state = stripState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(ui.episodes, key = { _, ep -> ep.id }) { _, episode ->
                        EpisodeCard(
                            episode = episode,
                            fallbackPoster = ui.show?.poster,
                            // v1.4.4 — season artwork beats the series poster
                            // as the per-episode fallback on panels without
                            // per-episode stills (admagnun.net).
                            seasonCover = ui.selectedSeasonCover,
                            preparing = ui.probingEpisodeId == episode.id,
                            resumeProgress = if (episode.id == ui.resumeEpisodeId &&
                                ui.resumeDurationMs > 0L
                            ) {
                                (ui.resumePositionMs.toFloat() / ui.resumeDurationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            } else null,
                            // v1.19.9 — the per-episode DOWNLOAD chip (the
                            // player's ⬇ button moved to the info pages): the
                            // card's own play affordance gains a download
                            // twin at its top-end corner.
                            downloadRunning = ui.downloadEpisodeId == episode.id,
                            downloadResolving = ui.downloadResolvingId == episode.id,
                            downloadBusyElsewhere = ui.downloadEpisodeId != null &&
                                ui.downloadEpisodeId != episode.id,
                            downloadFinalizing = ui.downloadFinalizing,
                            downloadPercent = ui.downloadPercent,
                            onDownload = { viewModel.toggleEpisodeDownload(episode.id) },
                            onClick = {
                                viewModel.prepareEpisodePlayback(episode.id, onPlayEpisode)
                            }
                        )
                    }
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
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    plot ?: stringResource(R.string.no_plot),
                    color = if (plot != null) TextSecondary else TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            // ── Cast section (reference: headshot row) ──
            if (ui.tmdbCast.isNotEmpty() || ui.castNames.isNotEmpty()) {
                SectionHeader(stringResource(R.string.cast_section))
                if (ui.tmdbCast.isNotEmpty()) {
                    // v1.4.5 — reference parity: TMDB tv credits when the
                    // panel's series info carries tmdb_id.
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

        // ── v1.19.9 — the download chip's transient message pill
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
        //    episode-download attempt lands here (the first is the free
        //    trial — the same DOWNLOAD trial the movie page shares). ──
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

/**
 * Season pill — v1.19.5: the tab-pill contract. v2.2.1 — THE PLAN-CARD
 * CONTRACT (user directive: “واصل تطبيقه على صفحة معلومات المسلسلات”):
 * the shared [vuPlanCardStyle] — animated golden ring (0.8dp rest → 1.6dp
 * lit), DARK GLASS resting face, warm-bronze GLASS lit face — with GOLD
 * text on BOTH faces; the ACTIVE season holds the lit face so the current
 * section stays obvious. The reference's indigo→cyan gradient and the old
 * solid-gold face are gone.
 */
@Composable
private fun SeasonPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .vuPlanCardStyle(cornerRadius = 16.dp, focused = focused, selected = active)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = VuGold.Text,
            fontSize = 12.sp,
            fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Episode card (reference): 16:9 thumbnail + play hint + translucent
 * black title bar with the episode title at the bottom. Fallback chain
 * (v1.4.4): episode still → SEASON cover → series poster → numbered
 * gradient tile. Shows a preparing spinner while the playback URL is
 * being verified (v1.4.4 gentle probe).
 *
 * v1.19.0 — CONTINUE WATCHING: [resumeProgress] non-null marks THIS
 * series' in-progress episode — a GOLD progress bar rides the title
 * bar + a gold "متابعة" chip floats top-start, so the user lands from
 * the Continue-Watching shelf straight onto the exact episode
 * (v1.19.5: cyan → gold, the app identity).
 *
 * v1.19.9 — THE DOWNLOAD CHIP (the player's ⬇ button moved to the info
 * pages): the card's top-end corner gets a download twin of its play
 * affordance — a small glass circle with the gold ⬇ at idle, a spinner
 * while the URL resolves, and a solid-gold percent pill while the engine
 * pulls the file. Muted while ANOTHER episode is downloading (the
 * engine's one-at-a-time rule; a tap explains it in the message pill).
 */
@Composable
private fun EpisodeCard(
    episode: VodEpisode,
    fallbackPoster: String?,
    seasonCover: String?,
    preparing: Boolean,
    resumeProgress: Float? = null,
    downloadRunning: Boolean = false,
    downloadResolving: Boolean = false,
    downloadBusyElsewhere: Boolean = false,
    downloadFinalizing: Boolean = false,
    downloadPercent: Int = -1,
    onDownload: () -> Unit = {},
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(enabled = !preparing) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            val thumb = episode.thumbnail ?: seasonCover ?: fallbackPoster
            if (!thumb.isNullOrBlank()) {
                AsyncImage(
                    model = thumb,
                    contentDescription = episode.title,
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
                                    VuGold.Rich.copy(alpha = 0.28f),
                                    VuGold.Gold.copy(alpha = 0.10f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${episode.episodeNumber}",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            // Center play hint — spinner while the URL is verified
            if (preparing) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(6.dp)
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play_episode),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(5.dp)
                )
            }
            // Translucent black title bar (reference)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    "S${episode.season}E${episode.episodeNumber} · ${episode.title}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── v1.19.9 — THE DOWNLOAD CHIP (top-end corner): the download
            //    twin of the card's play affordance. v2.2.1 — THE PLAN-CARD
            //    CONTRACT (user directive: “واصل تطبيقه على صفحة معلومات
            //    المسلسلات”): the chip wears the plan cards' skin — dark
            //    glass at rest, the lit warm-bronze face while the engine
            //    pulls the file, GOLD glyphs throughout; press it again to
            //    stop (a valid partial MP4 is finalized). ──
            when {
                downloadRunning -> {
                    var chipFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .vuPlanCardStyle(cornerRadius = 9.dp, focused = chipFocused, selected = true)
                            .onFocusChanged { chipFocused = it.isFocused }
                            .clickable { onDownload() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (downloadFinalizing) stringResource(R.string.download_finalizing)
                            else if (downloadPercent >= 0) "\u2B07 $downloadPercent%"
                            else "\u2B07 …",
                            color = VuGold.Text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                downloadResolving -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, VuGold.Gold.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = VuGold.Gold,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                else -> {
                    var chipFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .vuPlanCardStyle(cornerRadius = 13.dp, focused = chipFocused)
                            .onFocusChanged { chipFocused = it.isFocused }
                            .clickable { onDownload() }
                            .alpha(if (downloadBusyElsewhere) 0.45f else 1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.download_button),
                            tint = VuGold.Text,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // v1.19.0 — in-progress episode: GOLD progress bar + chip
            if (resumeProgress != null) {
                // progress bar riding the title bar's top edge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(resumeProgress)
                            .background(VuGold.Gold)
                    )
                }
                // "متابعة" chip — top-start, gold
                Text(
                    stringResource(R.string.resume_badge),
                    color = VuGold.OnGold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(VuGold.Gold)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

package com.agon.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.agon.app.ads.AdMobManager
import com.agon.app.data.FavoritesManager
import com.agon.app.data.ParentalPinManager
import com.agon.app.data.ProfileRepository
import com.agon.app.data.model.PlaylistType
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.agon.app.data.model.EpisodeItem
import com.agon.app.data.repository.MatchHarvesterRepository
import com.agon.app.data.repository.PlaylistRepository
import com.agon.app.proxy.GlobalPlaybackCoordinator
import kotlinx.coroutines.delay
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.AccentIndigoDark
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentCyanLight
import com.agon.app.ui.theme.SlateSurface
import com.agon.app.ui.theme.SlateSurfaceAlt
import com.agon.app.ui.theme.SlateBorder
import com.agon.app.ui.theme.SlateBorderLight
import com.agon.app.ui.theme.SlateTextPrimary
import com.agon.app.ui.theme.SlateTextSecondary
import com.agon.app.ui.theme.SlateTextMuted
import com.agon.app.ui.theme.NeonGreen
import com.agon.app.ui.theme.NeonGreenSoft
import com.agon.app.ui.theme.LiveRed
import com.agon.app.ui.theme.GlassSurface
import com.agon.app.ui.theme.GlassSurfaceAlt
import com.agon.app.ui.theme.glassmorphicCard
import com.agon.app.ui.theme.glassmorphicPill
import com.agon.app.ui.theme.glassmorphicPanel
import com.agon.app.ui.theme.PremiumSpring
import com.agon.app.ui.theme.SmoothSpring
import com.agon.app.ui.theme.focusGlow
import com.agon.app.ui.util.applyImmersiveFullscreen
import com.agon.app.ui.util.LogoPlaceholder
import com.agon.app.R
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import java.util.Calendar

/**
 * DashboardActivity - TV-First Premium Dashboard
 *
 * Layout:
 *   TOP BAR: [LIVE TV] [MOVIES] [SERIES]  ........  [Search] [SwapHoriz]
 *   LEFT SIDEBAR (18%): Category groups list only
 *   CONTENT GRID (82%): Compact cards, 100.dp adaptive
 *
 * Profile-aware: Reads PROFILE_ID from intent to load the correct account's streams.
 * On-demand: Movies/Series fetched ONLY when their tab is first clicked.
 */
class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveFullscreen()
        val profileId = intent.getStringExtra("PROFILE_ID")
        val initialCategory = intent.getStringExtra("CATEGORY") ?: "LIVE"
        val openSettings = intent.getBooleanExtra("OPEN_SETTINGS", false)
        // V8.5 — Hub peer mode. When the user tapped "Connect to Hub" on
        // LoginActivity, the launch Intent carries FROM_HUB=true and
        // HUB_URL="http://<host-ip>:<port>". Previously these extras were
        // sent but NEVER read — the peer landed on an empty grid because
        // it had no local profile and never fetched the host's playlist.
        val fromHub = intent.getBooleanExtra("FROM_HUB", false)
        val hubUrl = intent.getStringExtra("HUB_URL")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompactDashboardScreen(
                    profileId = profileId,
                    initialCategory = initialCategory,
                    openSettingsOnLaunch = openSettings,
                    fromHub = fromHub,
                    hubUrl = hubUrl
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDashboardScreen(
    profileId: String?,
    initialCategory: String = "LIVE",
    openSettingsOnLaunch: Boolean = false,
    fromHub: Boolean = false,
    hubUrl: String? = null
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val repo = remember { ProfileRepository.getInstance(context) }
        val favManager = remember { FavoritesManager.getInstance(context) }
        val pinManager = remember { ParentalPinManager.getInstance(context) }

        // ── Family Firewall state ──
        var lockedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
        var sessionUnlocked by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showPinSetup by remember { mutableStateOf(false) }
        var showPinEntry by remember { mutableStateOf(false) }
        var pinEntryTarget by remember { mutableStateOf("") }
        var pinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf("") }
        var pinSetupStep by remember { mutableStateOf("") }  // "create" or "confirm"
        var pinFirstInput by remember { mutableStateOf("") }
        var pendingLockGroup by remember { mutableStateOf("") }  // group to lock after PIN setup

        // Load locked groups on first composition
        LaunchedEffect(Unit) {
            lockedGroups = pinManager.getLockedGroups(context)
        }

        // ── ZERO-LAG ARCHITECTURE ──
        // DashboardActivity assumes ALL data is already cached in SessionData
        // by HubActivity's sync engine. We only fetch the profile name for the
        // top bar display — no stream fetching happens here.
        var currentProfileName by remember { mutableStateOf("") }
        var dataRefreshTrigger by remember { mutableStateOf(0) } // Forces UI recomposition

        // ═══════════════════════════════════════════════════════════════
        //  PHONE MODE TOGGLE — TV ⇄ Phone Interface Switch
        //  ═══════════════════════════════════════════════════════════════
        //  When [phoneMode] is false (default), the dashboard renders the
        //  standard horizontal TV grid interface.
        //  When [phoneMode] is true, a full-screen vertical phone interface
        //  overlay is rendered INSTEAD — with a horizontally-scrollable
        //  categories bar and a single-column vertical channel list.
        //
        //  The toggle also switches the device orientation at runtime:
        //    phoneMode = true  → PORTRAIT  (phones/tablets rotate)
        //    phoneMode = false → LANDSCAPE (TV mode)
        //  On Android TV boxes that don't support portrait, the layout
        //  still renders as a centered narrow phone-style column so the
        //  user gets the phone experience regardless.
        // ═══════════════════════════════════════════════════════════════
        var phoneMode by remember { mutableStateOf(false) }

        // ═══════════════════════════════════════════════════════════════
        //  COLLAPSIBLE HERO SLIDER — Leanback TV UI Refactoring
        //  ═══════════════════════════════════════════════════════════════
        //  When the Dashboard launches, the Hero Slider is shown at its
        //  full luxury size (190dp). As soon as the user D-Pads DOWN
        //  into the channel grid, [heroCollapsed] flips to true and the
        //  slider animates UP and OUT of view — the grid then expands
        //  to fill the full content area (Full Vertical Grid mode).
        //
        //  When the user D-Pads UP from the top row of the grid back
        //  toward the slider, [heroCollapsed] flips to false and the
        //  slider slides back down into view.
        //
        //  This is the "Collapsible Scrolling Layout" pattern from
        //  premium Leanback TV apps (Netflix / Disney+ / Prime Video).
        // ═══════════════════════════════════════════════════════════════
        var heroCollapsed by remember { mutableStateOf(false) }
        val heroFocusRequester = remember { FocusRequester() }

        // ═══════════════════════════════════════════════════════════════
        //  PHONE MODE MINI-PLAYER STATE — Unified Video Player
        //  ═══════════════════════════════════════════════════════════════
        //  Phone mode uses the SAME GlobalPlaybackCoordinator singleton
        //  as TV mode (shared ExoPlayer instance, shared buffer, resume
        //  bridge to PlayerActivity). When the user taps a channel card
        //  or the hero banner in phone mode, the stream is loaded into
        //  the coordinator and [phoneMiniPlayerStream] is set — the
        //  PhoneMiniPlayer overlay then renders at the bottom of the
        //  screen with a 16:9 video preview + Expand/Close buttons.
        //
        //  Tap Expand → launches PlayerActivity (resume bridge, zero
        //  black screen because the coordinator is already hot).
        //  Tap Close → pauses/stops the coordinator, clears the overlay.
        //
        //  The hero banner itself shows the video inline when its own
        //  stream is the active one — see [PhoneHeroBanner] below.
        // ═══════════════════════════════════════════════════════════════
        var phoneMiniPlayerStream by remember { mutableStateOf<StreamItem?>(null) }
        var phoneMiniPlayerUrl by remember { mutableStateOf("") }
        var phoneMiniPlayerName by remember { mutableStateOf("") }

        LaunchedEffect(profileId) {
            // V8.5 — HUB PEER MODE. When launched from "Connect to Hub",
            // this device has NO local profile. Instead of showing an empty
            // grid, fetch the host's playlist over the LAN via the
            // HubContentServer and parse it through the standard M3U
            // pipeline (PlaylistRepository.parseM3U populates SessionData,
            // which the grid reads from). This makes the peer mirror the
            // host's content instantly.
            if (fromHub && !hubUrl.isNullOrBlank()) {
                currentProfileName = "Hub: ${hubUrl.removePrefix("http://").removePrefix("https://")}"
                scope.launch {
                    val ok = PlaylistRepository.parseM3U("$hubUrl/playlist.m3u")
                    if (ok) {
                        dataRefreshTrigger++
                    } else {
                        android.util.Log.w("DashboardActivity",
                            "Hub playlist fetch failed from $hubUrl")
                    }
                }
                return@LaunchedEffect
            }

            if (!profileId.isNullOrEmpty()) repo.setActiveProfile(profileId)
            val profile = repo.getActiveProfile().first() ?: repo.getAllProfiles().first().firstOrNull()
            if (profile != null) {
                currentProfileName = profile.name
                dataRefreshTrigger++
            }
        }

        // ── Tab State ──
        // The initial category is passed in from HubActivity via Intent extra.
        // This highlights the correct top tab on opening.
        var selectedCategory by remember { mutableStateOf(initialCategory) }
        var searchQuery by remember { mutableStateOf("") }
        var selectedGroup by remember { mutableStateOf("All") }
        var showSearchBar by remember { mutableStateOf(false) }

        // ── Live Sports Harvester state ──
        // Drives the Glassmorphic Modal that shows today's matches. The
        // harvest itself runs in a background coroutine on first open;
        // subsequent opens re-use the cached list for 5 minutes.
        var showMatchesModal by remember { mutableStateOf(false) }
        var matchesState by remember {
            mutableStateOf<MatchHarvesterRepository.HarvestResult>(MatchHarvesterRepository.HarvestResult.Idle)
        }
        var matchesLastFetchedMs by remember { mutableLongStateOf(0L) }

        // SAFETY NET: when the modal opens with state = Idle (e.g. first
        // launch, or after process death), kick off the harvest here too.
        // This guarantees the loading spinner will eventually flip to
        // Loaded/Failed even if the button's onClick handler raced with
        // a recomposition. The button's own onClick handler is still the
        // primary trigger; this LaunchedEffect only fires when state is
        // still Idle AND the modal is visible.
        LaunchedEffect(showMatchesModal) {
            if (showMatchesModal &&
                matchesState is MatchHarvesterRepository.HarvestResult.Idle) {
                matchesState = MatchHarvesterRepository.HarvestResult.Loading
                val result = try {
                    val matches = MatchHarvesterRepository.getMatchesFromCache(context)
                    MatchHarvesterRepository.HarvestResult.Loaded(matches)
                } catch (t: Throwable) {
                    MatchHarvesterRepository.HarvestResult.Failed(
                        t.message ?: "Failed to load matches"
                    )
                }
                matchesState = result
                matchesLastFetchedMs = System.currentTimeMillis()
            }
        }

        // BACKGROUND RADAR SCAN — runs once on dashboard launch so the
        // sports ⚽ button can pulse green the moment any live match is
        // detected in the playlist (without requiring the user to open
        // the modal first). The scan is in-memory only, takes <1ms, and
        // does not block the UI thread (it runs on Dispatchers.Default).
        LaunchedEffect(Unit) {
            // Defer one frame so SessionData is populated by the time we scan.
            kotlinx.coroutines.delay(300)
            if (matchesState is MatchHarvesterRepository.HarvestResult.Idle) {
                val matches = try {
                    MatchHarvesterRepository.getMatchesFromCache(context)
                } catch (_: Throwable) { emptyList() }
                matchesState = MatchHarvesterRepository.HarvestResult.Loaded(matches)
                matchesLastFetchedMs = System.currentTimeMillis()
            }
        }
        var showSettings by remember { mutableStateOf(openSettingsOnLaunch) }
        // Silina Feed — TikTok-style vertical feed overlay
        var showSilinaFeed by remember { mutableStateOf(false) }
        // BandwidthShield overlay — shows data usage + daily limit controls
        var showZappingOverlay by remember { mutableStateOf(false) }
        // ═══ Sports Moments UI state ═══
        // Collects auto-clipped sports moments from SportsMomentsEngine.
        val sportsMoments by com.agon.app.sports.SportsMomentsEngine.moments
            .collectAsState(initial = emptyList())
        // ═══ Recommendations UI state ═══
        // Smart Zapping recommendations from WatchHistoryManager.
        var recommendations by remember { mutableStateOf<List<Pair<com.agon.app.data.model.StreamItem, Double>>>(emptyList()) }
        // ═══ Time Machine UI state ═══
        // Past programs available for catch-up replay.
        var timeMachinePrograms by remember { mutableStateOf<List<com.agon.app.timemachine.TimeMachineEngine.PastProgram>>(emptyList()) }
        // ═══ Catch-up channels list (for Time Machine) ═══
        var catchupChannels by remember { mutableStateOf<List<com.agon.app.data.model.StreamItem>>(emptyList()) }
        // Tracks whether the side-menu (sidebar) currently holds D-Pad focus.
        // Used by the contextual BackHandler to decide between "return focus
        // to the main grid" and "pop back to the Main Menu".
        var sidebarFocused by remember { mutableStateOf(false) }
        // Persistent favorites from DataStore (survives app restarts)
        val favoriteUrls by favManager.getFavorites()
            .collectAsState(initial = emptySet())

        // Data is already cached in SessionData by HubActivity — no lazy loading
        // state is needed anymore. Tabs switch instantly (Zero-Lag).

        // ── Episode Selector Dialog State ──
        var showEpisodeDialog by remember { mutableStateOf(false) }
        var episodeList by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
        var isLoadingEpisodes by remember { mutableStateOf(false) }
        var selectedSeriesName by remember { mutableStateOf("") }
        var selectedSeriesId by remember { mutableStateOf("") }
        var selectedSeriesCover by remember { mutableStateOf("") }

        // ── MINI-PLAYER + EPG PANEL STATE ──
        // Inline mini-player at the bottom of the dashboard — lets the user
        // browse channels while one plays in a small preview window.
        // Expand button opens PlayerActivity in full screen.
        var miniPlayerStream by remember { mutableStateOf<StreamItem?>(null) }
        var miniPlayerUrl by remember { mutableStateOf("") }
        var miniPlayerName by remember { mutableStateOf("") }
        var currentClock by remember { mutableLongStateOf(System.currentTimeMillis()) }

        // ═══════════════════════════════════════════════════════════════
        //  HOISTED FocusRequesters — D-Pad Spatial Navigation Fix
        //  ═══════════════════════════════════════════════════════════════
        //  These FocusRequesters are declared at the dashboard level so
        //  BOTH the LazyVerticalGrid (channel grid) AND the
        //  MiniPlayerEpgStrip (floating player overlay) can reference
        //  them in their `focusProperties` blocks.
        //
        //  Spatial Navigation Path:
        //    Channel Grid →(DOWN from bottom row)→ Expand Button
        //    Expand Button →(UP)→ Channel Grid (default traversal)
        //    Expand Button →(RIGHT)→ Close Button
        //    Close Button →(LEFT)→ Expand Button
        //
        //  Without this hoisting, the MiniPlayerEpgStrip's buttons were
        //  isolated from the grid's focus tree — the D-Pad couldn't
        //  reach them because there was no explicit spatial path
        //  connecting the two regions.
        // ═══════════════════════════════════════════════════════════════
        val miniPlayerExpandFocusRequester = remember { FocusRequester() }
        val miniPlayerCloseFocusRequester = remember { FocusRequester() }

        // ── Real EPG state for the Mini-Player ──
        // Fetched ONLY when the selected stream changes (single request per stream).
        // Progress is computed from the real start/stop timestamps — no dummy clock.
        var miniEpgData by remember { mutableStateOf<PlaylistRepository.LiveEpgData?>(null) }
        var miniEpgProgress by remember { mutableFloatStateOf(0f) }

        // ── MINI-PLAYER ExoPlayer instance ──
        // Uses the GlobalPlaybackCoordinator SINGLETON so the buffer is
        // shared with PlayerActivity. When the user taps Expand, the
        // coordinator already has the stream buffered — PlayerActivity
        // just calls coordinator.resume() (zero black screen).
        //
        // We do NOT release the singleton on dispose — it survives so
        // PlayerActivity can resume() the buffer. Just pause it when
        // the Dashboard leaves the foreground.
        val miniExoPlayer = remember { GlobalPlaybackCoordinator.getPlayer(context) }
        DisposableEffect(Unit) {
            onDispose {
                // Pause only — the singleton lives on.
                miniExoPlayer.pause()
            }
        }

        // ═══ DASHBOARD LIFECYCLE — RESUME MINI-PLAYER ON RETURN ═══
        // When the user returns from PlayerActivity (BACK button), the
        // singleton was paused by PlayerActivity.onPause(). If the
        // mini-player strip is still visible (miniPlayerStream != null),
        // resume playback so the audio continues on the Dashboard.
        //
        // ON_PAUSE: pause the singleton (prevents audio leak when the
        // user backgrounds the Dashboard or opens another Activity).
        val dashboardLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(dashboardLifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        // Pause the singleton so audio doesn't leak when the
                        // Dashboard is backgrounded (e.g. user opened another app).
                        GlobalPlaybackCoordinator.pause()
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        // If the mini-player strip is visible, resume the stream
                        // that was paused when the user navigated away.
                        if (miniPlayerStream != null) {
                            GlobalPlaybackCoordinator.resume()
                        }
                    }
                    else -> {}
                }
            }
            dashboardLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { dashboardLifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Clock ticker — only refreshes the "now" cursor every 30s so we can
        // recompute the live progress bar against the real EPG timestamps.
        // This does NOT trigger any network fetch.
        LaunchedEffect(Unit) {
            while (true) {
                currentClock = System.currentTimeMillis()
                val epg = miniEpgData
                if (epg != null && epg.stopTimestamp > epg.startTimestamp) {
                    val total = (epg.stopTimestamp - epg.startTimestamp).toFloat()
                    val elapsed = (currentClock - epg.startTimestamp).toFloat()
                    miniEpgProgress = if (total > 0f) (elapsed / total).coerceIn(0f, 1f) else 0f
                } else {
                    miniEpgProgress = 0f
                }
                delay(30_000L)
            }
        }

        // ═══ AUTO-START BACKEND ENGINES ═══
        // Start SportsMomentsEngine (polls for score changes, auto-clips
        // goals) and load Smart Zapping recommendations + Time Machine
        // programs as soon as the Dashboard opens.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(500) // let SessionData populate
            // 1. Start Sports Moments polling.
            scope.launch {
                try {
                    com.agon.app.sports.SportsMomentsEngine.start(context)
                } catch (_: Throwable) {}
            }
            // 2. Load Smart Zapping recommendations.
            scope.launch {
                try {
                    recommendations = com.agon.app.recommendation.WatchHistoryManager
                        .getRecommendations(context, limit = 6)
                } catch (_: Throwable) {}
            }
            // 3. Load Time Machine catch-up channels + past programs.
            scope.launch {
                try {
                    val channels = com.agon.app.timemachine.TimeMachineEngine.getCatchupChannels()
                    catchupChannels = channels
                    if (channels.isNotEmpty()) {
                        timeMachinePrograms = com.agon.app.timemachine.TimeMachineEngine
                            .getRecentPrograms(channels.first().url, maxHours = 6)
                    }
                } catch (_: Throwable) {}
            }
        }

        // Stop Sports Moments engine when leaving Dashboard.
        DisposableEffect(Unit) {
            onDispose {
                try { com.agon.app.sports.SportsMomentsEngine.stop() } catch (_: Throwable) {}
            }
        }

        // ── Dynamic EPG fetch — fires ONLY when the Mini-Player stream changes ──
        // Single request per stream change, served from RAM cache when available.
        // This is the ONLY EPG network call on the dashboard — never per-card.
        LaunchedEffect(miniPlayerUrl) {
            miniEpgData = null
            miniEpgProgress = 0f
            val stream = miniPlayerStream
            if (stream != null &&
                selectedCategory == "LIVE" &&
                SessionData.playlistType == PlaylistType.XTREAM_CODES
            ) {
                val streamId = stream.url.substringAfterLast("/").substringBeforeLast(".")
                if (streamId.isNotEmpty() && streamId.all { it.isDigit() }) {
                    val epg = PlaylistRepository.getShortEpg(streamId)
                    miniEpgData = epg
                    // Initial progress computation — subsequent ticks handled by the clock loop.
                    if (epg != null && epg.stopTimestamp > epg.startTimestamp) {
                        val now = System.currentTimeMillis()
                        val total = (epg.stopTimestamp - epg.startTimestamp).toFloat()
                        val elapsed = (now - epg.startTimestamp).toFloat()
                        miniEpgProgress = if (total > 0f) (elapsed / total).coerceIn(0f, 1f) else 0f
                    }
                }
            }
        }

        // ── Compute streams for current tab ──
        // ═══════════════════════════════════════════════════════════════
        //  PAGING 3 UI STREAMLINING
        //  ═══════════════════════════════════════════════════════════════
        //  For LIVE / MOVIES / SERIES tabs, the grid is now driven by
        //  Paging 3 — only the visible page (~40 items) is held in RAM,
        //  the full 22k+ list stays in Room. This eliminates the Compose
        //  frame drops that occurred when the entire list was loaded into
        //  memory and filtered in Kotlin.
        //
        //  For FAVORITES, we keep the in-memory filter path because
        //  favorites are derived from the favorites flow (not a Room
        //  table), so Paging 3 doesn't apply — the list is small anyway.
        //
        //  The `usePaging` flag controls which path the grid takes:
        //    - true  → pagedItems (collectAsLazyPagingItems)
        //    - false → filteredStreams (legacy in-memory List)
        // ═══════════════════════════════════════════════════════════════
        val trigger = dataRefreshTrigger // OBSERVE STATE

        // Map the selected category to a "kind" discriminator.
        val roomKind = when (selectedCategory) {
            "LIVE" -> PlaylistRepository.KIND_LIVE
            "MOVIES" -> PlaylistRepository.KIND_MOVIE
            "SERIES" -> PlaylistRepository.KIND_SERIES
            else -> ""  // FAVORITES — no Room kind
        }

        // ═══════════════════════════════════════════════════════════════
        //  PAGING 3 — now backed by SessionData directly (not Room).
        //  The paging source reads from SessionData.liveStreams /
        //  movieStreams / seriesStreams, so it ALWAYS has data when
        //  HubActivity has finished loading — regardless of whether
        //  Room was populated.
        //
        //  usePaging is true for LIVE / MOVIES / SERIES (any kind that
        //  has a SessionData list). FAVORITES uses the legacy in-memory
        //  path because favorites are derived from a flow, not a static
        //  list.
        //
        //  DATA REFRESH: The `dataRefreshTrigger` is included as a key
        //  for the LaunchedEffect that collects the paging flow. When
        //  HubActivity finishes loading (and increments dataRefreshTrigger),
        //  the flow is re-created with the new SessionData list reference
        //  → the grid immediately shows the freshly-loaded channels.
        // ═══════════════════════════════════════════════════════════════
        val usePaging = roomKind.isNotEmpty()

        // ═══════════════════════════════════════════════════════════════
        //  DERIVED STATE — Mini-Player Visibility (Recomposition Storm Fix)
        //  ═══════════════════════════════════════════════════════════════
        //  [miniPlayerVisible] is a derived Boolean that ONLY changes
        //  when the mini-player appears or disappears. It does NOT
        //  change when the user switches channels (miniPlayerStream
        //  changes, but miniPlayerStream != null stays true).
        //
        //  This is critical for the LazyVerticalGrid's focusProperties
        //  block: if we read `miniPlayerStream != null` directly inside
        //  the focusProperties lambda, the ENTIRE grid recomposes every
        //  time the user switches channels (because miniPlayerStream
        //  is a State<StreamItem?> that changes on every switch).
        //
        //  By reading [miniPlayerVisible] (a derived Boolean that only
        //  flips on show/hide), the grid's focusProperties block only
        //  recomposes when the mini-player actually appears or
        //  disappears — NOT on every channel switch.
        // ═══════════════════════════════════════════════════════════════
        val miniPlayerVisible by remember {
            derivedStateOf { miniPlayerStream != null }
        }

        // ═══════════════════════════════════════════════════════════════
        //  PAGER FLOW STABILIZATION — Prevent Invalidation Flashes
        //  ═══════════════════════════════════════════════════════════════
        //  The paging flow is wrapped in `remember` with a STABLE key
        //  (`roomKind + searchQuery + selectedGroup + trigger`). This
        //  ensures the flow is NOT re-created on every recomposition —
        //  only when the user actually changes the category, search
        //  query, group filter, or when HubActivity finishes loading.
        //
        //  Without this `remember`, every recomposition (e.g. when
        //  miniPlayerStream changes) would create a NEW Flow → a NEW
        //  Pager → a NEW PagingSource → Paging 3 would invalidate the
        //  entire grid and reload from page 0, causing the flash.
        //
        //  `collectAsLazyPagingItems()` then subscribes to this stable
        //  flow. The `.cachedIn(scope)` inside the repository keeps the
        //  PagingData alive across configuration changes.
        // ═══════════════════════════════════════════════════════════════
        val pagingFlow = remember(roomKind, searchQuery, selectedGroup, trigger) {
            if (usePaging) {
                PlaylistRepository.pagedChannelsFiltered(
                    context = context,
                    playlistId = SessionData.activePlaylistId,
                    kind = roomKind,
                    searchQuery = searchQuery,
                    group = selectedGroup,
                    scope = scope
                )
            } else {
                null
            }
        }

        // PAGING 3 FLOW — collects from SessionData via the custom
        // SessionDataPagingSource. The flow is cached in the screen's
        // coroutine scope so it survives recomposition without restarting
        // from page 0.
        //
        // CRITICAL: `pagingFlow` is wrapped in `remember` above with a
        // stable key, so this `collectAsLazyPagingItems()` call does NOT
        // re-subscribe on every recomposition. The LazyPagingItems
        // instance is stable across channel switches → no flash.
        val pagedItems: androidx.paging.compose.LazyPagingItems<StreamItem>? = if (pagingFlow != null) {
            pagingFlow.collectAsLazyPagingItems()
        } else {
            null
        }

        // ═══════════════════════════════════════════════════════════════
        //  ROOM FALLBACK DETECTION — SSOT + In-Memory Safety Net
        //  ═══════════════════════════════════════════════════════════════
        //  Room is the SINGLE SOURCE OF TRUTH. However, on first launch
        //  (before HubActivity's fetch writes to Room) or for M3U-only
        //  sessions that haven't been persisted yet, the Room `channels`
        //  table may be empty. In that case, we fall back to the
        //  in-memory SessionData lists so the grid is NEVER empty.
        //
        //  [roomEmpty] is true when the Room-backed PagingSource has
        //  finished loading (NotLoading) and returned 0 items. We check
        //  this AFTER the paging flow has settled — the initial state is
        //  "loading", so we don't prematurely fall back.
        //
        //  When [roomEmpty] is true AND SessionData has data for this
        //  kind, the grid uses [filteredStreams] (the legacy in-memory
        //  path) instead of [pagedItems] (the Room-backed path). This
        //  preserves the "never empty" guarantee while keeping Room as
        //  the SSOT for all subsequent loads (after the first fetch
        //  writes to Room, the fallback is no longer needed).
        // ═══════════════════════════════════════════════════════════════
        val roomLoadState = pagedItems?.loadState?.refresh
        val roomEmpty = usePaging && pagedItems != null &&
            pagedItems.itemCount == 0 &&
            roomLoadState is androidx.paging.LoadState.NotLoading
        val sessionDataHasData = when (roomKind) {
            PlaylistRepository.KIND_LIVE -> SessionData.liveStreams.isNotEmpty()
            PlaylistRepository.KIND_MOVIE -> SessionData.movieStreams.isNotEmpty()
            PlaylistRepository.KIND_SERIES -> SessionData.seriesStreams.isNotEmpty()
            else -> false
        }
        val useFallback = roomEmpty && sessionDataHasData

        // LEGACY IN-MEMORY PATH — used for FAVORITES AND as the Room
        // fallback when Room is empty but SessionData has data.
        val streams = when (selectedCategory) {
            "LIVE" -> SessionData.liveStreams
            "MOVIES" -> SessionData.movieStreams
            "SERIES" -> SessionData.seriesStreams
            // FAVORITES — aggregate all streams whose URL is in the user's
            // favorites set. Spans LIVE + MOVIES + SERIES so the user can
            // see their starred content in one place. The favoriteUrls
            // flow is collected above (favoriteUrls by favManager.getFavorites()).
            "FAVORITES" -> SessionData.allStreams.filter { it.url in favoriteUrls }
            else -> emptyList()
        }

        // ═══════════════════════════════════════════════════════════════
        //  GROUPS — derived from SessionData (not Room).
        //  For the paging path (LIVE / MOVIES / SERIES), we derive the
        //  groups from the same SessionData list that the paging source
        //  reads from. This guarantees the side rail shows the correct
        //  groups even when Room is empty.
        //
        //  For FAVORITES, we derive groups from the filtered favorites list.
        // ═══════════════════════════════════════════════════════════════
        val effectiveGroups = remember(streams, usePaging, roomKind, trigger) {
            if (usePaging) {
                // Derive groups from the SessionData list for this kind.
                // This is O(n) but only runs when the kind or data changes.
                val sourceList = when (roomKind) {
                    PlaylistRepository.KIND_LIVE -> SessionData.liveStreams
                    PlaylistRepository.KIND_MOVIE -> SessionData.movieStreams
                    PlaylistRepository.KIND_SERIES -> SessionData.seriesStreams
                    else -> emptyList()
                }
                listOf("All") + sourceList.map { it.group }.distinct().filter { it.isNotEmpty() }.sorted()
            } else {
                listOf("All") + streams.map { it.group }.distinct().filter { it.isNotEmpty() }.sorted()
            }
        }

        // ── Background filtering (off main thread for 50k+ stream lists) ──
        // Used for:
        //   1. FAVORITES tab — always (favorites are in-memory).
        //   2. Room fallback — when Room is empty but SessionData has data
        //      ([useFallback] == true), we filter SessionData in-memory.
        //   3. LIVE / MOVIES / SERIES with Room data — skipped (DB-side
        //      filtering via the Room PagingSource handles it).
        // favoriteUrls is included as a key so the grid refreshes when the
        // user toggles a star — without it, the FAVORITES tab would show a
        // stale (empty) list until the user navigated away and back.
        var filteredStreams by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
        LaunchedEffect(streams, searchQuery, selectedGroup, favoriteUrls, usePaging, useFallback) {
            if (usePaging && !useFallback) {
                // Room SSOT path — filteredStreams stays empty; the grid
                // uses pagedItems (Room-backed with DB-side filtering).
                filteredStreams = emptyList()
            } else {
                // FAVORITES tab OR Room fallback — filter SessionData in-memory.
                val result = withContext(Dispatchers.Default) {
                    streams.filter { (selectedGroup == "All" || it.group == selectedGroup) && it.name.contains(searchQuery, ignoreCase = true) }
                }
                filteredStreams = result
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // CONTEXTUAL HARDWARE BACK BUTTON HANDLER
        // Strict Back-Stack navigation — the hardware Back button MUST NOT
        // exit the app from the Channels Grid screen. It pops back to the
        // Main Menu (HubActivity) contextually:
        //   1. If the search bar is open → close it (and stay on this screen).
        //   2. Else if a side-menu group is focused/selected → reset focus
        //      to the main content grid by selecting "All" + clearing search.
        //   3. Else → finish() this screen to pop back to HubActivity.
        // The Settings overlay (showSettings) and any open dialogs take
        // precedence over this handler because they are rendered ABOVE the
        // grid in the z-stack and install their own key handlers.
        // ═══════════════════════════════════════════════════════════════════
        BackHandler(enabled = !showSettings && !showPinSetup && !showPinEntry && !showEpisodeDialog) {
            when {
                showSearchBar -> {
                    // 1. Close the search bar first; do NOT pop the screen.
                    showSearchBar = false
                    searchQuery = ""
                }
                sidebarFocused -> {
                    // 2. Sidebar has focus — return focus to the main content grid.
                    sidebarFocused = false
                    selectedGroup = "All"
                    searchQuery = ""
                }
                else -> {
                    // 3. Nothing else to close — pop back to the Main Menu.
                    (context as? ComponentActivity)?.finish()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        //  DIALOG BACK HANDLERS — each overlay gets its own BackHandler so
        //  pressing BACK closes the overlay instead of exiting the activity.
        //  Priority order (highest first): EpisodeDialog → PinEntry → PinSetup → Settings.
        // ═══════════════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════════
        //  EPISODES DIALOG BACK HANDLER — D-Pad Focus Navigation Fix
        //  Closes the episodes popup on BACK press and clears the episode
        //  list. Focus returns naturally to the series card in the grid
        //  that was clicked to open the dialog (Compose's focus system
        //  restores focus to the previously-focused composable when the
        //  dialog is dismissed).
        // ═══════════════════════════════════════════════════════════════════
        BackHandler(enabled = showEpisodeDialog) {
            showEpisodeDialog = false
            episodeList = emptyList()
        }
        BackHandler(enabled = showPinEntry && !showEpisodeDialog) {
            showPinEntry = false
        }
        BackHandler(enabled = showPinSetup && !showPinEntry && !showEpisodeDialog) {
            showPinSetup = false
        }
        BackHandler(enabled = showSettings && !showPinSetup && !showPinEntry && !showEpisodeDialog) {
            showSettings = false
        }

        // ROOT BOX TO ENFORCE Z-INDEX STACKING
        // Main Surface is drawn first (bottom layer), dialogs are drawn last (top layer).
        // This fixes the Z-Index visibility bug where the opaque Surface was painting over dialogs.
        Box(modifier = Modifier.fillMaxSize()) {

        // ══════════════════════════════════════════════════════════════════
        // 1. MAIN UI SURFACE (Drawn First -> Bottom Layer)
        // ══════════════════════════════════════════════════════════════════
        // ZERO-LAG: no loading screen — data is pre-cached by HubActivity.
        // V8.5.4 — Luxury black dashboard background (pure OLED-friendly
        // black with a subtle near-black gradient for depth).
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF050505), Color(0xFF000000))
                    )
                ),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ══════════════════════════════════════════════════════
                // FLOATING TOP NAV BAR — glassmorphic pill bar (2026)
                // Floats with padding + glass background instead of edge-to-edge.
                // ══════════════════════════════════════════════════════
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassmorphicPill(focused = false)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LEFT SIDE: Back button + Section Tabs
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Compact icon-only Back button — far left of the top nav.
                            // Returns to the Main Menu (HubActivity) by finishing this screen.
                            TopNavBackIcon(
                                onClick = {
                                    (context as? ComponentActivity)?.finish()
                                }
                            )
                            TopNavTab(
                                title = "LIVE TV",
                                isSelected = selectedCategory == "LIVE",
                                onClick = {
                                    selectedCategory = "LIVE"
                                    selectedGroup = "All"
                                    searchQuery = ""
                                    showSearchBar = false
                                    sidebarFocused = false
                                }
                            )
                            TopNavTab(
                                title = "MOVIES",
                                isSelected = selectedCategory == "MOVIES",
                                onClick = {
                                    selectedCategory = "MOVIES"
                                    selectedGroup = "All"
                                    searchQuery = ""
                                    showSearchBar = false
                                    sidebarFocused = false
                                }
                            )
                            TopNavTab(
                                title = "SERIES",
                                isSelected = selectedCategory == "SERIES",
                                onClick = {
                                    selectedCategory = "SERIES"
                                    selectedGroup = "All"
                                    searchQuery = ""
                                    showSearchBar = false
                                    sidebarFocused = false
                                }
                            )
                            // ═══ FAVORITES TAB ═══
                            // Shows all channels the user has starred (across
                            // LIVE / MOVIES / SERIES). Backed by FavoritesManager
                            // (DataStore-persisted). The star toggle on each
                            // card adds/removes the URL from this set.
                            TopNavTab(
                                title = "FAVORITES",
                                isSelected = selectedCategory == "FAVORITES",
                                onClick = {
                                    selectedCategory = "FAVORITES"
                                    selectedGroup = "All"
                                    searchQuery = ""
                                    showSearchBar = false
                                    sidebarFocused = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            // ═══ BANDWIDTH SHIELD BUTTON ═══
                            TopNavTab(
                                title = "FAST ZAP",
                                isSelected = showZappingOverlay,
                                onClick = { showZappingOverlay = !showZappingOverlay }
                            )
                            Spacer(Modifier.width(8.dp))
                            // ═══ LAN RELAY HUB BUTTON ═══
                            // Opens the LAN Relay Hub overlay — lets the user
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // ═══ PROFILE CHIP — Person icon button ═══
                            // Compact icon-only chip. Tapping it (or focusing it
                            // + pressing OK on D-Pad) opens a DropdownMenu below
                            // with the full account info: name + expiry date.
                            // DropdownMenu is used instead of raw Popup because
                            // it handles focus + dismissal correctly (no freeze).
                            if (currentProfileName.isNotEmpty()) {
                                var showAccountPopover by remember { mutableStateOf(false) }
                                var profileFocused by remember { mutableStateOf(false) }
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (profileFocused) Color.White.copy(alpha = 0.2f)
                                                else Color.White.copy(alpha = 0.06f)
                                            )
                                            .border(
                                                1.5.dp,
                                                if (profileFocused) AccentCyan else Color.White.copy(alpha = 0.1f),
                                                CircleShape
                                            )
                                            .focusable()
                                            .onFocusChanged { profileFocused = it.isFocused }
                                            .clickable { showAccountPopover = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = "Account",
                                            tint = AccentCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    // DropdownMenu — handles its own dismissal
                                    // (tap outside, BACK, Escape). No manual
                                    // close button needed — tapping anywhere
                                    // outside the menu closes it automatically.
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = showAccountPopover,
                                        onDismissRequest = { showAccountPopover = false },
                                        modifier = Modifier
                                            .width(220.dp)
                                            .background(
                                                Color(0xE61A1A1A),
                                                RoundedCornerShape(14.dp)
                                            )
                                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            // Account icon (larger, centered)
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape)
                                                    .background(AccentCyan.copy(alpha = 0.15f))
                                                    .border(1.5.dp, AccentCyan, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = AccentCyan,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            // Profile name
                                            Text(
                                                currentProfileName,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            // Account expiry — only shown when populated (Xtream accounts).
                                            val expiry = SessionData.accountExpiry
                                            if (expiry.isNotEmpty()) {
                                                Text(
                                                    "Account Expiry",
                                                    color = SlateTextMuted,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    expiry,
                                                    color = AccentCyan,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            } else {
                                                Text(
                                                    "No expiry (M3U playlist)",
                                                    color = SlateTextMuted,
                                                    fontSize = 10.sp
                                                )
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            // Close button — tapping anywhere outside
                                            // the menu also closes it, but this gives
                                            // an explicit, focusable target for D-Pad users.
                                            var closeFocused by remember { mutableStateOf(false) }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(
                                                        if (closeFocused) Color.White.copy(alpha = 0.2f)
                                                        else Color.White.copy(alpha = 0.08f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (closeFocused) AccentCyan else Color.White.copy(alpha = 0.15f),
                                                        RoundedCornerShape(50)
                                                    )
                                                    .focusable()
                                                    .onFocusChanged { closeFocused = it.isFocused }
                                                    .clickable { showAccountPopover = false },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "CLOSE",
                                                    color = if (closeFocused) Color.White else Color.White.copy(alpha = 0.7f),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // ── Live Sports Harvester trigger ──
                            // Glassmorphic icon-only button (same 30dp size /
                            // pill styling as TopNavIcon) — but with a green
                            // pulse glow when live matches are detected in
                            // the local playlist (Server Core radar scan).
                            // Does NOT alter any other button's weights or
                            // styling — strictly additive.
                            val hasLiveMatches = when (matchesState) {
                                is MatchHarvesterRepository.HarvestResult.Loaded ->
                                    (matchesState as MatchHarvesterRepository.HarvestResult.Loaded)
                                        .matches.isNotEmpty()
                                else -> false
                            }
                            LiveSportsButton(
                                hasLiveMatches = hasLiveMatches,
                                onClick = {
                                    showMatchesModal = true
                                    // CACHE-FIRST: read from local cache (0ms) and display
                                    // immediately without loading spinner. Then schedule a
                                    // background refresh with random jitter if TTL expired.
                                    scope.launch {
                                        // 1. Instant render from cache
                                        val cached = MatchHarvesterRepository.getMatchesFromCache(context)
                                        if (cached.isNotEmpty()) {
                                            matchesState = MatchHarvesterRepository.HarvestResult.Loaded(cached)
                                        } else {
                                            // First launch — no cache yet. Show loading + force fetch.
                                            matchesState = MatchHarvesterRepository.HarvestResult.Loading
                                            val fresh = MatchHarvesterRepository.forceFetch(context)
                                            matchesState = MatchHarvesterRepository.HarvestResult.Loaded(fresh)
                                        }
                                        // 2. Silent background refresh if TTL expired (0-30s jitter)
                                        scope.launch {
                                            MatchHarvesterRepository.refreshCacheInBackground(context)
                                            // After background refresh, update UI silently
                                            val updated = MatchHarvesterRepository.getMatchesFromCache(context)
                                            if (updated.isNotEmpty()) {
                                                matchesState = MatchHarvesterRepository.HarvestResult.Loaded(updated)
                                            }
                                        }
                                    }
                                }
                            )
                            TopNavIcon(
                                icon = Icons.Default.Refresh,
                                label = "Update",
                                onClick = {
                                    // Refresh re-fetches the LIVE cache only (VOD/Series stay cached).
                                    // Movies/Series tabs will continue to display from SessionData.
                                    scope.launch {
                                        val p = repo.getActiveProfile().first() ?: repo.getAllProfiles().first().firstOrNull()
                                        if (p != null) {
                                            SessionData.liveStreams = emptyList()
                                            try {
                                                var success = false
                                                if (p.serverUrl.isNotEmpty() && p.username.isNotEmpty()) {
                                                    success = PlaylistRepository.fetchXtreamStreams(p.serverUrl, p.username, p.password)
                                                } else if (p.m3uUrl.isNotEmpty()) {
                                                    success = PlaylistRepository.parseM3U(p.m3uUrl)
                                                }
                                                if (success && SessionData.liveStreams.isEmpty()) {
                                                    SessionData.liveStreams = SessionData.allStreams
                                                }
                                                PlaylistRepository.saveFastCache(context, p.id, "LIVE", SessionData.liveStreams)
                                            } catch (_: Exception) {}
                                            dataRefreshTrigger++
                                            selectedCategory = "LIVE"
                                        }
                                    }
                                }
                            )
                            TopNavIcon(
                                icon = Icons.Default.Search,
                                label = "Search",
                                onClick = { showSearchBar = !showSearchBar }
                            )
                            // ═══ PHONE MODE TOGGLE BUTTON ═══
                            // Toggles between the TV (landscape grid) and
                            // Phone (portrait vertical) interface WITHIN the
                            // same dashboard page — no new Activity launched.
                            //
                            // ICON SEMANTICS (intuitive for the user):
                            //   • In TV mode   → shows PhoneAndroid icon
                            //     ("tap to switch to phone interface")
                            //   • In Phone mode → shows Tv icon
                            //     ("tap to switch back to TV interface")
                            //
                            // The device orientation is rotated at runtime via
                            // setRequestedOrientation(). DashboardActivity
                            // declares configChanges=orientation|screenSize so
                            // the rotation does NOT recreate the Activity —
                            // the composable simply recomposes in the new mode.
                            TopNavIcon(
                                icon = if (phoneMode) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                                label = if (phoneMode) "TV Mode" else "Phone Mode",
                                onClick = {
                                    phoneMode = !phoneMode
                                    // Rotate the device to match the new mode.
                                    // On TV boxes that don't support portrait,
                                    // this is a no-op — the phone-style layout
                                    // still renders as a centered column.
                                    val activity = context as? ComponentActivity
                                    activity?.requestedOrientation =
                                        if (phoneMode) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                            )
                        }
                    }
                }

                // ── Optional Search Bar (below top nav) — floating glassmorphic ──
                if (showSearchBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .glassmorphicPill(focused = false)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = SlateTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = SlateTextPrimary,
                                    fontSize = 13.sp
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    "CLEAR",
                                    color = AccentIndigoLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { searchQuery = "" }
                                )
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                // MAIN BODY: Sidebar (18%) + Content Grid (82%) — Deep Space 2026
                // ══════════════════════════════════════════════════════
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {

                    // ── LEFT SIDEBAR: glassmorphic Categories/Groups (18%) ──
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.18f)
                            .padding(end = 6.dp, top = 2.dp, bottom = 4.dp)
                            .glassmorphicPanel(cornerRadius = 14)
                            .padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            "GROUPS",
                            color = SlateTextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            color = SlateBorder,
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            items(effectiveGroups, key = { it }) { group ->
                                val isLocked = lockedGroups.contains(group) && group != "All" && !sessionUnlocked.contains(group)
                                val groupCount = remember(streams, group) {
                                    if (group == "All") streams.size
                                        else streams.count { it.group == group }
                                    }
                                    GroupItem(
                                        name = group,
                                        count = groupCount,
                                        isSelected = selectedGroup == group,
                                        isLocked = isLocked,
                                        // Instant focus update — D-Pad navigation switches the
                                        // displayed group immediately without requiring a click.
                                        // Also marks the sidebar as focused so the contextual
                                        // BackHandler knows to return focus to the grid first.
                                        onFocus = {
                                            sidebarFocused = true
                                            if (!isLocked) selectedGroup = group
                                        },
                                        onClick = {
                                            if (isLocked) {
                                                pinEntryTarget = group
                                                pinInput = ""
                                                pinError = ""
                                                showPinEntry = true
                                            } else {
                                                selectedGroup = it
                                            }
                                        },
                                        onLongPress = {
                                            if (group == "All") return@GroupItem
                                            scope.launch {
                                                if (!pinManager.hasPin(context)) {
                                                    pinSetupStep = "create"
                                                    pendingLockGroup = group
                                                    showPinSetup = true
                                                } else {
                                                    pinManager.toggleGroupLock(context, group)
                                                    lockedGroups = pinManager.getLockedGroups(context)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // ── CONTENT AREA (82%) ──
                        // ZERO-LAG: data is pre-cached by HubActivity — no loading spinner.
                        Box(modifier = Modifier.fillMaxHeight().weight(0.82f)) {
                            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp)) {

                                // ═══════════════════════════════════════════════════════════
                                //  BANDWIDTH SHIELD PANEL (shown when user taps "DATA")
                                //  Replaces the old "For You" panel. Shows real-time
                                //  data usage + daily limit controls directly in the
                                //  dashboard — no need to dig through Settings.
                                // ═══════════════════════════════════════════════════════════
                                if (showZappingOverlay) {
                                    FastZappingPanel(
                                        context = context,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                
                                //  hero content is clipped during the collapse animation
                                //  (no visual overflow into the grid area).
                                // ═══════════════════════════════════════════════════════════
                                val heroTargetHeight = if (heroCollapsed || showZappingOverlay || streams.isEmpty()) 0.dp else 190.dp
                                val heroHeight by animateDpAsState(
                                    targetValue = heroTargetHeight,
                                    animationSpec = tween(400),
                                    label = "heroCollapse"
                                )
                                if (heroHeight > 0.dp && !showZappingOverlay && streams.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(heroHeight)
                                            .clipToBounds()
                                            .focusRequester(heroFocusRequester)
                                    ) {
                                        HeroSlider(
                                            streams = streams,
                                            onStreamClick = { stream ->
                                                // Same inline-playback pattern as grid card click
                                                miniPlayerStream = stream
                                                miniPlayerUrl = stream.url
                                                miniPlayerName = stream.name
                                                SessionData.currentSurferStreams = streams
                                                GlobalPlaybackCoordinator.playStream(stream.url)
                                            },
                                            modifier = Modifier.padding(bottom = 10.dp)
                                        )
                                    }
                                }

                                // ═══════════════════════════════════════════════════════════
                                //  MAIN CHANNEL GRID (always visible)
                                //  ═══════════════════════════════════════════════════════════
                                //  PAGING 3 UI STREAMLINING:
                                //  When [usePaging] is true (LIVE / MOVIES / SERIES tabs with
                                //  an active Room playlist), the grid is driven by
                                //  [pagedItems] (collectAsLazyPagingItems). Only the visible
                                //  page (~40 items) is held in RAM — the full 22k+ list stays
                                //  in Room and is paged in on demand.
                                //
                                //  When [usePaging] is false (FAVORITES tab or M3U-only
                                //  fallback), the grid uses the legacy [filteredStreams]
                                //  in-memory List path — favorites are derived from a flow,
                                //  not a Room table, so Paging 3 doesn't apply.
                                //
                                //  BOTH paths share the SAME LazyVerticalGrid container
                                //  with the SAME focusProperties wiring
                                //  (canFocus = false + down = miniPlayerExpandFocusRequester).
                                //  This guarantees the D-Pad focus navigation behavior is
                                //  IDENTICAL regardless of which data path is active.
                                // ═══════════════════════════════════════════════════════════
                                // ═══════════════════════════════════════════════════════════
                                //  EMPTY STATE + FALLBACK DETECTION
                                //  ═══════════════════════════════════════════════════════════
                                //  [pagingEmpty] — Room SSOT path returned 0 items AND
                                //    we're NOT using the in-memory fallback. This is the
                                //    TRUE empty state (no data in Room or SessionData).
                                //  [legacyEmpty] — FAVORITES tab or Room fallback path
                                //    with an empty filteredStreams list.
                                //
                                //  When [useFallback] is true (Room empty + SessionData
                                //  has data), we use the legacy path — the grid renders
                                //  from filteredStreams, NOT pagedItems. This is the
                                //  safety net that guarantees the grid is NEVER empty.
                                // ═══════════════════════════════════════════════════════════
                                val pagingEmpty = usePaging && !useFallback && pagedItems?.itemCount == 0 &&
                                    pagedItems?.loadState?.refresh is LoadState.NotLoading
                                val legacyEmpty = (!usePaging || useFallback) && filteredStreams.isEmpty()

                                if (pagingEmpty || legacyEmpty) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Default.StarBorder,
                                                contentDescription = null,
                                                tint = SlateTextMuted,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                when {
                                                    selectedCategory == "FAVORITES" && favoriteUrls.isEmpty() ->
                                                        "No favorites yet"
                                                    selectedCategory == "FAVORITES" ->
                                                        "Your favorites are loading..."
                                                    searchQuery.isNotEmpty() ->
                                                        "No channels match \"$searchQuery\""
                                                    selectedGroup != "All" ->
                                                        "No channels in \"$selectedGroup\""
                                                    else -> "No channels available"
                                                },
                                                color = SlateTextSecondary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (selectedCategory == "FAVORITES" && favoriteUrls.isEmpty()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    "Tap the ★ on any channel to add it here",
                                                    color = SlateTextMuted,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                } else {
                                // Master-Detail Grid: 5 fixed columns
                                // ═══════════════════════════════════════════════════════════════
                                //  ANDROID TV FOCUS REFACTOR — D-Pad / Remote Control
                                // ═══════════════════════════════════════════════════════════════
                                //  Defect fixed: "focus jumps randomly or freezes when
                                //  browsing fast up/down through the LazyVerticalGrid."
                                //
                                //  Root cause: the grid had no `focusProperties { canFocus
                                //  = false }` on its container, so the D-Pad could land on
                                //  the grid itself instead of on a channel card. With 22k+
                                //  channels, the nearest-neighbour algorithm also picked
                                //  unpredictable cards after a fast scroll.
                                //
                                //  Fix: the container is marked non-focusable so the
                                //  D-Pad ALWAYS lands on a channel card. Each card's own
                                //  focusable() + onFocusChanged (inside [CompactStreamCard])
                                //  handles the visual highlight. The `key = { it.url }`
                                //  item key already provides stable identity across scroll,
                                //  which is the foundation that lets Compose preserve the
                                //  focused item when it scrolls out and back in.
                                // ═══════════════════════════════════════════════════════════════
                                val gridState = rememberLazyGridState()
                                val gridFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
                                // Tracks whether the grid currently has D-Pad focus. Used by
                                // the scroll-based collapse below to distinguish D-Pad (focus)
                                // from touch (scroll) interactions — prevents the scroll-to-top
                                // auto-expand from fighting the focus-based collapse on D-Pad.
                                var gridHasFocus by remember { mutableStateOf(false) }

                                // ═══════════════════════════════════════════════════════════════
                                //  TOUCH SCROLL COLLAPSE — Hero collapses on touch scroll too
                                //  ═══════════════════════════════════════════════════════════════
                                //  The [onFocusChanged] below collapses the hero when a card
                                //  gains D-Pad focus. But on TOUCH devices (phones/tablets in
                                //  TV mode), the grid doesn't gain focus the same way — the
                                //  user scrolls with a finger drag, which doesn't trigger
                                //  focus changes.
                                //
                                //  This LaunchedEffect observes the grid's scroll position via
                                //  [snapshotFlow]. When the user scrolls DOWN (firstVisibleItem
                                //  index > 0 OR scroll offset > 200px), the hero collapses.
                                //  When they scroll back to the top AND the grid has no D-Pad
                                //  focus (i.e. it's a touch interaction), the hero expands again.
                                //
                                //  The [!gridHasFocus] guard on the expand prevents conflict
                                //  with the D-Pad path: on D-Pad, focus enters the grid →
                                //  hero collapses → the user is at index 0 → without the guard,
                                //  the scroll flow would immediately expand it back. With the
                                //  guard, the expand only fires for touch (no focus).
                                // ═══════════════════════════════════════════════════════════════
                                LaunchedEffect(gridState) {
                                    snapshotFlow {
                                        Pair(
                                            gridState.firstVisibleItemIndex,
                                            gridState.firstVisibleItemScrollOffset
                                        )
                                    }.collect { (index, offset) ->
                                        if (index > 0 || offset > 200) {
                                            // Scrolled away from top — collapse.
                                            if (!heroCollapsed) heroCollapsed = true
                                        } else if (!gridHasFocus) {
                                            // Scrolled back to top on TOUCH (no D-Pad focus) — expand.
                                            if (heroCollapsed) heroCollapsed = false
                                        }
                                    }
                                }
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(5),
                                    state = gridState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // ═══════════════════════════════════════════════════════════════
                                        //  COLLAPSIBLE HERO — Collapse on Grid Focus
                                        //  ═══════════════════════════════════════════════════════════════
                                        //  When any channel card in the grid gains focus
                                        //  (state.hasFocus becomes true), the hero slider
                                        //  collapses — the grid expands to Full Vertical Grid.
                                        //  This is the "D-Pad Down → slider slides up" behavior
                                        //  from the Leanback TV UI spec.
                                        // ═══════════════════════════════════════════════════════════════
                                        .onFocusChanged { state ->
                                            gridHasFocus = state.hasFocus
                                            if (state.hasFocus) {
                                                heroCollapsed = true
                                            }
                                        }
                                        // Container is NOT focusable — focus lands on
                                        // the channel cards inside, never on the grid
                                        // itself. This is the single most impactful fix
                                        // for the "random focus jump" defect on TV.
                                        //
                                        // SPATIAL EXIT: when the Mini-Player is visible
                                        // ([miniPlayerVisible] == true), pressing DOWN
                                        // from the BOTTOM ROW of the grid sends focus
                                        // directly to the Mini-Player's Expand button.
                                        //
                                        // RECOMPOSITION STORM FIX: We read
                                        // [miniPlayerVisible] (a derivedStateOf Boolean)
                                        // instead of `miniPlayerStream != null`. The
                                        // derived state ONLY changes when the mini-player
                                        // appears or disappears — NOT on every channel
                                        // switch. This prevents the entire grid from
                                        // recomposing when the user switches channels.
                                        .focusProperties {
                                            canFocus = false
                                            if (miniPlayerVisible) {
                                                down = miniPlayerExpandFocusRequester
                                            }
                                        }
                                        // ═══════════════════════════════════════════════════════════════
                                        //  GRID-BOTTOM FOCUS GUARD — "Exit → Loading Screen" Fix
                                        //  + COLLAPSIBLE HERO — D-Pad UP at top expands hero
                                        //  ═══════════════════════════════════════════════════════════════
                                        //  When the Mini-Player is NOT visible and the user
                                        //  D-Pads DOWN past the last row, the grid swallows
                                        //  the key so focus never escapes the grid (prevents
                                        //  the "exit → loading screen" defect).
                                        //
                                        //  When the user D-Pads UP at the TOP row:
                                        //    • If the hero is collapsed → expand it and move
                                        //      focus up to the hero slider after the expand
                                        //      animation completes.
                                        //    • If the hero is visible → move focus up to it
                                        //      immediately.
                                        //  This is the "D-Pad Up → slider slides back down"
                                        //  behavior from the Leanback TV UI spec.
                                        // ═══════════════════════════════════════════════════════════════
                                        .onKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                                            val isDown = event.key == androidx.compose.ui.input.key.Key.DirectionDown
                                            val isUp = event.key == androidx.compose.ui.input.key.Key.DirectionUp
                                            if (!isDown && !isUp) return@onKeyEvent false
                                            // Determine whether the grid can still scroll in the
                                            // requested direction. If it can't, swallow the key
                                            // so focus never escapes the grid.
                                            val total = if (usePaging && !useFallback && pagedItems != null) pagedItems.itemCount else filteredStreams.size
                                            val visible = gridState.layoutInfo.visibleItemsInfo
                                            val atBottom = visible.isNotEmpty() &&
                                                visible.last().index >= total - 1
                                            val atTop = gridState.firstVisibleItemIndex <= 0
                                            when {
                                                isDown && atBottom && !miniPlayerVisible -> {
                                                    // At the bottom row with no mini-player: keep the
                                                    // key inside the grid so the Activity never sees it.
                                                    true
                                                }
                                                isUp && atTop -> {
                                                    // D-Pad UP at the top row: expand the hero slider
                                                    // (if collapsed) and move focus to it.
                                                    if (heroCollapsed) {
                                                        heroCollapsed = false
                                                        // After the expand animation, move focus up
                                                        // to the hero slider.
                                                        scope.launch {
                                                            delay(450)
                                                            runCatching {
                                                                gridFocusManager.moveFocus(FocusDirection.Up)
                                                            }
                                                        }
                                                    } else {
                                                        // Hero is visible — move focus up immediately.
                                                        runCatching {
                                                            gridFocusManager.moveFocus(FocusDirection.Up)
                                                        }
                                                    }
                                                    true // consume
                                                }
                                                else -> false
                                            }
                                        },
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                        // ═══════════════════════════════════════════════════════════════
                                        //  PAGING 3 PATH (Room SSOT) — LIVE / MOVIES / SERIES
                                        //  ═══════════════════════════════════════════════════════════════
                                        //  Used when Room has data for this (playlistId, kind)
                                        //  slice. items(pagedItems.itemCount) iterates over the
                                        //  LazyPagingItems flow. Each `pagedItems[index]` is
                                        //  non-null when the page has loaded; null placeholders
                                        //  are skipped (Room doesn't support
                                        //  enablePlaceholders = true). The `key` lambda uses
                                        //  stream.url for stable identity — same as the legacy
                                        //  path, so Compose preserves focus across page loads.
                                        //
                                        //  When [useFallback] is true (Room empty + SessionData
                                        //  has data), we skip this path and use the legacy
                                        //  in-memory path below.
                                        // ═══════════════════════════════════════════════════════════════
                                        if (usePaging && !useFallback && pagedItems != null) {
                                            // ═══════════════════════════════════════════════════════════════
                                            //  UNIQUE-KEY GUARD — Duplicate-URL Crash Fix
                                            //  ═══════════════════════════════════════════════════════════════
                                            //  IPTV playlists routinely contain DUPLICATE stream
                                            //  URLs (the same channel mirrored across multiple
                                            //  groups, or the same movie URL reused). The previous
                                            //  key `pagedItems[index]?.url` collided when two
                                            //  pages both contained the same URL → Compose threw
                                            //  `IllegalArgumentException: Key must be unique`
                                            //  the moment a duplicate card scrolled into view.
                                            //
                                            //  That uncaught crash destroyed DashboardActivity;
                                            //  the back-stack fell back to HubActivity which
                                            //  re-showed the SyncUi ("Fetching Live TV…")
                                            //  loading screen — exactly the "exit → loading screen"
                                            //  defect reported when scrolling to the last rows.
                                            //
                                            //  Fix: suffix the URL with the stable page index so
                                            //  every slot has a globally-unique key, while still
                                            //  preserving identity across recompositions for the
                                            //  same slot (index is stable per LazyPagingItems).
                                            // ═══════════════════════════════════════════════════════════════
                                            // ═══════════════════════════════════════════════════════════════
                                            //  V8.3 — NATIVE AD INJECTION REMOVED
                                            //  ═══════════════════════════════════════════════════════════════
                                            //  The NativeAdCard + NATIVE_AD_INTERVAL ad-slot injection
                                            //  logic was physically removed in V8.3. The grid now
                                            //  renders content cards directly — no ad slots, no
                                            //  combined-count math.
                                            // ═══════════════════════════════════════════════════════════════
                                            items(pagedItems.itemCount, key = { index ->
                                                val url = pagedItems[index]?.url
                                                if (url.isNullOrEmpty()) "placeholder_$index" else "${url}_$index"
                                            }) { index ->
                                                val stream = pagedItems[index] ?: return@items
                                                // ═══════════════════════════════════════════════════════════════
                                                //  LAMBDA STATE DEFERRING — Recomposition Storm Fix
                                                //  ═══════════════════════════════════════════════════════════════
                                                //  isFavorite and isSelected are passed as LAMBDAS
                                                //  (not Booleans) so the card defers the state read
                                                //  into its own draw scope. When miniPlayerStream or
                                                //  favoriteUrls changes, only the AFFECTED cards
                                                //  recompose — the rest of the 22k grid stays stable.
                                                //
                                                //  CRITICAL: We capture `stream.url` and
                                                //  `miniPlayerUrl` inside the lambda — NOT the
                                                //  StreamItem object or the miniPlayerStream
                                                //  reference. This ensures the lambda's return
                                                //  value only changes when the URL actually
                                                //  changes, not when the parent recomposes.
                                                // ═══════════════════════════════════════════════════════════════
                                                CompactStreamCard(
                                                    stream = stream,
                                                    isFavorite = { favoriteUrls.contains(stream.url) },
                                                    onFavoriteClick = {
                                                        scope.launch { favManager.toggleFavorite(stream.url) }
                                                    },
                                                    isSelected = { miniPlayerUrl == stream.url },
                                                    onClick = {
                                                        if (selectedCategory == "SERIES" && SessionData.playlistType == PlaylistType.XTREAM_CODES) {
                                                            val seriesId = stream.url.substringAfterLast("/")
                                                            selectedSeriesName = stream.name
                                                            selectedSeriesId = seriesId
                                                            selectedSeriesCover = stream.logo
                                                            isLoadingEpisodes = true
                                                            showEpisodeDialog = true
                                                            scope.launch {
                                                                episodeList = PlaylistRepository.getSeriesEpisodes(seriesId)
                                                                isLoadingEpisodes = false
                                                            }
                                                        } else {
                                                            // Play in inline Mini-Player instead of opening PlayerActivity.
                                                            // User can tap Expand on the Mini-Player to go full screen.
                                                            SessionData.currentSurferStreams = streams
                                                            miniPlayerStream = stream
                                                            miniPlayerUrl = stream.url
                                                            miniPlayerName = stream.name
                                                            // Route through the coordinator — handles
                                                            // cancellation + IO scrape + single prepare.
                                                            GlobalPlaybackCoordinator.playStream(stream.url)
                                                        }
                                                    }
                                                )
                                            }
                                        } else {
                                            // ═══════════════════════════════════════════════════════════════
                                            //  LEGACY PATH — FAVORITES + Room Fallback
                                            //  ═══════════════════════════════════════════════════════════════
                                            //  Used for:
                                            //    1. FAVORITES tab — favorites are derived from a flow,
                                            //       not a Room table, so Paging 3 doesn't apply.
                                            //    2. Room fallback — when Room is empty for this slice
                                            //       but SessionData has data ([useFallback] == true),
                                            //       we render from [filteredStreams] so the grid is
                                            //       NEVER empty on first launch.
                                            //  STRICT NO-DDOS POLICY
                                            //  Channel cards in the grid MUST NOT fetch EPG data.
                                            //  Each card displays only the channel logo + name.
                                            //  EPG is fetched ONLY for the currently selected stream
                                            //  in the Mini-Player strip (single request per stream).
                                            // ═══════════════════════════════════════════════════════════════
                                            // ═══════════════════════════════════════════════════════════════
                                            //  V8.3 — NATIVE AD INJECTION REMOVED (legacy/fallback path)
                                            //  ═══════════════════════════════════════════════════════════════
                                            //  Same V8.3 removal as the paging path above — the legacy
                                            //  fallback grid now renders content cards directly with no
                                            //  ad slots.
                                            // ═══════════════════════════════════════════════════════════════
                                            items(filteredStreams.size, key = { index ->
                                                val s = filteredStreams.getOrNull(index)
                                                val url = s?.url ?: ""
                                                if (url.isEmpty()) "empty_${index}" else "${url}_${index}"
                                            }) { index ->
                                                val stream = filteredStreams.getOrNull(index) ?: return@items
                                                CompactStreamCard(
                                                    stream = stream,
                                                    isFavorite = { favoriteUrls.contains(stream.url) },
                                                    onFavoriteClick = {
                                                        scope.launch { favManager.toggleFavorite(stream.url) }
                                                    },
                                                    isSelected = { miniPlayerUrl == stream.url },
                                                    onClick = {
                                                        if (selectedCategory == "SERIES" && SessionData.playlistType == PlaylistType.XTREAM_CODES) {
                                                            val seriesId = stream.url.substringAfterLast("/")
                                                            selectedSeriesName = stream.name
                                                            selectedSeriesId = seriesId
                                                            selectedSeriesCover = stream.logo
                                                            isLoadingEpisodes = true
                                                            showEpisodeDialog = true
                                                            scope.launch {
                                                                episodeList = PlaylistRepository.getSeriesEpisodes(seriesId)
                                                                isLoadingEpisodes = false
                                                            }
                                                        } else {
                                                            // Play in inline Mini-Player instead of opening PlayerActivity.
                                                            // User can tap Expand on the Mini-Player to go full screen.
                                                            SessionData.currentSurferStreams = streams
                                                            miniPlayerStream = stream
                                                            miniPlayerUrl = stream.url
                                                            miniPlayerName = stream.name
                                                            // Route through the coordinator — handles
                                                            // cancellation + IO scrape + single prepare.
                                                            GlobalPlaybackCoordinator.playStream(stream.url)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } // end else (LazyVerticalGrid)
                                } // end if/else (pagingEmpty/legacyEmpty/grid)

                                // ── MINI-PLAYER + EPG PANEL (Bottom strip) ──
                                // Visible only when a stream has been selected.
                                // ═══════════════════════════════════════════════════════════════
                                //  SPATIAL NAVIGATION: the hoisted FocusRequesters
                                //  (miniPlayerExpandFocusRequester / miniPlayerCloseFocusRequester)
                                //  are passed as parameters so the Mini-Player's buttons
                                //  are connected to the channel grid's `focusProperties`
                                //  exit path. Pressing DOWN from the grid's bottom row
                                //  lands on the Expand button; pressing RIGHT from Expand
                                //  lands on Close; pressing LEFT from Close returns to Expand.
                                // ═══════════════════════════════════════════════════════════════
                                if (miniPlayerStream != null) {
                                    MiniPlayerEpgStrip(
                                        exoPlayer = miniExoPlayer,
                                        streamName = miniPlayerName,
                                        currentClock = currentClock,
                                        epgData = miniEpgData,
                                        epgProgress = miniEpgProgress,
                                        expandFocusRequester = miniPlayerExpandFocusRequester,
                                        closeFocusRequester = miniPlayerCloseFocusRequester,
                                        onExpand = {
                                            // RESUME BRIDGE: The coordinator singleton is already
                                            // playing this URL — we do NOT stop/clear it. Just
                                            // pause it so PlayerActivity can resume() the buffered
                                            // stream with zero latency (no scrape, no black screen).
                                            val s = miniPlayerStream
                                            miniPlayerStream = null
                                            if (s != null) {
                                                // Pause (not stop!) — the buffer survives for
                                                // PlayerActivity to resume().
                                                miniExoPlayer.pause()
                                                // Force-detach the mini-player's surface so the
                                                // orphaned PlayerView's surfaceDestroyed cannot
                                                // race PlayerActivity's fresh surface (caused a
                                                // video freeze on maximize).
                                                miniExoPlayer.clearVideoSurface()
                                                context.startActivity(
                                                    Intent(context, PlayerActivity::class.java).apply {
                                                        putExtra("STREAM_URL", s.url)
                                                        putExtra("STREAM_NAME", s.name)
                                                        // Tell PlayerActivity this is a resume-bridge
                                                        // launch — the coordinator is already hot.
                                                        putExtra("FROM_FEED", true)
                                                        putExtra("FEED_URL", s.url)
                                                    }
                                                )
                                            }
                                        },
                                        onClose = {
                                            // Close button: stop playback entirely. The singleton
                                            // is paused (NOT released — survives for next session).
                                            miniExoPlayer.pause()
                                            miniExoPlayer.stop()
                                            miniPlayerStream = null
                                            miniPlayerUrl = ""
                                            miniPlayerName = ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

        // ══════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════
        //  PHONE MODE OVERLAY — Vertical Phone Interface
        //  ═══════════════════════════════════════════════════════════════
        //  When [phoneMode] is true, this full-screen overlay is drawn
        //  ON TOP of the TV Surface (z-index: above TV layout, below
        //  dialogs). It renders a phone-optimized VERTICAL interface:
        //    • Compact top nav (back, brand, search, refresh, TV toggle)
        //    • Horizontally-scrollable categories bar (drag to scroll)
        //    • Horizontally-scrollable groups bar
        //    • Single-column vertical channel list (phone-friendly cards)
        //
        //  On phones/tablets the device rotates to PORTRAIT via
        //  setRequestedOrientation. On Android TV boxes that don't
        //  support portrait, the layout renders as a centered narrow
        //  column (max 520dp) so the user still gets the phone feel.
        //
        //  Channel tap opens PlayerActivity directly (full-screen) —
        //  the standard phone interaction pattern. Pressing BACK from
        //  the player returns to this phone-mode channel list.
        // ═══════════════════════════════════════════════════════════════
        if (phoneMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF06080F)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    AccentIndigo.copy(alpha = 0.18f),
                                    Color(0xFF06080F),
                                    Color(0xFF06080F)
                                )
                            )
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 520.dp)
                    ) {
                        // ── Premium Phone Top Nav (gradient glassmorphic) ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Back button
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { (context as? ComponentActivity)?.finish() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // Brand title with gradient accent
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Silina",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    "TV",
                                    color = AccentCyan,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            // Right-side action buttons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Search toggle
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.1f))
                                        .clickable { showSearchBar = !showSearchBar },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                // Toggle back to TV Mode (premium AccentIndigo Tv icon)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(listOf(AccentIndigo, AccentCyan))
                                        )
                                        .clickable {
                                            phoneMode = false
                                            (context as? ComponentActivity)?.requestedOrientation =
                                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Tv, contentDescription = "Switch to TV Mode", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // ── Optional Search Bar (phone variant, premium glassmorphic) ──
                        if (showSearchBar) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = SlateTextMuted, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Text("CLEAR", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { searchQuery = "" })
                                    }
                                }
                            }
                        }

                        // ── Scrollable Categories Bar (horizontal drag-scroll) ──
                        // Premium pill design — gradient on selected, subtle glass on unselected.
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            items(listOf("LIVE", "MOVIES", "SERIES", "FAVORITES")) { cat ->
                                val selected = selectedCategory == cat
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (selected) Brush.horizontalGradient(listOf(AccentIndigo, AccentCyan))
                                            else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.06f)))
                                        )
                                        .clickable {
                                            selectedCategory = cat
                                            selectedGroup = "All"
                                            searchQuery = ""
                                            showSearchBar = false
                                        }
                                        .padding(horizontal = 20.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        cat,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // ── Scrollable Groups Bar ──
                        if (effectiveGroups.size > 1) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                items(effectiveGroups) { grp ->
                                    val selected = selectedGroup == grp
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (selected) AccentCyan.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.07f))
                                            .clickable { selectedGroup = grp }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            grp,
                                            color = if (selected) AccentCyan else SlateTextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        // ═══════════════════════════════════════════════════════════════
                        //  FIXED HERO BANNER (sticky at top — does NOT scroll away)
                        //  ═══════════════════════════════════════════════════════════════
                        //  The hero banner is placed OUTSIDE the LazyColumn so it stays
                        //  fixed at the top while the channel rows scroll underneath.
                        //  It persists across category switches (as long as the new
                        //  category has streams).
                        // ═══════════════════════════════════════════════════════════════
                        val phonePagingEmpty = usePaging && !useFallback && pagedItems?.itemCount == 0 &&
                            pagedItems?.loadState?.refresh is LoadState.NotLoading
                        val phoneLegacyEmpty = (!usePaging || useFallback) && filteredStreams.isEmpty()

                        // Shared click handler — SERIES opens episode dialog, else
                        // unified mini-player (same GlobalPlaybackCoordinator as TV mode).
                        // Defined once here to avoid duplicating the series-detection logic
                        // across every card in the Netflix-style rows + flat list + hero.
                        //
                        // UNIFIED VIDEO PLAYER: phone mode uses the SAME coordinator
                        // singleton as TV mode — shared ExoPlayer, shared buffer, resume
                        // bridge to PlayerActivity. Tapping a card loads the stream into
                        // the coordinator and shows the PhoneMiniPlayer overlay; tapping
                        // the overlay's Expand button launches PlayerActivity with the
                        // resume bridge (FROM_FEED + FEED_URL) so the buffered stream
                        // resumes with zero black screen.
                        val onPhoneStreamClick: (com.agon.app.data.model.StreamItem) -> Unit = { stream ->
                            if (selectedCategory == "SERIES" && SessionData.playlistType == PlaylistType.XTREAM_CODES) {
                                val seriesId = stream.url.substringAfterLast("/")
                                selectedSeriesName = stream.name
                                selectedSeriesId = seriesId
                                selectedSeriesCover = stream.logo
                                isLoadingEpisodes = true
                                showEpisodeDialog = true
                                scope.launch {
                                    episodeList = PlaylistRepository.getSeriesEpisodes(seriesId)
                                    isLoadingEpisodes = false
                                }
                            } else {
                                // Load into the unified coordinator + show phone mini-player.
                                SessionData.currentSurferStreams = streams
                                phoneMiniPlayerStream = stream
                                phoneMiniPlayerUrl = stream.url
                                phoneMiniPlayerName = stream.name
                                GlobalPlaybackCoordinator.playStream(stream.url)
                            }
                        }

                        // ── Fixed hero banner (only when browsing "All" without search) ──
                        // The banner shows the channel logo by default. When the user taps
                        // it, the unified coordinator starts playback AND [phoneMiniPlayerUrl]
                        // is set — the banner then renders the live ExoPlayer video surface
                        // INSTEAD of the still image. A second tap expands to the full-screen
                        // PlayerActivity (resume bridge). This gives the hero banner the
                        // "tap to preview → tap again to go full screen" pattern from
                        // Netflix / Disney+ / Prime Video.
                        if (searchQuery.isEmpty() && selectedGroup == "All" && streams.isNotEmpty()) {
                            PhoneHeroBanner(
                                streams = streams,
                                exoPlayer = miniExoPlayer,
                                activeStreamUrl = phoneMiniPlayerUrl,
                                onStreamClick = onPhoneStreamClick,
                                onExpand = {
                                    // Second tap on the hero (while video is playing) →
                                    // expand to the full-screen PlayerActivity via the
                                    // resume bridge (coordinator already hot, zero black
                                    // screen).
                                    val s = phoneMiniPlayerStream
                                    if (s != null) {
                                        miniExoPlayer.pause()
                                        // Detach the hero surface so it cannot race
                                        // PlayerActivity's fresh surface (video-freeze fix).
                                        miniExoPlayer.clearVideoSurface()
                                        context.startActivity(
                                            Intent(context, PlayerActivity::class.java).apply {
                                                putExtra("STREAM_URL", s.url)
                                                putExtra("STREAM_NAME", s.name)
                                                putExtra("FROM_FEED", true)
                                                putExtra("FEED_URL", s.url)
                                            }
                                        )
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        if (phonePagingEmpty || phoneLegacyEmpty) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (searchQuery.isNotEmpty()) "No channels match \"$searchQuery\""
                                    else if (selectedGroup != "All") "No channels in \"$selectedGroup\""
                                    else "No channels available",
                                    color = SlateTextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            // ═══════════════════════════════════════════════════════════════
                            //  NETFLIX-STYLE CONTENT (horizontal rows by group)
                            //  ═══════════════════════════════════════════════════════════════
                            //  When browsing "All" without search, the content is organized
                            //  into horizontal LazyRow sliders — one per group — just like
                            //  Netflix / Disney+ / Shahid home screens. Each row has a
                            //  section header + a horizontally-scrollable strip of poster
                            //  cards. The user swipes left/right to browse within a row,
                            //  and scrolls vertically to move between rows.
                            //
                            //  When searching or a specific group is selected, we fall back
                            //  to a flat vertical list of filtered results (the rows pattern
                            //  doesn't make sense for a single filtered result set).
                            // ═══════════════════════════════════════════════════════════════
                            val showNetflixRows = searchQuery.isEmpty() && selectedGroup == "All" && streams.isNotEmpty()

                            if (showNetflixRows) {
                                // Build grouped slices for the Netflix-style rows.
                                // Top 8 groups (by count) each get a horizontal row, plus a
                                // "Trending" row at the top (first 20 items) and an "All
                                // Channels" row at the bottom.
                                val groupedRows = remember(streams, selectedCategory) {
                                    val byGroup = streams.groupBy { it.group }
                                        .filter { it.key.isNotEmpty() }
                                        .toList()
                                        .sortedByDescending { it.second.size }
                                        .take(8)
                                    buildList {
                                        // Trending row (first 20 channels with logos)
                                        val trending = streams.filter { it.logo.isNotEmpty() }.take(20)
                                        if (trending.isNotEmpty()) {
                                            add("Trending Now" to trending)
                                        }
                                        // Per-group rows
                                        addAll(byGroup)
                                        // All channels row (first 40)
                                        if (streams.size > 20) {
                                            add("All Channels" to streams.take(40))
                                        }
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(groupedRows.size, key = { index -> groupedRows[index].first }) { index ->
                                        val (rowTitle, rowStreams) = groupedRows[index]
                                        PhoneChannelRow(
                                            title = rowTitle,
                                            streams = rowStreams,
                                            onStreamClick = onPhoneStreamClick,
                                            isFavorite = { url -> favoriteUrls.contains(url) },
                                            onFavoriteClick = { url -> scope.launch { favManager.toggleFavorite(url) } }
                                        )
                                        // ═══════════════════════════════════════════════════════
                                        //  V8.3 — NATIVE AD REMOVED from phone Netflix-style rows.
                                        //  Previously a full-width NativeAdCard was injected after
                                        //  every 2 rows; the slot is now gone and only content
                                        //  rows render.
                                        // ═══════════════════════════════════════════════════════
                                    }
                                }
                            } else {
                                // ── Flat vertical list (search / filtered results) ──
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    if (usePaging && !useFallback && pagedItems != null) {
                                        // ═══════════════════════════════════════════════════════
                                        //  V8.3 — NATIVE AD REMOVED (phone flat list paging)
                                        //  ═══════════════════════════════════════════════════════
                                        items(pagedItems.itemCount, key = { index ->
                                            val url = pagedItems[index]?.url
                                            if (url.isNullOrEmpty()) "phone_ph_$index" else "${url}_$index"
                                        }) { index ->
                                            val stream = pagedItems[index] ?: return@items
                                            PhoneChannelCard(
                                                stream = stream,
                                                isFavorite = { favoriteUrls.contains(stream.url) },
                                                onFavoriteClick = { scope.launch { favManager.toggleFavorite(stream.url) } },
                                                onClick = { onPhoneStreamClick(stream) }
                                            )
                                        }
                                    } else {
                                        // ═══════════════════════════════════════════════════════
                                        //  V8.3 — NATIVE AD REMOVED (phone flat list fallback)
                                        //  ═══════════════════════════════════════════════════════
                                        items(filteredStreams.size, key = { index ->
                                            val s = filteredStreams.getOrNull(index)
                                            val url = s?.url ?: ""
                                            if (url.isEmpty()) "phone_empty_${index}" else "phone_${url}_${index}"
                                        }) { index ->
                                            val stream = filteredStreams.getOrNull(index) ?: return@items
                                            PhoneChannelCard(
                                                stream = stream,
                                                isFavorite = { favoriteUrls.contains(stream.url) },
                                                onFavoriteClick = { scope.launch { favManager.toggleFavorite(stream.url) } },
                                                onClick = { onPhoneStreamClick(stream) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  PHONE MODE MINI-PLAYER OVERLAY — Unified Video Player (bottom)
        //  ═══════════════════════════════════════════════════════════════
        //  When a stream is playing in phone mode ([phoneMiniPlayerStream]
        //  != null), this overlay renders at the BOTTOM of the screen
        //  with a 16:9 video preview + stream name + Expand/Close buttons.
        //  It uses the SAME GlobalPlaybackCoordinator singleton as TV
        //  mode — shared buffer, resume bridge to PlayerActivity.
        //
        //  Tap Expand → PlayerActivity full screen (resume bridge).
        //  Tap Close → stop playback, clear overlay.
        //
        //  The hero banner at the top shows the video inline when its
        //  stream is the active one, so the user sees the video in two
        //  places: the hero (top) and this mini-player (bottom). This
        //  matches the user's requirement: "tap to play video, tap again
        //  to open the same player in the split interface — always use
        //  the unified video player."
        // ═══════════════════════════════════════════════════════════════
        if (phoneMode && phoneMiniPlayerStream != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                PhoneMiniPlayer(
                    exoPlayer = miniExoPlayer,
                    streamName = phoneMiniPlayerName,
                    onExpand = {
                        // Resume bridge — coordinator already hot, zero black screen.
                        val s = phoneMiniPlayerStream
                        if (s != null) {
                            miniExoPlayer.pause()
                            // Detach the mini-player surface so it cannot race
                            // PlayerActivity's fresh surface (video-freeze fix).
                            miniExoPlayer.clearVideoSurface()
                            context.startActivity(
                                Intent(context, PlayerActivity::class.java).apply {
                                    putExtra("STREAM_URL", s.url)
                                    putExtra("STREAM_NAME", s.name)
                                    putExtra("FROM_FEED", true)
                                    putExtra("FEED_URL", s.url)
                                }
                            )
                        }
                    },
                    onClose = {
                        miniExoPlayer.pause()
                        miniExoPlayer.stop()
                        phoneMiniPlayerStream = null
                        phoneMiniPlayerUrl = ""
                        phoneMiniPlayerName = ""
                    }
                )
            }
        }

        // 2. DIALOGS (Drawn Last -> Top Layer)
        // ══════════════════════════════════════════════════════════
        // ══════════════════════════════════════════════════════════
        // LIVE SPORTS HARVESTER MODAL — Glassmorphic matches dialog
        // ══════════════════════════════════════════════════════════
        if (showMatchesModal) {
            LiveSportsMatchesModal(
                state = matchesState,
                onDismiss = { showMatchesModal = false },
                onPlayMatch = { streamItem ->
                    showMatchesModal = false
                    // Launch PlayerActivity with the matched channel's URL
                    // and name — same Intent contract as a normal channel tap.
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java).apply {
                            putExtra("STREAM_URL", streamItem.url)
                            putExtra("STREAM_NAME", streamItem.name)
                        }
                    )
                },
                onRetry = {
                    matchesState = MatchHarvesterRepository.HarvestResult.Loading
                    scope.launch {
                        val result = try {
                            val matches = MatchHarvesterRepository.getMatchesFromCache(context)
                            MatchHarvesterRepository.HarvestResult.Loaded(matches)
                        } catch (t: Throwable) {
                            MatchHarvesterRepository.HarvestResult.Failed(
                                t.message ?: "Failed to load matches"
                            )
                        }
                        matchesState = result
                        matchesLastFetchedMs = System.currentTimeMillis()
                    }
                }
            )
        }

        // ══════════════════════════════════════════════════════════
        // PIN SETUP DIALOG (Create new PIN)
        // ══════════════════════════════════════════════════════════
        if (showPinSetup) {
            // Focus requester — auto-shows soft keyboard when dialog opens
            val pinFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            // Auto-request focus on the hidden text field so the soft keyboard appears
            LaunchedEffect(showPinSetup, pinSetupStep) {
                if (showPinSetup) {
                    kotlinx.coroutines.delay(150)
                    try { pinFocusRequester.requestFocus() } catch (_: Exception) {}
                }
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = false) { })
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                color = SlateSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateBorderLight)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (pinSetupStep == "create") "Create Parental PIN" else "Confirm PIN", color = AccentIndigoLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text(if (pinSetupStep == "create") "Enter a 4-digit PIN to lock groups" else "Re-enter the 4-digit PIN to confirm", color = SlateTextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))

                    // Hidden BasicTextField — receives digits from the soft keyboard.
                    // Visually we render 4 dots below; this field is 1px thin and transparent.
                    androidx.compose.foundation.text.BasicTextField(
                        value = pinInput,
                        onValueChange = { v ->
                            // Only allow digits, max 4
                            val digits = v.filter { it.isDigit() }.take(4)
                            pinInput = digits
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.Transparent,
                            fontSize = 1.sp
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        modifier = Modifier
                            .size(width = 1.dp, height = 1.dp)
                            .focusRequester(pinFocusRequester)
                    )

                    // Visual PIN dots — display state of pinInput
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) { i ->
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(SlateSurfaceAlt).border(1.dp, if (pinInput.length > i) AccentIndigo else SlateBorderLight, CircleShape), contentAlignment = Alignment.Center) {
                                if (pinInput.length > i) Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AccentIndigoLight))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tap a dot to type", color = SlateTextMuted, fontSize = 9.sp)

                    if (pinError.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(pinError, color = Color(0xFFFF5252), fontSize = 12.sp) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { showPinSetup = false; pinInput = ""; pinError = "" }, color = SlateSurfaceAlt, shape = RoundedCornerShape(6.dp)) {
                            Text("Cancel", color = SlateTextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp)
                        }
                        Surface(onClick = {
                            scope.launch {
                                if (pinInput.length != 4) { pinError = "PIN must be 4 digits"; return@launch }
                                if (!pinInput.all { it.isDigit() }) { pinError = "PIN must be numbers only"; return@launch }
                                if (pinSetupStep == "create") {
                                    pinFirstInput = pinInput; pinSetupStep = "confirm"; pinInput = ""; pinError = ""
                                } else {
                                    if (pinInput == pinFirstInput) {
                                        pinManager.setPin(context, pinInput)
                                        if (pendingLockGroup.isNotEmpty()) {
                                            pinManager.lockGroup(context, pendingLockGroup)
                                            lockedGroups = pinManager.getLockedGroups(context)
                                            pendingLockGroup = ""
                                        }
                                        showPinSetup = false; pinInput = ""
                                    }
                                    else { pinError = "PINs do not match"; pinInput = ""; pinSetupStep = "create"; pinFirstInput = "" }
                                }
                            }
                        }, color = AccentIndigo, shape = RoundedCornerShape(6.dp)) {
                            Text("OK", color = Color(0xFF111111), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                }
            }
        }  // closes if (showPinSetup)

        // ══════════════════════════════════════════════════════════
        // PIN ENTRY DIALOG (Unlock group)
        // ══════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════
        //  ANDROID TV FOCUS REFACTOR — D-Pad / Remote Control
        // ═══════════════════════════════════════════════════════════════
        //  Defect fixed: "no visible focus indicator on PIN boxes; ordinary
        //  TextField doesn't respond to D-Pad."
        //
        //  Root cause: the legacy implementation used a SINGLE hidden
        //  BasicTextField with 4 decorative dot boxes that had no
        //  focusable() modifier. The D-Pad couldn't land on any box, and
        //  the user had no visual cue which box was active.
        //
        //  Fix applied (additive — pinInput, pinManager.verifyPin, and all
        //  business logic preserved verbatim):
        //
        //    1. The 4 dot boxes are now each [focusable] with their own
        //       [FocusRequester]. The D-Pad can land on each box
        //       individually.
        //
        //    2. Each box uses [onFocusChanged] to track its isFocused
        //       state and renders a bright [AccentCyan] border when
        //       active — the visible focus indicator required by the spec.
        //
        //    3. Each box installs an [onKeyEvent] handler that captures
        //       digit keys (0-9 + NumPad0-9) and the Backspace key.
        //       On digit entry, [pinInput] is appended and
        //       [focusManager.moveFocus(FocusDirection.Next)] advances
        //       focus to the next box automatically.
        //
        //    4. The first box auto-requests focus via [LaunchedEffect]
        //       when the dialog opens, so the D-Pad lands inside the
        //       PIN grid immediately.
        //
        //    5. The hidden BasicTextField is kept as a fallback for
        //       touch-screen devices (where the soft keyboard is the
        //       primary input). It is no longer the primary input path
        //       on TV — the per-box onKeyEvent handlers are.
        // ═══════════════════════════════════════════════════════════════
        if (showPinEntry) {
            val pinEntryFocusRequester = remember { FocusRequester() }
            // Per-box FocusRequesters so we can programmatically move
            // focus between the 4 PIN boxes on digit entry.
            val pinBoxFocusRequesters = remember {
                List(4) { FocusRequester() }
            }
            // Per-box isFocused state — drives the visible border color.
            val pinBoxFocused = remember {
                mutableStateListOf(false, false, false, false)
            }
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

            LaunchedEffect(showPinEntry) {
                if (showPinEntry) {
                    kotlinx.coroutines.delay(150)
                    // Land focus on the FIRST PIN box so the D-Pad has an
                    // anchor inside the dialog immediately.
                    runCatching { pinBoxFocusRequesters[0].requestFocus() }
                }
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { showPinEntry = false; pinInput = ""; pinError = "" })
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                color = SlateSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateBorderLight)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\uD83D\uDD12", fontSize = 28.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Locked Group", color = AccentIndigoLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(pinEntryTarget, color = SlateTextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    Spacer(Modifier.height(16.dp))

                    // Hidden BasicTextField — kept as a soft-keyboard fallback
                    // for touch devices. On TV, the per-box onKeyEvent handlers
                    // below are the primary input path.
                    androidx.compose.foundation.text.BasicTextField(
                        value = pinInput,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            pinInput = digits
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.Transparent,
                            fontSize = 1.sp
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        modifier = Modifier
                            .size(width = 1.dp, height = 1.dp)
                            .focusRequester(pinEntryFocusRequester)
                    )

                    // ── 4 FOCUSABLE PIN BOXES ──
                    // Each box is focusable, shows a bright border when
                    // focused, and captures digit keys to update pinInput.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) { i ->
                            val isFilled = pinInput.length > i
                            val isActive = pinInput.length == i // next-to-fill
                            val isBoxFocused = pinBoxFocused[i]
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    // Attach this box's FocusRequester so the
                                    // LaunchedEffect + moveFocus calls can
                                    // programmatically target it.
                                    .focusRequester(pinBoxFocusRequesters[i])
                                    .onFocusChanged { state ->
                                        pinBoxFocused[i] = state.isFocused
                                    }
                                    // Capture digit keys (0-9, NumPad0-9) and
                                    // Backspace. On digit entry, append to
                                    // pinInput and advance focus to the next
                                    // box. On Backspace, delete the last digit
                                    // and move focus back.
                                    .onKeyEvent { event ->
                                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                                            val digit = when (event.key) {
                                                androidx.compose.ui.input.key.Key.Zero, androidx.compose.ui.input.key.Key.NumPad0 -> "0"
                                                androidx.compose.ui.input.key.Key.One, androidx.compose.ui.input.key.Key.NumPad1 -> "1"
                                                androidx.compose.ui.input.key.Key.Two, androidx.compose.ui.input.key.Key.NumPad2 -> "2"
                                                androidx.compose.ui.input.key.Key.Three, androidx.compose.ui.input.key.Key.NumPad3 -> "3"
                                                androidx.compose.ui.input.key.Key.Four, androidx.compose.ui.input.key.Key.NumPad4 -> "4"
                                                androidx.compose.ui.input.key.Key.Five, androidx.compose.ui.input.key.Key.NumPad5 -> "5"
                                                androidx.compose.ui.input.key.Key.Six, androidx.compose.ui.input.key.Key.NumPad6 -> "6"
                                                androidx.compose.ui.input.key.Key.Seven, androidx.compose.ui.input.key.Key.NumPad7 -> "7"
                                                androidx.compose.ui.input.key.Key.Eight, androidx.compose.ui.input.key.Key.NumPad8 -> "8"
                                                androidx.compose.ui.input.key.Key.Nine, androidx.compose.ui.input.key.Key.NumPad9 -> "9"
                                                else -> null
                                            }
                                            if (digit != null && pinInput.length < 4) {
                                                pinInput = pinInput + digit
                                                // Auto-advance focus to the next box.
                                                if (i < 3) {
                                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                                                }
                                                true
                                            } else if (event.key == androidx.compose.ui.input.key.Key.Back && pinInput.isNotEmpty()) {
                                                pinInput = pinInput.dropLast(1)
                                                if (i > 0) {
                                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Previous)
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                    .focusable()
                                    .background(SlateSurfaceAlt)
                                    .border(
                                        width = if (isBoxFocused) 2.5.dp else 1.dp,
                                        color = when {
                                            isBoxFocused -> AccentCyan
                                            isFilled -> AccentIndigo
                                            isActive -> AccentIndigoLight.copy(alpha = 0.5f)
                                            else -> SlateBorderLight
                                        },
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isFilled) {
                                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(AccentIndigoLight))
                                }
                            }
                        }
                    }
                    if (pinError.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(pinError, color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { showPinEntry = false; pinInput = ""; pinError = "" }, color = SlateSurfaceAlt, shape = RoundedCornerShape(6.dp)) {
                            Text("Cancel", color = SlateTextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp)
                        }
                        Surface(onClick = {
                            scope.launch {
                                if (pinInput.length != 4) { pinError = "Enter 4 digits"; return@launch }
                                if (pinManager.verifyPin(context, pinInput)) {
                                    sessionUnlocked = sessionUnlocked + pinEntryTarget
                                    selectedGroup = pinEntryTarget
                                    showPinEntry = false; pinInput = ""
                                } else { pinError = "Access Denied"; pinInput = "" }
                            }
                        }, color = AccentIndigo, shape = RoundedCornerShape(6.dp)) {
                            Text("Unlock", color = Color(0xFF111111), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                }
            }
        }  // closes if (showPinEntry)

        // ══════════════════════════════════════════════════════════
        // EPISODE SELECTOR DIALOG (Series playback)
        // ══════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════
        //  ANDROID TV FOCUS REFACTOR — D-Pad / Remote Control
        // ═══════════════════════════════════════════════════════════════
        //  Defect fixed: "when the Episodes popup appears, focus stays
        //  trapped in the dimmed background, or escapes from the popup."
        //
        //  Root cause: the dialog had no explicit FocusRequester on its
        //  first focusable child, so Compose's default focus traversal
        //  kept the focus on whatever was focused before the dialog
        //  opened (the background grid). Pressing the D-Pad then either
        //  did nothing (because the background was covered by the
        //  dialog's scrim) or jumped to an unrelated element outside
        //  the dialog.
        //
        //  Fix applied (additive — episode data + playback Intent
        //  unchanged):
        //
        //    1. A single [FocusRequester] is created per dialog open and
        //       attached to the FIRST episode Surface in the LazyColumn.
        //
        //    2. A [LaunchedEffect(Unit)] requests focus on that first
        //       episode the instant the dialog's composition commits —
        //       this forces the D-Pad anchor inside the popup.
        //
        //    3. The [DialogProperties] already specifies
        //       `usePlatformDefaultWidth = false` (correct). The dialog's
        //       root Box also installs a `focusProperties { canFocus = false }`
        //       so the container itself never steals focus from the
        //       episodes inside it.
        //
        //    4. The existing [BackHandler] below already intercepts the
        //       BACK key and closes the dialog safely.
        // ═══════════════════════════════════════════════════════════════
        if (showEpisodeDialog) {
            // FocusRequester for the first episode — used by the
            // LaunchedEffect below to force the D-Pad anchor inside the
            // popup the instant it appears.
            val firstEpisodeFocusRequester = remember { FocusRequester() }

            Dialog(
                onDismissRequest = { showEpisodeDialog = false; episodeList = emptyList() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                // Auto-focus the first episode once the dialog's composition
                // has committed. The 100ms delay gives the LazyColumn time
                // to lay out its first item before we ask the focus system
                // to find the FocusRequester.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(100)
                    runCatching { firstEpisodeFocusRequester.requestFocus() }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        // The container itself is NOT focusable — focus
                        // always lands on the episodes inside, never on
                        // the scrim.
                        .focusProperties { canFocus = false }
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .clickable { showEpisodeDialog = false; episodeList = emptyList() }
                    )
                    Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 440.dp)
                        .heightIn(min = 200.dp, max = 520.dp),
                    color = Color(0xFF131419),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentIndigo)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            selectedSeriesName,
                            color = SlateTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${episodeList.size} episodes loaded",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(12.dp))

                        if (isLoadingEpisodes) {
                            Box(
                                Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AccentIndigo, strokeWidth = 3.dp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Loading episodes...", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                                }
                            }
                        } else if (episodeList.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No episodes found", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("This series may not have episode data available", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                var currentSeason = -1
                                items(episodeList) { episode ->
                                    if (episode.season != currentSeason) {
                                        currentSeason = episode.season
                                        Text(
                                            "Season ${currentSeason}",
                                            color = AccentIndigoLight,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                        )
                                    }
                                    val episodeInteractionSource = remember { MutableInteractionSource() }
                                    val isEpisodeFocused by episodeInteractionSource.collectIsFocusedAsState()
                                    val episodeIndex = episodeList.indexOf(episode)
                                    Surface(
                                        onClick = {
                                            val streamUrl = "${SessionData.xtreamBaseUrl}/series/${SessionData.xtreamUsername}/${SessionData.xtreamPassword}/${episode.id}.${episode.containerExtension}"
                                            // Convert episodes to StreamItem format so the player can display them in the mini-surfer
                                            SessionData.currentSurferStreams = episodeList.map { ep ->
                                                StreamItem(
                                                    url = "${SessionData.xtreamBaseUrl}/series/${SessionData.xtreamUsername}/${SessionData.xtreamPassword}/${ep.id}.${ep.containerExtension}",
                                                    name = ep.title ?: "Episode",
                                                    logo = "",
                                                    group = "Series",
                                                    epgChannelId = ""
                                                )
                                            }
                                            context.startActivity(
                                                Intent(context, PlayerActivity::class.java).apply {
                                                    putExtra("STREAM_URL", streamUrl)
                                                    putExtra("STREAM_NAME", "${selectedSeriesName} - S${episode.season}E${episode.episodeNum}")
                                                }
                                            )
                                            showEpisodeDialog = false
                                            episodeList = emptyList()
                                        },
                                        color = if (isEpisodeFocused) Color(0xFF2A2C36) else Color(0xFF171822),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            // Attach the first-episode FocusRequester
                                            // so the LaunchedEffect above can land the
                                            // D-Pad on it the instant the dialog opens.
                                            .then(
                                                if (episodeIndex == 0) Modifier.focusRequester(firstEpisodeFocusRequester)
                                                else Modifier
                                            )
                                            .focusable(interactionSource = episodeInteractionSource)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (selectedSeriesCover.isNotEmpty()) {
                                                SubcomposeAsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(selectedSeriesCover)
                                                        .size(120, 80)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .width(64.dp)
                                                        .height(42.dp)
                                                        .clip(RoundedCornerShape(4.dp)),
                                                    contentScale = ContentScale.Crop,
                                                    error = {
                                                        LogoPlaceholder(
                                                            Modifier.width(64.dp).height(42.dp)
                                                                .clip(RoundedCornerShape(4.dp))
                                                        )
                                                    }
                                                )
                                            } else {
                                                LogoPlaceholder(
                                                    Modifier.width(64.dp).height(42.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "S${episode.season} E${episode.episodeNum}",
                                                    color = if (isEpisodeFocused) AccentIndigoLight else Color.White.copy(alpha = 0.7f),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    episode.title,
                                                    color = if (isEpisodeFocused) Color.White else Color.White.copy(alpha = 0.8f),
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                onClick = { showEpisodeDialog = false; episodeList = emptyList() },
                                color = Color(0xFF2A2C36),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "Close",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                }
            }
        }  // closes if (showEpisodeDialog)

        // 3. SETTINGS OVERLAY (Full-screen on top of everything)
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050505))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Settings Top Bar with Back button (compact) ──
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SlateSurface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Compact Back button — small size, no large icon container
                            TopNavIcon(
                                icon = Icons.Default.ArrowBack,
                                label = "Back",
                                onClick = { showSettings = false }
                            )
                            Text(
                                "Settings",
                                color = SlateTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                    SettingsScreen()
                }
            }
        }

        }  // <-- Closes Root Box
    }  // closes CompositionLocalProvider
}  // closes CompactDashboardScreen

// ══════════════════════════════════════════════════════════════════════
// MINI-PLAYER + EPG STRIP
// Bottom strip of the dashboard — shows the currently selected stream in a
// small 16:9 player on the left and an EPG details panel on the right.
// Expand button opens PlayerActivity in full screen.
// Close button stops playback and hides the strip.
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun MiniPlayerEpgStrip(
    exoPlayer: ExoPlayer,
    streamName: String,
    currentClock: Long,
    epgData: PlaylistRepository.LiveEpgData?,
    epgProgress: Float,
    expandFocusRequester: FocusRequester,
    closeFocusRequester: FocusRequester,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    // ═══════════════════════════════════════════════════════════════
    //  ANDROID TV FOCUS REFACTOR — D-Pad / Remote Control
    // ═══════════════════════════════════════════════════════════════
    //  Defect fixed: "user can't navigate from the channel list to the
    //  floating player buttons (Expand, Close) in the corner."
    //
    //  Root cause: the mini-player's Expand and Close buttons were
    //  focusable but had no explicit spatial exit / entry mapping, so
    //  the D-Pad's nearest-neighbour algorithm often picked a channel
    //  card far away instead of the buttons when the user pressed DOWN
    //  from the channel grid.
    //
    //  Fix applied (additive — playback, EPG, and layout unchanged):
    //
    //    1. The mini-player's root Box installs [focusProperties] with
    //       `canFocus = false` so the container never steals focus —
    //       the D-Pad always lands on a BUTTON inside, not on the
    //       glassmorphic panel itself.
    //
    //    2. Each button (Expand / Close) installs an explicit
    //       [focusProperties] block with `enter = ...` mapping so that
    //       pressing DOWN from the channel grid lands deterministically
    //       on the Expand button (the leftmost button in the row).
    //
    //    3. The Close button's `exit = ...` mapping sends the focus
    //       back to the channel grid when the user presses UP from the
    //       button row — preventing the focus from getting stuck inside
    //       the mini-player after the user dismisses it.
    //
    //    4. Both buttons have [onKeyEvent] handlers for the OK / D-Pad
    //       Center key so the remote's primary action button triggers
    //       the onClick — this is the same fix applied to the settings
    //       toggles.
    // ═══════════════════════════════════════════════════════════════

    // ── Glassmorphic Mini-Player + EPG Panel (Deep Space 2026) ──
    // NOTE: expandFocusRequester and closeFocusRequester are now passed
    // as parameters from the parent composable so the channel grid can
    // reference them in its `focusProperties { down = ... }` block.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .glassmorphicPanel(cornerRadius = 16)
            // Container is NOT focusable — focus lands on the buttons
            // inside, never on the panel itself.
            .focusProperties { canFocus = false }
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // ── LEFT: 16:9 Mini-Player ──
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            keepScreenOn = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        if (view.player !== exoPlayer) view.player = exoPlayer
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                // "PREVIEW" label
                Text(
                    "PREVIEW",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            // ── MIDDLE: EPG details (expands) ──
            // Layout: [Header Row] [Scrollable EPG text area (weight 1f)] [Bottom-anchored buttons]
            // The text area takes only the remaining space (weight 1f) and never
            // pushes the button row out of bounds. The button row is always 100% visible.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ════════════════════════════════════════════════════════════════
                //  NEON BADGE + PROGRAM TITLE HEADER
                //  Replaces the old "NOW: {StreamName}" redundant text.
                //  Layout: [Neon "EPG" badge] [Program Title in AccentCyan] ... [clock]
                // ════════════════════════════════════════════════════════════════
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT: Neon Badge + Program Title
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── Pulsing Live Indicator (red dot) ──
                        // Animated pulse — scale 1.0 ↔ 1.3 infinitely.
                        val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .scale(pulseScale)
                                .clip(RoundedCornerShape(50))
                                .background(LiveRed)
                        )
                        // ── Neon "EPG" Badge ──
                        // Translucent Neon Green background (15% alpha) + 1dp Neon Green
                        // border + bold Neon Green text.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NeonGreen.copy(alpha = 0.15f))
                                .border(1.dp, NeonGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "EPG",
                                color = NeonGreenSoft,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                        // ── Program Title ──
                        // Bright AccentCyan so it stands out from plain white text.
                        // Falls back to "No Program Information" in muted text when null.
                        val programTitle = when {
                            epgData != null && epgData.title.isNotEmpty() -> epgData.title
                            epgData == null -> "No Program Information"
                            else -> "No Program Information"
                        }
                        val titleColor = when {
                            epgData != null && epgData.title.isNotEmpty() -> AccentCyan
                            else -> SlateTextMuted
                        }
                        Text(
                            programTitle,
                            color = titleColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    // RIGHT: Clock
                    Text(
                        formatDashboardClock(currentClock),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ── EPG TEXT AREA (flex-weighted: consumes only remaining space) ──
                // All texts are maxLines=1 + Ellipsis so they NEVER expand vertically
                // and NEVER push the button row below out of bounds.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Gradient progress bar — driven by real start/stop timestamps.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SlateBorderLight)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(epgProgress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentIndigo, AccentCyan)
                                    )
                                )
                        )
                    }

                    // Next program — maxLines=1, Ellipsis so it never wraps.
                    val nextLine = when {
                        epgData == null -> ""
                        epgData.nextTitle.isNotEmpty() -> {
                            val startFmt = formatDashboardClock(epgData.nextStartTimestamp)
                            "Next: $startFmt — ${epgData.nextTitle}"
                        }
                        else -> "Next: No upcoming program"
                    }
                    if (nextLine.isNotEmpty()) {
                        Text(
                            nextLine,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ════════════════════════════════════════════════════════════════
                //  BOTTOM-ANCHORED CAPSULE BUTTON ROW
                //  Pill-shaped (CircleShape) buttons with glassmorphic backgrounds
                //  (surface @ 20% alpha) + icons + glowing accent border on focus.
                // ════════════════════════════════════════════════════════════════
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ── Full Screen capsule button ──
                    var expandFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (expandFocused) AccentIndigo.copy(alpha = 0.30f)
                                else SlateSurface.copy(alpha = 0.20f)
                            )
                            .border(
                                width = if (expandFocused) 1.5.dp else 1.dp,
                                color = if (expandFocused) AccentIndigoLight
                                else Color.White.copy(alpha = 0.15f),
                                shape = CircleShape
                            )
                            // ── D-Pad focus entry point ──
                            // Pressing DOWN from the channel grid lands here.
                            .focusRequester(expandFocusRequester)
                            .focusProperties {
                                // When the user presses LEFT from the Close
                                // button, send focus to the Expand button
                                // (deterministic left-right flow inside the
                                // mini-player button row).
                                this.right = closeFocusRequester
                            }
                            .onKeyEvent { event ->
                                // OK / D-Pad Center triggers the expand action.
                                if (event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                                    onExpand()
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusable()
                            .onFocusChanged { expandFocused = it.isFocused }
                            .clickable(onClick = onExpand)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = null,
                                tint = if (expandFocused) AccentIndigoLight else SlateTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Full Screen",
                                color = if (expandFocused) AccentIndigoLight else SlateTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // ── Close capsule button ──
                    var closeFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (closeFocused) Color(0xFFFF5252).copy(alpha = 0.25f)
                                else SlateSurface.copy(alpha = 0.20f)
                            )
                            .border(
                                width = if (closeFocused) 1.5.dp else 1.dp,
                                color = if (closeFocused) Color(0xFFFF5252)
                                else Color.White.copy(alpha = 0.15f),
                                shape = CircleShape
                            )
                            // ── D-Pad focus wiring ──
                            .focusRequester(closeFocusRequester)
                            .focusProperties {
                                // LEFT from Close → Expand (deterministic).
                                this.left = expandFocusRequester
                            }
                            .onKeyEvent { event ->
                                // OK / D-Pad Center triggers the close action.
                                if (event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                                    onClose()
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusable()
                            .onFocusChanged { closeFocused = it.isFocused }
                            .clickable(onClick = onClose)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = if (closeFocused) Color(0xFFFF5252) else SlateTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Close",
                                color = if (closeFocused) Color.White else SlateTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDashboardClock(epochMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val h = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val m = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
    return "$h:$m"
}

// ══════════════════════════════════════════════════════════════════════
// TOP NAV TAB
// ══════════════════════════════════════════════════════════════════════

@Composable
fun TopNavTab(title: String, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Spring physics for premium feel.
    val targetScale = when {
        isPressed -> 0.96f
        isFocused -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, SmoothSpring, label = "navTabScale")

    Box(
        modifier = Modifier
            .scale(scale)
            .glassmorphicPill(focused = isFocused || isSelected)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            color = when {
                isSelected -> AccentCyan
                isFocused -> SlateTextPrimary
                else -> SlateTextSecondary
            },
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// TOP NAV ICON BUTTON (Search, SwapHoriz)
// ══════════════════════════════════════════════════════════════════════

@Composable
fun TopNavIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val targetScale = when {
        isPressed -> 0.94f
        isFocused -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, SmoothSpring, label = "navIconScale")

    // Icon-only button (no label text) for a slim top bar
    Box(
        modifier = Modifier
            .size(30.dp)
            .scale(scale)
            .glassmorphicPill(focused = isFocused)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isFocused) AccentCyan else SlateTextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// TOP NAV BACK ICON — compact icon-only Back button (no label).
// Used on the far left of the Channels Grid top nav to return to HubActivity.
// ══════════════════════════════════════════════════════════════════════

@Composable
fun TopNavBackIcon(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val targetScale = when {
        isPressed -> 0.94f
        isFocused -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, SmoothSpring, label = "navBackScale")

    Box(
        modifier = Modifier
            .size(30.dp)
            .scale(scale)
            .glassmorphicPill(focused = isFocused)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.ArrowBack,
            contentDescription = "Back to Main Menu",
            tint = if (isFocused) AccentCyan else SlateTextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// GROUP ITEM (Sidebar) — Deep Space 2026 glassmorphic
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupItem(
    name: String,
    count: Int = 0,
    isSelected: Boolean,
    isLocked: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: (String) -> Unit,
    onLongPress: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (isFocused) 1.04f else 1f,
        SmoothSpring,
        label = "groupScale"
    )

    // INSTANT FOCUS UPDATE: As soon as D-Pad focus lands on this group,
    // fire the onFocus callback so the grid updates without waiting for a click.
    LaunchedEffect(isFocused) {
        if (isFocused) onFocus()
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isSelected -> AccentIndigo.copy(alpha = 0.20f)
                    isFocused -> GlassSurface.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused || isSelected) 1.5.dp else 0.dp,
                color = when {
                    isSelected -> AccentIndigo
                    isFocused -> AccentIndigoLight
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onClick(name) },
                onLongClick = { onLongPress() }
            )
            .focusable(interactionSource = interactionSource)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                name,
                color = when {
                    isSelected -> AccentCyan
                    isFocused -> SlateTextPrimary
                    else -> SlateTextSecondary
                },
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                count.toString(),
                color = SlateTextMuted,
                fontSize = 9.sp
            )
            if (isLocked) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "\uD83D\uDD12",
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// COMPACT STREAM CARD — Glassmorphic 16:9 (Deep Space 2026)
// Glassmorphic background + spring scale on focus + glowing accent border.
// ══════════════════════════════════════════════════════════════════════

@Composable
fun CompactStreamCard(
    stream: StreamItem,
    isFavorite: () -> Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    isSelected: () -> Boolean = { false }
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    // ═══════════════════════════════════════════════════════════════
    //  LAMBDA STATE DEFERRING — Recomposition Storm Fix
    //  ═══════════════════════════════════════════════════════════════
    //  Instead of reading [isFavorite] and [isSelected] as Boolean
    //  values at the top of this composable (which would force EVERY
    //  card to recompose when the active stream / favorites change),
    //  we defer the read into the rendering lambdas below.
    //
    //  This means:
    //    - When the user taps a channel → only the OLD and NEW cards
    //      recompose (their isSelected() lambda returns a different
    //      value). The other 21,998 cards are untouched.
    //    - When the user toggles a favorite → only the affected card
    //      recomposes. The grid stays rock-stable.
    //
    //  The lambdas are cheap (a single Set.contains() or String
    //  comparison), so calling them inside the draw scope has zero
    //  measurable cost.
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  CONTENT CLASSIFICATION — Smart Learning Channels
    //  ═══════════════════════════════════════════════════════════════
    //  Classify the channel into LEARNING / NEWS / SPORTS / ENTERTAINMENT
    //  based on its name + group. The classification is cached per-URL
    //  so repeated calls (e.g. during scrolling) don't re-scan.
    //  The result powers the content-type badge (📚/📰/⚽/🎬) on the card.
    // ═══════════════════════════════════════════════════════════════
    val contentType = remember(stream.url) {
        com.agon.app.recommendation.ContentClassifier.classify(stream)
    }

    // Spring physics for natural feel — 1.05f on focus, 0.97f on press.
    val targetScale = when {
        isPressed -> 0.97f
        isFocused -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, PremiumSpring, label = "cardScale")

    Box(
        modifier = Modifier
            .scale(scale)
            .glassmorphicCard(cornerRadius = 14, focused = isFocused)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Image area: 16:9 aspect ratio (glassmorphic 2026) ──
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                // V4.3.1 FIX: Image loading issues fixed:
                // 1. Removed via.placeholder.com fallback (external service
                //    that was unreachable/slow, causing blank cards).
                //    Now uses a local gradient + initials placeholder.
                // 2. Removed .size(150,100) downsampling — let Coil load
                //    the full image so it's sharp on high-DPI screens.
                // 3. Added explicit error + loading states with gradient
                //    placeholders so the card never looks "broken".
                if (stream.logo.isNotEmpty()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(stream.logo)
                            .crossfade(true)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build(),
                        contentDescription = stream.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = { LogoPlaceholder(Modifier.fillMaxSize()) },
                        loading = {
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.horizontalGradient(
                                        listOf(AccentIndigo.copy(alpha = 0.2f), AccentCyan.copy(alpha = 0.1f))
                                    )
                                )
                            )
                        }
                    )
                } else {
                    // No logo URL — show the DOH brand logo placeholder
                    LogoPlaceholder(Modifier.fillMaxSize())
                }
                // Bottom gradient fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                )
                // ═══════════════════════════════════════════════════════════════
                //  CONTENT TYPE BADGE — Smart Learning Channels
                //  ═══════════════════════════════════════════════════════════════
                //  Small emoji badge in the TOP-LEFT corner showing the content
                //  type: 📚 (learning) / 📰 (news) / ⚽ (sports) / 🎬 (entertainment).
                //  Only shown for non-ENTERTAINMENT types (entertainment is the
                //  default — showing 🎬 on every card would be visual noise).
                // ═══════════════════════════════════════════════════════════════
                if (contentType != com.agon.app.recommendation.ContentClassifier.ContentType.ENTERTAINMENT) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            contentType.icon,
                            fontSize = 9.sp
                        )
                    }
                }
                // Favorite star — reads isFavorite() deferred so only
                // the affected card recomposes when favorites change.
                val fav = isFavorite()
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .padding(2.dp)
                ) {
                    Icon(
                        if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                        null,
                        tint = if (fav) AccentCyan else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                // Focus glow overlay (when card is focused)
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        AccentIndigo.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
                // ── Active selection glow — reads isSelected() deferred ──
                // When the user taps a channel, only THIS card recomposes
                // to show the selection ring. The grid stays stable.
                val selected = isSelected()
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        AccentCyan.copy(alpha = 0.12f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            }
            // ── Title area — channel name + selection indicator ──
            // Read isSelected() again here (cheap) so the text color
            // updates without forcing a full-card recompose.
            val selected = isSelected()
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    stream.name,
                    color = when {
                        selected -> AccentCyan
                        isFocused -> AccentCyan
                        else -> SlateTextPrimary
                    },
                    fontSize = 11.sp,
                    fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
// ═══════════════════════════════════════════════════════════════════════
// LIVE SPORTS HARVESTER — Glassmorphic Modal
//
// Renders today's matches in a Dialog with a glassy backdrop blur effect.
// Each match card shows: home team, away team, kickoff time, broadcaster,
// and (if matched) the local channel name with a pulsing green dot.
// Matched cards are clickable and launch PlayerActivity. Unmatched cards
// are grayed out and non-interactive.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LiveSportsMatchesModal(
    state: MatchHarvesterRepository.HarvestResult,
    onDismiss: () -> Unit,
    onPlayMatch: (com.agon.app.data.model.StreamItem) -> Unit,
    onRetry: () -> Unit
) {
    // Pulsing green "LIVE" dot — animates alpha between 0.3f and 1.0f.
    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val liveDotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner glassmorphic panel — stops click-through from dismissing.
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xE61A1A2E),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.08f)
                ),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}  // swallow clicks on the panel
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // ── Header ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SportsSoccer,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Today's Matches",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Live Sports Harvester",
                                    color = SlateTextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        // Close button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    // Inline divider (DashboardActivity doesn't share PlayerActivity's
                    // private HorizontalDividerLine composable).
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── Body — depends on state ──
                    when (state) {
                        MatchHarvesterRepository.HarvestResult.Idle -> {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Preparing…", color = SlateTextSecondary, fontSize = 13.sp)
                            }
                        }
                        MatchHarvesterRepository.HarvestResult.Loading -> {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = AccentCyan,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Harvesting today's fixtures…",
                                        color = SlateTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        is MatchHarvesterRepository.HarvestResult.Failed -> {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Text(
                                        "Failed to load matches",
                                        color = Color(0xFFFF5252),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        state.message,
                                        color = SlateTextSecondary,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AccentIndigo.copy(alpha = 0.6f))
                                            .clickable(onClick = onRetry)
                                            .padding(horizontal = 18.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Retry", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        is MatchHarvesterRepository.HarvestResult.Loaded -> {
                            if (state.matches.isEmpty()) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "No matches found for today",
                                            color = SlateTextSecondary,
                                            fontSize = 13.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Try again later",
                                            color = SlateTextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(state.matches) { match ->
                                        MatchCard(
                                            match = match,
                                            liveDotAlpha = liveDotAlpha,
                                            onClick = {
                                                match.matchedChannel?.let(onPlayMatch)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCard(
    match: MatchHarvesterRepository.HarvestedMatch,
    liveDotAlpha: Float,
    onClick: () -> Unit
) {
    val isMatched = match.isMatched
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // ═══ COMPACT CARD REDESIGN ═══
    // Previous version had 3 columns (kickoff / teams / matched-indicator)
    // each with 3 text lines + huge spacers → cards were ~80dp tall and
    // texts got clipped because the right column's widthIn(max=140dp)
    // left no room for the teams column.
    //
    // New layout: 2 rows inside a single Column.
    //   Row 1 (top):    [LIVE NOW ●]   [HomeTeam  ×  AwayTeam]      (one line)
    //   Row 2 (bottom): [Server Core]  [Broadcaster / Channel name] (one line)
    // Card is now ~56dp tall and every text has weight(1f) + ellipsis so
    // nothing gets clipped, regardless of screen width.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (!isMatched) Color.White.copy(alpha = 0.03f)
                else if (isFocused) AccentIndigo.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = if (isFocused && isMatched) 1.5.dp else 0.dp,
                color = if (isFocused && isMatched) AccentIndigoLight else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .then(
                if (isMatched) Modifier
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .focusable(interactionSource = interactionSource)
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val isLive = match.status == MatchHarvesterRepository.MatchStatus.LIVE
        val liveGreen = Color(0xFF00E676)
        val amberUpcoming = Color(0xFFFFC107)
        val statusColor = if (isLive) liveGreen else amberUpcoming

        // ── Row 1: Status badge + Teams ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status badge with pulsing dot — fixed width, no clipping
            Row(
                modifier = Modifier.width(80.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = liveDotAlpha))
                )
                Text(
                    match.kickoffTime,
                    color = statusColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Teams row: Home × Away — fills remaining width, ellipsises safely
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    match.homeTeam,
                    color = if (isMatched) Color.White else SlateTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (match.awayTeam.isNotBlank()) "×" else "",
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (match.awayTeam.isNotBlank()) {
                    Text(
                        match.awayTeam,
                        color = if (isMatched) Color.White else SlateTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Matched indicator — only if matched, compact
            if (isMatched) {
                Text(
                    "MATCHED",
                    color = liveGreen,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Row 2: Source + Broadcaster/Channel name ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Source label — fixed width 70dp aligned with badge above
            Text(
                match.source.ifBlank { "Server Core" },
                color = SlateTextMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.width(70.dp)
            )

            // Broadcaster / Channel name — fills remaining width safely
            if (isMatched) {
                Text(
                    match.matchedChannel?.name ?: match.broadcaster,
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    match.broadcaster.ifBlank { "Unknown broadcaster" },
                    color = SlateTextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// LIVE SPORTS BUTTON — Top-nav trigger with green pulse when matches live.
//
// Same 30dp size + glassmorphicPill styling as TopNavIcon so it slots
// seamlessly into the existing top bar. When hasLiveMatches=true, the
// icon turns green and a pulsing glow halo appears around the button —
// signalling to the user that live matches are detectable right now
// inside their server playlist (Server Core radar).
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LiveSportsButton(
    hasLiveMatches: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val targetScale = when {
        isPressed -> 0.94f
        isFocused -> 1.06f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, SmoothSpring, label = "sportsBtnScale")

    // Green pulse halo — alpha oscillates between 0.15 and 0.55 when live.
    val infiniteTransition = rememberInfiniteTransition(label = "sportsPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "sportsPulseAlpha"
    )

    // Color: green (#00E676) when live, AccentCyan otherwise (matches TopNavIcon focus colour).
    val iconTint = if (hasLiveMatches) Color(0xFF00E676) else SlateTextSecondary
    val focusTint = if (hasLiveMatches) Color(0xFF00E676) else AccentCyan

    Box(
        modifier = Modifier
            .size(30.dp)
            .scale(scale)
            // Green pulse glow halo when live matches are detected.
            .then(
                if (hasLiveMatches) Modifier.background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00E676).copy(alpha = pulseAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
                else Modifier
            )
            .glassmorphicPill(focused = isFocused || hasLiveMatches)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.SportsSoccer,
            contentDescription = "Today's Matches",
            tint = if (isFocused) focusTint else iconTint,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// RECOMMENDATION CARD — Smart Zapping UI element
// Shows a channel recommended by WatchHistoryManager with a match %.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun RecommendationCard(
    stream: com.agon.app.data.model.StreamItem,
    matchPercent: Int,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) AccentIndigo.copy(alpha = 0.3f) else GlassSurface.copy(alpha = 0.4f))
            .border(1.dp, if (isFocused) AccentCyan else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (stream.logo.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = stream.logo,
                    contentDescription = stream.name,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                    error = painterResource(R.drawable.ic_logo)
                )
            } else {
                LogoPlaceholder(Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stream.name,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                "$matchPercent% match",
                color = AccentCyan,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// TIME MACHINE CARD — Past program replay UI element
// Shows a program that already aired and can be re-watched via catch-up.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun TimeMachineCard(
    program: com.agon.app.timemachine.TimeMachineEngine.PastProgram,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color(0xFFFFC107).copy(alpha = 0.25f) else GlassSurface.copy(alpha = 0.4f))
            .border(1.dp, if (isFocused) Color(0xFFFFC107) else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Refresh, // reuse as "replay" icon
                contentDescription = "Replay",
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                program.title,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Text(
                program.timeAgoLabel,
                color = Color(0xFFFFC107).copy(alpha = 0.7f),
                fontSize = 8.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// SPORTS MOMENT CARD — Auto-clipped sports moment UI element
// Shows a clip of a goal/score change captured by SportsMomentsEngine.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SportsMomentCard(
    moment: com.agon.app.sports.SportsMomentsEngine.SportsMoment,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color(0xFF00E676).copy(alpha = 0.25f) else GlassSurface.copy(alpha = 0.4f))
            .border(1.dp, if (isFocused) Color(0xFF00E676) else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GOAL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(
                moment.matchName,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                moment.title,
                color = Color(0xFF00E676),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════════════
// FAST ZAPPING PANEL 
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FastZappingPanel(
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isFastZappingEnabled by com.agon.app.data.FastZappingManager.isEnabledFlow.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = null,
                tint = if (isFastZappingEnabled) AccentCyan else SlateTextMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "FAST ZAPPING (INSTANT PLAY)",
                color = if (isFastZappingEnabled) AccentCyan else SlateTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isFastZappingEnabled) "Instant Channel Switching Enabled" else "Stable Mode (Slower Zapping)",
                color = SlateTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            var toggleFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (toggleFocused) AccentIndigo.copy(alpha = 0.30f) else if (isFastZappingEnabled) AccentCyan.copy(alpha = 0.20f) else SlateSurface.copy(alpha = 0.20f))
                    .border(if (toggleFocused || isFastZappingEnabled) 1.5.dp else 1.dp, if (toggleFocused) AccentCyan else if (isFastZappingEnabled) AccentCyan else SlateBorder, RoundedCornerShape(6.dp))
                    .clickable { com.agon.app.data.FastZappingManager.setFastZappingEnabled(context, !isFastZappingEnabled) }
                    .focusable()
                    .onFocusChanged { toggleFocused = it.isFocused }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (isFastZappingEnabled) "ON" else "OFF", color = if (isFastZappingEnabled) AccentCyan else SlateTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
//    · Multi-stop gradient overlay for deep text readability
//    • Channel name (large bold) + group label (accent cyan)
//    • "Watch Now" pill button with play icon
//    • Dot indicators (bottom-center, clickable)
//    • Tap anywhere on the banner to play
//
//  This gives the phone interface the same luxury feel as Netflix /
//  Disney+ / Shahid home screens — a cinematic entry point above the
//  channel list.
// ══════════════════════════════════════════════════════════════════════
@Composable
fun PhoneHeroBanner(
    streams: List<StreamItem>,
    exoPlayer: ExoPlayer? = null,
    activeStreamUrl: String = "",
    onStreamClick: (StreamItem) -> Unit,
    onExpand: () -> Unit = {}
) {
    if (streams.isEmpty()) return
    val featured = remember(streams) {
        val withLogos = streams.filter { it.logo.isNotEmpty() }
        val source = if (withLogos.size >= 3) withLogos else streams
        source.take(5)
    }
    if (featured.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured.size) {
        if (featured.size > 1) {
            while (true) {
                delay(6000)
                currentIndex = (currentIndex + 1) % featured.size
            }
        }
    }

    val current = featured[currentIndex]
    // ═══════════════════════════════════════════════════════════════
    //  VIDEO vs IMAGE MODE
    //  ═══════════════════════════════════════════════════════════════
    //  Default: show the channel logo image (still).
    //  When [activeStreamUrl] == current.url AND an ExoPlayer is
    //  provided, the banner renders the live video surface INSTEAD
    //  of the still image — the stream is already playing in the
    //  unified coordinator.
    //
    //  Tap behavior:
    //    • Image mode  → onStreamClick (starts playback + shows mini-player)
    //    • Video mode  → onExpand (opens PlayerActivity full screen, resume bridge)
    //  This is the "tap to preview → tap again to go full screen" pattern.
    // ═══════════════════════════════════════════════════════════════
    val isVideoMode = exoPlayer != null &&
        activeStreamUrl.isNotEmpty() &&
        activeStreamUrl == current.url

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (isVideoMode) onExpand() else onStreamClick(current)
            }
    ) {
        if (isVideoMode && exoPlayer != null) {
            // ── Live video surface (unified ExoPlayer) ──
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // ── Backdrop (channel logo image) ──
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(current.logo)
                    .crossfade(true)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build(),
                contentDescription = current.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { LogoPlaceholder(Modifier.fillMaxSize()) },
                loading = {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                listOf(AccentIndigo.copy(alpha = 0.4f), AccentCyan.copy(alpha = 0.2f))
                            )
                        )
                    )
                }
            )
        }
        // ── Multi-stop gradient overlay (bottom-heavy for text) ──
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.3f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.5f),
                        Color.Black.copy(alpha = 0.9f)
                    )
                )
            )
        )
        // ── Content (bottom-left) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            // Group label
            if (current.group.isNotEmpty()) {
                Text(
                    current.group.uppercase(),
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            // Channel name
            Text(
                current.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            // Action pill — "Watch Now" (image mode) or "Expand" (video mode)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(listOf(AccentIndigo, AccentCyan))
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Icon(
                    if (isVideoMode) Icons.Default.Fullscreen else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (isVideoMode) "Expand" else "Watch Now",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // ── Dot indicators (bottom-center, above content) ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            featured.forEachIndexed { index, _ ->
                val isActive = index == currentIndex
                Box(
                    modifier = Modifier
                        .size(if (isActive) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) AccentCyan else Color.White.copy(alpha = 0.4f))
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHONE CHANNEL CARD — Premium phone-mode horizontal channel card
//  ══════════════════════════════════════════════════════════════════════
//  A wide, single-row card optimized for phone screens:
//    [72dp logo with gradient overlay + play icon] [name + group + badge (flexible)] [favorite star]
//
//  Premium design features:
//    • Glassmorphic background with subtle gradient border
//    • Larger 72dp logo with dark gradient overlay + center play icon
//    • Content-type badge (📚/📰/⚽/🎬) next to the name
//    • Bold white name + accent-cyan group label
//    • Gold star on favorite, subtle border highlight
// ══════════════════════════════════════════════════════════════════════
@Composable
fun PhoneChannelCard(
    stream: StreamItem,
    isFavorite: () -> Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit
) {
    val fav by remember { derivedStateOf { isFavorite() } }
    // Content-type classification (cached per URL) — same as TV cards.
    val contentType = remember(stream.url) {
        com.agon.app.recommendation.ContentClassifier.classify(stream)
    }
    val typeBadge = when (contentType) {
        com.agon.app.recommendation.ContentClassifier.ContentType.LEARNING -> "📚"
        com.agon.app.recommendation.ContentClassifier.ContentType.NEWS -> "📰"
        com.agon.app.recommendation.ContentClassifier.ContentType.SPORTS -> "⚽"
        else -> "🎬"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    if (fav) listOf(AccentIndigo.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))
                    else listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))
                )
            )
            .border(
                width = if (fav) 1.dp else 0.5.dp,
                color = if (fav) Color(0xFFFFD700).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Logo (72dp, with gradient overlay + play icon) ──
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(AccentIndigo.copy(alpha = 0.35f), AccentCyan.copy(alpha = 0.2f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (stream.logo.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(stream.logo)
                        .crossfade(true)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build(),
                    contentDescription = stream.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = { LogoPlaceholder(Modifier.fillMaxSize()) },
                    loading = { /* gradient placeholder already shown */ }
                )
            } else {
                LogoPlaceholder(Modifier.fillMaxSize())
            }
            // Dark gradient overlay at bottom of logo for depth
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                    )
                )
            )
            // Center play icon (semi-transparent circle)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // ── Name + group + badge ──
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeBadge, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stream.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (stream.group.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stream.group,
                    color = AccentCyan.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // ── Favorite star button (44dp touch target) ──
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onFavoriteClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (fav) "Remove favorite" else "Add favorite",
                tint = if (fav) Color(0xFFFFD700) else SlateTextMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHONE CHANNEL ROW — Netflix-style horizontal slider section
//  ══════════════════════════════════════════════════════════════════════
//  A section header + a horizontally-scrollable LazyRow of poster cards.
//  This is the core building block of the phone interface's Netflix-style
//  home screen — each group gets its own row that the user can swipe
//  left/right to browse.
//
//  Structure:
//    [Section Title]                    [N items]
//    [Poster] [Poster] [Poster] [Poster] ...  ← drag-scroll horizontal
// ══════════════════════════════════════════════════════════════════════
@Composable
fun PhoneChannelRow(
    title: String,
    streams: List<StreamItem>,
    onStreamClick: (StreamItem) -> Unit,
    isFavorite: (String) -> Boolean,
    onFavoriteClick: (String) -> Unit
) {
    if (streams.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Section header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${streams.size} items",
                color = SlateTextMuted,
                fontSize = 11.sp
            )
        }
        // ── Horizontal poster strip ──
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            items(streams, key = { "${title}_${it.url}_${it.name.hashCode()}" }) { stream ->
                PhonePosterCard(
                    stream = stream,
                    isFavorite = { isFavorite(stream.url) },
                    onFavoriteClick = { onFavoriteClick(stream.url) },
                    onClick = { onStreamClick(stream) }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHONE POSTER CARD — Vertical poster card for horizontal Netflix rows
//  ══════════════════════════════════════════════════════════════════════
//  A vertical poster-style card (120dp wide × ~180dp tall) designed for
//  the horizontal LazyRow sliders. Features:
//    • 120×160dp poster image with gradient overlay + play icon
//    • Channel name (2 lines max, bold white)
//    • Favorite star badge (top-right corner of poster)
//    • Premium glassmorphic styling
//
//  This is the Netflix/Disney+ "poster" card pattern — compact, visual,
//  designed for horizontal browsing strips.
// ══════════════════════════════════════════════════════════════════════
@Composable
fun PhonePosterCard(
    stream: StreamItem,
    isFavorite: () -> Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit
) {
    val fav by remember { derivedStateOf { isFavorite() } }
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        // ── Poster image (120×160dp) ──
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(AccentIndigo.copy(alpha = 0.35f), AccentCyan.copy(alpha = 0.15f))
                    )
                )
        ) {
            if (stream.logo.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(stream.logo)
                        .crossfade(true)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build(),
                    contentDescription = stream.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = { LogoPlaceholder(Modifier.fillMaxSize()) },
                    loading = { /* gradient placeholder already shown */ }
                )
            } else {
                LogoPlaceholder(Modifier.fillMaxSize())
            }
            // Bottom gradient for depth + text legibility
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                    )
                )
            )
            // Center play icon (semi-transparent)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            // Favorite star badge (top-right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onFavoriteClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (fav) "Remove favorite" else "Add favorite",
                    tint = if (fav) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // ── Channel name (2 lines max) ──
        Text(
            stream.name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHONE MINI-PLAYER — Bottom-pinned unified video player overlay
//  ══════════════════════════════════════════════════════════════════════
//  A bottom-pinned glassmorphic bar with a 16:9 video preview (from the
//  shared GlobalPlaybackCoordinator ExoPlayer) + stream name + Expand
//  and Close buttons. Renders ON TOP of the phone-mode content when a
//  stream is playing.
//
//  UNIFIED VIDEO PLAYER: this uses the SAME coordinator singleton as
//  TV mode's MiniPlayerEpgStrip — shared buffer, resume bridge to
//  PlayerActivity. The user's requirement: "always use the unified
//  video player" is satisfied — both phone and TV modes share the
//  same ExoPlayer instance and the same PlayerActivity full-screen
//  expansion path.
//
//  Tap Expand → PlayerActivity full screen (resume bridge, zero black
//  screen because the coordinator is already hot).
//  Tap Close  → stop playback, clear the overlay.
// ══════════════════════════════════════════════════════════════════════
@Composable
fun PhoneMiniPlayer(
    exoPlayer: ExoPlayer,
    streamName: String,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 16:9 Video preview ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            keepScreenOn = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        if (view.player !== exoPlayer) view.player = exoPlayer
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                // "PREVIEW" label
                Text(
                    "PREVIEW",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            // ── Controls row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stream name
                Text(
                    streamName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Expand button (gradient pill)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(listOf(AccentIndigo, AccentCyan))
                        )
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Expand", tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("Expand", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                // Close button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

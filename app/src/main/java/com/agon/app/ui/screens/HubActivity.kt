package com.agon.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agon.app.R
import com.agon.app.ads.AdConstants
import com.agon.app.ads.AdMobManager
import com.agon.app.data.ProfileRepository
import com.agon.app.data.model.PlaylistType
import com.agon.app.data.model.SessionData
import com.agon.app.data.repository.PlaylistRepository
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.SlateBorder
import com.agon.app.ui.theme.SlateSurface
import com.agon.app.ui.theme.SlateTextMuted
import com.agon.app.ui.theme.SlateTextPrimary
import com.agon.app.ui.theme.SlateTextSecondary
import com.agon.app.ui.theme.deepSpaceBackground
import com.agon.app.ui.theme.glassmorphicCard
import com.agon.app.ui.theme.glassmorphicPill
import com.agon.app.ui.theme.PremiumSpring
import com.agon.app.ui.theme.focusGlow
import com.agon.app.ui.util.applyImmersiveFullscreen
import com.agon.app.ui.util.AppBackgroundImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HubActivity — the new entry point after profile selection.
 *
 * ARCHITECTURE: Zero-Lag Clean Architecture
 *   State 1 (Sync UI)   : Strict Pre-fetching of Live → VOD → Series sequentially,
 *                         with a weighted progress bar (0% → 100%).
 *   State 2 (Hub UI)    : Three large cinematic category cards (LIVE / MOVIES / SERIES)
 *                         with Glassmorphism + focus-scale + glowing border.
 *
 * ROUTING: On card click, launches DashboardActivity passing the selected category
 *          via Intent extra. DashboardActivity assumes all data is already cached
 *          in SessionData (instant display, zero lag).
 *
 * THEME: Uses strictly MaterialTheme.colorScheme + pre-defined theme variables.
 *        No new hardcoded colors.
 */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveFullscreen()
        val profileId = intent.getStringExtra("PROFILE_ID")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HubScreen(profileId = profileId)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  V8.4 — REWARDED AD 24-HOUR GATE (SharedPreferences helpers)
//  ═══════════════════════════════════════════════════════════════════════
//  Two pure helpers that read / write the last successful rewarded-ad
//  timestamp to a private SharedPreferences file. The 24-hour validity
//  window is enforced LOCALLY — no server round-trip — which keeps the
//  gate snappy on weak TV hardware and immune to network failures.
//
//  POLICY-SAFE: the timestamp is written ONLY when AdMob's official
//  OnUserEarnedRewardListener confirms the user completed the full view.
//  Early-close = no timestamp update = no reward granted.
// ═══════════════════════════════════════════════════════════════════════

/**
 * Returns `true` if the user has a VALID rewarded-ad grant — i.e. they
 * watched a rewarded ad to completion within the last
 * [AdConstants.REWARDED_GRANT_DURATION_MS] milliseconds (24 hours).
 *
 * Reads from a private SharedPreferences file so the gate is immune to
 * clock skew across processes and survives app restarts.
 */
private fun isRewardValid(context: Context): Boolean {
    val prefs = context.getSharedPreferences(
        AdConstants.REWARD_PREFS_FILE,
        Context.MODE_PRIVATE
    )
    val lastTs = prefs.getLong(AdConstants.REWARD_TIMESTAMP_PREF_KEY, 0L)
    if (lastTs == 0L) return false
    val now = System.currentTimeMillis()
    val age = now - lastTs
    return age in 0..AdConstants.REWARDED_GRANT_DURATION_MS
}

/**
 * Persists the current epoch-millis timestamp as the moment the 24-hour
 * reward window begins. Called ONLY from inside the AdMob
 * OnUserEarnedRewardListener callback (via [AdMobManager.showRewardedAd]'s
 * onRewardEarned lambda) — never from any other path.
 */
private fun markRewardGranted(context: Context) {
    val prefs = context.getSharedPreferences(
        AdConstants.REWARD_PREFS_FILE,
        Context.MODE_PRIVATE
    )
    prefs.edit()
        .putLong(AdConstants.REWARD_TIMESTAMP_PREF_KEY, System.currentTimeMillis())
        .apply()
}

// ═══════════════════════════════════════════════════════════════════════
//  SYNC WEIGHTS — weighted step system for the 0% → 100% progress bar.
//  Weights are deliberately asymmetric: Live TV is by far the largest
//  payload, so it gets the biggest slice of the bar.
// ═══════════════════════════════════════════════════════════════════════
private object SyncWeights {
    const val AUTH     = 10   // Authenticating / injecting credentials
    const val LIVE     = 50   // Fetching Live TV streams (largest)
    const val MOVIES   = 25   // Fetching VOD / Movies
    const val SERIES   = 15   // Fetching Series
    const val TOTAL    = AUTH + LIVE + MOVIES + SERIES  // = 100
}

@Composable
fun HubScreen(profileId: String?) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val context = LocalContext.current
        val repo = remember { ProfileRepository.getInstance(context) }

        // ── Sync state ──
        var syncProgress by remember { mutableIntStateOf(0) }       // 0..100
        var syncStatus by remember { mutableStateOf("Authenticating…") }
        var syncDone by remember { mutableStateOf(false) }
        var currentProfileName by remember { mutableStateOf("") }
        var fatalError by remember { mutableStateOf<String?>(null) }

        // ═══════════════════════════════════════════════════════════════
        //  V8.4 — Rewarded Ad 24-Hour Gate State
        //  ═══════════════════════════════════════════════════════════════
        //  Two pieces of state govern the gate:
        //    • pendingCategory   — the category the user tapped ("LIVE" /
        //      "MOVIES" / "SERIES"). Held while the opt-in dialog is
        //      visible so we can launch it the instant the reward is earned.
        //    • showRewardDialog  — drives the visibility of the custom
        //      dark-themed opt-in dialog.
        //
        //  The FocusRequester [hubMainMenuFocusRequester] is attached to
        //  the LIVE TV card inside HubUi (the leftmost D-Pad entry point).
        //  After the rewarded ad is dismissed and the 24h grant is written,
        //  we force-request focus on it so the TV remote cursor lands back
        //  on the hub instantly — no frozen UI, no manual D-Pad hunting.
        // ═══════════════════════════════════════════════════════════════
        var pendingCategory by remember { mutableStateOf<String?>(null) }
        var showRewardDialog by remember { mutableStateOf(false) }
        val hubMainMenuFocusRequester = remember { FocusRequester() }

        // ═══════════════════════════════════════════════════════════════
        //  BACKGROUND PURGE WORKER — Silent Sports Match Cleanup
        //  ═══════════════════════════════════════════════════════════════
        //  Runs ONCE at app startup (when HubScreen first composes) to
        //  delete sports match rows older than 48 hours from the Room
        //  `matches` table. This prevents the database from growing
        //  without bound as the user opens the app day after day.
        //
        //  The purge is SILENT — it runs on Dispatchers.IO and never
        //  blocks the UI. Failures are logged but non-fatal (the next
        //  app launch will retry).
        //
        //  48 hours is chosen because:
        //    - Matches older than 24h are useless (already played).
        //    - 48h gives a 1-day grace window in case the user's clock
        //      is off or they want to check yesterday's results.
        //    - Beyond 48h, the rows are pure storage waste.
        // ═══════════════════════════════════════════════════════════════
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val cutoff = System.currentTimeMillis() - (48L * 60 * 60 * 1000)
                    com.agon.app.data.cache.MatchCacheManager.evictOlderThan(context, cutoff)
                    android.util.Log.i("HubActivity",
                        "Background purge: removed sports matches older than 48h (cutoff=$cutoff)")
                } catch (e: Exception) {
                    android.util.Log.w("HubActivity",
                        "Background purge failed (non-fatal): ${e.message}")
                }
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    com.agon.app.data.FastZappingManager.init(context)
                } catch (e: Exception) {
                    android.util.Log.w("HubActivity", "BandwidthShield init failed (non-fatal): ${e.message}")
                }
            }
        }

        // ── Strict Pre-fetching Engine ──
        // Sequential fetch: Auth → Live → VOD → Series, with weighted progress.
        LaunchedEffect(profileId) {
            if (!profileId.isNullOrEmpty()) repo.setActiveProfile(profileId)
            val profile = withContext(Dispatchers.IO) {
                repo.getActiveProfile().first() ?: repo.getAllProfiles().first().firstOrNull()
            }
            if (profile == null) {
                fatalError = "No profile selected."
                return@LaunchedEffect
            }
            currentProfileName = profile.name
            PlaylistRepository.clearSession()

            // ── STEP 1: Authenticating / injecting credentials ──
            syncStatus = "Authenticating…"
            if (profile.serverUrl.isNotEmpty() && profile.username.isNotEmpty()) {
                var cleanBase = profile.serverUrl.trim().trimEnd('/')
                if (cleanBase.lowercase().endsWith("/player_api.php")) {
                    cleanBase = cleanBase.substring(0, cleanBase.length - 15)
                }
                if (!cleanBase.startsWith("http://", ignoreCase = true) &&
                    !cleanBase.startsWith("https://", ignoreCase = true)) {
                    cleanBase = "http://$cleanBase"
                }
                SessionData.xtreamBaseUrl = cleanBase
                SessionData.xtreamUsername = profile.username
                SessionData.xtreamPassword = profile.password
                SessionData.playlistType = PlaylistType.XTREAM_CODES
            } else if (profile.m3uUrl.isNotEmpty()) {
                // M3U-only profile — playlist type stays M3U
                SessionData.playlistType = PlaylistType.M3U_PLAYLIST
            }
            syncProgress = SyncWeights.AUTH
            kotlinx.coroutines.delay(120)  // small visual pause for the auth step

            // ── STEP 2: Fetch Live TV (cache-first, then network) ──
            syncStatus = "Fetching Live TV…"
            val cachedLive = withContext(Dispatchers.IO) {
                PlaylistRepository.loadFastCache(context, profile.id, "LIVE", 24)
            }
            if (cachedLive.isNotEmpty()) {
                SessionData.liveStreams = cachedLive
            } else {
                try {
                    var success = false
                    if (profile.serverUrl.isNotEmpty() && profile.username.isNotEmpty()) {
                        success = PlaylistRepository.fetchXtreamStreams(profile.serverUrl, profile.username, profile.password)
                        if (success && SessionData.liveStreams.isEmpty()) {
                            SessionData.liveStreams = SessionData.allStreams
                        }
                    } else if (profile.m3uUrl.isNotEmpty()) {
                        success = PlaylistRepository.parseM3U(profile.m3uUrl)
                        if (success && SessionData.liveStreams.isEmpty()) {
                            SessionData.liveStreams = SessionData.allStreams
                        }
                    }
                    if (success) {
                        withContext(Dispatchers.IO) {
                            PlaylistRepository.saveFastCache(context, profile.id, "LIVE", SessionData.liveStreams)
                        }
                    }
                } catch (_: Exception) {}
            }
            syncProgress = SyncWeights.AUTH + SyncWeights.LIVE

            // ── STEP 3: Fetch VOD / Movies (Xtream-only) ──
            syncStatus = "Fetching Movies…"
            if (SessionData.playlistType == PlaylistType.XTREAM_CODES &&
                SessionData.xtreamBaseUrl.isNotEmpty()
            ) {
                val cachedMovies = withContext(Dispatchers.IO) {
                    PlaylistRepository.loadFastCache(context, profile.id, "MOVIES", 24)
                }
                if (cachedMovies.isNotEmpty()) {
                    SessionData.movieStreams = cachedMovies
                } else {
                    try {
                        PlaylistRepository.fetchVodStreams()
                        if (SessionData.movieStreams.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                PlaylistRepository.saveFastCache(context, profile.id, "MOVIES", SessionData.movieStreams)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            syncProgress = SyncWeights.AUTH + SyncWeights.LIVE + SyncWeights.MOVIES

            // ── STEP 4: Fetch Series (Xtream-only) ──
            syncStatus = "Fetching Series…"
            if (SessionData.playlistType == PlaylistType.XTREAM_CODES &&
                SessionData.xtreamBaseUrl.isNotEmpty()
            ) {
                val cachedSeries = withContext(Dispatchers.IO) {
                    PlaylistRepository.loadFastCache(context, profile.id, "SERIES", 24)
                }
                if (cachedSeries.isNotEmpty()) {
                    SessionData.seriesStreams = cachedSeries
                } else {
                    try {
                        PlaylistRepository.fetchSeriesStreams()
                        if (SessionData.seriesStreams.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                PlaylistRepository.saveFastCache(context, profile.id, "SERIES", SessionData.seriesStreams)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            syncProgress = SyncWeights.TOTAL  // 100

            // ── Brief settle pause so the user perceives the 100% frame ──
            kotlinx.coroutines.delay(280)
            syncDone = true
        }

        // ── ROOT CONTAINER — Deep Space 2026 ──
        // Deep Space vertical gradient + cinematic radial glow. Replaces the
        // flat dark background with a premium cosmic backdrop.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .deepSpaceBackground(withCinematicGlow = true)
        ) {
            // Animated transition: Sync UI  ⇄  Hub UI
            AnimatedContent(
                targetState = syncDone,
                transitionSpec = {
                    fadeIn(tween(450)) togetherWith fadeOut(tween(350))
                },
                label = "HubStateTransition"
            ) { done ->
                if (!done) {
                    SyncUi(
                        progress = syncProgress,
                        status = syncStatus,
                        profileName = currentProfileName,
                        error = fatalError
                    )
                } else {
                    HubUi(
                        profileName = currentProfileName,
                        mainMenuFocusRequester = hubMainMenuFocusRequester,
                        onCategoryClick = { category ->
                            // ═════════════════════════════════════════════════
                            //  V8.4 — Rewarded Ad 24-Hour Gate
                            //  ═════════════════════════════════════════════════
                            //  Before launching DashboardActivity, silently
                            //  check the LOCAL 24h reward timestamp. If valid,
                            //  the user passes through immediately — zero
                            //  friction. If expired (or first launch), the
                            //  gate blocks the launch and shows the EXPLICIT
                            //  opt-in rewarded-ad dialog required by AdMob
                            //  policy ("شاهد إعلان فيديو…").
                            // ═════════════════════════════════════════════════
                            if (isRewardValid(context)) {
                                // Reward window active — pass through.
                                context.startActivity(
                                    Intent(context, DashboardActivity::class.java).apply {
                                        putExtra("PROFILE_ID", profileId)
                                        putExtra("CATEGORY", category)
                                    }
                                )
                            } else {
                                // Reward expired — hold the category and
                                // show the opt-in dialog. The actual launch
                                // happens after the rewarded ad is completed.
                                pendingCategory = category
                                showRewardDialog = true
                            }
                        },
                        onSettingsClick = {
                            // Reuse the SettingsScreen by launching DashboardActivity
                            // in "settings" mode. This keeps Settings reachable from
                            // the hub without duplicating its UI here.
                            context.startActivity(
                                Intent(context, DashboardActivity::class.java).apply {
                                    putExtra("PROFILE_ID", profileId)
                                    putExtra("CATEGORY", "LIVE")
                                    putExtra("OPEN_SETTINGS", true)
                                }
                            )
                        },
                        onSwitchAccountClick = {
                            context.startActivity(Intent(context, ProfilesActivity::class.java))
                            (context as? ComponentActivity)?.finish()
                        }
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  V8.4 — Rewarded Ad Opt-in Dialog (24-Hour Gate)
            //  ═══════════════════════════════════════════════════════════════
            //  Shows a custom dark-themed dialog with a D-Pad focusable
            //  CTA button explicitly labeled "شاهد إعلان فيديو لفتح البث
            //  المجاني لمدة 24 ساعة كاملة" — the EXPLICIT opt-in required
            //  by AdMob policy before a rewarded ad can be presented.
            //
            //  On reward earned + ad dismissed:
            //    1. The 24h timestamp is written to SharedPreferences.
            //    2. The dialog closes.
            //    3. The pending category is launched (DashboardActivity).
            //    4. Focus is force-requested on the hub main menu so the
            //       TV remote cursor lands cleanly on the LIVE TV card
            //       when the user returns from the dashboard.
            // ═══════════════════════════════════════════════════════════════
            if (showRewardDialog) {
                RewardedGateDialog(
                    onWatchAd = {
                        // Dismiss the dialog first so the rewarded ad's
                        // full-screen overlay is the only thing on top.
                        showRewardDialog = false
                        // Pre-load another creative in case this one fails.
                        AdMobManager.loadRewardedAd(context)
                        // Present the cached rewarded ad. The onRewardEarned
                        // lambda fires ONLY after the user completes the
                        // full view AND the ad container is dismissed.
                        AdMobManager.showRewardedAd(context as ComponentActivity) {
                            // ── REWARD EARNED ──
                            // Write the 24h grant to SharedPreferences.
                            markRewardGranted(context)
                            // Launch the pending category (the one the user
                            // originally tapped) into DashboardActivity.
                            pendingCategory?.let { cat ->
                                context.startActivity(
                                    Intent(context, DashboardActivity::class.java).apply {
                                        putExtra("PROFILE_ID", profileId)
                                        putExtra("CATEGORY", cat)
                                    }
                                )
                            }
                            pendingCategory = null
                            // Force re-inject focus on the hub main menu so
                            // the TV remote cursor lands cleanly when the
                            // user returns. The small delay lets the ad
                            // overlay fully tear down before we ask the
                            // focus system to relocate.
                            kotlinx.coroutines.MainScope().launch {
                                delay(150)
                                runCatching {
                                    hubMainMenuFocusRequester.requestFocus()
                                }
                            }
                        }
                    },
                    onDismiss = {
                        // User cancelled (BACK / outside tap) — no reward,
                        // no launch. Focus is re-injected on the hub menu
                        // so the user can navigate freely.
                        showRewardDialog = false
                        pendingCategory = null
                        kotlinx.coroutines.MainScope().launch {
                            delay(100)
                            runCatching {
                                hubMainMenuFocusRequester.requestFocus()
                            }
                        }
                    }
                )
            }

            // ═══════════════════════════════════════════════════════════════
            //  V8.3 — BANNER AD REMOVED (Hub Screen)
            //  ═══════════════════════════════════════════════════════════════
            //  The Anchored Adaptive Banner (BannerAdView) was physically
            //  removed from the project in V8.3. The hub screen now ends
            //  cleanly below the hub cards — no ad slot.
            // ═══════════════════════════════════════════════════════════════
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SYNC UI — sleek centered progress bar with dynamic status text.
//  Colors come strictly from MaterialTheme.colorScheme + theme vars.
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun SyncUi(
    progress: Int,
    status: String,
    profileName: String,
    error: String?
) {
    // ── Immersive Sync Screen ──
    // Deep Space gradient + cinematic glow + animated brand pulse +
    // large circular progress with percentage inside + step indicator dots.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AppBackgroundImage(scrimAlpha = 0.55f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 64.dp)
        ) {
            // ── Brand logo with subtle pulse animation tied to progress ──
            val pulseScale by animateFloatAsState(
                targetValue = if (progress in 1..99) 1.04f else 1f,
                animationSpec = tween(800),
                label = "brandPulse"
            )
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "DOH",
                modifier = Modifier
                    .size(90.dp)
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(22.dp))
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(8.dp))

            // ── Large circular progress indicator (~120dp) ──
            // Percentage text is placed explicitly INSIDE the center of the
            // circular bar using a Box with Alignment.Center.
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 8.dp
                )
                Text(
                    "$progress%",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── Step Indicator: 4 dots that light up sequentially ──
            // Auth=10%, Live=60%, Movies=85%, Series=100%
            val thresholds = listOf(10, 60, 85, 100)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                thresholds.forEachIndexed { _, t ->
                    val active = progress >= t
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Dynamic status text
            Text(
                status,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            if (profileName.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Account: $profileName",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            if (error != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  HUB UI — three large cinematic category cards + top nav icons.
//  Glassmorphism = blur + theme-based linear gradient overlay.
//  Focus-scale = 1.10f smooth + glowing theme-accented border.
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun HubUi(
    profileName: String,
    mainMenuFocusRequester: FocusRequester,
    onCategoryClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchAccountClick: () -> Unit
) {
    // BackHandler — pressing BACK on the hub returns to ProfilesActivity
    // (switch-account screen) instead of exiting the app. This is the
    // expected Android-TV behavior: BACK always pops one level up.
    androidx.activity.compose.BackHandler(enabled = true) {
        onSwitchAccountClick()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AppBackgroundImage(scrimAlpha = 0.5f)
        Column(modifier = Modifier.fillMaxSize()) {

        // ── FLOATING TOP NAV BAR (glassmorphic pill) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: Mini brand logo + wordmark
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "DOH",
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "DOH PLAYER",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "PRO",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }

            // RIGHT: Profile name + Expiry Premium Badge + Settings + Switch Account
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (profileName.isNotEmpty()) {
                    Text(
                        profileName,
                        color = SlateTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp)
                    )
                }
                // ── Premium Badge: account expiration date ──
                val expiry = SessionData.accountExpiry
                if (expiry.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .glassmorphicPill(focused = false)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            expiry,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                HubTopIcon(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    onClick = onSettingsClick
                )
                HubTopIcon(
                    icon = Icons.Default.SwapHoriz,
                    label = "Switch Account",
                    onClick = onSwitchAccountClick
                )
            }
        }

        // ── THREE CARDS IN ONE ROW — top-aligned, graduated sizes ──
        // LIVE TV (tallest) | MOVIES (medium) | SERIES (shortest)
        // Each card has its own cinematic gradient + pure white text/icons.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Small gap below top nav ──
                Spacer(Modifier.height(8.dp))

                // ── Three cards in one row with graduated heights + update time bars ──
                // ═══════════════════════════════════════════════════════════════
                //  ANDROID TV FOCUS REFACTOR — D-Pad / Remote Control
                // ═══════════════════════════════════════════════════════════════
                //  Defect fixed: "focus jumps randomly or freezes when
                //  browsing the hub cards with the D-Pad."
                //
                //  Root cause: the three CategoryCards had no explicit
                //  FocusRequester on the FIRST card (LIVE TV), so on app
                //  entry the D-Pad had no anchor — it sometimes landed on
                //  the Settings icon, sometimes on the Series card, and
                //  sometimes nowhere at all.
                //
                //  Fix: a single [FocusRequester] is created here and
                //  attached to the LIVE TV card (the leftmost, tallest
                //  card). A [LaunchedEffect(Unit)] requests focus on it
                //  the moment the HubUi composes. Subsequent LEFT/RIGHT
                //  D-Pad navigation flows naturally between the three
                //  cards via their existing focusable() modifiers — no
                //  business logic changed.
                //
                //  V8.4: the FocusRequester is now hoisted to HubScreen
                //  (passed in as [mainMenuFocusRequester]) so that after
                //  the rewarded-ad dialog is dismissed, the parent can
                //  re-request focus on the LIVE TV card without having to
                //  reach into HubUi's internals.
                // ═══════════════════════════════════════════════════════════════
                LaunchedEffect(Unit) {
                    // Tiny delay so the cards have time to compose before
                    // we ask the focus system to find the FocusRequester.
                    kotlinx.coroutines.delay(200)
                    runCatching { mainMenuFocusRequester.requestFocus() }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top
                ) {
                    // ── LIVE TV — tallest card + update bar ──
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        CategoryCardNew(
                            title = "LIVE TV",
                            subtitle = "${SessionData.liveStreams.size} channels",
                            icon = Icons.Default.Tv,
                            gradient = Brush.linearGradient(
                                listOf(
                                    Color(0xFF00BCD4),  // Cyan / Teal
                                    Color(0xFF191970)   // Midnight Blue
                                )
                            ),
                            onClick = { onCategoryClick("LIVE") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                // ── D-Pad focus entry point ──
                                // Attach the hoisted FocusRequester so both
                                // the LaunchedEffect above AND the parent
                                // HubScreen (after the rewarded dialog
                                // dismisses) can land the D-Pad cursor here.
                                .focusRequester(mainMenuFocusRequester)
                        )
                        // ── Transparent update time bar ──
                        UpdateTimeBar()
                    }

                    // ── MOVIES — medium card + update bar ──
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        CategoryCardNew(
                            title = "MOVIES",
                            subtitle = "${SessionData.movieStreams.size} titles",
                            icon = Icons.Default.Movie,
                            gradient = Brush.linearGradient(
                                listOf(
                                    Color(0xFFFF6F00),  // Ember Orange / Amber
                                    Color(0xFF7B1FA2)   // Violet / Crimson
                                )
                            ),
                            onClick = { onCategoryClick("MOVIES") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(155.dp)
                        )
                        UpdateTimeBar()
                    }

                    // ── SERIES — shortest card + update bar ──
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        CategoryCardNew(
                            title = "SERIES",
                            subtitle = "${SessionData.seriesStreams.size} shows",
                            icon = Icons.Default.VideoLibrary,
                            gradient = Brush.linearGradient(
                                listOf(
                                    Color(0xFFE040FB),  // Magenta
                                    Color(0xFF3F51B5)   // Indigo
                                )
                            ),
                            onClick = { onCategoryClick("SERIES") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(125.dp)
                        )
                        UpdateTimeBar()
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun HubTopIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, tween(120), label = "hubIconScale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused) 1.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isFocused) MaterialTheme.colorScheme.primary else SlateTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (isFocused) MaterialTheme.colorScheme.primary else SlateTextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Hero Category Card — large featured banner (used for LIVE TV on top).
 * Glassmorphic + spring scale + glowing accent border on focus.
 *
 * LAYOUT FIXES (2026):
 *  - Scale is applied LAST (after clipping/padding) so neighbors are not
 *    clipped when the card grows on focus.
 *  - Padding is INSIDE a child Box (not on the outer Box) so the gradient
 *    + glass background extends edge-to-edge while text stays clear of the
 *    rounded corners.
 *  - Text uses a subtle shadow so it stays readable over the gradient.
 *  - The focused-glow overlay is drawn BEFORE the content (z-order) so it
 *    never covers the text.
 */
@Composable
private fun HeroCategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Smooth spring-based 4% scale-up (hero is already large, smaller delta)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = PremiumSpring,
        label = "heroCardScale"
    )

    Box(
        modifier = modifier
            // Scale applied LAST so the grown card doesn't clip neighbors.
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .background(
                brush = gradient,
                shape = RoundedCornerShape(24.dp),
                alpha = if (isFocused) 0.92f else 0.75f
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.CenterStart
    ) {
        // Focused glow overlay — drawn first so it sits behind the content.
        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        // Content Row — wrapped in its own padding so the text never touches
        // the card edges, and the gradient/glass extends edge-to-edge.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
            // Title + subtitle — wrap subtitle in weight(1f) so it never
            // overflows the card horizontally, and add a subtle text shadow
            // for contrast over the gradient.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            blurRadius = 4f
                        )
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            blurRadius = 3f
                        )
                    )
                )
            }
        }
    }
}

/**
 * Transparent update time bar — attached below each category card.
 * Shows the last sync time in a semi-transparent strip.
 */
@Composable
private fun UpdateTimeBar() {
    val currentTime = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Updated: $currentTime",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * New unified category card — compact, centered, with cinematic gradient.
 * Pure white text & icons for maximum contrast over vibrant gradients.
 * Used for all three hub cards (LIVE TV / MOVIES / SERIES).
 *
 * Design spec:
 *  - Cyan→Midnight Blue for LIVE TV
 *  - Ember Orange→Violet for MOVIES
 *  - Magenta→Indigo for SERIES
 *  - All text & icons: Pure White (#FFFFFF)
 */
@Composable
private fun CategoryCardNew(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = PremiumSpring,
        label = "newCardScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = gradient,
                shape = RoundedCornerShape(18.dp),
                alpha = if (isFocused) 0.95f else 0.85f
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        // Focused glow overlay
        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Content — centered with internal padding
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 4f
                    )
                )
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        blurRadius = 3f
                    )
                )
            )
        }
    }
}

/**
 * Cinematic category card with Glassmorphism + spring focus-scale + glowing border.
 *
 * LAYOUT FIXES (2026):
 *  - Scale applied LAST so neighbors are not clipped.
 *  - Padding INSIDE the Column (not on the outer Box) so the gradient
 *    extends edge-to-edge while text stays clear of rounded corners.
 *  - Subtitle uses maxLines=1 + Ellipsis + weight so it never wraps or
 *    pushes outside the card.
 *  - Text shadow for contrast over the gradient.
 */
@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Smooth spring-based 6% scale-up on focus (was 8% — reduced to prevent
    // clipping into the Hero card above).
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = PremiumSpring,
        label = "categoryCardScale"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            // Scale applied LAST so the grown card doesn't clip neighbors.
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .background(
                brush = gradient,
                shape = RoundedCornerShape(20.dp),
                alpha = if (isFocused) 0.92f else 0.75f
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        // Focused glow — drawn first so it sits behind the content.
        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Content — centered, with internal padding so text never touches
        // the card edges. Vertical arrangement uses spacedBy so subtitle
        // never pushes outside the card.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 4f
                    )
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        blurRadius = 3f
                    )
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  V8.4 — Rewarded Ad Opt-in Dialog (24-Hour Content-Unlock Gate)
// ═══════════════════════════════════════════════════════════════════════
//  Custom dark-themed Compose Dialog with a D-Pad-focusable CTA button.
//  The button label is the EXPLICIT opt-in text mandated by AdMob policy:
//    "شاهد إعلان فيديو لفتح البث المجاني لمدة 24 ساعة كاملة"
//
//  The dialog is shown when the 24h reward window has expired and the
//  user tapped a content category (Live / Movies / Series). It blocks
//  navigation until either:
//    (a) the user taps the CTA → onWatchAd fires → rewarded ad presents
//    (b) the user presses BACK / taps outside → onDismiss fires → no
//        reward, no launch (user stays on the hub)
//
//  TV-FRIENDLY DESIGN:
//    • The CTA button is wrapped with .focusable() + .focusRequester()
//      so the D-Pad lands on it the instant the dialog appears.
//    • A LaunchedEffect auto-requests focus on the CTA after a small
//      delay so the user can confirm with the remote's CENTER button
//      without hunting for the cursor.
//    • The dialog uses DialogProperties(usePlatformDefaultWidth = false)
//      so the dark glassmorphic surface fills a sensible TV-friendly
//      width (520.dp) regardless of the platform's default dialog
//      sizing heuristics.
//    • Dismiss on BACK press is enabled — but the onDismiss callback
//      does NOT grant the reward, preserving AdMob policy compliance.
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun RewardedGateDialog(
    onWatchAd: () -> Unit,
    onDismiss: () -> Unit
) {
    // ── FocusRequester for the CTA button so the D-Pad lands on it ──
    val ctaFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,  // force explicit CTA tap
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
                    onClick = { /* swallow outside clicks — must use CTA or BACK */ }
                ),
            contentAlignment = Alignment.Center
        ) {
            // ── Dark glassmorphic dialog surface ──
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF0E1320),
                                Color(0xFF06080F)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 28.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // ── Icon: Play circle (video ad cue) ──
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    // ── Headline ──
                    Text(
                        text = "Unlock Free Streaming",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    // ── Sub-text explaining the 24h window ──
                    Text(
                        text = "To unlock the content section, watch a short video ad ONE TIME to activate 24 hours of free streaming across all sections.",
                        color = SlateTextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    // ── CTA Button — D-Pad focusable, explicit opt-in label ──
                    // The button label is the EXACT text mandated by the V8.4
                    // directive: "Watch a video ad to unlock 24
                    // hours of free streaming". This explicit opt-in is REQUIRED
                    // by AdMob policy before a rewarded ad can be presented.
                    val ctaInteractionSource = remember { MutableInteractionSource() }
                    val ctaFocused by ctaInteractionSource.collectIsFocusedAsState()
                    val ctaScale by animateFloatAsState(
                        targetValue = if (ctaFocused) 1.04f else 1f,
                        animationSpec = tween(120),
                        label = "ctaScale"
                    )

                    // Auto-focus the CTA the instant the dialog appears so
                    // the TV remote user can confirm with CENTER immediately.
                    LaunchedEffect(Unit) {
                        delay(250)
                        runCatching { ctaFocusRequester.requestFocus() }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(ctaScale)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        if (ctaFocused) AccentCyan else AccentCyan.copy(alpha = 0.85f),
                                        if (ctaFocused) AccentIndigo else AccentIndigo.copy(alpha = 0.85f)
                                    )
                                )
                            )
                            .border(
                                width = if (ctaFocused) 2.dp else 0.dp,
                                color = if (ctaFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .focusRequester(ctaFocusRequester)
                            .focusable(interactionSource = ctaInteractionSource)
                            .clickable(
                                interactionSource = ctaInteractionSource,
                                indication = null,
                                onClick = { onWatchAd() }
                            )
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Watch a video ad to unlock 24 hours of free streaming",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Cancel button (smaller, secondary) ──
                    val cancelInteractionSource = remember { MutableInteractionSource() }
                    val cancelFocused by cancelInteractionSource.collectIsFocusedAsState()

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (cancelFocused) Color.White.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .focusable(interactionSource = cancelInteractionSource)
                            .clickable(
                                interactionSource = cancelInteractionSource,
                                indication = null,
                                onClick = { onDismiss() }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            color = SlateTextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Tiny policy disclosure (AdMob transparency) ──
                    Text(
                        text = "You can close the ad at any time, but the reward is only granted after watching it in full.",
                        color = SlateTextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

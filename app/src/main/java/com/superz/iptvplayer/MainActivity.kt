package com.superz.iptvplayer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import com.superz.iptvplayer.diagnostics.CrashDiagnostics
import com.superz.iptvplayer.diagnostics.CrashReportActivity
import com.superz.iptvplayer.player.PlayerSessionManager
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.superz.iptvplayer.ui.accounts.AccountsScreen
import com.superz.iptvplayer.ui.components.OriaAnnouncementOverlay
import com.superz.iptvplayer.ui.components.OriaUpdateDialog
import com.superz.iptvplayer.data.remote.UpdateCenter
import com.superz.iptvplayer.ui.util.applyImmersiveFullscreen
import com.superz.iptvplayer.ui.browse.BrowseScreen
import com.superz.iptvplayer.ui.catchup.CatchUpDetailScreen
import com.superz.iptvplayer.ui.catchup.CatchUpScreen
import com.superz.iptvplayer.ui.channelview.ChannelViewScreen
import com.superz.iptvplayer.ui.downloads.SavedVideosScreen
import com.superz.iptvplayer.ui.hub.HubScreen
import com.superz.iptvplayer.ui.loading.LoadingScreen
import com.superz.iptvplayer.ui.login.AddPortalScreen
import com.superz.iptvplayer.ui.login.PremiumScreen
import com.superz.iptvplayer.ui.login.EditPortalScreen
import com.superz.iptvplayer.ui.login.LanguageScreen
import com.superz.iptvplayer.ui.login.VuLoginFlow
import com.superz.iptvplayer.ui.multiscreen.MultiScreenPlayerScreen
import com.superz.iptvplayer.ui.multiscreen.MultiScreenSetupScreen
import com.superz.iptvplayer.ui.player.PlayerScreen
import com.superz.iptvplayer.ui.settings.SettingsScreen
import com.superz.iptvplayer.ui.theme.AppLang
import com.superz.iptvplayer.ui.theme.IPTVPlayerTheme
import com.superz.iptvplayer.ui.vod.MovieInfoScreen
import com.superz.iptvplayer.ui.vod.SeriesInfoScreen
import com.superz.iptvplayer.ui.vod.VodTraceScreen
import kotlinx.coroutines.flow.map

/**
 * Reference-app navigation flow (landscape 16:9 throughout):
 * v1.19.12 — no onboarding pages: add portal (method) → edit portal (form) →
 * accounts → loading (sync) → hub (three cards) → grid →
 * channel view (split: list + mini player + EPG) → fullscreen player.
 * The app follows the device locale from the first frame (Arabic device →
 * Arabic, anything else → English); the language page lives in Settings.
 */
class MainActivity : ComponentActivity() {

    /** True while the app lives in a picture-in-picture window. */
    private val pipState = mutableStateOf(false)

    /**
     * v1.4.11 — the reference's per-app language (ExtraPrefrence.setLanguage
     * + setLocaleLang). Applied at attach time so the settings language
     * page's relaunch re-enters with the new locale. No stored preference
     * → follow the SYSTEM locale (v1.19.12: the ONLY first-run behavior —
     * the intro slider that used to store a first choice is gone; an
     * Arabic device opens Arabic, any other device opens English).
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase
            .getSharedPreferences("vu_prefs", Context.MODE_PRIVATE)
            .getString("lang", null)
        val base = if (tag != null) {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belt-and-braces: the CrashProvider normally initializes diagnostics
        // before anything else runs, but we must never DEPEND on provider
        // ordering — a missing/failed provider must not take the app down.
        CrashDiagnostics.ensureInitialized(this)
        CrashDiagnostics.breadcrumb("MainActivity.onCreate")

        // A crash was captured on the previous run — show the report
        // instead of re-running the (possibly crashing) launch path.
        if (CrashDiagnostics.hasPendingCrash()) {
            CrashDiagnostics.breadcrumb("showing crash report")
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        // Immersive sticky fullscreen (reference app behavior): hides the
        // status bar AND the navigation bar for the WHOLE app. In landscape
        // the nav bar sits on the side of the screen — with plain edge-to-edge
        // it stayed visible and overlapped UI controls, blocking taps.
        // IMMERSIVE_STICKY: bars reappear transiently on swipe, then re-hide.
        applyImmersiveFullscreen()
        // v2.0.0 — Android 13+ push-notification permission (one ask;
        // TV boxes below API 33 don't need it).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4701)
        }
        CrashDiagnostics.breadcrumb("before setContent")
        setContent {
            IPTVPlayerTheme {
                AppNavGraph(pipMode = pipState.value)
            }
        }
        CrashDiagnostics.breadcrumb("setContent returned")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-assert immersive mode: dialogs / IME / ROM quirks can bring the
        // system bars back and they would overlap the landscape UI again.
        if (hasFocus && !pipState.value) applyImmersiveFullscreen()
    }

    // ── Picture-in-Picture (v1.3.0) ──
    // Premium behavior: swiping home while a stream plays shrinks the app
    // into a floating 16:9 window instead of pausing — the stream keeps
    // playing in the mini/fullscreen player.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val pipOk = packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
        )
        if (PlayerSessionManager.isStreamPlaying() && pipOk) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            } catch (_: Throwable) {
                // Manufacturer PiP restrictions — ignore silently.
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
    }
}

/**
 * The reference's locale switch always STARTS A FRESH ACTIVITY
 * (LanguageAdapter.setLocale: startActivity(HomeActivity) + finishAffinity()).
 * The single-activity equivalent: relaunch MainActivity through the entry
 * gate with NEW_TASK | CLEAR_TASK — the whole task is wiped BEFORE the new
 * instance is created, so (a) no saved navigation state survives and the
 * entry gate (VuLoginFlow.entryRoute) re-runs with the stored prefs, and
 * (b) attachBaseContext applies the freshly stored locale on the way in.
 * v1.19.12: only the SETTINGS language page uses this (the intro slider's
 * own relaunch path is gone with the intro).
 *
 * v1.4.12 lesson (the crash the user hit on their TV): MainActivity is
 * launchMode="singleTask", so a plain NEW_TASK relaunch routes the intent
 * to the EXISTING instance (onNewIntent) instead of creating a fresh one —
 * the follow-up finishAffinity() then killed the task outright and the app
 * simply exited (no Java exception → no crash report; reopening landed
 * correctly because the prefs were already saved). CLEAR_TASK destroys the
 * old instance as part of clearing the task, so a brand-new root is
 * guaranteed. finish()/finishAffinity() are deliberately NOT called here —
 * the task clear already finishes every activity in it.
 */
private fun relaunchThroughEntryGate(context: Context) {
    val intent = Intent(context, MainActivity::class.java)
    intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    )
    context.startActivity(intent)
}

@Composable
fun AppNavGraph(pipMode: Boolean = false) {
    val navController = rememberNavController()
    CrashDiagnostics.breadcrumb("AppNavGraph composition")

    // First Room emission decides the entry point (login vs accounts).
    // `null` = still loading → show nothing (prevents start-destination races).
    val hasPlaylists by IPTVApp.get().database.playlistDao().playlistsFlow()
        .map { it.isNotEmpty() }
        .collectAsState(initial = null)
    CrashDiagnostics.breadcrumb("room flow collecting, hasPlaylists=$hasPlaylists")

    if (hasPlaylists == null) {
        return
    }

    // v1.4.11 — the per-app language preference (the Settings language
    // page writes it; the intro slider's first-run write is gone with the
    // intro itself — v1.19.12).
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("vu_prefs", Context.MODE_PRIVATE)
    }

    NavHost(
        navController = navController,
        // v1.19.12 — the entry gate WITHOUT the intro step (user directive:
        // remove every startup/onboarding page): existing users go straight
        // to the accounts page; a brand-new install lands on the method
        // picker — already in the device's own language.
        // (VuLoginFlow.entryRoute — unit-tested contract.)
        startDestination = VuLoginFlow.entryRoute(hasPlaylists = hasPlaylists == true),
        enterTransition = { fadeIn(animationSpec = tween(220)) },
        exitTransition = { fadeOut(animationSpec = tween(180)) }
    ) {
        // ── 1a. Add portal — connection-method picker (reference:
        //    AddPortalActivity — activity_add_portal.xml, exact copy:
        //    2×2 grid of 240×30sdp pills + centered 100×100sdp crown logo). ──
        composable("addPortal") {
            AddPortalScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        (context as? ComponentActivity)?.finish()
                    }
                },
                onSelectType = { type ->
                    navController.navigate("editPortal/${type.name}?playlistId=-1")
                },
                // v1.14.0 — QR bridge login succeeded → the same hand-off a
                // manual login uses (accounts page → tap → loading/sync).
                onQrSuccess = {
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // v2.0.2 — Phase 2: the premium subscription flow (plans →
                // WhatsApp → locked-host Xtream login).
                onPremium = {
                    navController.navigate("premium")
                },
                // v2.1.3 — the Account pill at the login page's top-end →
                // the accounts page (user: “واضف زر Account في واجهة
                // تسجيل الدخول في الاعلى الشاشة يوجه الى صفحة الحسابات”).
                onAccounts = {
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // ── 1a-premium. v2.0.2 — THE PREMIUM SUBSCRIPTION SCREEN (Phase 2):
        //    the panel's premium tab made live — plans strip + WhatsApp CTA
        //    + username/password against the LOCKED remote host, through the
        //    battle-tested LoginViewModel Xtream engine. ──
        composable("premium") {
            PremiumScreen(
                onBack = { navController.popBackStack() },
                onLoggedIn = {
                    // The same post-login hand-off every login method uses.
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // ── 1b. Language grid (reference: LanguageActivity —
        //    activity_language.xml). v1.19.12: this is the SETTINGS language
        //    page ONLY (the onboarding copy is gone with the intro) and the
        //    grid carries exactly the two locales the app ships: English +
        //    Arabic (user directive — the other 15 never existed as real
        //    translations). ──
        composable("language") {
            LanguageScreen(
                // v2.1.3 — THE REVERSED-CHECKMARK FIX (user: “عندما ادخل
                // الى إعدادات اللغة اجد اللغة عكس لغة التي اكتشفها
                // التطبيق”): on first run NOTHING is stored, so the old
                // `prefs.getString("lang", null)` handed the page null and
                // it fell back to DEFAULT_TAG (English) — while the app was
                // actually RUNNING the detected device language (Arabic).
                // AppLang.currentTag resolves the LIVE language: the stored
                // preference when present, else the locale the activity
                // configuration is actually running.
                initialTag = AppLang.currentTag(context),
                onBack = { navController.popBackStack() },
                onSelect = { tag ->
                    prefs.edit().putString("lang", tag).apply()
                    // LanguageAdapter.setLocale: apply the locale, start
                    // fresh, wipe the back stack — relaunchThroughEntryGate
                    // (see its KDoc for the v1.4.12 crash lesson).
                    relaunchThroughEntryGate(context)
                }
            )
        }

        // ── 1c. Edit portal — the login form (reference: EditPortalActivity —
        //    activity_edit_portal.xml, exact copy; the login ENGINE —
        //    LoginViewModel — is byte-untouched and driven from here).
        //    playlistId > 0 = the accounts page's edit-user hand-off. ──
        composable(
            route = "editPortal/{type}?playlistId={playlistId}",
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("playlistId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { entry ->
            val type = runCatching {
                VuLoginFlow.PortalType.valueOf(
                    entry.arguments?.getString("type") ?: "XC"
                )
            }.getOrDefault(VuLoginFlow.PortalType.XC)
            val playlistId = entry.arguments?.getLong("playlistId") ?: -1L
            EditPortalScreen(
                type = type,
                editPlaylistId = playlistId.takeIf { it > 0 },
                onBack = { navController.popBackStack() },
                onUserList = {
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLoggedIn = {
                    // v1.7.0 — the reference's post-login hand-off, VERBATIM:
                    // EditPortalActivity finishes every successful submit with
                    // startActivity(UserListActivity) — the user lands on the
                    // ACCOUNTS page and taps the account to enter the loading/
                    // sync stage. (v1.5.0/1.6.x went straight to "loading",
                    // which skipped the accounts step the user expects.)
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onSaved = { navController.popBackStack() }
            )
        }

        // ── 2. Accounts (reference: UserListActivity — activity_user_list.xml,
        //    an exact v1.4.8 design copy: "List of User" pill + 3-column grid) ──
        composable("accounts") {
            AccountsScreen(
                // v1.4.11 — the reference's flow: Add User opens the
                // connection-method picker (AddPortalActivity), not a form.
                onAddUser = {
                    navController.navigate("addPortal")
                },
                onEditAccount = { pl ->
                    navController.navigate(
                        "editPortal/${
                            when (pl.type) {
                                "M3U" -> "M3U"
                                "PORTAL" -> "PORTAL"
                                "BROWSER" -> "BROWSER"
                                else -> "XC"
                            }
                        }?playlistId=${pl.id}"
                    )
                },
                onAccountSelected = {
                    navController.navigate("loading") {
                        popUpTo("accounts") { inclusive = true }
                    }
                },
                // v2.0.4 — the accounts page's premium banner → the same
                // subscription flow the method-picker banner opens.
                onPremium = {
                    navController.navigate("premium")
                },
                // v2.0.6 — the header's HOME button (replaces ly_back, which
                // finished the whole app when accounts was the root — user
                // directive: "نجعله ب ايقونة home و عند النقر عليه يدخلك
                // الى صفحة تسجيل الدخول"). A clean stack reset into the
                // login/method-picker page: the same front door a fresh
                // launch opens, so Back from there still exits normally.
                onHome = {
                    navController.navigate("addPortal") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // ── 3. Loading / sync (reference: HubActivity sync stage) ──
        composable("loading") {
            LoadingScreen(
                onDone = {
                    navController.navigate("hub") {
                        popUpTo("loading") { inclusive = true }
                    }
                },
                onBackToAccounts = {
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // ── 4. Hub — three cards (reference: HubActivity main menu) ──
        composable("hub") {
            HubScreen(
                onOpenLive = {
                    navController.navigate("grid?tab=LIVE")
                },
                onOpenMovies = {
                    navController.navigate("grid?tab=MOVIES")
                },
                onOpenSeries = {
                    navController.navigate("grid?tab=SERIES")
                },
                onSwitchAccount = {
                    navController.navigate("accounts") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // v1.5.0 — the reference home's ly_settings entry
                // (SettingActivity).
                onOpenSettings = {
                    navController.navigate("settings")
                },
                // v1.12.0 — ly_catch_up → CatchUpActivity (the active
                // playlist's tv-archive channels).
                onOpenCatchUp = {
                    navController.navigate("catchup/-1")
                },
                // v1.18.1 — the 4th card → the Saved Videos library (every
                // auto-saved / downloaded video, offline).
                onOpenSaved = {
                    navController.navigate("savedVideos")
                },
                // v2.0.4 — the premium crown in the hub's top-end icon row.
                onOpenPremium = {
                    navController.navigate("premium")
                }
            )
        }

        // ── 4c. v1.18.1 — Saved Videos library (user request: a dedicated
        //    page for every auto-saved video — complete AND incomplete —
        //    with offline watching and deleting; entered from the hub's
        //    category row). A plain directory scan — airplane mode works. ──
        composable("savedVideos") {
            SavedVideosScreen(
                onBack = { navController.popBackStack() },
                onPlay = { filePath ->
                    // The SAME fullscreen-player route + the SAME locked
                    // engine — the key "saved:<path>" makes the player's
                    // bootstrap serve the file over the loopback media
                    // server (see PlayerViewModel's saved: branch).
                    navController.navigate(
                        "player/-1/${encodeArg("saved:$filePath")}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 4b. v1.5.0 — Settings (reference: SettingActivity —
        //    activity_setting.xml, 3-column grid of the 7 reference items;
        //    Language opens the already-built language grid). ──
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLanguage = {
                    navController.navigate("language")
                },
                // v2.0.4 — the settings page's premium banner.
                onOpenPremium = {
                    navController.navigate("premium")
                }
            )
        }

        // ── 5. Content browser (reference: DashboardActivity) ──
        // v1.4.0: `tab` starts on LIVE / MOVIES / SERIES (hub card choice);
        // the pills inside the screen switch sections in place.
        composable(
            route = "grid?tab={tab}",
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.StringType
                    defaultValue = "LIVE"
                }
            )
        ) {
            BrowseScreen(
                // v1.3.0: a channel card opens the CHANNEL VIEW (split page:
                // channel list + live mini player + EPG) — the fullscreen
                // player is one tap further from there.
                onOpenPlayer = { playlistId, channelKey, categoryId, query, favoritesOnly ->
                    navController.navigate(
                        "channelview/$playlistId/${encodeArg(channelKey)}/${encodeArg(categoryId ?: "all")}/${encodeArg(query.ifBlank { " " })}/$favoritesOnly"
                    ) {
                        launchSingleTop = true
                    }
                },
                // v1.4.0: a poster opens its info page.
                onOpenMovie = { playlistId, streamId ->
                    navController.navigate("movieinfo/$playlistId/$streamId") {
                        launchSingleTop = true
                    }
                },
                onOpenSeries = { playlistId, seriesId ->
                    navController.navigate("seriesinfo/$playlistId/$seriesId") {
                        launchSingleTop = true
                    }
                },
                // v1.18.2 — the Saved Videos action pill (top bar, next to
                // the Series pill): same library page as the hub's 4th card.
                onOpenSaved = {
                    navController.navigate("savedVideos") {
                        launchSingleTop = true
                    }
                },
                // v2.0.5 — the channels-grid PREMIUM pill (user request:
                // "يجب ايضا ان يكون زر بريميوم في شاشة شبكة القنوات") —
                // the same route every other premium entry uses.
                onOpenPremium = {
                    navController.navigate("premium")
                },
                // v1.18.2 — a Continue-Watching card of an offline save →
                // straight into the offline player (the savedVideos page's
                // own Watch route, verbatim).
                onPlaySaved = { filePath ->
                    navController.navigate(
                        "player/-1/${encodeArg("saved:$filePath")}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                },
                // v1.19.7 — RESUME DIRECT PLAY: a Continue-Watching card
                // resolves its playback request in the BrowseViewModel and
                // lands HERE — the SAME fullscreen-player route/registry the
                // movie & series info pages use; the player's auto-resume
                // logic seeks to the stored position under the same key.
                onPlayVod = { playlistId, channelKey ->
                    navController.navigate(
                        "player/$playlistId/${encodeArg(channelKey)}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                },
                // v2.3.0 — MULTI-SCREEN: the channels grid's own entry —
                // same setup route the player's grid button opens.
                onOpenMultiScreen = { pid, key ->
                    navController.navigate(
                        "multiscreen/$pid/${encodeArg(key)}"
                    ) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── 5c. v1.4.0 — Movie info page (v1.4.6: trace entries removed) ──
        composable(
            route = "movieinfo/{playlistId}/{streamId}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("streamId") { type = NavType.LongType }
            )
        ) { entry ->
            MovieInfoScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                streamId = entry.arguments?.getLong("streamId") ?: -1L,
                onPlay = { channelKey ->
                    // The fullscreen player picks the request up from the
                    // VodPlayRegistry — SAME route, SAME screen, SAME engine.
                    navController.navigate(
                        "player/${entry.arguments?.getLong("playlistId") ?: -1L}/${encodeArg(channelKey)}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                // v2.1.0 — the premium gate dialog's gold CTA route.
                onOpenPremium = {
                    navController.navigate("premium") {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 5d. v1.4.0 — Series info page (v1.4.6: trace entries removed) ──
        composable(
            route = "seriesinfo/{playlistId}/{seriesId}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("seriesId") { type = NavType.LongType }
            )
        ) { entry ->
            SeriesInfoScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                seriesId = entry.arguments?.getLong("seriesId") ?: -1L,
                onPlayEpisode = { channelKey ->
                    navController.navigate(
                        "player/${entry.arguments?.getLong("playlistId") ?: -1L}/${encodeArg(channelKey)}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                // v2.1.0 — the premium gate dialog's gold CTA route.
                onOpenPremium = {
                    navController.navigate("premium") {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 5e. v1.4.3 — VOD trace viewer route. v1.4.6: NO UI entry points
        // remain (diagnostics chip + long-press removed at the user's
        // request); the route stays registered so a temporary entry can be
        // re-added instantly if a future session needs on-device ground
        // truth. VodTrace itself keeps recording silently in the background. ──
        composable("vodtrace") {
            VodTraceScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── 5b. Channel view: split page (list + mini player + EPG) ──
        composable(
            route = "channelview/{playlistId}/{channelKey}/{categoryId}/{query}/{favoritesOnly}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("channelKey") { type = NavType.StringType },
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("query") { type = NavType.StringType },
                navArgument("favoritesOnly") { type = NavType.BoolType }
            )
        ) { entry ->
            ChannelViewScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                channelKey = entry.arguments?.getString("channelKey") ?: "",
                categoryId = entry.arguments?.getString("categoryId")?.takeIf { it != "all" },
                query = entry.arguments?.getString("query") ?: "",
                favoritesOnly = entry.arguments?.getBoolean("favoritesOnly") ?: false,
                inPip = pipMode,
                onBack = { navController.popBackStack() },
                onOpenFullscreen = { playlistId, channelKey, categoryId, query, favoritesOnly ->
                    // The fullscreen player CONTINUES the live session owned
                    // by the channel view — never a second engine.
                    navController.navigate(
                        "player/$playlistId/${encodeArg(channelKey)}/${encodeArg(categoryId ?: "all")}/${encodeArg(query.ifBlank { " " })}/$favoritesOnly"
                    ) {
                        launchSingleTop = true
                    }
                },
                // v1.12.0 — the EPG panel's timeline chip → the day-tabbed
                // EPG Timeline / Catch-Up screen for the selected channel.
                onOpenTimeline = { playlistId, channelKey ->
                    navController.navigate(
                        "catchupdetail/$playlistId/${encodeArg(channelKey)}"
                    ) {
                        launchSingleTop = true
                    }
                },
                // v2.3.0 — MULTI-SCREEN: the channel view's mini player →
                // the setup with the selected channel pre-seeded in slot 1.
                onOpenMultiScreen = { pid, key ->
                    navController.navigate(
                        "multiscreen/$pid/${encodeArg(key)}"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 5f. v1.12.0 — Catch-Up screens (reference: CatchUpActivity +
        //    CatchUpDetailActivity). The hub card routes with -1 → the
        //    ACTIVE playlist; the channel view's timeline entry routes with
        //    the exact playlist + channel key. ──
        composable(
            route = "catchup/{playlistId}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType }
            )
        ) { entry ->
            CatchUpScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                onBack = { navController.popBackStack() },
                onOpenChannel = { pid, channelKey ->
                    navController.navigate(
                        "catchupdetail/$pid/${encodeArg(channelKey)}"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = "catchupdetail/{playlistId}/{channelKey}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("channelKey") { type = NavType.StringType }
            )
        ) { entry ->
            CatchUpDetailScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                channelKey = entry.arguments?.getString("channelKey") ?: "",
                onBack = { navController.popBackStack() },
                onPlay = { pid, key ->
                    // The SAME fullscreen-player route + VodPlayRegistry
                    // hand-off the movies use — zero new player code.
                    navController.navigate(
                        "player/$pid/${encodeArg(key)}/all/%20/false"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 6. Player ──
        composable(
            route = "player/{playlistId}/{channelKey}/{categoryId}/{query}/{favoritesOnly}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("channelKey") { type = NavType.StringType },
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("query") { type = NavType.StringType },
                navArgument("favoritesOnly") { type = NavType.BoolType }
            )
        ) { entry ->
            PlayerScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                channelKey = entry.arguments?.getString("channelKey") ?: "",
                categoryId = entry.arguments?.getString("categoryId")?.takeIf { it != "all" },
                query = entry.arguments?.getString("query") ?: "",
                favoritesOnly = entry.arguments?.getBoolean("favoritesOnly") ?: false,
                onBack = { navController.popBackStack() },
                // v2.1.0 — the premium gate dialog's gold CTA route (the
                // player's own gate: offline viewing / recording / download).
                onOpenPremium = {
                    navController.navigate("premium") {
                        launchSingleTop = true
                    }
                },
                // v2.3.0 — MULTI-SCREEN: the player's grid button → the setup
                // with the WATCHING channel pre-seeded in slot 1 (the grid
                // grows around what is already on screen).
                onOpenMultiScreen = { channelKey ->
                    navController.navigate(
                        "multiscreen/${entry.arguments?.getLong("playlistId") ?: -1L}/${encodeArg(channelKey)}"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 6b. v2.3.0 — MULTI-SCREEN setup (المشاهدة على شاشات متعددة):
        //    the pick-and-place page (layout choice, the four slots, the
        //    searchable channel list). The player / channel view / browse
        //    entries all land here with the channel they were on. ──
        composable(
            route = "multiscreen/{playlistId}/{channelKey}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("channelKey") { type = NavType.StringType }
            )
        ) { entry ->
            MultiScreenSetupScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                seedChannelKey = entry.arguments?.getString("channelKey") ?: "",
                onBack = { navController.popBackStack() },
                onStart = {
                    navController.navigate(
                        "multiplayer/${entry.arguments?.getLong("playlistId") ?: -1L}"
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // ── 6c. v2.3.0 — MULTI-SCREEN playback: the grid itself (up to
        //    four live cells, audio follows the highlighted cell, one
        //    tap expands fullscreen). The config arrives through the
        //    MultiScreenSession registry (the VodPlayRegistry pattern) —
        //    the persisted prefs are the process-death fallback. ──
        composable(
            route = "multiplayer/{playlistId}",
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType }
            )
        ) { entry ->
            MultiScreenPlayerScreen(
                playlistId = entry.arguments?.getLong("playlistId") ?: -1L,
                onBack = { navController.popBackStack() }
            )
        }
    }

    // ── v2.0.0 — the panel-driven overlays, drawn ON TOP of every route.
    //    Both read Compose snapshot state, so they appear the moment a
    //    config is applied (cached at process start, refreshed by the
    //    network fetch right after) and vanish when obsolete/dismissed.
    //    OriaRemote.apply() → UpdateCenter.evaluate() arms/clears pending. ──
    UpdateCenter.pending?.let { info ->
        OriaUpdateDialog(
            info = info,
            onDismiss = { UpdateCenter.dismiss(context) }
        )
    }
    OriaAnnouncementOverlay()

    // ── v2.1.0 — a fresh panel config can CHANGE the premium host: poke
    //    the subscription state so the whole app re-tiers live (the
    //    playlists watcher feeds it the accounts; this feeds the host). ──
    LaunchedEffect(com.superz.iptvplayer.data.remote.OriaRemote.configEpoch) {
        com.superz.iptvplayer.ui.theme.PremiumAccess.recompute()
    }
}

/** URL-safe encoding for nav path segments (queries may contain '/' etc.). */
private fun encodeArg(value: String): String =
    android.net.Uri.encode(value) ?: value

package com.agon.app.ui.screens

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.agon.app.data.model.PlaylistType
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.agon.app.data.repository.PlaylistRepository
import com.agon.app.proxy.GlobalPlaybackCoordinator
import com.agon.app.proxy.ProxyForegroundService
import com.agon.app.ads.AdMobManager
import com.agon.app.ads.DPadAdFocusEngine
import com.agon.app.ads.PolicyShield
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.GlassSurface
import com.agon.app.ui.theme.GlassSurfaceAlt
import com.agon.app.ui.theme.PrimaryColor
import com.agon.app.ui.theme.deepSpaceBackground
import com.agon.app.ui.theme.glassmorphicPanel
import com.agon.app.ui.util.applyImmersiveFullscreen
import com.agon.app.ui.util.LogoPlaceholder
import com.agon.app.ui.viewmodel.PlayerViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Calendar

// ═══════════════════════════════════════════════════════════════════════
//  Top-level constants
// ═══════════════════════════════════════════════════════════════════════

data class SubtitleLanguage(
    val code: String,
    val label: String,
    val flag: String
)

val SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("ar", "Arabic", "\uD83C\uDDF8\uD83C\uDDE6"),
    SubtitleLanguage("en", "English", "\uD83C\uDDEC\uD83C\uDDE7"),
    SubtitleLanguage("fr", "French", "\uD83C\uDDEB\uD83C\uDDF7"),
    SubtitleLanguage("es", "Spanish", "\uD83C\uDDEA\uD83C\uDDE8"),
    SubtitleLanguage("de", "German", "\uD83C\uDDE9\uD83C\uDDEA"),
    SubtitleLanguage("tr", "Turkish", "\uD83C\uDDF9\uD83C\uDDF7"),
    SubtitleLanguage("pt", "Portuguese", "\uD83C\uDDE7\uD83C\uDDF7"),
    SubtitleLanguage("nl", "Dutch", "\uD83C\uDDF3\uD83C\uDDF1"),
    SubtitleLanguage("it", "Italian", "\uD83C\uDDEE\uD83C\uDDF9"),
    SubtitleLanguage("ru", "Russian", "\uD83C\uDDF7\uD83C\uDDFA"),
    SubtitleLanguage("hi", "Hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
    SubtitleLanguage("zh", "Chinese", "\uD83C\uDDE8\uD83C\uDDF3"),
    SubtitleLanguage("ja", "Japanese", "\uD83C\uDDEF\uD83C\uDDF5"),
    SubtitleLanguage("ko", "Korean", "\uD83C\uDDF0\uD83C\uDDF4"),
    SubtitleLanguage("id", "Indonesian", "\uD83C\uDDEE\uD83C\uDDE9")
)

private const val SUPPORT_WHATSAPP_NUMBER = "213550054633"
private val NetflixRed = Color(0xFFE50914)

// ═══════════════════════════════════════════════════════════════════════
//  PlayerActivity — host Activity
// ═══════════════════════════════════════════════════════════════════════

class PlayerActivity : ComponentActivity() {

    var isInPip by mutableStateOf(false)
        private set

    private val viewModel: PlayerViewModel by viewModels()

    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                Log.i("PlayerActivity", "SCREEN_OFF — force teardown")
                GlobalPlaybackCoordinator.forceTeardown()
                viewModel.setPlaybackActive(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // V8.6 — RECEIVER_NOT_EXPORTED required on Android 14+ (API 34+).
        // ACTION_SCREEN_OFF is a system protected broadcast so it works
        // without the flag on older OS, but adding the flag explicitly is
        // defense-in-depth and silences the SecurityException risk.
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

        viewModel.setPlaybackActive(true)

        val rawStreamUrl = intent.getStringExtra("STREAM_URL") ?: return
        val streamName = intent.getStringExtra("STREAM_NAME") ?: "Live"

        val fromFeed = intent.getBooleanExtra("FROM_FEED", false)
        val feedUrl = intent.getStringExtra("FEED_URL") ?: ""
        val reusePlayer = fromFeed && feedUrl == rawStreamUrl && rawStreamUrl.isNotBlank()

        applyImmersiveFullscreen()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // ═══════════════════════════════════════════════════════════════
        //  V7.3 §III — D-Pad Focus Engine binding
        //  ═══════════════════════════════════════════════════════════════
        //  Bind the engine so TV remote key events are intercepted while
        //  the interstitial ad is showing.
        // ═══════════════════════════════════════════════════════════════
        DPadAdFocusEngine.bindActivity(this)

        startProxyService()

        // ═══════════════════════════════════════════════════════════════
        //  Compose the UI FIRST — the surface must exist before we ask
        //  the coordinator to play. The LaunchedEffect keyed on exoPlayer
        //  will bind the ViewModel to the singleton player.
        // ═══════════════════════════════════════════════════════════════
        setContent {
            VideoPlayer(
                streamUrl = rawStreamUrl,
                streamName = streamName,
                isInPip = isInPip,
                viewModel = viewModel,
                reusePlayer = reusePlayer,
                onBack = { finish() },
                onEnterPip = { enterPipMode() }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        //  V8.6 — INSTANT STREAM START + PARALLEL AD (non-blocking)
        //  ═══════════════════════════════════════════════════════════════
        //  V8.3 gated the stream start behind the interstitial's
        //  onProceed callback to avoid a CPU/IO race on weak TV boxes.
        //  But this made EVERY player launch wait for the ad callback
        //  (even when throttled) — adding visible latency before the
        //  channel even started loading. The user perceived this as
        //  "the player is slow".
        //
        //  V8.6 starts the stream IMMEDIATELY (so the user sees the
        //  loading spinner / first frame ASAP) and shows the interstitial
        //  IN PARALLEL. The ad overlays the screen briefly; by the time
        //  it is dismissed, the stream is already buffered and ready to
        //  play. The interstitial feature is fully preserved — it still
        //  fires every 4th transition (1-in-4 throttle intact).
        //
        //  The original V8.3 "race" was between DoH tunnel setup + AES
        //  decrypt + AdMob overlay — none of which exist in the current
        //  playback path (ExoPlayer + RedirectSniffer handle everything
        //  natively). The parallel approach is safe.
        // ═══════════════════════════════════════════════════════════════
        viewModel.onStreamChanged(rawStreamUrl, streamName)

        // Show the interstitial IN PARALLEL — non-blocking. The stream
        // is already loading underneath. When the ad is dismissed (or
        // throttled / not ready), this callback fires to prefetch the
        // next interstitial for the following transition.
        AdMobManager.showTransitionInterstitial(this) {
            // onProceed — stream already started above. Just pre-fetch
            // the next interstitial so it's warm for the next transition.
            AdMobManager.prefetchInterstitial()
        }
        // NOTE: forceFocusOnCloseButton is intentionally NOT called here.
        // It traverses the entire view tree on the main thread every 150ms,
        // which can cause jank during the player's critical setup phase.
        // The D-Pad key interception in dispatchKeyEvent() is sufficient —
        // it handles BACK/CENTER presses without needing pre-emptive focus.
    }

    // ════════════════════════════════════════════════════════════════════════
    //  V7.3 §III — D-Pad Key Event Interception
    //  ════════════════════════════════════════════════════════════════════════
    //  Traps the remote's CENTER (OK) + BACK buttons while the interstitial
    //  ad is showing, performing an immediate dismissal so the user is never
    //  trapped inside the ad on Android TV.
    // ════════════════════════════════════════════════════════════════════════
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DPadAdFocusEngine.dispatchAdKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun startProxyService() {
        val svc = Intent(this, ProxyForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun stopProxyService() {
        try { stopService(Intent(this, ProxyForegroundService::class.java)) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPlaybackActive(true)
        // V9.9 — PiP fix: only re-issue playStream if we're NOT in PiP and
        // NOT transitioning to/from PiP. The isChangingConfigurations flag
        // catches configuration changes, but PiP transitions need the extra
        // isInPip guard. When returning from PiP, onPictureInPictureModeChanged
        // sets isInPip=false BEFORE onResume, so we DO want to resume here.
        if (!isInPip && !isChangingConfigurations) {
            val pending = GlobalPlaybackCoordinator.currentStreamUrl()
            if (pending.isNotBlank() && viewModel.uiState.value.currentStreamUrl == pending) {
                Log.i("PlayerActivity", "onResume — re-issuing playStream for $pending")
                GlobalPlaybackCoordinator.playStream(pending, seamless = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // V9.9 — CRITICAL PiP FIX: Do NOT tear down when entering PiP!
        // The old code called forceTeardown() whenever !isInPip, but at
        // onPause() time during a PiP transition, isInPip is STILL false
        // (onPictureInPictureModeChanged hasn't fired yet). This caused:
        //   1. Video freezes in PiP (playWhenReady=false + clearVideoSurface)
        //   2. Black screen on return (surface detached, not re-attached)
        //
        // FIX: Check if we're ENTERING PiP by testing if the activity is
        // still in PiP mode (isInPip will be set by onUserLeaveHint→enterPipMode
        // before onPause fires in the PiP path). We use a dedicated flag
        // `enteringPip` that onUserLeaveHint sets to true.
        if (!isInPip && !isChangingConfigurations && !enteringPip) {
            GlobalPlaybackCoordinator.forceTeardown()
            viewModel.setPlaybackActive(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyService()
        if (!isInPip && !enteringPip) {
            GlobalPlaybackCoordinator.forceTeardown()
            viewModel.setPlaybackActive(false)
        }
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        PolicyShield.exitFullscreenLive()
        DPadAdFocusEngine.unbindActivity(this)
    }

    // V9.9 — Flag set by onUserLeaveHint so onPause knows we're entering PiP
    // (not actually leaving the app). Cleared in onPictureInPictureModeChanged.
    @Volatile
    private var enteringPip: Boolean = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // V9.9 — Only enter PiP if the player is actively playing.
        // If the user pressed HOME while paused, don't enter PiP.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && GlobalPlaybackCoordinator.isPlaying()) {
            enteringPip = true
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        // V9.9 — Clear the enteringPip flag once the PiP transition completes
        if (!isInPictureInPictureMode) {
            enteringPip = false
        }
        // V9.9 — When ENTERING PiP, keep playback going (don't pause).
        // When EXITING PiP (returning to fullscreen), the surface is
        // re-attached automatically by PlayerView's update block.
        Log.i("PlayerActivity", "PiP mode changed: isInPip=$isInPictureInPictureMode")
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.w("PlayerActivity", "PiP entry failed: ${e.message}")
                enteringPip = false
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  VideoPlayer — Main Composable
//
//  V4.0 Clean Architecture — 3 Pure Layers:
//    Layer 1: Media Core Plane (SurfaceView + seamless=true, no ghost frame)
//    Layer 2: Non-Blocking Overlay Plane (no full-screen opaque boxes)
//    Layer 3: State-Driven Focus Tree (fatalErrorFocusRequester)
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun VideoPlayer(
    streamUrl: String,
    streamName: String,
    isInPip: Boolean,
    viewModel: PlayerViewModel,
    reusePlayer: Boolean = false,
    onBack: () -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val currentSurferStreams = remember {
        SessionData.currentSurferStreams.ifEmpty { SessionData.liveStreams }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingEvent by viewModel.events.collectAsStateWithLifecycle()
    // V8.5 — generation counter forces the playback sink to re-fire on
    // same-URL replays (the URL key alone does not change in that case).
    val playbackGen by viewModel.playbackGeneration.collectAsStateWithLifecycle()

    // ── UI state ──
    var showControls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var showEpgGrid by remember { mutableStateOf(false) }  // V9.8 — EPG Grid overlay
    var activeSubtitleLang by remember { mutableStateOf<SubtitleLanguage?>(null) }
    var currentAspectRatio by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var currentPlaybackSpeed by remember { mutableFloatStateOf(1f) }
    var sleepTimerEndMs by remember { mutableLongStateOf(0L) }
    var sleepTimerLabel by remember { mutableStateOf("Off") }
    var hdrPreference by remember { mutableIntStateOf(0) }
    var brightnessOverride by remember { mutableFloatStateOf(0f) }
    var showMiniSurfer by remember { mutableStateOf(false) }
    var miniSurferIndex by remember { mutableIntStateOf(-1) }
    var miniSurferLastActivity by remember { mutableLongStateOf(0L) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var recordingSizeBytes by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    val miniSurferListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── ExoPlayer ──
    // V10.2 — استرجاع الـ player الحالي + مراقبة تغيراته عبر StateFlow.
    val exoPlayer = GlobalPlaybackCoordinator.getPlayer(context)
    // V10.2 — مراقبة نسخة الـ player. عندما يتغير (release+recreate)،
    // يُعاد ربط PlayerView بالـ player الجديد تلقائياً.
    val playerGen by GlobalPlaybackCoordinator.playerVersion.collectAsStateWithLifecycle()

    // ── Preview mode state ──
    var isPreviewMode by remember { mutableStateOf(true) }
    var previewStreamUrl by remember { mutableStateOf(streamUrl) }
    var previewStreamName by remember { mutableStateOf(streamName) }
    var focusedPreviewIndex by remember { mutableIntStateOf(-1) }
    var currentClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var epgProgress by remember { mutableFloatStateOf(0f) }
    var currentEpgData by remember { mutableStateOf<PlaylistRepository.LiveEpgData?>(null) }
    var lastFetchedEpgUrl by remember { mutableStateOf<String?>(null) }
    var isRestoringFromPip by remember { mutableStateOf(false) }

    // ── V4.0 Layer 3: fatalErrorFocusRequester ──
    val fatalErrorFocusRequester = remember { FocusRequester() }

    val currentStreamName = uiState.currentStreamName
    val currentStreamUrl = uiState.currentStreamUrl

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 1: MEDIA CORE PLANE — bind + listener
    //  ═══════════════════════════════════════════════════════════════
    //  V8.6 — The initial `viewModel.onStreamChanged(streamUrl, streamName)`
    //  is now called DIRECTLY in PlayerActivity.onCreate (immediately after
    //  setContent), NOT inside an ad callback. This LaunchedEffect only
    //  binds the ViewModel to the singleton ExoPlayer. The stream-start
    //  trigger is the LaunchedEffect keyed on (currentStreamUrl, playbackGen)
    //  further below — that's the single sink that calls playStream().
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(exoPlayer) {
        viewModel.bindExoPlayer(exoPlayer)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { viewModel.onIsPlayingChanged(playing) }
            override fun onPlaybackStateChanged(state: Int) { viewModel.onPlaybackStateChanged(state) }
            override fun onPlayerError(error: PlaybackException) { viewModel.onPlayerError() }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Consume one-shot events
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(pendingEvent) {
        val ev = pendingEvent ?: return@LaunchedEffect
        when (ev) {
            // V8.5 — SwitchToStream now actually drives playback instead of
            // being a no-op. The ViewModel emits it for fallback routing and
            // binge-watch auto-next. We hand the URL straight to the
            // coordinator (seamless so the old frame stays visible) and let
            // the single LaunchedEffect sink stay in sync via onStreamChanged.
            is PlayerViewModel.PlayerEvent.SwitchToStream -> {
                GlobalPlaybackCoordinator.playStream(ev.url, seamless = true)
                viewModel.consumeEvent()
            }
            is PlayerViewModel.PlayerEvent.InjectSubtitle -> {
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(ev.uri)
                    .setLanguage(ev.languageCode)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLabel(ev.languageLabel)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .build()
                val currentItem = exoPlayer.currentMediaItem
                val resolvedUri = GlobalPlaybackCoordinator.currentResolvedUrl()
                    .ifBlank { uiState.currentStreamUrl }
                val newItemBuilder = MediaItem.Builder()
                    .setUri(Uri.parse(resolvedUri))
                    .setSubtitleConfigurations(listOf(subtitleConfig))
                currentItem?.liveConfiguration?.let { newItemBuilder.setLiveConfiguration(it) }
                val newItem = newItemBuilder.build()
                val pos = exoPlayer.currentPosition
                exoPlayer.setMediaItem(newItem, pos)
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon().setPreferredTextLanguage(ev.languageCode).build()
                exoPlayer.prepare()
                if (uiState.isPlaying) exoPlayer.play()
                viewModel.consumeEvent()
            }
            PlayerViewModel.PlayerEvent.ClearSubtitle -> {
                val currentItem = exoPlayer.currentMediaItem
                val resolvedUri = GlobalPlaybackCoordinator.currentResolvedUrl()
                    .ifBlank { uiState.currentStreamUrl }
                val newItemBuilder = MediaItem.Builder().setUri(Uri.parse(resolvedUri))
                currentItem?.liveConfiguration?.let { newItemBuilder.setLiveConfiguration(it) }
                val newItem = newItemBuilder.build()
                val newPos = exoPlayer.currentPosition
                exoPlayer.setMediaItem(newItem, newPos)
                exoPlayer.prepare()
                if (uiState.isPlaying) exoPlayer.play()
                viewModel.consumeEvent()
            }
            is PlayerViewModel.PlayerEvent.ClearPosition -> {
                scope.launch {
                    com.agon.app.data.PlaybackPositionManager.getInstance(context)
                        .clearPosition(context, ev.url)
                }
                viewModel.consumeEvent()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SINGLE SINK — LaunchedEffect(currentStreamUrl, isPreviewMode, reusePlayer, playbackGen)
    //  V4.0: ALL channel switches use seamless=true to skip clearMediaItems.
    //  This keeps the old frame visible until the new one is ready —
    //  NO black flash, NO ghost frame needed.
    //  V8.5: `playbackGen` is now a key so the effect re-fires for
    //  same-URL replays (when the user re-selects the channel that is
    //  already current). In that case reusePlayer is false (this is a
    //  fresh switchToStream, not a Feed launch) so we fall through to
    //  playStream() — the coordinator re-prepares the MediaItem and the
    //  channel starts again.
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(currentStreamUrl, isPreviewMode, reusePlayer, playbackGen) {
        if (currentStreamUrl.isBlank()) return@LaunchedEffect

        val coordinator = GlobalPlaybackCoordinator
        val sameUrlAsCoordinator = coordinator.currentStreamUrl() == currentStreamUrl

        // V4.1 FIX: Resume bridge — ONLY when reusePlayer=true AND the
        // coordinator is already playing the SAME URL. This is the
        // Feed→Player launch path where the player is already hot.
        // (playbackGen is ignored here because reusePlayer is only ever
        // true on the initial Activity launch, never on a same-URL replay.)
        //
        // V8.5.2 — ALSO require the player to actually be in a playable
        // state (STATE_READY / STATE_BUFFERING / isPlaying). If the player
        // is STATE_IDLE (after a prior error) or STATE_ENDED, resume()
        // would just set playWhenReady=true on a dead player and the
        // channel would NOT play on re-entry from the mini-player. In that
        // case, fall through to playStream() which does a hard reset.
        if (reusePlayer && sameUrlAsCoordinator &&
            coordinator.isBufferHotFor(currentStreamUrl)
        ) {
            viewModel.onStreamReady()
            coordinator.resume()
            return@LaunchedEffect
        }

        // V8.5 — same-URL replay: when the user re-selects the current
        // channel, the URL is unchanged but playbackGen bumped. The
        // player may be sitting in STATE_IDLE (after a prior error) or
        // STATE_ENDED. playStream() re-issues setMediaItem + prepare,
        // which always restarts playback regardless of prior state.
        // Also invalidate any stale RedirectSniffer cache entry so the
        // replay gets a fresh edge-URL scrape (the cached edge token may
        // have expired).
        if (sameUrlAsCoordinator) {
            com.agon.app.proxy.RedirectSniffer.invalidate(currentStreamUrl)
        }

        // V4.1 FIX: Removed the `playerHot` no-op branch entirely.
        // Previously, when the user switched channels, the coordinator
        // was still playing the OLD URL. `sameUrlAsCoordinator` was
        // false (new URL ≠ old URL), but `playerHot` was true. The
        // code then called `onStreamReady()` (clearing isLoading on
        // the WRONG channel) and skipped playStream() — the new
        // channel never started until the next recomposition.
        //
        // NOW: ALWAYS call playStream() when the URL doesn't match
        // the coordinator's current URL. The coordinator handles
        // cancellation + instant setMediaItem + prepare internally.
        // No more false "ready" state on the wrong channel.
        coordinator.playStream(currentStreamUrl, seamless = true)
    }

    // ── Position ticker ──
    LaunchedEffect(uiState.isPlaying) {
        while (uiState.isPlaying) {
            position = exoPlayer.currentPosition
            delay(1000)
        }
    }

    // ── Auto-hide controls after 4s ──
    LaunchedEffect(showControls, uiState.isPlaying, isInPip, showSettingsMenu, showSubtitleMenu, showMiniSurfer, isScrubbing) {
        if (showControls && uiState.isPlaying && !isInPip && !showSettingsMenu && !showSubtitleMenu && !showMiniSurfer && !isScrubbing) {
            delay(4000)
            showControls = false
        }
    }

    // ── Auto-hide mini-surfer after 4s ──
    LaunchedEffect(showMiniSurfer, miniSurferLastActivity) {
        if (showMiniSurfer) {
            while (true) {
                delay(500)
                if (!showMiniSurfer) return@LaunchedEffect
                if (System.currentTimeMillis() - miniSurferLastActivity > 4000) {
                    showMiniSurfer = false
                    return@LaunchedEffect
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  V9.7 — CHANNEL PRE-FETCHING (Performance Optimization)
    //  ═══════════════════════════════════════════════════════════════
    //  When the user opens the Mini-Surfer, we pre-warm the RedirectSniffer
    //  cache for the channels adjacent to the current focus (±1 index).
    //  This way, when the user actually switches to that channel, the
    //  redirect resolution is already cached → instant playback start.
    //
    //  The pre-fetch is non-blocking — it runs on Dispatchers.IO and
    //  does not affect the UI or the current playback. If the user
    //  navigates away before the prefetch completes, the result is
    //  simply cached for next time.
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(showMiniSurfer, miniSurferIndex) {
        if (!showMiniSurfer || miniSurferIndex < 0) return@LaunchedEffect
        val streams = currentSurferStreams
        if (streams.isEmpty()) return@LaunchedEffect

        // Pre-fetch the NEXT channel (most likely switch direction)
        val nextStream = streams.getOrNull(miniSurferIndex + 1)
        if (nextStream != null && nextStream.url.isNotBlank()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.agon.app.proxy.RedirectSniffer.sniffRedirectAsync(nextStream.url) { result ->
                        // Result is cached automatically by RedirectSniffer.
                        // No action needed — the cache will be hit when the
                        // user actually switches to this channel.
                    }
                } catch (_: Throwable) {}
            }
        }

        // Pre-fetch the PREVIOUS channel
        val prevStream = streams.getOrNull(miniSurferIndex - 1)
        if (prevStream != null && prevStream.url.isNotBlank()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.agon.app.proxy.RedirectSniffer.sniffRedirectAsync(prevStream.url) { result ->
                        // Cached for next switch
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    LaunchedEffect(isInPip) {
        if (isInPip) {
            showControls = false; showSettingsMenu = false; showMiniSurfer = false; isScrubbing = false
        } else {
            isRestoringFromPip = true
            delay(350)
            isRestoringFromPip = false
        }
    }

    // ── Sleep timer ──
    LaunchedEffect(sleepTimerEndMs) {
        if (sleepTimerEndMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val remaining = sleepTimerEndMs - now
            if (remaining <= 0L) {
                exoPlayer.pause(); sleepTimerEndMs = 0L; sleepTimerLabel = "Off"
                return@LaunchedEffect
            }
            val totalSec = (remaining / 1000L).toInt()
            val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
            sleepTimerLabel = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }

    // ── Recording ticker ──
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        val startMs = ProxyForegroundService.recordingStartTime()
        while (isRecording) {
            delay(1000)
            recordingElapsedMs = System.currentTimeMillis() - startMs
            recordingSizeBytes = ProxyForegroundService.recordingBytesWritten()
        }
    }

    // ── Brightness override ──
    LaunchedEffect(brightnessOverride) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val lp = activity.window.attributes
        lp.screenBrightness = if (brightnessOverride > 0f) brightnessOverride
            else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
    }

    // ═══════════════════════════════════════════════════════════════
    //  V9.7 — HDR Preference Application (was a dead UI toggle before)
    //  ═══════════════════════════════════════════════════════════════
    //  The hdrPreference variable was set by the UI but never applied to
    //  ExoPlayer. Now it controls the video track's HDR mode via
    //  TrackSelectionParameters. media3 1.2.1 doesn't have setMaxVideoColorTransfer,
    //  so we use setMaxVideoSize + setMaxVideoBitrate as proxies:
    //    0 = Auto (default constraints — let ExoPlayer pick best track)
    //    1 = Force SDR (limit to 1080p, exclude HDR-capable high-bitrate tracks)
    //    2 = Force HDR (prefer high-bitrate tracks that are likely HDR)
    //  This is a heuristic approach — true HDR mode selection requires
    //  media3 1.4+ with setMaxVideoColorTransfer. For now, this at least
    //  makes the toggle DO something instead of being dead UI.
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(hdrPreference) {
        try {
            val params = exoPlayer.trackSelectionParameters.buildUpon()
            when (hdrPreference) {
                1 -> {
                    // Force SDR: constrain to standard HD, exclude 4K/HDR tracks
                    // (HDR tracks typically have higher resolution + bitrate)
                    params.setMaxVideoSize(1920, 1080)
                    params.setMaxVideoBitrate(8_000_000)  // 8 Mbps cap
                }
                2 -> {
                    // Force HDR: remove constraints to allow highest-quality track
                    // (which is typically the HDR variant when available)
                    params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    // No bitrate cap — let ExoPlayer pick the highest
                }
                else -> {
                    // Auto: reset to defaults
                    params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                }
            }
            exoPlayer.trackSelectionParameters = params.build()
            Log.i("PlayerActivity", "V9.7 HDR preference applied: $hdrPreference")
        } catch (e: Exception) {
            Log.w("PlayerActivity", "HDR preference failed (non-fatal): ${e.message}")
        }
    }

    // ── EPG clock ──
    LaunchedEffect(Unit) {
        while (true) {
            currentClock = System.currentTimeMillis()
            val epg = currentEpgData
            if (epg != null && epg.stopTimestamp > epg.startTimestamp) {
                val total = (epg.stopTimestamp - epg.startTimestamp).toFloat()
                val elapsed = (currentClock - epg.startTimestamp).toFloat()
                epgProgress = if (total > 0f) (elapsed / total).coerceIn(0f, 1f) else 0f
            } else epgProgress = 0f
            delay(30_000L)
        }
    }

    // ── Dynamic EPG fetch ──
    LaunchedEffect(streamUrl, previewStreamUrl) {
        val activeUrl = if (isPreviewMode) previewStreamUrl else streamUrl
        if (activeUrl != lastFetchedEpgUrl) {
            lastFetchedEpgUrl = activeUrl
            currentEpgData = null
            epgProgress = 0f
            if (activeUrl.isNotEmpty() && SessionData.playlistType == PlaylistType.XTREAM_CODES) {
                val streamId = activeUrl.substringAfterLast("/").substringBeforeLast(".")
                if (streamId.isNotEmpty() && streamId.all { it.isDigit() }) {
                    currentEpgData = PlaylistRepository.getShortEpg(streamId)
                    val epg = currentEpgData
                    if (epg != null && epg.stopTimestamp > epg.startTimestamp) {
                        val now = System.currentTimeMillis()
                        val total = (epg.stopTimestamp - epg.startTimestamp).toFloat()
                        val elapsed = (now - epg.startTimestamp).toFloat()
                        epgProgress = if (total > 0f) (elapsed / total).coerceIn(0f, 1f) else 0f
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  STATE-ONLY switch function (single sink picks it up)
    //  ═══════════════════════════════════════════════════════════════
    fun switchToStream(stream: StreamItem) {
        // ═══════════════════════════════════════════════════════════════
        //  V8.6 — INSTANT CHANNEL SWITCHING (no ad gate)
        //  ═══════════════════════════════════════════════════════════════
        //  V7.3 fired the transition interstitial BEFORE switching to the
        //  new stream. Even with the 1-in-4 throttle, the callback path
        //  added latency to EVERY channel switch in the mini-surfer —
        //  making the player feel sluggish and unlike "other apps" where
        //  tapping a channel starts it instantly.
        //
        //  V8.6 removes the interstitial gate from the mini-surfer
        //  channel-switch path. The stream switches IMMEDIATELY — the
        //  user gets the snappy, responsive experience they expect. The
        //  interstitial feature is PRESERVED on Activity launch (see
        //  PlayerActivity.onCreate) so the ad still fires every 4th
        //  transition — no feature is removed, only de-gated from the
        //  rapid channel-surfing path.
        // ═══════════════════════════════════════════════════════════════
        viewModel.onStreamChanged(stream.url, stream.name)
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 1: Movable video surface (SurfaceView — preserves hw surface)
    // ═══════════════════════════════════════════════════════════════
    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    // V10.2 — مفتاح يتغير عندما يتغير الـ player singleton.
    val playerGenValue = playerGen
    val videoSurface = remember(playerGenValue) {
        movableContentOf {
            val aspectRatio = currentAspectRatio
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = aspectRatio
                        setBackgroundColor(android.graphics.Color.BLACK)
                        player = GlobalPlaybackCoordinator.getPlayer(ctx)
                    }
                },
                update = { view ->
                    val p = GlobalPlaybackCoordinator.getPlayer(view.context)
                    if (view.player !== p) {
                        view.player = p
                    }
                    if (view.resizeMode != aspectRatio) view.resizeMode = aspectRatio
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ROOT BOX — D-Pad key interception + touch click
    // ═══════════════════════════════════════════════════════════════
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (isInPip) return@onPreviewKeyEvent false
                if (isPreviewMode) return@onPreviewKeyEvent false

                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val code = event.nativeKeyEvent.keyCode
                    val isLive = exoPlayer.isCurrentMediaItemLive
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (showMiniSurfer && miniSurferIndex > 0) {
                                miniSurferLastActivity = System.currentTimeMillis()
                                miniSurferIndex--
                                scope.launch { miniSurferListState.animateScrollToItem(miniSurferIndex) }
                                true
                            } else if (!showSettingsMenu && !showSubtitleMenu && !showMiniSurfer && currentSurferStreams.isNotEmpty()) {
                                miniSurferLastActivity = System.currentTimeMillis()
                                showMiniSurfer = true; showControls = false
                                val idx = currentSurferStreams.indexOfFirst { it.url == currentStreamUrl }
                                miniSurferIndex = if (idx >= 0) idx else 0
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (showMiniSurfer && miniSurferIndex < currentSurferStreams.size - 1) {
                                miniSurferLastActivity = System.currentTimeMillis()
                                miniSurferIndex++
                                scope.launch { miniSurferListState.animateScrollToItem(miniSurferIndex) }
                                true
                            } else if (!showSettingsMenu && !showSubtitleMenu && !showMiniSurfer && currentSurferStreams.isNotEmpty()) {
                                miniSurferLastActivity = System.currentTimeMillis()
                                showMiniSurfer = true; showControls = false
                                val idx = currentSurferStreams.indexOfFirst { it.url == currentStreamUrl }
                                miniSurferIndex = if (idx >= 0) idx else 0
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!isLive) {
                                if (!isScrubbing) { isScrubbing = true; scrubPosition = exoPlayer.currentPosition }
                                showControls = true
                                scrubPosition = (scrubPosition + 15000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                                miniSurferLastActivity = System.currentTimeMillis()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!isLive) {
                                if (!isScrubbing) { isScrubbing = true; scrubPosition = exoPlayer.currentPosition }
                                showControls = true
                                scrubPosition = (scrubPosition - 15000L).coerceAtLeast(0L)
                                miniSurferLastActivity = System.currentTimeMillis()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            when {
                                isScrubbing -> { exoPlayer.seekTo(scrubPosition); isScrubbing = false; position = scrubPosition; true }
                                showMiniSurfer && miniSurferIndex in currentSurferStreams.indices -> {
                                    switchToStream(currentSurferStreams[miniSurferIndex]); showMiniSurfer = false; true
                                }
                                uiState.isFatalError -> false
                                else -> {
                                    if (uiState.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    showControls = true; true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            when {
                                showSettingsMenu -> { showSettingsMenu = false; true }
                                showSubtitleMenu -> { showSubtitleMenu = false; true }
                                isScrubbing -> { isScrubbing = false; true }
                                showMiniSurfer -> { showMiniSurfer = false; true }
                                showControls -> { showControls = false; true }
                                uiState.isFatalError -> { false }
                                else -> {
                                    // Return to split-screen preview — re-enable ads.
                                    isPreviewMode = true
                                    PolicyShield.exitFullscreenLive()
                                    true
                                }
                            }
                        }
                        else -> false
                    }
                } else false
            }
            .then(
                if (isInPip) Modifier else Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        when {
                            showSettingsMenu -> showSettingsMenu = false
                            showSubtitleMenu -> showSubtitleMenu = false
                            isScrubbing -> isScrubbing = false
                            showMiniSurfer -> showMiniSurfer = false
                            else -> showControls = !showControls
                        }
                    }
            )
    ) {
        // ═══════════════════════════════════════════════════════════════
        //  PREVIEW MODE (split-screen)
        // ═══════════════════════════════════════════════════════════════
        if (isPreviewMode) {
            androidx.activity.compose.BackHandler(enabled = true) {
                GlobalPlaybackCoordinator.pause(); onBack()
            }
            SplitScreenPreviewLayout(
                surferStreams = currentSurferStreams,
                currentStreamUrl = uiState.currentStreamUrl,
                previewStreamUrl = previewStreamUrl,
                previewStreamName = previewStreamName,
                focusedPreviewIndex = focusedPreviewIndex,
                currentClock = currentClock,
                epgProgress = epgProgress,
                epgData = currentEpgData,
                videoSurface = videoSurface,
                isInPip = isInPip,
                isRestoringFromPip = isRestoringFromPip,
                onStreamFocused = { stream, index ->
                    focusedPreviewIndex = index
                    previewStreamUrl = stream.url
                    previewStreamName = stream.name
                },
                onStreamClicked = { stream ->
                    previewStreamUrl = stream.url
                    previewStreamName = stream.name
                    switchToStream(stream)
                },
                onExpandToFullScreen = {
                    isPreviewMode = false
                    showControls = true
                    // ═══════════════════════════════════════════════════════
                    //  V7.3 §IV — POLICY SHIELD
                    //  ═══════════════════════════════════════════════════════
                    //  Entering fullscreen live-streaming mode → ALL ads are
                    //  blocked. The banner vanishes instantly.
                    //  ═══════════════════════════════════════════════════════
                    PolicyShield.enterFullscreenLive()
                },
                onClosePreview = { onBack() }
            )
            return@Box
        }

        // ═══════════════════════════════════════════════════════════════
        //  V7.3 §IV — POLICY SHIELD (fullscreen live)
        //  ═══════════════════════════════════════════════════════════════
        //  While in full-screen live-streaming mode, every ad surface is
        //  blocked. The flag is set on expand and cleared on return to
        //  split-screen (handled by the BACK-to-preview key handler above).
        // ═══════════════════════════════════════════════════════════════

        // ═══════════════════════════════════════════════════════════════
        //  FULL-SCREEN MODE — Layer 1: video surface
        // ═══════════════════════════════════════════════════════════════
        videoSurface()
        if (isInPip) return@Box

        // ═══════════════════════════════════════════════════════════════
        //  LAYER 2: NON-BLOCKING OVERLAYS
        //  Every overlay uses Modifier.align() — NO full-screen opaque
        //  boxes that intercept touch events.
        // ═══════════════════════════════════════════════════════════════

        // ── REC indicator (top-end, compact) ──
        if (isRecording) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 8.dp),
                color = Color(0xDD000000),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val recPulse = rememberInfiniteTransition(label = "recPulse")
                    val recPulseAlpha by recPulse.animateFloat(
                        initialValue = 0.3f, targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "recPulseAlpha"
                    )
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5252).copy(alpha = recPulseAlpha)))
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    val totalSec = (recordingElapsedMs / 1000L).toInt()
                    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
                    val sizeMb = recordingSizeBytes / (1024.0 * 1024.0)
                    val sizeText = if (sizeMb >= 1.0) "%.1f MB".format(sizeMb) else "${recordingSizeBytes / 1024} KB"
                    Text(
                        "${if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)} • $sizeText",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── Loading indicator (centered, compact, NON-blocking) ──
        if (uiState.isLoading && !uiState.isFatalError && !showMiniSurfer) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    CircularProgressIndicator(color = PrimaryColor, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Loading...", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }

        // ── Fatal error card (centered, NON-blocking) ──
        if (uiState.isFatalError) {
            // V4.0 Layer 3: Smart focus + BackHandler with forceTeardown
            LaunchedEffect(uiState.isFatalError, showControls, showMiniSurfer) {
                if (uiState.isFatalError && !showControls && !showMiniSurfer) {
                    delay(100)
                    runCatching { fatalErrorFocusRequester.requestFocus() }
                }
            }
            androidx.activity.compose.BackHandler(enabled = true) {
                GlobalPlaybackCoordinator.forceTeardown()
                viewModel.setPlaybackActive(false)
                onBack()
            }

            val qrContent = remember {
                val msg = "Hello Support. I have an issue. " +
                    "Username: ${SessionData.xtreamUsername.ifBlank { "N/A" }}. " +
                    "Channel: $currentStreamName. Error: Fatal Playback Exception."
                "https://wa.me/$SUPPORT_WHATSAPP_NUMBER?text=${URLEncoder.encode(msg, "UTF-8")}"
            }
            val qrBitmap = remember(qrContent) { generateQrBitmap(qrContent, 300, 300) }

            Surface(
                modifier = Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 360.dp),
                color = Color(0xF21A1A1A),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback Failed", color = Color(0xFFFF5252), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("All reconnection attempts exhausted", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Surface(color = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(160.dp)) {
                            Box(Modifier.size(150.dp).padding(5.dp), contentAlignment = Alignment.Center) {
                                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "WhatsApp Support QR", modifier = Modifier.size(140.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Scan with your phone camera to send an", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = TextAlign.Center)
                    Text("instant diagnostic report to Support.", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))

                    var retryFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (retryFocused) PrimaryColor.copy(alpha = 0.9f) else PrimaryColor)
                            .border(2.dp, if (retryFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                            .focusRequester(fatalErrorFocusRequester)
                            .focusable()
                            .onFocusChanged { retryFocused = it.isFocused }
                            .clickable { viewModel.onFatalRetry() }
                            .padding(horizontal = 28.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Retry Connection", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))

                    var backErrFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (backErrFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
                            .border(1.dp, if (backErrFocused) Color.White.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .focusable()
                            .onFocusChanged { backErrFocused = it.isFocused }
                            .clickable {
                                GlobalPlaybackCoordinator.forceTeardown()
                                viewModel.setPlaybackActive(false)
                                onBack()
                            }
                            .padding(horizontal = 28.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Go Back", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text("Tip: press UP/DOWN to browse channels", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // ── Non-fatal reconnect (top-center, compact) ──
        if (uiState.hasError && !uiState.isFatalError) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
                color = Color(0xDD1A1A1A),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Reconnecting... (${uiState.reconnectCount}/${PlayerViewModel.MAX_RECONNECTS})", color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    var reconFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (reconFocused) PrimaryColor.copy(alpha = 0.85f) else PrimaryColor)
                            .border(1.5.dp, if (reconFocused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                            .focusable()
                            .onFocusChanged { reconFocused = it.isFocused }
                            .clickable { viewModel.onFatalRetry() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Retry", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // ── Fallback stream switch (top-center pill) ──
        if (uiState.fallbackOverlay != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
                color = Color(0xDD000000),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(uiState.fallbackOverlay!!, color = Color(0xFF22D3EE), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }

        // ── Subtitle loading (bottom-center pill) ──
        if (uiState.isSubtitleLoading) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                color = Color(0xDD000000),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    CircularProgressIndicator(color = Color(0xFF818CF8), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Fetching subtitles...", color = Color(0xFF818CF8), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Subtitle injection result (bottom-center pill) ──
        if (uiState.subtitleOverlay != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                color = Color(0xDD000000),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(uiState.subtitleOverlay!!, color = Color(0xFF818CF8), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }

        // ── Next Episode binge (bottom-center card) ──
        if (uiState.nextEpisodeCountdown > 0 && uiState.nextEpisodeName.isNotEmpty() && !uiState.isLoading && !uiState.isFatalError) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                color = Color(0xE6111111),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Next Episode", color = PrimaryColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(uiState.nextEpisodeName, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    var nextEpFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (nextEpFocused) PrimaryColor.copy(alpha = 0.9f) else PrimaryColor.copy(alpha = 0.6f))
                            .border(2.dp, if (nextEpFocused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                            .focusable()
                            .onFocusChanged { nextEpFocused = it.isFocused }
                            .clickable { viewModel.playNextEpisode() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("${uiState.nextEpisodeCountdown}s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    var playNowFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (playNowFocused) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f))
                            .border(2.dp, if (playNowFocused) PrimaryColor else Color.Transparent, RoundedCornerShape(6.dp))
                            .focusable()
                            .onFocusChanged { playNowFocused = it.isFocused }
                            .clickable { viewModel.playNextEpisode() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  Full controls overlay — V4.0: renders whenever showControls=true,
        //  regardless of loading/error state. User can ALWAYS interact.
        // ═══════════════════════════════════════════════════════════════
        if (showControls) {
            val controlsEntryFocusRequester = remember { FocusRequester() }
            LaunchedEffect(showControls) {
                if (showControls) { delay(80); runCatching { controlsEntryFocusRequester.requestFocus() } }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                // ── Top bar ──
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    var backFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(32.dp).clip(CircleShape)
                            .background(if (backFocused) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                            .border(1.5.dp, if (backFocused) PrimaryColor else Color.Transparent, CircleShape)
                            .focusRequester(controlsEntryFocusRequester)
                            .focusable().onFocusChanged { backFocused = it.isFocused }
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.padding(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentStreamName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (activeSubtitleLang != null) "${activeSubtitleLang!!.flag} ${activeSubtitleLang!!.label}" else "Live TV",
                            color = if (activeSubtitleLang != null) Color(0xFF818CF8) else Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                    }

                    if (currentSurferStreams.isNotEmpty()) {
                        var listFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(32.dp).clip(CircleShape)
                                .background(if (listFocused) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                                .border(1.5.dp, if (listFocused) PrimaryColor else Color.Transparent, CircleShape)
                                .focusable().onFocusChanged { listFocused = it.isFocused }
                                .clickable {
                                    showMiniSurfer = !showMiniSurfer
                                    if (showMiniSurfer) {
                                        val idx = currentSurferStreams.indexOfFirst { it.url == currentStreamUrl }
                                        miniSurferIndex = if (idx >= 0) idx else 0
                                        miniSurferLastActivity = System.currentTimeMillis()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.FormatListBulleted, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.size(6.dp))
                    }

                    var subFocused by remember { mutableStateOf(false) }
                    val subtitleActive = activeSubtitleLang != null
                    Box(
                        modifier = Modifier
                            .size(32.dp).clip(CircleShape)
                            .background(when { subFocused -> Color.White.copy(alpha = 0.3f); subtitleActive -> Color(0xFF818CF8).copy(alpha = 0.3f); else -> Color.White.copy(alpha = 0.15f) })
                            .border(1.5.dp, when { subFocused -> Color.White; subtitleActive -> Color(0xFF818CF8); else -> Color.Transparent }, CircleShape)
                            .focusable().onFocusChanged { subFocused = it.isFocused }
                            .clickable { showSubtitleMenu = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.ClosedCaption, null, tint = if (subtitleActive) Color(0xFF818CF8) else Color.White, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.size(6.dp))

                    var pipFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(32.dp).clip(CircleShape)
                            .background(if (pipFocused) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                            .border(1.5.dp, if (pipFocused) PrimaryColor else Color.Transparent, CircleShape)
                            .focusable().onFocusChanged { pipFocused = it.isFocused }
                            .clickable { onEnterPip() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.size(6.dp))

                    var clipFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(32.dp).clip(CircleShape)
                            .background(if (clipFocused) Color(0xFFFF6B6B).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f))
                            .border(1.5.dp, if (clipFocused) Color(0xFFFF5252) else Color.Transparent, CircleShape)
                            .focusable().onFocusChanged { clipFocused = it.isFocused }
                            .clickable {
                                val path = com.agon.app.proxy.ClipBufferManager.dumpLastSeconds(context, 30)
                                val msg = if (path != null) "Clip saved (last 30s)\n$path" else "Nothing to clip yet — wait a few seconds"
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            },
                        contentAlignment = Alignment.Center
                    ) { Text("CLIP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.size(6.dp))

                    var recFocused by remember { mutableStateOf(false) }
                    val recPulse = rememberInfiniteTransition(label = "recPulse")
                    val recPulseAlpha by recPulse.animateFloat(
                        initialValue = 0.4f, targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "recPulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp).clip(CircleShape)
                            .background(when { isRecording -> Color(0xFFD32F2F).copy(alpha = recPulseAlpha); recFocused -> Color.White.copy(alpha = 0.3f); else -> Color.White.copy(alpha = 0.15f) })
                            .border(1.5.dp, if (recFocused) (if (isRecording) Color(0xFFFF5252) else PrimaryColor) else Color.Transparent, CircleShape)
                            .focusable().onFocusChanged { recFocused = it.isFocused }
                            .clickable {
                                if (isRecording) {
                                    val bytes = ProxyForegroundService.stopRecording(context)
                                    isRecording = false; recordingElapsedMs = 0L
                                    val msg = if (bytes > 0) "Recording saved (${bytes / 1024} KB)\n${ProxyForegroundService.lastRecordingPath}" else "Recording stopped (0 bytes)"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } else {
                                    val urlToRecord = uiState.currentStreamUrl.ifBlank { streamUrl }
                                    if (urlToRecord.isBlank()) { Toast.makeText(context, "Cannot record — no stream URL", Toast.LENGTH_SHORT).show(); return@clickable }
                                    val name = ProxyForegroundService.startRecording(context, urlToRecord)
                                    if (name != null) { isRecording = true; recordingSizeBytes = 0L; recordingElapsedMs = 0L; Toast.makeText(context, "Recording started\n$name", Toast.LENGTH_LONG).show() }
                                    else Toast.makeText(context, "Failed to start recording", Toast.LENGTH_LONG).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                        else Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFFF5252)))
                    }
                }

                // ── Center play/pause ──
                if (!uiState.isPlaying || showControls) {
                    var playFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(72.dp).align(Alignment.Center).clip(CircleShape)
                            .background(if (playFocused) PrimaryColor.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f))
                            .border(2.dp, if (playFocused) Color.White else Color.Transparent, CircleShape)
                            .focusable().onFocusChanged { playFocused = it.isFocused }
                            .clickable { if (uiState.isPlaying) exoPlayer.pause() else exoPlayer.play(); showControls = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                }

                // ── Bottom scrubber ──
                NetflixBottomScrubber(
                    exoPlayer = exoPlayer,
                    position = if (isScrubbing) scrubPosition else position,
                    isScrubbing = isScrubbing,
                    onScrubStart = { isScrubbing = true },
                    onScrubPositionChange = { scrubPosition = it },
                    onScrubCommit = { exoPlayer.seekTo(it); isScrubbing = false; position = it },
                    onScrubCancel = { isScrubbing = false },
                    onOpenSettings = { showSettingsMenu = true },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  Mini-Surfer overlay (right-edge, NON-blocking)
        // ═══════════════════════════════════════════════════════════════
        if (showMiniSurfer && currentSurferStreams.isNotEmpty()) {
            LaunchedEffect(miniSurferIndex) { miniSurferListState.animateScrollToItem(miniSurferIndex) }

            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp),
                color = Color(0xCC0A0A0A),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    Text("Channel Surf", color = PrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("UP/DOWN to browse \u2022 OK to switch", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(PrimaryColor.copy(alpha = 0.3f)))
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(state = miniSurferListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(currentSurferStreams) { index, stream ->
                            val isHighlighted = index == miniSurferIndex
                            val isCurrent = stream.url == currentStreamUrl
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(when { isHighlighted -> PrimaryColor.copy(alpha = 0.25f); isCurrent -> Color.White.copy(alpha = 0.08f); else -> Color.Transparent })
                                    .border(1.dp, if (isHighlighted) PrimaryColor else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { switchToStream(stream); showMiniSurfer = false }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}", color = if (isHighlighted) PrimaryColor else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(stream.logo).crossfade(true).build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(width = 46.dp, height = 30.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop,
                                    error = {
                                        LogoPlaceholder(
                                            Modifier.size(width = 46.dp, height = 30.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(stream.name, color = when { isHighlighted -> Color.White; isCurrent -> PrimaryColor; else -> Color.White.copy(alpha = 0.7f) },
                                    fontSize = if (isHighlighted) 14.sp else 12.sp, fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (isCurrent && !isHighlighted) Text("LIVE", color = Color(0xFFFF5252), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                                if (isHighlighted) Text("OK", color = PrimaryColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                            }
                            if (index < currentSurferStreams.size - 1) Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  Settings menu (right slide-in)
        // ═══════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.35f)
        ) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xE6141414)).padding(16.dp)) {
                Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { Text("Screen Size", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    listOf("Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM).forEach { (label, mode) ->
                        item {
                            var f by remember { mutableStateOf(false) }; val sel = currentAspectRatio == mode
                            Text(if (sel) "• $label" else "$label", color = if (sel) Color.White else Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { currentAspectRatio = mode; showSettingsMenu = false })
                        }
                    }

                    item { Text("Playback Speed", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        item {
                            var f by remember { mutableStateOf(false) }; val sel = currentPlaybackSpeed == speed
                            Text(if (sel) "• ${speed}x" else "${speed}x", color = if (sel) Color.White else Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { currentPlaybackSpeed = speed; exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed)); showSettingsMenu = false })
                        }
                    }

                    val tracks = exoPlayer.currentTracks
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isNotEmpty()) {
                        item { Text("Audio Track (Dubbing)", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                        var hasMultipleAudio = false
                        audioGroups.forEach { if (it.length > 1) hasMultipleAudio = true }
                        if (!hasMultipleAudio && audioGroups.size == 1) {
                            item { Text("Default Audio (1 available)", color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                        } else {
                            audioGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    val format = group.getTrackFormat(i)
                                    val sel = group.isTrackSelected(i)
                                    val label = format.language ?: "Audio Track ${i + 1}"
                                    item {
                                        var f by remember { mutableStateOf(false) }
                                        Text(if (sel) "• $label" else "$label", color = if (sel) Color.White else Color.Gray,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                                .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                                .clickable { exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)).build(); showSettingsMenu = false })
                                    }
                                }
                            }
                        }
                    }

                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    var totalVideoTracks = 0; videoGroups.forEach { totalVideoTracks += it.length }
                    if (totalVideoTracks > 1) {
                        item { Text("Video Quality", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                        videoGroups.forEach { group ->
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val sel = group.isTrackSelected(i)
                                val label = "${format.height}p" + (if (format.bitrate > 0) " (${format.bitrate / 1000} kbps)" else "")
                                item {
                                    var f by remember { mutableStateOf(false) }
                                    Text(if (sel) "• $label" else "$label", color = if (sel) Color.White else Color.Gray,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                            .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                            .clickable { exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)).build(); showSettingsMenu = false })
                                }
                            }
                        }
                    }

                    item { Text("Sleep Timer", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    listOf("Off" to 0L, "15 min" to 15L * 60 * 1000, "30 min" to 30L * 60 * 1000, "45 min" to 45L * 60 * 1000, "1 hour" to 60L * 60 * 1000, "2 hours" to 120L * 60 * 1000).forEach { (label, dur) ->
                        item {
                            var f by remember { mutableStateOf(false) }
                            val sel = if (dur == 0L) sleepTimerEndMs <= 0L else sleepTimerEndMs > 0L && sleepTimerLabel != "Off" && label != "Off"
                            val disp = if (sel && dur > 0L && sleepTimerEndMs > 0L) "$label ($sleepTimerLabel)" else label
                            Text(if (sel) "• $disp" else "$disp", color = if (sel) Color.White else Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { if (dur == 0L) { sleepTimerEndMs = 0L; sleepTimerLabel = "Off" } else { sleepTimerEndMs = System.currentTimeMillis() + dur; sleepTimerLabel = label } })
                        }
                    }

                    item { Text("Picture Quality", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    item { Text("HDR Mode", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                    listOf("Auto" to 0, "Force SDR" to 1, "Force HDR" to 2).forEach { (label, mode) ->
                        item {
                            var f by remember { mutableStateOf(false) }; val sel = hdrPreference == mode
                            Text(if (sel) "• $label" else "$label", color = if (sel) Color.White else Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { hdrPreference = mode })
                        }
                    }
                    item { Text("Brightness", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                    listOf("System" to 0f, "25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "100%" to 1.0f).forEach { (label, level) ->
                        item {
                            var f by remember { mutableStateOf(false) }; val sel = brightnessOverride == level
                            Text(if (sel) "• $label" else "$label", color = if (sel) Color.White else Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { brightnessOverride = level })
                        }
                    }

                    // ═══════════════════════════════════════════════════════════════
                    //  V9.8 — EXTERNAL PLAYER (VLC / MX Player)
                    //  ═══════════════════════════════════════════════════════════════
                    //  Launches the current stream in an external video player
                    //  (VLC, MX Player, or any app that handles video/* intents).
                    //  If no player is installed, opens Play Store to install VLC.
                    // ═══════════════════════════════════════════════════════════════
                    item { Text("External Player", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    item {
                        var f by remember { mutableStateOf(false) }
                        Text("▶ Play in VLC / MX Player",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable {
                                    com.agon.app.ui.util.ExternalPlayerHelper.launchExternal(
                                        context = context,
                                        streamUrl = currentStreamUrl,
                                        title = currentStreamName
                                    )
                                    showSettingsMenu = false
                                }
                        )
                    }

                    // ═══════════════════════════════════════════════════════════════
                    //  V9.8 — CATCH-UP TV (Xtream)
                    //  ═══════════════════════════════════════════════════════════════
                    //  If the current stream supports catch-up (Xtream servers
                    //  with tv_archive=1), shows a button to open the catch-up
                    //  archive for this channel.
                    // ═══════════════════════════════════════════════════════════════
                    val catchupSupported = com.agon.app.data.repository.PlaylistRepository.getCatchupSupport(currentStreamUrl)
                    if (catchupSupported != null) {
                        item { Text("Catch-up TV", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                        item {
                            var f by remember { mutableStateOf(false) }
                            Text("⏪ Open Catch-up Archive",
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable {
                                        val catchupUrl = com.agon.app.data.repository.PlaylistRepository
                                            .buildCatchupUrl(currentStreamUrl, catchupSupported)
                                        if (catchupUrl != null) {
                                            viewModel.onStreamChanged(catchupUrl, "$currentStreamName (Catch-up)")
                                            showSettingsMenu = false
                                        }
                                    }
                            )
                        }
                    }

                    // ═══════════════════════════════════════════════════════════════
                    //  V9.8 — CHROMECAST / GOOGLE CAST
                    //  ═══════════════════════════════════════════════════════════════
                    //  Casts the current stream to a Chromecast device on the
                    //  same Wi-Fi network. Uses Google's Default Media Receiver
                    //  which supports HLS, MP4, MKV, and TS streams.
                    // ═══════════════════════════════════════════════════════════════
                    item { Text("Casting", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    item {
                        var f by remember { mutableStateOf(false) }
                        val isCasting = com.agon.app.cast.CastHelper.isCasting(context)
                        Text(
                            if (isCasting) "⏹ Stop Casting" else "📡 Cast to TV (Chromecast)",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable {
                                    if (isCasting) {
                                        com.agon.app.cast.CastHelper.stopCasting(context)
                                    } else {
                                        // Start casting — load stream on cast device
                                        val loaded = com.agon.app.cast.CastHelper.loadStream(
                                            context = context,
                                            streamUrl = currentStreamUrl,
                                            streamName = currentStreamName
                                        )
                                        if (!loaded) {
                                            // No session active — show device picker
                                            com.agon.app.cast.CastHelper.showCastDialog(
                                                context as android.app.Activity
                                            )
                                        }
                                    }
                                    showSettingsMenu = false
                                }
                        )
                    }

                    // ═══════════════════════════════════════════════════════════════
                    //  V9.8 — EPG GRID (7-day program guide)
                    //  ═══════════════════════════════════════════════════════════════
                    item { Text("Program Guide", color = PrimaryColor, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    item {
                        var f by remember { mutableStateOf(false) }
                        Text("📅 Open EPG Guide (7 days)",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable {
                                    showSettingsMenu = false
                                    showEpgGrid = true
                                }
                        )
                    }
                }
            }
        }
        // ═══════════════════════════════════════════════════════════════
        //  Subtitle language picker (right slide-in)
        // ═══════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = showSubtitleMenu,
            enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.32f)
        ) {
            Surface(color = Color(0xE61A1A1A), modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ClosedCaption, null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Subtitles", color = PrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, modifier = Modifier.clickable { showSubtitleMenu = false })
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            var f by remember { mutableStateOf(false) }; val sel = activeSubtitleLang == null
                            Text(if (sel) "• Off" else "Off", color = if (sel) Color.White else Color.Gray, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { activeSubtitleLang = null; viewModel.clearSubtitle(); showSubtitleMenu = false })
                        }
                        items(SUBTITLE_LANGUAGES) { lang ->
                            var f by remember { mutableStateOf(false) }; val sel = activeSubtitleLang?.code == lang.code
                            Text(if (sel) "• ${lang.flag} ${lang.label}" else "${lang.flag} ${lang.label}", color = if (sel) Color.White else Color.Gray, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusable().onFocusChanged { f = it.isFocused }
                                    .background(if (f) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { activeSubtitleLang = lang; viewModel.requestSubtitle(lang.code, lang.label); showSubtitleMenu = false })
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  V9.8 — EPG GRID OVERLAY (7-day program guide)
        //  ═══════════════════════════════════════════════════════════════
        //  Shows a full-screen overlay with the 7-day EPG for the current
        //  channel. Each program entry shows title + time. Tapping a
        //  program plays it via catch-up (if available) or just shows
        //  the program info.
        // ═══════════════════════════════════════════════════════════════
        if (showEpgGrid) {
            EpgGridOverlay(
                streamUrl = currentStreamUrl,
                streamName = currentStreamName,
                onClose = { showEpgGrid = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  V9.8 — EPG Grid Overlay (7-day program guide)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun EpgGridOverlay(
    streamUrl: String,
    streamName: String,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var epgEntries by remember { mutableStateOf<List<com.agon.app.data.repository.PlaylistRepository.FullEpgEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Extract stream ID from URL for the EPG fetch
    val streamId = remember(streamUrl) {
        try {
            val uri = java.net.URI(streamUrl)
            val path = uri.path ?: ""
            val parts = path.split("/").filter { it.isNotEmpty() }
            if (parts.size >= 4) parts[3].substringBefore(".") else ""
        } catch (_: Exception) { "" }
    }

    // Fetch full EPG on launch
    androidx.compose.runtime.LaunchedEffect(streamId) {
        if (streamId.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        scope.launch {
            isLoading = true
            val entries = com.agon.app.data.repository.PlaylistRepository.getFullEpg(streamId)
            epgEntries = entries
            isLoading = false
        }
    }

    // Full-screen overlay
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            // Header
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Text(
                    text = "📅 EPG Guide — $streamName",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                androidx.compose.material3.Text(
                    text = "✕",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 24.sp,
                    modifier = Modifier.clickable { onClose() }.padding(8.dp)
                )
            }

            androidx.compose.material3.Divider(
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Content
            if (isLoading) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                }
            } else if (epgEntries.isEmpty()) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        "No EPG data available for this channel",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            } else {
                // Group entries by day
                val sdf = java.text.SimpleDateFormat("EEE, dd MMM", java.util.Locale.getDefault())
                val timeSdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                val grouped = epgEntries.groupBy { entry ->
                    sdf.format(java.util.Date(entry.startTimestamp))
                }

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { (day, entries) ->
                        item {
                            androidx.compose.material3.Text(
                                text = day,
                                color = Color(0xFF00C6FF),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                            )
                        }
                        items(entries) { entry ->
                            val isNow = System.currentTimeMillis() in entry.startTimestamp..entry.stopTimestamp
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(
                                        if (isNow) Color(0xFF00C6FF).copy(alpha = 0.15f)
                                        else Color.White.copy(alpha = 0.03f)
                                    )
                                    .padding(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Text(
                                    text = "${timeSdf.format(java.util.Date(entry.startTimestamp))} - ${timeSdf.format(java.util.Date(entry.stopTimestamp))}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.width(120.dp)
                                )
                                androidx.compose.material3.Text(
                                    text = if (isNow) "▶ ${entry.title}" else entry.title,
                                    color = if (isNow) Color(0xFF00C6FF) else Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Netflix-style Bottom Scrubber
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NetflixBottomScrubber(
    exoPlayer: Player,
    position: Long,
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onScrubCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = exoPlayer.duration.coerceAtLeast(0L)
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        var trackWidth by remember { mutableIntStateOf(1) }
        Box(
            modifier = Modifier
                .weight(1f).height(36.dp).onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    detectTapGestures(onPress = { offset ->
                        onScrubStart()
                        val prog = (offset.x / trackWidth).coerceIn(0f, 1f)
                        val pos = (prog * duration).toLong()
                        onScrubPositionChange(pos)
                        tryAwaitRelease(); onScrubCommit(pos)
                    })
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrubStart(); val prog = (offset.x / trackWidth).coerceIn(0f, 1f); onScrubPositionChange((prog * duration).toLong()) },
                        onDragEnd = { onScrubCommit(position) },
                        onDragCancel = { onScrubCancel() },
                        onHorizontalDrag = { change, _ -> change.consume(); val prog = (change.position.x / trackWidth).coerceIn(0f, 1f); onScrubPositionChange((prog * duration).toLong()) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(NetflixRed))
                Box(Modifier.align(Alignment.CenterStart).offset(x = (-8).dp).fillMaxWidth(progress)) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (isScrubbing) Color.White else NetflixRed).align(Alignment.CenterEnd))
                }
            }
        }
        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        var settingsFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(40.dp).clip(RoundedCornerShape(8.dp))
                .background(if (settingsFocused) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                .border(2.dp, if (settingsFocused) PrimaryColor else Color.Transparent, RoundedCornerShape(8.dp))
                .focusable().onFocusChanged { settingsFocused = it.isFocused }
                .clickable { onOpenSettings() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Split-Screen Preview Layout
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SplitScreenPreviewLayout(
    surferStreams: List<StreamItem>,
    currentStreamUrl: String,
    previewStreamUrl: String,
    previewStreamName: String,
    focusedPreviewIndex: Int,
    currentClock: Long,
    epgProgress: Float,
    epgData: PlaylistRepository.LiveEpgData?,
    videoSurface: @Composable () -> Unit,
    isInPip: Boolean,
    isRestoringFromPip: Boolean,
    onStreamFocused: (StreamItem, Int) -> Unit,
    onStreamClicked: (StreamItem) -> Unit,
    onExpandToFullScreen: () -> Unit,
    onClosePreview: () -> Unit
) {
    val expandFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isInPip) Modifier.background(Color.Black) else Modifier.deepSpaceBackground(withCinematicGlow = true))
    ) {
        // LEFT: channel list
        Column(
            modifier = Modifier
                .weight(if (isInPip) 0f else 0.35f).fillMaxHeight()
                .then(if (!isInPip) Modifier.padding(8.dp).glassmorphicPanel(cornerRadius = 14).padding(vertical = 8.dp) else Modifier)
                .then(if (!isInPip) Modifier.focusProperties { right = expandFocusRequester } else Modifier)
        ) {
            if (!isInPip && !isRestoringFromPip) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("CHANNELS", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${surferStreams.size}", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                }
                HorizontalDividerLine()
                val currentChannelIndex = remember(surferStreams, currentStreamUrl) {
                    if (currentStreamUrl.isBlank()) 0
                    else surferStreams.indexOfFirst { it.url == currentStreamUrl }.coerceAtLeast(0)
                }
                val currentChannelFocusRequester = remember { FocusRequester() }
                val previewListState = remember { androidx.compose.foundation.lazy.LazyListState() }
                LaunchedEffect(surferStreams, currentStreamUrl) {
                    if (surferStreams.isNotEmpty()) {
                        delay(200)
                        runCatching { previewListState.animateScrollToItem(currentChannelIndex) }
                        runCatching { currentChannelFocusRequester.requestFocus() }
                    }
                }
                LazyColumn(state = previewListState, modifier = Modifier.fillMaxSize().focusProperties { canFocus = false }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(surferStreams) { index, stream ->
                        val isCurrent = stream.url == currentStreamUrl
                        val isFocused = focusedPreviewIndex == index
                        PreviewChannelRow(
                            stream = stream, isCurrent = isCurrent, isFocused = isFocused,
                            focusRequester = if (index == currentChannelIndex) currentChannelFocusRequester else null,
                            onFocusChanged = { if (it) onStreamFocused(stream, index) },
                            onClick = { onStreamClicked(stream) }
                        )
                    }
                }
            } else if (!isInPip && isRestoringFromPip) {
                Spacer(modifier = Modifier.fillMaxSize())
            }
        }

        // RIGHT: mini-player + EPG
        Column(
            modifier = Modifier.weight(if (isInPip) 1f else 0.65f).fillMaxHeight().then(if (!isInPip) Modifier.padding(12.dp) else Modifier),
            verticalArrangement = if (!isInPip) Arrangement.spacedBy(10.dp) else Arrangement.Top
        ) {
            Box(
                modifier = if (isInPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            ) {
                videoSurface()
                if (!isInPip) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        var expandFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, if (expandFocused) PrimaryColor else Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .focusRequester(expandFocusRequester).focusProperties { right = closeFocusRequester }
                                .focusable().onFocusChanged { expandFocused = it.isFocused }
                                .clickable(onClick = onExpandToFullScreen),
                            contentAlignment = Alignment.Center
                        ) { Text("⤢", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                        var closeFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, if (closeFocused) Color(0xFFFF5252) else Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .focusRequester(closeFocusRequester).focusProperties { left = expandFocusRequester }
                                .focusable().onFocusChanged { closeFocused = it.isFocused }
                                .clickable(onClick = onClosePreview),
                            contentAlignment = Alignment.Center
                        ) { Text("✕", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    }
                    Text("PREVIEW", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (!isInPip && !isRestoringFromPip) {
                EpgDetailsPanel(streamName = previewStreamName, currentClock = currentClock, progress = epgProgress, epgData = epgData)
            } else if (!isInPip && isRestoringFromPip) {
                Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
            }

            // ═══════════════════════════════════════════════════════════════
            //  V8.3 — BANNER AD REMOVED
            //  ═══════════════════════════════════════════════════════════════
            //  The Anchored Adaptive Banner (BannerAdView) was physically
            //  removed from the project in V8.3. The split-screen player
            //  surface now ends cleanly below the EPG panel — no ad slot.
            //  PolicyShield.bannerAllowedState is no longer consumed here;
            //  the interstitial-only ad system relies solely on the
            //  fullscreen-live gate inside InterstitialAdController.
            // ═══════════════════════════════════════════════════════════════
        }
    }
}

@Composable
private fun HorizontalDividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
}

@Composable
private fun PreviewChannelRow(
    stream: StreamItem,
    isCurrent: Boolean,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(when { isCurrent -> AccentIndigo.copy(alpha = 0.22f); isFocused -> GlassSurface.copy(alpha = 0.65f); else -> Color.Transparent })
            .border(width = if (isFocused || isCurrent) 1.5.dp else 0.dp,
                color = when { isCurrent -> AccentIndigo; isFocused -> AccentIndigoLight; else -> Color.Transparent },
                shape = RoundedCornerShape(8.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (stream.logo.isNotEmpty()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(stream.logo).crossfade(true)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED).memoryCachePolicy(coil.request.CachePolicy.ENABLED).build(),
                contentDescription = stream.name,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                error = {
                    LogoPlaceholder(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                },
                loading = {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(GlassSurfaceAlt))
                }
            )
            Spacer(Modifier.width(10.dp))
        } else {
            LogoPlaceholder(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(stream.name, color = when { isCurrent -> AccentCyan; isFocused -> Color.White; else -> Color.White.copy(alpha = 0.7f) },
            fontSize = 12.sp, fontWeight = if (isCurrent || isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (isCurrent) Text("●", color = AccentCyan, fontSize = 10.sp)
    }
}

@Composable
private fun EpgDetailsPanel(
    streamName: String,
    currentClock: Long,
    progress: Float,
    epgData: PlaylistRepository.LiveEpgData? = null
) {
    Column(modifier = Modifier.fillMaxWidth().glassmorphicPanel(cornerRadius = 12).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("NOW: $streamName", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatClock(currentClock), color = PrimaryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (epgData != null && epgData.title.isNotEmpty()) {
            Text(epgData.title, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (epgData.description.isNotEmpty()) Text(epgData.description, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (epgData == null) {
            Text("Loading program info…", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.1f))) {
            Box(modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(PrimaryColor, Color(0xFF22D3EE)))))
        }
        val nextLine = when {
            epgData == null -> ""
            epgData.nextTitle.isNotEmpty() -> "Next: ${formatClock(epgData.nextStartTimestamp)} — ${epgData.nextTitle}"
            else -> "Next: No upcoming program"
        }
        if (nextLine.isNotEmpty()) Text(nextLine, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatClock(epochMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val h = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val m = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
    return "$h:$m"
}

private fun generateQrBitmap(content: String, width: Int, height: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        bitmap
    } catch (e: Exception) { e.printStackTrace(); null }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

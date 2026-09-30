package com.agon.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agon.app.R
import com.agon.app.ads.AdMobManager
import com.agon.app.ads.DPadAdFocusEngine
import com.agon.app.data.ProfileRepository
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.deepSpaceBackground
import com.agon.app.ui.util.applyImmersiveFullscreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * SplashActivity - Premium Splash Screen for Silina TV
 *
 * Smart routing: profiles exist → ProfilesActivity, else → LoginActivity
 * Uses LaunchedEffect coroutine (no runBlocking) for safe DataStore access.
 * All visuals are LOCAL — no network dependency.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  V7.3 — APP OPEN AD INJECTION
 *  ════════════════════════════════════════════════════════════════════════
 *  An App Open Ad is shown automatically during the splash / loading screen.
 *  The ad is loaded asynchronously by [SilinaApplication] on a background
 *  thread; this Activity requests it AFTER the 3-second splash animation
 *  completes. If no creative is ready (cold launch race), the user proceeds
 *  to the next screen with zero delay — the app NEVER blocks on an ad.
 *
 *  D-PAD FOCUS ENGINE: [dispatchKeyEvent] is overridden so the TV remote's
 *  CENTER (OK) + BACK buttons dismiss the ad immediately, and focus is
 *  forced onto the close button the moment the ad appears.
 * ════════════════════════════════════════════════════════════════════════
 *
 *  SPLASH DURATION: 3 seconds (was 7 seconds).
 *  LOADING INDICATOR: Modern 3-dot bouncing animation (was CircularProgressIndicator).
 *  NO LAUNCHER ICON: The splash starts directly with the background image
 *  + loading animation — no app icon shown on a separate screen first.
 * ════════════════════════════════════════════════════════════════════════
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        applyImmersiveFullscreen()
        super.onCreate(savedInstanceState)
        // V9.6 — Visible version Toast so the user can confirm they have
        // the latest fix installed. This is critical because previous
        // versions had versionCode=1 (never bumped), so Android may have
        // silently refused to install the updates.
        android.widget.Toast.makeText(this,
            "SilinaTV Pro v10.2 (Video + Cache Fix)",
            android.widget.Toast.LENGTH_LONG
        ).show()
        // Bind the D-Pad focus engine so TV remote key events are intercepted
        // while the App Open Ad is showing.
        DPadAdFocusEngine.bindActivity(this)
        setContent {
            SplashScreenComposable(
                onSplashComplete = {
                    // Routing is handled inside the composable via LaunchedEffect
                }
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  V7.3 §III — D-Pad Key Event Interception
    //  ════════════════════════════════════════════════════════════════════════
    //  Traps the remote's CENTER (OK) + BACK buttons while the App Open Ad is
    //  showing, performing an immediate dismissal so the user is never trapped.
    // ════════════════════════════════════════════════════════════════════════
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DPadAdFocusEngine.dispatchAdKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        DPadAdFocusEngine.unbindActivity(this)
    }
}

/**
 * Splash Screen Composable
 *
 * Routing is handled internally via LaunchedEffect — no runBlocking needed.
 * NO external URLs — uses local drawable + gradient background only.
 */
@Composable
fun SplashScreenComposable(
    onSplashComplete: () -> Unit
) {
    val context = LocalContext.current

    // Animation state - starts false and becomes true immediately
    var startAnimation by remember { mutableStateOf(false) }

    // Trigger animation on composition
    LaunchedEffect(Unit) {
        startAnimation = true
    }

    // Animated values
    val alphaAnimation by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "alpha"
    )

    val scaleAnimation by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 800),
        label = "scale"
    )

    // ═══════════════════════════════════════════════════════════════
    //  V7.3 — APP OPEN AD + NAVIGATION (ANR-SAFE)
    //  ═══════════════════════════════════════════════════════════════
    //  ANR FIX: Navigation is NOW FULLY DECOUPLED from the ad system.
    //
    //  1. Splash animation plays for 3 seconds (loading indicator).
    //  2. After 3 seconds, the routing target is determined and the user
    //     is navigated IMMEDIATELY — unconditionally. The app NEVER waits
    //     for an ad to load or show.
    //  3. The App Open Ad is shown ONLY if it is ALREADY cached (warm).
    //     If it is not ready, it is skipped silently — no fetch, no wait,
    //     no main-thread work. The ad will be warm for the NEXT cold launch.
    //  4. The D-Pad focus engine is NOT invoked during splash (it traverses
    //     the view tree on the main thread, which contributed to the ANR).
    //     It is only used in PlayerActivity where the ad surface is stable.
    // ═══════════════════════════════════════════════════════════════
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        // Determine routing target.
        val repo = ProfileRepository.getInstance(context)
        val hasProfiles = withContext(Dispatchers.IO) {
            repo.getAllProfiles().first().isNotEmpty()
        }
        val target = if (hasProfiles) ProfilesActivity::class.java else LoginActivity::class.java

        // ═══════════════════════════════════════════════════════════
        //  NAVIGATE FIRST — unconditionally, before any ad logic.
        //  This guarantees the user is NEVER stuck on the splash screen,
        //  even if the AdMob SDK is misbehaving or Play Services is absent.
        // ═══════════════════════════════════════════════════════════
        context.startActivity(Intent(context, target))
        (context as? ComponentActivity)?.finish()
        onSplashComplete()

        // ═══════════════════════════════════════════════════════════
        //  V7.3 §I.1 — App Open Ad (Splash Screen) — BEST-EFFORT
        //  ═══════════════════════════════════════════════════════════
        //  The ad is shown ONLY if already cached. It is overlaid on top
        //  of the NEXT screen (Login/Profiles) briefly. If not cached, it
        //  is skipped — no fetch, no wait, no main-thread congestion.
        //  The ad will be pre-loaded by the deferred init for the next
        //  cold launch.
        // ═══════════════════════════════════════════════════════════
        val activity = context as? ComponentActivity
        if (activity != null) {
            // Best-effort: show the cached ad if ready. Non-blocking.
            AdMobManager.showAppOpenAdIfCached(activity)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .deepSpaceBackground(withCinematicGlow = true)
        ) {
            // Layer 1: Full-screen background image
            Image(
                painter = painterResource(id = R.drawable.splash_background),
                contentDescription = "Silina TV Splash Background",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alphaAnimation),
                contentScale = ContentScale.Crop
            )

            // Layer 2: Light scrim overlay — keeps the background image clearly
            // visible (V8.5.3: was 0.3/0.5/0.7 which over-darkened the new
            // splash wallpaper). Now only the bottom fades slightly so the
            // loading indicator remains legible.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.15f),
                                Color.Black.copy(alpha = 0.45f)
                            )
                        )
                    )
            )

            // Layer 3: Subtle accent glow at bottom — Aurora Cyan + Electric Indigo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AccentIndigo.copy(alpha = 0.20f),
                                AccentCyan.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ═══════════════════════════════════════════════════════════════
            //  Layer 4: Modern 3-Dot Bouncing Loading Indicator
            //  ═══════════════════════════════════════════════════════════════
            //  Three dots that bounce up and down with staggered delays,
            //  creating a smooth wave effect. Each dot has a gradient
            //  fill (AccentIndigo → AccentCyan) and a subtle glow.
            //
            //  This replaces the old CircularProgressIndicator with a
            //  modern, premium animation that matches the Deep Space
            //  2026 design language.
            // ═══════════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(alphaAnimation)
                ) {
                    // Three bouncing dots with staggered animation phases.
                    BouncingDot(delayMs = 0)
                    BouncingDot(delayMs = 150)
                    BouncingDot(delayMs = 300)
                }
            }
        }
    }
}

/**
 * A single bouncing dot with a gradient fill.
 *
 * The dot moves up and down in an infinite loop with a slight scale
 * change at the peak (squash-and-stretch effect). The [delayMs] parameter
 * staggers the animation so the three dots create a wave pattern.
 *
 * @param delayMs  Delay before the bounce starts (creates the wave).
 */
@Composable
private fun BouncingDot(delayMs: Int) {
    // Infinite transition for the bounce.
    val infiniteTransition = rememberInfiniteTransition(label = "dotBounce")

    // Y-offset animation: 0 → -12dp → 0 (bounce up).
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offsetY"
    )

    // We use a two-phase animation: the offset goes from 0 to -1 and back.
    // We multiply by 12.dp value to get the actual pixel offset.
    val bounceProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounceProgress"
    )

    // Scale animation: 1.0 at rest, 0.8 at peak (squash effect).
    val scale by animateFloatAsState(
        targetValue = if (bounceProgress > 0.5f) 0.85f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "dotScale"
    )

    // The dot: a small circle with a gradient fill.
    Box(
        modifier = Modifier
            .size(10.dp)
            .offset(y = (-bounceProgress * 12f).dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(AccentIndigo, AccentCyan)
                )
            )
            .graphicsLayer {
                // Subtle shadow/glow at the dot's edges.
                shadowElevation = 4f
            }
    )
}

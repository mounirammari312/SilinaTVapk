package com.superz.iptvplayer

import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import java.util.concurrent.TimeUnit

/**
 * Launch smoke test — reproduces the exact on-device startup path:
 * CrashProvider install → IPTVApp.onCreate → MainActivity.onCreate →
 * setContent → Compose composition → Room flow → LoginScreen render.
 *
 * If the app "crashes immediately on open" for logic/version reasons,
 * this test fails with the REAL stack trace (no adb needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchSmokeTest {

    @Test
    fun mainActivityLaunchesAndComposes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        try {
            // Drive the main looper: Compose composition, Choreographer frames,
            // recompositions triggered by the Room flow emission.
            // v1.19.12 — BOUNDED idling: a bare idle() drains the queue until
            // EMPTY, and Compose/Choreographer self-reposting frame callbacks
            // make that "never" — the loop below then never reached its own
            // deadline and the test hung forever on 2-core boxes. idleFor()
            // processes exactly one bounded time-slice per call, so the
            // deadline below is now the real wall-clock bound.
            val looper = Looper.getMainLooper()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                shadowOf(looper).idleFor(500L, TimeUnit.MILLISECONDS)
                Thread.sleep(50)
            }
        } finally {
            controller.destroy()
        }
    }
}

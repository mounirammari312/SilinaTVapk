package com.superz.iptvplayer.data.remote

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v2.0.0 — the update center's decision logic (Robolectric for the prefs).
 *
 * The panel announces {versionCode, apkUrl, mandatory}; the app shows the
 * update dialog ONLY when the announced code beats the running build AND a
 * real APK URL exists. A declined non-mandatory version stays declined until
 * the panel announces something newer; mandatory always comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCenterLogicTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val buildVersion: Int get() = com.superz.iptvplayer.BuildConfig.VERSION_CODE

    private fun info(
        versionCode: Int,
        apkUrl: String = "https://dl.example.com/oria.apk",
        mandatory: Boolean = false
    ) = RemoteConfig.UpdateInfo(
        versionCode = versionCode,
        versionName = "9.$versionCode.9",
        notes = "n",
        apkUrl = apkUrl,
        mandatory = mandatory
    )

    /** Fresh prefs + no pending offer before every case. */
    private fun reset() {
        context.getSharedPreferences("oria_remote", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // defaults announce the RUNNING version → isOutdated=false → clears
        UpdateCenter.evaluate(RemoteConfig(), context)
    }

    @Test
    fun outdatedOnlyWhenNewerCodeAndRealUrl() {
        assertTrue(UpdateCenter.isOutdated(info(buildVersion + 1)))
        // same version → not an update
        assertFalse(UpdateCenter.isOutdated(info(buildVersion)))
        // newer code but no APK to install → nothing to offer
        assertFalse(UpdateCenter.isOutdated(info(buildVersion + 1, apkUrl = "")))
    }

    @Test
    fun evaluateArmsPendingAndClearsWhenObsolete() {
        reset()
        val cfg = RemoteConfig(update = info(buildVersion + 3))
        UpdateCenter.evaluate(cfg, context)
        assertNotNull(UpdateCenter.pending)
        assertEquals(buildVersion + 3, UpdateCenter.pending?.versionCode)

        // the panel later announces the RUNNING version → the offer clears
        UpdateCenter.evaluate(RemoteConfig(update = info(buildVersion)), context)
        assertNull(UpdateCenter.pending)
    }

    @Test
    fun declinedVersionStaysDeclinedUntilSomethingNewer() {
        reset()
        val cfg = RemoteConfig(update = info(buildVersion + 5))
        UpdateCenter.evaluate(cfg, context)
        assertNotNull(UpdateCenter.pending)

        UpdateCenter.dismiss(context)   // user pressed "لاحقاً"
        assertNull(UpdateCenter.pending)

        // re-evaluating the SAME announcement does not nag again…
        UpdateCenter.evaluate(cfg, context)
        assertNull(UpdateCenter.pending)

        // …but a NEWER announcement re-arms the dialog
        val newer = RemoteConfig(update = info(buildVersion + 6))
        UpdateCenter.evaluate(newer, context)
        assertNotNull(UpdateCenter.pending)
    }

    @Test
    fun mandatoryUpdatesIgnoreTheDismissal() {
        reset()
        val cfg = RemoteConfig(update = info(buildVersion + 5, mandatory = true))
        UpdateCenter.evaluate(cfg, context)
        assertNotNull(UpdateCenter.pending)

        UpdateCenter.dismiss(context)   // even after a dismissal…
        UpdateCenter.evaluate(cfg, context)   // …a mandatory update returns
        assertNotNull(UpdateCenter.pending)
    }
}

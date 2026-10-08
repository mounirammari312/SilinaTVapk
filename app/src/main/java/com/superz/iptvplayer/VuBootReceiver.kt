package com.superz.iptvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.superz.iptvplayer.ui.settings.VuSettingsPrefs

/**
 * v1.5.0 — "AutoStart On Boot UP" (the reference's General Settings item,
 * GetSharedAppInfo default OFF): when the toggle is on and the device
 * finishes booting, launch the app's entry activity. The receiver is
 * registered unconditionally in the manifest and GUARDED by the stored
 * preference — off (the default) makes it a no-op, exactly like the
 * reference's checked state.
 */
class VuBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VuSettingsPrefs.bool(context, "general_autostart", false)) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }
}

package com.superz.iptvplayer.data.remote

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.superz.iptvplayer.MainActivity
import com.superz.iptvplayer.R

/**
 * v2.0.0 — displays the panel's push notifications.
 *
 * Foreground messages land in onMessageReceived (we show them ourselves);
 * background/killed-app messages with a `notification` payload are shown
 * by the system using this channel automatically — either way the user
 * sees the panel's announcement. Tapping opens the app.
 */
class OriaMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        val title = n.title ?: "ORIA"
        val body = n.body ?: return

        val tap = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 9001, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, OriaFirebase.CHANNEL_ID)
            .setSmallIcon(R.drawable.oria_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(notificationId(title, body), notification)
        } catch (_: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS grant — silently skip
        }
    }

    private fun notificationId(title: String, body: String): Int =
        (Uri.parse("oria://x/$title/$body").hashCode() and 0x7fffffff)

    override fun onNewToken(token: String) {
        // Topic-based delivery needs no per-device token bookkeeping.
    }
}

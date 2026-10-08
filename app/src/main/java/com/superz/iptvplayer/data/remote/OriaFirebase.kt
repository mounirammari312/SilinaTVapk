package com.superz.iptvplayer.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * v2.0.0 — FIREBASE CLOUD MESSAGING (push from the admin panel).
 *
 * Manual initialization (NO google-services.json / plugin needed): the
 * four project values below are all the SDK requires. They come from
 * the user's Firebase console → google-services.json:
 *
 *   FIREBASE_APP_ID     ← client[].client_info.mobilesdk_app_id
 *   FIREBASE_API_KEY    ← client[].api_key[].current_key
 *   FIREBASE_PROJECT_ID ← project_info.project_id
 *   FIREBASE_SENDER_ID  ← project_info.project_number
 *
 * While they are empty [ENABLED] is false and init() is a no-op: the
 * app runs 100% normally, the panel simply reports "notifications not
 * configured". The moment the values arrive (a one-line rebuild —
 * v2.0.1) push goes live with zero architectural change.
 */
object OriaFirebase {

    private const val TAG = "OriaFirebase"
    const val TOPIC = "oria_all"
    const val CHANNEL_ID = "oria_general"

    // ── fill from the user's google-services.json when they provide it ──
    private const val FIREBASE_APP_ID = ""
    private const val FIREBASE_API_KEY = ""
    private const val FIREBASE_PROJECT_ID = ""
    private const val FIREBASE_SENDER_ID = ""

    /** False until the four constants above are filled in. */
    val ENABLED: Boolean =
        FIREBASE_APP_ID.isNotBlank() && FIREBASE_API_KEY.isNotBlank() &&
                FIREBASE_PROJECT_ID.isNotBlank() && FIREBASE_SENDER_ID.isNotBlank()

    /** Safe to call on every start — no-ops when disabled or already init'd. */
    fun init(context: Context) {
        if (!ENABLED) {
            Log.d(TAG, "firebase not configured — push disabled (fill the 4 constants)")
            return
        }
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(FIREBASE_APP_ID)
                        .setApiKey(FIREBASE_API_KEY)
                        .setProjectId(FIREBASE_PROJECT_ID)
                        .setGcmSenderId(FIREBASE_SENDER_ID)
                        .build()
                )
            }
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) Log.i(TAG, "subscribed to topic $TOPIC")
                    else Log.w(TAG, "topic subscribe failed: ${task.exception?.message}")
                }
            createChannel(context)
            Log.i(TAG, "firebase push initialized")
        } catch (e: Exception) {
            Log.w(TAG, "firebase init failed (push stays off): ${e.message}")
        }
    }

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ORIA — إشعارات عامة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات لوحة تحكم ORIA"
                enableVibration(true)
            }
        )
    }
}

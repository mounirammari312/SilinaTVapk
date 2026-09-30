package com.agon.app.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * CastOptionsProvider — V9.8
 *
 * Required by the Google Cast framework. Registered in AndroidManifest.xml
 * via the "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
 * meta-data key.
 *
 * Configures:
 *   - The receiver application ID (DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
 *     uses Google's built-in media receiver — works with any HTTP stream)
 *   - No custom session providers (we use the default CastSession)
 *
 * To use a CUSTOM Styled Media Receiver (for branding), replace
 * DEFAULT_MEDIA_RECEIVER_APPLICATION_ID with your own receiver app ID
 * from the Cast SDK Developer Console (https://cast.google.com/publish).
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(
                com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null  // Use default CastSession only
    }
}

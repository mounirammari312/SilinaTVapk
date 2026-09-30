# ════════════════════════════════════════════════════════════════════════
#  V8.0 §V — R8 / ProGuard RULES (Silina TV Commercial Protection)
#  ════════════════════════════════════════════════════════════════════════
#
#  This file defines the obfuscation + shrink rules for the release build.
#  R8 obfuscates the bytecode (renames classes/methods/fields to short
#  meaningless names) and removes unused code — protecting the DoH tunnel,
#  API keys, and business logic from reverse engineering.
#
#  CRITICAL: the rules below PREVENT R8 from destroying the streaming
#  engine, the FFmpeg JNI bridge, the Room database schema, and the
#  serialization models. Removing any of these rules WILL crash the app.
# ════════════════════════════════════════════════════════════════════════

# ───────────────────────────────────────────────────────────────────────
#  1. MEDIA3 / EXOPLAYER — Streaming Engine Protection
# ───────────────────────────────────────────────────────────────────────
#  androidx.media3 is the core video engine (ExoPlayer + HLS + UI). R8 must
#  NOT touch its classes — the player uses reflection to instantiate
#  renderers, extractors, and track selectors at runtime.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.session.** { *; }

# ───────────────────────────────────────────────────────────────────────
#  2. FFMPEG JNI BRIDGE — Native Decoder Protection
# ───────────────────────────────────────────────────────────────────────
#  The :decoder_ffmpeg module loads libffmpegJNI.so via System.loadLibrary
#  and calls native methods declared in FfmpegLibrary + FfmpegAudioDecoder.
#  R8 must NOT rename these — the JNI bridge looks up the Java class/method
#  names by their ORIGINAL signatures.
#  The broad package keep + the global native-method keep below cover every
#  native method in the app without fragile per-class rules.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ───────────────────────────────────────────────────────────────────────
#  3. ROOM DATABASE — Schema + DAO Stability
# ───────────────────────────────────────────────────────────────────────
#  Room generates implementation classes at compile time (via KSP). R8 must
#  preserve the entity classes, DAO interfaces, and the generated
#  Xxx_Impl classes — otherwise the database cannot read/write channels,
#  playlists, or match data.
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class com.agon.app.data.db.** { *; }
-keep class com.agon.app.data.db.entities.** { *; }
-keep class com.agon.app.data.db.daos.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ───────────────────────────────────────────────────────────────────────
#  4. KOTLINX SERIALIZATION — JSON Model Stability
# ───────────────────────────────────────────────────────────────────────
#  kotlinx.serialization uses reflection + generated serializers to
#  parse M3U/Xtream JSON responses. R8 must NOT rename @Serializable
#  classes or their fields — the JSON keys are tied to field names.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keep class com.agon.app.data.model.** { *; }

# ───────────────────────────────────────────────────────────────────────
#  5. OKHTTP + DOH TUNNEL — Network Stack Protection
# ───────────────────────────────────────────────────────────────────────
#  OkHttp and the DoH (DNS-over-HTTPS) module use platform reflection and
#  must be preserved. The RedirectSniffer + ProxyForegroundService depend
#  on the internal DnsOverHttps resolver class.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.dnsoverhttps.** { *; }
-keep class com.agon.app.proxy.** { *; }
-keep class com.agon.app.bandwidth.** { *; }
-keep class com.agon.app.proxy.RedirectSniffer { *; }
-keep class com.agon.app.proxy.ProxyForegroundService { *; }

# ───────────────────────────────────────────────────────────────────────
#  6. ADMOB / GOOGLE PLAY SERVICES ADS — V7.3 Ad System
# ───────────────────────────────────────────────────────────────────────
#  The AdMob SDK uses runtime reflection to load ad adapters. R8 must
#  preserve the ads package + the Google Play Services ads classes.
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
-keep class com.agon.app.ads.** { *; }
-keep public class com.google.android.gms.common.** { *; }

# ───────────────────────────────────────────────────────────────────────
#  7. JETPACK COMPOSE — UI Runtime
# ───────────────────────────────────────────────────────────────────────
#  Compose uses extensive runtime reflection for the snapshot system + the
#  Composable function call dispatch. Keep the runtime + foundation.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.foundation.** { *; }
-dontwarn androidx.compose.**

# ───────────────────────────────────────────────────────────────────────
#  8. COIL IMAGE LOADING — Channel Logos
# ───────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ───────────────────────────────────────────────────────────────────────
#  9. RETROFIT + GSON — Xtream API Client
# ───────────────────────────────────────────────────────────────────────
-keep class retrofit2.** { *; }
-keepattributes Signature, Exceptions
-dontwarn retrofit2.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ───────────────────────────────────────────────────────────────────────
#  10. ZXING QR CODE — Smart Connect Bridge
# ───────────────────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ───────────────────────────────────────────────────────────────────────
#  11. JSOUP — Sports Match Harvester
# ───────────────────────────────────────────────────────────────────────
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ───────────────────────────────────────────────────────────────────────
#  12. APPLICATION ENTRY POINTS — Activities + Application
# ───────────────────────────────────────────────────────────────────────
#  Activities are referenced by name in AndroidManifest.xml — they must
#  keep their original class names.
-keep class com.agon.app.SilinaApplication { *; }
-keep class com.agon.app.ads.SilinaApplication { *; }
-keep class com.agon.app.MainActivity { *; }
-keep class com.agon.app.ui.screens.** { *; }
-keep class com.agon.app.config.AppConfig { *; }

# ───────────────────────────────────────────────────────────────────────
#  13. GENERAL ANDROID SAFETY NETS
# ───────────────────────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application
-keep public class * extends android.view.View
-keep public class * extends androidx.lifecycle.ViewModel
-keep public class * extends androidx.lifecycle.AndroidViewModel

#  Enum methods — R8 must keep the values() and valueOf() methods so
#  reflection-based enums (e.g. PlaylistType) keep working.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

#  Parcelable creators — must keep their CREATOR fields.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ───────────────────────────────────────────────────────────────────────
#  14. V9.7 — ANDROIDX DATASTORE + ENCRYPTED PREFS + VIEWMODELS
# ───────────────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
-keep class com.agon.app.data.** { *; }
-keep class com.agon.app.recommendation.** { *; }
-keep class com.agon.app.bandwidth.** { *; }

#  V9.7 — BuildConfig class (generated, must be kept for ADMOB_APP_ID access)
-keep class com.agon.app.BuildConfig { *; }

#  V9.7 — Keep all classes with @Serializable from Gson too (Xtream models
#  use Gson @SerializedName, not kotlinx.serialization)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.agon.app.data.model.** { *; }

# ───────────────────────────────────────────────────────────────────────
#  15. V9.8 — GOOGLE CAST (Chromecast) FRAMEWORK
# ───────────────────────────────────────────────────────────────────────
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-dontwarn com.google.android.gms.cast.**
-keep class com.agon.app.cast.** { *; }

# ───────────────────────────────────────────────────────────────────────
#  16. V9.8 — CRASH REPORTER + UTILS
# ───────────────────────────────────────────────────────────────────────
-keep class com.agon.app.util.** { *; }

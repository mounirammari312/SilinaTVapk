# Keep libVLC JNI bridge intact
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Keep Media3 (defensive; consumer rules normally cover this)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

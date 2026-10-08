// IPTV Player — Phase 1 (Hybrid Engine)
// Proven stable toolchain:
//   AGP 8.9.1 / Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Gradle 8.11.1
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

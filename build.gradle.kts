// Top-level build file
// ════════════════════════════════════════════════════════════════════════
//  V8.0 §II — BUILD ENVIRONMENT ALIGNMENT (Gradle & Kotlin Downgrade)
//  ════════════════════════════════════════════════════════════════════════
//  Versions downgraded to STABLE releases so any future buyer can sync the
//  project with ZERO errors on a standard Android Studio setup. The previous
//  bleeding-edge versions (Kotlin 2.2.x, AGP 8.10.x) required
//  non-default toolchains and caused sync failures on buyer machines.
//
//  Aligned stable stack:
//    • Android Gradle Plugin (AGP)  → 8.9.1
//    • Kotlin (android + compose + serialization) → 2.1.0
//    • KSP (Kotlin Symbol Processing) → 2.1.0-1.0.29
//  All app-level dependencies (Room 2.7.2, Compose BOM, etc.) are fully
//  compatible with Kotlin 2.1.0 — no further changes required.
// ════════════════════════════════════════════════════════════════════════
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

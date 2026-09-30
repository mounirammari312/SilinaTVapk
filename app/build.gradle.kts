import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ════════════════════════════════════════════════════════════════════════
//  V8.4 §Release — DEDICATED RELEASE SIGNING CONFIG
//  ════════════════════════════════════════════════════════════════════════
//  Loads the release keystore credentials from keystore.properties (kept at
//  the project root, NOT committed to VCS). When the file is present, the
//  release variant is signed with the dedicated silina-release.keystore —
//  producing a real, installable, signed SilinaTV-pro.apk.
//
//  If keystore.properties is absent (e.g. a fresh checkout by another
//  developer), the config transparently falls back to the debug keystore so
//  `./gradlew assembleRelease` never breaks. This guarantees the build is
//  reproducible everywhere while still preferring the real release key.
// ════════════════════════════════════════════════════════════════════════
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.agon.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.agon.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "10.5"

        // FFmpeg native libs are only built for ARM architectures.
        // Restrict the APK to arm64-v8a + armeabi-v7a (covers all
        // Android TV devices — Xiaomi MiTV, Amazon Fire Stick,
        // Nvidia Shield, generic Amlogic/Rockchip boxes, etc.).
        // x86/x86_64 are emulators/Intel-based tablets — not a target.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // V9.7 — AdMob App ID via BuildConfig + manifestPlaceholders.
        // Reads from local.properties (ADMOB_APP_ID=ca-app-pub-XXXX~XXXX).
        // Falls back to Google's official test App ID when not set, so
        // the build never breaks on a fresh checkout. To use real ads,
        // add ADMOB_APP_ID=ca-app-pub-YOUR_PUB~YOUR_APP to local.properties.
        val admobAppId = (project.findProperty("ADMOB_APP_ID") as String?)
            ?: System.getenv("ADMOB_APP_ID")
            ?: "ca-app-pub-3940256099942544~3347511713"  // Google test ID (fallback)
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        // V9.7 — Inject into AndroidManifest.xml meta-data
        manifestPlaceholders["admobAppId"] = admobAppId
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Prefer the dedicated release keystore (keystore.properties).
            // Fall back to the debug keystore so the build stays green when
            // the release credentials are not available.
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                storeFile = file("${rootProject.projectDir}/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // ═══════════════════════════════════════════════════════════════
            //  V9.7 — R8/ProGuard RE-ENABLED
            //  ═══════════════════════════════════════════════════════════════
            //  Previously disabled (V8.4) due to OOM on 4GB build boxes.
            //  V9.7 re-enables it with:
            //    1. Increased JVM heap in gradle.properties (-Xmx2g)
            //    2. Comprehensive ProGuard keep rules in proguard-rules.pro
            //    3. isShrinkResources = false (preserves dynamic resources)
            //
            //  Benefits:
            //    - APK size reduced ~30-40%
            //    - String constants (URLs, API keys) obfuscated
            //    - DoH + Header Spoofing logic protected from RE
            //    - Dead code removed (smaller attack surface)
            // ═══════════════════════════════════════════════════════════════
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Rename the release APK output to SilinaTV-pro.apk
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
                output.outputFileName = "SilinaTV-pro-v102.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true  // V9.7 — needed for ADMOB_APP_ID BuildConfig field
    }
}

dependencies {
    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Coil Image Loading (version 2.6.0 as specified)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Google Play Services Ads
    implementation("com.google.android.gms:play-services-ads:23.0.0")

    // ── V9.8: Google Cast (Chromecast) support ──
    implementation("com.google.android.gms:play-services-cast:21.3.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.3.0")

    // Retrofit & Gson for API calls
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // OkHttp for M3U parsing (version 4.12.0 as specified)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── V5.0: OkHttp DNS-over-HTTPS (DoH) module ──
    // Used by RedirectSniffer + ProxyForegroundService to resolve IPTV
    // domains (e.g. darplayer.xyz) via Cloudflare's 1.1.1.1 DoH endpoint,
    // bypassing local ISP DNS poisoning / hijacking. The DoH resolver
    // wraps every domain resolution in an encrypted HTTPS tunnel so
    // local cellular / landline providers cannot inspect or rewrite
    // the DNS answers.
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3 ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    // Media3 Session — binds ProxyForegroundService lifecycle to the player's MediaSession
    implementation("androidx.media3:media3-session:1.2.1")
    // FFmpeg extension — software fallback decoder for HEVC 10-bit, AV1,
    // DTS, AC3, E-AC3 and other codecs not supported by hardware decoders
    // on cheap Android TV boxes (Xiaomi MiTV, Amazon Fire Stick, etc.).
    // Native libs (libffmpegJNI.so) are prebuilt for arm64-v8a + armeabi-v7a.
    // The ExoPlayer uses EXTENSION_RENDERER_MODE_PREFER so FFmpeg is preferred
    // when available — this guarantees all channels play on all devices.
    implementation(project(":decoder_ffmpeg"))

    // ── Room Database (Local persistence for huge playlists + matches table) ──
    // Replaces in-memory arrays; Flow-based reads; PagingSource support.
    // Room 2.7.x is required for Kotlin 2.2.x + KSP2 compatibility.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.room:room-paging:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Paging 3 — lazy paged reads for very large channel lists (no UI lag)
    implementation("androidx.paging:paging-runtime-ktx:3.3.5")
    implementation("androidx.paging:paging-compose:3.3.5")

    // ZXing Core for QR Code generation (WhatsApp diagnostics)
    implementation("com.google.zxing:core:3.5.3")

    // Jsoup — Live Sports Harvester Engine (HTML scraping for today's matches)
    implementation("org.jsoup:jsoup:1.17.2")

    // ── AndroidX TV Libraries (D-Pad focus navigation + TV components) ──
    // Provides TvLazyColumn / TvLazyRow with built-in Focus Restorer,
    // Modifier.focusRestorer(), and immersive TV Material components.
    implementation("androidx.tv:tv-foundation:1.0.0-alpha12")
    implementation("androidx.tv:tv-material:1.0.0")

    // Debug Tools
    debugImplementation("androidx.compose.ui:ui-tooling")
}

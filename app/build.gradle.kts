import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing credentials loaded from keystore.properties (project root).
// Falls back gracefully when absent so the build never breaks on a fresh checkout.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.superz.iptvplayer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.superz.iptvplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 85
        versionName = "2.4.1"

        // v1.2.0 — arm64-v8a ONLY (95%+ of modern phones/TV boxes are arm64;
        // Android TV 12+ is 64-bit only). Cuts the APK from ~97MB to ~52MB with
        // ZERO performance loss on modern devices — 64-bit code paths are equal
        // or faster (more registers, optimized NEON). A v7a build remains one
        // flag away if ever needed. `-Pemu` adds x86_64 for local testing.
        ndk {
            if (project.hasProperty("emu")) {
                abiFilters += listOf("x86_64", "arm64-v8a")
            } else {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Rename the release APK output
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
                // v1.8.0 — rebrand: the app is now "Oria"
                output.outputFileName = "Oria-v${variant.versionName}.apk"
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
        // v2.0.0 — BuildConfig.VERSION_CODE/VERSION_NAME feed the update
        // comparison + heartbeat payload (AGP 8 defaults this to OFF).
        buildConfig = true
    }

    packaging {
        jniLibs {
            if (!project.hasProperty("emu")) {
                excludes += listOf("lib/x86/**", "lib/x86_64/**")
            }
        }
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs real resources (strings, themes, mipmap)
            isIncludeAndroidResources = true
            // Plain-JVM tests (ResilientDohTest) touch android.util.Log —
            // make framework stubs no-op instead of throwing.
            isReturnDefaultValues = true
            all { test ->
                test.maxHeapSize = "1280m"
            }
        }
    }
}

dependencies {
    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Coil — channel logo loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Kotlin Serialization + Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3 / ExoPlayer — primary engine (fast HLS/TS start, shared OkHttp pool)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // libVLC — fallback engine (RTSP/UDP/RTMP + exotic streams).
    // `-Plite` builds a ~12MB diagnostic APK WITHOUT the VLC natives
    // (90% of the APK size): launch-path crashes reproduce identically,
    // and if the lite build does NOT crash the problem is native/ABI-related.
    if (project.hasProperty("lite")) {
        compileOnly("org.videolan.android:libvlc-all:3.6.2")
    } else {
        implementation("org.videolan.android:libvlc-all:3.6.2")
    }

    // Room — playlists / channels / favorites / engine memory
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // OkHttp — shared network layer (Xtream API + M3U download + preconnect + ExoPlayer datasource)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // v1.2.0 — DNS-over-HTTPS module (~20KB): encrypted DNS lookups bypass
    // ISP DNS poisoning of IPTV panel domains → blocked accounts open WITHOUT
    // a VPN. Chain: Cloudflare → Google → System (silent fallback). Shared
    // client integration covers API + M3U + media segments + logos at once.
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // v1.14.0 — zxing QR core (the QR login engine's barcode generator —
    // same version the reference app uses for its SMART CONNECT feature).
    implementation("com.google.zxing:core:3.5.3")

    // v2.0.0 — FCM push from the admin panel (topic "oria_all"). Manual
    // initialization only (OriaFirebase.kt) — NO google-services plugin and
    // NO google-services.json: the four project values are baked as constants
    // the moment the user sends their Firebase config (v2.0.1 one-liner).
    // While the constants are empty every Firebase call is skipped — the
    // dependency sits dormant, costing only ~1.5MB of SDK.
    implementation("com.google.firebase:firebase-messaging:24.1.0")

    // Unit tests (Robolectric launch smoke test — reproduces the on-device
    // startup path on the JVM and captures the real crash stack trace)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Real org.json for plain-JVM unit tests (the SDK stub returns defaults;
    // runtime classpath puts this jar first, so EPG JSON parsing is real).
    testImplementation("org.json:json:20240303")
    // v1.11.0 — stalker VOD/Series client tests against a local HTTP server.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

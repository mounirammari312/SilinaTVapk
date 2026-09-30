plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-decoder:1.2.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("com.google.guava:guava:32.1.3-android")
}

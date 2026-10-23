plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 手機版和電視版共用：API、收藏／紀錄、播放器
android {
    namespace = "com.aniplay.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf("UnsafeOptInUsageError")
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2025.06.00"))
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.foundation:foundation")
    api("androidx.core:core-ktx:1.16.0")
    api("androidx.activity:activity-ktx:1.10.1")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.media3:media3-exoplayer:1.6.1")
    api("androidx.media3:media3-exoplayer-hls:1.6.1")
    api("androidx.media3:media3-ui:1.6.1")
    api("androidx.media3:media3-datasource-okhttp:1.6.1")
}

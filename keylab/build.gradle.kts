// Single-module app: the root project is the app. AGP 9 compiles Kotlin itself,
// so no separate Kotlin plugin is applied.
plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "xyz.pageturner.keylab"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.pageturner.keylab"
        // Built for one tablet (SM-T733, Android 14 / API 34), so min = target.
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

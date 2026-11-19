import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Last.fm API key + secret, kept out of git in local.properties (same as BitChord).
// Get them at https://www.last.fm/api/account/create
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()
        .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.ghostmikz.ghostly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostmikz.ghostly"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LASTFM_API_KEY", "\"${prop("LASTFM_API_KEY")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${prop("LASTFM_SECRET")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs without a keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Same as BitChord: a newer foundation (pulls Compose UI 1.10) for the vendored glass library.
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Last.fm only
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

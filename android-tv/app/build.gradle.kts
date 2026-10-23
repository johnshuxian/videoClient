import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
    .find(file("../../package.json").readText())!!.groupValues[1]
val versionParts = appVersion.split('.').map(String::toInt)
val signingStore = System.getenv("ANDROID_KEYSTORE_PATH")?.let(::file)
    ?: file("../../videoclient-release.jks")
val signingPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    ?: file("../../signing-password.txt").takeIf { it.isFile }?.readText()?.trim()

android {
    namespace = "com.shuxian.videoclient.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shuxian.videoclient.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
        versionName = appVersion
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (signingStore.isFile && !signingPassword.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = signingStore
                storePassword = signingPassword
                keyAlias = "videoclient"
                keyPassword = signingPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    testImplementation("junit:junit:4.13.2")
}

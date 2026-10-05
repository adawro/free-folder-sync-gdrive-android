import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ustawienia lokalne (nie w gicie): sdk.dir, podpis APK i builtinAuth - patrz README
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "pl.adamw.drivesync"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.adamw.drivesync"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }

    // Podpis z local.properties (signing.storeFile/storePassword/keyAlias/keyPassword);
    // bez nich release jest niepodpisany, a debug używa domyślnego klucza debug
    val signing = local.getProperty("signing.storeFile")?.let { path ->
        signingConfigs.create("local") {
            storeFile = rootProject.file(path)
            storePassword = local.getProperty("signing.storePassword")
            keyAlias = local.getProperty("signing.keyAlias")
            keyPassword = local.getProperty("signing.keyPassword")
        }
    }
    buildTypes {
        debug { signing?.let { signingConfig = it } }
        release {
            signing?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }
    defaultConfig {
        // Logowanie przez Google Play Services działa tylko z kluczem zarejestrowanym w Google Cloud autora
        buildConfigField("boolean", "BUILTIN_AUTH", local.getProperty("builtinAuth", "false"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

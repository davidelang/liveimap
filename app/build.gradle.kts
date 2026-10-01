plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.dlang.liveimap"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.dlang.liveimap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    androidResources {
        localeFilters += listOf("en")
    }
    val androidUserHome = System.getenv("ANDROID_USER_HOME")
        ?: error("ANDROID_USER_HOME unset; run ./build_app (sets worktree .android-shared)")
    signingConfigs {
        getByName("debug") {
            storeFile = file("$androidUserHome/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
}

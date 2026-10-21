plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.davidlang.liveimap"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.davidlang.liveimap"
        minSdk = 26
        targetSdk = 36
        // Must increase on every Play upload: raise app/versionCode.txt before each ./build_app --play.
        // A missing or non-numeric file fails the build.
        versionCode = file("versionCode.txt").readText().trim().toInt()
        val rawVersion = providers.exec {
            workingDir = project.rootDir
            commandLine(
                "bash",
                "-c",
                "BRANCH_NAME=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'HEAD') && (git describe --tags --match \"${'$'}{BRANCH_NAME}-start\" 2>/dev/null || git describe --always 2>/dev/null || echo 'dev')"
            )
        }.standardOutput.asText.orNull?.trim() ?: "dev"
        versionName = rawVersion
        val gitCommit = providers.exec {
            workingDir = project.rootDir
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim().orEmpty()
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        manifestPlaceholders["gitCommit"] = gitCommit
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // One APK per device ABI (as VehicleExpenses): app-arm64-<type>.apk for the Pixels, app-x86_64-<type>.apk
    // for the emulator. Every ABI shares the versionCode from app/versionCode.txt.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        // Play bundle only (./build_app --play runs bundlePlayRelease): every shipped ABI in one
        // AAB; Play delivers per-device splits. Its debug variant is disabled below.
        create("play") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
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
            // F20: R8 shrinking and resource shrinking; JNI and reflection entry points in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The play flavor is for the release bundle only; debug builds stay one APK per ABI.
androidComponents {
    beforeVariants(selector().withFlavor("abi" to "play").withBuildType("debug")) { it.enable = false }
}

// Same org/dlang FindClass literals as ProguardKeepSyncTest, plus BuildConfig, must survive arm64 R8.
tasks.register("checkArm64ReleaseKeeps") {
    dependsOn("assembleArm64Release")
    doLast {
        val usagePath = "app/build/outputs/mapping/arm64Release/usage.txt"
        val usage = rootProject.file(usagePath)
        if (!usage.isFile) {
            throw GradleException(usagePath)
        }
        val literal = Regex(""""(org/dlang/[^"]+)"""")
        val classes = file("src/main/cpp").walkTopDown()
            .filter { it.isFile && it.extension in setOf("c", "cc", "cpp", "h", "hpp") }
            .flatMap { literal.findAll(it.readText()).map { m -> m.groupValues[1].replace('/', '.') } }
            .toMutableSet()
        if (classes.isEmpty()) {
            throw GradleException("no org/dlang FindClass names in src/main/cpp")
        }
        classes.add("com.davidlang.liveimap.BuildConfig")
        // R8 prints static void <clinit>() after moving a kept $stable value into the dex
        // field. A -keep rule does not retain that method. Any other member, or the class
        // itself, is a failure.
        val bad = mutableListOf<String>()
        var header: String? = null
        var headerName: String? = null
        val members = mutableListOf<String>()
        fun flush() {
            val name = headerName ?: return
            val line = header ?: return
            if (name in classes) {
                val other = members.filter { it.trim() != "static void <clinit>()" }
                if (members.isEmpty() || other.isNotEmpty()) {
                    bad.add(line)
                    bad.addAll(other)
                }
            }
            header = null
            headerName = null
            members.clear()
        }
        for (line in usage.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            if (line[0] == ' ' || line[0] == '\t') {
                if (header != null) members.add(line)
            } else {
                flush()
                header = line
                headerName = line.trim().removeSuffix(":")
            }
        }
        flush()
        if (bad.isNotEmpty()) {
            bad.forEach { println(it) }
            throw GradleException("usage.txt lists a kept class or member")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    // F20: secrets are sealed with tink-android (security-crypto removed; no legacy read).
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

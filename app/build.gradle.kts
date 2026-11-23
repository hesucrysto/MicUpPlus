plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.micplugin"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        // MicUp++ ships as its own package so it can be installed next to upstream MicUp.
        applicationId = "com.micplugin.plus"
        minSdk = 26
        targetSdk = 34
        // Version injected by CI from git tag (e.g. v1.2.3 → 1.2.3)
        val tagName = System.getenv("RELEASE_TAG") ?: "2.0.0"
        val cleanTag = tagName.removePrefix("v")
        val parts = cleanTag.split(".").mapNotNull { it.toIntOrNull() }
        versionCode = (parts.getOrElse(0){1} * 10000) +
                      (parts.getOrElse(1){0} * 100) +
                       parts.getOrElse(2){0}
        versionName = cleanTag

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math", "-DANDROID")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DFETCHCONTENT_QUIET=OFF"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        prefab = true          // Required: exposes Oboe CMake targets via Prefab AAR
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Audio (Oboe via prefab)
    implementation(libs.oboe)

    // Kotlin
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // Core
    implementation(libs.core.ktx)

    // Storage Access Framework helpers for plugin-folder imports (Android 11+)
    implementation(libs.documentfile)

    // Shizuku — ADB-level privileged API access (no root required).
    // 12.2.0 keeps Shizuku.newProcess() public; 13.1.x made it private and it is
    // scheduled for removal in API 14. The shell bridge in ShizukuManager uses it
    // deliberately, so the API is pinned to the last public release.
    implementation("dev.rikka.shizuku:api:12.2.0")
    implementation("dev.rikka.shizuku:provider:12.2.0")
}

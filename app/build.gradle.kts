plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hasan.clearvoice"
    compileSdk = 34
    // Pinned NDK — must match the version installed in
    // .github/workflows/build-apk.yml via sdkmanager.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.hasan.clearvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // RNNoise native library (src/main/cpp). CMake 3.22.1 matches the
        // sdkmanager package installed in CI.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No AndroidX / Compose dependencies on purpose: framework-only keeps the
// build small and the CI build reliable. Kotlin stdlib is added by the
// Kotlin Android plugin.
dependencies {
}

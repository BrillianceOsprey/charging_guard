plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.charging_guard"
    compileSdk = 35
    ndkVersion = flutter.ndkVersion

    defaultConfig {
        applicationId = "com.example.charging_guard"
        minSdk = 23
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Use Java 17 with AGP 8.7.x / Kotlin 2.1
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    buildTypes {
        debug {
            // R8 off in debug
            isMinifyEnabled = false
            // IMPORTANT: disable resource shrinking if R8 is off
            isShrinkResources = false
        }
        release {
            // Option A (simple): keep R8 off, also keep shrinking off
            isMinifyEnabled = false
            isShrinkResources = false

            // If later you want smaller APKs, switch to:
            // isMinifyEnabled = true
            // isShrinkResources = true
            // and add a real signingConfig

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
}

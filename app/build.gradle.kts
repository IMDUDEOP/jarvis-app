plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Permanent per-device secret storage — AES-encrypted SharedPreferences,
    // not plain-text, since this secret is the device's entire identity
    // going forward.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Persistent WebSocket to remote_pairing.py's /ws endpoint.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR scanning. Wraps ZXing with a ready-made scanning Activity that
    // defaults to the back/environment-facing camera automatically — no
    // manual camera-selection code needed.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

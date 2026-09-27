plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.zcodemobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.zcodemobile.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The relay link is a credential, so it is never committed: pass it at
        // invocation time with -PrelayLink="<url>". Absent, the on-device
        // transport test skips itself.
        testInstrumentationRunnerArguments["relayLink"] =
            (project.findProperty("relayLink") as String?) ?: ""
    }

    buildTypes {
        debug {
            // Every ABI so the app runs on emulators and physical devices alike.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                // Real devices only. Shipping x86/x86_64 here would add ~12 MB of
                // barcode-scanner natives that no phone can load.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Markdown rendering. The `kind: assistantText` rows carry raw Markdown
    // (headings, bold, inline code, fenced blocks, tables, lists), so a real
    // renderer is required rather than plain Text.
    // `dev.snipme:highlights` comes transitively via -code; pinning it
    // separately would force a version the renderer wasn't built against.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.27.0")

    // QR scanning for the desktop pairing code.
    // ZXing rather than ML Kit: the only code we ever read is a QR code, and
    // ML Kit's barcode scanner bundles ~20 MB of native libraries plus TFLite
    // models, versus ~500 KB of pure Java here.
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // On-device verification of the Android transport layer. The JVM verifier
    // exercises the protocol over java.net.http.WebSocket; this covers the
    // OkHttp adapter plus Keystore-backed storage, which only exist on Android.
    // JVM unit tests. The QR decode path and the Markdown table splitter are
    // pure functions, so they are verified on the JVM rather than through a
    // device.
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The BOM from `implementation` does not reach this configuration, so the
    // Compose test artifacts need their own platform entry.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

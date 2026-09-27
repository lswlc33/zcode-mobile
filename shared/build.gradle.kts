import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Export a dynamic framework per iOS target; the Xcode shell in iosApp/
    // links against it and calls MainViewController.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ZcodeShared"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // CMP 1.8.2 maps materialIconsExtended to 1.7.3 whose iOS klib is
            // not published anywhere (available-at points at a 404). The UI
            // therefore ships its own vector icon set (ZcIcons) instead.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
            implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
            implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.27.0")
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.zcodemobile.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}tasks.register("checkFrameworkHeader") {
    doLast {
        val fw = file("build/bin/iosSimulatorArm64/releaseFramework/ZcodeShared.framework/Headers/ZcodeShared.h")
        if (fw.exists()) {
            val text = fw.readText()
            listOf("MainViewController", "AppModel", "IosLinkStore").forEach {
                println("HEADER-CONTAINS $it: ${text.contains(it)}")
            }
            println("HEADER-LINES: ${text.lines().size}")
        } else println("HEADER MISSING")
    }
}

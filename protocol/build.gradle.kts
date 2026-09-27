import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    // JVM toolchain drives both the JVM target and the Android build's
    // kotlinOptions; 17 matches what :app and CI use.
    jvmToolchain(17)

    // The Android shell (:app) and the JVM verifier consume the JVM variant
    // of this module through project(":protocol").
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS device + simulator. Konan downloads its toolchain on first use;
    // on Windows hosts it cross-compiles without any Apple SDK.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// The protocol sources were laid out flat under src/main (+ src/test) before
// the KMP migration; keep them where they are instead of moving 20 files.
// Platform actuals live in the conventional src/<target>Main/kotlin trees.
// The protocol sources were laid out flat under src/main (+ src/test) before
// the KMP migration; keep them where they are instead of moving 20 files.
// Platform actuals live in the conventional src/<target>Main/kotlin trees.
kotlin.sourceSets.configureEach {
    when (name) {
        "commonMain" -> kotlin.srcDir("src/main/kotlin")
        "jvmMain", "androidMain", "iosMain" -> kotlin.srcDir("src/$name/kotlin")
        // Tests were JVM-only pre-migration; they cover the pure common logic.
        "jvmTest" -> kotlin.srcDir("src/test/kotlin")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.1.0"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
        id("com.android.application") version "8.7.3"
    }
}

rootProject.name = "zcode-mobile"

include(":protocol")
include(":verify")

// The Android application module requires the Android SDK, and the Android
// Gradle plugin cannot be resolved without it. Including :app unconditionally
// would break JVM-only builds of :protocol / :verify on machines without the
// SDK, so it is included only when an SDK is actually discoverable.
val androidSdk: String? = sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    rootDir.resolve("local.properties")
        .takeIf { it.exists() }
        ?.let { f ->
            java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("sdk.dir")
        },
).firstOrNull { !it.isNullOrBlank() && file(it).isDirectory }

if (androidSdk != null) {
    include(":app")
} else {
    logger.lifecycle(
        "Android SDK not found (set ANDROID_HOME or local.properties sdk.dir) — " +
            "skipping :app. :protocol and :verify still build."
    )
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

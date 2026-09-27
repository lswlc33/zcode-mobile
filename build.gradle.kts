// KGP must sit on the ROOT buildscript classpath: :shared and :app are
// siblings that both touch Kotlin/Native build services, and with sibling
// classloaders Gradle 9 fails wiring the shared service
// ("kotlinNativeBundleBuildService ... project-app(export)"). Declaring the
// plugins here with `apply false` puts every module on one classloader.
//
// The old warning about this shadowing the Android module's classloader
// applied to mixed Kotlin versions; with a single KGP version everywhere
// (enforced in settings.pluginManagement) it is the supported layout.
plugins {
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.compose") apply false
}

allprojects {
    group = "dev.zcodemobile"
    version = "0.1.0"
}

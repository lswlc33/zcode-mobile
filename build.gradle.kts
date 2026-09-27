// Deliberately no plugins block here.
//
// Declaring `kotlin("jvm") apply false` at the root places the Kotlin Gradle
// plugin on the root buildscript classpath, which then shadows the classloader
// the Android module uses when it requests `org.jetbrains.kotlin.android` —
// surfacing as "Could not generate a decorated class for type
// KotlinAndroidTarget: com/android/build/gradle/api/BaseVariant".
//
// Versions live in `pluginManagement.plugins` (settings.gradle.kts); each
// module requests what it needs.
allprojects {
    group = "dev.zcodemobile"
    version = "0.1.0"
}

// Repository mirrors for networks where dl.google.com and repo1.maven.org are
// unreachable. Deliberately an init script rather than repository declarations
// in settings.gradle.kts, so the project itself stays on the canonical
// repositories and works unchanged elsewhere.
//
// Usage:
//   gradle --init-script gradle/mirrors.init.gradle.kts <tasks>

val GOOGLE = "https://maven.aliyun.com/repository/google"
val CENTRAL = "https://maven.aliyun.com/repository/central"
val GRADLE_PLUGIN = "https://maven.aliyun.com/repository/gradle-plugin"

settingsEvaluated {
    pluginManagement {
        repositories {
            maven { url = uri(GRADLE_PLUGIN) }
            maven { url = uri(GOOGLE) }
            maven { url = uri(CENTRAL) }
            gradlePluginPortal()
        }
    }
    dependencyResolutionManagement {
        repositories {
            maven { url = uri(GOOGLE) }
            maven { url = uri(CENTRAL) }
        }
    }
}

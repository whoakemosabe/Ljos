pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Ljos"
// The server's Docker build skips the Android app, so it needs no Android SDK.
if (System.getenv("LJOS_SKIP_APP") == null) include(":app")
include(":core")
include(":server")

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

rootProject.name = "LustrTV"

// core: talks to the lustr server (plain Kotlin, no Android) - tested with `gradlew :core:test`
// app:  the Android TV app (screens + player)
include(":core", ":app")

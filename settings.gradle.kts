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

rootProject.name = "RemoteStudy"

include(
    ":app-student",
    ":app-teacher",
    ":activity-detection",
    ":camera-capture",
    ":core-domain",
    ":core-protocol",
    ":core-sync",
    ":transport-api",
    ":transport-nearby",
    ":voice-command",
    ":voice-message",
)

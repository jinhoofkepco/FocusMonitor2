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
    ":activity-detection",
    ":camera-capture",
    ":core-domain",
    ":voice-command",
    ":voice-message",
    ":app-voice-lab",
    ":telegram-report",
)

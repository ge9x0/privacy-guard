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
        // libadb-android dhe spake2 publikohen vetem ne JitPack
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") } }
    }
}
rootProject.name = "PrivacyGuard"
include(":app")

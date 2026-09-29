pluginManagement {
    repositories {
        // NOTE: `gradlePluginPortal()` and `services.gradle.org` are unreachable from the
        // build sandbox, and Maven Central only mirrors AGP <= 2.3.0. AGP + androidx plugin
        // markers therefore have to come from google(), and the Kotlin/Compose plugin markers
        // from mavenCentral(). Order matters: google() first keeps AGP resolution fast.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "understudy"

include(":app")
include(":proxy")
include(":proxy-core")

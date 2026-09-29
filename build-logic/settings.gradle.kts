// Composite build carrying the convention plugins shared by the library
// modules (currently just wmkeyboard.detekt). Versions here must track
// gradle/libs.versions.toml; they cannot reference it directly because this
// build compiles before the main build's catalog exists.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"

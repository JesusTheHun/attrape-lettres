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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "attrape-lettres"

// The module graph mirrors apps/game-ios (ARCHITECTURE.md §1), one for one:
//
//   app ──▶ platform ──▶ core
//    │                     ▲
//    └────▶ ui ──▶ art ────┘
//
// `ui` never depends on `platform`. It reaches the device through interfaces
// declared in `core` and injected at the root — which is what keeps
// `./gradlew :core:test` able to run the whole game's logic with no emulator,
// no store, no network and no signing.
include(":core")
include(":art")
include(":ui")
include(":platform")
include(":app")

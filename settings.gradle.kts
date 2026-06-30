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
        // Boson messaging-client / ion-store-client are installed to the local Maven repo.
        mavenLocal()
    }
}

rootProject.name = "PhotonMessenger"

include(":app")
include(":core:model")
include(":core:network")
include(":core:security")
include(":core:boson-wrapper")
include(":core:database")
include(":core:designsystem")
include(":feature:onboarding")
include(":feature:chat")
include(":feature:contacts")
include(":feature:settings")

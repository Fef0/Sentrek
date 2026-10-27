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

rootProject.name = "Sentrek"

include(":app")
include(":core:data")
include(":core:geo")
include(":core:map")
include(":core:ui")
include(":feature:maps")

include(":feature:record")
include(":feature:planner")
include(":baselineprofile")

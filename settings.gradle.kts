pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:8.9.2")
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.omicronapps") } }
    }
}
rootProject.name = "InfiniFolder"
include(":app")

include(":storage-engine")
include(":viewer-common")
include(":viewer-image")
include(":viewer-text")
include(":viewer-markdown")
include(":viewer-html")
include(":viewer-pdf")
include(":viewer-csv")
include(":viewer-epub")
include(":viewer-audio")
include(":viewer-video")

include(":viewer-host")

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

// Pinned external source dependency; clone with --recurse-submodules.
val soundLibrary = file("libraries/libinfinisound")
check(soundLibrary.resolve("settings.gradle.kts").isFile) {
    "libinfinisound is missing. Run: git submodule update --init --recursive"
}
// Android composite builds do not inherit the host's local.properties. Share its
// SDK through AGP's supported system property when no SDK was supplied globally.
if (System.getenv("ANDROID_HOME").isNullOrBlank() &&
    System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() &&
    System.getProperty("android.home").isNullOrBlank()) {
    val localSdk = java.util.Properties().apply {
        val propertiesFile = file("local.properties")
        if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
    }.getProperty("sdk.dir")
    if (!localSdk.isNullOrBlank()) System.setProperty("android.home", file(localSdk).absolutePath)
}
includeBuild(soundLibrary)

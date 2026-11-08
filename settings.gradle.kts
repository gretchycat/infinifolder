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
// Viewer components are self-registering; new modules need no application source list.
rootDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("viewer-") && it.resolve("build.gradle.kts").isFile }
    .sortedBy { it.name }.forEach { include(":" + it.name) }



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

includeBuild("libraries/libinfinistream")
includeBuild("libraries/libinfinimeta")

includeBuild("libraries/libinfinistore")
includeBuild("libraries/libinfinivault")

includeBuild("libraries/libinfinicanvas")
includeBuild("libraries/libinfiniscribe")
includeBuild("libraries/libinfinivideo")
includeBuild("libraries/libinfiniselect")

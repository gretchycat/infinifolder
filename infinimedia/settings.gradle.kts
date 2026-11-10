pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
            useModule("com.android.tools.build:gradle:8.9.2")
        }
        if (requested.id.id == "org.jetbrains.kotlin.android") {
            useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
        }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.omicronapps") } }
    }
}

rootProject.name = "InfiniMedia"

listOf("storage-engine", "viewer-common", "viewer-audio", "viewer-video", "viewer-host").forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("../$name")
}

listOf(
    "libinfinistream", "libinfinimeta", "libinfinistore", "libinfinivault",
    "libinfinicanvas", "libinfiniscribe", "libinfinisound", "libinfinivideo", "libinfiniselect",
).forEach { includeBuild("../libraries/$it") }

if (System.getenv("ANDROID_HOME").isNullOrBlank() &&
    System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() &&
    System.getProperty("android.home").isNullOrBlank()) {
    val propertiesFile = file("../local.properties")
    val sdkPath = if (propertiesFile.isFile) java.util.Properties().apply {
        propertiesFile.inputStream().use { load(it) }
    }.getProperty("sdk.dir") else null
    if (!sdkPath.isNullOrBlank()) System.setProperty("android.home", file(sdkPath).absolutePath)
}

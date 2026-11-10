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

rootProject.name = "InfiniScribe"

// Build the same viewer components used by InfiniFolder, without including its
// application module. These paths are the workspace's reusable Android modules.
val modules = listOf(
    "storage-engine",
    "viewer-common", "viewer-image", "viewer-text", "viewer-markdown", "viewer-html",
    "viewer-epub", "viewer-csv", "viewer-pdf", "viewer-font"
)
modules.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("../$name")
}

// The application can also be built directly from this directory. The required
// library checkouts are the only composite builds it consumes.
includeBuild("../libraries/libinfinistream")
includeBuild("../libraries/libinfinistore")
includeBuild("../libraries/libinfinivault")
includeBuild("../libraries/libinfinicanvas")
includeBuild("../libraries/libinfinisound")
includeBuild("../libraries/libinfiniscribe")
includeBuild("../libraries/libinfiniselect")

if (System.getenv("ANDROID_HOME").isNullOrBlank() &&
    System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() &&
    System.getProperty("android.home").isNullOrBlank()) {
    val propertiesFile = file("../local.properties")
    val sdkPath = if (propertiesFile.isFile) java.util.Properties().apply {
        propertiesFile.inputStream().use { load(it) }
    }.getProperty("sdk.dir") else null
    if (!sdkPath.isNullOrBlank()) System.setProperty("android.home", file(sdkPath).absolutePath)
}

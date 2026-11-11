pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:8.9.2")
        if (requested.id.id.startsWith("org.jetbrains.kotlin.")) useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
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

// Read only this checkout's SDK configuration. Composite builds share the resolved SDK.
if (System.getenv("ANDROID_HOME").isNullOrBlank() && System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() && System.getProperty("android.home").isNullOrBlank()) {
    val local = file("local.properties")
    if (local.isFile) java.util.Properties().apply { local.inputStream().use { load(it) } }
        .getProperty("sdk.dir")?.let { System.setProperty("android.home", file(it).absolutePath) }
}
val dependencyRoot = file("libraries")
System.setProperty("infini.dependencies", dependencyRoot.canonicalPath)
includeBuild(dependencyRoot.resolve("libinfinistream"))
includeBuild(dependencyRoot.resolve("libinfiniselect"))
includeBuild(dependencyRoot.resolve("libinfinicore"))
includeBuild(dependencyRoot.resolve("libinfinistore"))
includeBuild(dependencyRoot.resolve("libinfinivault"))
includeBuild(dependencyRoot.resolve("libinfinicanvas"))
includeBuild(dependencyRoot.resolve("libinfinimeta"))
includeBuild(dependencyRoot.resolve("libinfinisound"))
includeBuild(dependencyRoot.resolve("libinfiniscribe"))
includeBuild(dependencyRoot.resolve("libinfinivideo"))
includeBuild(dependencyRoot.resolve("libinfiniui"))
include(":app", ":storage-engine", ":viewer-hex")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val viewerKind = providers.gradleProperty("viewer").orElse("all").get()
val viewers = listOf("image", "text", "markdown", "html", "pdf", "csv", "epub", "audio", "video")
require(viewerKind == "all" || viewerKind in viewers) { "Unknown viewer: $viewerKind" }
val mimeTypes = when (viewerKind) {
    "image" -> listOf("image/*")
    "text" -> listOf("text/*", "application/json", "application/xml", "application/javascript")
    "markdown" -> listOf("text/markdown", "text/x-markdown")
    "html" -> listOf("text/html", "application/xhtml+xml")
    "pdf" -> listOf("application/pdf")
    "csv" -> listOf("text/csv", "text/tab-separated-values")
    "epub" -> listOf("application/epub+zip")
    "audio" -> listOf("audio/*")
    "video" -> listOf("video/*")
    else -> listOf("text/*", "image/*", "audio/*", "video/*", "application/pdf", "application/epub+zip", "application/json", "application/xml")
}
android {
    namespace = "com.infinifolder.standalone"
    compileSdk = 36
    androidResources { noCompress += "bz2" }
    defaultConfig {
        applicationId = "com.infinifolder.standalone." + viewerKind
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        (1..8).forEach { manifestPlaceholders["viewerMime$it"] = mimeTypes.getOrElse(it - 1) { mimeTypes.first() } }
        manifestPlaceholders["viewerName"] = "InfiniFolder " + viewerKind.replaceFirstChar { it.uppercase() } + " Viewer"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}")
}
dependencies {
    implementation(project(":viewer-common"))
    (if (viewerKind == "all") viewers else listOf(viewerKind)).forEach { implementation(project(":viewer-$it")) }
}

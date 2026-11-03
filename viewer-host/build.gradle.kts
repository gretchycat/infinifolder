plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val viewerKind = providers.gradleProperty("viewer").orElse("all").get()
val viewers = listOf("image", "text", "markdown", "html", "pdf", "csv", "epub", "audio", "video")
require(viewerKind == "all" || viewerKind in viewers) { "Unknown viewer: $viewerKind" }
val workbookMimes=listOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.macroenabled.12", "application/vnd.ms-excel.template.macroenabled.12", "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
    "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template",
    "application/vnd.oasis.opendocument.spreadsheet-flat-xml")
val readingMimes=listOf("application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook",
    "application/x-fictionbook+xml", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-word.document.macroenabled.12", "application/vnd.ms-word.template.macroenabled.12", "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.presentationml.slideshow", "application/vnd.openxmlformats-officedocument.presentationml.template",
    "application/vnd.ms-powerpoint.presentation.macroenabled.12", "application/vnd.ms-powerpoint.slideshow.macroenabled.12", "application/vnd.ms-powerpoint.template.macroenabled.12", "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.text-template", "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template",
    "application/vnd.oasis.opendocument.presentation-flat-xml", "application/vnd.oasis.opendocument.text-flat-xml",
    "application/x-mobi8-ebook", "text/fb2+xml", "application/rtf", "text/rtf")
val mimeTypes = when (viewerKind) {
    "image" -> listOf("image/*")
    "text" -> listOf("text/*", "application/json", "application/xml", "application/javascript", "image/x-ansi")
    "markdown" -> listOf("text/markdown", "text/x-markdown")
    "html" -> listOf("text/html", "application/xhtml+xml")
    "pdf" -> listOf("application/pdf")
    "csv" -> listOf("text/csv", "text/tab-separated-values")+workbookMimes
    "epub" -> listOf("application/epub+zip")+readingMimes
    "audio" -> listOf("audio/*", "application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp")
    "video" -> listOf("video/*")
    else -> listOf("text/*", "image/*", "audio/*", "video/*", "application/pdf", "application/epub+zip", "application/json", "application/xml", "application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp")+workbookMimes+readingMimes
}
require(mimeTypes.size <= 64) { "Add manifest slots for all viewer MIME filters" }
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
        (1..64).forEach { manifestPlaceholders["viewerMime$it"] = mimeTypes.getOrElse(it - 1) { mimeTypes.first() } }
        manifestPlaceholders["viewerName"] = "InfiniFolder " + viewerKind.replaceFirstChar { it.uppercase() } + " Viewer"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}")
}
dependencies {
    implementation(project(":viewer-common"))
    if (viewerKind == "image") implementation(project(":viewer-text")) // ANSI art opens on the DOS text surface.
    (if (viewerKind == "all") viewers else listOf(viewerKind)).forEach { implementation(project(":viewer-$it")) }
}

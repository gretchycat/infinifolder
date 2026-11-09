plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val viewerKind = providers.gradleProperty("viewer").orElse("all").get()
// This module is also included by the independently buildable InfiniMedia app.
// Resolve shared scripts and artwork from the repository containing this module,
// not from whichever Gradle root happened to include it.
val repositoryRoot = layout.projectDirectory.dir("..").asFile
if (rootProject.name == "InfiniMedia") {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/viewer-host"))
}
val viewers = rootProject.subprojects.filter { it.name.startsWith("viewer-") && it.name !in setOf("viewer-host","viewer-common") }.map { it.name.removePrefix("viewer-") }.sorted()
require(viewerKind == "all" || viewerKind in viewers) { "Unknown viewer: $viewerKind" }
android {
    namespace = "com.infinifolder.standalone"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "bz2" }
    defaultConfig {
        applicationId = if (viewerKind == "audio") "com.infinimedia" else "com.infinifolder.standalone." + viewerKind
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "VIEWER_KIND", "\"$viewerKind\"")
        manifestPlaceholders["viewerName"] = if (viewerKind == "audio") "InfiniMedia"
            else "InfiniFolder " + viewerKind.replaceFirstChar { it.uppercase() } + " Viewer"
        manifestPlaceholders["viewerIcon"] = if (viewerKind == "audio") "@mipmap/ic_launcher" else "@android:drawable/sym_def_app_icon"
        manifestPlaceholders["viewerRoundIcon"] = if (viewerKind == "audio") "@mipmap/ic_launcher_round" else "@android:drawable/sym_def_app_icon"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.merges += "META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider"
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}")
}

if (viewerKind == "audio") {
    val mediaIconResources = layout.buildDirectory.dir("generated/infinimedia-res")
    val stageMediaIcons by tasks.registering(Copy::class) {
        from(repositoryRoot.resolve("themes/app-icons/generated/infinimedia")) { include("mipmap-*/**") }
        into(mediaIconResources)
    }
    android.sourceSets.getByName("main").res.srcDir(mediaIconResources)
    tasks.named("preBuild").configure { dependsOn(stageMediaIcons) }
}
dependencies {
    implementation(project(":viewer-common"))
    implementation("io.github.gretchycat.libinfiniselect:select-android:0.1.0")
    if (viewerKind == "image") implementation(project(":viewer-text")) // ANSI art opens on the DOS text surface.
    (if (viewerKind == "all") viewers else listOf(viewerKind)).forEach { implementation(project(":viewer-$it")) }
}

val generatedIntentManifest = layout.buildDirectory.file("generated/android-intents/AndroidManifest.xml")
val generateIntentManifest by tasks.registering(Exec::class) {
    description = "Register supported file types and URI handlers with Android"
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(repositoryRoot.resolve("scripts/build-android-intent-manifest.py"))
    inputs.property("host", viewerKind)
    outputs.file(generatedIntentManifest)
    outputs.file(generatedIntentManifest.map { it.asFile.resolveSibling("AndroidManifest.json") })
    commandLine(providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        repositoryRoot.resolve("scripts/build-android-intent-manifest.py"),
        "--template", layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile,
        "--output", generatedIntentManifest.get().asFile, "--host", "viewer", "--viewer", viewerKind)
}
android.sourceSets.getByName("main").manifest.srcFile(generatedIntentManifest)
tasks.named("preBuild").configure { dependsOn(generateIntentManifest) }

apply(from = repositoryRoot.resolve("scripts/runtime-capability-catalog.gradle"))

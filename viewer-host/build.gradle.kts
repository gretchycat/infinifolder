plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val viewerKind = providers.gradleProperty("viewer").orElse("all").get()
val viewers = rootProject.subprojects.filter { it.name.startsWith("viewer-") && it.name !in setOf("viewer-host","viewer-common") }.map { it.name.removePrefix("viewer-") }.sorted()
require(viewerKind == "all" || viewerKind in viewers) { "Unknown viewer: $viewerKind" }
android {
    namespace = "com.infinifolder.standalone"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "bz2" }
    defaultConfig {
        applicationId = "com.infinifolder.standalone." + viewerKind
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "VIEWER_KIND", "\"$viewerKind\"")
        manifestPlaceholders["viewerName"] = "InfiniFolder " + viewerKind.replaceFirstChar { it.uppercase() } + " Viewer"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.merges += "META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider"
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}")
}
dependencies {
    implementation(project(":viewer-common"))
    if (viewerKind == "image") implementation(project(":viewer-text")) // ANSI art opens on the DOS text surface.
    (if (viewerKind == "all") viewers else listOf(viewerKind)).forEach { implementation(project(":viewer-$it")) }
}

val generatedIntentManifest = layout.buildDirectory.file("generated/android-intents/AndroidManifest.xml")
val generateIntentManifest by tasks.registering(Exec::class) {
    description = "Register supported file types and URI handlers with Android"
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(rootProject.file("scripts/build-android-intent-manifest.py"))
    inputs.property("host", viewerKind)
    outputs.file(generatedIntentManifest)
    outputs.file(generatedIntentManifest.map { it.asFile.resolveSibling("AndroidManifest.json") })
    commandLine(providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        rootProject.file("scripts/build-android-intent-manifest.py"),
        "--template", layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile,
        "--output", generatedIntentManifest.get().asFile, "--host", "viewer", "--viewer", viewerKind)
}
android.sourceSets.getByName("main").manifest.srcFile(generatedIntentManifest)
tasks.named("preBuild").configure { dependsOn(generateIntentManifest) }

apply(from = rootProject.file("scripts/runtime-capability-catalog.gradle"))

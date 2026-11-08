plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    rootProject.subprojects.filter { it.name.startsWith("viewer-") && it.name !in setOf("viewer-host") }
        .sortedBy { it.name }.forEach { implementation(project(it.path)) }

    implementation(project(":storage-engine"))
    implementation("io.github.gretchycat.libinfinimeta:meta-sega:0.1.0")
    implementation("io.github.gretchycat.libinfiniselect:select-android:0.1.0")
    implementation("io.github.gretchycat.libinfiniscribe:scribe-android:0.1.0")
    implementation("io.github.gretchycat.libinfiniscribe:scribe-mobi-android:0.1.0")

    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

android {
    namespace = "com.infinifolder"
    compileSdk = 36
    androidResources { noCompress += "bz2" }

    defaultConfig {
        applicationId = "com.infinifolder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.infinifolder.IconRepairInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Sound and libVLC both provide the shared C++ runtime. Validate the packaged
    // runtime with both native backends; never ship two copies at the same path.
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.merges += "META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider"
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

val generatedIntentManifest = layout.buildDirectory.file("generated/android-intents/AndroidManifest.xml")
val generateIntentManifest by tasks.registering(Exec::class) {
    description = "Register supported file types and URI handlers with Android"
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(rootProject.file("scripts/build-android-intent-manifest.py"))
    inputs.file(layout.projectDirectory.file("src/main/java/com/infinifolder/BrowserLocations.kt"))
    inputs.file(layout.projectDirectory.file("src/main/java/com/infinifolder/TermuxCommandLink.kt"))
    inputs.property("host", "app")
    outputs.file(generatedIntentManifest)
    outputs.file(generatedIntentManifest.map { it.asFile.resolveSibling("AndroidManifest.json") })
    commandLine(providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        rootProject.file("scripts/build-android-intent-manifest.py"),
        "--template", layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile,
        "--output", generatedIntentManifest.get().asFile, "--host", "app", "--viewer", "all")
}
android.sourceSets.getByName("main").manifest.srcFile(generatedIntentManifest)
tasks.named("preBuild").configure { dependsOn(generateIntentManifest) }

apply(from = rootProject.file("scripts/runtime-capability-catalog.gradle"))

val exportSupportedTypes by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Export every linked library's runtime capability profiles as CSV"
    dependsOn("exportRuntimeCapabilities")
    inputs.file(layout.buildDirectory.file("generated/capabilities/catalog.json"))
    inputs.file(rootProject.file("scripts/export-supported-types.py"))
    val inventory = rootProject.file("docs/supported-types.csv")
    outputs.file(inventory)
    commandLine(
        providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        rootProject.file("scripts/export-supported-types.py"),
        "--catalog", layout.buildDirectory.file("generated/capabilities/catalog.json").get().asFile,
        "--output", inventory,
    )
}

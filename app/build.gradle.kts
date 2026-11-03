plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation(project(":storage-engine"))
    implementation(project(":viewer-common"))
    implementation(project(":viewer-image"))
    implementation(project(":viewer-text"))
    implementation(project(":viewer-markdown"))
    implementation(project(":viewer-html"))
    implementation(project(":viewer-pdf"))
    implementation(project(":viewer-csv"))
    implementation(project(":viewer-epub"))
    implementation(project(":viewer-font"))
    implementation(project(":viewer-audio"))
    implementation(project(":viewer-video"))
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
    inputs.file(rootProject.file("scripts/export-supported-types.py"))
    inputs.files(rootProject.files(
        "libraries/libinfinistore/store-android/src/main/java/com/infinifolder/mime/MimeRegistry.kt",
        "libraries/libinfinistore/store-android/src/main/java/com/infinifolder/archive/ArchiveFormats.kt",
        "viewer-common/src/main/java/com/infinifolder/preview/ImageFormats.kt",
        "viewer-common/src/main/java/com/infinifolder/viewer/MediaTypeDetector.kt",
        "libraries/libinfinisound/audio-processing/src/main/java/com/infinifolder/audio/AudioProcessing.kt",
        "libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/BuiltInAudioDecoders.kt",
        "libraries/libinfiniscribe/scribe-core/src/main/kotlin/io/github/gretchycat/infiniscribe/DocumentFormats.kt",
        "libraries/libinfiniscribe/scribe-core/src/main/kotlin/io/github/gretchycat/infiniscribe/FontDocuments.kt",
        "libraries/libinfinistore/store-android/src/main/java/com/infinifolder/storage/FileMagicDetector.kt"))
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

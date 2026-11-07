plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.infiniscribe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.infiniscribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.merges += "META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider"
    packaging.resources.excludes += setOf(
        "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*",
        "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}"
    )
}

dependencies {
    implementation(project(":viewer-common"))
    implementation(project(":viewer-text"))
    implementation(project(":viewer-markdown"))
    implementation(project(":viewer-html"))
    implementation(project(":viewer-epub"))
    implementation(project(":viewer-csv"))
    implementation(project(":viewer-pdf"))
    implementation(project(":viewer-font"))
    implementation("io.github.gretchycat.libinfiniselect:select-android:0.1.0")
    implementation("androidx.core:core:1.15.0")
}

val generatedIntentManifest = layout.buildDirectory.file("generated/android-intents/AndroidManifest.xml")
val generateIntentManifest by tasks.registering(Exec::class) {
    description = "Register file types reported by linked Scribe library capabilities"
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(layout.projectDirectory.file("../scripts/build-android-intent-manifest.py"))
    inputs.property("host", "infiniscribe")
    outputs.file(generatedIntentManifest)
    outputs.file(generatedIntentManifest.map { it.asFile.resolveSibling("AndroidManifest.json") })
    commandLine(providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        layout.projectDirectory.file("../scripts/build-android-intent-manifest.py").asFile,
        "--template", layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile,
        "--output", generatedIntentManifest.get().asFile, "--host", "infiniscribe")
}
android.sourceSets.getByName("main").manifest.srcFile(generatedIntentManifest)
tasks.named("preBuild").configure { dependsOn(generateIntentManifest) }

apply(from = layout.projectDirectory.file("../scripts/runtime-capability-catalog.gradle"))

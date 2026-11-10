plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Resolve shared artwork relative to this library instead of the consuming
// Gradle root so standalone viewer applications can include viewer-common.
val themeSource = layout.projectDirectory.dir("../themes/default theme")
val themeBuilder = layout.projectDirectory.file("../themes/icon/scripts/build_icon_theme.py")
val themeBuildRoot = layout.buildDirectory.dir("generated/default-icon-theme")
val prepareDefaultIconTheme by tasks.registering(Exec::class) {
    description = "Package the default icon theme directly from themes/"
    inputs.dir(themeSource.dir("svg"))
    inputs.file(themeSource.file("metadata.csv"))
    inputs.file(themeBuilder)
    inputs.file(layout.projectDirectory.file("../themes/icon/scripts/icon_style.py"))
    outputs.dir(themeBuildRoot)
    commandLine(providers.gradleProperty("iconThemePython").orElse("python3").get(),
        themeBuilder.asFile.absolutePath, "--no-layout",
        "--assets-dir", themeBuildRoot.get().dir("assets").asFile.absolutePath,
        "--kotlin-dir", themeBuildRoot.get().dir("kotlin").asFile.absolutePath)
}

android {
    namespace = "com.infinifolder.viewer.common"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("main") {
        java.srcDir(themeBuildRoot.map { it.dir("kotlin") })
    }
}
tasks.named("preBuild").configure { dependsOn(prepareDefaultIconTheme) }
dependencies {
    api("io.github.gretchycat.libinfinicanvas:canvas-android:0.1.0")
    api("io.github.gretchycat.libinfiniscribe:scribe-core:0.1.0")
    api("androidx.core:core:1.15.0")
    api(project(":storage-engine"))
    testRuntimeOnly("io.github.gretchycat.libinfinivideo:video-android:0.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

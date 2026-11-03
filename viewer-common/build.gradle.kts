plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val themeSource = rootProject.layout.projectDirectory.dir("themes/default theme")
val themeBuilder = rootProject.layout.projectDirectory.file("themes/icon/scripts/build_icon_theme.py")
val themeBuildRoot = layout.buildDirectory.dir("generated/default-icon-theme")
val prepareDefaultIconTheme by tasks.registering(Exec::class) {
    description = "Package the default icon theme directly from themes/"
    inputs.dir(themeSource.dir("svg"))
    inputs.file(themeSource.file("metadata.csv"))
    inputs.file(themeBuilder)
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
        assets.srcDir(themeBuildRoot.map { it.dir("assets") })
        java.srcDir(themeBuildRoot.map { it.dir("kotlin") })
    }
}
tasks.named("preBuild").configure { dependsOn(prepareDefaultIconTheme) }
dependencies {
    api("io.github.gretchycat.libinfinicanvas:canvas-android:0.1.0")
    api("io.github.gretchycat.libinfiniscribe:scribe-core:0.1.0")
    api("androidx.core:core:1.15.0")
    api(project(":storage-engine"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

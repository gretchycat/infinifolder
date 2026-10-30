plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.infinifolder.viewer.common"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api("io.github.gretchycat.libinfinicanvas:canvas-android:0.1.0")
    api("io.github.gretchycat.libinfiniscribe:scribe-core:0.1.0")
    api("androidx.core:core:1.15.0")
    api(project(":storage-engine"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

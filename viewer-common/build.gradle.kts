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
    api("androidx.core:core:1.15.0")
    api(project(":storage-engine"))
    api("com.caverock:androidsvg-aar:1.4")
    api("io.github.awxkee:jxl-coder:2.6.1")
    api("io.github.michaldvorak-gemalto:jp2-android:1.0.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

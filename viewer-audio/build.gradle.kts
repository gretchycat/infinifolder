plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.infinifolder.viewer.audio"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.customview:customview:1.1.0")
    api("androidx.core:core:1.15.0")
    api("io.github.gretchycat.libinfinisound:audio-processing:0.2.0")
    api(project(":viewer-common"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    tasks.withType<Test>().configureEach {
        val soundBuild = gradle.includedBuild("libinfinisound")
        dependsOn(soundBuild.task(":audio-processing:buildOpenMptNative"))
        val nativeDir = soundBuild.projectDir.resolve("audio-processing/build/openmptNative")
        inputs.dir(nativeDir)
        jvmArgs("-Djava.library.path=${nativeDir.resolve("arm64-v8a")}")
    }
}

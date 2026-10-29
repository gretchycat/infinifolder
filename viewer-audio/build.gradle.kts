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
    if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("openmptNative"))
    }
}

if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    val buildOpenMptNative = tasks.register<Exec>("buildOpenMptNative") {
        inputs.dir("src/main/cpp")
        outputs.dir(layout.buildDirectory.dir("openmptNative"))
        commandLine("sh", file("src/main/cpp/build-openmpt.sh"),
            layout.buildDirectory.dir("openmptNative").get().asFile, System.getProperty("java.home"))
    }
    tasks.named("preBuild").configure { dependsOn(buildOpenMptNative) }
    tasks.withType<Test>().configureEach {
        dependsOn(buildOpenMptNative)
        inputs.dir(layout.buildDirectory.dir("openmptNative"))
        jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("openmptNative/arm64-v8a").get().asFile}")
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.customview:customview:1.1.0")
    api("androidx.core:core:1.15.0")
    api("javazoom:jlayer:1.0.1")
    api(project(":viewer-common"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

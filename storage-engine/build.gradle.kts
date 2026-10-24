plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.infinifolder.storage.engine"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api("androidx.core:core:1.15.0")
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    api("com.github.mwiede:jsch:2.28.8")
    api("commons-net:commons-net:3.11.1")
    api("eu.agno3.jcifs:jcifs-ng:2.1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

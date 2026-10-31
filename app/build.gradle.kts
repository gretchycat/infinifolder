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
    implementation(project(":viewer-audio"))
    implementation(project(":viewer-video"))

    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

android {
    namespace = "com.infinifolder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.infinifolder"
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

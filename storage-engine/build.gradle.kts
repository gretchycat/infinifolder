plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.infinifolder.storage.engine"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api("io.github.gretchycat.libinfinistore:store-android:0.1.0")
    api("io.github.gretchycat.libinfinivault:vault-android:0.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    testImplementation("com.github.atomashpolskiy:bt-core:1.10") { exclude(group = "com.google.inject", module = "guice") }
    testImplementation("com.github.atomashpolskiy:bt-dht:1.10") { exclude(group = "com.google.inject", module = "guice") }
    testImplementation("com.google.inject:guice:4.2.3:no_aop")
}
// Existing cross-library regression tests retain access during compatibility migration.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name.contains("UnitTest") }.configureEach {
    compilerOptions.freeCompilerArgs.add(providers.provider {
        "-Xfriend-paths=" + libraries.files.filter { file ->
            file.path.contains("store-android") || file.path.contains("vault-android")
        }.joinToString(",") { it.absolutePath }
    })
}
if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    tasks.withType<Test>().configureEach {
        dependsOn(gradle.includedBuild("libinfinivault").task(":vault-android:buildExtNative"))
        val nativeDir = rootProject.file("libraries/libinfinivault/vault-android/build/extNative/arm64-v8a")
        inputs.dir(nativeDir)
        jvmArgs("-Djava.library.path=$nativeDir")
    }
}

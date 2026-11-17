# Infinifolder Android build and test guide

This page covers only the Infinifolder checkout. Each companion application owns
its build instructions in its own repository: [Infinimedia](https://github.com/infiniforge/infinimedia/blob/main/README.md),
[Infiniscribe](https://github.com/infiniforge/infiniscribe/blob/main/README.md), and
[Infinikey](https://github.com/infiniforge/infinikey-ime/blob/main/README.md).
Infinikey has no Infiniforge storage-library dependency. The other two current
clients use `libinfiniselect` and may use Infinifolder's provider at runtime.

## Toolchain

Use JDK 17 or 21, Python 3, Android SDK platform 36/build tools 35.0.0, NDK
27.2.12479018, and CMake 3.22.1. Set `ANDROID_HOME` or put `sdk.dir` in this
checkout's ignored `local.properties`. Never commit an SDK path or signing key.

On Termux, use its JDK, clang, aapt2, and the native packages documented by the
linked libraries. `build.sh` supplies native aapt2, discovers the SDK, and defaults
to arm64 packaging. Direct Gradle calls on Termux need
`android.aapt2FromMavenOverride`. The wrapper pins Gradle 9.7.1 and its checksum.

## Build

```sh
./build.sh --max-workers=1 --console=plain
```

This fetches the pinned library closure, assembles the debug APK, and runs the app
and hex-viewer unit tests. On Termux it also runs the native `storage-engine`
integration tests. Additional arguments are forwarded to Gradle, for example
`-PtargetAbi=arm64-v8a`.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. On Termux the native
storage-engine JNI tests run on the host; their Linux host-JVM setup is deferred.
This does not prevent Android APK cross-compilation with the NDK on desktop hosts.

## Library builds

Build reusable libraries in their own repositories using their READMEs. This app
includes only its pinned source dependency closure. `python3
scripts/fetch-dependencies.py --check` verifies the pins and clean dependency
state without replacing a checkout. An app build or JVM unit test is not Android
device acceptance; cross-app grants and backend lifecycle checks are listed in
[storage validation](storage-validation.md).

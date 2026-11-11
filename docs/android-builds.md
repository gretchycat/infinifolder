# Android builds and test ownership

Updated 2026-11-11. These commands replace the retired combined `viewer-host`
variants. Each application is cloned separately; no sibling application is needed.
The [audit](architecture-audit.md) records actual results and exact source commits.

## Toolchain

Use JDK 17 or 21, Python 3 and Android SDK platform 36/build tools 35.0.0.
Native modules use NDK 27.2.12479018 and CMake 3.22.1. Key instead uses Android
SDK platform 34/build tools 34.0.0; its build also generates keyboard assets.
Set `ANDROID_HOME` or put your SDK path in that checkout's ignored
`local.properties`. Never commit an SDK path or signing key.

On Termux use its JDK, clang, aapt2 and documented native packages. The build
script supplies native aapt2; direct Gradle calls need `android.aapt2FromMavenOverride`. Sound checks its
Termux native package manifest. Current Termux native packaging targets arm64.
Gradle wrappers pin version 9.7.1 and the distribution checksum.

## Applications

Each app repository provides an executable `build.sh`. It changes to its own
checkout, fetches declared libraries where required, and invokes its pinned wrapper
to build the debug APK and run unit tests. Folder also runs its native integration
suite when invoked on Termux. Extra Gradle arguments are forwarded unchanged.

Run each block from its own checkout, following its README for optional features.
Use `--max-workers=1` when storage/memory is constrained. First builds need network
access; `--offline` is appropriate only after dependencies have been fetched.

### Infinifolder

```sh
git clone https://github.com/gretchycat/infinifolder.git
cd infinifolder
./build.sh --max-workers=1 --console=plain
```

Optional packaging override: `./build.sh -PtargetAbi=arm64-v8a --max-workers=1`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
On Termux, build.sh also runs `:storage-engine:testDebugUnitTest` for native
filesystem/publication regression tests. Their JNI setup currently supports
Termux; a Linux host-JVM harness is deferred. This does not prevent Android APK
cross-compilation with the NDK on desktop hosts.

### Infiniscribe

```sh
git clone https://github.com/gretchycat/infiniscribe.git
cd infiniscribe
./build.sh --max-workers=1 --console=plain
```

Optional packaging override: `./build.sh -PtargetAbi=arm64-v8a --max-workers=1`.
APK: `build/outputs/apk/debug/InfiniScribe-debug.apk`.

### Infinimedia

```sh
git clone https://github.com/gretchycat/infinimedia.git
cd infinimedia
./build.sh --max-workers=1 --console=plain
```

Optional packaging override: `./build.sh -PtargetAbi=arm64-v8a --max-workers=1`.
APK: `build/outputs/apk/debug/InfiniMedia-debug.apk`.

### Infinikey

```sh
git clone https://github.com/gretchycat/infinikey-ime.git
cd infinikey-ime
./build.sh --max-workers=1 --console=plain
```

The script runs the APK build and local unit tests.
APK: `app/build/outputs/apk/debug/infinikey-ime-v<version>-debug.apk`.
No suite library bootstrap is needed. Debug builds use normal Android debug
signing; release signing is configured by the distributor.

## Library and test owners

Clone a library's own repository, run its `scripts/fetch-dependencies.py` when
present, and use its wrapper/README. Applications include only the source lock's
closure. `python3 scripts/fetch-dependencies.py --check` verifies pins and clean
dependency state without replacing a checkout.

| Library | Standalone build/test tasks or scope |
| --- | --- |
| Stream | `:stream-core:build` |
| Select | `:select-android:assembleDebug :select-android:assembleDebugAndroidTest`; instrumentation requires a device |
| Core | `:core-android:assembleDebug :core-android:testDebugUnitTest`; generic operations/cache/recovery/local/SAF tests |
| Store | `:store-android:assembleDebug :store-android:testDebugUnitTest`; network/torrent/provider safety tests |
| Vault | `:vault-android:assembleDebug :vault-android:testDebugUnitTest`; archive/filesystem tests |
| Canvas | `:canvas-android:assembleDebug :canvas-android:testDebugUnitTest` |
| Scribe library | Follow README for `scribe-core`, Office, RTF and Android/MOBI modules |
| Sound | Follow README for portable API/core tests and Android processing build |
| Video | `:video-api:test :video-android:assembleDebug` |
| Meta | Follow README for its portable metadata module tasks |
| UI | `:ui-android:assembleDebug :ui-android:testDebugUnitTest :ui-image:assembleDebug :ui-image:testDebugUnitTest` |

Old storage-engine test commands must use their current owners: generic
FileOperations/RecoveryTransfer/RevisionAndRecovery tests are in Core; torrent and
StorageSafetyRegression tests are in Store; archive-only tests belong to Vault.
Folder's remaining storage-engine tests cover cross-library container publication,
FAT/ext recovery and mounted-image integration. Preserve historical results under
their original revision instead of summing them as new cases.

## Verification boundaries and disk use

`scripts/verify-standalone.py` creates isolated remote clones and applies a Gradle
source-root guard. It records unit results and APK class/native/notice inventory.
Build one app at a time on constrained systems. Preserve the APK, source commit,
source lock, XML/results and logs before removing disposable build checkouts;
remove only directories created for that verification.

An Android APK build or JVM unit test is not a device test. Select instrumentation,
cross-app grants/fallback, proxy lifecycle, audio/video output, rendering and Cast
need the [device acceptance matrix](storage-validation.md). The local Android
app_process descriptor smoke probe tests low-level Android primitives only.
Other-platform ports and Ubuntu host-native test work are deferred at the owner's
request. Hosted Actions compile Android APKs on Ubuntu; they do not install a
local Ubuntu system or establish an Ubuntu application port.

## Fresh-checkout build scripts

Every application repository includes `build.sh` and `scripts/android-build-env.sh`. Run `./build.sh --max-workers=1 --console=plain` from the app checkout after installing the documented SDK and native packages. The script honors explicit SDK configuration, then discovers `~/android-sdk`, `~/Android/Sdk` or `~/Library/Android/sdk`. It reports a missing SDK before Gradle starts. On Termux it supplies the native aapt2 path and defaults to arm64 packaging. Direct Gradle invocation still requires explicit SDK/native-tool configuration. The scripts neither install SDKs nor write local.properties.

The initial separation verification supplied local.properties and therefore missed the unset-SDK startup failure reported from new checkouts. The follow-up verification records that case separately; previous compilation results do not establish out-of-box discovery. SDK-free libinfiniscribe JVM builds use `-PportableOnly`; Android modules are otherwise always declared.

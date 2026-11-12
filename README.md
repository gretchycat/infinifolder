# InfiniFolder

Runtime handler discovery: [interfaces, dependency audit, processing chains and verification](docs/runtime-capabilities.md). Libraries and linked viewer modules publish their own capability providers; linked library dependencies remain explicit.

Architecture: [current ownership and interfaces](docs/architecture.md), with [ownership and tested migration checkpoints](docs/infinistream-migration.md). Existing working features and data-safety guarantees remain requirements at every stage.

An Android file browser with interchangeable storage providers, browsable archives/disk images, and independent companion viewer/editor applications. Android SAF identities remain opaque; source permissions and format limits are checked through shared provider contracts.

## Current functionality

- Local shared/removable storage, user-granted SAF sources, SFTP, FTP/FTPS, SMB and WebDAV. Virtual root supports editable container links, with credentials stored separately.
- Grid/list browsing, navigation tree, breadcrumbs, persistent pinned targets, hold-to-drag files/selected sets and hover navigation. Same-device drops move; different/unknown devices copy. Drops onto pinned containers default to copy; empty pin-board space creates one link per drop. Two-finger rectangle selection leaves one finger available for scrolling.
- Shared nested File/Edit/View/Select/Settings menus; wildcard/regex selection; name/MIME/size/date sorting in either direction; supported new-document/archive templates.
- MIME-driven icons, application-package opening and explicit Browse for packaged containers such as APK/LOVE. Native links and .iflink shortcuts share resolution protections.
- InfiniFolder bundles image and hex/raw-media inspectors. Text, Markdown, HTML, PDF, CSV, EPUB/document and font viewing is handled by the separate InfiniScribe application; audio/video playback is handled by InfiniMedia. InfiniFolder forwards supported resources to the matching companion app when installed, or uses the user's external-app choice.
- Image animation includes IFF color cycling, indexed FLI/FLC, PNG-based MNG/APNG and platform GIF/WebP playback, with shared thumbnail decoding. Format subsets and device checks are documented in [image animation](docs/image-animation.md).
- Unidentified binary data opens in the hex explorer; valid raw-media sidecars restore PIC/WAVE configuration. Known formats retain their viewer/container routes. See [hex and raw-media behavior](docs/hex-editor.md).
- Capability-aware transfers, cancellation, per-file/overall progress, remembered optional copy verification, foreground execution and background completion notifications. Successful progress dialogs dismiss automatically; warnings/errors remain reviewable.
- ZIP/TAR and supported compressed-container writes use staged rebuild/publication. FAT image batches avoid committing each small file separately. Ext2/3/4 images have a guarded native writer with internal moves and ext3/4 journal support; see [scope and validation](docs/ext-image-writes.md). Other formats have explicit read-only/subset limits.
- FAT deleted-file copy-out and partial recovery with uncertainty reports; current-folder disk usage graph with animated folder navigation and live scan totals.
- Shared 200 MiB disk cache, LRU eviction before capacity prompts, separate individual-file limit and six-hour idle expiry. Unfinished editor work is protected outside disposable cache.
- Light/dark/system themes, configurable icons, previews and hidden-file display. Toolbar activity light: green reads, dim green load waits, red file work, amber verification. Tap for operation details.

The four applications have separate repositories: [Infinifolder](https://github.com/gretchycat/infinifolder),
[Infiniscribe](https://github.com/gretchycat/infiniscribe),
[Infinimedia](https://github.com/gretchycat/infinimedia), and
[Infinikey](https://github.com/gretchycat/infinikey-ime).
This repository fetches library dependencies only. See the
[architecture audit and verification matrix](docs/architecture-audit.md).

## Standalone build

Application ID `com.infinifolder`: minSdk 26, compileSdk 36, targetSdk 35.
Install JDK 17 or 21, Python 3, Android SDK platform 36/build tools 35.0.0,
NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` or `sdk.dir` in this
checkout's untracked `local.properties`.

```sh
git clone https://github.com/gretchycat/infinifolder.git
cd infinifolder
./build.sh --max-workers=1 --console=plain
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Add
`-PtargetAbi=arm64-v8a` to restrict application packaging. `./build.sh` fetches
this app's dependencies, builds the debug APK and runs app/hex unit tests; on Termux it also runs the native storage integration suite. Additional arguments go to Gradle.
The wrapper pins Gradle 9.7.1 and its distribution checksum. Source dependencies
are pinned by Git submodule commits and `dependencies.lock.json`, including their
transitive closure. No other application is cloned or required. Library checkout
paths are inside this checkout; the bootstrap rejects dependency symlinks and
conflicting pins and refuses to overwrite changes when switching revisions.

On Termux install its JDK, Python, clang, aapt2 and native dependencies documented
by the individual libraries. The build script configures the installed native aapt2; direct Gradle calls need
`android.aapt2FromMavenOverride` set to that executable. Native builds use the environment's toolchain
prefix rather than a fixed device path. The current Termux JNI packaging supports
arm64; standard desktop builds use the NDK.

For native filesystem regression tests on Termux, also run
`./gradlew :storage-engine:testDebugUnitTest`. Their Linux host-JVM JNI setup is
deferred; Android APK cross-compilation remains separate. See the
[Android build and test guide](docs/android-builds.md) for all four applications.

## Storage service

Infinifolder exposes advanced storage through the grant-checked Android
DocumentsProvider at `com.infinifolder.documents`. Third-party applications can
use standard SAF document/tree requests or the lightweight
[libinfiniselect](https://github.com/gretchycat/libinfiniselect) library. Native
random access is retained through proxy descriptors where supported; sequential
sources remain streams. Receiving apps do not include the storage engines.
The floating picker and temporary relay references remain supported.


## Diagnostics

In debug builds, choose a dump folder in Debug dump settings under Settings, or use a folder's **Use as debug dump folder** action. The toolbar insect button offloads a screenshot, log and JSON to that folder and refreshes the listing. ADB is unnecessary. Logs/screenshots can contain filenames and visible content.

## Limits and documentation

Android storage permissions still apply. Magnet links have read-only torrent browsing and selected-file copying. Downloads and continuation have owner confirmation; broader provider/interruption checks and seekable on-demand piece reads remain open. NFS/cloud providers are not implemented. Operation queues are process-local. Nested write chains, complete metadata fidelity and several filesystem subsets remain limited. Compressed writes rebuild; they are not in-place compressed random edits. Live VM reads are not snapshots. Core cache/external-editor behavior is owner-confirmed; expiry, provider and interruption variants still need focused checks.

Start with the [documentation index](docs/README.md), [architecture](docs/architecture.md), [format coverage](docs/file-formats.md), and [task list](docs/feature-todo.md). Historical audit/test evidence is retained with its revision.

## Reusable library sources

The source lock includes Stream, Select, Core, Store, Vault, Sound, Canvas, Scribe,
Video, Meta and UI. Responsibilities, transitive edges and repository links are
in [the architecture report](docs/architecture-audit.md). Run the dependency
bootstrap before building; individual libraries also have standalone build and
test workflows. The icon theme is pinned within UI. No application submodule is
included in this repository.

## Build environment discovery

`./build.sh` honors `ANDROID_HOME`, `ANDROID_SDK_ROOT` or this checkout's `local.properties`. With none configured, it discovers an installed SDK in `~/android-sdk`, `~/Android/Sdk` or `~/Library/Android/sdk`, and reports a clear error if absent. It does not install an SDK or write local configuration. On Termux it passes the installed native `aapt2` explicitly and defaults arm64 packaging to `arm64-v8a`; explicit Gradle arguments can override that default. Direct `./gradlew` invocation still requires SDK/tool configuration.

Run `python3 scripts/test-android-build-env.py` for the build entry point's isolated regression checks.

## Android verification checkpoint

On 2026-11-11, implementation commit `18c2481` produced the debug APK and passed 143 tests (including 91 native storage cases) in the user's independent Termux checkout. The documented build.sh ran with SDK environment variables unset and no local.properties, plus a guard rejecting project/source roots outside that checkout. APK inspection found no document-editor/player application classes, Winamp archives or SF3 bank. The 11 build-environment regression checks also passed. Full matrix, source pins, packaged libraries and runtime limits are in the [fresh-checkout follow-up](docs/validation/fresh-checkout-build-repair.json).

The shared rendering follow-up at d4d2293 also passed all 143 app/native tests and 11 build-environment checks in the independent checkout. See the [current architectural breakdown](docs/theme-rendering.md), [full report](docs/architecture-audit.md) and [executed theme/build evidence](docs/validation/theme-rendering.json). Media owns the sole packaged Infinimedia Default theme; Folder has no player theme assets or Media application dependency.

SVG/SVGZ render as static artwork; experimental SVG color cycling has been removed. IFF cycling remains supported. Saved audio defaults now migrate to the separate Media app, and type-wide choices override older extension defaults. See [theme rendering](docs/theme-rendering.md) and the [architecture audit](docs/architecture-audit.md) for current boundaries and verification.

Current follow-up: independent Folder verification passed 146 app/native tests and 11 environment checks. The [static SVG/audio-default report](docs/static-svg-audio-defaults.md) records the corrections, all affected build results and Android runtime limits.

Current thumbnail/navigation follow-up: ORA, KRA, large SVGZ and PostScript/EPS identities retain content thumbnails; the image viewer follows displayed icon order. Folder passed 150 tests, and the Android probe rendered the four original test formats. [Corrections and verification](docs/thumbnail-order.md).

# Independent viewer applications

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Each viewer is an Android library that depends on `:viewer-common` and `:storage-engine`, without depending on the browser or its Activity. Its manifest registers its viewer Activity/service. A separate application can include the library and launch it through `ViewerLaunch` with a resource entry.

The supplied `:viewer-host` provides a launcher document picker, Android VIEW/EDIT entry point for content URIs, recovery, and debug dump controls.

## Build

```sh
gradle :viewer-host:assembleDebug -Pviewer=pdf --offline
```

Replace `pdf` with `image`, `text`, `markdown`, `html`, `csv`, `epub`, `audio`, `video`, or `all` (default). The selected application's ID is `com.infinifolder.standalone.<kind>` and its APK is under `viewer-host/build/outputs/apk/debug/`. On ARM Termux use the configured native AAPT2 override if required, as for the main app.

Text, Markdown, HTML, and CSV include editing subject to complete load and source revision checks. Image edits export PNG copies. PDF/EPUB/audio/video currently expose viewing/playback. ACTION_EDIT is rejected for unsupported formats. A missing viewer library produces an explanatory message.

Audio parsing/processing is provided by the external `libinfinisound` build (`audio-api`, portable `audio-core` and Android `audio-processing`); the audio viewer retains playback/output, queue and Cast integration. See [audio library boundaries](audio-processing-library.md).

Shared UI/resources, image codecs, network backends, and themes currently remain common dependencies. Further size reduction can split those dependencies without bringing back a browser dependency. Document access and retained URI permissions still depend on the supplying Android provider. Standalone runtime use and editing require device acceptance checks in addition to build validation.

## Stream architecture checkpoint

The full app and standalone host compose pinned Stream/Store/Vault/Sound/Canvas/Scribe/Video source repositories. viewer-common retains Android contracts, source leases and icon/theme policy; image codecs are provided by Canvas and text/Markdown processing by Scribe. The image viewer requests a BitmapFrame with actual output metadata; text/HTML/CSV/Markdown loads keep complete original bytes for revision checks; video binds its staged source through the Video library while retaining current listeners and surfaces. Dependencies are still exported for compatibility: this checkpoint makes no APK-size or minimal-host claim.

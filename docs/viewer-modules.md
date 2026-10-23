# Independent viewer applications

Each viewer is an Android library that depends on `:viewer-common` and `:storage-engine`, without depending on the browser or its Activity. Its manifest registers its viewer Activity/service. A separate application can include the library and launch it through `ViewerLaunch` with a resource entry.

The supplied `:viewer-host` provides a launcher document picker, Android VIEW/EDIT entry point for content URIs, recovery, and debug dump controls.

## Build

```sh
gradle :viewer-host:assembleDebug -Pviewer=pdf --offline
```

Replace `pdf` with `image`, `text`, `markdown`, `html`, `csv`, `epub`, `audio`, `video`, or `all` (default). The selected application's ID is `com.infinifolder.standalone.<kind>` and its APK is under `viewer-host/build/outputs/apk/debug/`. On ARM Termux use the configured native AAPT2 override if required, as for the main app.

Text, Markdown, HTML, and CSV include editing subject to complete load and source revision checks. Image edits export PNG copies. PDF/EPUB/audio/video currently expose viewing/playback. ACTION_EDIT is rejected for unsupported formats. A missing viewer library produces an explanatory message.

Shared UI/resources, codecs, network backends, and themes currently remain common dependencies. Further size reduction can split those dependencies without bringing back a browser dependency. Document access and retained URI permissions still depend on the supplying Android provider. Standalone runtime use and editing require device acceptance checks in addition to build validation.

# InfiniScribe

InfiniScribe is a standalone Android document viewer built from the shared
`libinfiniscribe` libraries and the Scribe-backed viewer modules in this
workspace. It does not include or depend on the InfiniFolder application.

The host accepts one document per launch through a `content://` or `file://`
URI. For `content://` sources, the sending app must grant URI read access. The
viewer is selected at runtime from the installed viewer and Scribe capability
providers. Multiple-item intents are deliberately not treated as document
queues; queueing belongs to media playback.

## Build

From this directory, run:

```sh
gradle assembleDebug
```

The Android SDK must be configured in the environment or in the workspace's
`local.properties`. The adjacent library and viewer directories are included
as Gradle projects/composite builds, so no InfiniFolder application project is
needed. The debug APK is written to `build/outputs/apk/debug/`.

Supported viewer formats are discovered from the registered Scribe handlers;
formats and limitations come from those libraries rather than a second list in
this host app. The build exports that same runtime capability catalog into
Android `VIEW`, `EDIT`, and single-item `SEND` intent filters, including each
handler's MIME types and extensions. Registering another format handler under
an existing Scribe viewer presentation updates those filters on the next build.

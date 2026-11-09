# InfiniMedia

InfiniMedia is the standalone audio player built from the shared audio viewer
and library modules. It accepts individual audio files and playlist files from
Android's `VIEW`, `SEND`, and `SEND_MULTIPLE` intents. Launching InfiniMedia
without a file request opens the player; use **Add to playlist** to open the
media selector. Audio is available now, with video and image filters reserved
for later support. With InfiniFolder installed, InfiniSelect uses its floating
picker; without it, the picker falls back to Android's file picker. Multiple
selections and long-pressed folders containing audio are added to the current
queue as granted content URIs.

Playlist metadata is probed asynchronously by the audio player after playback
starts. InfiniSelect can also return a companion XML document containing the
selected items' URI, display name, MIME type, and known size.

## Build

From this directory, run:

```sh
gradle assembleDebug
```

The project includes only the storage engine, shared viewer, audio viewer, and
standalone host modules. Its linked `libinfini*` composite builds are declared
in `settings.gradle.kts`; it does not include or initialize the InfiniFolder
application. The APK is packaged as `com.infinimedia` and written to
`build/viewer-host/outputs/apk/debug/viewer-host-debug.apk`.

# libinfiniselect

`libinfiniselect` is a standalone Android library for viewer applications to
request file references. Its source repository is
[gretchycat/libinfiniselect](https://github.com/gretchycat/libinfiniselect).
It contains no InfiniFolder UI, storage, or native dependencies.

## Selection flow

1. A viewer calls `InfiniSelect.open(Activity, OpenRequest, requestCode)`.
2. If `com.infinifolder` is installed, the library opens InfiniFolder's
   floating selector at Virtual root. Selecting a directory or container link
   browses into it; selecting a file returns one content reference (or a list
   when `allowMultiple` is enabled).
3. If InfiniFolder is unavailable, the library presents the viewer's icon and
   name, available source buttons, Android's Storage Access Framework, and a
   per-viewer starting-folder control.
4. Optional source integrations implement `FileSourceBackend` and are added
   through `FileSourceRegistry` or Java `ServiceLoader`.

InfiniFolder emits grantable, read-only `content://com.infinifolder.selection/…`
URIs. A private 24-hour token maps each URI back to the selected VFS source,
and a bounded worker pool copies bytes into a pipe as the consumer reads. This
lets the viewer consume local, SAF, network, and archive entries without
receiving InfiniFolder storage credentials. The pipe provides sequential I/O;
seek-based consumers need their own staging or seek adapter.

## API and build

The public entry point is `io.github.gretchycat.infiniselect.InfiniSelect`.
`OpenRequest` carries a stable client ID, display label, MIME filters,
multiple-selection preference, and optional initial directory. Returned Android
Activity results use ordinary URI grants, `OpenableColumns.DISPLAY_NAME`,
`ContentResolver.getType`, and `ClipData` for multi-selection.

Clients may request `MultipleSelectionDelivery.JSPF` and optional
`SelectionMetadataFormat.XML`. A multi-item result then includes a JSPF content
URI whose track locations point at the selected, grantable item URIs, plus a
companion XML content URI containing each item's URI, display name, MIME type,
and known byte length. `InfiniSelect.readResult()` normalizes the result for
viewer code. A single selected item remains a regular content URI.

The audio client enables `allowRecursiveAudioDirectories`: long-pressing a
directory in InfiniFolder scans its descendants through the storage provider,
skips symbolic links, and returns supported audio files through the same JSPF
handoff. The standalone media app is built with
`gradle :viewer-host:assembleDebug -Pviewer=audio` and registers its supported
audio and playlist handlers for Android share intents.

Build the library independently with:

```sh
gradle :select-android:assembleRelease
```

The first integrated client is the independently buildable InfiniScribe host.
Device-level launch, permission, SAF, and cross-app stream checks remain needed
because there is no connected Android device in this build environment.

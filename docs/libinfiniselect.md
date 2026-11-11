# libinfiniselect storage boundary

Updated 2026-11-11. Source and authoritative API documentation:
[gretchycat/libinfiniselect](https://github.com/gretchycat/libinfiniselect).
This independently buildable Android library has no InfiniSuite implementation
dependency. Storage access, references and permissions are its scope.

## Selection and fallback

- `InfiniSelect.open(Activity, OpenRequest, requestCode)` requests single or
  multiple files. Read selection can use Folder's floating picker; absent or
  unavailable Folder falls back to Android SAF and registered storage sources.
- `OpenRequest(writable = true)` uses Android document selection with write
  flags. Folder's DocumentsProvider can be selected when installed.
- `StorageAccess.selectDirectory(...)` / `directoryIntent(...)` requests a
  standard Android tree, initially pointing at Folder's provider when available.
- `InfiniSelect.save(..., SaveRequest, ...)` requests a destination and copies
  the caller-authorized source; SAF create-document remains the fallback.
- `InfiniSelect.readResult(...)` normalizes content references and metadata.
  Multiple results use ClipData. Existing optional JSPF/XML URI-list serialization
  and recursive selection remain compatible; they do not decode media contents.

Optional `FileSourceBackend`/`FileSourceRegistry` hooks are storage-source hooks,
not general application integration. Media and document logic belongs elsewhere.

## Access and lifetime

`StorageAccess.openRead`, `openWrite`, and `openDescriptor` use ContentResolver.
The caller closes each returned stream/descriptor. `seekable` probes an owned
descriptor without copying. `capabilities` reports provider promises and whether
access has been persisted; UNKNOWN does not authorize a write. Providers can
reject a mode or report unsupported random access. No whole-file materialization
is performed to imitate seeking.

Folder exposes the standard authority `com.infinifolder.documents` with
MANAGE_DOCUMENTS protection and Android URI/tree grants. Backend credentials,
remote URIs, archives and filesystem implementation remain inside Folder.
Seekable native handles use proxy descriptors; sequential sources use reliable
pipes with observable errors and close propagation. Apps need no Store/Vault
code to consume either form, including third-party SAF clients.

Legacy `com.infinifolder.selection` references remain transient, read-granted
24-hour relays. They cannot be persisted. Saved playlists containing those
references inherit that limit. Standard SAF document/tree references support
persistence when the provider and returned flags allow it. Preserve only flags
actually granted, and request an explicit tree to browse siblings. Directory
metadata/navigation hints alone grant no sibling access.

## Standalone build and validation

```sh
git clone https://github.com/gretchycat/libinfiniselect.git
cd libinfiniselect
./gradlew :select-android:assembleDebug :select-android:assembleDebugAndroidTest
```

Use JDK 17/21 and Android SDK platform 36/build tools 35.0.0. This library fetches
no suite repositories. AndroidX instrumentation tests are compiled by that
command; run `:select-android:connectedDebugAndroidTest` only on an attached
Android device. See the [storage acceptance matrix](storage-validation.md) and
[executed audit evidence](architecture-audit.md) for the distinction between
compilation, descriptor smoke tests and cross-application device validation.

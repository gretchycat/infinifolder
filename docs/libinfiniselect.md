# libinfiniselect storage boundary

Updated 2026-11-13. Source and authoritative API documentation:
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
- `InfiniSelect.readResult(...)` returns each resource's separate content URI
  and authoritative URI, along with metadata. Multiple results use parallel
  arrays and ClipData for grantable access links.
- `InfiniSelect.resolveSource(...)` lazily resolves an authoritative location
  to an accessible content URI and returns both values. `resolveRelativeLocation`
  resolves playlist paths in the library and delegates container traversal
  through Folder IPC.
- The JSPF serializer stores authoritative locations, never temporary relay
  URIs. Metadata preserves access and identity separately. Recursive selection
  remains available without decoding media contents.

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
MANAGE_DOCUMENTS protection and Android URI/tree grants. Its separately
exported `com.infinifolder.selection` provider implements the libinfiniselect
resolution calls. Clients may retain an opaque authoritative URI such as an
SFTP or archive location, but only libinfiniselect and Folder interpret it;
credentials and backend implementations remain in Folder. Seekable native
handles use proxy descriptors; sequential sources use reliable pipes with
observable errors and close propagation. Apps need no Store/Vault code,
including third-party SAF clients.

`contentUri` is the current Android access handle; a Folder relay is a
read-granted 24-hour stream and cannot be persisted. `authoritativeUri` is the
resource identity used by playlists and later requests and does not become the
relay when the relay expires. Standard SAF document/tree references can serve
as both identity and access URI when the SAF URI itself is canonical; preserve
only flags actually granted, and request an explicit tree to browse siblings.
Directory metadata/navigation hints alone grant no sibling access.

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

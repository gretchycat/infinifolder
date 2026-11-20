# libinfiniselect storage boundary

Updated 2026-11-13. Source and authoritative API documentation:
[gretchycat/libinfiniselect](https://github.com/infiniforge/libinfiniselect).
This independently buildable Android library has no InfiniSuite implementation
dependency. Storage access, references and permissions are its scope.

Current application consumers are Infinifolder, Infinimedia and InfiniScribe.
They use the library's public Android storage APIs; Infinifolder supplies the
optional storage provider and backend resolution. Infinikey only shares the
suite branding: it does not depend on libinfiniselect or Infinifolder. The other
application directories are planned projects and are not current consumers.

## Selection and fallback

- `InfiniSelect.open(Activity, OpenRequest, requestCode)` requests single or
  multiple files. Read selection can use Folder's floating picker; absent or
  unavailable Folder falls back to Android SAF and registered storage sources.
  An optional `NewFileType` enables a long-press **New** action on Folder
  directories and returns the directory plus `SelectionResult.isNewFile` instead
  of an existing item. The no-Folder path uses SAF to select a directory.
  Writable requests offer the InfiniFolder new-file action alongside Android's
  editable-document picker.
- `OpenRequest(writable = true)` uses Android document selection with write
  flags. Folder's DocumentsProvider can be selected when installed.
- `StorageAccess.selectDirectory(...)` / `directoryIntent(...)` requests a
  standard Android tree, initially pointing at Folder's provider when available.
- `InfiniSelect.save(..., SaveRequest, ...)` requests a destination and copies
  the caller-authorized source; SAF create-document remains the fallback.
  `createFileDocument(...)` exposes a caller-owned local output file as a
  temporary read URI for this handoff; `SaveRequest.initialDirectory` can use
  the authoritative directory from a new-file result.
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
not general application integration. Media and document logic belongs in the
Media and Scribe applications or their libraries.

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
git clone https://github.com/infiniforge/libinfiniselect.git
cd libinfiniselect
./gradlew :select-android:assembleDebug :select-android:assembleDebugAndroidTest
```

Use JDK 17/21 and Android SDK platform 36/build tools 35.0.0. This library fetches
no suite repositories. AndroidX instrumentation tests are compiled by that
command; run `:select-android:connectedDebugAndroidTest` only on an attached
Android device. See the [storage acceptance matrix](storage-validation.md) and
[executed audit evidence](architecture-audit.md) for the distinction between
compilation, descriptor smoke tests and cross-application device validation.

## Embedded clients and caller actions

InfiniSelect 0.2.4 embeds Folder's complete browser, running inside Folder. The
same FolderBrowserController drives its floating and embedded views, including
breadcrumbs and navigation. The library owns transport, not a directory browser
or extracted file-card renderer. Surface hosting requires Android 11+ and updated
Folder; clients keep ordinary selection as their fallback. The embedding protocol
verifies Binder UID/session ownership and returns read-granted selected-file
relays with authoritative identities. Folder renders caller file-action menus;
clients handle the selected action. See the library's docs/embedded-browser.md.

Embedded setup failures preserve the failing step and exception message across
the UI executor and Binder response. Folder's diagnostic log records the cause
chain. An immediate setup error is distinct from the 10-second dispatch timeout;
failed sessions release their host without replacing the original error during
cleanup. Cross-app surface acceptance still requires a device test.

## Relayed listings and collections

The public selection provider retains LIST_DIRECTORY for bounded/paged JSON
metadata and SHORTCUT_COLLECTION for caller-scoped directory/link operations.
These are independent non-UI APIs; the embedded browser does not use JSON to
rebuild a viewer. Folder owns storage permissions, credentials, file listing and
artwork. The listing API remains available for the deferred playlist work.

InfiniFolder 0.1.9 accepts the optional embedded `lockRoot` flag supplied by
InfiniSelect's `open(directory, label, lockToDirectory = true)`. The configured
directory becomes the breadcrumb/Back anchor and the load-error recovery
location. Child browsing and opaque surface-state restoration retain that
anchor; unlocked Open/Browse continues through Virtual root. This is navigation
configuration, not an access-control boundary. Embedded roots have no floating
frame padding or elevation, preventing shadow surface insets from shifting the
rendered view; floating hosts retain their normal frame and placement.

The 0.1.9 standalone APK build and 180 app/storage/hex tests pass, including
locked-root snapshot anchors and error-recovery policy. The APK signature
verifies. Physical touch/offset and cross-app root-lock acceptance tests compile
in the selector's instrumentation APK; they still need device execution.

InfiniFolder 0.1.10 keeps Virtual root container links navigable even when an
embedded caller requests `selectLinkDocuments`. Saved network connections are
migrated into this container-link provider, so selecting their link now resolves
and browses the target with Folder's existing provider graph and credentials.
The option still returns ordinary game .iflink documents for Play to read its
system metadata. Direct archive browsing keeps its existing activation path.

InfiniFolder 0.1.11 resolves every activated shortcut before routing its target;
its storage location does not determine whether it can navigate. Directory and
archive targets browse in Folder. App, web, command and other URI targets use
Folder's existing URI dispatcher, including unavailable-handler feedback. The
embedded session stays available when dispatching a URI. For regular-file
targets, `selectLinkDocuments` can still return the original .iflink so Play can
read its optional system hint. Archive classification uses the actual target
metadata, preserving independent shortcut display labels. InfiniSelect 0.2.3
represents directory targets without treating them as invalid file links.

The 0.1.11 standalone APK build, signature verification and all 185
app/storage/hex tests pass. Five new target-routing tests cover network/local/SAF
folders, archives, arbitrary URI dispatch, apps and original game-link metadata.
Actual network and external-handler UI acceptance still needs device checks.

InfiniFolder 0.1.12 uses Store 0.1.1's component-aware SFTP URI construction:
spaces in the actual filename become `%20` in the resource URI, while the
connection ID, directory structure and display filename retain their meaning.
Reserved filename characters and Unicode are encoded once. Select 0.2.4 also
reads legacy provider addresses containing raw spaces without treating them as
strict java.net.URI input or rewriting their identity during JSON transport.

The final 0.1.12 APK build and signature verification pass, as do 185
app/storage/hex tests. Store's standalone AAR build and 124 JVM tests pass; the
SFTP regressions check the reported filename, `%20` spaces, `%21` exclamation
marks, Unicode spelling, literal plus/percent characters and parent navigation.
Select's standalone AAR/instrumentation compilation and 26 JVM tests pass.
Actual import from the user's SFTP server remains a device acceptance check.

Automatic container navigation verifies content with the shared detectors and
opens the actual Vault reader before changing location. Filename suffixes and
provider MIME types are only candidates. Firmware `.img` and ROM `.a26`, `.a52`,
`.a78` files that fail container verification follow ordinary file selection,
including resolved `.iflink` targets; successful archive/image reads remain
browsable. Verification runs off the UI thread and discards stale results.

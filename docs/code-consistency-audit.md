# Code consistency audit and future task list

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Historical evidence: pending statements and test totals are revision-specific. The [current status](project-status.md) and [task list](feature-todo.md) supersede older acceptance labels. Reviewed 2026-10-24.

Date: 2026-10-22. Source revision: `2c4de44`.

This records the original static audit of revision `2c4de44`. Findings and evidence line numbers describe that original source; later implementation status is tracked below and in the progress document. Source links have been updated after module extraction. Device, server, and external application behavior still requires acceptance validation.

## Implementation status — safety and architecture passes

The findings below record the original source audit. Implementation has now started; see [storage safety and architecture progress](storage-safety-progress.md) for the changes, validation, and remaining limitations. Checked tasks indicate implemented changes, not completed device/server acceptance testing.


Current implementation includes application-owned storage lifetime, resource capability/parent/link hooks, independent viewer/editor modules and a selectable standalone host, source revision checks and explicit recovery, a shared MIME/icon/package registry, common link resolution, and folder-based log/screenshot dumps. The regression suite passed **223 tests**; a PDF-only host compiled without the browser. Checked items denote implemented foundations; the acceptance checklist remains open until device/backend validation. Historical paragraphs below intentionally retain the originally observed defects. A03's cross-provider guarantees still need live SAF/FTP validation; A10/A11 (all mount mutations/cache cases), A12 (external write-back implementation complete; real-editor acceptance pending), A21 (full action-service extraction), and A22–A24 (ownership, staging, inner filesystem semantics) still have remaining work. See the progress document for current limitations.

## Intended behavior

- Every storage backend exposes the same resource and operation contracts. Actual support is reported per resource and connection; unsupported operations remain explicit.
- Archives and disc images can be browsed through those contracts regardless of their source backend. Native random access is used when available; staged access has explicit limitations and commit behavior.
- Each viewer, including its editor functionality, can be extracted into an independent application without depending on the file browser Activity.
- Symbolic links and shortcut files share a link category and consistent target actions while retaining their distinct storage semantics.
- One MIME classification system drives icons, viewer selection, external opening, and container browsing. A package's application type and its underlying container format are separate facts.
- APK, LÖVE, and other supported packaged formats open with an appropriate application and expose an independent long-press Browse action.

Priority: **P0** = potential data loss or incorrect writes; **P1** = core contract or requested behavior gap; **P2** = secondary consistency, resource management, or documentation work. Order within a priority is not an implementation dependency.

## Existing foundations

`StorageProvider`, `ResourceRef`, and `RoutingStorageProvider` already provide common stream and directory operations. `ArchiveStorageProvider` exposes virtual entries through that same interface. Local, SAF, SMB, SFTP, and FTP random-access adapters exist; remote sources use a range cache. FTP random writes are intentionally rejected. `MediaSourceAdapter` is a useful starting point for common viewer access. Custom icons already have MIME keys. `ArchiveFormats` and the long-press menu already distinguish APK/LÖVE-style packages from ordinary archives.

The tasks below address inconsistencies in those foundations rather than assuming the features are absent.

## Backend coverage observed in source

| Backend | Stream/directory interface | Random-access path | Main qualification |
| --- | --- | --- | --- |
| Direct local storage | Yes | `FileBlockDevice` | Provider bypasses much of `LocalFileStore`; link identities are canonicalized. |
| Android SAF / app document sources | Yes | `ContentUriBlockDevice` | Seekability is assumed; descriptor write channel is incorrect. App document sources use SAF rather than a separate I/O backend. |
| SFTP | Yes | `SftpBlockDevice` | Concrete-provider resolver; copy/move destination semantics differ. |
| FTP / FTPS | Yes | REST/RETR through `FtpBlockDevice` | Read ranges assume server support; no random writes. |
| SMB | Yes | `SmbBlockDevice` | Separate MIME table and concrete-provider resolver. |
| Archives / disc images | Yes, with unsupported mutations | Entry/partition slices or extraction | Write support varies by container and inner filesystem; staged nesting has no commit chain. |
| Installed applications | Read-only virtual listing / APK reads | No dedicated adapter | Launch, extraction, and uninstall are application-specific semantics. |
| NFS / WebDAV / cloud catalog entries | No registered implementation | None | Listed as source types, not equivalent working backends. |

Evidence: [provider contract](../storage-engine/src/main/java/com/infinifolder/storage/StorageProvider.kt), [routing](../storage-engine/src/main/java/com/infinifolder/storage/RoutingStorageProvider.kt), [registry](../storage-engine/src/main/java/com/infinifolder/storage/ProviderRegistry.kt), [initialization](../app/src/main/java/com/infinifolder/MainActivity.kt#L331), [seekable resolver](../storage-engine/src/main/java/com/infinifolder/archive/SeekableSourceResolver.kt), [apps provider](../storage-engine/src/main/java/com/infinifolder/apps/AppsStorageProvider.kt).

## P0 tasks: protect resource identity and data

### A01 — Preserve symbolic-link identity during operations

- [x] Keep a link's own resource identity distinct from its resolved target.

**Evidence:** [LocalStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/storage/LocalStorageProvider.kt#L106): `fileFor()` returns canonical files; `uriFor()` also canonicalizes before `entry()` constructs its reference. `entry()` can therefore describe the original link while its URI identifies the target. `rename()` and `delete()` resolve that URI. SFTP `delete()` starts with target-following `stat()` before deciding whether to recurse ([SftpStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/sftp/SftpStorageProvider.kt#L281)).

**Impact:** A local link's rename/delete can act on the target. SFTP deletion of a link to a directory can enter the target directory traversal path.

**Done when:** Rename/delete operate on the selected link itself; target operations are explicit; broken links remain addressable; deleting a directory link leaves all target contents intact on each supporting backend.

### A02 — Reject incomplete recursive transfers before source deletion

- [x] Require successful, complete listings throughout recursive copy/move and inspect deletion results.

**Evidence:** [FileOperations.kt](../storage-engine/src/main/java/com/infinifolder/ops/FileOperations.kt): `copyTreeRecursively()` and `copyAppCategoryAsLinks()` proceed when a listing is a failure/unsupported result; success results ignore `isLoading` and `notice`. `batchTransfer()` subsequently calls `provider.delete(entry.ref)` for moves without checking its result. SAF's own recursive copy has stronger completeness guards in [SafProvider.kt](../storage-engine/src/main/java/com/infinifolder/storage/SafProvider.kt#L180), but the batch path bypasses them.

**Impact:** A failed or partial source listing can become an apparently successful copy followed by source deletion. Failed deletion can be reported as a completed move.

**Done when:** Failed/loading/partial listings retain the source and report the transfer as incomplete; removal failures report a partial move; statistics do not silently present failed traversals as complete totals.

### A03 — Unify collision handling before creating destinations

- [x] Define create-new, overwrite, rename, skip, and temporary publication behavior across all backends and batch operations.

**Policy:** `createFile` and `createDirectory` are create-new operations and must fail on an existing exact name. Batch copies and links choose a collision-free suffixed name; a race after listing fails safely without retrying into an existing object. Overwrite is available only through the explicit revision-checked, recovery-backed replacement path. Rename uses backend no-replace behavior where available; SAF rename and FTP semantics are provider/server controlled, and FTP rename is disabled. Skip is not currently offered. Temporary container edits publish as a verified batch; stream replacement preserves a recoverable original.

**Evidence:** [FileOperations.kt](../storage-engine/src/main/java/com/infinifolder/ops/FileOperations.kt) prelists and reserves collision-free names, then cleans up only the reference returned by a successful create. Provider create-new methods use exclusive local/SMB operations, SFTP hardlink reservation, FTP STOU, container-specific create-new logic, and SAF `DocumentsContract.createDocument` after a collision check. Browser and direct provider `copy()`/`move()` route through `StorageTransfers` and the same batch implementation. Explicit replacement uses [ContentReplacement.kt](../storage-engine/src/main/java/com/infinifolder/storage/ContentReplacement.kt).

**Remaining limitation:** SAF create and rename atomicity, plus FTP create-new behavior, ultimately depend on the document provider/server. The app requests a new document and verifies collision state first, but cannot force an uncooperative provider to honor that contract. These backend guarantees remain subject to live-provider validation; the original broad claim that local creation could return an existing file was stale after the storage-engine migration.

### A04 — Fix SAF random writes and probe descriptor capabilities

- [x] Use a writable channel for writes/truncation and report only demonstrated descriptor capabilities.

**Evidence:** [BlockDevice.kt](../storage-engine/src/main/java/com/infinifolder/archive/BlockDevice.kt#L216): `ContentUriBlockDevice.channel` comes from `FileInputStream`; `writeAt()` and `truncate()` use that channel even though a separate `FileOutputStream` exists. Capabilities unconditionally include SEEK, and include WRITE/TRUNCATE/FLUSH when `isReadOnly` is false. Size errors fall back to zero. [SeekableSourceResolver.kt](../storage-engine/src/main/java/com/infinifolder/archive/SeekableSourceResolver.kt) wraps a content descriptor without probing it.

**Impact:** Writable SAF mounts advertise operations through a read channel. Pipe-backed or unknown-size document descriptors can appear seekable or empty.

**Done when:** Seekable read/write descriptors work; pipes use stream/staging fallback; unknown size is distinguished from zero; denied write access can still allow a read-only mount; failed construction closes descriptors.

### A05 — Prevent edits from overwriting files with truncated previews

- [x] Track whether an editable document was fully loaded and prohibit destructive overwrite of partial content.

**Evidence:** [TextViewerActivity.kt](../viewer-text/src/main/java/com/infinifolder/viewer/TextViewerActivity.kt#L404) loads at most 4 MiB, puts the prefix in the editor, and displays a truncation label without retaining a save guard. [MarkdownViewerActivity.kt](../viewer-markdown/src/main/java/com/infinifolder/viewer/MarkdownViewerActivity.kt#L258) discards the truncation flag at 4 MiB. [CsvViewerActivity.kt](../viewer-csv/src/main/java/com/infinifolder/viewer/CsvViewerActivity.kt#L217) ignores the flag at 8 MiB. Their save paths serialize the loaded content.

**Done when:** Large files either load fully for editing, use a suitable incremental editor, or remain preview-only; saving cannot silently discard an unread suffix. Include decoding/encoding and newline preservation in the editor contract.

### A06 — Make nested writable mounts commit to the original resource

- [x] Separate read-only extracted snapshots from writable resources with a complete outer-container commit chain.

**Evidence:** [ContainerWriteBatch.kt](../storage-engine/src/main/java/com/infinifolder/archive/ContainerWriteBatch.kt) refuses nested container batches before edits can be published, while [ArchiveStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveStorageProvider.kt) only grants writable snapshots for supported outer containers. The containing archive's MIME/metadata update chain is not implemented, so nested writable mounts are not advertised.

**Impact:** Nested container editing is unavailable until every enclosing layer can publish verified bytes and metadata.

**Done when:** Every writable nested mount either commits all enclosing layers with correct integrity metadata and conflict handling or explicitly reports read-only access. Successful save means the original resource was updated. This implementation meets the safe read-only branch; nested edit support remains future work.

## P1 tasks: establish equivalent hooks and dispatch

### A07 — Extend the provider contract with resource capabilities and metadata operations

- [x] Add common stat, parent/child resolution, write, seek, and operation capability APIs.

**Evidence:** [StorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/storage/StorageProvider.kt) has streams and mutations but no stat, parent lookup, seekable open, or capability query. [Resources.kt](../storage-engine/src/main/java/com/infinifolder/model/Resources.kt) lacks WRITE/SEEK/TRUNCATE capabilities; `canWrite` cannot represent rename-only, create-only, or stored-entry-only behavior. Backend-specific `getMetadata()` methods return partial pairs rather than common metadata. SAF flags are reduced to booleans and synthetic permission modes.

**Done when:** Menus, editors, operations, and mounts consume one per-resource capability result, including unknown/unsupported states and read-only versus writable random access. Protocol/filesystem limitations remain visible rather than being treated as parity bugs.

### A08 — Move random-access opening behind providers

- [x] Replace the concrete-backend switch in `SeekableSourceResolver` with provider-owned seekable access and a shared fallback policy.

**Evidence:** [SeekableSourceResolver.kt](../storage-engine/src/main/java/com/infinifolder/archive/SeekableSourceResolver.kt) accepts separate local/SFTP/FTP/SMB/archive providers, accesses client pools, and decodes each URI family. [MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L374) assembles the resolver. `RoutingStorageProvider` cannot dispatch random access.

**Done when:** Registering a backend supplies stream and optional seekable hooks without changing viewers or mount code. Cancellation, fallback, size discovery, and write denial use common results. FTP read ranges are advertised only when the connection supports restart semantics; unknown metadata does not silently become a zero-length file.

### A09 — Normalize copy/move destination semantics

- [x] Specify whether the destination is a directory or a complete new resource and implement that consistently.

**Evidence:** [SftpStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/sftp/SftpStorageProvider.kt#L315) writes/renames directly to the destination remote path and returns that reference. [SmbStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/smb/SmbStorageProvider.kt#L271), local, and SAF treat the destination as a directory and append/select a child name. SFTP direct copy rejects directories; FTP direct copy is unsupported. The router rejects cross-provider copy/move while `FileOperations` implements a separate stream-based transfer route.

**Done when:** One documented transfer service provides equivalent directory and cross-connection behavior, using backend acceleration where available. Every successful result identifies the actual created resource.

### A10 — Reconcile archive listing permissions with entry mutation support

- [ ] Compute archive entry capabilities from the correct mounted resource and operation.

**Evidence:** [ArchiveStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveStorageProvider.kt#L302) calls `isWritable(sourceUri)`, although `isWritable()` expects an `archive://` URI. Ordinary top-level sources therefore yield false listing permissions. [ArchiveReader.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveReader.kt#L54) permits some existing ZIP entries to be modified while creation/deletion use disc-image paths; archive rename/copy/move are unsupported.

**Done when:** Writable FAT entries and modifiable ZIP entries advertise their actual capabilities, with create/delete/rename distinguished. Read-only ISO/ext/etc. support is reported accurately instead of inferring writes from the source file alone.

### A11 — Correct mount and extraction cache identity/invalidation

- [ ] Key caches by original resource identity and revision, and invalidate all affected layers after mutations.

**Evidence:** [ArchiveStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveStorageProvider.kt#L175) caches trees under `URI:size:modified`, but mutation paths remove only `sourceUri.toString()`. `extractToCache()` reuses an existing file based only on expected size and a display-path-derived location. [MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L4246) stages external remote opens under connection plus basename, omitting the full remote resource path.

**Impact:** Same-size changes can reuse stale bytes; unchanged/unknown timestamps retain old trees; same-name remote files can share an external staging location.

**Done when:** Resource and connection identity are authoritative, revision changes invalidate trees/extractions, mutations invalidate the actual keys, and concurrent external opens of same-name files cannot replace one another's content.

### A12 — Give staged external edits a write-back lifecycle

- [ ] Route external opening through a common staging session with revision tracking and explicit commit/conflict behavior.

**Evidence:** [MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L4207) extracts/downloads archive and remote files and grants external apps write permission on the cache URI. [StagingRepository.kt](../storage-engine/src/main/java/com/infinifolder/staging/StagingRepository.kt) defines an identity/edit-state schema but has no callers implementing sessions or write-back.

**Done when:** Edits are associated with their original resource, changes are detected, commits honor provider/mount capabilities, conflicts are surfaced, and read-only snapshots are presented as copies. Cache-file writes alone are never presented as original-resource saves.

### A13 — Extract viewers and editors into independent modules

- [x] Create shared document/I/O contracts and separate viewer/editor modules with independent host applications possible.

**Evidence:** [settings.gradle.kts](../settings.gradle.kts) includes only `:app`; all nine viewer Activities and the audio service live inside it. [MediaSourceAdapter.kt](../viewer-common/src/main/java/com/infinifolder/viewer/MediaSourceAdapter.kt) depends on `MainActivity.activeProvider`, `activeArchiveProvider`, and `resourceRef()`. Browser teardown clears those globals and closes remote pools ([MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L9373)). Viewer registrations are unexported Activities without independent document-open intent filters ([manifest](../app/src/main/AndroidManifest.xml)).

**Done when:** Image, text, Markdown, HTML, PDF, CSV, EPUB, audio, and video modules depend on shared contracts rather than browser UI. Their editor/save features stay with the relevant modules. An independent host can open a granted document URI, restore its session, and run playback without a live browser Activity. PDF/EPUB/audio/video editor scope can be specified separately; current routing marks only five types editable.

### A14 — Replace URI/path guessing in viewer I/O and Save As

- [x] Resolve parents, list siblings, and create documents through provider/document contracts.

**Evidence:** [MediaSourceAdapter.kt](../viewer-common/src/main/java/com/infinifolder/viewer/MediaSourceAdapter.kt#L23) guesses local filesystem paths for any authority containing `.files`; `getParentDirectory()` trims URI text. Its local listings fabricate `ResourceRef(uri.toString(), uri.toString())` and discard provider errors/loading states. Text, Markdown, and image Save As append a filename to a parent URI ([text example](../viewer-text/src/main/java/com/infinifolder/viewer/TextViewerActivity.kt#L636)). HTML/CSV use a different system document-creation flow.

**Done when:** SAF IDs remain opaque, empty and failed listings are distinct, sibling navigation uses valid references, and Save As creates a document through a backend or Android document picker before writing. No viewer needs to reconstruct a FileProvider path.

### A15 — Introduce a shared link category and target resolver

- [x] Represent link kind, target identity, target type, and resolution status through one model and action policy.

**Evidence:** [Resources.kt](../storage-engine/src/main/java/com/infinifolder/model/Resources.kt) models symbolic links, while [ShortcutFile.kt](../storage-engine/src/main/java/com/infinifolder/shortcut/ShortcutFile.kt) models separate JSON shortcut records. [MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L7141) gives shortcut files their own menu branch; symbolic links otherwise enter ordinary folder/file branches. FTP listing ignores available link classification; ZIP listing omits link attributes, while TAR retains link information ([ArchiveReader.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveReader.kt#L258)).

**Done when:** Links share target/open/properties/broken-link presentation while preserving native versus file-backed semantics. Copy supports an explicit preserve-link/follow-target policy; recursive traversal detects cycles. Relative targets resolve within the proper provider or container. Unsupported link metadata is reported as unknown.

### A16 — Distinguish the application's shortcut file format from Windows LNK

- [x] Detect supported shortcut encodings explicitly and assign their MIME identity accordingly.

**Evidence:** [ShortcutFile.kt](../storage-engine/src/main/java/com/infinifolder/shortcut/ShortcutFile.kt) writes JSON using `.lnk` and `application/x-ms-shortcut`; detection accepts every `.lnk`, but reading only calls `fromJson()`. A genuine binary Windows shortcut is routed into a parser that cannot read it.

**Done when:** The custom shortcut format has an unambiguous identity, legacy custom files remain readable, and genuine Windows LNK/other link files are parsed if supported or reported as unsupported without being mislabeled as broken custom JSON.

### A17 — Centralize MIME classification and confidence

- [x] Produce one normalized classification used by providers, icons, previews, menus, properties, and open dispatch.

**Evidence:** Independent tables exist in local `MimeTypeMap` handling, SAF supplied MIME, SFTP/FTP/SMB `guessMimeType()`, [ArchiveMimeTypes](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveEntry.kt), [MediaTypeDetector](../viewer-common/src/main/java/com/infinifolder/viewer/MediaTypeDetector.kt), and browser `resolveMimeType()`. SFTP/SMB label JSON/XML/Markdown as `text/plain`; archive CSV/TSV/Markdown also use `text/plain`; compressed archive MIME mappings differ. Magic detection can change classification independently of package identity.

**Done when:** Identical content has the same effective MIME and actions on every backend. Classification retains supplied MIME, normalized aliases, filename evidence, detected content, application type, and container type with a documented precedence policy. Remote detection uses bounded provider reads rather than local-only shortcuts.

### A18 — Drive icon selection directly from the shared MIME result

- [x] Make MIME-to-icon resolution consume A17, with explicit preview and per-resource override layers.

**Evidence:** [CustomIconStore.kt](../viewer-common/src/main/java/com/infinifolder/preview/CustomIconStore.kt#L108) already keys custom icons by MIME, but synthesizes `application/x-<extension>` for unknown types. [IconThemeManager.kt](../viewer-common/src/main/java/com/infinifolder/theme/IconThemeManager.kt#L497) has its own extension-to-icon table and generic `text-plain` fallback. Browser [buildFileIconView()](../app/src/main/java/com/infinifolder/MainActivity.kt#L3182) additionally special-cases local `.apk` files by filename and storage location; `iconFor()` has another format policy.

**Done when:** The icon type and Open With MIME agree everywhere, including renamed/extensionless files and MIME aliases. Package-specific icons remain distinct from generic ZIP icons. User overrides, folder icons, thumbnails, and link badges have documented precedence. APK artwork availability can vary by backend without changing its underlying MIME classification.

### A19 — Generalize packaged-container Browse support

- [x] Register application MIME and underlying container separately, beyond the existing package extension whitelist.

**Evidence:** [ArchiveFormats.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveFormats.kt) lists APK/XAPK/APKM/AAB/JAR/WAR/EAR/AAR/LÖVE/IPA as packages, but omits EPUB, OOXML, OpenDocument, and other container-based documents. [MainActivity.open()](../app/src/main/java/com/infinifolder/MainActivity.kt#L4180) mounts an archive before viewer selection. An EPUB supplied/detected as ZIP can therefore mount instead of reaching its reader; correctly typed EPUB/Office files have no guaranteed Browse action. Package MIME mapping also groups AAB/XAPK/APKM under APK and WAR/EAR/AAR under JAR without distinguishing application handler compatibility.

**Done when:** Supported container-based documents/packages keep their application identity for opening and have a Browse action for their underlying format. The actual package MIME selects compatible handlers; no-handler behavior is explicit. Include APK, LÖVE, EPUB, DOCX/XLSX/PPTX, and ODT/ODS/ODP cases across local, SAF, remote, and nested resources.

### A20 — Normalize container decoder dispatch across package aliases

- [x] Select container readers and mutation support from detected/registered container type rather than repeated extension lists.

**Evidence:** [ArchiveReader.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveReader.kt#L87) gives the ZIP seekable/slice path only to ZIP/JAR/APK/AAR/CBZ. LÖVE/WAR/EAR/XAPK/APKM/AAB/IPA fall through different detection/stream paths despite being registered as packages. Existing-entry ZIP writes use yet another, smaller, case-sensitive extension list at lines 54–81.

**Done when:** Equivalent ZIP-based packages use the same indexed read and nested-slice capabilities, regardless of suffix or case. Mutability is separately constrained by container integrity/signing requirements and writer support, with accurate read-only reporting.

### A21 — Use one open/browse/target dispatcher

- [ ] Route taps, long-press actions, shortcuts, and external-open requests through the same resource action service.

**Evidence:** [MainActivity.kt](../app/src/main/java/com/infinifolder/MainActivity.kt#L4180) has distinct direct/remote/archive opening paths. `showEntryMenu()` performs synchronous local magic detection but otherwise depends on cached detection. `openShortcut()` extracts nested archive targets rather than retaining the direct nested mount path, and sends non-file/content custom schemes—including remote file schemes—to external URI dispatch ([shortcut handling](../app/src/main/java/com/infinifolder/MainActivity.kt#L8239)). `launchExternalViewIntent()` treats all non-file/content schemes as generic browsable URIs.

**Done when:** Opening a remote resource directly or through a shortcut reaches the same viewer/application; browsing retains original mount identity; menu and tap decisions share MIME and capabilities; remote/archive external opens consistently materialize shareable content. Long-press remains responsive during detection.

## P2 tasks: lifetime, limits, and filesystem fidelity

### A22 — Define ownership and cleanup for seekable devices and staging

- [ ] Give mount/read sessions explicit device ownership, close propagation, cancellation, and cleanup.

**Evidence:** [ArchiveStorageProvider.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveStorageProvider.kt) repeatedly resolves devices for tree reads, extraction, and capability probes without a matching session lifetime. [BlockDevice.kt](../storage-engine/src/main/java/com/infinifolder/archive/BlockDevice.kt#L420) channel wrappers deliberately do not close their device; `BlockDeviceInputStream` likewise has no device-closing override. Slices also intentionally retain their parent. [MediaSourceAdapter.kt](../viewer-common/src/main/java/com/infinifolder/viewer/MediaSourceAdapter.kt#L130) relies on `deleteOnExit()` and does not clean partial staging files for every failure.

**Done when:** Ownership is explicit for borrowed and owned devices; closing the last mount/session releases descriptors and caches without invalidating active siblings. Cancellation and failed staging remove partial files; cleanup works after process restart.

### A23 — Share random access with viewers and bound materialization

- [ ] Let viewers request seekable handles and stage only when their consumer requires it.

**Evidence:** [PdfViewerActivity.kt](../viewer-pdf/src/main/java/com/infinifolder/viewer/PdfViewerActivity.kt#L222), [EpubViewerActivity.kt](../viewer-epub/src/main/java/com/infinifolder/viewer/EpubViewerActivity.kt#L323), audio, and video call `stageToTempFile()`. This bypasses the mount range-access system for nonlocal sources and defaults to a 500 MiB staging limit. `MediaSourceAdapter.readAllBytes()` is unbounded; [ArchiveReader.openEntryStream()](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveReader.kt#L413) allocates entire 7z entry contents in memory.

**Done when:** Suitable consumers use native seekable access or an adapter; required full copies have explicit size/disk limits, progress, cancellation, and lifecycle cleanup. Large remote media and nested documents do not fail merely because unrelated mount and viewer paths use different policies.

### A24 — Preserve filesystem case and link semantics inside mounts

- [ ] Carry inner filesystem lookup policy and link metadata through the virtual resource model.

**Evidence:** [ArchiveTree](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveEntry.kt) falls back to case-insensitive lookup for every container; several reader entry lookups also ignore case. ZIP listing omits symbolic-link metadata, and the disc-to-archive mapping at [ArchiveReader.kt](../storage-engine/src/main/java/com/infinifolder/archive/ArchiveReader.kt#L147) copies basic fields only.

**Done when:** Case-sensitive archives/filesystems preserve distinct names such as `A.txt` and `a.txt`; case-insensitive filesystems use their own lookup rules. Link resolution, broken targets, and root containment remain correct inside mounted resources.

### A25 — Align architecture documentation and regression coverage with live paths

- [ ] Update architecture/format documentation after the contracts are settled and cover the actual production routes.

**Evidence:** [architecture.md](architecture.md) says network adapters, cross-provider transfer, and several permissions are absent, despite current implementations and manifest declarations. Its claims about verified local copies describe `LocalFileStore`, while `LocalStorageProvider.copy()` bypasses it. [SymbolicLinkTest.kt](../storage-engine/src/test/java/com/infinifolder/storage/SymbolicLinkTest.kt) mainly exercises Java filesystem behavior rather than provider identity/destructive operations. Existing random-access tests focus on local/memory adapters and do not establish SAF or real protocol parity.

**Done when:** Documentation describes active routes and real limitations. Future regression checks cover the acceptance scenarios below, with failures/cancellation as well as successful reads.

### A26 — Give media staging explicit cleanup ownership (P0, discovered during implementation)

- [x] Replace filename and directory heuristics with an ownership token for staged versus borrowed files.

**Evidence:** The original EPUB teardown deleted any opened file whose name began with `media_` or `staged_`, although `stageToTempFile()` could return the user's original local file. Audio/video/PDF cleanup also inferred ownership from directory strings.

**Implemented:** `MediaSourceAdapter.StagedSource` records ownership. All four consumers retain and close that token; borrowed originals are retained regardless of their name. Regression checks cover borrowed and owned cleanup.

### A27 — Prevent sampled image previews from replacing originals (P0, discovered during implementation)

- [x] Export edits to a new PNG while the image editor operates on a sampled, single-frame preview.

**Evidence:** `ImageViewerActivity.loadImage()` decodes a display bitmap with a 2560-pixel target, while its original save path overwrote the source using JPEG/WebP/PNG encoding. This could discard source resolution, animation, vector/RAW data, and metadata, or write PNG bytes under a different format's filename.

**Implemented:** Image edits use Save As with PNG MIME/extension and refuse a target equal to the source URI. Full-fidelity source-format editing is future work.

## Suggested implementation sequence

1. Fix A01–A06 before expanding editing or write support.
2. Establish provider capabilities/identity/transfer contracts (A07–A10), common staging and cache sessions (A11–A12), and ownership (A22).
3. Establish shared MIME/container and link models (A15–A20), then unify dispatch (A21).
4. Extract viewer/editor modules and replace URI guessing/materialization paths (A13–A14, A23).
5. Finish mounted filesystem fidelity, documentation, and coverage (A24–A25).

## Acceptance matrix for the future phase

These are planned checks, not results from this audit. Use representative local storage, seekable SAF, stream-only SAF, SFTP, FTP/FTPS with and without restart support, SMB, and nested archive/disc sources. Unsupported combinations should produce explicit capability results.

- [ ] The same ordinary file gets equivalent name/MIME/capabilities, icon, viewer, menu, and transfer behavior on every applicable backend.
- [ ] ZIP, TAR, 7z, ISO, FAT images, and supported VM/partition containers browse from all readable sources; read-only formats remain read-only.
- [ ] Native random reads do not trigger full staging; unavailable random access uses an explicit fallback. Writable access never silently downgrades to an editable cache copy.
- [ ] Nested STORED and compressed containers preserve parent identity; committed writes survive closing and reopening all layers with valid integrity metadata.
- [ ] APK/LÖVE and supported document packages open in the appropriate handler, offer Browse on long press, and remain browsable when no handler exists.
- [ ] MIME aliases, generic/mistaken provider MIME, extensionless files, renamed files, and uppercase suffixes yield consistent actions and icons.
- [ ] Link-to-file, link-to-directory, relative link, broken link, link cycle, JSON shortcut, and genuine Windows LNK cases retain their distinct semantics and common link presentation.
- [ ] Deleting/renaming/copying a link does not unexpectedly mutate its target; cycles cannot make recursive operations unbounded.
- [ ] Listing errors, partial/loading cursors, permission changes, failed deletion, collisions, cancellation, and connection loss never turn an incomplete move into source loss.
- [ ] Every viewer/editor opens from an independent host with the browser absent, creates Save As documents on local/SAF/remote destinations where supported, and refuses destructive saves from incomplete previews.
- [ ] Same-name remote files, same-size archive revisions, concurrent viewer sessions, process recreation, and external edits retain the correct source association and conflict behavior.
- [ ] Case-sensitive mounted filesystems preserve distinct case-only names; resources and temporary files are released after use.

## Additional filesystem-limit findings (2026-10-22)

- [x] A28: Add backend-neutral filesystem and single-file/name limit reporting; reject known violations before transfer mutation, retain unknown status for unreported network limits, and explain a possible FAT ceiling after applicable large-write failures.
- [x] A29: Prevent FAT image long-name directory entries from crossing beyond allocated clusters; delete through recorded slot addresses; keep short aliases unique and create-new exclusive; retain old clusters until an overwrite is published.
- [ ] Device and image acceptance for A28/A29, including fragmented directories, fixed FAT16 root capacity, unknown-length streams, and failure injection. These changes are implemented but do not establish journaled crash recovery. See [filesystem limits](storage-filesystem-limits.md).

## Destination metadata and link fidelity (user priority)

- [ ] A30: Extend compatibility preflight beyond file size/names to native modes, ownership/group, ACLs, symbolic/hard links, timestamps and precision, and xattrs. Track native versus synthetic metadata and filesystem versus backend/credential support. Define explicit lossy-copy policies and retain move sources when required metadata/link preservation cannot be verified. Apply equivalent rules to physical, network, archive, and mounted-image destinations. See [detailed acceptance criteria](storage-filesystem-limits.md#next-phase-metadata-and-link-compatibility-user-priority).

## Container target availability

- [x] A31: Replace the destination picker's blanket archive exclusion with asynchronous capability checks; include container nodes and writable partition folders in the tree.
- [x] A32: Add file-backed ZIP mutations with source-revision-aware publication/recovery; stream FAT entry writes from staging and support aborted staged writes. Retain explicit read-only behavior for unsupported/nested formats and guard container self-copy/moves.
- [ ] Device acceptance for A31/A32, including ZIP folder trees, FAT partitions, cancellation, and publication failures. ZIP batching/performance and complete metadata preservation remain follow-up work. See [container target details](container-targets.md).

### SFTP image-target follow-up (2026-10-22)

- [x] Identify FAT target rejection from the new device log: SFTP adapter refused WRITE for `fat12_20mb.img` and `fat16_20mb.img`.
- [x] Add staged SFTP image mutation with explicit successful-operation publication, snapshot revision checks, verified replacement and recovery/rollback.
- [x] Keep capability-only probes on range reads and explain unsupported image connections in user-facing terms.
- [ ] Confirm small copies into both SFTP-hosted images on the device, reopen, and inspect existing contents.
- [x] Batch container updates to avoid rebuilding/downloading/publishing an entire image per entry operation; new batch device acceptance remains pending.

See [container-targets.md](container-targets.md) for publication limits and log evidence.

## Device acceptance update — 2026-10-22 22:56

- [x] ZIP copies of multiple files and directory trees confirmed by user; successful read-back verification appears in the `225141-900` dump.
- [x] FAT16 free-space preflight confirmed to reject an oversized batch before destination creation.
- [x] Symbolic-link safety guard confirmed by `.backgrounds` rejection during source scan; native transfer remains unimplemented.
- [ ] A30 follow-up: define explicit choices for unsupported native links (InfiniFolder shortcut, copy target contents, skip), then implement cycle/broken-link checks, capacity accounting, verification, and move-source retention according to the selected policy. User preference pending.

### A30 shortcut fallback implementation

- [x] Use the accepted `.iflink` extension and shared shortcut MIME for native-symbolic-link copies, including links inside folder trees.
- [x] Account for serialized shortcut size, preserve unique destination names, verify written payloads, and avoid dereferencing target contents.
- [x] Keep native-link moves blocked before mutation; retain unresolved links as references to their original link entries.
- [ ] Confirm copied shortcuts open their targets on the device, including remote directories and nested tree links.
- [ ] Native-link preservation, target-content copying, and full metadata fidelity remain outstanding.

See [filesystem limits](storage-filesystem-limits.md#copy-to-and-link-to-updated-user-policy) for shortcut dependency semantics.

### Corrected link action policy

User clarified that Copy to must follow native links and copy the target contents. Automatic shortcut fallback is superseded. Link to is a separate action available on every file/directory and batch selection. Native link menus omit Move to.

- [x] Add explicit Link to with verified `.iflink` payload creation and capacity/collision handling.
- [x] Follow native links during recursive copy preflight; retain aliases, account for target sizes, and reject broken/cyclic/self-dependent targets before mutation.
- [x] Exclude Move to for native-link selections; preserve the move guard for trees containing links.
- [ ] Device acceptance of the corrected Copy to and Link to behavior.

Earlier shortcut-fallback acceptance notes describe the previous build, not the current policy.

### Device confirmation: Link to

- [x] User confirmed Link to functionality in the corrected Copy to / Link to build.
- [ ] Separate acceptance remains for dereferenced symbolic-link copying, broken/cyclic targets, native metadata preservation and container batching. Link creation confirmation does not establish those cases.

## Container batch boundary implementation

- [x] Add a provider-neutral isolated write-batch hook and route container copy/link operations through its private view.
- [x] Stage ZIP additions and rebuild once; share a single working FAT image across the operation.
- [x] Verify staged entries, recheck snapshot capacity, publish once with backing revision/recovery checks, and discard unpublished failed/cancelled sessions.
- [ ] Device acceptance of the new batch route, including cancellation, concurrency and remote publication failure.

See [container batching](container-batching.md). No new tests were added or run.

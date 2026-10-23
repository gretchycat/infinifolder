# Architecture

## Build modules and ownership

- `:app`: browser Activity, navigation, menus, Android integrations, operation foreground service.
- `:storage-engine`: resource models, provider registry/router, local/SAF/SMB/SFTP/FTP/app backends, archive/disc mounts, transfers, connection repository, MIME/link policy, diagnostics, and local-file sharing provider.
- `:viewer-common`: viewer launch/document contracts, themes/icons/previews, staging adapter, recovery UI, diagnostics UI, and screenshot export.
- `:viewer-image`, `:viewer-text`, `:viewer-markdown`, `:viewer-html`, `:viewer-pdf`, `:viewer-csv`, `:viewer-epub`, `:viewer-audio`, `:viewer-video`: independent Android libraries containing format-specific viewers and applicable editors.
- `:viewer-host`: standalone application including all viewers or one selected with `-Pviewer=<kind>`. It does not depend on the browser application.

Dependency direction is `app/host → viewer modules → viewer-common → storage-engine`. Viewer sources do not reference MainActivity. `StorageRuntime` owns the provider graph and pools using application context. Browser destruction leaves active viewer connections available. This runtime is process-local; operation queues are not process-death durable.

## Resource and capability contracts

`ResourceRef` contains provider identity and an opaque document ID. SAF URIs are never reconstructed as local paths. Local refs preserve a symbolic link's own directory entry. `StorageProvider` owns URI recognition, stat, listing, parent lookup, create/delete/rename, streams, link resolution, seekable access, and optional atomic replacement. `RoutingStorageProvider` dispatches by provider identity and normalizes MIME metadata.

`ResourceCapabilities` combines backend features, resource metadata, and operation hints. Supported, unsupported, and unknown are distinct. SAF flags remain per document; actual opens can still fail after permission changes. Seekable handles report demonstrated read/write support; a failed writable request cannot become a writable cache snapshot.

SMB, SFTP, and FTP connections use application-owned pools and existing credential stores. Persisted connection definitions live behind `ConnectionPreferences`; secrets remain separate from non-secret endpoint definitions. NFS, WebDAV, and cloud catalog entries are placeholders.

## Mounting and random access

Archives and disc images use provider-owned seekable hooks. Remote range caches and direct entry/partition slices avoid full staging where possible. Nonseekable sources may require bounded materialization. Container identity retains its original source, including nested entries. Nested writes are disabled until all enclosing container metadata and bytes can be committed coherently.

Top-level archive mutation depends on format, entry layout, and backing write capability. Registered package types identify application MIME separately from container decoding: ZIP-based packages share ZIP readers while retaining their application opening behavior. Broader mounted case-policy and streaming-link support remain unfinished.

## Transfers, edits, and recovery

All routed copy/move uses `StorageTransfers`/`FileOperations`: preflight complete trees, create collision-free destinations, verify bytes, recheck originals before deletion, and report failed removals as partial moves. No distributed transaction spans remote verification and deletion. Exclusive create support varies: local/SMB native flags, SFTP advertised hardlink extension, FTP server unique-name creation, SAF provider behavior. Unsupported safe operations fail explicitly.

Editors load through `MediaSourceAdapter` and retain original byte revisions. Incomplete text previews cannot overwrite originals. `ContentReplacement` rejects stale source fingerprints, uses atomic local replacement when available, or preserves a synced checksummed recovery original before streamed writes. Failed writes attempt verified rollback. `SaveRecovery` records interrupted sessions and exposes explicit export/restore/discard operations; it never automatically overwrites a source during startup.

The current sampled image editor exports a new PNG. External handlers receive read-only snapshots; external edit write-back is future work. Owned staged files have explicit cleanup tokens, so releasing a viewer cannot delete a borrowed original.

## MIME, icons, and links

`MimeRegistry` is the shared alias, inference, application-package, container, and MIME icon mapping. Icon themes and custom MIME keys consume its normalized results. Tapping a package opens an appropriate handler; long-press Browse mounts its registered container. Built-in viewer detection consumes the same MIME identity.

`LinkResolver` gives native links and application shortcuts common cycle/depth protection while retaining link kind and target semantics. `.iflink` is the application JSON format. Legacy JSON `.lnk` is recognized; native Windows LNK decoding is unsupported. Backends resolve native link targets rather than the UI guessing paths.

## Permissions and diagnostics

Minimum API is 26. SAF grants are user-selected and persist only returned flags. Optional shared-storage access follows Android's all-files/runtime permission flow; private data remains restricted. Local content URIs use a nonexported FileProvider and explicit URI grants. Standalone hosts receive the same sharing provider through the storage library.

Diagnostics record a bounded private session log with credential redaction. A user-selected dump folder is stored as a ResourceRef. **Offload debug information** captures the current app window on demand and exports timestamped PNG, log, and JSON through ordinary provider operations. PixelCopy falls back to view drawing; metadata records the method. No ADB connection is necessary. Logs may contain resource names and screenshots show current app content.

See [implementation progress and remaining limits](storage-safety-progress.md), [viewer builds](viewer-modules.md), and [original audit](code-consistency-audit.md).

## Container destinations

The destination picker queries provider CREATE capabilities asynchronously, including mounted container folders. Drag/drop transfer choices use provider device identities: same-device drops move, while cross-device or unknown-identity drops copy. FAT image writes use disk-backed entry staging and streamed allocation; ordinary ZIP updates rebuild a separate archive and publish through file-backed `ContentReplacement` with revision checks and recovery. Provider-owned backing-resource dependencies prevent copying a container into itself. Moves within the same mounted container stage additions and removals before one verified publication; cross-container moves are rejected. See [container target support](container-targets.md) for the format/backend matrix and remaining limits.

### Isolated destination batches

`StorageProvider.beginWriteBatch` optionally supplies a `StorageWriteBatch`: a private destination provider, explicit commit and discard-on-close. `RoutingStorageProvider` dispatches the hook; `FileOperations` retains common preflight, collision handling, link policy and verification. Writable top-level ZIP/FAT mounts implement isolated copy/link sessions. ZIP entries remain an overlay until one rebuild; images share one local snapshot. Publication uses the existing content-replacement/recovery contract. See [container batching](container-batching.md).

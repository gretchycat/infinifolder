# Container mutation batches

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Updated: 2026-10-23. Device copy and bulk-delete acceptance for the exercised FAT16 routes is recorded below.

## Behavior

A Copy to or Link to operation targeting a supported, top-level writable container now uses one private destination session for the entire selection, including directory trees.

- **ZIP:** snapshot the backing ZIP once, stage new directory/file entries as an overlay, verify each file, then rebuild the ZIP once with all additions. Existing entries are raw-copied with metadata and archive-comment checks; added members are read back and fingerprinted from the rebuilt ZIP.
- **FAT image/partition:** snapshot the backing image once, apply all directory/file operations to that local working image, and verify copied bytes there. Intermediate FAT flushes affect the workspace only.
- **TAR/compressed images:** TAR uses the same staged entry overlay and one verified rebuild; compressed FAT images use a private decompressed working image and one verified recompression. See [scope, space checks, and pending acceptance](compressed-container-writes.md).
- **Publication:** after the complete copy succeeds, sync and publish the final container once through source-revision-aware `ContentReplacement`. Local storage uses the existing atomic replacement path where supported. Sequential backing providers retain the original/verification/rollback/recovery path.

This removes repeated remote image downloads/publications and per-entry ZIP rebuilds from normal container transfers. Backing-file verification and recovery can still require additional reads. Individual edits/deletes outside a copy session retain their existing single-operation path. Transfers into a container from outside it remain copies; same-container moves are staged with the destination changes and published together. Nested-container writes remain unavailable.

## FAT image performance

Each FAT image batch uses a private local snapshot. The scratch archive provider now trusts that app-created snapshot's writable status for the lifetime of the batch, avoiding repeated full-image capability probes while listing entries and checking names. Every mutation still opens its actual writable handle, and the final backing publication still uses revision checks and recovery. This reduces per-entry overhead; it does not eliminate per-entry FAT parsing or the final full-image upload (the recent 20 MiB SFTP commit took about 4.4 seconds).

### Measured discrepancy and deferred work

The 2026-10-23 local FAT16 device dump (`004149-155`) records a batch with 402 files and 266 folders. Final 20 MiB publication took 555 ms, while per-entry stages remained much slower: median 114 ms to create files, 123 ms to create folders, about 81 ms for each file's read-back verification, and about 122 ms closing each staged file write. The log's bounded rotation dropped the beginning of the operation, so it cannot provide a reliable full wall-clock duration. Earlier complete runs also showed that total time scales mainly with the number of entries rather than image publication size. The remaining per-entry FAT read/write and verification cost is a known performance discrepancy; performance work is deferred for a future phase.

The direct raw-FAT path/cluster reader added after this dump keeps byte-for-byte verification while avoiding a full directory-tree parse for each verification read. Its device performance has not yet been measured. Preserve read-back verification and final commit syncing while evaluating further optimizations.

### Small-file investigation reopened: 2026-10-23

The user reported over 100 seconds to copy approximately 1.7 MB of many small files and requested another investigation. Inspection of the project-root `fat16_20mb.img` found a 20 MiB raw FAT16 volume with 2 KiB clusters, two FAT copies, 512 root directory slots, and 11,661,312 bytes free. The source image was inspected read-only; benchmark writes used separate copies under `build/fat-performance/`.

Three remaining costs were identified in the private batch path:

- `DiscImageReader.withWritableFat` still ran its own mount/loop-device checks for every create and write, even though the provider had already recognized the private snapshot. The loop-device check can launch `losetup` once per mutation.
- `ArchiveStorageProvider.openStream` rebuilt the image tree before reaching the direct FAT path reader, so verification still scanned unrelated directories after each write invalidated the tree cache.
- Each temporary file payload was synced before immediately being copied into the unpublished snapshot, adding a durability operation for every small file.

The private snapshot now supplies a dedicated block-device marker tied to its exact file. The reader skips repeated mount probes only for that matching handle, verification uses the requested path directly, and temporary payloads close without an individual sync. Only files validated inside the batch's private cache workspace can enter this path. Ordinary images still use mount checks and normal syncing. The original backing image is checked again before publication, the completed snapshot is synced, and revision checks, verified replacement/recovery, and per-file byte verification remain in place.

A local JVM storage benchmark using copies of the supplied image created 101 directories and copied 400 files of 4,352 bytes each (1,740,800 bytes total). It reproduced the prior provider's create/write/tree-lookup/read-back sequence and compared it with the private snapshot path:

| Measured phase | Before | After |
| --- | ---: | ---: |
| Empty file creation | 23,755 ms | 237 ms |
| Payload staging and image write | 26,803 ms | 300 ms |
| Per-file lookup and read-back verification | 19,775 ms | 361 ms |
| Total, including directory creation and final snapshot sync | 77,179 ms | 1,521 ms |

Every new file was read and compared during the copy and again after reopening; the original image's SHA-256 remained unchanged. This harness measures the storage sequence, not the Android UI, provider routing, source-device speed, or final backing publication. It supports the identified overhead reduction, not a promised handset transfer time. Logs and the temporary harness are in `build/fat-performance/`.

The three new private-snapshot regression tests and the existing disc-reader/mount tests passed (18 tests total). They cover many small files with long names, both FAT copies agreeing after writes, original-image isolation, mounted-original rejection, mismatched private handles, and read-only handles. Device acceptance of the full copy remains pending. A single `container.batch.private-image` diagnostic identifies use of the new private-image path.

### Device confirmation: 2026-10-23 19:44 UTC

The user confirmed that the FAT16 copy was much faster. The new dump, `build/infinifolder-debug-20261023-194453-573.log`, retains 412 successful file-verification stages and 412 file I/O summaries totaling 5,056,317 written bytes, plus 267 successful folder-create stages. Median file-create, folder-create, verification, and destination-close times are each 1 ms. No transfer/operation failure events appear in the retained log. The 20 MiB image was published through SAF in approximately 528 ms and the batch closed with `committed=true`.

The log begins mid-operation after bounded log rotation, so these counts describe its retained portion and do not establish a full end-to-end duration. This is device confirmation for the exercised copy route, not every image format or backing provider.

The user also observed the transfer dialog resizing as progress text changed. The dialog now has a bounded fixed width and reserved title, queue, status, statistics, and button space. Long failure details scroll within the status viewport. Handset acceptance of that visual change is pending.

## Shared architecture

### Bulk deletion

`FileOperations.batchDelete` now groups selected resources using the provider's backing-resource identity and opens one optional write batch per container. It resolves the complete deletion plan against each private snapshot before any removal, does not traverse symbolic links, and removes children before their parent directories. Each successful container batch is published once. A failed or cancelled unpublished batch is discarded; normal non-container backends retain their existing deletion behavior. Backends without the optional batching/parent hooks keep their normal delete path.

The original backing file still receives the existing mount check, revision verification, sync, and verified publication/recovery behavior. A selection spanning multiple containers has separate commits: a later failure does not undo containers already committed. The UI reports actual removal progress and a separate saving phase. Diagnostics include `delete.plan`, timed `delete.entry` records with `staged=true`, and the existing container stage/publish/commit records.

The new device dump `build/infinifolder-debug-20261023-195524-895.log` captured the old path: 1,603 archive deletes over 51.28 seconds in the retained portion, with median spacing of 31.61 ms, 1,604 non-batch write-capability probes, and no container batch records. No operation failure appears. The beginning rotated out, so the full operation may have been longer.

A local deletion benchmark on copies of the current project-root FAT16 image removed 400 files and 101 directories through `FileOperations.batchDelete`. The unbatched path took 26,672 ms; the batched path took 729 ms, with exactly one snapshot and one commit. Both runs reopened without the selected tree and reclaimed the expected clusters; the user's image SHA-256 was unchanged. The benchmark uses a FAT-backed adapter and local atomic publication; its timing includes snapshot copying and final syncing, but excludes Android UI/routing and the full backing-provider revision/recovery pipeline. It is evidence of reduced per-entry overhead, not a promised phone timing. The harness and output are under `build/fat-performance/`.

Regression checks cover one batch for overlapping selections, independent sessions for separate images, postorder deletion, partial-listing refusal, cancellation and failure discard, revision conflicts, cleanup after preparation failure, symlink handling, optional-hook fallbacks, root protection, and reporting partial completion across images. FAT tests additionally check cluster reclamation, both FAT copies agreeing, neighboring-file preservation, and original-image isolation.

The user confirmed successful deletion and provided `build/infinifolder-debug-20261023-205146-651.log`. Unlike its earlier copy section, this log retains the complete delete batch: 1,489 planned entries, exactly 1,489 successful `delete.entry` records with `staged=true`, and one container stage/publish/commit/close sequence. Snapshot staging began at 20:51:38.087 UTC and the verified commit completed at 20:51:41.386 UTC: approximately 3.30 seconds, including roughly 540 ms for publishing the 20 MiB image through SAF. Per-entry deletion time had a median of 1 ms and a maximum of 9 ms. The batch closed with `committed=true`; no failed-operation, failed-transfer, unsuccessful delete, or discarded-batch events appear in the retained log. This confirms the exercised FAT16 deletion route on the phone. Explicit reopen/neighbor-file checks, FAT12/FAT32 device coverage, and broader backing-provider acceptance remain separate checks.

`StorageProvider.beginWriteBatch` returns an optional `StorageWriteBatch` with a private `provider`, explicit `commit`, and discard-on-`close`. The router dispatches the hook without knowing container internals. Non-container backends return unsupported and retain normal per-entry transfers.

`FileOperations` performs source/name/size/free-space preflight before opening the session. It then routes destination creation, writes, verification and cleanup through the private provider. Other sources retain their usual storage backend. The snapshot's folder/name/capacity view is checked again before destination creation. Public refs and returned destination URIs continue to identify the original container, never cache files.

Every created ZIP entry is exclusive; the overlay refuses mutation/deletion of original ZIP entries through this copy session. Filename conversion, unique-name handling, symbolic-link target copying and explicit `.iflink` creation use the same transfer engine as other destinations.

## Failure and cancellation boundaries

Before publication, a failed/cancelled operation discards all changes from this session; the backing container and source files remain intact. Temporary output abort/close handling remains active. Cancellation is checked during snapshot copying, between entries, during added-payload compression, and before publication. A large raw-copy or verification step may finish before the next cancellation check.

Once publication starts, verification/rollback runs to completion even if cancellation is requested, to avoid abandoning a partially streamed backing file. Remote publication is not atomic or server-journaled; connection loss or process death can still require the existing recovery originals. A changed backing-file fingerprint rejects publication. This is not a cross-process lock. Locally accessible image mount/lock checks run again before publication.

Working space must hold the snapshot and staged payloads; ZIP additionally needs its rebuilt archive. Low-space errors discard the unpublished workspace. Interrupted cache workspaces may remain until Android/app cache cleanup. FAT directory-growth overhead and unknown-length sources can still exhaust the working image after the lower-bound capacity preflight; such failures no longer publish an empty entry or an earlier part of the batch.

## Diagnostics

Records include a batch ID and can be correlated on the operation thread:

- `container.batch.stage`: one initial snapshot.
- `container.batch.rebuild`: one ZIP rebuild, with addition count (ZIP only).
- `container.batch.publish`: one final publication attempt.
- `container.batch.committed`: verified publication completed.
- `container.batch.closed`: `committed=true` on success, otherwise workspace discarded.

The progress window distinguishes preparing the destination from publishing the container. An upload failure may trigger a rollback upload; one publication attempt does not imply one network read/write in every failure case.

## Acceptance checklist

- [ ] Multi-file and directory-tree copy into a local ZIP: existing and new entries reopen correctly; one rebuild and publish per operation.
- [ ] Equivalent SFTP ZIP and FAT image copies: one batch stage and publish, no per-entry remote image publication.
- [ ] FAT12/16/32 partition destinations retain name and capacity checks.
- [ ] Link to inside containers creates verified shortcut payloads in the batch.
- [ ] Cancel before publication or force an allocation/read failure: original container unchanged, source retained.
- [ ] Concurrent backing changes reject final publication; interrupted publication retains recovery originals when required.

Validation is Kotlin compilation, APK assembly and source whitespace checks. No tests were added or run for this increment.

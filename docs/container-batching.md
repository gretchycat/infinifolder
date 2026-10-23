# Container copy batches

Implemented: 2026-10-22. Device acceptance pending.

## Behavior

A Copy to or Link to operation targeting a supported, top-level writable container now uses one private destination session for the entire selection, including directory trees.

- **ZIP:** snapshot the backing ZIP once, stage new directory/file entries as an overlay, verify each file, then rebuild the ZIP once with all additions. Existing entries are raw-copied with metadata and archive-comment checks; added members are read back and fingerprinted from the rebuilt ZIP.
- **FAT image/partition:** snapshot the backing image once, apply all directory/file operations to that local working image, and verify copied bytes there. Intermediate FAT flushes affect the workspace only.
- **Publication:** after the complete copy succeeds, sync and publish the final container once through source-revision-aware `ContentReplacement`. Local storage uses the existing atomic replacement path where supported. Sequential backing providers retain the original/verification/rollback/recovery path.

This removes repeated remote image downloads/publications and per-entry ZIP rebuilds from normal container transfers. Backing-file verification and recovery can still require additional reads. Individual edits/deletes outside a copy session retain their existing single-operation path. Transfers into a container from outside it remain copies; same-container moves are staged with the destination changes and published together. Nested-container writes remain unavailable.

## FAT image performance

Each FAT image batch uses a private local snapshot. The scratch archive provider now trusts that app-created snapshot's writable status for the lifetime of the batch, avoiding repeated full-image capability probes while listing entries and checking names. Every mutation still opens its actual writable handle, and the final backing publication still uses revision checks and recovery. This reduces per-entry overhead; it does not eliminate per-entry FAT parsing or the final full-image upload (the recent 20 MiB SFTP commit took about 4.4 seconds).

### Measured discrepancy and deferred work

The 2026-10-23 local FAT16 device dump (`004149-155`) records a batch with 402 files and 266 folders. Final 20 MiB publication took 555 ms, while per-entry stages remained much slower: median 114 ms to create files, 123 ms to create folders, about 81 ms for each file's read-back verification, and about 122 ms closing each staged file write. The log's bounded rotation dropped the beginning of the operation, so it cannot provide a reliable full wall-clock duration. Earlier complete runs also showed that total time scales mainly with the number of entries rather than image publication size. The remaining per-entry FAT read/write and verification cost is a known performance discrepancy; performance work is deferred for a future phase.

The direct raw-FAT path/cluster reader added after this dump keeps byte-for-byte verification while avoiding a full directory-tree parse for each verification read. Its device performance has not yet been measured. Preserve read-back verification and final commit syncing while evaluating further optimizations.

## Shared architecture

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

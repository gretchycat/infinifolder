# Copying into mounted images and ZIP containers

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Updated: 2026-10-22.

## Using a container destination

Current ext-family support (2026-10-26): ext2/ext3/ext4 now have a journal-aware
writer for supported feature sets, including selected disk partitions. Changes
use private image batches and checked publication; native internal moves preserve
inodes. Unsupported features remain read-only with a reason. See
[ext writes, validation and pending handset checks](ext-image-writes.md).

1. Long-press source files/folders and select **Copy to…**.
2. Expand the storage location and the archive/image node in the destination tree. Containers directly under Internal shared storage are included as well as containers in subfolders.
3. For an image with multiple partitions, expand a writable FAT partition; its partition-table root is a virtual listing, not a filesystem directory.
4. Select the destination folder and press **Select**. Capability checks run on a worker; unavailable locations show a reason and cannot be selected. A selected, expanded writable container folder can also create child directories through the picker.
5. Reopen the container to inspect the copied files. Copy verification uses the same transfer engine as ordinary destinations.

## Writable targets in this increment

| Target | Behavior |
| --- | --- |
| Top-level FAT12/FAT16/FAT32 image filesystem | Create files/directories and copy folder trees when the backing provider supplies committed writable random access |
| FAT partition in a supported image/VM wrapper | Same, when the selected filesystem view and decoder are writable |
| Ordinary ZIP / compatible ZIP-based package | New files/directories, replacement content, and deletion through a rebuilt ZIP and revision-aware backing-file publication |
| Local ZIP backing file | Atomic file replacement where the filesystem supports the existing atomic-save contract |
| SAF/network ZIP backing file | Streamed publication with a durable original/recovery record, verification, and rollback attempt when the backend supports read/write |
| Nested mounted container | Read-only until enclosing-layer commits exist |
| ISO, ext, exFAT image views; TAR/7z/etc. update targets | Read-only: this increment does not add writers for these formats |

SFTP supplies staged writable random access: each image mutation edits a complete local working copy and publishes it through revision-aware replacement with recovery/rollback. Its native range reader still does not issue offset writes. FTP image mounts remain read-only. ZIP updating only needs sequential source read/write and can use those backends. APK targets are explicitly read-only; detected signing records, self-extracting prefixes, duplicate normalized ZIP paths, split/damaged end records, and legacy filename encodings that this writer cannot preserve cause updates to be refused with the original retained.

Moving into a container is supported only when every source is inside that same mounted container. The move stages additions and removals together and publishes them as one verified container update. Dragging ordinary files into a ZIP or filesystem image offers Copy; moving across container boundaries remains unavailable. Copying a container file, or a tree containing it, into that container's own mounted contents is rejected during preflight.

Drag and drop compares stable device identities: the same device moves, while a different or unknown device copies. Each archive/image mount has its own identity; network identities use the saved connection; local storage identities use the mounted volume or SAF root.

## Implementation and safety

The picker consumes provider CREATE capabilities rather than rejecting `archive://` identities. Image capability probing distinguishes the virtual partition-table root from a writable FAT directory and keeps ISO views read-only, including hybrid images with another embedded filesystem. Package containers are exposed in the directory tree independently from their default program-opening action in the file pane.

ZIP mutation stages an original snapshot and a separate rebuilt file in application cache. Existing entries are copied as raw compressed data, with entry metadata checks and preservation of the archive's raw comment. New/replaced content is read back and fingerprinted before publication. Publication checks the backing resource's source revision and file-size limit and then uses `ContentReplacement.replaceFromFile`: atomic local replacement where available, or the existing durable original/verified rollback path for streamed backends. A source that changed during preparation is rejected. Cross-process transactions and locking are not implied.

Copy to and Link to now stage an entire selection in a private batch and publish once. ZIP additions use one final rebuild; FAT image mutations share one working image. Before publication, errors/cancellation discard the whole session. Direct single-entry edits outside a transfer retain their existing path. Signed/unsupported input rejection does not publish the staged file. See [container batching](container-batching.md) for resource costs, cancellation boundaries and acceptance checks.

Container outputs spool to temporary files instead of accumulating entire entries in a ByteArrayOutputStream. Producer failure/cancellation can abort a staged output before close commits it. FAT image writes stream the staged file into newly allocated clusters and publish metadata before freeing an old allocation. ZIP entry reads stream through an owned ZIP reader; disc-entry verification uses a temporary extraction with explicit close/delete ownership, rather than a whole-entry memory buffer. Disc materialization still requires temporary space.

FAT size/name limits remain active during preflight. Existing ZIP entry payloads and checked metadata are retained, but full preservation of incoming source permissions, ownership, ACLs/xattrs, native links, and timestamp precision remains A30. Same-container moves currently use verified copy/delete inside the private batch; they preserve file contents and publish atomically, while copied ZIP entries receive default modes and new timestamps. Native symbolic-link moves remain explicitly unsupported. FAT mutations remain unjournaled within the private image; interruption before publication discards the snapshot, while publication still follows the existing recovery path.

## Acceptance remaining

- [ ] Copy a small file and a multi-level directory tree into a writable FAT image and reopen it.
- [ ] Copy into an existing ZIP folder and a new ZIP folder, then reopen and inspect both new and existing entries.
- [ ] Check local and SAF-backed ZIP publication and available network-backed ZIP cases.
- [ ] Confirm multipart image virtual roots, read-only filesystem views, and nested mounts cannot be selected as writable destinations.
- [ ] Confirm cancellation/read failure discards staged entry bytes and retains source files; failed publication keeps recovery originals where required.
- [ ] Confirm container-into-itself copies fail before mutation, cross-container moves are blocked, and same-container ZIP/FAT moves publish atomically.

Validation for this increment is Kotlin compilation, APK assembly, and source whitespace checks. No new tests were added or run; previous test counts predate this increment. Device validation of these destination paths is pending.

## Follow-up: FAT destinations rejected on device

The user reported that plain `fat12.img` and `fat16.img` destinations were reported read-only. Read-only inspection of the shared samples found valid FAT boot-sector geometry. The existing dumps predate this report; the device-specific rejection has not yet been reproduced or confirmed resolved.

Changes in the follow-up APK:

- Destination rows automatically expand when selected. Disclosure buttons still explicitly expand/collapse.
- The destination tree filters non-expandable ordinary-file/action rows at every level; folders and browsable containers remain visible, along with the create-directory action.
- FAT capability probing shares the actual writer's filesystem/partition selection and reports the specific refusal or access error in the picker and `archive.write-capability` debug records.
- Local random access uses the result of opening the file in `rw` mode instead of rejecting it solely from a preliminary `canWrite()` hint.
- Provider-owned local image descriptors and extracted snapshot handles close when the block device closes; borrowed handles retain their caller-owned lifetime.
- Selecting the sole partition's root strips its virtual partition prefix consistently for creation and writing.

APK assembly succeeded (`build/container-target-picker-fix-build.log`); `xdg-open` was invoked. No tests were added or run. Recheck a small copy into each plain FAT image and offload a fresh dump if rejected; confirmed on-device resolution remains pending.

## Confirmed SFTP rejection and staged image support

The fresh `20261022-222559-472` dump identifies the actual rejected resources as SFTP-hosted `fat12_20mb.img` and `fat16_20mb.img`. `storage.seekable.unsupported provider=sftp capability=WRITE` precedes each refusal. This supersedes the earlier assumption that the shared-storage samples were the images being selected. It does not demonstrate a FAT12/16 parsing or local-storage permission failure.

SFTP writable seek handles now use a common `StagedRandomWriteDevice`, while native SFTP offset writes remain disabled. The device downloads a complete original snapshot, fingerprints it, and applies bounded random edits to a private working file. Flush syncs only that file. An explicit commit after a successful filesystem operation uses `ContentReplacement.replaceFromFile` with the snapshot revision. Failed filesystem operations close/discard the working copy; publication failures use the existing original backup, read-back verification, and rollback/recovery path. Changed originals are rejected before replacement. Remote publication is streamed, not atomic; process death or network loss can still require recovery. Cross-process exclusion and server crash durability are not provided.

Capability-only FAT probes use the backing provider's advertised seek-write capability and a read-only filesystem probe; they do not stage or publish a full image. This describes the earlier single-operation SFTP path. Container copy/link transfers now use one private image for the whole selection and publish only after all entries verify; see [container batching](container-batching.md). Direct edits outside a copy session still use their single-operation path.

Unsupported connection messages now explain that the connection cannot safely save changes inside the image and suggest editing a writable local copy. Diagnostic records include `image.stage`, `image.publish`, and the existing save verification/recovery results. Device acceptance remains pending; no new tests were added or run.

## Device confirmation update — 22:51–22:56

- [x] User confirmed multiple files and directory trees copied into a ZIP. The `225141-900` dump shows successful verification inside `adisp2k.zip/TI Calculators`.
- [x] FAT16 insufficient-space rejection occurred before destination entry creation (47,394,816 bytes required; 20,267,008 free).
- [x] Native symbolic-link copy is safely rejected during source scan; the `225654-817` dump confirms `.backgrounds` was retained.
- [ ] Implement the chosen explicit unsupported-link fallback; native preservation and fallback acceptance remain open.

Cancellation, network interruption, full metadata fidelity, and backend-specific cases remain pending. See [device dump follow-up](device-dump-follow-up.md).

## Batch implementation update

Copy/link transfers now publish ZIPs and writable FAT images once per selection. Previous device confirmations predate batching; they remain evidence for the exercised entry writers, not acceptance of the new transaction boundary. See [container-batching.md](container-batching.md) for pending checks.

# InfiniStream migration checkpoints

Design authority: [implementation assignment](infinistream_architecture_codex.md). Baseline: InfiniFolder `9233495`, libinfinisound `c6137cd`, 2026-10-30. The user supplied the assignment as an untracked file; preserve it intact. No AGENTS.md was found in the workspace or its project/home ancestors. No source code has been moved at this documentation checkpoint.

## Ownership inventory

| Repository | Existing implementation to preserve | Transition seam |
| --- | --- | --- |
| libinfinistream | Generic positional BlockDevice operations currently in storage-engine/archive; new metadata/source/sink contracts | Preserve existing EOF and ownership through wrappers; no Android in foundation |
| libinfinistore | Resource models, StorageProvider, local/SAF/SFTP/FTP/SMB/WebDAV, connections, transfers, links and publication/recovery | Separate shared contracts from Android backends and host runtime assembly |
| libinfinivault | ArchiveReader/Writer, archive mounts, partitions, VM disks, FAT/ext/NTFS/HFS/UFS, compression and JNI | Consume Store contracts and Stream; no Store implementation dependency back to Vault |
| libinfinisound (reuse) | audio-api, portable audio-core, Android audio-processing | Add Stream wrappers; preserve codecs, PCM precision, timing and output lifetimes |
| libinfinicanvas | ThumbnailDecoder, image codecs and transformations in viewer-common/preview | Keep icon policy/UI in host; Android Bitmap and ContentResolver are explicit adapters |
| libinfiniscribe | MarkdownRenderer and bounded text reading/encoding | Retain WebView/editor UI and revision-checked saves in host |
| libinfinivideo | Existing Android VideoView/MediaPlayer presentation input/timing integration | Preserve this backend; this checkout has no libVLC implementation to replace |

`storage-engine` currently mixes all Store/Vault/runtime responsibilities. `StorageRuntime` and `RoutingStorageProvider` enumerate concrete providers and stay in the host composition layer until injection replaces those references. Existing archive-package names may temporarily survive as source/binary compatibility namespaces; their ownership must be documented rather than creating dependency cycles.

## Checkpoint rules

Each implementation checkpoint builds its standalone library and the full app, runs focused and existing tests, commits the affected repositories and updates the host's pinned submodules. Current APIs remain available until migrated callers and tests prove removal safe. No format or write capability is widened by extraction. Static descriptors, detected metadata, negotiated outputs and runtime events are distinct. Unknown length/capabilities remain unknown. Random access lost by a stage is never advertised as retained.

## Baseline verification

Running full app build and existing storage/common/audio/text/Markdown/image/video unit suites before source edits. Log: `build/infinistream-migration/baseline.log`. The full APK build passed; storage ran 348 tests with 30 pre-existing failures. The exact baseline failure list and XML snapshots are retained under build/infinistream-migration. Failures include outdated archive/LVM, transfer/recovery, and torrent expectations. Remaining viewer suites are running separately because Gradle stopped on the storage failures. These failures must not be misreported as a green baseline or hidden with exclusions. Previous user acceptance confirms current app/audio working. This does not establish all future runtime regressions are impossible; tests and device checkpoints are required.

## Licensing and F-Droid

The baseline has no top-level original-code license in InfiniFolder or libinfinisound. A license choice has been requested from the owner; do not silently relicense extracted code. Preserve third-party source notices and dependencies. Existing native ext/lwext4 and libopenmpt source build scripts remain; 7-Zip, JPEG XL and JPEG2000 binary dependency packaging needs F-Droid review. New repositories must record provenance, dependencies and any pending licensing rather than implying distribution clearance.

## Remaining sequence

Foundation and compatibility adapters precede mechanical relocation. Then Store/Vault, Canvas/Scribe/Video, Sound adapters, metadata/detection/planning, actual consumer routing and cleanup. Each stage records converted paths and validation here. Native iOS packaging and codec rewrites remain outside this structural migration.

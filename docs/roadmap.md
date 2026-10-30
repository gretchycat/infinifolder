# Roadmap

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Updated 2026-10-24. See [current status](project-status.md) for confirmed behavior and [detailed tasks](feature-todo.md) for acceptance work.

## Active: InfiniStream library migration

1. Align documentation and record baseline regressions and source ownership.
2. Create the justified library repositories, reusing libinfinisound.
3. Establish independently tested Stream source/sink, positional access, metadata and processor contracts.
4. Adapt existing opens and consumers before removing legacy contracts.
5. Extract Store/Vault and media/document implementations in verified commits with pinned submodules.
6. Add bounded content detection and pull planning; negotiate push/backpressure explicitly.
7. Recheck full and standalone builds and existing runtime acceptance after every migration. Preserve storage publication/recovery, audio gapless/seek and every current format.

Current progress and known pre-existing failures belong in [migration checkpoints](infinistream-migration.md). The remaining product/safety work below continues alongside this architecture work; no language port is included.

## Next: accept the new safety paths

- Exercise external-edit source checks, durable unfinished sessions and conflict choices with real editors.
- Exercise cache eviction, active leases, capacity choices, six-hour expiry and restart behavior.
- Confirm the latest remote breadcrumb repair; retain selected-set/backend gesture regressions. Image-viewer Back is user-confirmed working.
- Validate transfer/verification feedback and compressed-container low-space/interruption behavior.

## Then: finish architectural consistency

Complete mounted mutation capabilities, cache invalidation, ownership/cancellation and common action dispatch. Improve seekable viewer integration and metadata preservation. Extend durable operation recovery, pause/resume/retry and collision workflows. Keep unsupported operations explicit rather than claiming every backend has identical physical capabilities.

## Format and performance follow-up

Broaden format-specific byte/metadata/edge checks, multipart ZIP/7z, advanced LVM and partition validation. Expand NTFS/HFS/UFS subsets and FAT recovery only with safety evidence. Revisit non-ext enumeration, large view construction and remaining FAT/backend performance discrepancies after measurements.

## Requested future features

Most-used sorting; torrent device acceptance and seekable piece reads; Downloads organization rules; handset acceptance for floating browser and home-screen shortcuts; later widget. NFS/cloud providers, broader search/recents/trash and portable metadata remain future scope. WebDAV is user-confirmed working.

### Cloud storage through rclone

Investigate an optional rclone-backed provider to add cloud storage services through
the existing storage, capabilities, transfer and virtual-root contracts. Evaluate
Android packaging and lifecycle, authentication and credential storage, seekable
access, supported mutations, progress/cancellation, and error handling before
choosing an integration. SSH/SFTP, FTP, WebDAV and Samba have tested well; replacing
those existing providers is not a priority.

## Foundations already implemented

Local/SAF/network providers, independent viewer libraries/host, MIME/icon/package registry, links, container mounts and supported writes, batching, recovery, transfers/progress/notifications, menus/selection/sorting, pins/drag targets, Virtual root links, disk usage, shared cache and external-edit workflow. These are no longer roadmap proposals; acceptance and limitations remain tracked separately.

## Audio library portability

Target Android, Linux, Windows, macOS and iOS through `libinfinisound`. Its byte-source/PCM contracts and portable JVM core now isolate MP3/AAC/FLAC/WAVE parsing and existing composite rendering from Android. Android decoding is a library backend, and the player consumes decoded PCM. Follow up with desktop output/native adapters, native iOS/macOS contracts and packaging, and incremental MIDI/tracker/VGM rendering. The current JVM core does not provide iOS binaries. See [audio processing integration](audio-processing-library.md).

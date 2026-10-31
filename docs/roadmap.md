# Roadmap

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-30. See [current status](project-status.md) for confirmed behavior and [detailed tasks](feature-todo.md) for acceptance work.

## Active: InfiniStream library migration

1. Align documentation and record baseline regressions and source ownership.
2. Create the justified library repositories, reusing libinfinisound.
3. Establish independently tested Stream source/sink, positional access, metadata and processor contracts.
4. Adapt existing opens and consumers before removing legacy contracts.
5. Extract Store/Vault and media/document implementations in verified commits with pinned submodules.
6. Add bounded content detection and pull planning; negotiate push/backpressure explicitly.
7. Recheck full and standalone builds and existing runtime acceptance after every migration. Preserve storage publication/recovery, audio gapless/seek and every current format.

Audio output and analysis now share library-decoded PCM sources, with bounded startup for exact-length streams; unknown-length preparation and encoded source staging remain. Audible seek/gapless/visualizer acceptance is pending.

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

### Large text and CSV editing

Add editing beyond the current complete-file limits (4 MiB for text, 8 MiB for
CSV/TSV) using the existing random-access, chunked viewing foundation.

- Keep the edit model, parsing and serialization in Scribe, with bounded memory
  and undo/redo history. Handle edits across chunk boundaries, Unicode characters,
  quoted/multiline CSV records and changes in encoded length. Refresh affected
  row/byte positions and counts while preserving the visible position.
- Save through the host and Store/Vault publication contracts: stream the complete
  edited result to a staged destination, check the original revision and publish
  only after success. Retain conflict handling, cancellation, low-space checks and
  recovery; a loaded window must never overwrite the rest of a source.
- Keep provider capabilities explicit, including remote and container sources.
  Use staged replacement where supported; do not assume positional reads imply
  safe in-place writes. Preserve existing encoding, delimiter and literal-cell
  semantics unless a separate change is deliberately specified.
- Accept on disposable copies of fixtures 90/91: edit near the beginning, middle
  and end; insert/delete content across windows; scroll away and back; undo/redo;
  save/reopen and check untouched content. Include quoted CSV newlines, Unicode,
  source conflicts and interrupted saves.

This is planned work. Large files remain read-only under the current guards
until the chunked editing and safe publication paths are implemented and accepted.

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

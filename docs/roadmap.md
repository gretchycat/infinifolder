# Roadmap

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-31. See [current status](project-status.md) for confirmed behavior and [detailed tasks](feature-todo.md) for acceptance work.

## Active: InfiniStream library migration

1. Align documentation and record baseline regressions and source ownership.
2. Create the justified library repositories, reusing libinfinisound.
3. Establish independently tested Stream source/sink, positional access, metadata and processor contracts.
4. Adapt existing opens and consumers before removing legacy contracts.
5. Extract Store/Vault and media/document implementations in verified commits with pinned submodules.
6. Add bounded content detection and pull planning; negotiate push/backpressure explicitly.
7. Recheck full and standalone builds and existing runtime acceptance after every migration. Preserve storage publication/recovery, audio gapless/seek and every current format.

Audio output and analysis now share library-decoded PCM sources, with bounded startup and independent frame blocks for seek-capable decoders. Playback prefers portable MPEG for validated content, retaining Android fallback. Portable FLAC now positions exact frames while retaining 24/32-bit precision. Audio uses demonstrated positional provider handles directly, with explicit staging for unsupported/sequential, CUE, companion-file and extraction-backed archive inputs. Unknown-length preparation and other non-seekable decoder prefixes remain. Local MP3 seeking and gapless are user-confirmed; high-resolution FLAC, live remote/container and visualizer acceptance remain. The full image viewer now composes Canvas common resources and output frame metadata while retaining its native decode/sampling paths.

The [image inspector](image-inspector.md) also uses a Canvas common-source EXIF
processor with the existing Android backend. Host labels/formatting remain;
source ownership, cancellation and legal short reads are covered at the boundary.
Canvas also owns [bitmap edits and PNG encoding](image-processing.md) through
registered processors. Host gestures, preview controls and save policy remain.
Portable EXIF/image processing and streamed PNG publication remain future work.

Image-edit viewport follow-up: the host geometry correction for the blank
surface after edits and initial off-screen crop handles is user-confirmed
working (2026-10-31). See [image processing](image-processing.md#viewport-correction-after-checkpoint-18).

Current progress and known pre-existing failures belong in [migration checkpoints](infinistream-migration.md). The remaining product/safety work below continues alongside this architecture work; no language port is included.

PDF now uses Scribe's optional Android backend with common document/frame metadata
and explicit staged-file input. The host retains navigation, zoom and cache leases;
page work and cleanup are serialized. The owner confirms the earlier PDF report
was corrupt input, and the updated viewer still needs handset acceptance.

EPUB now composes Vault ZIP resource expansion and Scribe's portable structured
book processor over related common resources. The host retains full explicit
staging, cache policy, WebView, themes and font/navigation controls. EPUB 2/3,
fallback and invalid fixtures cover the migrated boundaries; EPUB viewing is
user-confirmed after the checkpoint 16 handoff. Broader interruption and live
remote/provider acceptance remain. EPUB 3 NAV labels and on-demand package resource
serving remain follow-up. PostScript/LaTeX and Office rendering are still planned.

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

### Paged documents and Office formats

Extend Scribe's document processing interfaces with replaceable backends for
PostScript/EPS and LaTeX typesetting, followed by DOCX, XLSX, PPTX and OpenDocument
(ODT/ODS/ODP) viewing. The host supplies sources and related resources; libraries
own parsing, layout and rendering, with actual page/table/slide metadata. Android,
Linux, Windows, macOS and iOS should share the public contracts while using
appropriate platform backends. Existing Android PDF rendering remains supported.

Choose source-buildable backends after checking licensing, Android packaging,
font handling and layout fidelity. Render pages/slides on demand with bounded
rasters, cancellation and explicit staging where the backend requires a file.
Resolve embedded images, styles, equations, tables and related files through
explicit resource inputs. Typesetting must have bounded execution and explicit
file-access rules; opening a document must not enable macros or shell commands.
Legacy binary DOC/XLS/PPT, encrypted documents, advanced spreadsheet calculations
and editing are separate capability decisions; report unsupported cases clearly.

PDF/PostScript/EPS/LaTeX fixtures are in `filesystem-test-images/documents/`, with
page counts, expected markers and a ZIP for archive reads. Add Office fixtures
before implementing their backends, covering pagination, Unicode/fonts, images,
tables, equations, worksheet values/formulas and slides. Compare actual rendered
results and document semantics on each supported backend. New formats remain
planned until their backend and acceptance checks are complete.

### ANSI music playback

Add ANSI/BBS music parsing and tone synthesis to Sound, exposing timed events and
PCM through the common interfaces for normal music-player playback. Terminal art
may be presented alongside the music using the same clock. Keep synthesis out of
the text viewer and Android output out of the library's portable parser/core.
Choose explicit dialects for CSI M/N/| and PLAY-style tempo, octave, lengths,
accidentals, rests and articulation; handle ambiguous terminal control sequences,
malformed or unterminated macros, duration bounds, cancellation and seeking.
Preserve static ANSI preview dispatch for files without music. The existing
synthetic fixture is `filesystem-test-images/audio/c_major_piano_ansi_music.ans`;
add dialect and malformed-input cases before enabling playback. ANSI music is
not currently supported by the music player.

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

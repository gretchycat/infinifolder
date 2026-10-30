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

## Checkpoint 1: foundation and live adapters

Created public repositories for Stream, Store, Vault, Canvas, Scribe and Video; Sound is reused unchanged in identity/history. Repositories are pinned submodules. Stream foundation commit `1b68893` builds independently: 13 tests passed. New APIs cover sequential/positional I/O, optional capabilities, metadata/provenance, independent cursors, bounded/replayable probing, typed processor ports/sessions, pull planning and blocking push backpressure. Automatic planning currently handles single-resource pull routes; multi-port descriptions and explicit bindings are supported, general multi-input graph search is follow-up.

Converted MediaSourceAdapter.openStream to ProviderStreams/InputStreamSource/FileSourceResource without adding stat requests or staging. Sound FileAudioInput uses the same positional source while preserving its file identity, cancellation and legacy codec hints. StreamAudioInput and AudioStreamProcessor accept common positional sources and publish actual PCM metadata, preserving borrowed-input ownership. Unknown-length/sequential-only audio inputs require explicit host staging. No output-device APIs moved.

Full app debug APK, audio core (including two new adapter cases), viewer-common and viewer-audio suites passed (`stream-adapters-final.log`). Baseline common/audio/Markdown suites also passed (57/45/8). Licensing remains pending owner selection; repository LICENSE.md files state this explicitly.

## Checkpoint 2: Store and Vault extraction

Store now owns resource/provider models, local/SAF/protocol/torrent backends, transfers, links, revision-checked publication/recovery, common cache and compatibility stream/block adapters. Vault owns archives, partition/VM disk/filesystem interpreters, mounts, private mutation batches and ext/lwext4 native sources. Original packages and JNI names remain. Host storage-engine retains only runtime/router composition and external-edit orchestration. Store never depends on Vault; generic legacy block I/O and URI/format hints remain below both until a later API cleanup. Diagnostics receives a lazy host-provider callback; the routed transfer checkpoint uses a contract-returning factory. No safety policy, format algorithm or writable capability was changed.

The full app builds after the split. Store's three new positional/ownership tests and Vault's common-source ZIP test pass. Existing integration tests retain Kotlin friend access through their actual compile artifacts; internals were not made public for tests. Regression comparison currently covers all 458 baseline cases: exactly the original 30 failures, no new failures or missing cases. The comparison script rejects new failures/skips and missing cases; it never suppresses failures in Gradle. Logs: store-vault-friends.log, store-vault-regression.log and store-vault-comparison.log. Third-party source notices and source provenance tables accompany the extracted repositories.

## Checkpoint 3: media/document library consumers

Canvas now owns the existing custom and Android image codecs/ThumbnailDecoder, including synthetic fixtures and the original codec regression tests. ImageFormats/custom-icon/directory policy and Android image UI remain host code. Actual BitmapFrame output metadata describes the returned bitmap; decoding adds no extra pixel copy. Bounded stream bytes prefer strong image signatures over extension hints; raw/headerless formats retain explicit hints, and direct Android URI handling does not reopen a one-shot source for probing.

Scribe owns the unchanged MarkdownRenderer and its eight tests, plus bounded charset decoding with completeness/encoding/output metadata. MediaSourceAdapter feeds it shared sources for text/Markdown/HTML/CSV loads and retains the original bytes for the same revision-checked save path. Truncated previews still cannot overwrite originals. Video owns the existing VideoView input adapter and timing helper plus actual negotiated surface metadata; platform controls/listeners, source leases and UI remain in the viewer. No libVLC replacement or media codec rewrite occurred.

The first media checkpoint full APK and affected suites passed. Final expanded regression and standalone builds are recorded below when complete. Existing compatibility APIs and exported dependencies remain; no installed-size, cross-platform runtime or universal graph-execution claim is made.

### Checkpoint 3 verification

All seven libraries built independently. Full app plus selected audio, text, image and video hosts built. The combined reports contain 522 tests: 492 pass, exactly 30 baseline failures remain, with no missing original cases or new failures. The original 458-case baseline remains frozen; infinistream-checkpoint.json records all 522 current cases so the gate also detects removal/regressions of newly added tests. Android app_process runtime checks loaded the actual full APK: 12 portable/platform audio decodes passed with equal frame counts and exact FLAC PCM through 32 bits, plus real TGA BitmapFrame metadata/borrowed lifetime, bounded Scribe text and Video surface-metadata checks. Actual audible playback, surface playback, seeking/gapless, remote services and UI acceptance remain separate device checks.

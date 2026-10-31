# InfiniStream migration checkpoints

Design authority: [implementation assignment](infinistream_architecture_codex.md). Baseline: InfiniFolder `9233495`, libinfinisound `c6137cd`, 2026-10-30. The user supplied the assignment as an untracked file; preserve it intact. No AGENTS.md was found in the workspace or its project/home ancestors. No source code has been moved at this documentation checkpoint.

## Published repositories and pinned dependency graph

All remote main hashes were checked against the local tested heads. Sound's existing history is preserved; the other six repositories were created for this assignment. Each repository records standalone dependencies and source provenance. The workspace pins all seven as Git submodules and Gradle composite builds; no unpublished Maven artifact or vendored library tree is required.

| Repository | Status | Pinned commit |
| --- | --- | --- |
| [libinfinistream](https://github.com/gretchycat/libinfinistream) | Created | [`6fee467`](https://github.com/gretchycat/libinfinistream/commit/6fee467d62e95b1afb231b019dd69dd35364349d) |
| [libinfinistore](https://github.com/gretchycat/libinfinistore) | Created | [`faee836`](https://github.com/gretchycat/libinfinistore/commit/faee836518fca09d2c9ba8cedb174b1a80779809) |
| [libinfinivault](https://github.com/gretchycat/libinfinivault) | Created | [`aa41c5d`](https://github.com/gretchycat/libinfinivault/commit/aa41c5d4e2b6db06b83d29e889c04ec069e2bcd2) |
| [libinfinisound](https://github.com/gretchycat/libinfinisound) | Reused | [`0e7a9c9`](https://github.com/gretchycat/libinfinisound/commit/0e7a9c9803ef2c86bd4e88d0e501444a1713b2a3) |
| [libinfinicanvas](https://github.com/gretchycat/libinfinicanvas) | Created | [`c3e8aaa`](https://github.com/gretchycat/libinfinicanvas/commit/c3e8aaab4fa52f5212a2c3f59f7c2af390c7b54c) |
| [libinfiniscribe](https://github.com/gretchycat/libinfiniscribe) | Created | [`5c98077`](https://github.com/gretchycat/libinfiniscribe/commit/5c980775a8f1d097848cf2ddd844814e0baebb6e) |
| [libinfinivideo](https://github.com/gretchycat/libinfinivideo) | Created | [`447c92d`](https://github.com/gretchycat/libinfinivideo/commit/447c92d593876d40a39807b424c3e0fb35c23807) |

```text
Store -> Stream
Vault -> Store + Stream + Sound audio-api (playlist containers)
Sound / Canvas / Scribe / Video -> Stream
Host storage-engine -> Store + Vault (runtime/router/external-edit composition)
viewer-common -> host storage-engine + Canvas + Scribe
viewer-audio -> Sound; viewer-video -> Video
```

Ordinary files open through Store/Stream directly; Vault is used for containers. Family dependencies contain no Store/Vault cycle and no library depends on InfiniFolder's application or viewer UI. Legacy packages are retained as compatibility names. dependencies.json in each repository records tested sibling revisions; clone those dependencies beside the repository or set -PinfiniLibrariesDir for a standalone build.

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

## Checkpoint 4: bounded detection and executable pull composition

StreamFormatProbe uses the shared positional probe contract and existing signature logic, checking bounded headers, nonzero offsets and footers without moving a cursor or buffering a large resource. It returns immutable provisional identification metadata/candidates/evidence, preserves names/provenance and reports contradictory MIME hints. The Vault source adapter chooses parsers from detected content, including ZIP data named .tar; sequential inputs replay a bounded prefix. It discovers positional capability on the actual opened handle, avoiding a needless stream downgrade when factory-level capability hints are unknown. Existing parser algorithms and write paths remain intact. The host's nested archive stream resolver now also uses the common sequential adapter while preserving Unsupported versus Failure behavior.

Stream's registry now matches metadata attributes, validates actual output ports/types/capabilities and executes bounded single-resource pull routes. Failure closes intermediate sessions in reverse order; the original input remains borrowed. Sequential wrappers advertise only their demonstrated operations/ownership, and invalid read results propagate as errors. Scribe's actual bounded text load path now uses that registry/executor; target representation metadata distinguishes encoded text from decoded TextDocument values. Existing original bytes, charset behavior, preview limits and save revision checks remain.

Added tests cover contradictory names/MIME, nonzero/footer reads from a simulated 9 GiB source with under 4 KiB fetched, unknown/raw data, probe-budget exhaustion, sequential ZIP replay, metadata constraints, reverse close/failure cleanup, passthrough ownership, actual output validation and text pipeline execution. The full app builds; the regression gate reports 532 cases: 502 pass, the original 30 failures remain, no regressions or missing expected cases. Final independent/Android checks are recorded in the verification section below.

The checkpoint dependency audit moved the unchanged playlist-container shim from Store to Vault beside its ArchiveReader caller, preserving its public package and delegation to Sound's audio-api. Store now depends only on Stream within the family. Vault depends on Store, Stream and the small Sound API; no reverse Store-to-Vault dependency exists. Earlier checkpoint records retain their historical dependency descriptions.

The final image input audit also moved the viewer's bounds/pixel BitmapFactory fallback and its existing sampling rule into Canvas. The viewer now calls decodeViewerFrame for every decode branch; source opening, bitmap cleanup and UI behavior remain host-owned. Two sampling regression tests preserve the old valid-input rule, including panorama behavior.

Domain metadata propagation was checked explicitly: the Sound stream processor preserves original source provenance/warnings through PCM decode; Video retains its logical resource URI separately from the local file view; text retains source provenance but does not mislabel decoded output as verified solely because input bytes were verified. Actual format/rate/dimensions remain domain-derived.

Document loads now stay on ByteSource through MediaSourceAdapter.openSource, Scribe's negotiated pull decoder and LoadedText.metadata. Source identity is supplied from the known URI without an extra stat request; the original byte revision and truncation guard remain unchanged. StreamMetadataCarrier preserves metadata through legacy InputStream adapters as well, recalculating the demonstrated sequential capabilities/ownership. A contract test exercises the exact document helper used by Android viewers. Canvas's generic source adapter also retains known input warnings in frame metadata.

## Final verification and reproducibility

Run `python3 scripts/verify-infinistream.py` from the checkout (offline by default; --online permits configured dependency resolution). It builds the APK, runs application/viewer/library suites and compares all expected cases. Compilation/build failures, new failures/skips, missing cases and stale failed-test reports fail the gate. The only tolerated Gradle failure is the storage test task containing the documented pre-migration failures; the runner reports that the full suite is not green. It never skips those tests. The original 458-case baseline is retained separately from the expanded 538-case checkpoint.

Current totals: **538 tests, 508 pass, 30 pre-existing failures, no new failures or missing cases**. Baseline failures cover archive/LVM, transfer/recovery and torrent expectations and remain unresolved; this migration does not claim those suites were repaired. The exact names are in validation/infinistream-baseline.json.

| Independent library | Tests | Failures |
| --- | ---: | ---: |
| Stream | 18 | 0 |
| Store | 6 | 0 |
| Vault | 3 | 0 |
| Sound | 20 | 0 |
| Canvas | 7 | 0 |
| Scribe | 13 | 0 |
| Video | 4 | 0 |

All seven standalone source builds passed with the pinned sibling dependencies. Stream/Scribe and portable Sound builds need no Android runtime. Android modules were built with SDK 36, JDK 21 targeting Java 17, Kotlin 2.2.20 and AGP 8.9.2 using Gradle 9.7.1. Full app and selected audio/text/image/video host debug APKs built. Termux ARM64 native source builds passed; the standard NDK/CMake path and desktop/iOS runtime execution were not exercised.

The Android app_process harness loaded the current full APK and passed 12 portable/platform audio decode cases plus real TGA BitmapFrame metadata/borrowed lifetime, bounded Scribe Unicode-text pipeline and Video surface-metadata checks. PCM frame counts/precision match across policies; FLAC samples are exact through 32 bits. Ext/tracker JNI, new library classes and JAAD's bundled license are present in the full APK. These 15 checks do not exercise audible playback, playback surfaces, UI, seeking/gapless, casting or live remote servers. Those require device/backend acceptance and remain explicitly unclaimed. Logs and harness outputs are under build/infinistream-migration.

## Next migration work and intentional compatibility

- Legacy StorageProvider/BlockDevice/File APIs and transitive exports remain where callers still need them. Do not remove them before source/consumer and publication/recovery tests prove replacement safe. Store's current implementation module is Android-specific; pure model/platform splits can follow without changing current semantics.
- The actual document path now negotiates a bounded pull route. Planning automatically finds single-resource routes; arbitrary multi-input/multi-track automatic search is not implemented. Multi-port descriptions and explicit registry bindings exist; general graph composition and further viewer/copy/write routing are subsequent checkpoints. Push is an optional synchronous, bounded/backpressured utility; no mandatory scheduler was added.
- Legacy UI dispatch, inspector/editor-specific helpers and some parser/storage adapters remain for incremental migration. Common-source Vault identification prefers headers/offsets/footers and safely replays sequential prefixes, but this does not claim every old filename dispatch was rewritten. Existing signature/format limits and writable restrictions still apply.
- Related-resource audio (banks/companion files), native iOS packaging, desktop output, all-ABI native verification and truly minimal viewer APK dependencies remain follow-up. The app retains File hints, output/lease ownership and existing composite renderer limits.
- Original-code licensing remains pending owner choice. Each repository states that status; public visibility is not a license grant. Existing 7-Zip/zstd/JPEG XL/JPEG2000 AAR/native packaging requires F-Droid source-build/licensing review. No opaque native blob or language port was introduced by this migration.

Final full debug APK SHA-256: `b751357d3e2c8c77257d7247afd7d0ecf9cc16ee82937b16bb37cb7899ca7cfe`.

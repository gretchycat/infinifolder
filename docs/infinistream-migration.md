# InfiniStream migration checkpoints

Design authority: [implementation assignment](infinistream_architecture_codex.md). Baseline: InfiniFolder `9233495`, libinfinisound `c6137cd`, 2026-10-30. The user supplied the assignment as an untracked file; preserve it intact. No AGENTS.md was found in the workspace or its project/home ancestors. The initial documentation checkpoint preceded all source moves; the implementation checkpoints below record the current state.

## Published repositories and pinned dependency graph

All remote main hashes were checked against the local tested heads. Sound's existing history is preserved; the other six repositories were created for this assignment. Each repository records standalone dependencies and source provenance. The workspace pins all seven as Git submodules and Gradle composite builds; no unpublished Maven artifact or vendored library tree is required.

| Repository | Status | Pinned commit |
| --- | --- | --- |
| [libinfinistream](https://github.com/gretchycat/libinfinistream) | Created | [`e86167f`](https://github.com/gretchycat/libinfinistream/commit/e86167f2fd0c2c19d24140f05478099fdb1a693b) |
| [libinfinistore](https://github.com/gretchycat/libinfinistore) | Created | [`b3ed247`](https://github.com/gretchycat/libinfinistore/commit/b3ed2474db561fd7c31c923cbb25aae990fb298d) |
| [libinfinivault](https://github.com/gretchycat/libinfinivault) | Created | [`14a7908`](https://github.com/gretchycat/libinfinivault/commit/14a7908ffb8cd1af7d9a5400621d91ea1626f4f0) |
| [libinfinisound](https://github.com/gretchycat/libinfinisound) | Reused | [`d4d266d`](https://github.com/gretchycat/libinfinisound/commit/d4d266d63fdcd3b9a849a14021822c599b39d0b4) |
| [libinfinicanvas](https://github.com/gretchycat/libinfinicanvas) | Created | [`ae706d7`](https://github.com/gretchycat/libinfinicanvas/commit/ae706d70d84a3d6e635d96347bc5c0fe26e5346b) |
| [libinfiniscribe](https://github.com/gretchycat/libinfiniscribe) | Created | [`084e9de`](https://github.com/gretchycat/libinfiniscribe/commit/084e9de5ab5335c79ae88fdf2c297314cfd664e3) |
| [libinfinivideo](https://github.com/gretchycat/libinfinivideo) | Created | [`091e10a`](https://github.com/gretchycat/libinfinivideo/commit/091e10a6c06b73fb1828ba75348d09338ac89116) |

```text
Store -> Stream
Vault -> Store + Stream + Sound audio-api (playlist containers)
Sound / Canvas / Scribe / Video -> Stream
Host storage-engine -> Store + Vault (runtime/router/external-edit composition)
viewer-common -> host storage-engine + Canvas + Scribe
viewer-audio -> Sound; viewer-video -> Video; viewer-image -> Canvas
viewer-csv / viewer-pdf -> Scribe
viewer-epub -> Scribe + Vault (host retains Store cache/staging leases)
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
| libinfiniscribe | MarkdownRenderer, bounded text reading/encoding and CSV/TSV parsing/formatting | Retain WebView/editor UI and revision-checked saves in host |
| libinfinivideo | Original Android presentation adapter retained for compatibility; libVLC selected by the current host at the owner's request | Library owns backend/surfaces/focus behind common controls; host retains UI/policy and source leases |

At the baseline, `storage-engine` mixed Store/Vault/runtime responsibilities; it now retains only host runtime/router/external-edit orchestration. `StorageRuntime` and `RoutingStorageProvider` enumerate concrete providers and stay in the host composition layer until injection replaces those references. Existing archive-package names may temporarily survive as source/binary compatibility namespaces; their ownership must be documented rather than creating dependency cycles.

## Checkpoint rules

Each implementation checkpoint builds its standalone library and the full app, runs focused and existing tests, commits the affected repositories and updates the host's pinned submodules. Current APIs remain available until migrated callers and tests prove removal safe. No format or write capability is widened by extraction. Static descriptors, detected metadata, negotiated outputs and runtime events are distinct. Unknown length/capabilities remain unknown. Random access lost by a stage is never advertised as retained.

## Baseline verification

Running full app build and existing storage/common/audio/text/Markdown/image/video unit suites before source edits. Log: `build/infinistream-migration/baseline.log`. The full APK build passed; storage ran 348 tests with 30 pre-existing failures. The exact baseline failure list and XML snapshots are retained under build/infinistream-migration. Failures include outdated archive/LVM, transfer/recovery, and torrent expectations. Remaining viewer suites are running separately because Gradle stopped on the storage failures. These failures must not be misreported as a green baseline or hidden with exclusions. Previous user acceptance confirms current app/audio working. This does not establish all future runtime regressions are impossible; tests and device checkpoints are required.

## Licensing and F-Droid

The baseline has no top-level original-code license in InfiniFolder or libinfinisound. A license choice has been requested from the owner; do not silently relicense extracted code. Preserve third-party source notices and dependencies. Existing native ext/lwext4 and libopenmpt source build scripts remain; 7-Zip, JPEG XL and JPEG2000 binary dependency packaging needs F-Droid review. New repositories must record provenance, dependencies and any pending licensing rather than implying distribution clearance. Checkpoint 20 intentionally adds the requested libVLC Maven native dependency, with its pin, LGPL assets and source-build override documented; the upstream VLC/contrib build and release review remain pending.

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

## Checkpoint 5: CSV/TSV document consumers

Moved the activity's existing delimiter/parser/formatter methods into Scribe's JVM DelimitedText implementation; compatibility companion methods now delegate. The four original host parser tests remain, and the same expectations run without Android in the library. Existing permissive behavior is retained, including filename TSV preference, heuristic tie ordering, trimming, blank-row handling, quoted newlines, CRLF and unmatched quotes.

The actual CSV load now passes a borrowed ByteSource through the text decoder and registered table parser. The host fingerprints the original bytes, before trimming/line-ending normalization. The viewer retains table metadata and editable rows; it does not keep a duplicate original table. Save serialization now uses the registered table-to-text formatter and its UTF-8 bytes, with the existing revision-checked save/Save As paths unchanged. Partial previews keep their read-only guard and lack a save revision. No source is reopened or implicitly staged, and no provider write/recovery API changed.

Both pull processors publish typed ports and actual metadata: table delimiter/format, Unicode representation, row/maximum-column counts, completeness, ownership, provenance/warnings, then actual serialized text counts/encoding/line endings. Output verification does not inherit verification of input bytes. Cancellation is cooperative, and processor sessions borrow their inputs.

Verification: the pre-edit 538-case gate and post-edit 552-case gate both report the same 30 existing failures, with no new failures/skips or missing cases. Four original parser expectations plus eight new library cases bring Scribe to 25 JVM tests; two additional host cases exercise the actual consumer. The full app and standalone CSV host build successfully; Scribe's standalone JVM build passes. All 120,000 seeded comparisons of delimiter detection/parsing/formatting against the pre-move methods match. A 5.4-million-character, 200,000-row sample measured median parsing times of 321 ms before and 324 ms after (five samples after warmup on this host; this is a local sanity check, not a cross-device performance guarantee).

The existing 15 packaged-APK Android checks still pass. Two more app_process checks exercise the real MediaSourceAdapter table consumer: original-byte revision, metadata, edited serialization/UTF-8/borrowed ownership, and incomplete-preview/no-save-revision propagation. Logs, extracted oracle, seeded comparison harness and runtime harnesses are under build/infinistream-migration. Interactive spreadsheet UI and live provider write acceptance remain manual checks.

## Checkpoint 6: positional document chunks and CSV routing

Handset testing found very slow/crashing 90/91 fixtures and loose CSV files opening as text. Android runtime inspection confirmed the CSV MIME is text/comma-separated-values; the early generic-text branch had bypassed the spreadsheet extension fallback. Explicit CSV aliases and specialized document extensions under generic text MIME now route correctly, while strong image/HTML MIME still takes precedence.

TextChunkReader and TableChunkReader in Scribe now consume actual common positional handles, with eight 16 KiB cached blocks and 64-bit byte offsets. Text windows default to 16 KiB/256 newlines and preserve UTF-8/UTF-16 boundaries. CSV windows stop at complete quoted records (50 matched rows/64 KiB scan by default), preserve the header and reuse DelimitedText parsing. Explicit sparse filtering/forward search scans windows with bounded memory. A single CSV record retains the prior 8 MiB budget. Source and actual window metadata distinguish right-hand EOF from a complete original; only an unfiltered whole source returns original bytes/revision eligibility.

MediaSourceAdapter prefers validated positional provider handles; only Unsupported falls back. Failure and unsuitable-handle cleanup are tested. Archive entries keep the sequential path because their old seek adapter may extract a whole entry. No full-file staging was introduced. Sequential fallback has bounded cached backward access and never invents arbitrary seeking. The viewers perform reads off-thread, cancel stale requests, close on teardown off-thread and show only one window. Text has Next/Previous/Go to byte offset; CSV uses record bookmarks and a grid bounded to 50 data rows plus header and 16 columns. Explicit complete-document editing keeps the old 4 MiB text/8 MiB CSV guard and revision/publication path; paged editing is not implemented by this correction.

The synthetic document samples and non-overwriting generator now live beside the other fixtures, with exact original cells/sizes/hashes and ZIP copies. No edited fixture is overwritten. The large samples' guide now checks chunk navigation and retained read-only/save guards, including reaching the text end marker by positional jump.

Validation: 568 reported cases, 538 pass, the same 30 pre-migration failures, no new failures/skips or missing cases. Twelve new library tests cover positional 64-bit reads, bounded fetches/cache, Unicode/line/record boundaries, sparse filters, search, cancellation/errors and ownership; four host tests cover MIME routing and source selection/error cleanup. Scribe builds independently, and full/text-host/CSV-host APK builds pass. The Android positional harness fetches 16 KiB for the first 50-row CSV window and 32 KiB for the first 16 KiB text window (one cached lookahead block); a jump to 4 MiB does not scan intervening data. Actual Android MIME routing selects the grid. These checks measure reads/decoding, not UI rendering. The attempted headless Android widget-layout setup was killed (SIGKILL), so no UI-layout timing or handset crash-free acceptance is claimed. The positional decode/routing harness runs separately from widget setup.

## Checkpoint 7: automatic bidirectional document scrolling

Text and CSV now prefetch previous/next windows near the vertical scroll edges. The manual page buttons and unbounded visited-page history are removed. Text retains three adjacent decoded windows, joins their exact text/bytes without inserted line breaks, captures the latest visible character/pixel position when an asynchronous result arrives, and restores that anchor before drawing. A native ScrollView adapter resumes the sampled fling after its local origin changes. The optional positional Go to action remains. CSV uses native recycled ListView rows, stable logical source-row IDs, a fixed header and up to five decoded record windows; insertions/trimming preserve the visible row/pixel offset. Only wholly off-screen windows can be evicted, including if a user reverses direction during a read. Reads remain serialized off-thread, stale results are ignored, and errors do not trigger endless retries.

Scribe provides adjacent backward text reads and backward CSV record reads from known boundaries. Reverse CSV scanning respects quotes, embedded newlines, escaped quotes and CR/LF/CRLF, retains actual logical row indices even for sparse filters, and does not need a global index or prefix rescan. The original eight-block source cache and document/record budgets remain. Real positional sources can recover evicted windows; sequential sources still have only bounded cached backward access. Complete-source metadata and revision guards remain required for editing/saving. Text search highlighting no longer replaces the complete displayed text on every highlight, and automatic window changes do not move to the first search match.

Validation: 580 reported cases, 550 pass and the same 30 pre-migration failures, with no regressions, missing cases or skips. All 12 new cases pass: six Scribe cases cover reverse Unicode/CSV boundaries, seeded records, sparse filters, 64-bit positional reads and cancellation; six presentation cases cover bounded eviction, direction reversal during a read, anchor shifts, duplicate results and exact whole/partial text composition. Independent Scribe and full/text-host/CSV-host builds pass. Android runtime checks pass for 250 forward/100 reverse CSV windows and 100 reverse text windows after a 4 MiB jump, including bounded reads beyond cache residency and forward/backward content agreement. These are decode/controller checks; no automated UI smoothness claim is made. Handset acceptance should scroll/fling both ways through fixtures 90/91, reverse during a load, check stable row/text position, use sparse CSV filters and the text end marker, and repeat the small edit/save/reopen checks. Automated parser/window checks do not establish handset UI smoothness.

## Checkpoint 8: accurate CSV row statistics

CSV statistics now show the source-row range actually visible in the ListView, updating on scroll instead of displaying the number of cached rows. Totals exclude the fixed header. Scribe's `countDataRows()` explicitly scans bounded record windows to EOF using the existing parser; an independent low-priority background worker owns its own source/cache, so counting does not consume the viewport cursor or delay the first chunk. The UI displays a counting/unknown state until the exact total is available; reaching EOF during normal browsing supplies the total immediately. Filtered views retain source row numbers, and empty/header-only files report zero data rows. Cancellation, source closure and read failures preserve unknown totals rather than inventing an estimate. Large text/CSV editing is now explicitly recorded in the roadmap and task list; the current read-only guards remain.

Validation: 588 reported cases, 558 pass, the same 30 existing failures and no regressions/skips/missing cases. Three new Scribe cases cover exact legacy-compatible counts, independent bounded readers, cancellation/failures and closure. Five new presentation cases cover visible ranges, unknown totals, empty/header-only files, filtered ranges and 64-bit row numbers. Scribe's standalone build, the full APK and the standalone CSV host pass. The Android counter reads the original large CSV in 16 KiB requests and reports exactly 31,669 data rows; its source closes and the viewport cache/cursor stay intact. The actual Android formatter shows the expected range/total and counting state. These three checks plus the preceding 22 runtime checks all pass; they do not exercise interactive widget scrolling.

## Checkpoint 9: shared syntax and appearance descriptors

Scribe now owns bounded lexical highlighting with TextMate scope names and reusable typed style/theme descriptors. Self-contained JSON themes use the VS Code tokenColors conventions, with a namespaced versioned extension for font family/size and partial emphasis inheritance. A schema and sample theme are included. The shared Android controller applies owned metric/style spans without replacing source text; it debounces edits/scrolls, bounds snapshots to 65,536 UTF-16 code units and 8,192 spans, cancels stale jobs, and preserves layout anchors. Text viewing/editing and HTML/Markdown source modes share controls, preferences and theme import/export. Text line labels use actual styled/wrapped baselines. Large-file editing and source-save guards remain as before. Scope selectors, lexical profiles and bounded-context limitations are described in [syntax highlighting](syntax-highlighting.md); this is not a full TextMate grammar/semantic engine. Gson 2.11.0 is a JVM dependency with its Apache license/notice bundled in the core and APK.

Validation: 608 reported cases, 578 pass and the same 30 known failures, with no regressions/skips/missing cases. All twenty new Scribe tests pass, covering theme conventions, inheritance, color alpha, descriptor round-trips/errors/bounds, token ranges, language detection, comments/strings/markup, continuation state, cancellation and budgets. Scribe's independent build, the full APK and independent text/HTML/Markdown host builds pass. The preceding 25 Android checks repeat successfully; two new checks verify actual descriptor round-trips and span updates preserving source, TextWatcher counts, selection and separate search spans, bringing non-rendering Android coverage to 27 checks. A separate headless TextPaint/typeface rendering attempt was killed (SIGKILL) after descriptor parsing; font rendering/widget layout and scrolling acceptance remain unverified on the handset. The successful span-data check does not retry font drawing or headless widget initialization. Evidence is retained under build/infinistream-migration, including syntax-runtime-probe.log and syntax-runtime-font-attempt.json. New synthetic source/theme samples live under filesystem-test-images/syntax; the edited CR-only document fixture remains outside this checkpoint.

## Checkpoint 10: CSV startup callback ordering

The handset crash export `build/infinifolder-crash-20261031-033508-902.log` records `UninitializedPropertyAccessException: lateinit property rowList has not been initialized` in updateRowStats, called synchronously by AbsListView.setOnScrollListener during setupUI. Checkpoint 8's statistics callback had been registered inside the ListView construction/apply block, before the rowList assignment completed. Listener registration now follows publication of that field, and statistics callbacks wait until all three required views are initialized. Reads, counts, parsers and save guards are unchanged.

Two mockable-Android regression cases invoke the statistics callback before rowList assignment and during partial setup. Both pass. The full APK and standalone CSV host build pass; the gate reports 610 cases, 580 passed, the same 30 known failures and no regressions/skips/missing cases. The preceding 27 non-rendering Android checks repeat successfully against this APK. The user subsequently confirms the corrected CSV opens and works on the handset. This accepts the startup fix; broader provider/format checks remain separately scoped. Raw crash logs remain local build artifacts.

## Checkpoint 11: pull-based PCM audio output

Following the user's report of slow MP3/other audio startup, the live normal and gapless preload paths now consume Sound's AudioWaveSource through a host PCM-only MediaDataSource adapter. Sound opens/validates one bounded PCM block when the decoder supplies an exact frame count, publishes actual WAVE/PCM metadata and pulls further samples into an explicit host-owned disk cache only when requested. Backward reads reuse that cache; forward seeks can decode intervening frames, as exposed by metadata. Unknown decoded lengths retain full preparation. Original encoded files never go directly to MediaPlayer; portable/Android codec selection remains in Sound. Existing prepareForOutput, File hints/companion behavior, source staging/cache leases, Cast conversion, output routing and linked gapless ownership remain.

Visualizer analysis now opens borrowed positional views of the same PCM resource instead of parsing a partially populated cache file or starting another compressed decoder. Closing those views does not close playback. Owner close signals cancellation and releases the decoder/cache. Late decode failure retries the existing full preparation policy and requires the same negotiated PCM format. Signal diagnostics describe complete validated output only. The pre-play metadata path no longer decodes a whole track to discover duration, and no longer repeats the format probe. `audio.load.prepared` logs separate stage timings and cached/total PCM byte counts.

Baseline gate before edits: 610 cases, the same 30 known failures. After migration: 625 cases, 595 pass, the same 30 failures, no regressions/skips/missing cases. Thirteen new Sound source tests and two host transport tests pass. Full APK, standalone audio host, standalone Android Sound and portable-only Sound builds pass. The existing 27 non-rendering device checks repeat successfully. Seven additional MediaExtractor checks cover the PCM output for MP3/ADTS AAC/MP4 AAC/WAVE and 16/24/32-bit FLAC, including middle/backward reads and exact byte lengths. A separate MediaPlayer check prepares a two-minute local MP3 without starting audio in 333 ms with 135,016 of 21,168,044 PCM bytes cached and an exact 120,000 ms duration. Repeat these eight checks with scripts/verify-audio-pull-runtime.py. Logs are retained under build/infinistream-migration/audio-pull-*.

This verifies lazy preparation/transport, not audible playback or hardware timing. Startup on actual user tracks, seeking while playing, gapless transitions, speed/routes and visualizer alignment need handset acceptance. Encoded remote/container staging can still delay startup; unknown-length and legacy composite sources can still need full preparation. The cache can grow to the complete decoded track; the existing RIFF size limit remains. Other legacy consumer and multi-input planner work below is still pending.

## Checkpoint 12: audio frame seeking

The user confirms startup is acceptable but reports slow forward/backward seeks. The previous prefix decoder took 4,557 ms for a cold 90-second MP3 read; a pending forward decode also held up cached backward requests. Sound now offers optional FrameSeekablePcmStream positioning and caches independent 4096-frame blocks when supported. Backward reads reuse valid blocks; cold forward reads position the decoder near the target. Sparse cache holes are never exposed as PCM. Metadata reports access strategy, cached byte count and decoder seek count. Non-seekable decoders retain explicit prefix access; unknown decoded lengths still use complete preparation.

Android's known-length standard backend and portable MPEG/WAVE implement frame positioning. A lazy MPEG header index retains at most 4096 coarse anchors and uses real packet offsets plus preroll to preserve encoder/synthesis delay. Portable positioning accounts for reservoir startup frames that produce no output. Android PCM reads use bulk native-buffer copies and do not block on codec input ahead of ready output. Late seek/runtime backend failures are reported and retain complete preparation fallback under the negotiated format.

MediaPlayer's fixed prefetch still makes backend throughput relevant: the recorded two-minute MP3 full decode was 777 ms portable versus 10,030 ms Android. Sound's openPlaybackSource therefore uses content plus validated MPEG timing to prefer the existing portable MPEG decoder, retaining Android fallback. Other formats retain platform-first playback. Explicit decoder policies/openWaveSource and existing codec implementations remain. InfiniFolder's normal and gapless paths call that library facade, and its existing MediaDataSource PCM output, queue, visualizer views and source leases are retained. Tested alternative output transports were not adopted.

Verification: 645 cases, 615 pass, the same 30 known failures, no regressions/skips/missing cases. Twenty new Sound tests cover cache positioning/holes/boundaries, exact MPEG/WAVE windows, packet indexes, malformed/short/cancelled inputs, playback policy and backend fallback. Full APK, standalone audio host and standalone Android/portable Sound builds pass. All preceding 35 non-audible device checks repeat; the long-track case now measures actual MediaPlayer seek-complete callbacks and requires byte-identical source windows after multiple forward/backward seeks. In the final recorded local run, preparation took 188 ms and four native callbacks completed in 26–362 ms. Logs are retained under build/infinistream-migration/audio-seek-*; scripts/verify-audio-pull-runtime.py now extends these checks with muted playback (checkpoint 13).

The user still needs to accept audible seeks on the previously slow files, gapless transitions and visualizer alignment. Local fixture timings are not a guarantee for every codec/provider. Portable codecs without frame positioning, legacy composite synthesis and encoded remote/container staging retain their documented costs. Other migration follow-up below remains pending.

## Checkpoint 13: tagged MP3 seek recovery

The owner supplied three MP3s for reproducing seeks beyond four/five minutes; the recordings and cover remain local test data outside source control. A near-end seek on Money reproduced a timeout and fallback: JLayer discarded the final MPEG packet when look-ahead encountered a trailing ID3v1 tag, leaving continuous PCM 241 frames short of its declared musical length. A temporary copy with only that footer removed completed the same seek. Two synthesized tagged-chord regressions also failed before the fix.

Sound now excludes that footer from JLayer's private owned byte view, including indexed restarts. Only three footer bytes are probed; original source metadata, encoder trim, frame counts and synthesis remain intact. Eight new tests cover exact continuous/seeked PCM through the tagged end, bounded probing, short reads, range/EOF handling, invalid reads, cancellation and ownership. The host records seek requests/completions, the selected decoder, elapsed time and cache/positioning metrics without requesting PCM. The native linked-player gapless path remains; the user separately reports gapless playback working.

Verification: 653 cases, 623 pass and the same 30 known failures, with no regressions/skips/missing cases. Full APK, standalone audio host, standalone Android Sound and portable-only Sound builds pass. All 27 preceding non-rendering device checks repeat. The PCM runtime harness now covers seven extractor cases, fifteen-minute variable/320 kbps constant-bitrate MP3s with jumps beyond five minutes, and automatic linked-player handoff using tagged synthesized audio. Three additional owner-supplied file cases run muted playback with concurrent analysis, native seek-complete callbacks, no full-file fallback and byte-identical seek windows. These 40 pipeline checks use the same final APK. Logs are retained under build/infinistream-migration/audio-tag-*; repeat the synthetic checks with scripts/verify-audio-pull-runtime.py and use --file for local recordings of at least 32 seconds. They do not establish audible continuity on every device.

A separate exploratory fifteen-minute 24-bit FLAC probe exposed an existing Android seek/PCM-format fallback failure (audio-long-flac-before.log). It is not counted as passing and remains follow-up; this checkpoint repairs the reproduced MP3 footer failure. Portable decoders without positioning and remote/container staging retain their documented costs.

## Checkpoint 14: FLAC precision, positional audio inputs and image composition

Baseline before edits: 653 cases with the same 30 known failures. The exploratory long 24-bit FLAC failure recorded in checkpoint 13 is repaired. Android's raw extractor actually returned 16-bit PCM, but overwriting its encoding hint labeled that data as 32-bit; late reads then failed and format-changing recovery was rejected. The adapter now checks real extractor precision, requests wider PCM only from codecs it configures, and normalizes wider FLAC codec containers to the canonical source width. Precision loss selects portable FLAC immediately.

Portable FLAC adds exact frame positioning using coded sample/frame numbers, validated frame CRCs and following-frame sample links, binary byte searches and at most 4096 anchors. It supports fixed/variable blocks without a seek table and retains the existing subframe reconstruction. Tests cover 16/24/32-bit mono/stereo windows, pending buffers, short reads, cancellation/close, target corruption, bounded cold far reads, and a valid-looking frame embedded inside verbatim sample data.

Stream's SharedPositionalResource owns one demonstrated known-length positional provider handle. Reopened borrowed views have independent cursors, serialized reads, strict EOF/progress checks and explicit owner cancellation. Sound exposes common-source playback/format/tag entry points. The host's main and gapless paths retain an AudioPlaybackInput lease, use direct positional handles where available, and keep local native file paths, CUE slicing, companion-file renderers and expensive archive seek adapters on the existing staged route. Unsupported positional access does not start a redundant sequential download; initial direct decoder-open failures can explicitly retry staging. Metadata is optional and failed/cancelled reads do not escape the load worker. Provider close errors are recorded without crashing player cleanup. The output edge remains PCM-only MediaPlayer, and the existing native linked-player mechanism is retained.

The image viewer now calls CanvasStreams.decodeViewer with a common resource factory and retains BitmapFrame metadata. This facade preserves native URI decoding and the original bounds/sampling fallback only for a matching, untransformed input; unrelated/transformed resources use their own bytes. Actual pixel dimensions/config/color-space metadata, provenance and warnings remain distinct from input hints. The inspector derives current frame attributes after edits. Existing editor/EXIF helpers and other legacy callers remain for later checkpoints; this does not claim all viewer composition is migrated.

Verification: 683 cases, 653 pass and the same 30 known failures, no regressions/skips/missing cases. Thirty new tests cover the paths above. Full APK, standalone audio/image hosts, Android Sound/Canvas and portable Sound/Stream builds pass. The same final APK passes 46 pipeline cases: the prior 27, seven PCM extractor cases, three fifteen-minute seek cases, file/shared tagged gapless handoff, direct positional MPEG/FLAC/AAC processing, explicit staging fallback and three owner-supplied MP3s. MPEG/FLAC seek windows are byte-identical to continuous decoding; AAC checks exact frame counts/metadata and PCM reads. The harness filters stale native seek callbacks by target, retains its 1500 ms local latency budget and runs playback muted with concurrent analysis. A failed stale-callback attempt is retained in checkpoint14-stale-seek-callback.log; final matching checks pass. Logs use checkpoint14-* under build/infinistream-migration. Image UI, live remotes and audible high-resolution output remain handset checks.

Final validation and handset boundaries are recorded below. Live remote latency is not established by a local positional-source harness. In particular, a cold exact MPEG seek still scans encoded packet headers; direct positional access is not a constant-time network-seek promise. Unsupported/sequential, CUE, companion-file and extraction-backed archive paths retain staging. Native iOS/desktop output and arbitrary multi-input planning remain follow-up.

## Checkpoint 15: PDF backend and document fixtures

The reported PDF regression was traced by the owner to corrupt input files; the
owner confirms valid PDFs still work. The original Android renderer also renders
the new known-good two-page fixture. This checkpoint continues the migration;
it does not claim to have repaired those corrupt documents.

Scribe's optional `scribe-android` module now owns the existing Android PDF
backend. The viewer calls `PdfDocuments` and retains explicit file staging,
cache leases, page navigation, fit/zoom and external-open UI. The library exposes
document and rendered-page StreamHandles with actual page geometry/raster metadata,
provenance and ownership. The host serializes/cancels page requests and discards
stale results; document cleanup follows active work and precedes staging-lease
release. Rendered pages close immediately, failed open closes its descriptor,
and a presentation transfer keeps a displayed bitmap alive after frame close.
One scale bounds both raster axes, preserving oversized-page aspect ratios.
This backend still needs a seekable local file and uses Android's native parser;
portable PDF backends and general document processor negotiation remain follow-up.

Synthetic fixtures under `filesystem-test-images/documents/` include a portrait/
landscape PDF, deliberately corrupt PDF, two-page PostScript, vector EPS, LaTeX
equations article and ZIP copies. Their generator preserves edited/existing files.
PDF structure and PS/EPS interpretation were independently checked with local
Poppler/Ghostscript tools; LaTeX was not typeset here. Full PostScript/EPS, LaTeX,
Office document rendering and ANSI music playback are requested roadmap work,
not newly enabled formats. Existing ANSI music fixture bytes remain unchanged.

Verification: 691 cases, 661 pass, the same 30 known failures and no regressions,
skips or missing cases. Eight new portable tests cover page raster geometry and
bounds. Full APK, standalone PDF host and independent Scribe core/Android builds
pass. `scripts/verify-pdf-runtime.py` adds eight real Android checks covering
metadata, pixels/pages, raster bounds, frame ownership/transfer, failed-request
recovery, cancellation/close, corrupt-input descriptor cleanup and serialized
render/close. PDF UI navigation, rapid requests, back during load/render and
archive/live remote opening still need handset acceptance. Logs use
`checkpoint15-*` under `build/infinistream-migration/`.

All previous 46 audio/document/image/video pipeline checks also pass against the
same full APK, including muted seeks on the three owner-supplied MP3s and linked
gapless handoff. Together with the new PDF cases this is 54 runtime checks.
Independent Scribe core also builds with both Android SDK environment variables
unset; the optional Android module is not configured on that path.

## Checkpoint 16: EPUB structure and leased ZIP expansion

Scribe's JVM core now owns EPUB container.xml/OPF/NCX parsing over borrowed
`DocumentResources` and common `SourceResource` handles. The actual viewer calls
its registered pull processor and consumes typed book/chapter metadata. First
title/author, spine ordering/non-linear exclusion, manifest/OPF fallback, NCX title
priority and bounded HTML title/heading rules remain. URI resolution preserves
literal plus signs, percent-decodes once, handles fragments and resolves NCX
references against the NCX directory. Related resource lookups are confined to
the host-owned directory and advertise demonstrated positional file handles.

Vault owns the former viewer's Java ZipFile expansion through host-supplied
capacity-checked output streams. The host still explicitly stages the ZIP and
expands all resources under its existing cache policy. It retains resource/source
leases, WebView presentation, themes, font controls and TOC/Previous/Next UI.
Loading is cancellable; cleanup runs after the worker and discarded completions
cannot update a destroyed viewer. Internal links use exact chapter paths and
retain fragment anchors, avoiding basename collisions and jumps to chapter one.

Platform SAX APIs provide the portable parser without a new dependency. Device
checks exposed different internal-entity callbacks on JVM/Android; a constant-
memory streaming guard now rejects internal DTD subsets before either parser can
expand them, while retaining external NCX DOCTYPE identifiers, comments, CDATA
and encoding detection. No external entity/DTD resources are loaded. Optional
XML/search work limits are explicit; defaults preserve valid-book size behavior,
and the viewer derives its byte limit from the existing cache budget and already
accepted expanded bytes, preserving active leases if the budget is lowered. The failed
device attempt remains in checkpoint16-epub-entity-check.log; corrected checks pass.

Four new synthetic books cover EPUB 2, EPUB 3 spine/HTML-title fallback, missing
metadata/spine/OPF-path fallback and invalid container metadata. An outer ZIP,
expected hashes and non-overwriting generator accompany them. Full EPUB 3 NAV-label
interpretation, lazy package serving, other Office formats, PostScript/EPS and
LaTeX rendering remain planned. The latter fixtures are not newly enabled formats.

Verification: 727 cases, 697 pass, the same 30 pre-existing failures and no new
failures/skips/missing cases. The 36 additions comprise 25 Scribe resource/XML/book
checks, six Vault extraction checks and five host chapter-link checks. Full APK,
standalone EPUB host, independent Scribe core (without SDK), Scribe Android and
Vault builds/tests pass. Ten new device cases check actual packaged Vault/Scribe
classes, structure/titles, related positional resources, invalid input, XML policy
and limits, cancellation and an EPUB nested in ZIP. The prior 54 runtime cases
also pass against the same APK: 64 total, including muted MP3 seeks and gapless.
Helper DEX targets API 26, matching the app's minimum API. Logs use checkpoint16-*.
The owner confirms EPUB viewing is working after the checkpoint 16 fixture/test
handoff (2026-10-31). Broader interruption/back-during-load and live remote/provider
checks remain separate acceptance work.

## Checkpoint 17: common-source image inspector

Canvas now owns the image inspector's existing Android EXIF calls through the
registered `canvas.android.exif` pull processor. The live viewer opens a common
ByteSource using its existing Store adapter, borrows it to the processor and
closes it after work. The library also offers an owned-open SourceResource route.
The immutable result retains raw camera/date/exposure/aperture/ISO/focal/GPS
values, actual structured metadata and source provenance/warnings. It does not
inherit encoded lengths/read capabilities, pixel attributes or verification.
Host labels, order, units, locale/float precision and basic current-bitmap details
remain. The existing Android EXIF backend and dependencies are retained.

Reads and final output check cancellation; source IOExceptions propagate even
when the platform parser catches them. Device checks exposed Android EXIF's full requested
read assumption: a one-byte-at-a-time source returned empty fields. The adapter
now fills the backend's supplied buffer across short reads until its request is
satisfied or EOF, without staging or another buffer. The original failed attempt
remains in checkpoint17-exif-short-read-attempt.log. Corrected device checks pass.
Newer inspector requests, image navigation and teardown invalidate old dialog
completions and interrupt source work; no new source stat runs on the UI thread.
Image decode/orientation/edit/export controls retain their previous paths.

Synthetic little-/big-endian tagged JPEGs, invalid metadata, original hashes and
ZIP copies live beside existing image fixtures. Their standard-library generator
preserves existing files and unrelated manifest entries. [Inspector checks and
scope](image-inspector.md) distinguish source metadata from current bitmap details.
Portable EXIF parsing, metadata writes and image-editor processing remain future
work, along with the previously recorded document format additions.

Verification: 748 cases, 718 pass, the same 30 pre-existing failures and no new
failures/skips/missing cases. The 21 additions are 16 Canvas processor contracts
and five host presentation checks. The full APK, independent Canvas build/tests
and image-only host APK pass. Ten new actual-APK Android checks compare legacy
field values, both byte orders, untagged input, source ownership/positional
cursors, short/unknown-length reads, cancellation/provider errors, invalid-input
cleanup, 100 repeated opens and ZIP input. All 64 preceding runtime cases repeat
against the same APK, for 74 total, including PDF/EPUB pipelines and muted MP3
seeks/gapless. A helper invocation with a nonexistent row-count fixture path was
corrected; only the successful rerun counts. The muted-audio helper's Handler
bridge now handles the expected stop interruption as cancellation, retaining
failure checks for unexpected active interruptions; the original shutdown-log
attempt is retained and the repeated audio cases pass. A repeated FLAC probe hit
the previously observed native stale-position callback race; the unchanged
callback/1500 ms/sample checks pass on retry. Its failed log remains in
checkpoint17-flac-stale-callback-attempt.log. The final audio aggregate contains
all 19 successful case logs against the same APK, retaining these separate failed
attempts. Logs use checkpoint17-* under
build/infinistream-migration. The owner confirms little-/big-endian EXIF fixture
checks and ZIP variants after the checkpoint 17 handoff (2026-10-31). The owner
also confirms the 20-byte truncated invalid JPEG remains listed and shows the
“Cannot decode this image format.” toast when opened, as expected for a file
without picture data. Other malformed input, UI and live provider acceptance
remains; see the inspector fixture notes.

## Checkpoint 18: image edits and PNG encoding

Canvas now owns the image editor's existing rotate/flip Matrix transforms,
source-pixel crop clamping/allocation, filtered resize and PNG compression behind
registered Stream pull processors. The live editor uses typed ImageTransform
requests and an actual-output BitmapFrame, preserving the original Android
algorithms. The view still maps its screen crop rectangle with the inverse
display matrix; its legacy getCroppedBitmap method delegates to Canvas. Edit
steps use explicit registry execution, avoiding an unchanged zero-step route
for a frame-to-frame target. PNG also works through ordinary pull composition.

Input frames are borrowed. New Bitmap objects are output-owned; exact-object
aliases are borrowed. Closing a result recycles only owned pixels, and
BitmapFrame.takeBitmap transfers them to the host without copying. Device
checks showed that mutable no-op resize can allocate a copy, while immutable
no-op resize/full crop can alias. Tests cover both actual ownership paths; the
initial incorrect mutable-alias test assumption is retained in
checkpoint18-mutable-noop-assumption-attempt.log. Each output publishes actual
dimensions/config/color space, source provenance/warnings and operation trace.

PNG output is an owned sequential/positional EncodedImage ByteSource with exact
encoded length, image/png type and width/height. It clears raw-frame attributes,
input filename and verification. takeBytes transfers storage to the unchanged
host byte-array publication path. PNG remains fully materialized. Native pixel
operations check cooperative cancellation before/after allocation; fresh
cancelled results are recycled. PNG checks during writes and preserves original
write exceptions if native compression catches them or clears the flag. The
native cancellation test can print expected InterruptedIOException diagnostics;
it requires cancellation to propagate without publishing partial bytes.

Host gestures, crop presets, viewport rotation, color-filter previews, dialogs,
slideshow and save/provider policy remain. Resize retains the image/metadata
captured when its dialog opened, preserving behavior if slideshow navigation
changes the current item. The host keeps sampled single-frame editing, resize
limits, original-URI protection and PNG-copy export. Richer edit history,
portable image processing and streamed publication remain future work.

Eight new fixture entries add a labeled asymmetric PNG, independent rotation/
flip/crop references, expected hashes and ZIP copies. The standard-library
generator preserves existing files and unrelated manifest entries. See
[image processing](image-processing.md) for contracts and handset checks.

Verification: 774 cases, 744 pass, the same 30 pre-existing failures and no new
failures/skips/missing cases. The 26 additions are Canvas geometry/options and
encoded-source tests; Canvas now has 52 JVM cases. Full APK, independent Canvas
build/tests and the image-only host build pass. Twelve new actual-APK groups
check independent and legacy pixels, transformed metadata, mapped/clamped crops,
filtered resize, owned/aliased/session/transferred lifetime, PNG bytes/alpha,
positional sources, pull composition, invalid input, cancellation and ZIP
fixtures. The preceding 74 device cases also pass against the same final APK:
86 total, including MP3/FLAC seek samples/callbacks and gapless. Logs use
checkpoint18-* under build/infinistream-migration. Earlier audio results before
the dialog-snapshot review are retained separately; final results use the final
APK. Interactive edit/export controls and live providers remain handset checks.

## Checkpoint 19: image viewport and crop follow-up

The owner's checkpoint 18 handset check found a black surface after edits that
reappeared after zoom, and initial freeform crop handles outside the screen.
Operations and saved results appeared correct. Retained 17:01 snapshots show
an empty viewer followed by correct archive thumbnails; no matrix state was
recorded by that APK.

The host reset rotated about the image center but centered using bounds rotated
about the origin. The old formula is reproduced with a real Android Matrix:
the 320×200 fixture lands entirely outside the 1344×2774 view after 90 degrees.
Reset now constructs a complete matrix using one origin and actual rotated
bounds. Pan limits use mapped bounds at every orientation. Replacing a bitmap
reapplies placement even without a layout-size change, with resets deferred for
zero-size views. Viewport/crop state is now included in diagnostic snapshots.

Crop initialization intersects image and visible viewport before adding margins,
reserves toolbar space, and removes the off-screen 100-pixel fallback. Corners
and whole-box dragging stay within visible bounds. Drawable coordinates are
converted to actual bitmap pixels before Canvas cropping, including density
scaling. Pixel transforms, library pins and save/publication policy are unchanged.
See [image processing](image-processing.md#viewport-correction-after-checkpoint-18).
Verification: 790 JVM cases, 760 passing and the unchanged 30 known failures;
all 16 new display-geometry cases pass. The native viewport helper reproduces
the old bug and checks rotation fit/fill, immediately visible software-drawn
pixels, rotated pan/crop and density-aware inverse mapping. The five viewport,
12 edit/PNG, 10 EXIF and eight PDF groups pass against the same rebuilt full APK
(35 focused native groups). Full and image-only debug builds pass. Logs use
`checkpoint19-*` under `build/infinistream-migration`; the viewport runner is
`scripts/verify-image-viewport-runtime.py`. The owner confirms the correction
works as expected after retesting (2026-10-31). Broader live provider/export
acceptance remains separate.

## Checkpoint 20: requested libVLC video backend

At the owner's explicit request, the actual video player now uses libVLC behind
libinfinivideo's backend-neutral `VideoPlayback` controls/events and scale modes.
The Android module owns native player creation/release, video/subtitle surfaces,
rate/seek/aspect translation and audio focus. The host retains controls,
playlist/repeat/PiP decisions, operation progress and source leases. Positions
and durations use long milliseconds. The old Android platform adapter and its
metadata APIs remain for compatibility.

Local files stay direct; remote/archive inputs keep the existing explicit staging
route. Common source metadata supplies logical origin, name/type and warnings;
actual input length/access capabilities describe the borrowed local view. Output
reports native dimensions/duration when known, backend and seekability, without
claiming CPU frame bytes. Native readers are released before their cache lease.
Stale staged loads and native events are rejected. Loading completion while
hidden does not start playback; onStop/onStart manage pause/resume while visible
PiP continues through onPause.

The Android binding is pinned to `org.videolan.android:libvlc-all:3.7.7`; its
packaged engine identifies as 3.0.25 Vetinari. This intentionally replaces the
platform codec backend with the requested native dependency. No AAR/.so is
committed. LGPL notices/license are bundled as assets, and a `libVlcAar` property
accepts an upstream source-built replacement. Native VLC/contrib source builds
and release/F-Droid license/build review remain outstanding; see the library's
[dependency notes](../libraries/libinfinivideo/docs/libvlc.md).
The app selects one shared C++ runtime per ABI; the checked ARM64 package retains
Sound's exact prior runtime and loads both Sound and VLC in one native process.

Nine fixture entries add original MP4/WebM/subtitled MKV, a silent six-minute
seek file, invalid input, expected values and ZIP copies with a non-overwriting
FFmpeg generator. See [video checks](video-formats.md#handset-checks).

Verification: 798 JVM cases, 768 pass and the same 30 known failures; all eight
new Video API cases pass (12 Video API cases in total). Seven native groups check
packaged Sound/VLC linkage, MP4/WebM/MKV track metadata, embedded subtitle
detection, the long-file index, software decoding, forward/back seek positions
with decoder progress, pause/speed/resume, malformed input and reopen cleanup.
Dummy outputs keep these checks silent and headless. Exit zero without all seven
markers fails the runner because libVLC redirects stdout/stderr to logcat.
The full app's five viewport, 12 edit/PNG, 10 EXIF and eight PDF groups also
pass against the final APK: 42 focused groups including video. The video-only
host passes its own seven native groups with its separately packaged VLC C++
runtime. Full APK, video-only host and standalone Video build/API tests pass.
Logs use `checkpoint20-*` under `build/infinistream-migration`. An initial
80-ms pause assumption failed in the host harness; the final check awaits actual
pause/resume state with a two-second bound. That attempt is retained separately.
Actual sound/sync, hardware surfaces, subtitle pixels, PiP and live provider
acceptance remain handset work. Direct arbitrary ByteSource callbacks, track
selection UI, new format routing, desktop/iOS adapters and encoding are follow-up.

## Checkpoint 21: activity routing and streamed video inputs

The reported Activity-bar shortcut opened the audio player for video operations.
It now maps the exact playback operation to its matching viewer and brings the
existing player forward. The selected video starts before sibling discovery
finishes, and libVLC's file/network startup buffers are explicitly 300 ms.

The previous remote/archive path did stage the complete video. The host now owns
source leases and passes common resources into Video. Video owns a loopback-only,
per-session HTTP adapter with 64 KiB reads, bounded clients/workers and nonblocking
socket writes. Actual positional sources serve native byte ranges directly.
Known-length sequential sources reopen and replay a prefix for seeks; unknown
lengths use chunked HTTP without invented positional capability. The adapter
never writes a staged video. Vault adds a non-extracting seekable-view probe:
stored ZIP/filesystem slices stay positional, compressed ZIP falls back to its
stream, and probing nested archives does not silently extract their parents.
Other inherited container/provider caching limits still apply. Stop cancels the
transport and native reader before the host closes the source owner.

The original six-minute 90 fixture is intentionally silent. New
`91-six-minute-audio-seek.mp4` and `video-audio-seek-checks.zip` add a 440 Hz AAC
tone without replacing the original seek file or original ZIP. The generator
preserves existing fixture payloads and clears Termux's inherited library override
for FFmpeg.

Verification adds 19 Video stream-transport cases and three Activity-routing
cases: 820 total, 790 pass and the same 30 known storage failures. All previous
798 cases retain their outcomes. Native streaming checks use the final APK's
actual libVLC to parse a 512 MiB virtual movie after reading 720,896 bytes, then
seek to 5:00, 0:10 and 5:50 with decoder progress in about 0.16 seconds each.
The complete probe/seek run reads about 23 MiB rather than the whole input.
Compressed ZIP replay, unknown-length WebM/MP4, native stored-ZIP slices,
non-extracting compressed-ZIP fallback and the audible fixture also pass.
These are headless checks with dummy audio output and video rendering disabled;
they do not establish picture/sound/sync or live server acceptance.

An initial native back-seek exposed blocking HTTP responses occupying workers;
nonblocking socket writes and a slow-client regression case correct that issue.
Intermediate attempts are retained separately from final `checkpoint21-final-*`
logs in `build/infinistream-migration`. The final full APK passes seven existing
video groups, eight streaming groups, and 35 affected image/PDF groups. The
video-only host repeats both video modes against its separately packaged C++
runtime. Full/host and standalone Video/Vault builds pass. Handset checks remain:
Activity shortcut, startup from local/remote/ZIP, cancellation, picture/sound,
long seeking, subtitles and PiP.

## Checkpoint 22: RealMedia routing and rejection of substitute audio

The owner reports checkpoint 21 working, then reports `.rm` opening the audio
player and producing the audio test chord. Store now identifies `.rm`/`.rmvb`
explicitly and the viewer routes those extensions to Video/libVLC, including
provider-supplied RealAudio MIME labels. `.ra` keeps its audio association.

The chord came from extracted legacy decoder stubs. Advanced-audio/RealMedia,
WMA, telephony and chiptune paths generated fixed PCM without decoding the input.
These substitute branches and unsupported registrations are removed. Existing
DSD/ADX sample conversion, actual MIDI/tracker rendering and valid soundbank
previews remain. Actual registered Android codecs still run inside Sound.
Unsupported input must fail and must not publish PCM. Missing/invalid SFZ
references and sampleless GUS patches now fail rather than inventing a sample;
SFZ reads its referenced WAV through the real decoder with actual sample rate
and channels. True chiptune emulation/additional portable codecs remain planned.

Six new Sound tests cover unsupported headers/extensions, the extensionless RMF
case, compatibility entry points, no WAVE publication on failure, missing samples
and exact SFZ sample bytes/rate. Three old fixture cases now assert rejection of
their former fake decoders while retaining the actual DSD/ADX checks. Their old
assertions accepted any nonempty generated PCM and did not prove decoding. The
regenerated JVM audio report now records unavailable codecs as failed and states
its Android-stub/device-acceptance limits. This corrects prior false support claims.

Three viewer-routing tests and one MIME test bring the gate to 830 cases: 800
pass, the same 30 pre-existing storage failures, and no missing cases. All prior
820 case outcomes remain; the intentionally corrected fixture expectations are
recorded above. Sound core has 75 passing cases including portable-only execution.

Original `04-realvideo-ac3.rm` contains actual RV20 video and AC-3 audio (12 s,
320×180, 440 Hz tone). `realmedia-checks.zip` has byte-identical valid/invalid
copies. The generator preserves previous fixture payloads. The invalid RMF header
must fail, and opening a valid movie afterward still works. Native libVLC checks
both video/audio decoder progress from local and common-source RealMedia inputs.

The exact final full APK passes 34 audio groups (including invalid-input/sample
rejection and actual Android AMR-NB/WB), eight video groups and eight streaming
groups: 50 focused groups, with dummy/muted outputs. Long MP3/24-bit FLAC seek
callbacks, sample-exact seek windows, positional sources and linked gapless
handoffs pass. The video-only host repeats eight video plus eight streaming
groups against its own C++ runtime. Full, audio/video host, standalone Android
Sound/Store and portable-only Sound builds pass. `checkpoint22-*` logs are in
`build/infinistream-migration`. An initial routing test exposed the earlier MIME
shortcut and an initial native helper had a Java checked-exception compile error;
those attempts are retained separately and are not counted as passing checks.
Video-only checkpoint 22 APK SHA-256: `0c3cc55b34b1cdc8f861207146246b8c6dd16cac39bcafd548391d9ec3ef78b0`.

Actual owner-file picture/sound, UI error handling and live-provider acceptance
remain handset checks; no substituted stream is acceptable.

## Final verification and reproducibility

Run `python3 scripts/verify-infinistream.py` from the checkout (offline by default; --online permits configured dependency resolution). It builds the APK, runs application/viewer/library suites and compares all expected cases. Compilation/build failures, new failures/skips, missing cases and stale failed-test reports fail the gate. The only tolerated Gradle failure is the storage test task containing the documented pre-migration failures; the runner reports that the full suite is not green. It never skips those tests. The original 458-case baseline is retained separately from the expanded 830-case checkpoint.

Current totals: **830 tests, 800 pass, 30 pre-existing failures, no new failures or missing cases**. Baseline failures cover archive/LVM, transfer/recovery and torrent expectations and remain unresolved; this migration does not claim those suites were repaired. The exact names are in validation/infinistream-baseline.json.

| Independent library | Tests | Failures |
| --- | ---: | ---: |
| Stream | 26 | 0 |
| Store | 6 | 0 |
| Vault | 9 | 0 |
| Sound | 75 | 0 |
| Canvas | 52 | 0 |
| Scribe | 99 | 0 |
| Video | 31 | 0 |

All seven standalone source builds passed with the pinned sibling dependencies. Stream/Scribe and portable Sound builds need no Android runtime. Android modules were built with SDK 36, JDK 21 targeting Java 17, Kotlin 2.2.20 and AGP 8.9.2 using Gradle 9.7.1. The preceding checkpoint built full app and selected audio/text/image/video host debug APKs; checkpoints 5/6/7 rebuilt the full app and affected text/CSV hosts, and repeated the independent Scribe build. Checkpoint 8 repeats full/CSV-host and independent Scribe builds; checkpoint 9 repeats full/text/HTML/Markdown-host and independent Scribe builds. Checkpoint 10 rebuilds full/CSV-host APKs for the startup callback fix. Checkpoint 11 rebuilds full/audio-host and standalone Android/portable Sound artifacts for PCM pull output; checkpoint 12 repeats those builds for decoder frame positioning and playback policy; checkpoint 13 repeats them for trailing MP3 metadata and long seeks; checkpoint 14 repeats affected Sound/Stream/Canvas and audio/image host builds for common encoded inputs and exact FLAC positioning. Termux ARM64 native source builds passed; the standard NDK/CMake path and desktop/iOS runtime execution were not exercised.

The Android app_process harness loaded the current full APK and passed 12 portable/platform audio decode cases plus real TGA BitmapFrame metadata/borrowed lifetime, bounded Scribe Unicode-text pipeline and Video surface-metadata checks. PCM frame counts/precision match across policies; FLAC samples are exact through 32 bits. Ext/tracker JNI, new library classes and JAAD's bundled license are present in the full APK. These 15 checks do not exercise audible playback, playback surfaces, UI, seeking/gapless, casting or live remote servers. Checkpoint 5 also passes two actual CSV consumer checks, bringing that checkpoint to 17 checks; checkpoint 6 repeats those and adds three positional-window/routing checks, bringing checkpoint 6 Android coverage to 20 checks. Checkpoint 7 repeats those checks and adds reverse CSV/text traversal checks, bringing checkpoint 7 Android coverage to 22 checks. Checkpoint 8 adds three row-total/independent-reader/label checks, bringing checkpoint 8 coverage to 25 checks. Checkpoint 9 adds two descriptor/span-data checks, bringing current non-rendering coverage to 27 checks; its separate font-rendering attempt was killed and is not counted as passed. Interactive playback and UI require device/backend acceptance and remain explicitly unclaimed. Checkpoint 11 repeats these 27 checks and adds seven PCM-source extractor checks and one MediaPlayer preparation check, bringing transport/non-audible runtime coverage to 35 checks. Checkpoint 12 repeats those checks and extends the long-track case with actual seek-complete callbacks, bounded indexed reads and byte-identical seek windows. Checkpoint 13 repeats the preceding 27 checks and adds two long-track cases, a tagged linked-player handoff, seven PCM extractor cases and three local affected-file cases: 40 pipeline checks in total. Checkpoint 14 expands this to 46 with high-resolution FLAC, direct encoded-source paths, explicit fallback and shared-source gapless handoff; final checks all use the recorded APK. Logs and harness outputs are under build/infinistream-migration.

## Next migration work and intentional compatibility

- Legacy StorageProvider/BlockDevice/File APIs and transitive exports remain where callers still need them. Do not remove them before source/consumer and publication/recovery tests prove replacement safe. Store's current implementation module is Android-specific; pure model/platform splits can follow without changing current semantics.
- The actual document path now negotiates a bounded pull route. Planning automatically finds single-resource routes; arbitrary multi-input/multi-track automatic search is not implemented. Multi-port descriptions and explicit registry bindings exist; general graph composition and further viewer/copy/write routing are subsequent checkpoints. Push is an optional synchronous, bounded/backpressured utility; no mandatory scheduler was added.
- Legacy UI dispatch, remaining inspector/editor-specific helpers and some parser/storage adapters remain for incremental migration. Common-source Vault identification prefers headers/offsets/footers and safely replays sequential prefixes, but this does not claim every old filename dispatch was rewritten. Existing signature/format limits and writable restrictions still apply.
- [Large text/CSV editing](roadmap.md#large-text-and-csv-editing) is planned; default viewing is chunked, while explicitly requested complete-document editing still uses the legacy guarded materialization path.
- Related-resource audio (banks/companion files), native iOS packaging, desktop output, all-ABI native verification and truly minimal viewer APK dependencies remain follow-up. The app retains File hints, output/lease ownership and existing composite renderer limits.
- Original-code licensing remains pending owner choice. Each repository states that status; public visibility is not a license grant. Existing 7-Zip/zstd/JPEG XL/JPEG2000 AAR/native packaging requires F-Droid source-build/licensing review. The requested libVLC backend adds a pinned published native AAR with source/override details; no native binary is committed and no language port is introduced.

Final full debug APK SHA-256: `29219a34caa3c8153ec24bf8e9a303be9c4bc8c855f33f9e9e06e6c3a20cb813`.

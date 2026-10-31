# Feature to-do list

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated: 2026-10-31. [Current confirmations and priorities](project-status.md) distinguish implementation from handset acceptance. Unchecked items below are remaining work, unless explicitly described as historical evidence.

## InfiniStream architecture migration

- [x] Align references with the supplied architecture; retain dated evidence and safety limits.
- [x] Create/reuse the seven justified repositories, pin source submodules and build them independently.
- [x] Extract existing Store/Vault and media/document implementations without codec/filesystem rewrites.
- [x] Use common sources in viewer/audio reads, preserve typed output metadata and source ownership, and execute the text pull pipeline.
- [x] Add bounded content identification, capability/metadata validation and an explicit regression comparison gate.
- [x] Move CSV/TSV delimiter/parser/formatter code into Scribe and use typed table processors for actual viewer loads/saves.
- [x] Use positional document chunks and bounded text/grid presentation; handle Android CSV MIME aliases and preserve complete-file save guards.
- [x] Share bounded source syntax highlighting and TextMate/VS Code-style descriptors across text viewing/editing and HTML/Markdown source modes; retain save guards. See [scope and controls](syntax-highlighting.md).
- [x] Migrate normal/gapless audio output and visualizer analysis to a shared PCM ByteSource, pulling exact-length decoded prefixes instead of preparing every complete track before playback. Retain unknown-length preparation, decoder fallback and source leases.
- [x] Add optional decoder frame positioning and independent PCM cache blocks; select the existing fast portable MPEG backend for validated playback content while retaining Android fallback. Check actual seek-complete callbacks and sample-exact seek windows.
- [x] Preserve tagged MP3 final frames to prevent seek-triggered full-file recovery; cover long seeks and muted linked-player handoff. User reports gapless playback working.
- [ ] Extend the user-confirmed local MP3 seeking/gapless behavior to high-resolution FLAC, live remote/container sources and visualizer alignment. Encoded source staging remains a separate latency cost.
- [x] Add exact portable FLAC positioning and validate real Android precision; use common positional inputs for normal/gapless audio where supported, retaining explicit staging fallbacks.
- [x] Route the full image viewer through Canvas common-resource composition, preserving native decode/sampling and output frame metadata.
- [x] Move image-inspector EXIF parsing behind a Canvas common-source processor, preserving fields/formatting and handling short reads, source errors and cancellation. See [inspector scope/checks](image-inspector.md).
- [x] Route image bitmap rotation/flip/crop/resize and PNG encoding through Canvas processors with actual metadata and explicit result ownership. See [scope/checks](image-processing.md).
- [ ] Accept image edit/PNG-export controls after the Canvas processing migration, including no-op edits and ZIP/SAF/network sources. The reported black viewport and off-screen crop handles have a host geometry correction awaiting handset checks; see [viewport correction](image-processing.md#viewport-correction-after-checkpoint-18).
- [x] Confirm little-/big-endian EXIF fixture checks and ZIP variants after checkpoint 17; user confirmation 2026-10-31.
- [x] Confirm the truncated invalid JPEG remains listed and is rejected with the decode-error toast; user confirmation 2026-10-31.
- [ ] Extend migrated image-inspector acceptance to other malformed input, live SAF/network sources, navigation/back during work and image editing/PNG-copy export.
- [x] Move Android PDF parsing/rendering into Scribe's optional Android backend, preserving host navigation/zoom/staging and serializing page lifetimes. Add valid/corrupt PDF, PostScript/EPS and LaTeX fixtures.
- [ ] Accept PDF navigation/fit/zoom, rapid page requests, back during work and archive/remote opening after the backend migration.
- [x] Move EPUB package/chapter/NCX parsing into Scribe's portable core over related common resources, and leased ZIP expansion into Vault. Retain the host's WebView, navigation, themes and font controls.
- [x] Confirm EPUB viewing after the Scribe/Vault migration; user confirmation follows the checkpoint 16 fixture/test handoff (2026-10-31).
- [ ] Extend EPUB acceptance to interruption/back during loading and live remote/provider staging. EPUB 3 NAV-label interpretation remains follow-up; current HTML-title fallback is retained.
- [ ] Continue converting legacy callers to metadata-driven composition before removing compatibility APIs or exported dependencies.
- [ ] Extend automatic planning for arbitrary multi-input/track graphs; current multi-port processors use explicit bindings.
- [ ] Verify live remote providers, Android playback/seek/gapless/route behavior and editor/container write acceptance after these checkpoints.
- [ ] Resolve original-code licensing and existing F-Droid native/AAR build requirements before a distributable library release.

## Large text and CSV editing

- [ ] Add a bounded chunk-backed edit model for text and CSV/TSV beyond the current complete-file limits, including cross-window Unicode/record edits, undo/redo and updated row/byte positions.
- [ ] Stream complete edited results through revision-checked staged publication, preserving provider capabilities, conflicts, cancellation, low-space behavior and recovery.
- [ ] Accept begin/middle/end edits, scroll-away/back, undo/redo and save/reopen on disposable copies of fixtures 90/91, including untouched-content and interrupted-save checks.

See the [large-file editing roadmap](roadmap.md#large-text-and-csv-editing). Current large-file read-only guards remain in place.

## Paged documents and Office formats

- [ ] Add full PostScript/EPS document rendering and LaTeX typesetting behind Scribe interfaces; retain existing raster-preview support and explicit platform capabilities.
- [ ] Add DOCX/XLSX/PPTX and OpenDocument viewing with source-buildable backends; validate fonts, pagination, tables, images, equations, worksheet semantics and slides using format-specific fixtures.
- [ ] Evaluate legacy DOC/XLS/PPT, encryption, calculations and editing separately; keep unsupported cases explicit.

See [document rendering roadmap](roadmap.md#paged-documents-and-office-formats).

## ANSI music

- [ ] Add [ANSI music playback](roadmap.md#ansi-music-playback) through Sound's portable note parser/synthesizer and existing PCM player, with optional synchronized terminal art and explicit dialect detection.

## Cloud storage

- [ ] Evaluate an optional rclone-backed provider for cloud services through the
  shared storage contract. SSH/SFTP, FTP, WebDAV and Samba have tested well;
  replacing those existing providers is a lower priority than adding cloud access.

## Current reliability priorities

- [x] Recent pane swiping is user-confirmed working. Retain icon/label/empty-space and vertical-scroll/hold-drag regressions across orientations.
- [x] Tree drops are user-confirmed working; the 02:47 UTC dump records accepted tree drops, successful verification and source removal for a same-device move. Broader backend and selected-set coverage remains useful regression work.
- [x] Tree drag autoscroll is user-confirmed working. The 02:52 UTC dump records repeated up/down scrolling followed by a successful drop into ZIP and destination tree refresh. Additional selected-set/orientation checks remain regression coverage.
- [ ] Confirm the destination tree refreshes after copying while preserving expanded branches and useful scroll position.
- [x] Validate the FAT image small-file optimization on the phone: the user confirms a much faster copy, and the new dump records successful verification and publication. See [device confirmation](container-batching.md#device-confirmation-2026-10-23-1944-utc).
- [ ] Confirm the transfer dialog stays the same size as filenames, phases, rates, and queue counts change; long failure details should remain readable by scrolling.
- [x] Validate batched FAT16 deletion on the phone: the user confirms success; the 20:51 UTC log records 1,489 successful staged removals and one verified commit in approximately 3.30 seconds. See [bulk deletion](container-batching.md#bulk-deletion).
- [ ] Complete explicit reopen/retained-file checks and FAT12/FAT32 device coverage for batched deletion.

FTP status: the user reports that FTP is functioning after testing. The earlier unique-file creation `502` error remains historical evidence, rather than a currently reproduced blocker. Revisit it if it recurs.

## Temporary pinned container targets

- [x] Hold a URI-backed breadcrumb and drag it into the file area to create an `.iflink` to that location in the displayed directory. The breadcrumb drag ignores selected files, never moves its source, and uses the existing link writer and collision handling. Releasing a held breadcrumb without dragging retains its pin menu. Device acceptance is pending.

Implemented 2026-10-23; handset acceptance is pending. Pins are kept in Activity memory for the current session and survive handled orientation changes, but are not saved as permanent bookmarks.

- [x] Allow a browsable container to be pinned: a directory, archive, disk-image folder, or another supported storage container.
- [x] Display pinned targets as icons in a row between the action bar and the breadcrumbs.
- [x] Size each pinned icon at approximately half the normal file icon size, following the configured icon size.
- [x] Keep pins temporary for the current browsing session; do not restore them as permanent bookmarks after restarting the app.
- [x] Hide the entire pinned-target row when no targets are pinned.
- [x] Tap a pinned icon to navigate to its container.
- [x] Accept a dragged file or selected file set over a pinned target. Use the same destination capability checks, safety checks, and device identity rules as other drop targets: move within the same device and copy between different devices.
- [x] Keep pinned targets available while navigating elsewhere, in portrait and landscape layouts.
- [x] Provide **Pin to top** in container long-press menus and **Unpin** on pinned-item long press, without introducing long-press actions on the navigation tree. Breadcrumb long press can also pin the referenced folder.
- [x] Retain the actual storage/container identity of each pin, including distinct SAF sources, network connections, and archive/image mounts. A pin must not bypass grants or make a read-only destination writable.

- [x] Use the same 700 ms drag-hover navigation for container cards, breadcrumbs, and pinned targets; preserve the full selected drag payload. Breadcrumb hover uses the breadcrumb navigation action, preserving its history semantics.
- [ ] Validate pin/unpin, half-size themed icons, horizontal clipping, multi-file drops and hover navigation on the handset in both orientations, including SAF/network/archive targets and read-only failures.

## Selection and sorting

- [x] Current-folder wildcard `*`/`?` and bounded regex selection, with matching options and selected-set actions.
- [x] Name, MIME type, size and modification-time sorting with both directions; folders remain grouped first.
- [x] Two-finger rectangle selection in ordinary and selection modes, plus Exit selection mode menus; user-confirmed.
- [ ] Complete pattern/error, sort/selection/scroll and menu acceptance checks.
- [ ] Add most-used sorting after defining usage statistics and stable resource identity.

See [implementation status and open issues](implementation-status-open-issues.md) for completed work, storage compatibility gaps, and deferred performance work.

## Additional container support and validation

- [ ] Accept the new TAR/compressed FAT-image write paths on-device, including low-space behavior. See [compressed container writes](compressed-container-writes.md).
- [ ] Validate drag-hover navigation: after 700 ms over a container card, navigate inside while retaining the full drag payload; confirm drops still work after navigation. Include directories, archives, packaged containers, and filesystem images in grid/list and portrait/landscape modes. Dragging onto the source itself must still open its menu on drop.
- [x] Add read-only RAR/RAR5 browsing/extraction and multipart handling through the shared native adapter; user confirmation received. See [scope and remaining checks](rar-arj-and-integrity.md).
- [x] Add ARJ compressed-method decoding and verified multipart segment assembly through the same adapter; user confirmation received.
- [x] Add read-only CAB browsing/extraction through the shared native adapter, preserving the selected cabinet and following linked sibling names. See [scope and handset checks](cab-support.md).
- [x] General CAB handset support confirmed by the user.
- [ ] Complete the individual CAB stored/MSZIP/LZX/Quantum, multipart and damaged-member checks.
- [ ] Evaluate LZH, CHM, RPM, XAR and WIM readers through shared native adapters rather than separate application-specific paths.
- [x] Add read-only BSD UFS1/UFS2 image browsing/extraction; the user confirms UFS and compressed UFS working. See [coverage and remaining checks](ufs-and-legacy-compression.md).
- [x] Correct `.tar.Z`/`.taz` decoding and add `.Z` image reading, with fixtures; TAR extraction acceptance remains pending.
- [ ] Validate UFS copied bytes, permissions, large/fragmented/sparse files, big-endian images, network/partition routes and malformed inputs.
- [x] Add a read-only NTFS 3.0/3.1 subset using live directory indexes, resident/nonresident/sparse data, record fixups and sequence checks. The user confirms NTFS working; see [limits and remaining checks](ntfs-support.md). NTFS3 is a driver, not an additional disk format.
- [ ] Accept NTFS raw/compressed fixture browsing and extraction on-device; validate nested directories, runlists, links and explicit unsupported-feature errors.
- [ ] Extend NTFS support for attribute lists, compressed/encrypted data, alternate streams, Windows ACLs and additional reparse formats.
- [x] Add limited read-only classic HFS/HFS+/HFSX image decoders through the shared block-device path. The user confirms HFS, HFS+ and HFSX working; see [scope and remaining checks](hfs-support.md).
- [x] Add independent HFS+/HFSX fixtures with Unicode, permissions/ownership, symbolic links, distinct case variants, gzip wrappers and expected payload hashes.
- [ ] Accept raw/compressed HFS/HFS+/HFSX copied bytes and metadata on the handset; add an embedded-volume fixture.
- [ ] Extend HFS support for resource forks, extended attributes/ACLs, hardlink/alias resolution, self-extending overflow trees and additional classic encodings.

Populated fixtures are in [filesystem-test-images](../filesystem-test-images/README.md). These proposed readers are not currently implemented merely because their sample images or external tools exist.

## Archive resilience and full disks

- [x] Mark unreadable archive members with an X while keeping identifiable readable members browsable.
- [x] Skip unreadable sources during directory copying, report warnings, and retain source folders when a move skipped contents.
- [x] Enable Android Zstandard decoding and fix CPIO UTF-8 names/link metadata; user confirmation received.
- [x] Organize fixtures by OS family and archive format and update generators/manifests.
- [x] Add complete MBR/GPT/APM disks and gzip wrappers; four browsing/extraction/source-immutability integration tests pass, with general phone confirmation.
- [ ] Add malformed partition-table, EBR, backup-GPT and partition-restricted write checks.
- [ ] Upgrade the native archive engine and extend multipart integration to ZIP/7z.

## FAT deleted-file recovery

Read-only view and copy-out recovery implemented. The user confirms undelete working and reports graceful handling after checking every delivered safety fixture on the handset. Additional provider/gesture coverage and explicit handset hash checks remain open. Scope is FAT12/FAT16/FAT32 image views through the shared block-device layer, including partition slices and supported image wrappers. Ordinary Android/SAF directory access does not expose deleted on-disk entries; physical-media recovery requires raw read access. exFAT needs a separate recovery implementation.

- [x] Add an explicit read-only **Deleted files** scan/view, separate from normal directory listings. Identify plausible deleted directory records (`0xE5`), validate geometry/attributes/cluster addresses and distinguish long-name records and genuine `0x05` filename escapes.
- [x] Recover surviving metadata and defensibly associated long-name fragments. Mark unknown short-name characters and uncertain names rather than inventing an original filename.
- [x] Begin with zero-length, single-cluster and plausible contiguous-file candidates. Clearly label contiguous allocation as an assumption when the original chain has been freed; fragmented reconstruction is a later feature.
- [x] Check candidates against live allocation and reachable files/directories. Refuse overlap with live data; free clusters alone do not prove that deleted contents survived overwrite or media discard.
- [x] Show confidence and specific limitations (metadata missing, fragmentation unknown, reused clusters or incomplete data). Do not label bytes verified without independent evidence.
- [x] Recover by copying into another storage location; for image sources, reject destinations inside the source image or its enclosing backing-container chain. Do not change directory markers or FAT allocation on the source. Explain when the destination's physical independence cannot be established.
- [x] Reuse shared copy progress, cancellation, destination-space checks and unreadable-file warnings. Cancellation reaches scanner/extraction/decompression reads; per-file scan/staging may delay visible byte progress.
- [x] Add trash-can badges and an **Undelete…** long-press option.
- [x] Add FAT12/16/32 short/long-name, empty/contiguous and live-subdirectory deleted-file fixtures; include compressed FAT16.
- [x] Add controlled fixtures for intact contiguous files, fragmentation, long names, deleted directories, reused directory entries, partial/full overwrite, zero-length files and malformed metadata; verify recovered bytes and unchanged source hashes.
- [x] Add stale-record protection, source snapshot/payload checks, active-FAT support, fresh compressed reads, transfer-origin guards and cancellation during staging.
- [x] Validate MBR/GPT/APM recovery plus compression variants; reject bounds/overlap and damaged GPT checksums.
- [x] Require an explicit unverified-recovery confirmation and retain uncertainty warnings after copy verification.
- [x] Handset acceptance of all delivered safety fixtures: the user reports every file handled gracefully.
- [ ] Complete additional provider, gesture, low-space/cancellation and explicit handset hash checks in the recovery documentation. Fragmented reconstruction and exFAT remain outside the supported scope.

See [implementation and limitations](fat-deleted-file-recovery.md).

Format reference: [Microsoft FAT specification](https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf). The deletion marker identifies a candidate record, not proof that its file contents remain recoverable.

### Partial recovery follow-up

- [x] Add an explicit partial-copy action that retains uncertain readable bytes and fills unavailable/allocated ranges with NUL bytes, preserving file offsets and length.
- [x] Save a companion JSON byte-range report, distinguishing confidence from copied/zero-filled disposition. Do not infer proven-good or proven-overwritten bytes from allocation alone.
- [x] Use shared magic/MIME detection for surviving header hints, package-aware comparisons and confidence downgrades on meaningful mismatches; never upgrade a header match to verified contents.
- [x] Verify data/report digests and clean up both outputs on report failures; retain source protections and cancellation.
- [x] Add partial-tail and matching/mismatching header fixtures; full suite passes 260 tests.
- [x] Confirm partial recovery working on the handset: user acceptance after the FAT16 export, byte-range/report verification and unchanged source hash. See [scope and evidence](partial-fat-recovery.md). Additional fixture/provider checks remain listed there.
- [ ] Add a selected-set partial-recovery menu and optional reference-hash/format-aware integrity checks, keeping structural consistency distinct from original-content proof.

### LVM image follow-up

- [x] Diagnose LVM browsing failure inside SFTP `echoesbbs-u.vdi`, Partition 3 using metadata logging: data-area offset was mistaken for metadata-area offset; large-partition fallback size also overflowed. Corrected area parsing and bounded metadata reads.
- [x] Confirm LVM/VDI browsing on the handset: user reports it is working after directory-based enumeration. Linear single-PV mappings validate identity and segment bounds; other layouts are rejected and logged.
- [ ] Add independent LVM fixture coverage, checksum validation, alternate metadata-area selection and advanced allocation support before expanding scope.

### Large remote image browsing

- [x] Confirm LVM metadata/mapping now succeeds for `echoesbbs-u.vdi`; the subsequent failure is in SFTP-backed filesystem reads.
- [ ] Accept shared archive scans and reduced SFTP range read-ahead on the handset; inspect the newly recorded transport causes and scan counters after reconnect/retry.
- [x] Implement directory-based catalogs for partition/LVM roots and ext2/3/4 browsing and extraction; the 03:27 UTC dump established eager enumeration as the remaining blocker.
- [x] User confirms VDI browsing is working with directory-based enumeration.
- [ ] Validate extraction separately and extend incremental enumeration to other image filesystem decoders. See [scope](image-directory-browsing.md).

- [x] User confirms the live-image copy is much faster. The 04:10 UTC retained tail shows 86 verified IO records, no transfer skip/failure records, cache refreshes and 161 ms median source opening. Coherent snapshot correctness and all size-change cases remain separate checks.

- [ ] Correct background readability probes of mounted ext directories/directory links: the 03:49 UTC dump tries opening `sbbs/web`, `exec`, `docs` and `webv4` as file streams and logs directory extraction errors after a completed transfer. Confirm their actual link/directory metadata before adjusting probe behavior.

### Mutable image source handling

- [x] Identify live VM writes as the source-change warning cause; user confirmed the VM is running. Retain source revision protection.
- [x] Implement the user-requested tolerant live-copy/backup policy: timestamp/size changes invalidate source caches and reopen fresh reads, with one best-effort consistency warning. Actual unreadable files remain reported. Use stable-image copies for controlled performance/byte comparisons.
- [ ] Capture a missed pane swipe with the new retained gesture trace; previous transfer-warning floods rotated out the needed events.

### Operation progress and navigation acceptance

- [x] Add shared transfer and verification ETA, including resumed torrent missing-byte accounting, external progress producers and background notifications. Handset acceptance is pending.

- [ ] Accept separate per-file and overall operation bars, including source preparation, verification, container publication, unknown sizes and fixed dialog sizing.
- [ ] Recheck intermittent Back failures with the refresh-generation fix and direct mounted-parent navigation; new retained Back logs should identify any remaining missed callback/slow parent lookup.

### UI responsiveness

- [x] Remove synchronous diagnostic file writes from touch/UI callers; coalesce transfer UI updates, release the coordinator lock before normal progress listeners, throttle notification updates and refresh terminal dialogs once.
- [ ] Accept responsiveness changes and inspect retained main-thread I/O/stall/render evidence during Back/swipe failures. See [investigation](ui-responsiveness.md).
- [ ] Use measured traces to prioritize remaining theme-icon disk reads, large grid/tree view construction and any remaining coordinator callback lock waits.

## Navigation/menu/usage implementation follow-up (2026-10-24)

Implemented nested File/Edit/View/Select/Settings menus, wildcard/regex selection, name/MIME/size/date sorting with direction, New document/archive templates, and the disk usage treemap. Removed the Virtual root swipe exclusion; changed viewer Back routing and moved the red-default activity light to the far right. These implementations require device acceptance; see [behavior, limits and checks](navigation-menus-disk-usage.md). Most-used sorting remains future work. Notifications, automatic successful-dialog dismissal and debug-toolbar placement are user-confirmed.

## BitTorrent virtual filesystem (downloads and resume confirmed; broader checks pending)

- [x] User confirms BitTorrent and magnet-link downloads and resuming working on the handset (2026-10-26). The detailed compatibility and failure-case checks below remain separate.
- [x] Retry after failure reuses partial files and saved checksums; user-confirmed.
- [x] Missing/corrupt checksum-cache recovery safely rechecks existing data; user-confirmed.
- [x] Indefinite peer waiting; user-confirmed.
- [x] Simultaneous transfers; user-confirmed. Detailed Activity row routing/rates and same-file conflict checks remain separate.
- [x] Add a separate read-only provider for `magnet:` links using the Apache-2.0 `bt` engine; keep torrent reads outside viewers.
- [x] Fetch magnet metadata before listing without downloading payload files. Expose torrent contents as normal folders/files with info-hash-based device identity.
- [x] Support copying a selected torrent file out by downloading only that file into app cache and returning it after the engine verifies it. Copy is currently a full-file download before the transfer stream starts.
- [ ] Device acceptance for metadata lookup, folder browsing, multi-selection copy, unavailable peers, cache behavior and large-file progress/cancellation.
- [ ] Add `.torrent` file URI support and seekable piece reads so archives and disk images inside torrents can mount without downloading full files.
- [ ] Implement on-demand piece requests and seekable reads, with buffering/progress/timeouts for missing pieces.
- [ ] Allow archives and disk images inside torrents to mount through the existing seekable provider path without downloading entire files.
- [ ] Distinguish not-yet-downloaded, waiting for peers, verified cached and failed/corrupt data. Torrent piece-hash checks remain mandatory regardless of optional post-copy verification.
- [ ] Define cache quota/eviction, cache storage choice, resume/background execution, network/battery preferences and explicit upload/seeding controls. Do not treat cache eviction or unmounting as deleting remote swarm content.
- [ ] Validate hostile metadata paths, overlapping piece/file boundaries, missing peers, cancellation, v1/v2/hybrid torrents and local fixture swarms. A torrent is not a writable copy/move destination.

The initial provider uses [Bt](https://github.com/atomashpolskiy/bt), whose documented features include magnet links, DHT, metadata exchange, partial file selection and Apache-2.0 licensing. Seekable on-demand piece reads and live transfer progress remain follow-up work.

## Downloads organization by rules (requested; deferred)

- [ ] Add optional organization of the Downloads directory using user-defined rules.
- [ ] Define matching conditions such as filename wildcard/regex, MIME/file type, source and age, with configurable destination folders and rule order.
- [ ] Provide preview/dry-run, conflict handling and an operation report before enabling automatic moves.
- [ ] Reuse the shared provider/transfer system, preserving source files on incomplete or failed transfers and avoiding rules that repeatedly move the same file.
- [ ] Define manual versus automatic triggering, background execution, exclusions and grant handling before implementation.

Requested as a future feature only; do not implement as part of current container-link/navigation work.

## Read-only overlay, home-screen shortcuts and widget

- [x] Add a read-only floating container browser with an Android overlay-permission prompt and a normal InfiniFolder fallback.
- [x] Open home-screen shortcuts to folders and mounted archive/disk-image roots in the floating browser; default size is half the display height by two-thirds of its width, with title-bar movement and a button that opens the current directory in the full file manager.
- [x] Allow pinch gestures to resize the floating browser's icon view.
- [x] Add themed lower-left/lower-right triangular window resize handles with enlarged grab areas, preserving icon scale and saved window geometry. User-confirmed working (2026-10-26).
- [x] Reuse the main file view's grid, icon cards, badges, filename rendering, sorting, MIME/theme icon selection and scroll/pinch view in the overlay. Disable icon dragging and per-item long-press menus. Long press anywhere in the grid opens the full application at the displayed directory and closes the floating window.
- [x] Provide **Add to home screen** in item long-press menus for files, folders, links and other entries, using Android pinned shortcuts and MIME-based fallback icons.
- [ ] Validate pinned shortcuts, permission return/fallback, all supported storage providers, and overlay lifecycle on a handset. This implementation has build validation only so far.
- [ ] Later provide the floating-window-style display as a home-screen widget, adapting its read-only navigation and launch actions to Android widget capabilities.
- [ ] Reuse the same URI/resource identities and MIME opening rules across normal browsing, overlay, shortcuts and widget; do not duplicate storage engines or bypass provider permissions.

The first app implementation landed on 2026-10-25. Device acceptance and widget support remain pending.

Launching a file or application from the floating browser closes the floating window after handing off the launch. Navigating into folders or browsable archives keeps the window open.

First-launch routing follow-up: the transparent dispatcher is now also the application's launcher entry and the activity associated with resource shortcuts. The ordinary app icon routes to the full browser; resource shortcuts stay in an independent transparent task. Existing shortcut routing migrates on package replacement and app startup without opening remote storage. Legacy intents delivered to an existing browser hide and finish that browser before dispatching. First-launch handset confirmation remains pending.

Floating-window follow-up: directory `.icon.*`/cover artwork, themed folder icons, installed application icons, category icons and link artwork now resolve asynchronously for visible cards. Home-screen pins use the same artwork resolver and existing pins are refreshed to use the transparent shortcut dispatcher. Container shortcuts open the overlay without constructing the full browser; application shortcuts launch the installed program directly. The floating toolbar has high-contrast open-in-file-manager/close controls and no parent-directory control. Handset acceptance of these changes is pending.

Startup crash (2026-10-25): automatic crash dumps identify `ForegroundServiceDidNotStartInTimeException` in `OperationService`, immediately after a resumed torrent fails to resolve the unavailable SFTP host `Boson.local`. A coordinator listener could stop the service before its foreground handshake. The service now enters foreground mode before listener registration and before processing every start command; callbacks run on the main thread, read current operation state, and stop only after a start command is received. Cancellation does not schedule automatic resumes. Saved torrent sessions remain intact. Build and handset validation of the fix are tracked separately; offline-launch confirmation is pending. Debug builds capture fatal errors before Activity construction and attempt automatic export to the configured dump folder, retrying at the next launch if needed.

## Handset confirmations and interaction follow-up (2026-10-24)

- [x] SMB/Samba direct opening confirmed.
- [x] Virtual root container links confirmed working.
- [x] Rectangle selection confirmed functional; changed to two-finger dragging so one-finger scrolling remains available. Two-finger behavior is now user-confirmed.
- [x] Pane swiping reported working on the latest build. Retain broader gesture checks as regression coverage.
- [x] Disk usage current-folder view broadly accepted. Parent navigation now resolves above the original scan starting folder; updated behavior awaits confirmation.
- [x] Removed the tree panel's separate hardcoded green tint in favor of the common theme background and secondary text colors.

## Two-finger selection acceptance

The user confirms the two-finger selection gesture working. It is now available directly in normal file browsing as well as selection mode, in grid/list views and for selectable Virtual root link entries. Drawing a rectangle enters selection mode automatically and preserves existing selected items. One-finger scrolling remains unchanged. The duplicate Virtual root breadcrumb and missing links on the Internal storage startup tree are both user-confirmed resolved.

## Shared disk cache (implemented; device acceptance pending)

- [x] Shared 200 MB default disk-data budget, separate configurable individual-file limit, persistent LRU eviction and active leases.
- [x] Evict unused entries before offering Copy to folder / Raise cache limits; apply active theme and light/dark mode to capacity/settings dialogs.
- [x] Integrate viewer/media/archive/compressed-image/icon cache allocations; preserve transactional work and recovery data outside eviction.
- [ ] Device acceptance of eviction, active-file protection, copy/raise choices, restart behavior and themed dialogs. See [policy and scope](disk-cache-policy.md).

## External write-back and cache expiry (implemented; device acceptance pending)

- [x] Gate external-edit return actions on the working copy's checksum; unchanged
  copies return automatically without a source read. Source conflict warnings use
  freshly read original content, ignoring timestamp-only differences.
- [x] Use a compact themed edit dialog with progress only during work and nested
  secondary actions. Handset acceptance pending for unchanged opens, timestamp-only
  updates, actual source-content conflicts and light/dark themes.

- [x] External working-copy handoff with before/after-staging and completion source fingerprints; source-conflict choices for a new name/location or explicit overwrite.
- [x] Durable unfinished external-edit review, explicit completion, writer-handle checks and verified publication without retaining version history.
- [x] Confirm editor return without a crash and a successful SFTP save; user
  confirmation and the 2026-10-26 22:07 UTC dump agree.
- [x] Six-hour idle cache expiry with a separate setting; active files and unfinished edits are exempt.
- [ ] Accept real-editor handoff/return, conflict choices and recovery on device. See [write-back behavior and limits](external-edit-writeback.md).

## Latest status and next acceptance pass

- [x] Loading light: green pulses on reads, steady dim green while waiting, idle after completion; latest handset confirmation received.
- [x] Duplicate Virtual root and missing startup tree links confirmed fixed; exit-selection action confirmed.
- [ ] Confirm latest unrelated Internal storage breadcrumb removal before a remote connection.
- [x] Image-viewer Back confirmed working.
- [ ] Confirm disk usage Parent above the initial scan root.
- [ ] Complete shared-cache and external-editor acceptance described above; these remain the next safety-focused checks.

Historical build/test totals describe their original revisions. No tests or build were run for this documentation synchronization. Architecture work and deferred requests are summarized in [project status](project-status.md).

## WebDAV acceptance

- [x] Add saved WebDAV connections, encrypted credentials, browsing, stream reads/writes, create/rename/delete and range reads when advertised.
- [ ] Complete the real-server protocol, transfer, range and failure checks in [WebDAV support](webdav-support.md).

Floating/full-browser switching: the floating title-bar maximize button now opens its current directory in the main file manager and closes the overlay. The main toolbar has an **Open current directory in floating window** button while browsing a directory; it opens that location and backgrounds the main browser, preserving its task. Toolbar controls scroll horizontally on narrow screens while the activity light stays fixed on the right. Android overlay permission uses the existing transparent dispatcher. Handset acceptance is pending.


## Storage API consistency pass (2026-10-26)

- [ ] Show actual extended attributes and POSIX ACL entries in Properties where
  the provider can read them; clearly identify unavailable metadata instead of
  presenting synthesized default Unix permissions as observed values.
- [ ] Accept an ext-to-ext metadata copy and copy-back using
  `filesystem-test-images/filesystems/linux/ext4-metadata-retry.img`, then inspect
  its modes, ownership, ACL, xattrs and contents against the adjacent JSON manifest.
- [x] Fix missing directory checksum update on moves of non-indexed ext directories;
  disposable-image regression and filesystem checks passed.
- [x] Confirm non-indexed directory moves on the handset and verify the resulting
  filesystem, file hashes, mode, owner/group, mtime, ACL and xattrs.
- [ ] Investigate the second damaged
  directory block and whether unrelated existing corruption can be detected before
  publishing changes. Preserve the original `ext4-metadata-tests.img` as evidence.

- [x] Add shared transfer metadata read/apply hooks with local and ext implementations,
  precise timestamps, ownership/modes, xattrs and ext ACL conversion/coherence checks.
  Read back destination attributes and retain move sources on metadata loss.
- [x] Preflight partition relocation sets and preserve native links during directory
  moves; refuse unsafe fallback for link-containing selections.
- [ ] Complete metadata parity for network/SAF/archive providers, hardlink topology,
  behavioral flags and the volume-integrity work in [metadata integrity](metadata-integrity.md).
- [ ] Confirm metadata-preserving copies and failure/source-retention behavior on
  the handset. Existing ext integration coverage does not establish provider parity.

- [x] Add a guarded ext2/ext3/ext4 writer through shared image mutations, private
  batches and checked publication. Include journal replay/transactions for journaled
  volumes, inode-preserving internal moves and explicit unsupported-feature gates.
  See [implementation, tests and limits](ext-image-writes.md).
- [ ] Confirm ext2/3/4 create/copy/delete and internal file/nonempty-directory moves
  on the handset, including compressed images and SAF/network publication. Extend
  indexed-directory, full-volume, fragmented and damaged-journal coverage.
- [x] Replace misleading FAT write errors for known read-only image filesystems
  with format-specific explanations, including ext2/3/4 and selected partitions.

- [x] Persist terminal Activity results and supported manual replay requests across
  launches, save removals, expose older results and suppress replayed notifications.
- [ ] Confirm result/removal persistence after app relaunch, including paused and
  cancelled torrent resume actions and ordinary-transfer restart source checks.

- [x] Route single/selected-set drops on empty file-pane space into the displayed
  container, and drops on non-container files into their provider-reported parent.
  Keep container/navigation targets, self-drop menus, capability checks and same-
  device move/cross-device copy rules. Already-in-folder drops do nothing.
- [ ] Validate blank-space/file-parent drops after drag-hover navigation in grid/list
  and portrait/landscape modes, with multiple selections, archives/FAT, read-only
  targets, Virtual root rules and same-folder no-ops.

- [x] Correct immediate Activity-history refresh after removal/Clear finished:
  guard dialog dismissal callbacks by identity, consume current task snapshots,
  and refresh after confirmation dismissal. Handset acceptance pending.

- [x] Add confirmed directional Activity gestures: active left Cancel/right Pause
  where resumable; failed/cancelled left Remove/right supported Resume/Restart.
  Clear finished includes failed/stopped history; files and checkpoints remain.
  User confirms swipe activities working in normal use (20:00 UTC follow-up);
  individual edge cases remain separate checks.
- [x] Keep data-only copy/move/link replay requests, refresh sources before restart,
  and retain collision protection. Pause/Cancel disable torrent automatic resume
  until explicit resume. Handset acceptance pending; general stream pause and
  process-death recovery for ordinary transfers remain separate work.

- [x] Implement optional batch-native relocation, with FAT12/16/32 preserving payload
  clusters instead of requiring duplicate free space for an internal move. Native
  FAT32 file moves user-confirmed in the 19:24 UTC dump: eight files, zero warnings.
  Directory/low-space/FAT12/16/compressed variants remain pending.
- [ ] Validate FAT relocation with less free space than the moved data, nonempty
  directories, long names/collisions, reopen, cancellation and failed publication.
- [ ] Extend safe native move support to other capable storage backends; copy/delete
  fallback can impose the same duplicate-space limit there.

- [x] Implement explicit failure reason warnings, one at a time, with foreground
  browser/viewer hosting and deferred background display; keep failed tasks and
  their reason available in Activity. Focused handset checks pending; BitTorrent/
  magnet completion tests are still running.

- [x] Add persistent common locations, Settings editing, automatic usage history,
  folder-picker shortcuts and capability-checked destination suggestions. Handset
  acceptance pending; see [common locations](common-locations.md).
- [x] Stop operation-status/resume callbacks reopening hidden Activity windows;
  preserve explicit Activity/notification access. Viewer-return acceptance pending.
- [x] Move hidden-operation feedback to a bottom status bar with action/item counts,
  combined rate and aggregate percentage; hide it completely when unused or Activity
  is visible. User-confirmed working; further polish deferred.
- [x] Add overall status-bar ETA from a time-weighted rolling one-minute transfer
  rate, retaining background history and counting stalls; adapt the strip to narrow
  screens. Status bar user-confirmed working; further polish deferred.
- [x] Centralize final capability restrictions and per-resource creation guarantees;
  copy through readable symbolic links, deny moves of non-deletable resources,
  report torrent read-only support, and stop fabricating SAF POSIX modes.
- [x] Share random-target validation, preserve backend-specific hooks, distinguish
  unsupported seek/write from real failures, and correct local/SAF resizing and
  range-cache invalidation around failed mutations.
- [x] Warn before random-access producers use unsupported targets; offer another
  target, explicit staged continuation, or cancellation without blocking the UI.
- [ ] Device acceptance of the new contract and staged fallback paths.
- [x] Share source/destination action decisions across menus, picker, paste, drops
  and transfer preflight; distinguish internal container moves from incoming moves.
  Internal ZIP moves user-confirmed with a clean committed transfer in the
  2026-10-26 18:57 UTC dump. FAT32 and broader acceptance remain pending.
- [ ] Move remaining mounted-source preflight disk I/O off the UI thread without
  weakening mounted-image protection; observed in the ZIP move dump.
- [ ] Finish remaining toolbar/New/catalog action dispatch, metadata preservation,
  access/publication guarantees and workspace policy. See
  [findings and remaining tasks](storage-api-consistency.md).

## Torrent verification checkpoint follow-up (2026-10-25)

- [x] Persist verified pieces in a sibling `.incomplete.cache` file and reuse matching clean checkpoints. User reports working.
- [ ] Recognize existing completed files and orphaned partial files; offer verify-and-reuse instead of automatically choosing another filename. Preserve existing data until the user explicitly authorizes replacement.
- [x] Add focused JVM coverage for changed/corrupt/missing companions, interrupted writes, failed flushes, lost acknowledgements, corrupt piece bytes, memory-cache overlap/eviction and completion cleanup. See [checkpoint safety checks](torrent-checkpoint-safety.md).
- [x] Confirm recovery from final-rename failure on SFTP: retained pieces reused,
  final file published, checkpoint/resume state cleaned, COMPLETE with zero warnings.
  User confirmation and the 2026-10-26 21:39 UTC dump agree.
- [ ] Complete remaining real-device network/process interruption cases across
  storage providers; this single successful SFTP recovery does not cover all failures.
- [ ] Investigate the 22:06 UTC SFTP torrent checkpoint read failure, reported as
  “channel is not opened.” Later torrent writes continued; establish whether saved
  verification progress and retries recovered safely.
- [ ] Review Bt teardown ordering: the successful 21:39 UTC run logs closed incoming
  listeners and task submissions after executor shutdown.
- [ ] Move cache initialization and debug-dump preference lookup off the main thread;
  StrictMode reports both in the 21:39 UTC dump.

- [x] Save each newly verified piece immediately during downloads and file verification, including partially completed startup scans; log saved-piece counts and checkpoint rejection reasons. 95 focused/regression checks pass; physical-device interruption acceptance remains pending.

- [x] Rename the torrent stop action to **Finish later**, preserve saved progress through interrupt-aware shutdown, fence queued writes and show paused history. 102 targeted/regression checks pass; device confirmation remains pending.

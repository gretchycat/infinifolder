# Remaining work

Updated 2026-11-04. This is the actionable backlog. [Project status](project-status.md)
records implemented behavior and owner confirmations; the [roadmap](roadmap.md)
describes future scope. Dated evidence stays in domain guides and validation records.
A pending variant does not revoke earlier acceptance.

## Completed foundations and scope

Application extraction is complete at checkpoint 31. The runtime-discovery gate
passes 1,071 cases; the 30 historical storage failures are repaired, not open work.
See [runtime discovery](runtime-capabilities.md) for exact revisions and limits.
Libraries are explicitly linked; handlers own format metadata and consumers use
typed services. Further source/sink adapters, multi-input planning, platform ports
and release/licensing work remain below.

Font preview (scaling, collections, dark mode, rotation, navigation and keyboard),
launcher folder/file/archive/remote/public-app shortcuts, connection badges,
core cache/external-edit behavior, document viewing and earlier animations have
owner confirmation. Opaque font content URIs and newer discovery/FLC/lifecycle
changes still need device checks. Ordinary copy/move undo excludes torrents.

## Unfinished downloads follow-up

- [ ] Verify `.download`, `.crdownload`, `.part`, `.partial`, aria2 pairs and
  torrent `.incomplete` files appear as links after browsing their source folders.
  Check the expanded Virtual root tree, target icons, restart persistence,
  completed-file pruning and link-only deletion on a device. Imported resume
  formats require their original source metadata; see
  [Unfinished downloads](partial-download-discovery.md).

## Appearance follow-up

- [ ] Verify the new default Virtual root device/application stamps, USB/SD
  identification and remote-provider cloud fallback; theme replacement, covers,
  links and tree/list/grid consistency remain device checks. Verify the extended
  unavailable-target badge on denied Samba folders and links, server loss and
  recovery. Earlier connection-badge acceptance remains intact. See
  [resource icons](resource-icons.md#default-storage-location-artwork--2026-11-04)
  and [connection badges](network-icon-badges.md#unavailable-folders-and-link-targets--2026-11-04).

- [ ] Verify unavailable-location × badges in list/grid/tree and pin-board access
  prompts after reinstall/revocation. Owner confirmed granting full shared-storage
  access restores the board. The new badge, grant/action continuation, denied
  refresh suppression and settings-recreation paths need device checks; see
  [pin-board permissions](navigation-menus-disk-usage.md#storage-permission-and-unavailable-location-badges).

- [ ] Diagnose the reported failure before the first browser screen on upgrade.
  Startup recovery now retains application-initialization exceptions/linkage
  failures and does not consider the transparent launcher a successful startup.
  Owner reports that `41b04e6` still cannot show recovery. Debug builds now include
  an isolated **InfiniFolder startup report** launcher (Android 11+) with Android
  exit history and Copy/Save actions. The owner saved a report successfully;
  Copy/native-trace export remain unverified. Retain existing data.
  Owner later reports a delayed direct launch that eventually works. The first
  report has no current startup exception/stall and predates that launch; obtain
  refreshed evidence. Icon-registry revision/migration/fingerprint work now runs
  off the UI thread, with elapsed-time logging; verify cold-start responsiveness
  and registry/theme changes on return to the app.
  Reinstall reports `(2)`/`(3)` contain restored October 29 app-private history
  and a new application session with no activity entry. Keep the watchdog active
  across Application/Activity construction and dispatcher handoff (implemented);
  collect that pending startup stack and phase markers. Underlying cause remains
  unresolved; no further data reset is required to collect this evidence.
  Owner confirms a subsequent launch opens the app; cold-start timing and the
  newer watchdog's stack capture remain unverified.

- [ ] Device-check repeated audio theme switches (including while playing),
  listener attachment/detachment, WMP Aero portrait/landscape and system insets,
  all transport/seek/volume/playlist controls, and restoring Winamp/Modern layouts.
  See [WMP interface](winamp-skins.md#windows-media-player-audio-interface).
- [ ] Verify Cast Stop cast, Cancel and chooser dismissal return directly to the
  player, with no gray host screen or automatic second chooser; include system
  chooser and rotation/restoration paths. Existing receiver streaming checks remain.
- [ ] Device-check the 10% alternate surface with light/dark and imported Winamp
  themes, especially Open with list scrolling; confirm actual skin rendering stays intact.
- [ ] Check Icon shadows in Settings → Appearance and themes: alpha edges on
  opaque/transparent icons, list/grid/tree/pins/floating views, small sizes,
  theme changes, immediate toggling and preference restoration after restart.
  Include cold startup while theme artwork is pending after the empty-bitmap crash fix.
  Main/standalone host build evidence: `build/docs-audit/appearance-shadows-build.log`
  and `build/docs-audit/icon-shadow-null-build.log` for the startup correction.
  No new automated tests or device UI checks were run for this appearance change.

## Current library/viewer follow-up

- [ ] Device-check FLC MIME aliases, newer FLC recovery, interactive MNG and lifecycle changes; earlier animation playback and IFF cycling confirmations are retained separately.
- [ ] Device-check generic binary/sidecar opening and real signature-selected archive listing.
- [ ] Accept continuous Cast transitions/seek/failure fallback on a receiver, plus shared Back/scroll and browser breadcrumb/position changes.

## Transfer warning checks

- [x] Owner confirms the warning checkbox works (2026-11-04).
- [ ] Extend warning checks to restart persistence, different messages, reset and move-source retention. See [metadata notices](metadata-integrity.md#document-provider-timestamp-notices--2026-11-04).

## Latest chooser and copy checks

- [x] Owner confirms normal file launching uses the internal viewer after the tap-path correction (2026-11-04).
- [ ] Extend chooser checks to unsupported formats, remembered internal/external file/type choices, Open externally, Cancel before staging and remote/archive handoff. See [opening rules](external-edit-writeback.md).
- [x] Owner confirms copying after the same-directory drag correction (2026-11-04).
- [ ] Extend copy checks to Copy to/Paste variants, confirmation Cancel, repeated names, selected sets and source retention; Move remains blocked there. See [copy behavior](navigation-menus-disk-usage.md#copy-into-the-source-directory).

## Link target icon checks

- [ ] Check remote folder/file links inherit target theme/cover/MIME artwork in an active connection, with host aliases, nested links, offline/reconnect and list/tree/pin/overlay variants. See [target artwork](resource-icons.md#storage-link-target-artwork--2026-11-04).

## Bundled default icon theme

- [ ] Accept cold/warm startup, representative file/folder/URI icons, theme switching and restart persistence on the handset. See [default theme](icon-themes.md#infinifolder-default).

## InfiniStream architecture migration

- [ ] Accept whole-transfer speed after the metadata changes; cache reuse alone leaves the small-file workflow slow. See [measurements and limits](remote-archive-transfer-performance.md#remaining-per-file-overhead--0351-utc-snapshot).
- [ ] Accept recovery from the recurring SFTP destination-listing failure. Unsupported-attribute warnings, warning-free move completion and move restoration after original deletion remain unverified.
- [ ] Extend the user-confirmed local MP3 seeking/gapless behavior to high-resolution FLAC, live remote/container sources and visualizer alignment. Encoded source staging remains a separate latency cost.
- [ ] Extend image edit/PNG-export acceptance to no-op edits and broader ZIP/SAF/network source and publication variants.
- [ ] Extend migrated image-inspector acceptance to other malformed input, live SAF/network sources, navigation/back during work and image editing/PNG-copy export.
- [ ] Accept PDF navigation/fit/zoom, rapid page requests, back during work and archive/remote opening after the backend migration.
- [ ] Extend EPUB acceptance to interruption/back during loading and live remote/provider staging. EPUB 3 NAV-label interpretation remains follow-up; current HTML-title fallback is retained.
- [ ] Accept Cast playback/seek/repeated ranges, stop/reconnect and older-URL reopening after the source migration, including archive/provider sources.
- [ ] Accept the mixed/all-unreadable and FluidR3 instrument fixtures on the handset, including their ZIP variants.
- [ ] Extend existing optional GME/libVLC handlers and portable codecs; report unavailable backends and unsupported input honestly.
- [ ] Accept updated remote/ZIP streaming startup and seeks, activity-shortcut return and the audible companion on the handset.
- [ ] Accept libVLC video surfaces/sound, seeking, aspect/speed, embedded subtitles, playlists/repeat, PiP and rapid navigation from local/ZIP/live remote inputs. See [video checks](video-formats.md#handset-checks).
- [ ] Continue converting legacy callers to metadata-driven composition before removing compatibility APIs or exported dependencies.
- [ ] Extend automatic planning for arbitrary multi-input/track graphs; current multi-port processors use explicit bindings.
- [ ] Verify live remote providers, Android playback/seek/gapless/route behavior and editor/container write acceptance after these checkpoints.
- [ ] Resolve original-code licensing and existing F-Droid native/AAR build requirements before a distributable library release.

## Large text and CSV editing

- [ ] Add a bounded chunk-backed edit model for text and CSV/TSV beyond the current complete-file limits, including cross-window Unicode/record edits, undo/redo and updated row/byte positions.
- [ ] Stream complete edited results through revision-checked staged publication, preserving provider capabilities, conflicts, cancellation, low-space behavior and recovery.
- [ ] Accept begin/middle/end edits, scroll-away/back, undo/redo and save/reopen on disposable copies of fixtures 90/91, including untouched-content and interrupted-save checks.

## Structured text formatting

- [ ] Extend large-document indexing/preloading beyond the current limits without weakening save guards.

## Paged documents and Office formats

- [ ] Check unstyled EPUB XML/XHTML chapters render as prose with working images,
  CSS/anchors, encoding and reader controls; confirm encrypted chapter metadata
  produces a persistent unsupported-encryption message. See
  [chapter fallback](document-and-ebook-readers.md#unstyled-chapters-and-encrypted-epubs).
- [ ] Add full PostScript/EPS document rendering and LaTeX typesetting behind Scribe interfaces; retain existing raster-preview support and explicit platform capabilities.
- [ ] Broaden document checks to format variants, Unicode/images/worksheets/slides, read-only guards and provider/remote/cancellation paths. See [document readers](document-and-ebook-readers.md).
- [ ] Extend layout fidelity and format conformance beyond the new Word 97+/Excel/PowerPoint reading subset; evaluate encryption, calculations and editing separately. Keep unsupported cases explicit.

## ANSI music

- [ ] Extend dialect/malformed-input coverage and evaluate normal music-player routing and synchronized terminal presentation separately.

## Cloud storage

- [ ] Evaluate an optional rclone-backed provider for cloud services through the
  shared storage contract. SSH/SFTP, FTP, WebDAV and Samba have tested well;
  replacing those existing providers is a lower priority than adding cloud access.

## Current reliability priorities

- [ ] Verify native hex range merges and rollback/recovery on disposable local,
  SFTP, SMB and SAF files; sequential archive back-scroll must stop at the 128 KiB
  memory window. Check old drafts, unknown EOF and shared cache-limit dialogs
  for sequential-write fallback. See [hex editing](hex-editor.md#editing-and-saves).
- [ ] Confirm Samba tests and folder opening agree on the configured share plus
  subfolder, and report reachable/connected/list-denied stages. The latest log
  confirms directory-list access denial; server permissions remain external.

- [ ] Diagnose the freeze/crash reported after `d5b9d4b` using a current fatal/stall
  trace. Existing exported crash logs predate that build. Startup monitoring and
  automatic debug-folder stall-log export have been added; capture/export and
  crash resolution remain unverified. See [reported startup failure](navigation-menus-disk-usage.md#reported-startup-freezecrash).
- [ ] Accept Virtual-root pin/delete/restore actions, stored-link deletion without
  touching targets, shared root/tree/pin icons, browser-page launcher routing and
  the tripled container/tree hover delays.

- [ ] Confirm the destination tree refreshes after copying while preserving expanded branches and useful scroll position.
- [ ] Confirm the transfer dialog stays the same size as filenames, phases, rates, and queue counts change; long failure details should remain readable by scrolling.
- [ ] Complete explicit reopen/retained-file checks and FAT12/FAT32 device coverage for batched deletion.

## Persistent pinned container targets

- [ ] Accept public InfiniFolder/Pin board creation, one-time/retried migration,
  restart, external file changes, Rename/Properties/Unpin and preserved targets;
  test storage denial, filename conflicts, embedded artwork and link navigation.
  See [public pin board](navigation-menus-disk-usage.md#public-pin-board-directory).
- [ ] Validate pin/unpin, half-size themed icons, horizontal clipping and single-link
  drops onto empty space/empty board, Virtual root, tree, breadcrumbs and the board
  directory in both orientations. Check that batch link creation is rejected,
  single/batch drops onto pinned folders default to real copies, and container
  hover opens in the regular file area after 2.1 seconds. Ordinary-file pins must
  reject drops. Include existing links, duplicate targets, SAF/network/archive
  destinations, read-only targets and write failures; pin reorder/swap must remain
  independent of container copying and hover navigation.
- [ ] Accept live pin-order previews, center-to-center swaps, gap insertion,
  First/Last drop spaces, horizontal/edge scrolling, cancel and retained scroll
  position. Check batch creation, dynamic padding, audit preservation of gaps,
  full reindex only when needed, rename-label independence and interrupted-rename
  recovery. Check emoji menu actions/accessibility and alternate pin/breadcrumb
  backgrounds while the file pane retains the normal theme color.

## Transfer cancellation

- [ ] Accept move-original restoration after deletion, conflicts and a distinct free-space exhaustion failure on-device. Investigate source attribute capture warnings in the 23:38 UTC dump. See [scope, safeguards and tests](transfer-cancellation.md).
- [ ] Extend accepted SFTP reconnect/disconnect badges to remaining channel-capacity/setup failure variants. See [evidence and implementation](sftp-channel-recovery.md).

## Selection and sorting

- [ ] Confirm alphabetical tree siblings in sidebar/picker, including Virtual root, remote/partial listings, archives, new folders, case-varied names and retained expansion/selection. See [tree ordering](navigation-menus-disk-usage.md#tree-ordering--2026-11-04).

- [ ] Complete pattern/error, sort/selection/scroll and menu acceptance checks.
- [ ] Add most-used sorting after defining usage statistics and stable resource identity.

## Additional container support and validation

- [ ] Accept the new TAR/compressed FAT-image write paths on-device, including low-space behavior. See [compressed container writes](compressed-container-writes.md).
- [ ] Validate drag-hover navigation: after 700 ms over a container card, navigate inside while retaining the full drag payload; confirm drops still work after navigation. Include directories, archives, packaged containers, and filesystem images in grid/list and portrait/landscape modes. Dragging onto the source itself must still open its menu on drop.
- [ ] Complete the individual CAB stored/MSZIP/LZX/Quantum, multipart and damaged-member checks.
- [ ] Evaluate LZH, CHM, RPM, XAR and WIM readers through shared native adapters rather than separate application-specific paths.
- [ ] Validate UFS copied bytes, permissions, large/fragmented/sparse files, big-endian images, network/partition routes and malformed inputs.
- [ ] Accept NTFS raw/compressed fixture browsing and extraction on-device; validate nested directories, runlists, links and explicit unsupported-feature errors.
- [ ] Extend NTFS support for attribute lists, compressed/encrypted data, alternate streams, Windows ACLs and additional reparse formats.
- [ ] Accept raw/compressed HFS/HFS+/HFSX copied bytes and metadata on the handset; add an embedded-volume fixture.
- [ ] Extend HFS support for resource forks, extended attributes/ACLs, hardlink/alias resolution, self-extending overflow trees and additional classic encodings.

## Archive resilience and full disks

- [ ] Add malformed partition-table, EBR, backup-GPT and partition-restricted write checks.
- [ ] Upgrade the native archive engine and extend multipart integration to ZIP/7z.

## FAT deleted-file recovery

- [ ] Complete additional provider, gesture, low-space/cancellation and explicit handset hash checks in the recovery documentation. Fragmented reconstruction and exFAT remain outside the supported scope.

### Partial recovery follow-up

- [ ] Add a selected-set partial-recovery menu and optional reference-hash/format-aware integrity checks, keeping structural consistency distinct from original-content proof.

### LVM image follow-up

- [ ] Add independent LVM fixture coverage, checksum validation, alternate metadata-area selection and advanced allocation support before expanding scope.

### Large remote image browsing

- [ ] Accept shared archive scans and reduced SFTP range read-ahead on the handset; inspect the newly recorded transport causes and scan counters after reconnect/retry.
- [ ] Validate extraction separately and extend incremental enumeration to other image filesystem decoders. See [scope](image-directory-browsing.md).
- [ ] Correct background readability probes of mounted ext directories/directory links: the 03:49 UTC dump tries opening `sbbs/web`, `exec`, `docs` and `webv4` as file streams and logs directory extraction errors after a completed transfer. Confirm their actual link/directory metadata before adjusting probe behavior.

### Mutable image source handling

- [ ] Capture a missed pane swipe with the new retained gesture trace; previous transfer-warning floods rotated out the needed events.

### Operation progress and navigation acceptance

- [ ] Accept separate per-file and overall operation bars, including source preparation, verification, container publication, unknown sizes and fixed dialog sizing.
- [ ] Recheck intermittent Back failures with the refresh-generation fix and direct mounted-parent navigation; new retained Back logs should identify any remaining missed callback/slow parent lookup.

### UI responsiveness

- [ ] Accept responsiveness changes and inspect retained main-thread I/O/stall/render evidence during Back/swipe failures. See [investigation](operation-feedback.md#responsiveness-and-diagnostics).
- [ ] Use measured traces to prioritize remaining theme-icon disk reads, large grid/tree view construction and any remaining coordinator callback lock waits.

## BitTorrent virtual filesystem (downloads and resume confirmed; broader checks pending)

- [ ] Device acceptance for metadata lookup, folder browsing, multi-selection copy, unavailable peers, cache behavior and large-file progress/cancellation.
- [ ] Extend existing `.torrent` file opening with seekable piece reads so archives and disk images inside torrents can mount without downloading full files.
- [ ] Implement on-demand piece requests and seekable reads, with buffering/progress/timeouts for missing pieces.
- [ ] Allow archives and disk images inside torrents to mount through the existing seekable provider path without downloading entire files.
- [ ] Distinguish not-yet-downloaded, waiting for peers, verified cached and failed/corrupt data. Torrent piece-hash checks remain mandatory regardless of optional post-copy verification.
- [ ] Define cache quota/eviction, cache storage choice, resume/background execution, network/battery preferences and explicit upload/seeding controls. Do not treat cache eviction or unmounting as deleting remote swarm content.
- [ ] Validate hostile metadata paths, overlapping piece/file boundaries, missing peers, cancellation, v1/v2/hybrid torrents and local fixture swarms. A torrent is not a writable copy/move destination.

## Downloads organization by rules (requested; deferred)

- [ ] Add optional organization of the Downloads directory using user-defined rules.
- [ ] Define matching conditions such as filename wildcard/regex, MIME/file type, source and age, with configurable destination folders and rule order.
- [ ] Provide preview/dry-run, conflict handling and an operation report before enabling automatic moves.
- [ ] Reuse the shared provider/transfer system, preserving source files on incomplete or failed transfers and avoiding rules that repeatedly move the same file.
- [ ] Define manual versus automatic triggering, background execution, exclusions and grant handling before implementation.

## Read-only overlay, home-screen shortcuts and widget

- [ ] Extend confirmed floating-to-Home drag/drop to quick release/cancellation, remaining labels/icons and launch variants. See [launcher shortcut drag](floating-launcher-shortcuts.md).
- [ ] Extend confirmed launcher shortcuts to permission-return/fallback, remaining provider and overlay-lifecycle variants.
- [ ] Later provide the floating-window-style display as a home-screen widget, adapting its read-only navigation and launch actions to Android widget capabilities.
- [ ] Reuse the same URI/resource identities and MIME opening rules across normal browsing, overlay, shortcuts and widget; do not duplicate storage engines or bypass provider permissions.

## Shared disk cache (implemented; core device acceptance confirmed 2026-11-01)

- [ ] Check six-hour idle expiry, incomplete-staging cleanup after restart, unknown-size streams, expanded EPUB accounting, simultaneous active mounts/viewers and themed dialogs. See [policy and scope](disk-cache-policy.md).

## External write-back and cache expiry (implemented; core device acceptance confirmed 2026-11-01)

- [ ] Check unchanged opens, timestamp-only changes, source changes during staging, provider-specific save paths and light/dark presentation. See [write-back behavior and limits](external-edit-writeback.md).

## Latest status and next acceptance pass

- [ ] Confirm latest unrelated Internal storage breadcrumb removal before a remote connection.
- [ ] Confirm disk usage Parent above the initial scan root.

## WebDAV acceptance

- [ ] Complete the real-server protocol, transfer, range and failure checks in [WebDAV support](webdav-support.md).

## Storage API consistency pass (2026-10-26)

- [ ] Show actual extended attributes and POSIX ACL entries in Properties where
  the provider can read them; clearly identify unavailable metadata instead of
  presenting synthesized default Unix permissions as observed values.
- [ ] Accept an ext-to-ext metadata copy and copy-back using
  `filesystem-test-images/filesystems/linux/ext4-metadata-retry.img`, then inspect
  its modes, ownership, ACL, xattrs and contents against the adjacent JSON manifest.
- [ ] Investigate the second damaged
  directory block and whether unrelated existing corruption can be detected before
  publishing changes. Preserve the original `ext4-metadata-tests.img` as evidence.
- [ ] Complete metadata parity for network/SAF/archive providers, hardlink topology,
  behavioral flags and the volume-integrity work in [metadata integrity](metadata-integrity.md).
- [ ] Confirm metadata-preserving copies and failure/source-retention behavior on
  the handset. Existing ext integration coverage does not establish provider parity.
- [ ] Confirm ext2/3/4 create/copy/delete and internal file/nonempty-directory moves
  on the handset, including compressed images and SAF/network publication. Extend
  indexed-directory, full-volume, fragmented and damaged-journal coverage.
- [ ] Confirm result/removal persistence after app relaunch, including paused and
  cancelled torrent resume actions and ordinary-transfer restart source checks.
- [ ] Validate blank-space/file-parent drops after drag-hover navigation in grid/list
  and portrait/landscape modes, with multiple selections, archives/FAT, read-only
  targets, Virtual root rules and same-folder no-ops.
- [ ] Validate FAT relocation with less free space than the moved data, nonempty
  directories, long names/collisions, reopen, cancellation and failed publication.
- [ ] Extend safe native move support to other capable storage backends; copy/delete
  fallback can impose the same duplicate-space limit there.
- [ ] Device acceptance of the new contract and staged fallback paths.
- [ ] Move remaining mounted-source preflight disk I/O off the UI thread without
  weakening mounted-image protection; observed in the ZIP move dump.
- [ ] Finish remaining toolbar/New/catalog action dispatch, metadata preservation,
  access/publication guarantees and workspace policy. See
  [findings and remaining tasks](storage-api-consistency.md).

## Torrent verification checkpoint follow-up (2026-10-25)

- [ ] Recognize existing completed files and orphaned partial files; offer verify-and-reuse instead of automatically choosing another filename. Preserve existing data until the user explicitly authorizes replacement.
- [ ] Complete remaining real-device network/process interruption cases across
  storage providers; this single successful SFTP recovery does not cover all failures.
- [ ] Investigate the 22:06 UTC SFTP torrent checkpoint read failure, reported as
  “channel is not opened.” Later torrent writes continued; establish whether saved
  verification progress and retries recovered safely.
- [ ] Review Bt teardown ordering: the successful 21:39 UTC run logs closed incoming
  listeners and task submissions after executor shutdown.
- [ ] Move cache initialization and debug-dump preference lookup off the main thread;
  StrictMode reports both in the 21:39 UTC dump.

### Theme source integration (2026-11-02)

- [ ] Verify the updated folder/archive artwork on a handset. Build evidence: `build/theme-source-integration/app-build.log`.

### Ebook cover icons (2026-11-02)

- [ ] Verify real covers, theme fallback, MIME toggles and link/tree display on a handset. No connected ADB device; automated tests not run. Build: `build/book-cover-icons/app-build.log`. Details: [Resource icons](resource-icons.md#ebook-icons).

### Android file and URI registration — 2026-11-03

- [ ] Accept Android chooser/default-app selection, opaque content grants, binary-MIME filename fallback, native network/container/torrent URIs and standalone selected-viewer variants on a handset.

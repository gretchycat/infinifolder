# Project status

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-31. This is the current status reference. Checked implementation work is not automatically device acceptance. Dated audits, build counts and dump reports describe the revisions they examined.

## Confirmed on the handset

- Valid PDFs are user-confirmed working; the reported failure was corrupt input
  (2026-10-31). Checkpoint 15 moves the existing Android PDF backend into Scribe
  and adds serialized page work, bounded aspect-preserving rasters and explicit
  resource cleanup. Its updated UI and archive/remote opening need acceptance.
  Full PostScript/EPS, LaTeX, Office viewing and ANSI music playback are planned;
  new document fixtures do not enable those formats.

- Local MP3 seeking, including the previously slow copied songs, and linked gapless playback are user-confirmed working (2026-10-31, checkpoint 13). Checkpoint 14 adds exact high-resolution FLAC positioning, direct positional audio inputs and image common-source composition; its high-resolution/remote/image UI acceptance remains pending. The checkpoint records 683 cases with the same 30 known failures and 46 passing pipeline checks.

- Directory/container artwork (`.icon.<ext>`, `.icon`, `cover.<ext>`, `cover`), arbitrary sibling `.icon-<full filename>.png` icon overrides, and sibling PNG program-icon caching inside remote directories (`sftp://`, `smb://`, `ftp://`, `webdav://`) and archive/disk-image containers (`archive://`) are user-confirmed working (2026-10-27). See [Resource icons](resource-icons.md).

- Opening a Python file through the Termux association is user-reported working
  after the shared working-file fix. Save-back and conflict/interruption cases have
  not yet been separately confirmed. See [Termux links](termux-command-links.md).

- Cancelling the Ubuntu transfer and then tapping its `.incomplete` file successfully
  resumes it (2026-10-26). The earlier completion report was corrected by the user;
  full completion remains unconfirmed.
- Moving and Activity swipe controls are user-confirmed working in normal use (2026-10-26, 20:00 UTC follow-up). The dump records five individual history removals and an explicit `activity-cancel` torrent cancellation; the retained main-log window contains no new move events. This supports the user's confirmation without establishing every pause/restart/error variant. Full torrent/magnet download completion remains unconfirmed.
- Native FAT32 file moves are user-confirmed (2026-10-26, 19:24 UTC dump): eight files, 8,751,605 bytes, existing payload clusters preserved, committed SAF publication, zero warnings, about 1.41 seconds overall. Nonempty-directory moves, low-space edge cases, FAT12/16 and compressed FAT still need separate checks.
- Moves within one ZIP are user-confirmed (2026-10-26, 18:57 UTC dump): 46 files, 8,395,781 bytes, verified source/destination data, one committed archive publication, and COMPLETE with zero warnings. FAT32 native file moves were subsequently confirmed separately.
- Bottom activity status bar, including the rolling overall ETA, is user-confirmed working well (2026-10-26). Further status-bar refinement is deferred.
- BitTorrent and magnet-link downloads/resuming, Retry after failure, safe rechecking with missing/corrupt checksum caches, indefinite peer waiting, and simultaneous transfers are user-confirmed working (2026-10-26). Broader protocol variants and additional failure-injection cases remain separate checks.
- Floating-window lower-left/lower-right resize handles, including enlarged grab areas, are user-confirmed working (2026-10-26).
- Image-viewer Back returns to the directory; user-confirmed.

- Local/SAF and network transfers exercised; FTP works, SMB direct share opening is fixed, and WebDAV connection/browsing is user-confirmed working. Broader DAV mutation/range coverage remains server-dependent. The earlier FTP 502 unique-create rejection is historical.
- ZIP file/directory writes, FAT size-limit preflight, link creation and copy-through-link behavior work. FAT16 small-file copying and batched deletion are substantially faster and accepted.
- UFS/compressed UFS, NTFS, HFS/HFS+/HFSX and general RAR/ARJ/CAB browsing work within their documented subsets. Whole-disk partition browsing and VDI/LVM navigation work; this does not establish every variant or metadata case.
- FAT deleted-file copy-out and partial recovery work on the delivered safety fixtures. Recovery never proves original bytes merely from free allocation; fragmented reconstruction and exFAT recovery remain unsupported.
- Tree drops and edge scrolling, recent pane switching, two-finger rectangle selection, exit-selection menus and Virtual root links work. Duplicate-root breadcrumbs and missing startup tree links are confirmed fixed.
- Disk usage's current-directory graph is broadly accepted. Size scans recurse into actual directories, while the graph displays one level; archives remain leaf files.
- Background completion notifications, automatic successful-dialog dismissal and the debug toolbar button work. A large SFTP copy was independently reported byte-identical by diff.
- Latest activity-light correction is confirmed: green pulses follow reads; pending loading without read progress is steady dim green; the light returns idle when loading ends. Red file operations and amber verification have separate theme fields.

## Implemented; focused acceptance still needed

Checkpoint 16 moves EPUB structural parsing into Scribe's portable core and ZIP
resource expansion into Vault, retaining the host's full staging/cache leases,
WebView, themes and font/navigation controls. Synthetic EPUB 2/3, fallback and
invalid samples are in `filesystem-test-images/documents/`. Current evidence is
727 cases with the same 30 known failures and 64 passing device pipeline checks.
EPUB images/CSS, fragment links, themes/font size, back during loading and live
remote opening still need handset acceptance. EPUB 3 NAV-label interpretation
and lazy package serving remain future work; PostScript/LaTeX rendering is planned.

| Area | Remaining acceptance |
| --- | --- |
| Shared disk cache | 200 MiB total, separate file limit, LRU before prompting, active leases, copy/raise choices, six-hour expiry, restart cleanup and themed dialogs |
| External editing | Real editor handoff/return; explicit Finish editing and save; unchanged files; source conflicts; save-as/copy/overwrite; interrupted sessions across providers |
| Navigation | Latest unrelated Internal storage breadcrumb removal before remote connections; disk usage Parent above its initial scan root |
| Menus and targets | New templates, wildcard/regex, sort directions, pins/hover and selected-set drops across backends/orientations |
| Transfer UI | Stable sizing, per-file/overall and verification progress, remembered verification switch, queues/cancellation and denied notification permissions |
| Activity history | Terminal results and supported manual replay requests survive relaunch; removals stay removed; restored results do not replay notifications; older-result paging |
| Storage coverage | Low-space/interrupted compressed-container writes, FAT12/FAT32 batches, retained-file hashes and format-specific edge fixtures |

Ext2/ext3/ext4 now support a guarded image-write subset: create, copy in, content
writes, deletes and inode-preserving internal moves. Ext3/4 journal recovery and
transactions run in private batches. Unsupported filesystem features stay read-only.
Focused checks pass; handset acceptance is pending. See [ext support](ext-image-writes.md).

Ordinary copy verification is remembered and optional; moves and recovery retain required checks. Live VM copying is best effort: source changes refresh caches and warn, but do not provide a coherent snapshot.

## Remaining engineering work

1. Complete backend parity: per-entry mutation capabilities, mounted cache invalidation, shared action dispatch, device/session cancellation and ownership, bounded viewer materialization/direct seekable integration, and mounted case/link semantics.
2. Preserve more incoming metadata where destinations support it: permissions, ownership, ACLs, extended attributes and hardlinks; report unsupported semantics consistently. Native ext internal moves retain the existing inode and its metadata.
3. Add durable ordinary operation queue recovery, general pause/resume/retry and richer collision choices. Terminal history and torrent resume sessions persist; active ordinary work currently survives Activity changes, not process death.
4. Extend format coverage: multipart ZIP/7z, maintained native archive engine, NTFS/HFS/UFS subset gaps, advanced/checksummed LVM, malformed/backup partition tables and other archive readers. See format-specific checklists.
5. Expand FAT recovery only with explicit safety evidence: fragmented files, deleted directory reconstruction, exFAT and selected-set partial recovery.
6. Revisit measured performance: non-ext image directory enumeration, large grid/tree rendering, UI-thread icon reads and remaining cache/session lifetimes. Accepted FAT16 improvements do not establish all filesystem/backend combinations.

## Deferred requested features

Most-used sorting; broader torrent compatibility and seekable piece reads; rule-based Downloads organization; remaining floating browser and home-screen shortcut handset acceptance; later floating-view widget. NFS and cloud catalogs do not yet have active providers. WebDAV is user-confirmed working.

## Documentation and validation

See the [documentation index](README.md), [task list](feature-todo.md), [architecture](architecture.md) and [roadmap](roadmap.md). Recent implementation builds succeeded and opened the APK with xdg-open. This documentation synchronization adds no build or automated test run. Historical test totals are revision-specific, not a claim that the current tree has been retested.


### Torrent verification and partial-file opening (2026-10-25)

- The latest device dump shows startup verification reading existing SFTP data when the three-minute no-download watchdog cancelled the client. This was not evidence of unreachable peers: verification had not finished.
- Verification reads now count as activity. The general transfer timeout measures inactivity rather than total elapsed time, so healthy long transfers can continue.
- A bounded 1 MiB read-ahead buffer replaces individual 16 KiB remote reads during sequential piece checks; writes invalidate that buffer. Torrent hashes remain the authority for accepting existing pieces.
- Startup checks report read progress and log `torrent.resume.checking` checkpoints.
- `.incomplete` has a dedicated MIME classification and Android VIEW associations. Tapping a saved destination retries its persisted torrent session; an active session opens the operations display. Manual retries bypass the automatic once-per-process retry guard.
- Files without matching saved torrent metadata receive an explanation rather than being treated as complete files. Moving partial files outside the recorded destination does not automatically migrate their session metadata.
- Pending device confirmation: resumed SFTP verification completes, downloads continue, and partial-file opening routes correctly from the browser and Android file picker.

- Torrent verification now supplies separate piece counters for both initial copies and resumed downloads. The operation dialog uses them for a determinate verification bar and a pieces/percentage label, without treating pieces as bytes. Download updates switch back to file-byte progress.
- The status message reserves three lines using TextView measurement instead of a separately sized scroll wrapper; the redundant empty “File progress” label is removed. Device layout confirmation is pending.

- Incoming torrent data now also enters a bounded 32 MiB write-through memory cache after successful destination writes. Bt's existing hash verifier reads these received bytes from memory, avoiding immediate SFTP/disk rereads. Overlapping writes invalidate older ranges; least recently used ranges are evicted and safely fall back to destination reads. No hash checks are skipped. Earlier-session bytes still require destination verification; this cache does not establish durable storage across restarts.
- `torrent.destination.client-stop` records verification bytes served from memory and actual destination bytes read for device confirmation.


### Persistent torrent verification checkpoints (2026-10-25)

- Direct torrent downloads create `filename.incomplete.cache` next to the partial destination, where the destination backend supports sibling files and usable size/modification metadata.
- The compact sidecar records the torrent identity, file path, piece size, verified-piece bitmap, destination fingerprint and a checksum protecting the checkpoint itself. It stores verification results, not another copy of the downloaded content.
- Startup verification reuses matching clean checkpoints through a custom Bt verifier. New incoming pieces always undergo the normal hash check using the memory cache; rechecked destination pieces also enter subsequent checkpoints.
- A dirty checkpoint is published before writes. The destination is flushed before publishing clean progress, after every newly verified piece (download or file recheck), and again when the client stops. Rewriting a piece removes its previous verified status until it passes again.
- Missing, invalid, dirty or metadata-mismatched sidecars cause a destination recheck. Older internal resume bitmaps remain hints for which ranges to inspect, and cannot independently bypass hashing. Pieces spanning other selected files cannot be trusted through a single file's checkpoint.
- Unsupported/unwritable companion storage falls back to rechecking. If an existing clean checkpoint cannot be invalidated, further writes fail rather than risk retaining stale trusted state. Unrelated files using the companion filename are preserved.
- Companion state is removed after a successful torrent data transfer, before final filename publication. Interrupted transfers retain it. An abrupt process stop during writes can require rechecking because its last checkpoint is dirty.
- Change detection uses backend-reported identity, size and modification time. External changes that preserve all those values cannot be detected without rereading the data; server write acknowledgements/flush semantics determine durability for remote storage.
- User confirmed persistent torrent verification checkpoints appear to work. Remaining focused checks: changed/missing/corrupt companions, interrupted writes and completed-download sidecar cleanup.


### Checkpoint corruption and failure checks (2026-10-25)

- Extracted the persistent codec/lifecycle into directly tested classes. Focused tests cover malformed/truncated/bit-flipped state, stale identity/metadata, dirty checkpoints, flush/invalidation failures, actual piece hash failures, memory-cache overlap/eviction and companion cleanup that preserves download data.
- Fixed a lost-acknowledgement case: a failed clean-sidecar write may have committed remotely, so the next data write now always invalidates that possibly clean checkpoint first.
- Oversized junk sidecars are preserved and disable checkpoint caching instead of blocking downloads. Unknown transport errors remain conservative.
- See [test coverage and remaining device checks](torrent-checkpoint-safety.md). Metadata-preserving corruption still requires an explicit data recheck; fast resume cannot prove unchanged bytes without reading them.

- Validation result: 87 targeted and related regression tests passed with zero failures/errors/skips. Debug APK build succeeded and was opened with `xdg-open`; real-device failure injection remains pending.


### Transfer ETA (2026-10-25)

- Shared operation snapshots now estimate time remaining from smoothed progress samples using a monotonic clock. Copy/move/archive/recovery operations, external progress producers and torrent resumes use the same estimator.
- Known-size transfers estimate remaining bytes; verification uses its own piece/byte progress and is labelled `Verification ETA`. Unknown byte totals can use item progress when available. Queued, unknown, inactive and untimed publication phases show waiting/estimating instead of a false countdown.
- Torrent producers report missing verified bytes so resumed download estimates exclude data already present. Existing overall/per-file progress remains independent of the ETA calculation.
- The transfer dialog reserves full-width rows for rate, ETA and elapsed time; foreground transfer notifications include the same ETA. Handset acceptance is pending.


### Latest device dump: verification finished, swarm lost (2026-10-26)

- `infinifolder-debug-20261026-004844-576.log` shows the Ubuntu torrent rechecking all 24,729 piece slots from 00:40:55 to 00:45:27 UTC. It retained 2,732 verified pieces (716,177,408 bytes). This run loaded zero trusted checkpoint pieces and used destination reads.
- All four connected peers disconnected by 00:42:59 UTC, before verification finished. The session then stayed at zero peers and zero newly downloaded bytes until its no-data timeout at 00:48:28. This was a post-verification discovery failure, not an incomplete verification scan.
- Direct transfers now defer the custom tracker announce until startup verification finishes, publish the completed verification checkpoint immediately, and revisit stalled discovery while respecting tracker-provided announce intervals. New `torrent.resume.ready` and `torrent.peer.refresh` events identify this transition.
- Shared operation progress now logs totals, remaining bytes, rate, ETA and verification counters at most once every ten seconds. The supplied screenshot shows directory browsing, so this dump does not establish transfer-dialog ETA acceptance. Device confirmation of the discovery fix is pending.


### Torrent progress interface correction (2026-10-26)

- The 00:54 UTC dumps show continued torrent destination writes, exceeding 64 MiB of new session data; their screenshots show the directory rather than the transfer dialog.
- Startup verification and downloading now produce distinct status phases. Once verification finishes, ordinary piece hash callbacks no longer replace the download message/bar with verification counts.
- Resumed downloads now populate expected totals and per-file progress, credit existing verified data in cumulative progress, and throttle UI reports. Initial copies also credit reused data and stop switching back to verification for each incoming piece.
- Notification progress uses piece counts during startup verification. Debug dumps include the rendered status/bar/ETA values and operation snapshots, and capture the progress dialog window when it is still visible.
- Handset confirmation of stable messages, bar percentages and ETA remains pending.


### Cached restoration wording (2026-10-26)

- The retained 00:53:58 dump includes the 00:51:16 restart loading 2,732 trusted checkpoint pieces. Its record walk ran from 00:51:19.565 to 00:51:20.141 UTC, about 0.6 seconds; this is distinct from the earlier multi-minute destination-data recheck. Download writes resumed at 00:51:25.
- Transfer status now explicitly says it is restoring saved progress and reports the trusted cached-piece count. The bar is labelled “Resume records” on that path. When no checkpoint can be reused, status explicitly says existing data needs checking. Cached restoration retains separate progress/ETA without implying cached bytes are being hashed again.


### Every-piece checkpoint persistence (2026-10-26)

- The 01:37/01:38 UTC dumps confirm a real destination recheck with zero trusted pieces. App updates restarted the application during long scans. Newly computed startup verification results had previously remained in memory until the scan finished.
- Every newly successful piece hash now flushes the destination and publishes the verification result immediately, both for downloaded pieces and existing-file verification. Already trusted cached results are restored without rewriting them. Failed hashes are never marked valid.
- Removed the previous 32-piece/ten-second download checkpoint batching. `torrent.checkpoint.loaded` includes a rejection reason, and startup check logs include `savedPieces` to show persistent progress accumulating.
- Distinguished checkpoint-write preflight rejection from an uncertain write acknowledgement: foreign or unavailable companion files cannot create phantom clean state, while an uncertain committed clean payload still requires invalidation before data writes.
- During startup checks with no overall byte totals yet, the overall bar is indeterminate and its caption explains that download progress follows verification, replacing the misleading zero-of-one item display.
- Validation: 95 targeted and related regression tests passed, zero failures/errors/skips. Includes immediate first-piece persistence in both paths, interrupted-scan progress, failed hashes, preflight rejection and cached restoration without redundant writes. A physical interruption while the sidecar itself is being written can still damage it and require a conservative recheck.


### Finish later and shutdown checkpointing (2026-10-26)

- The 01:56–01:58 UTC dumps show a successful cached restart at 01:57:41 restoring 7,185 pieces; that resumed session reread zero destination bytes. After cancelling an active transfer at 01:58:01, the next restart loaded zero trusted pieces. Interrupted shutdown checkpointing can leave the companion dirty, requiring rechecks.
- These dumps identify an installed APK updated at 01:39 UTC, earlier than the per-piece-persistence APK built at 01:48 UTC. They do not validate the latest per-piece changes.
- Added a lifecycle fence: queued data writes recheck closing/cancellation under the destination I/O lock. Shutdown rejects later verification callbacks, drains active writes, temporarily clears caller/shutdown interrupt flags for final checkpoint publication, and then restores the interrupt state.
- Resumable torrent operations now say **Finish later** in the transfer dialog and notification, and are shown as paused in activity history. Their partial files and saved verification state are retained. Other operations retain Cancel. The existing automatic-resume-on-app-relaunch policy is unchanged; tapping the partial file also resumes it.
- Resumability is explicit operation metadata rather than inferred from changing status messages. Ordinary cancellation is no longer labelled as an operation failure by the coordinator.
- Validation: 102 targeted/regression tests passed with no failures/errors/skips, including shutdown interruption, queued-write fencing, checkpoint preservation between sessions and stable Finish later labels. Handset acceptance remains pending.


### Floating-window dismissal and edge dragging

- Floating windows now dismiss on an outside touch or after losing window focus. Outside touches remain available to the underlying app, including launcher page swipes.
- Drag coordinates now use top/left gravity consistently, start from the rendered window position, and clamp/rebase movement at display edges. This fixes reversed horizontal coordinates and accumulated off-screen drag distance at the right edge.
- Device confirmation of edge reversal, outside-tap dismissal, and launcher page switching is pending.

### Partial-download discovery

- Virtual root now includes Partial downloads, a persistent catalog populated during normal directory browsing and disk-usage listings. Opening the catalog does not scan or connect to recorded sources.
- Recognizes .incomplete, .!qB, .!ut, .crdownload, .aria2, .part.met, .part and .partial candidates. Generic suffixes are explicitly ambiguous; detection does not establish resumability.
- Existing InfiniFolder torrent sessions can resume through the catalog. Foreign partial formats currently offer browsing their original location and removing the catalog record; importing their download state is pending.
- Successful directory listings prune missing entries. Deletion/rename operations invalidate old records.
- Possible future imports: aria2 publishes its control-file layout (https://aria2.github.io/manual/en/html/technical-notes.html); original source information is still required. Matching torrent metadata allows independent piece verification. Metalink supplies source URLs and optional piece hashes (https://www.rfc-editor.org/rfc/rfc5854.html). Browser download state is separate from partial data, for example Chromium's download database (https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/history/core/browser/download_database.cc).

- Floating toolbar simplification: removed the close button now that outside touch/focus loss dismisses the window. Replaced the text expand glyph with a theme-tinted window-and-arrow vector icon, labelled “Open in file manager” for accessibility and tooltips.

- Floating-window geometry is now persisted between instances and app restarts: rendered position is saved after dragging and before dismissal, together with window width/height. Saved values use density-independent units and are constrained to the current display when restored. Opening another location in an existing window retains its geometry.

- Floating toolbar is now 39 dp high (75% of its former 52 dp). Location text is bold and vertically centered, with its font size derived from toolbar height. The open-in-app button scales with the reduced bar height.

- The floating toolbar now shows the current container icon to the left of its name. Artwork uses the shared resource icon resolver (including custom/themed folder icons), updates during navigation, and resolves off the UI thread. Both icon and name act as window drag handles.

### Winamp skin import and floating chrome

- Inspected all four classic skin fixtures under `Winamp/`. Import now uses bitmap palettes, selected-row colors, font category and background-derived light/dark state; file labels consume theme colors.
- Classic title-bar bitmap textures now render in shared toolbars. Floating windows additionally render bitmap border strips with integer scaling and no smoothing.
- Skin import runs off the UI thread, offers an Android file picker, stages one durable private archive, and preserves the skin name on restart. Invalid imports no longer apply a fabricated fallback theme.
- Details and remaining compatibility limits: [Winamp skins](winamp-skins.md). Device appearance confirmation remains pending.

- Interface Theme now includes a persistent Skin image scaling choice: integer/crisp (default) or smooth/fractional with filtering. The shared bitmap chrome renderer follows this preference.

- Floating-window pinch zoom now persists its icon size independently of the main file manager. New instances restore the last floating scale (32–120 dp); before a floating scale is saved, the main browser's icon size supplies the initial default.

### Cached resource artwork and per-file icons

- Added shared memory/managed-disk artwork caching, with cached-first worker publication before source refresh. Main application/link cards, floating cards/location headers and shortcut creation share resolved artwork.
- Any listed resource can use a sibling `.icon-<full filename>.png`; exact filenames include extensions. Explicit sidecars take precedence over generated icons and are never overwritten by cache refresh.
- Installed application icons now track source update time and revalidate periodically. Resource artwork signatures include source/sidecar metadata and icon-theme identity, with a one-hour on-use refresh interval.
- Details and device acceptance checklist: [Resource icons](resource-icons.md).

### Icon retrieval latency correction

- Removed duplicate main-grid application/link icon resolution and automatic parent-directory scans from ordinary icon refresh.
- Cached PNG reads use independent workers; PNG encoding/persistence no longer delays publication and has a bounded best-effort queue. Stale cache callbacks cannot replace newer source results.
- Reuses known directory artwork listings, including negative results; uses embedded shortcut images before resolving targets. Queued obsolete floating-card work is discarded before starting.
- Added `artwork.resolve.slow` timing diagnostics. Build validation is complete; device performance comparison remains pending.

### Application icon display order

- Visible main/floating cards now explicitly sequence cache lookup/publication before authoritative retrieval, and queue resource-cache persistence only after applying the returned bitmap.
- Installed-app icon sources use dedicated workers. The shared path skips the provider's redundant disk-cache read/write and no longer builds app metadata solely to retrieve an icon.
- Build validation completed; device timing confirmation remains pending.

### Icon cache location correction

- Corrected the earlier interpretation: generated PNGs now go beside the resource as `.icon-<full filename>.png`, after display. Resource-artwork caching no longer creates private managed-cache UUID PNGs.
- Uses exact sibling names from ordinary listings (direct exact-path check for local standalone resources), with no extra folder scans or alternate image-cache searches.
- Existing custom PNGs are preserved. Provenance records allow generated-image refresh, with provider replacement support required. Generated PNGs do not themselves create nested cache PNGs.
- Added visible debug-log events for successful writes, failed writes/reads, unsupported refresh, queue deferral and cleanup failure. Read-only/virtual locations without physical sibling directories cannot receive PNG files; real app-link files can.
- Updated [Resource icons](resource-icons.md) with the current source-file policy and limitations. Device confirmation remains pending.

- Restricted automatic sibling icon cache creation to program artwork (application targets/links and actual APK package icons). Ordinary files, images and folders no longer create PNG cache files; manually supplied per-file icons still work. Existing PNG files are preserved.

### Selection overlay visibility

- File-card selection tint, outline and check mark now render in the icon container's foreground, after all child artwork and badges. Asynchronous cached/source icon replacement cannot cover or remove them.
- Selection uses theme colors, with the artwork tint kept translucent and the check mark given a contrasting foreground. Main grid and list cards share the correction.
- Device confirmation remains pending.

### Torrent checkpoint loss after Finish later

- The 03:42/03:50 dumps show pause-time checkpoint failure after at least 6,748 saved pieces, followed by an unusable dirty checkpoint and a later rebuild that successfully reused 626 pieces.
- Fixed clean publication with missing destination metadata: it now retains the previous sidecar and reports failure, rather than publishing dirty state over good verified progress. Dirty publication before actual destination writes remains mandatory.
- Preserved backend causes/messages for checkpoint read and destination-stat failures. Existing invalidated cache state still requires verification; download bytes are not deleted or reset.
- APK build validation and device pause/resume confirmation are tracked separately; handset acceptance is pending.

### Selection state shadowing correction

- Found why the foreground change alone did not show selection: within Android View `apply` blocks, unqualified `isSelected` resolved to the View's default false state instead of the FileEntryCard constructor flag.
- Renamed the card flag to `selectedItem`, so the icon foreground, filename highlight and list-row highlight now use the actual selected URI state. Asynchronous artwork remains beneath the foreground overlay.
- APK compilation checks the change; handset confirmation remains pending.

### Hide transfer dialog on outside touch

- The nonmodal transfer dialog now requests outside-touch events explicitly and dismisses itself when one arrives. It records the window as hidden, so progress updates do not reopen it.
- File operations continue running; the toolbar activity indicator/Unhide control reopens their progress. Outside dismissal is logged as `operation.dialog.hidden reason=outside-touch`.
- Device confirmation remains pending.

### Torrent verification latency: 04:16 dump

- Confirmed that persisted verification is reused: recent sessions loaded 1,020, 1,131, 1,324 and 1,950 trusted pieces. The latest cached record walk passed 6,560 records in about 0.104 seconds; subsequent SFTP reads plus per-piece publication covered roughly 30.75 MiB in 79 seconds.
- Reduced repeated checkpoint I/O while retaining every-piece persistence: read-only verification reuses its baseline fingerprint, real destination writes invalidate that baseline, and seekable backends retain the checkpoint device with padded 4 KiB allocation. Streaming fallback remains.
- Added cumulative checkpoint save count/time to verification logs. Ownership checks and safe-recheck behavior remain in place.
- Debug APK build succeeded and opened through xdg-open. Device performance comparison and pause/resume confirmation remain pending.

### Torrent checkpoint cleanup ordering

- Removed companion deletion from the downloader's successful-data-transfer shutdown. It now saves a final checkpoint and retains it while awaiting publication.
- Router cleanup is armed only for successful transfer results and runs after successful completed-file rename/publication. Automatic resume uses the shared rename path. Failed rename, pause, cancellation and failed verification preserve the cache.
- Cleanup failure is logged without reversing a completed download. Device completion/rename-failure confirmation remains pending.

### Cold sibling icon PNG decoding

- Found a cold-cache-only decoder bug: BitmapFactory.Options starts with inSampleSize=0, and the cache's sample-size arithmetic divided by it. The read failed before PNG decoding; memory hits did not take this path.
- Sampling now explicitly starts at one and uses bounded long arithmetic for the requested target. Added `artwork.cache.loaded` with filename, byte count and decoded dimensions to confirm real file reads in subsequent dumps.
- Device cold-start confirmation remains pending.

### Program-link display names with cached artwork

- Cached PNG hits no longer return the raw link filename as the final card label. The source worker resolves the link's saved display-name override, then the target application's label, with a filename stem only as fallback.
- PNG publication still happens first; reading link metadata does not wait for application icon retrieval. Parsed link metadata is reused and fed back into the main browser's shortcut cache for subsequent renders.
- Shared resolution covers main/floating cards and home shortcut creation. Device confirmation remains pending.

### Program-icon latency: 04:41 dump

- PNG decoding is confirmed working in the latest dump. Remaining delay includes alphabetical loading across the folder, oversized decoded cache images and work surviving same-directory rerenders.
- Replaced the visibility estimate with measured global viewport/card intersection and no offscreen buffer. Unmeasured viewports no longer schedule icon probes.
- Cached PNGs are resized to display dimensions before memory caching; digest formatting no longer allocates 32 Formatter instances per image. Same-directory rerenders now invalidate old artwork jobs.
- Added visible-probe counts and cache-read elapsed time for the next device comparison. Build/device validation are separate; acceptance is pending.

### Program-icon latency: 04:45 dump

- Latest logs confirm visible-only probes (28 initial cards, later rows deferred) and successful PNG reads, but individual small local reads still take tens of milliseconds and scrolling can leave older queued requests ahead of the viewport.
- Found repeated local-read overhead: ordinary external source files entered the private DiskCache pin lookup, which canonicalized every managed entry. Source reads now bypass private-cache initialization/locking when outside app cache storage; managed leases first reject external paths and identify their entry by its relative directory name.
- App-internal directory roots are discovered once per LocalStorageProvider instead of querying external files/cache roots for every icon/link read. Canonical path boundary checks remain in place.
- Main-card queued cache/source requests that have scrolled out of view are deferred until visible again. Added queue/read/UI-publication timing to slow cached-image display and read/decode/provenance timing to PNG loads.
- Build validation is separate from on-device performance confirmation; device comparison remains pending.

### Private Partial downloads links and navigation

- Changed discovery results from borrowed original-file entries into owned link entries backed by app-private `files/partial-download-links/`. Existing registry records materialize as links automatically; display names retain their original download names.
- Opening/listing/icon rendering uses private records only. Added an explicit private-root navigation route and names, and restricted generic storage-regrant fallback to content URIs, preventing this virtual collection from opening an Android folder-grant picker.
- Selecting an incomplete torrent link resolves its original source reference for resume. Forget/delete removes the collection link only. Passive discovery from shared directory and disk-space listings, pruning after completed source listings and offline retention remain in place.
- [Private collection details](partial-download-discovery.md). Device DCIM-prompt, discovery and restart acceptance remain pending.

### 2026-10-26 — preserve verified torrent pieces across active-download restart

Replaced whole-cache invalidation during destination writes with a durable,
checksummed piece invalidation journal. Restart can reuse untouched saved pieces
rather than hashing the entire partial download again. Data and checkpoint flush /
close paths remain in place. See `torrent-checkpoint-safety.md` for the exclusive
active-file ownership assumption, legacy cache fallback, and pending device check.

### 2026-10-26 — magnet metadata failure diagnostics

Added metadata request/cache-hit/lock-wait/start/waiting/received/timeout/stop/failure
logging, identified by info hash, with elapsed times and tracker scheme/host/port
summaries (no tracker path/query tokens). Waiting is recorded every 15 seconds on
the existing background worker. Bt tracker, peer, and metadata debug messages are
now captured. Bt errors retain bounded, IP-redacted nested cause stacks instead
of only the outer exception; this should expose the cause of incoming-listener
startup failure while another torrent is active. Retrieval behavior and the
three-minute timeout are unchanged. Device magnet retry and new dump pending.

### 2026-10-26 — flexible concurrent magnet networking

Magnet metadata now uses the same IPv6 wildcard / dual-stack network configuration
as downloads, including the Android local discovery workaround and tracker timeout.
Each runtime selects an available TCP/UDP port and retains an in-process reservation
until shutdown; DHT uses that runtime's selected port. This avoids every independent
client competing for the default listener port. Another process can still claim a
probed port before Bt binds it; nested bind diagnostics remain enabled.

Metadata requests are deduplicated by info hash. Duplicate callers share success or
failure rather than serially launching another three-minute lookup. Different hashes
no longer wait behind one global monitor. Metadata clients explicitly release their
runtime on success, failure, timeout, or interrupted waiting. The browser still uses
its existing background loading flow. Device validation with an active Ubuntu
transfer and a second magnet remains pending; debug APK builds completed.

### 2026-10-26 — correct UDP tracker replies for IPv6

The latest device dump confirms the earlier bind/address-family errors are gone,
but metadata timed out. Located Bt 1.10's IPv4-only UDP peer-list decoder. Added a
UDP adapter with transport-correct peer decoding, both address families, bounded
requests and response validation, URLData support, socket cleanup, and response
counts in diagnostics. HTTP/HTTPS tracker factories are retained. See
`magnet-networking.md` for evidence, protocol references, and pending device retry.

### 2026-10-26 — fix tracker adapter injection regression

Device dump 064039 showed Guice's wildcard-map mismatch preventing magnet and
Ubuntu-resume runtime startup. Moved the injection provider to Java with the exact
invariant tracker factory map required by Bt. UDP code and its protocol behavior
are unchanged. Device startup/metadata retry pending; the failed injector never
reached the UDP decoder, so its browsing fix is not yet confirmed.

### 2026-10-26 — encrypted torrent handshake buffering

Found an upstream MSE-to-pipeline double-decryption path affecting payload already
received during encryption negotiation. Added a pipeline factory that preserves the
existing decrypted prefix and applies the original cipher only to subsequent bytes.
Encryption remains enabled. Added per-connection handshake, self-connection,
extension-support, and metadata-exchange diagnostics. Exact self peer-ID matches are
rejected. See `magnet-networking.md`; device magnet retry is still pending.

### 2026-10-26 — simultaneous operations and live Activity overview

Removed the default two-to-four-operation worker cap; independent submissions use
elastic workers while existing same-file conflict locks are preserved. Replaced
implicit oldest-operation display with a live list of active transfers and recent
results, named destinations, row-to-detail navigation, and accurate running/waiting
counts. Multiple-operation foreground notifications summarize active work and open
the overview. Automatic completion dismissal is retained. See
`concurrent-activity.md` for scope and pending device checks.

### 2026-10-26 — unknown torrent pieces and honest empty-start status

Device log 070914 confirms magnet metadata retrieval (five files in 16.8 seconds).
A second metadata lookup was hidden behind an incorrect saving-verification message.
Clients now reuse cached metadata with the magnet tracker list preserved. Startup
verification explicitly skips absent piece ranges; a new download enters the peer
connection phase without a verification bar. Unset bitmap bits mean unknown, and
only validated data is marked verified. Cached resume behavior is retained. See
`torrent-checkpoint-safety.md`; new empty-start status validation remains pending.

### 2026-10-26 — purge completed Activity records

Added Clear completed to the live overview and Remove from list for individual
completed details. Coordinator cleanup removes completed history/bookkeeping and
refreshes listeners. Active, failed, and paused operations and transfer data remain.
Debug APK build validation; device confirmation pending.

### 2026-10-26 — multi-file torrent storage boundaries

Log 072102 confirms metadata retrieval, empty-start status, and concurrent Ubuntu
progress. Located an initialization failure caused by zero-capacity skipped files,
not lack of peers. Skipped files now retain real virtual sizes and bounded, sparse
neighboring data needed for cross-file piece hashes, without creating those files.
Stopped download engines now fail promptly instead of waiting for the no-data
timeout. Build succeeded and APK opened; successful multi-file transfer remains
pending device retry. See `magnet-networking.md` for details and memory bounds.

### 2026-10-26 — per-operation rates and torrent upload/peer statistics

Added a dedicated transfer-rate field to every running Activity row. Torrent
statistics are sampled per runtime and carried through manual/resumed transfer
progress: connected peer count, recently served connected peers (10-second payload
window), upload rate, and uploaded bytes. Detailed dialogs show those values in a
fixed-height themed area. Logs include per-operation peer/upload metrics. Debug
build validation only; device confirmation of rate/count display and real upload
activity remains pending. See `concurrent-activity.md` for measurement semantics.

### 2026-10-26 — preserve discovery when metadata hands off to download

Latest log confirms 13 saved pieces restored correctly, but the magnet download
connected to zero peers. Corrected UDP fallback routing (it had reached HTTP-only
OkHttp), independently registered public UDP tracker sources instead of consulting
only the first successful aggregate tracker, and carried in-memory connected peer
hints from metadata retrieval into downloading. Private tracker behavior is retained.
Metadata-only teardown now permits normal completion before explicit shutdown.
APK built/opened; device confirmation of resumed data arrival remains pending.

### 2026-10-26 — torrent retry and indefinite peer waiting

Added exact-destination resume metadata to operation snapshots, Retry in failed
torrent details, and Resume on paused results. Existing active destinations are
deduplicated. Added a remembered global Wait indefinitely for peers checkbox in
those details; metadata and post-startup data waits honor it dynamically while
cancellation, engine failures, and storage errors retain their behavior. Waiting
status now distinguishes zero peers from connected peers with no incoming data.
See `concurrent-activity.md`; device validation remains pending. The newest dump
available during this change was still 073706, so there was no newer working-run
log to confirm the user's success report independently.

### 2026-10-26 — floating-window corner resizing

Added triangular themed resize handles over the lower-left and lower-right corners.
Dragging keeps the top and opposite side anchored, constrains the window to the
display, and reflows grid columns without changing the remembered icon scale.
The handles overlay the existing grid, so the default window size and available
content area are retained. Each visible 32dp triangle has a larger 48dp triangular
grab area; the unused half of the touch square passes touches through to the grid.
Size and placement use the existing persistent window
preferences; resizing preserves scroll position and loading/error feedback.
Debug APK build succeeded and opened with xdg-open. The user subsequently confirmed
the resize handles work well, including the enlarged grab areas.

### 2026-10-26 — torrent and floating-resize handset confirmation

The user confirms BitTorrent and magnet links are downloading and resuming correctly,
and that the floating-window resize handles work well. This is handset acceptance
of those normal workflows; specific Retry failure cases, indefinite peer waiting,
corruption/interruption scenarios, and broader protocol compatibility remain focused
checks. No new build or automated tests were needed for this documentation update.

### 2026-10-26 — torrent recovery and concurrent-transfer acceptance

The user additionally confirms Retry after failure reuses partial files and saved
checksums, missing/corrupt checksum-cache recovery works, indefinite peer waiting
works, and simultaneous transfers work. These close the four suggested acceptance
items. Detailed Activity row routing/rates and backend conflict behavior remain
separate checks. The next recommended engineering work is shared storage capability
and action consistency across local, SAF, network, archive, and disk-image providers.

### 2026-10-26 — shared storage capabilities and random-access consistency

Implemented common final capability restrictions, resource-specific creation
guarantees, copy-through-link reporting, root/cache move restrictions and explicit
torrent read-only features. Removed fabricated SAF Unix modes. Added directory
TRANSFER_TARGET reporting and shared direct/router handle validation. Corrected
local size updates, SAF growth, flush/close error propagation, byte-range checks,
range-cache invalidation before mutation, and direct WebDAV/torrent MIME inference.

Random-access producers now warn before using unsupported destinations. The user
can choose another target, explicitly allow full-file staging and a streamed copy,
or cancel. Known limitations prompt before destination creation/batching; unknown
handles are probed. Existing partials are retained, streaming targets avoid final
rename requirements, and browsing/other operations continue while a choice waits.
Debug builds succeeded and opened with xdg-open, including the review corrections.
Handset acceptance remains pending. See
[contract, backend matrix and task list](storage-api-consistency.md).

### 2026-10-26 — common locations and unwanted Activity-window reopening

Added persistent common folders, automatic bounded usage history, Settings add/
rename/replace/remove controls, and folder-picker shortcuts. Random-write warnings
now suggest concrete common locations and saved connection roots, checking capability
reports asynchronously and marking prior successful direct writes. Current file
operations remain authoritative for actual permissions. See [details](common-locations.md).

Removed automatic Activity-window opening from status snapshots and ordinary
onResume. Browser pause hides overview/detail windows while transfers continue;
explicit activity-light/menu/notification requests remain supported, including
one-time notification handling across resume. This addresses windows reappearing
after closing a viewer. Build validation and handset acceptance are separate;
confirmation of these new workflows remains pending.
The combined debug build succeeded and the APK was opened with xdg-open.

### 2026-10-26 — bottom background-activity status

Moved hidden-operation feedback below the file/tree panes as a compact themed status
bar. It reports action count, processed/total reported items, combined current rate
and aggregate overall percentage. The row opens Activity. It is removed from layout
when idle or an Activity window is visible, returning its space to browsing. Shared
summary/rate formatting handles queued work, stale rates and unknown totals without
combining byte and item units. See [aggregation details](concurrent-activity.md).
Debug build succeeded and opened with xdg-open; handset acceptance remains pending.

### 2026-10-26 — rolling one-minute overall ETA

Added ETA to the bottom activity strip using remaining payload bytes and a
time-weighted 60-second aggregate transfer-rate history. Early estimates use the
available history after warm-up. Stalls count, stale samples expire, torrent cache
restoration is excluded, and history survives viewer/Activity changes within the
running process. Unknown work shows estimating; byte-complete work shows finishing.
The strip wraps to separate counts/metrics rows when needed and still occupies no
space when unused. See [calculation and remaining checks](concurrent-activity.md).
Debug APK build succeeded and opened with xdg-open; the user subsequently confirms
the status bar works well. Further refinement is deferred for a later pass.

### 2026-10-26 — shared action eligibility and container move context

Added `StorageActionPolicy` for menus, selected sets, destination selection,
clipboard paste, drops and transfer preflight. Writable containers explicitly report
internal move support, so the picker can accept moves within one container while
retaining cross-container/nesting restrictions. Drop capability/device checks run in
the background; source and destination transfer checks run again before mutation.
Special recovery, mounted-image and Virtual root link handling remain intact.

Internal ZIP moves are user-confirmed: the 18:57 UTC dump shows 46 files
(8,395,781 bytes) moved in about 3.93 seconds, with verification, one committed
publication and no warnings. Native FAT32 file moves were subsequently confirmed
in the 19:24 UTC dump. FAT directory moves, outside-to-container copies, read-only
rejection across entry points, native-link copy/no-Move and mixed readable/unreadable
selections remain focused acceptance checks.

The dump also exposes remaining UI-thread local reads during mounted-source
preflight; moving these checks to workers while retaining protection is pending.
Toolbar/New/catalog extraction and broader metadata preservation remain on the
consistency agenda. See [details](storage-api-consistency.md).

### 2026-10-26 — explicit failure warning dialogs (acceptance pending)

Failed operations now queue a themed reason dialog for the resumed browser/viewer;
background failures retain their system notification and wait for an activity to
resume before showing the dialog. Concurrent failures are shown one at a time;
unread warnings survive activity changes. Activity rows include the failure reason,
and exception causes remain visible. Success, pause and cancellation do not trigger
failure warnings; failed task records remain available for details/retry.

No build or tests were run during this implementation turn. A subsequent installed
build exercised native FAT relocation, but failure-dialog acceptance is still pending.
The user's BitTorrent/magnet end-to-end completion tests remain in progress. See
[activity behavior](concurrent-activity.md).

### 2026-10-26 — FAT32 internal moves blocked by duplicate space (file moves now confirmed)

The 19:09 UTC dump reports two FAT32 moves rejected before mutation: 32,437,248
bytes needed by the copy/delete strategy versus 20,632,064 bytes free. Sources were
retained. Native FAT12/16/32 relocation is now implemented through an optional shared
`StorageWriteBatch` hook, preserving payload clusters and updating directory entries
inside the unpublished snapshot. It retains collision/ancestry/partition checks,
parent-record verification, cancellation and verified image publication. Unsupported
backends retain full copy-space checks; other copy/delete implementations still need
safe native relocation support. ZIP's confirmed rebuild path remains separate.

Peer discovery logging is rate-limited to retain useful move diagnostics during
active torrents. The implementation turn did not build or run tests; a subsequent
installed APK now confirms the native FAT32 file-move path (19:24 UTC): eight files,
8,751,605 bytes, zero payload copies, one successful SAF publication and COMPLETE
with zero warnings in about 1.41 seconds. Nonempty directories, payload exceeding
remaining free space, FAT12/16/compressed FAT and failure-warning dialogs still need
focused acceptance. BitTorrent/magnet completion tests remain running in the dump.
See [details and checks](storage-api-consistency.md).

### 2026-10-26 — Activity swipe actions with confirmation (normal use confirmed)

Activity rows now request Cancel with a left swipe and Pause with a right swipe for
resumable running downloads. Failed/cancelled transfer rows request Remove on the
left and supported Resume/Retry/Restart on the right. Each action confirms first;
ordinary stream copies explain that Pause is unsupported. Clear finished includes
failed/stopped records, confirms first, and cannot remove active operations or data.
Details also expose removal and supported restarts.

User-stopped torrents retain data/checkpoints, distinguish pause from cancellation,
and disable automatic resume until explicitly resumed. Ordinary transfer restarts
retain a data-only request, refresh source metadata off the UI thread, review missing
sources and preserve normal collision protection. Arbitrary operation replay across
automatic process-death recovery of active work and general stream pause/resume
remain pending. Terminal replay requests are now saved with operation history. The installed ongoing
torrent/magnet end-to-end completion tests still have no completion confirmation.

Debug APK build succeeded in 39 seconds and opened with `xdg-open`; see
`build/activity-swipe-controls-build.log`. The user subsequently confirms swipe
activities working in normal use. Individual pause/restart/persistence and race cases
still need focused checks. No automated tests were added or run. See
[gesture mapping and limits](concurrent-activity.md).

### 2026-10-26 — Activity removal refresh fix (acceptance pending)

Fixed delayed dialog dismissal callbacks losing the current Activity list/detail
reference. They now clear only the matching dialog. UI notifications use fresh
coordinator snapshots, and confirmed row removal/Clear finished refresh both during
the action and after the confirmation closes. Removed rows should no longer require
closing/reopening Activity to disappear; handset acceptance is pending.

Debug APK build succeeded in 47 seconds and opened with `xdg-open`; see
`build/activity-history-refresh-build.log`. No automated tests were added or run.
Handset acceptance remains pending. See [refresh details](concurrent-activity.md).

### 2026-10-26 — moves and swipe Activity follow-up

The user reconfirms moving works and separately confirms swipe activities. Dump
`build/infinifolder-debug-20261026-200008-803.log` records five single-record history
removals, followed by an explicit `activity-cancel` at 19:59:39 UTC for the Ubuntu
torrent. The interrupted worker is recorded as CANCELLED, not FAILED, and its partial
progress remains in the task snapshot. The other torrent remains downloading. The
retained log window no longer contains move operations; the earlier ZIP/FAT32 logs
remain the detailed move evidence. Do not infer new filesystem variants or completed
downloads from this general confirmation.

Suggested engineering continuation: native local/SFTP/SMB moves where supported,
retaining the checked fallback and explicit publication/identity rules. Remaining
focused checks include nonempty FAT directories, constrained free space, failure
warnings, pause/manual resume persistence, ordinary-transfer restart, and Activity
removal refresh without reopening (not separately confirmed by this message).

### 2026-10-26 — blank-space and non-container drop targets

Empty file-pane space now receives the full drag selection into the displayed
container. Dropping on a regular file resolves its real parent through provider
navigation on a worker and transfers there. Existing container/navigation targets
and source self-drop menus retain priority. Shared capability/device checks apply;
all-sources-already-in-folder drops do nothing. Virtual root rules still apply.

Debug APK build succeeded in 17 seconds and opened with `xdg-open`; build log:
`build/container-background-file-drop-build.log`. No automated tests were added or
run. Handset validation remains pending; see
[drop rules and checks](storage-api-consistency.md).

### 2026-10-26 — persistent Activity results

Completed, failed, paused and cancelled records now persist in an app-private
atomic history file. Startup loads/merges results on a worker; supported manual
restart/resume requests are retained. Remove/Clear finished persist their changes,
and Show older results exposes entries beyond the initial 15. Restored results do
not replay old completion notifications, failure warnings or light pulses. Active
ordinary work is not a durable queue; torrent sessions keep their separate resume
storage. Newly scheduled history writes are asynchronous.

The user corrected the Ubuntu completion report: it had been cancelled. Tapping
its `.incomplete` file successfully continued the transfer. Record manual resume
as confirmed; full completion and this new history persistence remain separate
acceptance checks.

Debug APK build succeeded in 26 seconds and opened with `xdg-open`; see
`build/persistent-operation-history-build.log`. Static whitespace review passed.
No automated tests were added or run. Next focused handset check: reopen the app,
inspect saved results, remove one and reopen again to confirm it stays removed.
See [persistent results](concurrent-activity.md).

### 2026-10-26 — ext image write rejection

Dump `build/infinifolder-debug-20261026-202058-172.log` records repeated write
capability rejections for `ext4.img` at 20:20:07–20:20:50 UTC. They report
`writable=false` with the misleading reason `No writable FAT filesystem found`.
Inspection confirms ext2/3/4 share a reader and have no image mutation writer.
This is an implementation gap, not evidence of a permission failure in this dump.

The shared image write probe now explains that the recognized filesystem is
read-only in InfiniFolder and supports browse/copy-out only. Standalone ext images
and selected partitions use the same rejection, as do other recognized image
formats without writers. Actual ext mutation support remains on the task list;
this diagnostic correction does not enable writes.

This diagnosis describes the earlier revision. The subsequent ext writer below
supersedes the read-only limitation for supported feature sets.

Debug APK build succeeded in 23 seconds and opened with `xdg-open`; see
`build/ext-image-write-diagnostics-build.log`. Whitespace review passed. No tests
were added or run; handset acceptance of the revised message remains pending.

### 2026-10-26 — guarded ext2/3/4 writes, journals and internal moves

Implemented the supported ext write subset through `WritableImageFileSystem` and
a vendored lwext4 JNI backend. Ext2 skips journaling; journaled ext3/ext4 volumes
replay supported journals in the private snapshot and use metadata transactions.
Create, copy-in/content writes, recursive deletion and file/nonempty-directory
moves share the existing provider and checked image-publication path. Native moves
preserve the inode rather than duplicating payload blocks. Ext listings now expose
mode and numeric ownership, and properties report type and free space.

Unsupported features remain read-only. Ext writes cannot bypass private staging;
single image edits use private batches too. Failed private mutations prevent
publication. The integration retains native transaction failures, rejects detected
checksum corruption, and replaces unsafe upstream recursive deletion with checked
traversal and link-aware unlinking. An initial strict-check test exposed an upstream
stale-checksum/error-cleanup crash during directory deletion; the replacement path
and corrupt-inode rejection now pass the final checks.

Validation: four focused Kotlin/JNI integration tests passed, covering raw ext2/3/4,
a GPT ext4 partition, compressed capability probing, public-write refusal and
failed-snapshot marking. The separate native matrix passes create/write/move/delete,
inode/metadata/hardlink/xattr retention, outside hardlinks after recursive deletion,
path/collision/symlink-ancestor checks, injected I/O failures, corrupt-inode rejection,
and ext3/ext4 process-death journal replay. Disposable mutated/recovered volumes pass
`e2fsck -fn`; payload hashes match and user fixture hashes remain unchanged. Logs:
`build/ext-image-integration-tests.log` and `build/ext-integration/results.log`.

Added `filesystems/linux/ext2-write-tests.img`, `ext3-write-tests.img` and
`ext4-write-tests.img` under `filesystem-test-images/`, with populated `move-from`
and `move-to` folders, metadata/links and a non-overwriting generator. New fixtures
pass filesystem checks. Original fixture images were not changed.

Final debug APK build succeeded in 23 seconds and opened with `xdg-open`; see
`build/ext-journal-writes-build.log`. Whitespace review passed. Handset acceptance
of writes/internal moves is pending, including compressed and SAF/network backing
publication. [Scope, limits and next checks](ext-image-writes.md).

### 2026-10-26 — metadata preservation and torrent finalization follow-up

Added shared transfer metadata read/apply hooks and local/ext implementations for
mode, numeric ownership, precise access/modification times and xattrs. Ext ACLs
are converted and validated against modes. Attributes are read back after import;
incomplete metadata preserves move sources. Directory attributes are applied after
children. Native link-containing directory moves and cross-partition relocation
preflight preserve the existing data-safety rules. Broader provider parity and
volume management remain pending; see [metadata integrity](metadata-integrity.md).

The 21:18 UTC dumps record a completed 6,482,409,472-byte torrent with failed final
rename, followed by repeated SFTP write errors. They no longer contain the original
rename exception. Failed pooled SFTP requests now discard their request channel;
the first torrent destination failure stops writes. Successful shutdown does not
pass a shutdown-generated interrupt into publication. Finalization displays status,
logs stages and includes SFTP error codes. Download/checkpoint data is retained on
publication failure. See [checkpoint follow-up](torrent-checkpoint-safety.md).
Device retry is user-confirmed successful. The 21:39 UTC dump records SFTP rename,
checkpoint cleanup, resume-state migration/cleanup and COMPLETE with zero warnings.
It reused 24,728 trusted pieces, read 1 MiB during recovery, and transferred no new
payload. Finalization recovery can be marked accepted for this SFTP case.

Remaining log findings: Bt teardown submits work after executor shutdown and logs
closed incoming listeners. Main-thread StrictMode warnings identify cache startup
and debug-dump preference access. These remain follow-up work; they did not prevent
the recorded completion. Evidence: `build/infinifolder-debug-20261026-213929-565.log`.

Validation: five focused ext integration tests passed, including metadata/ACL and
inline/external xattr round-trips on disposable images. Debug APK build succeeded
in 1m 6s and opened with `xdg-open`; whitespace review passed. Logs:
`build/metadata-integrity-tests.log`, `build/torrent-finalization-recovery-build.log`.
Torrent finalization was build-checked; no new torrent tests were run.

### 2026-10-26 — external-edit content checks and compact themed UI

Returning from an external program now checks the local working checksum first.
Unchanged copies close automatically without checking the source over the network;
known open writable handles retain their session. Save actions appear only for
changed local content. A source conflict requires different original file bytes;
timestamp-only differences no longer cause conflict warnings during staging or
publication. Checks use the original provider/resource identity and fresh archive/
decoded-image reads. Unreadable sources remain ordinary failures with edits kept.

The activity is now a compact, scrolling, content-sized dialog with light/dark theme
colors for its window, text, buttons and progress. Secondary actions are nested,
and idle progress widgets are hidden. Device acceptance remains pending. See
[external editing](external-edit-writeback.md).

Final debug build succeeded in 8 seconds and opened through `xdg-open`; no tests
were added or run. Whitespace review passed. Build log:
`build/external-edit-content-review-final-build.log`.

The editor-return crash fix is user-confirmed. The 22:07 UTC dump records a changed
SFTP working copy and successful save. Other conflict and theme cases remain
unconfirmed. That dump also contains one SFTP torrent checkpoint read failure
(“channel is not opened”), followed by continued writes; checkpoint recovery needs
further investigation. Evidence: `build/infinifolder-debug-20261026-220706-813.log`.

### 2026-10-27 — remote and archive directory images, icon overrides, and sibling icon cache

Extended directory/container artwork discovery (`.icon.<ext>`, `.icon`, `cover.<ext>`,
`cover`), arbitrary `.icon-<full filename>.png` sibling icon overrides, and generated
program-icon sibling PNG caching to remote directories (`sftp://`, `smb://`, `ftp://`,
`webdav://`) and archive/disk-image containers (`archive://`) across the main grid/list,
Virtual Root links, `.iflnk` shortcuts, tree sidebar, pinned containers bar, properties
dialog header, floating container overlay, and launcher shortcuts.

`ThumbnailDecoder` now reads non-direct provider streams in a single bounded pass and
extracts archive entry extensions from the `path` query parameter. `FtpClientPool`
enables `listHiddenFiles = true` so dotfile icons are visible over FTP.
`ResourceArtworkCache` supports direct exact-name sibling lookup without parent
enumeration when a parent listing has not yet been observed (single-target `getMetadata`
on SFTP/FTP, `stat` on SMB/WebDAV/archive, and outer-container source lookup on archive
roots), plus archive batch writes (`beginWriteBatch`), `ArchiveStorageProvider.replaceContent`,
and `ContentReplacement.replace` fallback for sidecar persistence and refresh.
Remote and archived `.apk` files stage temporarily to extract real package artwork and
participate in `.icon-<apk>.png` sibling caching. User-confirmed working on the handset.
See [Resource icons](resource-icons.md).

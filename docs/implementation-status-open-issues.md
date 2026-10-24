# InfiniFolder implementation status and open issues

Updated: 2026-10-23

## Completed work

### Storage architecture

- Moved common storage contracts and backend implementations into `:storage-engine`. The application uses a shared provider graph for local storage, SAF, archives and disk images, installed applications, FTP, SFTP, and SMB.
- Separated viewers and editors into format modules, with shared viewer support and a standalone host that can include selected viewers without depending on the browser app.
- Connected MIME and package classification to file opening and icon selection. Archive-packaged applications and formats keep their application MIME identity while using archive readers to browse them.
- Added common capability reporting and random-access hooks where a backend provides them. Unsupported writes, mounts, or metadata operations are intended to fail explicitly.

### Copy, move, links, and containers

- Added transfer preflight, safe collision naming, file-size and capacity checks, byte verification, source rechecks for moves, cancellation checks, and diagnostics. Sources are retained when copying or verification fails.
- Added filename compatibility conversion for filesystems that cannot represent selected characters. FAT size and free-space limits are checked before destination entries are created when the required information is available.
- Added staged, batched writes for supported ZIP containers and writable FAT images. Multi-file and directory copies into ZIP were confirmed on-device; oversized FAT image copies were rejected before transfer in a confirmed device run.
- Added staged TAR/compressed TAR writes and compressed FAT-image recompression with working/recovery/destination space checks. These new paths still need handset acceptance; see [compressed container writes](compressed-container-writes.md).
- Added shared 700 ms drag-hover navigation for container cards, breadcrumbs and temporary pinned containers, preserving the full selection payload. Container menus expose **Pin to top**; pinned-item long press exposes **Unpin**. The half-size icon row appears between the toolbar and breadcrumbs and disappears when empty. Coordinate-based drop fallbacks cover navigation targets and dynamically loaded cards. Device acceptance is pending.
- Updated system Back handling for the browser and all built-in viewers. Viewers close to their caller while editor discard prompts remain; directory Back resolves the actual parent, including after pin/tree navigation and at mounted-container roots. See [Back navigation](back-navigation.md) for pending handset checks.
- Added a shared link category for symbolic links and InfiniFolder shortcut files. The `.iflink` shortcut behavior was confirmed by the user. Link resolution has cycle and depth limits; link and metadata behavior still varies by backend.
- Added drag and drop for selected files and folders. Same-device drops are moves; cross-device drops are copies. Archives, image mounts, and separate network connections have distinct identities. Breadcrumbs and the visible tree can be drop targets, with tree expansion and edge scrolling support.

### Browser and diagnostics

- Added debug offload for logs, metadata, and screenshots to a user-selected directory. The user confirmed that exported files can be placed in the project build directory, and dump-folder views refresh after export.
- Added automatic tree refresh after transfer completion, including failed or cancelled attempts that may have left partial destination changes. Root refreshes are sequenced before the destination node refresh to avoid replacing the node during its reload.
- Refined portrait pane swipes so the tree and file view track the finger, use a lower horizontal threshold, and allow horizontal gestures to reach the pager before the icon long-press drag is armed.
- The latest debug APK assembled successfully after the refresh and swipe changes and was opened with `xdg-open`. The user continues to report unreliable pane swipes and drops onto tree nodes; these remain active issues.

## Confirmed behavior

- Multi-file and directory copies into ZIP containers succeeded.
- FAT image free-space and maximum-file-size checks rejected an oversized copy before transfer began.
- Shortcut creation/opening was confirmed by the user.
- A nested directory copy to USB later succeeded with verified files after an earlier folder-creation failure on the same build.
- Debug logs and screenshots can be offloaded without ADB.
- The user reports that FTP is functioning after testing (2026-10-23). The earlier `502` unique-file creation error is retained as history; it is not currently a reproduced blocker.
- The user confirmed a much faster FAT16 copy after the private-image optimization. The 19:44 UTC dump shows successful verification and image publication; see [device performance confirmation](container-batching.md#device-confirmation-2026-10-23-1944-utc).
- The user confirmed batched FAT16 deletion. The 20:51 UTC dump records 1,489 successful staged removals and one verified image commit in approximately 3.30 seconds, including publication.

These confirmations apply to the exercised devices, destinations, and operations. They do not establish compatibility with every filesystem or server.

## Open issues and next checks

1. **Tree drag scrolling:** Tree drops are now user-confirmed working. The 02:47 UTC dump records two accepted tree drops, including a verified same-device move. Dragging near the tree's edges did not scroll: the old autoscroll handled only the file pane and depended on pointer movement. Added continuous animation-driven scrolling in the tree's top/bottom sixth, speed increasing toward the edge, and target/hover updates as rows scroll beneath the pointer. Selected-row centering is suppressed during dragging. The user confirms scrolling works perfectly; the 02:52 UTC dump records up/down autoscroll followed by a successful ZIP drop, verified publication and tree refresh. This issue is closed; broader drop coverage remains regression work.
2. **Pane swipe reliability:** The user again reports unreliable tree/file swipes on 2026-10-23. The 02:59 UTC dump identified scroll views blocking eligible horizontal swipes after early vertical movement. Browser dispatch now reopens interception for eligible horizontal motion while protecting armed entry holds and active drags; handset acceptance is pending. Focused gesture diagnostics record correlated dispatch/interception, child requests and hold/drag decisions, with movement sampled every 150 ms. Device checks are needed in both directions, starting from icons, blank file-view space, tree rows, and blank tree space. Check that vertical tree scrolling and long-press dragging still work.
3. **SAF/USB reliability:** A directory creation once stalled for about a minute and failed, then a later nested directory copy succeeded on the same build. A small-file write also showed a multi-second delay once and later completed quickly. The underlying provider, storage, reader, or connection cause remains undetermined.
4. **FAT image coverage:** Small-file copying and bulk deletion are now user-confirmed faster for the exercised FAT16 image. The latest device delete batch removed 1,489 entries with one snapshot and one verified publication in approximately 3.30 seconds. FAT12/FAT32 device checks, explicit retained-file/reopen checks, and broader backing-provider coverage remain open. See [container batching](container-batching.md#bulk-deletion).
5. **Metadata and links across filesystems:** Complete device acceptance is still needed for permissions, timestamps, ownership/ACLs/xattrs, symbolic and hard links, and explicit behavior when a destination cannot preserve them. Unsupported metadata must be reported or translated according to a clear policy.
6. **Backend parity:** The common provider architecture is in place, but behavior still depends on backend capabilities and server support. Continue reviewing equivalent hooks and user-visible behavior for SAF, local storage, archives/images, FTP, SFTP, and SMB, especially random writes, atomic replacement, safe unique creation, and metadata support.
7. **Device coverage:** Broader checks remain for SFTP, SMB, SAF grants, screenshot capture paths, and failure recovery, beyond the already confirmed scenarios. FTP is user-confirmed working for the latest exercised case. ADB was unavailable during earlier validation, so the selected-folder debug offload is the current way to provide diagnostics.

## Requested features

Temporary pinned container targets are implemented with device acceptance pending. Wildcard selection and expanded sorting controls remain on the [feature to-do list](feature-todo.md).

## Validation notes

The repository's progress notes record a prior 223-test module run and compilation checks. The latest refresh/swipe increment was verified with `gradle :app:assembleDebug`; no device interaction was available for that build. Historical automated results do not validate the remaining device and server issues above.

Related implementation notes: `docs/storage-safety-progress.md`, `docs/container-batching.md`, `docs/container-targets.md`, `docs/device-dump-follow-up.md`, `docs/storage-filesystem-limits.md`, and `docs/code-consistency-audit.md`.

## UFS and legacy archive additions (2026-10-23)

Read-only BSD UFS1/UFS2 browsing/extraction is implemented; the user confirms UFS and compressed UFS working. UFS/TAR ownership and permissions and ZIP Unix modes now reach resource metadata. Unix-compress `.tar.Z`/`.taz` and `.Z` image decoding are implemented. Metadata, explicit copied-byte checks and further variants remain pending. See [scope and checks](ufs-and-legacy-compression.md). Limited NTFS and HFS readers have since been added (below); RAR/ARJ have since been added (below); CAB has since been added (below), with general handset support confirmed.

## NTFS additions (2026-10-23)

Added a limited read-only NTFS 3.0/3.1 decoder through the shared block-device/image path. It reads live directory indexes and resident/nonresident/sparse data with fixup, sequence and bounds checks; unsupported features fail explicitly. Added `ntfs-populated.img.gz` while preserving existing fixtures. The user confirms NTFS working; specific coverage checks remain pending. See [NTFS scope and checks](ntfs-support.md); writes, attribute extensions, compressed/encrypted streams, alternate streams and ACL translation remain unavailable. HFS/HFS+/HFSX now have a separate limited reader; RAR/ARJ have since been added (below); CAB has since been added (below), with general handset support confirmed.

## HFS/HFS+/HFSX reader (2026-10-23)

Added a separate read-only decoder for classic HFS and HFS+/HFSX, including embedded HFS+ volumes, catalog leaf traversal, inline/overflow extents, and HFS+ BSD modes/ownership/symbolic links. It uses the existing block-device, partition and compressed-image routes. Unsupported forks, attributes, hardlinks, aliases, dirty volumes and unrepresentable names have explicit limits; no HFS writes are enabled. Added a compressed classic HFS fixture without replacing existing images. The user confirms HFS working on the handset. Added independent populated HFS+/HFSX fixtures and gzip wrappers with Unicode, Unix modes/ownership, symbolic links and distinct HFSX case variants. Expected payload hashes and metadata are recorded. The user confirms HFS+ and HFSX working after the header-offset fix. Specific compressed-image/copied-byte and metadata checks remain pending. See [scope and task list](hfs-support.md).

### HFS+/HFSX first handset failure

The new dump reports an allocation-extent error at volume-header fork decoding. Corrected overflow/catalog fork offsets from 160/240 to 192/272 to account for the 32-byte Finder information field. Added more specific extent diagnostics. The user confirms HFS+ and HFSX working after this fix; see [HFS details](hfs-support.md#hfshfsx-header-offset-correction).

## Archive resilience, fixture organization and full disks (2026-10-23)

Added shared read-only RAR/RAR5/ARJ decoding, sibling volume resolution, ARJ continuation assembly, per-member integrity status, X badges and verified extraction staging. Unreadable directory-copy sources are skipped with warnings; partially copied move-source folders are retained. Added Android Zstandard decoding and corrected CPIO UTF-8 filename/link metadata. The user confirms the delivered functionality working. See [archive scope and remaining checks](rar-arj-and-integrity.md).

Fixtures are grouped under `filesystems/` by OS family, `archives/` by format, `compression/` and `disks/`; generators and manifests follow the new paths. Added complete MBR, GPT and Apple Partition Map images. Four full-disk integration tests passed, checking partition discovery, copied README bytes through fresh handles and unchanged source hashes. The user confirms the delivered full-disk functionality working. See [full-disk coverage](partitioned-disk-fixtures.md).

CAB is now implemented through the shared adapter (below). Next are a maintained native-engine upgrade, malformed/extended partition layouts and metadata preservation gaps. Split ZIP/7z, archive repair, password UI and full lossless fork/ACL/hardlink preservation remain open.

## CAB through the shared adapter (2026-10-23)

Added read-only CAB routing by extension, MIME and signature. CAB uses the existing native integrity/X-badge/verified-extraction path. Sibling volume resolution preserves the selected cabinet and follows explicit previous/next header names, including differently named files. Added independent-folder intact/damaged cabinets, MSZIP, mixed-method and complete/missing multipart fixtures under `archives/cab/`. Build succeeded and APK opened; The user confirms CAB support working; detailed variant checks remain open. See [scope and checks](cab-support.md).

## FAT deleted-file recovery (2026-10-23)

Added a separate read-only deleted-file view for FAT12/16/32 images and FAT partitions. Long press the image for **Show deleted files**; deleted files have a trash-can badge and **Undelete…** opens the destination picker. Recovery copies out, blocks destinations in the source/enclosing containers, and never edits source allocation. Reused/allocated clusters produce the unreadable X; contiguous allocation and tentative names are explicitly unverified. Controlled fixtures cover short/long names, empty and contiguous files, live subdirectories, plus a compressed FAT16 image. The user confirms undelete working and subsequently checked all delivered safety fixtures, reporting graceful handling throughout. Additional provider/gesture and explicit handset source-hash checks remain open. Fragmented reconstruction, deleted directory trees and exFAT recovery remain future work. See [details](fat-deleted-file-recovery.md).

### Recovery safety follow-up

Added adversarial FAT12/16/32 images, MBR/GPT/APM and compression variants, and automated scanner/transfer checks. Recovery now fingerprints records, validates active/mirrored allocation, detects observed metadata/payload changes, avoids stale compressed caches, and carries cancellation through staging. Origin checks protect source/enclosing containers even when browsing inside recovered archives. GPT recovery checks both copies and CRCs. The UI requires an unverified-data confirmation; transfer completion retains the warning. Free-gap fragmentation and overwrite remain intrinsically unverified, with fixtures demonstrating wrong output despite free allocation. The user has checked all files in the safety directory on the handset and confirms graceful handling. See [evidence, handset acceptance and additional checks](fat-deleted-file-recovery.md).

### Explicit partial recovery and range reports

Added **Recover partial copy…** for eligible deleted candidates, including allocated-cluster X entries. It preserves readable uncertain bytes (the user's selected policy), fills unavailable/allocated ranges with NUL bytes and writes a `.recovery.json` sidecar beside a `.partial` copy. Reports distinguish confidence, copy/fill disposition and reasons at byte offsets. Magic/type hints use the shared MIME registry, respect packaged ZIP formats, and can lower confidence; no original-good/overwritten claims are inferred from metadata alone. Data and report are verified, and report failure cleans up newly created outputs. The 260-test storage suite passes; APK built and opened. The new partial fixtures and UI await handset confirmation. See [details](partial-fat-recovery.md).

### Recovery drag and container menus

The 02:32 UTC device dump shows an eligible X-marked FAT12 candidate dragged to a folder and skipped by ordinary copy preflight. Added an explicit partial-recovery choice to the shared confirmation for such selected candidates. Empty file-view space now opens the current container's entry menu even during selection; mounted roots use the backing archive/image's menu. See [dump evidence and follow-up checks](device-dump-follow-up.md#recovery-drag-and-empty-space-menus-2026-10-24-0232-utc-dump).

Partial recovery is now user-confirmed working. The subsequent 02:40 UTC FAT16 export produced the expected 512 NUL bytes and 512 surviving bytes, a matching report digest and an unchanged source image. Additional fixture/provider coverage remains tracked in [partial recovery](partial-fat-recovery.md).

### Large VDI over SFTP

The 03:17/03:20 UTC dumps confirm successful LVM metadata decoding and a 12.34 GB LV, followed by remote range failures during filesystem enumeration. Concurrent callers were scanning the same image repeatedly. Added shared scans, a smaller read-ahead queue for range channels and timing/root-cause/scan-progress logging. Live acceptance remains pending. Full recursive image catalog construction remains a performance limitation; see [dump analysis](device-dump-follow-up.md#vdisftp-scan-failure-2026-10-24-0317-and-0320-utc-dumps).

The subsequent 03:27 UTC dump shows healthy SFTP reads but 107 seconds of ext enumeration before displaying the folder. Implemented directory-based image catalogs: partition/LVM roots list volumes, ext folders read only ancestors/direct children, and extraction resolves the selected parent folder. Other image filesystems still filter existing full catalogs. APK built/opened; [device acceptance](image-directory-browsing.md) is pending.

The user subsequently confirms VDI browsing is working. Extraction and detailed navigation/symlink regression checks remain open; successful browsing alone does not establish copied-byte correctness.

### Live image source and gesture log retention

The user confirms the VDI is from a running VM; the 03:53/03:58 dumps show its mtime advancing, triggering the new cache safety guard. Successful source opens initially improve to 121 ms median, but continuing would mix image revisions. Changed source-revision handling to stop once and retain completed copies rather than flood the queue with skips. Gesture traces now have a bounded process-local ring appended to dumps so warning rotation cannot erase missed-swipe evidence. APK built/opened; device checks remain pending.

The user then requested forgiving handling for live copies/backups. Timestamp/size changes now retire cached source data and reopen fresh reads, with a single best-effort consistency warning, rather than stopping solely for metadata changes. Actual unreadable files/length mismatches/verification failures remain reported. Device acceptance is pending; this supersedes the earlier stop-on-mtime policy.

### Operation progress and Back follow-up

Added separate current-file and overall progress bars, with independent byte fields and cumulative progress retained through setup/verification. The user also reports intermittent Back failures in the 04:13 UTC dump. Fixed a code path that discarded Back when only a directory refresh occurred, added immediate mounted-path parent navigation and retained Back decision/system-callback logs. These UI changes await device acceptance. See [progress](operation-progress.md) and [Back](back-navigation.md).

### UI blocking investigation

Code review found diagnostic disk writes in touch handlers, uncoalesced transfer UI updates and normal progress listeners invoked under the coordinator lock. Moved log persistence off UI callers, coalesced progress, limited notifications, released the normal update lock before listeners and prevented repeated terminal-dialog refreshes. Added foreground main-thread I/O/stall/slow-render evidence retained in dumps. Build succeeded/APK opened; exact causes of the reported missed gestures and device acceptance remain open. See [scope and checks](ui-responsiveness.md).

### Operation completion feedback

Successful operation windows now dismiss automatically, queued work advances in the same window, and warning/error review stays available. Added a themed disk-style toolbar activity light and separate background completion notifications, including warning counts/failure messages. Android 13+ asks once for notification permission at the first operation. App visibility includes viewer activities. See [behavior and device checks](operation-feedback.md); device confirmation remains pending.

### Extensionless GPT browsing regression

The 04:42 UTC dump identifies `devsda` over SFTP as a magic-detected GPT image that missed directory-scoped browsing because it had no image extension. It recursively scanned 74,000+ ext entries before showing the root. Directory-scoped requests/cache keys now cover magic-detected names as well. Compilation/device acceptance are tracked in [image browsing notes](image-directory-browsing.md#extensionless-gpt-image-regression).

The user confirms the corrected APK opens the GPT image much faster. Initial extensionless GPT opening is resolved; the earlier repeat dump came from an installation timestamp predating the corrected build. Detailed browsing/extraction checks and remaining non-ext incremental enumeration work remain open.

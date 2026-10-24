# Device dump follow-up

Historical evidence: pending statements and test totals are revision-specific. The [current status](project-status.md) and [task list](feature-todo.md) supersede older acceptance labels. Reviewed 2026-10-24.

Date: 2026-10-22. Evidence: the three `infinifolder-debug-20261022-2049*` / `205025-471` dump sets in the project root. The subsequent `205224-724` log adds no failure events after the previous dump.

## Confirmed export behavior

All three original sets contain PNG, JSON, and log files. The latest PNG has valid chunk checksums and displays the browser correctly. Metadata reports PixelCopy, 1344 × 2992, Google Pixel 8 Pro. The configured destination is the project root, rather than its build subdirectory.

## Findings and changes

- **UI-thread SFTP access:** the capability query in `showEntryMenu` produced NetworkOnMainThreadException. Menu preparation now queries capabilities and archive write support on the worker executor, with navigation/request checks before displaying the result. Shortcut loading for the menu also runs there. Toolbar/destination presentation uses cached archive write hints; mutations still revalidate actual write support. Mounted checks no longer open remote sources.
- **Shared SFTP range/metadata channel:** range GETs previously used the metadata channel. Each SFTP block device now owns a separate synchronized range channel and releases it on close/error. Ordinary stream setup releases channels when opening fails. This addresses a likely contributor to the observed closed-stream error; the actual server path needs another device run.
- **Hidden SFTP write failures:** output stream close suppressed flush/completion failures. Close errors now propagate. Pool callbacks no longer automatically replay after connection loss, because a mutation could already have partially completed. This favors reporting a failure over silently repeating writes/deletes/renames.
- **Disc-image links and read failures:** ext inode symbolic links were treated as regular files, including inline targets incorrectly interpreted as block pointers. They now retain link metadata and targets, including partition/volume prefixes for absolute targets. Known filesystem parse failures propagate instead of becoming raw-partition fallback listings and later misleading entry-not-found errors. Truly unrecognized filesystems retain raw fallback behavior.
- **Copy destination I/O failure:** the captured exception occurred in FileOutputStream.write to a SAF destination, followed by successful deletion of the newly created partial destination. No source deletion followed. The dump cannot establish the underlying provider/storage cause. New transfer failures report stage, successfully written byte count, source/destination provider types, original-retained status, and whether partial cleanup succeeded. There is no automatic destructive retry.
- **Folder menu parity:** long-pressing empty folder space now loads metadata for the current folder in the background and dispatches to the exact same menu function as long-pressing its icon. Selection-mode batch behavior is retained. Remote root stat now returns an accessible root resource with root rename/delete/move disabled.

## Validation and next device checks

`gradle :app:assembleDebug --offline` succeeded. The APK was opened with `xdg-open` after the build. `git diff --check` passed. No new test suite was run in this follow-up; the earlier 223-test result predates these changes.

- [ ] Compare long-press menus on a folder icon and empty space inside that folder.
- [ ] Browse the same SFTP-hosted disc image, including `Partition 5 (rootfs)` links such as `lib`, `bin`, and `sbin`; offload another dump if a read fails.
- [ ] Repeat the failing copy using disposable files. Any repeated EIO should now identify its stage and byte count; confirm destination storage/provider availability separately.
- [ ] Check interrupted SFTP upload reporting and retained originals on disposable files.
- [ ] SMB access-denied, SFTP permission-denied, and host-resolution failures still require checking the selected credentials, permissions, and network. The dumps alone do not establish an application defect in those cases.

## Second device run: 20:59 UTC

`build/infinifolder-debug-20261022-205915-776.log` confirms the updated transfer code ran. At 20:57:57 UTC, copying `devsda` reported **writing destination after 0 bytes, SFTP → SAF, EIO**. This means a source read returned data and the first output write failed; zero counts completed output writes, not source bytes read. The partial destination was removed and the source retained. This localizes that failure to the destination write path but does not establish whether USB hardware, its filesystem, Android's document provider, or another destination condition caused it. Separate SFTP permission-denied messages are also present; the historical UI-thread/closed-stream messages earlier in the same cumulative log predate this run.

Future transfers now log destination document/provider identity, expected size, and distinct source-read/completed-write byte counts. Dump metadata includes application update time to distinguish installations that share the same version name.

Debug dumping now refreshes the current browser listing automatically after export completes, including partial export failures. The diagnostics screen refreshes its log too. Returning to the browser after exporting from another screen also refreshes its listing. Both automatic and manual folder refresh use the same method.

Moved the 15 original project-root dump files (five sets) to `build/debug-dumps/previous-root/`, retaining all evidence. The latest dump directly in `build/` was left in place. The ordinary `infinifolder.png` artwork was left in place.

## Third device run: small local-to-USB copy

`build/infinifolder-debug-20261022-210725-182.log` captured `game.love` (52,175 bytes) copied from local storage to `com.android.externalstorage.documents`, volume `635D-17EB`. The recorded sequence is:

| Event | UTC time |
| --- | --- |
| First destination listing | 21:06:20.989 |
| Destination created | 21:06:21.068 |
| Source/destination opened | 21:06:21.072–21.075 |
| Transfer started | 21:06:21.076 |
| Destination opened for verification | 21:06:26.377 |
| Browser refresh after success | 21:06:26.562 |

Setup took about 90 ms; roughly 5.3 seconds elapsed during copying/flushing/closing before verification began. SFTP was not involved. This confirms slow local-to-USB output behavior but does not distinguish a slow write from a slow close/flush, or establish the underlying filesystem/device cause.

Changes following this dump:

- Explicit indeterminate progress during source/destination checks, file creation/open, initial writing, flushing, finishing, verification, and source removal. Labels are displayed instead of being hidden by the filename. Byte progress publishes after the first successful write, even for a file below the periodic-update threshold.
- `transfer.stage` events measure preparation, opening, verification, and move checks. `transfer.io` reports aggregate milliseconds for source reads/close and destination writes/flush/close, including failure paths. The first failed I/O stage is retained when cleanup/close also runs.
- Source totals reuse the completed preflight snapshot instead of listing source trees a second time. Destination name selection reuses the completed listing and reserves chosen/actual names for the batch. Backend create-new checks and byte verification remain required.
- Failed/cancelled operations stop the indeterminate indicator.

These changes improve reporting and remove redundant metadata work. They do not claim to resolve the measured USB I/O delay. Another device copy and dump is needed for the per-call timing evidence. APK assembly and source whitespace checks are the validation for this follow-up; no additional tests were run.

## Directory-copy failure: 21:39 UTC

Evidence: `build/infinifolder-debug-20261022-213928-416.log`. The app update time in the accompanying JSON is 21:30:43 UTC.

A five-item local-to-SAF copy completed source-tree preflight in 101 ms, identified the VFAT destination in 17 ms, and listed it in 12 ms. The first destination directory, `yabause`, failed to create after approximately 60 seconds. Android's external-storage provider returned `IllegalStateException: Failed to mkdir /mnt/media_rw/635D-17EB/yabause`. No file-open, byte-copy, or verification stage had begun for this operation. The logs show no source deletion for it.

This is a destination directory-creation failure, not a symbolic-link preflight rejection, oversized-file rejection, or SFTP transfer failure. The provider's exception does not retain the underlying OS error code, so the evidence cannot establish the storage/device cause.

Read-only inspection from this development environment found the volume mounted `rw`, reporting 996,456,464,384 total bytes and 837,895,520,256 available bytes. Its root listed 23 entries and no `yabause` directory. These are observations at inspection time and in this process's mount namespace, not proof of the app's permissions or the device's earlier state. No diagnostic files/folders were written to the card.

Follow-up changes:

- Ordinary directory and application-category copies share a timed `Creating folder` stage and destination-name checks.
- Directory-create failures retain the provider's exception cause, identify the destination, and explicitly state that copying into that folder has not begun and its source was retained.
- Stage logs mark returned provider failures/unsupported results as unsuccessful, rather than reporting successful timing merely because the provider returned normally.
- Filesystem reporting includes available bytes where readable and read-only mount status; transfer logs and debug JSON include these fields. Confirmed read-only destinations are rejected before creation. Unknown status does not imply write permission.
- No automatic create retry, guessed-name cleanup, repair, or formatting is attempted after an ambiguous provider failure.

Pending isolation check: create an empty folder on this same card using Android's system Files app. Failure there would support a shared provider/storage-path issue; success would require comparing that app's exact destination/grant and InfiniFolder's request. Neither outcome by itself identifies hardware damage.

Kotlin compilation passed. APK assembly/opening is performed after these changes; no additional tests were added or run. The original 223-test result does not validate this follow-up.

Directory-copy follow-up APK assembly succeeded (`build/directory-copy-build.log`), and the APK was opened with `xdg-open`. The underlying mkdir failure remains unresolved; the diagnostic comparison is pending.

## Successful directory-copy retry: 21:47 UTC

Evidence: `build/infinifolder-debug-20261022-214748-944.log` (also captured in the preceding `214721-808` dump). The latest run records 27 successful directory creates and 118 file I/O summaries paired with 118 successful copy-verification stages. Destination bytes total 133,151,369. File completions span 21:47:01.754–21:47:14.176 UTC; no new failure events appear from 21:47:00 onward.

The 52,175-byte `game.love` copy also completed verification. Its aggregate I/O timing reports source reads below 1 ms, destination writes below 1 ms, and output close at 2 ms. The earlier five-second delay did not recur for this file.

Both the failed `213928-416` run and this successful retry report the same application update timestamp, 21:30:43.709 UTC. The retry therefore succeeded on the same installed build; it does not demonstrate that the later folder-diagnostic APK fixed the failure. Along with the user's report that system Files stalled and then succeeded, this supports an intermittent shared storage/provider/connection issue. A USB reader or connection is plausible but not established as the cause by these logs.

User device acceptance now includes a successful nested-directory copy with checksum verification. Metadata/link preservation, interruption behavior, mounted-image failure handling, and storage durability remain separate open checks.

## Oversized file versus image free space — 2026-10-22 22:38 dump

`infinifolder-debug-20261022-223825-177.log` records copying `SMS.tar.gz` (47,392,726 bytes) into SFTP-hosted `fat12_20mb.img` (20,971,520 bytes). The destination reported the FAT per-file ceiling but `availableBytes=null`. The copy therefore created/published an empty entry and staged all source bytes before allocation failed: 5,786 clusters needed versus 2,535 free. Cleanup deletion was verified (`save.verified`), followed by `Partial destination removed. Source retained.` The source was not deleted. This is an insufficient-volume-capacity failure, distinct from exceeding FAT's per-file size ceiling.

Implemented follow-up:

- FAT image filesystem queries scan the allocation table in bounded chunks and report free-cluster bytes and cluster allocation size.
- Transfer preflight sums known file sizes with cluster rounding, plus a minimum cluster for each newly copied directory, throughout the scanned tree. It compares this lower bound with reported free space before creating any destination entries.
- Insufficient space reports required/free byte counts, confirms no entries were created, and logs `transfer.capacity-rejected`.
- Ordinary name/size validation does not repeatedly rescan the FAT table.

Unknown source sizes, directory expansion/long-name entry overhead, and concurrent changes can still cause a later out-of-space error. Existing verified cleanup/recovery handling remains necessary. No tests were added or run. APK assembly succeeded; the revised rejection still requires device confirmation.

## Refresh position and remembered destination

File listings retain their current rows while the same folder reloads. Re-rendering restores the previous vertical offset after layout, guarded by location and load generation so a stale callback cannot scroll another folder. Navigation to a different folder starts at the top; a shorter refreshed listing naturally clamps the old offset to its remaining range. This applies to ordinary refresh, operation-triggered reloads, and debug-dump reloads of a file listing.

The folder picker stores a destination only after the Select button confirms it. Copy/Move pickers preselect that persisted URI on subsequent uses and expand its route when already present in the tree. Capabilities are checked afresh; remembering a destination does not grant access or bypass read-only/move restrictions. Pickers with an explicit initial location retain that supplied location; otherwise they use the remembered destination before the default writable folder. Cancelling does not replace the saved target.

Validation: APK assembly and whitespace checks; no tests added or run. Device confirmation of refresh position and destination recall remains pending.

## ZIP tree copies, capacity refusal and symbolic-link guard — 22:51–22:56

User confirmed copying multiple files and directories into a ZIP, plus early oversized-file rejection. The `225141-900` dump records successful copy verification for files inside `adisp2k.zip/TI Calculators`, including `ti86.rom`, and records the FAT16 preflight refusal at 22:49:44: at least 47,394,816 bytes required versus 20,267,008 free, with no destination entries created. These confirm the exercised routes, not every backend or cancellation scenario.

The `225654-817` dump records `Copy .backgrounds` failing during source scan at 22:56:45 with `Copying symbolic links requires a preserve-link backend; original retained`. No destination creation starts for that attempt. This confirms the existing guard, not successful native-link preservation.

Proposed next link behavior: preserve a native symbolic link when both sides and policy support it; otherwise offer a shortcut file, dereferenced target contents, or skip. The native shortcut extension is `.iflink`; it is not a binary Windows `.lnk`. Preference confirmation is pending. Dereferencing must detect cycles and broken links, include target bytes in capacity preflight and verification, and retain move sources until the requested policy is verified. Shortcut fallback must retain the original target association and explain that the target may remain on remote storage.

## Link to device confirmation

User confirmed link functionality after the separate Link to action was implemented. Record shortcut creation/opening as user-confirmed for the exercised case. No fresh dump analysis or additional device/test claims are implied. Symbolic-link target-content copying and edge cases remain separate acceptance items.

## Recovery drag and empty-space menus: 2026-10-24 02:32 UTC dump

Inspected `build/infinifolder-debug-20261024-023209-570.log` and its screenshot/metadata. The app update recorded in the dump is 02:29:53 UTC. At 02:31:18 a single-file drag started; after the drop, the 02:31:24 transfer skipped `...__LLOC.BIN` from `fat12-adversarial.img` because its clusters are allocated/reused. There is no `storage.recovery.partial` or `recovery.partial-export` event for that attempt. This was ordinary copying from the drag path, rather than a partial export that failed while writing its report.

The shared transfer confirmation now offers **Recover partial copies** when directly selected deleted candidates are unreadable but eligible for partial export. This covers drag/drop, clipboard and destination-picker transfers. The alternative **Skip unreadable files** preserves ordinary skip behavior. Partial mode still needs explicit confirmation and retains source protection, NUL filling and companion reports.

Empty-space long press now always resolves the current container's regular entry menu, including while files are selected. A mounted archive/image root resolves to its backing container file, exposing the same actions as long-pressing that file outside the mount. A normal folder or mounted subfolder retains its own folder identity. The navigation tree receives no new long-press action.

Build log: `build/recovery-drag-container-menu-build.log`. No additional automated tests were added or run for these UI changes; the prior 260-test result covers the partial-recovery engine and transfer behavior.

Device checks:

- [ ] Drag the eligible X-marked allocated candidate to an external folder; choose **Recover partial copies** and confirm both output files and `recovery.partial-export` in the next log.
- [ ] Compare empty-space menus with the corresponding folder/container entry menus, including mounted roots/subfolders and active file selections.

## Partial recovery confirmed: 2026-10-24 02:40 UTC dump

`build/infinifolder-debug-20261024-024047-678.log` records the updated app installed at 02:37:06 UTC. A single-file drag at 02:40:25 was followed by a successful partial export at 02:40:34: `storage.recovery.partial` succeeded, the 1,024-byte output was verified, and `recovery.partial-export` records a companion report with 512 zero-filled bytes.

Both output files exist under `filesystem-test-images/filesystems/`: `deleted-1fe20-c55587c05aa2e23940c08ef5b09f6018__LLOC.partial.BIN` and its `.recovery.json` companion. Direct inspection confirms bytes 0–511 are NUL, bytes 512–1023 are the fixture's surviving `S` bytes, and the report SHA-256 matches the saved payload. The report marks the first range zero-filled due to current allocation and the second copied but unverified due to assumed contiguous positioning. No original-good or proven-overwritten claim is made. The source `fat16-adversarial.img` still matches its fixture manifest SHA-256.

The repeated ordinary-read errors after listing/refresh remain background probes of deliberately X-marked candidates; they do not indicate that this partial export failed. No new code or APK build was needed for this inspection. Empty-space menu behavior is not established by this log.

## Tree drops and drag autoscroll: 2026-10-24 02:47 UTC dump

Dump: `build/infinifolder-debug-20261024-024756-363.log`. The user confirms tree drops work, but holding a dragged item near the top/bottom sixth does not scroll the tree.

- At 02:46:59 a tree drop onto Internal shared storage is accepted; the following transfer copies and verifies `infinifolder.png`.
- At 02:47:37 a second tree drop is accepted. The 2,091-byte PNG is copied, verified, rechecked and its original successfully removed for the same-device move. The destination tree refresh follows.
- Root cause: `updateDragEdgeAutoScroll` only scrolled `fileScroll`, once per location event. It did not handle `treeScrollView` or keep scrolling with a stationary pointer.
- Added a frame-driven loop with tree edge zones occupying the top/bottom sixth of its viewport. Speed scales toward the edges; file-pane scrolling uses the same loop with its existing edge zone. Each scroll updates tree target highlighting and linger timers. Drag completion, leaving edge zones and Activity destruction stop the loop; tree centering does not override an active drag.
- Device verification remains: hold a drag still in both tree edge zones, reverse direction, return to the middle, expand a row and drop on a row reached by scrolling. Repeat with selected sets and both orientations.

Build: `gradle :app:assembleDebug` succeeded in 14 seconds (`build/tree-drag-autoscroll-build.log`), then the APK was opened with `xdg-open`. No new automated tests were added or run for this UI change.


## Tree drag autoscroll confirmed: 2026-10-24 02:52 UTC dump

The user confirms the change works perfectly. `build/infinifolder-debug-20261024-025205-907.log` records repeated `browser.drag.autoscroll pane=tree` events in both directions between 02:51:37 and 02:51:54. At 02:51:59 the drop onto `backups.zip` is accepted. The 52,175-byte `game.love` copy is verified, its ZIP write batch commits successfully at 02:52:01, and the destination tree refresh follows. Tree drops and edge scrolling are accepted as working. Other selected-set, provider and orientation combinations remain regression coverage rather than an active reported failure.

No code changes or APK build were needed for this log review.

## Swipe diagnostics before handset testing

Added focused `browser.touch.*` events without changing gesture thresholds or recognition rules:

- `dispatch`: browser-area DOWN/UP/CANCEL and pointer transitions, with MOVE samples at most once per 150 ms. Records whether dispatch was handled, coordinates/displacement, elapsed time, pointer/tool/source, current pane, scroll positions and swipe eligibility/rejection reason.
- `interception-request`: child requests to allow/disallow interception, logged only when the request changes.
- `intercept`, `release`, `cancel`: pane takeover, release destination/offset/threshold and cancellation.
- `entry.down/armed/slop-crossed/drag-attempt/release/cancel`: icon/label/card gesture decisions, including hold timeout and drag acceptance.
- `files-intercept`: first file-scroll interception per gesture, distinguishing scrolling, pull-to-refresh and scale/multitouch. Existing drag/drop/autoscroll events remain available.

All touch events share the MotionEvent `downTime` as `gesture` so parent/child decisions can be correlated. Movement is sampled to limit synchronous log writes. Touch traces contain gesture state and positions, without adding filenames or text contents.

Handset sequence: swipe both directions from icons/labels, tree rows and blank spaces; try a slightly diagonal swipe; then vertical scrolling, a short tap and hold-then-drag. Offload the dump after any missed swipe. A missing pane interception alongside a child-disallow request, a file-scroll interception or an entry cancellation should identify the competing path.

Build: `gradle :app:assembleDebug` succeeded in 13 seconds (`build/swipe-diagnostics-build.log`); `xdg-open` opened the APK. No automated tests were added or run for this diagnostic-only change. Swipe behavior awaits the next handset session.

## Missed swipes: 2026-10-24 02:59 UTC dump

Dump: `build/infinifolder-debug-20261024-025905-483.log`; installed APK updated at 02:56:18 UTC.

- Gesture `861982650` ends in the tree with `dx=-904`, `dy=94`, swipe eligible, but `childDisallowed=true` and no pane takeover.
- Gestures `862000164` and `862001183` end in the file pane with predominantly horizontal motion (`601/111` and `946/119` pixels), likewise blocked by a file ScrollView interception request.
- Gesture `862106291` reproduces the tree case: `dx=-354`, `dy=-99`, eligible, child disallowed interception, no takeover.
- Other gestures switch successfully. These failures are child scrolling claiming a small early vertical component before the pane's horizontal threshold, then preventing the parent from observing later horizontal intent.

Change: browser dispatch now reopens interception when a child has blocked it and the existing horizontal swipe criteria become satisfied. The parent then takes over using the normal Android cancellation path. An explicit armed-entry-hold flag protects hold-then-drag; active drags, vertical-dominant motion, wrong directions and noncompact layouts retain their exclusions. Added `browser.touch.reclaim` evidence. Gesture thresholds are unchanged. Handset acceptance is pending.

### LVM image report

No LVM-specific event or fresh archive-list failure appears in this session. At 02:58:26 and 02:58:41 SFTP listings fail with `SSH_FX_PERMISSION_DENIED`; the log does not tie those failures to an image open. Requested the image filename/location or a project-local copy.

Code review shows LVM support exists, but `LvmDecoder` needs further validation: it treats a fixed PV-header field as the metadata location, and its segment mapper reads one stripe's physical extent while its comment claims striped/multiple-PV support. These are review findings, not a confirmed diagnosis for the user's image. Do not treat those layouts as validated. A reproducible image is needed before changing decoding.

Swipe fix build: `gradle :app:assembleDebug` succeeded in 13 seconds (`build/swipe-reclaim-build.log`), and `xdg-open` was run with the APK. No automated tests were added or run; handset verification of swipe reclaim and hold/drag preservation remains pending.

### VDI clarification and correlated evidence

The user clarified that LVM is inside the VDI opened immediately before the dump. The log identifies `echoesbbs-u.vdi` accessed through SFTP. At 02:58:54 its archive listing succeeds; subsequent probes enumerate Partition 1 (BIOS Boot), Partition 2 (Linux filesystem) and Partition 3 (Linux filesystem). At 02:58:59 the Partition 3 listing succeeds. The screenshot confirms the current path is `echoesbbs / echoesbbs-u.vdi / Partition 3 (Linux filesystem)` with a single partition `.raw` fallback entry. Thus VDI/partition decoding completed, but no browsable filesystem/logical volume was produced for Partition 3. The earlier SFTP permission errors do not establish the cause of this later image issue.

The `No writable FAT filesystem found` messages are write-capability probes, not the cause of the missing read-only listing. Current LVM detection/decoding can return no volumes or have an exception swallowed before the partition raw fallback, explaining the lack of an LVM error event. This dump cannot distinguish failed LVM detection, metadata decoding or an unsupported inner layout. No local VDI copy is present in the project. Obtain the VDI or exported third partition for direct inspection; do not claim the suspected LVM parser defects caused this particular failure without that evidence.

## VDI/LVM diagnostic logging

Added logging for another handset open of SFTP `echoesbbs-u.vdi`, especially Partition 3:

- `disc.vdi.detected/geometry/map/open-failed`: recognized wrapper, source/logical sizes, image version/type, block geometry (including extra-block-byte field), offsets, first eight block mappings and constructor exceptions.
- `disc.partition.probe/lvm-result/lvm-failed/raw-fallback`: partition name/type/bounds, LVM label detection, decoded volume count, previously swallowed exceptions and raw fallback.
- `disc.lvm.begin/label/pv-header/metadata-location/metadata-header/metadata-record/metadata-text/allocation/segment/result/empty`: stage-by-stage numeric metadata, chosen offsets/lengths, bounded PV location pairs, metadata text read counts and presence checks, extent geometry and decoded volume sizes. Early empty results now report their reason.

This is diagnostic instrumentation, not an LVM decoding fix or an image export. It logs no complete metadata text, UUIDs or file payload. A single optional PV header sample reads at most 256 bytes; failure of this diagnostic read is recorded without changing decoding. Segment details are limited to the first 16 mapped segments per volume. Existing decoder decisions and raw fallback behavior are retained.

After installing, reopen the VDI and Partition 3, then offload debug information. The new process should decode afresh rather than reuse the previous in-memory archive tree. No 25 GB image copy is required for this next diagnostic step.

Build: `gradle :app:assembleDebug` succeeded in 10 seconds (`build/lvm-diagnostics-build.log`), then `xdg-open` was run with the APK. No automated tests were added or run for this logging change; diagnostic output awaits the handset attempt.

## LVM metadata location diagnosed: 2026-10-24 03:11 UTC dump

Dump: `build/infinifolder-debug-20261024-031111-396.log`. VDI geometry reports a 26,843,545,600-byte logical disk in a 10,902,044,672-byte dynamic image, 1 MiB blocks and zero extra block bytes. Partition 3 begins at 2,149,580,800 and has 24,692,916,224 bytes. Its LVM label is found in sector 1.

The PV header location pairs are: data `(1048576, 0)`, terminator `(0, 0)`, metadata `(4096, 1044480)`, terminator `(0, 0)`. The old reader incorrectly used the first data-area offset as the metadata-header address, read an all-zero header at 1 MiB and then narrowed the remaining 24 GB length to Int before applying its small probe limit; the fallback probe read zero bytes. This explains the raw fallback without needing the full image.

Corrected the two terminated area lists, area/header bounds and signature/version checks, and metadata record reads. Committed text is bounded before narrowing to Int and circular-buffer wrapping is handled. The unstructured fallback scan was removed. Physical-volume identity, segment origin/length and linear single-PV layout are now checked; striped, multi-PV and other unsupported allocations are rejected and logged instead of mapped as linear bytes. This is read-only decoding. Additional metadata-area selection, checksums and advanced allocation support remain future work.

Reference: [upstream LVM2 on-disk structures](https://github.com/lvmteam/lvm2/blob/main/lib/format_text/layout.h), particularly the PV's separate data/metadata lists and metadata raw locations; [upstream metadata reader](https://github.com/lvmteam/lvm2/blob/main/lib/format_text/format-text.c).

### Boot partition clarification

For this VDI, the table labels Partition 1 as **BIOS Boot**, at 1 MiB with a 1 MiB size. It is exposed as raw because no supported filesystem is found there; it is not labelled EFI System. Partition 2 is labelled Linux filesystem and the dump shows paths containing `grub` and `lost+found`, so this boot-filesystem listing already works. Partition 3 is the LVM PV. No EFI System partition is identified among these three entries.

Build: `gradle :app:assembleDebug` succeeded in 9 seconds (`build/lvm-metadata-location-fix-build.log`), followed by `xdg-open` with the APK. No automated tests were added or run. Reopen Partition 3 after installation and offload a new dump to confirm metadata and logical-volume parsing on the actual image. The image is not available locally; handset acceptance is pending.

## VDI/SFTP scan failure: 2026-10-24 03:17 and 03:20 UTC dumps

Evidence: `build/infinifolder-debug-20261024-031743-679.log` and `build/infinifolder-debug-20261024-032008-480.log`; installed APK updated 03:16:33 UTC. The second dump includes the first attempt plus the reconnect/retry.

The corrected LVM decode succeeds: metadata is read at 4 KiB, the text read returns all 1,148 bytes, and one 12,343,836,672-byte logical volume is mapped. The later failures occur through `SftpBlockDevice -> RangeCachedBlockDevice -> VdiBlockDevice -> partition slices`, while reading the inner filesystem. At 03:17:53 three concurrent archive listings fail at physical VDI offsets 4,895,408,128; 3,449,880,576; and 6,096,814,080. After reconnect, the 03:18:25 scan fails at 03:19:53 at offset 7,219,707,904. These offsets are within the 10,902,044,672-byte source image. Existing error logging omitted the nested transport cause, so timeout/session/server causation is not yet established. Other permission-denied SFTP probes are separate events.

### Changes

- Archive callers now share an in-progress tree scan for the same source revision and recovery mode, instead of starting competing full scans. Waiting callers check cancellation; failures release the shared scan so a retry can start fresh. Added begin/wait/complete/failure elapsed-time events.
- Dedicated SFTP range channels use one outstanding read request instead of the streaming setting of 128. Range reads close after a small cached chunk; unnecessary streaming read-ahead must otherwise be cancelled/drained on close. Normal file streams retain their existing setting. See [upstream JSch range InputStream close](https://github.com/mwiede/jsch/blob/master/src/main/java/com/jcraft/jsch/ChannelSftp.java). This reduces a plausible source of redundant remote work; the dumps do not prove it caused the connection failure.
- Added bounded range progress (periodic/slow reads) and failure events with reader ID, offset, requested/returned bytes, elapsed time, cumulative reads/bytes, failure stage and nested cause messages. No automatic retry of writes or failed mutations was introduced.
- Added ext filesystem scan begin/progress/complete counters and cancellation checks during directory traversal. This should distinguish a long, productive scan from a blocked range open/read/close.

The archive catalog still recursively enumerates the inner filesystem before presenting its tree. Folder-at-a-time image browsing remains an architectural performance follow-up for large remote volumes; the new counters will quantify it. Live SFTP acceptance of these changes is pending.

Build: `gradle :app:assembleDebug` succeeded in 11 seconds (`build/sftp-image-scan-build.log`), followed by `xdg-open` with the APK. No automated tests were added or run. Reopen the VDI once after installing and offload the dump after success or failure.

## Healthy reads blocked by eager catalog: 2026-10-24 03:27 UTC dump

Dump: `build/infinifolder-debug-20261024-032705-634.log`. The new SFTP range path returns full 64 KiB chunks, commonly in 34–74 ms, and LVM decoding again succeeds. The ext root volume is still being recursively enumerated: at 03:27:04 its first scan has run 107,144 ms, visited 12,311 directories and cataloged 113,305 entries, with 129 MB of range data fetched nearby. No new range failure is shown for that scan before the dump; the UI is waiting for full enumeration. Retried opens start further scans at 03:26:22 and 03:26:58. The previous shared-load key did not coalesce these attempts; directory-scoped logs now include a key digest, source size and modification time for further identity diagnosis.

Implemented directory-based image catalogs for normal browsing. Multi-partition roots return partition headers without scanning filesystem contents. LVM roots return logical-volume headers. The ext2/3/4 reader follows just the requested folder's ancestors and reads its direct children; it does not descend through child folders or fetch unrelated ancestor child inodes. Provider list/stat/link resolution/cache extraction/stream lookup all request the appropriate directory, with folder-scoped caches. Disc extraction likewise resolves a file from its parent directory instead of recursively enumerating the volume. Wrappers resolve partition/LV mappings against the fresh extraction device rather than retaining the catalog's closed source. Deleted-file recovery retains its full safety scan.

Other filesystem decoders currently filter their existing full listings for a directory request; directory-at-a-time enumeration for them remains future work. ZIP/TAR/native archive catalogs retain their existing behavior. See [implementation scope and device checks](image-directory-browsing.md).

Build: `gradle :app:assembleDebug` succeeded in 11 seconds (`build/image-directory-browsing-build.log`), followed by `xdg-open` with the APK. No automated tests were added or run. Handset acceptance of opening, nested navigation and extraction is pending.

## Slow small-file VDI extraction: 2026-10-24 03:43 UTC snapshot

Dump: `build/infinifolder-debug-20261024-034332-819.log`; the user reports copying is still running. The captured files copy and verify successfully, so this snapshot confirms progress, not completion of the whole transfer. After 03:40 UTC, 146 recorded source opens have a median 1,224 ms and total 204,533 ms; destination creation has median 30 ms and verification median 3 ms. A 466-byte `sysmailr.ans` takes 1,220 ms to open, then only a few milliseconds to write/verify.

Each file resolves a fresh SFTP device/range cache, rereads the VDI header/map, decodes LVM, and resolves its parent ext directory. The SFTP work happens during staged source opening, explaining the near-zero subsequent source.readMs. Parent directory scans alone take about 0.45–0.52 seconds in the shown menu folder. This is repeated metadata/setup overhead, not evidence that writing 466 bytes itself is slow.

Added an operation-scoped read-only device cache through the shared SeekableSourceResolver. Ordinary copies to non-container destinations can retain up to four primitive backing devices and their existing range caches across child files; closing a per-entry lease does not close the backing device. Each borrow rechecks size/mtime; a changed stamp aborts that read. Sources without usable stamps remain uncached. Nested archive devices are not cached directly. Scope completion/failure/cancellation closes the retained devices. No reuse is enabled for moves, shortcut creation, partial recovery, recovery-origin roots or container destinations. Recovery keeps fresh safety reads; mutations keep their existing ownership.

New events: `image.read-session.open/reuse/closed/close-failed`; reuse logging is limited to the first and each hundredth reuse. VDI/LVM parsing still runs per file, but its underlying header/map/directory blocks can be served from the retained range cache. Persistent/global source caching and retaining decoded inode catalogs remain outside this increment.

Build: `gradle :app:assembleDebug` succeeded in 11 seconds (`build/image-copy-read-session-build.log`), followed by `xdg-open` with the APK. No automated tests were added or run. The current handset transfer was not cancelled or restarted by the agent; the user should finish it before installing. Performance and copied-byte acceptance of the new build remain pending.

## Completed old-build transfer baseline: 2026-10-24 03:49 UTC dump

The user reports completion and is installing the reuse build. `build/infinifolder-debug-20261024-034928-397.json` identifies the old APK updated at 03:31:20 UTC, so this is a baseline, not evidence for read-device reuse.

The retained log starts mid-record at about 03:45:54 and the transfer thread's final record is the successful 58-byte `bullseye.cfg` copy/verification at 03:48:47.835. Its retained tail contains 128 verified file IO records totaling 230,338 bytes; 127 source-open records have median 1,143 ms, versus median 27 ms for destination creation and 4 ms for verification. No transfer-thread failed/skipped/cancelled event appears in this retained section. Log rotation removed the beginning, so these are tail counts/timings, not whole-transfer totals or independent proof for every file.

Subsequent `Cannot extract directory entry` errors occur on background pool-5 probes of `sbbs/web`, `exec`, `docs` and `webv4`, after the transfer thread finished. They are separate from the recorded successful copies; directory/symlink probe handling needs follow-up rather than treating them as transfer failures.

Next comparison: repeat the same source copy into an empty destination with the reuse APK, then offload a dump. Check read-session reuse events and median source-open times, alongside successful destination verification. No new build or code change was needed for this log review.

## Live VM warnings and missing swipe evidence: 03:53/03:58 UTC dumps

Dumps: `build/infinifolder-debug-20261024-035325-243.log` and `build/infinifolder-debug-20261024-035816-035.log`. The user confirms the VDI belongs to a running VM. Source size stays 10,902,044,672 bytes while server mtime repeatedly advances (for example 1792813868000, 1792813895000, 1792813919000, 1792813928000 and later values). The read-session revision guard is detecting real changes; disabling it could combine cached old blocks with newly written data.

The first retained dump has 72 successful verified file IO records and 39 source-change skips; the later retained tail has another 228 source-change skips. These are retained sections, not complete queue totals. Successful source opens have median 121 ms, versus the previous approximately 1.1–1.2 seconds, so reuse reduces setup overhead before the source changes. This is not a controlled performance comparison or a consistency guarantee for the live guest filesystem. Destination hash verification establishes that saved bytes match the extracted stream, not that they form a coherent snapshot of a running VM.

### Changes

- Added a typed source-revision exception and preserved its cause across provider/transfer wrappers, including failed cleanup. It now stops the batch instead of skipping every subsequent file from the changed image.
- Earlier completed destination files remain when this specific fatal condition propagates through directory-copy cleanup. The failed file retains its existing per-file cleanup. No source files are deleted. The failure explains that the copy is incomplete and asks for a stopped VM or stable snapshot before retrying.
- The guard logs old/new size and mtime once at the stopping event. Cache safety is retained.
- Gesture events now also enter a bounded in-memory ring (1,024 events / 256 Ki characters) appended to debug snapshots. Transfer/storage log rotation cannot erase this ring; it adds no per-event disk writes. The ring covers browser touch/pane decisions and lasts for the current process.

The retained 03:53 dump has 118 browser touch events but no sufficient horizontal attempt trace to establish the reported swipe failure; the later dump has lost gesture events entirely to warning rotation. Swipe recognition behavior was not changed in this increment. Reproduce a missed swipe after installing and offload a dump; the retained gesture section should survive storage activity.

Final build: `gradle :app:assembleDebug` succeeded in 17 seconds (`build/source-change-stop-gesture-retention-build.log`), followed by `xdg-open` with the APK. An earlier 11-second build was also opened before preserving cleanup causes. No automated tests were added or run. Device acceptance of fail-fast behavior and retained gesture tracing is pending.

## User-selected tolerance for live copies and backups

The user requests more forgiving handling of timestamp and other source changes, including backup copies, after confirming the VDI is from a running VM. Ordinary copy read sessions now treat both timestamp and size changes as cache invalidations: retire the old backing device and range cache, reopen a fresh source, and continue. Dates/sizes alone no longer throw the fatal revision exception. The first observed change produces one operation warning that the resulting copy may contain files from different moments and may be inconsistent. Each refresh logs old/new sizes and times. Actual read failures and changed extracted-file sizes still use the existing skip/cleanup warnings; destination verification remains required.

An active lease is not closed out from under its reader. New callers receive fresh uncached devices until that lease ends, then the stale device is closed. Scope cleanup remains idempotent. File stream canonicalization now finishes before borrowing the extraction device, avoiding retention of an older device across a directory refresh. Moves, recovery and container-destination mutation paths remain excluded from read-device reuse. Existing backup copies without reuse already open fresh sources; this increment changes the previously strict reuse guard. No VM snapshots or atomic guest-backup guarantees are created by this feature.

The user-selected tolerant policy supersedes the timestamp-only stop and size-change stop described in the preceding historical entries. Deleted-file recovery safety checks are unchanged.

Final tolerant-copy build: `gradle :app:assembleDebug` succeeded in 14 seconds (`build/live-image-copy-backup-refresh-build.log`), followed by `xdg-open` with the APK. The preceding timestamp-only 10-second build was also opened. No automated tests were added or run; handset acceptance of live cache refresh and warning behavior is pending.

## Faster tolerant copy confirmed: 2026-10-24 04:10 UTC dump

The user reports the copy completed much faster. `build/infinifolder-debug-20261024-041051-989.json` reports APK update time 04:07:03 UTC. Its retained log begins around 04:09:20 and includes the final `bullseye.cfg` verification at 04:09:46.793, followed by read-session closure with zero outstanding leases. The retained tail contains 86 file IO/verification records and no transfer skip/failure records. Successful source opens have median 161 ms, compared with the earlier roughly 1.1–1.2 seconds. Log rotation prevents whole-queue totals or a full wall-time comparison.

The log shows several timestamp-triggered cache refreshes and continued copying. This confirms the exercised live timestamp-tolerance/reuse path; it does not establish that the extracted guest data forms a consistent snapshot or validate every size-change case. Existing uncertainty warning remains applicable.

## Separate progress bars and Back follow-up build

Implemented separate current-file/overall progress and followed up the user-reported Back failures in the 04:13 UTC dump. The progress-only build succeeded in 12 seconds and was opened; the combined progress/Back build succeeded in 28 seconds (`build/overall-progress-back-navigation-build.log`) and `xdg-open` was run with its APK. No automated tests were added or run. The latest dump lacks Back decision logs, so the suspected refresh-generation race remains a code-review finding awaiting device acceptance. See [operation progress](operation-progress.md) and [Back navigation follow-up](back-navigation.md#intermittent-back-follow-up).

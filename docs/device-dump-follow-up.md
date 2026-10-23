# Device dump follow-up

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

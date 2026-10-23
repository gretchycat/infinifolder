# InfiniFolder implementation status and open issues

Updated: 2026-10-22

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
- Added a shared link category for symbolic links and InfiniFolder shortcut files. The `.iflink` shortcut behavior was confirmed by the user. Link resolution has cycle and depth limits; link and metadata behavior still varies by backend.
- Added drag and drop for selected files and folders. Same-device drops are moves; cross-device drops are copies. Archives, image mounts, and separate network connections have distinct identities. Breadcrumbs and the visible tree can be drop targets, with tree expansion and edge scrolling support.

### Browser and diagnostics

- Added debug offload for logs, metadata, and screenshots to a user-selected directory. The user confirmed that exported files can be placed in the project build directory, and dump-folder views refresh after export.
- Added automatic tree refresh after transfer completion, including failed or cancelled attempts that may have left partial destination changes. Root refreshes are sequenced before the destination node refresh to avoid replacing the node during its reload.
- Refined portrait pane swipes so the tree and file view track the finger, use a lower horizontal threshold, and allow horizontal gestures to reach the pager before the icon long-press drag is armed.
- The latest debug APK assembled successfully after the refresh and swipe changes and was opened with `xdg-open`. Those latest touch changes still need handset acceptance.

## Confirmed behavior

- Multi-file and directory copies into ZIP containers succeeded.
- FAT image free-space and maximum-file-size checks rejected an oversized copy before transfer began.
- Shortcut creation/opening was confirmed by the user.
- A nested directory copy to USB later succeeded with verified files after an earlier folder-creation failure on the same build.
- Debug logs and screenshots can be offloaded without ADB.

These confirmations apply to the exercised devices, destinations, and operations. They do not establish compatibility with every filesystem or server.

## Open issues and next checks

1. **Tree drop reliability:** Drops onto tree nodes have been intermittent; the user reported that the drop was not received again after an earlier attempt. Retest copies to local, archive/image, and network tree nodes. If it recurs, offload a fresh dump containing the drag start/location/drop/end events.
2. **FTP create failure:** One FTP copy failed with `502 This command hasn't been implemented` while requesting a safe unique destination name. The current safety policy refuses ambiguous creation instead of risking overwrite. Confirm which FTP command the server rejects and decide whether a safe server-specific creation path is available.
3. **Pane swipe acceptance:** Tree/file swipes were reported as flaky. The latest code improves interception and animation, but requires a device check in both directions, starting from icons, blank file-view space, tree rows, and blank tree space. Check that vertical tree scrolling and long-press dragging still work.
4. **SAF/USB reliability:** A directory creation once stalled for about a minute and failed, then a later nested directory copy succeeded on the same build. A small-file write also showed a multi-second delay once and later completed quickly. The underlying provider, storage, reader, or connection cause remains undetermined.
5. **FAT image performance:** Image operations remain slower than ordinary local copies. Batching reduces repeated full-image reads and publications, but FAT directory work and final publication still take time. Performance investigation was deferred by agreement.
6. **Metadata and links across filesystems:** Complete device acceptance is still needed for permissions, timestamps, ownership/ACLs/xattrs, symbolic and hard links, and explicit behavior when a destination cannot preserve them. Unsupported metadata must be reported or translated according to a clear policy.
7. **Backend parity:** The common provider architecture is in place, but behavior still depends on backend capabilities and server support. Continue reviewing equivalent hooks and user-visible behavior for SAF, local storage, archives/images, FTP, SFTP, and SMB, especially random writes, atomic replacement, safe unique creation, and metadata support.
8. **Device coverage:** Live checks remain for FTP, SFTP, SMB, SAF grants, screenshots on the target phone, and failure recovery. ADB was unavailable during earlier validation, so the selected-folder debug offload is the current way to provide diagnostics.

## Validation notes

The repository's progress notes record a prior 223-test module run and compilation checks. The latest refresh/swipe increment was verified with `gradle :app:assembleDebug`; no device interaction was available for that build. Historical automated results do not validate the remaining device and server issues above.

Related implementation notes: `docs/storage-safety-progress.md`, `docs/container-batching.md`, `docs/container-targets.md`, `docs/device-dump-follow-up.md`, `docs/storage-filesystem-limits.md`, and `docs/code-consistency-audit.md`.

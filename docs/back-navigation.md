# Back navigation

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Implemented 2026-10-23; handset acceptance pending.

- All built-in viewers share `ViewerActivity`. System Back on Android 13+ now reaches the same `onBackPressed` action as toolbar/legacy Back through a registered `OnBackInvokedCallback`. The base action finishes the viewer, exposing the unchanged browser directory beneath it. The viewer modules remain independent of the browser.
- Text, image and Markdown editors retain their existing unsaved-change prompts before finishing.
- Browser Back moves to the current directory's actual parent through `StorageProvider.parent`, rather than following visit history. It also clears file selection during that action. A visible tree no longer consumes Back merely to hide itself.
- Leaving an archive/image root resolves the parent of its backing file. This also covers nested containers opened for reading.
- When a provider does not implement parent lookup, the browser uses parent relationships learned from listings or the navigation tree. The listing relationship cache is bounded. No parent is guessed by splitting opaque SAF document IDs or returning to an unrelated pinned destination's history.
- Parent queries/stat calls run off the UI thread. Results are ignored after navigation or a newer load. If lookup fails, the folder remains open and the error is shown.
- At a storage/grant root, Back returns to the corresponding logical source page or virtual root. Primary shared storage and app cache roots report no filesystem parent. At the virtual root, Back retains background-operation handling and backgrounds the launcher task when idle.

## Manual acceptance

- [ ] System gesture and three-button Back close every viewer to the same directory and scroll position.
- [ ] Unsaved text/image/Markdown edits still require confirmation.
- [ ] Local, SAF, SFTP, FTP, SMB, archive, and image folders move up exactly one level.
- [ ] After jumping via a pin or tree node, Back selects the real parent rather than the prior visited folder.
- [ ] Archive/image roots return to their backing file's directory; storage/grant roots return to source navigation.
- [ ] Repeated Back, delayed remote parent queries, selection mode, a visible tree, and dialogs behave consistently on Android 16+ and a supported older version.

Build log: `build/back-navigation-build.log`. No automated tests were added or run for this change.

## Intermittent Back follow-up

The user reports failed Back presses in `build/infinifolder-debug-20261024-041314-287.log`. That APK has no explicit Back decision events, so the dump cannot identify which callback or navigation branch failed. Code review found a pending parent lookup was discarded when loadGeneration changed, even if the user remained in the same directory and only a refresh occurred.

- Parent lookup validity now uses a logical navigation generation instead of the folder-load generation. Navigation to another location still invalidates stale results; refreshing the same directory does not cancel Back.
- Within mounted archive/image paths, the parent URI is resolved synchronously from the current mounted path, preserving deleted-view state. The parent folder's load is asynchronous, with no prerequisite remote stat/list to accept Back. Mounted roots retain backing-file parent resolution.
- Added browser.back request/resolve/busy/parent/discarded/failed events with state, timing and rejection reasons. The Android system Back bridge logs the activity receiving its callback. These events also enter the retained gesture ring.

These changes address code paths that can cause delayed or silently discarded Back navigation; they are not yet confirmed as the exact cause of the user's presses. Device acceptance remains pending, including Back during refresh/transfer, mounted paths/root exit, viewers, dialogs and ordinary storage roots.

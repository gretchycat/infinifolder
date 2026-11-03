# Drag floating icons to Home screen

Implemented 2026-11-03. The owner confirmed folders, regular files, archives,
remote locations and public application directories on the launcher on-device
on 2026-11-03.

## Use

Hold an item in the floating browser to start an Android drag. Home appears with
the icon under the pointer and a **Drop to add … to Home screen** prompt. Release
to request its shortcut, then complete any confirmation shown by the launcher.
Back, Recents, changing apps or ending the drag without an accepted drop cancels
placement. No shortcut request is sent at drag start.

The shortcut uses the existing `HomeItemActivity` dispatcher. Folders and recognized
browsable archive/disk-image containers open in the floating browser; ordinary
files follow their existing viewer/open route, application links launch the app,
and `.iflink`/symbolic links resolve through the existing storage/link service.
Resolved web links launch the browser or registered Android URI handler directly
from the transparent dispatcher, without opening the InfiniFolder browser first.
Overlay permission still uses the existing prompt and full-browser fallback.
Office/EPUB/APK and other recognized package documents keep their existing open
routes rather than becoming folders merely because they contain an archive.

Item holds now belong to shortcut dragging. Holding empty grid space still opens
the current directory in the full browser. Scrolling, pinch zoom, title-bar movement
and corner resizing retain their existing native gesture paths.

## Android integration and limits

The source uses `View.startDragAndDrop` with a real global drag shadow. A temporary,
non-exported transparent `HomeShortcutDragActivity` receives the drop above Home.
Its actual `ACTION_DROP` calls Android's
[`ShortcutManager.requestPinShortcut`](https://developer.android.com/reference/android/content/pm/ShortcutManager#requestPinShortcut(android.content.pm.ShortcutInfo,%20android.content.IntentSender))
while the activity is foreground. The launcher can require confirmation and
controls final icon placement. Exact pointer coordinates and hover-based page/folder
placement are not passed through to launcher internals; there is no universal
public external-shortcut drop protocol shared by all launchers. Unsupported
launchers report that pinned shortcuts are unavailable.

The drag captures the already displayed artwork into a small bitmap and reuses
`HomeShortcuts`' identity/intent builder. It does not perform remote icon or storage
reads on the gesture thread. Resolved display names are retained; an unresolved
link uses its filename stem until its artwork/name metadata is available. Pinned
artwork can subsequently refresh through the existing shortcut refresh path.

Only an opaque per-drag token is exposed in the drag clip. URI/shortcut metadata
travel in an explicit private activity intent, with no global storage grants.
Source and target exchange lifecycle notifications to handle quick release before
the target attaches, canceled drags, target destruction and native drag completion.
An unacknowledged source expires after five seconds, including process-death restore.
Queued listing/artwork publication is invalidated before handoff so that it cannot
replace/detach the active drag source. The floating source window is hidden and
kept attached only until the drag ends, then released with its geometry preserved.
Its normal focus/Recents dismissal is deferred during this deliberate Home handoff;
the drop activity cancels the drag when the user leaves it.

Android accepting a pin request is not reported as proof that the user confirmed
a new icon. Rejected requests/errors are shown and logged; canceled drags do not
manufacture a successful shortcut. No source file is moved or copied.

## Build and remaining checks

```sh
gradle :app:assembleDebug --offline --console=plain
```

Debug APK build passes. Log: `build/floating-launcher-drag/app-build-final.log`.
No automated tests were added for the drag implementation. The subsequent full
[regression gate](validation/font-gen-automated-2026-11-03.json) passes all 980 JVM
cases; native launcher interactions remain separate device checks.

The owner confirmed folder and regular-file drag/drop onto the launcher, followed
by archives, remote locations and public application directories. URL-link
drag/drop also worked, but launching the resulting shortcut exposed the routing
bug described below. Cancellation and other launchers remain unverified. The
agent had no connected ADB device during implementation.

For the remaining coverage, check URL shortcut activation, application-launch
links and `.iflink` from a moved/resized/scrolled floating window. Confirm its label and
icon, quick release/cancel, Back,
Recents/app switching, denied overlay permission, repeated drags and saved geometry.
Try another launcher and Android content-provider inputs separately. Compilation alone
cannot establish cross-launcher behavior.

## Direct URL shortcut repair

The 2026-11-03 `infinifolder-debug-20261103-210801-950.log` showed the Home
dispatcher resolving the link, then starting the full file browser. A resolved
HTTPS target has no storage entry, so it fell through to the in-app container
route and produced a missing-storage-provider error.

`HomeItemRouting` now distinguishes external URI handlers from storage targets
after link resolution. The dispatcher sends external targets directly to Android
`ACTION_VIEW`, trying the browsable category first and plain VIEW if no browsable
handler exists. The exact target URI is retained. Native storage/protocol targets
and owned entries keep their existing dispatch. Launch errors remain visible,
and diagnostics record the external scheme without URI credentials or tokens.
Existing pins use this path without needing to be recreated.

Four JVM routing tests exercise web/custom URIs, native storage schemes and
provider-owned entries. Verification command:

```sh
python3 scripts/verify-infinistream.py
```

The offline APK build and all 984 JVM tests passed (zero failures or skips,
zero missing baseline cases); the 30 historical baseline failures remain resolved.
Logs: `build/launcher-url-fix/gate-summary.log` and
`build/launcher-url-fix/verification-gate.log`. This run includes existing
uncommitted workspace changes outside the launcher repair; those changes are
preserved separately. Real browser/handler launch, missing-handler presentation
and the absence of a visible intermediate app screen still require on-device
verification.

## Container shortcut titles

The launcher dispatcher now passes the resolved link display name into the
floating-window service. Store's shared link resolver retains the outermost
nonblank explicit `.iflink` name for storage-backed targets and nested links,
without changing the destination entry's identity or filename. The pending
overlay-permission flow also retains the title.

The window uses that name immediately and keeps it after the asynchronous
listing completes and after pull-to-refresh. Entering a child directory or
archive uses the newly opened item's name. Without an explicit label, archive
roots fall back to the source archive's filename and internal directories use
their entry name; an archive URI's empty path no longer forces "Container".
Existing launcher pins resolve their link again and need not be recreated.

Device checks remain pending for a named archive `.iflink`, nested links,
refresh, opening children and overlay permission return. No automated tests
were added or run for this repair.

`gradle :app:assembleDebug --offline --console=plain` passed; the final build
took 18 seconds. Logs: `build/launcher-container-name/app-build.log` and
`app-build-final.log` in the same directory. Existing unrelated workspace changes
were included in the build and preserved separately.

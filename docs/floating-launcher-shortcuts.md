# Drag floating icons to Home screen

Implemented 2026-11-03. The owner confirmed dragging folders and regular files
onto the launcher on-device on 2026-11-03.

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

The owner confirmed folder and regular-file drag/drop onto the launcher. Subsequent shortcut taps
and floating-folder reopening, cancellation, other item types and other launchers
were not separately confirmed. The agent had no connected ADB device during
implementation.

For the remaining drag/drop coverage, check ZIP/disk-image containers, apps
and `.iflink` from a moved/resized/scrolled floating window. Confirm its label and
icon, container reopening, regular-file dispatch, quick release/cancel, Back,
Recents/app switching, denied overlay permission, repeated drags and saved geometry.
Try another launcher and remote/provider inputs separately. Compilation alone
cannot establish cross-launcher behavior.

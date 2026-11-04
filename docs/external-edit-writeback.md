# External editing and source conflict checks

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-11-01. The user confirms real-editor handoff/return, source
conflict detection with the edited copy preserved and available to save elsewhere,
and unfinished-session recovery after app restart. Unchanged opens, timestamp-only
changes, source changes during staging, provider coverage and themed presentation
remain to be checked.

## Workflow

Saving a new copy now confirms the chosen filename/destination before publication,
including a renamed copy in the original folder. Cancelling keeps the working edits
available. The cache-capacity Copy-to-folder flow likewise confirms after folder
selection. These confirmation paths and shared Back return routing need their own
device checks; they do not extend the earlier acceptance to every provider.

### One-shot file opens and startup

The owner reported that a previously opened image launched again on a new app
start. Source inspection found that Main replayed resource intents during task
recreation and that the external picker could automatically resolve its saved
target again. These are replay paths; no connected-device trace establishes
which one caused the reported image launch.

Main now copies each incoming launch command and immediately replaces its held
intent with a plain browser MAIN intent. Restored/history launches rebuild browser
state without executing old file, link or cache-copy commands. A new launch also
invalidates earlier asynchronous resource-open/copy callbacks. The launcher
dispatcher consumes its resource URI and records that consumption in saved state;
its pending overlay-permission URI/name remain explicit state for that flow.

A picker restored with an unfinished choice can show choices, but cannot use a
saved default to auto-launch the old target. Completed/history picker restores
finish without acting on the target. External-edit restoration may review a known
session, but does not stage and launch an old source again when no session was
saved. Retained working copies stay available for their existing URI grants and
explicit recovery; their existence is not a new open request.

Device checks remain pending for plain launcher start after an image open,
process recreation, Recents, a new explicit file/pin open, picker rotation and
permission return. No automated tests were added or run for this repair.

`gradle :app:assembleDebug --offline --console=plain` passed in 26 seconds;
log: `build/one-shot-file-launch/app-build.log`. The build includes existing
workspace changes. ADB listed no connected device, so UI behavior is unverified.

### InfiniFolder-only external defaults

File menus now expose **Open externally** (use saved local preferences) and
**Open with…** (always show the picker). The picker shows other installed apps
that can handle the file; InfiniFolder and its aliases are excluded by package.
It uses themed Android controls so it can include **Remember in InfiniFolder**
with **This file only** and **All files of this type** scopes. Leaving the box
unchecked opens once without changing a saved default. Android's own associations
are never changed.

Preferences are persistent and separate for opening and editing. A file-specific
choice precedes its canonical MIME-type choice. File identity is derived from the
original provider/resource reference, not the temporary working-copy URI; remote
URI credentials are not stored as preference keys. A rename that changes that
reference needs a new file-specific choice. Different extensions sharing a MIME
type share the type-wide default.

**Open with…** can replace either scope. Saving a new type-wide choice also clears
the current file's exception, so it applies to the file being opened; exceptions
for other files remain. Saved open preferences also take priority over built-in
viewers and Termux fallback on normal file opens. Existing dedicated theme imports,
torrent handling and container-shortcut navigation retain their own routes.

**Settings → External apps and default editor…** selects/clears the default editor
and lists saved rules with individual clear controls. **Edit externally** uses
the file's edit rule, then its MIME edit rule, then the global editor. EDIT handlers
are preferred; apps declaring VIEW support can also receive the writable working
copy. A saved component is used only if it is among the compatible installed
handlers for the actual request. Incompatible, disabled or removed choices fall
through to compatible alternatives or the picker. Empty/error results are shown
honestly and do not record a successful selection.

The shared picker preserves the target URI, MIME, read/write flags and ClipData,
launches the chosen component in its own task, and saves a remembered selection
only after Android accepts the launch. This does not prove the receiving app
successfully renders or edits the file. Existing working-copy and content-check
behavior still applies. Editor selection in Settings only saves a preference;
it does not launch a fabricated document.

Device checks remain pending: one-time open, both remember scopes and persistence,
default bypass/replacement with Open with…, normal taps, editor selection/clear,
uninstalled/incompatible handlers, denied URI access, cancellation and light/dark
layout. No automated tests were added or run for this change.

The debug APK builds passed with `gradle :app:assembleDebug --offline --console=plain`.
The final build took 23 seconds; logs are `build/local-external-defaults/app-build.log`
and `app-build-final.log` in the same directory. No ADB device was available for
UI acceptance. The builds include existing unrelated workspace changes.

External file opens from the browser now use a shared external-edit activity/provider in viewer-common and a durable session manager in storage-engine. The external application receives a read/write URI for a working copy, never a writable grant to the original source.

Each session contains one working copy and the original source identity/fingerprint. Successful completion removes the session; this is not a version-history feature.

1. Read source bytes and calculate their size and SHA-256 before grabbing the file.
2. Stage the working copy under the shared cache budget, then independently check the source again. The downloaded bytes must match both checks. A source change during staging stops the handoff; retry opening the file.
3. Move the working copy into durable app-private edit storage before launching the external program. It is no longer an evictable cache entry.
4. On return, hash the local working copy on a worker. If its checksum is unchanged,
   close the return screen automatically without contacting the source. No edit or
   conflict choices are shown. The shared working copy remains available for
   delayed or repeated reads until explicit completion or discard; returning to
   the browser does not prove that the external program has finished with it.
5. Show **Save changes**, **Save a copy…** and **More…** only when local content
   differs from the baseline. Saving remains explicit. Android programs do not all
   report completion reliably; known writable editor descriptors must close before
   saving. Unfinished sessions can still be reviewed later.
6. Freeze and hash changed bytes, check that the working file stayed stable, and
   independently hash the file at its original provider/resource location again.
7. If local edits exist and the source content still matches its original size and
   SHA-256, publish through ContentReplacement with another content-revision guard
   and verify the saved bytes. Timestamp changes alone never constitute a conflict.

Preparation and the return-time checksum check run without a visible dialog.
When changed edits need attention, checking, saving and verification show status
and byte progress where available. Publication uses the backend's replacement/
recovery implementation; it may be indeterminate while the backend commits.

### Quiet external handoff (2026-11-03)

Ordinary external opens now leave the existing browser screen visible while the
working copy is prepared, while Android chooses/launches the external app, and
while the returned copy is checked. The handoff activity starts with a translucent
window and no preview, dimming or visible preparation dialog. It reveals its
themed controls only for changed content, an actual error, or an explicit visit
to the unfinished-edits screen. The later local-default picker replaces the native
chooser and filters every activity and alias in the current app package, preventing
selection of InfiniFolder and recursive external opens. The same picker protects the browser's direct file/content handoff and
its MIME-free fallback. It retains the original URI grants and clip data.

Returning through task switching also triggers the local checksum check when an
external app does not finish its activity. Reopening and session restoration
perform their file work off the UI thread.

The startup recovery notice now hashes retained local copies and includes only
copies whose bytes differ from the baseline. Timestamp-only changes and an
unchanged copy retained for an open writer do not trigger the edit notice.
Unreadable sessions stay available under Settings → Unfinished external edits;
the content check does not discard them or contact the original storage source.
For shared Termux sessions the notice checks the recorded tool working file.

Device checks remain pending: unchanged read-only open/return, editing and saving,
task-switch return, chooser cancellation, light/dark presentation and recovery
after process death. No automated tests were added or run for this follow-up.
Both debug builds passed using `gradle :app:assembleDebug --offline --console=plain`;
the final build with chooser exclusions took 30 seconds. Logs are
`build/external-open-handoff/app-build.log` and `app-build-chooser.log` in the same
directory. The build includes the workspace's existing unrelated changes. No
ADB device was connected; chooser exclusions also require on-device acceptance.

### External VIEW lifetime investigation (2026-11-03)

The owner reported that LÖVE opens to a black screen while other loaders work,
and appears to run inside InfiniFolder. Source inspection found a generic handoff
bug: we requested an activity result for `ACTION_VIEW`, then treated that result
as completion. Android explicitly warns that VIEW has no result contract and a
separate-task launch can send cancellation immediately:
[Activity.startActivityForResult](https://developer.android.com/reference/android/app/Activity#startActivityForResult(android.content.Intent,%20int)).

The official LÖVE 11.5 [app manifest](https://github.com/love2d/love-android/blob/11.5/app/src/main/AndroidManifest.xml)
declares `singleInstance`. Its [normal-build filters](https://github.com/love2d/love-android/blob/11.5/app/src/normal/AndroidManifest.xml)
accept `content` with `application/x-love-game`, matching our registry and provider.
Its [GameActivity](https://github.com/love2d/love-android/blob/11.5/love/src/main/java/org/love2d/android/GameActivity.java)
copies the supplied content stream into its own cache. InfiniFolder's old result
handler could hash an unchanged copy, remove it and revoke access before a receiver
opened the stream. This is a plausible loading failure, not a confirmed diagnosis
of the owner's black screen. The available dump predates the report and contains
no LÖVE failure trace; no device was connected for reproduction.

The shared handoff now uses ordinary `startActivity` and `FLAG_ACTIVITY_NEW_TASK`
for every external file target. It does not request VIEW results or place a normal
receiver in the file manager's task. The checksum check still runs when the sender
resumes, but unchanged bytes no longer cause revocation or deletion. Read-only
provider opens remain permitted while an edit is being checked or awaiting an
explicit save decision; new writable opens are still blocked during those phases.
Diagnostics record successful provider opens/closes and external launches without
file contents or URI credentials. There are no LÖVE package-name or format branches.

Retained shared copies occupy app storage and remain visible in Settings →
Unfinished external edits, where they can be explicitly discarded after closing
the receiver. Unchanged copies do not trigger save controls or automatic recovery
notices. Saving/discarding explicitly still releases the copy. Automatic return
cannot safely establish when a different app has finished all future URI reads.

The owner confirmed that the interpreter now opens separately from InfiniFolder.
LÖVE still fails to load the program; the owner elected to treat that remaining
failure as a LÖVE issue. Its cause remains unconfirmed, and successful LÖVE startup
is not recorded as verified.

Device checks remain pending: another external viewer/editor,
return/Recents behavior, delayed/repeated read-only URI opens, actual edit/save
and chooser cancellation. No automated tests were added or run for this follow-up.
`gradle :app:assembleDebug --offline --console=plain` passed in 17 seconds;
log: `build/love-investigation/app-build.log`. The build includes existing
unrelated workspace changes.

## Conflict and failure behavior

When both local edits and a changed source-content checksum are detected, report:

> The file changed at the source while we were working on it.

Offer:

- **Save as…** — another filename in the source folder, using exclusive/unique creation.
- **Copy elsewhere…** — choose another storage folder.
- **Overwrite…** — confirm replacement, then check the current source again before publishing against that fresh revision. Further detected changes require another decision.

Failures, unavailable/deleted sources, read-only destinations and failed verification keep the local working copy. Never upload automatically after a process restart. Access unfinished sessions through **Settings → Unfinished external edits**; startup also offers review. The user can reopen the external program, finish, copy, keep for later, or explicitly discard. Discarding requires confirmation and does not modify the source.

In the unfinished edits dialog, swipe an entry or tap its × to discard it, or choose **Discard all**. Close external editors before confirming: unsaved working copies are deleted. Sessions with a tracked writer or operation in progress are kept. App-owned shared Termux working files are removed with their sessions; older copies in Termux's private cache must be removed from Termux separately.

An inaccessible source produces an ordinary read/save failure; it is not evidence
that the original content changed. Source checks invalidate archive directory
listings and bypass reusable decompressed image snapshots and transfer-device reuse.
They compare stored bytes, not the external working copy or a timestamp-keyed
decoded-image cache.

Copying edited data uses shared transfer validation and mandatory read-back verification. A warning or incomplete copy retains the working session for review. Full-app cache-capacity copies continue using the browser's transfer queue; the external-edit screen also supports an independent Android folder-picker route.

Conflict/recovery dialogs use the common themed dialog helper. They follow application light/dark mode and the current theme palette.

The edit activity itself now uses a content-sized dialog window and the active
light/dark palette. Text, backgrounds, buttons, input dialogs and progress indicators
are themed. Progress is visible only during work; secondary actions live in nested
menus. The content scrolls when needed on small screens rather than filling a
mostly empty full-screen page.

## Data safety boundaries

- Original URI/provider identity is retained; publication works through the provider contract for local, SAF, network and writable container sources. Backend write limitations still apply.
- Dirty/unfinished external-edit sessions and transient publish snapshots are outside automatic cache eviction and expiration. They are not historical versions.
- Writable URI handles are tracked within the process. After interruption the user must explicitly finish/reopen/review the retained session; no assumption of editor completion is made.
- Content decisions use byte digests; metadata supplies progress hints only.
  Hashing a large remote file requires full reads. Unchanged local copies skip
  that remote check entirely.
- Without backend conditional-write/CAS support there is an unavoidable race between the last source check and committing a write by another machine. Expected-content checks, native replacement where supported, streaming recovery and post-write verification reduce/detect failures; this is not a distributed locking/versioning system.

## Validation

### Return crash follow-up (2026-10-26)

The `infinifolder-crash-20261026-215958-433.log` dump records
`IllegalStateException: Activity ... did not call finish() prior to onResume()
completing`. The return-time checksum check called `Activity.setVisible(false)`
during the resume sequence. Android requires an activity hidden this way to
finish before resume completes.

The checksum review now keeps the activity visible to the lifecycle while its
window is temporarily transparent, non-touchable and non-focusable, with dimming
disabled. When local bytes changed or an error requires attention, the prior
window opacity and flags are restored before the themed dialog is shown. An
unchanged result closes normally. No working-copy or source data is removed by
this window fix. The user confirmed returning without a crash and successfully
saving an edit. The 22:07 UTC dump also records a changed SFTP working copy and
successful save. Unchanged opens, timestamp-only changes, conflict choices and
the remaining light/dark presentation still need their own device checks.

The final debug APK build succeeded in 8 seconds and build.sh opened it with
xdg-open (`build/external-edit-content-review-final-build.log`). Whitespace review
passed. No automated tests were added or run. On 2026-11-01, the user confirmed
real-editor return, source-content conflict handling with edits retained, a separate
save path, and reopening unfinished work after restarting the app. Remaining device
checks include unchanged edits, timestamp-only changes, source changes during
staging, overwrite choices, other providers, and the compact dialog in light and
dark themes.

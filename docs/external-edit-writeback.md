# External editing and source conflict checks

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-26. Content-based return handling and compact themed UI
are implemented; device acceptance is pending.

## Workflow

External file opens from the browser now use a shared external-edit activity/provider in viewer-common and a durable session manager in storage-engine. The external application receives a read/write URI for a working copy, never a writable grant to the original source.

Each session contains one working copy and the original source identity/fingerprint. Successful completion removes the session; this is not a version-history feature.

1. Read source bytes and calculate their size and SHA-256 before grabbing the file.
2. Stage the working copy under the shared cache budget, then independently check the source again. The downloaded bytes must match both checks. A source change during staging stops the handoff; retry opening the file.
3. Move the working copy into durable app-private edit storage before launching the external program. It is no longer an evictable cache entry.
4. On return, hash the local working copy on a worker. If its checksum is unchanged,
   close the return screen automatically without contacting the source. No edit or
   conflict choices are shown. Known active writable handles keep the session
   available for later review; otherwise the unchanged session is removed.
5. Show **Save changes**, **Save a copy…** and **More…** only when local content
   differs from the baseline. Saving remains explicit. Android programs do not all
   report completion reliably; known writable editor descriptors must close before
   saving. Unfinished sessions can still be reviewed later.
6. Freeze and hash changed bytes, check that the working file stayed stable, and
   independently hash the file at its original provider/resource location again.
7. If local edits exist and the source content still matches its original size and
   SHA-256, publish through ContentReplacement with another content-revision guard
   and verify the saved bytes. Timestamp changes alone never constitute a conflict.

Preparation, checking and verification show status and byte progress where available. Publication uses the backend's replacement/recovery implementation; it may be indeterminate while the backend commits.

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
passed. No automated tests were added or run. Device acceptance is needed with
real external editors, read-only viewers, unchanged edits, timestamp-only changes,
actual source-content conflicts, source changes during staging, rename/copy/
overwrite choices, interrupted sessions and provider-specific save paths. Check
the compact dialog and nested actions in both light and dark modes.

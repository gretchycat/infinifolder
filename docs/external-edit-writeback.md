# External editing and source conflict checks

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

## Workflow

External file opens from the browser now use a shared external-edit activity/provider in viewer-common and a durable session manager in storage-engine. The external application receives a read/write URI for a working copy, never a writable grant to the original source.

Each session contains one working copy and the original source identity/fingerprint. Successful completion removes the session; this is not a version-history feature.

1. Check source size, modification time and SHA-256 before grabbing the file.
2. Stage the working copy under the shared cache budget, then independently check the source again. The downloaded bytes must match both checks. A source change during staging stops the handoff; retry opening the file.
3. Move the working copy into durable app-private edit storage before launching the external program. It is no longer an evictable cache entry.
4. When the external program is finished, return and choose **Finish editing and save**. Android programs do not all report completion reliably; returning from a chooser or a viewer is not proof that editing has finished. This explicit action prevents premature publication/discard.
5. Block completion while known writable editor descriptors remain open. Freeze and hash the edited bytes, check that the working file stayed stable, and independently fingerprint the source again.
6. If the working copy is unchanged, do not write the source, including when the source changed independently.
7. If local edits exist and the source still matches its original fingerprint/metadata, publish through ContentReplacement with another expected-revision guard and verify the saved bytes.

Preparation, checking and verification show status and byte progress where available. Publication uses the backend's replacement/recovery implementation; it may be indeterminate while the backend commits.

## Conflict and failure behavior

When both local edits and source changes are detected, report:

> The file changed at the source while we were working on it.

Offer:

- **Save as…** — another filename in the source folder, using exclusive/unique creation.
- **Copy elsewhere…** — choose another storage folder.
- **Overwrite…** — confirm replacement, then check the current source again before publishing against that fresh revision. Further detected changes require another decision.

Failures, unavailable/deleted sources, read-only destinations and failed verification keep the local working copy. Never upload automatically after a process restart. Access unfinished sessions through **Settings → Unfinished external edits**; startup also offers review. The user can reopen the external program, finish, copy, keep for later, or explicitly discard. Discarding requires confirmation and does not modify the source.

Copying edited data uses shared transfer validation and mandatory read-back verification. A warning or incomplete copy retains the working session for review. Full-app cache-capacity copies continue using the browser's transfer queue; the external-edit screen also supports an independent Android folder-picker route.

Conflict/recovery dialogs use the common themed dialog helper. They follow application light/dark mode and the current theme palette.

## Data safety boundaries

- Original URI/provider identity is retained; publication works through the provider contract for local, SAF, network and writable container sources. Backend write limitations still apply.
- Dirty/unfinished external-edit sessions and transient publish snapshots are outside automatic cache eviction and expiration. They are not historical versions.
- Writable URI handles are tracked within the process. After interruption the user must explicitly finish/reopen/review the retained session; no assumption of editor completion is made.
- Size/timestamp checks alone are insufficient; byte digests are compared as well. Hashing a large remote file requires full reads.
- Without backend conditional-write/CAS support there is an unavoidable race between the last source check and committing a write by another machine. Expected-content checks, native replacement where supported, streaming recovery and post-write verification reduce/detect failures; this is not a distributed locking/versioning system.

## Validation

APK compilation completed and build.sh opened the APK with xdg-open. No automated tests were added or run. Device acceptance is needed with real external editors, read-only viewers, unchanged edits, source conflicts, source changes during staging, rename/copy/overwrite choices, interrupted sessions and provider-specific save paths.

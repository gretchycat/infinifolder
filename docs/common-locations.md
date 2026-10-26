# Common locations and destination suggestions

Implemented 2026-10-26; handset acceptance pending. Debug APK builds validate
compilation only. No automated tests were added or run for this change.

## Persistent folders

Settings → Common locations manages reusable folder references. Add folder uses the
existing directory picker and can save readable destinations even when they are
read-only. Tap a saved entry to rename it, change its folder, or remove it. Removal
and Clear recent affect only this private list, never the referenced storage.

Remember chosen destinations automatically is on by default. Folder selections
and successful copy/move destinations update usage; successful transfers with skipped
unreadable files can still contribute their destination. Common folders sort by
recorded usage, then recency. Automatically collected history is bounded to 24 folders;
explicitly saved/edited entries are retained. Clear recent removes automatic entries
and resets usage for manually saved ones. Disabling automatic remembering prevents
new automatic entries; already saved entries still update their usage/evidence.
Removed automatic locations may be collected again if used while remembering is on.

The folder picker includes a Common locations button. Choosing one updates the
selection, checks its capabilities, and reveals/expands its tree node when loaded.
Read-only entries remain unavailable for write destinations. Editing this list does
not replace the last chosen transfer destination. The common list is persistent and
separate from temporary pinned containers.

The application-owned CommonLocationsPreferences stores URI, label, usage, recency,
manual-retention status and the time of the last observed successful direct random
write. Labels remain editable. Credentials stay in the existing connection vaults;
password-bearing storage URIs, including nested archive sources, are rejected.

## Suggestions in the random-write fallback warning

The original warning named storage types generically. It now also lists concrete
common folders, Internal shared storage when authorized, and saved connection roots.
Saved connections remain managed through their existing connection settings; adding
or removing a common-folder reference does not change a connection definition.
Suggestions deduplicate provider references, exclude the rejected destination, and
prioritize common folders with prior direct-write success. Up to ten candidates
are checked with two background workers. The prompt is usable while checks run.

Each candidate is queried through the common capability API. Listing, creation and
TRANSFER_TARGET support must not be explicitly unsupported before the entry is
selectable. Unavailable or incompatible candidates remain disabled with a reason.
Unknown random-write support is labeled as needing a file-level check. Tapping a
candidate opens the existing target picker at that folder; it does not start a copy.

Successful direct random-access transfer and destination close record evidence for
the containing folder on a separate worker. This includes automatically resumed
transfers. Evidence means that actual data was written previously, not that current
permissions are guaranteed: the suggestion refreshes capability reports, and the
actual file creation/open/resize/write still decides current access. Capability
checks do not create probe files. Offline checks do not block browsing or unrelated
transfers, and pending checks are interrupted when their view closes.

## Remaining device checks

- [ ] Add, rename, replace and remove folders; restart and confirm persistence.
- [ ] Confirm automatic remembering, ranking, Clear recent and read-only selections.
- [ ] Select a common folder in the picker and confirm permission failures stay clear.
- [ ] Trigger a torrent fallback warning and choose an existing writable connection.
- [ ] Compare previously successful, unknown SAF, read-only and offline candidates.
- [ ] Check light/dark themes, long labels and a small portrait display.

See [storage API consistency](storage-api-consistency.md) and
[concurrent activity](concurrent-activity.md).

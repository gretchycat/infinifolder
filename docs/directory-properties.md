# Directory Properties

InfiniFolder 0.1.5 keeps the Properties dialog open when recursive folder totals
cannot be calculated. Android can expose a directory in a listing while denying
access to a descendant, such as `Android/data`; remote providers can also fail or
return incomplete listings.

Previously, the recursive size/count worker let a provider IOException escape
its executor, terminating the app. The app now contains ordinary scan exceptions
and displays **Unavailable** for size and item totals, plus the provider's reason
under **Folder scan**. Other metadata and the Close button remain available.
Readable directories and multi-item selections retain their existing totals.

The app uses libinfinicore's existing strict traversal API. It does not treat a
failed, loading or partial listing as complete, silently report zero, or bypass
Android permissions. Transfers and destructive-operation safety checks retain
their existing behavior. The provider error remains in the diagnostic log.

Dismissing Properties cancels the scan through a shared atomic flag. Interrupted
or cancelled work produces no completed totals or failure update. UI updates also
check the Activity lifecycle and whether the dialog is still visible.

`FolderPropertiesStatsTest` exercises the real recursive library calculation
through a fake storage provider: denied nested `data`, readable and multi-file
totals, loading/partial/unsupported listings, direct permission/network exceptions,
dismissal before/during listing, cancellation during failure, and interruption.
On 2026-11-19, `./build.sh --offline --max-workers=1 --console=plain` assembled
version 0.1.5 (code 6) and passed all 174 tests: 67 app (including the seven new
Properties cases), 16 hex-viewer and 91 native storage-engine cases. Dependency
pin checks and ecosystem/document-link validation passed.

The Android build and host tests do not replace a handset check of opening and
closing Properties on a restricted directory; no ADB device is connected here.

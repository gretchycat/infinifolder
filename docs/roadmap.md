# Roadmap

## Current feature requests and reliability work

See the [feature to-do list](feature-todo.md) for temporary pinned container targets, wildcard file selection, expanded sorting, and the active pane-swipe/tree-drop reliability work. The latest user test reports FTP working; the earlier FTP error is historical rather than an active reproduced blocker.

## Phase 1 — useful file manager (in progress)

- [x] Native Android application skeleton; min API 26 / target API 35.
- [x] SAF folder picker and persisted user-granted tree URI.
- [x] Browse SAF children without blocking the UI thread.
- [x] Grid/list display, name/date sorting, file metadata and `ACTION_VIEW`.
- [x] Configurable 32–96 dp grid icons with icon-relative cell spacing and responsive column count.
- [x] Sampled image previews in grid and list entries, decoded asynchronously with a bounded thumbnail cache and placeholder fallback.
- [x] Light/dark palette preference and restore selected location across launches.
- [x] Provider-neutral resource model, capability/result types, SAF provider extension point.
- [x] Location source registry hooks for SMB/Samba, FTP, SFTP/SSH, NFS, WebDAV, and cloud storage; save and list non-secret connection definitions without claiming protocol support.
- [x] Reuse applicable persisted SAF grants for known folders; keep saved roots in the tree without duplicate cards and mark sources still needing a grant. Match both standalone and tree-qualified SAF document URIs, and highlight the active tree location.
- [x] Display Android storage volumes in the location browser, with per-volume SAF grant flow.
- [x] Render storage sources as a Home root using the same grid/list presentation; mirror all virtual root entries in a connected, boxed plus/minus tree with on-demand unbounded-depth directory expansion. Use full-screen tree/file-pane switching on compact portrait screens and retain split view on wider or landscape layouts.
- [x] Add a Virtual root breadcrumb; browse actual shared-storage contents and discover installed `DocumentsProvider` roots, invoking the SAF picker only when an existing grant cannot access the target.
- [x] List real primary shared-storage files and folders under Internal shared storage, with optional All files access on Android 11+ and no duplicate grant nodes; selecting the tree root or shared-storage branch updates the right-side location.
- [x] Application public lists app-exported SAF document sources (including Termux-style home providers), requests permission per provider/root, and opens the actual returned URI. Preserve existing grants; keep Android/media folders in the shared-storage hierarchy. Launcher integration remains planned for Phase 3.
- [x] Keep Virtual root tree entries and ordering aligned with the Virtual root grid/list; keep removable-volume SAF grants at Virtual root rather than grouping them under primary shared storage.
- [x] Use the toolbar plus to add saved connections at Virtual root and create directories while browsing (provider permitting).
- [x] Show provider-reported hierarchical paths for saved SAF locations; provide long-press Properties and confirmed grant removal.
- [x] Worker queue abstraction and SAF same-provider recursive copy, copy-then-delete move, rename and delete.
- [x] Reuse the connected, expandable virtual-root tree for copy/move destinations; exclude the source parent and directory source subtree.
- [x] Fix virtual/locked tree taps and locked plus activation; add regression tests for missing resource identities and destination restrictions.
- [x] Direct primary-storage provider with FileProvider opening, create/rename/delete, temporary copy destinations, content verification, and filesystem safety tests.
- [ ] Validate the system All files access grant/deny/revoke flows, actual root/Downloads browsing, and external opening on a connected device.
- [x] Durable SQLite schema for source-to-stage identity and dirty/write-back tracking.
- [ ] Activity-independent application-scoped operation panel, durable queue recovery, foreground execution/notifications, byte progress, pause/cancel, retries, and conflict choices.
- [x] Distinguish unavailable/loading/partial SAF listings from confirmed empty folders; bounded retry in the file pane and tree; avoid picker-return navigation cancellation.
- [ ] Validate Termux Monet grant/reopen and folder contents on a connected device, alongside other SAF providers and removable storage.
- [ ] Create folder, multi-select, capability-aware menus, refresh observation, keyboard/accessibility and responsive layout review.
- [ ] Complete staging service and validate editor/provider contracts before remote integration.
- [ ] Emulator/device acceptance including a large transfer while browsing or leaving the Activity.

Verification on 2026-10-19: `:app:testDebugUnitTest` and `:app:assembleDebug` passed (22 tests, zero failures). ADB reported no connected devices; actual Termux Monet picker/grant/reopen behavior, touch interaction, settings grant/denial, and external FileProvider opening remain device acceptance checks.

## Phase 2 — actions and customization

Shortcuts and intent actions; InfiniFolder-scoped associations; application/category logical provider; portable metadata parsing; icon overrides and file type rules; hidden-file controls; full background operations and conflict workflow; staging/download/edit/write-back with source revision checks.

## Phase 3 — launcher supplement

Pinned shortcuts, directory widgets, compact windows, and explicitly opt-in overlay mode with regular-activity fallback. This must not replace the selected home launcher.

## Future

SFTP/SSH, SMB, WebDAV, additional cloud providers, search, bookmarks, recents, archive support, trash/undo, metadata and icon rule import/export, and cache policy controls.

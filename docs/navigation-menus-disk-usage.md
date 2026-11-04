# Navigation, menus, selection and disk usage

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

Updated 2026-11-04. See [current status](project-status.md) for acceptance distinctions.

## Menus and navigation

Hamburger and overflow open the same nested File/Edit/View/Select/Settings menu, with right-aligned submenu triangles. The Tree/Files toolbar label switches panes. File → New contains folder, link, external storage connection links and supported document/archive templates. Link creation offers a protocol dropdown for custom URI, SFTP/SSH, SMB/Samba, WebDAV and FTP/FTPS, with saved-connection selection or the matching setup form. The same choices are available when adding container links at Virtual root. Unsupported archive writers are disabled explicitly. View exposes hidden files, sorting and themes; Settings manages default viewers/apps; Select offers mode and wildcard/regex matching in the current folder. Most-used sorting remains future work.

Persistent half-size pins sit between toolbar and breadcrumbs; the row disappears when empty except during a drag. Pin to top/Unpin are long-press actions. Dropping onto the pin board always creates links. Other breadcrumbs, containers and visible tree nodes accept transfers and linger navigation. The navigation tree has no long-press actions and uses the common theme without a separate green tint.

The pin-board strip and the full breadcrumb box use the theme's alternate
background (10% toward white on dark backgrounds, toward black on light ones).
The file icon/list area keeps the normal theme background. Long-press file,
folder, pin and selection menus have a leading emoji/symbol column; nested browser
menus use the same action icons. Connection/location and disk-usage context menus
include icons too. Labels/indexes still identify the original actions, independently
of the decorative icon. Screen readers receive the action label once.

Hold countdown covers the icon; release after the hold opens the menu, movement begins drag. Selected sets drag together. Same-device drops move, cross/unknown-device drops copy. Source-self drops open the menu. Tree/file edges autoscroll and tree linger expands paths. Latest drops, autoscroll and pane switching are user-confirmed; broader backend/orientation checks remain.

Drag hover now waits **2.1 seconds** before opening a container (previously 700 ms),
and **1.95 seconds** before expanding a tree node (previously 650 ms). Moving away
cancels the pending action. Pin-board drops still do not navigate on hover.

## Virtual-root shortcut actions

Built-in and discovered location tiles have long-press **Open**, **Pin to top**/
**Unpin**, and **Delete link** actions. Existing connection/source actions remain
under **More options…**. Pinning browser pages uses a normal public `.iflink` with
an `infinifolder-location://<key>` target. `BrowserLocations` identifies built-in
pages; their existing browser controllers perform navigation, without pretending
these pages are readable storage backends. Storage permissions are checked by the
normal navigation flow. Unknown page targets show an error.

Built-in shortcut deletion hides the tile and its Virtual-root tree entry without
removing storage, credentials or pinned copies. **View → Restore Virtual root
shortcuts** restores these hidden tiles. Stored user `.iflink` items retain the
existing file menu; **Delete link** deletes that link file after confirmation,
leaving its target intact. Pin-board `.iflink` menus expose Delete link too.

Root tiles, tree entries, pinned page links and launcher links use the shared
icon resolver and theme logical keys. Stored root links supply their actual link
entry to tree icon resolution, preserving embedded/URI overrides. The Pin board
page retains directory-cover resolution. Page links opened from the launcher use
the full browser; actual storage containers retain floating-window behavior.

Build evidence for these changes and startup freeze diagnostics:
`build/docs-audit/virtual-root-pin-repair-build.log`; the app and combined
standalone-host debug build command passed. Documentation link checks found no
missing files or anchors. No automated tests were added
or run. Root long-press actions, hide/restore, pin navigation, theme consistency,
launcher routing and hover timing remain unverified on-device.

### Reported startup freeze/crash

The owner reports a freeze followed by a crash after build `d5b9d4b`. The latest
available exported fatal log (`infinifolder-crash-20261104-213324-073.log`) predates
that build; it does not establish the cause of this failure. No ADB device is
connected, and app-private current diagnostics cannot be read from this workspace.
The crash is **not yet diagnosed or confirmed fixed**.

The existing UI monitor missed pre-resume startup work. It now covers application
and activity startup as well as resumed screens. Debug builds capture bounded,
sanitized thread stacks after a sustained UI stall and export
`infinifolder-stall-<timestamp>.log` to the already configured debug folder, without
waiting for the debug button. This exports logs only. Private pending evidence is
retained if export cannot complete and retried on the next launch. Release builds
do not perform this automatic stall export. Actual freeze capture/export and the
reported crash still require device evidence.

Startup recovery also covers exceptions and linkage failures during application
initialization: these are saved before opening the lightweight recovery screen,
without deleting settings or user files. The fatal handler is installed before
diagnostic initialization. Transparent dispatch activities no longer mark startup
successful; readiness is recorded after a content activity's full resume callback.
This repairs gaps in error reporting, not the still-unidentified underlying crash.
Missing bundled-theme installations are created from APK assets; absent pin-board
directories and legacy preferences already have creation/migration paths. No
manual copying of those files is required when upgrading. A current recovery
report is still needed to establish whether a particular saved file is involved.

Recovery-build evidence: `gradle :app:assembleDebug :viewer-host:assembleDebug
--offline --console=plain` (`build/docs-audit/startup-recovery-build.log`). Android
ART verification compilation of the preceding APK completed without reported
verifier errors; this does not exercise application initialization or UI startup.
Documentation link checks passed (133 files, 632 links). No automated tests were
added or run for this change; device startup/recovery checks remain pending.

## Public pin board directory

The compact pin board is a view of **Internal storage → InfiniFolder → Pin board**.
Virtual root includes a **Pin board** directory entry. It uses the existing local
storage provider, `.iflink` format, shared file cards, artwork resolver and rename/
properties APIs. Normal directory browsing exposes the usual file operations;
the compact board keeps Open, Rename, Properties and Unpin readily available.
Unpin deletes the board's link, never the linked target. Non-link data placed in
this public folder can be opened/renamed normally; removing it requires the
ordinary directory view instead of the shortcut-only Unpin action.

Pin to top creates a real `.iflink` containing the target and existing embedded
artwork when available. Names are made unique without overwriting other links.
Dragging files, folders, archives, remote items or an existing link onto the compact
board creates `.iflink` files too, including selected sets. Drops on a pinned icon
or the space between icons add links to the board instead of transferring into
that icon's destination. An empty board shows **Drop here to pin a link** during
an in-app drag. Dropping onto the public Pin board directory via its Virtual root
entry, tree, breadcrumb or file pane follows the same link-only rule. Pinned icons
do not navigate on drag hover. Outside the board, existing transfer rules remain.
An existing link retains its destination, display name and embedded artwork;
duplicate targets are skipped, invalid links and write failures are reported.
Original items are never moved or copied by a pin-board drop. Work runs off the
UI thread through existing link/storage APIs, then refreshes the compact board
and the open board directory. This currently handles in-app drags, not arbitrary
external-app ClipData.

**Rename** on a board `.iflink` updates only its JSON display name, including when
invoked from the ordinary board directory listing. It preserves its filename,
position, target, embedded image and extra JSON fields, using the shared checked
replacement API. The dialog edits the display name without an extension or order
prefix. Other files retain ordinary filesystem Rename. The label supplies the
pin's tooltip/navigation title too.
Properties uses the normal link editor, including embedded icons. Changes made
in another app update the board through a debounced directory observer; returning
to the browser refreshes it too. Hidden files/cache sidecars are excluded from
the compact board. Files remain public and can be copied/backed up normally.

Existing preference pins migrate once, on a worker, without connecting to their
remote targets. The old payload remains a backup; migration is marked complete
only after every link is saved. Partial retries detect already migrated targets.
Storage failures retain the in-memory/legacy pins and record a diagnostic;
explicit Pin/Open/Unpin failures show an error. The public folder requires normal
shared-storage access. After migration, deleting the directory recreates an empty
board rather than resurrecting old preference entries.

### Pin order and filename audit

The board always displays filename order, ignoring case, independently of the
file pane's saved sort preference. Valid board `.iflink` files have numeric prefixes
such as `10 Music.iflink`, `20 Books.iflink`, `30 Pictures.iflink`. A worker audits
these names on load and after updates. Old unnumbered links get a prefix after
their existing label has been preserved in the JSON. Padding expands as needed
(`010`, `020`, …, `100`) to preserve alphabetical order; there is no fixed digit
count. Existing padding is retained when a high-numbered pin is removed.

Hold a pin and move to drag; hold and release opens its existing menu. Drop in
the middle of another pin to swap their existing order numbers. Drop between
pins to insert into an available gap (for example `15` between `10` and `20`).
Adjacent swaps also reuse their existing numbers. Only an exhausted insertion gap
requires a full reindex, restoring spacing of 10 between every pin. The audit
preserves valid gaps; malformed/duplicate numbering is repaired. Renaming a
display label does not renumber the board. Incoming batches share one destination
snapshot/number allocation and receive one final audit instead of re-enumerating
the board for each new shortcut.

The strip scrolls horizontally with a visible scrollbar and preserves its scroll
position on refresh. During a drag, **First** and **Last** spaces allow insertion
at either end. Holding near an edge scrolls to further pins. A live preview slides
existing cards into the candidate order using native view animations, shows a
faded source placeholder and marks insertion boundaries. Stable layout slots
determine the drop independently of animations. Cancel restores the original
view; only a drop changes stored order.

Ordering uses exclusive storage-provider renames, with a bounded hidden
`.pin-board-order.json` recovery plan and temporary link names. A restart resumes
an interrupted two-phase rename rather than overwriting a conflicting file. The
plan and temporary names disappear after successful completion. Failure is
reported, and the plan is retained when recovery cannot finish (for example if an
external app removed a participating link). Ordinary non-link files and invalid/
unreadable `.iflink` data are not renumbered; unreadable links cannot be reordered.
Other applications referencing a board link by its filename may need that reference
updated after an order change; the linked target itself is unchanged.
Renaming a label or ordering filenames invalidates only the affected link artwork,
parsed destination and connection-badge cache entries. This also handles swaps
whose links happen to share the same size/timestamp, while retaining other cached
icons and decoded targets.

Build evidence: `build/docs-audit/ebook-styles-pin-board-build.log` and
`build/docs-audit/pin-board-iflink-build.log`. Migration,
external edits, Rename/Properties/Unpin, restart, permissions, drag/drop and title
behavior still need device acceptance. No automated tests were run for this change.

The ordering, visual preview, menu-icon and alternate-background follow-up uses
`build/docs-audit/pin-board-order-build.log`: app and combined standalone debug
builds passed (`BUILD SUCCESSFUL in 28s`, 523 tasks). Documentation link checks
found no missing files or anchors. No automated tests were added or run;
drag insertion/swapping/cancel, First/Last drops, edge scrolling, batch pinning,
restart recovery, label-only Rename, theme/rotation and menu action checks remain
unverified on-device.

## Selection

Two-finger dragging draws a selection rectangle in ordinary or selection modes, grid/list and selectable root links. Its anchor is the midpoint when the second finger lands. It preserves initial selection, adding intersecting visible cards and removing newly covered cards when moved away. First update enters select mode. Lifting either finger finishes and consumes the remaining touch. One-finger scrolling remains available. Exit selection mode appears first in applicable long-press menus and the main menu, clearing selection. Both features are user-confirmed.

## Disk usage explorer

Properties on a directory opens the explorer. Recursive scans compute folder logical-byte totals, but the graph displays **only immediate children** of the current focus. There are no nested descendant rectangles. Directories have thick outlines; archives/packages/disk images remain leaf files at stored length. Explicitly browsed mounted directories can still be scanned.

Tap a folder to animate into its contents (450 ms). Large lists use navigable Other groups; synthetic groups cannot move/delete. Direct files show size. Partial immutable scan snapshots arrive roughly every 750 ms between completed listings and are labeled incomplete. Navigation works during scanning; final results preserve focus. Move/delete wait for scan completion.

Parent folder zooms to an in-memory parent or resolves/scans the provider parent above the initial scan root. A true storage root reports no parent. Header Back, Browse folder and system Back return to the file view in the focused folder. Long press actual nodes offers Browse/Move/Delete; deletion keeps the explorer open and rescans the surviving focus.

Scans are bounded to 200,000 nodes/depth 128 and do not follow symlinks. Unreadable/incomplete totals are lower bounds; logical byte sizes differ from allocated blocks and hardlinks may count more than once. Live changing directories are not atomic snapshots.

Image-viewer Back is user-confirmed working.

## Remaining acceptance

Parent beyond initial scan root, latest remote breadcrumb fix, menu/templates/pattern options, pin-board selected-set link drops and broader permission/theme/queue regressions. Current-folder explorer behavior is broadly accepted. Compilation evidence lives in the individual implementation records.

## Copy into the source directory

**Copy to…** can select the current/source directory. The confirmation appears
before a transfer is submitted and explains that existing files are kept and copies
receive unused names. Clipboard Paste uses the same confirmation/transfer path.
Store already reserves collision names such as `name (2).ext`, preserves the extension,
validates destination constraints and uses exclusive creation where supported;
no second naming implementation was added to the UI. **Move to…** still blocks the
current folder. Dropping files into their current directory also requests a
confirmed copy with unused names; dropping directly on the source icon still opens
its context menu.

The 2026-11-04 app/combined-host build passes. The owner confirms copying after
the same-directory drag correction. Confirmation Cancel, broader Copy to/Paste
variants, repeated copies, selected sets and backend-specific publication remain
separate checks. No automated tests were rerun for this acceptance record.

## Tree ordering — 2026-11-04

The sidebar and folder picker share one alphabetical sibling order, ignoring case.
It applies to Virtual root sources, application categories/public directories,
local/remote folders, archives and asynchronously loaded children. Exact name and
node key break ties deterministically; the synthetic **Create directory** action
stays last. Sorting uses display views of existing nodes, preserving node identities,
expanded branches and selection. Newly created folders use the same comparator.
Branch-line flags are computed in display order. The file pane retains its own
selected sort settings.

The app debug build passes. No automated tests were added or run; root/expanded
ordering, partial remote listings, duplicate/case-varied names, picker creation and
selection/expansion retention remain device checks.

# Navigation, menus, selection and disk usage

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

Updated 2026-10-24. See [current status](project-status.md) for acceptance distinctions.

## Menus and navigation

Hamburger and overflow open the same nested File/Edit/View/Select/Settings menu, with right-aligned submenu triangles. The Tree/Files toolbar label switches panes. File → New contains folder, link, external storage connection links and supported document/archive templates. Link creation offers a protocol dropdown for custom URI, SFTP/SSH, SMB/Samba, WebDAV and FTP/FTPS, with saved-connection selection or the matching setup form. The same choices are available when adding container links at Virtual root. Unsupported archive writers are disabled explicitly. View exposes hidden files, sorting and themes; Settings manages default viewers/apps; Select offers mode and wildcard/regex matching in the current folder. Most-used sorting remains future work.

Persistent half-size container pins sit between toolbar and breadcrumbs; the row disappears when empty. Pin to top/Unpin are long-press actions. Pins, breadcrumbs, containers and visible tree nodes accept transfers and linger navigation. The navigation tree has no long-press actions and uses the common theme without a separate green tint.

Hold countdown covers the icon; release after the hold opens the menu, movement begins drag. Selected sets drag together. Same-device drops move, cross/unknown-device drops copy. Source-self drops open the menu. Tree/file edges autoscroll and tree linger expands paths. Latest drops, autoscroll and pane switching are user-confirmed; broader backend/orientation checks remain.

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
Rename updates its filename and display-name field; `.iflink` is retained when
omitted. The link label also supplies its tooltip/navigation title. If updating
the label fails after the filename was renamed, the UI reports that partial result.
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

Build evidence: `build/docs-audit/ebook-styles-pin-board-build.log`. Migration,
external edits, Rename/Properties/Unpin, restart, permissions, drag/drop and title
behavior still need device acceptance. No automated tests were run for this change.

## Selection

Two-finger dragging draws a selection rectangle in ordinary or selection modes, grid/list and selectable root links. Its anchor is the midpoint when the second finger lands. It preserves initial selection, adding intersecting visible cards and removing newly covered cards when moved away. First update enters select mode. Lifting either finger finishes and consumes the remaining touch. One-finger scrolling remains available. Exit selection mode appears first in applicable long-press menus and the main menu, clearing selection. Both features are user-confirmed.

## Disk usage explorer

Properties on a directory opens the explorer. Recursive scans compute folder logical-byte totals, but the graph displays **only immediate children** of the current focus. There are no nested descendant rectangles. Directories have thick outlines; archives/packages/disk images remain leaf files at stored length. Explicitly browsed mounted directories can still be scanned.

Tap a folder to animate into its contents (450 ms). Large lists use navigable Other groups; synthetic groups cannot move/delete. Direct files show size. Partial immutable scan snapshots arrive roughly every 750 ms between completed listings and are labeled incomplete. Navigation works during scanning; final results preserve focus. Move/delete wait for scan completion.

Parent folder zooms to an in-memory parent or resolves/scans the provider parent above the initial scan root. A true storage root reports no parent. Header Back, Browse folder and system Back return to the file view in the focused folder. Long press actual nodes offers Browse/Move/Delete; deletion keeps the explorer open and rescans the surviving focus.

Scans are bounded to 200,000 nodes/depth 128 and do not follow symlinks. Unreadable/incomplete totals are lower bounds; logical byte sizes differ from allocated blocks and hardlinks may count more than once. Live changing directories are not atomic snapshots.

Image-viewer Back is user-confirmed working.

## Remaining acceptance

Parent beyond initial scan root, latest remote breadcrumb fix, menu/templates/pattern options, pins/selected-set transfers and broader permission/theme/queue regressions. Current-folder explorer behavior is broadly accepted. Compilation evidence lives in the individual implementation records; this docs update ran no tests/build.

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

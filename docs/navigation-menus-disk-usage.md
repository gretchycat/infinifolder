# Navigation, menus, selection and disk usage

Updated 2026-10-24. See [current status](project-status.md) for acceptance distinctions.

## Menus and navigation

Hamburger and overflow open the same nested File/Edit/View/Select/Settings menu, with right-aligned submenu triangles. The Tree/Files toolbar label switches panes. File → New contains folder, link, external storage connection links and supported document/archive templates. Link creation offers a protocol dropdown for custom URI, SFTP/SSH, SMB/Samba, WebDAV and FTP/FTPS, with saved-connection selection or the matching setup form. The same choices are available when adding container links at Virtual root. Unsupported archive writers are disabled explicitly. View exposes hidden files, internal viewing, sorting and themes; Select offers mode and wildcard/regex matching in the current folder. Most-used sorting remains future work.

Temporary half-size container pins sit between toolbar and breadcrumbs; the row disappears when empty. Pin to top/Unpin are long-press actions. Pins, breadcrumbs, containers and visible tree nodes accept transfers and linger navigation. The navigation tree has no long-press actions and uses the common theme without a separate green tint.

Hold countdown covers the icon; release after the hold opens the menu, movement begins drag. Selected sets drag together. Same-device drops move, cross/unknown-device drops copy. Source-self drops open the menu. Tree/file edges autoscroll and tree linger expands paths. Latest drops, autoscroll and pane switching are user-confirmed; broader backend/orientation checks remain.

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

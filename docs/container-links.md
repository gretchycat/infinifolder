# Container links and Virtual root

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

## Implemented

- Virtual root accepts container link records, not ordinary file payloads. Create, copy, move, rename and delete operate on link metadata; deleting a link preserves its target.
- The unified File → New → Link flow handles generic URI shortcuts and protocol-specific SFTP, FTP/FTPS, SMB and WebDAV connections. Android folders use the system grant picker.
- Saved network connections are migrated into Virtual root links. Deleting their link does not remove saved credentials or recreate the link on the next refresh.
- Long press a remote connection link and choose **Edit connection…** to edit its existing protocol settings. Shared links to that connection continue using the same saved account; link paths remain independent.
- Public network URIs bind to saved accounts. Ambiguous account matches require a choice; passwords belong in connection settings, not link URIs.
- Container links support navigation, selection and drag targets. Dropping actual containers onto Virtual root creates links. Ordinary files are rejected.
- Saving network link metadata does not contact the server. Access is checked when opening it.
- Magnet links open a read-only torrent browser. Metadata is loaded before listing; copying a file downloads only that selected torrent file and verifies it before exposing its contents.

## Other current changes

- Disk usage explorer keeps its window open after deletion, rescans and preserves a surviving focus. Recursive directory regions have thick outlines drawn over descendants.
- Transfer completion system notifications are user-confirmed. Optional ordinary-copy verification is remembered; moves retain mandatory verification.

## Remaining acceptance checks and limits

- Confirm the new menu routes, remote connection editing, root link copy/move/delete and disk usage refresh on device.
- Built-in local and authorized Android storage navigation entries remain system-managed. Mutable root link records are separate from those entries.
- Torrent device acceptance, seekable piece reads and Downloads organization remain future work. Floating-browser/home-shortcut code exists and awaits handset acceptance; widget support remains future work in feature-todo.md.

## SMB direct-open correction

SMB metadata lookup now queries a known target directly instead of listing its parent. Opening a configured share no longer requires server-wide share enumeration permission. This also avoids scanning a whole parent directory for individual file metadata. Share roots cannot be renamed, moved or deleted through these metadata capabilities. Direct SMB opening is user-confirmed.

## Duplicate Virtual root breadcrumb correction

The 20261024-201454-811 screenshot shows `Virtual root › Virtual root` and only the three stored connection links. Tree routes had begun including the mutable root provider URI in ordinary history, although breadcrumbs separately render Virtual root. Selecting that history entry bypassed the normal Home root and listed the raw link provider. Tree history now excludes that URI, restored histories are repaired, and tree/history/load entry points route it to the normal Virtual root view. The user confirms this root-navigation fix.

## Saved links missing from the tree after startup

Restoring or quickly opening Internal storage advanced the folder-list generation, discarding the pending Virtual root source-discovery result. The initial placeholder tree therefore omitted saved links until Virtual root was reopened. Source discovery now has its own generation: newer source discovery supersedes older discovery, while folder navigation does not cancel it. The tree updates in place without changing the current file location. The user confirms this root-navigation fix.

## Unrelated Internal storage breadcrumb before a remote connection

The 20261024-210431-755 screenshot shows `Virtual root › Internal shared storage › Boson` while listing the remote filesystem. Container links used ordinary push navigation while Home retained earlier folder history. Container-link jumps now reset folder history and logical local-storage prefixes before opening the target. Restored mixed local/network history is pruned at network-connection boundaries, so unrelated local storage is not displayed as a remote connection's ancestor. Device acceptance is pending.

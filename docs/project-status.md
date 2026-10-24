# Project status

Updated 2026-10-24. This is the current status reference. Checked implementation work is not automatically device acceptance. Dated audits, build counts and dump reports describe the revisions they examined.

## Confirmed on the handset

- Local/SAF and network transfers exercised; FTP works and SMB direct share opening is fixed. The earlier FTP 502 unique-create rejection is historical.
- ZIP file/directory writes, FAT size-limit preflight, link creation and copy-through-link behavior work. FAT16 small-file copying and batched deletion are substantially faster and accepted.
- UFS/compressed UFS, NTFS, HFS/HFS+/HFSX and general RAR/ARJ/CAB browsing work within their documented subsets. Whole-disk partition browsing and VDI/LVM navigation work; this does not establish every variant or metadata case.
- FAT deleted-file copy-out and partial recovery work on the delivered safety fixtures. Recovery never proves original bytes merely from free allocation; fragmented reconstruction and exFAT recovery remain unsupported.
- Tree drops and edge scrolling, recent pane switching, two-finger rectangle selection, exit-selection menus and Virtual root links work. Duplicate-root breadcrumbs and missing startup tree links are confirmed fixed.
- Disk usage's current-directory graph is broadly accepted. Size scans recurse into actual directories, while the graph displays one level; archives remain leaf files.
- Background completion notifications, automatic successful-dialog dismissal and the debug toolbar button work. A large SFTP copy was independently reported byte-identical by diff.
- Latest activity-light correction is confirmed: green pulses follow reads; pending loading without read progress is steady dim green; the light returns idle when loading ends. Red file operations and amber verification have separate theme fields.

## Implemented; focused acceptance still needed

| Area | Remaining acceptance |
| --- | --- |
| Shared disk cache | 200 MiB total, separate file limit, LRU before prompting, active leases, copy/raise choices, six-hour expiry, restart cleanup and themed dialogs |
| External editing | Real editor handoff/return; explicit Finish editing and save; unchanged files; source conflicts; save-as/copy/overwrite; interrupted sessions across providers |
| Navigation | Image-viewer Back; latest unrelated Internal storage breadcrumb removal before remote connections; disk usage Parent above its initial scan root |
| Menus and targets | New templates, wildcard/regex, sort directions, pins/hover and selected-set drops across backends/orientations |
| Transfer UI | Stable sizing, per-file/overall and verification progress, remembered verification switch, queues/cancellation and denied notification permissions |
| Storage coverage | Low-space/interrupted compressed-container writes, FAT12/FAT32 batches, retained-file hashes and format-specific edge fixtures |

Ordinary copy verification is remembered and optional; moves and recovery retain required checks. Live VM copying is best effort: source changes refresh caches and warn, but do not provide a coherent snapshot.

## Remaining engineering work

1. Complete backend parity: per-entry mutation capabilities, mounted cache invalidation, shared action dispatch, device/session cancellation and ownership, bounded viewer materialization/direct seekable integration, and mounted case/link semantics.
2. Preserve more metadata where destinations support it: permissions, ownership, ACLs, extended attributes and hardlinks; report unsupported semantics consistently.
3. Add durable operation queue recovery, general pause/resume/retry and richer collision choices. The current coordinator/foreground service survives Activity changes, not process death.
4. Extend format coverage: multipart ZIP/7z, maintained native archive engine, NTFS/HFS/UFS subset gaps, advanced/checksummed LVM, malformed/backup partition tables and other archive readers. See format-specific checklists.
5. Expand FAT recovery only with explicit safety evidence: fragmented files, deleted directory reconstruction, exFAT and selected-set partial recovery.
6. Revisit measured performance: non-ext image directory enumeration, large grid/tree rendering, UI-thread icon reads and remaining cache/session lifetimes. Accepted FAT16 improvements do not establish all filesystem/backend combinations.

## Deferred requested features

Most-used sorting; torrent/magnet browsing (URI links can already be stored); rule-based Downloads organization; read-only floating file browser; arbitrary-item home-screen shortcuts; later floating-view widget. NFS, WebDAV and cloud catalogs do not yet have active providers.

## Documentation and validation

See the [documentation index](README.md), [task list](feature-todo.md), [architecture](architecture.md) and [roadmap](roadmap.md). Recent implementation builds succeeded and opened the APK with xdg-open. This documentation synchronization adds no build or automated test run. Historical test totals are revision-specific, not a claim that the current tree has been retested.

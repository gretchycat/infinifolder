# Directory-based image browsing

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

## Motivation

The 03:27 UTC device dump shows successful SFTP reads and LVM decoding, but the UI waits over 107 seconds while the ext catalog scans more than 113,000 entries. Full-volume enumeration before displaying a folder is inappropriate for a large remote system disk.

## Implemented scope

- Normal image browsing asks the shared reader for one directory. Multi-partition roots list partitions; LVM roots list logical volumes.
- Ext2/3/4 resolves the requested path through ancestor directories, then reads only direct children. Subdirectories remain navigation objects and are loaded separately.
- Provider list, stat, symbolic-link lookup, virtual-path lookup, extraction preparation and stream canonicalization use the correct folder catalog. Catalog cache/load keys include the folder, source identity/revision and recovery mode.
- File extraction resolves its parent folder, then streams the selected entry. Partition and LV extraction wrappers resolve the fresh source device.
- Copying a directory still recursively lists it through normal provider operations, as needed to perform that requested copy. Merely opening it does not request a recursive scan.
- Deleted-file recovery keeps its complete allocation/safety scan. Full-catalog reader APIs remain available for existing consumers.

## Remaining scope

Other supported image filesystems currently use their existing full parser and filter its result to direct children. They need incremental enumeration separately. ZIP/TAR/native archive catalogs retain their existing behavior. General archive index paging, persistent remote range caches and cancellation of abandoned browser loads remain follow-ups. Single-partition images retain flattened filesystem/LV navigation; multi-partition images retain named partition nodes.

## Device acceptance

- [ ] Open SFTP echoesbbs-u.vdi: partition names should appear without a long recursive ext scan.
- [ ] Open Partition 3: logical volume names should appear.
- [ ] Open the LV root and several nested folders; check Back/breadcrumb/tree navigation.
- [ ] Copy a regular file out and compare bytes. Include a nested file, empty file and file requiring indirect/extent data.
- [ ] Follow an absolute and relative symlink within the mounted filesystem.
- [ ] Copy a small directory tree out, confirming nested provider enumeration works.
- [ ] Revisit partitioned FAT/UFS/HFS/NTFS and compressed fixtures to check navigation/extraction compatibility.

APK compilation succeeded; no new automated tests were added or run for this change. The user confirms VDI browsing is working. Detailed navigation, extraction and symlink checks above remain separate coverage items.

## Small-file copy follow-up

The 03:43 UTC in-progress copy snapshot verifies individual extracted files, but shows roughly 1.2 seconds spent opening each source. Added transfer-scoped reuse of stamped read-only backing devices/range caches through the shared resolver, bounded to four sources. This avoids reopening SFTP and refetching the same VDI/directory blocks for every small file. Moves, container destinations and deleted-file recovery are excluded. Device acceptance is pending; see [dump evidence](device-dump-follow-up.md#slow-small-file-vdi-extraction-2026-10-24-0343-utc-snapshot).

### Live-source tolerance

At the user's request, timestamp/size changes during ordinary copy/backup now discard cached source blocks and reopen the source instead of stopping solely for metadata changes. One operation warning identifies best-effort live copying; actual unreadable files, incompatible file lengths and failed destination verification remain reported. A running VM can still produce inconsistent extracted data; this does not implement a snapshot. Recovery and mutation paths do not use this cache policy. Device acceptance is pending.

## Extensionless GPT image regression

The `20261024-044250-436` dump shows SFTP image `devsda` (30,945,574,912 bytes) entering `archive.tree.begin` with `directory=null`. Content detection correctly recognizes a disk image, but the provider previously enabled directory-based browsing only for image filename extensions. The resulting full catalog scans every partition, including over 74,000 ext entries after 40 seconds, before displaying the disk root.

Directory-scoped requests and cache keys now also cover names that the reader resolves through magic detection (including extensionless and ambiguous names). A detected multi-partition GPT image lists its partition nodes first, and subsequent ext browsing enumerates the requested directory. Known ZIP/TAR/native archive catalog behavior is unchanged; recovery scans are unchanged. Unknown-name ordinary archives continue to produce full catalogs, with directory-specific cache entries to avoid confusing them with partial image catalogs.

Debug APK compilation is the build check; no new automated tests were added or run. Device confirmation needed: reopen `devsda`, enter `rootfs`, navigate several folders, and offload a dump. The root request should report `directory=` rather than `directory=null`, with no recursive `disc.ext.scan-progress` before the partition list appears. Other non-ext filesystem parsers still have the incremental-enumeration limitation described above.

### Device confirmation

The user confirms extensionless GPT image opening is now much faster with the corrected APK. Mark the initial-opening regression resolved. The first follow-up dump was from the previous APK: its installation timestamp preceded the corrected build, so that repeated full scan did not exercise the fix. Detailed filesystem, navigation and extraction checks above remain separate acceptance items.

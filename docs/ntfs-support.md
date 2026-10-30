# NTFS image support

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Implemented 2026-10-23. The user confirms NTFS working on the handset. This general confirmation does not establish all of the specific checks below.

## Scope

`NtfsFileSystem` is a separate read-only Kotlin decoder for NTFS 3.0/3.1. It shares `BlockDevice` with the other image decoders: local/network random access, partition slices, supported virtual-disk wrappers, logical volumes and compressed-image staging use the same reader. `.ntfs` has the shared raw-image MIME/icon mapping; NTFS boot signatures also identify generic image filenames.

- Reads live `$I30` directory indexes, including index-allocation blocks and allocation bitmaps. Deleted/unallocated MFT records are not treated as directory contents. DOS aliases are filtered; ordinary hardlink names remain separate entries.
- Reads resident file data, nonresident data runs including fragmented mappings, sparse runs, and zero-filled uninitialized tails.
- Checks FILE/INDX update-sequence fixups, record sequence references, directory-parent references and data/record/attribute bounds. Directory/index cycles, excessive depth, duplicate paths, oversized listings, incomplete reads and invalid geometry fail explicitly.
- Exposes relative NTFS symbolic-link targets through the existing link category. Junctions and absolute Windows links are listed as links with unresolved targets; no cross-volume target is guessed. Other reparse formats cannot be extracted or traversed as ordinary folders.
- Reports the NTFS version and read-only filesystem status. No NTFS mutation path is enabled. Existing successful FAT writes return before NTFS probing.

Sources: Microsoft's [MFT record definition](https://learn.microsoft.com/en-us/windows/win32/devnotes/file-record-segment-header) and [attribute record definition](https://learn.microsoft.com/en-us/windows/win32/devnotes/attribute-record-header), plus the Linux-NTFS project's [fixup](https://flatcap.github.io/linux-ntfs/ntfs/concepts/fixup.html), [data-run](https://flatcap.github.io/linux-ntfs/ntfs/concepts/data_runs.html), and [directory index](https://flatcap.github.io/linux-ntfs/ntfs/attributes/index_root.html) documentation. NTFS3 is a driver, not another disk format.

## Explicit limitations

- No writes, journal replay or dirty-volume recovery. A volume marked dirty is rejected.
- Compressed/encrypted file data, attribute lists and attribute extension records are unsupported. These fail explicitly; they are not silently omitted. An unsupported extension record may prevent a complete directory listing.
- Alternate data streams are not exposed. Extracting a file with named data streams is rejected to avoid silently dropping them.
- NTFS security descriptors, Windows owner/group SIDs and ACLs are not translated into POSIX modes or copied as ownership. No fabricated Unix permission bits are shown.
- Names must decode losslessly as UTF-16 and fit the existing archive path model. Boundary whitespace, path separators and invalid names that would be normalized to different paths are rejected. Full Windows collation/UpCase and per-directory case-sensitive behavior are not implemented.
- Limits: 200,000 entries/index nodes, 128 directory levels, 64 index levels, 65,536 runs per attribute, bounded MFT record cache and metadata reads. Hardlink copying does not reconstruct a shared destination inode.

## Fixtures and acceptance

Use copies of `filesystem-test-images/filesystems/windows/ntfs-populated.img` and its `.img.gz` wrapper. The existing `ntfs.img` is preserved. Host `ntfsinfo` inspection shows that the populated root uses an external 4 KiB `$I30` index block; this is fixture inspection, not proof that the app successfully reads it.

- [ ] Open raw and compressed NTFS images and confirm the expected 24 user files appear.
- [ ] Open/copy `README.txt`, `binary.bin`, empty files and the small-file set; compare bytes after reopening.
- [ ] Confirm NTFS image destinations are read-only, including compressed wrappers.
- [ ] Add nested-folder, fragmented/sparse, hardlink and reparse fixtures, then validate each path.
- [ ] Exercise NTFS 3.0, supported sector sizes, index subtrees, network/partition/virtual-disk routes, invalid fixups, stale references and incomplete reads.
- [ ] Check explicit errors for compressed/encrypted data, named streams, dirty volumes and attribute extension records.

Build log: `build/ntfs-support-build.log`. No automated implementation tests were added or run. NTFS support remains a limited read-only implementation until the above coverage is accepted; RAR/ARJ/CAB readers have since been implemented and generally confirmed; fuller NTFS coverage remains future work. A separate limited HFS/HFS+/HFSX reader has now been added; see [scope and checks](hfs-support.md).

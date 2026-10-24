# UFS and legacy archive support

Updated 2026-10-23. The user confirms that the UFS fixtures and compressed UFS work on the phone.

## Implemented

- Separate read-only BSD UFS1/UFS2 decoder with little/big endian structures, cylinder-group inode addressing, direct and single/double/triple indirect blocks, sparse regions, directories and inline/block-backed symbolic links.
- Uses the existing `BlockDevice` layer for local/network access, supported virtual-disk wrappers, partition slices, logical volumes and compressed-image staging. No native app binary or kernel mount is required.
- `.ufs`, `.ufs1`, `.ufs2`, `.ffs` use the shared raw-image MIME/icon mapping. Superblock magic identifies generically named images. Filesystem information identifies UFS1/UFS2 and reports read-only access.
- UFS permissions and numeric owner/group IDs reach normal resource properties. TAR permissions and owner/group names or IDs and ZIP Unix modes use the same metadata path. Displaying metadata does not guarantee that another backend recreates ownership or permissions during a copy.
- `.tar.Z`/`.taz` now use Unix-compress LZW decoding instead of gzip. `.Z`-wrapped images and compression magic use that codec too. `.Z` writing remains unavailable and is reported explicitly for TAR.

The Kotlin decoder follows FreeBSD's documented [superblock](https://github.com/freebsd/freebsd-src/blob/main/sys/ufs/ffs/fs.h), [inode](https://github.com/freebsd/freebsd-src/blob/main/sys/ufs/ufs/dinode.h) and [directory](https://github.com/freebsd/freebsd-src/blob/main/sys/ufs/ufs/dir.h) structures. Historical vendor variants are not all covered.

## Limits

UFS writes are disabled. Geometry, bounds and exact-read checks reject malformed/truncated blocks, invalid inode sizes/timestamps, directory cycles and duplicate paths. Listings are limited to 200,000 entries and 128 levels. Special files are rejected rather than exposed as empty ordinary files; whiteouts are skipped. Optional metadata check hashes and journal replay are not implemented.

Names and link targets must be valid UTF-8 and representable without loss in the current container-path model. Backslashes, NULs and whitespace at component boundaries are rejected where normalization would alter meaning. General byte-preserving POSIX names remain future work. Hard-linked ordinary files can be read through each entry; copying out does not reconstruct a shared destination inode.

Successful FAT mutations return before UFS probing to preserve the existing small-file optimization.

## Acceptance

- [x] User reports UFS fixture browsing working.
- [x] User confirms compressed UFS working.
- [ ] Verify copied bytes, nested/empty folders, symbolic links and reopening explicitly.
- [ ] Check permissions and numeric owner/group IDs with the metadata follow-up build.
- [ ] Check big-endian, large/fragmented/indirect and sparse files, malformed images and interrupted reads.
- [ ] Repeat through network, partition and virtual-disk routes.
- [ ] Confirm `.tar.Z`/`.taz` archive extraction separately from compressed UFS.

Added `archive.tar.Z`, `ufs1.img.Z`, and `ufs2.img.gz` without changing existing fixtures. Host libarchive creates the `.Z` fixtures; it is not an app dependency. The manifest inventories 25 generated fixtures. Build log: `build/ufs-archive-support-build.log`. No automated implementation tests were added or run in this phase.

NTFS, HFS/HFS+, RAR/RAR5, ARJ and CAB remain next steps.

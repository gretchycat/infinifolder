# TAR and compressed image mutation

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Implemented 2026-10-23; handset acceptance is pending.

## Supported write paths

- Plain TAR/CBT and TAR compressed with gzip, bzip2, xz, or LZMA now use the existing isolated container mutation session. TAR aliases `tgz`, `tbz`, `tbz2`, `txz`, and `tlz` use the same path.
- TAR additions and removals are staged together and rebuilt once per batch. This first implementation rewrites even plain TAR; append-only optimization remains future work.
- FAT12/FAT16/FAT32 images wrapped with `.gz`, `.bz2`/`.bz`, `.xz`, or `.lzma` are snapshotted, decompressed to a private image, edited through the existing FAT writer, and recompressed once per batch. The decompressed result is fingerprinted against the working image before publication.
- Creating a folder/file, deleting an entry, and saving an existing compressed-image file also use a private session. Existing TAR file saves preserve the original entry's metadata through a verified rebuild.
- ZIP behavior remains a rebuild with existing compressed payloads copied without recompression.

Current filesystem scope (2026-10-26): FAT12/16/32 and the supported ext2/3/4
feature subset can be edited through private decompressed batches. Ext changes use
the [journal-aware writer](ext-image-writes.md). ISO, exFAT, UFS, NTFS and HFS image
views support reading only. Compression does not add missing filesystem writers.
Nested-container writes remain read-only. Earlier codec/build statements describe
the original increment; current codec availability follows `ContainerCompression`.

## Safety and space

All changes are private until publication. The backing source revision is checked again, and publication retains the existing atomic-replacement or recovery/rollback route. Cancellation before publication discards staging; publication finishes verification/rollback once started.

TAR rebuilds retain directories, ordinary files, symbolic/hard links, permissions, ownership, timestamps, and supported special-entry metadata. Every retained payload and key metadata fields are compared with the rebuilt archive. Sparse entries, damaged headers, unsupported entry types, unsafe/duplicate paths and nonzero trailing data are rejected with the original retained. Removing the target of a retained hardlink is rejected unless that path is replaced in the same rebuild. Compression wrapper headers may be normalized; original compressor settings/comments are not preserved.

Space accounting includes:

1. The original container snapshot and staged file payloads.
2. A full uncompressed working image, or a rebuilt TAR before compression.
3. A new compressed output, conservatively estimated from uncompressed size before compression.
4. A reservation for an original-sized recovery copy, plus a 1 MiB working margin.
5. Destination free space, where the backing provider reports it, and its single-file size limit before publication.

Staging/decompression/output streams check available local space while writing. Recovery-copy creation also checks its actual filesystem before modifying the original. Available space is a changing measurement, not a reservation or cross-process lock. Remote providers may not report capacity; errors there still use recovery/rollback. Ordinary ZIP writer internals retain their existing full-rewrite behavior, and unknown-length inputs can fail during staging without publishing partial changes.

## Manual acceptance checklist

- [ ] Copy multiple files and a directory tree into `archive.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz`, and `.tar.lzma`; reopen and compare retained and added files.
- [ ] Create a folder, save an existing ordinary file, and delete a subtree in those TARs.
- [ ] Confirm permissions and symbolic/hard links remain correct. Deleting `README.txt` alone from the TAR fixture should fail while `readme-hardlink.txt` remains.
- [ ] Copy, create, edit, and delete inside copies of `fat16.img.gz`, `.img.bz2`, and `.img.xz`; close/reopen and verify both retained and changed files.
- [ ] Confirm compressed ISO still reports a read-only filesystem.
- [ ] Exercise low cache space, low destination space, cancellation, source revision changes, and publication failures. Originals must remain unchanged or recoverable with the documented recovery path.
- [ ] Repeat on SAF and network backing files, including providers without destination capacity information.

Fixtures and their scope are described in [filesystem-test-images/README.md](../filesystem-test-images/README.md). Diagnostic events include `container.batch.rebuild` for TAR/ZIP and `container.batch.recompress` for compressed images.

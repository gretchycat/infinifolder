# HFS image support

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Implemented 2026-10-23. The user confirms classic HFS working and, after the header-offset fix, confirms HFS+ and HFSX working on the handset. This general confirmation does not establish every compressed-image, copied-byte, metadata or edge-case check below.

## Scope

`HfsFileSystem` is a separate read-only Kotlin decoder for classic HFS, HFS+ version 4 and HFSX version 5. It uses the shared `BlockDevice` interface and existing image compression, network random access, partition and virtual-disk routes. HFS+ embedded in an HFS wrapper is recognized. `.hfs`, `.hfsplus` and `.hfsx` use the shared raw-image MIME/icon mapping; signatures identify generic image names too.

- Follows the live catalog B-tree leaf chain; thread records are not displayed as files. Checks node sizes, record boundaries, link cycles, record accounting, catalog IDs, parents, names and directory depth.
- Maps data forks through initial extents and the extents-overflow tree. Extraction uses the current provider handle rather than a closed listing handle.
- Decodes classic names as MacRoman and HFS+ names as UTF-16BE without Unicode normalization. HFS+ BSD modes and numeric owner/group IDs flow through existing resource metadata. Classic HFS permissions are not fabricated.
- Reads HFS+ symbolic-link contents as UTF-8. Finder aliases are recognized as unresolved links; they cannot be copied as ordinary empty files.
- Reports read-only status and allocation size. Writable FAT paths still return before the new reader is probed.

Format references: Apple's [HFS Plus volume specification](https://developer.apple.com/library/archive/technotes/tn/tn1150.html) and [HFS on-disk structure definitions](https://github.com/apple-oss-distributions/hfs/blob/main/core/hfs_format.h). The decoder is an independent implementation.

## Explicit limitations

- No HFS writes, journal replay or filesystem recovery. Unclean/unmounted-bit failures and inconsistent HFS+ volumes are rejected.
- Resource forks are not exported. A nonempty resource fork prevents file extraction, avoiding an incomplete ordinary-file copy.
- HFS+ compression, hardlink chains, extended attributes/ACLs, expanded timestamps and special files are unsupported. Affected file extraction fails explicitly; affected user directory records prevent listing because the current directory-copy API cannot preserve or reject their extra metadata individually. Root folder metadata is not exported.
- macOS private inode directories and their descendants are hidden, matching their internal role. Hardlink placeholders remain unsupported; directory hardlinks are not followed. Finder aliases are not resolved.
- Overflow extents are supported for catalog/data forks. An extents-overflow tree that itself needs overflow extents is rejected; bootstrap mapping remains future work.
- Classic names assume MacRoman; other script encodings are not decoded. Names that the current archive path model would change (separators, NUL, boundary whitespace, dot components) fail explicitly. HFS names containing slash need a reversible name representation before they can be browsed.
- HFSX lookup collation is not implemented or guessed; filesystem info leaves its case sensitivity unspecified. Listing follows catalog records without resorting by a guessed comparison table.
- No allocation-bitmap, complete B-tree index or journal consistency verification. Structural checks are not a replacement for filesystem repair. Limits include 400,000 leaf records, 200,000 catalog objects, 128 directory levels and 65,536 overflow records per fork.

## Fixtures and next checks

Use copies of `filesystem-test-images/filesystems/apple/hfs-populated.img` and `hfs-populated.img.gz`. The original `hfs.img` is preserved. These are classic HFS fixtures; they do not establish HFS+ or HFSX compatibility.

Added independent `hfsplus-populated.img` and `hfsx-populated.img` fixtures and their `.img.gz` wrappers. They include Unicode names, explicit permissions/UID/GID, file/directory symbolic links and distinct HFSX case variants. [Expected contents](../filesystem-test-images/hfsplus-expected.json) record payload hashes and metadata; [generation details](../filesystem-test-images/README.md#hfshfsx-fixtures) describe the host tools and inspection evidence. HFS+ has 119 user entries; HFSX has 120. The user confirms both formats working after the header-offset fix; detailed coverage checks remain pending.

- [ ] Browse both classic fixtures on the handset; confirm nested/empty folders, small files and MacRoman names.
- [ ] Open/copy README, binary, empty and small files; compare copied bytes.
- [ ] Confirm raw/compressed HFS targets report read-only.
- [x] Add independently generated HFS+ and HFSX fixtures with Unicode, BSD modes/ownership and symbolic links, including gzip wrappers and expected-content inventory.
- [x] Obtain general handset confirmation of HFS+ and HFSX after correcting volume-header fork offsets.
- [ ] Complete specific raw/gzip, copied-byte, link, metadata and distinct HFSX `Case.txt`/`case.txt` checks; general confirmation does not establish each case.
- [ ] Add an independently generated embedded HFS+ wrapper fixture and validate its offsets.
- [ ] Validate fragmented/overflow forks, large files and network/partition/virtual-disk routes.
- [ ] Exercise unsupported resource forks, aliases, hardlinks, attributes, compression, dirty volumes and malformed trees; confirm explicit errors and no source mutation.
- [ ] Add lossless resource-fork/attribute export and hardlink/alias resolution before widening compatibility claims.

Build log: `build/hfs-support-build.log`. No automated implementation tests were added or run. Successful compilation does not establish filesystem compatibility; the handset checks above remain pending.

## HFS+/HFSX header offset correction

The handset dump `build/infinifolder-debug-20261023-230507-053.log` reports `Invalid allocation extent` in `entries` while decoding the volume-header extents-overflow fork, for raw and staged compressed-image routes. The decoder incorrectly used byte offsets 160/240 for the overflow/catalog forks. Apple's structure includes 32 bytes of Finder information at bytes 80–111; the correct fork offsets are 192/272. Corrected both offsets using named constants and expanded extent errors with the descriptor index and geometry. Classic HFS uses separate MDB offsets. No fixture changes were needed for this correction.

Build log: `build/hfs-header-fix-build.log`. No automated implementation tests were added or run. The user confirms HFS+ and HFSX working after installing this fix. Detailed extraction, metadata and edge-case checks remain pending.

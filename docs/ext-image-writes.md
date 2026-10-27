# Ext2, ext3 and ext4 image writes

## Directory checksum failure and fix (2026-10-26)

The 22:28 UTC handset dump reports two failed moves after checksum corruption.
`e2fsck -fn` found invalid checksums in two directory blocks in the original
`ext4-metadata-tests.img`. It is preserved unchanged for investigation. A separate
repaired copy passes `e2fsck`, retains the ACL and extended attributes, and all
five fixture file contents match their recorded hashes. A second copied folder on
disk also has matching contents. The log does not establish the origin of both
checksum failures or demonstrate a completed metadata round trip.

A clean disposable image reproduced one corruption: upstream lwext4 changed the
`..` entry when moving an existing non-indexed directory without recalculating its
directory-block checksum. The writer now updates and verifies that checksum before
commit. Six focused ext integration tests passed, including a new non-indexed
directory move with `e2fsck` and content readback. The original failing move also
passes on a freshly generated disposable image with the fixed native library.

The new clean `ext4-metadata-retry.img` was provided for handset validation. Logs:
`build/ext-directory-checksum-tests.log`, `build/ext-directory-checksum-fix-build.log`.
The origin of the other original block checksum issue and broader damaged-filesystem
detection still require investigation; avoid treating this fix as a full filesystem audit.

The 22:34 UTC handset dump confirms five entries moved from `copy-from` to
`copy-back-here` and back within the same retry image. Both operations committed
with zero warnings and retained their original inodes. Afterward `e2fsck -fn`
passed; all five files matched expected content hashes, modes, numeric owner/group
and modification times. The POSIX ACL and both xattr values matched byte-for-byte.
This covers native moves, including the non-indexed nested directory; copying to a
different ext image and importing metadata still requires a separate handset check.

The 22:39 UTC handset copy targeted the older, damaged
`ext4-metadata-tests.img/copy-back-here`, not `ext4-write-tests.img`. Its private
batch encountered a checksum error while creating a nested file and was discarded;
the clean retry source and `ext4-write-tests.img` both still pass `e2fsck -fn`.
The staged ACL-bearing file and executable had their metadata read back before the
later failure, but the batch was not published. A reproduction on disposable copies
of the retry source and clean `ext4-write-tests.img` successfully exported/imported
both files' metadata, created the nested file, and passed `e2fsck -fn`; the copied
ACL-bearing file retained mode, owner/group, ACL and both xattrs. Full end-to-end
handset copy and copy-back validation remains pending.

The user subsequently reported that the handset copy worked; the exact destination
and a post-copy metadata and filesystem check have not been independently confirmed.

A headless integration test generates two disposable ext4 images, copies the
five-file metadata fixture between them, moves the copied directory within the
destination and copies it back. After closing and reopening the images, it checks
the known SHA-256 hashes and transfer metadata (including permissions, ownership,
timestamps, ACL and extended attributes), and both images pass `e2fsck -fn`.
This exercises the image reader and native write backend; it does not exercise the
Android picker or the full application transfer coordinator.

The capability probe still advertised the already-damaged old image as writable,
because it does not check every directory in the filesystem. Investigate stronger
pre-publication corruption detection and a clearer error. The damaged image remains
preserved for that work.

The 22:47 UTC retry also selected the old damaged `ext4-metadata-tests.img`, this
time its `copy-to` directory. Creation failed before any bytes were copied and the
private batch was discarded. The screenshot shows a separate ordinary `linux/copy-from`
folder; to exercise the fixture metadata, select files inside
`ext4-metadata-retry.img/copy-from` as the source and choose
`ext4-metadata-copy-target.img/copy-to` explicitly as the destination. Picker selection
behavior needs confirmation; the log does not record the individual picker taps.

After a later reported copy, `ext4-write-tests.img` had a bad checksum on the
second block of `/move-to/copy-to`. The available dump predates that operation, so
its source and precise cause are unknown. The image is preserved; a separate copy
was repaired for inspection. A second lwext4 defect can produce this kind of error:
an indexed-directory leaf checksum was computed before initializing its inode field
in a reused no-read buffer. With a deliberately nonzero buffer, the old code failed
`e2fsck` and the corrected initialization passed. This demonstrates the defect,
but does not establish whether it caused the handset failure. Seven focused ext
tests passed. The fresh `ext4-metadata-copy-target.img` passed `e2fsck` before use.
Picker diagnostics now record destination selection and capability-check timing;
an exception in a check is shown as a failure instead of leaving it pending forever.

The native writer now also probes the new directory's index and leaf checksums
before accepting directory creation. A fault-injected stale buffer reproduced a
bad checksum without the guard; with the guard, the mutation failed and the
private batch could not be published. This targeted check is not a full filesystem
scan. See [container integrity validation](container-integrity-validation.md).

Implemented 2026-10-26; handset acceptance pending. Ext2 has no journal. Ext3 and
ext4 use an internal journal when the filesystem has one; ext4 without a journal
uses the non-journal path. This is a supported feature subset, not every ext4
configuration.

## Operations and integration

- Create files/directories, write staged file contents, delete files/directory
  trees, and move files/nonempty directories within one filesystem.
- FAT and ext implement `WritableImageFileSystem`. The existing archive/image
  provider, transfer engine, block-device boundaries, private batch, cancellation
  and checked backing-file publication handle both.
- Native moves relink the original inode instead of copying payload blocks. They
  retain contents, mode, ownership, hardlinks and extended attributes. Directory
  parent links are updated. New entries begin with 0644/0755 defaults; checked
  transfers can then import modes, numeric ownership, precise timestamps and
  extended attributes/ACLs. See [metadata integrity](metadata-integrity.md).
- Standalone images and selected supported disk partitions use the same writer.
  Moves across partition boundaries use checked copy/delete fallback after
  preflighting the entire relocation set; metadata loss retains originals. LVM
  mutation, nested-container publication and arbitrary entry random-write handles
  remain unsupported.
- Compressed images use the existing decompress/edit/recompress private batch,
  including a read-only feature probe before accepting the destination. They do
  not update compressed bytes in place. Decoder/backing support and staging space
  still constrain which images can be changed.
- Ext properties report filesystem type, case sensitivity, allocation unit and
  free space from the superblock. Listing now exposes mode and numeric uid/gid.

## Journal and publication boundaries

The native backend is vendored lwext4 at commit
`58bcf89a121b72d4fb66334f1693d3b30e4cb9c5`. It supplies journal replay and journaled
metadata transactions. Journal recovery runs on the private snapshot before edits;
the journal is started, committed/stopped and the filesystem cleanly unmounted
before publication. Ext2 bypasses journal handling. This does not promise Linux
`data=journal` semantics or a coherent copy of a live mounted filesystem.

Presentation probes use read-only mounts and never replay into the public source.
All ext mutations require an app-private batch marker. Single file saves, creates
and deletes also use that batch; public-source writes cannot bypass it. Any failed
private filesystem mutation poisons the batch so it cannot publish. The existing
source-revision, mounted-source, verification and recovery/rollback checks still
run when publishing the completed image. Cancellation before publication discards
the private result.

The integration additionally preserves ignored native transaction errors, stops
on detected metadata/journal checksum failures, releases native registry state
after I/O errors, and supports `metadata_csum_seed`. Native calls serialize only
the ext library's global registry; independent providers and the Android UI are
not held by that mutex.

## Rejected configurations

Unknown feature bits are rejected. The initial writer rejects external journals,
MMP, fast commits, encryption, inline data, bigalloc, pending orphan cleanup and
filesystems flagged with errors. A clean orphan-file inode is preserved but not
used: the API has no long-lived open-unlinked files. A filesystem needing repairs
beyond supported journal replay must be checked separately with `e2fsck`.

The Termux build compiles an arm64 Android JNI library using clang and patchelf.
It depends only on Android system libc/libdl. Other build hosts use Android NDK
CMake. A device architecture without the native library retains browsing and gets
an explicit unavailable-writer error. Upstream source, local changes and license
notices are included under `storage-engine/src/main/cpp/third_party/lwext4`, with
the distribution notice/license in app assets.

## Validation

Tests operate on disposable copies; the user-owned fixtures are hash-checked.

- Native JNI harness: ext2/3/4 creates/writes, single-file and nonempty-directory
  moves, inode retention, recursive deletion, collisions, path traversal and
  symlink-ancestor rejection, mode/uid/gid/hardlink/xattr retention, I/O failures,
  and continued backend use after a failure.
- Deliberate process termination during ext3/ext4 writes, followed by journal
  recovery and successful `e2fsck -fn` checks.
- Content hashes and all five filesystem-checker passes after mutations; rejected
  feature/error probes leave bytes unchanged.
- Corrupt-inode rejection without a native crash, plus retained outside hardlinks
  after deleting their other link inside a directory tree.
- Kotlin image-reader integration: raw ext2/3/4 and a GPT ext4 partition, payload
  extraction, directory relocation, compressed-image probe, public-write refusal
  and failed-private-mutation marking.

Run the focused Kotlin checks with
`gradle :storage-engine:testDebugUnitTest --tests com.infinifolder.archive.ExtImageMutationTest`.
The native matrix is
`python3 storage-engine/src/test/native/run-ext-integration.py` after building the
native library. Reports are under `build/ext-integration/` and
`storage-engine/build/test-results/`.

The final run passed all four focused Kotlin tests and the native matrix. The debug
APK built in 23 seconds and opened with `xdg-open`. Populated handset fixtures are
`filesystem-test-images/filesystems/linux/ext{2,3,4}-write-tests.img`; move items from
`move-from` to `move-to`. Existing original fixtures are retained.

Handset checks still needed: copy a directory into each ext image; move a file and
a nonempty directory between folders, reopen and copy back out; cancel an edit and
confirm the original remains unchanged; exercise supported compressed images and
remote/SAF publication. Large/fragmented images, indexed-directory growth, full
volumes, extended journal variants and broader damaged-image coverage remain
additional validation work.

References: [lwext4 source and supported features](https://github.com/gkostka/lwext4),
[Linux ext journal documentation](https://www.kernel.org/doc/html/latest/filesystems/ext4/journal.html).

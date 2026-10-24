# Filesystem limits and destination preflight

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Updated: 2026-10-22.

## User-visible behavior

Folder **Properties** now shows Filesystem, Maximum file size, and Filename rules. The debug dump JSON includes filesystem details for its destination. Transfers log the detected type, limit, and evidence.

Before creating any destination files or folders, copy/move checks the complete source snapshot against known destination limits. A FAT-family destination rejects a file above **4,294,967,295 bytes (4 GiB minus 1 byte)** with a message explaining the limit and retaining the original. Nested source files are checked too. Unknown-length sources are also checked against a known size ceiling before each output write; a failing partial copy uses ordinary cleanup.

Known FAT names are checked for prohibited characters, invalid path components, trailing spaces/periods, and the supported name length. Collision-generated names are checked as well. Backend create-new guards and checksum verification remain in place.

## Detection and scope

`StorageProvider.fileSystemInfo` is the common hook. Unknown/unsupported reporting does not imply unlimited storage and does not block an otherwise valid transfer.

- **Local storage:** read the process's mount table, resolving a known external volume's underlying mount rather than treating FUSE as its physical filesystem. FileStore is a fallback when accessible.
- **Android external-storage SAF provider:** use that specific platform provider's volume identity to match a visible mount. Other providers' IDs remain opaque; no content URI is converted into a path for I/O.
- **Network backends:** currently report unsupported. A large-file destination write/flush/close failure adds a conditional note about the FAT limit when the actual size ceiling was not reported. The original error is preserved; the message does not claim the server uses FAT. Small-file and source-read errors do not receive this note.
- **Mounted images:** inspect boot/superblock metadata through the existing read-only block-device hooks, including VM wrappers and supported partition/volume selection. FAT geometry identifies FAT12/16/32 precisely and returns the image writer's VFAT name policy. exFAT/ext/ISO identification is provided without inventing unimplemented per-format limits. Nested writes remain disabled.

The readable mount table in this environment identifies volume `635D-17EB` at `/mnt/media_rw/635D-17EB` as `vfat`. This identifies the FAT family, not its exact FAT12/16/32 variant. The application still needs a device run to establish whether its own mount namespace exposes that entry.

Android's [volume construction code](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/storage/VolumeInfo.java) assigns a 4,294,967,295-byte maximum to VFAT volumes. The implementation uses confirmed mount/boot metadata rather than guessing from a volume UUID or the card's capacity.

## FAT image filename semantics and safety fixes

The image writer supports VFAT long names on FAT12/16/32. FAT16 alone therefore does not justify rejecting every name outside 8.3. It reports a 255-UTF-16-unit component limit, prohibited characters, and case-insensitive naming; each long name also gets a unique short alias. A separately reported DOS-only mount uses 8.3 validation. See Microsoft's [filename conventions](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file) and the [Microsoft FAT specification](https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf).

Related defects discovered while checking these limits were corrected:

- Long names may require directory entries spanning several clusters. Directory growth now allocates enough clusters and uses their actual slot addresses, avoiding writes beyond a single allocated cluster.
- Deletion records actual LFN/main-entry slot addresses rather than assuming that fragmented clusters are physically contiguous. It marks the entry deleted and syncs before releasing its data allocation.
- Short alias generation checks actual on-disk aliases, excludes non-ASCII alias characters, and fails explicitly if unique aliases are exhausted. Create-new also checks short-alias namespace collisions and cannot overwrite an existing file.
- FAT12/16 fixed-root slot exhaustion is detected before allocating the new file's data. Long names consume multiple slots, so the root's slot count is not a guaranteed count of long-named files.
- Overwrite allocates replacement data while retaining the original cluster chain, flushes the new bytes, publishes and flushes its directory entry, then releases the old allocation. Lack of spare clusters can therefore reject an overwrite while leaving the original intact.

These are not journaled image transactions. I/O interruption/power loss can still leave orphaned allocations or partial directory metadata; further rollback/journaling work remains. Some formats still require materialization, and the existing archive writer remains memory-backed. Maximum file length is separate from available space, quota, directory capacity, and backend implementation limits.

## Acceptance checks still needed

- [ ] Folder Properties on the actual SD/USB volume shows its detected type or an honest Unknown result.
- [x] A known oversized source is rejected before destination creation on a detected FAT volume (user confirmed).
- [ ] Unknown-size streams stop before crossing the known ceiling and preserve their source.
- [ ] FAT16 images accept supported VFAT long names, reject overlong/illegal names, and report fixed-root exhaustion.
- [ ] Long names spanning directory clusters and fragmented-slot deletion preserve neighboring file data.
- [ ] Short alias collisions, alias exhaustion, and full-image overwrite failures preserve existing entries.
- [ ] Large remote write failures show the conditional hint; successful remote transfers and small-file failures do not.

Validation for this increment consists of Kotlin compilation, APK assembly, and source whitespace checks. No new tests were added or run; the earlier 223-test result predates these changes.

Final APK assembly succeeded: `gradle :app:assembleDebug --offline` (`build/filesystem-limits-final-build.log`). The APK was opened with `xdg-open`. Device acceptance and image failure-injection checks above remain pending.

## Next phase: metadata and link compatibility (user priority)

The size/name checks are only part of destination compatibility. Extend the common provider/filesystem contract with independent read, create, preserve, and change support for native permissions, ownership/group, ACLs, symbolic links, hard links, timestamps/precision, and extended attributes. Distinguish filesystem support, backend/protocol access, mount policy, and current credentials. A capable filesystem accessed through a restrictive provider is still restricted; an unknown network destination remains unknown.

Planned behavior and acceptance criteria:

- [ ] Mark metadata provenance: native stored values, provider-emulated values, and unavailable values. Android/mount-generated permission displays must not masquerade as per-file POSIX modes preserved on FAT.
- [ ] Add batch compatibility preflight before destination creation. Identify relevant metadata that cannot be represented or verified at the destination; explain it once with the affected resources and fields.
- [ ] Define explicit policies for lossy copies and moves. User-approved copying without particular metadata must be visible in results. A move requiring preservation must retain its source when required metadata cannot be applied/verified. Never imply that checksum equality also proves permission/link preservation.
- [ ] Preserve a symbolic link as a link when supported. Never silently dereference it into a target copy or turn it into an ordinary file/application shortcut. Following targets, creating `.iflink` shortcuts, and preserving native links are distinct explicit actions.
- [ ] Cover relative/absolute links, directory links, broken links, cycles, and links that point outside the selected tree. Deleting/renaming a link must not unexpectedly modify its target.
- [ ] Preserve hard-link relationships only when both source identity and destination creation support are available; otherwise explain the unsupported semantics. A JSON shortcut is not a native hard/symbolic link.
- [ ] Preserve meaningful native mode bits, executable status, ownership/group, ACLs and xattrs where supported and authorized. Do not invent equivalent IDs across hosts or report success after a denied metadata update.
- [ ] Distinguish stored read-only attributes from effective access permissions. Timestamp support and rounding must be explicit; verification should use the destination's actual precision.
- [ ] Apply the same policies to mounted image entries and archive entries. Report each format's real metadata/link semantics independently from filename and size limits.
- [ ] Show unsupported fields/actions honestly in Properties/editor controls. An unavailable chmod/chown/link-create operation must not appear to have succeeded.
- [ ] Validate local/SAF/SMB/SFTP/FTP destinations and FAT16/FAT32/exFAT/ext image cases, including source-retention when byte copying succeeds but required metadata preservation fails.

Current behavior remains narrower: symbolic-link transfers are rejected explicitly and generic transfers verify bytes, but full permission/ownership/ACL/xattr/hard-link preservation and its loss policy are not implemented. This section is the next task list, not a claim that those features already work.

## Automatic filename character conversion (2026-10-22)

Copy/move transfers now convert prohibited characters to `_` when the destination reports VFAT or DOS naming rules. The same conversion applies to files and directories throughout a copied tree, before name validation and destination creation. FAT-prohibited control characters and `" * / : < > ? \ |` become underscores; trailing spaces and periods become underscores. DOS-only names additionally replace characters unsupported by the short-name character set. Unknown naming rules do not trigger speculative conversion. Filename length constraints still apply.

Conversion happens before collision checks. Different names that map to the same destination name receive unique suffixes rather than overwriting each other. VFAT collision suffixes respect the component-length limit; DOS destinations use short-name-compatible `~n` suffixes. Source names are not renamed. Conversion mappings are recorded as `transfer.filename-converted` in the debug log.

Example: `track:live?.mp3` becomes `track_live_.mp3` on a known VFAT destination. If occupied, the copy uses `track_live_ (2).mp3` or the next available name.

APK assembly passed (`build/filename-character-conversion-build.log`), and the APK was opened with `xdg-open`. No tests were added or run; on-device conversion and collision checks remain pending.

## Free space inside FAT images

FAT12/16/32 image queries now report available bytes by counting free allocation-table clusters. Copy/move preflight checks the whole scanned source tree against known destination free space before creating entries. Known file sizes are rounded to allocation units, and copied directories account for at least one cluster. The error distinguishes insufficient free space from the single-file size limit. This is a lower-bound check; unknown sizes, directory-entry growth and concurrent changes still require runtime allocation checks and cleanup.

## Copy to and Link to (updated user policy)

This policy supersedes the earlier automatic shortcut fallback:

- **Copy to…** follows native symbolic links and copies actual target contents. The selected link's name becomes the destination name. Linked directories, including links inside copied directory trees, participate in the normal recursive scan. Shared link resolution and ancestry checks reject broken/cyclic targets before destination creation. Target sizes and cluster rounding feed capacity checks; destination bytes use normal read-back verification. Copies retain original links and targets.
- **Link to…** is available for every file and directory, including entries in containers, native links, shortcut files and batch selections. It creates `<selected-name>.iflink` through the normal destination picker and verified transfer engine. It does not traverse directory contents. The serialized shortcut size feeds capacity checks. Name conversion, collision handling and partial-copy cleanup are shared with ordinary transfers.
- Native symbolic-link menus omit **Move to…**, as do batch menus containing selected native links. Moving ordinary directory trees containing native links remains blocked at source scan until native link preservation is implemented.

The `.iflink` format and MIME were already registered. For a native link, Link to resolves the actual target when available; otherwise it references the original link entry and retains the raw target text as descriptive data. An unresolved shortcut therefore depends on the original entry. A shortcut to remote or mounted content still depends on the connection/container and its original location; it is not an independent content copy or Windows binary `.lnk`.

Acceptance remaining: Copy `.backgrounds` and inspect its actual copied files; create `.backgrounds.iflink` with Link to and open its target; exercise Link to on ordinary files/directories and inside ZIPs; check collisions, nested links, broken links and cycles. APK assembly and source whitespace checks are the validation for this increment. No tests added or run.

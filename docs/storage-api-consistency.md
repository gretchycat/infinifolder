# Storage API consistency

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-26. This pass implements shared capability and random-access rules.
Internal ZIP moves and FAT32 native file moves are user-confirmed, supported by
the 18:57 and 19:24 UTC handset dumps. FAT directory moves and other contract checks
remain pending. Prior torrent/resize
confirmations apply to their preceding builds. No automated tests were added or run
for this pass.

## Common contract

The ext2/3/4 writer now uses `WritableImageFileSystem` alongside FAT. It adds native
inode relocation to the existing optional batch hook, uses the same provider-facing
capabilities, and keeps journal replay/transactions inside private snapshots.
Private mutation failures prevent publication. Focused native/JVM checks were added
for this later increment; handset acceptance is pending. See [ext support](ext-image-writes.md).

`StorageProvider` owns resource identities, metadata, streams, navigation and optional
seekable access. Callers retain opaque `ResourceRef` values rather than constructing
backend paths. `ResourceCapabilities.from` now applies backend defaults, per-resource
hints and explicit provider overrides through the same final restrictions:

- Directories list/create children; files read/write/seek their own contents.
- Backend read-only restrictions cannot be overridden by positive resource hints.
- Copy follows readable symbolic-link targets. Move remains unavailable for native
  links; ordinary moves require both copying and deletion. A non-deletable root or
  protected cache snapshot therefore cannot advertise Move.
- File-content write permission and permission to remove a directory entry remain
  separate: a read-only file can still be removable from a writable parent.
- Creation guarantees describe the selected directory, not every resource in a
  backend. SFTP reports its demonstrated exclusive-creation extension through this
  contract; directory links do not inherit ordinary directory rename support.
- Torrent contents explicitly report read-only operations. Virtual collection roots
  cannot advertise deletion/rename of the collection itself. Their child link records
  retain supported operations on those records.
- SAF document flags remain operation hints. They no longer produce invented POSIX
  modes such as 0644/0755; ownership and mode stay unknown when not supplied.

Unknown support permits an actual attempt. Unsupported support is explicit; I/O,
connection and permission errors remain failures rather than fallback signals.

## Shared action policy

`StorageActionPolicy` now supplies source action decisions and contextual destination
assessments to entry/selected-set menus, the File/Edit menu, clipboard paste,
destination selection, drag/drop and transfer preflight. Source actions distinguish
copying from deletion permission; native symbolic links still copy through their
targets and cannot be moved. Mixed selections retain skip-and-warn copying of
unreadable entries. Recovery and mounted-image guards remain in the operation path.

Destination inspection uses provider capabilities and opaque backing-resource
identities. `ACCEPT_INTERNAL_MOVE` explicitly describes a writable container's
support for moves in its own isolated mutation scope; it defaults to unsupported.
A move into a mounted container requires every source to share that immediate
backing resource and exactly one enclosing container. This fixes the picker refusing
moves that the engine already supported. Cross-container moves and nested-container
writes remain restricted. Copy remains available where creation is supported.

Drop device/capability queries run on a worker. Same-device eligible sources move;
other-device or unknown-identity drops copy. Entry menus no longer omit supported
archive actions solely because an entry has an archive URI. Failed capability queries
remain errors, while unknown support within a capability report permits an attempt.
The engine checks destination and source move eligibility again before mutation;
UI decisions do not establish continued access or guarantee publication.

Focused handset checks: move a file and directory between folders in one writable
ZIP/FAT image; copy from outside into that image; reject an explicit cross-container
move; copy through a native link without offering Move; try a read-only destination
through the picker, paste and each drop target; copy a mixed readable/unreadable set.

### ZIP move acceptance — 2026-10-26

The user confirms moving files inside a ZIP. Dump
`build/infinifolder-debug-20261026-185723-493.log` shows the `old ringers`
directory moved inside `Untitled.zip`: 46 file transfers totaling 8,395,781 bytes,
copy verification and source rechecking, staged original removal, one committed
container publication, and COMPLETE with zero warnings. The operation ran from
18:57:03.772 to 18:57:07.704 UTC (about 3.93 seconds). The batch recorded 58 additions
(including directory entries) and one removal. This confirms the exercised ZIP path;
it does not establish FAT32, interrupted publication or other unexercised variants.
The later FAT32 file-move confirmation is recorded below.

The same dump still records UI-thread disk reads in mounted-source checks reached
from `startBatchTransfer`, including local seekable opens and `/proc/mounts` reads.
These are separate from the successful ZIP transfer. A 782 ms startup delay also
appears earlier in the dump; the log does not establish that these move-time reads
caused that startup delay. Move remaining preflight I/O off the UI thread while
retaining mounted-image protection.

### FAT relocation correction — 2026-10-26 (file moves confirmed)

The 19:09 UTC dump, `build/infinifolder-debug-20261026-190933-523.log`, retains
two failed task snapshots: the shared copy/delete move path requested 32,437,248
bytes free inside FAT32, where only 20,632,064 bytes were free. The check ran before
creating destination entries and retained sources. This was an allocation limit of
the implemented move strategy, rather than a failure to recognize the destination.
A native move should preserve existing payload allocation.

`StorageWriteBatch` now has an optional native relocation hook. The transfer engine
defers its payload duplication check for an internal batch move, then uses native
relocation only when the private batch explicitly supports it. Unsupported hooks
must leave the view unchanged; fallback still checks full copy capacity. Failed or
cancelled batches are discarded without publishing. This hook is shared; the first
implementation is FAT12/16/32. ZIP/TAR retain their existing rebuild path. Other
storage implementations using copy/delete can still require duplicate payload
space until they gain a safe native move path; their limits are not bypassed.

FAT relocation preserves the file/directory cluster chain and the entry's attributes
and timestamps, writes and checks the destination short/LFN records, updates and
checks a moved directory's `..`, and unlinks the old name without freeing its data.
Directory ancestry, collisions, selected ancestor/descendant overlap and partition
boundaries are checked. Directory records may still need a few slots or another
cluster; FAT12/16 fixed-root entry limits still apply. The entire modified snapshot
uses the existing revision-checked publication and recovery path. Compressed FAT
images use that same relocation before verified recompression/publication.

The torrent peer-discovery logger also now aggregates its running counter to a
five-second cadence after the first three events. In the failing dump, repeated
peer-candidate messages had rotated away the actual transfer events; the task
snapshot is what preserved the failure reason. Discovery/connection behavior is
unchanged by the logging adjustment.

The implementation turn deferred building during active downloads. A subsequently
installed APK (device update time 19:21:49 UTC) exercised this native path successfully.
No automated tests were added or run for this correction.

#### FAT32 file-move acceptance — 19:24 UTC

The user confirms the move worked. Dump
`build/infinifolder-debug-20261026-192459-281.log` records eight files moved from
`hp48` to the root of `fat32.img`, totaling 8,751,605 bytes. Each entry logs
`payloadCopied=false` with its preserved start cluster; relocation took 87 ms.
One directory expansion supplied additional entry slots. The 20 MiB image was
published through SAF, committed successfully, and the task completed with zero
warnings. Overall operation time was approximately 1.41 seconds (19:24:46.992 to
19:24:48.406 UTC). Concurrent BitTorrent/magnet tasks remained running in the dump;
end-to-end completion of those downloads is still pending.

This confirms FAT32 native **file** moves. Its free space before the operation
(8,962,560 bytes) exceeded the selected payload, so the particular case of moving
more data than the remaining free space still needs an explicit check. Next checks:
move a nonempty directory (including its `..` update), read the moved contents,
reopen the image, exercise long names/collisions, and confirm cancellation/publication
failures retain originals. FAT12/16 and compressed FAT remain separate acceptance
cases; ZIP retains its earlier confirmation.

### General move follow-up — 20:00 UTC

The user reconfirms moving works. The latest dump's retained main-log window
contains recent torrent/activity events rather than move events, so the detailed
ZIP/FAT32 evidence above remains the basis for those specific paths. The user also
confirms Activity swipe controls; the log records five history removals and an
explicit torrent cancellation with retained progress. Nonempty FAT directory,
FAT12/16/compressed and constrained-space cases remain separately tracked. Native
local/SFTP/SMB moves are the next suggested architecture work to avoid the same
payload-duplication requirement on other capable storage backends.

### Background and ordinary-file drop destinations — 2026-10-26

The visible file pane accepts drops in blank space as drops into its displayed
container. A non-container file is a target for its containing folder; the worker
uses provider `parent` then `stat` to obtain its parent entry/URI rather than parsing
opaque document IDs. Existing container cards, breadcrumbs, pinned targets and tree
nodes retain priority. Dropping onto a selected source still triggers its menu.

Both new routes preserve the full drag selection and pass through the common drop
policy: same-device eligible sources move, cross-device/unknown identities copy,
and destination capabilities, recovery, nesting and mutation guards still apply.
When all provider-reported source parents already match the destination, the drop
is consumed as a no-op, avoiding duplicate copies or collision-name changes.
Blank-space hit testing is limited to the visible file viewport and excludes the
hidden portrait file pane when the tree is selected. Virtual root still accepts
container links only. Breadcrumb link-creation drags keep their existing behavior.

Diagnostic events: `browser.drag.background-drop`, `browser.drag.file-parent-drop`
and `browser.drag.no-op`. Debug APK build succeeded in 17 seconds and opened with
`xdg-open` (`build/container-background-file-drop-build.log`). Handset validation is
pending for empty folders, gaps/below icons, regular-file targets, drag-hover entry,
multiple selection, read-only destinations, same-folder drops and both orientations.
No automated tests were added or run.

## Random-access handles

Read handles require READ and SEEK. Writable requests additionally require WRITE;
`openTransferTarget` also requires TRUNCATE, whose block-device meaning is resize
in either direction. Both direct default transfer-target calls and router dispatch
use the same validator. The router preserves backend-specific target overrides.
Unsupported opens identify SEEK or SEEK_WRITE, while ordinary unsupported output
streams identify WRITE. Unsuitable handles are closed before returning.

| Backend | Read/write streams | Random access | New random-write target |
| --- | --- | --- | --- |
| Local | Yes, subject to access | Read/write | Yes |
| SAF | Per-document flags/grants | Probe descriptor; pipes are streams | Unknown until opened |
| SFTP | Yes, subject to server permissions | Read/write | Yes; safe creation remains server-dependent |
| SMB | Yes, subject to share permissions | Read/write | Yes |
| FTP/FTPS | Yes, subject to server support | Read only, requires REST | Unsupported |
| WebDAV | Yes, subject to server support | Read only, requires byte ranges | Unsupported |
| Archive/image contents | Format/backing dependent | Read slices or staged reads; writable entry handles unsupported | Unsupported |
| Torrent contents | Read/download only | General seekable reads unsupported | Unsupported as destination |
| Application/virtual-link catalogs | Catalog-specific stream access | Unsupported | Unsupported |

Corrections made in the core adapters:

- Local file-device size follows successful writes and resizing instead of retaining
  its initial size, including files initially created empty.
- SAF uses descriptor resizing for growth as well as shrinkage. Java's
  `FileChannel.truncate` alone cannot reserve a larger torrent target.
- Local flush failures and SMB close failures propagate. A random transfer whose
  destination close fails cannot report success or trigger publication cleanup.
- Cached ranges are invalidated before writes/resizes, including failures after
  partial mutation. Growing a cached short tail cannot retain the old short block.
- Core file/memory/descriptor/network/range/slice adapters validate byte ranges before
  access. Memory-device shrink/regrowth clears discarded bytes. Stream/channel
  adapters preserve their zero-length/EOF and NIO truncate conventions.
- WebDAV and torrent entry MIME inference now uses `MimeRegistry` directly, matching
  other providers even when accessed without the router.

Network completion/flush does not imply a stronger server durability guarantee than
the protocol implementation provides. Actual access may change after capability queries.

## User-approved fallback for destinations without random writes

TRANSFER_TARGET describes a directory's ability to supply readable, writable,
seekable, resizable children. It is separate from seeking the directory itself.
The transfer router identifies sources requiring random writes through
`requiresRandomAccess`, rather than UI protocol checks.

For known unsupported targets, copying prompts before creating entries or starting
a container mutation batch. Unknown support is checked on the actual new handle;
only Unsupported triggers the choice, not a network or permission failure.

The themed prompt says continuing is highly discouraged, recommends another target,
and offers Choose another target, Continue anyway, and Cancel. Approval applies to
the current batch. Only its worker waits; other transfers and browsing remain live.
Concrete saved-connection and common-folder suggestions check current capability
reports asynchronously; actual file operations still establish write access. See
[common locations](common-locations.md).
Callers without a confirmation callback fail explicitly instead of silently staging.
`FileOperations.batchTransfer` and `StorageTransfers.transfer` accept that callback,
so other UI hosts can provide the same choice without depending on the main Activity.
Declining is recorded as a stopped operation rather than a storage failure.

Continue anyway downloads the complete selected file to temporary local storage,
then streams it into the target using normal size/copy verification and container
commit paths. Free local space is checked by the torrent reader; container snapshots
and rewrites can require additional space. Metadata already fetched is reused.
Streaming destinations use their final filename, avoiding an unsupported final
rename on FTP or archive entries. Normal failure cleanup still applies.

Existing direct-download partials are retained when switching to a new streamed
target; they are not overwritten. Streaming fallback has no destination piece-resume
guarantee. It may repeat its final copy after interruption. The torrent staging cache
has its existing separate lifecycle; it is not a new unified LRU implementation.
Automatic resume continues to require direct random access and does not silently
choose staging in the background.

## Remaining task list

- [x] Share source action and transfer-destination eligibility across menus, picker,
  clipboard paste, drops and transfer preflight, including contextual container moves.
- [ ] Move remaining mounted-source preflight I/O off the UI thread; retain the
  existing mounted-image protection. Observed in the accepted ZIP move dump.
- [ ] Complete action dispatch extraction from MainActivity. Toolbar/New/template
  visibility and special Virtual root link conversion still have UI-specific paths;
  preserve backend mutation errors and mounted/recovery guards during that work.
- [x] Validate native FAT32 file relocation on the handset: eight files, preserved
  payload clusters, one committed SAF publication and no warnings (19:24 UTC).
- [ ] Validate FAT directory moves, payload larger than remaining free space,
  FAT12/16/compressed variants, collision/cancellation/publication errors, and failure
  warning dialogs. Extend safe native moves to other capable backends so copy/delete
  does not impose unnecessary duplicate payload space.
- [ ] Model native versus staged access and publication guarantees explicitly,
  including writable nesting, transaction ownership and mounted-cache invalidation.
- [ ] Unify metadata preservation and unsupported-semantics reporting: modes,
  ownership, ACLs, extended attributes, hardlinks and native link recreation.
- [ ] Define seekable access for application APKs/catalog records where available,
  and on-demand torrent piece reads. Centralize remaining viewer materialization.
- [ ] Bring full-file torrent staging into explicit workspace/quota/cleanup policy;
  retain safe temporary-space checks and recovery data.
- [ ] Validate direct and approved staged transfers on the handset, including
  local/SAF reserve-and-resume, FTP/WebDAV, ZIP/TAR/FAT targets, multi-file batches,
  cancellation, low space, errors during close, and retained original partials.

See [original audit](code-consistency-audit.md), [architecture](architecture.md),
[current status](project-status.md) and [concurrent activity](concurrent-activity.md).

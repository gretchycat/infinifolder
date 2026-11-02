# Cancelling ordinary copies and moves

Implemented 2026-11-02. This applies to active ordinary file copy/move operations,
including selected groups and directory trees. Torrent continuation/resume is
user-confirmed working and retains its existing pause/cancel and partial-data policy.
Link creation, installed-app link conversion, deleted-file recovery and deletion
are separate operations with their existing policies.

## Controls and result

- **Cancel** appears beside the file progress bar and on each ordinary transfer
  row in Activity. It uses the existing confirmation dialog and coordinator.
- A cancelling transfer stays active with an **Undoing…** state until cleanup or
  restoration finishes. Its Cancel control is disabled during undo.
- Cancelling a copy removes all of that operation's newly created outputs,
  including already completed files and empty directories. Pre-existing files,
  including collision targets, are retained.
- Moves finish every destination copy and container publication before deleting
  originals. If cancellation occurs during original removal, missing originals
  are restored from verified destination copies before those copies are removed.
  Restoration uses the source provider's parent/name APIs, not guessed URI paths.
- Recreated originals are verified and their captured supported attributes restored.
  Existing original paths are never overwritten; changed or conflicting data,
  additional directory children, inaccessible paths and failed cleanup are retained
  and reported. An incomplete undo is a **failed operation requiring review**, not
  a successful cancellation. The error identifies retained destinations.
- Unpublished private container batches are discarded. Native in-container moves
  cross a final commit handshake before atomic publication; Cancel is disabled
  during that finishing step. Cancellation wins if requested before the handshake.
- Cancellation requests for ordinary transfers are dispatched immediately, rather
  than waiting behind transfer workers in the UI loading pool.

## Implementation

Store's `TransferUndoJournal` records actual created resource identities, parents,
source mappings, hashes, byte counts and captured attributes for one live transfer.
Rollback checks content/identity and removes known children before an empty parent;
it does not recursively delete an arbitrary destination tree. Source restore
conflicts retain the destination as a recovery copy. A source that cannot identify
its original parent is rejected for a non-native move before output creation.

`RecoveryCancellation.withoutCancellation` temporarily masks the cancelled
operation's inherited flag and clears its worker interrupt while undo runs, then
restores both. Coordinator snapshots track cancellation requested and final commit,
so completion, failure and cancellation feedback reflect the finished undo result.
The shared batch transfer and existing container/provider APIs do the work; UI
buttons do not implement file mutation.

## Validation and limits

Regression cases run through the real `FileOperations` implementation with a
stateful byte/directory backend. They cover completed and nested copies, collision
files, cancellation before and after original deletion, partial directory deletion,
original-path conflicts, externally edited copies, foreign children, cleanup errors,
unsupported source parents, repeated copy sources, overlapping move selections
and thread-interrupt masking. Real coordinator/transfer
integration checks keep cancellation active until restore finishes, surface undo
conflicts as failures, and reject cancellation after the final commit handshake.
Existing recovery, torrent and real container regression suites are retained.

Commands:

```sh
gradle :storage-engine:testDebugUnitTest --offline --console=plain --max-workers=2
gradle :app:assembleDebug --offline --console=plain
python3 scripts/verify-infinistream.py
```

The final gate builds the APK and reports **958 passing cases, zero failures,
errors, skips, regressions or missing baseline cases** (1 minute 40 seconds).
Storage contributes **370 passing cases**, including 16 new cancellation/undo
regressions. The historical 30-failure baseline remains unchanged and all 30
cases remain resolved. The earlier focused transfer/recovery/torrent run and
standalone APK build also passed.

Results and logs are under `build/transfer-cancel-repair/`: `tests.log`,
`storage-all.log` (the earlier 365-case storage run), `app-build.log`,
`full-gate.log`, `storage-summary.json` and `full-gate-summary.json`.
The underlying Gradle gate log is
`build/infinistream-migration/verification-gate.log`. No device was connected through
ADB during implementation; subsequent owner-run acceptance is recorded below.

The undo journal is scoped to a live transfer; it is not a persistent general Undo
stack or automatic crash recovery. Undo requires storage access and can take time
for large moved files. Storage failures or concurrent external changes can prevent
full reversal; those cases preserve recovery data and report the remaining paths.

## Owner-run acceptance: 2026-11-02, 23:17 UTC dump

The owner reports copy, Cancel and space-restricted copy failure behaving as
expected. Evidence is `build/infinifolder-debug-20261102-231740-886.{log,json,png}`,
from a Pixel 8 Pro with the APK updated at 23:14:00 UTC.

- A roughly 31 GB `devsda` copy into an SFTP-hosted FAT16 image is rejected during
  preflight at zero transferred bytes. The reported constraint is FAT16's
  4 GiB-minus-one-byte maximum file size; the image also reports 18,020,352 bytes
  free. This exercises the file-size guard, not a free-space exhaustion failure.
  The operation becomes FAILED and its warning is shown.
- Copying `ubuntu-26.04.1-desktop-amd64.iso` from SFTP into an SFTP-hosted ZIP
  reaches 400,790,180 written bytes. The direct progress button requests Cancel at
  23:17:33.517743; the operation becomes CANCELLED at 23:17:33.555988, about 38 ms
  later. The log reports partial output removed and source retained; the private
  container batch closes with `committed=false`, followed by “Cancelled; copied
  changes removed.” The cancellation exception is expected here, not a second
  failed operation. No completed copy is recorded in this retained dump; ordinary
  copy acceptance comes from the owner's report.
- The screenshot shows the browser after the operation windows have closed. It
  does not independently verify destination contents or source hashes.

Move-original restoration, completed group/directory rollback, conflicts and live
SAF cancellation still need handset acceptance. The dump also contains background
icon/archive-probe interruptions, remote permission/channel failures and main-thread
disk-I/O warnings; these remain separate follow-up findings rather than evidence
that the two recorded transfer outcomes failed. No code changes, tests or rebuild
were needed for this review.

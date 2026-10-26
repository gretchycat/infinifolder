# Torrent checkpoint safety checks

## Failed finalization follow-up (2026-10-26 21:18 UTC dump)

The operation history records all 6,482,409,472 bytes received and zero remaining,
then a failed final rename. Repeated blank SFTP write errors rotated the original
rename exception out of the captured log, so its exact server-side cause cannot
be established from this dump.

The following reliability fixes are implemented; successful handset finalization
is user-confirmed and recorded in the 21:39 UTC dump:

- Discard a pooled SFTP request channel after a failed operation, reopening a
  channel for the next request without replaying mutations or disconnecting
  independent readers on the SSH session.
- Stop the torrent write gate and report the first destination write failure
  rather than letting the engine retry it indefinitely and flood diagnostics.
- Clear an interrupt generated during successful torrent shutdown before the
  caller performs final publication. Pre-existing interrupts, failed shutdowns
  and explicit cancellation retain interruption semantics.
- Show a finalizing status before renaming. Include the SFTP rename stage and
  numeric error code when the server supplies a blank error message.
- Keep partial data and resume checkpoints when publication fails. Auto-resume
  now migrates local resume state after a successful rename before cleanup.

Finalization failures emit `torrent.publication.start/failed/complete` diagnostics.
The 21:39:08 sequence shows a successful SFTP rename, published checkpoint cleanup,
resume-state migration/cleanup and operation COMPLETE with 6,482,409,472 bytes and
zero warnings. Recovery reused 24,728 trusted pieces, completed the 24,729-piece
set, read 1 MiB from the destination and downloaded no additional payload bytes.
Evidence: `build/infinifolder-debug-20261026-213929-565.log`.

Validation: debug APK build succeeded in 1m 6s and `xdg-open` launched the APK;
`git diff --check` passed. No new torrent tests were added or run. Device retry
succeeded. Build log: `build/torrent-finalization-recovery-build.log`.

Remaining observations in this dump: Bt teardown logs closed incoming listeners
and rejected tasks after executor shutdown. These did not prevent finalization;
teardown ordering needs review. StrictMode also reports cache initialization and
debug-dump preference access on the main thread. The repeated blank SFTP write
errors precede the new process session at 21:38:59 and do not establish a recurrence
in the successful updated session.

Updated 2026-10-25. Normal checkpoint reuse is user-confirmed. The checks below are JVM tests with fake storage and the real Bt piece-hash verifier, not a replacement for handset/network interruption checks.

## Findings and fixes

A failed remote checkpoint write can still have committed its bytes before the acknowledgement or stream close fails. Previously, a failed clean publication could leave the session believing its sidecar was still dirty. The next destination write could then proceed without invalidating the clean checkpoint actually present on the server.

Clean publication now marks the in-memory state as potentially clean **before** attempting the write. A later destination write must publish a dirty checkpoint, or stop if that cannot be done. The fault-injection test commits the clean bytes, throws an acknowledgement error, denies the following invalidation, and confirms that the next destination write is blocked.

Oversized junk sidecars are now distinguished from unknown transport/read failures. Junk is preserved, caching is disabled for that transfer, and the destination is rechecked. A transport error cannot be treated as proof that no clean checkpoint exists.

The codec and checkpoint lifecycle were extracted from the provider into `TorrentCheckpointCodec` and `TorrentVerificationCheckpoint`, so tests execute the same code used by real transfers.

## Covered cases

| Case | Required result |
| --- | --- |
| Valid clean sidecar and matching destination metadata | Reuse exactly the recorded verified pieces without destination reads |
| Missing, empty, random or malformed sidecar | Do not skip hashing |
| Truncated or bit-flipped sidecar | Reject changed payload; harmless changes to Properties comments/whitespace may retain the identical verified set |
| Altered bitmap, invalid Base64 or missing checksum | Do not trust the checkpoint |
| Changed destination identity, size or modification time | Recheck destination bytes |
| Changed torrent, file path or piece size | Do not reuse the checkpoint |
| Out-of-range piece index | Reject checkpoint bitmap |
| Piece spans beyond the selected file | Recheck; a single-file sidecar cannot establish the neighbouring bytes |
| Interrupted write with dirty checkpoint | Recheck on restart |
| Every newly successful download or file-verification hash | Flush destination and persist its verified piece immediately |
| Interrupted startup scan | Restore pieces saved before interruption rather than waiting for full-scan completion |
| Already trusted cached startup piece | Restore without redundant hashing or checkpoint rewrites |
| Graceful save | Flush destination before publishing clean progress |
| Failed destination flush | Do not publish a new clean checkpoint |
| Rewrite of an already verified piece | Remove its old trusted status until it passes again |
| Failed hash of a destination piece | Keep it missing; never record it as verified |
| Unavailable checkpoint permissions without prior clean state | Continue with checkpoint caching disabled |
| Unreadable possible prior checkpoint / failed invalidation | Do not permit writes under possibly stale clean state |
| Lost clean-write acknowledgement | Require invalidation before the next destination write |
| Oversized junk sidecar | Preserve junk, recheck destination, and continue without checkpoint caching |
| Unrelated file using the `.cache` name | Do not overwrite or delete it |
| Corrupt owned sidecar with intact ownership fields | Rebuild it after checking actual data |
| Successful cleanup | Remove only the companion; preserve all downloaded bytes |
| Newly downloaded piece | Always run its hash check even if the same index was trusted at startup |
| Mixed good and corrupt destination pieces | Accept good pieces and leave corrupt pieces missing |
| Cancelled verification | Stop before reading more data |
| Finish later during a partial piece | Close the write gate, drain writes, save known verified pieces, and retain the partial download |
| Caller or Bt shutdown interrupt | Clear it for final checkpoint I/O and restore it afterward |
| Queued write after shutdown fence | Reject before destination mutation |
| Memory-cache retransmissions, overlap, gaps, size cap and LRU | Never expose stale overlapping bytes; misses fall back to destination |

## Running the checks

```sh
gradle :storage-engine:testDebugUnitTest \
  --tests 'com.infinifolder.torrent.*' \
  --tests 'com.infinifolder.storage.TransferCheckpointStoreTest' \
  --tests 'com.infinifolder.ops.FileOperationsTest' \
  --tests 'com.infinifolder.archive.RandomAccessContainerIoTest' \
  --tests 'com.infinifolder.mime.MimeRegistryTest'
```

Latest result: **102 tests passed, zero failures/errors/skips** (71 checkpoint/verifier/memory-cache/lifecycle tests and 31 related transfer/MIME regressions). The debug APK build also succeeded and opened through `xdg-open`.

The retained command output is `build/torrent-checkpoint-tests.log`; JUnit XML is under `storage-engine/build/test-results/testDebugUnitTest/`.

## Practical limits and remaining handset checks

- Fast resume trusts backend-reported identity, size and modification time. Corruption or outside edits preserving all of those fields cannot be discovered without rereading the data. Removing the `.incomplete.cache` companion forces a recheck; preserve the `.incomplete` data file.
- The sidecar checksum detects accidental corruption; it is not an authenticated signature against deliberate forgery.
- Remote durability depends on the backend's write acknowledgement and flush guarantees.
- Still check on the handset: a real network disconnect during checkpoint publication, application/process interruption, an external edit followed by resume, and companion cleanup when the final rename fails.

## Pause-time metadata failure (2026-10-26 follow-up)

The 03:42 dump records 6,712 trusted pieces at the 03:34:12 resume and at least 6,748 saved pieces before Finish later at 03:34:46. During shutdown a checkpoint read failed; the final pause record reported zero saved pieces. The next resume at 03:42:16 rejected an interrupted/incomplete checkpoint. The 03:50 dump shows the replacement checkpoint being reused with 626 trusted pieces and additional successful verification results being saved.

The clean-publication path previously converted an unavailable destination fingerprint into a successful dirty checkpoint write. A transient metadata failure could therefore overwrite a previously usable checkpoint even during a read-only startup scan. Clean publication now fails without modifying the sidecar when metadata is unavailable; deliberate dirty publication remains required before actual data writes. Remote stat/read failure details are preserved in diagnostics instead of being reduced to a generic error or missing metadata.

The already-invalidated checkpoint cannot safely be promoted to trusted state without checking the data. This change prevents this loss path going forward; it does not waive hash checks or restore overwritten old cache records. The downloaded data file is preserved. Build validation is complete; no new test run was requested for this follow-up. Device pause/resume confirmation remains pending.

## Verification checkpoint I/O optimization (04:16 dump follow-up)

The latest dump (`infinifolder-debug-20261026-041618-659.log`) shows checkpoint reuse increasing across resumes: 1,020 → 1,131 → 1,324 → 1,950 trusted pieces. At 04:14:58 the startup record walk advanced from piece 32 to piece 6,560 in about 0.104 seconds without destination reads. It then read about 30.75 MiB over SFTP by 04:16:17, taking roughly 79 seconds while saving newly verified pieces individually. Cache reuse itself was fast; remote rereads and per-piece sidecar publication remained expensive.

- Read-only verification retains its original destination fingerprint rather than fetching identical metadata for every checked piece. Every actual destination-write attempt invalidates that fingerprint before mutation, so the next clean publication reacquires it. Retaining an earlier fingerprint is conservative for outside edits: a changed destination no longer matches it on restart.
- Where supported, sibling checkpoint I/O retains a readable/writable/truncatable block device instead of opening a new read/write stream connection for each publication. Ownership is still checked before every write.
- Checkpoint payloads use allocation in 4 KiB increments and trailing Properties whitespace, avoiding resize requests for each new bitmap bit. Existing checkpoint format/checksum semantics remain unchanged; the 4 MiB limit still applies. Streaming fallback remains for unsupported backends.
- The retained device is closed after shutdown checkpoint publication. Every newly successful piece still triggers immediate persistence; no hash verification or checkpoint batching was removed.
- `torrent.resume.checking` now includes `checkpointSaves` and cumulative `checkpointSaveMs` to separate checkpoint overhead from data reads/hashing in the next dump.

Validation for this change: APK compilation succeeded and xdg-open launched it for installation. No new automated test run was requested. On-device throughput and repeated pause/resume acceptance remain pending.

## Retain checkpoint until publication

The downloader now saves and retains its companion even after every selected-file piece has verified. Successful random-access completion registers cleanup with the router; cleanup runs only after the caller successfully renames/publishes the completed file (or explicitly finalizes an already published destination). Automatic resume uses the same rename path. Pause, cancellation, verification failure and rename failure retain the checkpoint. Cleanup failure leaves a harmless companion and is logged; it does not undo the published download. `torrent.checkpoint.awaiting-publication` and `torrent.checkpoint.published-cleanup` expose the two stages.

## Restart during active downloading (2026-10-26)

Previously every destination write replaced the complete saved bitmap with a dirty
checkpoint that the next launch rejected wholesale. This discarded usable startup
verification results when the app stopped between downloaded piece completions.
The sidecar was already flushed on publication and its device closed during normal
shutdown; leaving the file open was not the primary cause.

Active writes now publish a checksummed `unverified-pieces-only-1` journal before
mutating data. That journal removes only pieces touched by the impending write.
Untouched verified pieces survive an interrupted download. Writing another saved
piece republishes its invalidation first. Failed or uncertain invalidation prevents
the data write while a previous authoritative checkpoint may exist. Verified
incoming pieces and startup hashes continue to flush and publish immediately.
Normal pause/shutdown still flushes data, publishes a clean metadata snapshot, and
closes the sidecar. Final publication remains responsible for deleting it.

Clean snapshots require exact destination metadata. Interrupted write journals
require matching destination, torrent identity, allocated size, payload checksum,
and valid piece bounds; their whole-file modification time may differ because
our own writes change it. This assumes exclusive ownership of an active partial
download: an outside program changing already verified bytes without changing file
size during that interrupted-write interval cannot be detected from this journal.
Legacy dirty sidecars still require rechecking. Missing, malformed, or truncated
sidecars likewise cannot safely skip verification. No torrent data is deleted.

Validation: debug APK build only; device restart validation remains pending.

## Empty download status and unknown pieces (2026-10-26)

A piece with no saved verification result is unknown, represented by an unset bit
in the verified bitmap. Torrent metadata supplies the expected hashes; a reserved
or zero-filled destination does not supply verified content. Incoming pieces still
require their normal torrent hash check before a durable verified bit is saved.

Startup verification now receives an explicit bitmap of pieces known to have
existing downloaded data. Absent pieces are not passed to the file hasher. With no
existing pieces, the UI enters the peer/connection phase directly and does not show
a startup verification bar or “saving verified progress.” Known cached pieces retain
the existing trust checks; known data without trusted checkpoints is still rehashed.
The engine may iterate empty records in memory, logged separately as empty-records,
without reading their destination ranges or computing hashes.

The download client now reuses metadata already obtained while browsing the magnet.
Magnet tracker URLs are attached to the cached Torrent object if it has no announce
list, so switching to a cached metadata source preserves discovery. This removes a
second metadata fetch that previously occurred behind the verification status.
Build validation only; new-download UI behavior remains pending device confirmation.

## Recovery acceptance (2026-10-26)

The user confirms Retry after failure reuses partial data and saved checksums, and
missing/corrupt checksum caches trigger safe rechecking of existing data. Normal
BitTorrent and magnet-link resume is also confirmed. These close the corresponding
handset workflow checks; individual storage-failure, process-interruption, and final
rename-failure cases above still require their own evidence.

# Transfer verification and completion feedback

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

## Device evidence

The `20261024-181453-728` dump shows a 30,945,574,912-byte SFTP-to-SFTP copy of `devsda`, with the transfer still active in the screenshot and no terminal/verification log record. The user independently compared the files using `diff` and confirms identical contents. This establishes copied-byte correctness for that transfer; the dump does not establish whether the app was still writing, closing a stream, or rereading for verification. Overall progress deliberately remains below 100% until all required steps finish.

A separate compressed-image browse failed after about 26 minutes at the 512 MB streaming limit. That is a different failure and remains a follow-up; it does not establish a copy failure.

## Implemented

- The transfer dialog includes **Verify copied data (remembered)**. Its setting persists across launches and is also available under Settings → Copy verification. Existing behavior defaults to verification enabled.
- The setting is consulted when a copy enters read-back verification and between verification reads. Turning it off can stop an ongoing ordinary copy verification; a currently blocked read/close still needs to return. Queued and subsequent copies use the current setting. Re-enabling it does not restart verification already skipped/completed.
- Disabling read-back verification does not skip transfer-length checks, flush/close, container publication checks, move verification before source deletion, or deleted-file recovery verification. Moves and recovery show verification as required.
- Verification displays its own byte count, total and percentage in the current-file progress bar, while overall progress remains below 100%. Source rechecks for moves are explicitly labeled too. Verification byte counts are not added to copied-byte totals.
- The operation window refreshes elapsed time and time since the last progress update each second, including when I/O stops producing callbacks. Flush/close have named phases; unknown-duration steps are not assigned invented percentages.
- Logs now record phase entry, periodic copied/verified bytes, written-data completion, coordinator state transitions and completion-feedback dispatch. A disabled notification permission/channel is logged as skipped.
- SFTP stream reads/writes/flush/close have a channel inactivity guard: at least 120 seconds, or a larger configured timeout. SFTP byte-progress callbacks renew it, so a long transfer that keeps making progress is not timed out merely for its total duration. On a stall the guard logs the phase/worker stack and disconnects that stream's channel, preserving other channels on the session.
- A timeout after a full-length destination write retains that destination for inspection and reports failure with the source retained. It does not declare success without the remaining required steps. Incomplete writes, confirmed hash mismatches and cancellation retain their existing cleanup policy.
- Successful completion still dismisses the progress dialog and sends the existing background completion notification. Genuine I/O/verification failures use the failure path, not a forced success notification.

## Device checks pending

- Copy with verification on: watch Writing, flush/close and Verifying, including an advancing verification bar and final completion notification.
- Turn verification off in the transfer dialog before read-back; verify the remembered state on the next copy and after restarting.
- Turn verification off during a large file's read-back; confirm the operation finishes once its active read/channel close returns.
- Check moves and recovery keep their mandatory verification and do not remove originals after failures.
- Exercise an actual stalled SFTP stream, confirm timeout/failure notification and retained full-length output where applicable. Normal healthy large transfers should continue beyond two minutes.

Debug APK compilation is the implementation check. No new automated tests were added or run for this change. Device confirmation remains pending.

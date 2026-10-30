# Operation dialog progress

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

The operation dialog now has two separately labelled horizontal bars:

- **Current file** shows bytes and percentage for the file being copied/moved, retaining its byte position during flush/close/verification. It resets for the next file. Unknown lengths use an indeterminate bar. Other operations without per-file byte reporting show **Current item** activity.
- **Overall** uses cumulative bytes or item counts. It remains determinate when totals are known, including during source setup/verification phases. Running operations stay below 100% until completion, including final container publication.

OperationUpdate and OperationSnapshot carry nullable currentItemBytes/currentItemTotalBytes separately from cumulative bytes/totalBytes. The coordinator clears per-file fields on updates that do not report them so an unrelated operation/phase cannot inherit the previous file. Existing notification progress behavior is retained.

Labels/status rows use fixed line counts and progress bars have fixed heights, preserving the dialog's stable sizing. Failure details remain scrollable.

Device checks pending: multiple small files, a large file, empty files, unknown sizes, copy/move to a container with publication, deletion by item counts, cancellation/failure and queued-operation switching. No automated tests were added or run.

## Verification feedback

Read-back verification now has a named phase and byte/percentage progress in the per-file bar; overall progress remains below 100% until required finishing steps complete. The transfer dialog has a remembered verification checkbox for ordinary copies, including stopping an ongoing read-back. Moves/recovery remain verified. Elapsed/wait status refreshes once per second during blocked I/O. See [policy and device checks](transfer-verification-feedback.md).

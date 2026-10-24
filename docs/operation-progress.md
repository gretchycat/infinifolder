# Operation dialog progress

The operation dialog now has two separately labelled horizontal bars:

- **Current file** shows bytes and percentage for the file being copied/moved, retaining its byte position during flush/close/verification. It resets for the next file. Unknown lengths use an indeterminate bar. Other operations without per-file byte reporting show **Current item** activity.
- **Overall** uses cumulative bytes or item counts. It remains determinate when totals are known, including during source setup/verification phases. Running operations stay below 100% until completion, including final container publication.

OperationUpdate and OperationSnapshot carry nullable currentItemBytes/currentItemTotalBytes separately from cumulative bytes/totalBytes. The coordinator clears per-file fields on updates that do not report them so an unrelated operation/phase cannot inherit the previous file. Existing notification progress behavior is retained.

Labels/status rows use fixed line counts and progress bars have fixed heights, preserving the dialog's stable sizing. Failure details remain scrollable.

Device checks pending: multiple small files, a large file, empty files, unknown sizes, copy/move to a container with publication, deletion by item counts, cancellation/failure and queued-operation switching. No automated tests were added or run.

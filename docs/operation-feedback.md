# Operations, progress and diagnostics

Updated 2026-11-04. This guide consolidates progress, verification and responsiveness
notes. See [project status](project-status.md) for confirmations and the
[task list](feature-todo.md) for pending variants. Historical build timings are not
new verification evidence.

## Completion and activity

Successful/cancelled dialogs dismiss automatically; warnings/failures retain review
details. Queues reuse the window. Tap the toolbar activity light to restore progress
or history. Background completion uses a separate notification channel; internal
viewers count as foreground. Permission/channel failures are logged without failing
the transfer. Completion notifications, automatic successful dismissal and debug-only
toolbar dump placement are owner-confirmed.

The light uses theme colors: green reads, dim-green waits, red file work and amber
verification. Verification takes priority over writes, reads and waits; playback
alone does not drive it. Read pulses last at least 200 ms, blink period is 700 ms,
and pending loads without recent reads remain steady dim green (alpha 70/255).
Lifecycle subscriptions release their counters; finished activity returns to idle.
Connection badges are separate and [owner-confirmed](network-icon-badges.md).

The bottom Activity strip summarizes action/item counts, aggregate rate/percentage
and rolling ETA. Terminal history and supported manual replay requests persist;
active ordinary queues do not survive process death. See [Activity](concurrent-activity.md).

## Progress and verification

- **Current file** reports bytes/percentage and retains position during flush,
  close and verification. Unknown lengths are indeterminate. Other operations use
  current-item activity. Nullable per-item fields reset between unrelated phases.
- **Overall** reports cumulative bytes/items and stays below 100% until finishing
  and publication complete. Verification bytes do not inflate copied bytes.
- Fixed line counts and bar heights keep the dialog stable; failure details scroll.
  Elapsed/wait time updates once per second even without callbacks. Flush, close,
  verification and source rechecks have named phases, without invented percentages.
- Ordinary copies have a remembered read-back checkbox. Turning it off during
  verification stops between reads; it cannot interrupt blocked I/O. Turning it
  back on does not retroactively restart skipped verification.
- Moves and recovery retain mandatory verification. The checkbox never bypasses
  length checks, flush/close, publication or move-source deletion guards.

SFTP channels use an inactivity guard of at least 120 seconds or the larger configured
timeout. Progress renews it, so healthy transfers can run longer. A stall logs the
phase/worker stack and disconnects that stream channel, preserving other channels.
After a full-length destination write, a finishing timeout retains the destination
for inspection and reports failure with the source retained. Incomplete writes,
hash mismatches and cancellation retain their cleanup policy. See [channel recovery](sftp-channel-recovery.md).

Ordinary copy/move **Cancel** reverses copied outputs and restores removed move
originals through the shared undo contract. Failures/conflicts remain reviewable;
torrents retain their continuation policy. See [cancellation](transfer-cancellation.md).

Pending checks include multiple/empty/unknown-size files, queues, publication,
verification changes mid-read, stalled channels, move restoration, denied notifications
and lifecycle recreation. A healthy copy does not prove every rollback/timeout path.
The historical 30,945,574,912-byte `devsda` copy was byte-confirmed by the owner;
its then-unlabeled finishing phase was not identified. The separate slow compressed
image browse failure remains in the [dump timeline](device-dump-follow-up.md).

## Copy verification

The optional copy checkbox and required move/recovery policies above share the
same transfer implementation. Logs record phase entry, copied/verified bytes,
written-data completion, coordinator transitions and feedback dispatch. Skipped
notifications are logged; failures are never forced through a success handler.
Check remembered settings after restart, disabling verification during read-back,
mandatory move/recovery checks and real stalled SFTP channels separately.

## Responsiveness and diagnostics

DiagnosticLog queues bounded records instead of appending synchronously under a
shared lock in touch/UI callbacks. A worker writes/rotates logs; the queue holds
about 2 Mi characters, each record is capped at 64 Ki characters, and dropped
records are counted. Snapshot export waits on its worker; only fatal handling
briefly waits for persistence. DiagnosticsActivity refresh uses worker generations.

Progress coalesces to about 10 UI updates/second and notifications to four/second;
terminal states remain delivered. Normal progress releases coordinator locks before
listeners. Initial submission/cancellation/external-update callbacks retain separate
behavior. Completion refreshes folders once rather than on each terminal redraw.

Debug lifecycle monitoring covers the browser and independent viewers:

| Event | Meaning |
| --- | --- |
| `ui.main-thread.io` | API 28+ StrictMode disk/network violations, rate-limited by kind/site; existing penalties retained |
| `ui.main-thread.stall` | Foreground heartbeat delayed at least 500 ms, sampled main-thread stack, at most once per two seconds |
| `ui.main-thread.recovered` | Delayed heartbeat finally executed |
| `ui.render-entries.slow` | Folder construction over 100 ms, with entry count/grid mode |

These events enter the retained bounded dump trace. Scheduling can delay a heartbeat;
inspect its stack before assigning a cause. Grid/list/tree construction still uses
Android Views on the main thread. Capture large-folder scroll/swipe/Back stalls
during work before prioritizing remaining synchronous reads, construction or locks.

## Export a debug dump without ADB

1. In debug builds, choose a dump folder in Settings, or long-press a directory and
   select **Use as debug dump folder**. Grant access through Android's picker as needed.
2. Reproduce the issue, then press **Offload debug information** (the toolbar bug icon)
   in the browser/viewer. The toolbar button is absent from release builds.
3. Inspect timestamped `infinifolder-debug-*.png`, `.log` and `.json` files. JSON records
   application/device data, Activity, capture method and dimensions. Screenshot
   failure still permits other artifacts; export errors report completed artifacts.

Picker configuration can create a live log; choosing a folder from the browser stops
any previous live-log mirror. The bounded private log exists without an export folder.
Backend-assigned unique names are retained. PixelCopy captures the app window;
view-drawing fallback can omit video surfaces. Logs may contain resource names and
screenshots show visible content. Dump export is owner-confirmed; backend/grant
variants remain conditional.

## Remembering warning visibility

Warning dialogs display a **Don't show this message again** checkbox for each shown
message. Selecting it hides that same message on future transfers, including after
restart. Different messages still appear; errors use their existing failure path.
**Settings → Reset hidden transfer warnings** restores suppressed popups. Hidden
messages still count as warnings in Activity and keep their full diagnostic records;
metadata/source-retention policy is unaffected. A timestamp-preservation limitation
is described as a timestamp notice, not a fabricated permissions error. See
[metadata reporting](metadata-integrity.md#document-provider-timestamp-notices--2026-11-04).

The owner confirmed the warning checkbox, copying and normal file launching on
2026-11-04. Restart/reset and other-warning behavior remain focused regression
checks; this confirmation does not establish every provider or move-undo path.

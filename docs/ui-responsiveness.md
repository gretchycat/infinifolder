# UI responsiveness investigation

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

The user suspects intermittent swipes/Back failures may be UI stalls or I/O waits. Previous dumps lack main-thread stall/violation evidence, so the precise cause of each failed gesture is not established. Code review identified several concrete contributors.

## Changes

- DiagnosticLog.record previously appended/rotated the session file synchronously under a shared lock, including calls from touch handlers. It now enqueues bounded in-memory records; a dedicated writer handles disk writes and rotation. The queue holds at most about 2 Mi characters and reports dropped records, with individual records capped at 64 Ki characters. Sanitization patterns are precompiled. Gesture/Back retention remains bounded and in memory.
- Snapshot export waits for the writer on the existing dump worker. DiagnosticsActivity log refresh also moved to a worker, with generation checks before updating its text. Only the fatal uncaught-error path briefly waits for log persistence.
- Transfer snapshots are coalesced to the latest state at approximately 10 UI updates/second rather than posting every setup/flush/verify phase separately. Terminal states remain in the snapshots and are delivered.
- Normal coordinator progress updates release the coordinator lock before invoking listeners. This avoids holding that lock during foreground-notification Binder calls while UI code requests snapshots. Initial submission/cancellation/external-update callbacks retain their existing behavior and may need later review if traces identify them.
- Notification updates are limited to four/second for the same active operation; operation switches and stopping remain immediate.
- Completed/cancelled operations trigger their progress-dialog folder refresh once, rather than every redraw of the terminal snapshot. Existing transfer completion refresh hooks remain.

## New diagnostic evidence

- ui.main-thread.io: StrictMode disk-read/disk-write/network violations on the main thread, recorded asynchronously on API 28+, rate-limited by kind/call site. Existing policy penalties are retained; no new crash penalty is added.
- ui.main-thread.stall: a foreground main-loop heartbeat delayed at least 500 ms, with a stack sampled from the main thread while delayed. Reports are limited to one per two seconds. Scheduling delays may also cause these events; interpret the stack before assigning a cause.
- ui.main-thread.recovered: delayed heartbeat finally executes.
- ui.render-entries.slow: folder view construction exceeds 100 ms, including entry count and grid mode.

UI evidence also enters the retained trace appended to offloaded dumps, so storage log rotation cannot remove it. Monitoring is attached to the shared activity lifecycle, covering the browser and independent viewers while an activity is resumed.

## Remaining scope and device check

The browser still constructs all grid/list rows and much of the tree as Android Views on the main thread. Theme icon resolution and other UI paths may also perform synchronous local reads. The new instrumentation should identify which paths dominate on this device before a broader rendering/cache rewrite.

After installing, browse a large mounted folder, scroll, swipe panes and press Back during copying and after completion. Offload immediately after a stall. Correlate touch/Back decisions with the retained ui events and sampled main-thread stack.

Build: gradle :app:assembleDebug succeeded in 11 seconds (build/ui-responsiveness-build.log), followed by xdg-open with the APK. No automated tests were added or run. Responsiveness improvement and monitor output await device acceptance.

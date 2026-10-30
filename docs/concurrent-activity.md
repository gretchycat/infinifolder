# Concurrent operations and activity overview

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-26. BitTorrent/magnet downloads and resuming, Retry after failure, missing/corrupt checksum-cache recovery, indefinite peer waiting, and simultaneous transfers are user-confirmed working. Detailed Activity UI and additional failure-case checks below remain separate.

## Scheduling

The default OperationCoordinator uses an elastic worker pool rather than a fixed
CPU-derived cap of two to four operations. Independent transfers can start together;
workers waiting on conflict locks do not consume a limited pool and prevent unrelated
work from starting. Idle worker threads are reclaimed by the executor. An explicit
concurrency argument still supports a fixed worker count for callers that need one.

Existing conflict keys remain: operations using the same source/output file wait for
that file, while unrelated operations run concurrently. A waiting snapshot explicitly
says it is waiting for another operation using the same file. Operations affecting a
shared archive/image may still serialize inside that backend to preserve container
integrity. Parallelism is across submitted operations; files within one batch retain
the existing batch processing behavior. This does not change torrent piece scheduling.

## Activity UI

Activity opens a live overview instead of selecting the oldest transfer window.
All active operations appear first, followed by up to fifteen recent results. Rows
show a filename/batch name, destination, and progress/status. Copy/create/archive and
automatic torrent-resume submissions carry explicit names and destinations. Delete
and external activity can use their operation label when no destination exists.

Tap an active row to open its existing detailed progress dialog, with overall/file
progress, rate, ETA, verification controls, and stop/finish-later action. The Activity
button returns to the list. Selecting a row now prioritizes that operation's ID over
the previous display order. Counts distinguish running and waiting operations;
another transfer is no longer described as queued merely because its details are
not currently displayed. Destination path discovery for normal transfers happens
on the transfer worker; network labels can be resolved from saved connection data.

An automatically presented overview dismisses when work finishes. A manually opened
overview retains recent results for inspection. Outside dismissal hides the UI while
operations continue. The activity light and Unhide actions return to the overview.
Status callbacks and returning from viewers no longer open a hidden Activity window.
Leaving the main browser dismisses its overview/detail windows without stopping work.
Opening Activity, tapping its notification, or explicitly starting a new operation
while windows have not been hidden are the opening paths. Explicit notification
requests arriving before resume are consumed once after resume; ordinary resume
only updates the light and hidden-operation bar. Hidden state survives Activity
recreation. Handset confirmation of this 2026-10-26 visibility correction is pending.
Foreground notifications summarize multiple active operations, and tapping the
notification opens Activity. The existing service observes the coordinator's complete
operation list; displaying a single detail does not suspend other operations.

## Directional Activity controls

Implemented 2026-10-26. Debug APK build succeeded and opened with `xdg-open`;
swipe activities are user-confirmed working in normal use. Rows show their swipe actions. Horizontal swipes request an action, with a themed
confirmation dialog before changing the operation or removing its status record.
Vertical gestures scroll the list; ordinary taps still open details.

| Entry | Swipe left | Swipe right |
| --- | --- | --- |
| Running/queued resumable download | Cancel | Pause |
| Running/queued operation without resume support | Cancel | Explain that Pause is unsupported |
| Failed/cancelled transfer with saved torrent session | Remove from list | Retry/Resume |
| Failed/cancelled copy, move or link-creation transfer with a replay request | Remove from list | Restart |
| Other finished entries | Remove from list | No restart unless supported |

Confirmation checks the current task state again, so a gesture captured before a
completion cannot stop a finished task. Clear finished also asks before removing
completed, failed and stopped records. The coordinator refuses to remove active
records; removal only clears history/bookkeeping, not payload or checkpoint files.
Details expose Remove as well, and supported transfers expose Restart/Retry/Resume.
The accessibility Dismiss action for finished rows uses the same confirmation.

Pause uses the torrent shutdown/checkpoint path and displays a paused state; Cancel
displays cancelled. User-stopped torrent sessions retain partial data and checkpoint
state, but their automatic-resume setting is disabled before stopping. Manual resume
reenables it. Unstopped active sessions retain their normal startup restoration.
Arbitrary stream copies do not yet have true pause/resume and only offer Cancel.

Copy/move/link restarts store an immutable request with the coordinator snapshot,
without retaining an Activity or completed worker closure. Sources are checked on a
worker before restarting. Missing/unavailable sources reported as not-found are
listed for review before restarting the remaining sources; other stat failures
explain why restart cannot proceed. A restart can repeat previously completed copies,
using normal collision-free naming rather than overwriting destination files.
Other operation types without a replay request do not advertise Restart. Terminal
history and its data-only replay requests are now saved as described below. This
does not add automatic process-death recovery for active arbitrary copy/move
operations. Torrent sessions retain their existing durable resume support.

### Persistent results — 2026-10-26

Completed, failed, paused and cancelled records are saved in app-private
`files/operations/history.json` using atomic replacement. Loading, JSON encoding and
disk synchronization run on one background worker. Progress callbacks only schedule
work; the worker reads current coordinator state to avoid reviving removed rows
from an older callback. New results merge with restored history without replacing
live operations. Invalid history is backed up when possible, with diagnostic events.

Records remain until Remove or Clear finished; those changes are saved too. The
overview initially shows the latest 15 results and offers **Show older results**.
Restored records do not replay completion notifications, warning dialogs or light
pulses. Failed/stopped transfers retain supported manual Restart/Resume actions.
Playback records and active ordinary operation queues are not saved here. Torrent
sessions/checkpoints continue to use their separate durable storage. Writes are
asynchronous; an immediate process kill can interrupt a newly scheduled save.

The user corrected the reported Ubuntu completion: it was cancelled, and tapping
its `.incomplete` file successfully resumed it. This confirms manual resume after
cancellation, not full completion. Relaunch persistence and removal persistence
need handset acceptance after installing this revision.

The 20:00 UTC follow-up confirms swipe activities working. Its dump records five
history removals and an explicit running-torrent cancellation, with CANCELLED status
and retained partial progress. The other download stays active. This does not by
itself establish every action variant or the separate refresh correction.

Remaining focused acceptance: left/right swipes with Back/Keep versus confirmation; vertical
scrolling; task completion while confirmation is open; active rows surviving Clear
finished; failed-history removal; torrent Pause/Cancel/manual resume and app restart;
ordinary-copy restart after destination failure; partial moves with missing sources;
and accessibility/detail-button paths. No automated tests were added or run.

### History-removal refresh correction — 2026-10-26

The user reported that removed Activity entries remained visible until the dialog
was closed and reopened. Overview and detail dismissal callbacks now check their
dialog identity before clearing shared UI references; a delayed callback from an
older window cannot detach the newer visible overview/detail. Batched UI updates
read current coordinator snapshots, so a delayed worker notification cannot restore
a removed history row. Single-row removal and Clear finished refresh immediately
and again when their confirmation dialog closes. The open list, counters, button
state and empty state use that current snapshot without needing to reopen Activity.

Build/handset acceptance is recorded in project status. No automated tests were
added or run. Check removal of one failed entry and all finished entries while
other transfers continue, removal of the final row, and repeated detail/list changes.

## Failure warnings

Implemented 2026-10-26; focused handset acceptance pending. A subsequent installed
build exercised native FAT relocation; BitTorrent and magnet completion tests are
still running.

A failed non-playback operation queues a themed warning with the operation name,
current item, destination and reported reason. The coordinator retains useful nested
exception messages so a wrapper cannot conceal a server/permission/I/O reason.
Activity rows also show a failed task's reason, with full text available in details.

Warnings appear one at a time in the resumed browser or viewer. If the app is in the
background, the existing failure notification still applies and the dialog waits
until an app activity resumes. Rotation or opening a viewer retains an unread
warning; acknowledgement, Back or tapping outside dismisses it. The Activity button
explicitly opens the task list. Failed tasks remain there for inspection/retry;
success, pause and cancellation do not create failure warnings. Successful tasks
still dismiss automatically and do not require acknowledgement. A repeating
mounted-error dialog in the detail refresh path was removed; preflight mounted-image
protection remains intact.

Focused checks after the current downloads finish: fail a disposable transfer with
permission denied or a disconnected destination; repeat with Activity hidden and a
viewer open; fail two independent transfers; rotate with an unread warning; verify
background notification/return and that paused transfers do not warn.

## Bottom activity status bar (2026-10-26)

Hidden ongoing operations now use a compact themed strip below both browser panes,
above the system navigation inset. It shows active action count, processed/total
reported items, combined rate and overall percentage. The entire strip opens Activity.
It uses no space when no operations are active or overview/detail windows are visible.

OperationActivitySummary excludes playback and terminal history. Rates sum fresh
running-operation samples (five-second stale cutoff); queued work contributes to
counts and progress, but not rate. Progress uses byte weighting when all active byte
totals are known, item weighting for item-only operations, and equal action weighting
for mixed known progress. Unknown progress shows —%; unknown item totals show ? or +
alongside known counts. Item counts retain each producer's existing meaning, including
selected root entries for directory-copy batches. Rates use the same formatter as
operation details. Counts/percentage describe the current active set, so adding or
finishing an action can change that percentage. Verification retains overall transfer
position; final publication stays below 100% until completion. A one-second refresh
also expires stale rates while I/O is waiting. The user confirms the status bar works
well (2026-10-26); further status-bar polish is deferred at their request.

### Overall ETA from a rolling minute

The bottom bar also shows ETA from remaining reported payload bytes divided by a
time-weighted average of combined transfer rates over the last 60 seconds. Before
a full minute exists it uses available elapsed history, with a two-second warm-up.
Intervals are measured with a monotonic clock; notification frequency does not give
busy operations extra weight. Individual reported rates expire after five seconds,
including when no UI callback ran during the gap. Zero-rate waiting intervals count
toward the average, so prolonged stalls increase ETA and eventually show waiting.

The coordinator retains this history while windows/viewers change and while work
runs in the background. It resets when no non-playback operations remain active.
Torrent rate samples use actual payload statistics, excluding restored cached-byte
credit; verification rereads do not count as new payload throughput. Remaining-byte
reports take precedence over total-minus-progress, including resumed torrents.
Unknown remaining work stays estimating; byte-complete work shows finishing rather
than a misleading zero-second completion prediction. This estimates remaining data
delivery, not an independently timed verification/publication tail. The displayed
instantaneous rate and individual operation detail ETAs retain their separate roles.

The bar stacks counts and metrics when their measured text does not fit on one
line, preserving the ETA on portrait screens/large fonts. It remains completely
absent when unused. Debug APK build succeeded and opened with xdg-open. The user
subsequently confirms the status bar works well; this closes normal workflow
acceptance. Further refinement and detailed timing/appearance checks can be revisited
later rather than blocking the next architecture work.

## Device checks remaining

- Tap each row and verify the correct destination, progress, and stop action.
- Start conflicting operations and confirm the waiting reason while unrelated work runs.
- Hide/reopen Activity and tap the background notification.
- Confirm automatic dismissal and theme rendering in light/dark modes.

## Clearing finished history

Activity includes a Clear finished button, enabled whenever terminal records exist.
After confirmation it removes completed, failed, paused and cancelled records,
including ones beyond the recent-results limit, and keeps the overview open.
Individual results also offer Remove from list. Removal is saved and affects only
history/bookkeeping; running and waiting work is retained. Transfer files and
resume/checksum state are not deleted or cancelled.

## Per-transfer rates and torrent peer details

Each running overview row includes its own download/transfer rate in a separate
right-aligned field, so long status messages cannot hide it. Idle/stale rates show
zero. The overview refreshes once per second in addition to progress callbacks.

Torrent detail shows connected peers, Serving (last 10s), upload bytes per second,
and uploaded payload bytes. Serving counts distinct peers still connected to that
runtime for which a payload Piece was accepted into the outgoing pipeline during
the last ten seconds. It is an activity window, not a tracker-wide swarm count or
proof of remote acknowledgement. Metadata responses are not counted as file uploads.
Upload/download rates use monotonic-time deltas of the torrent session's payload
counters, sampled each second; the engine's session log remains throttled to ten
seconds. No torrent traffic or verification policy is changed.

Statistics flow through the storage transfer callback, operation updates, and
snapshots for both manual copies and automatically resumed torrents. Runtime-local
activity tracking prevents concurrent torrents' peer counts from mixing. Normal
file copies retain their existing transfer-rate measurement. Stopped operations
show zero live peer/upload activity while retaining total uploaded bytes.

Validation: debug APK builds only; device checks of download rate, connected peers,
and an actual nonzero upload are pending.

## Retry and waiting indefinitely for torrent peers

Failed torrent detail pages offer Retry once a saved destination/session exists.
Retry schedules resume for that exact destination through the shared router; it
keeps the partial file and checksum sidecar. A currently active transfer for that
destination is reused instead of scheduling a duplicate. Paused torrent results
also offer Resume. This retries the current torrent file, not an entire multi-file
copy batch, and does not replay destructive move/delete jobs. If the saved session
has already been removed/completed, the UI reports that no session remains.

Torrent details include a themed, remembered Wait indefinitely for peers (all
torrents) checkbox. It is shared by active and future torrents and read dynamically.
It disables the metadata peer-wait deadline and download peer/data idle deadlines
once startup checks finish, including external-reader torrent downloads. Tracker
query intervals and individual network request timeouts are unchanged, so dead
endpoints do not become blocking socket calls forever. Engine termination and
storage failures still fail normally. Finish later/cancellation still works.

With the option off, existing deadlines apply. Turning it off after a long wait
can therefore cause an already-overdue operation to time out promptly. Status text
distinguishes downloading, waiting for peers, and waiting for data from connected
peers, and explicitly indicates no timeout when enabled.

Validation: the user confirms Retry after failure, missing/corrupt checksum-cache
recovery, indefinite peer waiting, and simultaneous transfers working (2026-10-26).
These are handset reports; the original 073706 dump predates those successful runs.

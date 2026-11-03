# SFTP channel opening and reconnect indication

Implemented 2026-11-02. Handset retry remains pending.

Final debug APK build succeeded in 23 seconds (410 tasks, 6 executed). Whitespace
checks passed. ADB reports no connected device; the repaired badge and recovery
behavior have not been observed on the handset.

## Evidence and cause

Reviewed `build/infinifolder-debug-20261102-232702-296`, `233030-475`
and `233611-052` dump sets. All report APK update time 23:14:00 UTC,
before this repair. Repeated “channel is not opened” errors occur in metadata,
icon probes, archive reads and browser requests. They persist across multiple
requests; this is broader than a single cancelled transfer.

The client had two relevant gaps: range devices each retained an independent
channel, with no aggregate stream bound, and its one-shot read recovery did not
recognize channel-opening failure while the SSH session still reported connected.
Listing/stat queries also bypassed that read recovery. These gaps allow concurrent
probes to exhaust channel capacity and leave later requests repeatedly failing.
The old dumps do not record the SSH rejection reason, so server refusal versus
an interrupted confirmation cannot be established from their message alone.

[JSch's channel implementation](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Channel.java)
uses the same exception for failed confirmation and server rejection, and swallows
an interrupt while waiting for confirmation. New diagnostics retain the SSH reason
code from `exitStatus` when available, plus the live stream count. No new logging
includes credentials or target URI tokens.

The latest dump additionally records “Move Ringtones” cancelled during preparation
at zero bytes: RUNNING at 23:35:55.166, Cancel at 23:36:03.700 and CANCELLED at
23:36:03.719. It does not establish restoration after original deletion. Its
screenshot shows archive browsing still loading on the old build. The earlier
23:30 dump records two Ringtones copies successfully publishing into ZIP, each
with one warning; COMPLETE with warnings is not a clean whole-copy verification.

## Changes

- `SftpClientPool` separates one reusable range channel from the serialized
  metadata channel. Block devices borrow the range channel for a complete
  offset read/close; idle devices no longer reserve independent SSH channels.
  Range caching and one-request read-ahead remain in place.
- Independent read/write streams are limited to four per current session, leaving
  channel capacity for metadata and range access. Capacity waits check cancellation
  and time out explicitly. The server can still impose a smaller limit.
- Channel setup runs on a bounded executor, apart from cancellable UI/transfer
  workers. A cancelled waiter arranges to close a channel confirmed later; queued
  abandoned requests skip setup. Setup failures and timeouts remain errors.
- Read-only metadata calls retry once after a dead session or channel-setup error.
  Opening an independent stream or range channel can also reconnect once before
  executing file I/O. Write callbacks and partially executed writes are never
  replayed. Cancellation prevents reconnect attempts.
- A replaced, connected session is retired while its active calls and streams
  finish. Reaping waits for their ownership to end; a refusal to open a new
  channel does not disconnect existing write streams. Retired sessions are bounded
  and reaped on subsequent pool access; explicit disconnect/close releases all.
- Channel/stream cleanup defers an inherited worker interrupt while closing,
  then restores it. Failed range operations discard the range channel before reuse.

## Badge

`StorageActivity.Kind.RECONNECTING` counts concurrent reconnect attempts. The
existing toolbar activity light takes blinking red priority during the attempt,
with accessible description “Reconnecting.” It returns to its ordinary activity,
completion or idle state when all reconnect attempts finish or fail. The dot
retains its existing tap action. There is no “Wireless reconnecting” text label.
Initial connections do not count as reconnects; later recreation of a previously
connected session does. The host listens to the shared activity signal, rather
than parsing diagnostic logs.

## Validation and remaining acceptance

`gradle :app:assembleDebug --offline --console=plain` builds the app and changed
Store sources. Logs: `build/sftp-channel-repair/app-build.log` and
`app-build-final.log`. No automated tests were added or run in this increment.
The prior 958-case gate predates these changes and is not validation of this repair.

On-device checks remain: repeatedly browse the same SFTP directory and ZIP with
icons enabled; cancel a copy and browse again; exercise simultaneous reads/writes;
interrupt and restore connectivity while observing the red badge; confirm errors
remain visible if reconnect fails. New reason codes distinguish further server
limits from interrupted setup. Serialized range reads and the stream cap may
reduce peak parallel throughput; current fixes need this live comparison.

## First repaired-build dump: 2026-11-03 00:06 UTC

`build/infinifolder-debug-20261103-000635-639.{log,json,png}` reports APK update
time 2026-11-02 23:43:13 UTC, after the repair. The owner reports the connection
problem did not recur and a brief network interruption was too quick to observe
the red badge. The retained dump contains no “channel is not opened” error or
`sftp.session.reconnect` event. It does contain hostname-resolution failures at
00:04:11 and FTP/WebDAV/SMB connection timeouts, then successful SFTP listings and
range reads after connectivity returned. Permission-denied and cancelled icon
probes remain present. This supports the exercised browsing recovery; it does not
establish a particular reconnect route or visual badge acceptance.

Added `storage.reconnect.begin` / `storage.reconnect.end` diagnostics to the shared
activity signal, reporting active counts and attempt duration. These cover pooled
session recreation as well as the explicit one-shot recovery path. They do not
force disconnection, change the blink duration or hold red after an attempt ends.
The next dump can establish whether a very brief reconnect indication was actually
triggered. The tree-icon lifetime follow-up is documented in [resource icons](resource-icons.md).

# Connection badges on remote icons

Implemented 2026-11-02. Connection badges are owner-confirmed on the handset.

## Evidence and correction

Dumps `build/infinifolder-debug-20261103-015506-847` and `015751-122`
report the 00:17:20 UTC APK. Reconnects include repeated hostname failures in
1–9 ms while offline, then a later approximately one-second attempt followed by
successful SFTP listings. The earlier implementation changed only the toolbar
activity light, with no icon badge or persistent disconnected state. A fast
failure could immediately return to ordinary activity indication.

## Badges and targets

- A blinking red dot overlays the connection's icons while reconnecting.
- A steady unplugged symbol remains after an observed transport failure until
  a later successful connection/provider request. Connected/unobserved targets
  have no connection badge.
- The same 16 dp native Canvas/View overlay covers list/grid, floating containers,
  Virtual Root location cards, tree/picker rows and pins. Pin artwork replacement
  restores its badge. Existing primary artwork and shortcut/permission/selection
  emblems retain their behavior. Badges do not consume taps; detached/hidden
  badges stop animation and unsubscribe.
- `.iflink` and the existing legacy shortcut alias follow their parsed target,
  including with embedded or explicitly chosen icons. Shared
  `ResourceIconResolver.shortcutInfo` supplies the bounded parsed metadata; the
  badge introduces no second decoder. The revision-keyed memory target cache
  notifies existing views and clears with resolver invalidation. Unreadable links
  without a known target cannot claim a particular destination's state.
- Archive/image URIs unwrap to the backing connection, with bounded nesting.
  Host aliases use the existing saved-connection binding. Backend plus connection
  ID separates different accounts on the same host. External web/mail/phone
  targets have no fabricated storage-connection badge.

## State and limits

Store's `NetworkConnectionStatus` tracks UNKNOWN, CONNECTED, DISCONNECTED and
RECONNECTING. Request versions prevent older results overwriting newer completed
ones. SFTP session recreation reports explicit reconnect state. Routing observes
SFTP, FTP/FTPS, SMB and WebDAV provider calls, plus stream/positional transport
errors. These wrappers preserve bytes, exceptions and close/flush behavior; they
add no buffers or write retries. Buffered reads and metadata-only parent/capability
helpers do not assert a fresh connection.

DNS/socket/timeout/closed-session failures mark disconnected. Permission errors,
missing files, unsupported actions and deliberate cancellation do not. This is
process-local observed backend state, not radio monitoring or polling: actual
requests and existing transport timeouts detect loss. It adds no preferences or
credentials. `network.connection.state` records transitions alongside existing
`storage.reconnect.begin/end` timings.

## Validation

`gradle :app:assembleDebug --offline --console=plain` builds app/Store changes;
logs are in `build/network-icon-badges/`. Whitespace checks passed. No automated
tests were added or run. The first build passed in 2m 43s; later builds include
streaming error observation and final cleanup. ADB has no connected device.
The owner subsequently confirms connection badges working (2026-11-02). This
accepts the reported badge workflow. Offline/reconnect clearing, `.iflink`
variants, account separation, pins/tree and floating-window cases were not
individually specified. The reviewed dumps predate these icon badges.

Final debug APK build passed in 11 seconds (410 tasks, 17 executed), including
cached per-handle connection identity and preservation of local output stream
types. Log: `build/network-icon-badges/app-build-final.log`.

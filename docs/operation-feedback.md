# File operation feedback

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Updated 2026-10-24. See [current status](project-status.md).

Successful/cancelled operation dialogs dismiss automatically; warnings and failures retain review details. Queues reuse the progress window. Tap the toolbar light to restore active progress or recent operation history. Background completion uses a separate notification channel from foreground-service progress; viewing an internal viewer counts as foreground. Notification permissions/channel settings still apply and failures are logged without failing the transfer.

The user confirms background completion notifications, automatic successful-dialog dismissal and debug-only toolbar dump placement.

## Activity light

The light is at the right of the toolbar. Theme defaults are red `#E53935` for file operations, green `#4CAF50` for loading and amber `#FFB300` for verification. Verification takes priority over file work, then active reads, then pending load waits. Playback alone does not drive it.

- Actual reads pulse green; short pulses remain visible for 200 ms. Blink period is 700 ms.
- Loading pending without recent reads is **steady dim green** (alpha 70/255 of the same theme color), with no blinking animation scheduled.
- File-operation and verification counters drive red/amber activity. Completion briefly fades; warnings use amber and failures red.
- Once loads finish and held activity expires, the indicator returns idle. Loading counters count running tasks, not merely queued work.

Shared StorageActivity counters/pulses cover routed streams/block reads, metadata work, browser loading and content verification. Subscription cleanup follows Activity lifecycle. The latest loading correction and dim-wait appearance are user-confirmed.

## Remaining checks

Overlapping load/write/verify work, narrow layouts and custom theme contrast; denied/revoked notification permissions; viewer completion, queues and Activity recreation. These are broader regression cases, not a claim that the confirmed behavior remains broken. Recent implementation builds succeeded; no new tests or build were run for this documentation update.

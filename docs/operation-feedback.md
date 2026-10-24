# File operation feedback

## Implemented

- Successful copy, move, and other queued file operations dismiss their progress window automatically. Cancelling also dismisses it. A queued operation takes over the same window; failures still retain their error details. Transfer warnings keep their separate review dialog.
- A small light at the right of the browser toolbar pulses while file operations are active. It briefly lights and fades at completion, using the theme accent. Warning results use amber and failures use red. Tapping the light opens operation progress. Playback does not drive this indicator.
- Operations finishing while the app is backgrounded post a completion notification, separate from the temporary foreground-service progress notification. Notifications report warning counts or failure messages and open InfiniFolder when tapped. Viewing an in-app viewer counts as foreground use.
- Completion notifications are observed once per operation across activity recreation. Android 13+ requests notification permission once at the first operation. Android notification permission and channel settings apply; denied permission does not interrupt transfers.
- Notification work runs on a worker thread. The light only schedules redraws while active or briefly completing and visible.

## Device checks still needed

- Foreground and background copies and moves, including short operations and queues.
- Successful dialogs close automatically, and warning/error details remain accessible.
- Notification permission denied, notification tapped, and completion while using a viewer.
- Toolbar fit, light contrast with different themes, and activity recreation.

Compilation is checked with the debug APK build. Device behavior needs confirmation from the installed APK.

# Storage safety and architecture progress

Updated: 2026-10-22. Tracks implementation following the [original consistency audit](code-consistency-audit.md). Implementation status is distinct from device/server acceptance.

## Debug dumps without ADB

1. Browse to the intended destination, such as the project's `build` directory through Termux's document provider. Long-press its folder and select **Use as debug dump folder**. Alternatively, open **View options → Debug dump settings → Choose debug dump folder** and grant access through Android's picker.
2. Reproduce the problem. Press the bug icon (**Offload debug information**) in the browser or viewer toolbar in debug builds, or use View options. The toolbar button opens dump-folder configuration if needed; it does not overlay file or document content and is absent from release builds.
3. The chosen directory receives timestamped `infinifolder-debug-*.png`, `.log`, and `.json` files. JSON records device/application details, capture method, dimensions, and activity. Screenshot capture failure still permits log and metadata export.

The picker also creates a live log in the selected folder. Choosing a folder from the browser configures on-demand dumps and stops any previous live-log mirror. The bounded private log remains available without a configured destination. Existing dump files are never intentionally overwritten; backend-assigned unique names are retained. Export errors report how many artifacts completed.

Only the current app window is captured when requested. PixelCopy is preferred; drawing the view hierarchy is the fallback, which may omit video surfaces. No ADB connection is required. Screenshot capture and actual folder grants still need device validation.

## Data protection implemented

- Local links retain lexical resource identity; local/SFTP recursive deletion does not follow symbolic links. Storage roots are protected. Link transfers fail explicitly until preservation is supported.
- Browser and provider copy/move share complete preflight, collision naming, size/SHA-256 verification, source rechecks, cancellation, and partial-move reporting. Failed/incomplete listings cannot silently become successful empty copies.
- Local and SMB create-new operations use exclusive creation. SFTP uses a reserved temporary file plus the server's advertised OpenSSH hardlink extension; it refuses safe creation if that extension is absent. FTP uses STOU, verifies the resulting unique name, and returns that actual name. FTP rename is disabled because portable no-replace semantics are unavailable. SAF still depends on the document provider's create behavior and flags.
- SAF random writes use the writable channel. Seeking is probed, failed descriptors are closed, and writable requests cannot silently return read-only handles.
- Nested archive/disc-image mounts remain read-only until outer-layer commit and integrity updates exist.
- Text, Markdown, CSV, and HTML edits require a complete document load. Sessions retain a fingerprint of original source bytes; stale saves are rejected before replacement. Sampled image edits export new PNG copies.
- Local replacement uses synced atomic replacement where supported. Stream replacements retain a synced, checksummed original and recovery state, verify replacement bytes, and attempt verified rollback on failure.
- Pending recovery copies are shown on startup and in Recovery. The user can export, explicitly restore after checking the current revision, or discard a backup. Interrupted saves are never automatically replayed.
- Audio/video/PDF/EPUB staging distinguishes owned temporary files from borrowed originals; viewer cleanup cannot delete originals based on names.

## Unified storage architecture

`:storage-engine` owns provider contracts, backend implementations, mounts, transfers, MIME policy, link resolution, connection persistence, and diagnostics. `StorageRuntime` owns one application-lifetime provider graph and remote pools. Closing a browser Activity cannot invalidate a viewer's storage connection.

Common hooks include URI ownership, metadata, parent lookup, capabilities, streams, seekable handles, links, and optional atomic replacement. Capabilities distinguish supported, unsupported, and unknown and retain per-document SAF operation flags. Browser mutation menus consume these results. Remote behavior remains conditional on server features and permissions.

| Backend | Random read | Random write |
| --- | --- | --- |
| Local | Native | Native when allowed |
| SAF | Probed descriptor or stream fallback | Writable seekable descriptor when available |
| SFTP | Offset reads | Disabled pending a validated offset-write adapter |
| FTP/FTPS | Checked restart support and range cache | Unsupported |
| SMB | Native with range cache | Native when permitted |
| Nested archive/disc entry | Direct slice or staged snapshot | Unsupported |
| Installed applications | Stream extraction | Unsupported |

`:viewer-common` supplies document access, launch contracts, themes, staging adapters, recovery, and diagnostics. Nine format modules contain the viewers and editors. `:viewer-host` can include one selected module and has no dependency on `:app`; see [viewer modules](viewer-modules.md).

## MIME, packages, and links

`MimeRegistry` supplies normalized aliases, extension inference, package/container identity, and MIME icon candidates. Icon themes and custom MIME icon keys use it directly. ZIP-packaged APK, LÖVE, EPUB, Office, and related formats retain application MIME identities while sharing ZIP reader dispatch. Opening and long-press Browse remain separate actions.

Native symbolic links and application shortcut files use one cycle/depth-limited resolution path. New shortcuts use `.iflink` and `application/vnd.infinifolder.shortcut+json`. Legacy JSON `.lnk` is accepted; binary Windows LNK is explicitly unsupported. Indexed ZIP entries retain Unix link metadata.

## Remaining risks and agenda

- Live SMB/SFTP/FTP behavior, SAF grants, screenshot output, and installation flow require device/server checks. No ADB device is connected here.
- Revision verification is not a remote transaction: another client can change a source between the final check and mutation/deletion. Cross-process conditional writes and stronger transfer publication are still needed.
- Stream replacement recovery protects an original copy but cannot make remote writes atomic or guarantee server durability. Recovery requires the original provider/credentials to remain available.
- SAF create semantics are controlled by the provider. SFTP servers without hardlink support intentionally lose create/rename-file availability rather than risk replacing an unrelated destination.
- External editors receive read-only staged snapshots; a conflict-aware external write-back lifecycle remains open (A12).
- Mounted filesystem case semantics, full streamed-ZIP link metadata, and broader seekable viewer consumption remain open (A23/A24). Some viewers still materialize content with limits.
- Nested writable commit chains, full-resolution/source-format image editing, transaction-safe remote rename, and complete mounted session cleanup remain future work.
- NFS, WebDAV, and cloud catalog entries are placeholders, not functioning backends.

## Validation

The module regression suite passed **223 tests**, with zero failures, errors, or skipped cases, and app/standalone-host Kotlin compilation passed. A PDF-only host also passed compilation and manifest processing with `-Pviewer=pdf`, demonstrating the browser-independent build graph. See `build/architecture-validation.log` and `build/standalone-validation.log`; module test reports are under each module's `build/reports/tests/testDebugUnitTest`.

Tests cover incomplete listings, collisions, corrupt transfers, failed source deletion, link identity/cycles, exclusive local creates, stale saves, recovery checksums/conflicts, rollback, routing, handle/staging ownership, MIME/package classification, credential redaction, and debug export failure handling. These checks do not establish real-server durability or prove device screenshot behavior.

The final `gradle testDebugUnitTest :app:assembleDebug --offline` run succeeded (223 tests, zero failures/errors/skips). The debug APK was opened with `xdg-open` after the successful build. Build output: `build/final-build.log`.

## First device dump follow-up

Debug export is now confirmed on the Pixel 8 Pro: three readable screenshot/log/metadata sets arrived in the project root. Follow-up code changes address UI-thread capability queries, SFTP channel isolation and suppressed upload-close errors, disc-image link metadata and misleading read fallback, contextual copy failures, and empty-space/folder menu parity. See [device dump findings and acceptance tasks](device-dump-follow-up.md). The new APK assembled successfully and was opened; these changes need another device run. The destination EIO's underlying cause remains unconfirmed.

The second device run localizes the repeated copy EIO to the first SAF destination write after a successful source read. Source retention and partial cleanup are confirmed in the log. Destination hardware/provider cause is still unconfirmed. Dump completion now refreshes folder/log views automatically; transfer logs identify the destination and separate read/write byte counts. Old root dumps were archived under `build/debug-dumps/previous-root/`; the new dump arrived directly in `build/`.

## Filesystem limits

Added shared filesystem/limit reporting, FAT-family size/name preflight for copy/move, conditional large-file failure hints for unknown destinations, and mounted FAT geometry/filename awareness. Folder Properties and dump JSON expose detected information. Related FAT directory-slot, alias-collision, and overwrite-allocation defects were corrected. See [implementation scope, evidence, and pending acceptance](storage-filesystem-limits.md).

User validation confirms that the oversized-file error was shown before copying began. The next compatibility priority is metadata and link fidelity across destinations: native versus synthetic permissions, ownership/ACLs/xattrs, symbolic/hard links, timestamps, explicit metadata-loss policy, and source retention after failed required preservation. These remain planned under A30, not completed transfer features.

The latest five-directory copy failed on the first SAF `mkdir` after about 60 seconds, before any file bytes were copied. Current read-only inspection reports substantial available space and a read/write mount. Added timed folder creation, retained exception causes, and mount/free-space diagnostics; the underlying provider/storage cause remains unresolved pending a system Files folder-creation comparison. See [device dump follow-up](device-dump-follow-up.md#directory-copy-failure-2139-utc).

A subsequent retry on the same installed build succeeded: the 21:47 dump records 27 directory creates, 118 verified files, and 133,151,369 destination bytes with no new errors. The earlier 52 KB stall did not recur. System Files also recovered according to the user, supporting an intermittent shared storage/provider/reader path rather than establishing a software fix or confirmed hardware fault. See the latest device follow-up for timings.

## Container destinations

The destination picker now uses CREATE capabilities and exposes archive/image folders, including containers directly in Internal shared storage. Added disk-backed ZIP rewrite/publication and streamed FAT entry writes so copy targets cover ordinary ZIPs and writable FAT image filesystems. Moves within the same ZIP or writable FAT image are staged with their destination changes and published together; moves across containers remain blocked. Multipart virtual roots, nested mounts, unsupported image/archive writers, and unsafe ZIP inputs remain restricted. See [supported targets, usage, safety limits, and device checks](container-targets.md).

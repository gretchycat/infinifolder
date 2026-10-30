# InfiniFolder

Architecture: [InfiniStream implementation design](docs/infinistream_architecture_codex.md), with [ownership and tested migration checkpoints](docs/infinistream-migration.md). Existing working features and data-safety guarantees remain requirements at every stage.

An Android file browser with interchangeable storage providers, browsable archives/disk images, and independent viewer/editor modules. Android SAF identities remain opaque; source permissions and format limits are checked through shared provider contracts.

## Current functionality

- Local shared/removable storage, user-granted SAF sources, SFTP, FTP/FTPS, SMB and WebDAV. Virtual root supports editable container links, with credentials stored separately.
- Grid/list browsing, navigation tree, breadcrumbs, temporary pinned targets, hold-to-drag files/selected sets and hover navigation. Same-device drops move; different/unknown devices copy. Two-finger rectangle selection leaves one finger available for scrolling.
- Shared nested File/Edit/View/Select/Settings menus; wildcard/regex selection; name/MIME/size/date sorting in either direction; supported new-document/archive templates.
- MIME-driven icons, application-package opening and explicit Browse for packaged containers such as APK/LOVE. Native links and .iflink shortcuts share resolution protections.
- Independent image, text, Markdown, HTML, PDF, CSV, EPUB, audio and video viewers, with editing where supported. External editing uses durable working copies, source fingerprints and explicit conflict choices.
- Capability-aware transfers, cancellation, per-file/overall progress, remembered optional copy verification, foreground execution and background completion notifications. Successful progress dialogs dismiss automatically; warnings/errors remain reviewable.
- ZIP/TAR and supported compressed-container writes use staged rebuild/publication. FAT image batches avoid committing each small file separately. Ext2/3/4 images have a guarded native writer with internal moves and ext3/4 journal support; see [scope and validation](docs/ext-image-writes.md). Other formats have explicit read-only/subset limits.
- FAT deleted-file copy-out and partial recovery with uncertainty reports; current-folder disk usage graph with animated folder navigation and live scan totals.
- Shared 200 MiB disk cache, LRU eviction before capacity prompts, separate individual-file limit and six-hour idle expiry. Unfinished editor work is protected outside disposable cache.
- Light/dark/system themes, configurable icons, previews and hidden-file display. Toolbar activity light: green reads, dim green load waits, red file work, amber verification. Tap for operation details.

See [current confirmations and remaining work](docs/project-status.md). Implemented features and handset acceptance are tracked separately.

## Build

Kotlin Android application `com.infinifolder`: minSdk 26, compileSdk 36, targetSdk 35. Install the matching Android SDK and project Gradle dependencies, then run:

```sh
bash build.sh
```

The script configures native AAPT2 on Termux, assembles the debug APK and invokes `xdg-open` after success. Output: `app/build/outputs/apk/debug/app-debug.apk`. Elsewhere use `gradle :app:assembleDebug`; on ARM Termux the native AAPT2 override may be required. Independent applications are documented in [viewer modules](docs/viewer-modules.md).

### Audio library checkout

Audio parsing and processing come from [libinfinisound](https://github.com/gretchycat/libinfinisound), pinned as a submodule. Clone with `--recurse-submodules`, or run `git submodule update --init --recursive` before building an existing checkout. See [audio library integration](docs/audio-processing-library.md).

## Diagnostics

In debug builds, choose a dump folder in Debug dump settings under Settings, or use a folder's **Use as debug dump folder** action. The toolbar insect button offloads a screenshot, log and JSON to that folder and refreshes the listing. ADB is unnecessary. Logs/screenshots can contain filenames and visible content.

## Limits and documentation

Android storage permissions still apply. Magnet links now have read-only torrent browsing and selected-file copying; live device acceptance, in-progress download feedback and seekable piece reads remain open. NFS/cloud providers are not implemented. Operation queues are process-local. Nested write chains, complete metadata fidelity and several filesystem subsets remain limited. Compressed writes rebuild; they are not in-place compressed random edits. Live VM reads are not snapshots. Cache/external-editor changes still need focused handset acceptance.

Start with the [documentation index](docs/README.md), [architecture](docs/architecture.md), [format coverage](docs/file-formats.md), and [task list](docs/feature-todo.md). Historical audit/test evidence is retained with its revision.

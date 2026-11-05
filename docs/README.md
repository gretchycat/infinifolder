# Documentation

Updated 2026-11-04. Current status, actionable work and future design have separate
homes below. Format guides document real support and limitations. Dated audits and
validation records describe their recorded revisions, not new acceptance.

## Start here

- [Project status](project-status.md)
- [Remaining work](feature-todo.md)
- [Roadmap](roadmap.md)
- [Architecture](architecture.md)
- [Runtime capability discovery audit](runtime-capabilities.md)
- [Independent viewer applications](viewer-modules.md)
- [File formats and resource types](file-formats.md)
- [Supported type inventory](supported-types-inventory.md)

## Browsing, icons and Android

- [Navigation, menus, selection and disk usage](navigation-menus-disk-usage.md)
- [Back navigation](back-navigation.md)
- [Common locations and destination suggestions](common-locations.md)
- [Container links and Virtual root](container-links.md)
- [Drag floating icons to Home screen](floating-launcher-shortcuts.md)
- [Resource artwork and sibling PNG caches](resource-icons.md)
- [Icon system repair](icon-system-repair.md)
- [Desktop icon themes](icon-themes.md)
- [Connection badges on remote icons](network-icon-badges.md)
- [Android file and URI registration](android-file-uri-registration.md)
- [Termux command links](termux-command-links.md)
- [Shared disk cache policy](disk-cache-policy.md)
- [External editing and source conflict checks](external-edit-writeback.md)

## Storage, operations and containers

- [Storage API consistency](storage-api-consistency.md)
- [Transfer metadata and filesystem integrity](metadata-integrity.md)
- [Filesystem limits and destination preflight](storage-filesystem-limits.md)
- [Operations, progress and diagnostics](operation-feedback.md)
- [Concurrent operations and activity overview](concurrent-activity.md)
- [Cancelling ordinary copies and moves](transfer-cancellation.md)
- [Copying into mounted images and ZIP containers](container-targets.md)
- [Container mutation batches](container-batching.md)
- [Container integrity and publication validation](container-integrity-validation.md)
- [TAR and compressed image mutation](compressed-container-writes.md)
- [Directory-based image browsing](image-directory-browsing.md)
- [Remote archive transfer caching](remote-archive-transfer-performance.md)
- [SFTP channel opening and reconnect indication](sftp-channel-recovery.md)
- [WebDAV provider](webdav-support.md)
- [Magnet networking diagnosis and changes](magnet-networking.md)
- [Torrent checkpoint safety checks](torrent-checkpoint-safety.md)
- [Unfinished downloads link directory](partial-download-discovery.md)

## Viewers and formats

- [Image formats](image-formats.md)
- [Image processing through Canvas](image-processing.md)
- [Image inspector and Canvas](image-inspector.md)
- [Image animation](image-animation.md)
- [IFF palette cycling](iff-color-cycling.md)
- [RIPscrip graphics](ripscrip-graphics.md)
- [Audio and sound formats](audio-formats.md)
- [Audio parsing and processing library](audio-processing-library.md)
- [Video formats](video-formats.md)
- [Text, markup, document, and text-based media formats](text-and-document-formats.md)
- [Document and ebook readers](document-and-ebook-readers.md)
- [Syntax highlighting and text appearance](syntax-highlighting.md)
- [Font preview](font-viewer.md)
- [Hex viewer and editor](hex-editor.md)
- [Ringtones and the CP437 text viewer](ringtones-and-dos-text.md)
- [Winamp skin integration](winamp-skins.md)
- [CAB support](cab-support.md)
- [RAR, ARJ, integrity and unreadable-source handling](rar-arj-and-integrity.md)
- [UFS and legacy archive support](ufs-and-legacy-compression.md)
- [NTFS image support](ntfs-support.md)
- [HFS image support](hfs-support.md)
- [Ext2, ext3 and ext4 image writes](ext-image-writes.md)
- [FAT deleted-file recovery](fat-deleted-file-recovery.md)
- [Partial FAT recovery and byte-range reports](partial-fat-recovery.md)
- [Complete disk image fixtures](partitioned-disk-fixtures.md)

## Historical evidence and future design

- [InfiniStream migration checkpoints](infinistream-migration.md)
- [Library migration completion — checkpoint 31](library-migration-completion.md)
- [Recorded storage failure investigation](storage-failure-investigation.md)
- [Code consistency audit and future task list](code-consistency-audit.md)
- [File handler stub and false-success audit](file-handler-stub-audit.md)
- [Device dump follow-up](device-dump-follow-up.md)
- [Audio decoding and signal checks (JVM fixtures)](audio-playback-authoritative-report.md)
- [Infinifolder Library Extraction and Stream Architecture](infinistream_architecture_codex.md)
- [InfiniFolder — Android Content Integration and Universal Format Translation](infinifolder_android_content_integration.md)

## Libraries and validation

Each source repository documents its public capability API, registration mechanism,
linked dependencies, runtime limits and independent build commands:

- [Stream contracts and processors](../libraries/libinfinistream/CAPABILITIES.md)
- [Store resources/protocols](../libraries/libinfinistore/CAPABILITIES.md)
- [Vault containers/filesystems](../libraries/libinfinivault/CAPABILITIES.md)
- [Canvas images/animation](../libraries/libinfinicanvas/CAPABILITIES.md)
- [Scribe documents/fonts/reparsing](../libraries/libinfiniscribe/CAPABILITIES.md)
- [Sound audio](../libraries/libinfinisound/CAPABILITIES.md)
- [Video playback](../libraries/libinfinivideo/CAPABILITIES.md)
- [Runtime gate record](validation/runtime-capabilities-2026-11-04.json)

## Fixtures and decisions

- [Test images, generators and expected contents](../filesystem-test-images/README.md)
- [Native SAF first](decisions/0001-native-saf-first.md)
- [Optional direct shared-storage access](decisions/0002-direct-shared-storage.md)
- [Application public represents exported SAF sources](decisions/0003-app-document-sources.md)

Superseded summary/progress pages were consolidated into the current architecture,
status, backlog and operations guides. Detailed audit/log evidence, provenance and
license notices remain; earlier summary revisions are available in Git history.

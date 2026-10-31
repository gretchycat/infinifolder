# Documentation

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-27. Start with [project status](project-status.md), [task list](feature-todo.md), [roadmap](roadmap.md) and [architecture](architecture.md).

## Reference and evidence

- [Authoritative InfiniStream architecture and implementation assignment](infinistream_architecture_codex.md)
- [Migration ownership, checkpoints and regression baseline](infinistream-migration.md)

- [Architecture](architecture.md)
- [Back navigation](back-navigation.md)
- [CAB support](cab-support.md)
- [Code consistency audit and future task list](code-consistency-audit.md)
- [TAR and compressed image mutation](compressed-container-writes.md)
- [Termux command links](termux-command-links.md)
- [Container mutation batches](container-batching.md)
- [Container integrity and publication validation](container-integrity-validation.md)
- [Container links and Virtual root](container-links.md)
- [Common locations and destination suggestions](common-locations.md)
- [Copying into mounted images and ZIP containers](container-targets.md)
- [Device dump follow-up](device-dump-follow-up.md)
- [Shared disk cache policy](disk-cache-policy.md)
- [External editing and source conflict checks](external-edit-writeback.md)
- [Ext2/3/4 image writes and journals](ext-image-writes.md)
- [Transfer metadata and filesystem integrity](metadata-integrity.md)
- [FAT deleted-file recovery](fat-deleted-file-recovery.md)
- [Feature to-do list](feature-todo.md)
- [File formats and resource types](file-formats.md)
- [Image formats](image-formats.md)
- [Audio and sound formats](audio-formats.md)
- [Audio parsing and processing library](audio-processing-library.md)
- [Library separation audit](library-separation-audit.md)
- [Video formats](video-formats.md)
- [Text, markup, document, and text-based media formats](text-and-document-formats.md)
- [HFS image support](hfs-support.md)
- [Directory-based image browsing](image-directory-browsing.md)
- [Implementation status and open issues](implementation-status-open-issues.md)
- [Navigation, menus, selection and disk usage](navigation-menus-disk-usage.md)
- [NTFS image support](ntfs-support.md)
- [File operation feedback](operation-feedback.md)
- [Operation dialog progress](operation-progress.md)
- [Partial FAT recovery and byte-range reports](partial-fat-recovery.md)
- [Complete disk image fixtures](partitioned-disk-fixtures.md)
- [Project status](project-status.md)
- [RAR, ARJ, integrity and unreadable-source handling](rar-arj-and-integrity.md)
- [Roadmap](roadmap.md)
- [Syntax highlighting and text appearance](syntax-highlighting.md)
- [Filesystem limits and destination preflight](storage-filesystem-limits.md)
- [Storage safety and architecture progress](storage-safety-progress.md)
- [Storage API consistency and remaining tasks](storage-api-consistency.md)
- [Transfer verification and completion feedback](transfer-verification-feedback.md)
- [Torrent checkpoint corruption and failure checks](torrent-checkpoint-safety.md)
- [UFS and legacy archive support](ufs-and-legacy-compression.md)
- [UI responsiveness investigation](ui-responsiveness.md)
- [Independent viewer applications](viewer-modules.md)

## Fixtures and decisions

- [Test images, archives, generators and expected contents](../filesystem-test-images/README.md)
- [Decision 0001: native SAF first](decisions/0001-native-saf-first.md)
- [Decision 0002: optional direct access to primary shared storage](decisions/0002-direct-shared-storage.md)
- [Decision 0003: Application public represents app-exported SAF sources](decisions/0003-app-document-sources.md)

Current status separates implemented, user-confirmed, acceptance-pending and deferred work. Original audits, decision records and dump timelines retain their historical evidence.

- [WebDAV provider scope and remaining server checks](webdav-support.md)

- [Winamp skin integration](winamp-skins.md): import behavior, classic bitmap chrome, fixture findings and compatibility limits.
- [Resource icons](resource-icons.md): cached-first artwork, application icon refresh and per-file `.icon-<full filename>.png` images.
- [Private partial-download collection](partial-download-discovery.md): persisted private links, passive discovery, source permissions and resume behavior.

- [Concurrent operations and Activity overview](concurrent-activity.md): scheduling,
  conflict waits, transfer overview/detail navigation, and pending device checks.

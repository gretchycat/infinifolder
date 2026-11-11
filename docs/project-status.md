# Project status

Updated 2026-11-11. The suite now has four independent Android application
repositories and eleven reusable library repositories. Source locks fetch only
each application's declared library closure. Folder provides advanced storage
through Android document/stream interfaces; Scribe and Media use Select/SAF
without including Store/Vault. Key has no suite library dependency.

Current repository inventory, exact commits, APK sizes and executed test results
are in the [separation audit](architecture-audit.md). Use the
[Android build guide](android-builds.md) and [dependency graph](architecture.md).
The [task list](feature-todo.md) and [roadmap](roadmap.md) record future plans,
not additional tasks in this audit;
older migration totals are revision-specific evidence, not the current test count.

Ubuntu host-native test work and other-platform ports are deferred at the owner's
request. No platform port was installed locally. Cross-app Android permission,
provider lifecycle, playback, rendering and casting acceptance still requires
device validation; compilation alone does not establish these behaviors.

## Implemented foundations

| Area | Current implementation | Reference |
| --- | --- | --- |
| Libraries | Eleven pinned, standalone library repositories; handler discovery, dependency availability and bounded probes; typed services and existing stream pipelines | [Architecture](architecture.md), [runtime discovery](runtime-capabilities.md) |
| Storage | Local, SAF, SFTP, FTP/FTPS, SMB, WebDAV, application catalogs and torrent sources; actual resource capabilities govern operations | [Storage contracts](storage-api-consistency.md) |
| Containers | Supported archives, partitions and filesystem images; guarded ZIP/TAR/compressed/FAT/ext writes; nested write chains remain limited | [Format coverage](file-formats.md), [batches](container-batching.md) |
| Transfers | Shared preflight, verification, cancellation/undo, source checks, recovery and operation history; ordinary active queues are process-local | [Cancellation](transfer-cancellation.md), [Activity](concurrent-activity.md) |
| Viewers | Folder image/hex, Scribe documents/ebooks/fonts/mind maps, Media audio/video; independent application roots | [Viewer modules](viewer-modules.md) |
| Presentation | Shared MIME/URI icon policies, imported/bundled themes, content thumbnails, covers and overlays; persistent links/pins and floating browser | [Icons](resource-icons.md), [themes](icon-themes.md), [shortcuts](floating-launcher-shortcuts.md) |
| Editing/cache | Complete-document save guards, sampled-image PNG export, revision checks, durable external working copies and protected cache leases | [External edits](external-edit-writeback.md), [cache](disk-cache-policy.md) |

## Recorded handset confirmations

These are owner reports for their respective revisions, not proof that every
variant or the latest runtime-discovery change has been exercised on a device.

| Workflow | Confirmed scope |
| --- | --- |
| Browser | Two-finger selection, pane switching, tree drops/autoscroll, Virtual root links, loading-light behavior, notifications and automatic successful-dialog dismissal |
| File icons | Themed tree improvement, connection/disconnection badges including link targets, hidden-file badges, special folder icons and GEN binary identity |
| Cache/external editing | Core LRU/active-file protection/settings persistence; real-editor handoff, conflicts, preserved edits/Save As and unfinished-session recovery |
| Opening | Normal file taps use the internal viewer after the default-route correction; broader chooser/provider cases remain separate |
| Transfers | Current-directory copying and the warning-suppression checkbox; Copy, Cancel and restricted-destination failures; cancelled SAF move cleanup before originals were removed; earlier ZIP/FAT16 behavior and native FAT32 file moves |
| Recovery/formats | General delivered FAT recovery fixtures and partial recovery; reported RAR/ARJ/CAB, UFS, NTFS and HFS-family browsing; individual subsets remain documented |
| Audio | ANSI music and the softer waveform, local MP3 seeking/gapless and playable playlist lengths were confirmed |
| Documents/fonts | EPUB and the first Office/ebook reading pass; font display, rotation, navigation/keyboard insets |
| Launcher | Dragging folders, regular files, archives, remote locations and public application directories; direct URL handoff was subsequently repaired |
| Animations | IFF color cycling and the earlier autoplay/loop/progress repair; later FLC header recovery and interactive MNG remain separate checks |
| Network/torrents | Reported FTP/SMB/WebDAV use, torrent downloads/resume/retry and simultaneous transfers; source/provider-specific failure coverage is incomplete |

## Highest-priority acceptance

1. Device-check runtime-discovered viewer/editor/icon routing and long-press actions,
   standalone selectors, theme changes, defaults and content URI grants.
2. Check the FLC alias correction, recovered headers, MNG interactive states,
   animation lifecycle, Back/theme rebuilds and directory scroll/breadcrumb restoration.
3. Check continuous Cast transitions, seeks, repeat and failure fallback on a real
   receiver; extend local playback acceptance to high-resolution and remote inputs.
4. Recheck slow SFTP/archive small-file transfers, destination-listing recovery and
   move restoration after originals have been removed.
5. Extend cache, external-edit and container publication checks to interruption,
   low space, unknown lengths, concurrent writers and the remaining providers.

ADB had no connected device during the runtime-discovery gate. The latest native
surface/playback, Android UI and live-provider paths therefore remain unverified.
Detailed matrices live with the relevant feature, not in duplicate status diaries.

## Remaining engineering boundaries

- Legacy handlers retain documented conditional parser/native support. More
  negotiating stream adapters and arbitrary multi-input graph planning remain.
- Complete mounted capability/cache/lifetime consistency, case/link semantics,
  metadata fidelity and stronger publication guarantees across providers.
- Ordinary active transfer recovery after process death and general stream
  pause/resume are not implemented; terminal history and torrent sessions persist.
- Large text/CSV editing, broader format fidelity, seekable torrent pieces,
  NFS/cloud providers, a launcher widget and universal Android virtual-document
  export remain future work. See [priorities](feature-todo.md).
- Original-code licensing, source-buildable third-party/native dependencies and
  release/ABI verification remain prerequisites for distributable library releases.

## Historical evidence

[Migration checkpoints](infinistream-migration.md), the
[migration closeout](library-migration-completion.md), the
[storage failure investigation](storage-failure-investigation.md) and versioned
[validation records](validation/runtime-capabilities-2026-11-04.json) retain dated
results. Superseded audit/status diaries remain available in Git history.

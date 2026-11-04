# Project status

Updated 2026-11-04. This page summarizes implemented behavior and known acceptance.
The [task list](feature-todo.md) owns outstanding work; the [roadmap](roadmap.md)
explains future directions. Format guides retain their specific limits.

## Latest engineering evidence

The runtime-discovery gate at `f0781a9` passed **1,071 tests**, with no failures,
errors or skips. All seven libraries built independently; the main app and combined
standalone viewer APKs built. The baseline comparison found no regressions or
missing cases. Both APKs packaged 29 providers and the exported catalog contained
65 handlers. See [commands, pins and limits](runtime-capabilities.md) and the
[machine-readable record](validation/runtime-capabilities-2026-11-04.json).

The subsequent FLC routing correction expands Canvas-owned MIME aliases and uses
them for animation routing. It does not change the FLIC decoder or establish new
handset acceptance. `gradle :app:assembleDebug --offline --console=plain` passed
after the correction; no new automated test run was performed for this quick fix. See [animation support](image-animation.md).

The Open-with follow-up restores **Open internally** as the first supported choice,
allows remembering it for a file/type and keeps explicit external preferences.
Choosing an internal viewer avoids external staging. Same-folder Copy to/Paste uses
the existing unused-name transfer handling with confirmation; Move restrictions stay.
Both APKs build; these new chooser/copy interactions remain device-unverified.
A follow-up also removes the same-parent drag no-op and the legacy global viewer
switch that could still bypass internal defaults. File/type choices control external
opening; current-directory drops use confirmed copy/unused-name handling.

Documentation cleanup does not constitute a new implementation test run. Older
checkpoint totals are historical; they are not the current failure count.

## Implemented foundations

| Area | Current implementation | Reference |
| --- | --- | --- |
| Libraries | Seven pinned, standalone repositories; handler discovery, dependency availability and bounded probes; typed services and existing stream pipelines | [Architecture](architecture.md), [runtime discovery](runtime-capabilities.md) |
| Storage | Local, SAF, SFTP, FTP/FTPS, SMB, WebDAV, application catalogs and torrent sources; actual resource capabilities govern operations | [Storage contracts](storage-api-consistency.md) |
| Containers | Supported archives, partitions and filesystem images; guarded ZIP/TAR/compressed/FAT/ext writes; nested write chains remain limited | [Format coverage](file-formats.md), [batches](container-batching.md) |
| Transfers | Shared preflight, verification, cancellation/undo, source checks, recovery and operation history; ordinary active queues are process-local | [Cancellation](transfer-cancellation.md), [Activity](concurrent-activity.md) |
| Viewers | Eleven modules: image, text, Markdown, HTML, PDF, CSV, EPUB/document, font, hex/raw-media, audio and video; all or selected standalone host | [Viewer modules](viewer-modules.md) |
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
| Transfers | Copy, Cancel and restricted-destination failures; cancelled SAF move cleanup before originals were removed; earlier ZIP/FAT16 behavior and native FAT32 file moves |
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

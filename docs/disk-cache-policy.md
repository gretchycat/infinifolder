# Shared disk cache policy

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

## Implemented

- Default total reusable disk-cache budget: **200 MiB** (shown as MB in the interface), shared by the browser and viewer modules within the application.
- Separate, remembered individual-file limit. Default `0` means no additional individual restriction; a fully staged file must still fit the aggregate budget.
- **Settings → Disk cache** shows both limits and total/reserved usage. A reduced budget preserves active leases and evicts idle entries; active usage can temporarily exceed the newly reduced budget until released.
- Evict idle entries in least-recently-used order **before** offering any capacity choice. Known sizes reserve room before reading; unknown/expanding streams reserve additional bytes before each write. Parallel staging uses the same locked accounting.
- If eviction cannot make a file fit, offer **Copy to folder**, **Raise cache limits**, or Cancel. A file larger than the entire budget still cannot fit even after all idle entries are evicted. Files in use remain protected.
- Capacity/settings dialogs use the active light/dark mode and application palette, including custom colors, and update while open if the theme changes.
- Raising limits saves them; reopen the source to retry. Copy in the full application opens the normal destination picker and transfer queue, preserving progress, verification policy and background notifications. Standalone viewer hosts have an independent SAF folder-copy fallback with progress/cancellation.

## Covered paths and lifetime

- Shared manager: `storage-engine/.../storage/DiskCache.kt`.
- Internal PDF/EPUB/audio/video staging, expanded EPUB resources, external-edit preparation, clipboard image staging, extracted archive members, decompressed image snapshots, stream-only archive materialization, multipart volume staging, and optional disk-cached application icons use the shared budget.
- Viewer sources and expanded EPUB resources release their leases when closed and remain available for LRU eviction. Stable source revisions can reuse cached media/decompressed images. Unknown revisions are not reused by URI alone.
- Cached snapshots are immutable; provider writes, atomic replacements and other mutations to managed snapshots are refused. Save edits to the original resource or an explicit storage copy. Unsaved editor content/recovery copies never belong to this disposable pool.
- File-returning nested/archive consumers and read-only exports without a close signal require conservative leases; editable external opens instead use durable sessions. Those exported/nested source files are conservatively retained for the process lifetime; they can occupy the budget and cause the copy/increase-limit choice. Provider/device and viewer reads also pin managed files for their read lifetime.
- LRU timestamps and completion markers survive restart. Incomplete managed staging is discarded on the next process initialization. Known old internal media/network/icon read caches and decompressed image caches are adopted without duplicating their data. Historical external extraction directories are not automatically deleted: their contents may have been edited under older versions and lack reliable ownership/dirty metadata.

## Hex source and write fallback

Hex viewing never stages the entire source. Native offset reads are preferred;
sequential sources retain only Scribe's 128 KiB memory window and cannot scroll
back past evicted bytes. Native saves merge durable byte differences with offset
writes. Only unsupported random writes (or guarded replacement of an existing
export) use a normal shared-cache lease for the full replacement. Existing cache
limits, LRU eviction and Copy/Raise/Cancel dialogs apply without a second policy.
See [hex editing and saves](hex-editor.md#editing-and-saves).

## Boundaries

This is a **disposable disk-data cache** budget, not a maximum source-file size. Raw seekable network images keep their existing bounded range reads and are not rejected just because their logical source size exceeds 200 MB. Compressed images needing full materialization are limited by their expanded cached size. The former production 512 MB remote-image/500 MB viewer-staging thresholds are replaced by the shared policy.

Transfer/container transaction working files, native integrity-verification scratch, imported themes, diagnostics, editor recovery and user-selected output copies are separate storage categories. They are not evicted to make room for cache data. They continue using their existing cleanup, source-safety and free-space checks. In-memory thumbnail/range/catalog caches retain their own memory limits.

## Validation

APK compilation completed successfully and build.sh opened it with xdg-open. No automated tests were added or run for this change.

On 2026-11-01 the user confirmed LRU eviction under cache pressure, active-file protection, the capacity choices, and persistence of saved settings after restart. Device checks remain for six-hour idle expiry, incomplete-staging cleanup after restart, simultaneous active viewers/mounts, unknown-size streams, expanded EPUB accounting and custom/light/dark dialog appearance. The old unit expectation of a fixed 512 MB remote-image rejection is historical and needs updating when tests are next requested.

## Six-hour idle expiry

Default expiry is **6 hours since last use**, separately configurable under Settings → Disk cache (0 disables time-based expiry). Idle entries expire during startup, cache allocation/reuse and a 15-minute sweep while the process is alive. An app that is not running does not wake just to clean cache; startup performs cleanup. Releasing an active lease marks its last-use time. Active leases never expire.

External editing now uses a dedicated durable working-copy/session path rather than editing immutable cached snapshots. These unfinished copies do not expire or participate in LRU eviction; see [external edit checks](external-edit-writeback.md).

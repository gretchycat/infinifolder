# Resource artwork and sibling PNG caches

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

## Exact filename and location

Generated program icons are saved **in the same directory as the program file or application link** as `.icon-<full filename>.png`. Manually supplied artwork can use the same naming convention for any resource:

- `Editor.iflink` → `.icon-Editor.iflink.png`
- `report.pdf` → `.icon-report.pdf.png`
- directory `Projects` → sibling `.icon-Projects.png`
- archive or disk image `backup.zip` → sibling `.icon-backup.zip.png`

Matching includes extensions and exact case. These are distinct from `.icon.<ext>`, `.icon`, `cover.<ext>`, and `cover` inside a directory or archive/disk-image root, which supply the container's own artwork when no sibling `.icon-<container name>.png` override is present. Hidden-file visibility applies normally (including `listHiddenFiles = true` on FTP connections so dotfile icons are returned). Icon PNGs never generate further cache PNGs for themselves.

The earlier implementation wrote generated PNGs into private managed-cache UUID directories. That was not the intended location. The current resource-artwork cache no longer uses that private image index or creates those private PNGs. Previously created private entries can expire through the existing managed-cache policy.

## Display and retrieval order

For visible main/floating cards:

1. Apply an in-memory image immediately if available.
2. Read the exact sibling PNG found in the ordinary directory listing and apply it before scheduling source retrieval.
3. Retrieve authoritative artwork if needed; installed-app icons use separate source workers.
4. Apply the resulting image, then queue sibling PNG persistence on a separate worker.

There are no additional parent-directory scans or alternate cache-image searches. Ordinary directory listings populate `ResourceIconSidecars` for all local, remote (`sftp://`, `smb://`, `ftp://`, `webdav://`), and archive/disk-image (`archive://`) containers. When a standalone resource, tree node, pinned container, or shortcut target is resolved before its parent directory listing has been observed, `ResourceArtworkCache.sidecar` performs a single exact-name sibling lookup without enumerating the parent directory (direct file check on local storage, single-target `getMetadata` on SFTP/FTP, Depth-0 `stat` on WebDAV/SMB, and entry `stat` or outer-container source lookup on `archive://`). Background writes use the already observed parent URI, falling back to the provider's parent operation when needed.

## Preservation, refresh and source limitations

Existing custom PNGs are preserved. Private preferences contain only provenance (source URI, PNG SHA-256, source signature and acquisition time), not another image location. This identifies generated images eligible for periodic refresh; if a PNG's bytes no longer match the recorded hash it is treated as custom artwork. Copied PNGs without matching provenance also act as custom artwork.

Generated artwork can be refreshed on use after one hour or a source metadata/icon-theme change. Generated PNG replacement uses `provider.replaceContent` (including `ArchiveStorageProvider.replaceContent`) with fallback to `ContentReplacement.replace` for providers that replace via safe staging; unsupported replacement preserves the existing PNG. New files use the provider's exclusive creation contract (or `provider.beginWriteBatch` for single-pass archive/disk-image container commits), and unexpected renamed results or failed partial creations are cleaned up. A smaller floating-header image cannot replace a larger cached grid image with the same source signature.

Writable physical/provider directories can receive sibling files. Read-only locations cannot. The virtual Installed applications directory has no physical sibling directory and does not write PNGs into application installation directories; real application-link files receive their PNGs alongside those links. Cache-write failures do not prevent opening or displaying a resource.

Sibling PNGs are persistent files on the selected source, not entries managed by the application's disposable private-cache budget/expiry. In-process artwork has an 8 MiB LRU. Automatically refreshing artwork never modifies the original resource itself.

## Diagnostics and validation

- `artwork.cache.saved`: exact generated filename, byte count and provider.
- `artwork.cache.read-failed` / `artwork.cache.write-failed`: failed reads/writes, including the cause.
- `artwork.cache.custom-preserved`: existing custom artwork was retained.
- `artwork.cache.refresh-unsupported`: backend cannot replace generated PNG safely.
- `artwork.cache.write-deferred`: bounded persistence queue was full; a later display can retry.
- `artwork.cache.cleanup-failed`: partial newly created PNG could not be removed.
- `artwork.resolve.slow`: source retrieval took at least 500 ms.

APK compilation validates integration. Device confirmation of sibling creation, cold-start reuse, custom-image preservation and remote writes is pending.

## Program-only generation

Automatic sibling PNG creation is restricted to resolved program artwork: installed application targets, links resolving to applications, and APKs when an actual package icon is obtained. Ordinary images, documents, directories, and generic MIME/theme artwork do not generate cache files. Any entry can still read a manually supplied sibling PNG. Existing non-program PNGs are not deleted automatically.

### Cold PNG load correction

Sibling PNG sampling explicitly starts at `inSampleSize=1`. Android's default zero caused division-by-zero in the sampling loop, so cold file reads failed while memory hits worked. `artwork.cache.loaded` now records successful PNG-to-memory loads for device confirmation.

### Visible-first loading and memory sizing

The 04:41 dump confirms successful PNG reads but shows alphabetical loading across the directory while the viewport is near its end. Probe scheduling now requires nonzero measured card/viewport dimensions and intersects their actual global visible rectangles, with no offscreen prefetch buffer. Cached PNGs are scaled to their requested display dimensions before entering the 8 MiB memory LRU. SHA-256 hex encoding avoids per-byte Formatter allocation. A separate render generation discards obsolete icon work after selection/sorting/layout rerenders, not only directory navigation.

`artwork.visible-probes` reports started/deferred work and viewport scroll position. `artwork.cache.loaded` includes file-load/decode timing and final bitmap dimensions. Device confirmation of visible-first cold loading remains pending.

### Local provider read overhead

The 04:45 dumps show visible-only scheduling and successful PNG reads, with individual cache loads still taking tens of milliseconds. Ordinary local source reads previously entered a private-cache pin search that canonicalized all managed entry directories and also rediscovered app-internal storage roots for each open. External source files now bypass private-cache initialization/pin locking, managed lookups reject unrelated paths immediately, and app-internal roots are reused. No storage permission checks are bypassed; paths remain canonical and root comparisons honor path boundaries.

Queued main-card requests recheck an immutable snapshot of the actual viewport and defer offscreen work until it becomes visible again. `artwork.cache.display-slow` separates queue, read and UI-publication delays. `artwork.cache.loaded` separates file reading, bitmap decoding and provenance checks.

## Remote and archive container parity (2026-10-27, user-confirmed)

- **Directory and container artwork**: Remote directories (`sftp://`, `smb://`, `ftp://`, `webdav://`) and browsable archives/disk images (`archive://` and container files) resolve inner `.icon.<ext>`, `.icon`, `cover.<ext>`, and `cover` images across the main grid/list, Virtual Root links, `.iflnk` shortcuts, tree sidebar, pinned containers bar, properties dialog header, floating container overlay, and launcher shortcuts.
- **Single-pass stream decoding**: `ThumbnailDecoder` reads non-direct provider streams (`sftp://`, `smb://`, `ftp://`, `webdav://`, `archive://`) into memory once (bounded to 32 MiB) and extracts archive entry extensions from the `path` query parameter so stream-backed icons and SVG/raster artwork decode without double-opening or mid-stream closes.
- **Arbitrary overrides and sibling PNG cache in remote/archive containers**: Any file, directory, or archive inside local, remote, or archive/disk-image containers honors a sibling `.icon-<full filename>.png` override. Program artwork (`.iflnk` app shortcuts and `.apk` files, including remote/archived APKs staged temporarily for `PackageManager` icon extraction) automatically persists and refreshes `.icon-<full filename>.png` sidecars in writable remote directories and writable archives/disk images while preserving custom user-supplied sidecars.
- **Status**: User-confirmed working on the handset (2026-10-27).

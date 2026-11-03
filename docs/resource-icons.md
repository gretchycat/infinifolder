# Resource artwork and sibling PNG caches

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

## Application public

The Application public section uses the selected theme's application-place icon.
Its app-provided storage roots use the owning application's icon, resolved from
the DocumentsProvider package metadata rather than guessed from the authority.
The shared resolver supplies these icons to list/grid cards and tree entries,
including locations waiting for a folder grant; their access badge remains visible.
App artwork uses the existing app-icon memory/disk cache and is loaded on workers.
Unavailable package artwork falls back to the themed application-place icon.

## Audio file icons

After any explicit per-file icon, the shared listing resolver tries embedded
artwork from Sound's metadata reader, then valid `cover.*` (or extensionless
`cover`) images in the track's parent directory, then its effective MIME icon.
Audio cover fallback does not use `.icon.*` directory customizations. Covers use
the existing storage abstraction, supported image decoders, stable format ordering
and backend case-sensitivity rules; a corrupt candidate falls through to the next.

Metadata reads are asynchronous, bounded to 16 MiB of requested source data and
1.5 seconds, with encoded artwork capped at 8 MiB. They do not open playback,
calculate duration through fallback decoders, or extract compressed archive songs.
Embedded artwork depends on the existing platform metadata reader and a seekable
source; unsupported tags or sources fall through to the cover/MIME icon.

Decoded artwork and missing-art results are cached in memory. Decoded covers are
reused across tracks for each size/modification state; successful artwork also uses the shared
disposable disk-cache budget, with asynchronous PNG writes. Cover dependencies in
the listing cache change when cover metadata changes, and partial directory
enumerations retain the existing retry behavior.

Generated audio icons carry a musical-note symbol and format labels, including
MP3/MP2/MP1 rather than MPEG, and M4A/M4B/M4R rather than MP4. MIME associations
remain canonical; formats sharing a MIME retain distinct labels. Theme lookup
also tries extension-specific logical names (`audio-mp3`, `audio-x-mp3`, etc.)
before the default MIME artwork, preserving explicit MIME icon customizations
as the fallback when no album artwork is available. Existing registry records
and user overrides are retained.

## Default fallback style

Unlisted or missing MIME artwork now uses the same base SVG set as the bundled
default theme, with a type label on a colored plaque. The old separately drawn
paper icon has been removed. The build compiles `base-fallback-*.svg` and the
existing artwork metadata from `themes/default theme` into the app. Runtime
fallbacks choose the associated base only when the extension agrees with the
resolved MIME; otherwise they use MIME/category artwork, then the binary base.
This selects artwork and does not change identity, associations or capabilities.
Unknown binary extensions still receive their own label; extensionless sources
use a MIME label or FILE. Shared MIME audio formats retain their useful labels.

`themes/icon/scripts/icon_style.py` defines the deterministic label-color palette
used both by generated SVGs and the compiled runtime style. Generated file/URI
labels are uppercase and no longer stretch short strings across the whole plaque.
Folder variants retain only their pictogram stamps. Generic folder and archive
fallbacks also use the corresponding new SVG bases. The Android app drawer icon
remains its distinctive existing artwork.

Base SVGs are recorded once per category in memory and reused across sizes;
completed icons retain the existing bounded bitmap cache. There are no filesystem
or network reads to draw a fallback. Specific theme icons, per-file/MIME overrides
and requested content thumbnails retain their normal priority. Default-theme
revision keys and the listing artwork cache version invalidate old renders.

Regenerated all 675 icons and the importable ZIP. Debug build command:
`gradle :app:assembleDebug --offline --console=plain`. Logs:
`build/unified-icon-style/app-build-final.log`. No tests were added or run; real-device
appearance and navigation/theme acceptance remain unverified.

## Ebook icons

The shared resolver uses embedded EPUB, MOBI/KF8 and FB2 cover images, and PDF
first pages as content icons. The new MOBI/FB2 paths use the corresponding Scribe
readers; see [document/ebook support and pending acceptance](document-and-ebook-readers.md).
No cover is fabricated for a book without usable artwork. Explicit per-file artwork stays first, and failed or
unsupported covers fall back through the normal MIME override/theme/type icons.
Mounted book roots resolve their backing file's artwork too, after any explicit
inner directory cover. List/grid/tree/pins/links use the shared resolver.

Scribe's `EpubCovers.candidates` reads the package metadata without chapters or
whole-book expansion. It supports the [EPUB 3 cover-image declaration](https://www.w3.org/TR/epub-33/#sec-cover-image),
legacy EPUB 2 cover metadata, and [guide cover references](https://idpf.org/epub/20/spec/OPF_2.0_final_spec.html#Section2.6)
to images or XHTML/SVG image elements. Package-relative references use the existing
normalized, confined path resolver and bounded XML reader. Vault supplies package
members through the storage abstraction; Canvas supplies the image decoder.
The EPUB viewer's chapter rendering is unchanged; the same reader controls now host the additional reflow formats.

Scribe's existing Android PDF backend renders page zero at icon size. Real local
PDFs are opened directly; provider PDFs are staged through the shared disk cache
only up to 16 MiB. Larger provider PDFs retain their MIME icon and remain readable
in the full viewer. Password-protected, corrupt or unsupported documents fall
back normally. PDF content icons represent the first page, which may be a title
page rather than an embedded cover image.

The one-time `bookCoverIconsEnabled` registry migration sets EPUB/PDF `isImage=true`
and `overrideImageIcon=false`, preserving other fields and associations. Later
MIME-editor changes remain authoritative: selecting **Use MIME icon instead of
image** restores theme/user artwork. Successful covers are stored as small PNGs
in the shared disposable disk cache and reused across restarts, keyed by source
identity, name, MIME, size, modification state and requested icon size. Missing
artwork is cached briefly in memory; unknown modification states expire quickly.
New MOBI/FB2 records default to content icons without resetting explicit persisted
image-mode choices. `book-cover-v2` separates the expanded decoder cache. Local
MOBI/PDF inputs can be opened directly; provider book staging is capped at 16 MiB.
Book parsing/rendering runs off the UI thread in a bounded provider lane (3 s);
XML has byte/event/depth limits, and resource reads/staging share a 16 MiB decoded
byte budget. Underlying archive catalog/network access also uses the existing
provider implementation; a stalled provider cannot block the listing thread.

`gradle :app:assembleDebug --offline --console=plain` passes. No tests were added
or run. Existing EPUB fixtures were inventoried and contain no declared covers;
handset cover display, remote provider behavior, editor toggles and link/tree
acceptance remain unverified. Logs: `build/book-cover-icons/app-build-final.log`.

The current default theme has 774 source SVGs after adding the document and
source-code associations. Its MIME/extension labels use the same existing theme
generation path.

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

## Pinned links and embedded artwork (2026-11-02 repair)

Pins use `ResourceIconResolver`, including embedded link images, URI registry rules,
theme icons and directory covers. Their icon slots use their actual pixel size,
with asynchronous work independent of the scrolling file list. Theme/registry and
artwork notifications refresh the pin bar. A link's display-name override is used
for its pin label; a missing override falls back to its filename without the link
extension. Resolved labels are saved with existing pins; old preferences load
without a migration reset. Taps, menu-open and hover jumps use container navigation
with an absolute target route rather than appending unrelated visited folders.

In **Link properties → Embedded link icon**, **Choose image…** imports an image
and **Remove image** restores automatic target artwork. A pinned link's long-press
menu includes **Link properties**. Creating a link also supports choosing an image
by tapping its icon. These operations store a Base64-encoded PNG in the existing
optional `iconBase64` JSON field, so copying the `.iflink` carries its image without
a separate image path or permission grant. PNG imports are bounded to 256 pixels
independent of display density; the complete link must fit the existing 1 MiB
reader limit. Image updates use the shared verified replacement API with the
original content revision, retaining the original on a failed replacement.

The properties preview and pin/list/tree caches refresh after replacement or
removal. Saving other edited link fields retains the selected embedded image.
Malformed/absent images fall through to normal target/URI/theme artwork. Existing
link JSON and icon precedence remain compatible; no new link-file format exists.

## Tree artwork lifetime repair — 2026-11-02

Tree rows previously started with a platform emoji and applied their asynchronous
icon only if the file listing's `loadGeneration` still matched. Navigation can
advance that counter while retaining the same tree row, permanently discarding
the selected-theme result. Rows awaiting storage permission skipped theme lookup
entirely. These are code-path findings; the 00:06 UTC dump screenshot shows the
main file grid, not the reported tree regression.

`MainActivity.bindTreeIcon` now gives each row an independent request token,
detachment cancellation and a theme-revision check. File-list navigation cannot
invalidate a surviving tree row. It first uses the shared resolver's valid memory
cache or cached theme artwork, then resolves a theme folder/application baseline
on the cache executor before probing any source. Authoritative artwork cannot be
overwritten by a delayed baseline. Source resolution stays in the existing shared
resolver and its directory/program/source executors; cover precedence is unchanged.
An interrupted/failed resolver's generic fallback cannot replace the theme baseline.
Rows awaiting access receive theme artwork without enumerating that directory.
The access/create emblems retain their existing separate overlays.

A cold selected theme reserves its slot until the baseline worker supplies real
artwork. Decode failure has the built-in fallback; successful cached artwork is
reused immediately. Detached rows and results from a former theme cannot update
the visible tree. No new registry or persisted icon settings are introduced.

Validation: debug APK build and whitespace checks; no new automated tests added
or run. The owner subsequently confirms the tree-icon follow-up is better and
working (2026-11-02). This accepts the reported improvement; rapid navigation,
expansion/collapse, picker trees, missing grants, covers/provider icons and theme
switching were not individually confirmed. Build evidence is in `build/tree-icon-repair/`.

Final command `gradle :app:assembleDebug --offline --console=plain` succeeded in
2m 13s (410 tasks, 4 executed); the earlier build also passed in 2m 34s. Final log:
`build/tree-icon-repair/app-build-final.log`. No device is connected through ADB.

## Connection state emblems

Remote icons and `.iflink` destinations share per-connection badges: blinking red
while reconnecting and a persistent unplugged symbol after an observed transport
failure. These decorate the primary icon across list/grid, tree, Virtual Root,
pins and floating containers. See [state, targets and device checks](network-icon-badges.md).

Validation: `gradle :app:assembleDebug --offline --console=plain`; logs under
`build/pinned-link-repair/`. Handset display, picker return, copy/reopen, clear,
restart and breadcrumb/hover checks were not performed by the agent: ADB has no
connected device. The owner subsequently reports the current follow-up working
(2026-11-02); copy/reopen, restart and backend/hover variants were not individually
confirmed. The previous 942-test gate predates this follow-up and is not new
evidence for its UI behavior.

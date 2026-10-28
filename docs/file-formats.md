# File formats and resource types

## Implemented

- Android SAF documents and directories. The provider supplies MIME type, display name, optional size, and optional modification time. MIME types drive Android `ACTION_VIEW` dispatch.
- Archives/packages browse through shared container providers: ZIP and ZIP packages (APK/JAR/LOVE and registered aliases), TAR/compression wrappers, 7z, CPIO, ar/deb, RAR/ARJ and CAB. Read/write and multipart support vary by format; see the format documents. Files extracted for internal use follow the [shared cache policy](disk-cache-policy.md); external editing uses [durable working copies](external-edit-writeback.md), not an unbounded shared-storage extraction directory.
- Filesystem/image support includes FAT12/16/32 writes, a journal-aware ext2/3/4 write subset, read-only exFAT/UFS/NTFS/HFS-family subsets, partition tables, supported virtual-disk wrappers and linear single-PV LVM. See [ext writes and limits](ext-image-writes.md). General browsing confirmation does not establish full format compatibility.
- Image previews support all Android image formats and extended formats. Platform codecs (via `ImageDecoder` on API 28+ and `BitmapFactory` fallback) decode JPEG (including Ultra HDR), PNG/APNG, GIF, WebP, BMP, WBMP, ICO/CUR, HEIF/HEIC, AVIF, DNG, and camera RAW files with EXIF orientation. Vector images (.svg, .svgz) are rendered via AndroidSVG. JPEG XL (.jxl) is decoded via `jxl-coder`. Truevision TGA (.tga, .icb, .vda, .vst) is decoded via built-in `TgaDecoder`. Large images are downsampled to preserve memory; oversized data (>40,000 px or >160 MP) retains the placeholder icon. Animated formats display their first frame. See [Image formats](image-formats.md).
- Built-in media, text, and document viewers handle audio, video, plain text/code, Markdown, HTML, PDF, EPUB, and CSV/TSV spreadsheets:
  - [Image formats](image-formats.md) (`:viewer-image` and thumbnail/icon decoders)
  - [Audio and sound formats](audio-formats.md) (`:viewer-audio`)
  - [Video formats](video-formats.md) (`:viewer-video`)
  - [Text, markup, document, and text-based media formats](text-and-document-formats.md) (`:viewer-text`, `:viewer-markdown`, `:viewer-html`, `:viewer-pdf`, `:viewer-epub`, `:viewer-csv`, including planned text-renderer support for ANSI art and ANSI music)
- Directory and per-resource icon conventions: If a local, remote (`sftp://`, `smb://`, `ftp://`, `webdav://`), or archive/disk-image (`archive://`) directory contains a `.icon.<format>`, `.icon`, `cover.<format>`, or `cover` image file, that image is decoded and displayed as the icon for that container across grid, list, tree, pinned-container, shortcut, properties, and floating-overlay views, with `.icon.*` taking precedence over `cover.*`. Any resource (file, directory, or archive) can also use a sibling `.icon-<full filename>.png` override, which takes precedence over container icons and generated program-icon caches. See [Resource icons](resource-icons.md).
- Direct primary shared-storage resources use provider ID `local` and app-owned FileProvider content URIs under the `shared` namespace. Only this provider resolves its own URI components against the Android-discovered storage root; it rejects traversal; link resolution retains the source directory entry and uses shared safeguards. SAF identities are never decoded into local paths. File extensions supply MIME hints for local files.
- App-provided SAF sources use the actual authority and opaque document IDs advertised by the provider or returned by Android’s picker. Termux-style home IDs may resemble filesystem paths; InfiniFolder still accesses them only through SAF. `app_provider_…` preference keys identify app sources. Existing media-directory grants and cached names from earlier builds are retained, but those names no longer define Application public.
- Staging ledger is SQLite (`staging.db`, schema version 1); `resource_id` is an opaque provider identity, while `local_path` is a private cache location. The ledger is currently only a schema and has no staging data flow.

## Not yet implemented

- `.desktop` and `.directory` parsing, shortcut import/export, and preservation of unknown fields.
- Custom file type rules, signatures, metadata inheritance, and portable InfiniFolder extensions.
- Additional remote providers beyond implemented SMB/SFTP/FTP/WebDAV, and fuller portable metadata import/export.
- Unimplemented image, audio, video, document, and text-based media (ANSI art / ANSI music) formats listed in [Image formats](image-formats.md), [Audio and sound formats](audio-formats.md), [Video formats](video-formats.md), and [Text, markup, document, and text-based media formats](text-and-document-formats.md).

The app does not currently write metadata sidecars. File names and provider metadata are displayed as text and are not executed as commands.

Current status and limitations: [project status](project-status.md). The legacy staging-ledger schema is not the current external-edit workflow; dedicated durable sessions now handle that workflow.

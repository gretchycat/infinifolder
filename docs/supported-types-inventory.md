# Supported type inventory

[supported-types.csv](supported-types.csv) is a UTF-8 CSV (with BOM for spreadsheet
applications), generated from the current static registries and implementation
sources. Regenerate from the repository root:

```sh
python3 scripts/export-supported-types.py
```

Columns separate category/identifier, extensions, MIME, capabilities, support level,
write support, owning module, limitations and source paths. Categories cover MIME
identifiers, file extensions, archive/package/image patterns, filesystems,
partition/volume formats and URI schemes. Multi-valued extensions use semicolons.

This is an inventory of advertised associations, routing and implemented subsets,
not proof that every codec, filesystem feature or corrupt/encrypted/multipart input
works. MIME recognition is explicitly separate from rendering or playback. Archive
recognition requires actual parser validation before browsing. Filesystem writes
are listed separately from reads. URI icon/external-handler support is distinguished
from real storage providers; NFS has no native storage implementation.

The exported snapshot contains **1,213 rows**: 600 file extension/pattern rows,
242 MIME identifiers, 326 archive/package/image rows, 14 filesystem rows,
4 partition/volume rows and 27 URI rows. Overlap between categories is intentional.
File rows without a static MIME association leave that cell empty: Android's MIME
map, provider metadata and content detection may supply it at runtime. Device MIME
tables, arbitrary `text/*` fallbacks, user-defined MIME/URI rules and external app
handlers are open-ended; this file does not export private user settings or
credentials. Platform codec availability and per-device acceptance remain separate.

Format limits and fixture evidence are documented in [images](image-formats.md),
[audio](audio-formats.md), [video](video-formats.md),
[documents](text-and-document-formats.md) and [overall formats](file-formats.md).

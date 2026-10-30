# Winamp skin integration

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

## Supported resources

Classic Winamp and XMMS skins in `.wsz`, ZIP, TAR, TAR.GZ/TGZ or TAR.BZ2 archives can be imported from Interface Theme → Import Winamp / XMMS Skin using a path, storage URI, or Browse button. Import reads a private copy on a worker thread, validates it, then publishes the theme on the main thread. The private archive and display name survive app restarts. Failed imports leave the selected theme intact.

- `PLEDIT.TXT`: Normal, Current, NormalBG, SelectedBG and Font. Keys tolerate whitespace and case differences. Arial maps to the platform sans-serif font; monospace/Courier/console names select monospace. Arbitrary Windows font files are not imported.
- `VISCOLOR.TXT`: background and spectrum/oscilloscope colors, including decimal RGB values with leading zeroes and inline comments.
- `MAIN.BMP`, `GEN.BMP`, `CBUTTONS.BMP`: bitmap-derived surface, toolbar and button palettes. Black backgrounds receive differentiated fallback surfaces when artwork is missing.
- `TITLEBAR.BMP`: the audio player displays the original classic title-bar artwork, including any embedded text.
- Floating-window borders use narrow edge strips from `MAIN.BMP`, excluding playback controls. Bitmap strips are tiled and scaled in whole-number steps without smoothing.

File labels and selection backgrounds now consume the selected theme instead of fixed modern colors. Foreground colors for selected text and toolbar/button text receive contrast correction. The file manager toolbar uses theme colors without skin images; floating windows use the bitmap frame. Icons still follow the separate icon-theme system.

## Findings from project fixtures

All four files in `Winamp/` contain classic bitmap skins, despite the Winamp3, Winamp5 and Bento names. They contain both palette text files, main/title-bar bitmap sheets, and generic-window bitmap sheets. Base has green-on-black text and blue selection; Classified v3 has pale green text; Classified v5 has white text on a blue background; Bento has blue-gray text on near-black.

Previously the importer ignored all bitmap sheets, SelectedBG and Font, always marked imports dark/monospace, and could report success for unrelated ZIP files by generating a fabricated fallback theme. Copy failures could apply a theme that was not durably saved. File-card colors were also hardcoded, masking imported text colors.

## Limits and validation

This adapts classic skin chrome and palettes to the existing file manager layout; it does not execute Winamp scripts or implement modern XML/WAL skins, custom player layouts, cursors, or every bitmap control. Empty/unrecognized ZIP archives are rejected. Recognized entries are bounded to 8 MiB each, 24 MiB combined, at most 512 archive entries; the imported compressed file is limited to 32 MiB. Bitmap decoding checks dimensions before allocating pixels (maximum 2 million pixels).

Fixture archive contents were inspected directly. APK compilation validates integration; handset confirmation of each skin's appearance, theme restoration and floating chrome is pending.

## Image scaling preference

Interface Theme → Skin image scaling offers Integer scaling (crisp pixels, default) and Smooth scaling (fractional scaling with bitmap filtering). The setting is remembered across application restarts and applies to the audio player and floating frame.

## Audio player and XMMS

The audio player has its own skin selection and persists its own imported archive, independently of the file manager interface theme. XMMS classic skin archives share the classic bitmap layout; ZIP, WSZ, TAR, gzip-compressed TAR and bzip2-compressed TAR are recognized by content. XMMS-specific extensions and unpacked skin directories are not supported.

The audio visualizer is disabled until the playback subsystem provides reliable analysis data.

# Winamp skin integration

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

## Supported resources

Classic Winamp and XMMS skins in `.wsz`, ZIP, TAR, TAR.GZ/TGZ or TAR.BZ2 archives can be imported from Interface Theme → Import Winamp / XMMS Skin using a path, storage URI, or Browse button. Import reads a private copy on a worker thread, validates it, then publishes the theme on the main thread. The private archive and display name survive app restarts. Failed imports leave the selected theme intact.

- `PLEDIT.TXT`: Normal, Current, NormalBG, SelectedBG and Font. Keys tolerate whitespace and case differences. Arial maps to the platform sans-serif font; monospace/Courier/console names select monospace. Arbitrary Windows font files are not imported.
- `VISCOLOR.TXT`: background and spectrum/oscilloscope colors, including decimal RGB values with leading zeroes and inline comments.
- `MAIN.BMP`, `GEN.BMP`, `CBUTTONS.BMP`: bitmap-derived surface, toolbar and button palettes. Black backgrounds receive differentiated fallback surfaces when artwork is missing.
- `TITLEBAR.BMP`: the audio player displays the original classic title-bar artwork, including any embedded text.
- Floating-window borders use narrow edge strips from `MAIN.BMP`, excluding playback controls. Bitmap strips are tiled and scaled in whole-number steps without smoothing.

File labels and selection backgrounds now consume the selected theme instead of fixed modern colors. Foreground colors for selected text and toolbar/button text receive contrast correction. The file manager toolbar uses theme colors without skin images; floating windows use the bitmap frame. Icons still follow the separate icon-theme system.

## Regular UI surfaces

Ordinary alternate backgrounds (including Open with, common dialogs, cards,
hex rows/controls and external-edit handoff windows) use the main background
shifted 10% toward white for dark backgrounds or black for light backgrounds.
The choice uses the background's measured luminance, including imported skins.
Dialog lists disable the platform's opaque scrolling-cache color. Imported
`surface`/`cardBackground` palette values remain available to the actual Winamp
player, playlist and bitmap chrome; this rule does not recolor skin artwork.
[Icon shadows](resource-icons.md#icon-shadows) use the same derived color.

## Findings from project fixtures

All four files in `Winamp/` contain classic bitmap skins, despite the Winamp3, Winamp5 and Bento names. They contain both palette text files, main/title-bar bitmap sheets, and generic-window bitmap sheets. Base has green-on-black text and blue selection; Classified v3 has pale green text; Classified v5 has white text on a blue background; Bento has blue-gray text on near-black.

Previously the importer ignored all bitmap sheets, SelectedBG and Font, always marked imports dark/monospace, and could report success for unrelated ZIP files by generating a fabricated fallback theme. Copy failures could apply a theme that was not durably saved. File-card colors were also hardcoded, masking imported text colors.

## Limits and validation

This adapts classic skin chrome and palettes to the existing file manager layout; it does not execute Winamp scripts or implement modern XML/WAL skins, custom player layouts, cursors, or every bitmap control. Empty/unrecognized ZIP archives are rejected. Recognized entries are bounded to 8 MiB each, 24 MiB combined, at most 512 archive entries; the imported compressed file is limited to 32 MiB. Bitmap decoding checks dimensions before allocating pixels (maximum 2 million pixels).

Fixture archive contents were inspected directly. APK compilation validates integration; handset confirmation of each skin's appearance, theme restoration and floating chrome is pending.

## Image scaling preference

Interface Theme → Skin image scaling offers Integer scaling (crisp pixels, default) and Smooth scaling (fractional scaling with bitmap filtering). The setting is remembered across application restarts and applies to the audio player and floating frame.

## Windows Media Player audio interface

Selecting Windows Media Player (Aero) in the player's theme chooser now selects
`WmpPlayerLayout`, rather than recoloring the generic player. It renders a
midnight-blue gradient stage, glass transport dock, scalable glossy blue play/pause
orb, vector transport symbols, album art, metadata and seek/time/volume controls.
The top toolbar remains available for theme, playlist, Cast, external opening and
Back actions. Portrait stacks metadata above the art; landscape places them side
by side. Artwork remains square and fits the available stage, within system-bar
and cutout insets. This is an Aero-style built-in layout, not a WMP skin-file importer.

The layout reparents the existing audio-player widgets and preserves their
listeners. Play/pause, previous/next, stop, shuffle, repeat, seeking, speed,
volume, diagnostics, playlist selection and Cast therefore use the existing
service/controller paths. Returning to Winamp, Modern or foobar restores the
original widget parents, layout parameters and normal seek thumb. No codec,
source handling or playback service is replaced.

Theme notifications now snapshot listeners under the registry lock and invoke
callbacks outside it. Window rebuilds may add/remove icon-view listeners during
notification without invalidating the iteration. This fixes the
`ConcurrentModificationException` recorded during audio theme switches in
`build/infinifolder-crash-20261104-213324-073.log`.

Main and combined standalone host build command:
`gradle :app:assembleDebug :viewer-host:assembleDebug --offline --console=plain`.
Build log: `build/docs-audit/wmp-audio-interface-build.log`. No automated tests
were run for this UI change. Device appearance, controls, Cast, rotation,
restart restoration and repeated theme switching remain unverified.

## Audio player and XMMS

The audio player has its own skin selection and persists its own imported archive, independently of the file manager interface theme. XMMS classic skin archives share the classic bitmap layout; ZIP, WSZ, TAR, gzip-compressed TAR and bzip2-compressed TAR are recognized by content. XMMS-specific extensions and unpacked skin directories are not supported.

The audio visualizer is disabled until the playback subsystem provides reliable analysis data.

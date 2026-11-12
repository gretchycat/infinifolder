# Winamp skin integration

Updated 2026-11-11. Player-specific artwork and UI belong to Infinimedia;
reusable archive/image support belongs to shared UI and Canvas. See the
[theme rendering guide](theme-rendering.md) for the current interfaces and
[executed audit](architecture-audit.md) for build and runtime results.

**Infinimedia Default** is the only packaged skin. The four older skins are
compatibility fixtures outside production assets. The custom theme retains
its original artwork authors and copyright. Its corrected IFF faceplate cycles;
the visual media pane uses the VIDEO title plus GEN edges and bottom corners.

## Supported resources

Classic Winamp and XMMS skins in `.wsz`, ZIP, TAR, TAR.GZ/TGZ or TAR.BZ2 archives can be imported from the player’s theme chooser → Import Winamp / XMMS Skin using a path, storage URI, or Browse button. Import reads a private copy on a worker thread, validates it, then publishes the theme on the main thread. The private archive and display name survive app restarts. Failed imports leave the selected theme intact.

- `PLEDIT.TXT`: Normal, Current, NormalBG, SelectedBG and Font. Keys tolerate whitespace and case differences. Arial maps to the platform sans-serif font; monospace/Courier/console names select monospace. Arbitrary Windows font files are not imported.
- `VISCOLOR.TXT`: background and spectrum/oscilloscope colors, including decimal RGB values with leading zeroes and inline comments.
- Main, gen and cbuttons logical roles: decoded artwork supplies surface, toolbar and button palettes. BMP naming remains compatible, while any Canvas-supported encoding may supply the role. A theme.json mapping can select arbitrary filenames or ordered same-size layers.
- `TITLEBAR.BMP`: the audio player displays the original classic title-bar artwork, including any embedded text.
- The visual media frame combines VIDEO title sprites and GEN side/bottom pieces, with a 16:9 inner stage. Clicking its title collapses the stage. Playlist and other existing chrome keep their own sprite layouts.

Player text and selection backgrounds consume the selected palette with foreground contrast correction. Infinifolder uses its own modern light/dark interface. Icons follow the separate icon-theme system. The player pauses animated skin rendering with its Activity lifecycle; theme changes close old controllers and discard queued results.

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

All four archives owned by [Infinimedia](https://github.com/gretchycat/infinimedia/tree/main/test-assets/skins) contain classic bitmap skins, despite the Winamp3, Winamp5 and Bento names. They contain both palette text files, main/title-bar bitmap sheets, and generic-window bitmap sheets. Base has green-on-black text and blue selection; Classified v3 has pale green text; Classified v5 has white text on a blue background; Bento has blue-gray text on near-black.

Previously the importer ignored all bitmap sheets, SelectedBG and Font, always marked imports dark/monospace, and could report success for unrelated ZIP files by generating a fabricated fallback theme. Copy failures could apply a theme that was not durably saved. File-card colors were also hardcoded, masking imported text colors.

## Limits and validation

This adapts classic skin chrome and palettes to the existing player layout; it does not execute Winamp scripts or implement modern XML/WAL skins, custom player layouts, cursors, or every bitmap control. Empty/unrecognized archives are rejected. All expanded entries count toward the 8 MiB per-entry, 24 MiB combined and 512-entry limits; the imported compressed file is limited to 32 MiB. Skin images are limited to 2 million pixels each, with an aggregate 8 million pixel layer/composite cache budget. Unsupported codecs use the existing visual fallback.

All four compatibility archives are tested explicitly. The independent Media build passed 80 unit tests; the Android raster/lifecycle probe covers static SVG, IFF animation, compositing, format detection and the full visual frame. Activity appearance, touch handling, theme restoration and video surface composition remain device acceptance checks.

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

Build the current independent player from its own repository with
`./build.sh --max-workers=1 --console=plain`. The older WMP layout change has no
separate device acceptance result; controls, Cast, rotation and repeated theme
switches still require visible on-device validation.

## Audio player and XMMS

The audio player has its own skin selection and persists its own imported archive, independently of the file manager interface theme. XMMS classic skin archives share the classic bitmap layout; ZIP, WSZ, TAR, gzip-compressed TAR and bzip2-compressed TAR are recognized by content. XMMS-specific extensions and unpacked skin directories are not supported.

The frame/theme renderer does not change the playback analysis or visualization pipeline.

## Repository ownership

The custom default source lives in Infinimedia's theme/ directory and is packaged reproducibly as skins/Infini.wsz. The four original archives live in test-assets/skins, outside production assets. MediaAssetOwnershipTest checks every fixture, the default IFF, metadata name and retained authors explicitly. The generic parser remains reusable in libinfiniui, whose tests use synthetic fixtures. Former Folder copies in `Winamp/` and `themes/mediaplayer/` were identical and were consolidated into Media.

The optional `FluidR3Mono_GM.sf3` bank likewise belongs to Infinimedia, under `test-assets/soundfonts`, with the matching upstream license and provenance. The bank remains available for import/testing; current opt-in SoundFont playback behavior is preserved. See [asset comparison evidence](validation/media-asset-consolidation.json).

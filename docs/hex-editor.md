# Hex viewer and editor

Status: implementation, automated checks and debug build passed. Device
acceptance remains pending; the owner requested commit and push with those checks
documented as unverified. Saved raw-media metadata enables automatic
PIC/WAVE opening and image thumbnails; ordinary files retain their existing routing.

## Launch and presentation

Long-press a readable file and choose **Open in hex editor**. The action applies
to ordinary files, link-file bytes, archives, disk images and files inside
containers. Directories and virtual installed-app/category entries do not have
this byte action. The source URI goes directly to the viewer; the archive parser
does not turn a file into its directory listing for this action.

`viewer-hex` supplies Android presentation and a retained document worker. Scribe's
`HexDocument` supplies positional reads, sparse buffered edits, undo/redo, search,
encoding and checksums. Existing storage handles, staging and guarded replacement
remain responsible for access and saves. `MediaSourceAdapter.stageCancellable`
is an additive entry point on the existing shared cache path.

The viewer uses the active UI theme and follows theme changes. Native ListView,
HorizontalScrollView, ScrollView, dialogs, text selection and input fields handle
gestures and the keyboard. System bars, cutouts and IME insets are respected.

- The table shows hexadecimal byte offsets, hexadecimal bytes and text. Choose
  8 or 16 bytes per row from the overflow menu; pages contain 4096 source bytes.
  Rows use 1 dp vertical padding and a 20 dp minimum height. Alternate rows blend
  the theme background 10% toward white in dark themes or black in light themes.
  Hex row text scales to the available width for the selected column count,
  encoding and offset width, including after rotation/window resizing.
- **Go to…** accepts decimal or `0x` hexadecimal byte offsets, including offsets
  greater than 2 GiB. Status shows both hex and decimal positions.
- Tap a byte to select it; hold a byte or use **Edit byte…** to overwrite bytes.
- CP437 is the default; UTF-8 is selectable, and recreation restores the current
  selection. These encodings affect the decoded table column and text search/edit
  encoding. Valid UTF-8 glyphs retain their byte spans, including sequences
  crossing rows/pages. CP437 uses
  the existing bundled font. Malformed/unprintable characters appear as `·` in
  the table's decoded text column. Valid UTF-8 continuation bytes use empty table
  cells. **Strings** shows ASCII bytes `0x20`–`0x7E` and replaces every control
  byte and byte `0x7F`–`0xFF` with `·` in both encoding modes, preserving one
  displayed character per source byte. Strings are selectable and page with the
  same source data; switching modes does not rewrite the file.
- Selected bytes use the theme accent, the active search match is blue and
  changed bytes are amber. Selection takes visual priority over other highlights.

## HEX / PIC / WAVE

The bottom row contains **Save** in HEX mode or **Save metadata** in PIC/WAVE,
previous/next window buttons, a cancel/stop
button during cancellable work, and right-aligned **HEX**, **PIC**, **WAVE** mode
buttons. The modes form a native, mutually exclusive RadioGroup; the selected
button appears pressed. **HEX** is the default for the long-press byte action.
Each mode supplies its own tools beneath the filename. The tools scroll within
a bounded part of the viewport so the image/waveform remains accessible with
the keyboard open or in landscape. The controls have a theme-colored border,
non-fading native scrollbars and a fixed-height heading that indicates whether
more controls are above/below. Horizontal control rows also keep their native
scrollbars visible when overflowing. Modes read the same buffered document, so
image/waveform previews reflect unsaved byte edits.

PIC/WAVE have a fixed action row beneath the filename, outside the scrolling
controls: **Auto detect**, **Render**, and **Fit** (PIC) or **Play/Stop** (WAVE).
All three actions share the available width. Metadata saving is always visible
in the bottom bar, enabled only after a valid render of the current configuration.
It writes the sidecar, not source bytes. Source byte edits are saved in HEX mode.

### PIC: raw image explorer

Width and height use editable number fields with **− / +** buttons. A tap changes
one pixel; a native long press repeats slowly, then accelerates while held. Release,
cancellation, loss of window focus or reaching a limit stops repetition. Changes
request a live preview after about 30 ms; requests coalesce behind one active render,
so slower sources do not build a queue of obsolete images. Metadata saving stays
disabled until the displayed render matches the current settings.

Width accepts every integer that fits at least one complete row, within 1–4096
pixels; it is not restricted to divisors of the file size. Changing width fits
height to the maximum complete rows. Height can then be entered/stepped independently
from 1 to that maximum. Limits account for depth, byte-aligned rows, explicit stride,
4 megapixels and 16 MiB per rendered image. Six labeled fields show rows, bytes per
row, image bytes, available/decoded bytes, trailing bytes and padding bits per row.
The fields have stable positions and text heights; long values fit within their
own cells. PIC/WAVE status reserves two lines, with long-press access to the full
message, so changing text cannot resize the preview. The palette strip’s space
also stays reserved when direct-color modes hide its colors.
Trailing bytes are reported, not silently treated
as pixels or a reason to reject an arbitrary width. Compressed layouts use measured
decoded length. Complete compressed runs are decoded before cropping a preview
window; malformed/incomplete data does not supply geometry bounds.

The image view fills the preview area between the controls and bottom bar, so
pinches, double-taps and pans can start in the blank space around an image.
The image matrix fits the selected resolution with square source pixels; zoomed
content can expand across the preview instead of being clipped to its original
narrow fitted rectangle. PIC raises its zoom limit as needed to allow at least
16 dp per source pixel, including tall images only a few pixels wide. The ordinary
shared viewer’s default maximum remains 5×. Live geometry changes retain relative
zoom and the normalized center while fitting the new aspect ratio. **Smooth scaling**
switches immediately between Pixel (off, the default) and Smooth (on), without
decoding again. Pixel uses nearest-neighbor scaling with no dithering to preserve
palette colors; Smooth enables filtering and dithering. Rotation restores the choice,
and Save metadata preserves it with the image configuration.
Set a decimal/`0x` byte offset, row stride (`0` means tightly packed), compression,
palette, byte order, RGB/BGR order, bottom-up rows and optional 32-bit alpha.
The PIC switches use two columns when their complete labels fit, falling back
to one column on narrow windows or with larger fonts. Native switches retain
at least 48 dp touch targets. WAVE’s byte-order switch is also in its own row;
these switches require no sideways scrolling and remain in the tools’ vertical
scroll area.
Pinch, double-tap and pan use the existing `ZoomImageView`; **Fit** resets the view.

| Depth | Interpretation |
|---|---|
| 1 / 2 / 4 / 6 / 8 bpp | Packed, most-significant-bit-first palette indexes; each row starts on a byte boundary. |
| 12 bpp | Packed RGB444, most-significant nibble first. |
| 16 bpp | RGB565; selectable byte order. |
| 24 bpp | RGB or BGR bytes. |
| 32 bpp | RGBX/BGRX, or RGBA/BGRA when Alpha is enabled. |

The palette dropdown is filtered immediately when depth changes:

A single horizontal color strip beneath the selector shows every entry of the
selected preset or embedded file palette, in index order. It fits the full palette
across the available width without smoothing, updates immediately on palette/depth
changes and is hidden for direct-color depths.

| Depth | Available presets |
|---|---|
| 1 bpp | Monochrome, CGA monochrome, four approximate CGA NTSC artifact phases. |
| 2 bpp | Grayscale, all six CGA four-color variants, four artifact phases. |
| 4 bpp | Grayscale, CGA RGBI, EGA 16 colors, Commodore 64, Apple II, four artifact phases. |
| 6 bpp | Grayscale, EGA 64 colors. |
| 8 bpp | Grayscale, DOS VGA standard 256 colors, legacy RGBI/grayscale/color-cube preset. |
| 12–32 bpp | Direct color; palette control disabled. |

The six CGA four-color presets are palette 0, palette 1 and mode 5, each at low
and high intensity, with black backgrounds. Their RGBI index sets follow the
hardware color-select behavior documented in
[DOSBox Staging's implementation](https://github.com/dosbox-staging/dosbox-staging/blob/main/src/hardware/video/vga_other.cpp).
An embedded file palette is offered at its original compatible indexed depth;
out-of-range file-palette indexes fail instead of wrapping to misleading colors.
At 6 bpp, EGA supplies all 64 colors; this is packed indexed data, not EGA
plane-layout emulation. DOS VGA supplies the standard BIOS 256-color palette,
including its grayscale ramp and hue rings, verified against the factual DAC
values in [DOSBox Staging’s BIOS table](https://github.com/dosbox-staging/dosbox-staging/blob/main/src/ints/int10_modes.cpp).
The prior RGBI/cube preset remains a separate choice for saved configurations.
Neither preset recovers an arbitrary programmable palette from raw bytes. Detected
indexed BMP files can retain their embedded palette.

CGA artifact presets group neighboring bits into four-bit patterns at 1/2 bpp;
at larger indexed depths, the pattern palette is used directly. This is an
original YIQ-based approximation. It does not emulate early/late CGA boards,
320/640-mode composite circuitry, monitor filtering or CGA's banked video memory.
Real composite colors depend on mode, phase/hue and card revision; see
[DOSBox Staging's composite-video documentation](https://www.dosbox-staging.org/0.83/manual/graphics/composite-video/).
The Apple II preset uses the factual lo-res reference RGB values documented in
[AppleWin's RGB monitor source](https://github.com/AppleWin/AppleWin/blob/master/source/RGBMonitor.cpp);
it does not emulate Apple II address layouts or hi-res artifacting.

Compression choices have explicit variants: **Raw**, **RLE: PackBits**,
**RLE: count/value** (zero count means 256), and **LZW: TIFF/MSB** (9–12-bit,
early-change codes). GIF's LSB-first LZW and unspecified RLE variants are not
silently treated as equivalent. Compressed input is bounded to 16 MiB. Invalid
or incomplete data clears the preview and disables metadata saving. Previous/next
advances one uncompressed frame in Raw mode; compressed-stream offsets must be
set manually because their lengths vary.

**Auto detect** examines at most 64 KiB from the selected offset. Supported uncompressed BMP headers can
provide dimensions, palette, stride and orientation. RGB555/bitfield/compressed
BMP layouts are not claimed as supported raw configurations. Headerless suggestions
combine standard resolutions, associated color depths and byte counts. The current
depth is a ranking hint. Examples include 320×200 at 2 bpp with a CGA preset,
640×200 at 1 bpp, DOS EGA at 4 bpp, and VGA/indexed modes at 8 bpp. Exact-size
standard modes rank first; modes with a small remainder are also offered with an
explicit trailing-byte count. Generic common-width suggestions remain available.
Headerless results are explicitly labeled **Guess**: raw bytes cannot prove
resolution, palette or memory layout. Selecting a guess installs its geometry,
depth and palette together; manual geometry is unrestricted by these presets.

### WAVE: raw PCM explorer

Set offset, sample rate (1000–384000 Hz), mono/stereo, byte order and sample
encoding: unsigned/signed 8-bit, signed 16/24/32-bit or float32. The frame-window
size is 1–262144. Per-channel min/max envelopes preserve peaks, with at most
1024 columns. Tap the waveform to select the corresponding source byte offset;
previous/next moves by the configured frame window.

**Play** streams from the configured offset to EOF (or the detected WAVE data
boundary), **Stop** cancels, and leaving the activity stops playback. The existing
Sound PCM model performs conversion; Android `AudioTrack` and audio focus handle
output. Both display and playback use 16-bit precision. Non-finite float data is
rejected. Playback does not edit or export an audio file.

Auto detection reads a bounded prefix for RIFF/WAVE PCM/float headers. Other
files offer explicitly labeled common raw PCM guesses. Unsupported WAVE variants
are not presented as decoded audio.

### Save metadata and automatic opening/icons

After a successful image/waveform render, **Save metadata** writes a sibling named
exactly **`.filename.ext.meta`**, through the source's storage provider. Save byte
changes first; read-only locations report an error. The hidden JSON sidecar uses
format `infinifolder.raw-media`, version 1, exact `sourceName`, `kind` (`image` or
`audio`) and the corresponding image/audio configuration. Palette, 6-bit depth,
compression, layout, Pixel/Smooth scaling and audio byte-length settings all persist. Unknown existing
JSON fields survive updates. Unrelated, malformed, oversized or newer-version
sidecars are never overwritten. Replacement uses the existing guarded storage
save path; a failed new save removes its empty newly created sidecar.

Normal opening of a configured file enters PIC or WAVE without autoplay. Explicit
external defaults and **Open externally** retain priority; **Open in hex editor**
still starts in HEX. A valid image sidecar generates an actual thumbnail through
the shared icon resolver; audio metadata selects an audio icon. Explicit per-file
artwork remains higher priority. Failed rendering uses an image icon fallback.
Directory listings index sidecars and cached absence, avoiding repeated provider
reads for ordinary files. Save updates icon revisions and floating-window icons;
fresh listing changes/removal invalidate saved state, while a redraw of an old
listing cannot erase a newly saved configuration.

Image thumbnails use positional reads when available and otherwise consume only
a bounded sequential window, with no whole-file staging. A sequential thumbnail
cannot skip more than 16 MiB to reach an offset; it falls back to an image icon.
The full explorer retains its normal shared-cache staging support when a source
cannot provide random access. Backend reads remain subject to provider timeouts;
icon work uses the existing bounded background workers. Sidecars inside a
read-only archive require saving to a writable location first.

Raw image icons at least twice as tall as wide (or vice versa) use a centered
square crop before sampling. The narrow dimension fills the icon and the long
dimension is cropped, preserving narrow-side detail instead of reducing the
whole image to a thin stripe. Cropped thumbnails are rendered at icon size,
respecting saved Pixel/Smooth filtering. The full PIC view retains the entire
image and its geometry.

## Editing and saves

View mode uses real positional access when available. Sources without positional
access use the existing disk cache, subject to its configured capacity. Memory
usage is independent of total source length; this viewer does not allocate a
whole-file byte array or impose an in-memory file-size limit.

Before editing, the worker creates a private original snapshot in
`filesDir/hex_edits/<uuid>/`. Edits live in compact byte pages and a durable journal,
with bounded undo/redo. Rotation retains the worker/handles; Android saved state
can reopen the original snapshot and journal after process recreation. Draft
presence does not launch the viewer during a fresh browser startup. There is no
separate browse-all-drafts recovery UI in this first version.

Edits overwrite existing bytes; insertion, deletion and resizing are not included.
An individual text/hex/bit edit accepts up to 4096 encoded bytes. Up to 1 MiB of
changed bytes and 4096 distinct 4 KiB edit pages may be buffered between saves.
Undo retains up to 100 actions within 4 MiB of before/after data. Opening a large
image is cheap with positional access; enabling editing needs enough local disk
space for an entire snapshot. Saving needs a replacement file and may need the
storage backend's recovery copy too.

**Save** asks before replacing the original. The existing `ContentReplacement`
path checks the original byte revision, uses native replacement where supported
and retains the existing recovery/rollback behavior elsewhere. A conflict/error
leaves buffered edits available. Successfully committed bytes become the viewer's
new baseline, avoiding an old provider cache entry. A read-only original can be
edited locally and exported using **Save copy…**; copy export leaves changes to
the original unsaved. Back asks before discarding changed bytes.

## Search

**Search…** and **Next** scan buffered bytes in bounded blocks, preserving matches
across block boundaries and wrapping to the beginning when necessary. Search can
be cancelled between reads. A currently blocked backend read remains subject to
that backend's own timeout. Patterns are limited to 4096 bytes.

- Text uses the selected UTF-8 or CP437 encoding and matches case exactly.
- Hex accepts complete bytes such as `DE AD BE EF` or `0xDE,0xAD`.
- Binary accepts complete groups of eight bits, such as `01001000 01101001`.
- Integers accept decimal or `0x` input, signed/unsigned 8-, 16-, 24-, 32- and
  64-bit widths, and big/little endian. Search is byte-aligned and does not require
  alignment to the integer width. Unsigned 64-bit values retain all 64 bits.

## Fill byte range

Overflow → **Fill byte range…** repeats an 8-, 16-, 24- or 32-bit value over a
chosen byte range. Start is inclusive and end is exclusive. Decimal/`0x` values,
signedness and big/little endian are selectable. For example, 24-bit `0x123456`
emits `12 34 56` in big endian or `56 34 12` in little endian.

The range must contain a whole number of chunks and fit in the file. A fill is
limited to 1 MiB and becomes one undo action; it stays buffered until Save.

## Checksums and final-byte correction

Overflow → **Checksums…** calculates unsigned additive **sum8**, **CRC32** and
**SHA-256** over a chosen nonempty range of buffered bytes. Range end is exclusive.

**Adjust final byte of range** supports the common additive ROM checksum rule:
choose desired sum `00` or `FF`, review the old/proposed byte, then apply an unsaved
edit. The new byte is `(targetSum - currentSum + oldLastByte) & 255`. The same Save
and undo rules apply. This does not infer or repair vendor-specific BIOS formats,
signatures or other checksum algorithms.

## Validation

Automated checks exercise actual byte reading/editing/search/checksum logic,
including real files, short reads, premature EOF, sparse changes, undo/redo,
32-bit-boundary offsets, block-crossing searches/fills, signed and unsigned integer
limits, endian order, UTF-8 spans/errors, CP437, CRC32/SHA-256 reference vectors,
and additive final-byte correction to both targets. Build/test results are
recorded below.

Earlier full-suite command:

```sh
gradle :viewer-hex:testDebugUnitTest :libinfinicanvas:canvas-android:testDebugUnitTest :libinfinisound:audio-api:test :libinfiniscribe:scribe-core:test :viewer-common:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

Passed: 139 Scribe core tests (including 26 hex cases), 121 shared-viewer tests,
21 app tests, 80 Canvas tests (including 11 raw image cases), 7 raw PCM tests and
11 raw metadata/rendering tests: **379 total, zero failures/errors/skips**. The
debug APK build passed in the same run (18 seconds).

The raw cases decode actual packed samples, RGB pixels, compressed streams,
BMP/WAVE headers and waveform peaks. Metadata cases write/read real temporary
files through a storage provider, preserve source bytes and extra fields, reject
malformed/foreign metadata, test failures/removal/stale listings, render source
bytes and buffered edits, and prove a thumbnail of a 100 MiB sequential source
reads only the configured six-byte prefix.

Log: `build/hex-explorers/final.log`; structured results and APK SHA-256:
`build/hex-explorers/validation.json`. APK:
`app/build/outputs/apk/debug/app-debug.apk`. Earlier hex-only results remain in
`build/hex-editor/`. The APK includes pre-existing unrelated workspace changes.
No device/UI checks have run.

The earlier linked-dimensions/depth-filtered-palette update passed 85 Canvas
and 11 raw viewer tests: **96 focused tests**, zero failures/errors/skips, plus a
successful debug APK rebuild. New cases verify exact byte consumption at every
depth, stride/padding and safety limits, real compressed stream sizes, all six
CGA palettes, depth compatibility and invalid embedded-palette indexes. Logs and
APK hash: `build/hex-explorer-layouts/final.log` and
`build/hex-explorer-layouts/validation.json`. Its divisor-only geometry restriction
has since been replaced with arbitrary widths, bounded heights and a trailing-byte
count.

The current number-control/live-preview update passes 89 Canvas, 16 raw viewer
and 121 shared viewer tests: **226 focused tests**, zero failures/errors/skips,
plus a successful debug APK rebuild. Added coverage verifies arbitrary widths
such as 319/320/321, large/partial buffers, standard CGA/EGA/VGA resolution/depth
ranking and small remainders. Aspect-fit cases cover landscape, portrait, arbitrary
widths and rounding. Real sidecar save/reopen cases check scaling persistence.
Packed 4-bpp and 8-bpp vectors independently verify every DOS EGA and DOS VGA
color index against literal/reference palette values. Those RGB values were already correct; the native
[BitmapDrawable default enables filtering and dithering](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/graphics/drawable/BitmapDrawable.java),
which can blend indexed colors during scaling. PIC’s default Pixel mode disables those flags; Smooth mode explicitly enables
them. The original reported file has not been supplied, so its visual
EGA issue is not claimed reproduced or confirmed resolved.

Current focused command:

```sh
gradle :libinfinicanvas:canvas-android:testDebugUnitTest :viewer-hex:testDebugUnitTest :viewer-common:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

Current log and APK hash: `build/hex-geometry-controls/final.log` and
`build/hex-geometry-controls/validation.json`. Native hold acceleration, image
updates/aspect/zoom, palette appearance and Auto detect UI remain unverified on
a device.

The subsequent palette-strip UI change rebuilt successfully with
`gradle :app:assembleDebug --offline --console=plain` (28 seconds), logged in
`build/hex-palette-strip/build.log`. Tests were not rerun for that presentation
change; on-device palette-strip appearance remains unverified.

The separate layout-value fields, reserved preview status and reserved palette
space rebuilt successfully with the same assemble command (42 seconds), logged
in `build/hex-stable-values/build.log`. Tests were not rerun for this UI change;
changing large/small values, errors and palette/depth changes still need a device
check for a stable preview area.

The adaptive switch-grid change rebuilt successfully with the same assemble
command (21 seconds), logged in `build/hex-toggle-layout/build.log`. Tests were
not rerun. Phone/landscape widths and large-font switch layouts remain unverified
on a device.

The fixed explorer action row and bottom-bar metadata-save changes rebuilt
successfully with `gradle :app:assembleDebug --offline --console=plain`
(24 seconds), logged in `build/hex-explorer-actions/build.log`. Tests were not
rerun. Device checks remain pending for action visibility while scrolling,
mode changes, current-render save enablement and saving/reopening both sidecars.

The bordered controls area, directional scroll hint and non-fading native
scrollbars rebuilt successfully with the same assemble command (26 seconds),
logged in `build/hex-scroll-affordance/final.log`. Tests were not rerun for these
UI changes. On-device visibility/scroll checks remain pending.

The full-preview gesture surface and source-pixel zoom-limit change rebuilt
successfully with the same assemble command (13 seconds), logged in
`build/hex-full-preview-zoom/build.log`. Tests were not rerun. Device checks remain
pending for pinches beginning in the blank area, expansion of an 8-pixel-wide
image across the preview, panning at high zoom and Fit/geometry changes.

The raw-icon crop-before-sampling update rebuilt successfully with the same
assemble command (25 seconds), logged in `build/hex-fill-icons/build.log`.
Tests were not rerun. Device checks remain pending for tall/wide raw-image
icons in listing/tree/floating views and saved Pixel/Smooth filtering.

Device checks pending:

1. Long-press a binary file, ZIP, disk image and nested archive entry; verify the
   viewer shows their encoded bytes, rather than auto-browsing the container.
2. Check light/dark/custom themes, scrolling, rotation, 8/16-column layouts,
   UTF-8/CP437 switching, Strings, keyboard and system/navigation-bar insets.
3. Edit a known byte, undo/redo, cancel/discard, save, reopen and compare content.
   Repeat with a read-only resource and Save copy, plus a supported remote source.
4. Search for text/hex/bits and integers in both byte orders; test a block-boundary
   match and an offset above 2 GiB. Cancel a long search/download.
5. Fill 8/16/24/32-bit ranges in both orders, undo once, and check untouched bytes.
   Reject a partial final chunk and an out-of-file range.
6. On a disposable ROM copy, calculate checksums, correct the last byte to sum00
   and sumFF, save and independently verify the resulting sum and file length.
7. Change the original outside the viewer while editing; Save must reject the
   conflict, retain edited bytes and allow Save copy.
8. Check exclusive bottom mode buttons, tools scrolling, image zoom, editable
   dimensions, accelerating hold repeat, live previews, packed 6-bit EGA and all
   four artifact phases with known raw images. Verify manual 319/320/321 widths,
   height bounds and trailing-byte counts. Auto detect should prioritize standard
   resolution/depth combinations and install their corresponding palettes.
   Check all six CGA variants, DOS EGA/VGA reference colors and RLE/LZW fallback.
   Toggle Pixel/Smooth while zoomed, change geometry and rotate; check aspect,
   retained zoom/position, immediate filtering and saved/reopened scaling choice.
9. Check mono/stereo waveforms, endian/sample formats, Play/Stop and stopping on
   task changes. Test header detection and manually selected raw PCM guesses.
10. Save PIC and WAVE metadata, return to the browser and verify icons/opening,
    restart the app, then change/remove the sidecar and refresh. Repeat in a
    writable non-local backend and verify an error on a read-only location.

No device is currently connected through ADB. The owner requested commit/push
without waiting for the pending device checks; UI success is not inferred from
compilation.

# RIPscrip graphics

RIP `.rip` files use `image/x-rip` and open in the existing zoomable image viewer.
The MIME registry classifies them as image-renderable, so normal per-MIME icon
settings also control their thumbnails. Strong command prefixes can identify
RIP graphics independently of a filename extension. Local, content and routed
storage streams use the existing Canvas/Store interfaces.

## Rendering modes

**EGA simulation is the default**, for thumbnails and newly opened viewers.
The renderer uses a 640×350 indexed surface, the 16 active EGA palette entries,
palette changes that recolour existing pixels, patterned fills and COPY/XOR
operations. The displayed bitmap corrects the non-square EGA pixels to 4:3.
The image viewer disables bitmap filtering in this mode to retain the pixel grid.

The viewer's **EGA / Vector** control selects **High-resolution vector** rendering.
This replays geometry at up to four times the native coordinate resolution,
producing up to a 2560×1920 display bitmap, rather than enlarging the EGA render.
It remains a rasterized view of the RIP vector commands, like the existing SVG
viewer; this does not add SVG export. The mode survives activity recreation,
while a newly opened viewer defaults to EGA. Thumbnail rendering stays in EGA.
Bitmap fonts retain their bitmap shapes in both modes.

## Supported static graphics

- Reset/viewport clear and clipping; base-36 MegaNum parameters.
- Draw colour, full and single-entry EGA palette updates, COPY/XOR write mode.
- Pixels, lines, rectangles, filled bars, circles, ellipses, arcs and pie slices.
- Open/closed polygons, filled polygons, cubic Bezier curves and border flood fill.
- Built-in/custom line and fill patterns, line thickness.
- CP437 bitmap graphics text, text placement, horizontal/vertical size settings.
- In-memory clipboard get/put with COPY/XOR/OR/AND/NOT raster operations.
- Escaped delimiters/backslashes and continued physical lines.

This is a **partial static RIPscrip 1.54 implementation**, not a RIPterm emulator.
BGI stroke fonts currently use a reported CP437 bitmap fallback. TTY text outside
graphics command lines, styled buttons, formatted text regions, external ICN
assets/scenes and later RIP versions are not implemented. Unsupported commands
are reported in frame warnings; the viewer labels partial rendering and displays
a warning. Invalid parameters, files without supported commands and exceeded
limits fail decoding. No session replies, external file loads or BBS commands
are executed. The source is capped at 2 MiB, with command, pixel-work,
resolution/time and interruption limits.

## Ownership and reference

Canvas owns RipScriptDecoder, decoder dispatch, image signature hints and frame
warnings. Store owns MIME/magic recognition. The host adds the MIME image class
and viewer mode control. Existing public decode entry points retain their default
EGA overloads; ordinary image handling remains on the existing path.

The implementation follows TeleGrafix's published specification:
[RIPscrip 1.54 original reference](https://github.com/bbs-land/remote-imaging-protocol/blob/main/version/1.54/text/RIPScrip-1.54.txt).
The downloaded reference is kept only in the ignored build directory; no upstream
renderer code or proprietary font assets were copied.

## Validation

`gradle :app:assembleDebug --offline --console=plain` succeeded, including both
rendering modes. Log: `build/ripscrip-modes-build.log`. No tests were added or run
for this request. Device rendering, real historical art files, gestures and mode
switching remain unverified because no device is connected. Example source:
`filesystem-test-images/images/ripscrip-modes.rip`.

## Text sizing follow-up

The CP437 substitute for BGI stroke fonts now uses font-specific nominal metrics
and BGI fractional size factors instead of the default bitmap font's integer
magnification. In particular, Small/LITT at size 4 uses its native small metrics,
rather than a 32-pixel-tall 8×8 enlargement. Default bitmap-font scaling remains
unchanged. These are metric-scaled substitute glyphs, not the original CHR font
outlines; the partial-rendering warning remains. Metrics reference:
[BGI font measurements](https://github.com/DosWorld/dwbgi/blob/master/DOCS/CHR.MD).
Build log: `build/rip-text-theme-first-frame.log`; device checks remain pending.

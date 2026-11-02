# RIPscrip graphics

Current font support: the ten BGI stroke fonts are now bundled and rendered from
their original CHR outlines. The earlier substitution/metrics sections below
record the investigation that led to this implementation.

RIP `.rip` files use `image/x-rip` and open in the existing zoomable image viewer.
The MIME registry classifies them as image-renderable, so normal per-MIME icon
settings also control their thumbnails. Strong command prefixes can identify
RIP graphics independently of a filename extension. Local, content and routed
storage streams use the existing Canvas/Store interfaces.

## Rendering modes

**High-resolution vector is the viewer default**; thumbnails use EGA simulation.
The renderer uses a 640×350 indexed surface, the 16 active EGA palette entries,
palette changes that recolour existing pixels, patterned fills and COPY/XOR
operations. The displayed bitmap corrects the non-square EGA pixels to 4:3.
The image viewer disables bitmap filtering in this mode to retain the pixel grid.

The viewer's native **EGA / Vector** toggle selects **High-resolution vector** rendering.
This replays geometry at up to four times the native coordinate resolution,
producing up to a 2560×1920 display bitmap, rather than enlarging the EGA render.
It remains a rasterized view of the RIP vector commands, like the existing SVG
viewer; this does not add SVG export. The mode survives activity recreation,
while a newly opened viewer defaults to Vector. Switching modes preserves zoom
and pan across the different bitmap resolutions. Thumbnail rendering stays in EGA.
Bitmap fonts retain their bitmap shapes in both modes.

## Supported static graphics

- Reset/viewport clear and clipping; base-36 MegaNum parameters.
- Draw colour, full and single-entry EGA palette updates, COPY/XOR write mode.
- Pixels, lines, rectangles, filled bars, circles, ellipses, arcs and pie slices.
- Open/closed polygons, filled polygons, cubic Bezier curves and border flood fill.
- Built-in/custom line and fill patterns, line thickness.
- CP437 bitmap and original BGI stroke graphics text, text placement and horizontal/vertical sizing.
- In-memory clipboard get/put with COPY/XOR/OR/AND/NOT raster operations.
- Escaped delimiters/backslashes and continued physical lines.

This is a **partial static RIPscrip 1.54 implementation**, not a RIPterm emulator.
A missing/corrupt stroke font uses a reported CP437 fallback. Static button styles
and labels now have best-effort rendering. Formatted text regions, external ICN
asset loading/scenes, interactive BBS behavior and later RIP versions remain unsupported. Unsupported commands
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

## Device dump: REAPER.RIP font layout

Reviewed `build/infinifolder-debug-20261102-051712-385.{json,log,png}` and the
actual REAPER.RIP source in the user's Download/DWM_RIP1.ZIP. The screenshot
shows right-edge text clipping; the source selects Triplex (`Y01000400`) for
those labels. Numeric advance measurements from the reference TRIP.CHR put the
first label's right edge at x=617, whereas the previous fixed-width substitute
put it at x=658 on a 640-pixel-wide surface. All eight Triplex labels have measured
right edges between 611 and 619; the old estimates ran to 658–698.

RipFontMetrics now stores measured advances and nominal heights for font IDs
1–10. `scripts/measure-bgi-metrics.py` reproduces those numeric tables from the
reference files, with source SHA-256 hashes recorded. Only layout measurements
are included; downloaded CHR files/stroke outlines stay in ignored build storage.
CP437 substitute glyphs remain, with an explicit partial-rendering warning.
RIP files reference presumed terminal fonts by ID, size and direction rather
than embedding their glyphs. Authentic stroke-font shapes are still unsupported.

The original font references are under
[the RIPterm 1.54 reference font directory](https://github.com/bbs-land/remote-imaging-protocol/tree/main/version/1.54/assets/fonts).
The current app build succeeds (`build/theme-cache-rip-proportions-build.log`).
The source measurements explain the posted screenshot, but rendering the new
build on-device remains unverified. No tests were added or run.

## Bundled original BGI stroke fonts

Fonts 1–10 now load from local CHR resources: Triplex, Small, Sans Serif, Gothic,
Script, Simplex, Triplex Script, Complex, European and Bold. Font 0 retains the
CP437 bitmap renderer. BgiStrokeFonts reads the bounded header, advance/offset
tables, signed 7-bit vertices and move/draw/end opcodes. Text uses the real glyph
strokes, ascenders, proportional advances and BGI fractional size factors in
both EGA and high-resolution rendering. No font network requests occur at runtime.
Malformed/missing resources fall back with a warning instead of disappearing.

Font assets come from the pinned GameMaker distribution identified by
vector-text-borland as its MIT font source. Original Borland font headers and
the upstream license are preserved; full provenance, source revision and hashes
are in `libraries/libinfinicanvas/third_party/bgi-fonts/PROVENANCE.md`. Only font
assets were imported, not upstream program/compiler code. The APK contains all
ten CHR resources and their license. This supersedes the earlier statement that
authentic stroke shapes were unsupported; other static-RIP limitations remain.

App build: `gradle :app:assembleDebug --offline --console=plain`, successful.
Logs: `build/bgi-fonts-theme-cache-build.log` and `build/bgi-fonts-final-build.log`.
No tests were added or run. The posted screenshot was inspected, and its actual
RIP source examined; the new font rendering still needs on-device visual checks.

## Unsupported-command dump follow-up: text windows

Reviewed `build/infinifolder-debug-20261102-053308-566.{log,json,png}`. Its screenshot
shows the DWM_RIP1.ZIP directory; the log did not yet record renderer warnings.
Inspection of all seven actual `.RIP` sources identifies `w` (RIP_TEXT_WINDOW)
as their unsupported opcode. Graphics-text continuations in BF-109.RIP remain
part of their graphics text commands, rather than being routed as TTY text.

RipTextWindow now implements `w`, `e`, `g`, `H` and `>`: independent TTY bounds,
all five cell grids, wrap/truncate, scrolling, CP437 cells, cursor motion and
erasing. Common ANSI cursor, SGR colour and erase sequences render directly on
the shared indexed surface, independent of graphics clipping and write mode.
Reset restores the 80×43 window. Zero-bound disabled windows accept the legacy
nonzero size field found in REAPER/BLITZ; no unsupported operation is advertised
as implemented merely to silence a warning. Unsupported terminal attributes still
warn. DOS EOF metadata is not displayed as TTY content. Ordinary TTY backslashes
are preserved; only graphics command lines use RIP continuation rules.

Image-viewer warnings now emit `viewer.image.render-warning` diagnostics with the
filename and actual warnings, as well as the existing visible partial-rendering
notice. This makes the next offloaded log useful for further opcode work.

Validation: app debug build passed; `build/rip-text-window-build.log` and
`build/rip-text-window-final-build.log`. No tests were added or run. The new build
still needs device checks against the seven original art files; other unsupported
operations listed above remain unsupported.

## BANG_RIP archive: best-effort recovery

Inspected `build/infinifolder-debug-20261102-054304-984.{log,json,png}` and all
12 RIP sources in the user's Download/BANG_RIP.ZIP. The screenshot shows a failed
NUKE.RIP decode; the log identifies `1B/1U` button styling/instances and `1I`
external icons in other scenes. The archive is not uniformly corrupt:

- NUKE.RIP includes `p03DH84`, declaring a three-vertex polygon with only one
  vertex supplied. Previously that aborted the entire scene.
- COMMENT.RIP includes `w00000$0$10`, with non-MegaNum/unresolved window fields.
- FILEALL, MAINALL and MESSALL reference LORDNEWW, LORDDRAG.ICN and LORDWIZ2.
  None of those external ICN images is present in this archive. Its F.EXE is
  described by the author's documentation as a DOS viewer; it was not executed.
- POSTRIP starts with the documented SOH alternative RIP command prefix, which
  the renderer previously treated as ordinary TTY text.

The renderer now recovers from malformed command arguments, keeps the remaining
scene and emits a visible/logged partial-render warning. Command/work/time and
cancellation limits remain fatal, rather than being swallowed by recovery.
Deadline checks are amortized across pixel work to reduce overhead on fill-heavy
scenes. SOH/STX command prefixes are recognized, including after a TTY prefix.

Static `1B/1U` button rendering includes base panels, labels, outside bevels,
recessed/sunken effects, best-effort chisel decoration, selected appearance,
label shadows, label placement/justification, hotkey highlighting/underlining and
existing clipboard button imagery. It preserves graphics drawing state. This
renders menus as pictures; no BBS commands or interactive host actions execute.
Automatic button clipboard capture still warns as unsupported.

External `1I` references remain unavailable: the prescribed missing-art box is
rendered with a filename and a warning. No substitute artwork is fabricated and
no external paths are opened. The same marker is used for icon-button references.
Some screen details cannot be recovered without the missing original assets or
host-substituted fields. Other unsupported commands remain explicit warnings.

Validation: `gradle :app:assembleDebug --offline --console=plain` succeeded.
Logs: `build/rip-bang-recovery-build.log` and `build/rip-bang-recovery-final.log`.
No tests were added or run. This is source/dump-based repair; rendering the new
build against the BANG_RIP and original DWM sets on-device remains unverified.

## JOUST rendering and thumbnail follow-up

Reproduced the supplied `filesystem-test-images/images/JOUST.RIP` with real
Android bitmap rendering from the APK. Both modes had boundary-fill spills;
the vector render erased nearly all of the knight and horse. Flood connectivity
now uses a reference raster of the same command stream: native EGA checks a 2x
reference, and vector rendering checks the native raster. Coverage masks constrain
fills to the corresponding region, allowing one reference pixel at an edge.
A seed on the reference border does not flood. Graphics commands still draw on
each mode's own indexed surface; the vector image is not an enlarged EGA bitmap.
This adds bounded reference-render work, and remains best-effort static RIP
rendering rather than an exact BGI emulator.

The final APK rendered JOUST in both modes with the knight, horse and lance
visibly retained. Also rendered ANIF, BF-109, BLITZ, CASTLE, NIETZ and REAPER in
both modes: all 14 outputs decoded without warnings. JOUST took 876 ms in EGA
and 711 ms in vector in this diagnostic run; these are headless renderer times,
not UI-load measurements. Preview PNGs and the real Android render harness are
in `build/graphics-review`; final log: `checked-render.log`. Earlier prototype
runs in that directory are diagnostic experiments, not the final result.

ANSI thumbnails now trim unused black outer margins before fitting the icon;
interior spacing and colored backgrounds remain, with aspect ratio preserved.
Only icon/page-preview callers request trimming; the normal ANSI surface keeps
its terminal layout. The real `icon.ans` preview was rendered with both settings.

The corrupted `icon.pi1` thumbnail came from choosing alphabetically among
extensions sharing a MIME: `image/x-degas` selected compressed PC1 for uncompressed
PI1 data. The host registry now prefers the actual native extension when it
belongs to that MIME's renderer family. The registry/decoder diagnostic reproduced
the corrupt PC1-hinted output and produced the correct PI1 thumbnail with the
selected `pi1` hint. The existing one-argument registry API remains available;
the resolved-icon cache namespace was bumped to discard prior representations.

Build: `gradle :app:assembleDebug --offline --console=plain` succeeded in 23s;
log: `build/graphics-review/checked-build.log`. Native rendering and PNGs were
inspected; the full listing UI, gestures and mode-toggle display still need
on-device confirmation. No unit tests were added or run for this follow-up.

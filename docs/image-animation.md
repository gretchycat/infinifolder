# Image animation

Implemented November 4, 2026. Decoders live in Canvas; the image viewer owns
playback controls, zoom/pan and Android lifecycle handling. File icons remain
still images. No new codec dependency was added.

| Format | Playback |
| --- | --- |
| FLI / FLC / FLIC | Standard indexed 8-bit Autodesk Animator streams: COLOR_64/COLOR_256, BLACK, COPY, BRUN, byte LC and word SS2 deltas. FLI uses 70 Hz ticks; FLC uses milliseconds and per-frame delay overrides. Incomplete headers can be recovered from complete, consistently sized BRUN rows and validated frame data. Missing timing uses an explicitly reported 70 frames/s fallback. Loops restore the first frame's pixels and palette, excluding a ring frame where the declared frame count identifies one. |
| Animated GIF | Android `AnimatedImageDrawable` on API 28+; Android's legacy `Movie` renderer on API 26–27. The legacy renderer loops continuously. |
| Animated WebP | Android `AnimatedImageDrawable` on API 28+. Older Android falls back to a still image. |
| APNG (including `.png`) | PNG frame decoding uses Android's PNG codec. Canvas handles frame rectangles, SOURCE/OVER blending, BACKGROUND/PREVIOUS disposal, timing and finite/infinite play counts. A default image outside the animation is skipped for playback. |
| MNG | PNG-based streams: up to 256 stored objects, SHOW modes 0–7, MOVE, SAVE/SEEK states, DISC, FRAM modes 1–4, global palettes/color space, BACK color and TERM metadata. Dynamic `evNT` supports pointer enter/move/exit/down/up, rectangular/object/palette-index masks, and in-memory jumps to named states. This remains a subset of MNG. |
| HEIF / AVIF sequences | Native animation is used only if Android actually returns an animated drawable. Otherwise the existing still-image path remains; sequence playback is not guaranteed. |
| IFF ILBM / PBM | Existing [palette cycling](iff-color-cycling.md), using the shared bitmap animation scheduler. |

MNG Delta-PNG/JNG, cloning/pasting, nested LOOP/ENDL,
clipping/synchronization controls and intrapixel differencing are unsupported.
FLIC true-color/DTA/EGI extensions are unsupported. Animated JPEG XL, Aseprite,
SVG/SMIL and multi-page TIFF animation are not implemented by this change.
Unsupported operations produce a decode/playback error or a warning with a
usable still fallback where one exists.

## Playback and limits

- Labeled **Pause animation / Play animation** buttons appear for animated images.
  Each newly opened animation starts automatically and loops continuously in the
  player, including files whose stored play count is finite. That metadata is
  preserved in the source. Slideshow repeat remains a separate setting.
  Bitmap animation retains elapsed time across pause and
  same-source activity recreation; native drawable animation uses Android's own
  playback state and restarts after recreation.
- A moving timeline and time label are shown where a duration exists. FLI/FLC,
  APNG and timed MNG have a seek bar; seeking also updates paused frames, with
  bounded catch-up. Native GIF/WebP have a read-only progress bar based on
  container timing; Android's drawable does not expose seeking or an exact frame
  clock, so it can drift from presentation on slow devices. Independent IFF
  cycle ranges have no single timeline. Native playback restarts on resume.
- Swipe left/right for next/previous at Fit, using Android's gesture detector.
  Zoomed gestures pan; pinching and editing do not change files. Single tap
  reliably shows/hides toolbars; manual navigation at a non-repeating boundary
  stays in the viewer. Automatic slideshow navigation retains its finish behavior.
- Interactive MNG images reserve pointer gestures for their event bindings.
  Touch and drag simulates hover; a mouse uses Android hover events. Pinch still
  zooms, and masks follow the displayed image's matrix and sampling. Use the
  navigation arrows or disable touch input in **States…** to swipe between files.
  The state chooser also provides keyboard/accessibility access to named states.
  Static interactive states have no pretend Play button or idle frame polling;
  states containing timed frames retain playback controls. Format/recovery
  notices remain available in the existing image information dialog.
- Slideshows pause in the background and while editing. They wait for image
  decoding and active gestures, and restore the current image/repeat/play state
  after activity recreation. Manual navigation at a boundary never auto-closes.
- Playback pauses in the background. Navigation and destruction stop the old
  player. Entering Edit freezes the current frame; reopening restores animation.
  Saving an edited image uses the existing still-image export path.
- Animation source reads are bounded to 32 MiB and reused in memory. Existing
  platform still-image decoding remains available for larger files.
- Custom animation canvases are limited to 4 megapixels and 10,000 frames.
  Their worker performs bounded catch-up and checks cancellation. The scheduler
  publishes at most about 30 frames per second, keeping zoom/pan unchanged.
- Header recovery validates at most 64 megapixels of indexed frame work and
  rejects ambiguous geometry. Initial compositing is bounded and must finish
  before publishing its poster. Interactive state metadata is capped at 40,000
  frame/object references. Indexed input masks use Android PNG decoding with an
  identity palette and a four-megapixel LRU; original source bytes stay intact.
- FLIC retains encoded bytes, current indexed pixels and the first-frame state.
  APNG/MNG keep the compositor and an LRU of decoded patches capped at 8 MiB;
  they do not expand the entire animation into full-sized bitmaps.
- MIME associations, signature detection, registry thumbnail hints and generated
  Android Open-with registrations route FLI/FLC/MNG to the image viewer despite
  their conventional `video/*` MIME identifiers. Existing registry settings are
  preserved.

## Original samples

Run `python scripts/generate-animation-fixtures.py` (Pillow required). Files are
created in `filesystem-test-images/images/animation/`:

- `motion-palette.fli` and `.flc`: 320×200, 70 frames, motion and palette changes;
  BRUN first image, COPY at frame 21, BLACK at frame 36, LC/SS2 deltas elsewhere.
  FLC adds a longer delay at frame 51.
- Matching `.gif`, `.webp`, `.apng` and `.mng` samples.
- `stored-objects.mng`: a timed 70-frame sequence using hidden PNG objects,
  SAVE, grouped FRAM and SHOW commands.
- `interactive-regions.mng`: left/right pointer regions, a four-bit indexed
  input mask, and normal/left/right named states.
- `recoverable-header.flc`: complete frame data with deliberately zeroed size,
  count, dimensions and default timing, for the recovery path.
- `transparency-disposal.png`: a finite two-play APNG with translucent patches
  and alternating BACKGROUND/PREVIOUS disposal. The player now loops it by
  default, independently of its stored two-play count.
- `reference-frame-15.png`: still visual reference (FLI's 6-bit palette has
  normal quantization differences).

The local FFmpeg executable could not start because of a libplacebo/C++ runtime
symbol mismatch. Fixture generation uses Pillow and the original container
writers instead.

## Validation

Build: `gradle :app:assembleDebug --offline --console=plain`.
Initial build passed in 2m 24s (`build/image-animation/build.log`); the next build
passed in 29s (`build/image-animation/final.log`). The final build, including
timing/fallback refinements and decode diagnostics, passed in 14s
(`build/image-animation/ready.log`). Root, Canvas and Store diff checks passed.
No automated tests were added or run for this feature. The owner subsequently
confirmed the playback/control follow-up below. Individual format variants,
pause/backgrounding, rotation, zoom retention, still-frame editing and URI
routing have not all been confirmed separately.
The previous IFF color-cycling playback was confirmed by the owner.

### Playback/control follow-up

Read-only comparison of all installed APK DEX entries against the built APK found
that the installed application was older and lacked `AnimatedImages`,
`PngAnimationDecoder` and `ImageAnimationPlayback`. This explains the old
FLI video-player route, missing animation controls/slideshow entries, and MNG
video-player failure. The rebuilt APK contains the new decoder/controller and
timeline/gesture code; the installed-build comparison describes investigation
time, not the owner's subsequent confirmation.

Source inspection of `/sdcard/Download/specular.flc` found a zero file size,
frame count, width and height in its FLC header. Further inspection found
480 complete BRUN rows of 640 pixels and 27 complete frames. The recovery path
now derives dimensions/count from that encoded structure and validates every
recovered frame. Original timing is absent; the fallback is clearly reported.
The source file is never rewritten. Ambiguous or inconsistent files still fail
with a useful error. The generated `motion-palette.flc` has a complete header.
`australia1.mng` contains `evNT` input events, hidden stored PNG objects, SAVE/SEEK,
grouped FRAM and SHOW. Its indexed object masks and named-state transitions now
use the same general dynamic MNG implementation as the generated sample.

The MIME registry applies a one-time, revisioned change enabling content icons
for FLIC/MNG while preserving other fields and subsequent user edits.
The initial controls build found a nullable format-hint type error; it was fixed.
The final `gradle :app:assembleDebug --offline --console=plain` passed in 34s
(`build/image-animation-controls/ready.log`). Root, Canvas and Store diff checks
passed. No new automated tests were added or run. The owner confirmed the
follow-up behavior on November 4, 2026; the confirmation did not enumerate every
format or lifecycle scenario.

### Remaining-problems repair

Added conservative FLC recovery, dynamic MNG pointer/state handling, complete
initial posters, and a decoder revision in generated-icon cache signatures.
Fixed stale paused-seek callbacks, background slideshow advancement, manual
end-of-list closure while a slideshow was active, and activity recreation
returning to the original launch image. Touch state is cancelled on pause;
explicitly chosen MNG states and touch-input preference survive recreation.

The first repair build passed in 1m 36s, the next in 59s, and the next two in 30s
(`build/image-animation-repair/build.log`, `final.log`, `ready.log`, `complete.log`).
The final build, including static-state refinements, passed in 28s
(`build/image-animation-repair/ship.log`). No automated tests were added or run.
The new recovery/interactivity and lifecycle fixes
still need on-device acceptance; the earlier confirmation predates this repair.

## Format references

- [CompuPhase FLIC format specification](https://www.compuphase.com/flic.htm)
- [W3C PNG Third Edition (APNG)](https://www.w3.org/TR/png-3/)
- [PNG development group's MNG 1.0](https://www.libpng.org/pub/mng/spec/mng-1.0-20010209-pdg.html)

The new parsers/compositor are original implementations; no decoder source was
copied from a third-party project.

## FLC provider MIME aliases — 2026-11-04

Canvas already decodes FLI/FLC. Its handler now advertises the historical FLI/FLC/FLIC
MIME aliases under video, image and application categories, using the decoder's
shared metadata. This prevents generic video discovery from taking precedence for
those aliases and supplies Android Open-with registration from the linked catalog.
Host animation detection uses the same set; decoder algorithms and user MIME/icon
settings are unchanged. Main and combined-host debug builds pass. No new automated
suite or device playback check was performed for this quick fix; prior animation
acceptance applies to the earlier revision.

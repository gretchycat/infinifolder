# Image processing through Canvas

Canvas owns the existing Android rotate/flip, source-pixel crop, filtered resize
and PNG compression operations. The image editor calls registered Stream pull
processors through `ImageTransforms.apply` and `ImageEncoding.png`; output
metadata describes the actual pixels or encoded PNG. Android Bitmap/Matrix remain
backend details of this optional Android module. No new image codec or dependency
is required.

## Boundary and ownership

`ImageTransform` carries an orientation change, floating source-pixel crop bounds
or positive resize dimensions. The host maps its crop rectangle through the
inverse display matrix. Canvas retains the previous integer truncation/clamping,
including outside/reversed bounds collapsing to at least one source pixel. The
host retains its existing 1–10000 resize controls and sampled single-frame editing.

A processor borrows its input `BitmapFrame`. A newly allocated output owns its
bitmap; closing it recycles those pixels. A result that is the same Bitmap object
as its input is borrowed, so closing it leaves the input intact. Android may
allocate a copy for a mutable no-op and return an alias for an immutable no-op;
ownership follows the actual result. `takeBitmap()` transfers pixels to the host
without copying; the result handle then closes and must not be reused. The input
owner still controls any borrowed alias's lifetime. Older `BitmapFrame` constructors
and the view's `getCroppedBitmap` compatibility method remain available.

Each transformed frame derives dimensions, pixel configuration and color space
from its actual Bitmap. It preserves source provenance/checksums and warnings,
appends the operation and clears encoded byte/capability/verification claims.
Native transformations check cooperative cancellation before/after the Android
operation; cancelled fresh outputs are recycled without recycling the input.

PNG encoding returns an owned `EncodedImage` ByteSource with positional reads,
independent cursor adapters, exact encoded length, image/png type and actual
width/height. It retains source provenance/warnings, adds the PNG encoding step
and carries no raw bitmap configuration or input filename/verification claim.
`takeBytes()` transfers the byte array to the existing host save path without
another copy. Closing/transferring the source invalidates further reads.

PNG bytes remain fully materialized, matching the existing byte-array publication
boundary. Encoding checks cancellation before/during/after compression and
preserves the original write exception if Android catches it internally. Platform
native cancellation diagnostics can appear in the runtime test log; those tests
require InterruptedIOException and no published partial output.

## Preserved UI and save behavior

The host retains gestures, crop handles/presets, fit/fill, toolbar viewport
rotation, color-filter previews, dialogs, slideshow and file/provider navigation.
The edit menu applies bitmap rotation/flip/crop/resize. The resize dialog retains
its captured image and metadata if slideshow navigation changes the current item
while the dialog is open. Preview controls retain their previous behavior. The current bitmap is exported as a PNG copy through the
same save dialog, original-URI guard and provider publication method. Original
resolution, animation, vectors and metadata are protected by keeping the original
resource and exporting a copy. This checkpoint retains the existing editor's
state/save semantics; a portable editor, richer edit history and streamed PNG
publication remain follow-up work.

## Fixtures and handset checks

Open `filesystem-test-images/images/sample-image-edits.png`. Its four colored
corners are labeled TL/TR/BL/BR. Use copies for edit/export checks:

- Edit → Rotate & Flip: compare each operation with the corresponding
  `expected-image-cw.png`, `expected-image-ccw.png` or flip reference.
- Crop: move/resize the rectangle, change presets and apply near image edges;
  the result should match the selected source region. `expected-image-crop.png`
  is the exact source rectangle (80, 50) to (240, 150) for automated checks.
- Resize: change aspect-ratio lock, shrink/enlarge and check dimensions in ℹ.
- Save As: export, reopen the PNG and check pixels/dimensions; the source must
  remain unchanged. Check existing Save/Discard and back-navigation behavior.
- Repeat opening/editing from `image-edit-checks.zip`, including repeated no-op
  edits and navigation/back during other image loading.

The standard-library generator preserves existing files and records original
hashes in `image-edits-expected.json`; the ZIP contains byte-identical copies.
Reference transforms are generated independently as pixel-array remaps. JVM
checks cover geometry/options and encoded-source contracts. The device helper
`scripts/verify-image-edit-runtime.py` checks actual APK pixel parity, references,
metadata, ownership, PNG bytes/alpha, cancellation and pull composition without
bootstrapping widgets/fonts. Interactive controls and live SAF/network export
remain handset acceptance work.

## Viewport correction after checkpoint 18

The owner reported that an edit left a black surface until a zoom gesture and
that freeform crop handles initially appeared off screen. Operations and saved
results appeared correct. The retained `20261031-170119-218` snapshot shows the
blank viewer; the following snapshot shows intact archive thumbnails. These are
UI observations, not evidence of damaged pixels.

The reset matrix rotated about the image center while its centering calculation
used bounds rotated about the origin. A real Android Matrix reproduction places
the 320×200 fixture entirely outside a 1344×2774 view after a quarter-turn.
Panning also assumed a positive, unrotated matrix scale. The viewport now builds
one complete transform with a consistent origin, centers its actual rotated
bounds and clamps panning using mapped bounds. Bitmap replacement reapplies the
transform even when layout dimensions do not change; zero-size resets defer until
layout. These operations remain host display geometry; Canvas owns pixel edits.

Crop initialization intersects the displayed image with the visible viewport
before insetting it, reserves space for the editing toolbars, and leaves an empty
selection when there is no visible image. Corner and whole-box drags stay within
that visible area. Small images keep valid selections without the previous
100-pixel off-screen fallback. Inverse mapping converts density-scaled drawable
coordinates to actual source pixels before calling Canvas. `ui.image.viewport`
log entries capture reset/crop geometry without logging resource identities.

Repeat rotate, flip, resize and crop without pinching to restore the image.
Check freeform and preset crops in fit/fill, all four handles, toolbar clearance,
dragging against every edge and exported pixels. Repeat from the ZIP fixture.
The owner confirms the correction works as expected after retesting
(2026-10-31). This accepts the reported viewport/crop issues; broader live
SAF/network and export variants remain separate checks.

Sixteen new JVM geometry cases pass; the full gate reports 790 cases, 760 pass
and the same 30 pre-existing failures. `scripts/verify-image-viewport-runtime.py`
loads the packaged helper geometry from the APK and checks real Android matrices,
software-drawn image pixels, rotated pan/crop and density-aware inverse mapping.
It also reproduces the old off-screen formula. It does not instantiate ImageView
or exercise hardware rendering/touch input; the owner's confirmation above
covers the interactive retest.

The checkpoint 19 APK also passes the existing 12 image-edit/PNG, 10 EXIF and
eight PDF native groups: 35 focused runtime groups including the viewport checks.
Full and image-only debug builds pass. Logs are retained as `checkpoint19-*`
under `build/infinistream-migration`.

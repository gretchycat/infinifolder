# IFF palette cycling

The image viewer plays palette animation embedded in `.iff`, `.ilbm` and `.lbm`
files containing FORM ILBM or packed FORM PBM. Canvas owns parsing, decoded
indices and palette rendering; Android viewer code owns playback and controls.
Normal icon thumbnails remain still images.

## Supported cycling

- CRNG contiguous palette ranges, their active/reverse flags and stored rate.
  The legacy DeluxePaint rate-36 inactive marker is respected.
- CCRT ranges with forward/reverse direction and seconds/microseconds timing.
  A CCRT compatibility copy of a CRNG range is not applied twice.
- DRNG noncontiguous register cells, explicit RGB cells and interpolation through
  omitted virtual cells. Unknown/reserved DRNG flags fall back with a warning.
- Multiple ranges, indexed planar ILBM and packed PBM, raw or ByteRun1-compressed
  BODY data. HAM6/HAM8 use their held-color rules; EHB half-bright colors are
  regenerated from the cycled base registers. Mask planes and transparent-index
  opacity remain fixed while palette colors change.

Format fields and timing follow the
[AmigaOS ILBM specification, including DPaint IV DRNG](https://wiki.amigaos.net/wiki/ILBM_IFF_Interleaved_Bitmap).
This implementation is original code in the existing Canvas decoder path.

## Viewer behavior

Active cycling starts automatically. The shared **Pause animation / Play animation**
button below the image navigation controls pauses/resumes it. Its state and elapsed phase are saved
when the activity is recreated for the same source. Backgrounding pauses the
clock; returning resumes it if cycling was enabled.

One worker computes palette updates with at most one frame in flight. It retains
decoded pixel indices, reuses a frame buffer and updates one mutable bitmap only
when its palette changes. Display updates are limited to roughly 30 per second;
phase comes from elapsed time, so busy frames skip ahead without accumulating
queued work. There are no repeated file reads or BODY decompressions. The image
view and its zoom/pan matrix stay in place.

Entering Edit stops playback and freezes the displayed frame. Reopen the source
to resume cycling after editing. PNG copy export saves a still frame; it does
not rewrite IFF cycle chunks. Navigating to another image or destroying the viewer
cancels the old playback worker.

## Limits and fallbacks

The existing custom-decoder input limit is 32 MiB. Cycling retains indices for
at most four megapixels and 256 active ranges; larger/unsupported layouts retain
the existing still-image preview with a warning. Invalid range indices, timing
or cells are ignored with a warning. Inactive/single-color ranges do not animate.

Per-scanline PCHG/SHAM/CTBL/DYCP palettes are unsupported: their presence disables
cycling and reports a partial/static preview. IFF ANIM delta-frame animation,
copper emulation, cycling edits/export and animated file icons are not included.

## Validation

Build command:

```sh
gradle :app:assembleDebug --offline --console=plain
```

The final compatibility build passed in 1m 52s; its log is
`build/iff-color-cycling/compatibility.log`. Tests were not added or run for this
feature. The owner confirmed on-device color-cycling playback on November 4,
2026.

Individual format variants and lifecycle cases remain unverified: check
forward/reverse CRNG and CCRT, DRNG RGB/register cells, independent ranges,
EHB/HAM, mask transparency, compressed BODY, pause/resume/backgrounding, rotation,
navigation, zoom retention and entering Edit. Check static/malformed files and
unsupported scanline palettes retain a usable still preview.

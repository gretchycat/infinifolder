# File handler stub and false-success audit

Recorded 2026-10-31 against InfiniFolder `1487f7a`, following the owner's
confirmation of the RealMedia correction. The architecture description remains
authoritative. This audit records defects for the next corrections.

## Result

Additional placeholder and false-success behavior exists. The packaged APK
reproduces **22 problem cases in 45 observations**: eight unknown audio codecs,
13 unsupported/incomplete image inputs, and one ANSI glyph placeholder.
All **21 valid controls** decode. Unknown-codec WAVE and truncated TGA are
correctly rejected. These are observations of current defects, not 22 independent
bugs or passing conformance tests.

The prior fixed-chord decoder paths remain disabled. The new audio finding is
different: unsupported codec identifiers are ignored and encoded payloads are
interpreted as PCM. The selected bytes are used, but they have not been decoded
according to the declared format.

Evidence: [recorded observations and source revisions](validation/file-handler-stub-audit-2026-10-31.json).
APK SHA-256: `29219a34caa3c8153ec24bf8e9a303be9c4bc8c855f33f9e9e06e6c3a20cb813`.

## 1. Unknown audio codecs produce PCM — active, high priority

Changing only the codec identifier in valid AU, AIFC, CAF, Wave64, RF64, 8SVX,
MAUD and VOC fixtures still returns PCM through **AudioProcessing.decode**, the
Android library composition. In all eight cases its PCM SHA-256 is identical to
the original fixture's PCM: the changed codec identifier has effectively been
ignored. The custom-first legacy adapter runs before the Android backend for
these formats.

| Format | Implementation | Problem |
| --- | --- | --- |
| AU/SND | [SunAuDecoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/SunAuDecoder.kt), line 59 | Unknown encodings fall back to 16-bit big-endian PCM. |
| AIFC | [AiffDecoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/AiffDecoder.kt), lines 78–113 | Unknown compression types enter the ordinary PCM branch. |
| CAF | [CafDecoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/CafDecoder.kt), lines 70–105 | Unknown format IDs fall through to PCM conversion. |
| Wave64/RF64 | [Wave64AndRf64Decoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/Wave64AndRf64Decoder.kt), lines 136–164 | Every format tag other than IEEE float is treated as integer PCM. |
| 8SVX/MAUD | [IffAudioDecoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/IffAudioDecoder.kt), lines 49–73 | Unknown 8SVX compression becomes uncompressed; MAUD compression is discarded. |
| VOC | [VocDecoder.kt](../libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/VocDecoder.kt), lines 44–84 | Packing/codec fields are ignored or reduced to sample width. |

Reject unimplemented codecs and sample representations. Another registered
backend may decode the same source; it must not reinterpret unsupported encoded
data as raw PCM. Preserve known PCM/float, A-law, µ-law and implemented ADPCM
paths with sample comparisons and explicit malformed-input cases.

## 2. ANSI letters use fabricated glyphs — active placeholder

[MetafileAndVectorImageDecoder.kt](../libraries/libinfinicanvas/canvas-android/src/main/java/com/infinifolder/preview/MetafileAndVectorImageDecoder.kt),
line 552, explicitly uses a “deterministic glyph stipple” for ASCII and box drawing.
It derives pixel patterns from bits of the character code instead of reading a
real CP437 glyph. Blocks and shade characters have separate real shape masks.

The native probe renders `A` and verifies that every glyph pixel matches this
stipple formula. This is displayed through the normal `.ans` image path.
Use an actual glyph table/font and validate letters, punctuation and box drawing.
This is separate from the already planned ANSI music parser.

## 3. Unsupported image compression returns blank frames — active

| Format | Source | Native observation |
| --- | --- | --- |
| EXR RLE | [HdrAndScientificImageDecoder.kt](../libraries/libinfinicanvas/canvas-android/src/main/java/com/infinifolder/preview/HdrAndScientificImageDecoder.kt), lines 235–271 | Compression 1 is accepted by the block-layout selector but has no decoding branch. Blocks are skipped and a black 192×192 bitmap is returned. |
| XCF | [LayeredImageDecoder.kt](../libraries/libinfinicanvas/canvas-android/src/main/java/com/infinifolder/preview/LayeredImageDecoder.kt), lines 368–373 | Compression other than 0/1 leaves zero-filled tiles; the compression-2 probe returns a transparent 64×64 bitmap. |
| TIFF | [TiffAndRawImageDecoder.kt](../libraries/libinfinicanvas/canvas-android/src/main/java/com/infinifolder/preview/TiffAndRawImageDecoder.kt), lines 236–286 | Failed/unsupported strip decoding is skipped; zero-filled output is still converted into a bitmap. Unknown compression returns a transparent 192×192 image. |

The probes change the compression flag without re-encoding the payload; these
inputs should be rejected. Source inspection also confirms the missing EXR/XCF
decoding branches. The normal thumbnail/full-viewer dispatch calls these methods,
so a returned blank bitmap can prevent a real alternate decoder from being tried.

Gate unsupported modes before allocating successful output. Implement them through
a real backend when available; preserve existing working compression modes.

## 4. Incomplete image data is padded into successful images — active

Native probes remove most or all pixel data while retaining enough header
information to enter the decoder. The decoder still returns a bitmap:

- EXR missing chunks: black output.
- PSD missing composite pixels: transparent output. The PSB path shares this decoder.
- HDR missing scanlines: transparent output.
- FITS truncated pixel array: one decoded sample with the rest filled in.
- DDS missing pixel/block data: transparent output.
- PCX truncated scanlines: a uniform palette color. DCX shares its PCX reader.
- QOI missing encoded pixels: black output.
- PPM missing raster bytes: transparent output.
- EPSI with an empty declared preview: white output.
- WMF with missing embedded DIB pixels: transparent output.

See the case IDs and dimensions in the evidence JSON. These are ten reproduced
incomplete-data cases in addition to the three unsupported compression cases.
The shared [decodePackBits helper](../libraries/libinfinicanvas/canvas-android/src/main/java/com/infinifolder/preview/ImageDecodeUtils.kt)
also returns a zero-initialized full-length array without proving it decoded all
requested bytes. Review its callers during the correction.

Require complete mandatory pixel data, successful decompression and adequate
row/tile coverage before publishing a normal BitmapFrame. If partial previews
are intentionally supported, expose that condition and its limits in metadata
and UI rather than reporting a complete decode. This also matters for editing
and exporting an image derived from an incomplete input.

## 5. Vendored ALAC has unfinished branches — source confirmed

The bundled Java ALAC implementation has additional stubs:

- [StreamUtils.java](../libraries/libinfinisound/audio-core/src/main/java/com/beatofthedrum/alacdecoder/StreamUtils.java),
  line 151: `stream_eof` always returns zero. The demuxer calls it.
- The same file, line 162: `stream_setpos` always returns `-1`. Its caller has an
  explicit reopen/skip fallback, so this one reports failure rather than success.
- [AlacDecodeUtils.java](../libraries/libinfinisound/audio-core/src/main/java/com/beatofthedrum/alacdecoder/AlacDecodeUtils.java),
  lines 696, 917 and 929: unhandled prediction modes log a FIXME and continue
  toward PCM output without reconstructing the affected channel. Retained sample
  buffers may consequently be used. This is a source-confirmed risk, not a
  reproduced owner-file or malformed-frame failure in this audit.

These are incomplete branches in a real decoder, not evidence that all ALAC
playback is fake. Add focused frame/EOF cases and fail unsupported modes safely,
preserving upstream notices and known working decoding.

## 6. Other shipped scaffolding and limitations

- **Legacy PCM output simulation:** [PcmAudioEngine.kt](../viewer-audio/src/main/java/com/infinifolder/audio/engine/PcmAudioEngine.kt),
  lines 87 and 134–149, reports playback and advances time with no AudioTrack;
  failed/zero writes can also advance frames. The only instantiation found is
  in the JVM diagnostics fixture test. The current player uses MediaPlayer over
  library PCM, so this simulation is not its playback path. Move simulation into
  test support or make hardware/write failure explicit before reusing this class.
- **Inferred file permissions:** [PermissionHelper.kt](../libraries/libinfinistore/store-android/src/main/java/com/infinifolder/storage/PermissionHelper.kt),
  lines 220–243, invents a Unix mode and uses the app UID/GID after stat fails.
  File access checks do not establish actual owner/group/mode. Return unknown or
  label inference. This is conditional metadata behavior, not substituted content.
- **Intentional unsupported results:** the disabled WMA/chiptune compatibility
  objects return null/false; NFS/cloud catalog entries expose their unimplemented
  status; unresolved HFS hardlinks carry extraction errors. These do not claim
  to decode or copy replacement content.
- **Real embedded previews:** ORA/KRA merged PNGs, EPS previews and WMF raster
  records come from the selected input. They are limited preview support, not
  full layer/vector/PostScript interpretation. Their scope must remain explicit.
- **Test hooks:** mounted-partition mocks are assigned only by tests in the
  reviewed tree. Generic icons, visualizer peak decay, MIDI instruments and
  sample-bank auditions are intentional UI/synthesis behavior, not codec fallback
  replacements.

No additional fixed replacement audio/video generator was found in the reviewed
production dispatch paths. PDF uses the actual Android renderer; text, table,
Markdown and EPUB handlers consume their source content. Office and LaTeX/full
PostScript rendering remain planned rather than fabricated document renderers.

## Reproduce and interpret

Run `python scripts/audit-file-handlers.py` after building the full APK and library
JARs in the existing Termux/Android runtime-check environment. It reads named
synthetic fixtures, writes altered copies under ignored build output and runs
headless Bitmap/codec checks against a read-only snapshot of the actual APK.
No output device is opened. It does not modify original fixtures or the APK.

Results and logs are in `build/infinistream-migration/file-handler-audit-*`.
**Exit zero means the audit completed, including reporting defects.** It is not
a regression gate, does not freeze defective behavior as a passing expectation,
and fails if a valid control cannot be decoded or the runtime does not complete.

Scope: first-party dispatch, explicit scaffolding and suspicious success/fallback
branches across Sound, Canvas, Scribe, Video, Store, Vault and host viewers,
plus the identified vendored ALAC branches. This is not complete format
conformance testing, a dependency-wide codec audit, or handset UI acceptance.

Recommended correction order: reject unknown audio codecs; replace ANSI glyph
stipple; reject unsupported image modes/incomplete data; harden ALAC boundaries;
separate legacy output simulation and inferred metadata from actual results.

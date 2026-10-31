# Video formats

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-31.

## Current backend: libVLC

At the owner's request, `libinfinivideo/video-android` now implements video
playback with VideoLAN's pinned `libvlc-all:3.7.7` Android binding. Its packaged
native engine reports 3.0.25 Vetinari. `video-api` exposes backend-neutral
`VideoPlayback` controls/events, scale modes and long millisecond positions.
The app imports library types, with no direct libVLC dependency or player calls.
The library owns native decoding, video/subtitle surfaces and audio focus;
the host owns controls, playlists, repeat/PiP decisions and source leases.
The old Android platform adapter remains available for compatibility.

Local files are borrowed directly. Remote/archive videos now use common sources
through a library-owned loopback range transport: positional sources serve ranges
without downloading preceding bytes; known-length sequential streams reopen and
replay a prefix when seeking. Unknown lengths use sequential chunked HTTP.
The video path performs no full-file staging. Vault's non-extracting view API
avoids its legacy whole-entry extraction fallback, preserving actual native
slices for stored ZIP/filesystems and sequential streams for compressed ZIP.
Inherited format/enclosing-container caches can still be required; compressed
seeks can cost work proportional to the skipped prefix.

Common input metadata preserves logical identity, type and warnings. Output
reports actual discovered dimensions/duration and seekability, without claiming
raw CPU frame bytes. Native readers/transport stop before their provider owner
closes. Stale loads cannot replace a newer selection or reopen a closed activity.
The selected item starts without waiting for a folder listing. File/network
buffering is 300 ms. The Activity shortcut selects the matching video/audio
player and brings the existing instance forward. Input/open/ready/close records
include access mode and logical bytes served for diagnosis.

Fit, aspect-preserving fill, 16:9/4:3, 0.5×–2× speed, seeking, playlist controls,
repeat, operation progress and external opening remain. Embedded subtitle
surfaces are enabled; automatic external/sibling subtitle discovery is disabled.
External subtitle selection, audio/subtitle track menus, direct arbitrary Stream
callbacks, desktop/iOS packaging and encoding remain future work. A backend swap
does not establish app support for every format compiled into libVLC: routing,
container/codec variants and UI behavior must each be checked.

See [libVLC dependency/source-build notes](../libraries/libinfinivideo/docs/libvlc.md)
for the AAR pin, source-built override, notices and shared C++ packaging.

## Handset checks

Original synthetic fixtures are in `filesystem-test-images/video/`:

- `01-avc-aac.mp4`: 12-second moving test pattern and 440 Hz tone.
- `02-vp8-vorbis.webm`: the same duration/pattern/tone with different codecs.
- `03-avc-aac-subtitles.mkv`: the MP4 video/audio with embedded subtitles at
  1–5 seconds and 6–10 seconds; check the Unicode text.
- `90-six-minute-seek.mp4`: silent six-minute pattern; seek to 4:00, 5:00,
  5:50, then back to 0:10. Confirm the moving image resumes at each position.
- `invalid-video.mp4`: deliberately incomplete header; expect failure and
  successful opening of a valid clip afterward.
- `91-six-minute-audio-seek.mp4`: the six-minute video with a 440 Hz AAC tone;
  use this for sound plus seeking. The original 90 fixture is intentionally silent.
- `video-checks.zip`: original fixture copies for archive playback.
- `video-audio-seek-checks.zip`: the new audible six-minute companion.

Check sound/sync, pause/resume, progress dragging and ±10 seconds, all speed
and aspect modes, next/previous/repeat, orientation, PiP and returning from
another app. Switch files quickly and go Back during loading. Check at least
one existing video, ZIP and live remote input. Interactive acceptance of this
backend remains pending.

`scripts/verify-libvlc-runtime.py` checks actual APK native linkage with Sound,
MP4/WebM/MKV tracks/dimensions/duration, embedded subtitle detection, native
software decode, long forward/back seek positions with decoder progress,
pause/speed/resume, malformed input and cleanup. Dummy outputs keep it headless
and silent; it does not verify audible sound, hardware surfaces, subtitle pixels
or PiP. The default runner requires all seven completion markers because libVLC redirects
standard output/error into logcat. Exit zero alone is not treated as success.
Its `--streaming` mode requires eight markers for common-source/native range
playback, bounded reads of a 512 MiB virtual movie, compressed ZIP replay,
unknown-length WebM/MP4, non-extracting container views and the audible companion.
Streaming seek checks use native demux/decoder progress with a dummy audio clock
and video output disabled. Repeat the shortcut, local/remote/ZIP startup, seeking
and actual picture/sound controls on the handset.

## Historical platform-backend inventory (2026-10-27)

The remaining sections record the previous Android VideoView/MediaPlayer
implementation and implementation estimates made before libVLC was selected.
Their platform-codec restrictions and custom-decoder prerequisites are historical,
not a current libVLC capability list. Registered extensions below remain relevant;
additional formats require routing and fixture verification before support claims.

## Implemented

### Containers and extensions routed to `:viewer-video`

Recognized in `MediaTypeDetector.VIDEO_EXTENSIONS` and `MimeRegistry`, staged or streamed via `MediaSourceAdapter`, and played in `VideoPlayerActivity` (supporting aspect-ratio modes `FIT`, `FILL`, `16:9`, `4:3`, playback speed `0.5x`–`2.0x`, repeat modes, folder playlist navigation, and operation-bar progress integration):

- **MPEG-4 Part 14**: `.mp4`, `.m4v` (`video/mp4`) — magic-byte detection supports `ftyp` boxes
- **Matroska**: `.mkv` (`video/x-matroska`) — magic-byte detection supports EBML `1A 45 DF A3`
- **WebM**: `.webm` (`video/webm`) — magic-byte detection distinguishes `webm` doctype in EBML header
- **Audio Video Interleave (AVI)**: `.avi` (`video/x-msvideo`) — magic-byte detection supports `RIFF....AVI `
- **QuickTime Movie**: `.mov` (`video/quicktime`)
- **3GPP**: `.3gp` (`video/3gpp`)
- **MPEG Transport Stream**: `.ts`, `.m2ts` (`video/mp2t`)
- **Ogg Theora / Video**: `.ogv` (`video/ogg`)
- **Windows Media Video**: `.wmv` (`video/x-ms-wmv`) — recognized by extension; playback depends on device OEM codecs
- **Flash Video**: `.flv` (`video/x-flv`) — recognized by extension; MP4-based `.f4v` or OEM extractors required

### Video & audio codecs supported in `:viewer-video`

Before checkpoint 20, `VideoPlayerActivity` used Android's platform `VideoView` / `MediaPlayer`, so supported codecs followed Android platform `MediaCodec` decoders:

- **Video codecs**: H.264 / AVC, H.265 / HEVC, VP8, VP9, AV1 (Android 10+ software / hardware), MPEG-4 Part 2 (Simple Profile), H.263
- **Audio tracks**: AAC, MP3, Opus, Vorbis, FLAC, PCM

---

## Unimplemented

### Difficulty Rating Scale

- **Tier 1 — Trivial**: Extension/MIME registration or Android platform API call (<150 LOC).
- **Tier 2 — Low**: Frame-by-frame palette/RLE animation decoder or subtitle overlay parser (150–500 LOC).
- **Tier 3 — Medium**: Custom `SurfaceView`/`TextureView` + `AudioTrack` A/V sync loop or `MediaCodec` + `MediaMuxer` transcoding pipeline (500–2,000 LOC).
- **Tier 4 — High**: Complex custom DCT/vector-quantization/wavelet video codecs + custom container demuxing (2,000+ LOC).
- **Tier 5 — Very High**: Full multi-codec broadcast/proprietary video engine (requires native FFmpeg / Media3 integration).

### Core Architectural Prerequisites for Custom Video Formats

1. **Software Frame-Clock & `Surface` Blitter (for Retro/Animation Video)**:
   - `VideoPlayerActivity` previously delegated rendering to `android.widget.VideoView` (`MediaPlayer`). Playing retro animation/video formats (`.fli`/`.flc`, `.anim`, `.roq`, `.smk`, `.bik`) requires an alternative `SurfaceView` / `TextureView` frame loop that blits decoded `Bitmap` frames at the file's frame rate while synchronizing optional PCM audio on an `AudioTrack`.
2. **Indexed 8-Bit Frame Accumulator**:
   - Retro animation formats (`.fli`/`.flc`, `.anim`) encode delta changes against the previous indexed 8-bit frame buffer (or two pages ago for Amiga double-buffered `.anim`) and mutate entries in a 256-color palette between frames.
3. **Hardware `MediaCodec` + `MediaMuxer` Pipeline (for Video Creation/Export)**:
   - Creating `.mp4` or `.webm` videos (e.g., exporting slideshows, animated images, or ANSI/retro animations to video) requires feeding `Surface` or `ByteBuffer` frames into `MediaCodec.createEncoderByType("video/avc" | "video/x-vnd.on2.vp9")` and muxing via `android.media.MediaMuxer`.

---

### 1. Gaps in Existing Formats & Platform-Capable Features

| Feature / Format | Extensions | Player Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **`.ts` Extension Disambiguation** | `.ts` | **Tier 1 (Trivial)** | N/A | **Prerequisites**: Check for MPEG-TS `0x47` sync bytes at offsets `0, 188, 376` in `FileMagicDetector` so MPEG Transport Streams are not misclassified as TypeScript (`text/typescript`). |
| **Video Grid Thumbnails** | `.mp4`, `.mkv`, `.webm`, `.mov`, `.3gp` | **Tier 2 (Low)** | N/A | **Prerequisites**: Use `android.media.MediaMetadataRetriever.getFrameAtTime()` inside `ThumbnailDecoder` for video MIME types (with `MediaDataSource` stream/staged-file adapter). |
| **External & Embedded Subtitles** | `.srt`, `.vtt`, `.ass`, `.ssa`, `.sub` | **Tier 2 (Low)** | **Tier 1 (Trivial)** (`.srt`/`.vtt` writer) | **Prerequisites**: Subtitle overlay `TextView` in `VideoPlayerActivity` synchronized with `progressRunnable` (or `MediaPlayer.addTimedTextSource`), auto-discovering sibling `<videoname>.srt`/`.vtt` files in the parent container. |
| **3GPP2 & Flash MP4 Extensions** | `.3g2`, `.3gp2`, `.f4v`, `.m4v` | **Tier 1 (Trivial)** | **Tier 2 (Low)** | **Prerequisites**: Register extensions in `MediaTypeDetector.VIDEO_EXTENSIONS` & `MimeRegistry`. Android `MediaPlayer` already supports 3GPP2 and ISO-BMFF `.f4v`. |

---

### 2. Classic & Retro Animation / Game Video Formats

| Format | Extensions | Player Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Autodesk Animator (FLI / FLC)** | `.fli`, `.flc` | **Tier 2 (Low)** | **Tier 3 (Medium)** | **Prerequisites**: (1) **Software frame-clock blitter**; (2) **Indexed 8-bit frame accumulator** + 256-color palette table; (3) Chunk decoder for `0xF1FA` frames (`COLOR_256`, `COLOR_64`, `BYTE_RUN`, `DELTA_FLI`, `DELTA_FLC`, `BLACK`, `LITERAL`). Creation requires color quantization to 256 colors + frame-to-frame delta/RLE encoding. |
| **Amiga Deluxe Paint IFF ANIM** | `.anim`, `.anm` | **Tier 3 (Medium)** | **Tier 3 (Medium)** | **Prerequisites**: (1) IFF `ILBM` planar-to-chunky decoder (shared with `docs/image-formats.md`); (2) **Double-buffered** planar frame history (XOR/replace deltas apply against frame $N-2$); (3) `ANHD` / `DLTA` vertical byte/word column RLE decoders (methods 5, 7, 8). |
| **Id Software RoQ** | `.roq` | **Tier 3 (Medium)** | **Tier 4 (High)** (vector quantizer) | **Prerequisites**: Software frame-clock + `AudioTrack` (22.05 kHz DPCM audio chunks `0x1020`/`0x1021`) + 2×2 / 4×4 codebook vector-quantization (`0x1002`) and motion-compensated block copy (`0x1001`) decoder. |
| **RAD Game Tools Smacker** | `.smk` | **Tier 3–4 (Medium–High)** | **Tier 4 (High)** | **Prerequisites**: Software frame-clock + `AudioTrack` + 4-tree 16-bit Huffman decoder (`MMap`, `MClr`, `Full`, `Type` trees) + 4×4 2-color/1-color/motion block reconstruction + Smacker DPCM/Huffman audio decoder. |
| **RAD Game Tools Bink** | `.bik`, `.bk2` | **Tier 4–5 (High–Very High)** | **Tier 5 (Very High)** | **Prerequisites**: Custom DCT / wavelet block decoder + 16 block types + Bink audio (DCT/RDFT) decoder. |
| **PlayStation STR & Westwood VQA / Interplay MVE** | `.str`, `.vqa`, `.mve` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: CD-ROM XA sector demuxer + Sony MDEC IDCT macroblock decoder + XA ADPCM audio (`.str`), or vector-quantized codebook + IMA ADPCM decoder (`.vqa`/`.mve`). |

---

### 3. Broadcast, Legacy & High-Bitrate Video Containers

| Format | Extensions | Player Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **MPEG-1 / MPEG-2 Program Streams & DVD VOB** | `.mpg`, `.mpeg`, `.m2v`, `.vob`, `.mod`, `.tod`, `.dat` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: MPEG-PS pack/PES packet demuxer + MPEG-1/2 video decoder (hardware `MediaCodec` `video/mpeg2` where available on Android TV/handsets, or software MPEG-1/2 decoder) + MP2/AC-3 audio decoder. |
| **Windows Media / ASF & RealMedia** | `.wmv`, `.asf`, `.rm`, `.rmvb` | **Tier 5 (Very High)** | **Tier 5 (Very High)** | **Prerequisites**: ASF GUID packet demuxer + VC-1 / WMV7–9 and WMA decoders (or RMF demuxer + RV30/RV40 + Cook/AAC decoders), typically requiring a bundled native FFmpeg / Media3 software decoder module. |
| **Material Exchange Format (Broadcast)** | `.mxf`, `.gxf` | **Tier 4–5 (High–Very High)** | **Tier 5 (Very High)** | **Prerequisites**: SMPTE 377M KLV (Key-Length-Value) partition demuxer + DNxHD / ProRes / MPEG-2 / JPEG 2000 video decoder + multi-channel PCM extractor. |
| **Raw IVF / YUV4MPEG2 Streams** | `.ivf`, `.y4m` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: 32-byte `DKIF` header + 12-byte frame headers feeding Android's built-in `MediaCodec` (`video/x-vnd.on2.vp8`, `vp9`, `av01`) for `.ivf`; or ASCII `YUV4MPEG2` header + planar `YCbCr` → RGB converter for uncompressed `.y4m`. |

# Video formats

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-27. This document tracks video format support across MIME/magic classification (`MediaTypeDetector`, `MimeRegistry`, `FileMagicDetector`) and the standalone/integrated video player (`:viewer-video` / `VideoPlayerActivity`), along with difficulty ratings and prerequisites for implementing players and creators/muxers for currently unsupported formats.

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

Because `VideoPlayerActivity` uses Android's platform `VideoView` / `MediaPlayer`, supported codecs follow Android platform `MediaCodec` decoders:

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
   - `VideoPlayerActivity` currently delegates rendering to `android.widget.VideoView` (`MediaPlayer`). Playing retro animation/video formats (`.fli`/`.flc`, `.anim`, `.roq`, `.smk`, `.bik`) requires an alternative `SurfaceView` / `TextureView` frame loop that blits decoded `Bitmap` frames at the file's frame rate while synchronizing optional PCM audio on an `AudioTrack`.
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

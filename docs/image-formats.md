# Image formats

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Current implementation ownership: image decoders and ThumbnailDecoder now live in the pinned libinfinicanvas/canvas-android module. ImageFormats and icon-selection policy stay in the host. BitmapFrame exposes actual decoded dimensions/pixel format/color space; source-backed decoding uses Stream adapters and retains existing memory/format limits. Desktop/iOS codec portability remains incremental.

Updated 2026-10-27. This document tracks image format support across thumbnail generation (`ThumbnailDecoder`), container/directory icon discovery (`DirectoryIconResolver`), MIME/magic classification (`ImageFormats`, `MimeRegistry`, `FileMagicDetector`), and the standalone/integrated image viewer (`:viewer-image` / `ImageViewerActivity`), along with difficulty ratings and prerequisites for implementing viewers/decoders and creators/encoders for currently unsupported formats.

> Text-based visual media—such as ANSI art (`.ans`, `.idf`, `.xb`, `.pcb`, `.tnd`), ASCII/NFO art (`.asc`, `.nfo`, `.diz`), and PETSCII/ATASCII screens—are categorized under the text renderers in [Text, markup, document, and text-based media formats](text-and-document-formats.md).

## Implemented

### Standard raster formats (Android platform codecs)

Decoded via `ImageDecoder` (API 28+) with `BitmapFactory` fallback and EXIF orientation handling (`ExifInterface`):

- **JPEG / Ultra HDR**: `.jpg`, `.jpeg`, `.jpe`, `.jfif`, `.pjpeg`, `.pjp` (`image/jpeg`)
- **PNG / APNG**: `.png`, `.apng` (`image/png`, `image/apng`) — decodes static images and the first frame of APNG
- **GIF**: `.gif` (`image/gif`) — decodes static GIF and the first frame of animated GIF
- **WebP**: `.webp` (`image/webp`) — lossy, lossless, alpha, and first frame of animated WebP
- **BMP / DIB**: `.bmp`, `.dib` (`image/bmp`)
- **Wireless Bitmap (WBMP)**: `.wbmp` (`image/vnd.wap.wbmp`)
- **Windows Icon & Cursor**: `.ico`, `.cur` (`image/x-icon`)

### Modern high-efficiency & archival raster formats

- **HEIF / HEIC**: `.heic`, `.heif`, `.heics`, `.heifs`, `.hif` (`image/heic`, `image/heif`) via Android platform codecs
- **AVIF**: `.avif`, `.avifs` (`image/avif`) via Android 12+ (API 31+) platform codecs
- **JPEG XL**: `.jxl` (`image/jxl`) via bundled software decoder `io.github.awxkee:jxl-coder`
- **JPEG 2000**: `.jp2`, `.j2k`, `.jpf`, `.jpx`, `.j2c` (`image/jp2`, `image/jpx`) via `Jpeg2000Decoder` (`io.github.michaldvorak-gemalto:jp2-android` / OpenJPEG)
- **TIFF / BigTIFF & DNG**: `.tif`, `.tiff`, `.dng` (`image/tiff`, `image/x-adobe-dng`) via `TiffAndRawImageDecoder` (little/big-endian IFDs, strips and tiles, Uncompressed, PackBits, LZW, Deflate/Zlib, JPEG-in-TIFF, horizontal differencing predictor, RGB/Grayscale/Palette/CMYK, 1/8/16-bit) and Android platform RAW codecs
- **Quite OK Image (QOI) & farbfeld**: `.qoi`, `.ff` (`image/x-qoi`, `image/x-farbfeld`) via `SimpleLosslessImageDecoder`
- **Netpbm & Portable FloatMap**: `.pbm`, `.pgm`, `.ppm`, `.pnm`, `.pam`, `.pfm` (`image/x-portable-*`) via `NetpbmDecoder` (ASCII `P1`–`P3`, binary `P4`–`P6`, `P7` PAM, and `PF`/`Pf` 32-bit float HDR with tone mapping)

### Vector, metafile & terminal art formats

- **SVG / SVGZ**: `.svg`, `.svgz` (`image/svg+xml`) rendered to `Bitmap` at the target display/thumbnail dimensions via `com.caverock:androidsvg-aar`
- **Windows Metafile**: `.wmf`, `.wmz` (`image/wmf`) via `MetafileAndVectorImageDecoder` (supports GZIP `.wmz`, Aldus Placeable headers, and `META_STRETCHDIB` / `META_DIBSTRETCHBLT` / `META_SETDIBTODEV` records)
- **PostScript / Encapsulated PostScript**: `.ps`, `.eps`, `.ept` (`image/x-eps`) via `MetafileAndVectorImageDecoder` and `TiffAndRawImageDecoder` (DOS binary `.ept` embedded TIFF/WMF previews, EPSI `%%BeginPreview` hex bitmaps, and `DisplayImage` DirectClass/PseudoClass raster streams)
- **ANSI Art & DEC Sixel**: `.ans` (`image/x-ansi`) via `MetafileAndVectorImageDecoder` (CP437 8×16 glyph renderer with 16-color, 256-color, and 24-bit TrueColor SGR + `SAUCE00` metadata) and `.sixel`, `.six` (`image/x-sixel`) via `RetroImageDecoder`

### Multi-layer authoring & pixel-art project formats

Decoded via `LayeredImageDecoder`:

- **OpenRaster & Krita**: `.ora`, `.kra` (`image/openraster`, `image/x-krita`) — extracts `mergedimage.png` / `preview.png` / `Thumbnails/thumbnail.png`
- **Adobe Photoshop**: `.psd`, `.psb` (`image/vnd.adobe.photoshop`) — decodes v1 PSD and v2 PSB merged composite image data (Raw & PackBits RLE across Bitmap, Grayscale, Indexed, RGB, and CMYK modes, 8-bit & 16-bit)
- **GIMP XCF**: `.xcf` (`image/x-xcf`) — parses visible layers, tile hierarchies, and uncompressed/RLE 64×64 tiles and composites them onto the canvas
- **Aseprite**: `.ase`, `.aseprite` (`image/x-aseprite`) — parses frame 0 layer, palette (`0x2019`/`0x0004`), and raw/zlib-compressed cel (`0x2005`) chunks across 32-bit RGBA, 16-bit Grayscale, and 8-bit Indexed modes

### Workstation, classic computer & mobile bitmap formats

- **Truevision TGA**: `.tga`, `.icb`, `.vda`, `.vst` (`image/x-tga`) via `TgaDecoder`
- **X11 Bitmaps & Pixmaps**: `.xbm`, `.xpm`, `.xwd` (`image/x-xbitmap`, `image/x-xpixmap`, `image/x-xwindowdump`) via `X11ImageDecoder`
- **ZSoft PCX & multi-page DCX**: `.pcx`, `.dcx` (`image/vnd.zbrush.pcx`, `image/x-dcx`) via `PcxDecoder`
- **Amiga IFF / ILBM / PBM**: `.iff`, `.ilbm`, `.lbm` (`image/x-ilbm`) via `RetroImageDecoder` (1–8 bitplanes, EHB, HAM6/HAM8, 24-bit, ByteRun1 RLE)
- **Atari ST Neochrome & DEGAS**: `.neo`, `.pi1`, `.pi2`, `.pi3`, `.pc1`, `.pc2`, `.pc3` (`image/x-neochrome`, `image/x-degas`) via `RetroImageDecoder`
- **ZX Spectrum & Commodore 64**: `.scr` (`image/x-zx-spectrum-scr`, 256×192 interleaved + attributes) and `.kla`, `.koa` (`image/x-koala`, 160×200 VIC-II multicolor) via `RetroImageDecoder`
- **Apple ICNS, MacPaint & QuickDraw PICT**: `.icns` (`image/icns`), `.pntg`, `.mac` (`image/x-macpaint`), and `.pct`, `.pict` (`image/x-pict`) via `RetroImageDecoder`
- **Sun Raster & Silicon Graphics RGB**: `.ras`, `.sun` (`image/x-cmu-raster`) and `.sgi`, `.rgb`, `.bw`, `.rgba` (`image/x-sgi`, `image/x-rgb`) via `RetroImageDecoder`
- **Palm ImageViewer PDB & Nokia OTA Bitmap**: `.pdb` (`image/x-palm-pdb`) and `.otb` (`image/x-ota-bitmap`) via `RetroImageDecoder`

### HDR, GPU texture, astronomical & medical formats

Decoded via `HdrAndScientificImageDecoder`:

- **Radiance RGBE HDR**: `.hdr` (`image/vnd.radiance`) — adaptive RLE + RGBE exponent conversion + Reinhard tone mapping
- **OpenEXR**: `.exr` (`image/x-exr`) — scanline EXR supporting `NO_COMPRESSION`, `ZIPS`, and `ZIP` (delta predictor + byte de-interleaving) across `HALF` (`fp16`), `FLOAT` (`fp32`), and `UINT` channels
- **DirectDraw Surface & Khronos KTX**: `.dds` (`image/vnd.ms-dds`, uncompressed RGBA/RGB and `DXT1`/`DXT3`/`DXT5` block decompression) and `.ktx` (`image/ktx`, uncompressed `GL_RGBA`/`GL_RGB`)
- **Astronomy FITS**: `.fits`, `.fit`, `.fts` (`image/fits`) — 8/16/32-bit integer and 32/64-bit float 2D/3D arrays with `BZERO`/`BSCALE` and auto min/max normalization
- **Medical DICOM**: `.dcm`, `.dicom` (`image/dicom`) — Explicit/Implicit VR tag parser supporting 8/16-bit monochrome and RGB pixel data plus encapsulated JPEG frames

### Camera RAW formats

Decoded via Android's platform RAW extractor (`ImageDecoder` / `BitmapFactory` / `piex`):

- **Adobe Digital Negative**: `.dng` (`image/x-adobe-dng`)
- **Canon**: `.cr2`
- **Nikon**: `.nef`, `.nrw`
- **Sony**: `.arw`
- **Panasonic**: `.rw2`
- **Olympus**: `.orf`
- **Fujifilm**: `.raf`
- **Pentax**: `.pef`
- **Samsung**: `.srw`

### Viewer, thumbnail, and icon integration

- **Stream-backed decoding**: `ThumbnailDecoder` reads non-direct provider streams (`sftp://`, `smb://`, `ftp://`, `webdav://`, `archive://`) in a single bounded pass (up to 32 MiB) and extracts archive entry extensions from the `path` query parameter.
- **Safety bounds**: Images exceeding 40,000 px on a side or 160 MP total area are rejected during bounds probing to prevent OOM crashes.
- **Directory & container artwork**: `.icon.<ext>`, `.icon`, `cover.<ext>`, and `cover` files inside local, remote, and archive/disk-image containers are automatically decoded as container icons; sibling `.icon-<full filename>.png` files provide per-resource overrides and program-icon caching. See [Resource icons](resource-icons.md).
- **In-viewer editing & creation**: `ImageViewerActivity` supports crop, rotate/flip, color adjustment (brightness, contrast, saturation, grayscale), and resize, currently exporting edits as a lossless `.png` copy.

---

## Unimplemented

### Difficulty Rating Scale

- **Tier 1 — Trivial**: Platform API wiring, magic-byte registration, or simple uncompressed header + pixel loop (<150 LOC).
- **Tier 2 — Low**: RLE / palette / bitplane unpacking and packing in pure Kotlin (150–500 LOC, similar to `TgaDecoder`).
- **Tier 3 — Medium**: Multi-chunk container parsing (IFDs, layers, tiles), basic compression (LZW/Deflate/PackBits), or floating-point tone mapping (500–2,000 LOC).
- **Tier 4 — High**: Complex wavelet/frequency-domain codecs, multi-layer blend/effect compositing, or full vector command interpreters (2,000+ LOC or native library).
- **Tier 5 — Very High**: Full PostScript/PDF-compatible page description interpreters or proprietary undocumented camera/editor formats.

### Core Architectural Prerequisites for Custom Image Formats

1. **Pluggable Custom Codec Dispatch in `ThumbnailDecoder` & `ImageViewerActivity`**:
   - `ThumbnailDecoder` already dispatches `.svg`/`.svgz` and `.tga`/`.icb`/`.vda`/`.vst` before falling back to `ImageDecoder`/`BitmapFactory`. Adding new software decoders requires registering their extensions in `ImageFormats.decodableExtensions`, adding magic headers in `FileMagicDetector`, and routing them through the target-dimension downsampler so large images do not allocate full-resolution `IntArray` buffers when only a thumbnail is requested.
2. **Format-Selectable Export in `ImageViewerActivity`**:
   - Currently `ImageViewerActivity.promptSaveAs` hardcodes `image/png`. Supporting creation/saving of other formats requires a format selector in the Save As dialog (e.g., PNG, JPEG, WebP, TGA, BMP, QOI, PPM, ICO) and a corresponding `Bitmap` → byte-stream encoder registry.
3. **Planar-to-Chunky & Indexed Palette Converter**:
   - Classic computer formats (Amiga `.ilbm`, Atari ST `.pi1`–`.pi3`, EGA `.pcx`) store pixels as interleaved bitplanes rather than packed bytes. A shared bitplane unpacker/packer (1–8 planes + Amiga Hold-And-Modify HAM6/HAM8) unlocks the entire retro bitmap family.
4. **Color-Space & Tone-Mapping Pipeline**:
   - Required for HDR/scientific formats (`.hdr`, `.exr`, `.pfm`, `.fits`) and CMYK `.psd`/`.tiff` files to convert linear float or CMYK channels into displayable sRGB `ARGB_8888` bitmaps.

---

### 1. Gaps in Existing Formats & Platform-Capable Encoders

| Feature / Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Animated GIF / WebP / APNG / HEICS / AVIFS Playback** | `.gif`, `.webp`, `.apng`, `.heics`, `.avifs` | **Tier 2 (Low)** | **Tier 3 (Medium)** (multi-frame muxer) | **Prerequisites**: In `ImageViewerActivity`, allow `ZoomImageView` to host an `AnimatedImageDrawable` (API 28+) when not in edit mode, while retaining a decoded `Bitmap` frame for crop/adjust edits. Creation requires a multi-frame GIF LZW or WebP/APNG chunk assembler. |
| **JPEG & WebP Export in Editor** | `.jpg`, `.webp` | *Already implemented* | **Tier 1 (Trivial)** | **Prerequisites**: Format picker in `ImageViewerActivity` calling `Bitmap.compress(CompressFormat.JPEG / WEBP_LOSSY / WEBP_LOSSLESS, quality, out)`. |
| **TGA / BMP / ICO Creation** | `.tga`, `.bmp`, `.ico` | *Already implemented* | **Tier 1–2 (Trivial–Low)** | **Prerequisites**: Format picker in `ImageViewerActivity` + 18-byte TGA header + BGRA pixel writer, 54-byte BMP DIB writer, or 22-byte ICO directory + PNG/DIB payload wrapper. |
| **HEIC / AVIF / CUR / DNG Magic Detection** | `.heic`, `.avif`, `.cur`, `.dng` | **Tier 1 (Trivial)** | N/A | **Prerequisites**: Check `ftyp` major/compatible brands (`heic`, `heix`, `mif1`, `avif`, `avis`) and TIFF/DNG/CUR headers in `FileMagicDetector.detectFromBytes`. |

---

### 2. Standard, Archival & Simple Lossless Raster Formats

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Quite OK Image (QOI)** | `.qoi` | **Tier 1 (Trivial)** | **Tier 1 (Trivial)** | **Prerequisites**: 14-byte `qoif` header + 64-entry running pixel hash array + 4 opcodes (`QOI_OP_RGB`, `QOI_OP_RGBA`, `QOI_OP_INDEX`, `QOI_OP_DIFF`, `QOI_OP_LUMA`, `QOI_OP_RUN`). Both decoder and encoder fit in ~150 LOC of pure Kotlin with zero dependencies. |
| **Netpbm Family** | `.pbm`, `.pgm`, `.ppm`, `.pnm`, `.pam`, `.pfm` | **Tier 1 (Trivial)** | **Tier 1 (Trivial)** | **Prerequisites**: ASCII whitespace/comment token reader (`P1`–`P3`) and binary header + raw byte/float reader (`P4`–`P7`, `PF`/`Pf`). Creation is a 3-line ASCII header + raw RGB/grayscale byte dump. |
| **X11 Bitmap & Pixmap** | `.xbm`, `.xpm` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: C-syntax `#define` / hex-byte parser for 1-bit `.xbm`; ASCII character-to-color lookup table parser for `.xpm`. |
| **TIFF / BigTIFF** | `.tif`, `.tiff` | **Tier 3 (Medium)** | **Tier 2 (Low)** (uncompressed/Deflate RGB) | **Prerequisites**: Little/big-endian Image File Directory (IFD) tag parser (`49 49 2A 00` / `4D 4D 00 2A` / `2B 00`), strip & tile reader, predictor differencing, and compression codecs (`None`, `PackBits`, `LZW`, `Deflate/Zlib`, `JPEG-in-TIFF`, `CCITT T.4/T.6`), plus CMYK/YCbCr/16-bit-per-channel conversion. Multi-page TIFF also requires a page switcher UI (like `:viewer-pdf`). |
| **Apple Icon Image** | `.icns` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: 8-byte `icns` chunk reader; modern icon slots (`ic07`–`ic14`) embed standard PNG or JPEG 2000 streams, while legacy slots (`il32`/`ih32`/`s8mk`) use simple PackBits-like RLE + alpha masks. |
| **JPEG 2000** | `.jp2`, `.j2k`, `.jpx` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: Discrete Wavelet Transform (CDF 9/7 and 5/3) + EBCOT tier-1/tier-2 arithmetic coder (requires either a port of `JJ2000` or native `OpenJPEG` JNI). |
| **JPEG XR / BPG / FLIF** | `.jxr`, `.wdp`, `.bpg`, `.flif` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: PhotoCore lapped biorthogonal transform (`jxrlib`), HEVC intra-frame decoder (`libbpg`), or MANIAC entropy coder (`flif`). |

---

### 3. Classic & Retro Computer Bitmap Formats

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **ZSoft PCX & DCX** | `.pcx`, `.dcx` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: 128-byte header parser + `0xC0` run-length byte decoder + VGA 256-color trailer palette (`0x0C` marker at `EOF - 769`) or 4-plane EGA row combiner. `.dcx` adds a 1024-byte page offset table in front of multiple `.pcx` streams. |
| **Amiga IFF / ILBM / PBM** | `.iff`, `.ilbm`, `.lbm`, `.ham` | **Tier 2–3 (Low–Medium)** | **Tier 2–3 (Low–Medium)** | **Prerequisites**: (1) IFF chunk parser (`FORM`, `ILBM`/`PBM `, `BMHD`, `CMAP`, `CAMG`, `BODY`); (2) ByteRun1 RLE decompressor; (3) **Planar-to-chunky converter** (1–8 bitplanes, Extra Half-Brite EHB, Hold-And-Modify **HAM6/HAM8**, and 24-bit `ILBM`). Creation requires chunky-to-planar bit slicing + ByteRun1 compression. |
| **Atari ST Neochrome & DEGAS** | `.neo`, `.pi1`–`.pi3`, `.pc1`–`.pc3` | **Tier 2 (Low)** | **Tier 2 (Low)** (requires color quantizer) | **Prerequisites**: 16-color 9/12-bit Atari ST palette converter + 16-bit word-interleaved planar-to-chunky unpacker (low-res 320×200×4, med-res 640×200×2, high-res 640×400×1) + PackBits RLE for `.pc1`–`.pc3`. Creation from arbitrary RGB requires palette quantization to 16/4/2 colors. |
| **ZX Spectrum Screen** | `.scr` | **Tier 1 (Trivial)** | **Tier 2 (Low)** (attribute clash quantizer) | **Prerequisites**: Fixed 6912-byte buffer decoder: 6144 bytes of non-linearly interleaved 256×192 1-bit scanlines + 768 bytes of 32×24 8×8 attribute cells (INK, PAPER, BRIGHT). Creation requires quantizing each 8×8 tile to at most 2 colors from the 15-color Spectrum palette. |
| **Commodore 64 Koala & Art Studio** | `.kla`, `.koa`, `.art` | **Tier 2 (Low)** | **Tier 2 (Low)** (multicolor cell quantizer) | **Prerequisites**: Fixed VIC-II 16-color palette + PRG 2-byte load-address skipper + 160×200 multicolor (2 bits per fat pixel) or 320×200 hi-res bitmap + screen RAM + color RAM cell combiner. |
| **SGI RGB & Sun Raster** | `.sgi`, `.rgb`, `.ras`, `.sun` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: 512-byte SGI header + per-scanline RLE offset table reader, or 32-byte Sun Raster header + byte-level RLE reader. |
| **Apple MacPaint & PICT** | `.pntg`, `.mac`, `.pict`, `.pct` | **Tier 2 (MacPaint)** / **Tier 4 (PICT)** | **Tier 2 (MacPaint)** / **Tier 4 (PICT)** | **Prerequisites**: MacPaint is a fixed 576×720 1-bit PackBits stream (512-byte optional MacBinary header). QuickDraw `.pict` v1/v2 is a full opcode stream combining PackBits pixmaps, vector primitives, regions, and QuickTime compressed image atoms. |

---

### 4. Layered Design & Paint Formats

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **OpenRaster & Krita** | `.ora`, `.kra` | **Tier 2 (Thumbnail/Merged)** / **Tier 4 (Full Layers)** | **Tier 3 (Merged)** / **Tier 4 (Layered)** | **Prerequisites**: ZIP extractor (already in `storage-engine`)! Both `.ora` (`mergedimage.png` / `Thumbnails/thumbnail.png`) and `.kra` (`mergedimage.png` / `preview.png`) store standard PNG composites inside a ZIP container—so **Tier 2 viewer support** only needs to read `mergedimage.png` from the ZIP. Full layer editing requires XML stack parsing (`stack.xml` / `maindoc.xml`) + tile decompression + blend modes. |
| **Adobe Photoshop** | `.psd`, `.psb` | **Tier 3 (Merged Composite)** / **Tier 4 (Layer Tree)** | **Tier 3 (Merged)** / **Tier 5 (Full PSD)** | **Prerequisites**: 26-byte `8BPS` header + Color Mode Data + Image Resources + Layer/Mask section skipper + **Image Data section** reader (which stores a pre-composited full-image planar bitmap compressed with Raw, PackBits RLE, or ZIP) + CMYK/16-bit channel converter. |
| **Aseprite** | `.ase`, `.aseprite` | **Tier 3 (Medium)** | **Tier 3 (Medium)** | **Prerequisites**: 128-byte header + frame/chunk parser (`0x2004` Layer, `0x2005` Cel with zlib-compressed indexed/RGBA pixels, `0x2019` Palette) + normal/multiply/screen cel compositor. |
| **GIMP XCF** | `.xcf`, `.xcf.gz`, `.xcf.bz2` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: Outer Gzip/BZip2 stream wrapper (already in `FileMagicDetector`) + XCF pointer/property parser + 64×64 tile hierarchy reader (RLE or zlib) + GEGL/legacy layer blend mode compositor. |

---

### 5. HDR, Scientific, Medical & GPU Texture Formats

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Radiance RGBE HDR** | `.hdr`, `.pic` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: `#?RADIANCE` ASCII header + `-Y height +X width` resolution line + per-channel adaptive RLE decoder + 4-byte `[R, G, B, Exponent]` → linear float ($v = \text{mantissa} \times 2^{E - 128}$) + Reinhard/ACES filmic tone-mapping + $\gamma = 2.2$ sRGB encoding. |
| **DirectDraw Surface (DDS)** | `.dds` | **Tier 3 (Medium)** | **Tier 3 (Medium)** | **Prerequisites**: 128-byte `DDS ` header + optional `DX10` extended header + uncompressed RGBA/BGRA reader + **S3TC / BC1–BC7 (DXT1/DXT3/DXT5) 4×4 block decompressors**. |
| **Khronos KTX / KTX2 & ASTC** | `.ktx`, `.ktx2`, `.astc` | **Tier 3–4 (Medium–High)** | **Tier 4 (High)** | **Prerequisites**: KTX1/2 container parser + ETC1/ETC2/ASTC/Basis Universal software block decompressor (or OpenGL ES `GLES30` offscreen texture upload + `glReadPixels` readback on Android!). |
| **OpenEXR** | `.exr` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: `0x762F3101` magic + attribute header parser + scanline/tile offset tables + IEEE 754 `half` (16-bit float) converter + compression codecs (`NO_COMPRESSION`, `RLE`, `ZIPS`/`ZIP` with byte-reordering + predictor, `PIZ` wavelet+Huffman, `DWAA`/`DWAB`) + tone mapper. |
| **FITS (Astronomy)** | `.fits`, `.fit`, `.fts` | **Tier 3 (Medium)** | **Tier 2 (Low)** | **Prerequisites**: 2880-byte ASCII header block parser (`BITPIX`, `NAXIS`, `NAXIS1`, `NAXIS2`, `BZERO`, `BSCALE`) + big-endian 8/16/32-bit int or 32/64-bit float array reader + min/max/asinh histogram stretch. |
| **DICOM (Medical)** | `.dcm`, `.dicom` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: 128-byte preamble + `DICM` + Explicit/Implicit VR tag parser + Window Center / Window Width (VOI LUT) 12/16-bit grayscale mapper + encapsulated JPEG / JPEG Lossless / JPEG 2000 / RLE pixel data frame extractor. |

---

### 6. Additional Camera RAW & Vector Metafile Formats

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Canon CR3 & Legacy RAWs** | `.cr3`, `.crw`, `.x3f`, `.3fr`, `.iiq`, `.mrw`, `.gpr` | **Tier 2 (Embedded Preview)** / **Tier 5 (Full Demosaic)** | **Tier 5 (Very High)** | **Prerequisites**: Almost all camera RAW files (including ISOBMFF `.cr3` `PRVW`/`THMB` boxes and CIFF `.crw`) contain a full-size embedded JPEG preview. A **Tier 2 lightweight extractor** locates the embedded JPEG offset/length and passes it to `BitmapFactory`. Full sensor demosaicing requires `LibRaw` JNI. |
| **Windows Metafile (WMF / EMF)** | `.wmf`, `.emf`, `.wmz`, `.emz` | **Tier 3–4 (Medium–High)** | **Tier 3 (Medium)** | **Prerequisites**: GZIP wrapper (`.wmz`/`.emz`) + 16-bit WMF / 32-bit EMF GDI record interpreter mapping `PolyPolygon`, `PatBlt`, `StretchDIBits`, pens, brushes, and fonts onto an Android `Canvas` (`Bitmap`). |
| **PostScript / EPS** | `.ps`, `.eps` | **Tier 2 (DOS EPS Preview)** / **Tier 5 (Full Interpreter)** | **Tier 3 (Simple Vector/Bitmap EPS)** | **Prerequisites**: DOS binary EPS files (`C5 D0 D3 C6` magic) embed a TIFF or WMF preview image at a 30-byte header offset (**Tier 2**). Full `.ps`/`.eps` rendering requires a PostScript stack virtual machine and Type 1/3 font rasterizer. |

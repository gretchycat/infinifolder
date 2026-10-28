#!/usr/bin/env python3
"""
Generates comprehensive image and audio format test fixtures in:
  - filesystem-test-images/images/  (using the InfiniFolder app icon)
  - filesystem-test-images/audio/   (using a synthesized C-major piano chord: C4-E4-G4-C5)
"""

import gzip
import hashlib
import json
import math
import os
import shutil
import struct
import subprocess
import tempfile
import wave
import zipfile
import zlib
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES_DIR = Path(__file__).resolve().parent
IMAGES_DIR = FIXTURES_DIR / "images"
AUDIO_DIR = FIXTURES_DIR / "audio"
ICON_SOURCE = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"


def run_cmd(args, check=True):
    res = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({' '.join(map(str, args))}): {res.stderr.strip()}")
    return res


def get_raw_rgba(src_png: Path, width: int, height: int) -> bytes:
    with tempfile.NamedTemporaryFile(suffix=".rgba", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        run_cmd([
            "magick", str(src_png),
            "-background", "#141814",
            "-alpha", "remove", "-alpha", "off",
            "-resize", f"{width}x{height}!",
            "-depth", "8",
            f"rgba:{tmp_path}"
        ])
        return Path(tmp_path).read_bytes()
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


# ==============================================================================
# IMAGE FORMAT GENERATORS
# ==============================================================================

def write_openraster_and_krita(png_bytes: bytes, w: int, h: int):
    # OpenRaster (.ora)
    ora_path = IMAGES_DIR / "icon.ora"
    stack_xml = f"""<?xml version='1.0' encoding='UTF-8'?>
<image version="0.0.3" w="{w}" h="{h}" xres="72" yres="72">
  <stack>
    <layer name="background" src="data/layer0.png" x="0" y="0" opacity="1.0" visibility="visible" composite-op="svg:src-over"/>
  </stack>
</image>
""".encode("utf-8")
    with zipfile.ZipFile(ora_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("mimetype", b"image/openraster", compress_type=zipfile.ZIP_STORED)
        zf.writestr("stack.xml", stack_xml)
        zf.writestr("mergedimage.png", png_bytes)
        zf.writestr("Thumbnails/thumbnail.png", png_bytes)
        zf.writestr("data/layer0.png", png_bytes)

    # Krita (.kra)
    kra_path = IMAGES_DIR / "icon.kra"
    maindoc_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE DOC PUBLIC '-//KDE//DTD krita 2.0//EN' 'http://www.calligra.org/DTD/krita-2.0.dtd'>
<DOC xmlns="http://www.calligra.org/DTD/krita" syntaxVersion="2.0" editor="Krita">
 <IMAGE name="InfiniFolder Icon" mime="application/x-kra" width="{w}" height="{h}" colorspacename="RGBA" x-res="72" y-res="72">
  <layers/>
 </IMAGE>
</DOC>
""".encode("utf-8")
    with zipfile.ZipFile(kra_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("mimetype", b"application/x-krita", compress_type=zipfile.ZIP_STORED)
        zf.writestr("maindoc.xml", maindoc_xml)
        zf.writestr("mergedimage.png", png_bytes)
        zf.writestr("preview.png", png_bytes)


def write_apple_icns(png_128_bytes: bytes):
    # Apple ICNS container with ic07 (128x128 PNG) chunk
    chunk_type = b"ic07"
    chunk_len = 8 + len(png_128_bytes)
    total_len = 8 + chunk_len
    data = b"icns" + struct.pack(">I", total_len) + chunk_type + struct.pack(">I", chunk_len) + png_128_bytes
    (IMAGES_DIR / "icon.icns").write_bytes(data)


def write_amiga_ilbm(rgba: bytes, w: int, h: int):
    # Amiga IFF ILBM 24-bit (24 bitplanes interleaved per scanline) or 8-bit indexed CMAP
    # Build a clean 32-color (5-bitplane) indexed Amiga ILBM for universal compatibility
    # Quantize to a fixed 32-color palette (5-bit RGB cube + grays)
    palette = []
    for r in (0, 85, 170, 255):
        for g in (0, 128, 255):
            for b in (0, 255):
                if len(palette) < 32:
                    palette.append((r, g, b))
    while len(palette) < 32:
        v = (len(palette) * 8) & 0xFF
        palette.append((v, v, v))

    def nearest(r, g, b):
        best_i = 0
        best_d = 10**9
        for idx, (pr, pg, pb) in enumerate(palette):
            d = (r - pr) ** 2 + (g - pg) ** 2 + (b - pb) ** 2
            if d < best_d:
                best_d = d
                best_i = idx
        return best_i

    nplanes = 5
    row_bytes = ((w + 15) // 16) * 2
    body = bytearray()
    for y in range(h):
        indices = [
            nearest(rgba[(y * w + x) * 4], rgba[(y * w + x) * 4 + 1], rgba[(y * w + x) * 4 + 2])
            for x in range(w)
        ]
        for plane in range(nplanes):
            plane_row = bytearray(row_bytes)
            mask = 1 << plane
            for x, idx in enumerate(indices):
                if idx & mask:
                    plane_row[x >> 3] |= (0x80 >> (x & 7))
            body.extend(plane_row)

    bmhd = struct.pack(
        ">HHhhBBBBHBBhh",
        w, h, 0, 0, nplanes, 0, 0, 0, 0, 10, 11, w, h
    )
    cmap = bytes(c for rgb in palette for c in rgb)

    def iff_chunk(tag: bytes, payload: bytes) -> bytes:
        pad = b"\x00" if (len(payload) & 1) else b""
        return tag + struct.pack(">I", len(payload)) + payload + pad

    chunks = iff_chunk(b"BMHD", bmhd) + iff_chunk(b"CMAP", cmap) + iff_chunk(b"BODY", bytes(body))
    form = b"FORM" + struct.pack(">I", 4 + len(chunks)) + b"ILBM" + chunks
    (IMAGES_DIR / "icon.iff").write_bytes(form)
    (IMAGES_DIR / "icon.ilbm").write_bytes(form)
    (IMAGES_DIR / "icon.lbm").write_bytes(form)


def write_atari_st_formats(src_png: Path):
    # Atari ST Low-Res is 320x200, 16 colors, 4 word-interleaved bitplanes (32000 bytes)
    rgba = get_raw_rgba(src_png, 320, 200)
    # 16-color palette
    pal16 = [
        (20, 24, 20), (40, 80, 40), (76, 175, 80), (129, 199, 132),
        (200, 230, 201), (255, 255, 255), (30, 60, 90), (60, 120, 180),
        (255, 193, 7), (244, 67, 54), (156, 39, 176), (0, 188, 212),
        (121, 85, 72), (96, 125, 139), (66, 66, 66), (158, 158, 158)
    ]

    def nearest16(r, g, b):
        return min(range(16), key=lambda i: (r - pal16[i][0])**2 + (g - pal16[i][1])**2 + (b - pal16[i][2])**2)

    st_pal = []
    for r, g, b in pal16:
        st_word = (((r >> 5) & 7) << 8) | (((g >> 5) & 7) << 4) | ((b >> 5) & 7)
        st_pal.append(st_word)

    screen = bytearray(32000)
    pos = 0
    for y in range(200):
        for group in range(20):  # 20 groups of 16 pixels = 320 pixels
            words = [0, 0, 0, 0]
            for bit in range(16):
                x = group * 16 + bit
                off = (y * 320 + x) * 4
                idx = nearest16(rgba[off], rgba[off + 1], rgba[off + 2])
                for p in range(4):
                    if idx & (1 << p):
                        words[p] |= (1 << (15 - bit))
            for p in range(4):
                struct.pack_into(">H", screen, pos, words[p])
                pos += 2

    # DEGAS .pi1: 2-byte resolution (0 = low res) + 16 big-endian words palette + 32000 bytes screen = 32034 bytes
    pi1 = struct.pack(">H16H", 0, *st_pal) + bytes(screen)
    (IMAGES_DIR / "icon.pi1").write_bytes(pi1)

    # Neochrome .neo: 128-byte header (flag=0, res=0, 16-word palette, filename, etc.) + 32000 bytes screen = 32128 bytes
    neo_hdr = bytearray(128)
    struct.pack_into(">HH16H", neo_hdr, 0, 0, 0, *st_pal)
    neo_hdr[36:48] = b"ICON    .NEO"
    (IMAGES_DIR / "icon.neo").write_bytes(bytes(neo_hdr) + bytes(screen))


def write_zx_spectrum_scr(src_png: Path):
    # ZX Spectrum 256x192 display file: 6144 bytes bitmap + 768 bytes attributes = 6912 bytes
    rgba = get_raw_rgba(src_png, 256, 192)
    scr = bytearray(6912)
    for y in range(192):
        # ZX Spectrum interleaved Y address:
        # bits 0..2 of y -> bits 8..10 of addr; bits 3..5 of y -> bits 5..7; bits 6..7 of y -> bits 11..12
        y_addr = ((y & 0xC0) << 5) | ((y & 0x07) << 8) | ((y & 0x38) << 2)
        for bx in range(32):
            byte_val = 0
            for bit in range(8):
                x = bx * 8 + bit
                off = (y * 256 + x) * 4
                lum = (rgba[off] * 299 + rgba[off + 1] * 587 + rgba[off + 2] * 114) // 1000
                if lum > 96:
                    byte_val |= (0x80 >> bit)
            scr[y_addr | bx] = byte_val
    # Set attributes to bright green ink (4) on black paper (0) -> 0x44
    for i in range(6144, 6912):
        scr[i] = 0x44
    (IMAGES_DIR / "icon.scr").write_bytes(bytes(scr))


def write_c64_koala(src_png: Path):
    # C64 KoalaPainter: 2-byte load addr (0x6000) + 8000 bitmap + 1000 screen RAM + 1000 color RAM + 1 bg = 10003 bytes
    rgba = get_raw_rgba(src_png, 160, 200)
    bitmap = bytearray(8000)
    screen_ram = bytearray([0x5D] * 1000)  # green / light-green
    color_ram = bytearray([0x0F] * 1000)   # light gray
    for cy in range(25):
        for cx in range(40):
            cell_idx = cy * 40 + cx
            for row in range(8):
                y = cy * 8 + row
                b_val = 0
                for px in range(4):
                    x = cx * 4 + px
                    off = (y * 160 + x) * 4
                    lum = (rgba[off] * 299 + rgba[off + 1] * 587 + rgba[off + 2] * 114) // 1000
                    two_bit = min(3, lum // 64)
                    b_val = (b_val << 2) | two_bit
                bitmap[cell_idx * 8 + row] = b_val
    kla = struct.pack("<H", 0x6000) + bytes(bitmap) + bytes(screen_ram) + bytes(color_ram) + b"\x00"
    (IMAGES_DIR / "icon.kla").write_bytes(kla)
    (IMAGES_DIR / "icon.koa").write_bytes(kla)


def write_macpaint(src_png: Path):
    # MacPaint: 512-byte header + 720 scanlines of 72 bytes (576 pixels) PackBits-encoded
    rgba = get_raw_rgba(src_png, 576, 720)
    out = bytearray(512)  # 512-byte header (version 2)
    struct.pack_into(">I", out, 0, 2)
    for y in range(720):
        raw_row = bytearray(72)
        for bx in range(72):
            val = 0
            for bit in range(8):
                x = bx * 8 + bit
                off = (y * 576 + x) * 4
                lum = (rgba[off] * 299 + rgba[off + 1] * 587 + rgba[off + 2] * 114) // 1000
                if lum < 128:  # 1 = black in MacPaint
                    val |= (0x80 >> bit)
            raw_row[bx] = val
        # PackBits literal run of 72 bytes (0x47 followed by 72 bytes)
        out.append(71)
        out.extend(raw_row)
    (IMAGES_DIR / "icon.pntg").write_bytes(bytes(out))
    (IMAGES_DIR / "icon.mac").write_bytes(bytes(out))


def write_gimp_xcf(rgba: bytes, w: int, h: int):
    # Minimal valid GIMP XCF v001 uncompressed RGBA image with 1 layer (64x64 single tile)
    assert w == 64 and h == 64
    out = bytearray()
    out.extend(b"gimp xcf file\x00")
    out.extend(struct.pack(">III", w, h, 0))  # width, height, base_type=0 (RGB)
    # Image properties: PROP_COMPRESSION (17) = 0 (None), PROP_END (0)
    out.extend(struct.pack(">IIB", 17, 1, 0))
    out.extend(struct.pack(">II", 0, 0))
    # Layer pointer placeholder + 0 terminator + Channel pointer 0 terminator
    layer_ptr_pos = len(out)
    out.extend(struct.pack(">III", 0, 0, 0))

    # Layer structure at current offset
    layer_offset = len(out)
    struct.pack_into(">I", out, layer_ptr_pos, layer_offset)
    name_bytes = b"Icon\x00"
    out.extend(struct.pack(">III", w, h, 1))  # type=1 (RGBA)
    out.extend(struct.pack(">I", len(name_bytes)) + name_bytes)
    # Layer properties: PROP_OPACITY (6)=255, PROP_VISIBLE (8)=1, PROP_END (0)
    out.extend(struct.pack(">III", 6, 4, 255))
    out.extend(struct.pack(">III", 8, 4, 1))
    out.extend(struct.pack(">II", 0, 0))
    hierarchy_ptr_pos = len(out)
    out.extend(struct.pack(">II", 0, 0))  # hierarchy_ptr, mask_ptr=0

    # Hierarchy structure
    hierarchy_offset = len(out)
    struct.pack_into(">I", out, hierarchy_ptr_pos, hierarchy_offset)
    level_ptr_pos = len(out)
    out.extend(struct.pack(">IIIII", w, h, 4, 0, 0))  # w, h, bpp=4, level_ptr, 0

    # Level structure (1 tile since 64x64)
    level_offset = len(out)
    struct.pack_into(">I", out, level_ptr_pos + 12, level_offset)
    tile_ptr_pos = len(out)
    out.extend(struct.pack(">IIII", w, h, 0, 0))  # w, h, tile_ptr, 0

    # Tile data (uncompressed RGBA bytes)
    tile_offset = len(out)
    struct.pack_into(">I", out, tile_ptr_pos + 8, tile_offset)
    out.extend(rgba)
    (IMAGES_DIR / "icon.xcf").write_bytes(bytes(out))


def write_khronos_ktx(rgba: bytes, w: int, h: int):
    # Khronos KTX 1.1 uncompressed GL_RGBA8 (0x8058), GL_RGBA (0x1908), GL_UNSIGNED_BYTE (0x1401)
    ident = b"\xabKTX 11\xbb\r\n\x1a\n"
    hdr = struct.pack(
        "<12I",
        0x04030201,  # endianness
        0x1401,      # glType = GL_UNSIGNED_BYTE
        1,           # glTypeSize
        0x1908,      # glFormat = GL_RGBA
        0x8058,      # glInternalFormat = GL_RGBA8
        0x1908,      # glBaseInternalFormat = GL_RGBA
        w, h, 0,     # pixelWidth, pixelHeight, pixelDepth
        0, 1, 1      # numberOfArrayElements, numberOfFaces, numberOfMipmapLevels
    )
    bytes_of_kv = struct.pack("<I", 0)
    image_size = struct.pack("<I", len(rgba))
    (IMAGES_DIR / "icon.ktx").write_bytes(ident + hdr + bytes_of_kv + image_size + rgba)


def write_dicom(rgba: bytes, w: int, h: int):
    # DICOM Part 10 Explicit VR Little Endian RGB image
    rgb = bytes(b for i in range(0, len(rgba), 4) for b in rgba[i:i+3])

    def tag_elem(group: int, elem: int, vr: bytes, val: bytes) -> bytes:
        if len(val) & 1:
            val += b"\x00"
        if vr in (b"OB", b"OW", b"UN", b"SQ"):
            return struct.pack("<HH2sHI", group, elem, vr, 0, len(val)) + val
        return struct.pack("<HH2sH", group, elem, vr, len(val)) + val

    ts_uid = b"1.2.840.10008.1.2.1\x00"  # Explicit VR Little Endian
    meta_body = (
        tag_elem(0x0002, 0x0001, b"OB", b"\x00\x01") +
        tag_elem(0x0002, 0x0002, b"UI", b"1.2.840.10008.5.1.4.1.1.7\x00") +
        tag_elem(0x0002, 0x0003, b"UI", b"1.2.826.0.1.3680043.2.1125.1\x00") +
        tag_elem(0x0002, 0x0010, b"UI", ts_uid)
    )
    meta = tag_elem(0x0002, 0x0000, b"UL", struct.pack("<I", len(meta_body))) + meta_body
    dataset = (
        tag_elem(0x0008, 0x0060, b"CS", b"OT") +
        tag_elem(0x0028, 0x0002, b"US", struct.pack("<H", 3)) +      # SamplesPerPixel = 3
        tag_elem(0x0028, 0x0004, b"CS", b"RGB ") +                   # PhotometricInterpretation
        tag_elem(0x0028, 0x0006, b"US", struct.pack("<H", 0)) +      # PlanarConfiguration = 0
        tag_elem(0x0028, 0x0010, b"US", struct.pack("<H", h)) +      # Rows
        tag_elem(0x0028, 0x0011, b"US", struct.pack("<H", w)) +      # Columns
        tag_elem(0x0028, 0x0100, b"US", struct.pack("<H", 8)) +      # BitsAllocated
        tag_elem(0x0028, 0x0101, b"US", struct.pack("<H", 8)) +      # BitsStored
        tag_elem(0x0028, 0x0102, b"US", struct.pack("<H", 7)) +      # HighBit
        tag_elem(0x0028, 0x0103, b"US", struct.pack("<H", 0)) +      # PixelRepresentation
        tag_elem(0x7FE0, 0x0010, b"OB", rgb)                         # PixelData
    )
    dcm = (b"\x00" * 128) + b"DICM" + meta + dataset
    (IMAGES_DIR / "icon.dcm").write_bytes(dcm)


def write_dng(rgba: bytes, w: int, h: int):
    # Baseline TIFF + DNGVersion (0xC612) tag so Android/piex and TIFF readers recognize it as DNG
    rgb = bytes(b for i in range(0, len(rgba), 4) for b in rgba[i:i+3])
    # 11 IFD entries
    entries = []
    def add_entry(tag, typ, count, val_or_off):
        entries.append(struct.pack("<HHII", tag, typ, count, val_or_off))

    ifd_offset = 8
    num_entries = 11
    data_offset = ifd_offset + 2 + num_entries * 12 + 4
    bps_offset = data_offset
    pixel_offset = bps_offset + 6

    add_entry(254, 4, 1, 0)                      # NewSubfileType = 0 (Full-resolution image)
    add_entry(256, 4, 1, w)                      # ImageWidth
    add_entry(257, 4, 1, h)                      # ImageLength
    add_entry(258, 3, 3, bps_offset)             # BitsPerSample = 8,8,8
    add_entry(259, 3, 1, 1)                      # Compression = 1 (Uncompressed)
    add_entry(262, 3, 1, 2)                      # PhotometricInterpretation = 2 (RGB)
    add_entry(273, 4, 1, pixel_offset)           # StripOffsets
    add_entry(277, 3, 1, 3)                      # SamplesPerPixel = 3
    add_entry(278, 4, 1, h)                      # RowsPerStrip
    add_entry(279, 4, 1, len(rgb))               # StripByteCounts
    add_entry(0xC612, 1, 4, 0x00000401)          # DNGVersion = 1.4.0.0

    header = b"II" + struct.pack("<HIH", 42, ifd_offset, num_entries)
    ifd = b"".join(entries) + struct.pack("<I", 0)
    bps = struct.pack("<HHH", 8, 8, 8)
    (IMAGES_DIR / "icon.dng").write_bytes(header + ifd + bps + rgb)


def write_wmf_and_emf(rgba: bytes, w: int, h: int):
    # Build 24-bit bottom-up DIB for StretchDIBits inside WMF
    row_stride = (w * 3 + 3) & ~3
    dib_pixels = bytearray(row_stride * h)
    for y in range(h):
        src_y = h - 1 - y
        for x in range(w):
            off = (src_y * w + x) * 4
            dst = y * row_stride + x * 3
            dib_pixels[dst] = rgba[off + 2]      # B
            dib_pixels[dst + 1] = rgba[off + 1]  # G
            dib_pixels[dst + 2] = rgba[off]      # R
    bmi = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, len(dib_pixels), 2835, 2835, 0, 0)
    dib = bmi + bytes(dib_pixels)

    # WMF records: SetMapMode(MM_ANISOTROPIC=8), SetWindowOrg(0,0), SetWindowExt(h,w), StretchDIBits, EOF
    rec_mapmode = struct.pack("<IHH", 4, 0x0103, 8)
    rec_winorg = struct.pack("<IHhh", 5, 0x020B, 0, 0)
    rec_winext = struct.pack("<IHhh", 5, 0x020C, h, w)
    # META_STRETCHDIB (0x0F43): size in words, func, rop(uint32=0x00CC0020 SRCCOPY), usage(0), srcH, srcW, srcY, srcX, dstH, dstW, dstY, dstX, dib
    stretch_payload = struct.pack("<IHhhhhhhhh", 0x00CC0020, 0, h, w, 0, 0, h, w, 0, 0) + dib
    if len(stretch_payload) & 1:
        stretch_payload += b"\x00"
    rec_stretch = struct.pack("<IH", 3 + len(stretch_payload) // 2, 0x0F43) + stretch_payload
    rec_eof = struct.pack("<IH", 3, 0x0000)
    wmf_body = rec_mapmode + rec_winorg + rec_winext + rec_stretch + rec_eof
    max_rec = 3 + len(stretch_payload) // 2
    wmf_hdr = struct.pack("<HHHIIH", 1, 9, 0x0300, (18 + len(wmf_body)) // 2, 0, max_rec) + struct.pack("<H", 0)

    # Aldus Placeable WMF header (0x9AC6CDD7, 22 bytes)
    placeable = bytearray(struct.pack("<IHhhhhHIH", 0x9AC6CDD7, 0, 0, 0, w, h, 96, 0, 0))
    chk = 0
    for i in range(0, 20, 2):
        chk ^= struct.unpack_from("<H", placeable, i)[0]
    struct.pack_into("<H", placeable, 20, chk)
    wmf_bytes = bytes(placeable) + wmf_hdr + wmf_body
    (IMAGES_DIR / "icon.wmf").write_bytes(wmf_bytes)
    (IMAGES_DIR / "icon.wmz").write_bytes(gzip.compress(wmf_bytes))


def write_ansi_art_icon(src_png: Path):
    # 64x32 pixel icon rendered as 64x16 rows of CP437 upper-half block (0xDF) with 24-bit ANSI SGR + SAUCE00
    rgba = get_raw_rgba(src_png, 64, 32)
    lines = []
    for row in range(0, 32, 2):
        parts = []
        for x in range(64):
            top = (row * 64 + x) * 4
            bot = ((row + 1) * 64 + x) * 4
            tr, tg, tb = rgba[top], rgba[top + 1], rgba[top + 2]
            br, bg, bb = rgba[bot], rgba[bot + 1], rgba[bot + 2]
            parts.append(f"\x1b[38;2;{tr};{tg};{tb};48;2;{br};{bg};{bb}m".encode("ascii") + b"\xdf")
        parts.append(b"\x1b[0m\r\n")
        lines.append(b"".join(parts))
    body = b"".join(lines)
    # 128-byte SAUCE00 record
    sauce = bytearray(128)
    sauce[0:7] = b"SAUCE00"
    sauce[7:42] = b"InfiniFolder Icon                  "
    sauce[42:62] = b"InfiniFolder        "
    sauce[62:82] = b"Test Fixtures       "
    sauce[82:90] = b"20261027"
    struct.pack_into("<I", sauce, 90, len(body))
    sauce[94] = 1   # DataType = Character
    sauce[95] = 1   # FileType = ANSi
    struct.pack_into("<HH", sauce, 96, 64, 16)  # TInfo1=width(64), TInfo2=height(16)
    sauce[106:128] = b"IBM VGA\x00" + b"\x00" * 14
    (IMAGES_DIR / "icon.ans").write_bytes(body + b"\x1a" + bytes(sauce))


def generate_all_images():
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    # 1. ImageMagick batch conversions from ICON_SOURCE (192x192)
    im_targets = [
        ("icon.png", []),
        ("icon.apng", ["APNG:"]),
        ("icon.jpg", ["-background", "#141814", "-alpha", "remove"]),
        ("icon.jpeg", ["-background", "#141814", "-alpha", "remove"]),
        ("icon.jpe", ["-background", "#141814", "-alpha", "remove"]),
        ("icon.jfif", ["-background", "#141814", "-alpha", "remove"]),
        ("icon.gif", []),
        ("icon.webp", []),
        ("icon.bmp", []),
        ("icon.dib", ["BMP3:"]),
        ("icon.wbmp", ["-monochrome"]),
        ("icon.ico", ["-resize", "64x64"]),
        ("icon.cur", ["-resize", "32x32", "CUR:"]),
        ("icon.heic", []),
        ("icon.heif", ["HEIF:"]),
        ("icon.avif", []),
        ("icon.jxl", []),
        ("icon.tga", []),
        ("icon.icb", ["ICB:"]),
        ("icon.vda", ["VDA:"]),
        ("icon.vst", ["VST:"]),
        ("icon.tif", ["-compress", "LZW"]),
        ("icon.tiff", ["-compress", "Zip"]),
        ("icon.jp2", []),
        ("icon.j2k", ["J2K:"]),
        ("icon.qoi", []),
        ("icon.pbm", ["-monochrome"]),
        ("icon.pgm", ["-colorspace", "Gray"]),
        ("icon.ppm", []),
        ("icon.pnm", []),
        ("icon.pam", []),
        ("icon.pfm", []),
        ("icon.xbm", ["-resize", "64x64", "-monochrome"]),
        ("icon.xpm", ["-resize", "64x64", "-colors", "32"]),
        ("icon.xwd", ["-resize", "64x64"]),
        ("icon.pcx", ["-colors", "256"]),
        ("icon.dcx", ["-colors", "256", "DCX:"]),
        ("icon.psd", []),
        ("icon.psb", ["PSB:"]),
        ("icon.ase", ["ASE:"]),
        ("icon.aseprite", ["ASEPRITE:"]),
        ("icon.hdr", ["HDR:"]),
        ("icon.exr", ["EXR:"]),
        ("icon.dds", ["DDS:"]),
        ("icon.fits", ["-colorspace", "Gray", "FITS:"]),
        ("icon.ras", ["RAS:"]),
        ("icon.sun", ["SUN:"]),
        ("icon.sgi", ["SGI:"]),
        ("icon.rgb", ["SGI:"]),
        ("icon.pct", ["PCT:"]),
        ("icon.pict", ["PICT:"]),
        ("icon.pdb", ["-resize", "64x64", "-monochrome", "PDB:"]),
        ("icon.otb", ["-resize", "64x64", "-monochrome", "OTB:"]),
        ("icon.ff", ["FARBFELD:"]),
        ("icon.sixel", ["-resize", "64x64", "-colors", "16", "SIXEL:"]),
        ("icon.ps", ["PS:"]),
        ("icon.eps", ["EPS:"]),
        ("icon.ept", ["EPT:"]),
    ]

    for filename, extra in im_targets:
        out_path = IMAGES_DIR / filename
        if extra and extra[-1].endswith(":"):
            prefix = extra[-1]
            opts = extra[:-1]
            target_arg = f"{prefix}{out_path}"
        else:
            opts = extra
            target_arg = str(out_path)
        run_cmd(["magick", str(ICON_SOURCE), *opts, target_arg])

    # Clean vector SVG & SVGZ embedding the icon graphic + vector badge frame
    png_128 = IMAGES_DIR / "icon.png"
    import base64
    b64_png = base64.b64encode(ICON_SOURCE.read_bytes()).decode("ascii")
    svg_bytes = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192" width="192" height="192">
  <rect width="192" height="192" rx="32" fill="#141814"/>
  <image href="data:image/png;base64,{b64_png}" width="192" height="192"/>
</svg>
""".encode("utf-8")
    (IMAGES_DIR / "icon.svg").write_bytes(svg_bytes)
    (IMAGES_DIR / "icon.svgz").write_bytes(gzip.compress(svg_bytes))

    # Custom binary image formats
    rgba_64 = get_raw_rgba(ICON_SOURCE, 64, 64)
    write_openraster_and_krita(ICON_SOURCE.read_bytes(), 192, 192)
    write_apple_icns((IMAGES_DIR / "icon.png").read_bytes())
    write_amiga_ilbm(rgba_64, 64, 64)
    write_atari_st_formats(ICON_SOURCE)
    write_zx_spectrum_scr(ICON_SOURCE)
    write_c64_koala(ICON_SOURCE)
    write_macpaint(ICON_SOURCE)
    write_gimp_xcf(rgba_64, 64, 64)
    write_khronos_ktx(rgba_64, 64, 64)
    write_dicom(rgba_64, 64, 64)
    write_dng(rgba_64, 64, 64)
    write_wmf_and_emf(rgba_64, 64, 64)
    write_ansi_art_icon(ICON_SOURCE)


# ==============================================================================
# AUDIO FORMAT GENERATORS (C-MAJOR PIANO CHORD: C4 - E4 - G4 - C5)
# ==============================================================================

def synthesize_piano_note(freq: float, sample_rate: int, duration_s: float) -> list[float]:
    n_samples = int(sample_rate * duration_s)
    harmonics = [
        (1.0, 1.00, 1.8),
        (2.0, 0.55, 2.6),
        (3.0, 0.30, 3.4),
        (4.0, 0.18, 4.5),
        (5.0, 0.10, 5.8),
        (6.0, 0.06, 7.2),
    ]
    inharmonicity = 0.00035
    out = [0.0] * n_samples
    attack_s = 0.008
    for i in range(n_samples):
        t = i / sample_rate
        env_attack = min(1.0, t / attack_s)
        # Release taper in final 80ms
        env_release = min(1.0, (duration_s - t) / 0.08) if (duration_s - t) < 0.08 else 1.0
        val = 0.0
        for h_num, amp, decay in harmonics:
            f_h = freq * h_num * math.sqrt(1.0 + inharmonicity * h_num * h_num)
            if f_h >= sample_rate * 0.45:
                continue
            val += amp * math.exp(-decay * t) * math.sin(2.0 * math.pi * f_h * t)
        # Subtle hammer thump in first 12 ms
        if t < 0.012:
            val += 0.15 * math.exp(-250.0 * t) * math.sin(2.0 * math.pi * (freq * 0.5) * t)
        out[i] = val * env_attack * env_release
    return out


def generate_master_piano_chord_wav(wav_path: Path, sample_rate: int = 44100, duration_s: float = 1.5):
    # C4 (261.63 Hz), E4 (329.63 Hz), G4 (392.00 Hz), C5 (523.25 Hz) with slight stereo spread
    notes = [
        (261.6256, 0.65, 0.35),  # C4 slightly left
        (329.6276, 0.55, 0.45),  # E4 center-left
        (391.9954, 0.45, 0.55),  # G4 center-right
        (523.2511, 0.35, 0.65),  # C5 slightly right
    ]
    n_samples = int(sample_rate * duration_s)
    left = [0.0] * n_samples
    right = [0.0] * n_samples
    for freq, l_gain, r_gain in notes:
        wave_data = synthesize_piano_note(freq, sample_rate, duration_s)
        for i, s in enumerate(wave_data):
            left[i] += s * l_gain
            right[i] += s * r_gain

    peak = max(max(abs(x) for x in left), max(abs(x) for x in right), 1e-6)
    scale = 29000.0 / peak
    pcm = bytearray(n_samples * 4)
    for i in range(n_samples):
        l_int = max(-32768, min(32767, int(left[i] * scale)))
        r_int = max(-32768, min(32767, int(right[i] * scale)))
        struct.pack_into("<hh", pcm, i * 4, l_int, r_int)

    with wave.open(str(wav_path), "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(bytes(pcm))


def write_midi_files():
    # Standard MIDI File (Format 0, 1 track, 480 ticks/quarter, Acoustic Grand Piano playing C4-E4-G4-C5 chord)
    events = bytearray()
    # Tempo 120 BPM (500,000 us/qn)
    events.extend(b"\x00\xFF\x51\x03\x07\xA1\x20")
    # Program Change ch 0 -> 0 (Acoustic Grand Piano)
    events.extend(b"\x00\xC0\x00")
    # Text / Lyric event for .kar compatibility
    events.extend(b"\x00\xFF\x01\x0DC Major Chord")
    # Note On: C4 (60), E4 (64), G4 (67), C5 (72) velocity 100
    for note in (60, 64, 67, 72):
        events.extend(bytes([0x00, 0x90, note, 100]))
    # Hold for 1440 ticks (3 beats = 1.5s at 120 BPM) -> VLQ for 1440 is 0x8B 0x20
    events.extend(bytes([0x8B, 0x20, 0x80, 60, 0]))
    for note in (64, 67, 72):
        events.extend(bytes([0x00, 0x80, note, 0]))
    # End of Track
    events.extend(b"\x00\xFF\x2F\x00")

    mthd = b"MThd" + struct.pack(">IHHH", 6, 0, 1, 480)
    mtrk = b"MTrk" + struct.pack(">I", len(events)) + bytes(events)
    mid_bytes = mthd + mtrk

    (AUDIO_DIR / "c_major_piano.mid").write_bytes(mid_bytes)
    (AUDIO_DIR / "c_major_piano.midi").write_bytes(mid_bytes)
    (AUDIO_DIR / "c_major_piano.kar").write_bytes(mid_bytes)

    # RIFF MIDI (.rmi)
    data_chunk = b"data" + struct.pack("<I", len(mid_bytes)) + mid_bytes
    if len(data_chunk) & 1:
        data_chunk += b"\x00"
    rmi_bytes = b"RIFF" + struct.pack("<I", 4 + len(data_chunk)) + b"RMID" + data_chunk
    (AUDIO_DIR / "c_major_piano.rmi").write_bytes(rmi_bytes)


def get_piano_cycle_8bit(num_samples: int = 256) -> bytes:
    # Single-cycle / looped rich piano-like waveform (fundamental + harmonics 2..6) in signed 8-bit PCM
    # 32 samples per period ensures standard 8363 Hz / period 428 plays exact C-4 (261.63 Hz)
    vals = []
    for i in range(num_samples):
        phase = 2.0 * math.pi * (i % 32) / 32.0
        v = (
            1.00 * math.sin(phase) +
            0.50 * math.sin(2 * phase) +
            0.25 * math.sin(3 * phase) +
            0.12 * math.sin(4 * phase) +
            0.06 * math.sin(5 * phase)
        )
        vals.append(v)
    peak = max(abs(x) for x in vals)
    return bytes((max(-127, min(127, int(x / peak * 110))) & 0xFF) for x in vals)


def write_protracker_mod():
    # Valid 4-channel ProTracker ("M.K.") module playing C-3 (428), E-3 (339), G-3 (285), C-4 (214) on channels 1..4
    sample_pcm = get_piano_cycle_8bit(512)
    mod = bytearray(1084)
    mod[0:20] = b"C Major Piano Chord\x00"
    # Sample 1 header at offset 20 (30 bytes)
    mod[20:42] = b"Grand Piano Cycle     "
    struct.pack_into(">HBBHH", mod, 42, len(sample_pcm) // 2, 0, 64, 0, len(sample_pcm) // 2)
    mod[950] = 1    # Song length = 1 pattern
    mod[951] = 127  # Restart byte
    mod[952] = 0    # Pattern 0 in order table
    mod[1080:1084] = b"M.K."

    # 1 pattern = 64 rows * 4 channels * 4 bytes = 1024 bytes
    pat = bytearray(1024)

    def put_note(row: int, ch: int, per: int, vol: int = 64):
        off = (row * 4 + ch) * 4
        b0 = (1 & 0xF0) | ((per >> 8) & 0x0F)
        b1 = per & 0xFF
        b2 = ((1 & 0x0F) << 4) | 0x0C
        b3 = vol
        pat[off : off + 4] = bytes([b0, b1, b2, b3])

    # 1. Row 0: Play C major chord across all 4 channels (C-3, E-3, G-3, C-4)
    chord_periods = [428, 339, 285, 214]
    for ch, per in enumerate(chord_periods):
        put_note(0, ch, per, 64)

    # Chord taper over rows 8..15
    for row in range(8, 16):
        v = int(64 * (1.0 - (row - 8) / 8.0))
        for ch in range(4):
            off = (row * 4 + ch) * 4
            pat[off + 2] = 0x0C
            pat[off + 3] = v

    # 2. Arpeggio sequence from highest to lowest note on channel 0:
    put_note(16, 0, 214, 64)  # C-4 (highest note)
    put_note(20, 0, 285, 64)  # G-3
    put_note(24, 0, 339, 64)  # E-3
    put_note(28, 0, 428, 64)  # C-3 (lowest note)

    # Arpeggio fade-out over rows 32..60
    for row in range(32, 64):
        v = max(0, int(64 * (1.0 - (row - 32) / 32.0)))
        off = row * 4 * 4
        pat[off + 2] = 0x0C
        pat[off + 3] = v

    (AUDIO_DIR / "c_major_piano.mod").write_bytes(bytes(mod) + bytes(pat) + sample_pcm)


def write_screamtracker_s3m():
    # Valid Scream Tracker 3 (.s3m) module with 1 PCM instrument playing C-4, E-4, G-4, C-5 on channels 0..3
    sample_signed = get_piano_cycle_8bit(512)
    # S3M 8-bit samples are unsigned (0x80 center)
    sample_unsigned = bytes(((b ^ 0x80) & 0xFF) for b in sample_signed)

    hdr = bytearray(96)
    hdr[0:28] = b"C Major Piano Chord\x00" + b"\x00" * 8
    hdr[28] = 0x1A
    hdr[29] = 16  # Type = ST3 module
    struct.pack_into("<HHHHH", hdr, 32, 2, 1, 1, 0, 0x1320)  # ordNum=2, insNum=1, patNum=1, flags=0, cwtv=0x1320
    struct.pack_into("<H", hdr, 42, 2)  # ffi = 2 (unsigned samples)
    hdr[44:48] = b"SCRM"
    hdr[48] = 64   # global volume
    hdr[49] = 6    # initial speed
    hdr[50] = 125  # initial tempo
    hdr[51] = 0xB0 # master volume + stereo
    for i in range(32):
        hdr[64 + i] = i if i < 4 else 255  # 4 active channels

    orders = b"\x00\xFF"  # Order 0 = Pattern 0, Order 1 = 0xFF end
    # Pattern 0 at offset 192: packed S3M pattern
    # Row 0: ch 0 = C-4 (0x40), ch 1 = E-4 (0x44), ch 2 = G-4 (0x47), ch 3 = C-5 (0x50), inst 1, vol 64
    pat_body = bytearray()
    notes = [0x40, 0x44, 0x47, 0x50]
    for ch, note in enumerate(notes):
        pat_body.extend(bytes([ch | 0x20 | 0x40, note, 1, 64]))
    pat_body.append(0)  # end of row 0
    # Rows 1..15 taper
    for row in range(1, 16):
        v = max(0, int(64 * (1.0 - (row - 1) / 15.0)))
        for ch in range(4):
            pat_body.extend(bytes([ch | 0x40, v]))
        pat_body.append(0)
    # Rows 16, 20, 24, 28: Arpeggio highest to lowest (C-5, G-4, E-4, C-4) on channel 0
    arp_notes_s3m = {16: 0x50, 20: 0x47, 24: 0x44, 28: 0x40}
    for row in range(16, 32):
        if row in arp_notes_s3m:
            pat_body.extend(bytes([0 | 0x20 | 0x40, arp_notes_s3m[row], 1, 64]))
        pat_body.append(0)
    # Fade out rows 32..63
    for row in range(32, 64):
        v = max(0, int(64 * (1.0 - (row - 32) / 32.0)))
        pat_body.extend(bytes([0 | 0x40, v]))
        pat_body.append(0)
    pat_chunk = struct.pack("<H", len(pat_body) + 2) + bytes(pat_body)

    ins_para = 0x0007
    pat_para = 0x000C  # offset 192
    smp_offset = (192 + len(pat_chunk) + 15) & ~15
    smp_para = smp_offset >> 4
    ptrs = struct.pack("<HH", ins_para, pat_para)
    pre_pad = bytes(112 - (len(hdr) + len(orders) + len(ptrs)))

    # 80-byte S3M PCM instrument header at offset 112
    ins = bytearray(80)
    ins[0] = 1  # Type = 1 (PCM)
    ins[1:13] = b"piano.wav\x00\x00\x00"
    ins[13] = (smp_para >> 16) & 0xFF
    struct.pack_into("<H", ins, 14, smp_para & 0xFFFF)
    struct.pack_into("<III", ins, 16, len(sample_unsigned), 0, len(sample_unsigned))
    ins[28] = 64   # volume
    ins[30] = 0    # unpacked
    ins[31] = 1    # loop on
    struct.pack_into("<I", ins, 32, 8363)  # C4 speed = 8363 Hz
    ins[48:76] = b"Grand Piano C-Major         "
    ins[76:80] = b"SCRS"

    pat_pad = bytes(smp_offset - (192 + len(pat_chunk)))

    (AUDIO_DIR / "c_major_piano.s3m").write_bytes(
        bytes(hdr) + orders + ptrs + pre_pad + bytes(ins) + pat_chunk + pat_pad + sample_unsigned
    )


def write_fasttracker_xm():
    # Valid FastTracker II (.xm) v1.04 module with 1 instrument playing C-4, E-4, G-4, C-5
    sample_signed = get_piano_cycle_8bit(512)
    # XM sample data is delta-encoded signed 8-bit
    delta_smp = bytearray(len(sample_signed))
    prev = 0
    for i, b in enumerate(sample_signed):
        s = b - 256 if b >= 128 else b
        d = (s - prev) & 0xFF
        delta_smp[i] = d
        prev = s

    xm_hdr = bytearray(60 + 276)
    xm_hdr[0:17] = b"Extended Module: "
    xm_hdr[17:37] = b"C Major Piano Chord "
    xm_hdr[37] = 0x1A
    xm_hdr[38:58] = b"InfiniFolder Fixture"
    struct.pack_into("<HIHHHHHHHH", xm_hdr, 58, 0x0104, 276, 1, 0, 4, 1, 1, 1, 6, 125)
    xm_hdr[80] = 0  # order 0 = pattern 0

    # Pattern 0: 64 rows, 4 channels
    pat_data = bytearray()
    xm_notes = [49, 53, 56, 61]  # C-4=49, E-4=53, G-4=56, C-5=61 in XM (1=C-0)
    for ch in range(4):
        # Packed note: 0x80 | 0x01(note) | 0x02(inst) | 0x04(vol), note, inst=1, vol=0x50 (max vol)
        pat_data.extend(bytes([0x87, xm_notes[ch], 1, 0x50]))
    for row in range(1, 16):
        vol_byte = 0x10 + max(0, int(64 * (1.0 - (row - 1) / 15.0)))
        for _ in range(4):
            pat_data.extend(bytes([0x84, vol_byte]))
    # Rows 16, 20, 24, 28: Arpeggio highest to lowest (C-5, G-4, E-4, C-4) on channel 0
    arp_notes_xm = {16: 61, 20: 56, 24: 53, 28: 49}
    for row in range(16, 32):
        if row in arp_notes_xm:
            pat_data.extend(bytes([0x87, arp_notes_xm[row], 1, 0x50]))
        else:
            pat_data.extend(bytes([0x80]))  # empty row channel 0
        for _ in range(3):
            pat_data.extend(bytes([0x80]))  # empty channels 1..3
    for row in range(32, 64):
        vol_byte = 0x10 + max(0, int(64 * (1.0 - (row - 32) / 32.0)))
        pat_data.extend(bytes([0x84, vol_byte]))
        for _ in range(3):
            pat_data.extend(bytes([0x80]))
    pat_hdr = struct.pack("<IBHH", 9, 0, 64, len(pat_data))

    # Instrument 1 header (263 bytes) + 1 Sample header (40 bytes) + delta sample bytes
    ins_hdr = bytearray(263)
    struct.pack_into("<I", ins_hdr, 0, 263)
    ins_hdr[4:26] = b"Grand Piano           "
    struct.pack_into("<H", ins_hdr, 27, 1)    # 1 sample
    struct.pack_into("<I", ins_hdr, 29, 40)   # sample header size = 40

    smp_hdr = bytearray(40)
    struct.pack_into("<III", smp_hdr, 0, len(delta_smp), 0, len(delta_smp))
    smp_hdr[12] = 64   # volume
    smp_hdr[14] = 1    # forward loop
    smp_hdr[15] = 128  # panning center
    smp_hdr[18:40] = b"Piano Cycle           "

    (AUDIO_DIR / "c_major_piano.xm").write_bytes(
        bytes(xm_hdr) + pat_hdr + bytes(pat_data) + bytes(ins_hdr) + bytes(smp_hdr) + bytes(delta_smp)
    )


def write_impulsetracker_it():
    # Valid Impulse Tracker (.it) v2.14 module with 1 sample playing C-4, E-4, G-4, C-5
    sample_signed = get_piano_cycle_8bit(512)
    hdr = bytearray(192)
    hdr[0:4] = b"IMPM"
    hdr[4:30] = b"C Major Piano Chord\x00" + b"\x00" * 6
    struct.pack_into("<HHHHHHH", hdr, 32, 2, 0, 1, 1, 0x0214, 0x0200, 0x0009)  # ord=2, ins=0, smp=1, pat=1
    hdr[48] = 128  # global vol
    hdr[49] = 80   # mix vol
    hdr[50] = 6    # initial speed
    hdr[51] = 125  # initial tempo
    hdr[52] = 128  # pan sep
    for i in range(64):
        hdr[64 + i] = 32 if i < 4 else 128   # pan center for ch 0..3, disabled for 4..63
        hdr[128 + i] = 64                     # channel volume 64

    orders = b"\x00\xFF"
    smp_offset = 208
    pat_offset = smp_offset + 80
    pat_body = bytearray()
    it_notes = [60, 64, 67, 72]  # C-4=60, E-4=64, G-4=67, C-5=72
    for ch, note in enumerate(it_notes):
        pat_body.extend(bytes([(ch + 1) | 0x80, 0x07, note, 1, 64]))
    pat_body.append(0)
    for row in range(1, 16):
        vol = max(0, int(64 * (1.0 - (row - 1) / 15.0)))
        for ch in range(4):
            pat_body.extend(bytes([(ch + 1) | 0x80, 0x04, vol]))
        pat_body.append(0)
    # Rows 16, 20, 24, 28: Arpeggio highest to lowest (C-5, G-4, E-4, C-4) on channel 0
    arp_notes_it = {16: 72, 20: 67, 24: 64, 28: 60}
    for row in range(16, 32):
        if row in arp_notes_it:
            pat_body.extend(bytes([1 | 0x80, 0x07, arp_notes_it[row], 1, 64]))
        pat_body.append(0)
    for row in range(32, 64):
        vol = max(0, int(64 * (1.0 - (row - 32) / 32.0)))
        pat_body.extend(bytes([1 | 0x80, 0x04, vol]))
        pat_body.append(0)
    pat_hdr = struct.pack("<HH", len(pat_body), 64) + bytes(4) + bytes(pat_body)
    dat_offset = pat_offset + len(pat_hdr)
    ptrs = struct.pack("<II", smp_offset, pat_offset)
    pad0 = bytes(smp_offset - (192 + len(orders) + len(ptrs)))

    smp = bytearray(80)
    smp[0:4] = b"IMPS"
    smp[4:16] = b"piano.wav\x00\x00\x00"
    smp[17] = 64  # global vol
    smp[18] = 0x11  # flags: sample present + loop enabled
    smp[19] = 64  # default vol
    smp[20:46] = b"Grand Piano Cycle         "
    smp[46] = 0x01  # cvt = signed samples
    struct.pack_into("<IIIIII", smp, 48, len(sample_signed), 0, len(sample_signed), 8363, 0, 0)
    struct.pack_into("<I", smp, 72, dat_offset)

    (AUDIO_DIR / "c_major_piano.it").write_bytes(
        bytes(hdr) + orders + ptrs + pad0 + bytes(smp) + pat_hdr + sample_signed
    )


def write_soundfont_and_gus():
    # Minimal valid SoundFont 2 (.sf2) RIFF sfbk containing a C4 piano loop sample and preset 0:0
    sample_16 = bytearray()
    for i in range(256):
        phase = 2.0 * math.pi * (i % 64) / 64.0
        v = int(20000 * (math.sin(phase) + 0.4 * math.sin(2 * phase) + 0.2 * math.sin(3 * phase)))
        sample_16.extend(struct.pack("<h", max(-32768, min(32767, v))))
    sample_16.extend(b"\x00" * (46 * 2))  # 46 mandatory zero-padding samples per SF2 spec

    def chunk(tag: bytes, data: bytes) -> bytes:
        pad = b"\x00" if (len(data) & 1) else b""
        return tag + struct.pack("<I", len(data)) + data + pad

    def list_chunk(list_type: bytes, body: bytes) -> bytes:
        return chunk(b"LIST", list_type + body)

    info_body = (
        chunk(b"ifil", struct.pack("<HH", 2, 1)) +
        chunk(b"isng", b"EMU8000\x00") +
        chunk(b"INAM", b"InfiniFolder C Major Piano\x00")
    )
    sdta_body = chunk(b"smpl", bytes(sample_16))
    # Minimal terminal records in pdta
    phdr = struct.pack("<20sHHHIII", b"Piano\x00", 0, 0, 0, 0, 0, 0) + struct.pack("<20sHHHIII", b"EOP\x00", 0, 0, 1, 0, 0, 0)
    pbag = struct.pack("<HH", 0, 0) + struct.pack("<HH", 1, 0)
    pmod = struct.pack("<HHhHH", 0, 0, 0, 0, 0)
    pgen = struct.pack("<HH", 41, 0) + struct.pack("<HH", 0, 0)  # instrument=0
    inst = struct.pack("<20sH", b"PianoInst\x00", 0) + struct.pack("<20sH", b"EOI\x00", 1)
    ibag = struct.pack("<HH", 0, 0) + struct.pack("<HH", 2, 0)
    imod = struct.pack("<HHhHH", 0, 0, 0, 0, 0)
    igen = struct.pack("<HH", 54, 1) + struct.pack("<HH", 53, 0) + struct.pack("<HH", 0, 0)  # sampleModes=1, sampleID=0
    shdr = (
        struct.pack("<20sIIIIIBbHH", b"PianoC4\x00", 0, 256, 0, 256, 261 * 64, 60, 0, 0, 1) +
        struct.pack("<20sIIIIIBbHH", b"EOS\x00", 0, 0, 0, 0, 0, 0, 0, 0, 0)
    )
    pdta_body = (
        chunk(b"phdr", phdr) + chunk(b"pbag", pbag) + chunk(b"pmod", pmod) + chunk(b"pgen", pgen) +
        chunk(b"inst", inst) + chunk(b"ibag", ibag) + chunk(b"imod", imod) + chunk(b"igen", igen) +
        chunk(b"shdr", shdr)
    )
    sf2 = b"RIFF" + struct.pack("<I", 4 + len(list_chunk(b"INFO", info_body)) + len(list_chunk(b"sdta", sdta_body)) + len(list_chunk(b"pdta", pdta_body))) + b"sfbk" + list_chunk(b"INFO", info_body) + list_chunk(b"sdta", sdta_body) + list_chunk(b"pdta", pdta_body)
    (AUDIO_DIR / "c_major_piano.sf2").write_bytes(sf2)

    # SFZ definition referencing c_major_piano.wav
    sfz_text = """// InfiniFolder C Major Piano SFZ
<region>
sample=c_major_piano.wav
lokey=0 hikey=127
pitch_keycenter=60
"""
    (AUDIO_DIR / "c_major_piano.sfz").write_text(sfz_text, encoding="utf-8")

    # Gravis Ultrasound (.pat) GF1PATCH110 header + 1 instrument + 1 layer + 1 wave + PCM
    pat = bytearray(239)
    pat[0:12] = b"GF1PATCH110\x00"
    pat[12:22] = b"ID#000002\x00"
    pat[22:82] = b"InfiniFolder C Major Piano Patch".ljust(60, b"\x00")
    pat[82] = 1   # 1 instrument
    pat[83] = 14  # voices
    pat[84] = 0   # channels
    struct.pack_into("<HH", pat, 85, 1, 64)  # waveforms=1, master_vol=64
    # Instrument header (63 bytes) + Layer header (47 bytes) + Patch wave header (96 bytes)
    pat_wave_hdr = bytearray(96)
    pat_wave_hdr[0:7] = b"PianoC4"
    struct.pack_into("<IIIH", pat_wave_hdr, 8, len(sample_16), 0, len(sample_16), 44100)
    (AUDIO_DIR / "c_major_piano.pat").write_bytes(bytes(pat) + bytes(pat_wave_hdr) + bytes(sample_16))


def write_chiptune_formats():
    # 1. VGM & VGZ (Video Game Music v1.50 using SN76489 PSG at 3,579,545 Hz playing C4, E4, G4 for 1.5s = 66150 samples)
    vgm_cmds = bytearray()
    # SN76489 divider N = 3579545 / (32 * freq): C4->427 (0x1AB), E4->339 (0x153), G4->285 (0x11D)
    dividers = [(0, 427), (1, 339), (2, 285)]
    for ch, div in dividers:
        vgm_cmds.extend(bytes([0x50, 0x80 | (ch << 5) | (div & 0x0F), (div >> 4) & 0x3F]))
        vgm_cmds.extend(bytes([0x50, 0x90 | (ch << 5) | 0x02]))  # volume attenuation = 2 (loud)
    # Wait 66150 samples (90 NTSC frames of 735 samples via 0x62)
    for _ in range(90):
        vgm_cmds.append(0x62)
    # Silence channels and End of Sound Data (0x66)
    for ch in range(3):
        vgm_cmds.extend(bytes([0x50, 0x90 | (ch << 5) | 0x0F]))
    vgm_cmds.append(0x66)

    vgm_hdr = bytearray(64)
    vgm_hdr[0:4] = b"Vgm "
    struct.pack_into("<IIII", vgm_hdr, 4, (64 + len(vgm_cmds)) - 4, 0x00000150, 3579545, 0)
    struct.pack_into("<II", vgm_hdr, 24, 66150, 0)      # Total # samples = 66150
    struct.pack_into("<I", vgm_hdr, 36, 60)             # Rate = 60 Hz
    struct.pack_into("<HBB", vgm_hdr, 40, 0x0009, 16, 0) # SN76489 feedback/shift
    struct.pack_into("<I", vgm_hdr, 52, 0x0C)           # VGM data offset relative to 0x34 -> 0x40
    vgm_bytes = bytes(vgm_hdr) + bytes(vgm_cmds)
    (AUDIO_DIR / "c_major_piano.vgm").write_bytes(vgm_bytes)
    (AUDIO_DIR / "c_major_piano.vgz").write_bytes(gzip.compress(vgm_bytes))

    # 2. DOSBox Raw OPL v2.0 (.dro) & Id Software (.imf) OPL2 FM stream playing C4, E4, G4 on channels 0, 1, 2
    opl_regs = [
        # Operator setup for channels 0, 1, 2 (modulator: 0x20..0x22, carrier: 0x23..0x25)
        (0x20, 0x21), (0x40, 0x10), (0x60, 0xF2), (0x80, 0x24), (0xE0, 0x00),
        (0x23, 0x01), (0x43, 0x00), (0x63, 0xF2), (0x83, 0x24), (0xE3, 0x00),
        (0x21, 0x21), (0x41, 0x10), (0x61, 0xF2), (0x81, 0x24), (0xE1, 0x00),
        (0x24, 0x01), (0x44, 0x00), (0x64, 0xF2), (0x84, 0x24), (0xE4, 0x00),
        (0x22, 0x21), (0x42, 0x10), (0x62, 0xF2), (0x82, 0x24), (0xE2, 0x00),
        (0x25, 0x01), (0x45, 0x00), (0x65, 0xF2), (0x85, 0x24), (0xE5, 0x00),
        (0xC0, 0x30), (0xC1, 0x30), (0xC2, 0x30),
        # Trigger C4 (F-num 0x157, block 4), E4 (0x1AE, block 4), G4 (0x202, block 4) with Key-On (0x20)
        (0xA0, 0x57), (0xB0, 0x31),
        (0xA1, 0xAE), (0xB1, 0x31),
        (0xA2, 0x02), (0xB2, 0x32),
    ]
    # IMF Type 1 (2-byte length header + [reg, val, delay_lo, delay_hi] at 560 Hz)
    imf_body = bytearray()
    for i, (r, v) in enumerate(opl_regs):
        delay = 840 if i == len(opl_regs) - 1 else 0  # hold 1.5s (840 ticks at 560 Hz) on final note-on
        imf_body.extend(struct.pack("<BBH", r, v, delay))
    for ch in range(3):
        imf_body.extend(struct.pack("<BBH", 0xB0 + ch, 0x00, 0))
    (AUDIO_DIR / "c_major_piano.imf").write_bytes(struct.pack("<H", len(imf_body)) + bytes(imf_body))

    # DRO v2.0
    unique_regs = sorted({r for r, _ in opl_regs} | {0xB0, 0xB1, 0xB2})
    reg_to_idx = {r: idx for idx, r in enumerate(unique_regs)}
    dro_data = bytearray()
    for r, v in opl_regs:
        dro_data.extend(bytes([reg_to_idx[r], v]))
    # Long delay command (code 1): (value + 1) * 256 ms -> 5 * 256 = 1280 ms
    dro_data.extend(bytes([1, 5]))
    for ch in range(3):
        dro_data.extend(bytes([reg_to_idx[0xB0 + ch], 0x00]))
    dro_hdr = b"DBRAWOPL" + struct.pack("<HHIIBBBBBB", 2, 0, len(dro_data) // 2, 1500, 0, 0, 0, 0, 1, len(unique_regs))
    (AUDIO_DIR / "c_major_piano.dro").write_bytes(dro_hdr + bytes(unique_regs) + bytes(dro_data))

    # 3. Atari ST YM5! (Uncompressed YM5!LeOnArD! 50 Hz frame dump for 75 frames = 1.5s)
    n_frames = 75
    ym_hdr = (
        b"YM5!LeOnArD!" +
        struct.pack(">IIHIHHI", n_frames, 1, 0, 2000000, 50, 0, 0) +
        b"C Major Piano Chord\x00InfiniFolder\x00Atari ST YM2149\x00"
    )
    # YM2149 at 2 MHz: period = 2000000 / (16 * freq) -> C4=478 (0x1DE), E4=379 (0x17B), G4=319 (0x13F)
    frame_regs = [0xDE, 0x01, 0x7B, 0x01, 0x3F, 0x01, 0x00, 0x38, 15, 15, 15, 0, 0, 0xFF, 0, 0]
    interleaved = bytearray(n_frames * 16)
    for r in range(16):
        for f in range(n_frames):
            val = frame_regs[r]
            if r in (8, 9, 10):  # fade volume 15 -> 2 across 75 frames
                val = max(2, 15 - (f * 13) // n_frames)
            interleaved[r * n_frames + f] = val
    (AUDIO_DIR / "c_major_piano.ym").write_bytes(ym_hdr + bytes(interleaved) + b"End!")

    # 4. Commodore 64 SID (PSID v2 with 6502 routine at $1000 playing C4, E4, G4 on SID $D400..$D418)
    psid = bytearray(0x7C)
    psid[0:4] = b"PSID"
    struct.pack_into(">HHHHHHHI", psid, 4, 2, 0x7C, 0x0000, 0x1000, 0x1033, 1, 1, 0)
    psid[0x16:0x36] = b"C Major Piano Chord".ljust(32, b"\x00")
    psid[0x36:0x56] = b"InfiniFolder".ljust(32, b"\x00")
    psid[0x56:0x76] = b"2026 InfiniFolder".ljust(32, b"\x00")
    # 6502 code at $1000: sets SID volume=15 ($D418), Voice 1=C4 ($1125), Voice 2=E4 ($159A), Voice 3=G4 ($19B1), pulse+triangle+gate, RTS
    sid_code = bytes([
        0xA9, 0x0F, 0x8D, 0x18, 0xD4,  # LDA #$0F; STA $D418 (master vol 15)
        0xA9, 0x09, 0x8D, 0x05, 0xD4, 0x8D, 0x0C, 0xD4, 0x8D, 0x13, 0xD4,  # AD = $09
        0xA9, 0xA0, 0x8D, 0x06, 0xD4, 0x8D, 0x0D, 0xD4, 0x8D, 0x14, 0xD4,  # SR = $A0
        0xA9, 0x25, 0x8D, 0x00, 0xD4, 0xA9, 0x11, 0x8D, 0x01, 0xD4,        # Voice 1 = C4 ($1125)
        0xA9, 0x9A, 0x8D, 0x07, 0xD4, 0xA9, 0x15, 0x8D, 0x08, 0xD4,        # Voice 2 = E4 ($159A)
        0xA9, 0xB1, 0x8D, 0x0E, 0xD4, 0xA9, 0x19, 0x8D, 0x0F, 0xD4,        # Voice 3 = G4 ($19B1)
        0xA9, 0x11, 0x8D, 0x04, 0xD4, 0x8D, 0x0B, 0xD4, 0x8D, 0x12, 0xD4,  # Gate + Triangle
        0x60,  # RTS ($1033 play routine also points to RTS)
        0x60
    ])
    (AUDIO_DIR / "c_major_piano.sid").write_bytes(bytes(psid) + struct.pack("<H", 0x1000) + sid_code)

    # 5. NES Sound Format (.nsf) with 6502 routine at $8000 playing C4, E4, G4 on Pulse 1, Pulse 2, Triangle
    nsf_hdr = bytearray(128)
    nsf_hdr[0:5] = b"NESM\x1a"
    nsf_hdr[5] = 1   # version 1
    nsf_hdr[6] = 1   # 1 song
    nsf_hdr[7] = 1   # starting song 1
    struct.pack_into("<HHH", nsf_hdr, 8, 0x8000, 0x8000, 0x8027)
    nsf_hdr[14:46] = b"C Major Piano Chord".ljust(32, b"\x00")
    nsf_hdr[46:78] = b"InfiniFolder".ljust(32, b"\x00")
    nsf_hdr[78:110] = b"2026 InfiniFolder".ljust(32, b"\x00")
    struct.pack_into("<H", nsf_hdr, 110, 16666)  # 60 Hz NTSC
    nsf_code = bytes([
        0xA9, 0x07, 0x8D, 0x15, 0x40,  # LDA #$07; STA $4015 (enable Pulse 1, Pulse 2, Triangle)
        0xA9, 0x9C, 0x8D, 0x00, 0x40,  # Pulse 1 duty 50%, vol 12
        0xA9, 0xAB, 0x8D, 0x02, 0x40, 0xA9, 0x01, 0x8D, 0x03, 0x40,  # C4 timer = $01AB (427)
        0xA9, 0x9C, 0x8D, 0x04, 0x40,  # Pulse 2 duty 50%, vol 12
        0xA9, 0x52, 0x8D, 0x06, 0x40, 0xA9, 0x01, 0x8D, 0x07, 0x40,  # E4 timer = $0152 (338)
        0xA9, 0xFF, 0x8D, 0x08, 0x40,  # Triangle linear counter
        0xA9, 0x8E, 0x8D, 0x0A, 0x40, 0xA9, 0x00, 0x8D, 0x0B, 0x40,  # G4 triangle timer = $008E (142)
        0x60, 0x60  # RTS for init & play
    ])
    (AUDIO_DIR / "c_major_piano.nsf").write_bytes(bytes(nsf_hdr) + nsf_code)

    # 6. Super Nintendo SPC700 (.spc) 66048-byte snapshot with BRR piano sample and S-DSP voices 0..2 playing C4, E4, G4
    spc = bytearray(66048)
    spc[0:33] = b"SNES-SPC700 Sound File Data v0.30"
    spc[33:35] = b"\x1a\x1a"
    spc[35] = 26  # ID666 header present
    spc[36] = 30  # version minor 30
    struct.pack_into("<H", spc, 37, 0x0200)  # PC = $0200
    spc[46:78] = b"C Major Piano Chord".ljust(32, b"\x00")
    spc[78:110] = b"InfiniFolder".ljust(32, b"\x00")
    spc[131:163] = b"InfiniFolder Test Fixture".ljust(32, b"\x00")
    spc[169:172] = b"2  "   # 2 seconds before fade
    spc[172:177] = b"500  " # 500 ms fade
    # SPC700 RAM starts at offset 256 (65536 bytes)
    ram_base = 256
    # Put `BRA $FE` (2F FE) infinite idle loop at $0200
    spc[ram_base + 0x0200 : ram_base + 0x0202] = b"\x2F\xFE"
    # Sample directory at $0300: entry 0 -> start $0400, loop $0400
    struct.pack_into("<HH", spc, ram_base + 0x0300, 0x0400, 0x0400)
    # 16-sample BRR block at $0400 (loop + end flags = 0xB3, followed by 8 bytes of triangle/sine wave nibbles)
    spc[ram_base + 0x0400 : ram_base + 0x0409] = bytes([0xB3, 0x24, 0x67, 0x76, 0x42, 0xEC, 0x98, 0x89, 0xCE])
    # S-DSP registers (128 bytes at offset 65792)
    dsp_base = 65792
    pitches = [0x085F, 0x0A8C, 0x0C8B]  # C4, E4, G4 relative to 16-sample loop at 32 kHz
    for v, p in enumerate(pitches):
        v_off = dsp_base + v * 16
        spc[v_off + 0] = 64              # VOL L
        spc[v_off + 1] = 64              # VOL R
        spc[v_off + 2] = p & 0xFF        # PITCH L
        spc[v_off + 3] = (p >> 8) & 0x3F # PITCH H
        spc[v_off + 4] = 0               # SRCN = 0
        spc[v_off + 5] = 0xFF            # ADSR1 (enabled, fast attack, slow decay)
        spc[v_off + 6] = 0xE8            # ADSR2 (sustain level 7, slow release)
    spc[dsp_base + 0x0C] = 100   # MVOLL
    spc[dsp_base + 0x1C] = 100   # MVOLR
    spc[dsp_base + 0x4C] = 0x07  # KON voices 0, 1, 2
    spc[dsp_base + 0x5D] = 0x03  # DIR = $0300
    spc[dsp_base + 0x6C] = 0x20  # FLG = unmute, disable echo write
    (AUDIO_DIR / "c_major_piano.spc").write_bytes(bytes(spc))


def write_dsd_files():
    # Generate 1.5s DSD64 (2,822,400 Hz 1-bit delta-sigma modulated) C-major chord in .dsf and .dff
    # from the master stereo piano chord WAV
    master_wav = AUDIO_DIR / "c_major_piano.wav"
    if not master_wav.exists():
        generate_master_piano_chord_wav(master_wav)

    wav = wave.open(str(master_wav), "rb")
    n_frames = wav.getnframes()
    raw_frames = np.frombuffer(wav.readframes(n_frames), dtype=np.int16)
    left_pcm = raw_frames[0::2].astype(np.float32) / 32768.0
    right_pcm = raw_frames[1::2].astype(np.float32) / 32768.0

    dsd_rate = 2822400
    oversample = 64
    n_out = len(left_pcm) * oversample

    def modulate(pcm_samples):
        upsampled = np.interp(np.linspace(0, len(pcm_samples)-1, n_out), np.arange(len(pcm_samples)), pcm_samples) * 0.70
        bits = np.zeros(n_out, dtype=np.uint8)
        acc = 0.0
        for i in range(n_out):
            x = upsampled[i]
            if x >= acc:
                bits[i] = 1
                acc += (1.0 - x) * 0.70
            else:
                bits[i] = 0
                acc -= (1.0 + x) * 0.70
        return bits

    left_bits = modulate(left_pcm)
    right_bits = modulate(right_pcm)

    n_bytes_per_ch = n_out // 8
    n_blocks = n_bytes_per_ch // 4096
    n_bytes_aligned = n_blocks * 4096

    left_msb = np.packbits(left_bits[:n_bytes_aligned * 8].reshape(-1, 8), axis=1).flatten()
    right_msb = np.packbits(right_bits[:n_bytes_aligned * 8].reshape(-1, 8), axis=1).flatten()

    bit_rev = np.array([int(f"{b:08b}"[::-1], 2) for b in range(256)], dtype=np.uint8)
    left_lsb = bit_rev[left_msb]
    right_lsb = bit_rev[right_msb]

    # Philips DSDIFF (.dff): stereo interleaved byte by byte (L0, R0, L1, R1, ...) with MSB bit order
    dff_data = np.empty(n_bytes_aligned * 2, dtype=np.uint8)
    dff_data[0::2] = left_msb
    dff_data[1::2] = right_msb

    def dff_ck(id4: bytes, payload: bytes) -> bytes:
        return id4 + struct.pack(">Q", len(payload)) + payload

    prop_snd = (
        b"SND " +
        dff_ck(b"FS  ", struct.pack(">I", dsd_rate)) +
        dff_ck(b"CHNL", struct.pack(">H4s4s", 2, b"SLFT", b"SRGT")) +
        dff_ck(b"CMPR", b"DSD \x0enot compressed")
    )
    frm8_body = (
        b"DSD " +
        dff_ck(b"FVER", struct.pack(">I", 0x01050000)) +
        dff_ck(b"PROP", prop_snd) +
        dff_ck(b"DSD ", dff_data.tobytes())
    )
    (AUDIO_DIR / "c_major_piano.dff").write_bytes(dff_ck(b"FRM8", frm8_body))

    # Sony DSF (.dsf): stereo blocked by 4096 bytes per channel with LSB bit order
    dsf_blocks = []
    for b in range(n_blocks):
        dsf_blocks.append(left_lsb[b * 4096 : (b + 1) * 4096].tobytes())
        dsf_blocks.append(right_lsb[b * 4096 : (b + 1) * 4096].tobytes())
    dsf_data = b"".join(dsf_blocks)

    total_dsf_size = 28 + 52 + 12 + len(dsf_data)
    dsd_chunk = b"DSD " + struct.pack("<QQQ", 28, total_dsf_size, 0)
    fmt_chunk = b"fmt " + struct.pack("<QIIIIIIQII", 52, 1, 0, 2, 2, dsd_rate, 1, n_bytes_aligned * 8, 4096, 0)
    data_chunk = b"data" + struct.pack("<Q", 12 + len(dsf_data)) + dsf_data
    (AUDIO_DIR / "c_major_piano.dsf").write_bytes(dsd_chunk + fmt_chunk + data_chunk)


def write_cdda_file():
    # Raw CDDA Red Book audio: 44.1 kHz, 16-bit signed stereo PCM, 2352 bytes/sector
    master_wav = AUDIO_DIR / "c_major_piano.wav"
    if not master_wav.exists():
        generate_master_piano_chord_wav(master_wav)
    wav = wave.open(str(master_wav), "rb")
    n_frames = wav.getnframes()
    raw_frames = wav.readframes(n_frames)
    sector_size = 2352
    n_sectors = len(raw_frames) // sector_size
    cdda_data = raw_frames[: n_sectors * sector_size]
    (AUDIO_DIR / "c_major_piano.cdda").write_bytes(cdda_data)
    (AUDIO_DIR / "c_major_piano.bin").write_bytes(cdda_data)


def write_ansi_music_and_playlists():
    # ANSI file containing both ANSI art header and ANSI Music (`ESC[M...` + Ctrl-N) playing C-major chord arpeggio
    ansi_music = (
        "\x1b[0;1;32m╔══════════════════════════════════════════════════════╗\r\n"
        "║  InfiniFolder ANSI Music Fixture: C Major Piano      ║\r\n"
        "║  Notes: C4 - E4 - G4 - C5 (120 BPM)                  ║\r\n"
        "╚══════════════════════════════════════════════════════╝\x1b[0m\r\n"
        "\x1b[MNT120O4L8CEG>C4<GEC1\x0e\r\n"
    ).encode("utf-8")
    (AUDIO_DIR / "c_major_piano_ansi_music.ans").write_bytes(ansi_music)

    # Playlists (.m3u, .m3u8, .pls, .xspf) and Cue Sheet (.cue) in dedicated playlists directory
    playlists_dir = FIXTURES_DIR / "playlists"
    playlists_dir.mkdir(parents=True, exist_ok=True)

    m3u_content = """#EXTM3U
#EXTINF:2,InfiniFolder - C Major Piano Chord (WAV)
../audio/c_major_piano.wav
#EXTINF:2,InfiniFolder - C Major Piano Chord (FLAC)
../audio/c_major_piano.flac
#EXTINF:2,InfiniFolder - C Major Piano Chord (MP3)
../audio/c_major_piano.mp3
#EXTINF:2,InfiniFolder - C Major Piano Chord (OGG)
../audio/c_major_piano.ogg
#EXTINF:2,InfiniFolder - C Major Piano Chord (Opus)
../audio/c_major_piano.opus
#EXTINF:2,InfiniFolder - C Major Piano Chord (AAC M4A)
../audio/c_major_piano.m4a
#EXTINF:2,InfiniFolder - C Major Piano Chord (ALAC)
../audio/c_major_piano.alac
#EXTINF:2,InfiniFolder - C Major Piano Chord (MP2)
../audio/c_major_piano.mp2
#EXTINF:2,InfiniFolder - C Major Piano Chord (ProTracker MOD)
../audio/c_major_piano.mod
#EXTINF:2,InfiniFolder - C Major Piano Chord (ScreamTracker S3M)
../audio/c_major_piano.s3m
#EXTINF:2,InfiniFolder - C Major Piano Chord (FastTracker XM)
../audio/c_major_piano.xm
#EXTINF:2,InfiniFolder - C Major Piano Chord (ImpulseTracker IT)
../audio/c_major_piano.it
#EXTINF:2,InfiniFolder - C Major Piano Chord (MIDI)
../audio/c_major_piano.mid
#EXTINF:2,InfiniFolder - C Major Piano Chord (WMA)
../audio/c_major_piano.wma
"""
    (playlists_dir / "c_major_piano.m3u").write_text(m3u_content, encoding="utf-8")
    (playlists_dir / "c_major_piano.m3u8").write_text(m3u_content, encoding="utf-8")

    pls_content = """[playlist]
File1=../audio/c_major_piano.wav
Title1=InfiniFolder - C Major Piano Chord (WAV)
Length1=2
File2=../audio/c_major_piano.flac
Title2=InfiniFolder - C Major Piano Chord (FLAC)
Length2=2
File3=../audio/c_major_piano.mp3
Title3=InfiniFolder - C Major Piano Chord (MP3)
Length3=2
File4=../audio/c_major_piano.alac
Title4=InfiniFolder - C Major Piano Chord (ALAC)
Length4=2
File5=../audio/c_major_piano.mp2
Title5=InfiniFolder - C Major Piano Chord (MP2)
Length5=2
File6=../audio/c_major_piano.mod
Title6=InfiniFolder - C Major Piano Chord (MOD)
Length6=2
NumberOfEntries=6
Version=2
"""
    (playlists_dir / "c_major_piano.pls").write_text(pls_content, encoding="utf-8")

    xspf_content = """<?xml version="1.0" encoding="UTF-8"?>
<playlist version="1" xmlns="http://xspf.org/ns/0/">
  <title>InfiniFolder Audio Fixtures</title>
  <trackList>
    <track>
      <location>../audio/c_major_piano.wav</location>
      <title>C Major Piano Chord (WAV)</title>
    </track>
    <track>
      <location>../audio/c_major_piano.flac</location>
      <title>C Major Piano Chord (FLAC)</title>
    </track>
    <track>
      <location>../audio/c_major_piano.mp3</location>
      <title>C Major Piano Chord (MP3)</title>
    </track>
    <track>
      <location>../audio/c_major_piano.alac</location>
      <title>C Major Piano Chord (ALAC)</title>
    </track>
    <track>
      <location>../audio/c_major_piano.mp2</location>
      <title>C Major Piano Chord (MP2)</title>
    </track>
    <track>
      <location>../audio/c_major_piano.mod</location>
      <title>C Major Piano Chord (MOD)</title>
    </track>
  </trackList>
</playlist>
"""
    (playlists_dir / "c_major_piano.xspf").write_text(xspf_content, encoding="utf-8")

    cue_content = """REM GENRE "Classical"
REM DATE 2026
REM DISCID 04000102
PERFORMER "InfiniFolder"
TITLE "C Major Piano Chord Suite"
FILE "../audio/c_major_piano.bin" BINARY
  TRACK 01 AUDIO
    TITLE "C Major Attack"
    PERFORMER "InfiniFolder"
    INDEX 01 00:00:00
  TRACK 02 AUDIO
    TITLE "C Major Sustain & Decay"
    PERFORMER "InfiniFolder"
    INDEX 01 00:00:38
"""
    (playlists_dir / "c_major_piano.cue").write_text(cue_content, encoding="utf-8")

    cddb_content = """# xmcd CD database file
DISCID=04000102
DTITLE=InfiniFolder / C Major Piano Chord Suite
DYEAR=2026
DGENRE=Classical
TTITLE0=C Major Attack
TTITLE1=C Major Sustain & Decay
EXTD=
EXTT0=
EXTT1=
PLAYORDER=
"""
    (playlists_dir / "cddb.toc").write_text(cddb_content, encoding="utf-8")

    nested_m3u_content = """#EXTM3U
#EXTINF:2,InfiniFolder - Overture (WAV)
../audio/c_major_piano.wav
#EXTINF:-1,InfiniFolder - Piano Suite (Sub-Playlist)
c_major_piano.m3u
#EXTINF:2,InfiniFolder - Finale Tracker (XM)
../audio/c_major_piano.xm
"""
    (playlists_dir / "nested_playlist.m3u").write_text(nested_m3u_content, encoding="utf-8")


def generate_all_audio():
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    master_wav = AUDIO_DIR / "c_major_piano.wav"
    generate_master_piano_chord_wav(master_wav)

    # 1. FFmpeg conversions from master C-major piano WAV
    ffmpeg_targets = [
        ("c_major_piano.mp3", ["-c:a", "libmp3lame", "-b:a", "192k"]),
        ("c_major_piano.mp2", ["-c:a", "mp2", "-b:a", "192k"]),
        ("c_major_piano.flac", ["-c:a", "flac"]),
        ("c_major_piano.ogg", ["-c:a", "libvorbis", "-q:a", "5"]),
        ("c_major_piano.oga", ["-c:a", "libvorbis", "-q:a", "5", "-f", "ogg"]),
        ("c_major_piano.opus", ["-c:a", "libopus", "-b:a", "128k"]),
        ("c_major_piano.m4a", ["-c:a", "aac", "-b:a", "160k"]),
        ("c_major_piano.m4b", ["-c:a", "aac", "-b:a", "160k", "-f", "ipod"]),
        ("c_major_piano.m4r", ["-c:a", "aac", "-b:a", "160k", "-f", "ipod"]),
        ("c_major_piano.alac.m4a", ["-c:a", "alac"]),
        ("c_major_piano.alac", ["-c:a", "alac", "-f", "ipod"]),
        ("c_major_piano.aac", ["-c:a", "aac", "-b:a", "160k", "-f", "adts"]),
        ("c_major_piano.3ga", ["-c:a", "aac", "-b:a", "128k", "-f", "3gp"]),
        ("c_major_piano.mka", ["-c:a", "libopus", "-b:a", "128k", "-f", "matroska"]),
        ("c_major_piano.weba", ["-c:a", "libopus", "-b:a", "128k", "-f", "webm"]),
        ("c_major_piano.amr", ["-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"]),
        ("c_major_piano.awb", ["-ar", "16000", "-ac", "1", "-c:a", "libvo_amrwbenc", "-b:a", "23.85k", "-f", "amr"]),
        ("c_major_piano.wma", ["-c:a", "wmav2", "-b:a", "160k"]),
        ("c_major_piano.wv", ["-c:a", "wavpack"]),
        ("c_major_piano.tta", ["-c:a", "tta"]),
        ("c_major_piano.ac3", ["-c:a", "ac3", "-b:a", "192k"]),
        ("c_major_piano.eac3", ["-c:a", "eac3", "-b:a", "192k"]),
        ("c_major_piano.dts", ["-strict", "-2", "-c:a", "dca", "-b:a", "384k", "-f", "dts"]),
        ("c_major_piano.mlp", ["-strict", "-2", "-c:a", "mlp", "-f", "mlp"]),
        ("c_major_piano.thd", ["-strict", "-2", "-c:a", "truehd", "-f", "truehd"]),
        ("c_major_piano.adx", ["-c:a", "adpcm_adx", "-f", "adx"]),
        ("c_major_piano.g722", ["-ar", "16000", "-ac", "1", "-c:a", "g722", "-f", "g722"]),
        ("c_major_piano.ra", ["-ar", "8000", "-ac", "1", "-c:a", "real_144", "-f", "rm"]),
        ("c_major_piano.rf64", ["-c:a", "pcm_s16le", "-rf64", "always", "-f", "wav"]),
    ]

    for filename, opts in ffmpeg_targets:
        out_path = AUDIO_DIR / filename
        run_cmd(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(master_wav), *opts, str(out_path)])

    # 2. SoX conversions for classic workstation & sample formats
    sox_targets = [
        ("c_major_piano.aiff", []),
        ("c_major_piano.aif", ["-t", "aiff"]),
        ("c_major_piano.aifc", ["-t", "aifc"]),
        ("c_major_piano.au", []),
        ("c_major_piano.snd", ["-t", "au"]),
        ("c_major_piano.voc", ["-c", "1"]),
        ("c_major_piano.8svx", ["-b", "8", "-c", "1", "-r", "16000"]),
        ("c_major_piano.svx", ["-t", "8svx", "-b", "8", "-c", "1", "-r", "16000"]),
        ("c_major_piano.maud", ["-b", "16", "-c", "2"]),
        ("c_major_piano.caf", []),
        ("c_major_piano.w64", []),
        ("c_major_piano.gsm", ["-r", "8000", "-c", "1"]),
        ("c_major_piano.sph", ["-t", "sph"]),
        ("c_major_piano.ircam", ["-t", "ircam"]),
        ("c_major_piano.avr", ["-t", "avr", "-b", "16", "-c", "1", "-r", "22050"]),
        ("c_major_piano.wve", ["-t", "wve", "-r", "8000", "-c", "1"]),
        ("c_major_piano.pvf", ["-t", "pvf", "-r", "16000", "-c", "1"]),
        ("c_major_piano.htk", ["-t", "htk", "-r", "16000", "-c", "1"]),
    ]

    for filename, opts in sox_targets:
        out_path = AUDIO_DIR / filename
        run_cmd(["sox", str(master_wav), *opts, str(out_path)])

    # 3. MIDI, SoundFont, Tracker Modules, Chiptune, DSD, ANSI Music, and Playlists
    write_midi_files()
    write_protracker_mod()
    write_screamtracker_s3m()
    write_fasttracker_xm()
    write_impulsetracker_it()
    write_soundfont_and_gus()
    write_chiptune_formats()
    write_dsd_files()
    write_cdda_file()
    write_ansi_music_and_playlists()


def write_manifest():
    manifest = {"images": {}, "audio": {}}
    for p in sorted(IMAGES_DIR.iterdir()):
        if p.is_file():
            data = p.read_bytes()
            manifest["images"][p.name] = {
                "size_bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
    for p in sorted(AUDIO_DIR.iterdir()):
        if p.is_file():
            data = p.read_bytes()
            manifest["audio"][p.name] = {
                "size_bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
    (FIXTURES_DIR / "media-fixtures-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Generated {len(manifest['images'])} image fixtures in {IMAGES_DIR}")
    print(f"Generated {len(manifest['audio'])} audio fixtures in {AUDIO_DIR}")


if __name__ == "__main__":
    generate_all_images()
    generate_all_audio()
    write_manifest()

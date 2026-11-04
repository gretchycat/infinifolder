#!/usr/bin/env python3
"""Generate original visual acceptance samples. Requires Pillow (including WebP).

FLI/FLC and MNG containers are written here so no external encoder is required.
Re-run from any directory; output goes into filesystem-test-images/images/animation.
"""
from io import BytesIO
from pathlib import Path
import colorsys
import struct
import zlib
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'filesystem-test-images/images/animation'
W, H, COUNT = 320, 200, 70


def chunk(kind, payload):
    return struct.pack('<IH', len(payload) + 6 + len(payload) % 2, kind) + payload + b'\0' * (len(payload) % 2)


def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))


def palette(frame):
    colors = [(8, 12, 25)]
    for n in range(1, 255):
        rgb = colorsys.hsv_to_rgb(((n + frame * 3) % 254) / 254, .8, .95)
        colors.append(tuple(round(c * 255) for c in rgb))
    colors.append((255, 255, 255))
    return colors


def image(frame):
    im = Image.new('P', (W, H))
    im.putpalette([c for rgb in palette(frame) for c in rgb])
    draw = ImageDraw.Draw(im)
    for x in range(0, W, 16):
        draw.line((x, 24, x, 178), fill=20)
    for y in range(24, 180, 16):
        draw.line((0, y, W - 1, y), fill=20)
    for x in range(W):
        draw.line((x, 145, x, 177), fill=1 + x * 253 // W)
    # The palette moves even where pixel indices are unchanged.
    x = round(20 + (W - 64) * (1 - abs(frame * 2 / COUNT - 1)))
    y = round(65 + 22 * __import__('math').sin(frame * 2 * __import__('math').pi / COUNT))
    draw.ellipse((x, y, x + 36, y + 36), fill=140, outline=255, width=2)
    draw.text((8, 6), 'INFINIFOLDER ANIMATION', fill=255)
    draw.text((8, 184), f'FRAME {frame + 1:02}/{COUNT}   MOTION + PALETTE', fill=255)
    if frame == 35:
        im.paste(0, (0, 0, W, H))  # Exercise BLACK, then recover using a delta.
    return im


def brun(pixels):
    data = bytearray()
    for y in range(H):
        row = pixels[y * W:(y + 1) * W]
        packets = bytearray()
        count, x = 0, 0
        while x < W:
            run = 1
            while x + run < W and run < 127 and row[x + run] == row[x]:
                run += 1
            # Both positive repeat and negative literal packets occur in samples.
            if run >= 3:
                packets += struct.pack('<bB', run, row[x])
            else:
                run = min(127, W - x)
                packets += struct.pack('<b', -run) + row[x:x + run]
            x += run
            count += 1
        data += bytes([count]) + packets
    return data


def line_delta(old, pixels):
    data = bytearray(struct.pack('<HH', 0, H))
    for y in range(H):
        a, b = old[y * W:(y + 1) * W], pixels[y * W:(y + 1) * W]
        changed = [x for x in range(W) if a[x] != b[x]]
        if not changed:
            data.append(0)
            continue
        start, end = changed[0], changed[-1] + 1
        packets = bytearray()
        count, skip, x = 0, start, start
        while skip > 255:
            packets += bytes([255, 0])
            count += 1
            skip -= 255
        while x < end:
            run = 1
            while x + run < end and run < 127 and b[x + run] == b[x]:
                run += 1
            if run >= 3:
                packets += struct.pack('<BbB', skip, -run, b[x])
            else:
                run = min(127, end - x)
                packets += struct.pack('<Bb', skip, run) + b[x:x + run]
            x += run
            skip = 0
            count += 1
        data += bytes([count]) + packets
    return data


def word_delta(old, pixels):
    rows = [y for y in range(H) if old[y * W:(y + 1) * W] != pixels[y * W:(y + 1) * W]]
    data = bytearray(struct.pack('<H', len(rows)))
    next_y = 0
    for y in rows:
        if y > next_y:
            data += struct.pack('<h', next_y - y)
        row = pixels[y * W:(y + 1) * W]
        data += struct.pack('<H', (W // 2 + 126) // 127)
        for x in range(0, W, 254):
            part = row[x:x + 254]
            data += bytes([0, len(part) // 2]) + part
        next_y = y + 1
    return data


def flic(images, flc=False):
    frames = []
    old = bytes(W * H)
    for n, im in enumerate(images):
        pixels = im.tobytes()
        rgb = bytes(c if flc else round(c * 63 / 255) for color in palette(n) for c in color)
        parts = [chunk(4 if flc else 11, struct.pack('<HBB', 1, 0, 0) + rgb)]
        if n == 0:
            parts.append(chunk(15, brun(pixels)))
        elif n == 20:
            parts.append(chunk(16, pixels))
        elif n == 35:
            parts.append(chunk(13, b''))
        else:
            parts.append(chunk(7 if flc else 12, word_delta(old, pixels) if flc else line_delta(old, pixels)))
        payload = b''.join(parts)
        delay = 210 if flc and n == 50 else 0
        frames.append(struct.pack('<IHHHHHH', len(payload) + 16, 0xf1fa, len(parts), delay, 0, 0, 0) + payload)
        old = pixels
    header = bytearray(128)
    struct.pack_into('<IHHHHHHI', header, 0, 128 + sum(map(len, frames)), 0xaf12 if flc else 0xaf11, COUNT, W, H, 8, 3, 70 if flc else 5)
    if flc:
        struct.pack_into('<II', header, 80, 128, 128 + len(frames[0]))
    return bytes(header) + b''.join(frames)


def mng(images):
    data = bytearray(b'\x8aMNG\r\n\x1a\n')
    data += png_chunk(b'MHDR', struct.pack('>7I', W, H, 100, COUNT + 1, COUNT, COUNT * 7, 3))
    data += png_chunk(b'TERM', struct.pack('>BBII', 3, 0, 0, 0x7fffffff))
    data += png_chunk(b'FRAM', b'\x03\x00\x02\x00\x00\x00' + struct.pack('>I', 7))
    for im in images:
        png = BytesIO()
        im.convert('RGBA').save(png, format='PNG')
        data += png.getvalue()[8:]  # MNG embeds PNG chunks, without PNG signature.
    return data + png_chunk(b'MEND', b'')


def stored_mng(images):
    """An actual stored-object animation, using grouped FRAM and SHOW commands."""
    data = bytearray(b'\x8aMNG\r\n\x1a\n')
    data += png_chunk(b'MHDR', struct.pack('>7I', W, H, 100, 0, COUNT, COUNT * 7, 0))
    data += png_chunk(b'TERM', struct.pack('>BBII', 3, 0, 0, 0x7fffffff))
    for index, im in enumerate(images, 1):
        data += png_chunk(b'DEFI', struct.pack('>HB', index, 1))
        png = BytesIO()
        im.convert('RGBA').save(png, format='PNG')
        data += png.getvalue()[8:]
    data += png_chunk(b'SAVE', b'')
    for index in range(1, COUNT + 1):
        data += png_chunk(b'FRAM', b'\x04\x00\x02\x00\x00\x00' + struct.pack('>I', 7))
        data += png_chunk(b'SHOW', struct.pack('>H', index))
    return data + png_chunk(b'MEND', b'')


def interactive_mng():
    """Named views and a four-bit palette-index hit map; no external content/actions."""
    palette_data = [8, 12, 25, 255, 110, 35, 40, 180, 250, 255, 255, 255] + [0] * (768 - 12)
    normal = Image.new('P', (W, H)); normal.putpalette(palette_data)
    draw = ImageDraw.Draw(normal)
    draw.rectangle((20, 40, 149, 155), outline=1, width=3)
    draw.rectangle((170, 40, 299, 155), outline=2, width=3)
    draw.text((30, 10), 'TOUCH / DRAG OR CHOOSE A STATE', fill=3)
    draw.text((35, 170), 'LEFT', fill=3); draw.text((220, 170), 'RIGHT', fill=3)
    mask = Image.new('P', (W, H)); mask.putpalette(palette_data)
    ImageDraw.Draw(mask).rectangle((20, 40, 149, 155), fill=1)
    ImageDraw.Draw(mask).rectangle((170, 40, 299, 155), fill=2)
    left = normal.copy(); ImageDraw.Draw(left).rectangle((20, 40, 149, 155), fill=1)
    right = normal.copy(); ImageDraw.Draw(right).rectangle((170, 40, 299, 155), fill=2)
    data = bytearray(b'\x8aMNG\r\n\x1a\n')
    data += png_chunk(b'MHDR', struct.pack('>7I', W, H, 1000, 0, 0, 0, 0))
    rules = [bytes([2, 3]) + struct.pack('>HB', 2, index) + name
             for index, name in [(1, b'left'), (2, b'right'), (0, b'normal')]]
    rules.append(bytes([3, 0]) + b'normal')
    data += png_chunk(b'evNT', b'\0'.join(rules))
    for index, image_data in enumerate([normal, mask, left, right], 1):
        data += png_chunk(b'DEFI', struct.pack('>HB', index, 1))
        png = BytesIO(); image_data.save(png, format='PNG', bits=4)
        data += png.getvalue()[8:]
    data += png_chunk(b'SAVE', b'')
    for name, index in [(b'normal', 1), (b'left', 3), (b'right', 4)]:
        data += png_chunk(b'SEEK', name) + png_chunk(b'FRAM', b'\x04')
        data += png_chunk(b'SHOW', struct.pack('>H', index))
    return data + png_chunk(b'MEND', b'')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    images = [image(n) for n in range(COUNT)]
    (OUT / 'motion-palette.fli').write_bytes(flic(images))
    (OUT / 'motion-palette.flc').write_bytes(flic(images, True))
    (OUT / 'motion-palette.mng').write_bytes(mng(images))
    (OUT / 'stored-objects.mng').write_bytes(stored_mng(images))
    (OUT / 'interactive-regions.mng').write_bytes(interactive_mng())
    recoverable = bytearray(flic(images, True))
    recoverable[0:4] = b'\0' * 4
    recoverable[6:12] = b'\0' * 6
    recoverable[16:20] = b'\0' * 4
    (OUT / 'recoverable-header.flc').write_bytes(recoverable)
    rgba = [im.convert('RGBA') for im in images]
    rgba[0].save(OUT / 'motion-palette.gif', save_all=True, append_images=rgba[1:], duration=70, loop=0, disposal=2)
    rgba[0].save(OUT / 'motion-palette.webp', save_all=True, append_images=rgba[1:], duration=70, loop=0, lossless=True)
    rgba[0].save(OUT / 'motion-palette.apng', format='PNG', save_all=True, append_images=rgba[1:], duration=70, loop=0)
    # Independent translucent patches exercise SOURCE/OVER and BACKGROUND/PREVIOUS.
    patches = []
    for n in range(24):
        patch = Image.new('RGBA', (W, H))
        draw = ImageDraw.Draw(patch)
        draw.ellipse((n * 10, 65, n * 10 + 70, 135), fill=(255, 80, 25, 170))
        patches.append(patch)
    patches[0].save(OUT / 'transparency-disposal.png', save_all=True, append_images=patches[1:], duration=100,
                    loop=2, disposal=[0] + [1 if n % 2 else 2 for n in range(1, 24)], blend=[0] + [1] * 23)
    images[14].convert('RGB').save(OUT / 'reference-frame-15.png')
    for path in sorted(OUT.glob('*')):
        if path.is_file():
            print(f'{path.relative_to(ROOT)}: {path.stat().st_size:,} bytes')


if __name__ == '__main__':
    main()

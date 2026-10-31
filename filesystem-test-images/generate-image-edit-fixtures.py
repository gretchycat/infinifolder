#!/usr/bin/env python3
"""Original asymmetric image/edit references; stdlib only, preserve existing files."""
import hashlib
import io
import json
import struct
import zipfile
import zlib
from fixture_paths import ROOT, update_manifest

OUT = ROOT / 'images'


def png(rows):
    width, height = len(rows[0]), len(rows)
    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value))
    raw = b''.join(b'\0' + bytes(channel for pixel in row for channel in pixel) for row in rows)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def main():
    colors = [(224, 48, 48), (64, 176, 80), (48, 96, 208), (224, 192, 64)]
    rows = [[colors[(y >= 100) * 2 + (x >= 160)] for x in range(320)] for y in range(200)]
    font = {'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
            'L': ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
            'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
            'B': ['11110', '10001', '10001', '11110', '10001', '10001', '11110']}
    for index, label in enumerate(['TL', 'TR', 'BL', 'BR']):
        left, top = (index % 2) * 160 + 36, (index // 2) * 100 + 22
        ink = (255, 255, 255) if index in (0, 2) else (0, 0, 0)
        for letter, glyph in enumerate(label):
            for gy, line in enumerate(font[glyph]):
                for gx, bit in enumerate(line):
                    if bit == '1':
                        for dy in range(8):
                            for dx in range(8):
                                rows[top + gy*8 + dy][left + letter*48 + gx*8 + dx] = ink
    for y in range(200):
        for x in range(320):
            if x < 4 or y < 4 or x >= 316 or y >= 196:
                rows[y][x] = (255, 255, 255)
    variants = {'sample-image-edits.png': rows,
        'expected-image-cw.png': [list(row) for row in zip(*rows[::-1])],
        'expected-image-ccw.png': [list(row) for row in zip(*rows)][::-1],
        'expected-image-flip-horizontal.png': [row[::-1] for row in rows],
        'expected-image-flip-vertical.png': rows[::-1],
        'expected-image-crop.png': [row[80:240] for row in rows[50:150]]}
    files = {name: png(value) for name, value in variants.items()}
    expected = {name: {'width': len(variants[name][0]), 'height': len(variants[name]), 'sha256': hashlib.sha256(value).hexdigest()} for name, value in files.items()}
    files['image-edits-expected.json'] = (json.dumps({'files': expected, 'crop': [80, 50, 240, 150],
        'notes': 'Original synthetic RGB corner labels; compare orientation pixels exactly. Resize uses the retained Android filtered backend.'}, indent=2) + '\n').encode()
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w') as output:
        for name, data in files.items():
            entry = zipfile.ZipInfo('images/' + name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, data)
    files['image-edit-checks.zip'] = archive.getvalue()
    paths = []
    for name, data in files.items():
        path = OUT / name
        if not path.exists():
            path.write_bytes(data)
        paths.append(path)
    update_manifest(paths)
    print('Preserved or created ' + str(len(paths)) + ' image-edit fixtures')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Add EXIF to existing app-icon JPEG pixels using stdlib only; preserve existing files."""
import hashlib
import io
import json
import struct
import zipfile
from fixture_paths import ROOT, update_manifest

OUT = ROOT / 'images'


def exif(order):
    pack = lambda fmt, *values: struct.pack(order + fmt, *values)
    data = bytearray((b'II' if order == '<' else b'MM') + pack('HI', 42, 8))

    def ifd(entries):
        offset = len(data)
        data.extend(pack('H', len(entries)) + bytes(len(entries) * 12 + 4))
        for index, (tag, kind, count, value) in enumerate(entries):
            record = offset + 2 + 12 * index
            data[record:record+8] = pack('HHI', tag, kind, count)
            if len(value) <= 4:
                data[record+8:record+12] = value.ljust(4, b'\0')
            else:
                if len(data) % 2:
                    data.append(0)
                data[record+8:record+12] = pack('I', len(data))
                data.extend(value)
        return offset

    def ascii_value(tag, value):
        raw = value.encode('ascii') + b'\0'
        return tag, 2, len(raw), raw

    # Pointers are patched after all three IFDs are laid out.
    root = ifd([ascii_value(0x010f, 'InfiniStream Synthetic'), ascii_value(0x0110, 'Metadata Fixture'),
                (0x0112, 3, 1, pack('H', 1)), ascii_value(0x0132, '2026:10:31 12:34:56'),
                (0x8769, 4, 1, pack('I', 0)), (0x8825, 4, 1, pack('I', 0))])
    camera = ifd([(0x829a, 5, 1, pack('II', 1, 125)), (0x829d, 5, 1, pack('II', 28, 10)),
                  (0x8827, 3, 1, pack('H', 200)), (0x920a, 5, 1, pack('II', 50, 1))])
    gps = ifd([(1, 2, 2, b'N\0'), (2, 5, 3, pack('IIIIII', 41, 1, 30, 1, 0, 1)),
               (3, 2, 2, b'W\0'), (4, 5, 3, pack('IIIIII', 87, 1, 15, 1, 0, 1))])
    for index, target in ((4, camera), (5, gps)):
        start = root + 2 + 12 * index + 8
        data[start:start+4] = pack('I', target)
    return b'Exif\0\0' + data


def main():
    original = (OUT / 'icon.jpg').read_bytes()
    assert original[:2] == b'\xff\xd8'
    files = {}
    for name, order in [('sample-exif-le.jpg', '<'), ('sample-exif-be.jpg', '>')]:
        payload = exif(order)
        files[name] = original[:2] + b'\xff\xe1' + struct.pack('>H', len(payload) + 2) + payload + original[2:]
    files['invalid-metadata.jpg'] = b'\xff\xd8\xff\xe1\x00\x40Exif\0\0II\x2a\0\xff\xff\xff\x7f'
    expected = {name: {'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()} for name, value in files.items()}
    files['image-metadata-expected.json'] = (json.dumps({'files': expected, 'camera': 'InfiniStream Synthetic Metadata Fixture',
        'dateTime': '2026:10:31 12:34:56', 'iso': '200', 'latitude': 41.5, 'longitude': -87.25,
        'note': 'Synthetic tags, not real camera/location data. Exposure/f-number/focal text follows the platform backend.'}, indent=2) + '\n').encode()
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w') as output:
        for name, data in files.items():
            entry = zipfile.ZipInfo('images/' + name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, data)
    files['image-metadata-checks.zip'] = archive.getvalue()
    paths = []
    for name, data in files.items():
        path = OUT / name
        if not path.exists():
            path.write_bytes(data)
        paths.append(path)
    update_manifest(paths)
    print('Preserved or created ' + str(len(paths)) + ' image metadata fixtures')


if __name__ == '__main__':
    main()

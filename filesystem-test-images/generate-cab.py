#!/usr/bin/env python3
"""Create cabinets and import multipart fixtures; preserve existing files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zlib
from fixture_paths import ROOT
from generate import populate


def publish(source, target):
    if target.exists():
        print('Keep existing:', target.relative_to(ROOT))
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as inp, target.open('xb') as out:
        try:
            with out: shutil.copyfileobj(inp, out)
        except BaseException:
            target.unlink()
            raise
    print('Created:', target.relative_to(ROOT))


def checksum(data):
    value = 0
    end = len(data) // 4 * 4
    for i in range(0, end, 4):
        value ^= struct.unpack_from('<I', data, i)[0]
    if len(data) != end:
        value ^= int.from_bytes(data[end:], 'big')
    return value


def cabinet(payloads):
    """MS-CAB stored cabinet: one independent compression folder per member."""
    files = bytearray()
    for index, (name, data) in enumerate(payloads.items()):
        files += struct.pack('<IIHHHH', len(data), 0, index, (43 << 9) | (11 << 5) | 14,
                             (22 << 11) | (13 << 5) | 10, 0xa0)
        files += name.replace('/', '\\').encode('utf-8') + b'\0'
    offset = 36 + len(payloads) * 8 + len(files)
    folders = bytearray()
    blocks = bytearray()
    locations = {}
    for name, data in payloads.items():
        folders += struct.pack('<IHH', offset + len(blocks), 1, 0)
        lengths = struct.pack('<HH', len(data), len(data))
        crc = checksum(lengths) ^ checksum(data)
        locations[name] = offset + len(blocks) + 8
        blocks += struct.pack('<I', crc) + lengths + data
    header = struct.pack('<4sIIIIIBBHHHHH', b'MSCF', 0, offset + len(blocks), 0,
                         36 + len(folders), 0, 3, 1, len(payloads), len(payloads), 0, 0x4f49, 0)
    return header + folders + files + blocks, locations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--libmspack', type=Path, required=True)
    args = parser.parse_args()
    upstream = args.libmspack.resolve()
    root = ROOT / 'archives/cab'
    root.mkdir(parents=True, exist_ok=True)
    payloads = {'good.txt': b'Intact CAB member; this should remain readable.\n',
                'bad.bin': bytes(range(256)) * 16,
                'nested/message.txt': b'Nested intact CAB member.\n',
                'names/cafe\u0301.txt': b'Unicode CAB filename.\n'}
    expected_path = root / 'expected.json'
    expected = json.loads(expected_path.read_text()) if expected_path.exists() else {}
    with tempfile.TemporaryDirectory(prefix='cab-fixtures-', dir=root) as tmp:
        work = Path(tmp)
        data, offsets = cabinet(payloads)
        for name, damaged in [('independent-folders.cab', False), ('damaged-member.cab', True)]:
            staged = work / name
            changed = bytearray(data)
            if damaged: changed[offsets['bad.bin'] + 100] ^= 0x40
            staged.write_bytes(changed)
            target = root / name
            if not target.exists():
                expected[name] = {path: {'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
                                        'readable': not (damaged and path == 'bad.bin')}
                                  for path, content in payloads.items()}
            publish(staged, target)
        source = work / 'source';source.mkdir()
        files = populate(source)
        staged = work / 'mszip.cab'
        subprocess.run(['gcab', '-c', '-z', str(staged), *sorted(files)], cwd=source, check=True)
        if not (root / staged.name).exists():
            expected[staged.name] = {name: {'bytes':len(data), 'sha256':hashlib.sha256(data).hexdigest(), 'readable':True}
                                     for name,data in files.items()}
        publish(staged, root / staged.name)
        inputs = upstream / 'libmspack/test/test_files/cabd'
        for i in range(1, 6):
            member = inputs / f'multi_basic_pt{i}.cab'
            # These are the literal cabinet names embedded in the upstream headers.
            name = f'cabd_multi_basic_pt{i}.cab'
            publish(member, root / 'multipart' / name)
            if i != 3: publish(member, root / 'missing-volume' / name)
        publish(inputs / 'mszip_lzx_qtm.cab', root / 'methods.cab')
        publish(upstream / 'libmspack/COPYING.LIB', root / 'LIBMSPACK-LICENSE.txt')
    expected_path.write_text(json.dumps(expected, ensure_ascii=False, indent=2) + '\n')
    revision = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
    provenance = root / 'PROVENANCE.md'
    if not provenance.exists():
        provenance.write_text(f'''# CAB fixtures\n\n`mszip.cab` was made with gcab from the common test tree. Stored cabinets with independent compression folders were constructed according to Microsoft's MS-CAB layout, including CFDATA checksums; the damaged copy changes only `bad.bin` payload bytes.\n\nMultipart and methods fixtures are from https://github.com/kyz/libmspack at `{revision}`, under `libmspack/test/test_files/cabd/`. The multipart files are published with the names referenced in their headers (`cabd_multi_basic_ptN.cab`); member bytes are unchanged. `missing-volume/` omits part 3. `methods.cab` is the upstream `mszip_lzx_qtm.cab`. The upstream LGPL notice is included.\n\nExpected hashes for generated file contents are in `expected.json`. These cabinets do not exercise CAB creation in the Android app.\n''')
    manifest_path = ROOT / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    for p in root.rglob('*'):
        if p.is_file():
            manifest[p.relative_to(ROOT).as_posix()] = {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest_path.write_text(json.dumps(dict(sorted(manifest.items())), indent=2) + '\n')


if __name__ == '__main__':
    main()

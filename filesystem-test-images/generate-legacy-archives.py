#!/usr/bin/env python3
"""Create ARJ fixtures and import redistributable RAR fixtures, preserving files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent


def copy_new(source, target):
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as inp, target.open('xb') as out:
        shutil.copyfileobj(inp, out)


def damage_arj(data, wanted):
    data = bytearray(data)
    p = 0
    first = True
    while p + 4 < len(data):
        signature, size = struct.unpack_from('<HH', data, p)
        if signature != 0xea60 or size == 0:
            break
        header = data[p+4:p+4+size]
        p += 4 + size + 4
        while True:
            count = struct.unpack_from('<H', data, p)[0]
            p += 2
            if not count:
                break
            p += count + 4
        if first:
            first = False
            continue
        name = bytes(header[header[0]:]).split(b'\0')[0].decode('ascii')
        packed = struct.unpack_from('<I', header, 12)[0]
        if name == wanted:
            data[p + packed // 2] ^= 0x41
            return data
        p += packed
    raise ValueError('Cannot locate ARJ payload for damage fixture')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rar-fixtures', type=Path, required=True)
    parser.add_argument('--junrar', type=Path, required=True)
    args = parser.parse_args()
    imported = args.rar_fixtures.resolve()
    junrar = args.junrar.resolve()
    root = ROOT / 'archives/legacy'
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='legacy-fixtures-', dir=ROOT) as tmp:
        work = Path(tmp)
        source = work / 'source'
        source.mkdir()
        (source / 'good.txt').write_bytes(b'Readable text; copy this even if another member is damaged.\n')
        (source / 'bad.bin').write_bytes(bytes(range(256)) * 64)
        (source / 'after.txt').write_bytes(b'Another intact member after bad.bin.\n')
        (source / 'nested').mkdir()
        (source / 'nested/message.txt').write_bytes(b'Nested ARJ file.\n')
        for name, mode in [('compressed.arj', '-m1'), ('stored.arj', '-m0')]:
            staged = work / name
            subprocess.run(['arj', 'a', '-r', '-y', mode, str(staged), 'good.txt', 'bad.bin', 'after.txt', 'nested'], cwd=source, check=True)
            copy_new(staged, root / 'arj' / name)
        damaged = work / 'damaged.arj'
        damaged.write_bytes(damage_arj((work / 'stored.arj').read_bytes(), 'bad.bin'))
        copy_new(damaged, root / 'arj/damaged.arj')
        (source / 'spanned.bin').write_bytes(bytes(range(256)) * 1024)
        multipart = work / 'multipart'
        multipart.mkdir()
        subprocess.run(['arj', 'a', '-y', '-m0', '-v50K', str(multipart / 'volumes.arj'), 'good.txt', 'spanned.bin', 'after.txt'], cwd=source, check=True)
        volumes = sorted(multipart.iterdir())
        for p in volumes:
            copy_new(p, root / 'arj-multipart' / p.name)
        continuation = sorted(p for p in volumes if p.suffix != '.arj')
        missing = continuation[len(continuation)//2]
        for p in volumes:
            if p != missing:
                copy_new(p, root / 'arj-missing-volume' / p.name)
        copy_new(imported / 'LICENSE.cc0', root / 'RAR-FIXTURES-CC0.txt')
        for source_name, name in [('testfile.rar3.rar','rar4.rar'), ('testfile.rar5.rar','rar5.rar'), ('testfile.rar5.solid.rar','rar5-solid.rar')]:
            copy_new(imported / 'build' / source_name, root / 'rar' / name)
        fixtures = junrar / 'src/test/resources/com/github/junrar/volumes'
        copy_new(junrar / 'LICENSE', root / 'JUNRAR-LICENSE.txt')
        for p in sorted((fixtures / 'new-part').glob('*.rar')):
            copy_new(p, root / 'rar4-multipart' / p.name)
        for p in sorted((fixtures / 'rar5-part').glob('vols.part*.rar')):
            copy_new(p, root / 'rar5-multipart' / p.name)
            if p.name != 'vols.part2.rar':
                copy_new(p, root / 'rar5-missing-volume' / p.name)
            damaged = work / ('damaged-' + p.name)
            data = bytearray(p.read_bytes())
            if p.name == 'vols.part2.rar':
                data[len(data)//2] ^= 0x41
            damaged.write_bytes(data)
            copy_new(damaged, root / 'rar5-damaged-volume' / p.name)
        copy_new(fixtures / 'rar5-part/README.md', root / 'RAR5-UPSTREAM-RECIPE.md')
    manifest = ROOT / 'manifest.json'
    inventory = json.loads(manifest.read_text())
    for p in sorted(root.rglob('*')):
        if p.is_file():
            inventory[p.relative_to(ROOT).as_posix()] = {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest.write_text(json.dumps(dict(sorted(inventory.items())), indent=2) + '\n')
    print('ARJ missing-volume fixture omits:', missing.name)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Generate known deleted FAT12/16/32 files using mkfs.fat and mtools; preserve existing fixtures."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from fixture_paths import ROOT, update_manifest


def run(*args):
    return subprocess.check_output(list(map(str, args)), stderr=subprocess.STDOUT,
                                   env={**os.environ, 'MTOOLS_SKIP_CHECK': '1'}).decode()


def sha(path):
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256')
    return digest.hexdigest()


def main():
    out = ROOT / 'filesystems/windows/recovery'
    out.mkdir(parents=True, exist_ok=True)
    expected_path = out / 'expected.json'
    expected = json.loads(expected_path.read_text()) if expected_path.exists() else {}
    for bits, size in [(12, 1440 * 1024), (16, 16 * 1024**2), (32, 64 * 1024**2)]:
        name = f'fat{bits}-deleted.img'
        if (out / name).exists():
            print('Keep existing:', name)
            continue
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            image = work / name
            with image.open('xb') as f:
                f.truncate(size)
            print(run('mkfs.fat', '-F', bits, '-s', 1, '-n', f'RECOVER{bits}', image).strip())
            payloads = {
                'DELETE.TXT': b'Known deleted short-name file. Recover this text.\n',
                'Deleted long filename.txt': b'Known deleted long-name file.\n',
                'EMPTY.TXT': b'',
                'CONTIG.BIN': bytes(range(256)) * 37,
                'LIVE/NESTED.TXT': b'Deleted file inside a directory that remains live.\n',
            }
            live = work / 'readme.txt'
            live.write_text('Live control file. Deleted candidates are unverified; expected.json records original bytes.\n')
            run('mcopy', '-i', image, live, '::README.TXT')
            run('mmd', '-i', image, '::LIVE')
            details = {}
            for index, (path, data) in enumerate(payloads.items()):
                source = work / f'payload-{index}'
                source.write_bytes(data)
                run('mcopy', '-i', image, source, f'::{path}')
                details[path] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                                 'allocation_before_delete': run('mshowfat', '-i', image, f'::{path}').strip()}
            for path in payloads:
                run('mdel', '-i', image, f'::{path}')
            # No allocations after deletion, to preserve these controlled contiguous candidates.
            with image.open('rb') as inp, (out / name).open('xb') as dest:
                shutil.copyfileobj(inp, dest)
            expected[name] = {'fat': bits, 'size': size, 'sha256': sha(out / name), 'deleted': details,
                              'live': ['README.TXT', 'LIVE'], 'recipe': 'All payloads copied before deletion; no subsequent allocations.'}
            expected_path.write_text(json.dumps(expected, indent=2) + '\n')
            print('Created:', name)
    compressed = out / 'fat16-deleted.img.gz'
    if not compressed.exists():
        with (out / 'fat16-deleted.img').open('rb') as source, compressed.open('xb') as dest:
            with gzip.GzipFile(filename='', mode='wb', fileobj=dest, mtime=0) as gz:
                shutil.copyfileobj(source, gz)
        expected[compressed.name] = {'sha256': sha(compressed), 'uncompressed': 'fat16-deleted.img'}
        expected_path.write_text(json.dumps(expected, indent=2) + '\n')

    update_manifest(out.rglob('*'))


if __name__ == '__main__':
    main()

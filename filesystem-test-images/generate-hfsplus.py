#!/usr/bin/env python3
"""Create HFS+/HFSX fixtures using independent host tools; keep existing files.

See README.md for pinned tool sources and host-only patches. No Android reader
is used to construct these images. No filesystem is mounted on the host.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unicodedata

from generate import EPOCH, ROOT, populate
from fixture_paths import fixture_path


def publish(staged, target):
    """Exclusive publication, with cleanup only of a file created by this call."""
    with staged.open('rb') as source:
        out = target.open('xb')
        try:
            with out:
                shutil.copyfileobj(source, out)
        except BaseException:
            target.unlink()
            raise


def payload_tar(source, target, case_sensitive):
    expected = {}
    with tarfile.open(target, 'w', format=tarfile.USTAR_FORMAT, encoding='utf-8') as archive:
        for p in sorted(source.rglob('*'), key=lambda p: (len(p.relative_to(source).parts), p.as_posix())):
            name = unicodedata.normalize('NFD', p.relative_to(source).as_posix())
            if name == 'case.txt' and not case_sensitive:
                continue
            info = tarfile.TarInfo(name)
            info.uid = info.gid = 1000
            info.mtime = EPOCH
            record = {'uid': 1000, 'gid': 1000}
            if p.is_symlink():
                info.type, info.mode = tarfile.SYMTYPE, 0o777
                info.linkname = str(p.readlink())
                record.update(type='symlink', target=info.linkname)
                kind = stat.S_IFLNK
            elif p.is_dir():
                info.type, info.mode = tarfile.DIRTYPE, (0o700 if name == 'restricted' else 0o755)
                record.update(type='directory')
                kind = stat.S_IFDIR
            else:
                data = p.read_bytes()
                info.mode = 0o755 if name == 'run.sh' else 0o600 if name == 'restricted/private.txt' else 0o644
                info.size = len(data)
                record.update(type='file', bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                kind = stat.S_IFREG
            record['mode'] = f'{kind | info.mode:06o}'
            expected[name] = record
            archive.addfile(info, io.BytesIO(data) if record['type'] == 'file' else None)
    return dict(sorted(expected.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--newfs-hfs', type=Path, required=True)
    parser.add_argument('--hfsplus', type=Path, required=True)
    args = parser.parse_args()
    formatter, population_tool = args.newfs_hfs.resolve(), args.hfsplus.resolve()
    inventory = {}
    names = []
    with tempfile.TemporaryDirectory(prefix='hfsplus-fixtures-', dir=ROOT) as tmp:
        work = Path(tmp)
        source = work / 'source'
        source.mkdir()
        populate(source)
        (source / 'names/日本語.txt').write_text('Japanese filename.\n', encoding='utf-8')
        (source / 'names/emoji-😀.txt').write_text('Supplementary Unicode filename.\n', encoding='utf-8')
        (source / 'Case.txt').write_bytes(b'Uppercase C file.\n')
        (source / 'case.txt').write_bytes(b'Lowercase c file, distinct on HFSX.\n')
        (source / 'restricted').mkdir()
        (source / 'restricted/private.txt').write_bytes(b'Permission fixture: mode 0600.\n')
        for variant in ('hfsplus', 'hfsx'):
            name = f'{variant}-populated.img'
            tar = work / f'{variant}.tar'
            entries = payload_tar(source, tar, variant == 'hfsx')
            inventory[fixture_path(name).relative_to(ROOT).as_posix()] = {'filesystem': 'HFSX' if variant == 'hfsx' else 'HFS+',
                               'root_uid': 1000, 'root_gid': 1000, 'root_mode': '040755', 'entries': entries}
            dest = fixture_path(name)
            dest.parent.mkdir(parents=True, exist_ok=True)
            names.append(name)
            if dest.exists():
                print('Keep existing:', name, flush=True)
            else:
                staged = work / name
                with staged.open('xb') as out:
                    out.truncate(32 * 1024 * 1024)
                command = [str(formatter), '-b', '4096', '-U', '1000', '-G', '1000', '-M', '0755',
                           '-v', f'INF_TEST_{variant.upper()}']
                if variant == 'hfsx':
                    command.append('-s')
                subprocess.run([*command, str(staged)], check=True)
                subprocess.run([str(population_tool), str(staged), 'untar', str(tar)], check=True)
                publish(staged, dest)
                print('Created:', name, len(entries), 'user entries', flush=True)
            compressed = fixture_path(f'{name}.gz')
            names.append(compressed.name)
            if compressed.exists():
                print('Keep existing:', compressed.name, flush=True)
            else:
                staged = work / compressed.name
                with staged.open('xb') as out, gzip.GzipFile(filename='', fileobj=out, mode='wb', mtime=EPOCH) as zipped, dest.open('rb') as image:
                    shutil.copyfileobj(image, zipped)
                publish(staged, compressed)
                print('Created:', compressed.name, flush=True)
        expected = ROOT / 'hfsplus-expected.json'
        if not expected.exists():
            staged = work / expected.name
            staged.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            publish(staged, expected)
    # Merge into the common manifest; retain other generators' entries.
    manifest = ROOT / 'manifest.json'
    current = json.loads(manifest.read_text()) if manifest.exists() else {}
    for name in names:
        p = fixture_path(name)
        with p.open('rb') as stream:
            current[p.relative_to(ROOT).as_posix()] = {'bytes': p.stat().st_size, 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}
    manifest.write_text(json.dumps(dict(sorted(current.items())), indent=2) + '\n')


if __name__ == '__main__':
    main()

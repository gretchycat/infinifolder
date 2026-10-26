#!/usr/bin/env python3
"""Create new populated ext mutation fixtures; never overwrite existing images."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parent
destination = root / 'filesystems/linux'
destination.mkdir(parents=True, exist_ok=True)
manifest = destination / 'ext-write-fixtures.json'
records = []
with tempfile.TemporaryDirectory(prefix='if-ext-seed-') as temporary:
    tree = Path(temporary)
    source = tree / 'move-from'
    (source / 'directory-with-files/nested').mkdir(parents=True)
    (tree / 'move-to').mkdir()
    (source / 'empty-directory').mkdir()
    (source / 'single-file.txt').write_text('Move this file into move-to.\n')
    (source / 'directory-with-files/nested/payload.bin').write_bytes(bytes(range(256)) * 257)
    (source / 'directory-with-files/readme.txt').write_text('Move this nonempty directory as one item.\n')
    (source / 'directory-with-files/unicode-🌍.txt').write_text('Unicode filename and UTF-8 contents: 🌍\n')
    os.chmod(source / 'single-file.txt', 0o640)
    for kind in ('ext2', 'ext3', 'ext4'):
        image = destination / f'{kind}-write-tests.img'
        if not image.exists():
            # Exclusive creation protects files supplied by the user.
            with image.open('xb') as out: out.truncate(24 * 1024 * 1024)
            try:
                subprocess.run(['mke2fs', '-q', '-t', kind, '-b', '1024', '-L', kind + '-writes',
                    '-d', str(tree), str(image)], check=True)
                # Android can deny host hardlinks/symlinks even in private dirs;
                # create native filesystem links inside the disposable image.
                for command in [
                    'link /move-from/single-file.txt /outside-hardlink.txt',
                    'set_inode_field /move-from/single-file.txt links_count 2',
                    'symlink /move-from/symbolic-link single-file.txt',
                ]:
                    subprocess.run(['debugfs', '-w', '-R', command, str(image)], check=True)
                subprocess.run(['e2fsck', '-fn', str(image)], check=True)
            except BaseException:
                image.unlink()
                raise
        records.append({'path': image.relative_to(root).as_posix(), 'size': image.stat().st_size,
            'sha256': hashlib.sha256(image.read_bytes()).hexdigest(),
            'purpose': 'File and nonempty-directory moves, writes, deletion, links and retained metadata'})
if not manifest.exists(): manifest.write_text(json.dumps(records, indent=2) + '\n')
print('\n'.join(record['path'] for record in records))

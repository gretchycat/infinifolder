#!/usr/bin/env python3
"""Create additional fixtures without overwriting existing test data.

Uses Python 3, 7z, cpio, ar, zstd, mkntfs/ntfscp and makefs.
HFS creation uses libhfs through --hfs-library (no OS mount or ~/.hcwd).
"""
import argparse
import bz2
import ctypes as C
import gzip
import hashlib
import io
import json
import lzma
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile

from fixture_paths import ROOT, fixture_path
EPOCH = 1700000000


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def compress_z(source, target):
    """Use libarchive's Unix-compress encoder; the archive payload is unchanged."""
    lib = C.CDLL('libarchive.so')
    def bind(name, result, *arguments):
        function = getattr(lib, name)
        function.restype, function.argtypes = result, list(arguments)
        return function
    create = bind('archive_write_new', C.c_void_p)
    new_entry = bind('archive_entry_new', C.c_void_p)
    archive_free = bind('archive_write_free', C.c_int, C.c_void_p)
    entry_free = bind('archive_entry_free', None, C.c_void_p)
    error = bind('archive_error_string', C.c_char_p, C.c_void_p)
    raw_format = bind('archive_write_set_format_raw', C.c_int, C.c_void_p)
    compress = bind('archive_write_add_filter_compress', C.c_int, C.c_void_p)
    open_file = bind('archive_write_open_filename', C.c_int, C.c_void_p, C.c_char_p)
    set_name = bind('archive_entry_set_pathname', None, C.c_void_p, C.c_char_p)
    set_size = bind('archive_entry_set_size', None, C.c_void_p, C.c_longlong)
    set_type = bind('archive_entry_set_filetype', None, C.c_void_p, C.c_uint)
    write_header = bind('archive_write_header', C.c_int, C.c_void_p, C.c_void_p)
    write = bind('archive_write_data', C.c_ssize_t, C.c_void_p, C.c_void_p, C.c_size_t)
    close = bind('archive_write_close', C.c_int, C.c_void_p)
    archive, entry = create(), new_entry()
    def check(code):
        if code < 0:
            raise RuntimeError(error(archive))
    try:
        check(raw_format(archive)); check(compress(archive))
        check(open_file(archive, os.fsencode(target)))
        set_name(entry, os.fsencode(source.name))
        set_size(entry, source.stat().st_size)
        set_type(entry, stat.S_IFREG)
        check(write_header(archive, entry))
        with source.open('rb') as stream:
            while data := stream.read(128 * 1024):
                buffer = C.create_string_buffer(data)
                written = write(archive, buffer, len(data))
                check(written)
                if written != len(data):
                    raise RuntimeError('Incomplete Unix-compress fixture write')
        check(close(archive))
    finally:
        entry_free(entry)
        archive_free(archive)


def populate(root):
    files = {
        'README.txt': b'Infinifolder fixture: browsing, extraction, copying and deletion.\n',
        'nested/deeper/message.txt': b'Nested file content.\n',
        'names/Long file name with spaces.txt': b'Long filename content.\n',
        'names/caf\u00e9.txt': 'UTF-8 filename and content: caf\u00e9.\n'.encode(),
        'empty.txt': b'',
        'run.sh': b'#!/bin/sh\nprintf "fixture\\n"\n',
        'binary.bin': bytes(range(256)) * 32,
    }
    files.update({f'small-files/file-{i:03}.txt': f'Small file {i}\n'.encode() for i in range(100)})
    for name, data in files.items():
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        p.chmod(0o755 if name == 'run.sh' else 0o644)
    (root / 'empty-directory').mkdir()
    (root / 'readme-link').symlink_to('README.txt')
    (root / 'nested-link').symlink_to('nested', target_is_directory=True)
    for p in root.rglob('*'):
        os.utime(p, (EPOCH, EPOCH), follow_symlinks=False)
    return files


def zip_tree(root, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(root.rglob('*')):
            name = p.relative_to(root).as_posix()
            if p.is_symlink():
                info = zipfile.ZipInfo(name, (2023, 11, 14, 22, 13, 20))
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                z.writestr(info, os.readlink(p))
            else:
                z.write(p, name)


def hfs_image(library, root, target):
    lib = C.CDLL(str(library))
    def bind(name, restype, *args):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = restype, list(args)
        return fn
    fmt = bind('hfs_format', C.c_int, C.c_char_p, C.c_int, C.c_int, C.c_char_p, C.c_uint, C.c_void_p)
    mount = bind('hfs_mount', C.c_void_p, C.c_char_p, C.c_int, C.c_int)
    mkdir = bind('hfs_mkdir', C.c_int, C.c_void_p, C.c_char_p)
    create = bind('hfs_create', C.c_void_p, C.c_void_p, C.c_char_p, C.c_char_p, C.c_char_p)
    write = bind('hfs_write', C.c_ulong, C.c_void_p, C.c_void_p, C.c_ulong)
    close = bind('hfs_close', C.c_int, C.c_void_p)
    umount = bind('hfs_umount', C.c_int, C.c_void_p)
    def checked(ok):
        if not ok:
            raise RuntimeError(C.c_char_p.in_dll(lib, 'hfs_error').value)
    with target.open('wb') as f:
        f.truncate(16 * 1024 * 1024)
    checked(fmt(os.fsencode(target), 0, 1, b'INF_TEST_HFS', 0, None) == 0)
    vol = mount(os.fsencode(target), 0, 1)
    checked(vol)
    try:
        for p in sorted(root.rglob('*')):
            if p.is_symlink():
                continue
            # Classic HFS has a 31-byte MacRoman filename limit.
            parts = [part.encode('mac_roman')[:31] for part in p.relative_to(root).parts]
            dest = b':' + b':'.join(parts)
            if p.is_dir():
                checked(mkdir(vol, dest) == 0)
            else:
                f = create(vol, dest, b'TEXT', b'ttxt')
                checked(f)
                try:
                    data = p.read_bytes()
                    checked(write(f, data, len(data)) == len(data))
                finally:
                    checked(close(f) == 0)
    finally:
        checked(umount(vol) == 0)
    # Reopen through an independent handle and verify a known payload.
    vol = mount(os.fsencode(target), 0, 0)
    checked(vol)
    op = bind('hfs_open', C.c_void_p, C.c_void_p, C.c_char_p)
    read = bind('hfs_read', C.c_ulong, C.c_void_p, C.c_void_p, C.c_ulong)
    try:
        f = op(vol, b':README.txt')
        checked(f)
        try:
            expected = (root / 'README.txt').read_bytes()
            buffer = C.create_string_buffer(len(expected))
            checked(read(f, buffer, len(expected)) == len(expected) and buffer.raw == expected)
        finally:
            close(f)
    finally:
        umount(vol)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--makefs', type=Path, required=True)
    parser.add_argument('--hfs-library', type=Path, required=True)
    args = parser.parse_args()
    makefs, hfslib = args.makefs.resolve(), args.hfs_library.resolve()
    records = []
    fixture_names = []
    # Stage and publish each fixture exclusively; never clobber a user's image.
    with tempfile.TemporaryDirectory(prefix='fixtures-', dir=ROOT) as tmp:
        work = Path(tmp)
        source = work / 'source'
        source.mkdir()
        payloads = populate(source)
        def emit(name, creator):
            fixture_names.append(name)
            dest = fixture_path(name)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists():
                print('Keep existing:', name, flush=True)
                return
            staged = work / name
            creator(staged)
            with staged.open('rb') as src, dest.open('xb') as out:
                shutil.copyfileobj(src, out)
            records.append(name)
            print('Created:', name, dest.stat().st_size, flush=True)
        def tar(p, mode='w'):
            with tarfile.open(p, mode, format=tarfile.PAX_FORMAT) as archive:
                for child in sorted(source.iterdir()):
                    archive.add(child, child.name)
                info = tarfile.TarInfo('readme-hardlink.txt')
                info.type = tarfile.LNKTYPE
                info.linkname = 'README.txt'
                info.mtime = EPOCH
                archive.addfile(info)
        emit('archive.tar', tar)
        emit('archive.tar.gz', lambda p: tar(p, 'w:gz'))
        emit('archive.tar.bz2', lambda p: tar(p, 'w:bz2'))
        emit('archive.tar.xz', lambda p: tar(p, 'w:xz'))
        emit('archive.tar.lzma', lambda p: p.write_bytes(lzma.compress((fixture_path('archive.tar')).read_bytes(), format=lzma.FORMAT_ALONE)))
        emit('archive.tar.zst', lambda p: run('zstd', '-q', '-o', p, fixture_path('archive.tar')))
        emit('README.txt.zst', lambda p: run('zstd', '-q', '-o', p, source / 'README.txt'))
        emit('archive.tar.Z', lambda p: compress_z(fixture_path('archive.tar'), p))
        emit('archive.zip', lambda p: zip_tree(source, p))
        emit('archive.7z', lambda p: run('7z', 'a', '-t7z', '-snl', '-snh', p, '.', cwd=source, stdout=subprocess.DEVNULL))
        def cpio(p):
            names = b'\0'.join(os.fsencode(x.relative_to(source)) for x in sorted(source.rglob('*'))) + b'\0'
            with p.open('wb') as out:
                run('cpio', '--null', '-o', '-H', 'newc', cwd=source, input=names, stdout=out)
        emit('archive.cpio', cpio)
        emit('archive.ar', lambda p: run('ar', 'qcD', p, source / 'README.txt', source / 'binary.bin', source / 'empty.txt'))
        def deb(p):
            package = work / 'deb'
            package.mkdir()
            (package / 'debian-binary').write_bytes(b'2.0\n')
            control = b'Package: infinifolder-fixture\nVersion: 1.0\nArchitecture: all\nMaintainer: Fixture <fixture@example.invalid>\nDescription: Archive test fixture\n'
            with tarfile.open(package / 'control.tar.gz', 'w:gz') as z:
                info = tarfile.TarInfo('./control'); info.size = len(control); info.mtime = EPOCH
                z.addfile(info, io.BytesIO(control))
            shutil.copyfile(fixture_path('archive.tar.gz'), package / 'data.tar.gz')
            run('ar', 'qcD', p, 'debian-binary', 'control.tar.gz', 'data.tar.gz', cwd=package)
        emit('archive.deb', deb)
        def love(p):
            with zipfile.ZipFile(p, 'w', zipfile.ZIP_DEFLATED) as z:
                z.writestr('main.lua', 'function love.draw() love.graphics.print("Infinifolder fixture", 20, 20) end\n')
                z.writestr('README.txt', payloads['README.txt'])
        emit('archive.love', love)
        emit('rockridge.iso', lambda p: run(makefs, '-t', 'cd9660', '-o', 'rockridge,label=INF_TEST', p, source))
        emit('rockridge.iso.gz', lambda p: p.write_bytes(gzip.compress((fixture_path('rockridge.iso')).read_bytes(), mtime=EPOCH)))
        for version in (1, 2):
            emit(f'ufs{version}.img', lambda p, v=version: run(makefs, '-t', 'ffs', '-s', '16m', '-o', f'version={v}', p, source))
        emit('ufs1.img.Z', lambda p: compress_z(fixture_path('ufs1.img'), p))
        def gzip_ufs(p):
            with (fixture_path('ufs2.img')).open('rb') as src, gzip.open(p, 'wb') as out:
                shutil.copyfileobj(src, out)
        emit('ufs2.img.gz', gzip_ufs)
        emit('ufs2.img.zst', lambda p: run('zstd', '-q', '-o', p, fixture_path('ufs2.img')))
        # exFAT has no native links: omit symlinks and materialize hardlinks.
        ordinary = work / 'ordinary'
        shutil.copytree(source, ordinary, symlinks=True)
        for p in ordinary.rglob('*'):
            if p.is_symlink():
                p.unlink()
        emit('exfat.img', lambda p: run(makefs, '-t', 'exfat', '-s', '32m', p, ordinary))
        def ntfs(p):
            with p.open('wb') as f:
                f.truncate(32 * 1024 * 1024)
            run('mkntfs', '-F', '-Q', '-L', 'INF_TEST_NTFS', p)
            for name in ('README.txt', 'binary.bin', 'empty.txt', 'run.sh'):
                run('ntfscp', '-f', p, source / name, '/' + name)
            for i in range(20):
                run('ntfscp', '-f', p, source / f'small-files/file-{i:03}.txt', f'/file-{i:03}.txt')
            actual = subprocess.check_output(['ntfscat', str(p), '/README.txt'])
            assert actual == payloads['README.txt']
        emit('ntfs-populated.img', ntfs)
        def gzip_ntfs(p):
            with (fixture_path('ntfs-populated.img')).open('rb') as src, gzip.open(p, 'wb') as out:
                shutil.copyfileobj(src, out)
        emit('ntfs-populated.img.gz', gzip_ntfs)
        emit('hfs-populated.img', lambda p: hfs_image(hfslib, source, p))
        def gzip_hfs(p):
            with (fixture_path('hfs-populated.img')).open('rb') as src, gzip.open(p, 'wb') as out:
                shutil.copyfileobj(src, out)
        emit('hfs-populated.img.gz', gzip_hfs)
        # Wrap an existing user FAT16 image read-only; never modify its contents.
        fat16 = fixture_path('fat16.img')
        if fat16.exists():
            for suffix, opener in [('gz', gzip.open), ('bz2', bz2.open), ('xz', lzma.open)]:
                def compressed_fat(p, open_compressed=opener):
                    with fat16.open('rb') as src, open_compressed(p, 'wb') as out:
                        shutil.copyfileobj(src, out)
                emit(f'fat16.img.{suffix}', compressed_fat)
    # Hash the generator's fixture inventory, excluding other user files.
    manifest_path = ROOT / 'manifest.json'
    previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    # Retain fixtures produced by the HFS+/HFSX generator.
    manifest = {name: value for name, value in previous.items() if (ROOT / name).is_file()}
    for name in sorted(fixture_names):
        p = fixture_path(name)
        if p.is_file():
            with p.open('rb') as stream:
                manifest[p.relative_to(ROOT).as_posix()] = {'bytes': p.stat().st_size, 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}
    (ROOT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('New fixtures:', len(records))


if __name__ == '__main__':
    main()

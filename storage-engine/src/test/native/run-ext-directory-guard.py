#!/usr/bin/env python3
"""Inject a stale native directory buffer and verify the private mutation is rejected.

Compiles disposable test-only native libraries; never edits project images or sources.
Requires Termux clang, JDK, patchelf, python3 and e2fsprogs.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
native = root / 'storage-engine/src/main/cpp'
java_home = Path('/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk')

def run(args, allowed=(0,)):
    result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode not in allowed:
        raise RuntimeError(f'{args[0]} exited {result.returncode}:\n{result.stdout}')
    return result

with tempfile.TemporaryDirectory(prefix='if-ext-guard-') as temporary:
    work = Path(temporary)
    generator = work / 'fixture.py'
    shutil.copyfile(root / 'filesystem-test-images/generate-ext-metadata-fixture.py', generator)
    run(['python3', generator])
    fixture = work / 'filesystems/linux/ext4-metadata-tests.img'
    classes = work / 'classes'
    classes.mkdir()
    run(['javac', '-d', classes, Path(__file__).with_name('ExtNative.java')])
    index = (native / 'third_party/lwext4/src/ext4_dir_idx.c').read_text()
    anchor = '\t/* Fill the whole block with empty entry */'
    assert index.count(anchor) == 1
    index = index.replace(anchor, '\tmemset(new_block.data, 0xa5, block_size); /* simulated stale buffer */\n' + anchor)
    index = index.replace('\tmemset(new_block.data, 0, block_size);\n', '', 1)
    index = index.replace('\text4_dir_en_set_inode(be, 0);\n', '', 1)
    index = index.replace('\text4_trans_set_block_dirty(new_block.buf);',
                          '\text4_dir_en_set_inode(be, 0);\n\text4_trans_set_block_dirty(new_block.buf);', 1)
    variant = work / 'ext4_dir_idx.c'
    variant.write_text(index)
    backend = (native / 'ext_backend.c').read_text()
    check = '        CHECK(verify_new_directory(source_path), "verify new ext directory checksum");\n'
    assert backend.count(check) == 1
    sources = [native / 'ext_jni.c', native / 'ext_metadata.c'] + sorted(
        p for p in (native / 'third_party/lwext4/src').glob('*.c')
        if p.name not in ('ext4_mkfs.c', 'ext4_mbr.c', 'ext4_dir_idx.c'))
    for guarded in (False, True):
        name = 'guarded' if guarded else 'unguarded'
        directory = work / name
        directory.mkdir()
        back = directory / 'ext_backend.c'
        back.write_text(backend if guarded else backend.replace(check, ''))
        lib = directory / 'libinfiniext.so'
        run(['clang', '-std=gnu11', '-O2', '-fPIC', '-shared', '-DCONFIG_USE_DEFAULT_CFG=1',
             '-DCONFIG_DEBUG_PRINTF=0', '-DCONFIG_DEBUG_ASSERT=0', '-DCONFIG_BLOCK_DEV_CACHE_SIZE=64',
             '-I' + str(native), '-I' + str(native / 'third_party/lwext4/include'),
             '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
             back, variant, *sources, '-Wl,-z,max-page-size=16384', '-Wl,-soname,libinfiniext.so', '-o', lib])
        run(['patchelf', '--remove-rpath', lib])
        image = directory / 'image.img'
        shutil.copyfile(fixture, image)
        command = 'mkdir-corrupt-rejected' if guarded else 'mkdir'
        result = run(['java', '-Djava.library.path=' + str(directory), '-cp', classes,
                      'com.infinifolder.archive.ExtNative', command, image])
        print(name + ': ' + result.stdout.strip(), flush=True)
        if not guarded:
            check_result = run(['e2fsck', '-fn', image], allowed=(4,))
            if 'fails checksum' not in check_result.stdout:
                raise AssertionError('Fault injection did not reproduce the directory checksum defect')
            print('unguarded: e2fsck reproduced the bad directory checksum', flush=True)
print('New-directory checksum guard rejected the injected defect.')

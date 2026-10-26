#!/usr/bin/env python3
"""Exercise the shipped JNI bridge on disposable image copies, then run e2fsck.
Requires Termux JDK, e2fsprogs and a built libinfiniext.so. Never edits fixtures.
"""
from pathlib import Path
import hashlib
import shutil
import subprocess

root = Path(__file__).resolve().parents[4]
work = root / 'build/ext-integration'
work.mkdir(parents=True, exist_ok=True)
classes = work / 'classes'
classes.mkdir(exist_ok=True)
lib = root / 'storage-engine/build/extNative/arm64-v8a'
subprocess.run(['javac', '-d', str(classes), str(Path(__file__).with_name('ExtNative.java'))], check=True)
java = ['java', '-Djava.library.path=' + str(lib), '-cp', str(classes), 'com.infinifolder.archive.ExtNative']
payload = work / 'payload.bin'
payload.write_bytes(bytes(range(256)) * 1031)

def run(args, ok=(0,)):
    result = subprocess.run([str(a) for a in args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    with (work / 'results.log').open('a') as log:
        log.write('$ ' + ' '.join(str(a) for a in args) + '\n' + result.stdout + '\n')
    if result.returncode not in ok:
        raise RuntimeError(f'{args[0]} exited {result.returncode}:\n{result.stdout}')
    return result

def checksum(path):
    return hashlib.sha256(path.read_bytes()).digest()

(work / 'results.log').write_text('Ext native integration: disposable images only\n')
for kind in ('ext2', 'ext3', 'ext4'):
    original = root / f'filesystem-test-images/filesystems/linux/{kind}.img'
    before = checksum(original)
    image = work / f'{kind}-exercise.img'
    shutil.copyfile(original, image)
    run(java + ['exercise', image, payload])
    run(['e2fsck', '-fn', image])
    for entry in ('data-🌍.bin', 'subtree/child'):
        dump = work / (kind + '-' + entry.replace('/', '-') + '.bin')
        if dump.exists(): dump.unlink()
        run(['debugfs', '-R', f'dump /destination/{entry} {dump}', image])
        assert checksum(dump) == checksum(payload), f'{kind}: contents differ'
    for command in [
        'set_inode_field /destination/data-🌍.bin mode 0100640',
        'set_inode_field /destination/data-🌍.bin uid 123',
        'set_inode_field /destination/data-🌍.bin gid 456',
        'ea_set /destination/data-🌍.bin user.infini preserved',
        'link /destination/data-🌍.bin /destination/hardlink.bin',
        'set_inode_field /destination/data-🌍.bin links_count 2',
        'symlink /pointer /destination',
    ]:
        run(['debugfs', '-w', '-R', command, image])
    run(java + ['metadata', image, payload])
    stat = run(['debugfs', '-R', 'stat /destination/metadata-moved.bin', image]).stdout
    assert '0640' in stat and '123' in stat and '456' in stat and 'Links: 2' in stat, stat
    attributes = run(['debugfs', '-R', 'ea_list /destination/metadata-moved.bin', image]).stdout
    assert 'user.infini' in attributes and 'preserved' in attributes, attributes
    run(['debugfs', '-w', '-R', 'link /destination/subtree/child /destination/outside-hardlink.bin', image])
    run(['debugfs', '-w', '-R', 'set_inode_field /destination/subtree/child links_count 2', image])
    run(java + ['unlink-shared', image])
    retained = work / (kind + '-retained-hardlink.bin')
    if retained.exists(): retained.unlink()
    run(['debugfs', '-R', f'dump /destination/outside-hardlink.bin {retained}', image])
    assert checksum(retained) == checksum(payload), 'Recursive deletion damaged an outside hardlink'
    run(['e2fsck', '-fn', image])
    fault = work / f'{kind}-fault.img'
    shutil.copyfile(original, fault)
    run(java + ['fault', fault, original])
    assert checksum(original) == before, 'User fixture was modified'
    print(f'{kind}: mutations, inode-preserving moves, fsck, contents and I/O failure cleanup passed', flush=True)

# Journal replay after actual process death mid-write, using the same image engine.
for kind in ('ext3', 'ext4'):
    image = work / f'{kind}-crash.img'
    shutil.copyfile(work / f'{kind}-exercise.img', image)
    # Keep the crash target separate from the metadata-preservation check above.
    run(['debugfs', '-w', '-R', 'link /destination/metadata-moved.bin /destination/data-🌍.bin', image])
    run(['debugfs', '-w', '-R', 'set_inode_field /destination/data-🌍.bin links_count 3', image])
    run(java + ['crash', image, payload], ok=(77,))
    run(java + ['recover', image])
    run(['e2fsck', '-fn', image])
    print(f'{kind}: journal recovery after process death and clean fsck passed', flush=True)

# Unsupported feature bits and filesystem errors must not be presented as writable.
for feature, offset, mask in [('mmp', 96, 0x100), ('bigalloc', 100, 0x200), ('errors', 58, 2)]:
    image = work / f'reject-{feature}.img'
    shutil.copyfile(root / 'filesystem-test-images/filesystems/linux/ext2.img', image)
    with image.open('r+b') as stream:
        stream.seek(1024 + offset)
        value = int.from_bytes(stream.read(4), 'little') | mask
        stream.seek(1024 + offset); stream.write(value.to_bytes(4, 'little'))
    before = checksum(image)
    run(java + ['reject', image])
    assert checksum(image) == before, 'Rejected probe changed the image'
print('All ext integration checks passed. Details: ' + str(work / 'results.log'))

# Corrupt an allocated inode without updating its checksum. It must fail cleanly,
# without crashing native cleanup or preventing later use of an independent image.
original = root / 'filesystem-test-images/filesystems/linux/ext4.img'
image = work / 'corrupt-inode.img'
shutil.copyfile(original, image)
with image.open('r+b') as stream:
    stream.seek(1024); sb = stream.read(1024)
    block_size = 1024 << int.from_bytes(sb[24:28], 'little')
    inode_size = int.from_bytes(sb[88:90], 'little')
    stream.seek(2048 if block_size == 1024 else block_size)
    descriptor = stream.read(64)
    table = int.from_bytes(descriptor[8:12], 'little') | int.from_bytes(descriptor[40:44], 'little') << 32
    offset = table * block_size + inode_size + 2
    stream.seek(offset); value = stream.read(1)[0]
    stream.seek(offset); stream.write(bytes([value ^ 1]))
run(java + ['corrupt', image, original])
print('Corrupt inode rejection and native reuse passed')

#!/usr/bin/env python3
"""Create reproducible FAT recovery damage cases; never change existing fixtures.

These intentionally edited images are evidence about recovery limitations, not
claims that free clusters contain original data. All offsets are volume-relative.
"""
import bz2
import gzip
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import shutil
import struct
import tempfile
from fixture_paths import ROOT, update_manifest

OUT = ROOT / 'filesystems/windows/recovery/safety'
BASE = OUT.parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Volume:
    def __init__(self, data, bits):
        self.data = bytearray(data)
        self.bits = bits
        self.bps = self.u16(11)
        self.cluster_size = self.bps * self.data[13]
        self.reserved = self.u16(14)
        self.copies = self.data[16]
        self.fat_length = (self.u16(22) or self.u32(36)) * self.bps
        self.fat_start = self.reserved * self.bps
        self.root_count = self.u16(17)
        self.data_start = self.fat_start + self.copies * self.fat_length + ((self.root_count * 32 + self.bps - 1) // self.bps) * self.bps
        self.root = self.data_start + (self.u32(44) - 2) * self.cluster_size if bits == 32 else self.fat_start + self.copies * self.fat_length
        self.root_length = self.cluster_size if bits == 32 else self.root_count * 32
        self.total_clusters = ((self.u16(19) or self.u32(32)) * self.bps - self.data_start) // self.cluster_size
        self.next_record = next(p for p in range(self.root, self.root + self.root_length, 32) if self.data[p] == 0)
        self.cases = {}

    def u16(self, p):
        return struct.unpack_from('<H', self.data, p)[0]

    def u32(self, p):
        return struct.unpack_from('<I', self.data, p)[0]

    def put_fat(self, c, value, copies=None):
        for index in range(self.copies) if copies is None else copies:
            base = self.fat_start + index * self.fat_length
            if self.bits == 12:
                at = base + c + c // 2
                previous = self.u16(at)
                struct.pack_into('<H', self.data, at, (previous & 15 | value << 4) if c & 1 else (previous & 0xf000 | value))
            elif self.bits == 16:
                struct.pack_into('<H', self.data, base + c * 2, value)
            else:
                struct.pack_into('<I', self.data, base + c * 4, value)

    @property
    def eoc(self):
        return {12: 0xfff, 16: 0xffff, 32: 0xfffffff}[self.bits]

    def cluster(self, c, payload):
        assert len(payload) <= self.cluster_size
        at = self.data_start + (c - 2) * self.cluster_size
        self.data[at:at+self.cluster_size] = payload.ljust(self.cluster_size, b'\0')

    def record(self, name, start, size, deleted=True, attr=0x20, address=None):
        base, _, ext = name.partition('.')
        short = (base.ljust(8) + ext.ljust(3)).encode('ascii')
        assert len(short) == 11
        b = bytearray(32)
        b[:11] = short
        if deleted:
            b[0] = 0xe5
        b[11] = attr
        if self.bits == 32:
            struct.pack_into('<H', b, 20, start >> 16)
        struct.pack_into('<H', b, 26, start & 65535)
        struct.pack_into('<I', b, 28, size)
        at = self.next_record if address is None else address
        assert at + 32 <= self.root + self.root_length, 'fixture root requires expansion'
        self.data[at:at+32] = b
        if address is None:
            self.next_record += 32
        return at

    def case(self, name, start, chunks, outcome, original=None, detail=''):
        payload = b''.join(chunks)
        address = self.record(name, start, len(payload))
        for offset, data in enumerate(chunks):
            self.cluster(start + offset, data)
        recovered = bytes(self.data[self.data_start+(start-2)*self.cluster_size:self.data_start+(start-2)*self.cluster_size+len(payload)])
        original = payload if original is None else original
        self.cases[name] = {'slot': address, 'start_cluster': start, 'size': len(payload), 'outcome': outcome,
                            'original_sha256': sha(original), 'assumed_contiguous_sha256': sha(recovered), 'detail': detail}


def save(name, data, description):
    path = OUT / name
    if path.exists():
        print('Keep existing:', name)
        return
    with path.open('xb') as f:
        f.write(data)
    description['sha256'] = sha(data)
    description['bytes'] = len(data)
    EXPECTED[name] = description
    MANIFEST.write_text(json.dumps(EXPECTED, indent=2) + '\n')
    print('Created:', name)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for bits in (12, 16, 32):
        v = Volume((BASE / f'fat{bits}-deleted.img').read_bytes(), bits)
        # FAT32 root has just 16 slots in the baseline; use a fresh root sector
        # for adversarial entries and retain one known valid live root chain.
        v.data[v.root:v.root+v.root_length] = bytes(v.root_length)
        v.next_record = v.root
        n = v.cluster_size
        v.case('GOOD.TXT', 40, [b'Known intact single-cluster recovery.\n'], 'unverified-intact')
        v.case('ALLOC.BIN', 50, [b'R' * n, b'S' * n], 'blocked', detail='First cluster reused by a live file')
        v.put_fat(50, v.eoc)
        v.record('OWNER.BIN', 50, n, deleted=False)
        v.case('FATCOPY.BIN', 60, [b'F' * n], 'blocked', detail='Only the second FAT marks this cluster allocated')
        v.put_fat(60, v.eoc, [1])
        v.case('FRAGLIVE.BIN', 80, [b'A' * n, b'L' * n], 'blocked', original=b'A' * n + b'B' * n,
               detail='Original chain 80 -> 82; intervening cluster 81 belongs to a live file')
        v.cluster(82, b'B' * n)
        v.put_fat(81, v.eoc)
        v.record('GAPOWNER.BIN', 81, n, deleted=False)
        v.case('FRAGFREE.BIN', 90, [b'A' * n, b'G' * n], 'unverified-wrong-bytes', original=b'A' * n + b'B' * n,
               detail='Original chain 90 -> 92 was freed; gap 91 is also free. Fragmentation is undetectable from surviving metadata.')
        v.cluster(92, b'B' * n)
        v.case('PARTIAL.BIN', 100, [b'X' * (n // 2) + b'O' * (n - n // 2)], 'unverified-wrong-bytes', original=b'O' * n,
               detail='Payload partially overwritten while FAT remains free')
        v.case('OVERWR.BIN', 110, [b'X' * n], 'unverified-wrong-bytes', original=b'O' * n,
               detail='Payload fully overwritten while FAT remains free')
        v.record('OUTSIDE.BIN', v.total_clusters + 2, n)
        v.cases['OUTSIDE.BIN'] = {'outcome': 'blocked', 'detail': 'First cluster outside volume'}
        v.record('EMPTY.TXT', 0, 0)
        v.cases['EMPTY.TXT'] = {'outcome': 'unverified-intact', 'size': 0, 'original_sha256': sha(b''), 'assumed_contiguous_sha256': sha(b'')}
        deleted_dir = v.record('GONE', 120, 0, attr=0x10)
        # Deleted directory's contents are deliberately not reachable live directories.
        at = v.data_start + (120 - 2) * n
        child = bytearray(32); child[:11] = b'\xe5HILD   TXT'; child[11] = 0x20
        struct.pack_into('<H', child, 26, 121); struct.pack_into('<I', child, 28, 4)
        v.data[at:at+32] = child; v.cluster(121, b'lost')
        v.cases['GONE/CHILD.TXT'] = {'outcome': 'absent', 'detail': 'Deleted directory trees are deliberately excluded', 'slot': deleted_dir}
        reused = v.record('REUSED.TXT', 130, 4, deleted=False)
        v.put_fat(130, v.eoc); v.cluster(130, b'live')
        v.cases['REUSED.TXT'] = {'outcome': 'absent', 'slot': reused, 'detail': 'An old deleted record was replaced with a live file'}
        # A leading 0x05 is the live-name escape for 0xE5, not a deleted record.
        escaped = v.record('KANJI.TXT', 131, 4, deleted=False)
        v.data[escaped] = 5; v.put_fat(131, v.eoc); v.cluster(131, b'live')
        v.cases['KANJI.TXT'] = {'outcome': 'absent', 'detail': 'Live first-byte 0x05 escape must not be treated as deleted'}
        save(f'fat{bits}-adversarial.img', v.data, {'fat': bits, 'cases': v.cases})
        if bits == 16:
            for label, mutate in [
                ('dirty', lambda x: [x.put_fat(1, 0x7fff, [i]) for i in range(x.copies)]),
                ('live-cycle', lambda x: x.put_fat(50, 50)),
                ('mirror-disagreement', lambda x: x.put_fat(50, 51, [1])),
                ('invalid-geometry', lambda x: struct.pack_into('<H', x.data, 17, 0)),
                ('invalid-cluster-size', lambda x: x.data.__setitem__(13, 3)),
                ('invalid-boot-signature', lambda x: x.data.__setitem__(510, 0)),
            ]:
                bad = Volume(v.data, bits)
                mutate(bad)
                save(f'fat16-{label}.img', bad.data, {'outcome': 'scan-refused', 'reason': label})
        if bits == 32:
            active = Volume((BASE / 'fat32-deleted.img').read_bytes(), bits)
            struct.pack_into('<H', active.data, 40, 0x81)
            active.data[active.fat_start:active.fat_start+active.fat_length] = bytes(active.fat_length)
            save('fat32-active-second-fat.img', active.data, {'fat': 32, 'outcome': 'baseline-five-candidates', 'detail': 'FAT1 is active; inactive FAT0 is stale and empty'})
            inactive = Volume(active.data, bits)
            # An allocated value in an explicitly inactive FAT is stale, not a live allocation.
            inactive.put_fat(4, inactive.eoc, [0])
            save('fat32-stale-inactive-fat.img', inactive.data, {'fat': 32, 'outcome': 'baseline-five-candidates', 'detail': 'FAT1 active; FAT0 contains stale allocation for a deleted file'})
            struct.pack_into('<H', active.data, 40, 0x82)
            save('fat32-invalid-active-fat.img', active.data, {'outcome': 'scan-refused', 'reason': 'Active index is past the two FAT copies'})
    partial = Volume((BASE / 'fat16-deleted.img').read_bytes(), 16)
    partial.data[partial.root:partial.root+partial.root_length] = bytes(partial.root_length)
    partial.next_record = partial.root
    n = partial.cluster_size
    for name, start, header in [('MISMATCH.PNG', 40, b'%PDF-1.7\n'), ('MATCH.PNG', 42, b'\x89PNG\r\n\x1a\n')]:
        partial.record(name, start, n * 2)
        partial.cluster(start, header.ljust(n, b'P'))
        partial.cluster(start + 1, b'CURRENT OWNER DATA'.ljust(n, b'X'))
        partial.put_fat(start + 1, partial.eoc)
        partial.record(f'OWNER{start}.BIN', start + 1, n, deleted=False)
    save('fat16-magic-partial.img', partial.data, {'outcome': 'explicit-partial-only',
        'detail': 'Two allocated second clusters must be zero-filled. MATCH.PNG has a PNG signature; MISMATCH.PNG has a PDF signature. Neither header verifies the payload.'})
    tail = Volume((BASE / 'fat16-deleted.img').read_bytes(), 16)
    tail.record('TAIL.BIN', tail.total_clusters + 1, tail.cluster_size * 2)
    tail.cluster(tail.total_clusters + 1, b'T' * tail.cluster_size)
    save('fat16-partial-tail.img', tail.data, {'outcome': 'explicit-partial-only', 'detail': 'First cluster inside volume; second outside. Keep first and zero-fill second.'})
    # Reuse independently generated MBR/GPT/APM writer; sources are never modified.
    spec = importlib.util.spec_from_file_location('partition_generator', ROOT / 'generate-partitioned.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    source = 'filesystems/windows/recovery/fat16-deleted.img'
    for table, kind in [('MBR', 0x06), ('GPT', 'ebd0a0a2-b9e5-4433-87c0-68b6b72699c7'), ('APM', 'DOS_FAT_16')]:
        name = table.lower() + '-fat16-deleted.img'
        if not (OUT / name).exists():
            with tempfile.TemporaryDirectory(dir=OUT) as tmp:
                path = Path(tmp) / name
                layout = module.make_disk(path, table, [(source, kind, 'Recovery FAT16')])
                save(name, path.read_bytes(), {'outcome': 'baseline-five-candidates', **layout})
    for name, offset in [('gpt-bad-primary-crc.img', 512 + 16),
                         ('gpt-bad-entry-crc.img', 1024 + 16),
                         ('gpt-bad-backup-crc.img', -512 + 16)]:
        disk = bytearray((OUT / 'gpt-fat16-deleted.img').read_bytes())
        disk[offset] ^= 1
        save(name, disk, {'outcome': 'scan-refused', 'reason': 'GPT checksum damage', 'modified_byte': offset})
    disk = bytearray((OUT / 'mbr-fat16-deleted.img').read_bytes())
    struct.pack_into('<I', disk, 446 + 12, len(disk) // 512)
    save('mbr-outside-volume.img', disk, {'outcome': 'scan-refused', 'reason': 'Partition extends beyond the disk'})
    disk = bytearray((OUT / 'mbr-fat16-deleted.img').read_bytes())
    disk[462:478] = disk[446:462]
    save('mbr-overlapping-volumes.img', disk, {'outcome': 'scan-refused', 'reason': 'Two FAT partitions overlap'})
    for extension, compress in [('gz', gzip.compress), ('bz2', bz2.compress), ('xz', lzma.compress)]:
        data = (BASE / 'fat16-deleted.img').read_bytes()
        save(f'fat16-deleted.img.{extension}', compress(data), {'outcome': 'baseline-five-candidates', 'uncompressed': '../fat16-deleted.img'})
        data = (OUT / 'mbr-fat16-deleted.img').read_bytes()
        save(f'mbr-fat16-deleted.img.{extension}', compress(data), {'outcome': 'baseline-five-candidates', 'uncompressed': 'mbr-fat16-deleted.img'})

    update_manifest(BASE.rglob('*'))


if __name__ == '__main__':
    MANIFEST = OUT / 'expected.json'
    EXPECTED = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    main()

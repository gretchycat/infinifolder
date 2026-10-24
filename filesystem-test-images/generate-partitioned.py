#!/usr/bin/env python3
"""Wrap existing volumes in complete disk images without changing source files."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import tempfile
import uuid
import zlib
from fixture_paths import ROOT, fixture_path

SECTOR = 512
ALIGNMENT = 2048


def align(value):
    return (value + ALIGNMENT - 1) // ALIGNMENT * ALIGNMENT


def mbr_entry(kind, start, length):
    return struct.pack('<B3sB3sII', 0, b'\xfe\xff\xff', kind, b'\xfe\xff\xff', start, length)


def place(out, parts):
    cursor = ALIGNMENT
    layout = []
    for name, kind, label in parts:
        source = fixture_path(name)
        count = (source.stat().st_size + 511) // 512
        out.seek(cursor * SECTOR)
        with source.open('rb') as inp:
            shutil.copyfileobj(inp, out)
        layout.append({'source': source.relative_to(ROOT).as_posix(), 'first_lba': cursor,
                       'sectors': count, 'label': label, 'type': kind,
                       'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
        cursor = align(cursor + count)
    return layout, cursor + ALIGNMENT


def make_disk(path, table, parts):
    with path.open('w+b') as out:
        layout, sectors = place(out, parts)
        out.truncate(sectors * SECTOR)
        if table in ('MBR', 'GPT'):
            mbr = bytearray(512)
            if table == 'MBR':
                for index, p in enumerate(layout):
                    mbr[446 + index*16:462 + index*16] = mbr_entry(p['type'],p['first_lba'],p['sectors'])
            else:
                mbr[446:462] = mbr_entry(0xee, 1, sectors-1)
            mbr[510:512] = b'\x55\xaa'
            out.seek(0); out.write(mbr)
        if table == 'GPT':
            entries = bytearray(128*128)
            for index, p in enumerate(layout):
                at = index*128
                entries[at:at+16] = uuid.UUID(p['type']).bytes_le
                entries[at+16:at+32] = uuid.uuid5(uuid.NAMESPACE_URL, f'infinifolder:{path.name}:{index}').bytes_le
                struct.pack_into('<QQQ',entries,at+32,p['first_lba'],p['first_lba']+p['sectors']-1,0)
                name = p['label'].encode('utf-16le')
                entries[at+56:at+56+len(name)] = name
            disk_id = uuid.uuid5(uuid.NAMESPACE_URL, 'infinifolder:'+path.name).bytes_le
            def header(current, alternate, array_lba):
                h = bytearray(512)
                struct.pack_into('<8sIIIIQQQQ16sQIII',h,0,b'EFI PART',0x10000,92,0,0,current,alternate,34,sectors-34,disk_id,array_lba,128,128,zlib.crc32(entries))
                struct.pack_into('<I',h,16,zlib.crc32(h[:92]))
                return h
            out.seek(512); out.write(header(1,sectors-1,2))
            out.seek(2*512); out.write(entries)
            out.seek((sectors-33)*512); out.write(entries)
            out.seek((sectors-1)*512); out.write(header(sectors-1,1,sectors-33))
        elif table == 'APM':
            driver = bytearray(512)
            struct.pack_into('>HHI',driver,0,0x4552,512,sectors)
            out.seek(0); out.write(driver)
            rows=[{'first_lba':1,'sectors':63,'label':'Apple','type':'Apple_partition_map'},*layout]
            for index,p in enumerate(rows):
                b=bytearray(512)
                struct.pack_into('>HHIII',b,0,0x504d,0,len(rows),p['first_lba'],p['sectors'])
                for offset,text in [(16,p['label']),(48,p['type'])]:
                    encoded=text.encode('ascii'); b[offset:offset+len(encoded)]=encoded
                struct.pack_into('>III',b,80,0,p['sectors'],0x33)
                out.seek((index+1)*512);out.write(b)
    return {'table':table,'bytes':path.stat().st_size,'partitions':layout}


def main():
    folder=ROOT/'disks';folder.mkdir(exist_ok=True)
    microsoft='ebd0a0a2-b9e5-4433-87c0-68b6b72699c7'
    recipes=[
        ('mbr-windows.img','MBR',[('fat16.img',0x06,'FAT16'),('ntfs-populated.img',0x07,'NTFS')]),
        ('gpt-mixed.img','GPT',[('ext4.img','0fc63daf-8483-4772-8e79-3d69d8477de4','Linux ext4'),('ufs2.img','516e7cb6-6ecf-11d6-8ff8-00022d09712b','BSD UFS2'),('ntfs-populated.img',microsoft,'Windows NTFS')]),
        ('apm-apple.img','APM',[('hfsplus-populated.img','Apple_HFS','HFS Plus'),('hfsx-populated.img','Apple_HFS','HFSX')]),
    ]
    expected_path=folder/'expected-layout.json'
    expected=json.loads(expected_path.read_text()) if expected_path.exists() else {}
    names=[]
    with tempfile.TemporaryDirectory(prefix='disk-fixtures-',dir=folder) as tmp:
        work=Path(tmp)
        for name,table,parts in recipes:
            target=folder/name
            if not target.exists():
                staged=work/name
                description=make_disk(staged,table,parts)
                with staged.open('rb') as inp,target.open('xb') as out: shutil.copyfileobj(inp,out)
                expected[name]=description
                print('Created:',target.relative_to(ROOT))
            names.append(target)
            compressed=folder/(name+'.gz')
            if not compressed.exists():
                with compressed.open('xb') as out,gzip.GzipFile(filename='',fileobj=out,mode='wb',mtime=1700000000) as zipped,target.open('rb') as inp: shutil.copyfileobj(inp,zipped)
            names.append(compressed)
    expected_path.write_text(json.dumps(expected,indent=2)+'\n')
    manifest_path=ROOT/'manifest.json';manifest=json.loads(manifest_path.read_text())
    for p in names:
        with p.open('rb') as stream:
            manifest[p.relative_to(ROOT).as_posix()]={'bytes':p.stat().st_size,'sha256':hashlib.file_digest(stream,'sha256').hexdigest()}
    manifest_path.write_text(json.dumps(dict(sorted(manifest.items())),indent=2)+'\n')


if __name__=='__main__':
    main()

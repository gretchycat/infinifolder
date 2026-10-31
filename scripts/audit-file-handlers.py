#!/usr/bin/env python3
"""Observe decoder rejection behavior in the built APK, without changing original fixtures.

Exit zero means the audit completed, not that handlers are correct. Findings and
valid controls are recorded separately in file-handler-audit-results.json.
Requires the same Termux/Android SDK/JDK environment as the native runtime checks.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
INPUTS = OUT / 'file-handler-audit-inputs'
INPUTS.mkdir(parents=True, exist_ok=True)
cases = []


def control(family, ext):
    folder, stem = ('audio', 'c_major_piano.') if family == 'audio' else ('images', 'icon.')
    path = ROOT / 'filesystem-test-images' / folder / (stem + ext)
    cases.append((family + '-valid-' + ext, family, ext, path, 'control'))
    return bytearray(path.read_bytes())


def altered(name, family, ext, payload):
    path = INPUTS / (name + '.' + ext)
    path.write_bytes(payload)
    cases.append((name, family, ext, path, 'reject'))


for ext in ['au', 'aifc', 'caf', 'w64', 'rf64', '8svx', 'maud', 'voc', 'wav']:
    data = control('audio', ext)
    if ext == 'au':
        struct.pack_into('>I', data, 12, 0xffff)
    elif ext == 'aifc':
        start = data.index(b'COMM') + 26
        data[start:start + 4] = b'zzzz'
    elif ext == 'caf':
        start = data.index(b'desc') + 20
        data[start:start + 4] = b'zzzz'
    elif ext in ['w64', 'rf64', 'wav']:
        start = data.index(b'fmt ') + (24 if ext == 'w64' else 8)
        struct.pack_into('<H', data, start, 0xffff)
    elif ext == '8svx':
        data[data.index(b'VHDR') + 23] = 0x7f
    elif ext == 'maud':
        struct.pack_into('>H', data, data.index(b'MHDR') + 20, 0xffff)
    else:
        start = struct.unpack_from('<H', data, 20)[0]
        assert data[start] == 9, 'Expected the fixture new-format VOC block'
        struct.pack_into('<H', data, start + 4 + 6, 0xffff)
    altered('unknown-codec-' + ext, 'audio', ext, data)

data = control('image', 'exr')
pos = 8
compression = None
while data[pos]:
    end = data.index(0, pos)
    name = data[pos:end]
    pos = data.index(0, end + 1) + 1
    size = struct.unpack_from('<I', data, pos)[0]
    pos += 4
    if name == b'compression':
        compression = pos
    pos += size
assert compression is not None
data[compression] = 1
altered('unimplemented-exr-rle', 'image', 'exr', data)
data[compression] = 0
height = 192  # Original fixture dimensions, checked against its data window below.
window = data.index(b'dataWindow\0box2i\0') + len(b'dataWindow\0box2i\0') + 4
x0, y0, x1, y1 = struct.unpack_from('<iiii', data, window)
assert (x1 - x0 + 1, y1 - y0 + 1) == (192, height)
data[pos + 1:pos + 1 + height * 8] = bytes(height * 8)
altered('missing-exr-chunks', 'image', 'exr', data)

data = control('image', 'xcf')
marker = data.index(struct.pack('>II', 17, 1), 26) + 8
data[marker] = 2
altered('unsupported-xcf-compression', 'image', 'xcf', data)

data = control('image', 'psd')
pos = 26
for _ in range(3):
    size = struct.unpack_from('>I', data, pos)[0]
    pos += 4 + size
altered('missing-psd-pixels', 'image', 'psd', data[:pos] + b'\0\0')

data = control('image', 'hdr')
end = re.search(rb'-Y \d+ \+X \d+\n', data).end()
altered('missing-hdr-pixels', 'image', 'hdr', data[:end])
data = control('image', 'fits')
altered('truncated-fits-pixels', 'image', 'fits', data[:2881])
data = control('image', 'dds')
altered('truncated-dds-pixels', 'image', 'dds', data[:129])
data = control('image', 'pcx')
altered('truncated-pcx-pixels', 'image', 'pcx', data[:129])
data = control('image', 'tga')
altered('truncated-tga-pixels', 'image', 'tga', data[:19])
data = control('image', 'qoi')
altered('missing-qoi-pixels', 'image', 'qoi', data[:14] + b'\0\0\0\0\0\0\0\1')
altered('missing-ppm-pixels', 'image', 'ppm', b'P6\n2 2\n255\n')
data = control('image', 'tiff')
endian = '<' if data[:2] == b'II' else '>'
ifd = struct.unpack_from(endian + 'I', data, 4)[0]
entries = struct.unpack_from(endian + 'H', data, ifd)[0]
for index in range(entries):
    entry = ifd + 2 + index * 12
    if struct.unpack_from(endian + 'H', data, entry)[0] == 259:
        assert struct.unpack_from(endian + 'H', data, entry + 2)[0] == 3
        assert struct.unpack_from(endian + 'I', data, entry + 4)[0] == 1
        struct.pack_into(endian + 'H', data, entry + 8, 0xffff)
        break
else:
    raise AssertionError('TIFF fixture compression tag missing')
altered('unsupported-tiff-compression', 'image', 'tiff', data)
control('image', 'eps')
altered('missing-epsi-preview', 'image', 'eps',
    b'%!PS-Adobe-3.0 EPSF-3.0\n%%BeginPreview: 8 1 1 1\n%%EndPreview\n')
data = control('image', 'wmf')
pos = (22 if data[:4] == bytes.fromhex('d7cdc69a') else 0) + 18
while pos + 6 <= len(data):
    words, opcode = struct.unpack_from('<IH', data, pos)
    if opcode in {0x0f43, 0x0b41, 0x0d33}:
        dib = pos + {0x0f43: 28, 0x0b41: 26, 0x0d33: 24}[opcode]
        header, = struct.unpack_from('<I', data, dib)
        bits, = struct.unpack_from('<H', data, dib + 14)
        colors, = struct.unpack_from('<I', data, dib + 32)
        palette = (colors or 1 << bits) if bits <= 8 else 0
        altered('missing-wmf-dib-pixels', 'image', 'wmf', data[:dib + header + palette * 4])
        break
    assert words >= 3
    pos += words * 2
else:
    raise AssertionError('WMF fixture DIB record missing')
path = INPUTS / 'ascii.ans'
path.write_bytes(b'A')
cases.append(('ansi-stipple', 'image', 'ans', path, 'glyph'))
manifest = INPUTS / 'cases.tsv'
manifest.write_text(''.join('\t'.join(map(str, case)) + '\n' for case in cases))

SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
android = SDK / 'platforms/android-36/android.jar'
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classpath = [android, kotlin,
    ROOT / 'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar',
    ROOT / 'libraries/libinfinicanvas/canvas-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar',
    ROOT / 'libraries/libinfinisound/audio-api/build/libs/audio-api-0.2.0.jar',
    ROOT / 'libraries/libinfinisound/audio-core/build/libs/audio-core-0.2.0.jar',
    ROOT / 'libraries/libinfinisound/audio-processing/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar']
classes, dex = OUT / 'runtime-file-handler-audit-classes', OUT / 'runtime-file-handler-audit-dex'
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)
for item in classes.glob('FileHandlerAuditRuntimeCheck*.class'):
    item.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
    str(ROOT / 'scripts/runtime/FileHandlerAuditRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(android), '--output', str(dex)] +
    [item for path in classpath[1:] for item in ('--classpath', str(path))] +
    [str(path) for path in classes.glob('FileHandlerAuditRuntimeCheck*.class')], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / 'file-handler-audit.apk'
if apk.exists():
    apk.chmod(0o644)
shutil.copyfile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
observations = OUT / 'file-handler-audit-observations.json'
observations.unlink(missing_ok=True)
with (OUT / 'file-handler-audit-runtime.log').open('w') as log:
    subprocess.run(['/system/bin/app_process64', '/system/bin', 'FileHandlerAuditRuntimeCheck',
        str(manifest), str(observations)], env=env, stdout=log, stderr=subprocess.STDOUT, timeout=90, check=True)
rows = json.loads(observations.read_text())
assert len(rows) == len(cases)
findings = [row for row in rows if (row['probe'] == 'reject' and row['accepted']) or row.get('matchesCodeBitStipple', False)]
controls = [row for row in rows if row['probe'] == 'control']
failed_controls = [row for row in controls if not row['accepted']]
with apk.open('rb') as source:
    digest = hashlib.file_digest(source, 'sha256').hexdigest()
report = {'apkSha256': digest, 'cases': rows, 'findings': [row['id'] for row in findings],
    'failedControls': [row['id'] for row in failed_controls]}
(OUT / 'file-handler-audit-results.json').write_text(json.dumps(report, indent=2) + '\n')
for row in rows:
    label = 'FINDING' if row in findings else 'CONTROL' if row['probe'] == 'control' else 'REJECTED'
    print(label, row['id'], json.dumps(row))
print(f'Audit complete: {len(rows)} observations; {len(findings)} findings; {len(failed_controls)} failed controls')
print('APK SHA-256:', digest)
if failed_controls:
    raise SystemExit('Valid controls failed; review audit coverage before drawing conclusions')

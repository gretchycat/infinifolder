#!/usr/bin/env python3
"""Check libVLC native video parsing/decode/seek and Sound linkage in the full APK without UI bootstrap."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path)
parser.add_argument('--label', default='', help='Separate logs/artifacts for another APK, e.g. host')
args = parser.parse_args()
if args.label and not args.label.replace('-', '').isalnum():
    parser.error('label must contain only letters, numbers or hyphens')
tag = '-' + args.label if args.label else ''

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
ANDROID = SDK / 'platforms/android-36/android.jar'
classes = OUT / 'runtime-libvlc-classes'
dex = OUT / 'runtime-libvlc-dex'
classes.mkdir(parents=True, exist_ok=True)
import zipfile
vlc_aar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.videolan.android/libvlc-all/3.7.7').glob('*/*.aar'))
(OUT / 'vlc-research').mkdir(exist_ok=True)
with zipfile.ZipFile(vlc_aar) as z:
    (OUT / 'vlc-research/classes.jar').write_bytes(z.read('classes.jar'))
dex.mkdir(exist_ok=True)
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classpath = [ANDROID, kotlin,
    ROOT / 'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar',
    ROOT / 'libraries/libinfinivideo/video-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar',
    OUT / 'vlc-research/classes.jar']
for path in classes.glob('LibVlcRuntimeCheck*.class'):
    path.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
    str(ROOT / 'scripts/runtime/LibVlcRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(ANDROID), '--output', str(dex)] +
    [item for path in classpath[1:] for item in ('--classpath', str(path))] +
    [str(path) for path in sorted(classes.glob('LibVlcRuntimeCheck*.class'))], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / ('libvlc' + tag + '-runtime.apk')
if apk.exists():
    apk.chmod(0o644)
shutil.copyfile(args.apk or ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
native = OUT / ('runtime-libvlc' + tag + '-native')
native.mkdir(exist_ok=True)
for stale in native.glob('*.so'):
    stale.chmod(0o644)
    stale.unlink()
with zipfile.ZipFile(apk) as z:
    if args.apk is None:
        assert 'lib/arm64-v8a/libinfiniopenmpt.so' in z.namelist(), 'Full APK must include Sound'
    for name in z.namelist():
        if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
            target = native / Path(name).name
            if target.exists(): target.chmod(0o644)
            target.write_bytes(z.read(name))
            target.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
log = OUT / ('libvlc' + tag + '-runtime.log')
evidence = OUT / ('libvlc' + tag + '-runtime-evidence.log')
if evidence.exists(): evidence.unlink()
with log.open('w') as output:
    result = subprocess.run(['/system/bin/app_process64', '-Djava.library.path=' + str(native), '/system/bin', 'LibVlcRuntimeCheck',
        str(ROOT / 'filesystem-test-images/video'), str(OUT / 'libvlc-state'), str(native), str(evidence)], env=env, stdout=output,
        stderr=subprocess.STDOUT, timeout=60)
print(log.read_text(), end='')
report = evidence.read_text() if evidence.exists() else ''
print(report, end='')
if result.returncode == 0 and sum(line.startswith('PASS libvlc.') for line in report.splitlines()) != 7:
    raise SystemExit('Missing native check completion: libVLC redirects stdout/stderr to logcat; exit 0 alone is not acceptance')
with apk.open('rb') as source:
    print('APK SHA-256: ' + hashlib.file_digest(source, 'sha256').hexdigest())
raise SystemExit(result.returncode)

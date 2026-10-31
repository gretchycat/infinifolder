#!/usr/bin/env python3
"""Check image viewport matrices, visible pixels and crop placement in the full APK without UI bootstrap."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
ANDROID = SDK / 'platforms/android-36/android.jar'
classes = OUT / 'runtime-image-viewport-classes'
dex = OUT / 'runtime-image-viewport-dex'
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classpath = [ANDROID, kotlin,
    ROOT / 'viewer-common/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar']
for path in classes.glob('ImageViewportRuntimeCheck*.class'):
    path.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
    str(ROOT / 'scripts/runtime/ImageViewportRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(ANDROID), '--output', str(dex)] +
    [item for path in classpath[1:] for item in ('--classpath', str(path))] +
    [str(path) for path in sorted(classes.glob('ImageViewportRuntimeCheck*.class'))], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / 'image-viewport-runtime.apk'
if apk.exists():
    apk.chmod(0o644)
shutil.copyfile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
log = OUT / 'image-viewport-runtime.log'
with log.open('w') as output:
    result = subprocess.run(['/system/bin/app_process64', '/system/bin', 'ImageViewportRuntimeCheck',
        str(ROOT / 'filesystem-test-images/images')], env=env, stdout=output,
        stderr=subprocess.STDOUT, timeout=60)
print(log.read_text(), end='')
with apk.open('rb') as source:
    print('APK SHA-256: ' + hashlib.file_digest(source, 'sha256').hexdigest())
raise SystemExit(result.returncode)

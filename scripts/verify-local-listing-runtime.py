#!/usr/bin/env python3
"""Use the built APK's real local provider and inotify to check deferred content reads."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
ANDROID = SDK / 'platforms/android-36/android.jar'
classes, dex = [OUT / name for name in ('runtime-local-listing-classes', 'runtime-local-listing-dex')]
for path in (classes, dex):
    path.mkdir(parents=True, exist_ok=True)
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classpath = [ANDROID, kotlin,
    ROOT / 'libraries/libinfinistore/store-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar']
for path in classes.glob('LocalListingRuntimeCheck*.class'):
    path.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
    str(ROOT / 'scripts/runtime/LocalListingRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(ANDROID), '--output', str(dex)] +
    [item for path in classpath[1:] for item in ('--classpath', str(path))] +
    [str(path) for path in sorted(classes.glob('LocalListingRuntimeCheck*.class'))], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / 'local-listing-runtime.apk'
if apk.exists():
    apk.chmod(0o644)
shutil.copyfile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
with tempfile.TemporaryDirectory(prefix='local-listing-', dir=OUT) as work:
    result = subprocess.run(['/system/bin/app_process64', '/system/bin', 'LocalListingRuntimeCheck', work],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
print(result.stdout, end='')
if result.returncode or 'LOCAL_LISTING_COMPLETE' not in result.stdout:
    raise SystemExit('Local listing runtime checks did not complete')

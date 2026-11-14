#!/usr/bin/env python3
"""Verify the built relay manifest with Android's binary XML parser and PathPermission matcher."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, default=root / 'app-debug.apk')
parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', Path.home() / 'android-sdk')))
args = parser.parse_args()
android = args.sdk / 'platforms/android-36/android.jar'
runtime = Path('/system/bin/app_process64')
for path in (args.apk, android, runtime):
    if not path.is_file():
        parser.error(f'Missing required APK, SDK or Android runtime: {path}')
with tempfile.TemporaryDirectory(prefix='infini-selection-permissions-') as directory:
    work = Path(directory)
    classes, dex = work / 'classes', work / 'dex'
    classes.mkdir()
    dex.mkdir()
    subprocess.run(['javac', '--release', '17', '-cp', str(android), '-d', str(classes),
                    str(root / 'scripts/runtime/SelectionPermissionRuntimeCheck.java')], check=True)
    subprocess.run([str(args.sdk / 'build-tools/35.0.0/d8'), '--min-api', '26', '--lib', str(android),
                    '--output', str(dex), *map(str, classes.glob('*.class'))], check=True)
    (dex / 'classes.dex').chmod(0o444)
    apk = work / 'runtime.apk'
    shutil.copyfile(args.apk, apk)
    apk.chmod(0o444)
    env = os.environ.copy()
    env.pop('LD_PRELOAD', None)
    env.pop('LD_LIBRARY_PATH', None)
    env['CLASSPATH'] = str(dex / 'classes.dex')
    result = subprocess.run([str(runtime), '/system/bin', 'SelectionPermissionRuntimeCheck', str(apk)],
                            env=env, timeout=30)
    print('APK SHA-256:', hashlib.sha256(args.apk.read_bytes()).hexdigest(), flush=True)
    raise SystemExit(result.returncode)

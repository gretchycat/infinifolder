#!/usr/bin/env python3
"""Verify modular text reparsers on Android's real JsonReader, using Termux ART."""
from pathlib import Path
import os
import sys
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/text-reparser-runtime'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
ANDROID = SDK / 'platforms/android-36/android.jar'
OUT.mkdir(parents=True, exist_ok=True)
classes = OUT / 'classes'
classes.mkdir(exist_ok=True)
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
stream = ROOT / 'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar'
scribe = ROOT / 'libraries/libinfiniscribe'
modules = [scribe / f'scribe-reparse-{kind}/build/libs/scribe-reparse-{kind}-0.1.0.jar'
           for kind in ('api', 'xml', 'yaml', 'toml')]
modules += [scribe / 'scribe-reparse-json-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar',
            scribe / 'scribe-core/build/libs/scribe-core-0.1.0.jar']
def dependency(group, artifact, version):
    return next((Path.home() / f'.gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}').glob('*/*.jar'))
third_party = [dependency('org.yaml', 'snakeyaml', '2.4'), dependency('org.tomlj', 'tomlj', '1.1.1'),
               dependency('org.antlr', 'antlr4-runtime', '4.11.1'), dependency('com.google.code.gson', 'gson', '2.11.0')]
classpath = ':'.join(map(str, [ANDROID, kotlin, stream] + modules + third_party))
subprocess.run(['javac', '-classpath', classpath, '-d', str(classes), str(ROOT / 'scripts/runtime/TextReparserRuntimeCheck.java')], check=True)
program = OUT / 'reparser.jar'
with zipfile.ZipFile(program, 'w') as dest:
    for module in modules:
        with zipfile.ZipFile(module) as source:
            for name in source.namelist():
                if name.endswith('.class'):
                    dest.writestr(name, source.read(name))
    for item in classes.glob('*.class'):
        dest.write(item, item.name)
dex = OUT / 'classes.dex'
if dex.exists():
    dex.chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(ANDROID), '--output', str(OUT), str(program), str(kotlin), str(stream)] + [str(path) for path in third_party], check=True)
dex.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex)
subprocess.run(['/system/bin/app_process64', '/system/bin', 'TextReparserRuntimeCheck'] + sys.argv[1:], env=env, check=True, timeout=60)

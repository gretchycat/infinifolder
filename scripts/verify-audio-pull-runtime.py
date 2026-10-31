#!/usr/bin/env python3
"""Termux/device check of the built APK's PCM sources and seeks; playback is muted.

Requires a built full debug APK/library JARs, JDK, SDK 36/build-tools 36.0.0,
cached Kotlin stdlib 2.2.20, ffmpeg with libmp3lame, and Android app_process64.
All generated files remain in ignored build/infinistream-migration.
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
ANDROID = SDK / 'platforms/android-36/android.jar'
OUT.mkdir(parents=True, exist_ok=True)
classes = OUT / 'runtime-audio-pull-classes'
dex = OUT / 'runtime-audio-pull-dex'
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classpath = [ANDROID, kotlin,
    ROOT / 'libraries/libinfinisound/audio-api/build/libs/audio-api-0.2.0.jar',
    ROOT / 'libraries/libinfinisound/audio-core/build/libs/audio-core-0.2.0.jar',
    ROOT / 'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar',
    ROOT / 'libraries/libinfinisound/audio-processing/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar',
    ROOT / 'viewer-audio/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar']
for path in classes.glob('AudioPullRuntimeCheck*.class'):
    path.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
    str(ROOT / 'scripts/runtime/AudioPullRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--lib', str(ANDROID), '--output', str(dex)] + [item for path in classpath[1:] for item in ('--classpath', str(path))] + [str(path) for path in sorted(classes.glob('AudioPullRuntimeCheck*.class'))], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / 'audio-pull-runtime.apk'
if apk.exists():
    apk.chmod(0o644)
shutil.copyfile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None)
env.pop('LD_PRELOAD', None)
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
names = ['chord.mp3', 'chord.aac', 'chord.m4a', 'chord.wav', 'chord.flac', 'high-resolution.flac', 'full-resolution.flac', 'long', 'long-cbr', 'gapless']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('cases', nargs='*')
parser.add_argument('--file', action='append', type=Path, default=[], help='Check a local MP3 of at least 32 seconds without copying it into the repository')
args = parser.parse_args()
selected = args.cases or ([] if args.file else names)
if set(selected) - set(names):
    raise SystemExit('Unknown runtime case')
for name in selected:
    if name not in ('long', 'long-cbr'):
        continue
    codec = ['-c:a', 'libmp3lame', '-q:a', '4'] if name == 'long' else ['-c:a', 'libmp3lame', '-b:a', '320k']
    target = OUT / ('audio-pull-long.mp3' if name == 'long' else 'audio-pull-long-cbr.mp3')
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
        'anoisesrc=color=pink:sample_rate=44100:duration=900:seed=47', '-ac', '2'] + codec + ['-y', str(target)], env=env, check=True)
runs = [(name, []) for name in selected] + [('provided-' + str(index), ['provided', str(path.resolve())]) for index, path in enumerate(args.file)]
for name, extra in runs:
    log_path = OUT / ('audio-pull-' + name + '-runtime.log')
    with log_path.open('w') as log:
        result = subprocess.run(['/system/bin/app_process64', '/system/bin', 'AudioPullRuntimeCheck',
            str(ROOT)] + (extra or [name]), env=env, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    print(log_path.read_text(), end='', flush=True)
    if result.returncode:
        raise SystemExit(f'{name}: Android runtime exit {result.returncode}; see {log_path}')

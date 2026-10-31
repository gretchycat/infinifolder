#!/usr/bin/env python3
"""Check real libVLC PCM fallback and bundled FluidR3 from the final APK; no device output."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/infinistream-migration'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
android = SDK / 'platforms/android-36/android.jar'
kotlin = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/*.jar'))
classes, dex, native, work = [OUT / name for name in ('runtime-vlc-audio-classes', 'runtime-vlc-audio-dex', 'runtime-vlc-audio-native', 'runtime-vlc-audio-work')]
for path in (classes, dex, native, work):
    path.mkdir(parents=True, exist_ok=True)
classpath = [android, kotlin,
    ROOT / 'libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar',
    ROOT / 'libraries/libinfinisound/audio-api/build/libs/audio-api-0.2.0.jar',
    ROOT / 'libraries/libinfinisound/audio-core/build/libs/audio-core-0.2.0.jar',
    ROOT / 'libraries/libinfinisound/audio-processing/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar']
for path in classes.glob('LibVlcAudioRuntimeCheck*.class'):
    path.unlink()
subprocess.run(['javac', '-classpath', ':'.join(map(str, classpath)), '-d', str(classes),
                str(ROOT / 'scripts/runtime/LibVlcAudioRuntimeCheck.java')], check=True)
if (dex / 'classes.dex').exists():
    (dex / 'classes.dex').chmod(0o644)
subprocess.run([str(SDK / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib', str(android), '--output', str(dex)] +
    [item for path in classpath[1:] for item in ('--classpath', str(path))] +
    [str(path) for path in classes.glob('*.class')], check=True)
(dex / 'classes.dex').chmod(0o444)
apk = OUT / 'libvlc-audio-runtime.apk'
if apk.exists(): apk.chmod(0o644)
shutil.copyfile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
apk.chmod(0o444)
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
            target = native / Path(name).name
            if target.exists(): target.chmod(0o644)
            target.write_bytes(archive.read(name)); target.chmod(0o444)
    for part in range(3):
        assert archive.getinfo(f'assets/soundfonts/FluidR3_GM.sf2.part{part}.bz2').compress_type == zipfile.ZIP_STORED
# SMF format 0, 480 PPQN, program change + one quarter-note + end-of-track.
for name, program, channel, note in [('piano', 0, 0, 60), ('flute', 73, 0, 60), ('low-piano', 0, 0, 36), ('drums', 0, 9, 36)]:
    events = bytes([0, 0xc0 | channel, program, 0, 0x90 | channel, note, 100,
                    0x83, 0x60, 0x80 | channel, note, 0, 0, 0xff, 0x2f, 0])
    (work / (name + '.mid')).write_bytes(b'MThd' + (6).to_bytes(4, 'big') + b'\0\0\0\1\1\xe0' + b'MTrk' + len(events).to_bytes(4, 'big') + events)
env = os.environ.copy()
env.pop('LD_LIBRARY_PATH', None); env.pop('LD_PRELOAD', None)
# A real source larger than the PCM callback queue exercises close under backpressure.
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
    'sine=frequency=440:sample_rate=44100:duration=120', '-ac', '2', '-c:a', 'wmav2', '-b:a', '128k',
    str(work / 'long.wma')], env=env, check=True)
# Independent reference frame counts prevent an early EOF from looking successful.
reference_counts = []
for ext in ('wma', 'ra'):
    result = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(ROOT / 'filesystem-test-images/audio' / ('c_major_piano.' + ext)),
        '-map', '0:a:0', '-f', 's16le', '-'], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    assert len(result.stdout) % 2 == 0
    reference_counts.append(ext + '\t' + str(len(result.stdout) // 2))
(work / 'reference-samples.tsv').write_text('\n'.join(reference_counts) + '\n')
env['CLASSPATH'] = str(dex / 'classes.dex') + ':' + str(apk)
log = OUT / 'libvlc-audio-runtime.log'
with log.open('w') as output:
    result = subprocess.run(['/system/bin/app_process64', '-Djava.library.path=' + str(native), '/system/bin',
        'LibVlcAudioRuntimeCheck', str(ROOT), str(work), str(apk)], env=env, stdout=output, stderr=subprocess.STDOUT, timeout=180)
report = log.read_text()
print(report, end='')
if result.returncode or 'LIBVLC_AUDIO_COMPLETE controls=' not in report:
    raise SystemExit('libVLC audio/SoundFont checks did not complete')
print('APK SHA-256:', hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest())

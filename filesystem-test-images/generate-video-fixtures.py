#!/usr/bin/env python3
"""Generate original libVLC checks with FFmpeg; preserve existing fixtures."""
import json
import os
import subprocess
import zipfile
from fixture_paths import ROOT, update_manifest

OUT = ROOT / 'video'
OUT.mkdir(exist_ok=True)
ENV = os.environ.copy()
# Codex's bundled runtime can shadow the host FFmpeg's newer C++ runtime.
ENV.pop('LD_LIBRARY_PATH', None)


def encode(name, codec, duration=12, audio=True):
    path = OUT / name
    if path.exists():
        return
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-n',
        '-f', 'lavfi', '-i', f'testsrc2=size=320x180:rate=10:duration={duration}']
    if audio:
        command += ['-f', 'lavfi', '-i', f'sine=frequency=440:sample_rate=44100:duration={duration}']
    command += ['-c:v', codec, '-threads', '2', '-b:v', '24k' if duration > 12 else '160k', '-g', '20', '-pix_fmt', 'yuv420p']
    if audio:
        command += ['-c:a', 'libvorbis' if name.endswith('.webm') else 'aac', '-b:a', '48k']
    else:
        command += ['-an']
    if name.endswith('.mp4'):
        command += ['-movflags', '+faststart']
    subprocess.run(command + [str(path)], check=True, env=ENV)


encode('01-avc-aac.mp4', 'libx264')
encode('02-vp8-vorbis.webm', 'libvpx')
encode('90-six-minute-seek.mp4', 'libx264', 360, False)
subtitle = OUT / '03-embedded-subtitles.srt'
if not subtitle.exists():
    subtitle.write_text('1\n00:00:01,000 --> 00:00:05,000\nInfiniStream embedded subtitle check\n\n2\n00:00:06,000 --> 00:00:10,000\nSecond subtitle: café Ω\n', encoding='utf-8')
mkv = OUT / '03-avc-aac-subtitles.mkv'
if not mkv.exists():
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-n',
        '-i', str(OUT / '01-avc-aac.mp4'), '-i', str(subtitle), '-map', '0:v', '-map', '0:a', '-map', '1:0',
        '-c', 'copy', '-c:s', 'srt', str(mkv)], check=True, env=ENV)
invalid = OUT / 'invalid-video.mp4'
if not invalid.exists():
    invalid.write_bytes(b'\x00\x00\x00\x18ftypisom\x00\x00\x00\x00isommp42')
expected = OUT / 'video-expected.json'
if not expected.exists():
    expected.write_text(json.dumps({'dimensions': [320, 180], 'shortDurationMs': 12000,
        'longDurationMs': 360000, 'toneHz': 440, 'shorts': ['01-avc-aac.mp4', '02-vp8-vorbis.webm', '03-avc-aac-subtitles.mkv'],
        'seekPositionsMs': [10000, 240000, 300000, 350000], 'subtitleTimesSeconds': [[1, 5], [6, 10]]}, indent=2) + '\n')
archive = OUT / 'video-checks.zip'
if not archive.exists():
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(OUT.iterdir()):
            if path.suffix in {'.mp4', '.webm', '.mkv', '.json', '.srt'}:
                z.write(path, 'video/' + path.name)
update_manifest([p for p in OUT.iterdir() if p.is_file()])

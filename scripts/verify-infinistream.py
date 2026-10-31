#!/usr/bin/env python3
"""Build the full APK and run the library/application regression gate.

Default offline operation matches the existing source-build workflow. Known
pre-migration assertion failures remain visible; compilation/build failures,
new test failures/skips, missing cases and stale failed-test reports fail the gate.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
MODULES = [
    (':app:testDebugUnitTest', 'app/build/test-results/testDebugUnitTest'),
    (':viewer-csv:testDebugUnitTest', 'viewer-csv/build/test-results/testDebugUnitTest'),
    (':storage-engine:testDebugUnitTest', 'storage-engine/build/test-results/testDebugUnitTest'),
    (':viewer-common:testDebugUnitTest', 'viewer-common/build/test-results/testDebugUnitTest'),
    (':viewer-audio:testDebugUnitTest', 'viewer-audio/build/test-results/testDebugUnitTest'),
    (':viewer-image:testDebugUnitTest', 'viewer-image/build/test-results/testDebugUnitTest'),
    (':viewer-epub:testDebugUnitTest', 'viewer-epub/build/test-results/testDebugUnitTest'),
    (':libinfinistream:stream-core:test', 'libraries/libinfinistream/stream-core/build/test-results/test'),
    (':libinfinisound:audio-core:test', 'libraries/libinfinisound/audio-core/build/test-results/test'),
    (':libinfinistore:store-android:testDebugUnitTest', 'libraries/libinfinistore/store-android/build/test-results/testDebugUnitTest'),
    (':libinfinivault:vault-android:testDebugUnitTest', 'libraries/libinfinivault/vault-android/build/test-results/testDebugUnitTest'),
    (':libinfinicanvas:canvas-android:testDebugUnitTest', 'libraries/libinfinicanvas/canvas-android/build/test-results/testDebugUnitTest'),
    (':libinfiniscribe:scribe-core:test', 'libraries/libinfiniscribe/scribe-core/build/test-results/test'),
    (':libinfinivideo:video-api:test', 'libraries/libinfinivideo/video-api/build/test-results/test'),
]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--online', action='store_true', help='Allow Gradle dependency resolution from configured repositories')
args = parser.parse_args()
log = ROOT / 'build/infinistream-migration/verification-gate.log'
log.parent.mkdir(parents=True, exist_ok=True)
command = ['gradle', ':app:assembleDebug'] + [task for task, _ in MODULES] + ['--continue', '--console=plain', '--max-workers=2']
if not args.online:
    command.append('--offline')
started = time.time()
print('Building APK and running regression suites; log: ' + str(log), flush=True)
with log.open('w') as output:
    result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
text = log.read_text(errors='replace')
failed_tasks = set(re.findall(r'^> Task (:\S+) FAILED\s*$', text, re.MULTILINE))
known_task = ':storage-engine:testDebugUnitTest'
if result.returncode and (failed_tasks != {known_task} or 'There were failing tests.' not in text):
    print('Build/test infrastructure failed; the known baseline exception does not apply. See ' + str(log))
    sys.exit(1)
if result.returncode:
    reports = list((ROOT / dict(MODULES)[known_task]).glob('TEST-*.xml'))
    if not reports or any(path.stat().st_mtime < started - 1 for path in reports):
        print('Failed test reports are missing or stale; refusing to reuse prior results.')
        sys.exit(1)
comparison = subprocess.run([sys.executable, str(ROOT / 'scripts/check-migration-regressions.py')] +
    [str(ROOT / path) for _, path in MODULES], cwd=ROOT)
if comparison.returncode:
    sys.exit(comparison.returncode)
if result.returncode:
    print('Regression comparison passed; the full suite still contains the documented pre-existing failures.')
else:
    print('APK build and all regression suites passed.')

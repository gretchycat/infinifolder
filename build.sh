#!/usr/bin/env sh
set -eu
cd -- "$(dirname -- "$0")"
python3 scripts/fetch-dependencies.py
if [ -n "${TERMUX_VERSION:-}" ]; then
    exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :viewer-hex:testDebugUnitTest :storage-engine:testDebugUnitTest "$@"
fi
exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :viewer-hex:testDebugUnitTest "$@"

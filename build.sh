#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Configure Android SDK location if not already defined
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/android-sdk" ]; then
        export ANDROID_HOME="$HOME/android-sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi

# Ensure local.properties points to the SDK
if [ ! -f "local.properties" ] && [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

# In Termux, ensure user gradle.properties points to native aapt2 if present
if [ -x "/data/data/com.termux/files/usr/bin/aapt2" ]; then
    mkdir -p "$HOME/.gradle"
    if [ ! -f "$HOME/.gradle/gradle.properties" ] || ! grep -q "android.aapt2FromMavenOverride" "$HOME/.gradle/gradle.properties"; then
        echo "android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2" >> "$HOME/.gradle/gradle.properties"
    fi
fi

echo "Building InfiniFolder APK..."
gradle :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    echo "Build succeeded: $APK"
    if command -v xdg-open >/dev/null 2>&1; then
        echo "Launching APK with xdg-open..."
        xdg-open "$APK"
    else
        echo "xdg-open not found; APK is available at $APK"
    fi
else
    echo "Error: APK not found at $APK" >&2
    exit 1
fi

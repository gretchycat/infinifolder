#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_dir"

target="all"
open_apks=true
abi="auto"

usage() {
    cat <<'USAGE'
Usage: ./build.sh [--only TARGET] [--abi auto|all|ABI] [--open-apks|--no-open]

Targets:
  all            InfiniFolder, InfiniScribe, InfiniMedia, and InfiniKey if present (default)
  infinifolder   InfiniFolder only
  viewer-host    Optional combined viewer host for integration/debugging
  infiniscribe   InfiniScribe
  infinimedia    InfiniMedia
  infinikey      InfiniKey from the neighboring ../infinikey-ime checkout

--open-apks opens each built APK with xdg-open, pausing between APKs when interactive.
--no-open skips the installer launch and only builds/lists the APKs.
Built APKs are copied to ./packages/ with the app name and ABI in each filename.
--abi auto targets this device's CPU architecture (default); --abi all builds universal APKs.
Other Android ABIs can be selected explicitly, such as --abi x86_64.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --only)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            target="$2"
            shift 2
            ;;
        --open-apks|--send-apks)
            open_apks=true
            shift
            ;;
        --abi)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            abi="$2"
            shift 2
            ;;
        --no-open|--no-send)
            open_apks=false
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

case "$target" in
    all|infinifolder|viewer-host|infiniscribe|infinimedia|infinikey) ;;
    *) echo "Unknown build target: $target" >&2; usage >&2; exit 2 ;;
esac

if [ "$abi" = "auto" ]; then
    case "$(uname -m)" in
        aarch64|arm64) abi="arm64-v8a" ;;
        armv7l|armv8l) abi="armeabi-v7a" ;;
        x86_64|amd64) abi="x86_64" ;;
        i686|i386) abi="x86" ;;
        *) abi="all" ;;
    esac
fi
case "$abi" in
    all|arm64-v8a|armeabi-v7a|x86_64|x86) ;;
    *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;;
esac
gradle_abi_args=()
if [ "$abi" != "all" ]; then
    gradle_abi_args+=("-PtargetAbi=$abi")
fi

# Configure Android SDK location if not already defined.
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/android-sdk" ]; then
        export ANDROID_HOME="$HOME/android-sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi

if [ ! -f "$repo_dir/local.properties" ] && [ -n "${ANDROID_HOME:-}" ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$repo_dir/local.properties"
fi

# In Termux, prefer its native aapt2 when available.
if [ -x "/data/data/com.termux/files/usr/bin/aapt2" ]; then
    mkdir -p "$HOME/.gradle"
    if [ ! -f "$HOME/.gradle/gradle.properties" ] || ! grep -q "android.aapt2FromMavenOverride" "$HOME/.gradle/gradle.properties"; then
        printf '%s\n' 'android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2' >> "$HOME/.gradle/gradle.properties"
    fi
fi

run_gradle() {
    if ! gradle "${gradle_abi_args[@]}" "$@"; then
        echo "Gradle failed; stopping daemons and retrying once without a persistent daemon." >&2
        gradle --stop || true
        gradle --no-daemon "${gradle_abi_args[@]}" "$@"
    fi
}

apk_files=()
append_apk() {
    local apk_path="$1"
    local app_name="$2"
    if [ -f "$apk_path" ]; then
        local package_path="$repo_dir/packages/${app_name}-${abi}.apk"
        local temporary_path="${package_path}.tmp.$$"
        mkdir -p "$repo_dir/packages"
        cp -- "$apk_path" "$temporary_path"
        mv -f -- "$temporary_path" "$package_path"
        apk_files+=("$package_path")
    else
        echo "Expected APK was not produced: $apk_path" >&2
        return 1
    fi
}

# InfiniKey is maintained as a neighboring project. Build it when checked out beside
# InfiniFolder, without making this repository depend on a separate checkout.
key_project="$repo_dir/../infinikey-ime"
build_folder=false
build_viewer_host=false
build_scribe=false
build_media=false
build_key=false
case "$target" in
    all)
        build_folder=true
        build_scribe=true
        build_media=true
        build_key=true
        ;;
    infinifolder)
        build_folder=true
        ;;
    viewer-host) build_viewer_host=true ;;
    infiniscribe) build_scribe=true ;;
    infinimedia) build_media=true ;;
    infinikey) build_key=true ;;
esac

if [ "$build_folder" = true ]; then
    echo "Building InfiniFolder..."
    # Dropped large, uncompressed assets can remain as zero padding in an
    # incrementally rewritten APK. Start this package fresh so stale payload
    # space does not survive an asset replacement/removal.
    run_gradle :app:clean :app:assembleDebug
    append_apk "$repo_dir/app/build/outputs/apk/debug/app-debug.apk" "InfiniFolder"
fi

if [ "$build_viewer_host" = true ]; then
    echo "Building the standalone viewer host..."
    # As with InfiniFolder, removed uncompressed assets can leave large stale
    # holes in an incrementally rewritten APK.
    run_gradle :viewer-host:clean :viewer-host:assembleDebug
    append_apk "$repo_dir/viewer-host/build/outputs/apk/debug/viewer-host-debug.apk" "ViewerHost"
fi

if [ "$build_scribe" = true ]; then
    echo "Building InfiniScribe..."
    run_gradle -p "$repo_dir/infiniscribe" clean assembleDebug
    append_apk "$repo_dir/infiniscribe/build/outputs/apk/debug/InfiniScribe-debug.apk" "InfiniScribe"
fi

if [ "$build_media" = true ]; then
    echo "Building InfiniMedia..."
    run_gradle -p "$repo_dir/infinimedia" -Pviewer=audio :viewer-host:clean :viewer-host:assembleDebug
    append_apk "$repo_dir/infinimedia/build/viewer-host/outputs/apk/debug/viewer-host-debug.apk" "InfiniMedia"
fi

if [ "$build_key" = true ]; then
    if [ ! -f "$key_project/settings.gradle.kts" ]; then
        echo "InfiniKey checkout not found at $key_project" >&2
        exit 1
    fi
    echo "Building InfiniKey..."
    run_gradle -p "$key_project" assembleDebug
    while IFS= read -r -d '' apk_path; do
        append_apk "$apk_path" "InfiniKey"
    done < <(find "$key_project" -type f -path '*/build/outputs/apk/debug/*.apk' -print0 | sort -z)
fi

if [ "${#apk_files[@]}" -eq 0 ]; then
    echo "No APKs were produced." >&2
    exit 1
fi

printf '\nPackaged APKs in %s:\n' "$repo_dir/packages"
printf '  %s\n' "${apk_files[@]}"

if [ "$open_apks" = false ]; then
    echo "APK opening disabled (--no-open)."
    exit 0
fi

if ! command -v xdg-open >/dev/null 2>&1; then
    echo "xdg-open not found; APKs are listed above."
    exit 0
fi

for index in "${!apk_files[@]}"; do
    apk_path="${apk_files[$index]}"
    echo "Opening $(basename "$apk_path") with xdg-open..."
    xdg-open "$apk_path"

    if [ "$index" -lt "$(( ${#apk_files[@]} - 1 ))" ]; then
        if [ -t 0 ]; then
            read -r -p "Install or dismiss this APK, then press Enter to open the next one. " _
        else
            echo "No interactive terminal; waiting 3 seconds before the next APK."
            sleep 3
        fi
    fi
done

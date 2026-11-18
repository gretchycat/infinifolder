# Visual media fixtures

This directory is the standard image test corpus for Infinifolder. It includes
supported still formats, layered artwork, retro/scientific image samples,
animated formats, transformation references, and a deliberately invalid image.
Animation-specific samples and expected viewer behavior are in
[animation/README.md](animation/README.md).

## Render the corpus on Android

Build the debug app, then run from the repository root on an Android device or
emulator with access to this checkout:

~~~sh
python3 scripts/verify-visual-media-runtime.py
~~~

The harness runs Canvas in Android app_process, so it uses the platform codecs
and the same pinned Canvas/Stream byte decoder as the app. It attempts every
visual file under images/, including ORA archives with embedded IFF, FLI/FLC,
GIF, APNG, WebP, and MNG layers, plus standalone files in those formats.
ZIP bundles and fixture documentation/manifests are not rendered. Each fixture
must produce visible pixels; animation samples must expose a playback renderer
and custom frame renderers are sampled at two times. Animated GIF and WebP
fixtures must also contain colored poster and playback pixels so a black screen
cannot pass as visible output. The intentionally malformed
invalid-metadata.jpg must be rejected. The current icon.svg and icon.svgz samples
are reported as known unsupported until Canvas renders them; after adding support,
remove them from that list to make them required passes.

The harness writes its detailed log and runtime APK under
build/visual-media-runtime/. It requires the configured Android SDK/build tools,
an assembled app/build/outputs/apk/debug/app-debug.apk, and Android app_process64
access on the device. It does not modify fixtures.

Regenerate the animation samples with:

~~~sh
python3 scripts/generate-animation-fixtures.py
~~~

Pillow is required. Other image fixture generators and source manifests are
listed in [the filesystem test inventory](../README.md).

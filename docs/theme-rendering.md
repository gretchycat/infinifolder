# Theme rendering and ownership

## Application and library boundaries

* **libinfinicanvas:** `CanvasStreams.decodeBytes` uses content signatures and the existing codec registry for in-memory artwork. It preserves IFF color cycling and directly renders the Infini SVG palette extension. `BitmapFrame` exposes the bitmap and optional animation controller.
* **libinfiniui:** skin archive validation, logical sprite roles, `theme.json` mappings/layers, bitmap caching and lifecycle scheduling. The common `ImageAnimationPlayback` scheduler is now in ui-android; the optional ui-image screen uses the same class through its existing dependency. Media therefore does not need that screen to animate a skin.
* **Infinimedia:** the custom default artwork and attribution, theme selection, deterministic packaging and the player-specific full visual frame. VIDEO supplies the title; GEN supplies side borders and bottom corners. Scaling uses the player's 275-pixel grid, while the inner media stage retains 16:9 geometry. Title clicks retain collapse behavior.

These changes add no storage implementation dependency, application dependency or library cycle. The existing application/library dependency graphs remain valid.

## Default assets and metadata

The default theme is **Infinimedia Default**. Its metadata and chooser entry use that name. The stable generated asset filename remains `skins/Infini.wsz`; archive naming does not override the manifest's display name. `main.iff` is the corrected full opaque 275 × 116 faceplate, rather than a transparent logo overlay. Its source SHA-256 is `69b15911c5819e98d1a54c4cf58b59ea965bb4d1d08ab4ac34c5891b14c853b8`.

The original artwork attribution remains: design by Sven Kistner, classic version by Zarko Jovic and GuidoD, edited by Wildrose-Wally. The original copyright notice is retained. No new license is asserted for inherited artwork.

Only this custom skin is packaged. The four former bundled archives remain unchanged under Media's `test-assets/skins`, for compatibility tests and attribution. The SF3 bank remains under `test-assets/soundfonts`, outside APK source sets. Conversion scripts, backup images and authoring/debug files in `theme/` are excluded by the package builder. ZIP ordering and timestamps are deterministic.

New installations select the custom theme. Explicit imported skin or palette selections remain preferred. Saved references to removed bundled skin assets fall back to the application's default artwork. Importing other Winamp/XMMS skins remains supported.

## Image and animation interfaces

Classic logical names such as `main.bmp` no longer require BMP encoding. Without a manifest mapping, an exact role.bmp is preferred, followed by another role extension in stable order. Explicit mappings can use arbitrary filenames:

```json
{"name":"Infinimedia Default","images":{"main":"main.iff","video":["panel.png","title.svg"]}}
```

Each role accepts one to four same-canvas images, composited in order. Support is limited to codecs actually provided by Canvas and Android; an arbitrary filename is not a promise that every possible image encoding is available. Invalid manifests reject the import; undecodable artwork uses the existing visual fallback.

Archive limits: 512 entries, 8 MiB per entry and 24 MiB total expanded data. Skin images are limited to 2 million pixels each, with an aggregate 8 million pixel budget for layer/composite buffers. Hosts register an animation handle while resumed and close it while paused/destroyed. One shared worker renders skin frames; published frames are updated on the main thread. Closing the last host pauses work, and changing a theme discards queued old frames.

The SVG extension uses the namespace `https://infinicanvas.org/svg`, an indexed palette, disjoint cycle ranges and `palette-index` paint bindings. Its renderer retains vector geometry and does not quantize to 256 colors or recolor untagged equal-RGB paint. See [Canvas's schema and limits](https://github.com/gretchycat/libinfinicanvas/blob/9e169420b46d4d0c0e8cc28af4a1fffc0585e5a5/docs/svg-palette-cycling.md).

## Reproducible checks

From Media's own checkout:

```sh
python3 scripts/test-package-default-theme.py
./build.sh --max-workers=1 --console=plain
# Local Android 10+/Termux runtime, after the build:
python3 scripts/verify-theme-runtime.py
```

The runtime probe loads the actual built APK using app_process. Its isolated process initializes a real system font for AndroidSVG without bootstrapping an Activity. It verifies indexed SVG paints/direction, a palette above 256 colors, the supplied infini.svg, corrected IFF opacity/cycling, content-signature format detection, ordered transparent layers, bitmap lifetime, complete frame pixels at two scales, and cache play/pause/resume/clear. PNG frame previews are written under `build/theme-runtime`. A [recorded full-frame preview](validation/visual-media-frame-550.png) shows the assembled renderer output.

These checks do not establish Activity layout/touch behavior, video surface composition, audible playback or storage grants. Ubuntu testing and other-platform ports remain deferred. Executed build and raster results are recorded in the architecture audit and its validation evidence.

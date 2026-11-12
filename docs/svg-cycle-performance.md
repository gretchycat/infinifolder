# SVG color cycling performance correction

## Cause and implementation

The previous renderer rebuilt palette CSS and redrew the supplied SVG's 4,013 paths for every animation frame. Both image playback controllers then waited another 33 ms after rendering and publishing. At roughly 204 ms of rendering work per frame, a 24-step/second palette advanced several colors between visible updates.

Canvas now compiles flat indexed paints into a cached raster at the requested decode size. Setup renders the initial image plus black, white and palette-ID probes. The resulting arrays retain the initial pixels, animated positions, palette indices and coverage. Playback updates those pixels through the original palette; it does not redraw paths, rebuild CSS or allocate a new bitmap each frame. Parsed SVG/setup data are released. No full-frame sequence or disk conversion is generated.

Explicit marker IDs distinguish duplicate palette colors. Untagged equal-RGB artwork remains fixed. Palettes larger than 256 colors remain supported. Initial pixels, alpha, fixed gradients and clipping are captured from AndroidSVG. Mixed indexed-paint edges/overlaps are represented by a nearest palette index plus their original contribution, so later frames can differ slightly from a fresh vector render. Tagged animated gradient stops retain the vector fallback to preserve their continuous interpolation.

The optional FramePacedBitmapAnimation interface supplies a 16–33 ms interval for the cached renderer. Shared UI and Media's playlist-image controller count worker/publication time inside that interval. They retain existing pause, seek and cancellation behavior. Still-only color sampling skips SVG animation compilation and returns initial pixels without playback state; existing overload signatures are preserved. An extra native parity check found different static IFF preview pixels, so non-SVG formats now retain the established first-frame decoder before their playback state is discarded.

## Executed measurements

Same device, local Android app_process, the supplied 1800 × 900 SVG, eight warmup frames and 48 measured forced frames:

| Measurement | Previous vector path | Cached raster path |
| --- | ---: | ---: |
| Median CPU render time | 204.32 ms | 8.62 ms |
| 95th percentile | 223.55 ms | 8.79 ms |
| Maximum measured frame | 231.06 ms | 8.84 ms |
| One-time decode/setup | 514.00 ms | 1,477.72 ms |

The measured median improvement is about **24×**. These figures measure renderer work, not display FPS or end-to-end Activity performance. Raster setup adds a one-time loading cost. Actual scheduling can still skip steps if the OS stalls or a cycle exceeds the display's capacity.

The initial raster matched the vector reference exactly. At three later sampled times, whole-canvas mean RGB-channel errors were 0.046, 0.135 and 0.218 on the 0–255 scale; the largest sampled channel difference was 39 at blended pixels. This is an approximation, not a promise of pixel identity for every phase or arbitrary SVG. The original SVG source is unchanged.

## Verification

* Canvas built independently and passed 104 tests, including full rotations, reverse/multiple ranges, duplicate colors, coverage/alpha, unchanged-frame handling, nearest-color lookup, cancellation and phase boundaries.
* Shared UI and its optional image module built independently and passed 121 + 5 tests.
* Independent Media, Scribe and Folder checkouts built their APKs and passed 80, 17 and 143 tests respectively, with SDK variables unset, no local.properties, source-root guards and dependency-pin checks. Each passed 11 build-environment tests. Media packaging still passed its three tests.
* Fifteen Android runtime groups passed against the independently built Media APK. They include all 192 phases of the supplied SVG, complete 300-color rotations, duplicate IDs with opacity, still-only decoding, gradient fallback, IFF compatibility, unchanged frame chrome, composition and lifecycle cleanup.
* Both playback controllers passed a 12 ms render-work simulation: measured median intervals were 20 and 21 ms for a requested 20 ms interval. The previous completion-plus-delay scheduling would add that work to its delay.

Reproduce from Media's own checkout:

```sh
./build.sh --max-workers=1 --console=plain
python3 scripts/verify-theme-runtime.py
python3 scripts/verify-theme-runtime.py --benchmark
```

[Machine-readable evidence](validation/svg-cycle-performance.json) records commands, APK hashes, pins, test suites, timings and sampled image errors. Developer-only --canvas-override/--decoder-only options allow isolated decoder comparisons; the final APK verification above used neither override nor decoder-only mode.

## Architecture and limits

Canvas owns raster compilation, palette timing and the optional pacing hint. Shared UI owns common image scheduling and still palette sampling. Media owns its playlist-image scheduling and real SVG fixture/probe. Folder and Scribe only update their pinned library closure. No application-to-application dependency, storage implementation dependency or new asset bundle is introduced.

The four-megapixel animation bound and existing SVG metadata limits remain. A shared channel-coverage lookup uses about 512 KiB; raster state scales with decoded pixels rather than cycle length. Infinikey has no affected dependency and was not rerun. Visible UI, touch, video/audio and storage acceptance remain separate device tests. Ubuntu testing and other-platform ports remain deferred.

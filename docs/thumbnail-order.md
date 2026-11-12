# Authoring thumbnails and image navigation — 2026-11-11

## Problems found and corrections

- **ORA/KRA:** The image identity probe did not recognize their ZIP mimetype entries, so their container identity could win over image presentation. Canvas now validates the authoring mimetype and provides the correct renderer extension. Thumbnail requests stream a bounded preview/merged PNG rather than buffering the full project. Full viewing retains its existing merged-image route.
- **SVGZ:** Generic gzip handling could run before registered image validators. The old SVGZ validator also rejected any document expanding past 64 KiB, even when its SVG root was valid. The actual icon.svgz expands to 72,458 bytes. Core now consults registered validators first; Canvas identifies only a bounded decompressed root prefix. Ordinary gzip remains a container, and SVG rendering remains static.
- **EPS:** Content probing identifies application/postscript, while the original image association was image/x-eps. The application MIME had no explicit thumbnail declaration, so presentation could fall through to a binary/type icon. Canvas now declares the PostScript thumbnail alias. Folder also treats explicit decoder MIME aliases as thumbnail-capable despite stale binary/type icon settings. The existing EPS raster/preview decoder is retained.
- **Viewer order:** The viewer previously relisted its parent through the provider, losing the browser's sorting/filtering. Folder now captures the exact displayed image rows. Shared UI stores a private snapshot and passes an opaque token to its local image Activity, including the internal-image route in Open with. Next/previous and slideshow use that snapshot without relisting or resorting it. Missing/corrupt snapshots fall back to stable name order.
- **Audio defaults:** The existing Folder policy already selects installed Infinimedia when audio has no saved choice. New regression cases cover extensionless audio, generic MIME values, playlists and non-audio exclusion. Existing saved choices remain preferred; Media must be installed/enabled and handle the request. These are Folder defaults, not changes to Android's system defaults.

## Ownership and limits

Canvas owns format identity/decoding; Core consults its registered capability interface without depending on Canvas. Shared UI owns the navigation snapshot and image Activity. Folder supplies displayed order and icon presentation policy. Media owns the headless runtime probe; Scribe receives the shared library corrections. Store/Vault received only compatible Core/Store pin updates. No repository dependency edge, storage boundary or application dependency was added. All source locks and Folder gitlinks are aligned.

ZIP thumbnails are bounded to 32 MiB encoded input, 128 MiB expanded scanning, 4,096 entries and 16 MiB per preview; PNG decoding samples to the requested dimensions. This avoids whole-project materialization, but does not promise previews located beyond the scan budget. The existing full-viewer input limits remain. SVGZ root validation is bounded to 64 KiB expanded prefix; it does not require a whole-document expansion. SVG cycling remains removed; IFF cycling remains supported.

Navigation snapshots retain opaque URI/name/MIME values in the same app's private cache. They are limited to 8 MiB, 100,000 image rows and 32 files. This keeps large lists out of Binder extras and survives Activity recreation while cached. A snapshot grants no URI permissions and is not a cross-application protocol. Explicit external apps retain their own navigation behavior.

EPS support remains the existing ImageMagick raster EPS, EPSI and binary-preview subsets. The actual filesystem-test-images/images/icon.eps is byte-identical to Canvas's checked-in fixture (SHA-256 c6dde9cc6355e68229caa4737f12b035d604abcaec799af1cbe5289f7a1d8eef). Its real decoder route now produces the expected 96 × 96 artwork. This does not add a general PostScript interpreter for arbitrary vector EPS documents.

## Executed checks

| Component | Standalone build | Tests |
| --- | --- | ---: |
| Canvas | Debug AAR passed | 96 passed |
| Core | Debug AAR passed | 140 passed |
| Shared UI / optional image UI | Debug AARs passed | 130 + 5 passed |
| Store | Debug AAR passed | 119 passed; 1 real-server SFTP case skipped |
| Vault | Debug AAR passed | 48 passed |
| Infinifolder | Independent build.sh / debug APK passed | 150 passed, including 91 native storage cases |
| Infinimedia | Independent build.sh / debug APK passed | 80 passed |
| Infiniscribe | Independent build.sh / debug APK passed | 17 passed |
| Infinikey | Unchanged; not rerun | Earlier results remain historical |

Each app also passed 11 build-environment cases. These were incremental runs in the existing independent user checkouts with SDK variables unset, no local.properties, source-root guards and source-pin validation. All four apps retain their own build.sh. [Build instructions](android-builds.md).

The default Android app_process probe passed 16 groups against the independently built Media APK. Running it with the original SVGZ fixture passed **17 groups**. These checks rendered the real EPS/ORA/KRA fixtures through opaque content URIs, verified their identities and thumbnail MIME routes, and rendered the original 72,458-byte-expanded SVGZ plus a larger synthetic SVGZ. All four 96 × 96 outputs were visually inspected and show the expected artwork. Existing IFF cycling, static SVG, frame assembly, cache lifecycle, saved-default roundtrip, packaged audio intents and both scheduler deadlines remain passing. Both controllers measured 34 ms median intervals in the final run with simulated 20 ms render work.

Queue unit tests cover snapshot roundtrips, arbitrary/reversed order, missing/truncated snapshots, traversal rejection, oversized input cleanup and cache limits. Folder integration tests derive queues from the same displayed rows for name, size, modified and hidden-file settings. Audio fallback classification and saved-choice precedence are covered separately. Media's three deterministic skin packaging tests passed.

APK inspection retained application/storage separation, the sole Media default skin and absence of SF3/project/EPS test fixtures in production packages. Native library lists are unchanged. Folder's resource-link step emitted No package ID diagnostics; the resulting APK assembled, and aapt2 dump badging read its package, labels and resources with no stderr. The capability CSV was regenerated from the verified Folder build: [425 current profiles](supported-types.csv).

[Executed evidence, test suites, skipped case, source pins and APK hashes](validation/thumbnail-order.json).

## Reproduce the Android probe

From the built Media checkout on Android/Termux:

```sh
python3 scripts/verify-theme-runtime.py
# Optional: also test the original SVGZ from the explicit fixture directory:
python3 scripts/verify-theme-runtime.py --image-fixtures /path/to/filesystem-test-images/images
```

The optional fixture directory is runtime test input; it is not required to build Media and no sibling application is a build dependency.

## Pushed implementation commits

| Repository | Commit |
| --- | --- |
| infinimedia | [aea7644](https://github.com/gretchycat/infinimedia/commit/aea764464e3d3c092d4688d3d59ad93297736a2e) |
| infiniscribe | [746855b](https://github.com/gretchycat/infiniscribe/commit/746855bf39d5a04daf275d6e82939aaebda59ece) |
| infinifolder | [2651f71](https://github.com/gretchycat/infinifolder/commit/2651f71bf4d62e7b737fbe6ad8fc919806234c7b) |
| libinfinicanvas | [c0b6c1e](https://github.com/gretchycat/libinfinicanvas/commit/c0b6c1ec7e5c3149c0ffb983fc1ae2a15c430a1a) |
| libinfinicore | [0f98a0e](https://github.com/gretchycat/libinfinicore/commit/0f98a0e9c94e9de0133e4e1ab53a1274b0fd33d8) |
| libinfiniui | [6072700](https://github.com/gretchycat/libinfiniui/commit/6072700abebbc7142120d78a44e544cfa958f8e7) |
| libinfinistore | [c4c29cb](https://github.com/gretchycat/libinfinistore/commit/c4c29cb9f47bc7ef9e0ab722cab49fc83005cfb8) |
| libinfinivault | [141ed43](https://github.com/gretchycat/libinfinivault/commit/141ed43d6d68ced92a313eb18f37ef378a124bea) |

The optional original-SVGZ probe extension is recorded at Media 9eb030b; it changes test tooling only, not the built APK. Later documentation commits record this report. No published history was rewritten and no repositories were created.

## Remaining validation

Visible grid refresh, Activity next/previous/slideshow interaction and Android resolver/Always UI still need handset acceptance; headless rendering and unit tests are not a claim of full UI automation. The skipped SFTP test requires a real configured server. Ubuntu testing, other-platform ports and future roadmap/TODO items remain deferred. Original user assets, backups, SVG authoring sources and the SVG-to-IFF converter are preserved.

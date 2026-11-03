# Font preview

Implemented 2026-11-03. Tap a supported font with built-in viewers enabled, or
choose InfiniFolder through Android Open-with. The combined standalone viewer and
`-Pviewer=font` builds also include this preview.

| Files | Backend |
| --- | --- |
| `.ttf` | TrueType font |
| `.otf` | OpenType font |
| `.ttc`, `.otc` | TrueType/OpenType collections, with a face selector |

The screen provides custom sample text, an 8–144 sp size control, alphabets,
numbers, punctuation and multilingual samples, plus a light/dark specimen toggle.
The selected face, sample, size, preview background and scroll offset survive
Activity recreation. Details show available family/style/full/PostScript names,
glyph count, units per em and weight. Variable fonts show their axes and render
the default instance; axis editing is not implemented.

## Ownership and actual open path

- **Store:** MIME associations/aliases and bounded signature detection. Font
  identification does not imply a ROM, archive, image or executable capability.
- **Scribe core:** `FontFormats` and `FontDocuments.inspect`. This reads SFNT table
  directories, names and variation metadata with bounds checks; it does not
  implement a new glyph decoder.
- **Scribe Android:** `FontTypefaces.open` delegates real shaping/rasterization to
  Android's [Typeface.Builder](https://developer.android.com/reference/android/graphics/Typeface.Builder),
  including collection indices. A failed load raises an error; no default font is
  substituted as a successful file load.
- **viewer-font:** native Android text widgets, collection selection and specimen
  controls. `MediaTypeDetector` → `ViewerCatalog` → `ViewerLaunch` connects the
  normal browser/shortcut/external-intent paths to `FontViewerActivity`.
- **viewer-common:** the existing storage adapter/cache stages provider and
  archive sources when a native decoder requires a file. `stageBounded` preserves
  the original unbounded `stage` contract for other viewers.

Canvas retains graphics/ANSI font rendering. No Canvas decoder or storage
implementation is copied into the font viewer.

Metadata follows the OpenType
[font-file](https://learn.microsoft.com/en-us/typography/opentype/spec/otff) and
[naming-table](https://learn.microsoft.com/en-us/typography/opentype/spec/name)
structures. Inspection is capped at 64 MiB, 256 faces, 4,096 tables/name records
per face and 64 variation axes. Table ranges and string offsets must remain
inside the supplied file. Reads/native font loads run on a single worker, with
stale-result rejection and cancellation on destruction. Local files are used
directly; cached sources retain a lease until the viewer closes. Unknown-length
streams also enforce the byte limit while staging, rather than after downloading
an arbitrarily large font. Permission, cache capacity and renderer failures show
an error without changing the source.

The shared MIME/icon registry and generated Android registrations include these
formats and legacy MIME aliases. Font icons use the document base and extension
label. Read-only font previews are not advertised as editors. WOFF/WOFF2, Type 1,
FON/FNT, PSF and other legacy bitmap/vector font containers have no new renderer.
Actual native support, color fonts and shaping depend on the Android device.
Android may supply fallback glyphs for characters missing from a loaded font;
the specimen explicitly explains this. Viewing never installs a system font.

## Build evidence and acceptance

`gradle :app:assembleDebug :viewer-host:assembleDebug --offline --console=plain`
builds the app and combined host. A separate
`gradle :viewer-host:assembleDebug -Pviewer=font --offline --console=plain` builds
the dedicated font host. Logs are under `build/font-viewer/`.

The owner confirmed local font preview, the size control, dark/light mode,
rotation and the navigation-bar layout on 2026-11-03. The owner also confirmed
that GEN files display the binary icon. These are user-reported device checks;
the agent had no connected ADB device during implementation.

`content://` font access remains unverified because the owner had no provider URI
available. Collection face selection, custom sample editing, archive/remote
sources, keyboard/status-bar layout, cancellation and failure cases were not
separately confirmed. No automated tests were added or run. Builds and packaged
manifest inspection are separate evidence from device interaction checks.

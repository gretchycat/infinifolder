# Independent viewer applications

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Each viewer is an Android library that depends on `:viewer-common` and `:storage-engine`, without depending on the browser or its Activity. Its manifest registers its viewer Activity/service. A separate application can include the library and launch it through `ViewerLaunch` with a resource entry.

The supplied `:viewer-host` provides a launcher document picker, Android VIEW/EDIT entry point for content URIs, recovery, and debug dump controls.

## Build

```sh
gradle :viewer-host:assembleDebug -Pviewer=pdf --offline
```

Replace `pdf` with `image`, `text`, `markdown`, `html`, `csv`, `epub`, `audio`, `video`, or `all` (default). The selected application's ID is `com.infinifolder.standalone.<kind>` and its APK is under `viewer-host/build/outputs/apk/debug/`. On ARM Termux use the configured native AAPT2 override if required, as for the main app.

Text, Markdown, HTML, and CSV include editing subject to complete load and source revision checks. Image edits export PNG copies. PDF/EPUB/audio/video currently expose viewing/playback. ACTION_EDIT is rejected for unsupported formats. A missing viewer library produces an explanatory message.

Audio parsing/processing is provided by the external `libinfinisound` build (`audio-api`, portable `audio-core` and Android `audio-processing`); the audio viewer retains playback/output, queue and Cast integration. See [audio library boundaries](audio-processing-library.md).

Shared UI/resources, image codecs, network backends, and themes currently remain common dependencies. Further size reduction can split those dependencies without bringing back a browser dependency. Document access and retained URI permissions still depend on the supplying Android provider. Standalone runtime use and editing require device acceptance checks in addition to build validation.

## Stream architecture checkpoint

The full app and standalone host compose pinned Stream/Store/Vault/Sound/Canvas/Scribe/Video source repositories. viewer-common retains Android contracts, source leases and icon/theme policy; image codecs are provided by Canvas and text/Markdown processing by Scribe. The image viewer requests a BitmapFrame with actual output metadata; text/HTML/CSV/Markdown loads keep complete original bytes for revision checks; CSV parsing and save serialization execute Scribe table processors while preserving the 8 MiB preview and guarded-save policy; video binds its staged source through the Video library while retaining current listeners and surfaces. Dependencies are still exported for compatibility: this checkpoint makes no APK-size or minimal-host claim.

### Discrete text and CSV viewing

Text and CSV viewers now request Scribe windows through common sources. Local,
SAF and protocol handles use real positional reads when available; Unsupported
can fall back to a bounded sequential stream, while genuine errors propagate.
Archive entries stay sequential because the legacy archive seek adapter can
extract a whole entry. No hidden document spool was added.

Text and CSV load previous/next windows automatically near the vertical scroll
edges. Text retains at most three adjacent 16 KiB/256-newline windows, preserves
a character/pixel anchor and resumes a fling after reflow. CSV retains at most
five 50-record windows, uses recycled native rows with stable source row IDs and
a fixed header, and restores the visible row/pixel offset when inserting or
trimming off-screen windows. The grid shows 16 columns at a time, with controls
for wider tables. Backward reads start at known character/record boundaries;
no unbounded page history or prefix scan is needed. Text keeps Go to byte offset
for positional sources. Forward-only inputs can revisit only cached ranges.
Filters and forward search run on the worker; stale loads are ignored and handles
close off the UI thread. Loose Android CSV MIME aliases select the grid, matching
archive CSV behavior. UI smoothness still requires handset acceptance.

The CSV counter tracks the visible source-row range rather than the cache size.
An independent background reader counts data records exactly in bounded windows;
the header is excluded, totals remain unknown until EOF, and filtered ranges keep
the original source row numbers. Counting closes/cancels independently of scrolling.

Only a complete source obtains an edit/save revision. Explicit editing keeps
the existing complete-document 4 MiB text/8 MiB CSV loading and publication path;
large-file editing is [roadmap work](roadmap.md#large-text-and-csv-editing). The large delivered fixtures remain read-only
and can now be browsed beyond the former prefix instead of laying out that whole
prefix in one Android widget. See the updated [fixture guide](../filesystem-test-images/documents/README.md).

### Shared source styling

Text viewing/editing and HTML/Markdown source modes now consume Scribe scope/style
descriptors through one shared Android adapter. See [syntax highlighting](syntax-highlighting.md)
for controls, theme import/export, supported lexical profiles and bounded-context limits.

# Independent viewer applications

Updated 2026-11-11. [Architecture](architecture.md) and the
[separation audit](architecture-audit.md) define current ownership and executed
verification. Use the [Android build guide](android-builds.md) for commands;
`viewer-host` variants and `-Pviewer=...` are retired development structures.

## Application and module ownership

| Owner | Application UI/modules |
| --- | --- |
| Infinifolder | File manager, `viewer-hex`, storage composition; uses UI's image viewer and reusable document/video thumbnails |
| Infiniscribe | Text, Markdown, HTML, PDF, CSV, EPUB/Office, font and legacy mind-map viewers; `document-common` shares app document staging |
| Infinimedia | Audio/video Activities, queue, visualizations, output and Cast integration |
| libinfiniui | `ui-android` shared source/UI support; optional `ui-image` image viewer |

Every application has its own repository and application ID. There is no combined
product host or sibling source build. The old Media ViewerHostActivity component
is retained as a manifest alias for compatibility. Folder opens document and
media resources through the appropriate independent app or Android external-app
choice; it does not include complete document/editor or player Activities.

Shared UI uses Core's local/SAF adapters by default. Folder installs its advanced
provider and consistency hooks through `ViewerStorage`. Scribe and Media do not
link Store/Vault. External sources use [libinfiniselect](libinfiniselect.md) and
Android content grants; native seekability is retained when available, and pipes
remain sequential. Staging required by an individual decoder belongs to that
consumer, not the storage boundary.

Stream owns viewer component/capability contracts. Canvas owns image decoding;
Scribe owns document backends and highlighting; Sound owns audio parsing,
decoding/synthesis/PCM; Video owns reusable video adapters. Applications own their
Activities, lifecycle and user interaction. ServiceLoader discovery operates only
on explicitly declared, packaged library artifacts.

Text, Markdown, HTML and CSV editing remains subject to complete-load and source
revision checks. Image edits export PNG copies. Font preview uses Scribe's
portable font tables and Android Typeface adapter. PDF/EPUB/audio/video behavior
and legacy format limits remain described in their format guides. Compilation
and unit tests do not establish handset playback, rendering or grant acceptance.

## Document behavior

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

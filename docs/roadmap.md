# Roadmap

Updated 2026-11-04. [Project status](project-status.md) records what is implemented
and accepted. [Feature to-do](feature-todo.md) is the actionable backlog; this page
explains direction and constraints rather than repeating completed checklists.

## Library and stream composition

Repository extraction and runtime discovery are implemented. Each library links
its dependencies and reports its handlers through Stream; consumers call the
library APIs directly. The current registry is for installed components, not a
downloaded code loader. See [runtime discovery](runtime-capabilities.md).

Continue exposing negotiating stream adapters around existing implementations,
including clearer positional/local-file requirements. Reuse Stream's processor
planner; do not implement another pipeline engine or duplicate decoders. Multi-port
ambiguity still needs explicit bindings. Desktop output/native packaging and iOS
backends remain separate work from JVM portability. Release licensing and native
source-build review are still required.

## Reliability before expansion

Prioritize the outstanding device matrix, SFTP small-file performance and listing
recovery, metadata/source retention, cache/lifetime consistency, and interrupted
container publication. Extend ordinary-operation durability and resumable controls
without weakening collisions, source checks or undo. Live VM copies are best effort,
not coherent snapshots. Details and evidence belong in the feature guides linked
from the [backlog](feature-todo.md).

## Large text and CSV editing

Windowed reading exists; complete-file editing remains limited to 4 MiB text and
8 MiB CSV/TSV. A future bounded edit model belongs in Scribe and must support edits
across Unicode/record boundaries, sparse indexes and undo/redo without retaining
the whole file. Publish complete results through revision-checked staged outputs;
a loaded window must never overwrite the rest of the source.

Accept beginning/middle/end edits, changed encoded lengths, quoted CSV newlines,
scroll-away/back, undo/redo, save/reopen, unchanged regions, cancellation, low space
and conflicts on disposable fixture 90/91 copies and supported remote/container
sources. Positional reads do not imply safe in-place mutation.

## Structured text formatting

JSON/XML/YAML/TOML reparsers are separate discoverable modules. Reparse is a
view-only, debug-only control before wrapping; editing restores original source.
Continue bounded preloading/indexing and global visual-row navigation without
weakening complete-file save guards. See [text formats](text-and-document-formats.md).

## Paged documents and Office formats

The read-only MOBI/KF8, FB2, Word, spreadsheet, presentation, OpenDocument and RTF
reading pass is implemented and broadly owner-confirmed. Extend real-document
coverage, layout fidelity and portable backends. Faithful pagination, charts,
equations, formula calculation, editing, encryption and DRM are not implied.
EPUB 3 NAV labels and lazy package serving remain follow-up work.

Full PostScript/EPS and LaTeX rendering are future Scribe backends; existing limited
raster previews remain. Reuse maintained libraries with bounded execution,
explicit resources/fonts and honest format failures. See [document readers](document-and-ebook-readers.md).

## Media and animations

Complete device acceptance for recovered FLC headers, FLC MIME aliases, interactive
MNG, native animation timelines and lifecycle changes. Keep unsupported animation
variants explicit; see [animation limits](image-animation.md).

Continuous Cast audio is implemented as normalized 48 kHz stereo 16-bit PCM WAVE,
with a 4 GiB RIFF limit and separate-track fallback. Receiver compatibility,
transitions/seeks/repeat and remote/archive failure behavior need real-receiver
acceptance. Broader transport/codec work must remain in the linked libraries.
ANSI music's softer waveform is already confirmed; additional dialects, malformed
input and synchronized-art presentation remain separate work.

## Cloud storage through rclone

Evaluate an optional rclone-backed storage handler with explicit packaging,
authentication, credential protection, capabilities, random access, mutation,
progress/cancellation and lifecycle rules. Existing working SFTP/FTP/SMB/WebDAV
backends need not be replaced. NFS/cloud storage is not implemented simply because
an icon or URI rule exists.

## Android integration and deferred features

A shared floating-browser/widget presentation must respect Android widget limits.
Floating browsing and launcher shortcuts exist; a general interactive widget does
not. Rule-based Downloads organization needs dry-run/conflicts and explicit trigger
policies. Most-used sorting needs stable identities and usage/privacy rules.

[Android content integration](infinifolder_android_content_integration.md) remains
a design proposal for virtual documents and translation. Runtime handler discovery
now supplies part of its foundation; universal export/conversion is not implemented.
Future work must link the real libraries and reuse their streams and typed outputs.

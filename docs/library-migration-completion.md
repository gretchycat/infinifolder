# Library migration completion — checkpoint 31

Scope: finish the existing Android application's library migration before new
product features. Authority: [implementation assignment](infinistream_architecture_codex.md).
Existing behavior, source/publication safety and supported formats are preserved.
The [checkpoint history](infinistream-migration.md) records repository URLs,
pinned commits, dependencies and earlier runtime evidence.

## Completion work

- [x] Seven standalone source repositories, pinned submodules and real application consumers.
- [x] Stream contracts, metadata, capability negotiation, bounded detection, pull planning and optional backpressured push.
- [x] Store/Vault separation, ordinary files without a mandatory Vault hop, explicit random-access capability loss.
- [x] Audio, image, video, text/document/CSV and optional reparser processing owned by specialist libraries.
- [x] Reparse and highlighting workflow confirmed on the handset.
- [x] Remaining transfer, fingerprint, save/recovery and external-edit byte loops use Stream.
- [x] Host processing/dependency audit complete; artwork cache decode/PNG paths moved to Canvas.
- [x] All seven standalone library builds pass (Store repeated after configuring its SDK).
- [x] All nine individual viewer hosts and the combined host build.
- [x] Final regression/runtime evidence and host pin commit recorded (`6f13255`).

## Final consumer changes

Stream supplies borrowed JVM transfers and explicitly bounded materialization.
Short reads are valid, zero progress is an error, oversized input fails rather
than becoming truncated success, cancellation is cooperative, and progress counts
successful writes. The owner still closes/flushes its handles and decides how to
publish or roll back partial output.

Store's file transfers, verification digests, disk-cache staging, replacement
snapshots and rollback use this primitive. `ContentRevision` accepts a borrowed
`ByteSource` with cancellation and progress; the InputStream API remains usable.
Interrupted replacement defers the existing thread interrupt during rollback and
recovery recording, then restores the interrupt flag. Recovery publishes from the
backup file without materializing the entire backup in RAM.

Host external edits, Termux working copies, recovery exports, new-file writes,
clipboard image transfer, torrent input and bounded theme/icon imports consume
Stream's copy/read helpers. Existing limits, lease ownership, fingerprints,
flush/sync, publication and failure cleanup remain at their established boundaries.
Canvas owns sampled cache-image decoding and the existing PNG encoder; the app
retains exact sidecar names, freshness and provenance checks.

## Audit of retained boundaries

| Retained code/API | Reason |
| --- | --- |
| StorageProvider / BlockDevice / File compatibility | Still used by providers, container implementations and native backends. Adapters preserve ownership and positional behavior; deleting them would break real callers. |
| StorageRuntime / RoutingStorageProvider / ExternalEdits | Host composition, credential/session lookup, fresh-read scopes and application recovery policy. Shared transfer and fingerprint work runs in the libraries. |
| MediaSourceAdapter / UniversalAudioDecoder | Source/lease composition and diagnostic forwarding to actual library processors; no second codec implementation. |
| ImageInspectorFields / playlist metadata callbacks | UI labels, queue policy and presentation of library output. |
| Android widgets, gestures, WebView, surfaces and output devices | Platform presentation and output boundaries. |
| Theme/skin config, native asset File copies and bounded HTTP socket/header handling | Application UI assets and transport protocol, with actual media processing delegated to libraries. |
| Legacy `com.infinifolder` packages inside libraries | Compatibility namespaces, not a dependency on the application. The pure Markdown renderer retains its old package. |

Dependency audit: all seven libraries use Stream; Store does not depend on Vault.
Vault uses Store contracts and Sound's portable audio API for playlist containers.
Sound/Canvas/Scribe/Video have no application/viewer dependency. Public transitive
exports still have callers and remain for compatibility. No unused parallel codec
or IO framework was introduced or removed by name alone.

## Verification

Final full gate: 917 observed cases, 887 passed, the same 30 known failures,
zero regressions/skips/missing protected cases. The protected checkpoint contains
915 cases; two additional passing Cast cases belong to unrelated workspace edits.
Twelve new cases cover borrowed transfer ownership/errors/limits, revision
fingerprints, interrupted-save restoration image sampling arithmetic and the original compiled copy entry point.
The original failure baseline is unchanged.

The Android Canvas runtime harness passes 13 checks, including the moved cache
image path's original dimensions, legacy pixels, invalid input and transferred
bitmap ownership. Native cancellation traces in its output are expected assertions,
not suppressed failures. This harness does not launch widgets or prove live remote
server/device playback behavior.

Final build results and packaged-runtime hash are recorded in the
[verification snapshot](validation/library-migration-completion-checkpoint31.json).
The final APK also passes the JSON/XML/YAML/TOML runtime harness. The owner's
903,633-byte file reparses to 912,621 characters / 1,281 logical lines, with tokens
and source unchanged (129 ms in this local run, not a cross-device guarantee).
Local build logs live under `build/`; they are not source artifacts.

## Work outside this migration

The migration introduces no new language port, codec rewrite or binary blob.
Automatic arbitrary multi-input graph search, new formats/providers, paged editing,
new desktop/iOS backends, truly minimal per-viewer dependencies and broader ABI or
live-server/device acceptance remain separate capability work.

Original-code licensing still requires the owner's choice. Existing native/AAR
7-Zip, zstd, JPEG XL, JPEG2000 and libVLC dependencies retain their recorded source,
license and F-Droid build-review requirements. Finishing source extraction does not
resolve those release decisions. No remote publication is claimed for this final
local commit set; use the pinned revisions when publishing the libraries before
the host repository. Unrelated workspace edits remain outside the migration commits.

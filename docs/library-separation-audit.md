# Library separation audit

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Original audit reviewed 2026-10-29. The assessment and migration plan describe the architecture before extraction; implemented progress is recorded below. The plan builds on `architecture.md` and `viewer-modules.md` and preserves their existing storage and data-safety requirements.

## Audio extraction status — 2026-10-30

The first audio increment is implemented in `audio-api` and `audio-processing`, now hosted in the independent `libinfinisound` repository and imported as a pinned submodule/composite build; see [the library boundary and usage](audio-processing-library.md). Parsing, codec adapters, metadata probing and signal analysis have moved out of `viewer-audio`. Playback/output, source staging, queue lifetimes and Cast remain in the viewer. Storage shares the playlist parser through the small API without linking the processing implementations. The assessment below records the pre-extraction architecture. The InfiniStream assignment supersedes its proposed boundaries and sequence.

## Assessment

The split is feasible. There are already useful resource/provider contracts, separate Gradle viewer libraries, and a standalone viewer host. However, the libraries are currently separated mainly at the Activity level: shared dependencies and global runtime construction still tie them to most of the application. Extracting a small core requires moving those boundaries, not just moving source files into more Gradle modules.

The most useful distinction is between **what can be done** and **where or how it is implemented**. A viewer or file operation should request resources, streams, optional random access, or media services through contracts. A local disk, Android document provider, SSH server, mounted ZIP, FAT image, or other implementation supplies those contracts. An archive format parser consumes bytes; a mounted-archive adapter turns its entries into navigable resources; the archive's bytes may themselves come from any suitable provider. The same separation applies between media decoders, playback services, and viewer UI.

A smaller core and independently reusable libraries are achievable without immediately making everything Android-independent. Smaller installed APKs require additionally excluding unused modules and dependencies from a particular build; a library split alone does not do that. Downloadable runtime plugins or dynamic features would be a separate product decision.

## What exists today

- `StorageProvider` and the resource model express opaque references, capabilities, stream access, seekable access, batching, and error distinctions. This is a substantial foundation. The provider API currently exposes Android `Uri` and `File` and returns `archive.BlockDevice`; even ordinary local or network providers therefore depend on a type placed in the archive package.
- `StorageRuntime` creates local, archive, app, network, torrent, and virtual providers in one singleton and connects them to `RoutingStorageProvider`. The registry exists, but the runtime and router still name concrete implementations. Shared preferences, diagnostics, common locations, archive resolution, and virtual-root policy are also assembled there.
- Archive parsers, image filesystems, block devices, caching, mounted-provider logic, mutation batches, and several native implementations reside in `storage-engine`. `ArchiveReader` also refers to the storage disk cache. A module per format is not a mechanical move: dependencies and ownership must be untangled first.
- Every viewer is a separate Android library and `viewer-host` can select one without depending on `app`. However, every viewer uses `viewer-common`, which exports the entire `storage-engine` and SVG, JPEG XL, and JPEG 2000 dependencies. The standalone host is proof of a separate host, not proof of minimal dependencies or full runtime support for every source.
- `MediaSourceAdapter` is a useful common seam, but directly looks up `StorageRuntime`, interprets Android URIs, and stages content via the shared disk cache. Some viewer debug actions name `MainActivity` directly. `ViewerCatalog` contains a fixed activity-name list rather than a host-supplied set of installed viewers.
- Image codecs and thumbnail handling live mostly in `viewer-common`, while the image viewer is thin. Many codecs use Android bitmaps. Audio decoders, playlist handling, analysis, Android playback service, Cast, and themed UI live together in `viewer-audio`. `CastManager` depends on `AudioPlayerService`; the service and Activity are also tightly coupled. `MainActivity` is about 13,700 lines and combines browser UI with orchestration, making it another significant extraction target.
- The main app currently links all viewers and the single storage engine. These are ordinary compile-time Gradle libraries, not independently installed runtime extensions.

## Proposed boundaries

The original illustrative boundaries below are historical. The authoritative repository names are libinfinistream, libinfinistore, libinfinivault, libinfinisound, libinfinicanvas, libinfinivideo and libinfiniscribe; see the migration mapping. Arrows point from consumer to contract or implementation it chooses to include. Implementations must not depend back on the app or a particular viewer.

| Boundary | Responsibility | Depends on |
| --- | --- | --- |
| Resource and I/O API | Opaque resource IDs, metadata, capability and error results, streams, closeable random-access devices, ownership/lifetime rules | Small common types; initially Android URI adapters may live alongside or immediately outside it |
| Storage operations | Listing/navigation, copy/move/delete, transfer policy, revision and publication safety, cancellation and progress | Resource/I/O contracts, injected provider and recovery/cache services |
| Provider implementations | Local, SAF, SFTP, FTP, SMB, WebDAV, torrent, virtual catalogs | Contracts and their own protocol libraries; Android adapters where required |
| Container/format libraries | Archive and compression readers/writers, partition and filesystem-image readers/writers | Seekable/stream I/O contracts and only the relevant format/native libraries |
| Mounted-container adapter | Presents archive/image entries as storage resources; coordinates nested mounts and safe publication | Resource contracts, format libraries, host-provided source/cache services |
| Media source adapter | Resolves a resource to streams, seekable handles, or a leased local staging file as needed | Resource/I/O contracts and injected cache; Android URI bridge at the edge |
| Image libraries | Detection and decoding, metadata, thumbnail/transform operations; Android bitmap adapter where needed | Media source contracts and selected codecs; no viewer Activity |
| Audio libraries | Format detection/decoding and metadata, playlist parsing, PCM/analysis; separately Android playback and output routing/Cast adapters | Media source contracts and selected codecs; playback may depend on Android |
| Viewer libraries | UI, editing/display, interaction and viewer-specific lifecycle | Viewer launch contract plus relevant media/document services |
| File-manager core and app | Navigation/session and operation coordination in core; Android Activity, settings, registration and dependency assembly in app | Contracts and the implementations/viewers chosen by the host |

The core should not enumerate SFTP, ZIP, audio codecs, or viewer Activities. The Android app's composition root can register concrete providers, container handlers and viewers explicitly. A standalone viewer host can register a smaller set. Registration needs deterministic detection/priority, unavailable-format handling, and clear ownership of providers and open handles. Start with compile-time composition; discoverable or downloadable plugins can be assessed later if actually needed.

Android URI grants and intent behavior are real platform requirements. First extract a small Android-compatible contract and move URI conversion to a bridge where practical. If a truly portable Kotlin/JVM or multiplatform library is desired later, define a platform-neutral identity and byte-source API and adapt Android `Uri`, file descriptors, and bitmap/audio output at its boundary. This compatibility effort is now required by the InfiniStream assignment; Gradle module renaming alone does not provide it.

## High-risk seams and behavior to preserve

- **Provider semantics:** Preserve supported/unsupported/unknown distinctions, failures versus fallback, read/write/seek/resize capabilities, opaque identities, connection and permission behavior, and handle ownership. A provider-neutral block-device contract belongs below archives; its existing close, read-only, seek and resize semantics must remain intact.
- **Containers and writes:** Keep nested backing-resource identities and cycle guards. Preserve isolated mutation batches, revision checks, verification, publication and recovery, cleanup, cancellation, and read-only behavior. A format implementation must not publish directly around the existing safety path. FAT/ext/native image behavior and archive entries over remote or nested sources need particular care.
- **Cache and staging:** A viewer may need a real file even if its source is remote or archived. Pass a source/staging service with explicit closeable leases instead of introducing a separate cache in each library. Preserve cache pinning, invalidation, bounded space, and safe cleanup while playback, preload, Cast or an editor still holds a source. Prefer streams or random access where supported; staging remains an explicit fallback.
- **Audio:** Separate decoder/metadata/analysis APIs from service, UI, and Cast incrementally, with an Android playback adapter. Do not require decoding an entire track into memory merely to use the API. Preserve playlist expansion and metadata, filename ordering when shuffle is off, CUE track ranges, playback state, repeat/shuffle, and frequency/VU data. Protect the recently confirmed gapless path: preload and handoff, source and cache lifetimes, cancellation, and playback across local, network and archive sources. Silent PCM is valid audio; end-of-stream comes from decoder/source completion, not an all-zero buffer. Cast/output routing has its own state and source lifetime requirements.
- **Images and documents:** Move codec dispatch and thumbnails to image services; keep Android bitmap and URI adapters explicit. Verify detection, orientation, large-image memory limits, and existing formats. Editors need revision-checked save and recovery services, not just read streams. Shared themes and viewer launch/debug helpers should not require the browser or image codecs.
- **Android packaging:** JNI libraries, load names, ABI coverage, manifests, permissions, services, resources, minification rules and intent filters must follow the module that owns them. Termux native builds and standard Android/NDK builds both need verification. Audit third-party/native license obligations before distributing extracted libraries. Runtime and APK-size reductions must be measured on actual variants; dependency graph reduction alone is not an APK size measurement.

## Migration sequence

1. **Baseline and guardrails.** Record dependency graphs and APK sizes for the app and single-viewer hosts; identify current device-tested cases. Add focused contract/integration checks for safety-sensitive storage, viewer launch/edit, audio queue and staging lifetimes. Preserve existing tests and the user's confirmed gapless behavior. Exercise representative local, SAF, SSH, archive and nested sources on devices.
2. **Extract contracts and inject the runtime.** Move general resource and block-device types below archive/provider implementations. Introduce a narrow source/staging interface and a host-owned composition root, with a compatibility adapter to the current runtime. Keep behavior and the existing provider graph unchanged first. Replace hardcoded browser/debug callbacks and viewer lookup with host-supplied capabilities.
3. **Split shared viewer dependencies.** Separate viewer launch/UI/theme utilities from storage integration and image decoding. Make viewer dependencies explicit. Build a text-only and an image-only host and check both the actual packaged dependencies and launch/edit behavior. This is the smallest useful test of the proposed architecture.
4. **Extract providers and container engines.** Move one straightforward provider at a time behind the existing contract, then split archive/image formats from the mounted-provider adapter and safely reconnect them through injected source/cache services. Break cycles deliberately; add a provider or format only to hosts that need it. Check network connections and nested read/write/publication after each move.
5. **Extract media services.** Move image decoding and thumbnails into an image library with selected codec adapters. Split audio into format/metadata/analysis, playback and Cast/output, then retain the viewer as a consumer. Preserve queue ownership and gapless handoff throughout rather than rewriting playback at the same time.
6. **Slim the file-manager core.** Pull navigation, operations, and policy out of `MainActivity` behind small host-facing APIs. Keep Android Activity/lifecycle, views, dialogs, settings and registration in the app. Each viewer remains usable through its host contract. Measure build graph, packaged binaries and launch performance before claiming size or startup gains.

The sequence is incremental: the app remains functional at every stage, and individual modules are reusable as soon as their host contract is proven. There is no need to wait for a complete rewrite before gaining separation.

## Acceptance criteria

- A minimal host can use local/SAF resources and a text viewer without compiling or packaging network, torrent, archive, audio or image-codec implementations; deliberately adding an implementation makes it available without changing the core.
- An image host and an audio host each include their selected services and work without the file-manager Activity. Missing viewers or formats yield clear unsupported results.
- The full app retains current navigation, remote providers, nested containers, editing and mutation/recovery guarantees. Capability and failure semantics remain consistent between direct and routed calls.
- Audio device checks cover continuous local and network gapless transitions, metadata population, filename ordering, shuffle/repeat, CUE boundaries, seeking, interruption, Cast, and leased-file cleanup. Silence must play normally.
- Build and inspect relevant standalone and full-app variants on supported Android ABIs and both native build paths where available. Measure installed/download size separately from module count and run representative device tests; compilation alone does not prove runtime correctness.

## Validation of this audit

The findings above come from inspecting the current Gradle declarations and source boundaries. No application code was refactored as part of this audit. The offline `:viewer-host:assembleDebug -Pviewer=text` build succeeded. Its debug APK was 55,547,399 bytes and included 7-Zip, JPEG XL and OpenJPEG native libraries across multiple ABIs, confirming that the text-only host still packages unrelated implementations. This is a debug APK built in the current Termux environment, not an installed-size or release-size measurement. The build emitted AAPT2 resource diagnostics and compiler warnings; runtime behavior was not tested. The acceptance criteria describe future work rather than tests already passed.

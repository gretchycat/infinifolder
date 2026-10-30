# Infinifolder Library Extraction and Stream Architecture
## Implementation instructions for Codex

**Project:** `gretchycat/infinifolder`  
**Goal:** Separate reusable libraries into their own Git repositories and refactor infinifolder to compose them through a common, metadata-aware stream architecture.  
**Foundation:** `libinfinistream`  
**Priority:** Preserve functionality, enable random access, and keep libraries independent of the Android application.

> **Important:** This is an implementation assignment, not just an architecture proposal. Inspect the repository and its `AGENTS.md` instructions before touching code. Execute the migration incrementally, with compilable checkpoints and tests. Do not change languages or rewrite working codec implementations merely to establish the architecture.

---

## 1. Repository ownership and library boundaries

Create new repositories under the existing GitHub owner **as needed**, using the names below. Reuse existing repositories if they already exist; do not overwrite or recreate them. Determine the actual existing code layout before extracting modules.

| Repository | Responsibilities |
|---|---|
| **`libinfinistream`** | Small, platform-neutral interfaces and utilities for sources, sinks, positional I/O, metadata, capabilities, composition, filters, and optional push/pull processing. No application-specific implementation. |
| **`libinfinistore`** | Local/device and remote storage backends, resource opening, file operations, link/shortcut resolution, and source/sink adapters. Android-dependent adapters may remain in separate Android modules. |
| **`libinfinivault`** | Archives, compressed containers, virtual filesystems, partition tables, disk images, nested mounted resources, and their random-access views. |
| **`libinfinisound`** | Audio parsing, decoding, encoding where supported, metadata, trackers, MIDI, DSP, playback-related processing. **Already extracted; preserve its repository/history and migrate its interfaces incrementally.** |
| **`libinfinicanvas`** | Image parsing, bitmap/vector decoding, animation/image-sequence formats, raw pixels/sprites, image transformations where supported. |
| **`libinfinivideo`** | Video parsing, decoding, timing, presentation integration, subtitles where already supported; retain libVLC or other existing backend(s) rather than rewriting them. |
| **`libinfiniscribe`** | Text encoding/decoding, syntax highlighting, Markdown/document structure and formatting, HTML/CSS presentation adapters where appropriate. |

Only create libraries justified by existing code or clear shared functionality. A planned library may remain uncreated if there is nothing meaningful to move into it yet. Keep the family open to future libraries.

**Dependency direction:** Every library uses `libinfinistream` contracts; `libinfinistore` implements storage adapters; `libinfinivault` interprets sources and exposes nested entries; media/document libraries consume sources and offer processors and outputs. **Vault is not a mandatory hop** for an ordinary file. Libraries should not depend on `infinifolder` or on its UI. Avoid cycles, including `store` ↔ `vault`.

Keep the initial extraction structural whenever possible. Porting implementations to Rust is **not** part of this assignment.

## 2. Repository and build integration

1. Inspect the existing repository, Git remotes, current build, local modifications, and `AGENTS.md` (root and any relevant nested instructions). Follow those constraints.
2. Inventory existing modules/classes and propose ownership based on their actual responsibilities. Avoid relocating unrelated code.
3. Create standalone repositories for extracted libraries when appropriate. Preserve license notices, third-party attributions, and existing history where feasible. Existing `libinfinisound` is not to be recreated.
4. Each library must have a standalone build, clear README, license, tests, minimal public API, and documented dependencies. Do not introduce circular dependencies.
5. Integrate the libraries back into `infinifolder` as **pinned Git submodules plus Gradle modules/composite builds** where practical; do not copy entire library trees into the main repository as vendored source. Keep the submodule layout tidy, e.g. `libraries/libinfinistream`.
6. Keep builds reproducible and compatible with a source-build workflow suitable for F-Droid. Do not commit unexplained precompiled `.so`, `.aar`, or other opaque third-party blobs. If an existing dependency requires special F-Droid treatment, document it rather than silently replacing it.
7. Where GitHub repo creation/push is unavailable, prepare the repositories and exact commands/changes locally, and clearly report which remote operations remain; do not claim they happened.

## 3. `libinfinistream`: the common contract

Make this library **small, independent, composable and long-lived**. Prefer capability-based interfaces over one huge interface.

Required concepts:

- **Source:** readable bytes or typed data.
- **Sink:** writable bytes or typed data.
- **Sequential read/write:** supported for pipes, live inputs, network streams and bounded buffers.
- **Positional/random-access read/write:** separately advertised capability with 64-bit offsets; prefer `readAt(offset, ...)` semantics that do not mutate a shared cursor.
- **Seekable cursors:** optional adapters on top of positional I/O, with independent cursor positions.
- **Known length, mutable length, truncate, flush, append, atomic commit, sparse ranges, and concurrency:** optional capabilities, never universal assumptions.
- **Composable transformations:** processors consume one or more inputs and can produce one or more outputs, with stream metadata on both sides.
- **Cancellation, progress, backpressure, close/ownership rules, and error propagation:** explicit contracts.
- **Lazy evaluation:** avoid reading whole resources just to discover types or open metadata.
- **Bounded memory:** allow large files and deep nested containers without eagerly loading them.

The names below illustrate semantics; adapt to the actual language and code conventions:

```kotlin
interface ByteSource : AutoCloseable {
    val metadata: StreamMetadata
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

interface PositionalReadable {
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

interface ByteSink : AutoCloseable {
    val metadata: StreamMetadata
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

interface PositionalWritable {
    fun writeAt(position: Long, buffer: ByteArray, offset: Int, length: Int)
}

interface StreamProcessor {
    val descriptor: ProcessorDescriptor
    fun open(inputs: Map<String, StreamHandle>, options: ProcessorOptions): ProcessorSession
}
```

**These are examples, not a mandate to break existing APIs.** Avoid ambiguous thread-safety, hidden cursor movement, and obscure lifetime ownership. A source can offer multiple capabilities; an unsupported capability must be represented honestly.

A file, a network object, a decompressed archive member, a generated audio stream, and a decoded image can participate in the same composition model without pretending they are all identical byte sequences.

### Typed streams and domains

Keep the transport abstraction minimal. Metadata can identify **bytes, PCM audio, encoded audio, image frames, video frames, text, events, directories, or structured objects**. Specialized types may belong in domain libraries rather than in the stream core. Do not force expensive conversions (e.g. GPU video frame → CPU bytes → GPU texture) just to fit a universal byte-stream interface.

## 4. Input and output metadata are first-class

**Every source and every processor output has metadata**, not only files. Output metadata describes the **actual output stream**, not merely the input file or processing step.

Represent at least the following, where known:

- Content/media type (`MIME` plus a precise internal format identifier), encoding, byte order and optional extension/name hints.
- Length, whether total length is known, and whether content is finite/live.
- Capabilities: sequential read, positional read, write, seek, repeatability, ownership and other supported operations.
- Stream-specific characteristics: audio sample format/rate/channels/duration; image pixel format/dimensions/color space; video dimensions/frame rate/timestamps; text character encoding/language; directory/entry metadata, etc.
- Integrity/provenance where applicable: checksums, original source URI, offset range, transformations, timestamps, confidence/verification state.
- Errors/warnings and incomplete or provisional metadata.

**Each processor/class declares its accepted input types and capabilities, produced output types and capabilities, options, and whether it supports pull, push, or both.** At runtime it exposes actual negotiated input and output metadata. For example, a FLAC decoder may accept an encoded FLAC byte source and produce `PCM, 48 kHz, stereo, float32`; a ZIP reader accepts a byte source and outputs a virtual directory of entry sources.

Provide a clean distinction between:

1. **Static descriptor**: what a processor *can* accept or produce.
2. **Detected input metadata**: what a particular source appears to contain.
3. **Actual output metadata**: what the opened instance *will* produce.
4. **Runtime updates/events**: what is learned or changed after opening or during processing.

Metadata should be immutable snapshots or explicitly versioned updates where possible, so consumers don't observe inconsistent state. Unknown fields remain unknown—never fabricate certainty.

## 5. Format detection: prefer content signatures / magic bytes

**Prefer magic bytes, internal headers, structural validation, and bounded probing** to filename extensions. Use extensions, MIME hints and user overrides as secondary signals.

- Probe without consuming a caller's shared stream position. Use positional reads or a small replayable peek buffer.
- Support formats with signatures at nonzero offsets, footer structures, or multiple competing interpretations.
- Use a bounded probe budget: bytes read, number of probes, time and recursion depth. Avoid downloading entire remote files for identification.
- Return multiple candidates and confidence/validation evidence where necessary; a short coincidental byte pattern is not definitive.
- Prefer structural checks over extension-based certainty. Some headerless/raw formats **cannot** be identified reliably; request parameters or use explicit raw-mode configuration.
- Do not automatically run expensive or destructive parsers based only on a weak match.
- For outputs, select encoder/container from explicit options or a target format, not blindly from the destination filename; validate extension compatibility.
- Retain the original name/extension as useful provenance even when content sniffing disagrees.

## 6. Automatic composition, metadata propagation, and optional push

Build a registry of available processor descriptors and a **planner/negotiator** capable of finding compatible transformations.

Examples:

```text
SFTP byte source
  → VDI image reader
  → GPT partition table
  → ext4 filesystem
  → ZIP archive entry
  → FLAC decoder
  → PCM stream
  → platform audio sink
```

```text
Remote image file
  → image decoder
  → pixel/frame stream + metadata
  → resize/color transform
  → image encoder
  → destination sink
```

The planner should:

- Match **types + capabilities + metadata constraints**, not just names or extensions.
- Support single-input/single-output, one-to-many (archive or demuxer), many-to-one (mixer), and multi-track graphs.
- Preserve **random access end-to-end when every relevant stage supports it**. When a transformation destroys random access, mark that explicitly and offer indexed seeking, buffering/spooling, or sequential fallback when appropriate.
- Pass through or derive metadata automatically, while allowing processor-specific metadata generation and overrides.
- Avoid infinite recursion and cycles; constrain automatic exploration and guard nested/decompression expansion.
- Support both **pull** (consumer asks for bytes/frames at a position) and optional **push** (producer emits chunks/events to downstream consumers). Push should not be mandatory; negotiate backpressure and cancellation.
- Never infer that metadata availability means processing must begin automatically. Allow lazy metadata reads and explicit execution.
- Support automatic routing based on detected type, but provide a way for callers to select a backend or prohibit transformations.

**Performance:** Avoid unbounded chains of independent caches/buffers. Allow observability of stages, offsets, byte counts, latency, cache hit rates and failures. Keep platform-specific scheduling/presentation out of the core contract.

## 7. Storage, Vault, and link resolution

- `libinfinistore` owns local/remote access (SAF, SFTP, SMB, WebDAV, etc., according to existing support), resource resolution, and link/shortcut behavior.
- `libinfinivault` owns interpretation of archives, partition tables, filesystem images, compressed volumes, and nested virtual directories.
- A Vault entry should expose a normal stream/source handle plus entry metadata and capabilities. Nested Vault readers consume that handle unchanged.
- Treat links and nested mounts carefully: cycle detection, hop limits, permission/security boundaries, path traversal defense, and consistent resource identity.
- Do not assume all archive members can seek efficiently. Distinguish native positional access, indexed decompression, re-decompression, and fully cached access. Avoid surprising full downloads.
- Preserve existing read/write behavior and commit/collision semantics. Do not silently make read-only formats writable.

## 8. Refactor infinifolder to use the abstractions

This is a real integration task, not just an unused new library:

1. Locate current file opens, stream adapters, parsers, decoders, viewers, players, copy/move operations, and link handling.
2. Introduce **compatibility adapters** so existing implementations can initially work through `libinfinistream` without being rewritten.
3. Convert consumers incrementally: ordinary file views; Vault nesting; audio (from existing `libinfinisound`); image; video; text; relevant copy/transform operations.
4. Route input through Store/Stream contracts, Vault where needed, and specialist libraries. Use Stream metadata to select compatible viewers/processors and inform output handling.
5. Preserve Android-specific playback surfaces, `MediaCodec`, `WebView`, SAF permissions and platform APIs behind adapters; do not make the common stream foundation depend on Android.
6. Keep all existing user-facing functionality and UI behavior unless a correction is explicitly justified and tested.
7. Remove duplicated I/O abstractions **only after** all their callers have moved and tests pass.

### Audio migration caveat

`libinfinisound` has already been separated. **Do not redo its extraction or port it to Rust.** Add compatibility with `libinfinistream` via wrappers/adapters first. Preserve gapless playback, seek behavior, metadata, formats, casting, visualizations and tracker/MIDI support. Any later internal rewrite should be a separate, approved task.

## 9. Safety, efficiency and testing

Prioritize reproducible behavior and bounded resource use over clever automatic inference.

**Minimum regression coverage:**

- Local files and network sources; sequential-only and random-access sources.
- Reads at zero, middle, EOF; independent cursors; short reads; unknown lengths; cancellation.
- Nested archive/filesystem chains, including offset mapping and a link cycle.
- Format detection when extension is absent, misleading or wrong; ambiguous signatures; raw/headerless data.
- Metadata propagation and output metadata correctness, including updates.
- Pull pipelines and push pipelines with backpressure.
- One-to-many (ZIP) and many-to-one (audio mixer) processor descriptions.
- Large remote file reads without full-file buffering.
- Security cases: malformed headers, decompression bombs, path traversal, huge lengths, overflow, malicious recursion.
- Existing infinifolder file operations, audio playback, gapless, seeking, image opening, text/Markdown rendering and video playback.

Run existing tests before changes, keep a baseline, then run tests after each library migration. Build the standalone libraries and the main application. Add small targeted tests rather than undertaking broad unrelated rewrites.

## 10. Execution sequence and checkpoints

1. **Baseline:** inspect architecture, `AGENTS.md`, uncommitted state, repository ownership and tests; document the mapping.
2. **Foundation:** create `libinfinistream` and a minimal tested API for sources, sinks, capabilities and metadata.
3. **Adapters:** make existing I/O work through compatibility interfaces without changing behavior.
4. **Repositories:** extract Store, Vault and other justified existing components, preserving dependencies and Git history where feasible. Reuse `libinfinisound`.
5. **Integration:** wire Gradle and pinned submodules; ensure standalone and integrated builds work.
6. **Metadata + detection:** standardize declared and actual I/O metadata, magic-byte probes and bounded registry lookup.
7. **Composition:** add pull planning first, push where useful, preserving seekability honestly.
8. **Incremental consumer migration:** move infinifolder's file/archival/media/document paths to stream abstractions one area at a time.
9. **Cleanup:** remove obsolete adapters only when no longer used; document public interfaces and versioning.
10. **Verification:** run and report tests/builds, regressions, performance checks and outstanding limitations.

After each major checkpoint: commit coherent working changes in the affected repository, and update the pinned submodule commit(s) in infinifolder. Do not make one enormous all-or-nothing commit.

## 11. Acceptance criteria

This assignment is complete when:

- `libinfinistream` builds independently and provides small, documented source/sink, positional access, capability and metadata contracts.
- Existing standalone `libinfinisound` is preserved and can consume compatible sources via an adapter/interface.
- Each library created has its own repository, source build and tests; infinifolder uses pinned submodule references or a documented equivalent.
- Input **and output** metadata are available; processor capabilities and accepted/produced types are inspectable.
- Content-based magic/header detection is preferred over filename-based dispatch, with safe fallbacks.
- Random access is used where available and lost capabilities are explicit.
- Push and pull paths are supported where practical, without forcing all producers into push mode.
- Infinifolder's actual I/O and media flows use the new abstractions, not merely the old APIs next to unused new classes.
- Existing working features continue to work and test/build results are reported.
- No unauthorized language port, destructive rewrite, or opaque binary dependency is introduced.

## 12. Required final report

Summarize:

1. Repositories created or reused, with repository links and commit hashes where available.
2. Submodules and dependency relationships.
3. Public interfaces, capabilities, metadata schema and processor negotiation.
4. Actual infinifolder call paths converted.
5. What was intentionally left behind or requires follow-up.
6. Builds/tests performed, results and any regressions.
7. Remaining F-Droid build or licensing concerns.

**Guiding principle:** *Anything that can be read, transformed, interpreted or written should be composable—without sacrificing random access, metadata, portability, safety, or existing functionality.*

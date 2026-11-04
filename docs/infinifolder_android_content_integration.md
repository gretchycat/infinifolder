# InfiniFolder — Android Content Integration and Universal Format Translation

Status: design proposal retained for future work, reviewed 2026-11-04. This is a
requested direction, not an implementation or acceptance record. Existing URI
grants/opening remain implemented separately; general translation and virtual
document export described here are not established by the current library/viewer
synchronization. See [current component status](component-status-2026-11-04.md).

## Objective

Extend InfiniFolder into a general-purpose Android content provider, document provider, and format translation intermediary.

The goal is to make arbitrary files, archives, disk images, remote resources, and supported legacy formats accessible to other Android applications through standard Android APIs.

**Core principle: Any content that InfiniFolder can interpret should, wherever practical, be accessible to other applications in a representation they can understand.**

This functionality must build upon the existing `libinfini*` ecosystem, not duplicate its implementations.

## 1. Initial reconnaissance

Before modifying code:

1. Read the repository's `AGENTS.md` and follow its instructions.
2. Identify the existing architecture and interfaces of:
   - `libinfinistream`
   - `libinfinistore`
   - `libinfinivault`
   - `libinfinisound`
   - `libinfinicanvas`
3. Inspect existing Android intent handling, file associations, URI resolution, sharing, and external application launching.
4. Identify existing MIME-type detection, format conversion, caching, and stream abstractions.
5. Identify existing tests and compatibility requirements.

**Do not create replacement implementations for functionality already provided by these libraries.**

Document the existing capabilities, missing components, and proposed integration points before implementation.

## 2. Architectural principles

### Separation of concerns

Keep Android-specific APIs in an Android integration layer.

Core libraries must not depend on:

- Android `ContentProvider`
- Android `DocumentsProvider`
- Android `Intent`
- Android `ParcelFileDescriptor`
- Android application lifecycle classes

The underlying content access and conversion mechanisms should remain portable to Linux, Windows, macOS, and other platforms.

### Unified resource abstraction

Provide or reuse an abstraction capable of identifying resources independently of their physical location.

Potential sources include:

- Local filesystem
- Android SAF
- SFTP, FTP/FTPS, SMB, WebDAV, and other supported remote backends
- Archives and nested archives
- Filesystem images
- Partitioned disk images
- Virtual or generated content

Preserve source identities and container nesting.

Do not require wholesale extraction of archives or disk images merely to expose individual entries.

### Representation independence

A source resource may have multiple available representations.

For example:

| Original resource | Possible representations |
| --- | --- |
| `.sid` | Original, WAV, MP3, FLAC |
| `.mod`, `.xm`, `.s3m` | Original, WAV, MP3 |
| KoalaPainter image | Original, PNG, WebP |
| IFF image | Original, PNG |
| Legacy text document | Original, UTF-8 text |
| Archive member | Original or supported converted representation |

Only advertise representations that are actually supported by available converters.

Do not implement dummy conversion handlers.

## 3. ContentProvider implementation

Implement a provider capable of exposing InfiniFolder resources through `content://` URIs.

Requirements:

- Stable resource identifiers when appropriate.
- Correct MIME types.
- Display names and size metadata when known.
- Read-only access initially.
- Correct URI permission handling.
- Support for application cancellation.
- Appropriate cleanup of streams and temporary resources.
- Support for multiple simultaneous consumers.
- Avoid loading entire files into memory.

### Streaming and seeking

Support two delivery approaches:

**Sequential streaming**

For operations that can generate content progressively.

Examples:

- SID to MP3
- Tracker module to WAV
- Image conversion where streaming is supported

**Seekable content**

For applications requiring random access.

Possible implementations:

- Existing seekable source
- Temporary materialization
- Cached converted output

Do not claim seekability when it is unavailable.

Do not assume that every converted stream can support arbitrary seeking.

### Conversion caching

Reuse the existing memory and persistent caching architecture.

Cache identifiers should account for relevant properties such as:

- Source identity and version
- Output format
- Conversion parameters
- Decoder or converter version

Invalidate stale results safely.

## 4. DocumentsProvider implementation

Expose InfiniFolder's virtual filesystem hierarchy through Android's Storage Access Framework.

The provider should allow other applications to browse supported resources, including nested containers.

Example:

```text
InfiniFolder
  └── Network Storage
      └── Boson (SFTP)
          └── archives
              └── backup.zip
                  └── disk.img
                      └── Partition 1
                          ├── README.TXT
                          └── IMAGE.IFF
```

The provider must use existing navigation and filesystem abstractions.

### Required capabilities

- Enumerate roots.
- Enumerate child documents.
- Open document streams.
- Provide document metadata.
- Report supported MIME types.
- Support document thumbnails where practical.
- Handle unavailable resources gracefully.
- Preserve stable document IDs where practical.
- Report capabilities accurately.

Archives, partitions, and mounted virtual filesystems should be represented as navigable directories where the existing libraries support them.

Do not expose unsupported write operations.

### Virtual documents

Support Android's virtual document model where appropriate.

Investigate and implement applicable APIs, including:

- `FLAG_VIRTUAL_DOCUMENT`
- `getDocumentStreamTypes()`
- `openTypedDocument()`

A resource may advertise alternative representations rather than exposing only its original binary content.

For wider application compatibility, also support ordinary converted document entries where practical.

Ensure MIME types and returned binary representations agree.

## 5. Universal format conversion

Implement a reusable content-representation negotiation layer.

Conceptually:

```text
Source resource
      ↓
Content identification
      ↓
Available representations
      ↓
Target format selection
      ↓
Decoder / converter
      ↓
Stream or cached output
      ↓
Destination application
```

Do not tie conversion logic directly to Android provider classes.

Use existing libinfini components.

### Examples

**SID audio**

A Commodore 64 SID file may be exposed as MP3 or WAV.

Consider:

- SID emulation
- Multiple subtunes
- Song duration estimation
- Configurable fallback duration
- Sequential playback
- Seeking limitations
- Caching

**Legacy images**

Formats such as KoalaPainter or IFF may be exposed as PNG.

Avoid reimplementing decoders already present in `libinfinicanvas`.

**Nested resources**

An image inside an archive inside a disk image should be accessible without unnecessarily extracting the entire hierarchy.

### Conversion failures

Failures must be reported accurately.

Never silently return placeholder content, synthetic audio, blank images, or unrelated data.

Unsupported conversion must remain explicitly unsupported.

## 6. Android external application integration

Extend the existing **Open With / External Application** functionality.

### Application discovery

Use Android's `PackageManager` APIs to discover compatible activities.

Observe Android package visibility restrictions.

### Prevent recursive launches

When InfiniFolder invokes Android's chooser, exclude its own matching activities where possible using `Intent.EXTRA_EXCLUDE_COMPONENTS`.

Prevent accidental loops when an external application redirects the same resource back to InfiniFolder.

Do not rely exclusively on activity exclusion for recursion prevention.

### Original versus converted content

Support these conceptual actions:

- Open internally.
- Open original externally.
- Open converted externally.

Applications receiving converted content should be given an accurate MIME type and suitable URI.

Preserve existing external-launch behavior.

### Handler preferences

Design for future user-configurable application priorities.

Potential preferences include:

- Preferred application by MIME type.
- Preferred application by file extension.
- Preferred output representation.
- Whether conversion is automatic.
- Explicit choice to preserve original bytes.

Do not make InfiniFolder the forced default for every file.

## 7. Security and access control

Treat all incoming files and external URIs as untrusted.

Requirements:

- Validate resource identifiers.
- Prevent traversal outside authorized resource roots.
- Enforce permissions on each exposed resource.
- Do not leak credentials for remote connections.
- Grant only necessary URI permissions.
- Restrict access to other private application data.
- Handle malicious or malformed archives.
- Prevent unbounded recursive container traversal.
- Bound resource consumption for conversion.
- Support cancellation and cleanup.
- Avoid decompression bombs and excessive temporary storage consumption.

Do not expose arbitrary private files merely because InfiniFolder itself can access them.

Ensure URI permissions work correctly when another application needs to retain access.

## 8. Performance

The solution must support large resources.

Assume files may be tens or hundreds of gigabytes.

Requirements:

- Lazy directory enumeration where supported.
- Bounded memory consumption.
- Random access through existing abstractions.
- Progressive generation of converted content when possible.
- Background decoding and conversion.
- Cancellation when a consumer closes a stream.
- Cache reuse.
- No blocking of the Android UI thread.

Reuse existing thumbnail-generation and caching mechanisms.

Remote resources should not be downloaded entirely unless required by the requested operation.

## 9. Compatibility and regression testing

Existing InfiniFolder functionality must continue working.

Verify:

- Local directory navigation.
- Remote directory navigation.
- Archive browsing.
- Filesystem image browsing.
- Nested containers.
- Thumbnail generation.
- Existing internal viewers.
- External application launching.
- Existing audio playback and gapless behavior.
- Existing MIME registry behavior.
- Existing icon themes.

### Required integration tests

**Test A — External file access**

Open a regular local file through the new ContentProvider using a separate Android application.

Verify that actual bytes match the source.

**Test B — Archive member**

Open a file contained inside a ZIP without manually extracting the archive.

Verify content integrity.

**Test C — Disk image**

Expose a file located inside a partition of a disk image through DocumentsProvider.

Verify that another Android application can browse and open it.

**Test D — Conversion**

Open a supported legacy image as PNG in an ordinary external image viewer.

Verify that the result is a genuine decoded image.

**Test E — Audio conversion**

Expose a SID or tracker file as a conventional audio stream using an existing decoder.

Verify playback and correct handling of duration and seeking capabilities.

**Test F — Recursive launch prevention**

Verify that opening files externally does not redirect infinitely into InfiniFolder.

**Test G — Failure handling**

Verify that unsupported formats, corrupt data, inaccessible remote sources, and cancelled operations produce correct failures.

**Test H — Large resources**

Verify that accessing a small file inside a large disk image does not require loading the entire image into memory.

## 10. Phased implementation

Implement incrementally.

### Phase 1 — Architecture and ContentProvider

- Audit existing APIs.
- Implement read-only content exposure.
- Verify URI permissions.
- Expose ordinary files and archive members.
- Preserve existing behavior.

Build and test before proceeding.

### Phase 2 — DocumentsProvider

- Expose navigable roots and directories.
- Support archive and filesystem-image navigation.
- Integrate with Android's document picker.
- Verify read-only access.

Build and test before proceeding.

### Phase 3 — Representation negotiation

- Implement format capability discovery.
- Support virtual documents.
- Add conversion-backed content URIs.
- Integrate existing image and audio conversion mechanisms.

Build and test before proceeding.

### Phase 4 — External application integration

- Extend Open With behavior.
- Support original and converted representations.
- Exclude InfiniFolder from its own chooser.
- Prevent recursive launching.
- Preserve existing application handling.

Build and test before proceeding.

### Phase 5 — Hardening and optimization

- Improve caching.
- Verify large-file operation.
- Add cancellation and timeout handling.
- Improve security boundaries.
- Run compatibility and regression tests.

Do not begin later phases while earlier phases contain unresolved critical failures.

## 11. Engineering constraints

1. Follow `AGENTS.md`.
2. Preserve existing functionality.
3. Maintain clear module boundaries.
4. Do not duplicate existing decoders or filesystem implementations.
5. Do not introduce fabricated success results.
6. Do not create placeholder audio, images, or content to satisfy tests.
7. Do not silently replace unsupported functionality with stubs.
8. Do not claim a feature is complete unless it has been exercised through meaningful tests.
9. Keep conversion and stream APIs platform-independent where practical.
10. Document unsupported operations and limitations.
11. Commit completed, tested phases according to repository conventions.
12. Prefer targeted changes over unrelated architectural rewrites.

## 12. Acceptance criteria

The implementation is successful when:

- An ordinary Android application can read content exposed by InfiniFolder.
- Android's document picker can navigate supported InfiniFolder virtual directories.
- Nested archives and disk-image contents can be accessed externally.
- Supported legacy formats can be exposed as conventional representations.
- Original data remains unchanged unless explicitly edited.
- External launching does not recurse into InfiniFolder.
- Providers enforce proper access permissions.
- Large files remain practical to browse.
- Existing InfiniFolder capabilities continue functioning.
- Core libraries remain independent of Android-specific provider APIs.

## 13. Final report

At the end of each phase, report:

- Components implemented.
- Existing libraries reused.
- Files and modules changed.
- Tests executed and their actual results.
- Known limitations.
- Unsupported operations.
- Any deviations from the proposed architecture.
- Next recommended phase.

Clearly distinguish verified functionality from untested functionality.

**The ultimate objective is not simply to create Android providers. It is to make InfiniFolder a reusable content-access and format-translation service through which otherwise incompatible Android applications can use data they do not natively understand.**

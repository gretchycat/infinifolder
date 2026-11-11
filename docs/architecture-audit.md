# InfiniSuite architecture audit and separation

## Fresh-checkout and media-asset correction (2026-11-11)

The initial isolated builds supplied SDK configuration and missed the startup failure reported from new checkouts with neither SDK variables nor local.properties. This was a verification gap. Scribe conditionally hid its Android modules when the SDK was absent, leading to misleading attempts to fetch unpublished Android coordinates from Maven. Its Android modules are now declared by default; SDK-free JVM builds explicitly use `-PportableOnly`. Every application's build.sh now discovers installed conventional SDK locations, forwards the SDK as a JVM property (required for existing Termux daemons), passes native aapt2 on Termux and defaults arm64 packaging. Explicit SDK settings and user Gradle arguments remain supported.

The previous asset inventory focused on production source sets and missed repository-root duplicates. Infinimedia owns all four original Winamp archives and the optional SF3 bank. Seven byte-identical Folder copies total 29,870,265 bytes; the original archives/bank are retained in Media, with the bank's exact matching upstream license and provenance. This reduces checkout duplication; no APK size reduction is attributed to copies that were never packaged. The bank remains outside production assets, preserving current portable-synth and user-import behavior. See [byte comparison evidence](validation/media-asset-consolidation.json).

A shared UI test previously searched parent directories for a skin and silently skipped its assertions when absent. Its real-asset coverage now belongs to Media and requires every packaged archive. UI retains synthetic parser tests without application asset dependencies. Media additionally verifies the optional SF3's byte count, header and SHA-256; this establishes preservation, not runtime synthesis or visual correctness.

Follow-up app build results are recorded separately after executing the scripts in the user's new checkouts. The inventory tool now offers `--all-tracked`, with regression tests covering root/test assets and exclusion of untracked files. The [expanded inventory](validation/separation-tracked-inventory.json) covers all fifteen code repositories plus the pinned icon artwork repository. Its largest Folder files are storage regression disk images, which remain required fixtures. Duplicate extension aliases exercise format identification; Android TestUri fixtures support isolated JVM tests; wrapper/bootstrap copies support independent builds. Neither hash equality nor an uncommon extension alone justifies removing these files. Earlier checkpoints below describe their original test environments, rather than proving SDK discovery. Ubuntu testing and other platform ports remain deferred.


Updated 2026-11-11. This report records implementation, repository organization,
cleanup and executed Android verification. Other-platform ports and Ubuntu
host-native test work are deferred at the owner's request. Compilation, unit
tests and limited Android descriptor probes are distinguished from device
acceptance throughout.

### Corrected checkout verification

All four documented build scripts passed in the user's new checkouts with both SDK variables unset and no local.properties. Adjacent app checkouts existed on disk; the Gradle guard rejected project/settings/source roots outside each individual app checkout. Library pins were checked after each library-consuming build. The implementation commits below precede subsequent documentation-only commits.

| Application | Tested implementation commit | Debug APK MiB | Passed tests |
| --- | --- | ---: | ---: |
| Folder | `18c2481` | 83.07 | 143 (91 native storage) |
| Scribe | `9f0ee73` | 29.73 | 17 |
| Media | `9f53814` | 69.32 | 76 |
| Key | `c844925` | 8.47 | 23 |

All 259 app tests passed without skips or failures. Each application's 11 build-environment regression checks passed; the expanded inventory tool's two fixture tests passed. APK class inspection rejected other app implementations; only Media packaged the four Winamp archives, and none packaged the optional SF3 bank. Media's APK is 500 bytes larger than the earlier checkpoint after adding attribution documentation; the other APK sizes are effectively unchanged. No APK reduction is attributed to removing assets outside production source sets. See [executed commands, pins, suites and APK contents](validation/fresh-checkout-build-repair.json).

Scribe's standalone portable core passed 144 tests, and its default/portable module graphs were checked in a separate library checkout with no SDK configuration: the three Android modules are always visible by default and absent with `-PportableOnly`. Shared UI and its optional image module produced their standalone AARs and passed all 119 tests. See [library commands, suites and module-selection checks](validation/fresh-checkout-library-repair.json).

These are compilation, JVM/native and package checks. Device appearance, audible SF3 playback, end-to-end storage selection/grants and lifecycle behavior still need device validation. No Ubuntu or other-platform testing was performed in this follow-up; roadmap/TODO work remains future work.

### Follow-up GitHub changes

The existing six repositories were updated and pushed; no repository was created or history rewritten in this correction.

| Repository | Implementation commit | Subsequent documentation commit |
| --- | --- | --- |
| infinifolder | `18c2481` | This report's revision |
| infiniscribe | `9f0ee73` | `a0d437c` |
| infinimedia | `9f53814` | `764d632` |
| infinikey-ime | `c844925` | `06cdb03` |
| libinfiniscribe | `60dd088` | Included |
| libinfiniui | `85da49a` | Included |

README build examples now use build.sh; workflows run the script and build-environment checks. The Folder workflow also tests the expanded inventory tool. Hosted runs were skipped for this follow-up, respecting the deferred Ubuntu testing.

## Repository inventory

All repositories are under `gretchycat`. Existing repositories and published
history were retained. **Infinimedia, libinfinicore and libinfiniui were created.**

| Repository | Purpose | Application consumers |
| --- | --- | --- |
| [infinifolder](https://github.com/gretchycat/infinifolder) | File manager, advanced storage composition, hex/raw inspection and storage service; ID `com.infinifolder` | Application |
| [infiniscribe](https://github.com/gretchycat/infiniscribe) | Document editing/viewing, ebooks, fonts, source and legacy mind maps; ID `com.infiniscribe` | Application |
| [infinimedia](https://github.com/gretchycat/infinimedia) | Audio/video player UI, playlists, visualizations and casting; ID `com.infinimedia` | Application |
| [infinikey-ime](https://github.com/gretchycat/infinikey-ime) | Keyboard, layouts and input helpers; ID `com.infinikey_ime` | Application |
| [libinfinistream](https://github.com/gretchycat/libinfinistream) | Portable streams, positional I/O and processing contracts | Folder, Scribe, Media |
| [libinfiniselect](https://github.com/gretchycat/libinfiniselect) | Android file/tree selection, content access, grants and capability helpers | Folder, Scribe, Media; third-party clients |
| [libinfinicore](https://github.com/gretchycat/libinfinicore) | Resource/provider contracts, local/SAF adapters, guarded I/O, cache and generic support | Folder, Scribe, Media |
| [libinfinistore](https://github.com/gretchycat/libinfinistore) | Advanced routing, remote clients, torrents, virtual and installed-app sources | Folder |
| [libinfinivault](https://github.com/gretchycat/libinfinivault) | Archives, compressed containers, partitions, disk images and filesystems | Folder |
| [libinfinicanvas](https://github.com/gretchycat/libinfinicanvas) | Image decoding/processing and Android bitmap adapters | Folder, Scribe, Media UI |
| [libinfiniscribe](https://github.com/gretchycat/libinfiniscribe) | Document/text parsing, rendering/editing and resource expansion | Scribe; core/thumbnail subsets in Folder; core in Media UI |
| [libinfinisound](https://github.com/gretchycat/libinfinisound) | Audio contracts, formats, metadata, synthesis, decoding and PCM | Media processing; Scribe soundbank inspection; Folder/Vault contracts |
| [libinfinivideo](https://github.com/gretchycat/libinfinivideo) | Video contracts, platform playback and thumbnails | Media playback, Folder thumbnails |
| [libinfinimeta](https://github.com/gretchycat/libinfinimeta) | Portable console/game/legacy metadata | Folder |
| [libinfiniui](https://github.com/gretchycat/libinfiniui) | Shared Android UI/source support and optional image viewer | Folder, Scribe, Media |
| [infini-icon-theme](https://github.com/gretchycat/infini-icon-theme) | Unchanged pinned artwork/theme repository, owned as a UI dependency | Transitive through UI |

No applicable AGENTS.md files were found in the inspected repositories. Original
license/provenance files were preserved; Key's MIT license remains. Original-code
licenses marked pending were not replaced with a new license decision.

## Dependency graph

[architecture.md](architecture.md) contains Mermaid diagrams. Direct edges below
also specify the complete transitive graph: follow each edge recursively. There
are no application-to-application, library-to-application or circular build edges.

| Component | Direct suite dependencies |
| --- | --- |
| Stream, Select | None |
| Core | Stream |
| Store | Core, Stream |
| Vault | Store, Stream, Sound **audio-api** |
| Canvas, Scribe library, Sound, Video, Meta | Stream |
| UI | Core, Stream, Select, Canvas, Scribe **core** |
| Folder | UI/image, Select, Store, Vault, Meta, Scribe thumbnail modules, Video thumbnails, Sound API; Core/Stream/Canvas through these modules |
| Scribe app | UI/image, Select, document backends, Sound **audio-core** |
| Media app | UI/base, Select, Sound **audio-processing**, Video |
| Key | None |

Flattened checkout closures:

- Folder: all eleven libraries.
- Scribe: Stream, Select, Core, Canvas, Scribe, Sound, UI (seven).
- Media: Stream, Select, Core, Canvas, Scribe core, Sound, Video, UI (eight).
- Key: no suite libraries.

Source repositories contain optional modules, but only declared artifacts are
compiled/packaged. Vault does not pull Sound's decoder implementation. Scribe's
soundbank inspector does not link Android audio processing. Media's shared Scribe
core dependency does not include Office/PDF/MOBI. Folder retains reusable
thumbnail backends without full editor/player Activities. Lower-level libraries
have no dependency on application implementations.

## Reproducible repository organization

Every app/library has a build root, README, wrapper, ignore rules and standalone
workflow. Source locks pin exact commits, including the transitive closure;
bootstrap scripts fetch into the checkout, initialize declared submodules, reject
conflicting pins/symlinks and refuse to replace dirty differing revisions.
`--check` rejects dirty or mismatched dependencies and submodules. Folder retains
matching library gitlinks; Scribe/Media use locks without inherited monorepo
submodules. UI owns the one pinned icon-theme submodule.

This source-composite approach preserves independently versioned repositories
without requiring unpublished Maven artifacts. Gradle/version checksums, NDK,
CMake, external artifact versions and Actions are pinned. SDK paths, native
prefixes and signing keys are environment/local configuration. Termux's installed
native tracker inputs have a checked version manifest. Normal SDK/JDK/package
repositories and caches are toolchain inputs, not sibling source dependencies.

Core imported relevant Store history; UI retained filtered common-viewer history;
Media retained audio/video extraction history through its initial merge. Existing
repos received normal commits. No published history was force-pushed or rewritten.

## Confirmed problems and implemented corrections

The previous separation was incomplete. Findings below are established from
source/configuration, hashes and executed builds, rather than assumptions that
uncommon code was unused.

1. **Consumers pulled advanced storage engines.** Contracts, local/SAF adapters,
   cache and guarded I/O moved from Store to Core. Store retains concrete advanced
   engines and routing. Shared UI now uses `ViewerStorage` with an Android default;
   Folder injects its advanced provider and consistency hooks before first use.
   Scribe and Media no longer fetch Store/Vault.
2. **Incomplete app roots and sibling paths.** Media gained a complete independent
   repository/build. Scribe lost inherited storage/common/image copies and parent
   source dependencies. Key's sibling aapt2 tooling and machine signing config were
   removed. Folder's build script builds Folder only. Optional runtime probes now
   accept explicit APK/jar inputs instead of sibling application sources.
3. **Repeated common/image UI and themes.** UI is the single owner of former
   viewer-common; ui-image consolidates the identical image viewer. Shared theme/icon infrastructure
   moved to UI; actual Winamp archives and the optional SF3 bank belong to Media. Generic external-edit handling moved from storage-engine to UI,
   preserving archive freshness through an injected consistency hook.
4. **Misplaced responsibilities.** Routing moved to Store; document ZIP expansion
   and tests moved from Vault to Scribe Office. Legacy MindNode Touch viewing moved
   from Folder to Scribe. Media owns player Activities and uses Sound's canonical
   playlist parser. A thin old Vault parser alias remains for public compatibility.
   Existing package names were retained where practical; `com.infinifolder.*` in
   library code does not imply an application build dependency.
5. **Seekability/lifetime losses.** Native seekable handles now use Android proxy
   descriptors; sequential sources use reliable pipes. Proxy reads handle short
   reads/EOF/stalls/ranges explicitly. Streams, devices, callback threads and handle
   slots close on release; concurrency is bounded. Write-only native opens retain
   write-only descriptors. The boundary does not materialize entire files to
   emulate seeking; a codec/renderer may explicitly stage data for its own API.
6. **Caller and permission handling.** URI recipients use the framework caller
   rather than a spoofable package extra. Save sources require caller read
   authority/ownership. Saved results return Android document references with
   actual read/write/persist flags and ClipData. Foreign document IDs are no
   longer treated as local filenames. Directory hints use opaque Android document
   identities and confer no implicit sibling permission.
7. **No standard persistent advanced-storage boundary.** Folder now exposes a
   MANAGE_DOCUMENTS-protected DocumentsProvider for local storage, saved connections,
   SAF grants and saved containers. Opaque IDs persist privately; ancestry is
   checked; create/delete/open and loading cursor notifications are supported.
   Select adds file/tree/write/descriptor/seek/capability/persistence helpers with
   no suite implementation dependency. Normal SAF remains available without Folder.
8. **Tests depended on the old monorepo.** Provider/UI tests moved to their owners.
   Media fixtures live in already required Sound test resources; UI font tests use
   its declared Canvas source. Upward fixture searches were removed. Diagnostic
   output is written under build output, not committed source documentation.
9. **Executed regressions.** A 512-byte probe ending inside UTF-8 was incorrectly
   classified as CP437; an incomplete tail is now tolerated only when more bytes
   exist. Malformed interior bytes remain rejected. MIME refinement and common
   video identities work without the player module. Canvas tests now supply valid
   structural image data/PNG CRCs; a table test matches deliberate whitespace
   preservation. No legacy format decoder was removed.
10. **Missing keyboard network permission.** Key already has a configurable
    HttpURLConnection cloud transcription client, but its manifest lacked INTERNET.
    The normal permission was added; rebuilt APK declarations and the existing
    unit suite are checked. No remote transcription service was contacted.
11. **Reproducibility and attribution gaps.** Wrappers, source locks, bootstrap
    checks and CI were added throughout. Native paths use supported environment
    inputs. Dependency/native notices are included in APK assets; similarly named
    bytecode is excluded from notice collection.

Select remains a storage interface, with no decoder/renderer/metadata engine.
Existing JSPF/XML serialization is retained for URI-list compatibility, without
interpreting media contents. See [storage API](libinfiniselect.md) and
[storage validation](storage-validation.md) for grants, lifetimes and errors.
Legacy read relays expire after 24 hours and cannot be persisted. Saved playlists
containing them inherit that limit; standard SAF documents/trees can be persisted
when their provider and returned flags allow it.

## Cleanup and assets

[Hash inventory](validation/separation-inventory.json) covers tracked `src/main`
files in all fifteen code repositories, including native source/assets/notices.
[The script](../scripts/inventory-repositories.py) accepts explicit repository
paths. Tests, build outputs and untracked user files are excluded. Hash equality
is not itself proof of unused code. No exact duplicate production source files
remain within that scope. The follow-up [all-tracked inventory](validation/separation-tracked-inventory.json) also covers root/test assets and the pinned icon repository. Its MIME/extension icon aliases and format-test aliases remain intentional.

- Removed app copies of common/image UI, duplicate themes/gitlinks, obsolete
  parent paths and complete editor/player UI from Folder's build.
- Replaced **20 identical round launcher PNGs** across all four apps with aliases.
- Removed **18 unreferenced Key res/raw WAV copies**, totaling **177,266 source
  bytes**. SoundPool uses the retained byte-identical assets/audio paths; split
  down/up samples and sound choices remain.
- Centralized Media's test audio/playlist fixtures under Sound's test resources;
  they are absent from production assets. Required legacy integration fixtures
  remain. Build outputs are ignored; wrapper binaries are intentionally tracked.
- Preserved dynamic format handlers, native codecs, BGI fonts, original artwork,
  attribution and compatibility aliases. No MIDI SoundFont bank is bundled.

Remaining exact resource matches include small aliases/backgrounds, empty
manifests, independent license/provenance copies, editable launcher art, named
keyboard themes, and configurable keyboard image paths alongside settings
drawables. Folder's small drawable override matches UI. These do not justify
new app dependencies or deleting public asset paths. AAPT merges duplicate names;
independently usable components retain their own attribution.

APKs below compare pre-audit packages with verified isolated arm64 debug outputs.
[Baseline hashes/sizes](validation/separation-apks-before.json) are preserved.
This is an observed artifact comparison, not a controlled rebuild of the original
source revision; signing, debug packaging, generated assets and notices affect
bytes. Final APKs are repackaged without unused ZIP regions left by incremental
packaging. Native codecs dominate size and were preserved for format support.

## APK size results

| App | Before bytes (MiB) | Verified bytes (MiB) | Reduction |
| --- | --- | --- | --- |
| Infinifolder | 87,143,358 (83.11) | 87,109,144 (83.07) | 0.03 MiB (0.04%) |
| Infiniscribe | 40,348,793 (38.48) | 31,176,404 (29.73) | 8.75 MiB (22.7%) |
| Infinimedia | 82,094,003 (78.29) | 72,682,787 (69.32) | 8.98 MiB (11.5%) |
| Infinikey | 9,226,066 (8.80) | 8,876,990 (8.47) | 0.33 MiB (3.8%) |

Media and Scribe lose the unnecessary advanced storage stack. Folder retains its
storage/native/thumbnail functionality; its size change is small.
libVLC remains approximately 43.75 MiB in Folder/Media, and Folder's C++ runtime
is another approximately 8.86 MiB. Canvas's JPEG XL/native image support, Office
classes and the shared generated icon theme are other material contributors.
The native payload and twenty largest entries per APK are in the evidence JSON.
No codec/legacy format was removed to obtain a smaller package.

## Application verification matrix

Each application was cloned remotely into its own temporary parent, containing
only that checkout and its declared library closure. Gradle's init-script guard
rejects project/source roots outside the checkout. SDK/JDK/normal dependency caches
remain external toolchain inputs. No sibling application was present. After a
Termux crash/cache cleanup, the same isolated source trees were reused and the
build/unit commands repeated; this did not substitute a monorepo build.

[Machine-readable application evidence](validation/separation-app-builds.json)
records commands, exact commits/pins, XML counts, APK hashes, native libraries,
resolved runtime artifacts, notices and representative DEX class definitions.
Absence checks inspect class definitions rather than matching arbitrary strings.

| App | Isolated Android debug APK | Unit cases | Failures/errors/skips | Verified commit |
| --- | --- | --- | --- | --- |
| Infinifolder | PASS | 143 | 0/0/0 | [`57de730`](https://github.com/gretchycat/infinifolder/commit/57de7306787ff19751128bf6ec9095394b586d06) |
| Infiniscribe | PASS | 17 | 0/0/0 | [`859b68d`](https://github.com/gretchycat/infiniscribe/commit/859b68d1140a5b267ac015a4d753d0e4e97307e7) |
| Infinimedia | PASS | 74 | 0/0/0 | [`839f3cb`](https://github.com/gretchycat/infinimedia/commit/839f3cba173d82352fba5d8130be0ad68ef5954e) |
| Infinikey | PASS | 23 | 0/0/0 | [`1a2363b`](https://github.com/gretchycat/infinikey-ime/commit/1a2363ba386d7b95a8797bd30004789d92b38b0e) |

Folder's counts include app, hex and 91 cross-library storage integration cases.
Its four new mode regressions validate read-only, write-only, read/write and
unsupported mode classification. Scribe and Media APK checks show no Folder
Activity, routing, archive, SFTP or torrent implementation. Folder contains no
full text-editor, mind-map or audio/video-player Activities; Key contains none of
those suite types. [Packaged permission declarations](validation/separation-permissions.json)
were also inspected. Key retains its own app-launcher/overlay/settings permissions;
Scribe and Media do not inherit Folder's broad file/package/overlay permissions.

Every app repository now includes **build.sh** and **gradlew**. Build scripts
change to their own checkout, fetch declared libraries where needed, build the
debug APK and run unit tests; extra Gradle arguments are preserved. Folder also
runs its native storage suite on Termux. Script verification is recorded separately
in [build-script evidence](validation/separation-build-scripts.json). See the
[Android build guide](android-builds.md) for commands and exact APK paths.

## Standalone library verification matrix

All eleven library workflows below completed from fresh GitHub checkouts at the
pinned commits. Their workflows bootstrap declared dependencies, assemble their
published module types and run available JVM tests. These are already executed
Android/JVM checks; no further Ubuntu run was requested after host-native work
was deferred. The [CI status snapshot](validation/separation-ci.json) distinguishes
successes from the Folder host-JNI failure.

| Library | Standalone result | Scope/evidence |
| --- | --- | --- |
| libinfinistream | PASS `0dcb1b1` | [Stream JVM tests and assemble](https://github.com/gretchycat/libinfinistream/actions/runs/43084088174) |
| libinfiniselect | PASS `74bdacf` | [Android AAR and instrumentation APK; no JVM cases, instrumentation NOT run](https://github.com/gretchycat/libinfiniselect/actions/runs/43087306205) |
| libinfinicore | PASS `1fb1c26` | [Android AAR and unit tests](https://github.com/gretchycat/libinfinicore/actions/runs/43084099694) |
| libinfinistore | PASS `4f7a2f9` | [Android AAR and unit tests; live SFTP integration requires configuration](https://github.com/gretchycat/libinfinistore/actions/runs/43084128683) |
| libinfinivault | PASS `a672ea7` | [Android AAR and unit tests](https://github.com/gretchycat/libinfinivault/actions/runs/43087336879) |
| libinfinicanvas | PASS `ed3bf70` | [Android AAR and image unit tests](https://github.com/gretchycat/libinfinicanvas/actions/runs/43087311800) |
| libinfiniscribe | PASS `f3a930c` | [Portable tests and Android/reparse/MOBI modules](https://github.com/gretchycat/libinfiniscribe/actions/runs/43084114203) |
| libinfinisound | PASS `14b482e` | [API/core/GuitarPro tests, Android processing tests/build](https://github.com/gretchycat/libinfinisound/actions/runs/43087324237) |
| libinfinivideo | PASS `22d234f` | [API/Android tests and Android AAR](https://github.com/gretchycat/libinfinivideo/actions/runs/43084123272) |
| libinfinimeta | PASS `0459017` | [Portable module tests and assemble](https://github.com/gretchycat/libinfinimeta/actions/runs/43084108662) |
| libinfiniui | PASS `ecf5086` | [Common/image unit tests and image AAR](https://github.com/gretchycat/libinfiniui/actions/runs/43087332490) |

The earlier local cross-library regression run also passed: Core 138 cases,
Canvas 90, Scribe core/Office 150, UI 120 and Vault 48. Store had 120 cases,
119 passing and one unconfigured live SFTP test skipped. Skips are not counted as
passes. Local intermediate logs/build directories were later removed during disk
cleanup; the linked fresh-checkout CI runs retain independent execution evidence.

### Hosted Folder test limitation

The last executed Folder hosted run failed 13 native filesystem cases because
its Linux JVM could not load the Android/Termux JNI library. The same cases pass
in the local Termux storage suite. This is not recorded as a successful CI run.
The workflow now builds the Android APK and runs app/hex unit tests; the 91-case
host-native storage suite is explicitly deferred. That workflow scope change has
not been rerun, per the owner's request. Recent follow-up commits use `[skip ci]`
to avoid starting more hosted runs. Future Android builds can still use desktop
NDK tooling; no Ubuntu application port was attempted or installed locally.

## Android runtime validation

The local app_process64 probe passed six actual Android primitive checks: native
seek, positional reads, explicit pipe nonseekability, producer-error delivery,
consumer-close delivery and repeated close without descriptor leakage. Exact
artifact hash/results are in [runtime evidence](validation/separation-storage-runtime.json).
These do not test Folder's real proxy callback, cross-UID grants or backend/UI
lifecycle. Select's instrumentation APK compiled successfully, but its device
tests were not run. No ADB device was available for acceptance during the audit.

Cross-app single/multiple/tree selection, Folder present/absent, persistent and
transient grants, writes, remote/container seeking, revocation, process death and
third-party clients still require the [device matrix](storage-validation.md).
Playback/rendering/casting, Key microphone/cloud transcription, and all
obscure/legacy format combinations were not revalidated on hardware. Compilation is not presented as runtime proof.

## GitHub changes and commit delivery

All four apps and eleven libraries were modified and pushed. The three new
repositories are identified in the inventory; the auxiliary theme repository was
retained unchanged. Initial separation/source-history commits include Folder
`4a9e47f`, Scribe app `c9a2974`, and Media's preserved-history merge `1f76e3b`.
The latest verified application/script heads and pinned library heads are below.
The report's own documentation commit is separate and does not change APK inputs.

| Repository | Pushed implementation/script head |
| --- | --- |
| infinifolder | [`57de730`](https://github.com/gretchycat/infinifolder/commit/57de7306787ff19751128bf6ec9095394b586d06) |
| infiniscribe | [`859b68d`](https://github.com/gretchycat/infiniscribe/commit/859b68d1140a5b267ac015a4d753d0e4e97307e7) |
| infinimedia | [`839f3cb`](https://github.com/gretchycat/infinimedia/commit/839f3cba173d82352fba5d8130be0ad68ef5954e) |
| infinikey-ime | [`1a2363b`](https://github.com/gretchycat/infinikey-ime/commit/1a2363ba386d7b95a8797bd30004789d92b38b0e) |
| libinfinistream | [`0dcb1b1`](https://github.com/gretchycat/libinfinistream/commit/0dcb1b17b0450d900d0581f2fd2c13bf0dd32e4d) |
| libinfiniselect | [`74bdacf`](https://github.com/gretchycat/libinfiniselect/commit/74bdacf5d88fcdcaa97eb50343b0b47b31c398ed) |
| libinfinicore | [`1fb1c26`](https://github.com/gretchycat/libinfinicore/commit/1fb1c266ff037c6924571406af814ab638beb464) |
| libinfinistore | [`4f7a2f9`](https://github.com/gretchycat/libinfinistore/commit/4f7a2f94500220c36e8e0dc8a46837f3ec0404ff) |
| libinfinivault | [`a672ea7`](https://github.com/gretchycat/libinfinivault/commit/a672ea77e5dff258329d5ac6c9d702fd681dd368) |
| libinfinicanvas | [`ed3bf70`](https://github.com/gretchycat/libinfinicanvas/commit/ed3bf70090b19f03e1935a976579d751f3c6a298) |
| libinfiniscribe | [`f3a930c`](https://github.com/gretchycat/libinfiniscribe/commit/f3a930c9accffd363c05370a2e520856eb760f48) |
| libinfinisound | [`14b482e`](https://github.com/gretchycat/libinfinisound/commit/14b482e314a661ebdbc346d111884b874bc42adf) |
| libinfinivideo | [`22d234f`](https://github.com/gretchycat/libinfinivideo/commit/22d234fd3c525b6d920ff3072f428ca7d2897d0e) |
| libinfinimeta | [`0459017`](https://github.com/gretchycat/libinfinimeta/commit/045901712b0e55d561f982a50e06185b587a6035) |
| libinfiniui | [`ecf5086`](https://github.com/gretchycat/libinfiniui/commit/ecf508685c6bc02ca893d50a7219a6a99777a839) |

Documentation now describes current repo ownership, eleven libraries, standalone
build/test scripts, storage lifetimes, source/asset owners and acceptance limits.
Obsolete viewer-host commands and older test totals are marked historical rather
than silently rewritten as new evidence. Cache/external-edit/theme/WebDAV paths
were corrected. Current documentation links and the inventory script were checked;
[tooling evidence](validation/separation-tooling.json) records those checks.

## Outstanding issues and scope

- Cross-application Android device acceptance and hardware playback/rendering/
  casting remain unexecuted; use the documented matrix before release validation.
- Ubuntu host-native test setup and other-platform ports are deferred explicitly.
  TODOs/roadmaps remain future plans, not additional tasks in this audit.
- Legacy 24-hour relay references remain temporary; persistent queues should use
  provider-supported SAF grants. Unsupported seeking/writing remains explicit.
- The existing Termux native tracker path targets arm64; other environments retain
  their documented portable fallback. This audit does not establish equal native
  codec availability on every host or ABI.
- Original-code license choices previously marked pending remain the owner's
  decision. Existing third-party notices/source links are preserved and packaged;
  this is not a substitute for a distribution/source-build license review.
- Exact hashes cannot prove all semantic duplication or dynamic-resource usage.
  Uncertain format/resource cleanup was preserved to avoid losing functionality.

No unrelated repositories, user assets or unfinished edits were discarded.
Disposable isolated verification checkouts were removed after preserving the
verified APKs under `packages/verified/` and recording their hashes/test results. Existing functionality is preserved to the extent the
executed tests establish; full device behavior is not asserted beyond that scope.

# Library and viewer synchronization — 2026-11-04

This record covers source ownership and the outstanding changes published after
the animation increment. It supplements the dated migration evidence; it does
not replace old test results with a claim that the current revision was tested.

## Libraries

| Library | Current ownership and synchronization |
| --- | --- |
| Stream | Common resource, processor, source and transfer contracts. No new source changes in this synchronization. |
| Store | MIME/extension identity and bounded magic; FLIC/MNG aliases/signatures; generic BIN remains binary; stronger MPEG and text sniffing prevents erased binary samples from being accepted as audio/text. A BIN extension alone no longer advertises a disc container. |
| Vault | Archive/filesystem readers and mutations. The mounted-reader compatibility path now consults content signatures even for familiar extensions and preserves recognized outer compression, including compressed ZIP streams. Detection selects a real parser; malformed input still fails validation. |
| Sound | PCM decoding/analysis. Additive output options support rate conversion and mono/stereo mixing for the host's continuous Cast stream; unchanged options retain existing output. Analyzer windows follow the playback position rather than skipping most of a large PCM packet. |
| Canvas | IFF cycling, FLI/FLC recovery, APNG/MNG composition, interactive MNG states, native animation adapters and bounded posters. Rendering/caches remain separate from host controls. See [animation limits](image-animation.md) and [IFF cycling](iff-color-cycling.md). |
| Scribe | Text, structured text, font, document/ebook, table and hex processing. README wording is synchronized; no new decoder implementation in this pass. |
| Video | libVLC playback backend and common playback contracts. No new source changes in this synchronization. |

All seven source repositories remain pinned by the main repository. Modified
libraries must be published before their updated pins; unchanged libraries retain
their existing revisions. No independent release artifacts or new version tags
are implied by publishing source commits.

## Viewers and host adapters

| Module | Current behavior / outstanding acceptance |
| --- | --- |
| viewer-common | Shared Back diagnostics, callback rebinding after content replacement, return-to-browser handling, and copy/save-copy confirmation. System Back/theme-rebuild variants remain device checks. |
| viewer-image | Swipe navigation, autoplay/looping, Play/Pause, custom animation seeking, native progress, MNG touch/state selection, rotation/background handling and cache revision invalidation. Earlier playback follow-up was owner-confirmed; later FLC recovery, interactive MNG and lifecycle repairs remain unverified on device. |
| viewer-text | Existing Android text widgets, bounded source loading/reparse and syntax adapter; no new source changes in this pass. |
| viewer-markdown | Shared native scroll surfaces and one drag handle for rendered/source views; discard returns through the browser route. |
| viewer-html | Shared native scroll surfaces and one drag handle for rendered/source views; discard returns through the browser route. |
| viewer-csv | Native list fast scrolling and shared discard/return route. Scribe still owns parsing and serialization; Office worksheets reuse the grid. |
| viewer-epub | Existing EPUB and supported Office/ebook readers; document support was owner-confirmed. No new source changes in this pass. |
| viewer-pdf | Existing Scribe Android PDF backend; no new source changes in this pass. |
| viewer-font | Existing Scribe/Android font preview; font display, rotation, navigation/keyboard insets were owner-confirmed. Content-provider grants remain a separate device check. |
| viewer-hex | Sidecar presence selects the explorer even if parsing fails; only a valid sidecar configures PIC/WAVE. Unidentified generic binary files use HEX after bounded detection. Explicit external choices retain priority. |
| viewer-audio | Continuous Cast PCM timeline, per-session Cast failure skipping, shared analysis input and smoother displayed progress. Real-receiver playback and transition/seek behavior remain unverified. |
| viewer-video | Existing Video-backed playback and URI/source handling; no new source changes in this pass. |
| viewer-host | Existing standalone selection of ten viewers or `all`; HEX is currently available through the main app and is not a standalone selector. All-viewer build coverage does not establish runtime acceptance. |

The main browser also retains the outstanding directory scroll restoration,
absolute link breadcrumb routing, denser list rows, and copy/move confirmation
changes. These are source changes, not new claims of handset confirmation.

## Build and acceptance

The preceding animation build passed `gradle :app:assembleDebug --offline
--console=plain` in 28 seconds (`build/image-animation-repair/ship.log`).
The synchronization build passed in 42 seconds (518 tasks, 39 executed) using:

```sh
gradle :app:assembleDebug :viewer-host:assembleDebug -Pviewer=all --offline --console=plain
```

Log: `build/component-sync/assemble.log`. This compiles the main app's viewers
(including HEX) and the all-viewer standalone host against the pinned source
libraries. It is not an independent build of each standalone selector or a
physical-device run. Root and library whitespace checks also passed.
No automated tests were added or run in this synchronization. Existing test-file
and generated-report edits predate this pass; historical totals remain historical.

Priority device checks:

1. Interactive MNG touch/state switching and recovered FLC timing notices.
2. Binary/sidecar routing, real signature-selected archive listing, and invalid-input fallback.
3. Cast transitions, seeks, repeats, failures and receiver-specific WAVE support.
4. Back after theme rebuild, scroll handles, restored directory position and link breadcrumbs.
5. Copy/move and external save-copy confirmation, including cancellation and retained edits.

Continuous Cast uses RIFF WAVE with a 4 GiB PCM limit, falling back to separate
tracks when unavailable. Missing FLC timing uses a reported 70 fps default.
Advanced unsupported MNG commands fail honestly. Existing provider permissions,
transactional save protections and format limits still apply.

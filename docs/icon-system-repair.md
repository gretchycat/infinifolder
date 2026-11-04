# Icon system repair

## Status

Implementation and automated JVM checks are complete. Debug app and native
acceptance-test APKs build. **Device display, gesture/long-press interaction and
live remote favicon/provider checks remain unverified:** ADB has no connected
device. The user explicitly accepted completing code/tests with UI checks marked
unverified. This is not a claim that the full device acceptance matrix passed.

## Audit and root causes

No root, ancestor or nested AGENTS.md instructions were found during the audit.
Existing theme assets, CustomIconStore mappings, MIME associations, shortcut
parsing, storage abstractions and archive navigation were inspected. Prior icon
fixes did not cover the actual execution path: ordinary listing cards often
bypassed ResourceIconResolver, while tree, home and properties used competing
resolution logic. Directory discovery preferred `.icon` over `cover`; archive
recognition could replace a specific MIME representation; thumbnail behavior was
not controlled by a persistent per-MIME setting. URI labels could be interpreted
as extensions, yielding meaningless badges such as NET3A133.

The active path is now:

```
MainActivity entryCard / tree / properties / home resource
  -> visible-item worker -> ResourceIconResolver
  -> identity -> representation policy -> theme/decoder/fallback
  -> assigned ImageView bitmap
FloatingContainerService -> same ResourceIconResolver
Long-press preparation -> ArchiveIconCapabilities -> actual archive listing
  -> Browse archive -> existing archive navigator / nested archive extraction
```

FileIconView supplies only a cheap initial placeholder. Generated disk artwork
is no longer treated as an explicit per-file override. Existing genuine custom
artwork remains supported. MainActivity's pre-existing unrelated changes were
preserved.

## Behavior and persistence

- Visible hidden files and folders have an eye-with-slash badge in browser
  list/grid and tree rows, using the provider's hidden flag. Floating windows
  always filter hidden items, independently of the browser's Show hidden setting.
  This decoration uses metadata only, adds no reads, and remains outside cached
  theme/thumbnail artwork. The badge leaves item gestures available and moves
  beside the selection check on selected cards. The debug APK build passed in
  10 seconds (`build/hidden-file-badge/app-build-final.log`); no automated tests
  were added/run for this addition. ADB has no device: badge appearance, selection,
  gestures and floating-window filtering still require device checks.
- Directory enumeration uses the storage provider, with valid `cover.*` before
  `.icon.*`. Corrupt, transparent and unsupported renders fall through. Candidate
  ordering is stable by supported format then name. Known backend case rules
  are used; unknown backends default to exact matching (SMB defaults insensitive).
  Partially loaded asynchronous directories schedule bounded retries. No rendered
  directory result is permanently memoized over subsequent cover changes.
- `filesDir/icon_registry.json` stores MIME and URI edits independently of theme
  packages. Atomic writes preserve unknown fields. Corrupt data is readable as
  defaults but cannot be silently overwritten by an edit.
- MIME entries include canonical MIME, extension set, logical icon name, optional
  override, isImage and overrideImageIcon. New image-mode values default to
  **true**, including PNG/JPEG. False requests a real thumbnail before the user
  MIME override/theme fallback. Disabled image mode retains its stored value.
- Existing `mime_icons/index.json` custom mappings are retained and consulted;
  no separate ignored UI registry was introduced. No explicit persisted legacy
  thumbnail-mode preference was found. Existing image caches are not preferences.
- The Appearance menu opens **MIME and URI icon registry…**. The editor exposes
  association/image-mode fields, actual theme and override previews, import and
  clear. File properties opens the same MIME editor.
- Explicit associations, including multipart extensions, control identity.
  Missing/unknown/ambiguous associations use bounded existing magic detection.
  Additional container support does not replace DOCX/EPUB/APK or intentional
  text associations. Ordinary/source text uses a text icon. Supported ANSI art
  is image-capable; unsupported art renderers are not claimed.
- Archive capabilities require the existing container provider to actually list
  the source successfully. The action uses the real existing navigator, not a
  success stub. Unsupported/corrupt candidates fall back without a browse action.
- URI precedence is embedded shortcut icon, exact override, specific rule,
  discovered/provider artwork, theme scheme icon, built-in link. Local targets
  use the same file service. Other schemes have native semantic fallbacks
  (including magnet), with no speculative protocol network requests.
- URI matching preserves port, raw path/escaping, query and meaningful case;
  scheme and host are normalized. Path rules match segment boundaries.
- HTTP favicon work is asynchronous, origin-only, cached and size/time bounded;
  credentials are rejected and original path/query tokens are never fetched.
  Redirects and resolved addresses are checked against private/local targets.
  DNS lookup is bounded, but addresses are not pinned to the subsequent platform
  connection: complete DNS-rebinding resistance is not claimed.
- Theme lookups use logical keys, parent/category fallback and visible bitmap
  validation. Registry/theme/custom-index revisions, URI, name, MIME, modification
  metadata, size and icon size participate in caches. Content work is bounded
  and cancellation-aware; failed work produces a usable fallback.

## Principal changed files

- App: ResourceIconResolver, FileIconView, FileTypeIcons, LinkIcons, WebLinkIcons,
  ResourceArtworkCache, ArchiveIconCapabilities, IconWork, MainActivity icon/menu
  integration, FloatingContainerService.
- Common: IconRegistry, IconIdentity, IconPolicy, IconOverrideAssets,
  IconBitmapUsability, IconRegistryEditorActivity/manifest, DirectoryIconResolver,
  CustomIconStore, ImageFormats, IconThemeManager, FreeDesktopIconTheme,
  PublicIconUrls, WebMetadataResolver.
- Store: additive immutable MIME seed-association API.
- Canvas: additive ThumbnailDecoder overload for authoritative renderer hints;
  recognized signatures take precedence, original two-argument API preserved.
- Tests: registry/identity/policy/URI/directory/public-address suites,
  IconWorkTest, IconContainerIntegrationTest and native IconRepairInstrumentation.

## Automated validation

Commands (run offline using the installed Android toolchain):

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest \
  :viewer-common:testDebugUnitTest :app:testDebugUnitTest --offline --console=plain
gradle :storage-engine:testDebugUnitTest \
  --tests com.infinifolder.archive.IconContainerIntegrationTest \
  --tests com.infinifolder.archive.ArchiveFormatsTest \
  --tests com.infinifolder.archive.ArchiveStorageTest --offline --console=plain
```

- App/common build and tests: successful; **116 common + 17 app tests passed**.
- Broader container check: **20 passed, 1 failed** out of 21.
  `ArchiveStorageTest.testLargeRemoteDiscImageSizeLimit` fails its expected error
  message assertion. This exact failure is recorded in
  `docs/validation/infinistream-baseline.json`; unrelated disk policy was left
  intact. All three new real-file integration tests passed: actual ZIP listing
  and payload read (ZIP, extensionless, known-text and package names), malformed
  ZIP rejection and extensionless PNG signature recognition.
- A focused rerun of IconContainerIntegrationTest and ArchiveFormatsTest passed
  all **11 tests** and is recorded in `build/icon-repair-focused-container-tests.log`.
- Build log: `build/icon-repair-build-and-tests.log`. Broader container log:
  `build/icon-repair-container-tests.log`. Whitespace diff check passed.

## Device acceptance — prepared, not executed

The native instrumentation APK exercises actual MainActivity listing cards and
assigned icons, directory cover priority/corrupt fallthrough, extensionless
recognition, real registry checkbox editing, thumbnails/override fallback, theme
switches, MIME identity retention, ANSI rendering, URI rules/scheme/broken links,
list/grid consistency, a real archive-backed non-local cover, archive entry reads,
and the actual Browse archive menu/navigation path. Assertions inspect production
outputs; it does not assert scripted mock results. It restores saved settings and
uses private temporary fixtures. Its code is compiled, not device-validated.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.infinifolder.test/com.infinifolder.IconRepairInstrumentation
```

Pending: execute that suite, visually inspect icons at device sizes/themes and
rapid scrolling, tree/overlay/search parity, live HTTP favicon/offline behavior,
permission-denied content providers, inaccessible/slow network directory covers,
and long-press behavior on a device. Archive-backed storage is covered by the
prepared native suite; live SMB/SFTP/provider behavior has not been verified.

## Follow-up: image defaults and Settings access

The user subsequently requested content icons for all image MIME types. This
supersedes the original default-true requirement above: renderable image entries
now default to `overrideImageIcon=false`. A one-time
`imageContentIconsEnabled` migration changes only that field for existing image
entries (including supported art), retaining icon overrides, associations and
unknown fields. Later per-MIME edits remain authoritative. Unsupported rendering
still falls back to the MIME icon.

Settings now exposes **MIME and URI icon registry…**, **Browse icon themes…** and
**Import freedesktop.org icon theme…**. The existing archive importer remains the
implementation. The installed-theme browser filters by name and shows actual
folder/text/image/PDF/archive artwork without changing the active theme. Applying
a theme preserves MIME/URI overrides. This browses installed/bundled/system
themes; it is not an online theme catalog. Device UI verification remains pending.

Follow-up validation: `gradle :app:assembleDebug :viewer-common:testDebugUnitTest
:app:testDebugUnitTest --offline --console=plain` passed (116 common and 17 app
tests). The native acceptance APK also rebuilt successfully. Logs:
`build/icon-settings-final.log` and `build/icon-settings-build.log`. The existing
acceptance fixture now checks the default thumbnail before exercising the MIME
icon toggle; the suite still awaits a device.

## Follow-up: editor artwork previews

MIME and URI registry rows now include asynchronously loaded current icon artwork
with a bounded bitmap cache and recycled-row cancellation/binding checks. Individual
editors show labeled Current, Theme default and User override previews, update
when the logical name or image-mode fields change, and reopen with the selected
artwork after import. Missing overrides are explicitly labeled None. Thumbnail
mode explains that the displayed icon is the fallback for a per-file thumbnail.
URI previews show the rule's registered/theme/scheme artwork without fetching a
remote target.

FileTypeIcons and LinkIcons moved unchanged into viewer-common, retaining their
package/API, so the editor uses the same native fallback artwork as file listings.
Validation: `gradle :app:assembleDebug --offline --console=plain`; build log
`build/icon-editor-previews-final.log`. Device display remains unverified. Tests
were not run for this UI-only follow-up.

## Follow-up: registry scroll restoration

The registry retains the native ListView state (including row offset) and filter
separately for MIME types and URI rules before opening/rebuilding editors. Saving,
clearing an override or returning from the icon picker restores the list state;
Android activity recreation preserves it through saved instance state. Canceling
an editor leaves the existing list intact. Build command:
`gradle :app:assembleDebug --offline --console=plain`; log:
`build/icon-editor-scroll-build.log`. Device interaction remains unverified;
no tests were run for this follow-up.

## Selected-theme first frame

FileIconView no longer paints built-in artwork ahead of a selected theme. It
uses a bounded, revision/size-keyed memory cache of already decoded theme icons,
shared by theme manager instances. Cold entries reserve their icon space until
the existing background resolver supplies the chosen artwork. No theme file
lookup or decode is added to drawing on the UI thread. This also covers floating
container cards through their shared FileIconView. Per-MIME content-thumbnail
choices remain in effect. Build log: `build/rip-text-theme-first-frame.log`;
no tests run and device display remains unverified for this follow-up.

## Persistent local theme render cache and dump follow-up

Imported theme packages remain in app-private `files/icon_themes`. Decoded theme
icons now also have a persistent PNG cache at `cache/icon_theme_renders`, so they
can be reused across process restarts without rerasterizing SVG assets. Memory
caches still serve warm first frames. Disk reads/writes stay in theme-resolution
workers. Keys cover renderer version, active theme/import revision, theme/source paths,
index and asset modification metadata, fallback PNG metadata and requested size.
Atomic temporary-file publication, bounded/validated image reads and LRU eviction
limit the disk cache to 48 MiB and 2048 icons (4 MiB per cached PNG). Cache failures
fall back to normal theme rendering. Android may reclaim this disposable cache;
installed theme packages and saved theme selection are persistent settings/data.

The posted 20261102-051712-385 dump also contained repeated attempts to open
`files/null`. Optional JSON icon overrides/URI matches now accept actual strings
and treat JSON null as absent; this fixes repeated failed reads without removing
saved associations or valid overrides.

Build: `gradle :app:assembleDebug --offline --console=plain`, successful; log:
`build/theme-cache-rip-proportions-build.log`. Device cache restart/eviction checks
remain unverified; no tests were added or run for this follow-up.

## Theme icon latency follow-up

The listing and floating container now request provisional theme artwork on a
separate bounded worker queue, independently of thumbnail, sidecar and content
work. Cached final artwork and final resolver results take precedence; the full
resolver still applies covers, custom icons, URI destinations and saved image
mode choices. Queue saturation only skips this optional early display.

MIME extension associations are indexed per registry revision instead of scanning
all records and repeatedly checking the registry file for each listing item.
Observed sidecar absence avoids redundant filesystem probes, and generated
sidecars are identified before decoding when only custom artwork is needed.
Warm theme artwork bypasses repeated source metadata probes for five seconds;
theme revisions invalidate it immediately and later worker lookups revalidate
source metadata. Cold PNG cache compression and eviction now run asynchronously
on a bounded writer queue; reads no longer wait for unrelated writes. Cache
failures continue to use the rendered theme bitmap.

Removed redundant per-card UI-thread cache publication (the resolver already
publishes on its worker), whose registry/custom-icon filesystem checks appeared
in the 20261102-054304-984 dump. Floating listings also capture the icon revision
once per render rather than once per card. Slow main-list theme previews log
queue, resolution and UI delivery times under `artwork.theme.display-slow`.

Build: `gradle :app:assembleDebug --offline --console=plain` succeeded in 21s;
log: `build/icon-speed-final-build.log`. No tests were added or run for this
follow-up. Device latency and visual precedence checks remain unverified.

## MIME icon regression rollback

The user reported missing MIME icons after the latency follow-up. Reverted its
speculative theme-preview lane and restored the previously working listing,
overlay, sidecar, registry association and warm-theme lookup paths. This removes
the independent provisional icon selection and its race with authoritative MIME
artwork; the exact device failure remains unconfirmed without a fresh device
trace. Saved MIME/URI mappings, thumbnail preferences and installed themes are
unchanged. The asynchronous PNG disk-cache writer remains: it does not select
icons, and the display path no longer waits for PNG compression or eviction.

Validation: `gradle :app:assembleDebug --offline --console=plain` (log:
`build/mime-icon-regression-build.log`). No tests added or run. Device icon-display
verification remains outstanding; compilation alone does not confirm recovery.

## Generated MIME icons versus generic theme fallbacks

Inspected `build/infinifolder-debug-20261102-060742-355.png` and its matching
log. The screenshot shows generic theme pages for unsupported extensions and
text categories rather than the generated labeled icons. The previous rollback
restored old code but did not address this representation-order problem:
`resolveMimeDefaults` includes generic keys, so THEME_CATEGORY could always win
before the procedural GENERIC renderer.

Per the user's clarified rule, only specific theme MIME keys (including office
and media aliases) precede the generated type icon. Broad `*-x-generic`,
`application-octet-stream` and `unknown` keys do not suppress procedural MIME or
extension artwork. Custom image overrides, requested successful thumbnails and
supported archive-folder representations keep their precedence. MIME registry
list/editor previews use the same specific-theme lookup. First-frame memory
lookup no longer flashes generic category artwork; the resolver cache namespace
was bumped to discard old resolved placeholders.

Build command: `gradle :app:assembleDebug --offline --console=plain`; log:
`build/generated-mime-fallback-final-build.log`. No tests added or run for this
follow-up. The supplied screenshot was inspected; the revised APK still needs
on-device visual confirmation.

## Investigation of remaining icon latency (20261102-182841-908)

Inspected the newly posted log and matching screenshot. The latest screenshot
shows the selected theme's folder icons. The log contains failed SFTP directory
probes on the same two IconWork threads used for local sidecars/magic detection
(`ResourceIconResolver.directory` stacks at 18:28:29 and 18:28:35), plus repeated
folder enumeration and UI-thread registry/custom-icon stats. Cancellation bounded
the caller's wait but could not stop provider I/O that ignores interruption.
The old log has no per-source queue/resolve timing, so it does not establish an
exact end-to-end speedup or assign all latency to one stage.

Repairs keep the generated-versus-generic icon policy intact:

- Separate metadata, directory, thumbnail and archive work per provider; canceled
  queued tasks are removed immediately. A stalled SFTP probe cannot monopolize
  the workers used for local metadata or image rendering.
- Separate ordinary MIME source jobs, folder discovery, content renders and app
  icons. Main listing and floating overlay use the same source scheduler.
- Return valid resolved cache hits immediately, including directories. Files are
  reusable for five minutes with identity/mtime/size/registry/theme/custom-icon
  keys (links and items without modification timestamps retain the 30-second
  lifetime); directories for five seconds, including observed cover candidate stamps.
  Existing invalidation still applies. Final cached artwork can be drawn directly
  on the first frame via a memory-only revision snapshot, without enqueuing work.
- Reuse complete directory observations for five seconds, including absence of
  covers. Partial asynchronous listings are marked incomplete. Filesystem case
  metadata is requested only when cover candidates exist. Show the shared theme
  folder baseline while discovery proceeds; a valid cover still wins finally.
- Index extension associations once per registry revision, preserving explicit
  associations, ambiguous candidates and longest multipart extensions. Avoid
  repeated registry stats for each record in every file's lookup.
- Skip sidecar work when the listing already proved absence; verify generated
  sidecar provenance before an unnecessary bitmap decode. Custom images retain
  priority and validation.
- Reuse warm theme bitmaps for five seconds before source metadata revalidation;
  theme/import revisions invalidate that cache immediately. Existing persistent
  PNG theme cache and asynchronous writes remain in use. Removed duplicate UI
  cache publication and per-card overlay revision reads.

New `artwork.work.slow`, `artwork.resolve.slow` and
`artwork.source.display-slow` records distinguish provider queue saturation,
resolver work, source queue wait and UI delivery. Memory caches remain bounded
by the existing bitmap byte limits; idle work threads time out. No MIME/URI
settings or thumbnail choices are changed.

Validation: `gradle :app:assembleDebug --offline --console=plain`; logs
`build/icon-latency-investigation-build.log` and
`build/icon-latency-investigation-final-build.log` (final build successful in
1m 41s). No tests added or run for this
follow-up. ADB still reports no attached device: post-change scrolling, cover
refresh behavior and measured display latency remain unverified.

User follow-up: icon loading is confirmed substantially faster on the device.
This confirms the perceived latency improvement; it does not substitute for the
other outstanding UI regression checks.

## RIP viewer default and mode toggle

New RIP viewer sessions default to high-resolution vector rendering. A native
Android ToggleButton now switches directly between Vector (checked) and EGA
(unchecked); the selected mode still survives activity recreation. Replacing the
render preserves the current normalized image-to-screen transform, pinch zoom
factor, rotation, fit/fill mode and pan position across the different bitmap
resolutions. Normal image navigation retains its existing viewport reset.
The viewport is preserved when decoding completes, including gestures made while
rendering; stale asynchronous results are rejected by the existing generation
check. A failed mode render keeps the old image and restores the toggle to its
displayed mode. The existing unsaved-edit guard remains active.

Build: `gradle :app:assembleDebug --offline --console=plain`; log:
`build/rip-vector-toggle-build.log` (successful in 58s). No tests added or run. The toggle's physical
device behavior and viewport retention still need on-device confirmation.

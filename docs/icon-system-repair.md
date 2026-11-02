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

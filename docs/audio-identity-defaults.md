# Audio file identity and Infinimedia defaults — 2026-11-11

## Corrections

The listing icon resolver and open path now keep an audio extension's MIME
identity when a bounded probe returns no format. This state is inconclusive;
it is not evidence that the file failed audio parsing. A concrete detected
format still wins, an explicit generic-binary result routes to Binary, and an
empty audio file is treated as Binary. This prevents a supported extension
from changing between Audio and Binary as icon/open checks retry.

Infinimedia's exported Activity is launched explicitly by Folder, so Folder no
longer requires Android's package manager to match a declared MIME filter
before saving a local association. On Folder startup and resume, it seeds
extension-level defaults for registered audio/playlist types and MIME-level
defaults for extensionless audio. It preserves existing choices and only saves
defaults while the Infinimedia Activity is installed, enabled and exported.
Folder also checks this state after resume, and before resolving an incoming
`ACTION_VIEW`, so installing Infinimedia while Folder is already running does
not require a process restart. When the open chooser queries Android for
handlers, it explicitly retains a saved, installed Infinimedia component even
if Android omitted it from the current MIME-filtered result. The preferences
remain local to Folder; Android's system-wide defaults are unchanged.

Icon resolution keeps the extension's audio identity while probing. Missing
album art leaves the audio icon in place and does not schedule a retry; only a
successful artwork read requests a refresh. The icon cache invalidates only
entries for that URI, and Folder refreshes the visible card rather than
rebuilding the whole directory listing for a single artwork result.

The policy lives at the application boundary. Folder does not bundle
Infinimedia's playback implementation or add an application dependency.

## Verification

- Shared UI: standalone `:ui-android:testDebugUnitTest` passed **134 tests**,
  including extension identity on inconclusive probes and explicit binary or
  conflicting-content results.
- Independent app checkout: `./build.sh --no-daemon --max-workers=1
  --console=plain` succeeded with Android SDK/NDK environment variables unset
  and no checkout-local `local.properties`. The script fetched the declared
  source dependencies and verified the app against the committed library pins.
  App tests passed **45**, viewer-hex tests passed **16**, and native
  storage-engine tests passed **91**: **152 passed, 0 failed, 0 skipped**.
  `:app:assembleDebug` produced an 87,989,041-byte debug APK in the fresh
  InfiniSuite checkout.
  This confirms an Android debug build and those JVM/native test suites; it is
  not a release-size measurement.

No Android device is connected through ADB, so visible icon stability,
Infinimedia playback of real files and post-install association behavior still
require handset confirmation. Compilation and unit tests do not establish
those runtime interactions. In particular, Folder can classify a concrete
binary probe result and route an inconclusive audio probe to Infinimedia, but
it cannot receive a playback-parser failure from the separate Media process.

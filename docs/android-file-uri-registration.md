# Infinifolder Android file and URI registration

Infinifolder generates its Android file-opening filters from the runtime
capability catalog on its own linked library classpath. The catalog drives both
Android's installed intent filters and the app's runtime handler discovery, so a
library capability can appear only when this application links the providing
library.

## Generated registration

During the Infinifolder build, `scripts/runtime-capability-catalog.gradle` runs
the catalog exporter against the declared runtime classpath. The app's Gradle
configuration then invokes `scripts/build-android-intent-manifest.py` to create
`app/build/generated/android-intents/AndroidManifest.xml` and its JSON summary.
The app source set consumes the generated manifest. The handwritten app manifest
still owns permissions and Android components.

The generated registrations cover the formats and URI schemes exposed by the
linked handlers. They describe candidates for Android's resolver; they do not
promise that arbitrary bytes or every device codec will open. Runtime handlers
still validate the source, capability, permissions, and selected backend.

Infinifolder also registers app-owned location and Termux command links. These
routes are handled by app code and are not provided by `libinfiniselect`. Unknown
location pages and unconfirmed commands are rejected or shown for review.

## Grants and resolution

The app accepts supported `content://` and `file://` requests through its normal
open path. Android's resolver cannot inspect file bytes or query every provider's
display name before choosing a handler. Providers should return useful MIME
metadata; generic types continue through the app's bounded detection and normal
permission checks. A generated filter does not change another provider's MIME
metadata or establish a default app choice.

The active app uses `MainActivity.handleExternalLinkIntent` to validate and route
requests. Folder-specific remote/container schemes remain behind the app's own
provider and storage libraries. Clients should use `libinfiniselect` and retain
URI grants rather than parsing those schemes or loading Folder classes.

## Build and inspection

Build from the Infinifolder checkout with:

```sh
./build.sh --max-workers=1 --console=plain
```

Inspect the generated catalog at
`app/build/generated/android-intents/AndroidManifest.json` after the build. Media
and Scribe generate their own manifests from their own linked classpaths; their
current behavior is documented in their respective repositories. The retired
`viewer-host` layouts and old `-Pviewer=...` commands are historical and are not
part of this build.

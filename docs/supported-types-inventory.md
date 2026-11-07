# Supported type inventory

[supported-types.csv](supported-types.csv) is a UTF-8 CSV (with BOM for spreadsheet
applications), exported from the capability providers on the app's actual linked
runtime classpath. It includes every handler's declared format profiles, including
overlapping handlers and filesystem/disk image formats supplied by Vault. Regenerate
it from the repository root with:

```sh
gradle :app:exportSupportedTypes --offline
```

The task first exports `app/build/generated/capabilities/catalog.json` from the
linked libraries and then writes the CSV. Columns include handler/library identity,
profile index, operations, MIME types, extensions, URI schemes, magic signatures,
metadata, limitations, attributes and runtime availability. Multi-valued selectors
use semicolons. Availability in a build snapshot is `NOT_EVALUATED`; build-time
discovery does not initialize platform codecs or open content.

This is an inventory of handler declarations, not proof that every codec,
filesystem feature or corrupt/encrypted/multipart input works. MIME recognition is
separate from rendering or playback. Archive recognition requires actual parser
validation before browsing. Per-handler operations, limitations and resource-level
capability checks describe read/write restrictions. URI recognition is distinct
from storage-provider availability.

Format limits and fixture evidence are documented in [images](image-formats.md),
[audio](audio-formats.md), [video](video-formats.md),
[documents](text-and-document-formats.md) and [overall formats](file-formats.md).

## Installed handler catalog

For the JSON catalog used both by Android intent generation and the CSV, run:

```sh
gradle :app:exportRuntimeCapabilities --offline
```

Output: `app/build/generated/capabilities/catalog.json` (schemaVersion 1). This
queries the actual dependency classpath without starting InfiniFolder. Runtime
availability from this build snapshot is `NOT_EVALUATED`; use CapabilityJson on the
running platform for optional dependency reports. No user MIME/icon/URI overrides
or credentials are exported. See [runtime discovery](runtime-capabilities.md).

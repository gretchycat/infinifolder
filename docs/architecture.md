# Infinifolder architecture and storage ownership

Infinifolder is an independent Android file manager and storage provider. It owns
its app UI, storage credentials, backend routing, file operations, and installed
provider service. It builds from this checkout and its pinned library closure;
Media, Scribe, Infinikey, and the planned apps are not Gradle dependencies.

## App modules and libraries

| Component | Ownership |
| --- | --- |
| `app` | File browser, app-owned settings, public Android intents/provider endpoints, and user interaction |
| `storage-engine` | Infinifolder-specific composition and integration of reusable storage APIs |
| `viewer-hex` | Infinifolder's hex/raw-media inspection UI |
| `libinfiniselect` | Public Android selection, grant, and content-source API used by this app and current clients |
| `libinfinistore` | Reusable storage routing and remote-source clients |
| `libinfinivault` | Reusable archive, disk-image, and filesystem operations |
| `libinfinicore` / `libinfinistream` | Public resource, provider, stream, and I/O contracts |
| `libinfinicanvas` / `libinfinimeta` | Reusable image and game-metadata processing |
| `libinfiniscribe` / `libinfinisound` / `libinfinivideo` | Reusable document, audio, and video APIs used where declared |
| `libinfiniui` | Shared Android UI and image presentation APIs |

Library source revisions and their transitive dependencies are pinned inside this
checkout. Shared libraries may retain `com.infinifolder.*` package names for
source/API compatibility; those names do not make the Infinifolder application a
runtime or build dependency of a consumer.

## Shared storage contract

`libinfiniselect` is the client-facing API. It preserves a resource's
`authoritativeUri` separately from the current Android `contentUri`, handles
Android grants, and can resolve supported authoritative locations through
Infinifolder's provider. Infinifolder remains the owner of remote/container
schemes, credentials, and backend implementation.

Current `libinfiniselect` application consumers are Infinifolder, Infinimedia,
and Infiniscribe. Media and Scribe also retain local-file and Android SAF
workflows when Infinifolder is absent. Infinikey shares the suite branding only:
it has no `libinfiniselect` dependency and its own DocumentsProvider exposes
Infinikey-owned layout/theme files. The planned Canvas, Wave, Track, Launch, and
Play directories do not yet implement storage clients.

## Android provider and resource lifetime

Infinifolder exposes the standard DocumentsProvider authority
`com.infinifolder.documents`, protected by Android document permissions and URI
grants. Its separate `com.infinifolder.selection` provider implements the
`libinfiniselect` resolution contract. Clients may keep an opaque authoritative
URI, but only the public library and Infinifolder interpret Folder-specific
locations; clients never read Folder-private files or credentials.

A relay `contentUri` is a temporary read-granted stream and is not a durable
identity. Clients retain the authoritative URI and request a fresh access URI
when needed. Standard SAF document/tree URIs remain valid according to their
actual persisted grants. Directory hints alone do not grant sibling access;
browsing requires an explicit tree grant.

Seekable provider sources use proxy descriptors where supported; sequential
sources remain streams or reliable pipes. Errors and close operations cross the
provider boundary. Consumers do not stage whole files to imitate seekability;
individual decoders may stage data when their own APIs require it.

## Companion app handoff

Infinifolder bundles its image and hex inspectors. It sends supported documents
to Infiniscribe and supported audio/video to Infinimedia through Android intents,
content URIs, and grants. Those apps own their viewer/player activities and
continue to work without Folder. See [companion application boundaries](viewer-modules.md),
[the libinfiniselect boundary](libinfiniselect.md), and the
[storage validation guide](storage-validation.md).

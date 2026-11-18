# InfiniFolder application

## Purpose

InfiniFolder is InfiniForge's Android file manager, filesystem browser, storage interface, and resource-navigation hub. It gives users one place to browse and manage resources across Android local storage, granted SAF locations, supported network providers, archives, disk images, and legacy filesystems. It is the suite's primary filesystem interface and optional storage companion; it is not the suite launcher or the owner of every file-format implementation and viewer.

## Primary use cases

- Browse, select, sort, organize, copy, move, create, rename, and delete supported files and directories.
- Connect to supported remote sources, retain credentials in Infinifolder, and move resources between local and remote locations.
- Navigate supported archives, disk images, and filesystem images, including supported nested-container workflows.
- Inspect file identity and metadata, select by filename patterns, inspect raw data, and view supported images.
- Open a resource in Infinimedia, Infiniscribe, or an external Android app, preserving grants and authoritative resource identity where available.
- Expose selected resources and storage through Android SAF/DocumentsProvider and the public libinfiniselect client contract.
- Use pinned locations, container links, supported Termux command links, and other directory-oriented shortcuts.

## Implementation status

Status note: implementation statements below are based on inspected source and current repository documentation. This audit did not rerun builds or device acceptance; dated verification and remaining checks remain in the README and validation guides.

### Implemented in this checkout

This checkout contains an installable Android app with an app module, a storage-composition module, and an Infinifolder-owned hex/raw-media inspector. The browser supports local shared/removable storage, user-granted SAF sources, SFTP, FTP/FTPS, SMB, WebDAV, file operations, transfer progress/cancellation, pinned locations, and supported file/content opening. Exact provider and format coverage depends on linked library capabilities and is listed in [file formats](file-formats.md), [runtime capabilities](runtime-capabilities.md), and the storage guides.

The app composes reusable storage, resource, archive, filesystem, streaming, and format APIs. Current behavior includes supported archive and filesystem-image browsing, selective write paths with format-specific limits, selected FAT deleted-file copy-out/recovery, and a guarded ext-family image writer. This is not general undelete or universal read/write support. Image and hex/raw-media inspection remain app features; broader document viewing/editing and audio/video playback are handled by companion applications.

InfiniFolder publishes its Android DocumentsProvider and a separate resource-resolution provider. The libinfiniselect API keeps a durable authoritative resource reference distinct from a temporary Android access content URI. A Folder relay link is temporary and read-only; clients must preserve the original identity and Android grant behavior. See [architecture](architecture.md), [libinfiniselect boundary](libinfiniselect.md), and [companion handoff](viewer-modules.md).

## Responsibilities and shared libraries

Infinifolder owns its Android browser and selection UI, application settings and credentials, operation orchestration, provider endpoints, and composition of storage capabilities. Reusable APIs have separate owners:

- libinfinicore and libinfinistream: resource, provider, capability, stream, and I/O contracts.
- libinfinistore: reusable local and remote storage backends and protocol clients.
- libinfinivault: archive, disk-image, and filesystem operations.
- libinfiniselect: Android selection, grants, resource resolution, and relative-resource APIs.
- libinfinicanvas, libinfinimeta, libinfiniscribe, libinfinisound, libinfinivideo, and libinfiniui: reusable image, metadata, document, audio, video, and UI functionality where declared by this app.

Consult the exact pinned dependency closure and each library's public API; package names inherited from older code do not imply a runtime dependency on another application.

## Interoperability

Infinimedia and Infiniscribe use Android intents, content URIs, URI grants, and libinfiniselect; when installed, Infinifolder's provider can resolve its supported remote/container resource references. Both companion apps keep local and SAF workflows when Folder is absent. Infinikey is a separate IME with its own DocumentsProvider and does not use libinfiniselect or Folder storage. The remaining five app repositories are placeholders, so their integrations are future work. See the [generated ecosystem map](ECOSYSTEM.md) for organization-wide contracts.

## Planned and potential work

Provider and format coverage can grow through shared libraries and their capability APIs. Additional search, filesystem recovery, safe write support, and executable/shortcut workflows should be documented only as implementation and Android constraints are established. Do not move specialized parsing, decoding, or companion-app workflows into this application solely because a resource is reachable from its browser.

## Limitations and outstanding work

- NFS and general cloud-drive providers are not implemented. Magnet/torrent access is limited and documented as read-only browsing/selected-file transfer rather than a general mounted filesystem.
- Recovery and write support vary by filesystem and format; FAT recovery is partial, while several formats are read-only or support only defined subsets.
- A directory hint or temporary content URI does not grant access to sibling resources. SAF tree access and Android provider grants remain authoritative.
- Provider, remote-network, cancellation, and lifecycle behavior still has device-acceptance gaps recorded in storage validation documentation. Historical build and test checkpoints do not prove every device/provider combination.
- The app does not provide a universal document editor, media player, or suite application launcher.

## Documentation audit

- Consistent: the current README and architecture guide place browser/provider composition in the app and reusable storage/format implementations in shared libraries.
- Incomplete: there was no concise application-level summary bringing together the app's role, capabilities, companion boundaries, and limitations; this document adds that index-level description.
- Unverified: broad remote/provider and device behavior still has acceptance gaps; the docs do not treat historical build tests as proof of every backend.
- Reconciliation: “universal storage” describes the direction, not every provider, archive, filesystem, recovery operation, or write mode.


STL and G-code previews come from pinned libinfinemesh, through the runtime
thumbnail capability pipeline. Mesh thumbnails use transparent flat-lit geometry;
G-code thumbnails show the complete travel/extrusion path. Decode and raster
budgets prevent large models from monopolizing icon workers; failed thumbnails
fall back to the file type icon. No source files are changed.

When InfiniMesh is installed, supported model filenames/MIME aliases receive a
local companion-viewer default, preserving saved user choices. Explicit read-only
relay handoffs retain the authoritative source identity and selection metadata,
including archive and remote resources. InfiniFolder does not embed the model
viewer app. The library thumbnail backend works without InfiniMesh installed.

The `.ngc` suffix is shared with Neo Geo Pocket ROMs. It remains ambiguous in the
runtime MIME registry: generic/text candidates can open as G-code, while an
identified ROM MIME retains its normal route and icon.

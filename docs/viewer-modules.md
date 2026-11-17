# Companion applications and Android handoff

Infinifolder is the file manager and optional storage provider. It keeps its own
image inspector and hex/raw-media inspector. Document, audio, and video
experiences belong to independent companion apps, which receive Android intents,
content URIs, and grants rather than Folder source or private files.

| Application | Current ownership | Storage relationship |
| --- | --- | --- |
| [Infinimedia](https://github.com/infiniforge/infinimedia) | Audio/video player, queues, playlists, visualizations, casting, and player themes | Uses `libinfiniselect`; local/SAF access works without Folder |
| [Infiniscribe](https://github.com/infiniforge/infiniscribe) | Text/document/ebook/font viewers and supported editing | Uses `libinfiniselect`; local/SAF access works without Folder |
| [Infinikey](https://github.com/infiniforge/infinikey-ime) | Android keyboard, layouts, and input settings | Shares branding only; no `libinfiniselect` or Folder backend dependency; owns a separate SAF DocumentsProvider |

InfiniCanvas, InfiniWave, InfiniTrack, InfiniLaunch, and InfiniPlay are planned
placeholders. Their integration contracts are future work; none is an existing
runtime or build dependency.

## URI and grant boundary

Current clients use `libinfiniselect` for file selection and resource resolution.
It keeps the authoritative resource identity distinct from a temporary Android
access URI. When Folder is installed, its `com.infinifolder.selection` provider
resolves its supported remote/container locations. The receiving app retains
Android URI grants and can continue using local and SAF sources without Folder.

The client does not bundle Folder's Store/Vault implementations. Seekable handles
remain positional when the provider supports them; sequential inputs remain
streams. A reader may stage input for its own parser, but that is application
policy. See [libinfiniselect](libinfiniselect.md) and
[storage validation](storage-validation.md).

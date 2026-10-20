# File formats and resource types

## Implemented

- Android SAF documents and directories. The provider supplies MIME type, display name, optional size, and optional modification time. MIME types drive Android `ACTION_VIEW` dispatch.
- Image MIME types use Android `BitmapFactory` previews when decodable. The browser checks dimensions and samples large images before displaying them; unsupported or oversized data falls back to the generic image icon. Animated formats display the decoder's still bitmap frame.
- Direct primary shared-storage resources use provider ID `local` and app-owned FileProvider content URIs under the `shared` namespace. Only this provider resolves its own URI components against the Android-discovered storage root; it rejects traversal and symbolic links. SAF identities are never decoded into local paths. File extensions supply MIME hints for local files.
- App-provided SAF sources use the actual authority and opaque document IDs advertised by the provider or returned by Android’s picker. Termux-style home IDs may resemble filesystem paths; InfiniFolder still accesses them only through SAF. `app_provider_…` preference keys identify app sources. Existing media-directory grants and cached names from earlier builds are retained, but those names no longer define Application public.
- Staging ledger is SQLite (`staging.db`, schema version 1); `resource_id` is an opaque provider identity, while `local_path` is a private cache location. The ledger is currently only a schema and has no staging data flow.

## Not yet implemented

- `.desktop` and `.directory` parsing, shortcut import/export, and preservation of unknown fields.
- `.icon.<format>` directory icon conventions and `.<filename>.icon.<format>` sidecars.
- Custom file type rules, signatures, metadata inheritance, and portable InfiniFolder extensions.
- Remote provider formats, connection-specific cache layout, and write-back manifests.

The app does not currently write metadata sidecars. File names and provider metadata are displayed as text and are not executed as commands.

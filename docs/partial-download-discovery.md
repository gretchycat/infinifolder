# Private Partial downloads collection

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Virtual root → Partial downloads is backed by the app-private `files/partial-download-links/` directory. Each discovered unfinished download gets an actual `.iflink` record there. A stable hashed link filename avoids collisions between identically named downloads on different sources; its saved display name remains the original download name.

## Discovery

The shared routing provider observes successful ordinary directory listings, including those made by the disk-space explorer. Known unfinished filename patterns and paired aria2 control/data files update the saved discovery registry and private links. No additional source scan is started. Discovery persists across restarts. Existing discovery records migrate into private link files automatically.

Recognized candidates include `.incomplete`, `.!qB`, `.!ut`, `.crdownload`, `.aria2`, `.part.met`, `.part` and `.partial`. Generic `.part`/`.partial` suffixes are ambiguous; discovery does not prove the file is resumable. Paired control/data files appear as one data link when identified.

## Private navigation and permissions

Opening, listing and rendering this collection only reads private records. The collection owns its entry URIs/references; it no longer exposes original source files as if they were children of the private directory. Its icon renderer does not follow targets for thumbnails or reconnect to servers. Navigation has an explicit private-root route and names, and never routes this virtual scheme into Android's storage grant picker. Generic permission-regrant fallback is restricted to content-provider URIs.

Original storage is contacted when the user chooses a download or Browse location. Actual access to an original SAF document still requires its valid grant; the collection does not broaden source permissions.

## Link and source behavior

- Selecting an InfiniFolder `.incomplete` link uses its original source reference to locate/resume its saved torrent session.
- Other formats retain their explanation and Browse location action; foreign resume-state import is not implemented.
- Forget/delete removes only the private link and discovery record, never the original partial file or torrent checksum cache.
- A complete successful listing prunes downloads that disappeared or completed. Failed/offline listings do not erase remembered links. Normal source delete/rename events remove their old discovery records.
- The collection accepts no ordinary user-file creations; it is maintained by discovery, with individual link removal supported.

Build integration is validated; handset confirmation of collection opening without a DCIM prompt, discovery via both browsing paths, restart persistence and link-only deletion remains pending.

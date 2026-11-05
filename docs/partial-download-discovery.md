# Unfinished downloads link directory

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

Virtual root → Unfinished downloads is backed by the app-private `files/partial-download-links/` directory. Each discovered unfinished download gets an actual `.iflink` record there. A stable hashed link filename avoids collisions between identically named downloads on different sources; its saved display name remains the original download name.

## Discovery

The shared routing provider observes successful ordinary directory listings, including those made by the disk-space explorer and the Virtual root internal-storage tree. Known unfinished filename patterns and paired aria2 control/data files update the saved discovery registry and private links. No additional source scan is started. Discovery persists across restarts. Existing discovery records migrate into private link files automatically.

Recognized candidates include `.incomplete`, `.!qB`, `.!ut`, `.crdownload`, `.download`, `.aria2`, `.part.met`, `.part` and `.partial`. Providers can also mark a resource with the existing partial-download MIME. Generic `.download`, `.part` and `.partial` suffixes are ambiguous; discovery alone does not prove that InfiniFolder can resume them. Paired aria2 control/data files appear as one data link when identified.

## Private navigation and permissions

Opening and listing the collection read private link records. The directory is expandable in the tree, where its children are links named after their source files. The regular icon resolver can fetch target metadata for visible link icons; offline targets fall back safely. The collection owns its entry URIs/references and exposes private `.iflink` records rather than pretending source files are physically inside the directory. Navigation keeps the stable `partial-downloads:///` route and never routes this virtual scheme into Android's storage grant picker. Generic permission-regrant fallback is restricted to content-provider URIs.

Original storage may be queried for a visible link icon and when the user chooses a download or Browse location. Actual access to an original SAF document still requires its valid grant; the collection does not broaden source permissions.

## Link and source behavior

- Selecting an InfiniFolder `.incomplete` link uses its original source reference to locate/resume its saved torrent session.
- Other formats retain their explanation and Browse location action; foreign resume-state import is not implemented.
- Forget/delete removes only the private link and discovery record, never the original partial file or torrent checksum cache.
- A complete successful listing prunes downloads that disappeared or completed. Failed/offline listings do not erase remembered links. Normal source delete/rename events remove their old discovery records.
- The collection accepts no ordinary user-file creations; it is maintained by discovery, with individual link removal supported.

The app and standalone viewer APKs build after the tree, icon and `.download` changes. Device checks remain for source discovery, visible links, tree expansion, target icons, restart persistence and link-only deletion. Foreign download managers still require their own resume metadata and a compatible importer; this directory does not claim to resume unsupported formats.

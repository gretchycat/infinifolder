# Text and file-icon artwork follow-up

This page records Infinifolder-owned icon routing changes associated with the
CP437 text viewer. The text viewer and ANSI music UI belong to InfiniScribe;
ordinary audio/video playback belongs to InfiniMedia. Current app behavior is
owned by each application's documentation.

## Text previews

Icon previews are limited to ANSI/text-art formats (`ans`, `ansi`, `asc`, `diz`,
`nfo`, or content detected as `image/x-ansi`). Plain text, unknown text, and
source files use ordinary text icons. PDF and Word files use document-type icons.
Custom icons keep priority. DOS text extensions can generate previews without
replacing the selectable document surface.

## Fallback artwork

Regular files without a custom or theme icon receive native folded-page artwork
labeled with their extension or MIME subtype and stable type colors. Detected
text and source files share the TXT icon; files without a useful extension or
MIME use FILE. Theme lookup tries extension-based names before category and
generic fallbacks. Generated fallback artwork is cached within a 4 MiB memory
budget and does not read file contents.

## Link artwork

`.iflink` artwork follows the resolved target. Explicit embedded/custom artwork
remains first. Web links use saved or fetched favicons, including SVG, with a
WEB globe fallback. Favicon lookup runs on the existing background artwork
worker, uses a bounded memory cache and up to 128 cache-directory PNGs, and
retries missing icons after ten minutes. Cached icons expire after a day. Saved
links are not rewritten. Network links and connection roots use protocol-labeled
artwork without connecting to remote storage. Local file, folder, app, and
archive links retain target artwork; unsupported schemes get a labeled link icon.

Magnet links select dedicated magnet artwork before target filename, extension,
or provider lookup. Link artwork signatures are versioned so stale generated
icons refresh after policy changes. These are Infinifolder presentation rules;
they do not grant access to a target or alter its storage identity.

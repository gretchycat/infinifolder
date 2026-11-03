# Android file and URI registration

Implemented 2026-11-03. The main app and standalone host generate their Android
intent filters from the live supported-type definitions at build time.

## Registered handlers

The main APK declares 260 MIME entries (including generic `text/*`), 827 filename
suffixes including multipart RAR/ARJ volume names, and 19 native URI schemes:

| Group | Schemes |
| --- | --- |
| Local/provider files | `file`, `content` |
| Storage containers/catalogs | `archive`, `apps`, `container-links`, `partial-downloads` |
| Network providers and aliases | `ftp`, `ftps`, `sftp`, `ssh`, `sshfs`, `smb`, `cifs`, `webdav`, `dav`, `davs` |
| Torrents | `magnet`, `torrent` |
| Command links | `infinifolder-termux` |

Image/audio/video, text/source/ANSI, PDF/ebooks/Office/RTF, archives, supported disk
images/filesystems, playlists, torrent/resume files, InfiniFolder links and Winamp
skins use their existing reader, player, browser or import paths. Registration
describes a supported route; actual codec/container variants still require valid
content and their documented backend capabilities. Generic `.bin` is not registered
as a disc image. Its actual content can still be recognized when opened by MIME
or from inside InfiniFolder.

HTTP(S), mail, phone/SMS and IRC schemes retain external-app delegation. NFS has
icon/recognition rules but no native provider, so it is not advertised as an opener.
Registering these as native handlers would send delegated opens back to the app.
Network URI bindings still use the existing account/credential prompts and grants.

External `infinifolder-termux` URIs show the command and working directory with
**Run/Cancel** before invoking the existing Termux permission flow. External link
files resolving to a command receive the same review, including recursive links.
Existing trusted in-app shortcut behavior is preserved.

The combined standalone host declares 214 MIME entries and 310 filename suffixes.
Selected viewer builds generate filters for their included representation; they
keep the existing major-type contracts. Text-only builds display structured text
as source, while audio-only builds can request audio from supported 3GP/MP4
containers. Edit registrations are generated for the editable representation
families, with existing read-only/complete-file guards retained at launch/save.
Standalone applications register file/content URI access and do not advertise the
main app's remote/container browsing UI.

## Real open path

`MainActivity.handleExternalLinkIntent` now handles ordinary files through provider
metadata, the supplied MIME type and bounded content detection when identity is
generic/ambiguous. Previously every external content/file URI attempted torrent
resume. Direct Open-with requests now select a real internal viewer or container
browser; unsupported input produces a message rather than re-entering Android's
chooser and selecting this same handler again. This explicit app selection does
not change the saved preference for normal internal listing taps.

Directories reuse normal navigation; `.torrent`, `.incomplete`, `.iflink`/legacy
links, application targets and `.wsz` retain their dedicated actions. Known
specific MIME identities are preserved instead of replacing a link/ebook MIME
with a generic JSON/XML signature. Extensionless generic sources can still use
existing magic detection. Remote URIs can target either folders or files; `cifs`
uses the existing SMB binding rather than a new storage implementation.

## Android matching limits

[MIME-only filters](https://developer.android.com/guide/topics/manifest/data-element)
accept file/content URIs, including opaque provider IDs. A provider must supply a
useful MIME type when its URI path has no filename. Android's resolver cannot read
file contents or query the display name to evaluate a filename suffix filter.
Registration does not alter another provider's MIME metadata or force a default
app choice; install/update the APK and choose InfiniFolder in Android's opener.

Separate untyped and generic-binary filters use filename constraints. Android 12+
uses `pathSuffix`, covering dotted filenames/directories. Lowercase and uppercase
suffixes are included. Older Android retains the basic escaped `pathPattern`
fallback; filenames with multiple dots can require a supplied MIME on those
versions. No unrestricted file `*/*` handler is added. The existing conditional
**Open with Termux** alias remains separate, and the old any-MIME `.incomplete`
resume contract remains constrained to that suffix. Typed and untyped native URI
filters are separate from file MIME/path filters to avoid accidental combinations.

## Generation and build evidence

`scripts/build-android-intent-manifest.py` reuses the source inventory in
`scripts/export-supported-types.py`; importing the inventory no longer rewrites
its CSV. The generator reads the Store, Scribe, Canvas/common and Sound format
lists, writes each module's generated manifest and a JSON registration summary,
and is wired into Gradle before manifest processing. Source templates retain
permissions and components. There is no fixed count of MIME placeholders to fill.

Generated summaries:

- `app/build/generated/android-intents/AndroidManifest.json`
- `viewer-host/build/generated/android-intents/AndroidManifest.json`

Command:

```sh
gradle :app:assembleDebug :viewer-host:assembleDebug --offline --console=plain
```

Both debug APKs build successfully. Log: `build/android-intents/build-final.log`.
Packaged APK manifests were inspected using `aapt2 dump xmltree`, including MIME
entries, URI schemes and compiled suffix/pattern constraints. The supported-types
CSV was refreshed from source (1,282 rows). No tests were added or run. Individual
viewer configurations and Android chooser/permission/default-app behavior still
need handset acceptance; no connected ADB device is available.

Check Open-with on a real image, Office/ebook file, ZIP/disk image, torrent/link,
opaque provider URI with MIME, generic-binary URI with a real filename, network
alias and magnet. Also check denied grants, invalid formats, command review,
read-only Edit rejection and previously configured default apps. Compilation and
manifest inspection do not establish runtime provider/decoder acceptance.

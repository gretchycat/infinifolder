# Android file and URI registration

The main app, InfiniScribe and standalone viewer hosts generate Android intent
filters from the runtime capability catalog of their linked libraries. Android
reads intent filters when a package is installed or updated, so an app cannot
add them when it starts. During each build, the catalog queries every registered
`CapabilityProvider` on that target's actual runtime classpath; the manifest
generator does not maintain a second file-type list. The running app separately
discovers those same providers for format detection and dispatch.

## Registered handlers

The main APK generates MIME entries, filename suffixes and URI schemes from linked
handlers. Exact values belong to the generated JSON summary, not a separate
handwritten registry. Native scheme groups currently include:

| Group | Schemes |
| --- | --- |
| Local/provider files | `file`, `content` |
| Storage containers/catalogs | `archive`, `apps`, `container-links`, `partial-downloads` |
| Network providers and aliases | `ftp`, `ftps`, `sftp`, `ssh`, `sshfs`, `smb`, `cifs`, `webdav`, `dav`, `davs` |
| Torrents | `magnet`, `torrent` |
| Command links | `infinifolder-termux` |
| Browser-page navigation (main app only) | `infinifolder-location` |

Native TTF/OTF/TTC/OTC fonts, image/audio/video, text/source/ANSI, PDF/ebooks/Office/RTF, archives, supported disk
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

The app-only browser-page scheme is read from `BrowserLocations.SCHEME` by the
manifest generator. It opens built-in UI pages referenced by `.iflink` pins, not a
filesystem backend. Unknown page names are rejected. Standalone viewers do not
register it. See [Virtual-root shortcut actions](navigation-menus-disk-usage.md#virtual-root-shortcut-actions).

External `infinifolder-termux` URIs show the command and working directory with
**Run/Cancel** before invoking the existing Termux permission flow. External link
files resolving to a command receive the same review, including recursive links.
Existing trusted in-app shortcut behavior is preserved.

The combined standalone host aggregates the linked viewer handlers.
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
versions. InfiniFolder accepts generic MIME and untyped `content://` and `file://`
view requests because Android cannot inspect provider content before choosing an
app. This also covers `.iflink` when a provider supplies an unusual MIME; every
route still invokes the normal target resolver and provider grants remain
required. The share receiver accepts arbitrary MIME because it records externally
shared file and URI references.

These filters are Android package metadata. A library capability change updates
them when the host is rebuilt and installed/updated; an Activity cannot add new
package intent filters after startup. The running app separately queries its
installed capability registry for format detection and dispatch.

## Generation and build evidence

`scripts/runtime-capability-catalog.gradle` exports each host's capability catalog
from its linked runtime classpath before manifest processing. The generator reads
only that catalog for file MIME types, extensions and library URI schemes, then
writes the generated manifest and a JSON summary listing the contributing
handlers and libraries. InfiniFolder's browser-page and Termux-command URI
schemes are read from their app-owned handlers. Source templates retain
permissions and components.

Store's identification handler publishes its explicit filename identities
through the same capability catalog. This includes 107 source extension
associations such as C/C++ headers, Pascal, Ada, BASIC and batch files, along
with archive, ebook, font, media, link and package identities. Adding or changing
an association in a linked library therefore updates generated Android filters
on the next build. Editable MIME entries come from handlers that advertise edit
support and retain launch/save capability checks. See [source identity and
highlighting](syntax-highlighting.md#source-file-identity) for the distinction
between file identity, text editing and lexical profiles.
Read-only [font previews](font-viewer.md) add four filename suffixes and ten
MIME identifiers/aliases; dedicated `-Pviewer=font` builds register only these
font inputs. GEN ROM identity/icons do not advertise a parser or native opener.

Generated summaries:

- `app/build/generated/android-intents/AndroidManifest.json`
- `infiniscribe/build/generated/android-intents/AndroidManifest.json`
- `viewer-host/build/generated/android-intents/AndroidManifest.json`

Build commands:

```sh
gradle :app:assembleDebug :viewer-host:assembleDebug -Pviewer=all --offline --console=plain
gradle -p infiniscribe assembleDebug --offline --console=plain
```

Current build evidence:

| Command | Result |
| --- | --- |
| `gradle :app:assembleDebug --offline --console=plain` | Passed; catalog contains 63 handlers from 7 libraries, 324 MIME types and 927 extensions. Includes `.iflink`, `.incomplete`, `.torrent`, `.wsz`, split archive volumes and the Termux URI scheme. |
| `gradle -p infiniscribe assembleDebug --offline --console=plain` | Passed; catalog contains 21 Scribe/viewer handlers, 78 MIME types and 190 extensions. It excludes unrelated image, audio and storage formats. |
| `gradle :viewer-host:assembleDebug -Pviewer=all --offline --console=plain` | Passed; catalog contains 12 viewer handlers, 123 MIME types and 402 extensions. |

The catalog is regenerated from each app's linked runtime classpath, so newly
registered providers flow into its manifest without edits to a central type
list. A new capability only reaches a host that links the library or component
that provides it.

Both generated summaries include the catalog fingerprint and contributing handler
and library names. These builds validate capability export, manifest generation
and packaging; no tests or on-device chooser/grant checks were run for this change.

Check Open-with on a real image, Office/ebook file, ZIP/disk image, torrent/link,
opaque provider URI with MIME, generic-binary URI with a real filename, network
alias and magnet. Also check denied grants, invalid formats, command review,
read-only Edit rejection and previously configured default apps. Compilation and
manifest inspection do not establish runtime provider/decoder acceptance.

# Desktop icon themes

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

The icon theme chooser prepares the bundled themes on first use, off the UI thread. Select **Icon theme…** in the file manager menu, choose a theme, and apply it. Imported themes can still be selected or installed from a ZIP or TAR archive. File previews and explicit custom icons take precedence where applicable.

## InfiniFolder Default

The supplied artwork in `themes/default theme` is now bundled as **InfiniFolder
Default**, alongside the existing desktop themes. Its source is a Freeicon SVG/CSV
bundle; the app-ready archive follows the
[freedesktop.org icon theme layout](https://specifications.freedesktop.org/icon-theme/latest/),
with `index.theme`, a scalable directory and logical icon names. The 774 source
SVGs produce 3,328 logical names for extensions, MIME types, directory variants,
URI schemes and generic fallbacks. Source SVG bytes are copied, not regenerated.

With no stored theme preference, the app selects this default. Existing named selections and
MIME/URI/per-file overrides are retained. Existing installations using the old
absence-of-preference fallback now receive the new default; the old format did not
distinguish that from never selecting a theme. Selecting **Built-in icons** explicitly
now stores an empty preference and keeps the legacy generated artwork on restart.
The browser labels the new choice **Bundled default**. Imported copies have their
own normal theme ID and can be selected separately.

The bundled choice uses the app preference ID `builtin:infinifolder-default`;
its exported freedesktop directory ID is `infinifolder-default`. This separates
the immutable bundle from imported copies without changing standard theme IDs.
The existing extraction and SVG renderer are reused. One dedicated worker stages
the asset archive into versioned app-private storage, then publishes it by rename.
Icon requests can cancel their wait without cancelling shared setup. No extraction
runs on the UI thread, and setup failure logs an error and allows normal fallback.
The existing memory and disk render caches include the bundle revision. Artwork
remains available locally across restarts; no remote theme retrieval is needed.
Cancelled requests and requests finishing after a theme/registry change do not
publish their fallback/old artwork into the current listing cache.

For this theme, a filename-specific icon is used when its source extension/MIME
association agrees with the resolved MIME. Longest extension matches win. Intentional
user mappings to another MIME and custom logical icon names keep their priority.
The source associations describe artwork only; they do not replace the editable
MIME registry or alter thumbnail/image-mode settings. Scheme-specific logical
names are tried before the existing shared scheme fallbacks, so FTP, SFTP, SMB,
magnet and other supplied schemes can retain their own artwork.

Regenerate only the app-ready package from the project root:

```sh
python3 themes/icon/scripts/build_icon_theme.py
gradle :app:assembleDebug --offline --console=plain
```

The theme source is used directly by the build. `:viewer-common:prepareDefaultIconTheme`
reads `themes/default theme/svg`, `metadata.csv` and the packager, then writes the
asset ZIP and Kotlin revision/extension map under `viewer-common/build/generated/`.
Gradle tracks these inputs; editing source art invalidates the generated outputs.
There is no checked-in asset copy to refresh. Python 3.9+ is required (override the
executable with `-PiconThemePython=/path/to/python3`).

The explicit packaging command above also creates `index.theme` and generated
`scalable/` aliases inside the source theme, plus the portable import archive
`themes/exports/infinifolder-default.zip`. ZIP entries contain real SVG bytes,
including on platforms without symlink support. Import that ZIP through Settings.
Member order, timestamps and the compiled content revision are deterministic.
The old `scripts/build-default-icon-theme.py` command forwards to this packager.
`generate_icons.py` explicitly regenerates source art from the reviewed base
assignments directly into the canonical source directory. `build_freeicon_pack.py`
exports the current source for contributor review without copying old exports
back over edited artwork.

Unspecified folders use `base-fallback-folder.svg`, without the FOLDER badge or
nested folder marker. Known special directories retain their variants. Mounted
archive roots use their backing file's MIME/extension theme icon, including ZIP,
7Z and package-specific artwork; internal archive directories remain folders.
Saved MIME customizations retain priority. Generic archive fallbacks use the
archive base instead of the directory-with-archive subtype. Directory covers
retain their normal precedence.

The debug app build passes (`build/theme-source-integration/app-build.log`). No
tests were added or run. Handset folder/archive display, theme switching and
restart persistence on this revision remain unverified.

## Other bundled desktop themes

The bundled archives contain the desktop themes' places, MIME type and device icons needed by the file manager, rather than their complete desktop application and panel icon sets. Symbolic links in the original packages are stored as copies so that icons work in app-private storage. Missing icons fall back to inherited themes and ultimately the app's default icons.

- GNOME Adwaita 50.0 and Adwaita Legacy 46.2: <https://download.gnome.org/sources/adwaita-icon-theme/50/> and <https://download.gnome.org/sources/adwaita-icon-theme-legacy/46/>. The packaged icon data and indexes come from the corresponding installed Termux packages.
- KDE Breeze and Breeze Dark 6.30.0: <https://download.kde.org/stable/frameworks/6.30/>. The packaged icon data and indexes come from the installed Termux package.
- Community Papirus 20260801: <https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/releases/tag/20260801>. The bundled archive contains its 16- and 48-pixel places, MIME type and device icons. It inherits Breeze.

Their license notices are in [licenses/icon-themes](licenses/icon-themes/). A Tela 2026-07-07 source archive was downloaded for compatibility investigation; Tela's source tree requires its installer to assemble a theme and is not included as a selectable theme.

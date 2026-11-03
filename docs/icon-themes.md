# Desktop icon themes

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

The icon theme chooser prepares the bundled themes on first use, off the UI thread. Select **Icon theme…** in the file manager menu, choose a theme, and apply it. Imported themes can still be selected or installed from a ZIP or TAR archive. File previews and explicit custom icons take precedence where applicable.

## InfiniFolder Default

The supplied artwork in `themes/default theme` is now bundled as **InfiniFolder
Default**, alongside the existing desktop themes. Its source is a Freeicon SVG/CSV
bundle; the app-ready archive follows the
[freedesktop.org icon theme layout](https://specifications.freedesktop.org/icon-theme/latest/),
with `index.theme`, a scalable directory and logical icon names. The 675 source
SVGs produce 2,874 logical names for extensions, MIME types, directory variants,
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
python3 scripts/build-default-icon-theme.py
gradle :app:assembleDebug --offline --console=plain
```

The packager reads the supplied source set and creates
`viewer-common/src/main/assets/icon-themes/infinifolder-default.zip` plus
`BundledDefaultThemeInfo.kt`. ZIP member order, timestamps and content revision are
deterministic. Rebuild the package after editing the source SVGs/CSV. The original
Freeicon source generators are a separate workflow that can replace source art.

The final APK build passes (`build/bundled-default-theme/app-build-complete.log`).
Inspection of the actual APK finds the theme asset with 2,876 members, including
the index and PDF/DOCX/MP3/folder/magnet mappings. No tests were added or run. Handset
display, cold-start preparation, theme switching and restart persistence on this
revision remain unverified. Logs are under `build/bundled-default-theme/`.

## Other bundled desktop themes

The bundled archives contain the desktop themes' places, MIME type and device icons needed by the file manager, rather than their complete desktop application and panel icon sets. Symbolic links in the original packages are stored as copies so that icons work in app-private storage. Missing icons fall back to inherited themes and ultimately the app's default icons.

- GNOME Adwaita 50.0 and Adwaita Legacy 46.2: <https://download.gnome.org/sources/adwaita-icon-theme/50/> and <https://download.gnome.org/sources/adwaita-icon-theme-legacy/46/>. The packaged icon data and indexes come from the corresponding installed Termux packages.
- KDE Breeze and Breeze Dark 6.30.0: <https://download.kde.org/stable/frameworks/6.30/>. The packaged icon data and indexes come from the installed Termux package.
- Community Papirus 20260801: <https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/releases/tag/20260801>. The bundled archive contains its 16- and 48-pixel places, MIME type and device icons. It inherits Breeze.

Their license notices are in [licenses/icon-themes](licenses/icon-themes/). A Tela 2026-07-07 source archive was downloaded for compatibility investigation; Tela's source tree requires its installer to assemble a theme and is not included as a selectable theme.

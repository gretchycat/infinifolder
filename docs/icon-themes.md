# Desktop icon themes

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

The icon theme chooser starts with InfiniFolder's bundled default icon artwork. Additional freedesktop themes can be imported from a ZIP or TAR archive or scanned from Termux/system locations. File previews and explicit custom icons take precedence where applicable.

## InfiniFolder Default

The supplied artwork in `libraries/libinfiniui/themes/icon-theme/default theme` is bundled in InfiniFolder as
**InfiniFolder Default**. Other freedesktop themes are not preloaded into the APK.
Its source is a Freeicon SVG/CSV
bundle; the app-ready archive follows the
[freedesktop.org icon theme layout](https://specifications.freedesktop.org/icon-theme/latest/),
with `index.theme`, a scalable directory and logical icon names. The 790 source
SVGs produce 3,352 logical names for extensions, MIME types, directory variants,
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
python3 libraries/libinfiniui/themes/icon-theme/icon/scripts/build_icon_theme.py
./gradlew :app:assembleDebug --console=plain
```

The theme source is used directly by the build. `:libinfiniui:ui-android:prepareDefaultIconTheme`
generates Kotlin metadata and the app-ready ZIP under `libraries/libinfiniui/ui-android/build/generated/`;
the shared UI artifact packages that ZIP once in each consuming APK. Applications
use the declared shared theme source instead of maintaining their own copies. Gradle
tracks the source art and packager inputs. Python 3.9+ is required (override the
executable with `-PiconThemePython=/path/to/python3`).

The explicit packaging command above also creates `index.theme` and generated
`scalable/` aliases inside the source theme, plus the portable import archive
`libraries/libinfiniui/themes/exports/infinifolder-default.zip`. ZIP entries contain real SVG bytes,
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

Additional icon themes remain importable at runtime. Their source licenses and
provenance are kept in the repository, but optional theme archives are no longer
packaged in InfiniFolder.

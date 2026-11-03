# Default Theme

This is the canonical icon source used directly by the app build. Edit artwork in
`svg/`; `metadata.csv` supplies logical MIME, extension, directory and URI aliases.
Gradle packages these files automatically. Do not copy artwork into module assets.

## Export an importable theme

From the project root:

```sh
python3 themes/icon/scripts/build_icon_theme.py
```

Import `themes/exports/infinifolder-default.zip` from the icon theme settings.
The script also prepares `index.theme` and generated `scalable/` aliases here for
folder-based import. The ZIP contains real files rather than symlinks. Derived
layouts and exports are ignored by Git; the artwork and metadata are tracked.
Packaging preserves source SVG bytes. Generic folders use the unbadged folder
base; generic archives use the archive base. Specific format icons retain their
extension and MIME names.

`:viewer-common:prepareDefaultIconTheme` runs the same packager during app builds,
writing only to the module's build directory. Its compiled content revision
invalidates the app's local theme cache when artwork changes. Python 3.9+ is
required; select another executable with `-PiconThemePython=/path/to/python3`.
The root `scripts/build-default-icon-theme.py` remains a compatible entry point.

## Regenerate artwork deliberately

```sh
python3 themes/icon/scripts/generate_icons.py
```

This replaces generated source art here from `themes/icon/bases/`, reviewed
`base-assignments.csv` and `themes/supported-types.csv`, then refreshes the import
layout and ZIP. Ordinary app builds do not regenerate the artwork.
`build_freeicon_pack.py` exports these current sources for Freeicon review and
refreshes the import ZIP; it never copies an older export back over this theme.

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

## Special-purpose folders

`directory_type` rows in `metadata.csv` define the folder roles. For example,
`directory:ringtones` packages its SVG as `folder-ringtones.svg`, and
`directory:notifications` supplies `folder-notifications.svg`. The build also
generates the app's folder-name lookup from these same rows. All declared roles
are recognized, including Alarms, Podcasts, Recordings, Camera/DCIM, Screenshots,
Fonts, Themes, Wallpapers, eBooks, Archives, Projects and Backups.
Media and Multimedia use the same video/music artwork; Books uses the eBooks
artwork, and Audiobooks has a book with headphones. Their logical theme keys are
`folder-media`, `folder-multimedia`, `folder-books` and `folder-audiobooks`.

The owner confirmed on-device that the new special-folder icons display correctly
on 2026-11-03.

Folder names match case-insensitively, by their whole name rather than a prefix.
The active theme supplies the corresponding logical icon; imported themes can
provide the same `folder-<role>` names. Existing desktop naming alternatives such
as `folder-download`, `folder-publicshare` and `user-home` remain available.
If the theme lacks a role icon, normal theme inheritance and the plain folder
fallback apply. A folder's valid `cover.*` or `.icon.*` still takes priority.

To change artwork for an existing role, edit its SVG here and run the export
command above. To add a role, add its source SVG and `directory_type` metadata row;
the next app build incorporates its name without a separate UI mapping. Changes
to manually added roles must also be reflected in the artwork generator's
`DIRECTORIES` list if you intend to regenerate all artwork later. Exported themes
use ordinary freedesktop icon filenames; arbitrary folder-name recognition is
an InfiniFolder behavior, not an additional standard `index.theme` field.

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

## Storage and application locations

Virtual root uses these logical default keys. Imported themes can replace the
same keys; artwork stays in the active theme and its inheritance chain. Existing
URI/per-file overrides and directory covers retain their priority. Theme changes
invalidate the compiled bundled-theme revision and rendered caches without
changing saved icon preferences.

| Location | Primary logical key |
| --- | --- |
| Internal shared storage | `drive-harddisk` |
| USB storage | `drive-removable-media-usb` |
| SD card | `media-flash-sd-mmc` |
| Unknown removable storage | `drive-removable-media` |
| Network storage | `network-server` |
| Cloud / remote document-provider root | `folder-cloud` |
| Application public section | `folder-application-public` |
| Installed applications | `start-here-android` |

Each uses a distinct folder stamp, with no bottom description plaque. The
Application public section is distinct from the applications grid; individual
application-owned storage entries retain their owning app icon. Ordinary inner
folders keep their folder artwork. Android volume/provider metadata determines
root roles on workers, with bounded queries and a short metadata cache. A device
whose provider hides USB/SD identification gets the neutral removable icon;
a user-editable volume title is not treated as hardware evidence. Cloud artwork
applies to existing remote provider roots, not a fabricated cloud connection.

For changes confined to folder/device stamps:

```sh
python3 themes/icon/scripts/generate_icons.py --directories-only
```

This updates registered directory roles and preserves existing file/URI/base
artwork and custom directory metadata. Full regeneration still requires the
reviewed file assignment cache to match the current supported-type inventory.

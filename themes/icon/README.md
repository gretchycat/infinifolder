# Supported type icon set

This directory contains editable SVG bases, reviewed assignments and theme tools.
The canonical generated artwork lives in `../default theme/`, which the app build
reads directly. Python 3.9+ and its standard library are required.

## Regenerate icons

Edit a base SVG in `bases/` (for example `bases/audio.svg`), then run:

```sh
python3 scripts/generate_icons.py
```

The generator reads `../supported-types.csv` and creates SVGs for every `file_type`
and `uri_type` row under `../default theme/svg/`, plus a generic folder and common directory
icons for Home, Desktop, Documents, Images/Pictures, Videos/Movies, Audio/Music,
eBooks, Downloads, Archives, Apps, Camera/DCIM, Screenshots, Podcasts, Recordings,
Ringtones, Notifications, Alarms, Wallpapers, Fonts, Themes, Templates, Shared,
Public, Cloud, Offline, Recent, Temporary, Contacts, Calendar, Favorites, Projects,
Backups, Trash, and the generic folder.
The Android directory has its own robot silhouette stamp at 50% opacity.
The folder base stays fully opaque.
Media and Multimedia share a video/music stamp. Books shares the eBooks stamp;
Audiobooks uses a book with headphones. These folder names match regardless of case.
File extensions and URI schemes appear in uppercase on the bottom colored label.
`icon_style.py` supplies the shared readable palette, selected deterministically
from each label; the app uses the same palette for unlisted MIME fallbacks. Folder variants
show only their pictogram stamp, without a description plaque. Directory names,
MIME types and inventory details remain in `../default theme/metadata.csv`.
The theme packager generates folder-name recognition from every `directory_type`
row, so Ringtones, Notifications and the other declared roles reach their artwork
in listings. Logical names are `folder-<role>` (for example `folder-ringtones`).
Music/Audio and Movies/Videos keep their individual artwork instead of sharing
an alias that hides one of the supplied stamps. Existing standard theme aliases
and plain-folder fallbacks remain available.
The generic folder uses the plain folder base. The artwork is built from original,
hand-authored SVG paths and shapes. Regeneration replaces source art and refreshes
the importable theme layout/ZIP; ordinary app builds only package current sources.
The build also compiles the canonical base SVGs and artwork associations into the
app, so runtime fallback icons use these bases without file or network reads.
After editing the palette or generator bases, regenerate the source icons.

`base-assignments.csv` stores a reviewed base-icon assignment for every file type,
including its MIME type, inventory notes, source paths, and assignment rationale.
The generator checks this cache against the inventory and stops if file types or
their supporting details changed, so new associations cannot silently use the
generic binary base. Review and add a cache row when the inventory changes.
The assignments use the project's registered MIME and extension associations,
cross-checked against the [freedesktop shared MIME database](https://cgit.freedesktop.org/xdg/shared-mime-info/tree/data/freedesktop.org.xml.in).
They select a display icon; they do not prove a file's actual content. Content
detection can override an ambiguous or misleading filename extension.

## Regenerate only directory and storage roles

```sh
python3 scripts/generate_icons.py --directories-only
```

This preserves existing file/URI/base artwork and custom directory rows while
updating the generator's directory roles. It is useful when changing stamps
without changing reviewed file assignments. The import layout and ZIP are rebuilt.
The new storage roles and theme override keys are documented in the
[default theme](../default%20theme/README.md#storage-and-application-locations).

## Prepare the Freeicon submission bundle

```sh
python3 scripts/build_freeicon_pack.py
```

This reads the current canonical theme and refreshes `freeicon-pack/` with its SVG
source files, metadata CSV, and submission notes. It never copies that export back
over the canonical source. It also refreshes the importable theme package. Freeicon currently requests original SVG source files and says
CSV metadata is optional; the bundle is arranged for its contributor review and
handoff process. Upload is a manual contributor workflow.

## Contents

- `bases/`: common pictograms, including the shared folder shape. Keep their SVG `viewBox` at `0 0 128 128`.
- `base-assignments.csv`: one explicit, traceable pictogram choice per supported file extension.
- `scripts/`: deterministic generators and shared label palette; Python standard library only.
- `../default theme/svg/`: source icons used directly by the app.
- `scripts/build_icon_theme.py`: generate freedesktop aliases and `../exports/infinifolder-default.zip` for import.
- `../default theme/scalable/`: disposable logical filename aliases; ZIP exports dereference them.
- `freeicon-pack/`: submission-ready copy of the generated SVG set.

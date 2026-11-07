# Infini app icons

`icons.zip` at the repository root contains the standalone bases for InfiniFolder,
Infiniscribe, InfiniMedia, InfiniCanvas and InfiniKey. The source emblem is
`infini.svg`. Run this from the repository root to regenerate both the default
theme artwork and the app icons. The app-icon step composes the emblem over each
base, adds a soft black drop shadow behind the emblem, renders adaptive/legacy
density assets, and installs them into projects that exist in this checkout:

```sh
python3 scripts/regenerate-icons.py
```

The script uses `rsvg-convert` from librsvg. Composited SVGs and generated PNGs
are kept under `themes/app-icons/generated/`; existing InfiniFolder,
Infiniscribe and sibling InfiniKey Android resource folders are updated too.
InfiniMedia and InfiniCanvas outputs stay in the generated directory until those
app projects exist here. Running the command again refreshes every available
target from the root artwork sources.

Adaptive foreground art is scaled into the Android icon safe area. Legacy square
and round PNGs use the full composition on the shared dark background.

For launcher assets only, run `python3 scripts/build-app-icons.py`. For the
freedesktop theme only, edit `themes/icon/bases/` as needed and run
`python3 themes/icon/scripts/generate_icons.py`.

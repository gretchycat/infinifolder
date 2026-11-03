# Default Theme

Project-facing copy of the Infinifolder Freeicon SVG icon set. The `svg/` directory contains the generated file type, URI, directory, and base fallback icons; `metadata.csv` maps each SVG to its type.

## App-ready theme

From the project root, run:

```sh
python3 scripts/build-default-icon-theme.py
```

This reads these SVGs and the CSV without regenerating the artwork. It creates
`viewer-common/src/main/assets/icon-themes/infinifolder-default.zip`, a freedesktop.org
theme named **InfiniFolder Default**, and updates its compiled content revision and
extension/MIME artwork associations. The archive includes `index.theme`, a scalable
icon directory, the original metadata CSV and logical MIME, extension, folder and
URI icon names. It can also be imported with the existing icon-theme importer.

The app bundles this theme as its default when no theme selection is stored.
Existing named theme selections remain in effect. Choose **InfiniFolder Default**
in the icon theme browser to switch explicitly.

The source generators under `icon/scripts/` are a separate workflow. Running
`generate_icons.py` and `build_freeicon_pack.py` recreates the source set and can
replace artwork here; use them only when that is intended.

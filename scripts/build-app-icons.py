#!/usr/bin/env python3
"""Build launcher icon PNGs by overlaying infini.svg on icons.zip app bases."""

from __future__ import annotations

import argparse
import copy
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZIP = ROOT / "icons.zip"
LOGO = ROOT / "infini.svg"
GENERATED = ROOT / "themes/app-icons/generated"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)

BASES = {
    "infinifolder": "folder.svg",
    "infiniscribe": "scribe.svg",
    "infinimedia": "media.svg",
    "infinicanvas": "canvas.svg",
    "infinikey": "key.svg",
}

ROOTS = {
    "infinifolder": ROOT / "app/src/main/res",
    "infiniscribe": ROOT / "infiniscribe/src/main/res",
    "infinimedia": ROOT / "infinimedia/app/src/main/res",
    "infinicanvas": ROOT / "infinicanvas/app/src/main/res",
    "infinikey": ROOT.parent / "infinikey-ime/app/src/main/res",
}

DENSITIES = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
             "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}


def tag(name: str) -> str:
    return f"{{{SVG}}}{name}"


def qualify_ids(root: ET.Element, prefix: str) -> None:
    ids = {}
    for node in root.iter():
        value = node.attrib.get("id")
        if value:
            ids[value] = prefix + value
    for node in root.iter():
        for key, value in list(node.attrib.items()):
            if key == "id" and value in ids:
                node.attrib[key] = ids[value]
            else:
                value = re.sub(r"url\(#([^)]+)\)", lambda m: f"url(#{ids.get(m.group(1), prefix + m.group(1))})", value)
                if key.endswith("href") and value.startswith("#"):
                    value = "#" + ids.get(value[1:], prefix + value[1:])
                node.attrib[key] = value


def make_composite(base: ET.Element, logo: ET.Element, launcher: bool = False) -> bytes:
    base = copy.deepcopy(base)
    logo = copy.deepcopy(logo)
    qualify_ids(base, "base-")
    qualify_ids(logo, "infini-")
    outer = ET.Element(tag("svg"), {"width": "512", "height": "512", "viewBox": "0 0 512 512"})
    defs = ET.SubElement(outer, tag("defs"))
    shadow = ET.SubElement(defs, tag("filter"), {
        "id": "infinity-shadow", "x": "-25%", "y": "-35%", "width": "150%", "height": "180%",
        "color-interpolation-filters": "sRGB",
    })
    ET.SubElement(shadow, tag("feGaussianBlur"), {"in": "SourceAlpha", "stdDeviation": "20", "result": "blur"})
    ET.SubElement(shadow, tag("feOffset"), {"in": "blur", "dx": "0", "dy": "0", "result": "offset-blur"})
    ET.SubElement(shadow, tag("feFlood"), {"flood-color": "#000000", "flood-opacity": ".82", "result": "shadow-color"})
    ET.SubElement(shadow, tag("feComposite"), {"in": "shadow-color", "in2": "offset-blur", "operator": "in", "result": "shadow"})
    merge = ET.SubElement(shadow, tag("feMerge"))
    ET.SubElement(merge, tag("feMergeNode"), {"in": "shadow"})
    ET.SubElement(merge, tag("feMergeNode"), {"in": "SourceGraphic"})
    outline = ET.SubElement(defs, tag("filter"), {
        "id": "icon-outline", "x": "-12%", "y": "-12%", "width": "124%", "height": "124%",
        "color-interpolation-filters": "sRGB",
    })
    ET.SubElement(outline, tag("feMorphology"), {
        "in": "SourceAlpha", "operator": "dilate", "radius": "12", "result": "expanded-alpha",
    })
    ET.SubElement(outline, tag("feFlood"), {"flood-color": "#000000", "result": "outline-color"})
    ET.SubElement(outline, tag("feComposite"), {
        "in": "outline-color", "in2": "expanded-alpha", "operator": "in", "result": "outline-shape",
    })
    merge_outline = ET.SubElement(outline, tag("feMerge"))
    ET.SubElement(merge_outline, tag("feMergeNode"), {"in": "outline-shape"})
    ET.SubElement(merge_outline, tag("feMergeNode"), {"in": "SourceGraphic"})
    if launcher:
        group = ET.SubElement(outer, tag("g"))
        parent = group
    else:
        # Adaptive icons reserve a central safe zone inside their outer mask.
        parent = ET.SubElement(outer, tag("g"), {"transform": "translate(64 64) scale(.75)"})
    art = ET.SubElement(parent, tag("g"), {"filter": "url(#icon-outline)"})
    base.set("x", "0"); base.set("y", "0"); base.set("width", "512"); base.set("height", "512")
    art.append(base)
    # Keep the wide emblem centered and clear of the distinctive app artwork.
    # Leave margin for the emblem's wide outer stroke and shadow so launchers
    # with tight masks do not clip its left or right edges.
    logo.set("x", "36"); logo.set("y", "160"); logo.set("width", "440"); logo.set("height", "215")
    logo.set("filter", "url(#infinity-shadow)")
    art.append(logo)
    return ET.tostring(outer, encoding="utf-8", xml_declaration=True)


def rasterize(source: Path, destination: Path, size: int, renderer: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        subprocess.run([renderer, "-w", str(size), "-h", str(size), str(source)],
                       stdout=output, check=True)


def build_one(name: str, base_svg: bytes, logo_root: ET.Element, output: Path,
              resource_root: Path | None, renderer: str) -> None:
    base_root = ET.fromstring(base_svg)
    generated_dir = output / name
    generated_dir.mkdir(parents=True, exist_ok=True)
    # Keep a standalone composited SVG beside the PNGs so future app modules can
    # use the same artwork without copying the large logo into their resources.
    foreground_svg = generated_dir / "ic_launcher_foreground.svg"
    foreground_svg.write_bytes(make_composite(base_root, logo_root))
    square_svg = generated_dir / "ic_launcher-square.svg"
    square_svg.write_bytes(make_composite(base_root, logo_root, launcher=True))
    round_svg = generated_dir / "ic_launcher-round.svg"
    # Keep the full square artwork in round-icon resources too. Android launchers
    # apply their own icon mask; pre-clipping here permanently discarded the
    # corners and other artwork outside a circle.
    round_svg.write_bytes(make_composite(base_root, logo_root, launcher=True))
    targets = [generated_dir]
    if resource_root is not None:
        targets = [resource_root]
        # Adaptive-icon XML applies the launcher mask to the art. Use the
        # generated full-frame bitmap resources so corners are not pre-clipped.
        adaptive_dir = resource_root / "mipmap-anydpi-v26"
        for adaptive_icon in ("ic_launcher.xml", "ic_launcher_round.xml"):
            (adaptive_dir / adaptive_icon).unlink(missing_ok=True)
    for target in targets:
        for density, (legacy_size, foreground_size) in DENSITIES.items():
            folder = target / f"mipmap-{density}"
            rasterize(foreground_svg, folder / "ic_launcher_foreground.png", foreground_size, renderer)
            rasterize(square_svg, folder / "ic_launcher.png", legacy_size, renderer)
            rasterize(round_svg, folder / "ic_launcher_round.png", legacy_size, renderer)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--renderer", default=shutil.which("rsvg-convert") or "rsvg-convert",
                        help="librsvg rsvg-convert executable")
    parser.add_argument("--output", type=Path, default=GENERATED,
                        help="generated source/icon directory for apps without projects yet")
    parser.add_argument("--only", choices=sorted(BASES), action="append",
                        help="build only this app (may be supplied repeatedly)")
    args = parser.parse_args()
    if not ZIP.is_file() or not LOGO.is_file():
        print("icons.zip and infini.svg must be present in the project root", file=sys.stderr)
        return 2
    try:
        logo = ET.fromstring(LOGO.read_bytes())
        with zipfile.ZipFile(ZIP) as archive:
            members = set(archive.namelist())
            for name in args.only or BASES.keys():
                filename = BASES[name]
                if filename not in members:
                    raise FileNotFoundError(f"{filename} is missing from {ZIP.name}")
                destination = ROOTS[name]
                # Keep exports ready for components whose standalone projects
                # have not been created, without inventing those projects here.
                target = destination if destination.exists() else None
                build_one(name, archive.read(filename), logo, args.output, target, args.renderer)
                print(f"Built {name} launcher art" + (f" -> {destination}" if target else " (generated bundle)"))
    except (OSError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Cannot build launcher icons: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

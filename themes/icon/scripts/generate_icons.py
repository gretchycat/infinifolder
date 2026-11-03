#!/usr/bin/env python3
"""Build original SVG file and URI icons from the supported-types inventory."""

from __future__ import annotations

import csv
import html
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


HERE = Path(__file__).resolve().parent
ICON_DIR = HERE.parent
CSV_PATH = ICON_DIR.parent / "supported-types.csv"
ASSIGNMENTS_PATH = ICON_DIR / "base-assignments.csv"
BASE_DIR = ICON_DIR / "bases"
THEME_DIR = ICON_DIR.parent / "default theme"
OUTPUT_DIR = THEME_DIR / "svg"
SVG_NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG_NS)

DIRECTORIES = [
    ("directory", "FOLDER", '<path d="M48 63h32v24H48zM53 58h22l5 5H48z" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("home", "HOME", '<path d="m42 68 22-19 22 19v19H70V74H57v13H42z" fill="url(#directory-light)" stroke="#18324a" stroke-width="4" stroke-linejoin="round"/>'),
    ("documents", "DOCS", '<path d="M48 54h27l9 9v25H48zM75 54v10h9M55 70h20M55 77h20" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>'),
    ("images", "IMAGES", '<rect x="45" y="56" width="39" height="30" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><circle cx="56" cy="65" r="3" fill="#f2bd72"/><path d="m48 81 10-10 7 7 6-6 10 9" fill="none" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("videos", "VIDEOS", '<rect x="45" y="56" width="39" height="30" rx="4" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="m59 62 15 9-15 9z" fill="#75b5cb" stroke="#18324a" stroke-width="2" stroke-linejoin="round"/>'),
    ("audio", "AUDIO", '<path d="M47 68h9l12-10v28L56 76h-9zM74 65q9 7 0 14M80 61q15 10 0 22" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("ebooks", "EBOOKS", '<path d="M45 58q11-5 19 2 8-7 19-2v27q-11-5-19 2-8-7-19-2zM64 61v26" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("downloads", "DOWNLOADS", '<path d="M64 53v24m-10-10 10 10 10-10M49 82v6h30v-6" fill="none" stroke="#18324a" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("archives", "ARCHIVES", '<path d="M48 58h32v28H48zM44 54h40v8H44zM60 66h8v5h-8zm0 9h8v5h-8z" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("apps", "APPS", '<g fill="url(#directory-light)" stroke="#18324a" stroke-width="3"><rect x="48" y="56" width="13" height="13" rx="2"/><rect x="67" y="56" width="13" height="13" rx="2"/><rect x="48" y="74" width="13" height="13" rx="2"/><rect x="67" y="74" width="13" height="13" rx="2"/></g>'),
    ("camera", "CAMERA", '<path d="M47 62h9l4-6h11l4 6h8v24H47z" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/><circle cx="64" cy="73" r="8" fill="#b7d6fa" fill-opacity=".55" stroke="#18324a" stroke-width="3"/>'),
    ("screenshots", "SCREENSHOTS", '<rect x="51" y="55" width="27" height="32" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M57 61h15M57 67h15M57 73h10" stroke="#75b5cb" stroke-width="3" stroke-linecap="round"/>'),
    ("music", "MUSIC", '<path d="M55 74V57l20-5v17M55 57l20-5" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/><ellipse cx="51" cy="77" rx="6" ry="4" transform="rotate(-18 51 77)" fill="#75b5cb" stroke="#18324a" stroke-width="2"/><ellipse cx="71" cy="72" rx="6" ry="4" transform="rotate(-18 71 72)" fill="#f39b62" stroke="#18324a" stroke-width="2"/><path d="M82 59q6 4 0 9m5-15q11 7 0 21" fill="none" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("movies", "MOVIES", '<rect x="46" y="57" width="36" height="29" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M47 65h34M47 78h34M55 58v6m10-6v6m10-6v6M55 79v6m10-6v6m10-6v6" stroke="#18324a" stroke-width="2"/>'),
    ("favorites", "FAVORITES", '<path d="m64 54 6 12 13 2-9 9 2 13-12-6-12 6 2-13-9-9 13-2z" fill="#f2bd72" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("projects", "PROJECTS", '<path d="M48 57h32v29H48zM57 52h14v8H57zM55 68h18M55 76h12" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>'),
    ("backups", "BACKUPS", '<path d="M50 61a16 16 0 0 1 28-5l5 5m0-10v10H73M78 79a16 16 0 0 1-28 5l-5-5m0 10V79h10" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("trash", "TRASH", '<path d="M52 61h24l-2 26H54zM49 56h30M59 52h10M60 66v14m8-14v14" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("desktop", "DESKTOP", '<rect x="46" y="55" width="37" height="26" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M64 81v6m-9 0h18" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("pictures", "PICTURES", '<rect x="45" y="56" width="39" height="30" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><circle cx="56" cy="65" r="3" fill="#f2bd72"/><path d="m48 81 10-10 7 7 6-6 10 9" fill="none" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("dcim", "DCIM", '<path d="M47 62h9l4-6h11l4 6h8v24H47z" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/><circle cx="64" cy="73" r="8" fill="#b7d6fa" fill-opacity=".55" stroke="#18324a" stroke-width="3"/>'),
    ("podcasts", "PODCASTS", '<circle cx="64" cy="69" r="5" fill="#f2bd72" stroke="#18324a" stroke-width="3"/><path d="M55 61a13 13 0 0 0 0 17m18-17a13 13 0 0 1 0 17M49 55a21 21 0 0 0 0 29m30-29a21 21 0 0 1 0 29" fill="none" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("recordings", "RECORDINGS", '<circle cx="64" cy="69" r="13" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><circle cx="64" cy="69" r="5" fill="#f39b62" fill-opacity=".8" stroke="#18324a" stroke-width="2"/>'),
    ("ringtones", "RINGTONES", '<rect x="55" y="53" width="18" height="34" rx="4" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M61 58h6M63 81h2M50 62q-5 6 0 12m-4-16q-8 10 0 20m32-16q5 6 0 12m4-16q8 10 0 20" fill="none" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("notifications", "NOTIFICATIONS", '<path d="M53 76h22l-3-5v-7a8 8 0 0 0-16 0v7zM61 81a3 3 0 0 0 6 0" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("alarms", "ALARMS", '<circle cx="64" cy="71" r="15" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M64 61v11l8 5M49 56l7-5m23 5-7-5" fill="none" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("wallpapers", "WALLPAPERS", '<rect x="49" y="54" width="30" height="35" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><circle cx="58" cy="63" r="3" fill="#f2bd72"/><path d="m52 82 9-10 6 6 4-4 5 6" fill="none" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("fonts", "FONTS", '<path d="m49 85 14-30h3l14 30M55 73h19M78 58h7m-3-4v31m-4 0h8" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("themes", "THEMES", '<path d="M64 53c-11 0-20 8-20 18s8 18 19 18h2c4 0 7-3 7-7 0-3-2-5-2-7 0-3 2-5 6-5h4c4 0 7-3 6-7-2-6-11-10-22-10z" fill="#fbfaf6" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/><circle cx="54" cy="68" r="2.6" fill="#f39b62"/><circle cx="63" cy="60" r="2.6" fill="#75b5cb"/><circle cx="74" cy="63" r="2.6" fill="#87d9a7"/><circle cx="59" cy="78" r="2.6" fill="#f0c45f"/>'),
    ("templates", "TEMPLATES", '<path d="M49 57h30v30H49zM56 52h30v30" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/><path d="M55 67h17m-17 7h17" stroke="#75b5cb" stroke-width="3" stroke-linecap="round"/>'),
    ("shared", "SHARED", '<circle cx="56" cy="63" r="6" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><circle cx="73" cy="63" r="6" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M45 84q1-12 11-12t11 12m0 0q1-12 11-12t11 12" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("public", "PUBLIC", '<circle cx="64" cy="70" r="16" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M48 70h32M64 54q-10 16 0 32 10-16 0-32zm-9 7q9 5 18 0m-18 18q9-5 18 0" fill="none" stroke="#18324a" stroke-width="2"/>'),
    ("cloud", "CLOUD", '<path d="M53 82h25a9 9 0 0 0 1-18 15 15 0 0 0-28-2 10 10 0 0 0 2 20z" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round"/>'),
    ("offline", "OFFLINE", '<path d="M50 63a22 22 0 0 1 28-2M54 70a15 15 0 0 1 19-2M60 78a7 7 0 0 1 7-1m-20-22 35 29" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round"/>'),
    ("recent", "RECENT", '<circle cx="64" cy="70" r="17" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M64 59v12l9 5" fill="none" stroke="#18324a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>'),
    ("temporary", "TEMP", '<path d="M53 56h22M58 56v12L50 82a4 4 0 0 0 4 6h20a4 4 0 0 0 4-6l-8-14V56M56 78h16" fill="url(#directory-light)" stroke="#18324a" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>'),
    ("contacts", "CONTACTS", '<circle cx="64" cy="64" r="7" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M50 87q1-14 14-14t14 14M48 59q2-8 8-10m24 10q-2-8-8-10" fill="none" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
    ("calendar", "CALENDAR", '<rect x="48" y="57" width="32" height="30" rx="3" fill="url(#directory-light)" stroke="#18324a" stroke-width="3"/><path d="M48 66h32m-23-13v8m14-8v8m-14 13h4m5 0h4m-13 7h4m5 0h4" stroke="#18324a" stroke-width="3" stroke-linecap="round"/>'),
]


def slug(value: str) -> str:
    value = value.lower().strip().lstrip(".")
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value or "type"


def load_base_assignments(file_rows: list[dict[str, str]]) -> dict[str, dict[str, str]]:
    if not ASSIGNMENTS_PATH.is_file():
        raise FileNotFoundError(f"Base assignment cache not found: {ASSIGNMENTS_PATH}")
    assignments: dict[str, dict[str, str]] = {}
    with ASSIGNMENTS_PATH.open("r", encoding="utf-8-sig", newline="") as source:
        for assignment in csv.DictReader(source):
            identifier = assignment["identifier"]
            if identifier in assignments:
                raise ValueError(f"Duplicate base assignment for {identifier}")
            assignments[identifier] = assignment

    inventory = {row["identifier"]: row for row in file_rows}
    missing = sorted(inventory.keys() - assignments.keys())
    stale = sorted(assignments.keys() - inventory.keys())
    if missing or stale:
        raise ValueError(
            "Base assignment cache must match file_type rows in supported-types.csv. "
            f"Missing: {', '.join(missing[:12]) or 'none'}; "
            f"stale: {', '.join(stale[:12]) or 'none'}."
        )
    for identifier, row in inventory.items():
        assignment = assignments[identifier]
        context_fields = ("extensions", "mime_type", "support_level", "notes", "source_paths")
        if any(assignment.get(field, "") != row.get(field, "") for field in context_fields):
            raise ValueError(
                f"Inventory details changed for {identifier}; review its row in {ASSIGNMENTS_PATH.name}."
            )
    return assignments


def svg_for(row: dict[str, str], base_svg: str, label: str, marker: str = "") -> str:
    root = ET.fromstring(base_svg)
    art = "".join(ET.tostring(child, encoding="unicode") for child in root)
    safe_label = html.escape(label, quote=True)
    safe_title = html.escape(row["identifier"])
    marker_art = f'<g stroke-opacity="0.5">{marker}</g>' if row["category"] == "directory_type" else marker
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="{SVG_NS}" width="128" height="128" viewBox="0 0 128 128" role="img" aria-labelledby="title">
  <title id="title">{safe_title}</title>
  <defs>
    <linearGradient id="directory-light" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop stop-color="#ffffff" stop-opacity="0.42"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0.08"/>
    </linearGradient>
  </defs>
  {art}
  {marker_art}
  <rect x="5" y="96" width="118" height="27" rx="8" fill="#18324a" stroke="#ffffff" stroke-width="1.5"/>
  <text x="64" y="115" text-anchor="middle" fill="#ffffff" font-family="DejaVu Sans,Arial,sans-serif" font-size="16" font-weight="700" textLength="108" lengthAdjust="spacingAndGlyphs">{safe_label}</text>
</svg>
'''


def main() -> int:
    if not CSV_PATH.is_file():
        print(f"Supported type CSV not found: {CSV_PATH}", file=sys.stderr)
        return 2
    with CSV_PATH.open("r", encoding="utf-8-sig", newline="") as source:
        rows = [row for row in csv.DictReader(source) if row["category"] in {"file_type", "uri_type"}]
    file_rows = [row for row in rows if row["category"] == "file_type"]
    if not file_rows:
        print("No file_type or uri_type rows found.", file=sys.stderr)
        return 2
    assignments = load_base_assignments(file_rows)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    metadata_path = THEME_DIR / "metadata.csv"
    if metadata_path.is_file():
        with metadata_path.open("r", encoding="utf-8-sig", newline="") as previous:
            for old_row in csv.DictReader(previous):
                old_icon = OUTPUT_DIR / old_row.get("filename", "")
                if old_icon.parent == OUTPUT_DIR and old_icon.suffix == ".svg":
                    old_icon.unlink(missing_ok=True)
    bases: dict[str, str] = {}
    metadata: list[dict[str, str]] = []
    used_names: set[str] = set()
    jobs = [(row, row["extensions"] or row["identifier"], None) for row in rows]
    for directory, label, marker in DIRECTORIES:
        jobs.append(({
            "category": "directory_type",
            "identifier": f"directory:{directory}",
            "extensions": "",
            "mime_type": "",
            "capabilities": "",
            "support_level": "directory icon",
            "module": "Infinifolder icon set",
        }, label, marker))

    for row, label, marker in jobs:
        if row["category"] == "file_type":
            assignment = assignments[row["identifier"]]
            base_name = assignment["base_icon"]
            assignment_basis = assignment["assignment_basis"]
            assignment_reason = assignment["assignment_reason"]
        elif row["category"] == "uri_type":
            base_name = "link"
            assignment_basis = "uri_scheme"
            assignment_reason = "URI schemes use the link pictogram."
        else:
            base_name = "folder"
            assignment_basis = "directory_type"
            assignment_reason = "Directory pictogram layered on the shared folder base."
        if base_name not in bases:
            base_path = BASE_DIR / f"{base_name}.svg"
            if not base_path.is_file():
                raise FileNotFoundError(f"Missing base artwork: {base_path}")
            bases[base_name] = base_path.read_text(encoding="utf-8")
        identity = row["identifier"].rstrip(":") if row["category"] == "uri_type" else row["identifier"]
        prefix = {"uri_type": "uri", "directory_type": "directory"}.get(row["category"], "file")
        stem = f"{prefix}-{slug(identity.removeprefix('directory:'))}"
        if stem in used_names:
            raise ValueError(f"Output filename collision: {stem}")
        used_names.add(stem)
        filename = f"{stem}.svg"
        art = bases[base_name] if row["identifier"] == "directory:directory" else svg_for(row, bases[base_name], label, marker or "")
        (OUTPUT_DIR / filename).write_text(art, encoding="utf-8")
        metadata.append({
            "filename": filename,
            "base_artwork": f"bases/{base_name}.svg",
            "category": row["category"],
            "identifier": row["identifier"],
            "display_label": label,
            "extensions": row["extensions"],
            "mime_type": row["mime_type"],
            "capabilities": row["capabilities"],
            "support_level": row["support_level"],
            "module": row["module"],
            "assignment_basis": assignment_basis,
            "assignment_reason": assignment_reason,
        })
    for base_path in sorted(BASE_DIR.glob("*.svg")):
        filename = f"base-fallback-{base_path.stem}.svg"
        (OUTPUT_DIR / filename).write_bytes(base_path.read_bytes())
        metadata.append(dict.fromkeys(metadata[0], "") | {
            "filename": filename, "base_artwork": f"bases/{base_path.name}",
            "category": "base_fallback", "identifier": f"base:{base_path.stem}",
            "display_label": f"{base_path.stem.title()} base fallback",
            "support_level": "base icon fallback", "module": "Infinifolder icon set",
        })
    with metadata_path.open("w", encoding="utf-8-sig", newline="") as target:
        fields = list(metadata[0])
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(metadata)
    print(f"Generated {len(metadata)} SVG icons in {OUTPUT_DIR}")
    from build_icon_theme import main as package_theme
    package_theme([])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

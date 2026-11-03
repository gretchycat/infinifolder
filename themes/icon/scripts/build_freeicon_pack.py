#!/usr/bin/env python3
"""Export the canonical theme for Freeicon review and freedesktop theme import."""
import csv
import shutil
from pathlib import Path
from build_icon_theme import main as package_theme

ICON_DIR = Path(__file__).resolve().parent.parent
SOURCE = ICON_DIR.parent / "default theme"
PACK_DIR = ICON_DIR / "freeicon-pack"


def main() -> int:
    # Artwork is maintained directly in the project theme, never copied back
    # from an older export over the current source.
    with (SOURCE / "metadata.csv").open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    svg_dir = PACK_DIR / "svg"
    svg_dir.mkdir(parents=True, exist_ok=True)
    for old in svg_dir.glob("*.svg"):
        old.unlink()
    for row in rows:
        filename = row["filename"]
        if Path(filename).name != filename or not filename.endswith(".svg"):
            raise ValueError(f"Invalid artwork filename: {filename}")
        shutil.copy2(SOURCE / "svg" / filename, svg_dir / filename)
    shutil.copy2(SOURCE / "metadata.csv", PACK_DIR / "metadata.csv")
    shutil.copy2(SOURCE / "SUBMISSION-INFO.md", PACK_DIR / "SUBMISSION-INFO.md")
    package_theme([])
    print(f"Exported {len(rows)} SVG source files to {PACK_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

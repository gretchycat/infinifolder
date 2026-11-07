#!/usr/bin/env python3
"""Regenerate theme artwork and all app icons from icons.zip and infini.svg."""

from __future__ import annotations

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "icons.zip"
THEME_BASES = ROOT / "themes/icon/bases"
BASE_NAMES = ("archive.svg", "audio.svg", "binary.svg", "code.svg", "disk.svg",
              "document.svg", "folder.svg", "image.svg", "link.svg", "video.svg")


def main() -> int:
    if not ARCHIVE.is_file() or not (ROOT / "infini.svg").is_file():
        print("Expected icons.zip and infini.svg in the project root", file=sys.stderr)
        return 2
    if not shutil.which("rsvg-convert"):
        print("rsvg-convert is required to render launcher PNGs (install librsvg)", file=sys.stderr)
        return 2
    try:
        with zipfile.ZipFile(ARCHIVE) as archive:
            members = set(archive.namelist())
            missing = sorted(set(BASE_NAMES) - members)
            if missing:
                raise FileNotFoundError("icons.zip is missing theme bases: " + ", ".join(missing))
            # Validate all SVGs before replacing any current theme source files.
            payloads = {name: archive.read(name) for name in BASE_NAMES}
        for name, data in payloads.items():
            (THEME_BASES / name).write_bytes(data)
        subprocess.run([sys.executable, str(ROOT / "themes/icon/scripts/generate_icons.py")],
                       cwd=ROOT, check=True)
        subprocess.run([sys.executable, str(ROOT / "scripts/build-app-icons.py")],
                       cwd=ROOT, check=True)
    except (OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Icon regeneration failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

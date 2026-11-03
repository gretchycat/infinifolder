#!/usr/bin/env python3
"""Compatibility entry point; theme tooling lives under themes/icon/scripts/."""
from pathlib import Path
import runpy
import sys

if __name__ == "__main__":
    script = Path(__file__).resolve().parents[1] / "themes/icon/scripts/build_icon_theme.py"
    sys.path.insert(0, str(script.parent))
    runpy.run_path(str(script), run_name="__main__")

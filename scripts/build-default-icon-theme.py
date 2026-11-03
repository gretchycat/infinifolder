#!/usr/bin/env python3
"""Compatibility entry point; theme tooling lives under themes/icon/scripts/."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parents[1] / "themes/icon/scripts/build_icon_theme.py"), run_name="__main__")

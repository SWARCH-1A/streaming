#!/usr/bin/env python3
"""Manage the root Compose's nine-service local installation (macOS/Linux/WSL)."""
import os
from pathlib import Path
import runpy

os.environ["P1_PROFILE"] = "local"
runpy.run_path(str(Path(__file__).resolve().parents[1] / "p1/manage.py"), run_name="__main__")

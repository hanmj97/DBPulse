#!/usr/bin/env python3
"""Generate Windows DBPulse app icon from the checked-in approved artwork.

No network; generated .ico is a build artifact, never user profile data.
Pillow 11.3.0 is installed by the Windows build workflow.
"""
from __future__ import annotations
from pathlib import Path
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets" / "DBPulse-icon-source.webp"
OUTPUT = ROOT / "assets" / "DBPulse.ico"
SIZES = ((16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256))


def build_icon(source: Path = SOURCE, output: Path = OUTPUT) -> None:
    if not source.is_file():
        raise RuntimeError("DBPULSE_ICON_SOURCE_MISSING")
    if output.exists():
        # A CI re-run cannot silently use an out-of-date or user-customized icon.
        raise RuntimeError("DBPULSE_ICON_OUTPUT_ALREADY_EXISTS")
    with Image.open(source) as source_image:
        if source_image.width < 128 or source_image.height < 128:
            raise RuntimeError("DBPULSE_ICON_SOURCE_TOO_SMALL")
        artwork = source_image.convert("RGBA").resize((256, 256), Image.Resampling.LANCZOS)
    output.parent.mkdir(parents=True, exist_ok=True)
    artwork.save(output,format="ICO",sizes=SIZES)
    with Image.open(output) as verify:
        if not set(SIZES).issubset(verify.ico.sizes()):
            output.unlink(missing_ok=True)
            raise RuntimeError("DBPULSE_ICON_MULTIRES_VERIFY_FAILED")


if __name__ == "__main__":
    try:
        build_icon()
        print("DBPULSE_WINDOWS_ICON_7_SIZES_READY")
    except (OSError, ValueError, RuntimeError) as exc:
        print(str(exc) if isinstance(exc,RuntimeError) else "DBPULSE_ICON_BUILD_FAILED",file=sys.stderr)
        sys.exit(2)

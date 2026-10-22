#!/usr/bin/env python3
"""Read-only, fail-closed release preflight for a *stopped* DBPulse Windows app.

Inspects the installed app-image and only the ten known local SQLite metadata
tables; it never uploads/writes record contents, reconfigures SQL Server, or
deletes files. It is NOT a data backup or a production release approval.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import sys

EXPECTED_MARKER = b"DBPULSE_SQLITE_DESKTOP_V1\n"
TABLES = (
    "local_target_registry", "metric_snapshots", "query_performance_snapshots",
    "incidents", "blocking_chain_samples", "blocking_chain_edges",
    "deadlock_events", "deadlock_participants", "wait_samples", "wait_observations",
)
BUNDLE = ("DBPulse.exe", "window/DBPulse.WindowHost.exe", "runtime", "app")


class PreflightError(Exception):
    pass


def _plain(path: Path, directory: bool = False) -> bool:
    if path.is_symlink():
        return False
    return path.is_dir() if directory else path.is_file()


def inspect(root: Path, installed: Path) -> dict[str, int]:
    if not _plain(root, True) or not _plain(installed, True):
        raise PreflightError("FOLDER_MISSING_OR_UNSAFE")
    if any(not _plain(installed / part, part in ("runtime", "app")) for part in BUNDLE):
        raise PreflightError("INSTALLED_BUNDLE_INCOMPLETE")
    if any((installed / part).is_symlink() for part in ("window", "runtime", "app")):
        raise PreflightError("UNSAFE_INSTALL_DIRECTORY")

    data, desktop = root / "data", root / "desktop"
    if not _plain(data, True) or not _plain(desktop, True):
        raise PreflightError("DATA_DIRECTORY_MISSING_OR_UNSAFE")
    marker = desktop / "sqlite-mode.enabled"
    if not _plain(marker) or marker.stat().st_size > 100:
        raise PreflightError("SQLITE_MARKER_MISSING_OR_INVALID")
    if marker.read_bytes() != EXPECTED_MARKER:
        raise PreflightError("SQLITE_MARKER_INVALID")

    db = data / "dbpulse.sqlite"
    if not _plain(db) or db.stat().st_size < 4096:
        raise PreflightError("SQLITE_MISSING_OR_UNSAFE")

    # Mode=ro prevents accidental DB creation. NOT immutable=1; this honours
    # SQLite WAL so the test must run after an orderly application shutdown.
    uri = db.resolve().as_uri() + "?mode=ro"
    try:
        with closing(sqlite3.connect(uri, uri=True, timeout=5)) as con:
            con.execute("PRAGMA query_only=ON")
            if con.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                raise PreflightError("SQLITE_INTEGRITY_FAILED")
            if con.execute("PRAGMA foreign_key_check").fetchone() is not None:
                raise PreflightError("SQLITE_FOREIGN_KEYS_FAILED")
            found = {row[0] for row in con.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
            if not set(TABLES).issubset(found):
                raise PreflightError("SQLITE_REQUIRED_TABLES_MISSING")
            counts = {t: con.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
                      for t in TABLES}
    except sqlite3.Error:
        raise PreflightError("SQLITE_READ_FAILED") from None
    return counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Stopped DBPulse app: local installation + SQLite integrity preflight"
    )
    parser.add_argument("--root", type=Path, help="Test override: DBPulse user-data root")
    parser.add_argument("--installed", type=Path, help="Test override: installed app folder")
    args = parser.parse_args(argv)
    local = os.environ.get("LOCALAPPDATA")
    if (args.root is None or args.installed is None) and not local:
        print("RELEASE_PREFLIGHT_STOPPED: LOCALAPPDATA_REQUIRED", file=sys.stderr)
        return 2
    root = args.root or Path(local) / "DBPulse"
    installed = args.installed or Path(local) / "Programs" / "DBPulse"
    try:
        counts = inspect(root, installed)
    except (PreflightError, OSError, ValueError) as exc:
        code = str(exc) if isinstance(exc, PreflightError) else "FILESYSTEM_ERROR"
        print("RELEASE_PREFLIGHT_STOPPED: " + code, file=sys.stderr)
        return 2
    # Only numeric totals. No SQL text, target name, username or local paths.
    print("RELEASE_PREFLIGHT_LOCAL_INSTALL_AND_SQLITE_OK")
    print("REQUIRED_TABLES_VERIFIED: " + str(len(TABLES)))
    print("REGISTERED_TARGET_COUNT: " + str(counts["local_target_registry"]))
    print("TOTAL_METADATA_ROWS: " + str(sum(counts.values())))
    print("NO_SOURCE_DATA_OR_CREDENTIALS_MODIFIED")
    print("NOT_A_BACKUP_OR_PRODUCTION_RELEASE_APPROVAL")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

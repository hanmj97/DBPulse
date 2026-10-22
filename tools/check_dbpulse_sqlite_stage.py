#!/usr/bin/env python3
"""Read-only DBPulse Phase 15 SQLite staging/preview integrity smoke test.

Uses only Python's standard library. Never creates, deletes, or modifies the
SQLite file. Values such as login names, SQL text and credentials are not shown.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import sys

TABLES = (
    "local_target_registry",
    "metric_snapshots",
    "query_performance_snapshots",
    "incidents",
    "blocking_chain_samples",
    "blocking_chain_edges",
    "deadlock_events",
    "deadlock_participants",
    "wait_samples",
    "wait_observations",
)


def check(path: Path) -> dict[str, int]:
    if not path.is_file() or path.is_symlink() or path.suffix.lower() != ".sqlite":
        raise ValueError("SQLITE_STAGE_FILE_INVALID")
    # Open existing file strictly read-only; URI mode=ro must never create it.
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.execute("PRAGMA foreign_keys=ON")
        if db.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
            raise ValueError("SQLITE_FOREIGN_KEYS_DISABLED")
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("SQLITE_INTEGRITY_CHECK_FAILED")
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("SQLITE_FOREIGN_KEY_VIOLATION")
        existing = {row[0] for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if not set(TABLES).issubset(existing):
            raise ValueError("SQLITE_METADATA_TABLE_MISSING")
        result = {table: db.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
                  for table in TABLES}  # Hard-coded identifier allowlist.
        if sum(result.values()) == 0:
            raise ValueError("SQLITE_METADATA_UNEXPECTEDLY_EMPTY")
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", required=True, type=Path)
    args = parser.parse_args()
    try:
        counts = check(args.file)
    except (OSError, sqlite3.Error, ValueError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else "SQLITE_STAGE_READ_FAILED"
        print("SQLITE_STAGE_CHECK_FAILED: " + code, file=sys.stderr)
        return 1
    for table, count in counts.items():
        print(f"{table}: {count}")
    print(f"TOTAL_METADATA_ROWS: {sum(counts.values())}")
    print("SQLITE_STAGE_READ_ONLY_INTEGRITY_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

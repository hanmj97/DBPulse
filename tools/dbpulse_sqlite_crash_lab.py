#!/usr/bin/env python3
"""DBPulse Phase 16: disposable SQLite WAL crash/recovery exercise.

Only a fresh, isolated test database is created. The DBPulse production DB,
its backup, configuration, EXE, and Docker containers are never opened.
"""
from __future__ import annotations

import argparse
import contextlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

EXIT_COMMITTED = 27
EXIT_UNCOMMITTED = 28
SCHEMA = """
PRAGMA foreign_keys=ON;
CREATE TABLE parent_events (
    id INTEGER PRIMARY KEY,
    payload TEXT NOT NULL
);
CREATE TABLE child_events (
    id INTEGER PRIMARY KEY,
    parent_id INTEGER NOT NULL REFERENCES parent_events(id),
    payload TEXT NOT NULL
);
"""


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(str(path), timeout=5.0)
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA busy_timeout=5000")
    return connection


def assert_integrity(conn: sqlite3.Connection) -> None:
    if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise RuntimeError("SQLITE_INTEGRITY_FAILED")
    if conn.execute("PRAGMA foreign_key_check").fetchall():
        raise RuntimeError("SQLITE_FOREIGN_KEYS_FAILED")


def ids(conn: sqlite3.Connection) -> list[int]:
    return [row[0] for row in conn.execute("SELECT id FROM parent_events ORDER BY id")]


def initialize_scratch(path: Path) -> None:
    with contextlib.closing(connect(path)) as db:
        if db.execute("PRAGMA journal_mode=WAL").fetchone()[0].lower() != "wal":
            raise RuntimeError("WAL_MODE_UNAVAILABLE")
        db.executescript(SCHEMA)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("INSERT INTO parent_events(id, payload) VALUES (1, 'seed')")
        db.execute("INSERT INTO child_events(id, parent_id, payload) VALUES (1, 1, 'seed-child')")
        db.commit()


def child_crash(path: Path, scenario: str) -> None:
    # os._exit deliberately leaves the connection open. This is NEVER invoked
    # in the parent process or against an existing DBPulse data file.
    db = connect(path)
    db.execute("PRAGMA synchronous=FULL")
    db.execute("PRAGMA wal_autocheckpoint=0")
    db.execute("BEGIN IMMEDIATE")
    if scenario == "committed":
        db.execute("INSERT INTO parent_events(id, payload) VALUES (2, 'durable')")
        db.execute("INSERT INTO child_events(id, parent_id, payload) VALUES (2, 2, 'durable-child')")
        db.commit()
        os._exit(EXIT_COMMITTED)
    if scenario == "uncommitted":
        db.execute("INSERT INTO parent_events(id, payload) VALUES (3, 'should-rollback')")
        db.execute("INSERT INTO child_events(id, parent_id, payload) VALUES (3, 3, 'should-rollback')")
        os._exit(EXIT_UNCOMMITTED)
    os._exit(90)


def validate_child_path(path: Path) -> None:
    # Prevent accidentally invoking the destructive crash-only child mode on
    # any real DBPulse data file via direct CLI invocation.
    if (path.name != "scratch.sqlite" or
            not path.parent.name.startswith("dbpulse-wal-crash-") or
            not (path.parent / ".disposable-crash-lab").is_file()):
        raise RuntimeError("REFUSING_NONDISPOSABLE_DATABASE")


def run_child(path: Path, scenario: str, expected_code: int) -> None:
    validate_child_path(path)
    proc = subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), "_child", str(path), scenario],
        capture_output=True, text=True, timeout=20, check=False,
    )
    if proc.returncode != expected_code:
        raise RuntimeError("UNEXPECTED_CRASH_LAB_CHILD_EXIT")


def rows_match(db: sqlite3.Connection, expected: list[int]) -> None:
    if ids(db) != expected:
        raise RuntimeError("CRASH_LAB_ROWS_MISMATCH")
    if db.execute("SELECT COUNT(*) FROM child_events").fetchone()[0] != len(expected):
        raise RuntimeError("CRASH_LAB_CHILD_ROWS_MISMATCH")
    assert_integrity(db)


def corrupt_disposable_copy(path: Path) -> bool:
    # Corrupt ONLY a throwaway COPY of the verified backup. No real user DB.
    with open(path, "r+b") as file:
        file.seek(0)
        file.write(b"NOT_A_SQLITE_FILE")
        file.flush()
        os.fsync(file.fileno())
    try:
        with contextlib.closing(sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)) as conn:
            conn.execute("PRAGMA integrity_check").fetchone()
    except sqlite3.DatabaseError:
        return True
    return False


def drill(scratch_parent: Path) -> dict[str, bool]:
    scratch_parent = scratch_parent.expanduser().resolve()
    scratch_parent.mkdir(parents=True, exist_ok=True)
    # Work only inside a newly created, private temporary subdirectory.
    with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-", dir=str(scratch_parent)) as td:
        work = Path(td)
        dbpath = work / "scratch.sqlite"
        (work / ".disposable-crash-lab").write_text("DBPulse disposable crash lab", encoding="utf-8")
        backup = work / "restored-backup.sqlite"
        corrupt = work / "corrupt-copy.sqlite"
        initialize_scratch(dbpath)
        run_child(dbpath, "committed", EXIT_COMMITTED)
        # Ensure this really exercised committed records in a WAL sidecar.
        wal_path = Path(str(dbpath) + "-wal")
        if not wal_path.is_file() or wal_path.stat().st_size == 0:
            raise RuntimeError("COMMITTED_WAL_NOT_PRESENT")
        # A committed WAL record survives an abrupt process exit.
        with contextlib.closing(connect(dbpath)) as db:
            rows_match(db, [1, 2])
            # Online SQLite backup API includes WAL-resident committed rows.
            with contextlib.closing(connect(backup)) as restored:
                db.backup(restored)
                restored.commit()
        with contextlib.closing(connect(backup)) as restored:
            rows_match(restored, [1, 2])
        # Crash during an uncommitted transaction must not add data.
        run_child(dbpath, "uncommitted", EXIT_UNCOMMITTED)
        with contextlib.closing(connect(dbpath)) as db:
            rows_match(db, [1, 2])
        # Validation rejects a deliberately corrupted disposable copy.
        shutil.copy2(backup, corrupt)
        if not corrupt_disposable_copy(corrupt):
            raise RuntimeError("CORRUPT_COPY_NOT_DETECTED")
    return {
        "committed_transaction_recovered": True,
        "uncommitted_transaction_rolled_back": True,
        "wal_backup_restore_verified": True,
        "foreign_keys_and_integrity_verified": True,
        "corrupt_test_copy_detected": True,
        "production_sqlite_never_opened": True,
    }


def default_scratch_parent() -> Path:
    if os.getenv("LOCALAPPDATA"):
        return Path(os.environ["LOCALAPPDATA"]) / "DBPulse" / "crash-lab"
    return Path(tempfile.gettempdir()) / "dbpulse-crash-lab"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Disposable SQLite crash and WAL verification")
    parser.add_argument("--scratch-parent", type=Path, default=default_scratch_parent(),
                        help="A directory under which NEW temporary test DBs are created")
    args = parser.parse_args(argv)
    try:
        result = drill(args.scratch_parent)
    except (OSError, sqlite3.Error, RuntimeError, subprocess.TimeoutExpired) as e:
        print("SQLITE_CRASH_DRILL_FAILED: " + type(e).__name__ + ": " + str(e).splitlines()[0], file=sys.stderr)
        return 2
    for key, ok in result.items():
        print(key.upper() + (": OK" if ok else ": FAILED"))
    print("DBPULSE_SQLITE_WAL_CRASH_RECOVERY_DRILL_OK")
    print("SCRATCH_DATABASE_REMOVED")
    print("NO_DBPULSE_DATA_OR_DOCKER_MODIFIED")
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "_child":
        # Child command is internal to this tool; the path is generated by the
        # parent inside a fresh temporary directory, never user-supplied DBs.
        candidate = Path(sys.argv[2])
        validate_child_path(candidate)
        child_crash(candidate, sys.argv[3])
        raise SystemExit(91)
    raise SystemExit(main())

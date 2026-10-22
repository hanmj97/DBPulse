#!/usr/bin/env python3
"""DBPulse Phase 15: verified, non-destructive PostgreSQL -> SQLite staging copy.

One-time developer migration tool. Does NOT change the live DBPulse runtime or
remove PostgreSQL. Requires Docker solely to read the already-running PG source.
No third-party Python modules are needed.
"""

from __future__ import annotations

import argparse
import csv
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import time
import uuid
from urllib.parse import quote
from typing import TextIO

TABLES = (
    ("local_target_registry", "id"),
    ("metric_snapshots", "id"),
    ("query_performance_snapshots", "id"),
    ("incidents", "id"),
    ("blocking_chain_samples", "id"),
    ("blocking_chain_edges", "id"),
    ("deadlock_events", "id"),
    ("deadlock_participants", "deadlock_event_id, session_id"),
    ("wait_samples", "id"),
    ("wait_observations", "id"),
)
TABLE_NAMES = frozenset(name for name, _ in TABLES)


class MigrationError(Exception):
    """Deliberately non-sensitive failure reason, safe to show to an operator."""


def source_sql() -> str:
    """One PostgreSQL session, one read-only REPEATABLE READ snapshot."""
    sql = [
        "\\set ON_ERROR_STOP on",
        "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;",
        "SET LOCAL TIME ZONE 'UTC';",
    ]
    for name, keys in TABLES:
        # Both identifiers are compile-time allowlisted, never user input.
        sql.extend((
            "COPY (SELECT json_build_object('kind','begin','table','" + name +
            "')::text) TO STDOUT WITH (FORMAT CSV);",
            "COPY (SELECT json_build_object('kind','row','table','" + name +
            "','values',to_jsonb(t))::text FROM public." + name + " t ORDER BY " + keys +
            ") TO STDOUT WITH (FORMAT CSV);",
            "COPY (SELECT json_build_object('kind','end','table','" + name +
            "','count',(SELECT count(*) FROM public." + name +
            "))::text) TO STDOUT WITH (FORMAT CSV);",
        ))
    sql.append("COMMIT;")
    return "\n".join(sql) + "\n"


def columns_and_types(db: sqlite3.Connection, table: str) -> tuple[tuple[str, ...], tuple[str, ...]]:
    if table not in TABLE_NAMES:
        raise MigrationError("UNKNOWN_METADATA_TABLE")
    info = db.execute(f"PRAGMA table_info({table})").fetchall()
    if not info:
        raise MigrationError("SQLITE_SCHEMA_INCOMPLETE")
    return tuple(row[1] for row in info), tuple(row[2].upper() for row in info)


def canonical_record(values: dict, columns: tuple[str, ...], types: tuple[str, ...]) -> bytes:
    """Canonical compare across PostgreSQL JSON, SQLite INTEGER booleans/REALs."""
    if set(values) != set(columns):
        raise MigrationError("METADATA_SCHEMA_MISMATCH")
    out = {}
    for col, typ in zip(columns, types):
        value = values[col]
        if value is None:
            out[col] = None
        elif typ in ("BOOLEAN", "BOOL"):
            if value not in (True, False, 0, 1):
                raise MigrationError("INVALID_BOOLEAN_VALUE")
            out[col] = bool(value)
        elif typ in ("REAL", "DOUBLE", "DOUBLE PRECISION", "FLOAT"):
            # Reject NaN/Infinity rather than silently damaging the migration.
            from math import isfinite
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise MigrationError("INVALID_FLOAT_VALUE")
            if not isfinite(float(value)):
                raise MigrationError("INVALID_FLOAT_VALUE")
            out[col] = float(value)
        elif typ in ("INTEGER", "BIGINT", "INT"):
            if isinstance(value, bool) or not isinstance(value, int):
                raise MigrationError("INVALID_INTEGER_VALUE")
            out[col] = int(value)
        else:
            # PostgreSQL row_to_json encodes TIMESTAMPTZ as exact text; store
            # it verbatim, including its microseconds and UTC offset.
            if not isinstance(value, str):
                raise MigrationError("INVALID_TEXT_OR_TIMESTAMP_VALUE")
            out[col] = value
    return json.dumps(out, sort_keys=True, ensure_ascii=False,
                      allow_nan=False, separators=(",", ":")).encode("utf-8") + b"\n"


def read_stream_into_stage(db: sqlite3.Connection, stream: TextIO) -> tuple[dict[str, int], dict[str, str]]:
    """Read strict framed COPY/CSV; reject missing tables, counts and rows."""
    counts = {}
    source_hashes = {}
    expected_index = 0
    active = None
    current_count = 0
    digest = None
    schema = {}
    inserts = {}

    for name, _ in TABLES:
        cols, types = columns_and_types(db, name)
        schema[name] = (cols, types)
        inserts[name] = ("INSERT INTO " + name + " (" + ", ".join(cols) +
                         ") VALUES (" + ", ".join("?" for _ in cols) + ")")

    try:
        lines = csv.reader(stream, strict=True)
        for csv_row in lines:
            if len(csv_row) != 1:
                raise MigrationError("POSTGRES_EXPORT_FORMAT_INVALID")
            try:
                event = json.loads(csv_row[0])
            except (ValueError, TypeError) as failure:
                raise MigrationError("POSTGRES_EXPORT_JSON_INVALID") from failure
            if not isinstance(event, dict):
                raise MigrationError("POSTGRES_EXPORT_JSON_INVALID")
            if expected_index >= len(TABLES):
                raise MigrationError("POSTGRES_EXPORT_HAS_EXTRA_DATA")
            expected = TABLES[expected_index][0]
            if event.get("table") != expected:
                raise MigrationError("POSTGRES_TABLE_ORDER_INVALID")
            kind = event.get("kind")
            if kind == "begin":
                if active is not None or set(event) != {"kind", "table"}:
                    raise MigrationError("POSTGRES_EXPORT_FRAMING_INVALID")
                active = expected
                current_count = 0
                digest = hashlib.sha256()
            elif kind == "row":
                if active != expected or set(event) != {"kind", "table", "values"}:
                    raise MigrationError("POSTGRES_EXPORT_FRAMING_INVALID")
                values = event["values"]
                if not isinstance(values, dict):
                    raise MigrationError("POSTGRES_EXPORT_ROW_INVALID")
                cols, types = schema[expected]
                digest.update(canonical_record(values, cols, types))
                db.execute(inserts[expected], tuple(values[col] for col in cols))
                current_count += 1
            elif kind == "end":
                if active != expected or set(event) != {"kind", "table", "count"}:
                    raise MigrationError("POSTGRES_EXPORT_FRAMING_INVALID")
                if (type(event["count"]) is not int or
                        event["count"] != current_count):
                    raise MigrationError("POSTGRES_SOURCE_COUNT_MISMATCH")
                counts[expected] = current_count
                source_hashes[expected] = digest.hexdigest()
                active = None
                expected_index += 1
            else:
                raise MigrationError("POSTGRES_EXPORT_FRAMING_INVALID")
    except csv.Error as failure:
        raise MigrationError("POSTGRES_EXPORT_CSV_INVALID") from failure
    if active is not None or expected_index != len(TABLES):
        raise MigrationError("POSTGRES_EXPORT_INCOMPLETE")
    return counts, source_hashes


def verify_stage(db: sqlite3.Connection, counts: dict[str, int],
                 hashes: dict[str, str]) -> None:
    """Counts, full row values, referential integrity and SQLite integrity."""
    for table, keys in TABLES:
        cols, types = columns_and_types(db, table)
        actual = db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
        if actual != counts[table]:
            raise MigrationError("SQLITE_ROW_COUNT_MISMATCH")
        digest = hashlib.sha256()
        for row in db.execute(f"SELECT * FROM {table} ORDER BY {keys}"):
            digest.update(canonical_record(dict(zip(cols, row)), cols, types))
        if digest.hexdigest() != hashes[table]:
            raise MigrationError("SQLITE_ROW_DIGEST_MISMATCH")
    if db.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
        raise MigrationError("SQLITE_FOREIGN_KEYS_DISABLED")
    if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise MigrationError("SQLITE_FOREIGN_KEY_VIOLATION")
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise MigrationError("SQLITE_INTEGRITY_CHECK_FAILED")


def require_backend_stopped() -> None:
    # Fail closed if an API OR unrelated service is listening on DBPulse's port.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        if sock.connect_ex(("127.0.0.1", 18081)) == 0:
            raise MigrationError("STOP_DBPULSE_PORT_18081_BEFORE_MIGRATION")


def validate_backup(backup: Path) -> None:
    if not backup.is_file() or backup.is_symlink() or backup.stat().st_size < 1024:
        raise MigrationError("VERIFIED_POSTGRES_BACKUP_REQUIRED")
    if backup.suffix.lower() != ".dump":
        raise MigrationError("POSTGRES_BACKUP_FORMAT_UNRECOGNIZED")


def stage_path() -> tuple[Path, Path]:
    base = os.getenv("LOCALAPPDATA")
    if not base or os.name != "nt":
        raise MigrationError("WINDOWS_LOCALAPPDATA_REQUIRED")
    directory = Path(base) / "DBPulse" / "data" / "staging"
    # Don't follow an existing directory junction/symlink to untrusted paths.
    for ancestor in (directory.parent.parent, directory.parent, directory):
        if ancestor.is_symlink():
            raise MigrationError("UNSAFE_STAGING_DIRECTORY")
    directory.mkdir(parents=True, exist_ok=True)
    if directory.is_symlink():
        raise MigrationError("UNSAFE_STAGING_DIRECTORY")
    token = uuid.uuid4().hex
    return (directory / f"dbpulse-pg-stage-{token}.partial.sqlite",
            directory / f"dbpulse-pg-stage-{token}.sqlite")


def promote_staged_file(partial: Path, completed: Path) -> None:
    """Promote only a verified, closed SQLite file; never overwrite old data."""
    for attempt in range(10):
        if completed.exists():
            raise MigrationError("STAGING_OUTPUT_ALREADY_EXISTS")
        try:
            partial.rename(completed)
            return
        except PermissionError:
            # Windows security scanners may briefly hold a closed file handle.
            # Retry only this transient class, with a hard upper bound (2s).
            if attempt == 9:
                raise
            time.sleep(0.2)


def migrate(project_root: Path, backup: Path, schema_path: Path) -> tuple[Path, dict[str, int]]:
    validate_backup(backup)
    require_backend_stopped()
    if not (project_root / "docker-compose.yml").is_file():
        raise MigrationError("DBPULSE_PROJECT_ROOT_REQUIRED")
    if not schema_path.is_file():
        raise MigrationError("SQLITE_SCHEMA_FILE_MISSING")
    if not shutil.which("docker"):
        raise MigrationError("DOCKER_NOT_INSTALLED_OR_NOT_ON_PATH")
    partial, completed = stage_path()
    os.close(os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
    process = None
    try:
        # sqlite3.Connection.__exit__ commits/rolls back but DOES NOT CLOSE.
        # Windows forbids renaming an open SQLite file: closing() is essential.
        with closing(sqlite3.connect(partial, timeout=10)) as db:
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA journal_mode=DELETE")
            db.execute("PRAGMA synchronous=FULL")
            if db.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
                raise MigrationError("SQLITE_FOREIGN_KEYS_DISABLED")
            db.executescript(schema_path.read_text(encoding="utf-8-sig"))
            db.execute("BEGIN IMMEDIATE")
            command = ["docker", "compose", "exec", "-T", "postgres", "psql",
                       "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "dbpulse",
                       "-d", "dbpulse", "-f", "-"]
            # stderr suppressed so PG messages cannot leak database contents,
            # usernames or connection details into the console/logs.
            process = subprocess.Popen(command, cwd=project_root,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, text=True,
                                       encoding="utf-8", errors="strict")
            assert process.stdin is not None and process.stdout is not None
            process.stdin.write(source_sql())
            process.stdin.close()
            counts, hashes = read_stream_into_stage(db, process.stdout)
            process.stdout.close()
            if process.wait(timeout=30) != 0:
                raise MigrationError("POSTGRES_READ_ONLY_EXPORT_FAILED")
            process = None
            verify_stage(db, counts, hashes)
            db.commit()
        # Inspect again after commit with a separate SQLite connection.
        with closing(sqlite3.connect(f"file:{quote(partial.as_posix(), safe='/:')}?mode=ro", uri=True)) as check:
            check.execute("PRAGMA foreign_keys=ON")
            verify_stage(check, counts, hashes)
        # Both database handles are now closed before the Windows rename.
        promote_staged_file(partial, completed)
        return completed, counts
    except (OSError, sqlite3.Error, subprocess.SubprocessError, UnicodeError, ValueError) as failure:
        raise MigrationError("STAGED_MIGRATION_FAILED_CHECK_LOCAL_ENVIRONMENT") from failure
    finally:
        if process is not None:
            process.kill()
            process.wait()
        if partial.exists():
            try:
                partial.unlink()
            except OSError:
                # Never hide the original failure with another WinError 32.
                # Preserve the unverified partial for manual review instead.
                print("STAGING_PARTIAL_FILE_CLEANUP_DEFERRED", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--verified-backup", type=Path, required=True,
                        help="Path of the separately restored and count-verified .dump backup")
    args = parser.parse_args(argv)
    project_root = args.project_root.resolve()
    schema = project_root / "backend" / "src" / "main" / "resources" / "schema-sqlite.sql"
    try:
        output, counts = migrate(project_root, args.verified_backup.resolve(), schema)
        for table, _ in TABLES:
            print(f"{table}: {counts[table]}")
        print(f"TOTAL_METADATA_ROWS: {sum(counts.values())}")
        print("SQLITE_STAGING_COUNTS_DIGESTS_FK_INTEGRITY_VERIFIED")
        print(f"STAGING_FILE: {output}")
        print("POSTGRESQL_SOURCE_UNCHANGED")
        print("DBPULSE_RUNTIME_STILL_USES_POSTGRESQL")
        return 0
    except MigrationError as failure:
        print(f"SQLITE_STAGING_FAILED: {failure}", file=sys.stderr)
        print("POSTGRESQL_SOURCE_UNCHANGED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

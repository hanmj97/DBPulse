"""Phase 15 DBPulse SQLite non-destructive backup and restore drill.

Only opens the desktop source in SQLite URI mode=ro. Backups use the SQLite
online backup API (not filesystem copies of a possibly WAL-backed live DB).
Never prints database record values, passwords, or SQL payloads.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import uuid

TABLES = (
    "local_target_registry", "metric_snapshots", "query_performance_snapshots",
    "incidents", "blocking_chain_samples", "blocking_chain_edges",
    "deadlock_events", "deadlock_participants", "wait_samples", "wait_observations",
)
MAX_MANIFEST_SIZE = 24_576


class VerificationError(Exception):
    pass


def read_only(path: Path) -> sqlite3.Connection:
    if not path.is_file() or path.is_symlink():
        raise VerificationError("SQLITE_FILE_MISSING_OR_UNSAFE")
    # URI read-only. Never use immutable=1: it would ignore uncheckpointed WAL.
    connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=15)
    connection.execute("PRAGMA busy_timeout=15000")
    connection.execute("PRAGMA query_only=ON")
    return connection


def _hash_bytes(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for segment in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(segment)
    return digest.hexdigest()


def _canonical(value):
    if isinstance(value, bytes):
        return {"blob": value.hex()}
    if isinstance(value, float):
        return {"float": value.hex()}
    if isinstance(value, int):
        return {"integer": value}
    if isinstance(value, str):
        return {"string": value}
    if value is None:
        return {"null": True}
    raise VerificationError("UNSUPPORTED_DATA_TYPE")


def inspect(connection: sqlite3.Connection) -> dict:
    integrity = connection.execute("PRAGMA integrity_check").fetchall()
    if integrity != [("ok",)]:
        raise VerificationError("SQLITE_INTEGRITY_FAILED")
    if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise VerificationError("SQLITE_FOREIGN_KEYS_FAILED")
    names = {row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if not set(TABLES).issubset(names):
        raise VerificationError("SQLITE_REQUIRED_TABLE_MISSING")

    signature = hashlib.sha256()
    schema_rows = connection.execute("""
        SELECT type, name, tbl_name, sql FROM sqlite_master
        WHERE type IN ('table','index','view','trigger')
        ORDER BY type, name, tbl_name
    """).fetchall()
    signature.update(json.dumps(schema_rows, ensure_ascii=False, separators=(",", ":"))
                     .encode("utf-8"))
    counts: dict[str, int] = {}
    contents: dict[str, str] = {}
    for table in TABLES:
        info = connection.execute(f'PRAGMA table_info("{table}")').fetchall()
        # Stable row ordering for row-by-row fingerprints, including composite PK.
        keys = [col[1] for col in sorted(info, key=lambda col: col[5]) if col[5] > 0]
        if not keys:
            raise VerificationError("TABLE_WITHOUT_PRIMARY_KEY")
        order = ",".join('"' + name + '"' for name in keys)
        digest = hashlib.sha256()
        count = 0
        for row in connection.execute(f'SELECT * FROM "{table}" ORDER BY {order}'):
            encoded = json.dumps([_canonical(v) for v in row], ensure_ascii=False,
                                 separators=(",", ":")).encode("utf-8")
            digest.update(len(encoded).to_bytes(8, "big"))
            digest.update(encoded)
            count += 1
        counts[table] = count
        contents[table] = digest.hexdigest()
    if counts["local_target_registry"] < 1 or counts["metric_snapshots"] < 1:
        raise VerificationError("EXPECTED_HISTORY_MISSING")
    return {"counts": counts, "row_sha256": contents, "schema_sha256": signature.hexdigest()}


def _check_folders(file: Path, root: Path) -> None:
    if file.is_symlink() or root.is_symlink():
        raise VerificationError("SYMLINK_NOT_ALLOWED")
    if not file.is_file():
        raise VerificationError("SQLITE_FILE_MISSING_OR_UNSAFE")
    if file.resolve() != (root / "data" / "dbpulse.sqlite").resolve():
        raise VerificationError("SQLITE_OUTSIDE_DESKTOP_DATA_FOLDER")
    for directory in (root, root / "data", root / "backups"):
        if directory.is_symlink():
            raise VerificationError("SYMLINK_NOT_ALLOWED")


def _check_equal(expected: dict, actual: dict) -> None:
    if expected != actual:
        raise VerificationError("RESTORED_DATABASE_MISMATCH")


def _restoration_drill(backup_path: Path, expected: dict) -> None:
    # A second database is rebuilt via SQLite's backup API in a temporary
    # directory; the persistent desktop source is never involved in restore.
    with tempfile.TemporaryDirectory(prefix="dbpulse-restore-") as directory:
        restored = Path(directory) / "restored.sqlite"
        with closing(read_only(backup_path)) as source, closing(sqlite3.connect(restored)) as target:
            source.backup(target, pages=128, sleep=0.05)
            target.commit()
        with closing(read_only(restored)) as check:
            _check_equal(expected, inspect(check))


def _exclusive_json_write(path: Path, contents: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(contents, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")


def make_backup(source: Path, backups: Path) -> tuple[Path, dict]:
    if not source.is_file() or source.is_symlink():
        raise VerificationError("SQLITE_FILE_MISSING_OR_UNSAFE")
    if backups.is_symlink():
        raise VerificationError("SYMLINK_NOT_ALLOWED")
    backups.mkdir(parents=True, exist_ok=True)
    if backups.is_symlink():
        raise VerificationError("SYMLINK_NOT_ALLOWED")

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    token = uuid.uuid4().hex[:10]
    final = backups / f"dbpulse-sqlite-{timestamp}-{token}.sqlite"
    manifest = backups / f"{final.name}.manifest.json"
    # No replacement or source mutation. Failure leaves final path uncommitted.
    temporary = backups / f".dbpulse-{token}.partial.sqlite"
    try:
        with closing(read_only(source)) as live, closing(sqlite3.connect(str(temporary))) as snapshot:
            live.backup(snapshot, pages=128, sleep=0.05)
            snapshot.commit()
        with closing(read_only(temporary)) as check:
            summary = inspect(check)
        _restoration_drill(temporary, summary)
        if final.exists() or manifest.exists():
            raise VerificationError("BACKUP_DESTINATION_ALREADY_EXISTS")
        # All connections are explicitly CLOSED before rename on Windows.
        temporary.rename(final)
        metadata = {
            "format": "dbpulse-sqlite-backup-v1",
            "created_utc": datetime.now(timezone.utc).isoformat(),
            "database_filename": final.name,
            "backup_sha256": _hash_bytes(final),
            **summary,
        }
        _exclusive_json_write(manifest, metadata)
        return final, metadata
    except Exception:
        if temporary.exists():
            try:
                temporary.unlink()
            except OSError:
                pass
        raise


def verify_backup(backup: Path) -> dict:
    if not backup.is_file() or backup.is_symlink() or backup.suffix != ".sqlite":
        raise VerificationError("BACKUP_NOT_FOUND_OR_UNSAFE")
    manifest_path = backup.with_name(backup.name + ".manifest.json")
    if (not manifest_path.is_file() or manifest_path.is_symlink()
            or manifest_path.stat().st_size > MAX_MANIFEST_SIZE):
        raise VerificationError("BACKUP_MANIFEST_NOT_FOUND_OR_INVALID")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (manifest.get("format") != "dbpulse-sqlite-backup-v1"
            or manifest.get("database_filename") != backup.name
            or manifest.get("backup_sha256") != _hash_bytes(backup)):
        raise VerificationError("BACKUP_HASH_OR_MANIFEST_MISMATCH")
    with closing(read_only(backup)) as check:
        summary = inspect(check)
    expected = {k: manifest[k] for k in ("counts", "row_sha256", "schema_sha256")}
    _check_equal(expected, summary)
    _restoration_drill(backup, summary)
    return summary


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DBPulse local SQLite restore drill; no writes to the desktop database")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    sub.add_parser("backup")
    verify = sub.add_parser("verify-backup")
    verify.add_argument("--backup", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "verify-backup":
            summary = verify_backup(args.backup)
            print("DBPULSE_SQLITE_BACKUP_RESTORE_DRILL_OK")
        else:
            appdata = os.environ.get("LOCALAPPDATA")
            if not appdata:
                raise VerificationError("WINDOWS_LOCALAPPDATA_REQUIRED")
            root = Path(appdata) / "DBPulse"
            datafile = root / "data" / "dbpulse.sqlite"
            _check_folders(datafile, root)
            if args.command == "check":
                with closing(read_only(datafile)) as connection:
                    summary = inspect(connection)
                print("DBPULSE_SQLITE_DESKTOP_READ_ONLY_INTEGRITY_OK")
            else:
                path, summary = make_backup(datafile, root / "backups" / "sqlite")
                print("DBPULSE_SQLITE_BACKUP_RESTORE_DRILL_OK")
                print("BACKUP_FILE: " + str(path))
                print("MANIFEST_FILE: " + str(path) + ".manifest.json")
        print("TABLES_VERIFIED: 10")
        print("TOTAL_METADATA_ROWS: " + str(sum(summary["counts"].values())))
        print("DESKTOP_SQLITE_SOURCE_NOT_MODIFIED_BY_TOOL")
        return 0
    except (VerificationError, OSError, sqlite3.Error, ValueError, KeyError, TypeError,
            json.JSONDecodeError) as error:
        # Never echo raw OS/SQL error messages, which could reveal private paths.
        code = str(error) if isinstance(error, VerificationError) else "SQLITE_BACKUP_IO_OR_FORMAT_ERROR"
        print("DBPULSE_SQLITE_RECOVERY_STOPPED: " + code, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

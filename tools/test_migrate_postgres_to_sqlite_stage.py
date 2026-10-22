"""Run with: py -3 -m unittest discover -s tools -p 'test_migrate_postgres_to_sqlite_stage.py' -v"""

import csv
from contextlib import closing
import io
import json
import sqlite3
import tempfile
from pathlib import Path
from unittest.mock import patch

import migrate_postgres_to_sqlite_stage as migration
import unittest

from migrate_postgres_to_sqlite_stage import (
    MigrationError, TABLES, canonical_record, read_stream_into_stage,
    source_sql, verify_stage,
)


class StageMigrationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        for name, _ in TABLES:
            if name == "deadlock_participants":
                self.db.execute("""CREATE TABLE deadlock_participants (
                    deadlock_event_id BIGINT NOT NULL,
                    session_id INTEGER NOT NULL,
                    was_victim BOOLEAN NOT NULL,
                    PRIMARY KEY(deadlock_event_id, session_id))""")
            elif name == "wait_observations":
                self.db.execute("""CREATE TABLE wait_observations (
                    id INTEGER PRIMARY KEY,
                    observation_name TEXT,
                    recorded_at TIMESTAMP,
                    avg_cpu_ms REAL,
                    limit_reached BOOLEAN)""")
            else:
                self.db.execute(f"CREATE TABLE {name} (id INTEGER PRIMARY KEY)")

    def tearDown(self):
        self.db.close()

    @staticmethod
    def framed(rows_by_table=None, overrides=None):
        rows_by_table = rows_by_table or {}
        out = io.StringIO(newline="")
        writer = csv.writer(out)
        for name, _ in TABLES:
            records = rows_by_table.get(name, [])
            writer.writerow([json.dumps({"kind": "begin", "table": name})])
            for data in records:
                writer.writerow([json.dumps({"kind": "row", "table": name, "values": data})])
            count = len(records)
            if overrides and name in overrides:
                count = overrides[name]
            writer.writerow([json.dumps({"kind": "end", "table": name, "count": count})])
        out.seek(0)
        return out

    def test_copy_all_tables_and_verify_microseconds_unicode_booleans_float(self):
        rows = {
            "local_target_registry": [{"id": 11}],
            "deadlock_participants": [{"deadlock_event_id": 15, "session_id": 31, "was_victim": True}],
            "wait_observations": [{
                "id": 20, "observation_name": "한글 \"quoted\", comma, newline\\n",
                "recorded_at": "2026-10-21T05:12:01.123456+00:00", "avg_cpu_ms": 1,
                "limit_reached": False,
            }],
        }
        counts, hashes = read_stream_into_stage(self.db, self.framed(rows))
        verify_stage(self.db, counts, hashes)
        self.assertEqual(3, sum(counts.values()))
        self.assertEqual(
            "2026-10-21T05:12:01.123456+00:00",
            self.db.execute("SELECT recorded_at FROM wait_observations").fetchone()[0],
        )
        self.assertEqual(1, self.db.execute(
            "SELECT was_victim FROM deadlock_participants").fetchone()[0])

    def test_detect_source_count_mismatch(self):
        with self.assertRaisesRegex(MigrationError, "POSTGRES_SOURCE_COUNT_MISMATCH"):
            read_stream_into_stage(self.db, self.framed({"local_target_registry": [{"id": 1}]},
                                                         {"local_target_registry": 2}))

    def test_detect_schema_mismatch(self):
        with self.assertRaisesRegex(MigrationError, "METADATA_SCHEMA_MISMATCH"):
            read_stream_into_stage(self.db, self.framed({"metric_snapshots": [{"id": 1,
                                                                                "unexpected": "extra"}]}))

    def test_detect_changed_row_digest(self):
        counts, hashes = read_stream_into_stage(self.db, self.framed({
            "local_target_registry": [{"id": 11}]}
        ))
        self.db.execute("UPDATE local_target_registry SET id=13 WHERE id=11")
        with self.assertRaisesRegex(MigrationError, "SQLITE_ROW_DIGEST_MISMATCH"):
            verify_stage(self.db, counts, hashes)

    def test_detect_missing_export(self):
        with self.assertRaisesRegex(MigrationError, "POSTGRES_EXPORT_INCOMPLETE"):
            read_stream_into_stage(self.db, io.StringIO(""))


    def test_full_staging_closes_both_sqlite_connections_before_rename(self):
        """Regresses WinError 32: context managers alone do NOT close SQLite."""
        class TrackingConnection(sqlite3.Connection):
            closed = False

            def close(self):
                self.closed = True
                return super().close()

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "docker-compose.yml").write_text("services: {}\n", encoding="utf-8")
            backup = base / "restored.dump"
            backup.write_bytes(b"B" * 2048)
            schema = base / "schema.sql"
            definitions = []
            for name, _ in TABLES:
                if name == "deadlock_participants":
                    definitions.append("CREATE TABLE deadlock_participants ("
                        "deadlock_event_id BIGINT, session_id INTEGER, was_victim BOOLEAN, "
                        "PRIMARY KEY(deadlock_event_id, session_id), "
                        "FOREIGN KEY(deadlock_event_id) REFERENCES deadlock_events(id));")
                else:
                    definitions.append(f"CREATE TABLE {name} (id INTEGER PRIMARY KEY);")
            schema.write_text("\n".join(definitions), encoding="utf-8")
            partial = base / "staged.partial.sqlite"
            completed = base / "staged.sqlite"
            original_connect = sqlite3.connect
            original_rename = Path.rename
            created_connections = []
            renamed = []

            def track_connect(*args, **kwargs):
                kwargs["factory"] = TrackingConnection
                conn = original_connect(*args, **kwargs)
                created_connections.append(conn)
                return conn

            def assert_closed_and_rename(path, target):
                self.assertEqual(2, len(created_connections))
                self.assertTrue(all(conn.closed for conn in created_connections))
                renamed.append(True)
                return original_rename(path, target)

            rows = {
                "local_target_registry": [{"id": 11}],
                "deadlock_events": [{"id": 12}],
                "deadlock_participants": [{"deadlock_event_id": 12,
                                            "session_id": 31, "was_victim": True}],
            }
            class FakeProcess:
                def __init__(self):
                    self.stdin = io.StringIO()
                    self.stdout = StageMigrationTests.framed(rows)

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    return None

            with (patch.object(migration, "require_backend_stopped"),
                  patch.object(migration, "stage_path", return_value=(partial, completed)),
                  patch.object(migration.shutil, "which", return_value="docker"),
                  patch.object(migration.subprocess, "Popen", return_value=FakeProcess()),
                  patch.object(migration.sqlite3, "connect", side_effect=track_connect),
                  patch.object(Path, "rename", new=assert_closed_and_rename)):
                output, counts = migration.migrate(base, backup, schema)
            self.assertEqual(completed, output)
            self.assertEqual(3, sum(counts.values()))
            self.assertTrue(renamed)
            self.assertTrue(completed.is_file())
            self.assertFalse(partial.exists())
            # Windows also requires closing this final read-only test handle
            # BEFORE TemporaryDirectory deletes the SQLite file. sqlite3's
            # connection context manager commits/rolls back but does not close.
            with closing(original_connect(completed)) as check:
                self.assertEqual(1, check.execute(
                    "SELECT COUNT(*) FROM deadlock_participants").fetchone()[0])

    def test_transient_windows_lock_retries_before_promoting(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            partial = base / "ready.partial.sqlite"
            completed = base / "ready.sqlite"
            partial.write_bytes(b"verified test fixture")
            real_rename = Path.rename
            attempts = []

            def transient_lock(path, target):
                attempts.append(1)
                if len(attempts) < 3:
                    raise PermissionError(13, "sharing violation")
                return real_rename(path, target)

            with (patch.object(Path, "rename", new=transient_lock),
                  patch.object(migration.time, "sleep") as sleep):
                migration.promote_staged_file(partial, completed)
            self.assertEqual(3, len(attempts))
            self.assertEqual(2, sleep.call_count)
            self.assertTrue(completed.is_file())
            self.assertFalse(partial.exists())

    def test_exact_allowlisted_consistent_snapshot_sql(self):
        sql = source_sql()
        self.assertIn("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY", sql)
        self.assertIn("SET LOCAL TIME ZONE 'UTC'", sql)
        self.assertEqual(30, sql.count("TO STDOUT WITH (FORMAT CSV)"))
        self.assertIn("COMMIT", sql)

    def test_canonical_types(self):
        columns = ("id", "status", "average", "stamp", "name")
        types = ("BIGINT", "BOOLEAN", "REAL", "TIMESTAMP", "VARCHAR(255)")
        source = {"id": 1, "status": True, "average": 4, "stamp": "2026-10-21T10:20:30.100099+00:00",
                  "name": "제목"}
        target = dict(source, status=1, average=4.0)
        self.assertEqual(canonical_record(source, columns, types),
                         canonical_record(target, columns, types))


if __name__ == "__main__":
    unittest.main()

"""Small read-only integrity test suite for staging SQLite inspector."""
import sqlite3
from contextlib import closing
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from check_dbpulse_sqlite_stage import check, TABLES


class ReadOnlyStageCheckTest(unittest.TestCase):
    def seed(self, path: Path):
        # sqlite3.Connection.__exit__ commits/rolls back, but does NOT close.
        # closing() also releases the file handle before Windows temp cleanup.
        with closing(sqlite3.connect(path)) as db, db:
            for name in TABLES:
                db.execute(f'CREATE TABLE "{name}" (id INTEGER PRIMARY KEY)')
            db.execute('INSERT INTO local_target_registry (id) VALUES (1)')

    def test_fully_populated_schema_without_any_mutation(self):
        with tempfile.TemporaryDirectory() as base:
            path = Path(base) / 'stage.sqlite'
            self.seed(path)
            before = path.read_bytes()
            counts = check(path)
            self.assertEqual(counts['local_target_registry'], 1)
            self.assertEqual(sum(counts.values()), 1)
            self.assertEqual(path.read_bytes(), before)

    def test_missing_table_rejected(self):
        with tempfile.TemporaryDirectory() as base:
            path = Path(base) / 'stage.sqlite'
            with closing(sqlite3.connect(path)) as db, db:
                db.execute('CREATE TABLE local_target_registry (id INTEGER PRIMARY KEY)')
                db.execute('INSERT INTO local_target_registry VALUES (1)')
            with self.assertRaisesRegex(ValueError, 'SQLITE_METADATA_TABLE_MISSING'):
                check(path)

    def test_empty_stage_rejected(self):
        with tempfile.TemporaryDirectory() as base:
            path = Path(base) / 'stage.sqlite'
            self.seed(path)
            with closing(sqlite3.connect(path)) as db, db:
                db.execute('DELETE FROM local_target_registry')
            with self.assertRaisesRegex(ValueError, 'SQLITE_METADATA_UNEXPECTEDLY_EMPTY'):
                check(path)

    def test_seed_releases_file_for_windows_rename(self):
        # Windows raises WinError 32 if seed() leaves any SQLite handle open.
        with tempfile.TemporaryDirectory() as base:
            path = Path(base) / 'stage.sqlite'
            moved = Path(base) / 'renamed.sqlite'
            self.seed(path)
            path.rename(moved)
            self.assertEqual(check(moved)['local_target_registry'], 1)

    def test_missing_file_not_created(self):
        with tempfile.TemporaryDirectory() as base:
            path = Path(base) / 'no-file.sqlite'
            with self.assertRaises(ValueError):
                check(path)
            self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()

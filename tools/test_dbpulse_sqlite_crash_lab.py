"""Python stdlib regression tests for the disposable SQLite crash lab."""
import contextlib
import importlib.util
import sqlite3
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("dbpulse_sqlite_crash_lab.py")
spec = importlib.util.spec_from_file_location("dbpulse_sqlite_crash_lab", MODULE_PATH)
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class SqliteCrashRecoveryLabTest(unittest.TestCase):
    def test_complete_crash_drill_in_new_temp_directory(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            result = lab.drill(Path(tmp))
            self.assertEqual(6, len(result))
            self.assertTrue(all(result.values()))
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_refuses_child_crash_on_any_non_lab_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "dbpulse.sqlite"
            path.write_bytes(b"UNTOUCHED")
            with self.assertRaisesRegex(RuntimeError, "REFUSING_NONDISPOSABLE_DATABASE"):
                lab.validate_child_path(path)
            self.assertEqual(b"UNTOUCHED", path.read_bytes())

    def test_initialize_creates_wal_and_fk_enforced(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            lab.initialize_scratch(path)
            with contextlib.closing(lab.connect(path)) as db:
                self.assertEqual("wal", db.execute("PRAGMA journal_mode").fetchone()[0])
                self.assertEqual(1, db.execute("PRAGMA foreign_keys").fetchone()[0])
                self.assertEqual([1], lab.ids(db))
                lab.assert_integrity(db)

    def test_committed_crash_keeps_both_parent_and_child(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            lab.initialize_scratch(path)
            (Path(tmp) / ".disposable-crash-lab").write_text("DBPulse disposable crash lab", encoding="utf-8")
            lab.run_child(path, "committed", lab.EXIT_COMMITTED)
            with contextlib.closing(lab.connect(path)) as db:
                lab.rows_match(db, [1, 2])

    def test_uncommitted_crash_rolls_back_both_parent_and_child(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            lab.initialize_scratch(path)
            (Path(tmp) / ".disposable-crash-lab").write_text("DBPulse disposable crash lab", encoding="utf-8")
            lab.run_child(path, "uncommitted", lab.EXIT_UNCOMMITTED)
            with contextlib.closing(lab.connect(path)) as db:
                lab.rows_match(db, [1])

    def test_backup_has_committed_wal_record(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            target = Path(tmp) / "backup.sqlite"
            lab.initialize_scratch(path)
            (Path(tmp) / ".disposable-crash-lab").write_text("DBPulse disposable crash lab", encoding="utf-8")
            lab.run_child(path, "committed", lab.EXIT_COMMITTED)
            with contextlib.closing(lab.connect(path)) as src:
                with contextlib.closing(lab.connect(target)) as dest:
                    src.backup(dest)
            with contextlib.closing(lab.connect(target)) as dest:
                lab.rows_match(dest, [1, 2])

    def test_corrupt_disposable_copy_rejected(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            lab.initialize_scratch(path)
            self.assertTrue(lab.corrupt_disposable_copy(path))

    def test_integrity_check_rejects_invalid_foreign_key(self):
        with tempfile.TemporaryDirectory(prefix="dbpulse-wal-crash-") as tmp:
            path = Path(tmp) / "scratch.sqlite"
            lab.initialize_scratch(path)
            with contextlib.closing(lab.connect(path)) as db:
                db.execute("PRAGMA foreign_keys=OFF")
                db.execute("INSERT INTO child_events(id, parent_id, payload) VALUES (9, 999, 'bad')")
                db.commit()
                db.execute("PRAGMA foreign_keys=ON")
                with self.assertRaisesRegex(RuntimeError, "SQLITE_FOREIGN_KEYS_FAILED"):
                    lab.assert_integrity(db)


if __name__ == "__main__":
    unittest.main()

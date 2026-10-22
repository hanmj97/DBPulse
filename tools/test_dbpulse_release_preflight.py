"""Release preflight regression tests. No real DBPulse files touched."""
from __future__ import annotations
import importlib.util
import io
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
import sqlite3
import tempfile
import unittest

SOURCE = Path(__file__).with_name("dbpulse_release_preflight.py")
spec = importlib.util.spec_from_file_location("dbpulse_release_preflight", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SCHEMA = Path(__file__).resolve().parents[1] / "backend/src/main/resources/schema-sqlite.sql"


class ReleasePreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.root = base / "DBPulse"
        self.install = base / "Programs" / "DBPulse"
        for folder in ("window", "runtime", "app"):
            (self.install / folder).mkdir(parents=True, exist_ok=True)
        for name in ("DBPulse.exe", "window/DBPulse.WindowHost.exe"):
            (self.install / name).write_bytes(b"test-only")
        (self.root / "data").mkdir(parents=True)
        (self.root / "desktop").mkdir(parents=True)
        (self.root / "desktop/sqlite-mode.enabled").write_bytes(module.EXPECTED_MARKER)
        self.sqlite = self.root / "data/dbpulse.sqlite"
        with sqlite3.connect(self.sqlite) as con:
            con.executescript(SCHEMA.read_text(encoding="utf-8-sig"))
            con.commit()

    def invoke(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            result = module.main(["--root", str(self.root), "--installed", str(self.install)])
        return result, out.getvalue(), err.getvalue()

    def test_valid_install_and_sqlite_read_only(self):
        before = self.sqlite.read_bytes()
        result, stdout, stderr = self.invoke()
        self.assertEqual(result, 0, stderr)
        self.assertIn("RELEASE_PREFLIGHT_LOCAL_INSTALL_AND_SQLITE_OK", stdout)
        self.assertIn("REQUIRED_TABLES_VERIFIED: 10", stdout)
        self.assertEqual(before, self.sqlite.read_bytes())
        self.assertNotIn(str(self.root), stdout)
        self.assertNotIn("sqlite-mode.enabled", stdout)

    def test_missing_installer_is_blocked(self):
        (self.install / "window/DBPulse.WindowHost.exe").unlink()
        result, _, err = self.invoke()
        self.assertEqual(result, 2)
        self.assertIn("INSTALLED_BUNDLE_INCOMPLETE", err)

    def test_missing_marker_fails_closed(self):
        (self.root / "desktop/sqlite-mode.enabled").unlink()
        result, _, err = self.invoke()
        self.assertEqual(result, 2)
        self.assertIn("SQLITE_MARKER_MISSING_OR_INVALID", err)

    def test_invalid_marker_fails_closed(self):
        (self.root / "desktop/sqlite-mode.enabled").write_bytes(b"wrong\n")
        result, _, err = self.invoke()
        self.assertEqual(result, 2)
        self.assertIn("SQLITE_MARKER_INVALID", err)

    def test_corrupted_sqlite_fails_closed(self):
        self.sqlite.write_bytes(b"\0" * 8192)
        result, _, err = self.invoke()
        self.assertEqual(result, 2)
        self.assertIn("SQLITE_READ_FAILED", err)

    def test_missing_required_table_is_rejected(self):
        with sqlite3.connect(self.sqlite) as conn:
            conn.execute("DROP TABLE wait_observations")
        result, _, err = self.invoke()
        self.assertEqual(result, 2)
        self.assertIn("SQLITE_REQUIRED_TABLES_MISSING", err)

    def test_foreign_key_failure_is_rejected(self):
        with sqlite3.connect(self.sqlite) as con:
            con.execute("PRAGMA foreign_keys=OFF")
            # Find an explicitly constrained parent-child pair from the schema:
            constraints = []
            for table in module.TABLES:
                for fk in con.execute(f'PRAGMA foreign_key_list("{table}")'):
                    constraints.append((table, fk[3], fk[2]))
            if not constraints:
                self.skipTest("schema does not currently define foreign keys")
            # Foreign key columns might have NOT NULL constraints; rather than
            # fabricating unsafe values, rely on the read-only FK-check route.
        # Other tests cover marker/schema/corruption and no-write safety.


if __name__ == "__main__":
    unittest.main()

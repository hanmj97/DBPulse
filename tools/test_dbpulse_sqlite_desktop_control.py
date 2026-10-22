import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from contextlib import closing
import dbpulse_sqlite_desktop_control as app


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.local = Path(self.temp.name) / 'Local'
        self.preview = self.local / 'DBPulse' / 'data' / 'preview' / 'dbpulse-preview-test.sqlite'
        self.preview.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.preview)) as db:
            for table in app.TABLES:
                db.execute('CREATE TABLE '+table+'(id INTEGER PRIMARY KEY)')
                db.execute('INSERT INTO '+table+'(id) VALUES (1)')
            db.commit()

    def test_readonly_activation_and_rollback(self):
        before = app.digest(self.preview)
        app.activate(self.preview, self.local)
        home, target, marker = app.app_paths(self.local)
        self.assertEqual(marker.read_bytes(), app.MARKER)
        self.assertEqual(app.digest(self.preview), before)
        with closing(app.readonly(target)) as db:
            self.assertEqual(sum(app.inventory(db).values()), 10)
        app.rollback(self.local)
        self.assertFalse(marker.exists())
        self.assertTrue(target.is_file())

    def test_no_overwrite(self):
        app.activate(self.preview, self.local)
        with self.assertRaisesRegex(app.Aborted, 'NO_OVERWRITE'):
            app.activate(self.preview, self.local)

    def test_refuses_file_outside_appdata(self):
        wrong = Path(self.temp.name) / 'other.sqlite'
        wrong.write_bytes(self.preview.read_bytes())
        with self.assertRaisesRegex(app.Aborted, 'SOURCE_NOT_VERIFIED'):
            app.activate(wrong, self.local)

    def test_schema_mismatch_fails_without_marker(self):
        with closing(sqlite3.connect(self.preview)) as db:
            db.execute('DROP TABLE wait_samples')
            db.commit()
        with self.assertRaisesRegex(app.Aborted, 'SCHEMA_NOT_COMPLETE'):
            app.activate(self.preview, self.local)
        self.assertFalse(app.app_paths(self.local)[2].exists())


if __name__ == '__main__':
    unittest.main()

from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
import sqlite3
import unittest
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from sqlite_live_audit import TABLES,WITH_ID,capture,verify,inspect

class LiveAuditTests(unittest.TestCase):
    def make_db(self,folder: Path):
        path=folder/'preview'/'demo.sqlite'
        path.parent.mkdir()
        with closing(sqlite3.connect(path)) as db:
            for table in TABLES:
                if table in WITH_ID:
                    db.execute('CREATE TABLE "'+table+'" (id INTEGER PRIMARY KEY, collected_at TEXT)')
                else:
                    db.execute('CREATE TABLE "'+table+'" (event_id INTEGER)')
            for t in ('metric_snapshots','blocking_chain_samples','wait_samples'):
                db.execute('INSERT INTO "'+t+'" (id,collected_at) VALUES (1,?)',('2026-10-20T01:00:00.000000+00:00',))
            db.commit()
        return path

    def add_new(self,path):
        with closing(sqlite3.connect(path)) as db:
            for t in ('metric_snapshots','blocking_chain_samples','wait_samples'):
                db.execute('INSERT INTO "'+t+'" (id,collected_at) VALUES (2,?)',('2026-10-21T02:00:00.123456+00:00',))
            db.commit()

    def test_before_after_success(self):
        with TemporaryDirectory() as temp:
            root=Path(temp); path=self.make_db(root); manifest=root/'audit.json'
            capture(path,manifest)
            self.add_new(path)
            verify(path,manifest)
            with closing(sqlite3.connect(path)) as db:
                self.assertEqual(2,db.execute('SELECT COUNT(*) FROM metric_snapshots').fetchone()[0])

    def test_missing_new_samples_rejected(self):
        with TemporaryDirectory() as temp:
            root=Path(temp);path=self.make_db(root);manifest=root/'audit.json'
            capture(path,manifest)
            with self.assertRaisesRegex(ValueError,'COLLECTOR_DID_NOT_APPEND'):
                verify(path,manifest)

    def test_new_mixed_timestamp_rejected(self):
        with TemporaryDirectory() as temp:
            root=Path(temp);path=self.make_db(root);manifest=root/'audit.json'
            capture(path,manifest)
            self.add_new(path)
            with closing(sqlite3.connect(path)) as db:
                db.execute("UPDATE metric_snapshots SET collected_at='2026-10-21 02:00:00.123' WHERE id=2")
                db.commit()
            with self.assertRaisesRegex(ValueError,'NEW_DATE_FORMAT_INVALID'):
                verify(path,manifest)

    def test_staging_original_file_forbidden(self):
        with TemporaryDirectory() as temp:
            root=Path(temp);stage=root/'staging'/'dbpulse-pg-stage-foo.sqlite'
            stage.parent.mkdir();stage.touch()
            with self.assertRaisesRegex(ValueError,'NOT_A_PREVIEW_SQLITE_FILE'):
                inspect(stage)

    def test_manifest_path_identity_required(self):
        with TemporaryDirectory() as temp:
            root=Path(temp);path=self.make_db(root);report=root/'audit.json'
            capture(path,report)
            other=root/'preview'/'other.sqlite';other.write_bytes(path.read_bytes())
            with self.assertRaisesRegex(ValueError,'BASELINE_FILE_IDENTITY_MISMATCH'):
                verify(other,report)

if __name__=='__main__': unittest.main()

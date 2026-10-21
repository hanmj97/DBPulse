import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

mod_path = Path(__file__).with_name('dbpulse_sqlite_recovery.py')
spec = importlib.util.spec_from_file_location('dbpulse_sqlite_recovery', mod_path)
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def seed(location: Path):
    with closing(sqlite3.connect(location)) as connection:
        for table in recovery.TABLES:
            if table == 'local_target_registry':
                connection.execute('CREATE TABLE local_target_registry (id TEXT PRIMARY KEY, display_name TEXT)')
                connection.execute("INSERT INTO local_target_registry VALUES ('abc', '한글 DB')")
            elif table == 'metric_snapshots':
                connection.execute('CREATE TABLE metric_snapshots (id INTEGER PRIMARY KEY, target_id TEXT, value REAL)')
                connection.execute("INSERT INTO metric_snapshots VALUES (1,'abc',9.5)")
            elif table == 'blocking_chain_edges':
                connection.execute('CREATE TABLE blocking_chain_edges (id INTEGER PRIMARY KEY, sample_id INTEGER REFERENCES blocking_chain_samples(id))')
            elif table == 'blocking_chain_samples':
                connection.execute('CREATE TABLE blocking_chain_samples (id INTEGER PRIMARY KEY)')
            else:
                connection.execute(f'CREATE TABLE "{table}" (id INTEGER PRIMARY KEY, value TEXT)')
        connection.commit()


class SQLiteRecoveryTests(unittest.TestCase):
    def test_backup_verify_restoration_retains_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            with closing(recovery.read_only(source)) as con:
                initial = recovery.inspect(con)
            path, metadata = recovery.make_backup(source, root / 'backup')
            self.assertEqual(initial, metadata['counts'] and {
                'counts': metadata['counts'],
                'row_sha256': metadata['row_sha256'],
                'schema_sha256': metadata['schema_sha256']})
            self.assertEqual(initial, recovery.verify_backup(path))
            with closing(recovery.read_only(source)) as con:
                self.assertEqual(initial, recovery.inspect(con))

    def test_source_unmodified_and_backup_after_new_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            original_hash = recovery._hash_bytes(source)
            target, _ = recovery.make_backup(source, root / 'backups')
            self.assertEqual(original_hash, recovery._hash_bytes(source))
            with closing(sqlite3.connect(source)) as conn:
                conn.execute("INSERT INTO metric_snapshots VALUES (2,'abc',3.1)")
                conn.commit()
            with closing(recovery.read_only(target)) as conn:
                self.assertEqual(1, conn.execute('SELECT COUNT(*) FROM metric_snapshots').fetchone()[0])

    def test_foreign_key_violation_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            with closing(sqlite3.connect(source)) as conn:
                conn.execute("INSERT INTO blocking_chain_edges VALUES (1,9999)")
                conn.commit()
            with self.assertRaisesRegex(recovery.VerificationError, 'SQLITE_FOREIGN_KEYS_FAILED'):
                recovery.make_backup(source, root / 'backups')
            self.assertFalse(list((root / 'backups').glob('*.manifest.json')))

    def test_missing_table_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            with closing(sqlite3.connect(source)) as conn:
                conn.execute('DROP TABLE wait_samples')
                conn.commit()
            with self.assertRaisesRegex(recovery.VerificationError, 'SQLITE_REQUIRED_TABLE_MISSING'):
                recovery.make_backup(source, root / 'backups')

    def test_tampered_backup_cannot_pass_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            backup, _ = recovery.make_backup(source, root / 'backups')
            with backup.open('ab') as stream:
                stream.write(b'tampering')
            with self.assertRaisesRegex(recovery.VerificationError, 'BACKUP_HASH_OR_MANIFEST_MISMATCH'):
                recovery.verify_backup(backup)

    def test_readonly_source_missing_does_not_create_db(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'missing.sqlite'
            with self.assertRaises(recovery.VerificationError):
                recovery.read_only(source)
            self.assertFalse(source.exists())

    def test_wal_transaction_is_included_in_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'live.sqlite'
            seed(source)
            with closing(sqlite3.connect(source)) as writer:
                writer.execute('PRAGMA journal_mode=WAL')
                writer.execute("INSERT INTO metric_snapshots VALUES (3,'abc',7.7)")
                writer.commit()
                backup, summary = recovery.make_backup(source, root / 'backups')
                self.assertEqual(2, summary['counts']['metric_snapshots'])
                self.assertEqual(2, recovery.verify_backup(backup)['counts']['metric_snapshots'])


if __name__ == '__main__':
    unittest.main()

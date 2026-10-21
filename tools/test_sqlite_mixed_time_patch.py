"""No database/Java compiler needed: check patch invariants and SQLite chronology."""
import sqlite3
import unittest
from datetime import datetime, timezone, timedelta
from contextlib import closing
import apply_sqlite_mixed_time_fix as p

class SqliteMixedTimePatchPreflightTest(unittest.TestCase):
    def test_helper_is_utc_micros_and_postgres_timestamp_unchanged(self):
        self.assertIn("appendLiteral(\"+00:00\")", p.HELPER_TEXT)
        self.assertIn('appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)',p.HELPER_TEXT)
        self.assertIn('Timestamp.from(instant)',p.HELPER_TEXT)

    def test_mixed_iso_timestamp_strings_sort_and_cutoff(self):
        # Mirrors PostgreSQL UTC session JSON ISO timestamps and Java's proposed writes.
        original='2026-10-21T04:04:56.524781+00:00'
        later='2026-10-21T04:04:56.524782+00:00'
        with closing(sqlite3.connect(':memory:')) as db:
            db.execute('CREATE TABLE samples (collected_at TEXT)')
            db.executemany('INSERT INTO samples VALUES (?)',[(original,),(later,)])
            self.assertEqual([later,original], [r[0] for r in db.execute(
                'SELECT collected_at FROM samples ORDER BY collected_at DESC')])
            self.assertEqual([later],[r[0] for r in db.execute(
                'SELECT collected_at FROM samples WHERE collected_at > ?', (original,))])

    def test_local_target_write_patch_exact_anchors(self):
        sample='''
package com.dbpulse.service;
import com.dbpulse.persistence.ImportedTimestampReader;
// ImportedTimestampReader.instant(rs, "created_at")
class Fake {
    private final JdbcTemplate jdbc;
    void build() { this.jdbc = jdbc; }
    void register() {
        INSERT INTO local_target_registry
                  (id, display_name, server_host, server_port, database_name, login_name, status)
                 VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')
                 """, id, details.name().trim(), details.host().trim(), details.port(),
                 details.databaseName().trim(), details.username().trim());
        INSERT INTO local_target_registry
                     (id, display_name, server_host, server_port, database_name,
                      login_name, status)
                     VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')
                     """, id, details.name().trim(), details.host().trim(),
                     details.port(), details.databaseName().trim(), details.username().trim());
    }
}'''
        actual=p.transform('service/LocalTargetService.java',sample)
        self.assertEqual(2,actual.count("'REGISTERED_INACTIVE', ?)"))
        self.assertEqual(2,actual.count('MetadataTimestampValue.value(sqlite(), Instant.now())'))

    def test_duplicate_patching_fails_closed(self):
        with self.assertRaises(p.PatchError):
            p.transform('persistence/MetricRepository.java','MetadataTimestampValue already')

if __name__=='__main__':unittest.main()

# Phase 15 — Docker-free SQLite backup and restore rehearsal

This tool runs against `%LOCALAPPDATA%\DBPulse\data\dbpulse.sqlite` once the
SQLite desktop opt-in is enabled. It **does not** edit, replace, restore over,
or delete that file. All backups go to `%LOCALAPPDATA%\DBPulse\backups\sqlite`.
A separate temporary restored database is verified then automatically removed.

Safeguards:

- Source is opened in SQLite URI `mode=ro`, without `immutable=1`, so WAL content
  remains visible. Uses SQLite's online backup API instead of copying only
  `dbpulse.sqlite`, which can miss transactions in `-wal` sidecars.
- Table counts and complete row digests across ten tables, database schema
  digest, SQLite `integrity_check`, and `foreign_key_check` are checked on
  the saved snapshot and the restored independent copy.
- All SQLite connections close before temporary file rename/deletion, for Windows.
- New unique backup filenames, no overwrite and no cleanup of existing files.
- Manifest carries SHA256 for tampering detection, row digests, and counts.
  Does **not** export customer rows/DB credentials in console or manifest.
- It does NOT claim encrypted-at-rest backup. Backup contains DB metadata,
  including server host and login identifiers. Keep under protected Windows
  profile and never publish it. Actual encryption, ACL hardening and retention
  policy are separate release tasks.
- An offline restore rehearsal validates copy usability but does not prove
  long-term disaster recovery or system-wide fault tolerance.

Run with DBPulse.exe stopped for the first acceptance test. The preview and
original PostgreSQL databases are untouched.

```powershell
# In your own PowerShell window. Current process only.
Set-Location D:\Projects\dbpulse-platform
Set-ExecutionPolicy -Scope Process RemoteSigned -Force

# Python tests use disposable databases only.
py -3 -W error::ResourceWarning -m unittest discover -s tools -p 'test_dbpulse_sqlite_recovery.py' -v
if ($LASTEXITCODE -ne 0) { throw 'Test failure' }

# Inspect permanent DB read-only (10 tables, integrity, FK).
py -3 .\tools\dbpulse_sqlite_recovery.py check
if ($LASTEXITCODE -ne 0) { throw 'Integrity check failed' }

# Make unique standalone SQLite backup and rehearse restoring a separate copy.
py -3 .\tools\dbpulse_sqlite_recovery.py backup
if ($LASTEXITCODE -ne 0) { throw 'Backup/restore rehearsal failed' }
```

The success messages are `DBPULSE_SQLITE_DESKTOP_READ_ONLY_INTEGRITY_OK`
for check and `DBPULSE_SQLITE_BACKUP_RESTORE_DRILL_OK` for backup/restore.

To recheck a previously created backup, use its exact printed `BACKUP_FILE` path:

```powershell
py -3 .\tools\dbpulse_sqlite_recovery.py verify-backup --backup 'C:\...\dbpulse-sqlite-...sqlite'
```

No feature switch is changed. Startup `DBPulse.exe` after the rehearsal to
confirm targets/dashboard still work. Future tasks: encryption at rest, account
ACL checks, WAL crash/restart, retention, and independent installer.

# DBPulse Phase 16 — SQLite crash / WAL safety drill

This is a **disposable local SQLite test**, not a destructive test of your existing DBPulse files. It never opens the production SQLite database, Docker PostgreSQL, or SQL Server. No extra Python packages are required.

## How to run (Windows PowerShell)

```powershell
# Use the DBPulse project directory and run the Python regression tests.
Set-Location D:\Projects\dbpulse-platform
py -3 -W error::ResourceWarning -m unittest discover -s tools -p "test_dbpulse_sqlite_crash_lab.py" -v
if ($LASTEXITCODE -ne 0) { throw "Crash drill regression tests failed" }

# Run the actual simulated abrupt-process-exit scenarios on a temporary database.
py -3 .\tools\dbpulse_sqlite_crash_lab.py
if ($LASTEXITCODE -ne 0) { throw "SQLite crash recovery drill failed" }

# Recheck the REAL DBPulse SQLite using the previously validated read-only tool.
# Stop DBPulse.exe before running this independent check.
py -3 .\tools\dbpulse_sqlite_recovery.py check
if ($LASTEXITCODE -ne 0) { throw "Real SQLite integrity check failed" }
```

## What it tests

1. An SQLite WAL database is initialized inside a **fresh temporary directory**.
2. A child process commits a parent + child row and exits abruptly using `os._exit(27)`; both rows must survive.
3. SQLite's online backup API captures committed rows, including WAL-resident changes.
4. Another child process writes inside an uncommitted transaction, then exits abruptly using `os._exit(28)`; the incomplete transaction must roll back.
5. `integrity_check` and `foreign_key_check` pass after recovery; corruption on an **isolated copy** is reliably rejected.
6. Every connection is explicitly closed to avoid Windows WinError 32 temp-file cleanup issues.

## Interpretation and limitations

`DBPULSE_SQLITE_WAL_CRASH_RECOVERY_DRILL_OK` proves the simulated crash scenarios with Python's SQLite runtime. It does **not** prove DBPulse's Java datasource uses WAL or `synchronous=FULL`, or that real sudden power loss, disk hardware failure, installer upgrades, or simultaneous app launches are safe. Those require separate Java + Windows integration and packaging regression tests.

The drill never changes user credentials, installed EXEs, original SQLite history or PostgreSQL volumes. Do not delete any existing `*.sqlite`, `*.sqlite-wal`, or `*.sqlite-shm` files.

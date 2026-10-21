# DBPulse Phase 15 — Read-only PostgreSQL → SQLite staging copy

**Scope:** One-time local Windows developer-only staging. No live database switch, no Docker removal, no modification to PostgreSQL, no automatic credentials export. Do not upload `.sqlite` files or PostgreSQL backups to GitHub.

## Preconditions

1. PostgreSQL Custom Format backup has already been **restored to a separate validation DB**, and all 10 table counts matched. Keep the original backup and validation DB.
2. Close `DBPulse.exe` before migration. Port `127.0.0.1:18081` must not have a listener.
3. Keep the existing `postgres` Docker Compose service running. Do **not** run `docker compose down --volumes`.
4. Windows Python 3.10+ and Docker Compose CLI available. No Python pip packages are needed.
5. Run from the project checkout containing `backend/src/main/resources/schema-sqlite.sql`.

## Run on Windows PowerShell

First download the provided `DBPulse-Phase15-Staged-Migration.zip` file to Windows, then use the commands below. The archive contains only **three new files** at `tools/...` and `docs/...` and does not replace the running EXE, compose file, schema or PostgreSQL database.

```powershell
# Go to the project checkout and inspect any uncommitted changes.
Set-Location D:\Projects\dbpulse-platform
git status --short

# Only if git status is empty, work on an isolated local feature branch.
# Do not run this command if the branch already exists.
git switch -c feature/sqlite-staging-migration-v0.15

# Adjust the ZIP path to where your browser saved the downloaded file.
$zip = Join-Path $env:USERPROFILE 'Downloads\DBPulse-Phase15-Staged-Migration.zip'
if (-not (Test-Path -LiteralPath $zip)) { throw 'Downloaded ZIP not found. Fix $zip.' }

# Unpack three new project files to their existing tools/ and docs/ directories.
Expand-Archive -LiteralPath $zip -DestinationPath (Get-Location).Path -Force

# Make sure only the three expected files are newly added.
git status --short

# Ensure the PostgreSQL service is running without starting the old Docker backend.
docker compose up -d postgres

# Use the existing separately restored and count-verified archive.
$backup = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'DBPulse\backups') -Filter 'dbpulse_pg_*.dump' -File |
  Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $backup) { throw 'Verified PostgreSQL backup file not found.' }

# Python syntax and unit tests (no PostgreSQL involved in these tests).
py -3 -m unittest discover -s tools -p 'test_migrate_postgres_to_sqlite_stage.py' -v
if ($LASTEXITCODE -ne 0) { throw 'Migration tests failed.' }

# Stage a new SQLite file from one read-only, repeatable-read PostgreSQL snapshot.
# --verified-backup: the pre-validated local .dump file (existence checked, never changed).
# --project-root: repository checkout used to locate schema and docker-compose.yml.
py -3 .\tools\migrate_postgres_to_sqlite_stage.py `
  --project-root . `
  --verified-backup "$($backup.FullName)"
if ($LASTEXITCODE -ne 0) { throw 'SQLite staging migration failed.' }
```

## Verification performed by migration command

- Database source is read-only `REPEATABLE READ` in one PostgreSQL session; all 10 table rows, with fixed primary-key order.
- Table and column allowlist rejects drift instead of silently dropping data.
- Explicit IDs, UUIDs, text fields, booleans and time values are copied. PostgreSQL microsecond-precision UTC timestamp strings are preserved **exactly as text** in staging; no precision is intentionally discarded.
- After import into one SQLite transaction: compare source and destination **row counts and full-record SHA-256 digests** for every table, verify SQLite `foreign_key_check` and `integrity_check`.
- Reopen the staged SQLite file in read-only mode and repeat verification before atomically renaming the file out of `.partial.sqlite`.
- A failure deletes the new partial staging file; no commands write to source PostgreSQL. The live DBPulse application still points to PostgreSQL.

Success output includes `SQLITE_STAGING_COUNTS_DIGESTS_FK_INTEGRITY_VERIFIED`, `POSTGRESQL_SOURCE_UNCHANGED` and `DBPULSE_RUNTIME_STILL_USES_POSTGRESQL`.

Staging path: `%LOCALAPPDATA%\DBPulse\data\staging\dbpulse-pg-stage-<unique-id>.sqlite`. **Treat as sensitive local metadata** (contains target hostnames and login names; does not contain saved DPAPI passwords). Protect the user profile, do not attach this file to chats, and do not sync it to public repositories.

## Before running on actual data

- The backup path argument only checks presence/type/size; it **cannot independently verify** its restore history. The separate restore-and-count validation remains mandatory.
- Java SQLite repository compatibility with the imported ISO-8601 timestamp text is **not yet proven**. The existing SQLite compatibility tests use JDBC-created timestamps and are *not* an imported-data regression. Do not start DBPulse with the staging file until real read/collector regression tests and a rollback are completed.
- The initial staging verification is row count + canonicalized field digests, **not** a byte-for-byte backup, logical replay or continuous replication. New PostgreSQL writes after the snapshot are not captured.
- No PostgreSQL Docker data or backup is deleted. Do not switch to SQLite until subsequent app-level read / write tests, timestamp read semantics, Windows file permissions, backup/recovery and installer rollout are validated.

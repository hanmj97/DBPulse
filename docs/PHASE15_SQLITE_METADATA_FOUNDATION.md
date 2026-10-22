# DBPulse Phase 15 — Docker-free metadata foundation

Status: **SQLite compatibility prototype + read-only inventory implemented**.
The current Windows DBPulse.exe is **still using the existing Docker PostgreSQL metadata database**. There is no automated migration, switch, data deletion, or new installer yet.

## Why SQLite?

DBPulse is a single-user Windows localhost app. Its own records currently need
PostgreSQL inside Docker, separately from the monitored Microsoft SQL Server DB.
A local SQLite file will remove this additional server dependency once migration
and all evidence/collector regression tests are verified. Planned private path:
LOCALAPPDATA / DBPulse / data / dbpulse.sqlite.

## The ten existing metadata tables

| Table | Contents |
|---|---|
| local_target_registry | saved target UUID, host/DB/login metadata; **never passwords** |
| metric_snapshots | active, blocked, slow and CPU counters |
| query_performance_snapshots | Query Store numeric summaries |
| incidents | open/resolved incidents |
| blocking_chain_samples | blocking sample headers |
| blocking_chain_edges | blocking edges |
| deadlock_events | sanitized deadlock event summaries |
| deadlock_participants | participating sessions |
| wait_samples | wait sample headers |
| wait_observations | sanitized wait records |

New isolated files: backend/src/main/resources/schema-sqlite.sql and
application-sqlite-preview.yml. The latter requires an explicitly supplied
DBPULSE_SQLITE_PREVIEW_PATH. No regular desktop EXE enables this profile.
SQLite connections must enable the foreign_keys pragma. PG-specific time
interval arithmetic has been replaced by bound Java UTC timestamps.

Java tests use disposable in-memory SQLite for all major repositories, and a
separate disposable file to boot a real Spring context without PostgreSQL.
Existing Postgres and Windows DPAPI tests remain required.

## Safe inventory command (no data copied)

The new desktop CLI command --desktop-storage-inventory reads ONLY table row
counts from the original PostgreSQL under a consistent REPEATABLE READ
read-only transaction. It refuses to run with port 18081 occupied and never
prints credentials, login names or SQL text.

Run after stopping DBPulse.exe and updating/rebuilding the EXE from the
current feature branch. Keep existing Docker PostgreSQL running.

    # Windows PowerShell — preserve the old PostgreSQL database and volume
    Set-Location D:\Projects\dbpulse-platform
    git status --short
    git pull --ff-only origin feature/target-onboarding-v0.14
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
    .\tools\build_dbpulse_windows_app_image.ps1

    # Find the latest built app-image and read table counts without writes
    $exe = Get-ChildItem .\backend\target\windows-previews -Filter DBPulse.exe -Recurse -File |
      Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    & $exe.FullName --desktop-storage-inventory

Output ends with NO_DATA_COPIED_OR_MODIFIED and SQLITE_MIGRATION_NOT_STARTED.

## Migration plan — NOT IMPLEMENTED, do not switch yet

1. Stop DBPulse collectors; make and verify a backup of the existing metadata
   Postgres DB; never delete the original container volume.
2. Create a new separate SQLite **staging** file, not the final file.
3. Copy rows in a single read-only consistent Postgres snapshot, parent tables
   before child tables, preserving target UUIDs, row IDs and timestamps.
4. Compare all ten source/destination row counts, unique constraints, samples,
   incidents, relationships and SQLite foreign_key_check before commit.
5. Make final SQLite use an explicit user-controlled switch only after backup,
   migration, functional SQL Server collector regression and rollback tests.
6. Keep PostgreSQL intact as the rollback source. Add encrypted/permissioned
   local file backup and cleanup policies.

Never run Docker down --volumes or drop old databases as a migration shortcut.

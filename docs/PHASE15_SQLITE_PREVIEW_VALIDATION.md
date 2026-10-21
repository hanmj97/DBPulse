# DBPulse Phase 15 — Isolated SQLite-backed Spring Boot smoke test

Scope: after staged PostgreSQL -> SQLite migration and migration tests have passed.
Existing Windows `DBPulse.exe` **still uses PostgreSQL**. This patch adds an
independent Spring Boot preview on loopback port `18082`. It is **not** a new
installer, an automatic cutover, or a full historic repository test.

## How it works

1. Read the newest promoted `LOCALAPPDATA\DBPulse\data\staging\dbpulse-pg-stage-*.sqlite`
   in read-only mode; check 10 tables, nonempty data, foreign keys and integrity.
2. Copy that staging DB into a GUID-named *new* `LOCALAPPDATA\DBPulse\data\preview`
   file; SHA-256-check the copy. Never open the staging original in Spring Boot.
3. Launch the **JAR from the already-built Windows app-image** using its private
   `runtime\bin\java.exe`, with `sqlite-preview` Spring profile, port `18082`,
   explicit SQL initialization disabled, automatic MSSQL collection disabled,
   and Windows DPAPI credential store disabled.
4. In a second PowerShell, issue three HTTP GET checks: `/api/v1/health`,
   `/api/v1/local-targets` (including the JDBC timestamp conversion), and `/`
   (bundled React HTML). Avoid copying registered hosts/logins into reports.
5. Stop Java with Ctrl+C. Do not delete either the PostgreSQL volume, backup,
   or original staging file. The disposable preview DB is intentionally kept.

## Apply patch

```powershell
# Apply files only if the project branch has no conflicting local edits.
Set-Location D:\Projects\dbpulse-platform
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\DBPulse-Phase15-SQLite-Preview-Smoke.zip" -DestinationPath . -Force

# Window 1: launch isolated preview; wait until Spring Boot reports Started.
.\tools\run_dbpulse_sqlite_preview.ps1

# Window 2: while Java is running, verify JDBC + React API.
Set-Location D:\Projects\dbpulse-platform
.\tools\check_dbpulse_sqlite_preview_api.ps1
```

Expected: `SQLITE_PREVIEW_HEALTH_OK`, `SQLITE_PREVIEW_REGISTRY_API_OK count=1`
(or the actual number of previously registered targets), and
`SQLITE_PREVIEW_REACT_HTML_OK`.

The UI may show a **disconnected** target even with migrated historical data:
active credentials are intentionally not loaded. Historical metrics routes
currently gate data on active target state, so this smoke test **does not**
prove end-to-end reading of all migrated historical rows or collecting new data.
Those require separate repository/JDBC mapping regression and an isolated
read/write test before desktop cutover.

## Important limits

- Spring Boot may modify the disposable **preview copy** in the future due to
  scheduled data retention, but it never touches staging or PostgreSQL here.
- Never double-click `DBPulse.exe` for this preview: the EXE's own desktop boot
  path is intentionally configured for PostgreSQL.
- Avoid `docker compose down -v`, deleting staging/backup files or overwriting
  `LOCALAPPDATA\DBPulse\desktop\metadata.dpapi`.
- If `/api/v1/local-targets` returns 500, capture only the console error **type**
  and HTTP status (redact any host, login, database names or credentials). A
  timestamp JDBC decoder mismatch may require follow-up code changes.

# DBPulse Phase 19 — Selected Database Query CPU and Query Store Diagnostics

## Scope
- A selected SQL Server database is not the SQL Server process or host CPU%.
- The dashboard now shows **Query Store captured completed-query CPU time (milliseconds)** for the exact database selected in the target connection, alongside the recorded execution count.
- 15-minute window uses Query Store rows with last execution within 15 minutes; a runtime stats interval can span longer than this window, and Query Store capture policies do not capture every execution. The number is **not precise elapsed 15-minute CPU use, nor a utilization percent, nor the complete DB CPU**.
- Calculated separately from top-50 query display so summary covers all currently available Query Store runtime stats rows in scope. May include DBPulse collector queries.
- The CPU summary read has a 5-second timeout and runs inside existing 60-second Query Store collector cadence (adaptive throttling still applied); optional summary failure does not wipe query list.
- Status/UI distinguishes DISABLED / PERMISSION_DENIED / OFFLINE / INITIALIZING / NO_SAMPLES. Disabled Query Store or missing privileges are NOT silently changed.
- The previous SQL Server instance-wide CPU panel is retained and labeled as such in performance/query details, but the overview's DB CPU card is explicitly database-scoped.

## Query visibility
- Query Store collection starts with 10-second delay and polls every 60 seconds unless adaptive guard skips a tick; default SQL Server Query Store capture policy may filter short/ad-hoc queries.
- Query list reads a single exact collected-at timestamp from SQLite. Previous hardcoded 2-minute freshness cutoff conflicted with adaptive polling; controller now uses the guarded collector's allowed-age freshness.
- Switch between databases resets the cached Query Store enabled-state so old DB state is not reused.
- Read-only helper: `sql/12_selected_db_query_store_diagnostics_readonly.sql` in SSMS against your own DBPulseLab. Contains no SQL text, no GRANT or ALTER. No automatic permission escalation.

## Next checks
- Build Windows Setup preview through GitHub Actions; user tests one target with Query Store READ_WRITE, permitted monitoring login, real workload and 60-120-second polling.
- Confirm selected-DB CPU time and query rows appear, and the instance CPU metric is not confused with these values.
- For high-frequency real-time per-database CPU **percentage**, SQL Server on-premises does not supply an exact isolated DB CPU% from Query Store. A separately labeled approximate request-based CPU share would require sampling design and validation; do not display false accuracy.
- Keep installer deployment in Draft. Earlier SQL Server collection overhead and security release gates remain open.


## Phase 19 follow-up: OFFLINE failure stage, Windows installer screenshots

User-confirmed symptoms: Overview/Incidents/Waits ONLINE, Query Store observer 0 successful / 1 failed, SQL Tuning Advisor OFFLINE, optional SQL Server INSTANCE CPU panel DISABLED. These are independent features; do not enable DBPULSE_SQLSERVER_CPU_ENABLED merely to display a selected DB CPU metric.

Stage-specific, safe API status now returns errors like:
- QUERY_STORE_CONNECT_SQL_0 — dedicated Query Store JDBC connection failed.
- QUERY_STORE_STATE_SQL_229 — Query Store mode query permission issue.
- QUERY_STORE_STATS_SQL_207 — query statistics SQL invalid column error.
- QUERY_STORE_LOCAL_SNAPSHOT_STORE_FAILED — SQLite snapshot persistence layer exception.

Only fixed stage words and SQL Server numeric vendor codes appear; SQL text, raw JDBC driver message, passwords, DB data, and connection URI remain hidden. This helps distinguish a collector error from the healthy 5-second DMV diagnostics.

The optional instance-wide CPU panel has been removed from the *Queries* page to avoid showing a disabled unrelated metric next to the selected database CPU time. The CPU collection service is not changed or force-enabled.

To inspect the exact SQL Server side without changing state: execute
`sql/13_query_store_failure_diagnosis_readonly.sql`
from SSMS under the *same restricted DBPulse monitoring login*, against the selected DB. Do not grant privileges or enable Query Store without reviewing results.


## Unambiguous Phase19 installer identity

The Phase19 installer has `AppVersion=0.19.0`, GitHub Actions artifact
`DBPulse-v0.19-Windows-Setup-preview`, and executable filename
`DBPulse-Setup-v0.19.0-preview.exe`. The existing Inno AppId remains
unchanged so it upgrades the same per-user install. Sidebar footer displays
`v0.19.0-preview` to distinguish this release from previously distributed
v0.18 packages. Data in `%LOCALAPPDATA%\\DBPulse` is unchanged.

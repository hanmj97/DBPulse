# DBPulse v0.7.0-alpha — Wait Diagnostics

## Goal
Provide actual point-in-time SQL Server request wait observations independently
from the existing Blocking Chain, Deadlock, Incident and Query Store pipelines.
This phase is **not** a cumulative `sys.dm_os_wait_stats` report, root-cause
determination, or automated SQL tuning engine.

## Design
Every ~5 seconds, a read-only monitoring login queries `sys.dm_exec_requests`
with `r.database_id = DB_ID()`, positive `wait_time`, non-null `wait_type`,
and excludes the collector's own session. Up to 100 requests are collected,
sorted by observed wait time.

Safe stored fields:
- session ID, wait type, conservative wait category
- wait duration in milliseconds at sampling time
- request total elapsed milliseconds, request status
- positive blocking session ID, when present
- coarse resource kind: KEY/PAGE/RID/OBJECT/HOBT/DATABASE/
  TRANSACTION/METADATA/APPLICATION/ALLOCATION_UNIT/FILE/UNSPECIFIED

The **raw** `wait_resource` value is reduced to a category **in SQL Server**
and is never sent over JDBC. No SQL query text, parameters, logins, hostnames
or database row contents are collected.

`WaitCategory` assigns a conservative label for recognizable wait type prefixes
(`LCK_M_`, `PAGEIOLATCH_`, `WRITELOG`, etc.). Unrecognized waits are `OTHER`,
not invented causes. Category classification is not diagnostic proof.

## API
- `GET /api/v1/targets/local-mssql/waits` returns status, last collection
  time, observed request count, `limitReached`, and current observations.
- `GET /api/v1/targets/local-mssql/waits/history?limit=20` returns 1..50
  recently stored **positive** wait observations.

Empty ONLINE sample = no waits observed *in that instant*.
OFFLINE, INITIALIZING, NOT_CONFIGURED and STALE never present the previous
sample as current data. POSTGRES retains empty samples and positive records
for seven days.

## Permissions
SQL Server 2022+: monitoring login requires `VIEW SERVER PERFORMANCE STATE`,
previously configured for `dbpulse_monitor` on local SQL Server 2025.
Do not grant data modification or db_owner rights.

## Local verification
```powershell
# Check and preserve local uncommitted work
cd D:\Projects\dbpulse-platform
git status --short

# Fetch branch and switch only when clean
git fetch origin
git switch --track origin/feature/wait-diagnostics-v0.7

# Build Spring Boot from this branch without deleting the PostgreSQL volume
docker compose up -d --build --force-recreate backend

# API status/current observations
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/waits" | ConvertTo-Json -Depth 8

# Recent positive observation history
Invoke-RestMethod "$base/waits/history?limit=20" | ConvertTo-Json -Depth 8
```

In SSMS, use the existing **DBPulseLab-only** Blocking test:
`sql/06_incident_blocking_demo_lab_only.sql`. With session A holding an
exclusive lock and session B waiting on the same row, the current Wait
Diagnostics UI should show a row with `LCK_M_X`, category `LOCK`, resource
kind commonly `KEY`, and a positive blocking session ID. SQL Server can
choose a different resource kind; do not require KEY every run.

After blocking ends, current wait observations should clear but historical
observations remain. Check `http://127.0.0.1:5174/#wait-diagnostics`.

Run existing Regression/CI tests. Source/API are still **local-only** with
no customer authentication or tenant security. Do not deploy externally.

## Future
- Compare incident windows with persisted wait observations.
- Cumulative wait deltas and baselines, excluding known benign waits.
- Correlate performance impact with safe execution-plan diagnostics.
- Approved, auditable recovery and external customer collector architecture.

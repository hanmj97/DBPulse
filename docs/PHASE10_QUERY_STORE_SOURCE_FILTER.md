# DBPulse Query Store — DBPulse collector-pattern filtering

## Confirmed in DBPulseLab screenshot, 2026-10-20

A `SELECT * FROM dbo.DeadlockDemo` is Query #247 / Plan #191.
Query Store also lists recurring query IDs matching DBPulse's source collectors:

- #72 — DMV active requests: `active_requests`, `blocked_requests`
- #237 — current wait observations and `resource_kind`
- #229 — blocked sessions with `blocker_open_transactions`
- #230 — `system_health` XEvent deadlock polling
- #244 — Query Store performance aggregate
- #95 — Query Store state `actual_state_desc`

The recurring collector queries advance their own `last_execution_time`.
That is why a user-run query may move down a correctly sorted recent-first
list. Neither an ascending Query ID nor a still-open user transaction makes
its completed UPDATE/SELECT the newest statement.

## Implementation

- Classify **inside SQL Server** by carefully constrained SQL-text patterns
  matching known DBPulse collector SQL shapes. SQL text is used only within
  SQL Server and **is not transferred or persisted** in the DBPulse backend.
- Store one bounded string: `source_category` with:
  - `DBPULSE_PATTERN` — SQL shape matches a known DBPulse collector;
  - `OTHER_OR_UNKNOWN` — no current fingerprint matched;
  - `UNKNOWN` — legacy snapshot rows before classification.
- The Source Category is **not proof of executable owner/client**. Query Store
  does not provide the client program per captured query. A user/tool running
  SQL with identical text may be classified as DBPulse-pattern, and unrecognized
  or future collector SQL may appear in the other category.
- Latest 20 + expensive 20 distinct `OTHER_OR_UNKNOWN` query/plan rows
  (maximum 40) **plus 10 recent DBPulse-pattern rows** are kept per snapshot.
  This prevents repetitive DBPulse monitoring SQL from displacing fresh user SQL
  in the default window.
- The **DBPULSE / 쿼리** page has one display mode shared by both the
  SQL Tuning Advisor and the detailed Query Store table:
  1. `DBPulse 수집 패턴 제외 (기본)`
  2. `전체 수집 쿼리`
  3. `DBPulse 수집 패턴만`
- Query/Plan ID search, priority selection, recent-first sort, and opt-in
  plan evidence still work. All current candidate IDs remain eligible for
  the read-only execution-plan endpoint, including when the monitoring-only
  view is selected.
- Runtime, average CPU, duration and logical-read heuristics are unchanged.
- There is no DB-wide "exclude every SELECT" rule, no KILL, DDL or SQL
  content export. The pattern check is performed while compiling one
  bounded Query Store poll roughly every 30s. It does use Query Store
  query text inside the monitored SQL Server; review cost before
  production deployment.

## Schema migration

A non-destructive PostgreSQL additive migration is in `schema.sql`:
`ALTER TABLE query_performance_snapshots ADD COLUMN IF NOT EXISTS
source_category VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`.

This preserves existing snapshots and adds the new column on backend
startup. Do not remove Docker volumes or reset SQL Server data.

## Local verification

```powershell
# From the existing v0.10 feature branch, preserve local modifications first
cd D:\Projects\dbpulse-platform
git status --short
git branch --show-current
git pull --ff-only origin feature/query-plan-operators-v0.10

# Rebuild backend because Query Store SQL and PostgreSQL schema changed
docker compose up -d --build --force-recreate backend
docker compose ps
docker compose logs --tail=50 backend

# Let Query Store collect at least once (~30 sec)
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/query-performance/status" | Format-List

# Review newest classified pairs, without downloading SQL text
$report = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$report.candidates |
  Select-Object -First 20 queryId, planId, lastExecutedAt, sourceCategory |
  Format-Table

# Search for the previously known lab query IF still recent and captured
$report.candidates | Where-Object { $_.queryId -eq 247 -and $_.planId -eq 191 } |
  Format-List queryId, planId, lastExecutedAt, sourceCategory
```

Open `http://127.0.0.1:5174/#query-performance` and verify:
- By default, known DBPulse collector-pattern pairs should be hidden,
  while a recent user query (e.g. #247) appears sorted by its last execution.
- `전체 수집 쿼리` shows all classified candidates.
- `DBPulse 수집 패턴만` shows collector-pattern candidates; known
  #72/#95/#229/#230/#237/#244 are expected if still captured.
- Query ID + Plan ID search, priority, sort, and plan evidence still work.
- Both Navy and Midnight Dark are readable.
- No SQL text, input values, plans or object/index names in HTTP/DB.

**Final confidence requires one local MSSQL integration test**: the SQL
fingerprints may differ by Server version, parameterization and Query
Store capture policy. A successful Java/React CI run proves code builds,
but does not prove all fingerprints match the user's SQL Server.

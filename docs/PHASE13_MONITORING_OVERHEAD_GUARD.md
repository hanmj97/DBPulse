# DBPulse v0.13.0-alpha — Monitoring Overhead Guard

## User story

DBPulseLab showed internal Query Store status SQL (`sys.database_query_store_options`)
with ~1,079 logical reads per recorded execution, alongside other DBPulse
monitoring SQL with CPU/reads. Internal SQL is now correctly classified and
no longer misleadingly labelled as a user-query tuning issue (v0.12), and
the status query is cached for 5 minutes.

**v0.13 adds an observable, guarded Query Store collection cadence.**
It is a LOCAL ONLY prototype of a collection-overhead governor, not proof
that monitoring cannot impact a customer database.

## What is controlled

- DBPulse Query Store historical performance **collector only**.
- Base interval configurable via existing local environment:
  `DBPULSE_QUERY_ANALYTICS_INTERVAL_MS=60000` (default 60 s).
- When protection is active, skip one next scheduled Query Store tick;
  effective interval is **120 seconds with 60-second default base**.
- Three *successfully collected, inexpensive* observations recover to
  base interval. Two consecutive expensive observations trigger backoff.
- Two consecutive collection failures also trigger the same cooldown, to
  avoid repeatedly hammering a failing Query Store.
- State is in memory; backend restart resets it to normal and clears
  counters. No DDL, new DB permissions or background SQL queries added.
- This DOES NOT alter 5-second DMV request/incident collection,
  Blocking/Deadlock/Wait collectors or any on-demand SQL text and plan
  inspection. Query Store status query still uses v0.12's 5-minute cache.

## Initial, deliberately conservative heuristic

The in-process governor measures:
- **Client-side JDBC Query Store collection duration** (connection + SQL;
  not server-side CPU), expensive at >=2,000ms; OR
- Maximum Query Store-recorded **per-execution mean CPU** among the bounded
  internal SQL-pattern samples, expensive at >=100ms; OR
- Maximum Query Store-recorded **per-execution mean logical reads** among
  the bounded internal SQL-pattern samples, expensive at >=1,000 pages.

These numbers are **heuristics for collector overhead**, not measurements
of overall DB CPU utilisation, total DBPulse workload, or customer
user-request response-time degradation. Query Store stats are rolling
aggregates that can be delayed. The DBPulse collector patterns are
best-effort SQL shape recognition, not authenticated client attribution;
only up to 10 recent internal plan pairs are present in the bounded
sample. Missing internal query samples is *unknown*, never proof of
safety. Backoff can persist while rolling averages remain high.

The governor never automatically executes `KILL`, `ALTER INDEX`, DDL,
`QUERY_STORE` changes, or a SQL Server restart.

## Endpoint

Read-only, in-memory:
`GET /api/v1/targets/local-mssql/query-performance/overhead`

No SQL Server request from this endpoint.

JSON contains:
`mode` (NORMAL / THROTTLED), `reason`, `baseIntervalMs`,
`effectiveIntervalMs`, `lastCollectorWallMs`,
`lastObservedAt`, `sampledInternalPlans`,
`peakInternalAvgCpuMs`, `peakInternalAvgLogicalReadsPages`,
`expensiveStreak`, `recoveryStreak`, `failureStreak`,
`skippedCycles`, and explicit `limitation`.

The existing Query Store status, advisor and demand-only plan/text
endpoints remain backward compatible. Cached Query Store snapshots
are accepted only within a **bounded freshness threshold**
(default base 60sec -> 180sec), allowing one skipped scheduled tick
while still refusing arbitrary stale captures. SQL Store collection
failures keep previous failure behaviour (status OFFLINE).

## UI

A new **수집 부하 보호** panel appears above the SQL Tuning Advisor on
the Query page, in both approved dark themes. It reads only the new
DBPulse API once on mount and every 15 seconds, displaying the observed
mode, effective interval, last JDBC duration, highest observed internal
SQL average CPU/read cost, number of skipped cycles, reason and caveat.

UI is read-only; it is not a threshold editor nor an emergency toggle.

## Local test

```powershell
# Ensure no local modifications will be overwritten
cd D:\Projects\dbpulse-platform
git status --short

# Fetch the v0.13 work branch
git fetch origin
git switch --track origin/feature/monitoring-overhead-v0.13

# Apply backend; preserve Docker/PostgreSQL data volume
docker compose up -d --build --force-recreate backend
docker compose ps
docker compose logs --tail=50 backend

# Confirm version
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/health"

# Check status; this HTTP request does NOT query MSSQL
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/query-performance/overhead" |
  Format-List mode,reason,baseIntervalMs,effectiveIntervalMs,lastCollectorWallMs,
    sampledInternalPlans,peakInternalAvgCpuMs,peakInternalAvgLogicalReadsPages,
    expensiveStreak,recoveryStreak,skippedCycles

# Confirm Query Store collector remains ONLINE
Invoke-RestMethod "$base/query-performance/status" | Format-List
```

Wait at least two **actual successful Query Store collection cycles**:
by default they are 60 seconds apart (first run has initial delay).
With previously observed internal Query Store reads of >=1,000 pages
(e.g. query #95 in DBPulseLab), the guard is *expected* to transition
from NORMAL to THROTTLED, effective interval 120,000ms, and after the
next scheduled tick the skippedCycles counter to increase.
This is not guaranteed: Query Store rollups, internal sample selection
and cost conditions may differ. Do not fabricate load in a production DB.

Open `http://127.0.0.1:5174/#query-performance` to inspect the card.
Verify existing Query ID/Plan ID filters, last-run sort, opt-in SQL viewer,
plan evidence and comparisons; also check Incident/Blocking/Deadlock/Wait
are still collected at their existing cadence.

## Before external customer rollout

Critical missing functionality:
- Per-collector **actual SQL Server resource budget** accounting,
  not just max per-execution Query Store aggregates.
- A representative SQL Server before/after load benchmark including user
  response time, total DB CPU, waits, logical + physical reads and actual
  DBPulse statement invocation frequency.
- A DBPulse SQL allowance/SLO, self-protection metrics for *all* collectors,
  stop/pause/circuit-breaker policy, on-demand API quotas.
- Authentication, per-target authorization, secrets handling, audit,
  privacy controls and tenant isolation.
- An approved, nonintrusive observability design that is safe if Query
  Store capture is disabled or SQL Server is already overloaded.

**Release gate:** CI green and local DBPulseLab integration validated.
This feature stays on a Draft PR until those checks pass.

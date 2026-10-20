# DBPulse v0.13 — Observer cost measurement protocol

## What has and has not been confirmed

The user validated `THROTTLED`, `effectiveIntervalMs=120000`
and `skippedCycles=3`. That confirms the self-protection
state machine is operating, **not** that SQL Server incurs less
total CPU, logical reads or customer-query latency.

This change adds:
1. JVM process-lifetime counters to the existing read-only `/overhead`
   API: `totalCollectorAttempts`, `successfulCollections`,
   `failedCollections`, `cumulativeJdbcWallMs`. The React card also
   presents these values. They reset after JVM/container restart,
   so don't calculate deltas across a restart.
2. A manual, read-only SQL Server Query Store delta collector:
   `sql/09_observer_cost_window_lab_only.sql`.
   Two snapshots use one SSMS **session-local temporary table**
   baseline and a fixed last-4-hour cutoff to avoid a moving
   lookback boundary. It dynamically identifies DBPulse statements
   by SQL marker or statement shape, NEVER fixed Query/Plan IDs.
   Query text is consulted **inside MSSQL only** and never output.

## Interpreting the SQL measurements

The SQL profiler calculates changes in cumulative
`SUM(count_executions)`, weighted `SUM(avg_cpu_time * count_executions)`
and weighted `SUM(avg_logical_io_reads * count_executions)` for Query
Store runtime rows with `execution_type=0`. CPU results are in ms,
logical reads in 8KB pages. Output includes *per-minute* rates to
normalize different capture windows.

The script cannot attribute workload to a client conclusively.
A matching SQL shape or marker is a **heuristic**. Query Store stores
aggregated intervals, may flush late, expire rows, change plans or be
read-only, and counts don't necessarily align exactly with wall time.
When a plan counter disappears or decreases, the script flags that
plan as invalid rather than counting a negative delta; any such
invalid rows should make the comparison inconclusive.
This profiler itself executes Query Store SQL and adds overhead;
run it only manually in the isolated DBPulseLab.

**Unlike the 5s DMV collector**, only the 60s/120s Query Store collector
is throttled by v0.13. Other fixed-interval DMV / wait / deadlock
queries still run; compare **per-source** rows and total workload
rather than expecting all DBPulse workload to halve.

## Two comparable A/B windows (local lab only)

1. Do not run high-volume SQL load, edit indexes or change the data
   between windows. Use the same baseline workload; ideally a repeatable
   scripted representative workload after separate approval.
2. **A: Baseline 60s**: run the main v0.12 backend on local DBPulseLab;
   it uses a fixed 60s Query Store polling rate, no v0.13 governor.
   Keep frontend in a separate session only if needed. Ensure backend
   logs confirm Query Store ONLINE.
3. In SSMS DBPulseLab, open
   `sql/09_observer_cost_window_lab_only.sql`; execute with
   `@Phase='START'`. Wait ~10 minutes without closing the tab.
   Change to `@Phase='END'`; execute **in the same connection**.
   Record only aggregated per-minute counts/CPU/reads.
4. **B: Adaptive 120s**: switch to v0.13 backend after A has finished,
   rebuild (leave DBPulseLab and PostgreSQL data untouched), wait for
   `THROTTLED` and verify `effectiveIntervalMs=120000`.
   Then run START/END again over the same 10-minute period in the
   same SSMS tab, while `THROTTLED` holds.
5. Compare matched category rates `QUERY_STORE_STATE`,
   `QUERY_STORE_COLLECTOR`, `QUERY_TEXT_VIEWER`,
   `OTHER_INTERNAL`, plus sum of all rows. Compare *at least*
   Query Store collector CPU/read cost **per minute**, not per-execution
   average only. If modes switch within B, record the transition; this
   invalidates a controlled 120s window.
6. Optionally query `/overhead` at start/end to see differences
   in `totalCollectorAttempts`, `skippedCycles` and
   `cumulativeJdbcWallMs`; it requires **no additional MSSQL
   query**. Do not compare process counters across a JVM restart.
7. Alongside these totals, capture SQL Server user-query response
   time, CPU, wait durations and physical read I/O for comparable
   workloads; Query Store's total CPU isn't server utilization.
8. Report `A` vs `B` as **an estimate under observed lab conditions**.
   Re-run under a representative workload before calling it a proven
   production reduction. No external customer deployment.

### Commands

```powershell
# DBPulse API endpoints do not read SQL Server; no secrets in these responses
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$before = Invoke-RestMethod "$base/query-performance/overhead"

# After a measurement interval WITHOUT restarting the backend
$after = Invoke-RestMethod "$base/query-performance/overhead"
[pscustomobject]@{
    StartedMode = $before.mode
    EndMode = $after.mode
    CompletedPolls = $after.successfulCollections - $before.successfulCollections
    FailedPolls = $after.failedCollections - $before.failedCollections
    SkippedTicks = $after.skippedCycles - $before.skippedCycles
    JdbcWallMs = $after.cumulativeJdbcWallMs - $before.cumulativeJdbcWallMs
} | Format-List
```

## Release gate

v0.13 remains Draft PR #12 until local A/B measurement and Query
Store freshness/regression checks. A SQL comparison with a changed
load, negative deltas, absent internal SQL, or an unknown
`THROTTLED` duration must be labelled **inconclusive**.

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
invalid rows should make the comparison inconclusive. Plans first appearing
after START are also excluded from cumulative deltas (not incorrectly
treated as entirely new resource consumption); check
`newly_observed_plans_excluded` and do not call the A/B comparison complete
if it is nonzero.
This profiler itself executes Query Store SQL and adds overhead;
run it only manually in the isolated DBPulseLab.

**Unlike the 5s DMV collector**, only the 60s/120s Query Store collector
is throttled by v0.13. Other fixed-interval DMV / wait / deadlock
queries still run; compare **per-source** rows and total workload
rather than expecting all DBPulse workload to halve.


## XE preflight and 90-second smoke test (local DBPulseLab)

**Actual event-file directory:** `D:\Projects\DBPulseLabXE` on
the **SQL Server host**, not the DBPulse backend container. The
SQL Server *database engine service account* needs Modify access to
this folder only. Do not grant `Everyone` access or change any
`dbpulse_monitor` server privileges. Check the exact engine
service account with Windows-authenticated SSMS sysadmin:

```sql
SELECT servicename, service_account, status_desc
FROM sys.dm_server_services
WHERE servicename LIKE N'SQL Server (%';
```

Then from PowerShell inspect the folder and its ACL:

```powershell
# Check lab event-file directory without modifying anything
$xePath = 'D:\Projects\DBPulseLabXE'
Test-Path -LiteralPath $xePath

# Show current permissions; do not change root D: ACLs
if (Test-Path -LiteralPath $xePath) {
    Get-Acl -LiteralPath $xePath |
        Select-Object -ExpandProperty Access |
        Select-Object IdentityReference, FileSystemRights, AccessControlType
}

# View local SQL Server service identity for cross-checking
Get-CimInstance Win32_Service |
    Where-Object { $_.Name -eq 'MSSQLSERVER' -or $_.Name -like 'MSSQL$*' } |
    Select-Object Name, StartName, State
```

After confirming exact SQL Server service-folder access,
open `sql/11_observer_ab_extended_events_lab_only.sql` in a NEW SSMS tab.
Set `@Phase = 'START'`, execute, **keep the tab open**, wait 90 seconds,
change to `'END'` and execute again in that same SSMS connection.
Confirm `dropped_xe_events = 0` and check event category counts.
A 90-second smoke test only verifies XE operation, **not** an A/B result.
At the end use `@Phase = 'CLEANUP'` in the same window, then
delete only `ObserverCost*.xel` from the dedicated event directory.
The script reports aggregates and does not print captured statements,
but the XEL files **can contain raw SQL** and must never be committed.
If there are no `QUERY_STORE_COLLECTOR` records, compare with the
JVM `successfulCollections` delta and diagnose XE capture scope rather
than interpreting zero as a zero-cost collector. Do not execute on
customer databases.


## 2026-10-20 smoke-test diagnostic: 179 seconds, zero events

A 179-second SQL 11 smoke capture reported `dropped_xe_events=0`,
but **no rows** in the first results table. This does NOT establish
zero DBPulse load or a successful A/B measurement. The first version
listened only to `sql_statement_completed`. JDBC prepared statements
commonly use RPC/procedure execution, so it could miss the outer call.

The updated SQL 11 captures **`rpc_completed` and
`sql_batch_completed` only** (request-level completions);
do not sum these with nested statement-completed events. It reports
counts per `event_name` and `app_name` before categorized totals
and normalizes SQL-batch CPU milliseconds to microseconds, then to
display milliseconds (RPC CPU is already microseconds on supported
SQL Server 2012+). There is a separate `XE_SMOKE_PROBE` category
for the local diagnostic client. Generic `DBPulseQueryStore` RPC calls
without SQL text are `QUERY_STORE_UNATTRIBUTED` rather than falsely
identified as stats or state requests.

**Redo 90-second smoke test before A/B:**
- Run `@Phase='CLEANUP'` in the original **stopped** SQL 11 SSMS
  session; check the dedicated XE session is absent.
- Delete only `D:\Projects\DBPulseLabXE\ObserverCost*.xel`
  *after* confirming the previous test's results are no longer needed.
  XELs can contain SQL text and should not enter Git.
- Pull the revised branch, open the updated SQL 11 in a **new SSMS tab**,
  run `START`. Use a local ADO.NET connection with
  `Application Name=DBPulseXEProbe` and a parameterized
  `SELECT @value` call, which exercises the RPC path.
- Wait at least 90 seconds, then run `END` in **that same new tab**.
  Check `rpc_completed`/application rows, `XE_SMOKE_PROBE`,
  `QUERY_STORE_COLLECTOR` or `QUERY_STORE_UNATTRIBUTED`, and
  `dropped_xe_events=0`. Absence of expected probe events remains
  a hard block on proceeding to the 10-minute benchmark.
- Compare DBPulse JVM `successfulCollections` between START and END
  before drawing any conclusion about a missing collector row.

## Two comparable A/B windows (local lab only)

1. Do not run high-volume SQL load, edit indexes or change the data
   between windows. Use the same baseline workload; ideally a repeatable
   scripted representative workload after separate approval.
2. **A: fixed baseline 60s, SAME v0.13 binary**: set
   `DBPULSE_QUERY_ANALYTICS_INTERVAL_MS=60000` and
   `DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED=false` for the *isolated lab*
   backend; rebuild only `backend`. The `/overhead` response must show
   `policy=FIXED_BASELINE_LAB_ONLY`, `mode=NORMAL`, interval 60000.
   Do NOT disable adaptive protection in a customer environment.
   This avoids comparing unrelated v0.12 and v0.13 feature changes.
3. In SSMS DBPulseLab, open
   `sql/09_observer_cost_window_lab_only.sql`; execute with
   `@Phase='START'`. Wait ~10 minutes without closing the tab.
   Change to `@Phase='END'`; execute **in the same connection**.
   Record only aggregated per-minute counts/CPU/reads.
4. **B: adaptive 120s**, using the **same v0.13 build**:
   set `DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED=true`; rebuild only
   `backend` (leave DBPulseLab and PostgreSQL data untouched). Only START
   after API reports `policy=ADAPTIVE`, `mode=THROTTLED`, and
   `effectiveIntervalMs=120000`.
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


## Why QUERY_STORE_COLLECTOR can be 0 in the 598-second report

The Java `MssqlQueryStoreCollector.collect()` uses the
`DBPULSE_INTERNAL_QSTORE_STATS_V1` query. A successful JVM
`successfulCollections` delta is evidence that this method returned
successfully. The historical Query Store report is **not** a direct
recording of JDBC executions: capture mode `AUTO`, Query Store read-only
mode, visibility/flush delay, plan turnover and our SQL fingerprint
heuristics can all produce missing rows. The supplied 598-second sample
has no guard counters from START/END, so **its 60s/120s mode is UNKNOWN**.

Run `sql/10_query_store_collector_capture_diagnostics_lab_only.sql`
in SSMS to inspect `actual_state_desc`, `query_capture_mode_desc`,
collector marker/shape presence in Query Store, and (if authorized)
independent cached-plan execution data in `sys.dm_exec_query_stats`.
Zero rows are still not proof of zero calls. Never alter the database's
Query Store settings simply to manufacture capture during an A/B window.

## Stronger local A/B evidence: XEvents + fixed synthetic workload

The historic Query Store counter script is a cross-check, not the
primary quantitative signal while the collector is unobserved.
`sql/11_observer_ab_extended_events_lab_only.sql` can use a **local
server-side Extended Events** `sql_statement_completed` event session
filtered to client app names `DBPulse%`. It sums statement CPU in
microseconds (reported in milliseconds), logical and physical 8KB
pages, execution counts and call rate by SQL type; the experimental
Query Store collector is identified by application name and SQL shape.
It does **not** double-count `rpc_completed` and
`sql_statement_completed`. Extra non-collector internal SQL stays
in a separate category.

**Safety:** administrator-only, DBPulseLab only. Event files live at
`D:\Projects\DBPulseLabXE\ObserverCost*.xel` and may **contain SQL statements**,
even though reports expose only aggregated numbers. Set up the
directory and SQL Server service-account file permissions manually,
delete any old matching XEL *before* each START and securely remove the
XEL after aggregating. Do not commit any XEL. The session incurs its
own instrumentation cost (identical on A/B). If XE cannot see the
collector while the JVM reports successful calls, the comparison is
**inconclusive**, not zero overhead.

The repeatable **synthetic** SQL client, `tools/run_observer_ab_workload_lab.ps1`,
uses `dbo.Accounts` from `sql/03_prepare_load_test_data.sql`,
read-only indexed range queries through a local
`Application Name=DBPulseLabWorkload` session, Windows integrated
authentication, fixed input sequence and 500ms pacing over 10 minutes.
It measures client-perceived average/p95 latency and counts
collector attempts, failures and skipped ticks at both ends. It also
samples mode every 15 seconds: an unexpected mode transition, API
failure, backend restart or different execution counts disqualifies
a clean controlled comparison.

### Lab protocol per window

1. Run the capture diagnostics in SSMS, ensure Query Store state is
   stable, and check the 20,000-row `dbo.Accounts` lab fixture.
2. Prepare A/B backend policy (see below), wait for Query Store ONLINE.
   Verify status `/overhead` and existing incident/wait APIs before START.
3. In **SSMS tab #1**, `sql/09...` at START to cross-check Query Store
   summary. In **SSMS tab #2**, `sql/11...` at START to create/start XE.
   Keep both tabs/connections open; do not interchange their START/END.
4. In PowerShell run `tools/run_observer_ab_workload_lab.ps1 -Mode A`
   (or `-Mode B`). Do not issue extra UI queries/plan opens between runs.
   Maintain **identical workload** for both 10-minute windows.
5. End the XEvent capture using `@Phase='END'` in tab #2 and then end
   Query Store window with `@Phase='END'` in tab #1. Record both
   category totals **and** the PowerShell aggregate JSON.
6. In tab #2 use `@Phase='CLEANUP'`; securely delete only the
   dedicated XE output files. Complete A before restarting backend for B.
7. Confirm A `policy=FIXED_BASELINE_LAB_ONLY` and B
   `policy=ADAPTIVE`, uninterrupted `THROTTLED`. Run B after
   similar warm-up. If throttling recovers mid-window, re-run B under
   stable conditions; **never claim an adaptive 120s measurement from
   a mixed-mode window**.

### PowerShell examples

```powershell
# Select the existing v0.13 PR branch; preserve any uncommitted work.
Set-Location D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch feature/monitoring-overhead-v0.13
git pull --ff-only origin feature/monitoring-overhead-v0.13

# A: exactly the same v0.13 binary, with lab-only fixed polling.
# Environment variables override Docker Compose .env for this invocation.
$env:DBPULSE_QUERY_ANALYTICS_INTERVAL_MS='60000'
$env:DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED='false'
docker compose up -d --build --force-recreate backend
Invoke-RestMethod 'http://127.0.0.1:18081/api/v1/targets/local-mssql/query-performance/overhead' |
  Format-List policy,mode,effectiveIntervalMs,totalCollectorAttempts

# After XE and Query Store START, generate comparable A workload.
.\tools\run_observer_ab_workload_lab.ps1 -Mode A

# B: complete A and cleanup first. Re-enable v0.13 adaptive protection.
$env:DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED='true'
docker compose up -d --build --force-recreate backend
# Wait until it is genuinely THROTTLED; never assume elapsed time proves it.
Invoke-RestMethod 'http://127.0.0.1:18081/api/v1/targets/local-mssql/query-performance/overhead' |
  Format-List policy,mode,effectiveIntervalMs,skippedCycles

# After XE and Query Store START for B, run identical load.
.\tools\run_observer_ab_workload_lab.ps1 -Mode B
```

The PowerShell harness saves **aggregates only** to
`$env:TEMP\DBPulse_AB_A.json` and `DBPulse_AB_B.json`.
Do not publish credentials, SQL statement XEL files or customer data.

### Comparison acceptance

Require both:
- Server-side XE rows for `QUERY_STORE_COLLECTOR` (or verified JDBC
  evidence plus an explicitly stated XE attribution limitation), with
  comparable per-minute collector CPU, reads and calls.
- LAB_WORKLOAD sample counts/rates within a chosen tolerance (recommend
  within 5%), client p95 and average latency, XE statement p95 and
  overall SQL Server resource trends without meaningful regression.

Calculate `(A_rate - B_rate)/A_rate*100` separately for collector CPU,
reads, and calls. Do not mix XE and Query Store totals (overlapping
measurements), and do not claim a reduction if the A rate is zero.
Keep the 5s collector workload in an independent category; its
frequency should remain unchanged. Check Query Store diagnostics, API
regressions, query text/plan views and dark UI. Local evidence supports
only a LAB release, not production-safe customer monitoring.

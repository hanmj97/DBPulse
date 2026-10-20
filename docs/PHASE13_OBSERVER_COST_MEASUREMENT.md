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

## 2026-10-20 — successful corrected XE smoke measurement (120 seconds)

After replacing statement-only capture with request-level `rpc_completed` /
`sql_batch_completed`, the user ran an actual **120-second** local XE
smoke test against DBPulseLab. The report contained **81 RPC completions**,
with **zero reported dropped XE events**:

| App | RPC completions |
|---|---:|
| DBPulseBlockingChain | 23 |
| DBPulseCollector | 23 |
| DBPulseCpuSample | 2 |
| DBPulseDeadlocks | 7 |
| DBPulseQueryStore | 1 |
| DBPulseQueryText | 1 |
| DBPulseWaits | 23 |
| DBPulseXEProbe | 1 |

| Classified XE category | Calls | CPU ms | Logical read pages | Physical read pages | Avg duration ms |
|---|---:|---:|---:|---:|---:|
| OTHER_INTERNAL | 79 | 235 | 11,403 | 863 | 34.95 |
| QUERY_STORE_COLLECTOR | 1 | 47 | 12,447 | 483 | 4,011.07 |
| XE_SMOKE_PROBE | 1 | 0 | 0 | 0 | 0.19 |

**Interpretation:** Real DBPulse collector RPC was observed, addressing
the zero-event blind spot in the earlier XE configuration. The
Query Store-only 598-second report had `QUERY_STORE_COLLECTOR=0`, but
that was **not** proof of zero collector calls. The corrected XE capture
recorded one collector completion with nonzero CPU and reads. One
request's ~4-second elapsed duration is not the same as 47 ms of CPU
and is insufficient to determine the source of the remaining time.
The result proves that the instrumentation sees this query, **not** that
adaptive scheduling reduces total cost or customer query latency.

**Next:** After copying these aggregate numbers, `CLEANUP` the stopped
XE session and remove only its dedicated XEL files; perform 10-minute
A=fixed and B=continuous THROTTLED measurements with matching workloads.
Keep PR #12 Draft until measured results and regression checks pass.

## Required A/B synthetic table (before either measurement window)

The local `DBPulseLab.dbo.Accounts` table is **not created by DBPulse startup**.
The original `sql/03_prepare_load_test_data.sql` also modifies Query Store
configuration, so **do not rerun SQL 03 during v0.13 benchmarking**.
Use the isolated `sql/12_prepare_ab_accounts_lab_only.sql` instead,
through a Windows-authenticated SSMS session, **before opening the A/B
START windows**. It creates the missing table and seeds 20,000 synthetic
rows only when empty, verifies IDs 1..20000, and leaves Query Store
configuration and other DBPulse tables untouched.

Check that the current SSMS connection is on local DBPulseLab.
If either SQL 09 or SQL 11 START has already been executed without the
fixture, invalidate/cancel that interval and restart cleanly after
creating the table. Do not measure setup/index build inside A or B.
The PowerShell workload now checks the fixture before its timed loop
and fails with an actionable message if the table is missing.

## 2026-10-20 — A baseline: fixed 60s, 10-minute workload (measured)

The user completed the **A** run with \`policy=FIXED_BASELINE_LAB_ONLY\`,
\`mode=NORMAL\` throughout, **1,200 synthetic client calls** in exactly
~600 seconds, mean client latency **5.975 ms**, p95 **1.624 ms**,
\`successfulCollectorDelta=9\`, \`failedCollectorDelta=0\`,
\`skippedCycleDelta=0\`, \`JdbcWallMsDelta=2926\`,
\`modeViolationSamples=0\`, \`apiStatusErrors=0\`,
\`comparisonEligible=true\`.

**Primary server-side XE baseline (628 seconds; 0 dropped events):**

| XE category | Requests | CPU ms | Logical read pages | Physical read pages | Mean duration ms |
|---|---:|---:|---:|---:|---:|
| LAB_WORKLOAD | 1,202 | 2,123 | 19,053 | 563 | 5.67 |
| OTHER_INTERNAL | 397 | 2,827 | 65,008 | 3,348 | 125.8 |
| QUERY_STORE_COLLECTOR | 11 | 467 | 119,662 | 4,437 | 180.34 |
| QUERY_STORE_STATE | 3 | 0 (rounded) | 6,366 | 287 | 83.94 |
| **DBPulse internal sum** | **411** | **3,294** | **191,036** | **8,072** | — |

The XE workload total includes **1,200 RPC + 2 completed SQL batches**,
which is why the measured SQL request count is 1,202 while the scripted
workload executed 1,200 timed client calls. The 628-second XE window
started ~16 seconds before and ended ~12 seconds after the 600-second
client-load window. This also explains why **11 XE Query Store requests
must not be naively equated with the JVM's 9 completed polls over its
shorter window**. For B, preserve the same timing and compare normalized
per-minute XE rates: A internal ~314.7 CPU ms/min,
~18,251.8 logical-read pages/min, ~39.27 calls/min;
A Query Store collector ~44.62 CPU ms/min,
~11,433 logical-read pages/min, ~1.05 calls/min.

**Secondary SQL 09 Query Store baseline (634 seconds; incomplete):**
\`OTHER_INTERNAL\` 266 executions, 4,829.11 ms CPU, 7,070 logical
pages; \`QUERY_STORE_STATE\` 12 executions, 74.2 ms CPU, 26,732
logical pages; \`QUERY_STORE_COLLECTOR\` 0 newly observed executions.
\`invalid_or_disappeared_plans=0\` but
\`newly_observed_plans_excluded=1\` for state. These results are **not
strictly comparable to the XE totals**, and the missing Query Store
collector row does not cancel the observed 11 XE calls.
Do **not** claim successful Query Store-based A/B validation yet.

**Latency caveat:** measured mean client/server duration exceeds p95,
which can happen if a small number of extreme slow requests raises the
mean. A/B results should report this distribution anomaly and avoid
claiming a latency improvement from these summary metrics alone.
No customer-production safety claim is supported.

**Remaining gate:** Run **B** with the same code and scripted workload,
wait for stable \`policy=ADAPTIVE\`, \`mode=THROTTLED\`,
\`effectiveIntervalMs=120000\` *before* XE/SQL 09 START, and verify it
remains so throughout the 10-minute client load. Do not modify fixture,
Query Store settings, or SQL 11 instrumentation between A and B.


## 2026-10-20 — B adaptive 120s A/B comparison (measured, release held)

The user completed B with the same v0.13 app and same 1,200 timed
read-only synthetic queries over 600 seconds:
\`policy=ADAPTIVE\` and \`mode=THROTTLED\` at start and end,
\`successfulCollectorDelta=5\`, \`failedCollectorDelta=0\`,
\`skippedCycleDelta=5\`, \`JdbcWallMsDelta=1289\`,
\`modeViolationSamples=0\`, \`apiStatusErrors=0\`,
\`comparisonEligible=true\`. Client mean latency **11.38 ms** and
p95 **1.69 ms** (A: 5.975 ms and 1.624 ms).

**XE B (710 seconds; 0 dropped events; vs A: 628 seconds):**

| XE class | B completed requests | B total CPU ms | B logical pages | B physical pages | B mean duration ms |
|---|---:|---:|---:|---:|---:|
| LAB_WORKLOAD (1,200 RPC + 2 setup batches) | 1,202 | 17,421 | 18,545 | 860 | 11.07 |
| OTHER_INTERNAL | 460 | 3,547 | 68,151 | 4,353 | 82.22 |
| QUERY_STORE_COLLECTOR | 6 | 299 | 64,697 | 2,414 | 120.95 |
| QUERY_STORE_STATE | 2 | 15 | 5,150 | 308 | 31.07 |
| **DBPulse internal total (not workload)** | **468** | **3,861** | **137,998** | **7,075** | — |

**Per-minute comparison** (computed as value × 60 / XE elapsed seconds):

| Metric / minute | A 628s | B 710s | Change B vs A |
|---|---:|---:|---:|
| Query Store collector completed requests | 1.051 | 0.507 | **−51.8%** |
| Query Store collector CPU ms | 44.62 | 25.27 | **−43.4%** |
| Query Store collector logical-read pages | 11,433 | 5,467 | **−52.2%** |
| ALL DBPulse internal CPU ms | 314.71 | 326.28 | **+3.7%** |
| ALL DBPulse internal logical-read pages | 18,252 | 11,662 | **−36.1%** |
| ALL DBPulse internal calls | 39.27 | 39.55 | **+0.7%** |
| OTHER_INTERNAL CPU ms | 270.10 | 299.75 | **+11.0%** |

**User workload risk:**
A client mean 5.975 ms → B 11.38 ms (**+90.5%**);
client p95 1.624 ms → B 1.69 ms (**+4.1%**).
XE workload mean 5.67 ms → 11.07 ms (**+95.2%**) and
XE workload p95 1.31 ms → 1.35 ms (**+3.1%**).
The entire LAB_WORKLOAD XE CPU sum rose from **2,123 ms** to
**17,421 ms** (**+720.6%**). No causality is established; a small
number of outliers and two preflight batch events mixed into the
1,202-request total must be investigated. An increased mean with
a near-stable p95 is plausible under skewed tails.
**Do not claim user-query performance protection or improvement.**

The B SQL 09 Query Store counter view still did not display a
\`QUERY_STORE_COLLECTOR\` category, despite 6 actual XE completions,
and is **inconclusive for collector attribution**. B secondary
OTHER_INTERNAL: 311 calls / 6,434.1 ms CPU / 8,688 logical pages;
QUERY_STORE_STATE: 8 calls / 48.17 ms CPU / 15,105 pages.
Do not merge or add these values to XE totals.

**Confounders:** XE A covered 628 s and B 710 s, while each client load
ran ~600 s. Normalize *background-collector* comparisons by their own
XE windows; do not treat XE per-minute workload counts as the
actual scripted load rate. These are two single nonrandomized sequential
lab runs, not a controlled proof of production observer impact.
Repeat with closely matched capture windows and a crossover order
(A-B-B-A or B-A-A-B) if attribution is needed.

**Next blocking investigation:** Keep B
\`D:\\Projects\\DBPulseLabXE\\ObserverCost*.xel\` files, and run
\`sql/13_ab_xe_workload_spike_diagnostics_lab_only.sql\` in Windows-auth
local SSMS master to split RPC vs 2 batch costs and show only numeric
p50/p95/p99/max latency and largest 15 event durations; it emits
**no statement text or parameters**. If B XEL files are already deleted,
the diagnostic cannot recover them; remeasure rather than invent values.
Retain Draft PR #12 until workload regression, Query Store/XE attribution,
5-second incident monitoring and both dark themes are reviewed.


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

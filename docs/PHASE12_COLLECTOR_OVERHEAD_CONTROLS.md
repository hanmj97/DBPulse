# DBPulse v0.12 — Collector overhead controls (pre-production)

## Why we made this change

User verified that internal DBPulse Query Store status SQL

```sql
SELECT actual_state_desc FROM sys.database_query_store_options
```

is correctly tagged `DBPULSE_PATTERN`, yet DBPulse still displayed
`점검 권장` due to Query Store **average logical reads ~1,071 pages**.
The recorded average duration was ~45.76ms, CPU ~1.96ms, and 108
aggregated executions. These are observed **Query Store summaries**, not
proof of disk I/O bottleneck or customer-visible performance degradation.

**Two separate issues** must be addressed:
1. Monitor overhead must be reduced and budgeted—not merely hidden.
2. Internal monitoring queries must be evaluated as **collector overhead**,
   never as customer SQL requiring index changes.

## Implemented v0.12 mitigations

1. Query Store collector polling default changed from `30,000ms`
   (30s) to **60,000ms** (60s), configurable with
   `DBPULSE_QUERY_ANALYTICS_INTERVAL_MS` in the local `.env`.
   The setting only controls the Query Store polling schedule, **not**
   the existing 5s DMV collectors (which require independent profiling).
2. `SELECT actual_state_desc FROM sys.database_query_store_options`
   is executed only on cache miss/startup, at most once per **5 minutes**
   under normal operation. The successful state is reused between polls.
   On SQL exception, the cached state is invalidated to allow
   refresh at the next poll.
3. Query Store metrics SQL first aggregates *recent 15-minute runtime
   rows by Query/Plan* and then attaches `sys.query_store_query_text`
   for SQL-shape classification. This reduces unnecessary joins against
   historical Query Store text in the logical query structure.
   SQL Server's optimizer controls the physical plan; improvements
   **must be measured**, not assumed.
4. Rows classified `DBPULSE_PATTERN` are now tagged
   `COLLECTOR_OVERHEAD` and `INTERNAL`, not `HIGH_READS` and
   `MEDIUM` tuning priorities.
   SQL Tuning Advisor shows **수집 부하**, recommending interval/collector
   review rather than customer index tuning. Plan evidence correlation
   likewise becomes `COLLECTOR_OVERHEAD_ONLY`. Historical samples
   are preserved unmodified.
5. All existing features remain, including Query Store SQL viewer,
   bounded plan analysis, query-plan comparison, source-pattern filter,
   incident/deadlock/wait collection, both chosen dark themes.

## Explicit limitations

- Caching Query Store state for 5min may delay discovering a status change
  where other Query Store statistics reads continue to complete without
  an error. Failure to read Query Store statistics invalidates cache.
  This is a monitoring freshness vs observer overhead tradeoff.
- The actual DBPulse Query Store workload on a customer's database may
  be much higher; the 60s default does **not** guarantee a safe query
  budget, and no per-tenant rate limits, concurrency controls or complete
  workload quotas exist in the local prototype yet.
- `avg_logical_io_reads` is logical page reads (8KB pages), not physical
  disk reads. Query Store aggregated execution count may span runtime
  stats intervals and is NOT the precise count executed between two
  DBPulse polls.
- The query text fingerprint is a *heuristic*, not proof that a statement
  was issued by DBPulse; Query IDs are never hardcoded.
- No benchmark or live SQL Server measured performance improvement is
  claimed before local integration tests.
- The local prototype should **not** be deployed against customers'
  production databases until explicit load budgeting, permission
  isolation, backpressure, security, and failure scenarios are tested.

## Test plan (local DBPulseLab only)

**Before updating:** save a screenshot of the Query Store status SQL's
Query/Plan, average logical reads, CPU, duration and the time of
observation. Note this is already available in the conversation, but
repeat in a stable environment if precise baseline is needed.

```powershell
# Inspect and preserve local changes before updating to the v0.12 branch
cd D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch feature/query-plan-comparison-v0.12
git pull --ff-only origin feature/query-plan-comparison-v0.12

# Use the safe 60-second default unless you need a longer interval
# In .env, optional value:
# DBPULSE_QUERY_ANALYTICS_INTERVAL_MS=60000

# Rebuild backend without touching PostgreSQL volumes or SQL Server data
docker compose up -d --build --force-recreate backend
docker compose ps
docker compose logs --tail=50 backend

$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/query-performance/status" | Format-List

# After 2+ collection cycles, check internal SQL categories and signals.
$report = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$report.candidates |
  Where-Object {$_.sourceCategory -eq 'DBPULSE_PATTERN'} |
  Select-Object queryId,planId,priority,diagnosisCode,lastExecutedAt |
  Format-Table
```

**Expected**: DBPulse query #95 (or other customer-specific ID) is
`DBPULSE_PATTERN`, `INTERNAL`, `COLLECTOR_OVERHEAD` in
the latest successful collection.

In the UI select **DBPulse 수집 패턴만**:
- Row displays `수집 부하` instead of `점검 권장`.
- Detail says collector SQL: investigate polling cadence / CPU / reads,
  not user index creation.
- Opt-in plan evidence correlation shows `COLLECTOR_OVERHEAD_ONLY`.
- Default view still excludes monitoring SQL; user SQL is unaffected.

**Measure overhead in the actual SQL Server** before and after at
comparable workloads for 10–15 minutes. Read Query Store aggregates
plus target-wide CPU, storage read I/O, waits, and user query response
time. An execution-time decrease alone does not establish reduced
server impact; capture **call frequency**, total CPU, logical/physical
reads and observation completeness.
Confirm the Query Store collector state stays ONLINE and no 503/STALE
regressions occur under normal 60s polling.

## Future operational requirements

- Real per-target quotas and self-throttling when collection exceeds a
  workload budget; rate-limited plan/XML access and query text retrieval.
- Explicit per-customer opt-in, credentials least privilege, strong
  authentication and tenant isolation.
- Safe collection timeouts, circuit breakers, adaptive polling, and
  independent failure states for every collector.
- Avoid unbounded text scans and prove query plans on representative
  customer SQL Server datasets with production-like concurrency.

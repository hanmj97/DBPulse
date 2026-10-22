# DBPulse v0.13 — Query CPU and optional SQL Server CPU percentage

## Motivation

DBAs need elapsed duration, logical reads, execution count and CPU information.
**These are two fundamentally different CPU metrics**:

1. **Query CPU time (ms):** Query Store aggregates
   `avg_cpu_time` (microseconds) normalized to milliseconds, grouped per
   `query_id + plan_id`. Existing DBPulse collector already captures
   `avgCpuMs` and `executionCount`.
2. **SQL Server instance process CPU share (%):** a host-level diagnostic
   snapshot indicating SQL Server process CPU relative to host capacity,
   across **all databases**. It is NOT a CPU percentage for DBPulseLab or
   an individual SQL statement. Other host processes contribute to
   host CPU utilization.

The Query Tuning Advisor row now shows:
average elapsed ms, average CPU ms, aggregate executions, logical pages.
The expanded row and Query Store table show **estimated total CPU ms =
avgCpuMs * executionCount**. This estimate is for the current *Query Store
collection query's aggregated runtime interval records*; executionCount
is not the number executed in the latest 60/120-second collection cycle.
Query Store capture/flushing can lag.

## Optional CPU percentage (lab only)

Source: latest `RING_BUFFER_SCHEDULER_MONITOR` SystemHealth XML
`ProcessUtilization` and `SystemIdle` read from
`sys.dm_os_ring_buffers` combined with `sys.dm_os_sys_info.ms_ticks`.

**CAUTION:** Microsoft documents `record` content of
`sys.dm_os_ring_buffers` as informational/internal and not
guaranteed stable. This is an experimental best-effort LOCAL-LAB source,
NOT a production-supported customer CPU metric. The UI labels the
scope clearly and shows unavailable / permissions missing rather than
inventing a number. A supported per-host metrics agent or cloud
monitoring provider should replace this implementation before a
real multi-customer service.

### Guardrails

- **Off by default**, zero extra SQL unless operator explicitly enables.
- Local `.env`: `DBPULSE_SQLSERVER_CPU_ENABLED=true`.
  Optional `DBPULSE_SQLSERVER_CPU_INTERVAL_MS=60000` (60s).
- New collector independent from existing Query Store (60s/120s)
  and DMV (5s) collectors.
- One read-only single-row SQL Server query, maximum 4s statement timeout,
  no text, parameters, DB DML, config changes, stored plans or credentials
  leave SQL Server.
- SQL Server query contains a DBPulse internal marker and a structural
  source fingerprint; never hardcode database-specific Query ID/Plan ID.
- `/api/v1/targets/local-mssql/instance-cpu` only reads the latest
  in-JVM sample; browser polling every 15s **does not query SQL Server**.
- If the ring-buffer sample is >180s old, malformed, has an invalid
  percentage, access is denied, or the diagnostic is unsupported,
  the CPU percentage is **null** and status is UNAVAILABLE or
  PERMISSION_DENIED.
- Read-only API returns `status,scope,source,sqlProcessCpuPercent,
  hostBusyCpuPercent,collectedAt,ringBufferAgeMs,limitation`.
  Cache-Control: no-store.

### Scope / permissions

SQL Server 2019 and earlier typically require `VIEW SERVER STATE`;
SQL Server 2022+ `VIEW SERVER PERFORMANCE STATE` to query server DMVs.
These are **server-level privileges**, and DBPulse does not attempt to
grant or elevate them. If the monitoring login is not already entitled,
leave this optional CPU card unavailable; never make it `sysadmin` just
to show a CPU percentage.

The API does not expose the target's connection credentials or raw DMV
XML. No CPU data persisted to DBPulse PostgreSQL in this phase.

## Local validation

```powershell
# Confirm current work before updating the remote feature branch.
cd D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch feature/monitoring-overhead-v0.13
git pull --ff-only origin feature/monitoring-overhead-v0.13

# Add in local .env (not GitHub):
# DBPULSE_SQLSERVER_CPU_ENABLED=true
# DBPULSE_SQLSERVER_CPU_INTERVAL_MS=60000

# Recreate backend only; data volume remains intact.
docker compose up -d --build --force-recreate backend

# Check opt-in CPU telemetry. Initial state may be INITIALIZING for ~20s.
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/instance-cpu" |
  Format-List status,scope,sqlProcessCpuPercent,hostBusyCpuPercent,
    collectedAt,ringBufferAgeMs,limitation

# Existing Query Store statistics and overhead guard must remain online.
Invoke-RestMethod "$base/query-performance/status" | Format-List
Invoke-RestMethod "$base/query-performance/overhead" |
  Format-List mode,effectiveIntervalMs,skippedCycles
```

In React `http://127.0.0.1:5174/#query-performance`, verify:
- **SQL Server CPU 사용률 — 인스턴스 전체** panel exists separately
  from Query Store query CPU times.
- The card shows process % and host % or an **explicit unavailable state**.
- Query Advisor row displays four separate metrics and the lower table
  shows `CPU 총량 추정` and CPU sorting.
- Query/Plan ID filtering, on-demand SQL text, Source filtering,
  Plan Comparison and Monitoring Overhead Guard work.
- In DBPulse source-only mode, CPU sampler's internal SQL is categorized
  `DBPULSE_PATTERN`, never customer tuning advice.
- Disabling flag again returns DISABLED and never polls SQL Server.

## Honest limits

A SQL Server instance can host many databases simultaneously. Its process
CPU usage cannot be attributed as a precise single-database percentage
without separate workload attribution. A Query Store CPU time total is
**not** the CPU utilization percentage. No proof of query improvement or
monitoring safety follows from any one CPU snapshot.

Production integration must use tested supported host OS CPU metrics,
or cloud SQL performance metrics with explicitly defined scope,
tenant isolation, permissions and collector overhead budgets.

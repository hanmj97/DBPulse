# Phase 14 proposal — user-registered SQL Server targets and CPU attribution

> Proposal only, not implemented by v0.13 and not part of PR #12's
> functional release gates. Keep v0.13 single local target unchanged.

## UI contract

A signed-in DBPulse user opens **대상 서버 > 새 데이터베이스 등록**.
Fields: user-facing connection name; DBMS/provider; host (or supported
private agent); port; **database name**; DB login; secret; strict TLS
certificate verification. Separate actions:

1. Validate field formats/endpoint allowlist under backend policy.
2. Test credentials with timeouts and **non-mutating** queries only.
3. Explain minimal read permissions and optional DMV/Query Store coverage.
4. Store a target for that authenticated owner/tenant, with sensitive
   secrets encrypted using a managed secret store/KMS (never GET echo).
5. Expose only safe target metadata (id, `name`, `databaseName`,
   server type/scope, health, timestamps) through authorized APIs.
6. Let user select a target; every Overview, incident, CPU, Query Store
   and Wait request must be tenant-authorized and scoped to that target.

Never use a browser-to-database connection; the browser only calls
authenticated DBPulse APIs. No SQL credentials in localStorage, browser
URL, notifications or logging. Use CSRF/authz/session controls, rate
limits, target ownership, SSRF/egress restrictions, connection ceilings
and certificate identity validation. A cloud deployment cannot assume
it can reach a private user SQL Server; plan secure private agent/
customer-approved networking, **never** direct unauthorized probing.

## Data field semantics

- `target.name` = friendly server connection label.
- `target.databaseName` = actual database name given and verified.
- Current v0.13 local implementation already returns these target
  fields from the configured local MSSQL properties; there is NO
  browser target-registration functionality yet.
- Overview MONITORING TARGET should emphasize
  `target.databaseName`; the server connection label stays a
  secondary descriptive field.
- Graph subtitle should identify `target.databaseName` as the
  **database whose 5-second DMV requests are filtered**.
- Existing `GET /targets/{id}/instance-cpu` reports
  `sqlProcessCpuPercent` for the **whole SQL Server process/
  instance, across all databases**. It is not a per-database metric.
  Its legend must retain the "instance/process-wide" scope even
  when the selected DB is listed elsewhere.

## CPU metrics per DBMS (do not manufacture a DB CPU%)

SQL Server installed locally/on-prem:
- There is no guaranteed native exact per-database share of the
  total SQL Server CPU counter for arbitrary workload types.
- Per-database **recorded query CPU time** can be reported in ms per
  collection interval from that database's Query Store runtime
  aggregation. Query Store time columns are microseconds; convert
  correctly, handle overlapping intervals, double counting,
  plan/execution window, attribution and coverage. Label it
  "해당 DB의 Query Store 쿼리 CPU 시간(ms / 측정 구간)".
- Optional sampled `sys.dm_exec_requests` by `database_id` can
  inform **active request counts and in-flight CPU time (ms)** but
  not a precise per-DB CPU percent. Completed work can be missed.
- Background work, parallel query CPU, system queries, compilation
  and workloads omitted by Query Store prevent a guarantee of
  DB CPU % on shared instances. Only after independent evidence
  and a documented approximation methodology may DBPulse present
  estimated attribution with an explicit label, NEVER as a
  verified server CPU percentage.

Azure SQL Database (different managed platform):
- `sys.dm_db_resource_stats.avg_cpu_percent` reports database
  CPU use relative to its **service tier compute limit**, not the
  percentage of physical host SQL Server CPU. Support as a
  **provider-specific** collector and distinct metric; permissions,
  SKU limits and availability require separate tests.
- Do not use this Azure-only view as if it applied to the local
  SQL Server Developer/on-prem deployment.

## UI future recommendation

Show a metric selector with explicit units/scopes:
1. "인스턴스 CPU (%)" for supported instance measurements.
2. "선택 DB의 쿼리 CPU (ms/구간)" backed by Query Store, otherwise
   `UNAVAILABLE` or `PERMISSION_DENIED`, never invented 0%.
3. For Azure SQL Database only, a dedicated
   "DB CPU (% / 서비스 계층 한도)" series.

These are separate axes/series. Do NOT overlay query CPU time (ms)
on an instance CPU (%) axis without proper scaling and labeling.

## Release boundary

Finish v0.13 UI/regression gates first. Build multi-user auth,
target registration, secret storage, permission enforcement, secure
networking, provider-specific CPU statistics, and isolation tests
in later phases with explicit migration/review. Do not grant
`sysadmin` to the monitoring login or enable a client-provided
arbitrary SQL runner.

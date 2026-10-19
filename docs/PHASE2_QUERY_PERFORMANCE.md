# DBPulse — Phase 2 / Query Performance Analyzer v0.2.0

## Why this is a PRODUCT feature

A busy DB may execute 100-200+ SQL statements per second; sampling `sys.dm_exec_requests` every 5 seconds does not measure that throughput or capture all fast completed queries.

Phase 2 begins read-only **Query Store** analysis of completed statements using query_id + plan_id. It is the foundation for root-cause investigation, regression detection, and query tuning recommendations. It is not a load-test playground, and it does not complete the tuning/auto-recovery roadmap yet.

## Where data flows

SQL Server `DBPulseLab` Query Store -> `MssqlQueryStoreCollector` (30s) -> `QueryPerformanceRepository` -> PostgreSQL `query_performance_snapshots` -> REST -> React "Query Performance Analyzer".

The original 5s active-request collector runs independently; a Query Store error does not change its `collectorStatus`.

## Confidentiality boundary (v0.2.0)

- Only `query_id`, `plan_id`, execution count, averaged duration/CPU/logical reads, and timestamps are collected.
- **Do not collect** SQL source text, literal parameters, plan XML, sensitive row data, or a full connection string in this version.
- Query Store must be enabled by a human DBA; DBPulse does not run administrative SQL on the monitored instance.
- All endpoints are **local only**; customer access and login do not exist yet.

## Metric semantics

| Field | Meaning |
|---|---|
| `executionCount` | Query Store count across **runtime rows whose last execution was within last 15m**; cumulative and may cover an interval starting before the lookback |
| `avgDurationMs` | Execution-count-weighted mean of Query Store `avg_duration` values (microseconds converted to milliseconds) |
| `avgCpuMs` | Weighted average CPU time in milliseconds |
| `avgLogicalReadsPages` | Weighted average logical reads, in 8KB pages |
| `lastExecutedAt` | Last execution time from Query Store |
| `diagnosisCode` | Deterministic numeric triage signal, NOT verified root cause |

Query Store flushes statistics asynchronously. The v0.2 analyzer is **near-real-time**, not a per-event profiler. The UI never labels counts as "this polling interval QPS".

Status `ONLINE` means Query Store succeeded. `QUERY_STORE_DISABLED` means enable it in DBPulseLab. `OFFLINE` means Query Store read failed; the 5s DMV monitor can remain `ONLINE`.

## Roadmap continuity

- Phase 2.1: per-query plan/Wait/Blocking correlation, execution baseline and regression confidence, redacted SQL preview behind explicit authorization.
- Phase 3: users/roles, managed targets, safe agent and encrypted credentials.
- Phase 4-5: incident and recovery policy engines.
- Phase 6: actual explainable, test-validated SQL tuning recommendations.
- Phase 7: dedicated load generator at 100-200 QPS, 10s bursts and reproducible Blocking/deadlocks.

## Important constraints

This phase is local-dev only, tested by the user's local build. Do not directly attach it to a production/customer DB. Query Store `QUERY_CAPTURE_MODE=ALL` in the SQL script is intended **only** for the dedicated lab, where storage is capped to 256MB. Real DBs require per-workload Query Store capture/retention decisions.

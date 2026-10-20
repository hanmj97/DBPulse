# DBPulse v0.12 — Query Store internal query exclusion for SQL text viewer

## Problem observed in DBPulseLab

After a user selected **SQL 보기**, `Query #252 / Plan #197` surfaced in
the main Query Store list. Its stored SQL included:

`SUBSTRING(qt.query_sql_text, 1, @P0) AS captured_text`

`DATALENGTH(qt.query_sql_text) AS original_bytes`

This is the very query used by the v0.12 SQL-text drawer's on-demand
`MssqlQueryTextCollector`. Its last_execution_time is more recent than the
user's `SELECT * FROM dbo.DeadlockDemo`, so recent-first sorting is working;
the missing exclusion classification is the bug.

## Identifiers are NEVER portable

SQL Server Query Store Query ID and Plan ID are **per monitored database
and Query Store state**, and may differ across installations, databases and
recompilation/plan evolution. DBPulse MUST NOT hide hardcoded #252/#197 or
historical IDs #72, #95, etc.

The classifier runs as part of each SQL Server-side Query Store polling query
for **that target database**, before converting candidate results into
PostgreSQL snapshots. Thus `source_category` is computed dynamically from
the captured query text on each target, not a static identifier list.

### Strategy implemented

1. All current DBPulse MSSQL internal SQL statements now carry a
   `/* DBPULSE_INTERNAL_<COLLECTOR>_V1 */` tag in SQL text. This is purely
   descriptive; it is not a secure/authoritative client identity.
2. Where Query Store preserves the comment, the collector checks
   `CHARINDEX(N'DBPULSE_INTERNAL_', qt.query_sql_text) > 0`.
3. Query Store may strip comments, normalize whitespace, or parameterize
   SQL. Existing fallback SQL-structure fingerprints therefore remain,
   including a **new** SQL-text collector fingerprint requiring all of:
   `sys.query_store_query_text`, `SUBSTRING`, `captured_text`,
   `DATALENGTH`, and `original_bytes`.
4. Matching query/plans are classified as `DBPULSE_PATTERN`, otherwise
   `OTHER_OR_UNKNOWN`. SQL Server performs this match; raw text **does not
   leave SQL Server during ordinary polling**, though the explicitly
   opted-in SQL drawer still displays it on request.
5. The React default `DBPulse 수집 패턴 제외` continues to remove only
   `DBPULSE_PATTERN`. `전체 수집 쿼리` and
   `DBPulse 수집 패턴만` remain available.

### Limitations

- Query Store **does not record the source client application for each
  stored query**. `applicationName=DBPulseQueryText` helps inspect active
  SQL sessions but is not stored as a Query Store per-query field.
- Matching by marker or statement shape is heuristic. A user or third-party
  tool executing matching SQL could be hidden; an unfamiliar internal
  query could appear among user queries. Neither result proves origin.
- This is not a complete/cryptographically authenticated "business SQL only"
  guarantee; stronger client attribution needs a separate recording design
  and an authorization model before external customer onboarding.
- No Query/Plan ID exclusion list, no "remove all SELECT" rule, no automatic
  SQL text collection for display, and no changes to existing business data.

## Local verification

1. Sync `feature/query-plan-comparison-v0.12`, rebuild backend and leave
   the PostgreSQL volume intact.
2. Use `DBPulse / 쿼리` with SQL original viewer enabled locally,
   select a *real* Query/Plan ID, click **SQL 보기**.
3. Wait for one successful Query Store poll (~30 seconds), then query:

```powershell
# Only metadata; do NOT fetch/query actual SQL text into PowerShell
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$report = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$report.candidates |
  Select-Object -First 20 queryId,planId,sourceCategory,lastExecutedAt |
  Format-Table
```

4. Switch to **DBPulse 수집 패턴만**. The SQL text lookup may be
   tagged `DBPULSE_PATTERN` with whatever Query ID / Plan ID your
   database assigns it. Switch back to default: it should disappear.
5. In **DBPulseLab** only, run
   `sql/08_query_text_exclusion_verification_lab_only.sql` to see
   identifying metadata and signal names without exposing SQL contents.
6. User SQL like `SELECT * FROM dbo.DeadlockDemo` should remain in default
   when captured and within the bounded latest 15-minute Query Store window.

Java source-level tests verify all current collectors carry identifiers
and the new fallback is present; only an actual SQL Server Query Store
integration test can confirm capture/normalization behavior.

**Important:** The previously persisted `source_category` history for
old snapshots is not modified in place. A new successful collection
writes the new classification. The frontend uses latest sample only.

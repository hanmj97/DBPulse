# DBPulse v0.9.0-alpha — SQL Tuning Advisor, phase A

## Product scope
The first Tuning Advisor phase **triages SQL Server Query Store aggregate measurements**.
It is a **review assistant**. It does not yet read SQL text, query parameters,
plan XML, operator cardinalities, missing-index DMV results, or exact index
definitions. Consequently it **cannot** assert that a Key Lookup, Scan,
Sort or other plan operator is present; it only tells an authorized DBA what
to inspect in SSMS.

No SQL Server schema, credentials, permissions, or running transactions are
changed. Existing Query Store poll every 30 seconds is reused; the advisor
queries already persisted PostgreSQL snapshots only.

## API
- `GET /api/v1/targets/local-mssql/query-performance/advisor?limit=10`
- `limit`: 1..20; default 10. Unknown target: 404. Out-of-range limit: 400.
- Returns `status`, `collectedAt`, `source=QUERY_STORE_AGGREGATES`,
  `coverage`, `errorCode`, `candidates`.
- Each candidate contains `queryId`, `planId`, `priority`,
  `diagnosisCode`, `lastExecutedAt`, `metrics`, `actions`, and
  `limitation`. No raw SQL text or plan XML.
- If Query Store is OFFLINE, INITIALIZING, NOT_CONFIGURED,
  QUERY_STORE_DISABLED or last successful sample is >2m old, returns
  status with an **empty** list, never stale findings presented as live.

## Candidate priority (heuristic, not verified root cause)
- HIGH: average duration >= 500 ms AND (average CPU >= 200 ms OR
  logical reads >= 1,000 pages).
- MEDIUM: any one of those thresholds reached.
- OBSERVE: no thresholds exceeded; not proof of healthy SQL.
- Sort HIGH first, then MEDIUM, then OBSERVE; high duration first
  within priority. Max Query Store top 20 pairs per poll.

Suggested review actions are evidence-driven but **conditional**:
- High reads -> inspect actual plan access paths, scan vs seek, key lookups.
- High CPU -> inspect operators (sort, hash/join, parallelism).
- High duration -> check contemporaneous Wait/Blocking and real plans.
- If no thresholds: capture a longer baseline before concluding.

Never auto-generate an unverified CREATE INDEX or perform KILL/DDL.

## Query Store sampling limitations
- Existing SQL query chooses Query Store runtime-stat rows with
  `last_execution_time >= now()-15m`, then aggregates all executions
  represented by those matching rows. Therefore executionCount is
  **not** the number of executions exactly within the last 15 min,
  and not the count since the last 30-second poll.
- Query Store may flush asynchronously and be unavailable/read-only.
- Only top 20 query-plan pairs ranked by weighted duration are available.
- Average duration/CPU statistics alone do not identify cause or guarantee
  changes would improve performance. Different plans of the same query
  are separate candidates.
- The UI's action suggestions are not based on inspection of query text
  or XML plan, so they must not be presented as confirmed findings.

## Local verification

```powershell
# Go to DBPulse project and check uncommitted work
cd D:\Projects\dbpulse-platform
git status --short

# Fetch and checkout the v0.9 feature branch
git fetch origin
git switch --track origin/feature/sql-tuning-advisor-v0.9

# Rebuild the backend to register /advisor (keep PostgreSQL volume)
docker compose up -d --build --force-recreate backend

# Verify backend boot, with no accidental credential output
docker compose ps
docker compose logs --tail=50 backend

# Query Store / Advisor API
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/query-performance/status" | Format-List
$advisor = Invoke-RestMethod "$base/query-performance/advisor?limit=10"
$advisor | Select-Object status, collectedAt, source, errorCode
$advisor.candidates |
  Format-Table queryId, planId, priority, diagnosisCode
if (@($advisor.candidates).Count -gt 0) {
  $advisor.candidates[0].actions |
    Format-Table code, title, description -Wrap
}
```

Open React `http://127.0.0.1:5174/#query-performance`.
The advisor panel should be above the old Query Store table. Click
one candidate, verify data-backed action steps and limitations, and
try HIGH/MEDIUM/OBSERVE filters. Toggle Navy/Midnight Dark.

If there are no candidates, first ensure Query Store is collecting. Use
the previously verified **DBPulseLab-only** query script:
`sql/05_query_store_demo_lab_only.sql`. Wait 30-60 seconds for aggregation.
This tool **does not** need extra privileges or any production workload.

## Regression checks
- Metrics, Incidents, Investigation + Wait evidence, Blocking Chains,
  Deadlocks, standalone Wait Diagnostics, and Query Store remain functional.
- No plaintext SQL/credential/parameter or plan XML is exposed by API.
- Query Store disabled or stale samples show empty candidates and
  a visible data status rather than an unqualified zero/high priority.
- Advisor never writes to MSSQL. It reads existing PostgreSQL snapshots.
- CI Java tests and React build pass; SQL Server + PostgreSQL integration
  must be confirmed locally before merging.

## Next phase
Introduce an **explicitly opted-in** and strictly bounded *structural
execution-plan evidence* collector (approved metadata only), backed
by actual SQL Server tests, so scan/lookup/operator detection can be
defensible. Keep default privacy controls and review all access rights
and CPU impact. Any actual index suggestion must be accompanied by
real plan evidence, current index definitions, estimated impact,
regression risks, and operator approval.

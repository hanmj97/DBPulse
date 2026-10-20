# DBPulse v0.10.0-alpha — Safe Query Store Plan Operator Evidence

## Purpose
Extend v0.9 SQL Tuning Advisor with **verified structural execution-plan
operators**, rather than asserting that a Scan, Lookup, Hash or Sort operator
exists based only on duration/CPU/logical-read aggregates.

**On demand only.** A user expands a Query Store candidate and clicks
**연산 정보 확인**. No new background poll, no DDL, no plan forcing.

## Security and data boundaries

The plan contains SQL statement text, predicates, index/table names and
potentially sensitive literals. Its **raw XML is NEVER transmitted** to the
Java backend, React UI or PostgreSQL. SQL Server performs on-server
`TRY_CONVERT(xml, query_plan)` and XQuery
`//RelOp/@PhysicalOp`. Only coarse, allowlisted **physical operator
types and occurrence counts** are returned to JDBC.

- Input is parameterized `queryId` / `planId`, selected from the
  latest stored top-20 query-plan pairs for the current target.
- XML is parsed in SQL Server only, *after* a separate metadata check.
- Plan text limit: 1,000,000 bytes of SQL Server `DATALENGTH(query_plan)`.
- At most 101 operator nodes fetched to detect a >100-node truncated result.
- Unrecognized/blank values become `OTHER_OPERATOR`; never return
  raw, unrecognized names.
- Query collection credentials remain unchanged; same read-only
  monitoring login, same SQL Server Query Store permissions.
- No sensitive logging, no persistence of plan-derived observations,
  and no extra SQL Server rights are granted.
- Local-only API. Authentication, tenant isolation, rate limits, audit
  logging and workload budget enforcement are prerequisites for any
  customer-facing rollout.

## API

`GET /api/v1/targets/local-mssql/query-performance/plan-evidence/{queryId}/{planId}`

Before reading MSSQL, the endpoint checks:
- the configured target ID,
- positive query/plan IDs,
- a fresh (<2 minutes) Query Store successful collection,
- and the exact query/plan pair appears in that latest top 20.

Success example (illustrative):

```json
{
  "queryId": 51,
  "planId": 70,
  "status": "AVAILABLE",
  "parallelPlan": false,
  "forcedPlan": false,
  "planBytes": 25100,
  "sampledOperators": 3,
  "operators": [
    {"type":"KEY_LOOKUP","count":1},
    {"type":"INDEX_SEEK","count":2}
  ],
  "interpretation": "..."
}
```

Possible statuses:
- AVAILABLE — 1..100 operator nodes classified.
- OPERATOR_LIMIT_REACHED — only first 100 nodes shown; NOT complete counts.
- PLAN_TOO_LARGE — safety limit exceeded; no XML parsing attempted.
- PLAN_NOT_FOUND / PLAN_XML_UNAVAILABLE — removed/unavailable.
- NO_OPERATORS_OBSERVED — no parseable RelOp found or plan contains none.

Source type is a Query Store **compiled/estimated** plan representation,
not the per-run actual execution plan. Node counts are structural occurrences,
not operator runtime invocation counts. Even a verified Scan or Key Lookup
is not proof of inefficient indexing.

## Local verification

```powershell
# Check and preserve local changes before branch switch
cd D:\Projects\dbpulse-platform
git status --short

# Get latest source
git fetch origin

# Switch to the v0.10 feature branch (or git switch if already present)
git switch --track origin/feature/query-plan-operators-v0.10

# Compile and restart API only; no PostgreSQL volume deletion
docker compose up -d --build --force-recreate backend

# Check backend health and Query Store collection
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/health"
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
Invoke-RestMethod "$base/query-performance/status" | Format-List

# Obtain one *real*, currently sampled pair; no hardcoded plan IDs
$report = Invoke-RestMethod "$base/query-performance/advisor?limit=20"
$report.candidates | Format-Table queryId, planId, priority
if (@($report.candidates).Count -gt 0) {
  $candidate = @($report.candidates)[0]
  $plan = Invoke-RestMethod "$base/query-performance/plan-evidence/$($candidate.queryId)/$($candidate.planId)"
  $plan | Format-List queryId, planId, status, parallelPlan, forcedPlan, planBytes, sampledOperators
  $plan.operators | Format-Table type, count
}
```

Open `http://127.0.0.1:5174/#query-performance`, select a Query
Store candidate and click **연산 정보 확인**. Verify the displayed IDs,
operators and safety notes agree with the API. Both DBPulse Navy and
Midnight Dark must remain readable.

If the query/plan XML is unavailable or exceeds the size limit, the UI
should display the corresponding reason, not a fake plan.

## SQL Server lab check

Run the previously verified **DBPulseLab-only** Query Store test
`sql/05_query_store_demo_lab_only.sql` if no current candidates exist.
Choose a plan whose actual Query Store plan contains recognizable operators
and compare operator types in SSMS (admin) with the sanitized DBPulse
results. If SQL Server returns 503, review backend logs and permissions:
do **not** expand privileges without explicit inspection.

## Known limitations
- `sys.query_store_plan.query_plan` is Query Store ShowPlan XML,
  and plan operators may be absent, truncated or differ by SQL Server version.
- XML parsing can add CPU load on SQL Server: per-request limits reduce,
  but do not eliminate, this cost. Do not use this local prototype on live
  customer systems without a rate-limited, audited collector architecture.
- No query text, parameter values, predicate, object/index names,
  estimated/actual rows, operator cost percentages, warnings or missing-index
  requests are returned. Detailed tuning recommendations remain tentative.
- No automatic index creation, plan forcing, killing sessions or recovery.

## Validation / merge
GitHub Actions must pass Maven tests and React production build, then local
SQL Server 2025/Query Store integration is verified by the user. Keep
PR in Draft until then. v0.9.0 main is the stable baseline.

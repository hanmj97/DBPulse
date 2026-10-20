# DBPulse v0.12.0-alpha — Same-query Plan Comparison

## Purpose
Enable a DBA to select a real Query ID and Plan ID and compare already
captured Query Store metrics across multiple plans for the **same SQL Server
Query Store query_id**. This is a read-only review tool and does **not**
assert that a plan regression occurred.

Only *already persisted*, successful latest DBPulse Query Store snapshots
are used. No new query text or plan XML collection, no MSSQL write operation
or additional monitor permissions. 15-minute Query Store aggregated runtime
intervals can overlap and include different parameters and data sizes.

## API

`GET /api/v1/targets/local-mssql/query-performance/plan-comparison/{queryId}/{planId}`

- Target and selected query_id / plan_id validated.
- Requires Query Store collection status ONLINE or READ_ONLY and last sample
  within 2 minutes. No previous snapshot treated as current.
- Selected plan must exist in the last bounded top-50 rows (40 general
  patterns + 10 matched DBPulse collector patterns), otherwise HTTP 404.
- Peer plans must have the **same query ID** and a different Plan ID in
  that exact sample. Arbitrary cross-query comparisons are rejected by
  construction. No request to MSSQL is issued by this API.

Response fields:
`queryId,selectedPlanId,sampleCollectedAt,status,selected,comparisons,limitation`.
`selected` and each peer's `metrics` include executionCount,
avgDurationMs, avgCpuMs, avgLogicalReadsPages, lastExecutedAt,
sourceCategory. A comparison includes `durationRatioToSelected`,
`durationRatioLabel`, `assessment` and `nextCheck`.

Assessments:
- `ONLY_ONE_PLAN_SAMPLED` if only the selected plan is in this recent
  bounded sample. NOT proof the query never had other plans.
- `INSUFFICIENT_SAMPLES` if either plan has <5 Query Store aggregated
  executions (this is not a precise independent per-run sample count).
- `SMALL_DURATION_BASELINE` if either average is <5ms; ratio suppressed
  because tiny timing baselines are unstable.
- `MEAN_DURATION_DIFFERENCE` if both means >=5ms, both counts >=5 and
  selected-to-peer ratio >=1.5x or <=1/1.5x. Observation, not regression.
- `NO_LARGE_MEAN_DIFFERENCE` otherwise.

The ratio is **peer mean duration / selected mean duration**.
1.50x means peer's captured mean duration is 50% higher, NOT that the
peer regressed by 50% due to the change in plan.

## UI
Open DBPulse / 쿼리 -> expand a candidate -> click
**실행계획별 성능 비교**. The adjacent on-demand
**연산 정보 확인** action continues to work independently.
The comparison panel uses DBPulse Navy/Midnight Dark theme variables.

By default, known DBPulse collector SQL patterns are hidden. Selecting
**전체 수집 쿼리** can reveal other candidate pairs. Query ID / Plan ID
search, priority tabs, recent sort and Incident/Wait/Deadlock features are
unchanged.

## Local acceptance check

```powershell
# Confirm the feature branch and avoid overwriting local modifications
cd D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch --track origin/feature/query-plan-comparison-v0.12

# Rebuild only backend; PostgreSQL volume and MSSQL DB remain untouched
docker compose up -d --build --force-recreate backend
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/health"

# Actual Query Store candidates only: no guessed IDs
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$advisor = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$advisor.candidates |
  Format-Table queryId,planId,priority,lastExecutedAt,sourceCategory

# Prefer a real query ID with >=2 current plans if available
$multi = $advisor.candidates |
  Group-Object queryId |
  Where-Object { $_.Count -gt 1 } |
  Select-Object -First 1
if ($null -ne $multi) {
  $candidate = @($multi.Group)[0]
} else {
  $candidate = @($advisor.candidates)[0]
}

if ($null -ne $candidate) {
  $compare = Invoke-RestMethod "$base/query-performance/plan-comparison/$($candidate.queryId)/$($candidate.planId)"
  $compare | Format-List queryId,selectedPlanId,status,sampleCollectedAt
  $compare.selected | Format-List
  $compare.comparisons | Format-Table planId,assessment,durationRatioLabel,nextCheck -Wrap
}
```

If no pair appears or only one plan per query is captured, that's a
valid, explicit limitation. DO NOT force a query plan or change indexes
simply to make the demo produce two plans. Use already existing
Query Store multi-plan test data in DBPulseLab if available.

**Note:** The `git switch --track` above is only for first local
checkout of this branch. For an existing local branch use
`git switch feature/query-plan-comparison-v0.12` and then
`git pull --ff-only origin feature/query-plan-comparison-v0.12`.

## Security and deployment
- Bounded, single-target, local-only API until authentication, multi-tenant
  authorization and rate-limiting exist.
- No SQL text, literals, execution plan XML, object names, passwords,
  parameters or indexing DDL exposed or stored.
- No automatic remediation.
- This is a pairwise comparison of Query Store aggregates, not an
  experiment with equivalent runtime conditions; avoid promising speedups.

## Release gate
Java unit tests and React build must pass GitHub CI, and the user must
verify the endpoint plus browser panel using actual DBPulseLab Query Store
data. Keep the PR as Draft until that local integration check.

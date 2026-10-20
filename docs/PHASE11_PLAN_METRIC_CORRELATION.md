# DBPulse v0.11.0-alpha — Query Store × Plan Operator Correlation

## Goal

Bridge the v0.9 *numeric-only* Tuning Advisor and v0.10 *structural operator*
inspector to help DBAs **review combined evidence for one specific Query ID
and Plan ID** without exposing SQL text or automatically modifying SQL Server.

This is a **read-only, user-triggered investigation**, NOT AI-confirmed root
cause analysis, operator runtime profiling, or an index recommendation engine.

## User workflow

1. Visit `DBPULSE / 쿼리`, with the existing source-category selector
   defaulting to **DBPulse 수집 패턴 제외**.
2. Sort newest-first (existing v0.10 feature), optionally enter precise
   Query ID and/or Plan ID.
3. Expand one real candidate. Review persisted Query Store mean duration,
   CPU and logical reads.
4. Click **연산 정보 확인** (the only action that contacts SQL Server to parse
   a ShowPlan XML).
5. View verified physical operator names/counts plus the new
   **성능 지표 × 실행계획 연계 분석** cards.
6. Cards include measured evidence and a safe, suggested next investigation
   step in SSMS. NO DDL, index creation, plan forcing or automatic recovery.

Both DBPulse Navy and Midnight Dark remain available and unchanged.

## API: additive, backward compatible

Existing endpoint:

`GET /api/v1/targets/{id}/query-performance/plan-evidence/{queryId}/{planId}`

Previously returned plan fields `queryId`, `planId`, `status`,
`collectedAt`, `parallelPlan`, `forcedPlan`, `planBytes`,
`sampledOperators`, `operators`, and `interpretation` remain at
their original top-level JSON locations.

New `correlation` object includes:
- `status`: `EVIDENCE_AVAILABLE`, `PARTIAL_EVIDENCE`,
  `PLAN_UNAVAILABLE`.
- `sampleCollectedAt`: UTC timestamp of the exact persisted Query Store
  snapshot matched to the plan ID.
- `findings`: list with `code`, `kind` (`REVIEW` or `INFO`),
  `title`, `evidence`, and `nextCheck`.
- `limitation`: explicit noncausal interpretation and privacy boundary.

No additional JDBC query is needed for metrics. The endpoint already loads
the latest persisted top-50 query/plan set to authorize plan inspection; v0.11
**reuses that exact row**. It refuses to correlate mismatched IDs.

### Rules (transparent, heuristic)

The thresholds are the **same** as the current Tuning Advisor:

| Metric / verified physical operator | Correlation |
| --- | --- |
| avg logical reads >= 1,000 pages + Key/RID Lookup | `READS_WITH_LOOKUP` |
| avg logical reads >= 1,000 pages + Table/Index/Clustered Index Scan | `READS_WITH_SCAN` |
| avg CPU >= 200 ms + Sort or Hash Match | `CPU_WITH_SORT_OR_HASH` |
| avg duration >= 500 ms | `DURATION_REVIEW`; review Wait/Blocking |
| DML Insert/Update/Delete operator present | `DML_OPERATOR_OBSERVED` (INFO) |
| none of the rules match | `NO_THRESHOLD_CORRELATION` (INFO) |

A scan/lookup/hash/sort may be completely appropriate. No rule claims the
observed operator caused high IO/CPU/time, proves inefficiency, or suggests a
specific index. Low metrics do not guarantee a query is healthy.

### Incomplete / missing plans

- `AVAILABLE`: structural operators were observed under 100 nodes.
- `OPERATOR_LIMIT_REACHED`: only the first 100 nodes were sampled.
  Correlation status `PARTIAL_EVIDENCE` visibly warns that *missing*
  operators cannot be ruled out. Existing sampled operators can still be
  reported as observations.
- Plan missing, too large (>1MB), XML unavailable, or no operators:
  `PLAN_UNAVAILABLE`, **no fabricated operator correlations**.

Query Store aggregates can cover executions spanning runtime-stat intervals.
ExecutionCount is not the per-poll count, and these are **compiled/estimated
structural** plan operators rather than Actual Rows/operator CPU. The Query
Store snapshot and plan retrieval can occur at different timestamps; their
matching Query/Plan IDs are necessary but do not establish a single live
execution's runtime.

## Security

- Single locally configured MSSQL target; matching `queryId/planId` must
  appear in the current fresh Query Store snapshot.
- Reuses DBPulse v0.10's on-demand, bounded SQL Server-side XML parsing.
- Does not collect raw SQL, bind values, predicate/object/index names,
  SQL Server actual plan XML, or login credentials in the API response.
- Analysis is ephemeral — no new PostgreSQL columns, stored raw plans,
  additional SQL Server privileges or background collection.
- No SQL text filtering changes, no automatic recovery, no DDL.
- Production use still requires login/tenant authorization, collector
  auditing, rate limits and careful SQL Server workload controls.

## Test instructions — local DBPulseLab only

```powershell
# Check uncommitted work before changing branch
cd D:\Projects\dbpulse-platform
git status --short

# Obtain v0.11 branch and switch once local work is safe
git fetch origin
git switch --track origin/feature/plan-metric-correlation-v0.11

# Build Java API; no data volume deletion or extra SQL privileges
docker compose up -d --build --force-recreate backend

# Verify backend reports phase 11
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/health"

# Find a real Query Store query/plan candidate
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$advisor = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$advisor.candidates |
  Select-Object -First 15 queryId,planId,priority,lastExecutedAt,sourceCategory |
  Format-Table

# Only execute if a real candidate exists
if (@($advisor.candidates).Count -gt 0) {
  $item = @($advisor.candidates)[0]
  $plan = Invoke-RestMethod "$base/query-performance/plan-evidence/$($item.queryId)/$($item.planId)"
  $plan | Format-List queryId,planId,status,sampledOperators
  $plan.operators | Format-Table type,count
  $plan.correlation | Format-List status,sampleCollectedAt,limitation
  $plan.correlation.findings | Format-Table code,kind,title,evidence,nextCheck -Wrap
}
```

Use `http://127.0.0.1:5174/#query-performance` to click the same
candidate and verify the added correlation cards. A typical low-cost
`SELECT * FROM dbo.DeadlockDemo` with one seek/scan operation should
produce INFO-only or empty high-cost correlations, not fake warnings.

Validate both standard themes, Query ID/Plan ID search, recent sort,
DBPulse pattern filtering, and previous Incident/Wait/Blocking/Deadlock
pages still behave as before.

## Release gate

Java unit tests verify correct ID matching, Lookup+reads, Scan+reads,
Sort/Hash+CPU, low metrics, DML observations, truncated plan and unavailable
plan. GitHub React production build checks UI compilation. **Do not merge**
until local v0.11 SQL Server/PostgreSQL/API/browser test confirms the added
JSON `correlation` matches the React UI and does not regress v0.10.

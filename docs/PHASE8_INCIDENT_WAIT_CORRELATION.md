# DBPulse v0.8.0-alpha — Historical Wait Correlation

## Purpose

Extend the **existing incident investigation** to include historical wait
observations from PostgreSQL. This is neither cumulative SQL Server Wait Stats
nor SQL tuning automation. The aim is to show contemporaneous evidence and
sampling limitations.

## API

`GET /api/v1/targets/{id}/incidents/{incidentId}/investigation`

The existing response fields remain unchanged. New `waitEvidence` object:

- `sampleCount`: number of **wait_samples** within the incident time window,
  including zero-wait snapshots.
- `truncatedSampleCount`: number of samples that hit the collector's
  100-wait-request limit.
- `sampledWaitCount`: sum of count-by-category **sampled observations**,
  **not unique waits or total database activity**.
- `categories`: up to ten categories with `sampledCount` and
  `peakWaitMs` from the same time range.
- `recent`: up to 12 session-level point-in-time waits with session IDs,
  Wait Type, coarse Resource Kind, duration and positive blocking SPID if known.

The existing `findings` list also receives entries with explicit
`OBSERVED` versus `LIMITATION` labels.

### Important semantics

- All PostgreSQL queries filter `target_id` and the bounded incident
  investigation interval from `IncidentEvidenceWindow`.
- A session blocked for 15 seconds may appear in three 5s samples.
  These are three **observations** of potentially the same wait.
- No samples -> `WAIT_SAMPLES_UNAVAILABLE` limitation; some zero-wait
  samples -> `WAITS_NOT_OBSERVED`, **not proof** of no wait.
- Any `limit_reached` snapshot -> `WAIT_COLLECTION_LIMIT_REACHED`.
- Wait categories and nearby Deadlocks are not declared causal.
- Existing 7-day wait-sample retention means older incidents may have
  no wait evidence even though the incident itself still exists.
- Raw `wait_resource`, SQL text, parameters, host/login names and
  query-plan XML are not collected in Phase 8.

## Local test plan

```powershell
# Move to DBPulse; check uncommitted changes before switching
cd D:\Projects\dbpulse-platform
git status --short

# Fetch new v0.8 feature branch
git fetch origin
git switch --track origin/feature/incident-wait-correlation-v0.8

# Rebuild API without touching the persistent PostgreSQL volume
docker compose up -d --build --force-recreate backend

# Fetch existing incident IDs as individual elements (PowerShell 5.1 safe)
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$ids = @(Invoke-RestMethod "$base/incidents?limit=20" | ForEach-Object { $_.id })
$ids | Format-Table
if ($ids.Count -gt 0) {
  $id = $ids[0]
  $report = Invoke-RestMethod "$base/incidents/$id/investigation"
  $report.waitEvidence | ConvertTo-Json -Depth 8
  $report.findings | Format-Table code, kind, title, detail -Wrap
}
```

In React at `http://127.0.0.1:5174/#incidents`, select an
Incident and **원인 분석 근거 보기**. The new **Wait 관측 근거** area
shows matching wait categories and session observations, or explicit
missing-data information.

Reproduce Blocking only inside **DBPulseLab** if no recent incident
has retained Wait samples. Verify `LCK_M_X` / category `LOCK`
appears during the same incident window and the UI clearly labels
repeat observations as samples, not distinct wait occurrences.
No KILL, no automatic rollback, no customer DB mutation.

## Verification before merging
- Maven/JUnit CI success and React production build success.
- Existing Investigation response fields unchanged.
- Past incident shows past waits, never live SQL Server snapshots.
- Valid empty evidence on old incidents; no false downtime/root-cause claims.
- Other API endpoints and dashboards still work.
- Local-only until user authentication, tenant isolation and secret storage exist.

## Next
Query Store plan metadata and validated query tuning recommendations, preserving
strict user consent, SQL text redaction and no automatic DDL.

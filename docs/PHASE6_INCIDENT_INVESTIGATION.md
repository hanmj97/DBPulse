# DBPulse v0.6.0-alpha — Incident Investigation Engine

## Scope
A historical, evidence-linked investigation endpoint for persisted incidents.
Existing independent SQL Server collectors and incident state machinery are
unchanged. This is the first part of Phase 6; it does **not** prove root cause.

For each incident, DBPulse correlates target-specific PostgreSQL records:
- Metric snapshots: sample count, maximum blocked and long-running requests,
  maximum observed request duration.
- Up to 12 Blocking Chain relationships in the incident's time window.
- Up to 8 nearby Deadlock Event summaries in that window.
- Machine-generated findings explicitly labeled OBSERVED or LIMITATION.

No SQL texts, bind values, credentials, user identity or raw event XML are used.

## New API
`GET /api/v1/targets/local-mssql/incidents/{incidentId}/investigation`

Response:
- `incident`: existing incident record
- `window`: `from`, `to`, `truncated`
- `metrics`: `samples`, `peakBlocked`, `peakLongRunning`, `peakElapsedMs`
- `blocking`: historical blocked/blocker SPIDs, wait type and time
- `deadlocks`: same-period deadlock event IDs and victim SPIDs where known
- `findings`: observation or data limitation notes

ID is target scoped; unknown incident is 404, invalid ID <=0 is 400.

## Window and limitations
- Resolved: from incident start - 15 seconds to resolution + 15 seconds.
- Open: from incident start - 15 seconds through the current API request.
- Bound investigations to the last ~15 minutes of the incident, exposing
  `window.truncated` where earlier time is excluded.
- Blocking sample retention 7 days; Deadlock event retention 14 days.
- A sample gap or missing Blocking Chain row is NOT proof of no Blocking.
- Different collectors have different timing; XEL flush may lag by seconds.
- Temporal overlap does not establish causation or authorize remediation.

## Local verification
```powershell
# Open the source project
cd D:\Projects\dbpulse-platform

# Check unsaved local work before switching branches
git status --short

# Fetch the latest remote refs
git fetch origin

# Checkout the v0.6 development branch
git switch --track origin/feature/incident-investigation-v0.6

# Rebuild backend without removing PostgreSQL data
docker compose up -d --build --force-recreate backend

# Incident list and investigation for the first available real incident
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$incidents = @(Invoke-RestMethod "$base/incidents?limit=20")
$incidents | Format-Table id, type, status, startedAt

# Pick a real incident ID from the returned list
if ($incidents.Count -gt 0) {
  $incidentId = $incidents[0].id
  Invoke-RestMethod "$base/incidents/$incidentId/investigation" |
      ConvertTo-Json -Depth 8
}
```
Open the Incidents section of local React dashboard at
`http://127.0.0.1:5174/#incidents`. Choose **원인 분석 근거 보기**.

## Test checklist
- Old metrics, Incident, Blocking Chains, Query Store and Deadlock APIs remain good.
- A resolved incident shows *past* observations and not the latest live DMV state.
- COLLECTION_FAILURE never claims SQL Server downtime without proof.
- Correlation window is truncated for long-running incidents and flagged.
- API handles incident IDs not belonging to configured target (404).
- Old incidents past data retention return limitations, not fabricated evidence.
- No actions modify MSSQL data, trigger KILL, restart DB or auto-fix anything.

Next: enriched Wait/lock diagnostics and traceable resolution playbooks with
explicit approvals. Public SaaS requires authentication, tenant isolation,
credential management and a security review.

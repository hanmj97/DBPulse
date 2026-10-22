# v0.13.0-alpha handoff — 2026-10-21

## Status

- Branch: `feature/monitoring-overhead-v0.13`; Draft PR #12; `main` remains separate.
- Latest request/CPU chart split: left DMV **요청 수(건)**, right SQL
  Server **인스턴스 CPU(%)**. Each has an independent scale, selection
  and actual-sample timestamp. No synthetic database CPU% output.
- Operations summary: bounded preview with real Wait categories/current
  alerts and remaining-item links, no independent long scrollbar.
- User has reviewed successive Overview screenshots and on 2026-10-21
  said the UI can be wrapped up and the project can move forward.
  **Interpret as approval of the Overview design direction**, not proof
  that every other release scenario has been manually exercised.

## Confirmed checks

- GitHub Actions **React frontend build/tests** and **Java backend tests**
  passed at commit `13f7496429b1babb8b6719be6d25eab7fb95bea4`.
- Earlier local user report: read-only `tools/check_v013_api_regression_lab.ps1`
  completed successfully. Query Store fixed/adaptive A/B workload and
  observer-cost XE analysis were performed in isolated `DBPulseLab`.
  Query Store collector calls and reads fell under the tested adaptive
  setting; results **cannot** demonstrate that monitoring caused or
  eliminated sporadic SQL latency spikes.
- Separate selected CPU collection may be disabled without affecting
  real DMV/Query Store monitoring.

## Not confirmed — keep Draft until closure

- The latest post-chart-split local browser run on both Navy/Midnight Dark,
  including narrow viewport, separate hover and keyboard selection.
- Complete latest local regression: Query Store filters, plan comparisons,
  SQL text drawer opt-in/disabled behavior and Incident Investigation
  navigation after the latest UI edits.
- Confirm `DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED=true` again after
  lab fixed baseline tests; do not assert an undocumented local setting.
- Release tag/merging into `main` and distribution: **not done**.

## Final 5-minute non-destructive release gate

```powershell
# From the local checkout, verify which branch is checked out.
Set-Location D:\Projects\dbpulse-platform
git branch --show-current

# Confirm there are no local files that would be overwritten by a pull.
git status --short

# On feature/monitoring-overhead-v0.13, update to the reviewed head.
git pull --ff-only origin feature/monitoring-overhead-v0.13

# Confirm loopback API is live; no mutating requests.
Invoke-RestMethod http://127.0.0.1:18081/api/v1/health

# Start the same Vite dashboard command that works in PowerShell.
Set-Location .\frontend
npm.cmd run dev
```

At `http://127.0.0.1:5174/#overview`: visually confirm both charts
show separate axes and use actual observations. Check #queries (Query
Store filters/disabled SQL text), #incidents (open an existing Incident
Investigation), theme picker and narrow view. If a scenario has no
available data, explicitly record `NOT TESTED`, not `PASS`.
Never upload credentials, raw SQL text or XEL files.

## Next-stage boundary

Begin `Phase 14` with threat-model / account identity and
authorization / network connectivity and secret-store design before
exposing a credential-entry form or browser-directed DB connection.
See `docs/PHASE14_TARGET_ONBOARDING_DESIGN.md`.

v0.13 API remains local and single-target until all public-service
protections pass. No production external SQL Server connection yet.

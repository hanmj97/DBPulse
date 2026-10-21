## 2026-10-21 — independent shortcut height and CPU (%) overlay

The user's second screenshot confirmed that the *lower*
`운영 바로가기` card was stretched to the taller sibling
`최근 인시던트 타임라인` by legacy
`.rev-overview-secondary > .rev-panel {height:100%}`.
`dashboard.css` now overrides the height at the *operations*
layout scope, using `align-items:start` and intrinsic
`height:auto/fit-content` for its cards. The right card has a
stable height determined by the three fixed shortcuts and will
no longer expand as the left incident timeline changes; the
left list remains fully accessible without introducing internal
scroll or clipping. Narrow-screen stacking is preserved.

CPU is a new optional overlaid time series **only when true
saved measurements exist**. `OverviewDashboard.jsx` polls
`GET /api/v1/targets/{id}/instance-cpu` every 15 seconds.
This API returns an **existing cached JVM CPU sample**, so no
SQL Server query is triggered by page polling. The optional
backend sampler operates about every 60 seconds when
`DBPULSE_SQLSERVER_CPU_ENABLED=true`; when disabled or
unavailable the chart does not invent a numeric CPU sample.

Each distinct `collectedAt` is held in memory while the
Overview is open, max 60 samples. There is **no persistent
CPU history API** yet, so the graph begins at the first
observed sample after opening and resets upon unmount/reload.
Only CPU points inside the visible real DMV window are
displayed, without filling missing timestamps; gaps over
150 seconds are not connected. The right axis is always
0–100% and orange dashed, distinct from **left axis request
counts**. The readout shows the nearest actual CPU sample
time to avoid falsely implying CPU was measured every 5s.

**Semantic limit:** `sqlProcessCpuPercent` is SQL Server
**instance/process-wide CPU (%) across all databases**,
not the connected `DBPulseLab` database CPU percent. No
per-database CPU% estimator has been added. Per-query SQL
CPU time (ms) continues to come from Query Store; do not
conflate it with host/process CPU %. The optional CPU
feature uses SQL Server ring-buffer diagnostics and may
be DISABLED, INITIALIZING, PERMISSION_DENIED or UNAVAILABLE.
Do not grant additional SQL privileges to bypass errors.

Acceptance:
- [ ] At `http://127.0.0.1:5174/#overview`, right
      "운영 바로가기" panel ends after the 3 actions without
      an empty internal block forced by the taller left list.
- [ ] In Navy and Midnight Dark, graph displays orange
      CPU series/0–100% axis when actual CPU samples arrive
      (usually wait until TWO different 60s samples for a line).
- [ ] If CPU unavailable, no fake 0% line appears; the
      status is noted. Existing DMV graph count series still work.
- [ ] Hover shows the actual recorded CPU sample's time and
      value; time gaps remain gaps.
- [ ] Resize to desktop 1366px and mobile; no clipped actions.
- [ ] Query-page CPU widget and SQL text drawer remain unchanged.

The user has not yet visually verified this latest code.
Draft PR #12 remains unmerged.

## Overview right-rail growth fix (2026-10-21; local visual feedback)

The owner observed that multiple "현재 관측 신호" entries could
extend the right panel beyond the adjacent DMV chart, forcing the
entire CSS grid row to grow and leaving a large blank area under
the graph. The existing unbounded right-rail list is therefore
unsuitable for a concise operational overview.

Implementation (React/CSS only; no new SQL query or backend API):
- The overview shows **up to two** current-signal rows. Live
  Blocking/long-running DMV signals get preview priority;
  open Incident rows remain available through the separate
  incident timeline, table and Incidents detail page.
- When more signals exist, an "추가 신호 N개" summary links to
  Incident history. The KPI buttons above continue to navigate
  to live Blocking and Performance information.
- The overview shows **up to two** current Wait categories; any
  additional categories are summarized and linked to the full
  Wait Diagnostics page. It never misstates the full count.
- Constrain preview rows to consistent height and truncate overly
  long labels *in the overview only*; detailed pages still show the
  original information.
- Empty and unavailable states remain distinct. Do not show an
  "all clear" indicator when collection is stale or missing.
- No changes to Navy/Midnight Dark colors, stored metrics,
  incidents, Query Store, collector timing, or backend services.

New manual visual acceptance:
- [ ] Open `http://127.0.0.1:5174/#overview`, with one to
      many live/current signals. Current-signals card shows
      at most **two** rows even when 4+ exist; parent row does
      not grow excessively relative to the DMV trend card.
- [ ] When the signal count exceeds two, the remaining count is
      visible, and incident navigation plus existing KPI shortcuts
      lead to full applicable views.
- [ ] When more than two Wait categories exist, the "다른 대기
      유형" link is correct.
- [ ] The right rail remains compact at 1920px and 1366px,
      and becomes a single column on narrow mobile widths.
- [ ] Navy/Midnight Dark theme, tooltip/keyboard chart controls
      and existing incident investigation links still work.

Keep the v0.13 PR Draft until the actual browser changes are
confirmed. Prior submitted screenshot documented the defect; it
is not acceptance evidence for the newer code.

## Operations-centered Overview revision (2026-10-20; user requested)

This is an **actual React/CSS code change** on PR #12, not an image
mockup. The prior Overview/Query screenshots and user report established
that the pre-change screens were working. The changes below have
**not yet been reviewed by the user in their running local browser**.

- A full-width server-status bar replaces the numeric "1 monitored
  target" KPI. It shows the actual `target.name`, database name,
  collector connection state and timestamp of the most recent **fresh**
  5-second DMV snapshot. If the last snapshot is stale (>30s) or absent,
  do not present the last historical sample as "healthy".
- Five remaining operational KPI buttons preserve their original
  destinations: open incidents, live Blocking, long-running requests,
  recent Deadlock events, and current Wait observations.
  Historic Deadlock count is *not* presented as active Deadlocks.
- The existing DMV trend chart still uses **actual** activeRequests,
  blockedRequests and longRunningRequests samples. It now supports
  pointer hover and keyboard Left/Right/Home/End sample inspection
  with a timestamp and exact observed values. No artificial points,
  server CPU or Query Store data are inserted into the DMV chart.
- If no Wait observations or active alert signals are available, their
  cards collapse to small honest state indicators. Missing/offline
  data uses a distinct "unavailable" state, **not** a green success
  indicator. Observed waits retain category breakdown.
- Add read-only navigation shortcuts to Blocking, Wait Diagnostics
  and Query Performance. Existing clickable Incident timeline and
  Incident detail table remain intact.
- Uses only existing `--t-*` palette tokens; Navy and Midnight Dark
  presets and SQL text portal styling remain intact. Adds narrow
  viewport layout rules and static frontend regression tests.

Manual acceptance checks after `git pull` + `npm run dev`:

- [ ] At `http://127.0.0.1:5174/#overview`, the server bar
      displays the **actual** MSSQL target, database name, sample
      time and meaningful running/unknown state.
- [ ] Hover over or focus the chart and press arrow keys; displayed
      date/time and all three counts correspond to the hovered point.
- [ ] With no current Wait/alerts, the right cards are compact; if
      collection is offline/stale, the server/empty states do not
      falsely show a clean bill of health.
- [ ] Click all five KPIs and three operator shortcuts; menu/hash
      navigation works without full-page errors.
- [ ] Recent Incident timeline and table both open Investigation
      for existing records (if no records, mark scenario NOT TESTED).
- [ ] In Navy and Midnight Dark, hover/graph/empty cards readable.
      Check narrow browser/mobile widths and theme persistence.
- [ ] SQL drawer/Query page remain unchanged, functional.
- [ ] No errors in browser DevTools console.

No database changes, backend collector code, intervals, or MSSQL
permissions are involved. This is visual/UI behavior work only.
The branch remains Draft until reviewed and the original v0.13
release gates are satisfied.

# DBPulse v0.13 — Final local UI regression checklist (Draft PR #12)

Scope: **local-only** browser review of existing features. This is not a
production security review, real customer workload test, or a license to
run modifications against an external database. Never upload SQL text,
connection strings, passwords, or captured .xel files.

## Baseline / preparation

- [x] Backend's loopback HTTP read-only API smoke: user reported that
      `tools/check_v013_api_regression_lab.ps1` completed normally.
      (User report; no raw output attached here.)
- [x] Java and frontend static tests/build: GitHub Actions passed.
- [x] v0.13 frontend static wiring checks cover themes, nav hashes,
      Query/SQL drawer/plan page wiring, and release labels.
- [ ] **Local browser UI review:** still pending user confirmation.

PowerShell, with explanatory comments:

```powershell
# Bring the latest v0.13 GitHub UI changes into the working tree.
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/monitoring-overhead-v0.13

# Start the local React/Vite dashboard; KEEP this terminal open.
# If already running, restart it after the pull to load current assets.
Set-Location .\frontend
npm run dev

# Browse http://127.0.0.1:5174 (Vite proxy uses backend 18081).
```

The current `frontend/vite.config.js` specifies port **5174**, not 5173.
The backend remains on **127.0.0.1:18081**.

## Pass conditions to verify MANUALLY

### 1. Overview and existing monitoring
- [ ] Overview renders (no blank page or unexpected JS console errors).
- [ ] Current target shows expected MSSQL name and ONLINE (if connected);
      newest metric timestamp/graph advances at 5-second collection cadence.
- [ ] "수집 정상" vs empty incident/wait/deadlock is not confused:
      zero events can be perfectly normal; don't create dummy incidents.
- [ ] Sidebar navigates Overview, Incidents, Wait Diagnostics,
      Deadlock, Blocking, Performance, Queries, Investigation, Targets.

### 2. Dark UI (do not change appearance)
- [ ] Header theme `DBPulse 네이비` works.
- [ ] Header theme `미드나잇 다크` works.
- [ ] Reload in each theme: setting persists in localStorage;
      sidebar, charts, labels, cards, popovers, drawer all readable.
- [ ] Narrow browser viewport: menu opens/closes and no critical control
      is clipped. Compare both themes at the same page/viewport.
- [ ] Sidebar product footer displays `v0.13.0-alpha`, not v0.8.0.

### 3. Query Store and adaptive observer
- [ ] Query page shows the guard status; `policy=ADAPTIVE` after
      lab fixed-mode reset. NORMAL(60s) **or** THROTTLED(120s)
      may be valid depending on measured costs. The card displays
      actual `effectiveIntervalMs` from the API; no synthetic cost claims.
- [ ] Query section indicates `60s 기본 · 자동 완화`, not hardcoded
      guaranteed 60-second collection.
- [ ] Internal-source selector excludes DBPulse by default,
      `전체 수집 쿼리` includes matching available rows,
      and `DBPulse 수집 패턴만` shows only that classification.
      Empty rows are valid when the matching samples do not exist.
- [ ] Tuning Advisor priority filter, sorting, Query ID/Plan ID
      number-only filtering, and clear search are functional.
- [ ] Expand a real Query ID/Plan ID to see Plan Operator Evidence
      and Plan Comparison. If only one plan is sampled, confirm the
      empty/limited comparison explanation rather than expecting a
      fabricated second plan.
- [ ] SQL 보기 opens the right-side drawer, respects both themes,
      and closes via Escape and close button. With
      `DBPULSE_QUERY_TEXT_ENABLED=false`, a 403 / explicit disabled
      explanation is **expected**, not proof of query-fetch failure.
      Do not enable raw SQL access just for a screenshot.
- [ ] Optional instance CPU widget may report DISABLED or
      PERMISSION_DENIED; those are expected supported states when
      collection is not enabled. No privilege escalation.

### 4. Incident and diagnosis detail
- [ ] Wait live/history and Blocking live/history render or show
      accurate no-observations messaging; do not manufacture events.
- [ ] Deadlock status/history renders or shows no recorded events
      without representing that as an error.
- [ ] With an existing incident ID, Investigation shows time window,
      observations, limitations; if there is no incident, mark detail
      scenario **NOT TESTED** pending isolated lab reproduction.
- [ ] Any fatal JavaScript exception, missing component, falsely
      fabricated incident, SQL execution from readonly UI, or wrong
      policy is a release blocker.

## Test evidence

Ask user to report: which pages were opened, both theme results,
whether query filters+SQL drawer worked, any red errors, and whether an
existing incident detail was available. A screenshot may show the
Overview and Query pages **only after hiding sensitive SQL text and
connection information**. Browser review cannot be marked passed merely
because source-level tests/build or read-only API GETs passed.

After the manual UI result, review remaining incidents/blocking test
coverage, README/architecture/release notes, and Draft PR #12.
**Do not merge into main while release checks remain unresolved.**

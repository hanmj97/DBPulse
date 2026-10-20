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

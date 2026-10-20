# DBPulse UI Redesign — Stage 1 (Real React Implementation)

## Product direction

The redesign is based on the user's selected **dark-navy executive overview**
concept. This is *actual application source*, not generated image assets or
placeholder UI. Existing v0.8 Incident-Wait Correlation backend is untouched.

### Replaced long scrolling landing page

- Sidebar links navigate independent hash-driven pages:
  `#overview`, `#incidents`, `#wait-diagnostics`, `#deadlocks`,
  `#blocking-chains`, `#performance`, `#query-performance`,
  `#investigation`, `#targets`.
- The main dashboard features six KPI cards (target, OPEN incidents, current
  blocked requests, recent stored deadlocks, long-running requests and current
  wait observations), a real DMV trend chart, current wait type mix, incident
  timeline, currently observed signals, and recent incident table.
- Target and collection health are dynamic; if data are unavailable, show
  `—` or a visible unavailable message instead of fake zeros.
- All five detailed analyses (Incident Investigation, Wait, Blocking, Deadlock,
  Query Store) continue to use their existing API paths.
- Global search is **limited to incident number/type/summary**, as these are
  currently available locally. No fake cross-database search.
- Chart time selector changes the number of available samples shown (15/30/60),
  rather than advertising a real 24-hour window the API does not provide.
- Notification bell opens Incidents, and alerts show only actually observed
  current signals. A dedicated notification policy is not implemented yet.
- Reports, Settings and multi-target registration are marked planned/disabled.
- Responsive behavior includes a mobile drawer, compact KPI cards and
  horizontally scrollable tables.

## Source organization

- `frontend/src/DashboardShell.jsx`: navbar, top search bar, status and navigation
- `frontend/src/OverviewDashboard.jsx`: real-data summary dashboard + waits/deadlocks feed
- `frontend/src/dashboard.css`: updated visual tokens and responsive layout
- `frontend/src/main.jsx`: actual routing of preserved existing feature panels
- Existing detailed feature source and CSS are preserved for follow-up polish.

## Local verification

```powershell
# Check unsaved local edits first
cd D:\Projects\dbpulse-platform
git status --short

# Fetch the UI branch, which includes v0.8 work (no DB migration)
git fetch origin
git switch --track origin/feature/dashboard-redesign-v0.8

# Existing Docker backend continues to run, no rebuild required
cd .\frontend

# Install dependencies (only if needed), then start Vite
npm.cmd install
npm.cmd run dev
```

Open `http://127.0.0.1:5174/#overview`.
Hard-refresh once if a previous Vite instance is serving cached content.

### Manual QA
- No synthetic demo counts are shown; all six cards reflect API state.
- Change 15/30/60 sample selector: graph window changes.
- Click each KPI/sidebar item and use browser back/forward and direct hash URL.
- Incident search can find an existing number/type; other results are not
  advertised.
- Click latest incident in overview -> correct historical investigation opens
  on Incidents with Wait evidence still available.
- Check Blocking Chains, Wait Diagnostics, Deadlocks, Query Store, and detailed
  performance sections still work with actual MSSQL data.
- Try a disconnected backend: avoid falsely reporting healthy metrics.
- Check narrow/mobile layout, long incident notes, overflowing tables.

## Following UI stages

1. Stage 2: unify detailed panels, visual evidence timeline, incident drawer,
   scoped filters and drill-down interactions.
2. Stage 3: accessibility/performance improvements, chart labels/tooltips,
   table filtering/pagination, screenshots and end-to-end tests.

### Safety
The visuals do not alter SQL Server, start automated recovery, grant permissions
or expose raw SQL. Chart values cannot represent CPU/logical reads unless
proper collectors and APIs are added in a separate backend feature.

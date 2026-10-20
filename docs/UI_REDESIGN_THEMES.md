# DBPulse Dashboard Refinement — Alignment & Color Themes

This update responds to the user's screenshots of the Incident, Wait Diagnostics
and Overview pages, and their request to align the **현재 관측 신호** card to its
adjacent **최근 인시던트 타임라인** card and support portfolio-like themes.

## Changes implemented

- The two overview secondary cards now stretch to an equal grid row height.
  The live signals disclaimer remains anchored to the card bottom and
  individual items use consistent density.
- The application topbar includes a six-preset theme selector.
- Presets: DBPulse Navy (default), Clean White, Pastel Lavender, Pastel Sage,
  Midnight Dark, Soft Charcoal.
- Selection is saved under `localStorage['dbpulse-ui-theme']`. Refreshing
  or navigating to a different analyzer retains the chosen theme. If storage
  is unavailable, the choice still works for the current render session.
- Theme colors affect the app shell, sidebar/nav, topbar, KPI accents,
  summary panels, performance chart accent, table surfaces, and existing
  Wait/Deadlock/Blocking/Investigation detailed panels.
- Semantic severity status (incident OPEN/RESOLVED, errors, warnings) stays
  distinct from cosmetic theme colors.
- No CSS library, fake telemetry or backend schema/API change was introduced.

## Local verification

```powershell
# From the existing UI redesign branch, with no local unstaged changes:
cd D:\Projects\dbpulse-platform
git status --short
git branch --show-current
git pull --ff-only origin feature/dashboard-redesign-v0.8

# Restart the Vite server only if HMR did not refresh:
cd .\frontend
npm.cmd run dev
```

Open `http://127.0.0.1:5174/#overview`.
Try the topbar **테마** selector and confirm six choices.
Check white, lavender, sage, and slate for readable text and navigation;
then choose one, refresh and confirm it is remembered.

Check overview with both 0 and several live alerts, and confirm the two
cards in the second row line up at the bottom on the same desktop viewport.
At narrow/mobile widths they intentionally stack instead.

Navigate to Incident History, Wait Diagnostics, Deadlock, Blocking and Query
Store, confirming the selected theme persists with correct API data.

## Deployment and safety

This is a frontend-only style change, so no MSSQL or PostgreSQL rebuild
is required. This work remains on `feature/dashboard-redesign-v0.8`
and the existing draft UI PR until visual QA passes. That branch already
contains v0.8 Incident Wait Correlation, so its base remains v0.8 feature.

Do not merge the UI branch directly to main while v0.8's base PR is pending.

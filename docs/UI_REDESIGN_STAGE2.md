# DBPulse UI Redesign — Stage 2: Readability & Focused Analysis

This phase refines the existing **real React UI redesign** after the user
verified the Stage 1 overview against their real connected SQL Server.

## Observations from the user's overview screenshot
1. Typography was too small for comfortable monitoring at desktop widths.
2. The integer-request chart duplicated Y-axis labels when the observed peak
   was only two requests. Tick marks must be actual distinct integers.
3. Some cards stretched with unhelpful blank space.
4. The collector's known English incident summaries appeared verbatim on
   the Korean overview.
5. Existing detailed analyzer pages still used old, inconsistent styling.

## Changes
- Legibility: larger navigation, KPI captions, tables, subtitles, diagnostics
  and incident evidence text without removing data density.
- Chart: generate distinct integer Y ticks from the observed peak. No
  synthetic datapoints are added.
- Incident summaries: translate only the known internal phrases:
  `Long-running requests persisted across samples.` and
  `Blocked requests persisted across samples.`. Unknown text is left
  unchanged, not guessed.
- Remove unnecessary equal-height stretching in the two summary card columns.
- Analyst pages: shared dark cards, headings, contextual explanation,
  "개요로 돌아가기", improved tables and mobile responsive behavior.
- Incident History: All/Open/Resolved filters, result count, first 10
  incident rows by default with a working "show more" control.
- Existing incident investigator and wait correlation data still use the
  prior REST endpoints and persisted PostgreSQL evidence.

## Local verification

```powershell
# First preserve any local changes, then obtain latest UI branch
cd D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch feature/dashboard-redesign-v0.8
git pull --ff-only

# Restart frontend if needed; backend container unchanged
cd .\frontend
npm.cmd run dev
```

Open `http://127.0.0.1:5174/#overview`.

Validation:
1. Chart maximum 2 counts -> Y axis labels are 2,1,0 without duplicated ticks.
2. Card/table typography is readable without browser zoom.
3. Incident summaries in overview use Korean only for the documented
   internal messages; no fabricated translation for unknown summaries.
4. Sidebar navigates to all existing detailed analyzer pages; typography
   and cards now match overview.
5. Incident History All/Open/Resolved tabs and "show more" work.
6. Selecting an incident opens its actual Incident Investigation data.
7. Wait, Blocking, Deadlock, Query Store and historical correlations still
   show actual data with no fake counters.
8. Browser back/forward, narrow viewport and error states remain usable.

CI validates React compilation and Java unit tests; it does not
replace browser visual QA on the user's device.

## Follow-on work
Stage 3 can consolidate the remaining duplicate legacy CSS,
add table sorting/export/accessibility tests, contextual investigation
drawers, and modern charts that use explicit sampling timestamps. The
backend v0.8 rollout is kept separate from frontend visual changes.

# Phase 20 - Selected DB CPU chart and opt-in SQL text

## Graph and percentage

A true percentage of capacity for one on-premises SQL Server database is
not directly available in standard on-prem DMV readings. SQL Server process
CPU percentages are NOT per-database percentages.

The app shows a different, explicitly named measurement: Query Store CPU
time normalized against ONE logical CPU core. Formula for a new collection
sample: (new Query Store recorded CPU ms - previous recorded CPU ms) divided
by elapsed milliseconds, times 100. 100% = one full logical core. It may
exceed 100% for parallel CPU usage, and is NOT an exact realtime DB CPU%.
Query Store aggregates asynchronously into intervals, its capture policy
can omit some executions, and old aggregate rows may leave the sliding
15-minute selection. These effects can create gaps and distorted rates.

The graph is an in-memory last-30-collections view scoped by the registered
target ID. First sample has no rate. Stale/missing/offline, delta-negative,
or large-gap readings are UNKNOWN (not zero). Query Store CPU ms and
execution count are preserved as distinct raw aggregated numbers.

## SQL statement drawer

The original backend intentionally blocks raw SQL text unless explicitly
opted in: DBPULSE_QUERY_TEXT_ENABLED=true. SQL text may contain personal
information, SQL parameters, secrets and access tokens, so do not enable it
for public/LAN or shared workstations.

On a trusted local Windows installation:
1. Close DBPulse. Verify that port 18081 is not listening.
2. Run the repo's tools/start_dbpulse_query_text_opt_in.ps1 with PowerShell,
   or manually start the installed app from one PowerShell session:

    # Only one local session; close existing DBPulse first
    $env:DBPULSE_QUERY_TEXT_ENABLED='true'
    try {
        Start-Process -FilePath "$env:LOCALAPPDATA\Programs\DBPulse\window\DBPulse.WindowHost.exe" -Wait
    } finally {
        Remove-Item Env:\DBPULSE_QUERY_TEXT_ENABLED -ErrorAction SilentlyContinue
    }

3. Start monitoring the authorized database. In Query Store select SQL 보기.
   The on-demand endpoint verifies current target, recent sampled queryId
   and planId, caps text length, prevents caching, and does not persist text.
4. Close application when done; next ordinary shortcut starts with disabled
   SQL text view. The per-session launch flag should not be persisted.

## Release gates

Windows x64 native installer v0.20 is an unsigned Draft preview. Tests cover
CPU rate helper, UI wiring and independent request graph, Java suite, Windows
Setup install/uninstall and data preservation. Real SQL Server / WebView2
test is still required after installation. GitHub main stays unchanged.

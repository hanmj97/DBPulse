# DBPulse Phase 17 - WebView2 native window preview (Draft)

This is an experimental Windows-only desktop window alongside the existing DBPulse.exe; not a signed installer or a production release.

## Architecture
- WinForms net10.0-windows self-contained x64 host (DBPulse.WindowHost.exe) with pinned Microsoft.Web.WebView2 NuGet package.
- Existing Java 21 jpackage DBPulse.exe remains the Spring Boot + embedded React localhost backend.
- Host verifies 127.0.0.1:18081 using both expected /api/v1/health JSON (service DBPulse, status UP, phase 14) and React index title. An occupied unknown port is rejected.
- When the port is free, the host starts the bundled DBPulse.exe without a console window, using DBPULSE_EMBEDDED_WINDOW_HOST=1 and redirected stdin. This flag only suppresses external browser opening and activates a stdin-EOF graceful shutdown watcher; ordinary EXE behavior remains unchanged.
- On window close, only the Java process started by this host receives stdin EOF and closes the Spring context. An externally started DBPulse is never stopped. No force-kill is used on an SQLite writer.
- WebView2 permits navigation ONLY to http://127.0.0.1:18081, blocking external origins and popups. No SQL credentials, .env or DPAPI secrets flow through the host.
- Profile: %LOCALAPPDATA%/DBPulse/webview2. SQLite stays at %LOCALAPPDATA%/DBPulse/data/dbpulse.sqlite.

## Build in Windows PowerShell
Install the .NET 10 SDK on the development PC first; ensure WebView2 Runtime exists on target Windows machines.

    # Work at repository root, after closing prior DBPulse processes.
    Set-Location D:\Projects\dbpulse-platform
    # Fetch GitHub Phase17 feature changes. Do not reset or clean untracked work.
    git fetch origin
    git switch feature/webview2-window-v0.17
    git pull --ff-only
    # Build a new Java jpackage image plus self-contained WebView2 shell.
    .\tools\build_dbpulse_webview2_window_preview.ps1

The result is a versioned folder under backend/target/windows-previews/<uuid>/DBPulse/, containing DBPulse.exe and runtime/, with window/DBPulse.WindowHost.exe. KEEP THE ENTIRE DBPulse DIRECTORY together, then double-click window/DBPulse.WindowHost.exe.

Alternatively, the GitHub Actions job named Windows DBPulse.exe preview + native DPAPI smoke uploads a 7-day unsigned artifact containing both Java backend and WebView2 window. The artifact does not require .NET SDK at runtime because the host is self-contained, but WebView2 Runtime remains required.

## Safe local acceptance
1. Exit old Java/DBPulse processes, ensure port 18081 is free. Do NOT delete SQLite, DPAPI or PostgreSQL data.
2. Leave PostgreSQL stopped; confirm SQLite opt-in with: py -3 .\tools\dbpulse_sqlite_desktop_control.py status
3. Double-click the NEW window/DBPulse.WindowHost.exe; one native app window should open. The browser and backend console should not appear.
4. Verify registration, metrics history. Explicitly connect your authorized DBPulseLab, observe data, disconnect, close window, reopen and check persistence.
5. When PORT_18081_OCCUPIED_BY_UNKNOWN_SERVICE appears, examine the occupying process. Do not kill unrelated processes or bypass identity checks.
6. Do not share DB content, saved credentials, appdata or SQL text in issue reports.

## Limitations / release gates
- No signed installer, auto-update, tray icon, cryptographic local app authentication, or final shutdown timeout policy. Loopback response identity is a sanity check, not a trust boundary.
- Existing separately launched DBPulse Java services may be reused and are NOT terminated when a window closes.
- External links/popups are blocked in this first preview (fail closed).
- GitHub Windows CI compilation is not the same as real WebView2 runtime and SQL Server user acceptance testing.
- Prior unresolved Phase13/14 observer overhead + security release gates remain open. Keep this PR Draft; do not merge main yet.


## 18081 port collision / false-unknown diagnosis

If the window displays PORT_18081_OCCUPIED_BY_UNKNOWN_SERVICE:
1. Close the failed **window only**. Run \`.\tools\diagnose_dbpulse_webview2_port.ps1\` in PowerShell.
2. If \`PORT_18081_FREE\`, open the new host again.
3. If another process is listening, inspect its process ID/name and the health/page match flags. The tool never reads SQL secrets and never terminates a process.
4. If the process is an old known DBPulse.exe/Java console, close the old application normally and retry the host. If unknown or its health/title do not match, **stop** and investigate; do not automatically accept or kill the occupant.
5. Phase17 WebView2 host now bypasses system proxies for 127.0.0.1 identity checks and retries startup identity briefly. This does not relax the DBPulse health+UI identity requirement.


## Window-host self-diagnostics for prelaunch port errors

The window host writes **status codes only** to
`%LOCALAPPDATA%\DBPulse\logs\window-host-status.txt`, overwriting on each
launch. It excludes passwords, SQL, HTTP response contents, usernames, process
arguments and exception messages. It distinguishes an actually refused TCP
connection, a TCP connect timeout, unexpected API status/content-type,
DBPulse API identity mismatch, malformed/oversized API response, React title
mismatch, and failure to reach an HTTP service after TCP connects.

When the app shows `PORT_18081_OCCUPIED_BY_UNKNOWN_SERVICE`, **leave the
error window open** and run:

```powershell
# Read bounded diagnostic *codes*, not private SQL Server data.
$log = Join-Path $env:LOCALAPPDATA 'DBPulse\logs\window-host-status.txt'
if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 35 }
# Also inspect port ownership while the window is still open.
Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalPort,OwningProcess
```

The current error code is generated **before launching the bundled Java backend**
when the initial 18081 probe cannot identify a trustworthy DBPulse server.
Do not delete the SQLite file, DPAPI credentials or PostgreSQL volumes, or kill
unrelated processes to work around it.

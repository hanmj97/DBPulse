# DBPulse v0.21 Preview — Windows install and local data safety

## What is distributed

- Unsigned Windows x64 Inno Setup preview, built by GitHub Actions from
  the release-hardening branch. No official signed GitHub Release yet.
- Java runtime and .NET WebView2 window host are bundled. Microsoft
  Edge WebView2 Runtime must exist separately on Windows.
- The installer **does not** provision the local SQLite metadata file or
  activate the SQLite desktop mode on a fresh machine. The developer preview
  is suitable for the existing verified DBPulseLab PC with the prior
  Phase15/16 SQLite migration completed. General public setup is BLOCKED.

## Data and privacy

- User data: %LOCALAPPDATA%\DBPulse\data\dbpulse.sqlite, plus possible
  SQLite WAL/SHM sidecars.
- SQLite mode marker: %LOCALAPPDATA%\DBPulse\desktop\sqlite-mode.enabled.
- Local DPAPI credentials, private backups, logs and desktop settings live
  under %LOCALAPPDATA%\DBPulse; no cleanup or source delete is performed
  by the Windows installer. Removing/uninstalling app files should not
  delete that data, but back it up before any upgrade.
- SQL text can include password literals/tokens. The desktop viewer fetches
  it only on explicit click and does not archive it. Do not share raw
  SQL/logs/private DB or DPAPI content publicly.
- The latest query list uses Query Store's recent 15-minute execution window.
  Saved local numerical observations can be shown for up to seven days when
  previously captured, not full every-statement audit trail.

## Backup and preflight (Windows PowerShell, with app closed)

    # Move into local source repository
    Set-Location D:\Projects\dbpulse-platform

    # Stop DBPulse via X, then inspect TCP LISTEN without ending any process
    Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue

    # Read-only SQLite integrity + row counts
    py -3 .\tools\dbpulse_sqlite_recovery.py check

    # Snapshot via SQLite online backup API; original SQLite never replaced
    py -3 .\tools\dbpulse_sqlite_recovery.py backup

    # Verify printed backup path with manifest hash and temp restore
    py -3 .\tools\dbpulse_sqlite_recovery.py verify-backup --backup "PATH_FROM_BACKUP_OUTPUT"

    # Read-only: bundle/opt-in marker/10 table integrity/foreign keys
    py -3 .\tools\dbpulse_release_preflight.py

The tools only print counts/status, not SQL text or credentials.
If any check fails, leave the original installed data untouched and stop
release preparation. Do not delete WAL/SHM by hand or copy only dbpulse.sqlite
while the app is running.

## Download integrity: SHA256SUMS.txt

Each GitHub Actions build now packages these two files together:
- DBPulse-Setup-v0.21.0-preview.exe
- SHA256SUMS.txt

Use this in PowerShell after extracting the artifact ZIP to a private
Downloads folder:

    # Set the actual extraction directory; do not invent a location
    $dir = "C:\PATH\TO\EXTRACTED\ARTIFACT"
    $exe = Join-Path $dir "DBPulse-Setup-v0.21.0-preview.exe"
    $manifest = Join-Path $dir "SHA256SUMS.txt"

    # Compare installed binary SHA-256 to the published artifact manifest
    $expected = ((Get-Content -LiteralPath $manifest -Raw).Trim() -split '\s+')[0]
    $actual = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "DBPULSE_INSTALLER_HASH_MISMATCH"
    }
    Write-Host "DBPULSE_INSTALLER_HASH_OK"

The checksum detects file corruption **but is not digital code signing**.
Only download from the project's intended GitHub repository. Do not bypass
Windows policy on untrusted computers. Never ask others to share credentials
or Windows DPAPI secret files.

## Manual acceptance on authorized Windows test machine

- Install while DBPulse is closed. Do not uninstall or delete the SQLite
  data directory merely to apply an upgrade.
- Launch from Start Menu. Verify active DBPulseLab is still registered;
  reselect/reconnect intentionally (auto-monitor is not guaranteed).
- Verify initial CPU SVG/chart, live and 7-day saved Query Store stats,
  SQL viewer, Incident, Blocking, Wait, Deadlock and tuning panel behavior.
- Run a foreground 60-minute health probe while the app remains open:

    # Two localhost GETs per interval, no workload SQL or response-body logs
    py -3 .\tools\dbpulse_local_soak_probe.py --minutes 60 --interval-sec 60

- A successful probe ONLY proves local HTTP service liveness. To validate
  the real collector overhead, use the authorized MSSQL lab observer
  measurement design, not the HTTP probe.
- Close the DBPulse window; port 18081 should no longer have a LISTEN entry.
  Verify SQLite integrity again; relaunch, confirm existing history.
- Test uninstall on an isolated Windows test account/system only if a
  verified backup exists and program/data directories are clearly separate.
  CI already checks preservation of test sentinels in five user-data folders.

## Not yet guaranteed

- Fresh empty Windows account onboarding without an existing SQLite DB.
- Zero downtime upgrades, automatic repair, automatic updates, code signing.
- Strong multi-user/local malicious process isolation for SQL text viewing.
- Database-specific exact CPU utilization percentage or full SQL audit log.
- Production-safe automatic recovery (no unapproved destructive action).
- 24-hour durability and performance on a wide range of MSSQL versions.

Do not promote this preview to a stable public release until the gates in
docs/RELEASE_READINESS_V021.md are reviewed and signed off.

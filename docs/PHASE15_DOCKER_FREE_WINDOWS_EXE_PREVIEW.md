# DBPulse Phase 15 — Docker-free Windows EXE, explicit opt-in preview

**Safety boundary:** The user's existing `DBPulse.exe`, PostgreSQL volume and backups remain unchanged. This patch does NOT produce an installer, a standalone application window, auto-updates, or an externally secured remote service. Building the preview still uses Docker for Maven **at build time**. On the activated desktop, Java+React+Spring Boot use the packaged runtime and the local SQLite file. No Docker or system Java is necessary **at runtime**.

## Changes

- `DesktopSqliteStorage.java`: checks for an explicit marker under `%LOCALAPPDATA%\DBPulse\desktop\sqlite-mode.enabled`; verifies the SQLite file exists and is not a symlink, `PRAGMA quick_check`, `PRAGMA foreign_key_check` and the 10 metadata tables. If invalid it aborts startup: **never silently falls back to PostgreSQL**.
- `patch_windows_sqlite_desktop.py`: narrowly modifies only the `WindowsDesktopBoot.configure` method, creates a local copy of the original Java file and preserves the previously applied timestamp compatibility fixes in other classes.
- `dbpulse_sqlite_desktop_control.py`: from a user-selected verified staging/preview SQLite source, creates an **independent SQLite database** in `%LOCALAPPDATA%\DBPulse\data\dbpulse.sqlite` using SQLite's backup API, verifies counts/integrity and enables the opt-in marker. Refuses to overwrite existing SQLite/marker. `rollback` disables the marker but never deletes data. No PostgreSQL login or Docker required for activation.
- `DesktopSqliteStorageTest`: Java datasource mode tests; existing tests remain mandatory.
- Desktop EXE continues to use Windows DPAPI for saved **SQL Server** target credentials; the PostgreSQL *metadata* password is not read in SQLite mode. It does not automatically start monitoring without a user action.
- The existing console and browser behavior is deliberately preserved. The independent window and Windows installer are separate future phases.

## Step A — Patch, test, and build (Windows PowerShell, at project root)

**Close all DBPulse.exe and preview ports 18081 and 18082 first.** Keep original volumes/backups.

```powershell
# Windows scripts need this permission only in the current PowerShell process.
Set-Location D:\Projects\dbpulse-platform
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

# Before patching, inspect current local source changes. DO NOT git reset/clean.
git status --short

# Download/unzip this patch into the project root, retaining existing sources.
# Then run local patcher tests without using a real database.
py -3 -m unittest discover -s tools -p 'test_patch_windows_sqlite_desktop.py' -v
py -3 -m unittest discover -s tools -p 'test_dbpulse_sqlite_desktop_control.py' -v

# Check that the existing Windows desktop bootstrap code matches expectation.
py -3 .\tools\patch_windows_sqlite_desktop.py --project-root .

# Apply once. This backs up the original WindowsDesktopBoot.java.
py -3 .\tools\patch_windows_sqlite_desktop.py --project-root . --apply

# Build a completely new app-image, never overwriting an existing app folder.
.\tools\build_dbpulse_windows_app_image.ps1

# Actually compile and run all backend tests against the newly built sources.
docker run --rm --entrypoint mvn dbpulse-windows-image-builder -B test
# Stop immediately if the tests fail. Never opt into SQLite on a failed build.
if ($LASTEXITCODE -ne 0) { throw 'JAVA_REGRESSION_TEST_FAILED' }
```

The build script's printed line saying PostgreSQL is required describes the *legacy default*, not the activated SQLite mode. SQLite activation comes later.

## Step B — Choose **exactly one** known-good SQLite preview

The recent user run produced `dbpulse-preview-07ea503b937944f6981b57d420ff0dd1.sqlite` (43,504 rows at the time of comparison); select it only if it still exists and was fully closed. **Do not select any `*.partial.sqlite` file.** Use an actual file path rather than an automatically chosen latest copy. The activation tool refuses a source outside `%LOCALAPPDATA%\DBPulse\data\preview` or `staging`.

```powershell
# Manually choose an already tested, closed snapshot; do not paste placeholders.
$source = Join-Path $env:LOCALAPPDATA 'DBPulse\data\preview\dbpulse-preview-07ea503b937944f6981b57d420ff0dd1.sqlite'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'SQLITE_SOURCE_MISSING' }

# Confirm neither backend is running before creating the persistent copy.
if (Get-NetTCPConnection -LocalPort 18081,18082 -State Listen -ErrorAction SilentlyContinue) {
    throw 'STOP_BOTH_DBPULSE_SERVERS_FIRST'
}

# Read-only status and explicit activation. Do not re-run activate after success.
py -3 .\tools\dbpulse_sqlite_desktop_control.py status
py -3 .\tools\dbpulse_sqlite_desktop_control.py activate --source "$source"
if ($LASTEXITCODE -ne 0) { throw 'SQLITE_ACTIVATION_FAILED' }
```

If `SQLITE_ALREADY_PRESENT_NO_OVERWRITE` appears, **stop** and inspect. Never delete `dbpulse.sqlite` to bypass the guard. Do not run `docker compose down -v`.

## Step C — Test **new EXE** with PostgreSQL stopped

```powershell
# Check which EXE was most recently built; it must be the new one.
$exe = Get-ChildItem .\backend\target\windows-previews -Filter DBPulse.exe -Recurse -File |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $exe) { throw 'WINDOWS_EXE_NOT_FOUND' }
Write-Host "NEW_EXE: $($exe.FullName)"

# Stop only the metadata PostgreSQL service. This does not delete its volume.
docker compose stop postgres

# Double-click the printed new EXE path in Explorer (use entire app folder).
# Do not start the legacy Docker backend on 18081.
```

Wait for the browser to open `http://127.0.0.1:18081/#targets`. While it runs, in a **second** PowerShell window:

```powershell
# Confirm real EXE endpoints and production React bundle.
Set-Location D:\Projects\dbpulse-platform
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
.\tools\check_dbpulse_sqlite_desktop.ps1

# Confirm the expected local store mode, size, and row counts without modifying it.
py -3 .\tools\dbpulse_sqlite_desktop_control.py status
```

The native EXE still opens a browser and a console in this preview. If it shows an error, **do not immediately switch back to PostgreSQL**; record its safe startup code and check the local logs, not any SQL password.

## Step D — Persistence test and rollback

Close the original EXE console with Ctrl+C, and reopen the **same** new `DBPulse.exe`. Confirm the registry and metrics are present. If you want to prove new SQLite writes persist, deliberately start your own test MSSQL target and wait a few samples, disconnect, exit, then reopen. Do not reuse a source SQLite file to overwrite the persistent copy.

To disable SQLite **only after closing DBPulse.exe**:

```powershell
# Switch the next EXE launch back to the OLD PostgreSQL mode.
# The SQLite data/credentials and old PostgreSQL volume are never deleted.
py -3 .\tools\dbpulse_sqlite_desktop_control.py rollback
# PostgreSQL can then be restarted manually IF rollback is wanted:
# docker compose up -d postgres
```

**Important:** If new samples were written while using the SQLite main file, rolling back to PostgreSQL does NOT magically merge them. PostgreSQL remains an older independent history until a reconciled data-migration plan is written.

## Future work

1. Investigate SQLite WAL/busy/restart/recovery, retention and encrypted backups (DPAPI only encrypts credentials, **not the SQLite data file**).
2. Remove build-time Docker, optionally ship a private build pipeline for signed binaries.
3. Package an independent window (WebView2/JavaFX/Tauri-style shell), hide console and implement safe exit/duplicate launch handling.
4. Create a genuine Windows installer (`jpackage` MSI or WiX/NSIS), desktop shortcut, Start Menu and uninstaller with data-preserving upgrades.
5. Separate SQL Server instance CPU (%) from selected DB Query Store CPU-time metrics. Do not mislabel it Azure SQL Database CPU (%).

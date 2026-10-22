# DBPulse Phase 18 - Unsigned per-user Windows Setup EXE (Draft)

Phase18 packages the Phase17 WebView2 app window, self-contained .NET runtime, and jpackage Java 21 app-image into an Inno Setup 6 installer. Docker and .NET SDK are NOT needed to run the installed application. WebView2 Runtime remains a prerequisite, not bundled by this preview.

## Installer behavior
- Output: DBPulse-Setup-v0.18.0-preview.exe, unsigned developer preview.
- Windows x64, current-user installation under %LOCALAPPDATA%\Programs\DBPulse. No administrator rights requested.
- Start Menu shortcut and optional desktop shortcut target window\DBPulse.WindowHost.exe, not the browser launcher.
- Packages DBPulse.exe, app/, runtime/, window/. Does NOT install PostgreSQL or change the SQLite mode marker.
- Build script uses only an app-image inside backend/target and refuses database files, DPAPI blobs, dumps, key files and .env in its source directory.
- Uninstall removes installer-managed PROGRAM files and shortcuts ONLY, never user data under %LOCALAPPDATA%\DBPulse\data, desktop, backups or logs. It does not touch saved DPAPI credentials.
- Window host holds Local\DBPulse.WindowHost.InstallGuard named mutex while running; Inno AppMutex blocks installing/upgrading/uninstalling over an active instance of the new window.
- Legacy Java-only DBPulse.exe is not covered by that mutex: close it before installing.
- No signing, auto-update, repair, WebView2 runtime bootstrapper or production backend service support. Not approved for external rollout.

## Developer Windows build
Build requirements: Inno Setup 6 Compiler ISCC.exe, .NET 10 SDK, Java 21, npm, and Docker used only to build the existing Java app-image.

    # PowerShell from DBPulse repository root
    Set-Location D:\Projects\dbpulse-platform
    git fetch origin
    git switch feature/windows-installer-v0.18
    git pull --ff-only
    .\tools\build_dbpulse_webview2_window_preview.ps1
    # Specify the NEW app-image directory output by the prior command.
    .\tools\build_dbpulse_windows_installer.ps1 -AppImagePath "D:\...\backend\target\windows-previews\...\DBPulse"

GitHub Actions builds the host, compiles the installer and then runs SILENT install/self-test/uninstall on a disposable Windows runner. A CI-only sentinel under %LOCALAPPDATA%\DBPulse\data must survive uninstall intact. The destructive install-test script refuses to run outside CI.

## Human Windows acceptance before release
1. Exit all DBPulse/Java processes, confirm 18081 is free. Back up SQLite with Phase16 tools. Do not delete original PostgreSQL volumes.
2. Download NEW DBPulse-v0.18-Windows-Setup-preview GitHub Actions artifact, not the old unpacked app-image.
3. Install for current Windows user, verify Start Menu and optional desktop shortcuts.
4. Launch. Verify existing registered DBPulseLab, SQLite metrics, MSSQL sample collection and no external browser or console.
5. Close and check port 18081 is free. Relaunch and verify persistence.
6. Uninstall through Windows Apps settings; verify EXE and shortcuts gone but original DBPulse SQLite and DPAPI data intact.
7. Optionally reinstall and confirm persisted data. New PC first-run SQLite onboarding is NOT implemented.

## Release gates
- User-PC installation/uninstallation and long-run persistence pending.
- Fresh PC first-run SQLite setup/migration needs onboarding wizard.
- Code signing, SmartScreen reputation, WebView2 prerequisite installation, OS power-loss, update/repair testing remain.
- Prior Phase13/14 production safety and observer-overhead gates open. Draft PR; main unchanged.

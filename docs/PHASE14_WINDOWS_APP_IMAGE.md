# DBPulse v0.14 — Windows EXE app-image preview

**Status:** Experimental unsigned Windows `jpackage --type app-image` build. A complete native application folder is generated with `DBPulse.exe`, the packaged React dashboard + Spring Boot and a private Java 21 runtime. This is **not** a system-wide installer/MSI or a portable, Docker-free application.

## What this preview does

- Double-click `DBPulse.exe` to launch a local Spring Boot + React dashboard in the default Windows browser: `http://127.0.0.1:18081/#targets`. The native application window is a console in the preview and must remain open while running.
- Windows user-account DPAPI storage already used for SQL Server passwords continues unchanged in `%LOCALAPPDATA%\DBPulse\credentials`.
- Metadata PostgreSQL password is enrolled **once** (from the existing private `.env`) into a **different DPAPI encrypted blob**: `%LOCALAPPDATA%\DBPulse\desktop\metadata.dpapi`. This is not a plaintext file and is tied to the same Windows account. Do not back up or publish the encrypted file as if it were a transferable password.
- PostgreSQL metadata database still runs in the **existing Docker Desktop container** on `127.0.0.1:5544`; the app does not start/stop the container and does not delete Docker volumes. Docker Desktop must be running, and the DBPulse PostgreSQL container must already be up.
- Original `backend\target\dbpulse-api.jar`, existing DPAPI SQL Server credentials, PostgreSQL tables and MSSQL databases are not overwritten.

## Build locally (developer preview)

Prerequisites: Windows 10/11, Java **21 JDK** (for `jpackage.exe`), Node/npm, Docker Desktop with the existing DBPulse PostgreSQL container, and the checked-out `feature/target-onboarding-v0.14` branch.

1. Close the previous DBPulse Windows Spring Boot PowerShell console using `Ctrl+C`, and verify port 18081 is free. Never terminate arbitrary Java processes.
2. Build the app-image:

```powershell
# Project root; check for local changes before pulling
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14

# This PowerShell window only; no system-level permanent policy change
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

# Builds React, bundles Spring Boot and produces a native Windows app-image
.\tools\build_dbpulse_windows_app_image.ps1
```

The script prints the newly generated `DBPulse.exe` path inside `backend\target\windows-previews\<unique-id>\DBPulse\DBPulse.exe`. Each build has a different folder so no running JAR or Windows executable is overwritten.

3. **One-time encrypted metadata setup** (only after verifying you are using your own local trusted source checkout):

```powershell
# Uses the newest built app-image by default. No SQL Server credentials touched.
# Reads only DBPULSE_POSTGRES_PASSWORD and local lab TLS preference from .env
# Enrolls the metadata DB password using Windows CURRENT_USER DPAPI.
.\tools\provision_dbpulse_windows_app.ps1
```

Existing `metadata.dpapi` is not overwritten unless `-ReplaceExisting` is passed explicitly. Provisioning never creates a plain text DB password file. The app's Java entrypoint supports `--desktop-provision` and `--desktop-check` (both native Windows DPAPI operations) without starting the web server.

4. Make sure the PostgreSQL container is already running, not a new empty database:

```powershell
# Check only: no containers deleted or reset
docker compose ps

# Start ONLY existing PostgreSQL if needed; volumes are preserved
docker compose up -d postgres
```

5. Double-click the built `DBPulse.exe` in Windows Explorer. No system Java, PowerShell commands, Node or Vite runtime is required for repeated launches (Docker Desktop + DBPulse PostgreSQL remain external dependencies). Wait for the browser to open to `http://127.0.0.1:18081/#targets`.

6. Verify saved DBPulseLab record and `저장된 비밀번호로 모니터링 시작`. On self-signed local Developer SQL Server, explicitly use `고급 연결 설정` when needed; the option is **not** enabled automatically for arbitrary servers. Confirm collector `ONLINE`, Query Store, CPU, Deadlock and Blocking panels. Close the preview console to stop the backend, and restart the EXE to check saved DPAPI credentials.

## CI Windows build and downloadable artifact

The `windows-app-image` GitHub Actions job builds the same app image on a `windows-latest` runner and tests DPAPI user encryption + decryption with a **fake CI password**. On passing runs, it uploads `DBPulse-v0.14-Windows-app-image-preview` as a **short-lived (7-day)** workflow artifact. Extract the entire artifact folder before running `DBPulse.exe`; do not copy the EXE alone because its private Java runtime and app files are required.

Note: GitHub Actions is not an end-to-end replacement for your Windows desktop's SQL Server + existing PostgreSQL. Test the app image with your actual lab and keep the PR Draft until validated.

## Security and deployment limitations

- **Unsigned executable:** Windows Defender/SmartScreen may warn because the preview is not code-signed. Do not disable security controls globally; verify the GitHub source/CI artifact provenance before using it.
- **Preview, not installer:** No Start Menu integration, registered uninstall application, automatic updates, Windows signing or NSIS/WiX installation flow yet.
- **Docker dependency:** Metadata PostgreSQL is not embedded, initialized or migrated. PostgreSQL settings are fixed to `127.0.0.1:5544/dbpulse` for this lab and the existing `dbpulse` metadata account. A truly standalone end-user installer requires a tested local persistence strategy and backup/migration plan.
- **Local security:** API binds only to loopback. The current web client does not support shared/multi-user/Internet access. Use only your own workstation; do not publish port 18081. DB metadata and DPAPI credentials are scoped to Windows current user.
- **No silent database reconnection:** Stored SQL Server credentials are reused only after explicit user action.

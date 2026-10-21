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

## DBPulse.exe 중복 실행 시 기존 화면 다시 열기 (2026-10-21)

기존 버전은 Windows DBPulse.exe를 두 번 실행하면 두 번째 Java 프로세스가 PostgreSQL 연결까지 시작한 뒤 **18081 포트 충돌**로 종료됐습니다. 개선 버전에서는 **Spring Boot·PostgreSQL·DPAPI 연결 전에** 아래 순서로 로컬 포트를 검사합니다.

1. Windows 루프백 `127.0.0.1:18081`에 아무 서버도 없으면 새 DBPulse를 정상 실행합니다.
2. 이미 응답하는 서버가 있으면 `GET /api/v1/health`가 DBPulse 서비스/상태/버전을 포함한 200 JSON인지 확인하고, `GET /`의 실제 DBPulse React HTML 제목도 확인합니다. 첫 실행 직후 HTTP 준비 중이라면 짧게 재확인합니다.
3. **이미 DBPulse가 정상 실행 중이면** 두 번째 프로세스는 수집기를 시작하거나 DB 비밀번호를 읽지 않고 브라우저의 `http://127.0.0.1:18081/#targets`를 다시 연 후 종료합니다. 기존 첫 DBPulse 백엔드 프로세스는 그대로 실행됩니다.
4. **다른 프로그램이 18081을 사용 중이거나 응답을 검증할 수 없으면** 해당 프로세스를 종료·조작하지 않고 `PORT_18081_ALREADY_IN_USE` 안내를 제공합니다. 포트를 사용하는 미확인 서버의 화면은 자동으로 열지 않습니다.

이 동작은 **사용자 편의용 루프백 확인이지 다른 계정/악성 로컬 프로그램에 대한 인증이 아닙니다.** DBPulse는 공개 웹/다중 사용자 서비스가 아닌 현재 사용자의 로컬 앱으로 유지해야 합니다. 이 기능은 DBPulse의 기존 SQL Server 수집 이력·PostgreSQL 볼륨·DPAPI 저장 비밀번호를 변경하지 않습니다.

### Windows 수용 테스트

```powershell
# 현재 개발 브랜치 소스를 동기화하고 버전별 신규 앱 이미지를 생성
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
.\tools\build_dbpulse_windows_app_image.ps1

# 이전에 초기 DPAPI 등록을 완료했다면 이 명령은 다시 실행하지 않음.
# .\tools\provision_dbpulse_windows_app.ps1
```

새 빌드의 DBPulse.exe를 첫 번째로 실행하여 UI 로드/대상 수집 `ONLINE`을 확인합니다. 첫 프로그램과 콘솔을 닫지 않은 상태에서 **동일한 EXE를 다시 더블클릭**합니다. 기대 결과: 웹 화면이 다시 열리고, 기존 수집이 중단되거나 백엔드가 재시작되지 않으며, 별도의 `Port 18081 already in use` 오류가 표시되지 않습니다.

실행 중인 프로그램을 종료하려면 원래 DBPulse 콘솔을 정상 종료합니다. 이후 실행 시 새 백엔드가 시작하는 것이 정상입니다. 다른 프로그램이 포트 18081을 사용 중인 경우에는 DBPulse가 미확인 서비스를 열거나 프로세스를 강제로 종료하지 않아야 합니다.

---

## DBPulse.exe를 더블클릭했는데 창이 닫히는 경우 (2026-10-21)

Windows 실행 파일 생성과 암호화 DPAPI 테스트는 성공해도 **실제 Windows PC의 PostgreSQL 연결과 Spring Boot 서버 구동은 따로 검증해야** 합니다. 이 단계에서 오류가 발생하면 `ApplicationReadyEvent` 전에 실행이 끝나므로 브라우저는 열리지 않습니다.

새 버전은 일반적인 Spring Boot 초기화 실패를 잡아 **오류 창**을 표시하고, 민감한 예외 메시지/SQL 원문을 저장하지 않은 **상태 코드만** `%LOCALAPPDATA%\DBPulse\logs\desktop-startup-status.txt`에 기록합니다. 분류 가능한 항목에는 최초 DPAPI 초기 설정 누락, 현재 사용자 DPAPI 해제 실패, PostgreSQL 5544 연결/인증 실패, DB 이름 오류, 18081 포트 충돌이 있습니다. 실제 오류 원인을 모르면 `BACKEND_STARTUP_FAILED`로 표시하며 추측하지 않습니다.

```powershell
# 현재 app-image를 수정하거나 DB 비밀번호를 공개하지 않는 읽기 전용 검사
Set-Location D:\Projects\dbpulse-platform
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
.\tools\diagnose_dbpulse_windows_app.ps1

# 여전히 문제라면 현재 PowerShell 창 안에서 EXE를 실행해
# 콘솔이 자동으로 닫히지 않게 하고 원본 예외를 확인
.\tools\diagnose_dbpulse_windows_app.ps1 -RunExe
```

- `Windows metadata DPAPI file exists: False` → **최초 암호화 설정 미완료**일 수 있습니다. 기존 `.env`를 다른 사람에게 공유하지 않고 `tools/provision_dbpulse_windows_app.ps1`로 1회 등록합니다.
- `127.0.0.1:5544 TCP reachable: False` → 기존 Docker Desktop과 `dbpulse-postgres-1` 컨테이너가 실행 중인지 확인합니다. **볼륨 삭제나 재초기화 금지**.
- `DESKTOP_CONFIG_CANNOT_DECRYPT` → 다른 Windows 사용자 계정으로 실행했거나 DPAPI 프로필 문제가 있을 수 있습니다. 파일 내용을 공유하지 말고 프로필을 확인합니다.
- `PORT_18081_ALREADY_IN_USE` → 기존 Windows DBPulse/Java 백엔드를 해당 PowerShell에서 `Ctrl+C`로 종료해야 합니다. 다른 Java 프로그램은 임의로 종료하지 않습니다.
- `BACKEND_STARTUP_FAILED` → PowerShell에서 실행한 원본 로그의 `ERROR`·`Caused by` 부분을 확인합니다. 공유할 때 비밀번호/URL 쿼리 매개변수/SQL 본문은 가립니다.

**구버전 EXE에는 위 상태 파일/안내 창이 포함되지 않습니다.** 최신 코드를 당겨 `tools/build_dbpulse_windows_app_image.ps1`로 새 앱 이미지를 만든 뒤 사용하세요. 기존 암호화 저장소나 PostgreSQL 이력은 새 이미지 빌드로 삭제되지 않습니다.

---

## CI Windows build and downloadable artifact

The `windows-app-image` GitHub Actions job builds the same app image on a `windows-latest` runner and tests DPAPI user encryption + decryption with a **fake CI password**. On passing runs, it uploads `DBPulse-v0.14-Windows-app-image-preview` as a **short-lived (7-day)** workflow artifact. Extract the entire artifact folder before running `DBPulse.exe`; do not copy the EXE alone because its private Java runtime and app files are required.

Note: GitHub Actions is not an end-to-end replacement for your Windows desktop's SQL Server + existing PostgreSQL. Test the app image with your actual lab and keep the PR Draft until validated.

## Security and deployment limitations

- **Unsigned executable:** Windows Defender/SmartScreen may warn because the preview is not code-signed. Do not disable security controls globally; verify the GitHub source/CI artifact provenance before using it.
- **Preview, not installer:** No Start Menu integration, registered uninstall application, automatic updates, Windows signing or NSIS/WiX installation flow yet.
- **Docker dependency:** Metadata PostgreSQL is not embedded, initialized or migrated. PostgreSQL settings are fixed to `127.0.0.1:5544/dbpulse` for this lab and the existing `dbpulse` metadata account. A truly standalone end-user installer requires a tested local persistence strategy and backup/migration plan.
- **Local security:** API binds only to loopback. The current web client does not support shared/multi-user/Internet access. Use only your own workstation; do not publish port 18081. DB metadata and DPAPI credentials are scoped to Windows current user.
- **No silent database reconnection:** Stored SQL Server credentials are reused only after explicit user action.

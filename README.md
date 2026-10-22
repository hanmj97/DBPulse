# DBPulse — SQL Server Monitoring & Diagnostics

**Windows release target (CI verification required): [v0.21.3](https://github.com/hanmj97/DBPulse/releases/tag/v0.21.3)** · Windows x64 · Unsigned installer · Local desktop application

DBPulse is a Windows-local SQL Server monitoring and DBA diagnostic application. Connect to an authorized SQL Server, monitor active requests and blocking, examine deadlock/wait/incident evidence, and analyze Query Store history, query plans and tuning candidates. The packaged version opens a native desktop window and stores local application data in SQLite.

**[Download Windows installer](https://github.com/hanmj97/DBPulse/releases/download/v0.21.3/DBPulse-Setup-v0.21.3.exe)** · [Installation & data protection](docs/USER_INSTALL_GUIDE_V021.md) · [Current release overview & architecture](docs/RELEASE_OVERVIEW_V0212.md) · [Known validation limits](docs/RELEASE_READINESS_V021.md)

> **Release disclosure:** This installer is unsigned; Microsoft Edge WebView2 Runtime is required. The 60-minute continuous stability test was deliberately skipped and no 24-hour soak or independent security audit is claimed. Selected-database Query Store CPU is a one-core-equivalent workload indicator, **not exact per-database CPU percentage**. Do not publicly expose the local API or provide access to SQL Server systems you are not authorized to monitor.

### Current Windows desktop capabilities

- SQL Server target registration, explicit connection selection and disconnection, with Windows DPAPI credential protection
- Live DMV-based request, blocking and long-running query indicators
- Deadlock, wait and incident evidence with investigation views
- Query Store performance/plan analysis, query history, read-only SQL text on explicit request, and tuning candidates
- Local SQLite history, backup/recovery tooling, installer upgrades designed to preserve user data
- Navy/midnight monitoring dashboard and Windows application icon with adaptive initial window sizing

**Important:** This is a local diagnostic and analysis tool, not a production-certified autonomous recovery system. The project contains historical development documentation below; earlier Docker, PostgreSQL and Vite instructions do **not** describe the recommended end-user Windows installation path.

---

<details>
<summary><strong>Historical development README (v0.1–v0.21 implementation notes)</strong></summary>


> **실제 DB 운영 서비스**를 목표로 개발하는 프로젝트. MSSQL 우선 구현 후 다른 DBMS 지원 확장.

## 현재 상태 — v0.21.3 Windows 중복 실행 방지 (2026-10-22)

**v0.21.1부터 GitHub Releases를 통한 Windows x64 공개 다운로드를 제공합니다. 미서명 설치 파일이며, 보안 감사·장시간 수집·별도 새 PC 검증은 미완료이므로 운영 DB에 대한 무중단·안전성을 보증하지 않습니다.**
최신 검증 대상은 **Windows x64 로컬 WebView2 프로그램 + Java 21 + Spring Boot/React + SQLite**입니다.
구형 개발 설명과 달리, 현재 테스트 PC에서는 Docker PostgreSQL 없이 로컬 SQLite를 사용할 수 있으나
**v0.21.1에서는 완전히 새로운 Windows 사용자에게만 빈 SQLite를 초기 구성하도록 추가했습니다. 기존 로컬/DPAPI/DB 메타데이터는 덮어쓰지 않습니다. 실제 새 PC 설치 검증은 남아 있습니다.**

- 기능: MSSQL 대상 수동 등록·연결, DMV 5초 모니터링, Blocking/Deadlock/Wait/Incident,
  Query Store 기반 쿼리 성능·저장 이력 7일·SQL 원문 선택 조회·Tuning Advisor.
- 선택 DB의 CPU 수치는 **Query Store CPU 시간과 1논리코어 환산 추정 지표**이며,
  정확한 데이터베이스 CPU 점유율(%) 또는 SQL Server 전체 CPU%가 아닙니다.
- 보안: 로컬 설치형에서 SQL 원문은 클릭할 때만 가져옵니다. 원문에는 개인정보와 토큰이
  포함될 수 있으므로 화면 캡처/공개 로그 공유를 피하세요. 외부 공개 서버로 사용하지 마세요.
- 배포: 사용자별 unsigned Inno Setup 설치 EXE, 시작 메뉴/선택적 바탕화면 바로가기,
  일반 종료 시 Java 백엔드 정리. 언인스톨은 프로그램 파일만 삭제하도록 설계되어 있습니다.
- 아직 필요한 작업: 장시간 부하/안정성 검증, 보안 검토,
  새 PC 최초 실행 검증, WebView2 사전 설치 확인, 코드 서명 및 운영 적합성 추가 검증.

**[최신 Windows 다운로드 (v0.21.3)](https://github.com/hanmj97/DBPulse/releases/tag/v0.21.3)** ·
**[v0.21.3 릴리스 노트](docs/releases/v0.21.3.md)** ·
**[안정화/미검증 항목](docs/RELEASE_READINESS_V021.md)** ·
**[설치 및 사용자 데이터 보호 가이드](docs/USER_INSTALL_GUIDE_V021.md)** ·
**[현재 작업 Draft PR 목록](https://github.com/hanmj97/DBPulse/pulls)**

> v0.21.3: DBPulse DB+Pulse 전용 네이비 아이콘, 바탕화면·시작메뉴·작업표시줄·설치파일 아이콘 통일, 시작 시 모니터 작업 영역에 맞춰 더 큰 창으로 표시합니다.
>
> 아래 Phase 13–16 설명은 **당시의 개발 이력**으로 남겨둔 문서입니다.
> 현재 설치 절차로 따라하지 말고 위 최신 가이드를 우선하세요.

---

## 제품 핵심 목표

1. **장애 감지·원인 파악·안전한 자동복구** — Blocking/Deadlock/연결 장애 등, 위험 조치는 승인 후 실행
2. **쿼리·트랜잭션 성능 분석** — 실시간/누적 통계, 실행계획, Wait, Lock, 성능 변화
3. **저성능 SQL 진단·튜닝 추천** — 쿼리 자동 수집, 원인 분석, 개선안 및 검증

[불변 프로젝트 헌장](docs/PROJECT_CHARTER.md) · [아키텍처](docs/ARCHITECTURE.md) · [로드맵 및 검증 기준](docs/ROADMAP.md)

## Phase 15 — Docker 없는 로컬 SQLite 저장소 준비 (호환성 검증 단계)

Windows 설치형 제품의 남은 Docker PostgreSQL 의존성을 없애기 위한 **SQLite 파일 저장소 호환성 기반**을 추가했습니다.

- PostgreSQL 메타데이터의 **10개 테이블**에 대응하는 SQLite 스키마와 별도의 sqlite-preview 실행 프로필 추가. 기존 Windows EXE와 PostgreSQL 기본 동작은 변경되지 않습니다.
- 저장 계층의 PostgreSQL 날짜 INTERVAL 계산을 JDBC UTC 시간 매개변수로 교체해 두 저장소에서 동일한 보존 기간과 최신성 기준을 사용하도록 함.
- Java 통합 테스트는 **PostgreSQL 서버 없이** SQLite 임시 파일로 Spring Boot 컨텍스트와 외래 키 사용을 검증하고, metric/incident/Query Store/Wait/Blocking/Deadlock/대상 등록 저장·조회 기능도 테스트합니다.
- 기존 PostgreSQL을 삭제하지 않고 테이블별 건수만 읽는 **--desktop-storage-inventory** 개발 진단 명령 추가. 새 EXE 빌드 뒤 DBPulse 프로세스를 중단한 상태에서 사용합니다.
- **아직 미구현:** 기존 PostgreSQL 이력의 실제 SQLite 이동, 원본/대상 건수 대조와 트랜잭션 검증, 데이터 파일 복구/백업, 최종 EXE의 SQLite 전환. 이 검증이 완료되기 전까지 기존 Docker 데이터를 지우면 안 됩니다.

[Phase 15 SQLite 호환성·안전한 데이터 이전 계획](docs/PHASE15_SQLITE_METADATA_FOUNDATION.md)

---

## DBPulse.exe 중복 실행 → 실행 중인 화면 다시 열기

Windows DBPulse.exe가 이미 `127.0.0.1:18081`에서 실행 중일 때 동일한 앱 이미지를 다시 더블클릭하면, **새 데이터 수집/Java 서버를 시작하지 않고 기존 브라우저 화면만 다시 엽니다.** 시작 전 `/api/v1/health`의 DBPulse 정보와 정적 React 화면이 실제로 확인된 경우에만 수행합니다. 잘 모르는 다른 프로그램이 18081 포트를 사용 중이라면 **안전하게 오류 안내만 표시하며 해당 프로그램을 종료하거나 브라우저로 열지 않습니다.**

새 앱 이미지 빌드(기존 Windows 프로세스는 종료하지 않아도 별도 경로 생성):

```powershell
# 최신 소스 적용 및 Windows 미리보기 새 버전 빌드
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
.\tools\build_dbpulse_windows_app_image.ps1
```

이미 Windows DPAPI 메타데이터 암호화 등록을 완료했다면 **설정 스크립트를 다시 실행할 필요는 없습니다.** 새로 생성된 `DBPulse.exe`를 실행해 브라우저가 열리는지 확인하고, **첫 번째 앱을 실행한 상태에서 다시 더블클릭해 재사용 동작**을 시험해 주세요. 이 기능은 PostgreSQL Docker 컨테이너/데이터나 등록 DB의 비밀번호를 변경하지 않습니다. [상세 수용 테스트](docs/PHASE14_WINDOWS_APP_IMAGE.md).

---

## Windows DBPulse.exe app-image 미리보기 (jpackage · 아직 설치기는 아님)

Windows Java 21 `jpackage`로 실제 **`DBPulse.exe` 실행 파일 + 전용 Java 런타임 + React + Spring Boot**를 하나의 폴더로 생성하는 실험적 배포 단계가 추가됐습니다. 최초 한 번 메타 PostgreSQL 접속 비밀번호를 Windows 사용자 DPAPI로 암호화 등록하면, 이후 더블클릭 실행 시 백엔드를 시작하고 `http://127.0.0.1:18081/#targets` 브라우저를 열도록 설계했습니다.

```powershell
# 첫 빌드/프로비저닝: 현재 사용자의 Windows 로컬 개발 PC에서 실행
# 기존 Spring Boot PowerShell 콘솔은 Ctrl+C로 종료하고 시작
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

# React+Spring Boot+Java Runtime이 포함된 버전별 Windows app-image 빌드
.\tools\build_dbpulse_windows_app_image.ps1

# 사용자 .env 의 DBPulse PostgreSQL 접속 암호를 Windows DPAPI로 1회 등록
.\tools\provision_dbpulse_windows_app.ps1
```

출력되는 `backend\target\windows-previews\<빌드ID>\DBPulse\DBPulse.exe`를 직접 실행해 확인합니다. 버전별 폴더 생성으로 **기존 실행 중인 JAR/EXE를 덮어쓰지 않습니다.** Windows CI에서도 app-image를 빌드하고 현재 사용자 DPAPI 암호화·복호화를 실제로 시험하며, 성공한 실행에서는 `DBPulse-v0.14-Windows-app-image-preview` 워크플로 아티팩트를 받을 수 있습니다.

**미완료:** 시스템 설치/제거 프로그램, 디지털 서명, 자동 업데이트, Docker 없는 PostgreSQL 운영. 아직 Docker Desktop의 **기존 dbpulse PostgreSQL 컨테이너가 켜져 있어야** 작동합니다. 기존 볼륨/데이터/SQL Server DPAPI 비밀번호를 삭제하거나 초기화하지 마세요.

[Windows EXE 빌드·한 번만 암호화 설정·검증 절차](docs/PHASE14_WINDOWS_APP_IMAGE.md)

---

## v0.14 React + Spring Boot 단일 프로세스 실행 (Windows 개발 미리보기)

> **더 이상 Vite 프론트엔드와 Spring Boot를 각각 실행할 필요가 없습니다.**
> React 배포용 파일을 Spring Boot JAR에 포함해 `http://127.0.0.1:18081/#targets`에서 화면과 API를 함께 제공합니다. 준비되면 브라우저도 자동으로 열립니다.

```powershell
# Windows PC에서 통합 실행 (Node, Java 21 JDK, Docker Desktop 필요)
# Maven이 없다면 제한된 Docker 빌드 컨텍스트에서 Maven JAR만 빌드
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14

# 이 PowerShell 창에만 적용, 관리자/영구 실행 정책 변경 불필요
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

# React 빌드 → 단일 JAR → Windows 백엔드 실행 → 브라우저 열기
.\tools\run_dbpulse_windows_ui.ps1
```

이전에 열어두었던 `npm.cmd run dev`(5174 포트)는 종료해도 됩니다. **이제 접속 주소는 18081**입니다. Windows 사용자 DPAPI에 저장된 DB 비밀번호와 기존 Docker PostgreSQL 볼륨은 계속 보존됩니다. 단, **메타 PostgreSQL은 아직 Docker에 있으며, 단독 설치 .exe는 아직 제공되지 않습니다.** 기존 실행 코드와 시크릿은 절대 삭제하지 마세요.

[Windows 통합 실행 가이드 및 검증 항목](docs/PHASE14_WINDOWS_UI_BUNDLE.md)

---

## v0.14 Windows 호스트 실행 + 선택적 DPAPI 보안 저장 (개발 기능)

> **Windows 사용자 계정 DPAPI 암호화 저장이 구현됐습니다.** 단, 기존 Docker 백엔드는 Linux이므로 이 기능이 **비활성화**됩니다. 사용자가 설치한 Windows 사용자 세션에서 Java 백엔드를 직접 실행하는 **개발용 Windows 호스트 모드**에서만 활성화됩니다. Windows `.exe` 설치 파일은 아직 만들지 않았습니다.

이 기능은 기존 DB 연결을 바꾸지 않으며, PostgreSQL에 저장된 메트릭·등록 정보·Incident·Query Store 이력도 삭제하지 않습니다.

- `대상 서버` 화면은 `/api/v1/local-targets/credential-capability`로 Windows DPAPI 사용 가능 여부를 확인합니다. Docker에서는 **암호화 저장 없음**으로 표시하고 기존 수동 비밀번호 입력 방식을 유지합니다.
- Windows 호스트 모드에서 DB 모니터링이 `ONLINE`이면 `이 PC에 암호화 저장` 버튼을 직접 눌러 저장합니다. `%LOCALAPPDATA%\DBPulse\credentials\<등록 UUID>.dpapi`에 **Windows 사용자 계정으로만 복호화 가능한 암호문**을 저장합니다. 비밀번호 평문은 파일/GET API/브라우저 저장소에 보관하지 않습니다.
- 그 뒤 `현재 DB 연결 해제`를 누르고 `로컬 등록 대상`에서 해당 DB의 `저장된 연결`을 클릭하면 **비밀번호를 재입력하지 않고** 현재 Windows 계정의 DPAPI 암호문을 복호화해 실제 MSSQL 연결 검증 후 수집을 시작합니다.
- `암호 해제`로 저장된 비밀번호만 지울 수 있습니다. `등록 해제`는 암호문까지 제거하지만 실제 SQL Server DB와 기존 수집 이력은 그대로입니다. 정상적인 데이터 수집 여부는 계속 `ONLINE`을 확인해야 합니다.
- **자동 시작 시 무단 DB 재접속 없음:** Windows 호스트 재실행 후에도 등록 DB에 저장된 자격증명을 사용할 수 있지만, 시작은 사용자가 직접 `저장된 연결`을 선택해야 합니다. 백그라운드 무인 자동 재연결은 설치 프로그램/선택 동의 기능 준비 후 개발합니다.

### Windows 호스트 모드 실행(현재 개발 테스트)

```powershell
# Windows PowerShell에서 실행. Docker 기존 Postgres는 계속 사용하고
# Docker 백엔드만 중지한 다음 Windows 로그인 계정으로 Java 백엔드 실행.
# Java 21 필요. Maven이 없으면 Docker 빌더로 JAR을 빌드합니다.
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14
.\tools\run_dbpulse_windows_host_lab.ps1

# 프론트엔드는 별도 PowerShell 창에서 실행.
Set-Location D:\Projects\dbpulse-platform\frontend
npm.cmd run dev
```

실행·저장·연결 해제·재접속·기존 Docker 모드 복귀의 자세한 검증 항목과 보안 주의사항은 [Windows DPAPI 구현 및 로컬 검증 안내](docs/PHASE14_WINDOWS_DPAPI_HANDOFF.md)를 참고하세요. **GitHub CI는 Linux에서 Windows DPAPI를 실제로 호출하지 않으므로 로컬 검증이 필요합니다.**

---

## v0.14 UI 연결 — 기존 DBPulseLab 자동 연결을 끄고 원하는 DB 모니터링 시작

> 사용자 요청 반영: **등록된 DB 클릭 → 비밀번호 재입력 → 모니터링 시작**, **현재 DB 연결 해제**를 모두 웹에서 처리. 백엔드·Docker 재시작 버튼 없이 연결을 안전하게 바꿉니다.


> **이번 개발 기능은 실제 모니터링까지 연결됩니다.** SQL Server DBA 읽기 전용 계정의 DMV/Query Store 권한은 별도로 필요하며, Java 프로세스를 종료하면 암호화되지 않은 **메모리 전용** 연결 비밀번호는 사라집니다. 새로운 Windows EXE 설치기는 아직 구현되지 않았습니다.

**기존 PostgreSQL volume / 이력 / SQL Server DB는 절대로 삭제하지 않습니다.** 아래는 기존 자동 연결만 해제하는 절차입니다.

```powershell
# 현재 v0.14 브랜치 확인 및 소스 반영
Set-Location D:\Projects\dbpulse-platform
git branch --show-current
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14

# 기존 .env 파일을 직접 편집해 예전 DB 연결/비밀번호를 지웁니다.
# Git 작업 폴더 안에 비밀번호가 들어간 .env 백업 복사본을 만들지 마세요.
notepad .env
```

`.env`에서 다음 항목을 맞춥니다. `DBPULSE_MSSQL_USERNAME` 및 `DBPULSE_MSSQL_PASSWORD`의 **이전 값은 지우고 빈 값으로** 둡니다. `DBPULSE_LOCAL_TRUST_SELF_SIGNED=true`는 자신이 관리하는 로컬 SQL Server Developer 자체 서명 인증서에서만 사용할 수 있습니다.

```dotenv
# 현재 SQL Server 자동 연결 중지. 기존 데이터·이력은 삭제되지 않음.
DBPULSE_MSSQL_ENABLED=false
DBPULSE_MSSQL_USERNAME=
DBPULSE_MSSQL_PASSWORD=

# 사용자가 직접 연결할 로컬 대상 서버 등록·모니터링 활성화
DBPULSE_LOCAL_TARGETS_ENABLED=true
DBPULSE_LOCAL_ALLOWED_DB_HOSTS=host.docker.internal,localhost,127.0.0.1
DBPULSE_LOCAL_TRUST_SELF_SIGNED=false
```

```powershell
# 백엔드 코드와 .env 변경 반영 (PostgreSQL volume 보존)
docker compose up -d --build --force-recreate backend

# 연결 해제 상태 확인. status NOT_CONFIGURED가 예상됨
Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets

# 다른 PowerShell 창에서 프론트엔드 개발 서버 재실행
Set-Location .\frontend
npm.cmd run dev
```

브라우저 `http://127.0.0.1:5174/#targets`에서 이름·호스트(`host.docker.internal`)·포트·DB명·모니터링 로그인·비밀번호를 입력합니다.

1. **연결 테스트** — `SELECT DB_NAME()`만 수행. 성공해도 DMV/Query Store 권한이 충분한지는 따로 확인해야 합니다.
2. **선택한 DB 모니터링 시작** — 새 DB의 UUID를 실시간 수집기 대상으로 설정. 약 5~15초 후 개요 `collectorStatus=ONLINE` 여부를 확인합니다.
3. 개요 `http://127.0.0.1:5174/#overview`로 이동. 5초 DMV 요청 그래프와 별도 CPU 그래프의 실제 데이터·범례를 확인합니다. 값이 0인 것은 수집 시점에 진행 중인 요청이 없을 수 있다는 뜻입니다.
4. Query Store 기능은 선택한 DB에서 Query Store 활성화/필요 권한이 있어야 합니다. 새로운 수집은 대체로 60초 이상 기다려야 데이터가 보일 수 있습니다.
5. **다른 DB로 변경하려면** `대상 서버`에서 **현재 DB 연결 해제 · 다른 DB 선택** 버튼을 누릅니다. 수집 중인 쿼리가 종료될 때까지 안전하게 기다린 뒤 연결이 해제됩니다. **Docker 재시작과 PowerShell은 필요하지 않습니다.**
6. **로컬 등록 대상** 목록에서 원하는 DB를 클릭하면 연결 이름·호스트·포트·DB명·로그인이 자동 입력됩니다. 비밀번호는 영구 저장하지 않았으므로 다시 입력해야 합니다. **선택한 DB 모니터링 시작**을 누르면 내부 연결 테스트까지 수행합니다.
7. 선택한 DB의 실시간 수집이 `INITIALIZING`에서 `ONLINE`으로 바뀌는지 확인합니다. 기존 대상의 PostgreSQL 이력은 삭제되지 않고 각각의 ID로 분리됩니다.

### 등록 DB의 이름 수정·등록 삭제 (v0.14)

- `대상 서버 → 로컬 등록 대상`의 `수정`으로 **표시 이름만** 변경할 수 있습니다. 서버/포트/DB명/로그인은 원래 등록 정보 그대로이고 과거 이력도 동일 UUID에 남습니다.
- `삭제`는 **로컬 연결 목록에서만 제거**하는 작업입니다. SQL Server의 실제 데이터베이스/테이블은 삭제하지 않고, 과거 DBPulse 메트릭·인시던트·Query Store 기록도 유지합니다. 재등록하면 새 대상 UUID로 분리됩니다.
- 현재 `ONLINE`/활성 대상의 `수정`·`삭제` 버튼은 비활성화됩니다. 먼저 `현재 DB 연결 해제 · 다른 DB 선택`을 누르세요.
- 호스트/포트/DB명/로그인을 변경하려면 새 연결 정보를 검증해 별도 대상으로 등록한 뒤 기존 등록을 해제하는 방식을 사용합니다. 기존 대상의 ID를 새 DB에 재사용해 서로 다른 DB 이력이 섞이는 일을 방지합니다.
- 모든 변경은 `127.0.0.1` 로컬 앱 전용이며, 비밀번호 저장이나 프로세스 재시작 권한을 요구하지 않습니다.

**주의:** `docker compose down --volumes`는 PostgreSQL 데이터를 삭제하므로 실행하지 마세요. 비밀번호가 들어간 `.env`, 로컬 로그 및 XEL 원본은 GitHub에 업로드하지 마세요. 이 경로는 개발 PC의 `127.0.0.1` 전용이고, 연결이 승인된 테스트 DB에서만 사용합니다.

[실제 연결 개발 및 검증 계획](docs/PHASE14_IMPLEMENTATION_PLAN.md).

---

## 다음 작업 브랜치: v0.14.0-alpha — 설치형 로컬 웹 DBPulse

> **확정된 제품 방향:** 각 사용자가 자기 Windows PC에서 설치·실행하는 로컬 웹 대시보드. **회원가입·로그인·도메인·별도 SaaS 서버는 필요하지 않습니다.** 현재는 Docker + Vite 개발환경이며 Windows 설치기(.exe)는 후속 작업입니다.

- 작업 브랜치: `feature/target-onboarding-v0.14` · [Draft PR #13](https://github.com/hanmj97/DBPulse/pull/13). 아직 v0.13 Draft PR #12 위에 쌓인 개발 브랜치입니다.
- `대상 서버`에 SQL Server **연결 테스트 / 대상 메타데이터 등록 및 단일 DB 모니터링 시작** UI 및 로컬 API가 구현되었습니다.
- **중요:** 저장되는 것은 연결 이름·호스트·포트·DB명·로그인명 등의 비밀번호 제외 메타데이터뿐입니다. 비밀번호는 **Java 프로세스 메모리에만 보관**하며 저장하지 않습니다. **등록된 DB를 고른 뒤 재입력하여 실행 중 전환할 수 있지만**, 비밀번호 없는 자동 재연결이나 여러 DB 동시 수집은 아직 제공하지 않습니다.
- 신규 로컬 API는 기본 비활성화되며, 서버가 명시적으로 허용한 DB 호스트에서만 연결 테스트를 수행합니다. Docker와 Vite를 로컬 `127.0.0.1`에만 바인딩하세요.

### 로컬 대상 등록 기능을 개발 환경에서 시험하기

```powershell
# DBPulse 프로젝트 루트로 이동
Set-Location D:\Projects\dbpulse-platform

# .env에 아래 두 개 값을 추가하거나 기존 값을 수정합니다.
# DBPULSE_LOCAL_TARGETS_ENABLED=true
# DBPULSE_LOCAL_ALLOWED_DB_HOSTS=host.docker.internal,localhost,127.0.0.1
notepad .env

# 새 API와 DB 스키마가 포함되도록 백엔드를 **재빌드**
# postgres 데이터 볼륨은 보존합니다.
docker compose up -d --build backend

# 백엔드 상태 확인
docker compose ps

# React 개발 서버: 별도 PowerShell에서 실행
Set-Location .\frontend
npm.cmd run dev
```

`http://127.0.0.1:5174/#targets`에서 연결 정보를 입력하고 먼저 `연결 테스트`를 선택합니다. 연결 테스트 후 `대상 정보만 등록`을 선택하거나 `선택한 DB 모니터링 시작`으로 실제 수집을 시작할 수 있습니다. `.env`의 기존 자동 MSSQL 수집 활성화 여부와 선택한 UI 대상은 서로 다르게 취급되며, 수집을 시작하려면 기존 자동 연결을 꺼야 합니다.

**고급 연결 설정:** 대상 서버 화면에서는 자체 서명 TLS 예외가 기본적으로 숨겨져 있습니다. `고급 연결 설정`을 펼쳤을 때만 선택할 수 있으며, 인증서 검증이 기본값입니다. 연결 오류는 TLS 협상/인증서 신뢰, 네트워크, 타임아웃, 로그인·DB 접근 권한을 가능한 범위에서 구분하고, `선택한 DB 모니터링 시작` 직후에는 실제 DMV 수집 상태가 `INITIALIZING → ONLINE/OFFLINE`으로 표시됩니다.

**개발용 자체 서명 TLS:** 로컬 SQL Server Developer만 테스트하고 인증서가 자체 서명인 경우, 서버 측 `.env`에 `DBPULSE_LOCAL_TRUST_SELF_SIGNED=true`를 **명시적으로** 설정하고 백엔드 컨테이너를 다시 생성한 뒤 대상 등록 폼의 “로컬 개발용 자체 서명 인증서 허용” 체크박스를 선택할 수 있습니다. `localhost`/`127.0.0.1`/`host.docker.internal`에서만 허용하며 **원격 DB에는 적용할 수 없습니다**. 실사용은 신뢰 가능한 서버 인증서가 우선입니다.

**TLS 주의:** 새 테스트는 기본으로 서버 인증서를 검증합니다. 자체 서명 인증서만 사용 중인 SQL Server Developer 환경에서는 `CONNECTION_OR_TLS_FAILED`가 반환될 수 있습니다. 보안을 끄거나 권한을 높이기 전에 테스트용 신뢰 인증서 설정을 확인해 주세요. 원격 호스트는 해당 PC/컨테이너에서 명시적 접근 허용이 필요합니다.

연결 테스트는 `POST /api/v1/local-targets/test-connection`, 대상 등록은 `POST /api/v1/local-targets`, 목록은 `GET /api/v1/local-targets`이며, POST는 JSON + `X-DBPulse-Local-Request: 1` 헤더 및 로컬 Origin 보호가 필요합니다.

[Phase 14 로컬 설치형 구현 계획](docs/PHASE14_IMPLEMENTATION_PLAN.md)

---

## 현재 개발 버전: v0.13.0-alpha — 모니터링 오버헤드 가드와 운영 대시보드

> **개발 브랜치:** `feature/monitoring-overhead-v0.13` · [Draft PR #12](https://github.com/hanmj97/DBPulse/pull/12). `main`에 병합된 정식 배포가 아니며, 실제 외부 고객 서비스는 아직 지원하지 않습니다.

React → Spring Boot REST API → PostgreSQL 메트릭 저장소 → 읽기 전용 MSSQL Collector 구조로, **로컬 환경에서 설정한 SQL Server 대상 1개**를 모니터링합니다.

- **5초 간격 DMV 수집**: 실제 활성 요청/Blocking/장시간 요청, 연결 상태 및 사건 이력. 화면의 요청 수는 QPS/TPS가 아닙니다.
- **진단과 근거 조회**: Blocking Chain, Deadlock 사건, Wait Diagnostics, Incident Investigation.
- **Query Store**: 실행계획별 성능 통계/튜닝 후보, 근거 연산자, 플랜 비교 및 옵트인 SQL 원문 조회(기본 비활성).
- **v0.13 adaptive guard**: Query Store 분석 수집의 관측 비용에 따라 60초 기본 주기를 120초까지 완화하는 휴리스틱. DMV 5초 수집은 그대로입니다. 단기 A/B 로컬 측정에서 Query Store 수집 비용 감소를 관측했으나 **업무 SQL의 성능 개선 또는 운영환경 무영향을 입증한 것은 아닙니다**.
- **2개 분리 그래프**: 왼쪽은 요청 수(건), 오른쪽은 선택적 **SQL Server 인스턴스 전체 프로세스 CPU(%)**. 일반 MSSQL에서 특정 DB만의 CPU(%)를 뜻하지 않습니다. 약 60초 CPU 표본은 실제 수집값만 표시합니다.
- **현재 미구현**: 여러 DB **동시 수집**, 비밀번호 없는 자동 재연결, 자격증명 암호화 저장, 검증된 자동복구 및 Windows 설치기. 회원가입/로그인은 설치형 도구 목표에서 제외.

구체적인 상태 및 주의사항은 [개발 현황](docs/PROJECT_STATUS_2026-10-21.md), [아키텍처](docs/ARCHITECTURE.md), [v0.13 검증 체크리스트](docs/PHASE13_FINAL_UI_REGRESSION.md), [다음 단계 대상 등록 설계](docs/PHASE14_TARGET_ONBOARDING_DESIGN.md)를 참고하세요.

> ⚠️ **로컬 개발 환경에서만 사용하세요.** 사용자 인증과 다중 테넌트 보호가 없으므로 서비스를 인터넷에 공개하거나 미승인 외부 SQL Server에 연결하지 마세요. SQL 텍스트, 자격증명, XE 원본 파일은 저장소에 업로드하지 않습니다.

## 요구사항

- Windows 10/11 + Docker Desktop (WSL2 실행)
- Node.js 20.19+ 또는 22.12+ (프론트엔드 개발 서버)
- SSMS / SQL Server Developer 설치(선택: 초기 화면은 SQL Server 없이도 열림)
- 로컬 SQL Server의 TCP/IP 활성화 및 정확한 TCP 포트 확인 (기본 1433이라고 단정하지 않음)
- 첫 Docker 이미지 빌드에는 인터넷이 필요 (Maven 의존성 다운로드)

Java/Maven을 Windows에 설치하지 않아도 백엔드는 Docker에서 빌드한다.

## Windows PowerShell 실행 가이드

### 1. 압축 해제 후 `dbpulse-platform` 폴더에서 실행

```powershell
# 프로젝트 루트로 이동: 실제 압축 해제 경로에 맞게 수정
cd D:\Projects\dbpulse-platform

# 로컬 전용 환경 변수 파일 생성 (.env는 git에서 제외)
Copy-Item .env.example .env

# 환경 변수를 확인하고 변경: PostgreSQL 비밀번호, MSSQL 서버/포트/계정 등
notepad .env

# 프로젝트 백엔드와 별도 PostgreSQL 데이터 저장소 실행
# --build : Dockerfile로 Spring Boot JAR 빌드
# -d : 컨테이너를 백그라운드에서 실행
docker compose up --build -d

# 정상 실행 및 오류 확인: backend, postgres 두 서비스
docker compose ps

docker compose logs --tail=100 backend

# Spring Boot API가 응답하는지 확인
Invoke-RestMethod http://127.0.0.1:18081/api/v1/health

# 아직 MSSQL 연결값이 비활성인 초기 상태 확인
Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets
```

### 2. 웹 대시보드 실행

```powershell
# React 프론트엔드 폴더로 이동
cd .\frontend

# package.json에 지정한 라이브러리 설치
npm install

# PowerShell 실행 정책과 무관하게 Vite 개발 서버 실행 (창 유지)
npm.cmd run dev
```

브라우저에서 **http://127.0.0.1:5174** 열기. 로컬 Vite 프록시는 `18081`의 Spring Boot API를 호출한다. MSSQL을 아직 연결하지 않았다면 '설정 필요'가 정상이다.

### 3. 로컬 MSSQL 연결 설정

1. SSMS에서 `sql/01_create_lab.sql` 실행 → **`DBPulseLab` DB만 생성**, 기존 `Dataops_mssql` 및 `dataops` 데이터는 변경하지 않음.
2. SSMS에서 SQL 인증 로그인 `dbpulse_monitor`를 직접 생성. 데이터베이스 조회/성능 모니터에 필요한 권한 외 부여하지 않는다.
3. SQL Server 2022 이상이면 `sql/02_monitor_permissions_SQL2022_plus.sql`을 관리자 권한으로 검토 후 실행. 2019 이하의 DMV 권한은 `VIEW SERVER STATE`이므로 해당 스크립트를 그대로 실행하지 말 것.
4. TCP 포트를 SSMS/SQL Server Configuration Manager에서 확인한다. 예전 프로젝트에서 1434를 사용했더라도 **이번 서버의 현재 포트를 직접 확인**한다.
5. 루트 `.env`를 편집하여 `DBPULSE_MSSQL_ENABLED=true`, `DBPULSE_MSSQL_PORT=실제포트`, `DBPULSE_MSSQL_USERNAME=dbpulse_monitor`, `DBPULSE_MSSQL_PASSWORD=실제비밀번호`로 변경.
6. Docker Desktop에서 SQL Server에 접근할 때 `host.docker.internal`을 사용한다. 자체 서명 인증서를 사용하는 로컬 개발에서만 `DBPULSE_MSSQL_TRUST_SERVER_CERTIFICATE=true`를 허용한다.

```powershell
# 프로젝트 루트에서 실행 (frontend 폴더에 있다면 .. 으로 이동)
cd ..

# 변경된 환경변수를 적용하여 백엔드 컨테이너 다시 생성
docker compose up -d --force-recreate backend

# DB 연결 에러나 권한 문제를 확인
docker compose logs --tail=100 backend

# 실제 수집 상태 및 샘플 이력 조회
Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets
Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets/local-mssql/metrics?limit=10
```

`collectorStatus=ONLINE`이 되면 `metric_snapshots` 저장과 UI 갱신까지 완료된 것. 수집 이후 값이 모두 0인 경우, 요청들이 매우 빨리 끝나 5초 간격 DMV 스냅샷에 잡히지 않은 것일 수도 있다. 이를 오류나 QPS 0으로 오해하지 말 것.

### 4. PostgreSQL 데이터 보존/종료

```powershell
# 실행 중인 컨테이너 종료; named volume은 보존
docker compose down

# 컨테이너 재시작 시 기존 수집 이력이 그대로 유지됨
docker compose up -d

# 경고: 아래 명령의 --volumes는 DBPulse 메트릭 데이터를 완전히 삭제하므로 일반적으로 실행하지 말 것
# docker compose down --volumes
```

## API (v0.2.0)

| HTTP | 주소 | 설명 |
|---|---|---|
| GET | `/api/v1/health` | 백엔드 생존 확인. SQL Server 가용성 보증 아님 |
| GET | `/api/v1/targets` | 로컬 1개 MSSQL 타깃의 상태/설정 공개 정보 |
| GET | `/api/v1/targets/local-mssql/metrics?limit=60` | 최근 60개 실제 DMV 집계 스냅샷 (1~360개 지원) |
| GET | `/api/v1/targets/local-mssql/query-performance/status` | Query Store 수집 상태 (DMV 수집 상태와 독립) |
| GET | `/api/v1/targets/local-mssql/query-performance?limit=20` | 최근 Query Store 실행계획별 지표 (1~20개 지원) |

## 버전 격리 및 기존 프로젝트 보호

- 별도 PostgreSQL DB/Volume: `dbpulse`, `dbpulse_pgdata`, 호스트 포트 `5544`. 기존 DataOps PostgreSQL `dataops`/`5433`와 별개.
- 별도 MSSQL 대상 DB: `DBPulseLab`. 기존 `Dataops_mssql`을 생성/수정/삭제하지 않음.
- 개발용 .env, 비밀번호, 테스트 데이터는 GitHub에 올리지 않음.
- 모니터링 수집은 SELECT 문만 실행하고, 사용자 DB에 DDL/DML을 실행하지 않음.

## 로컬 트래픽 검증 도구

직접 요청한 **10초당 1,000~2,000개의 SQL/동시 트랜잭션 재현**은 제품 검증 항목으로 보존했다. Phase 1에서도 `tools/loadtest/`에 격리된 테스트용 부하 발생기를 제공한다. 실제 대량 테스트 진행 전에 MSSQL 포트/성능 모니터 수집부터 확인한다.

[부하 테스트 도구 사용 가이드](tools/loadtest/README.md). 절대 운영 DB에서 사용하지 않는다.

## Phase 2: Query Performance Analyzer 검증

1. 관리자 SSMS에서 **전용 로컬 DBPulseLab만** 대상으로 `sql/04_enable_query_store_lab.sql`을 검토·실행. Query Store가 `READ_WRITE` 상태인지 확인.
2. `docker compose up -d --build --force-recreate backend`로 빌드. `backend/pom.xml`은 `<finalName>dbpulse-api</finalName>`을 사용하며 Dockerfile은 `dbpulse-api.jar`을 복사하도록 되어 있음.
3. `Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets`와 `Invoke-RestMethod http://127.0.0.1:18081/api/v1/targets/local-mssql/query-performance/status`로 두 수집기 상태를 각각 검증.
4. SSMS에서 `sql/05_query_store_demo_lab_only.sql`을 실행해 테스트 쿼리 통계를 발생시킨 뒤, Query Store의 비동기 반영을 기다려 API와 React 테이블을 확인.
5. 상세 설계·지표의 의미·주의사항은 [Phase 2 설계 문서](docs/PHASE2_QUERY_PERFORMANCE.md) 참고.

**주의:** Query Store의 `executionCount`는 집계값이며 현재 초당 처리량(QPS)이 아니다. DMV 5초 스냅샷도 아주 짧은 쿼리를 놓칠 수 있다.

## 다음 제품 개발

실시간/누적 성능 분석의 로컬 통합 테스트 후 Blocking·Deadlock 원인 분석과 장애 이벤트 이력, 안전한 복구 정책, 검증 가능한 SQL 튜닝 추천, 사용자별 DB 연결 보안으로 확장한다. 부하 테스트는 제품 신뢰성 검증을 위한 독립 도구로 유지한다. 별도 부하 발생기는 제품 **검증 도구**에만 속한다.

## Phase 3 — Incident Detection (개발 브랜치)

- PostgreSQL의 `incidents` 이력 테이블 및 전용 API
- 2회 연속 Blocking·장시간 요청·Collector 실패 감지 후 OPEN; 2회 연속 정상 감지 후 RESOLVED
- React의 Incidents 화면에 사건 이력 표시 (실제 검증 전)
- 자동복구, Deadlock 상세 원인 분석, SQL 튜닝 제안은 아직 **미구현**
- [세부 설계 및 로컬 검증 순서](docs/PHASE3_INCIDENT_DETECTION.md)

## Phase 4 — Blocking Chain Analyzer (개발 중)

- 별도 읽기 전용 SQL Server DMV 수집기: `sys.dm_exec_requests` + `sys.dm_exec_sessions`.
- 수집 항목: 대기/차단 세션 ID, Wait Type, 대기/요청 경과, 차단 세션 상태 및 열린 트랜잭션 건수.
- 관계 체인 분석: 최상위 차단 세션 **후보**, 체인 깊이, 관측된 순환 여부. 최대 100개 관계만 조회.
- PostgreSQL 저장: Blocking이 0인 정상 시점도 저장하며, 7일 보존.
- API: `GET /api/v1/targets/local-mssql/blocking-chains`
- API: `GET /api/v1/targets/local-mssql/blocking-chains/history?limit=20`
- React: **Blocking Chains** 실시간 목록과 과거 Blocking 이력.
- [상세 설계와 검증 절차](docs/PHASE4_BLOCKING_CHAIN_ANALYZER.md)
- **실행 전 주의:** 별도 개발 브랜치 코드이며 로컬 Maven/SQL Server 통합 검증 전. 기존 정상 동작하는 v0.3 main은 유지됨.

## Phase 5 — Deadlock Detection & Analysis

- 15초 간격으로 SQL Server `system_health*.xel`에서 `xml_deadlock_report` 읽기.
- 대상 DB의 참여 세션 번호, 희생 여부 및 이벤트 발생 시각만 저장 (SQL/XEL 경로/XE XML 미저장).
- PostgreSQL 중복 이벤트 방지 + React **Deadlocks** 화면.
- API: `/api/v1/targets/local-mssql/deadlocks/status` 및 `/deadlocks?limit=20`.
- [구현·권한·제한 사항 및 로컬 검증 가이드](docs/PHASE5_DEADLOCK_EVENTS.md).
- 코드는 개발 브랜치에 있으며 로컬 DB 통합 검증 전; v0.4.0 main은 보존됨.

## Phase 6 — Incident Investigation Engine (개발 중)
- 사건의 **원인 분석 근거 보기**를 선택해 저장된 메트릭·Blocking Chain·Deadlock을 시간대로 연관 조회.
- 확정된 원인과 단순 관측 사실을 혼동하지 않도록 OBSERVED/LIMITATION 라벨 사용.
- `GET /api/v1/targets/local-mssql/incidents/{incidentId}/investigation`
- [v0.6 검증 안내](docs/PHASE6_INCIDENT_INVESTIGATION.md)
- v0.6은 로컬 MSSQL 통합 검증 전이며, 기존 v0.5.0은 main에 보존됨.

## Phase 7 — Wait Diagnostics (개발 중)

- DBPulse Wait Diagnostics: MSSQL `sys.dm_exec_requests`에서 최대 100개의 실제 대기 요청을 5초마다 수집.
- Wait Type, 안전하게 분류된 Resource Kind, 대기 시간, 차단 세션 ID를 PostgreSQL 7일 보존.
- `GET /api/v1/targets/local-mssql/waits`, `/waits/history?limit=20`.
- React: **Wait Diagnostics** 현재 상태·Wait 종류·관측 이력.
- [설계·개발 제한과 로컬 검증 절차](docs/PHASE7_WAIT_DIAGNOSTICS.md).
- 별도 개발 브랜치에서 로컬 MSSQL 통합 검증 대기. `main`은 v0.6.0 유지.

## Phase 8 — Incident Wait Correlation (개발 중)

- Incident Investigation의 과거 시간 범위에 맞춰 Wait 수집 기록을 별도로 연결.
- 유형별 반복 관측 횟수, 유형별 최대 대기, 최근 세션별 Wait 표본.
- 기록된 Wait 수집 시점 수와 100건 수집 한계에 걸린 시점 수도 표시.
- `GET /api/v1/targets/local-mssql/incidents/{incidentId}/investigation` 에 `waitEvidence` 추가.
- [설계 및 검증 절차](docs/PHASE8_INCIDENT_WAIT_CORRELATION.md)
- 개발 브랜치에 있으며 로컬 MSSQL 통합 검증 전. 기존 v0.7.0은 main에 유지.

## Phase 9 — SQL Tuning Advisor (metric-based triage, 개발 중)

- 기존 SQL Server Query Store 수집값을 사용해 query_id/plan_id별 **우선 점검 / 점검 권장 / 관찰** 분류.
- 실제 관측값: 평균 실행 시간, CPU, 논리 읽기, Query Store 집계 실행 횟수.
- 읽기 전용 `GET /api/v1/targets/local-mssql/query-performance/advisor?limit=10` 추가.
- React **쿼리** 메뉴에서 후보 정렬, 우선순위 필터, 점검 근거·권장 확인 순서 제공.
- **SQL 원문·실행계획·파라미터 미수집**. Key Lookup·Scan·인덱스 변경을 확인된 원인이나 확정된 해결책으로 주장하지 않음.
- [설계·제한사항·로컬 통합 검증](docs/PHASE9_SQL_TUNING_ADVISOR.md).
- 현재 개발 브랜치이며 사용자 로컬 검증 전. v0.8.0은 `main`에 병합됨.

## Phase 10 — Query Store Plan Operator Evidence (개발 중)

- v0.9 SQL Tuning Advisor의 후보를 선택해 **연산 정보 확인**을 누를 때만 실제 Query Store 계획의 연산을 분석.
- `GET /api/v1/targets/local-mssql/query-performance/plan-evidence/{queryId}/{planId}`
- MSSQL 서버 내부에서 ShowPlan XML을 파싱하고 안전하게 분류된 연산 종류·개수만 전달.
- 최대 계획 1MB / 100개 연산, 상위 20개 Query Store 후보 한정, 최신 수집 데이터 요구.
- **SQL 원문, 파라미터, 인덱스/테이블명, 계획 XML을 수집·저장하지 않음.**
- [v0.10 설계·한계 및 실제 SQL Server 검증 방법](docs/PHASE10_PLAN_OPERATOR_EVIDENCE.md).
- 별도 개발 브랜치로 사용자 로컬 통합 테스트 전이며, v0.9.0은 main에 유지.

### 쿼리 화면 최신 실행순 정렬 (v0.10 개선)
- 기본: `DBPULSE / 쿼리`에서 `lastExecutedAt` 최근 실행순
- 선택: 튜닝 우선순위순·평균 실행시간순 (Query Store 표는 읽기량순도 가능)
- 수집: 최근 실행 상위 20개 + 실행 비용 상위 20개, 중복 제거(최대 40개)
- Query Store 수집 및 통계 반영 지연에 따라 방금 실행한 SQL이 보이지 않을 수 있음

## Phase 11 — Query Store × Execution Plan Correlation (개발 중)

- Query Store의 실측 평균 실행 시간·CPU·논리 읽기와 **동일 query_id/plan_id의 확인된 실행계획 연산**을 연결.
- 기존 `GET .../plan-evidence/{queryId}/{planId}` JSON에 `correlation` 항목을 추가해 하위 호환성 유지.
- `Lookup+높은 읽기량`, `Scan+높은 읽기량`, `Sort/Hash+높은 CPU`, `실행 지연`, `DML 관측` 등 근거별 안전한 점검 안내.
- 실행계획 수집 실패 및 100연산 제한 시 추측 결과 금지. **관측의 상관관계만 제공**하고 원인 확정·자동 인덱스 변경은 하지 않음.
- 기존 확정 UI(네이비/미드나잇 다크), Query ID 검색, 모니터링 SQL 패턴 제외 및 모든 수집 API 유지.
- [설계·안전 경계·로컬 통합 검증](docs/PHASE11_PLAN_METRIC_CORRELATION.md).

## Phase 12 — 동일 Query ID의 Plan별 성능 비교 (개발 중)

- 현재 Query Store 수집 범위 내 같은 Query ID의 **여러 Plan ID**를 비교합니다.
- 기존 평균 실행시간·CPU·논리 읽기·집계 실행 횟수를 선택한 계획 기준으로 나란히 보여줍니다.
- 단일 계획만 수집됐을 때는 비교 불가 상태를 명확히 표시합니다.
- 5회 미만 표본, 5ms 미만 평균 실행시간은 비교 신뢰도 부족을 표시합니다.
- **실행계획별 집계값의 차이는 시간순 성능 회귀가 확정됐다는 뜻이 아닙니다.**
- API: `GET /api/v1/targets/local-mssql/query-performance/plan-comparison/{queryId}/{planId}`
- [개발·검증 상세](docs/PHASE12_QUERY_PLAN_COMPARISON.md).
- 아직 Draft PR / 로컬 MSSQL 검증 전; v0.11.0이 main 기준 버전.

## v0.12 추가 기능 — Query Store SQL 원문 드로어

- `DBPULSE / 쿼리`의 `SQL 보기` 버튼을 클릭하면 오른쪽 패널에서 **선택한 Query ID/Plan ID의 Query Store SQL 원문** 조회
- 민감한 SQL 원문 노출 방지를 위해 **기본 비활성화**; 로컬 `.env`의 `DBPULSE_QUERY_TEXT_ENABLED=true`로 명시적으로 허용
- 클릭할 때만 DB 조회, SQL 원문 PostgreSQL/로그 미저장, API/브라우저 캐시 방지, 최대 65,536자 및 초과 시 잘림 표시
- Query Store 텍스트는 실제 값이 치환된 원본 배치와 다를 수 있음. 외부 공개 서비스에서 인증 없이 활성화 금지
- [기능·제한·검증 가이드](docs/PHASE12_QUERY_TEXT_DRAWER.md)

## Phase 13 — Monitoring Overhead Guard (개발 중)

- DBPulse 자체 Query Store 수집 비용을 JDBC 수집시간과 Query Store 내부 SQL 실행당 평균 CPU·논리 읽기로 추정하여 표시.
- 2회 연속 부하 징후(또는 반복 실패) 시 **Query Store 통계 폴링만** 기본 60초 → 120초로 완화, 정상 3회 뒤 복귀.
- Incident/Blocking/Deadlock/Wait 등 기존 기본 DMV 모니터링(5초)은 유지.
- `GET /api/v1/targets/local-mssql/query-performance/overhead`는 Java 프로세스 메모리 상태만 반환하며 MSSQL 추가 조회 없음.
- Query Store 과거 통계의 집계값이므로 **실제 고객 DB 전체 부하나 성능 영향을 입증하지 못함**. 로컬 환경 외 사용 금지.
- [설계, API, 로컬 검증 및 향후 제약](docs/PHASE13_MONITORING_OVERHEAD_GUARD.md)

## v0.13 추가: 쿼리 CPU 시간 + 선택적 SQL Server CPU 사용률

- 기존 Query Store 쿼리별 평균 CPU(ms), 실행횟수를 목록 상단에서 직접 확인. 집계 CPU 총량은 `avgCpuMs × executionCount`로 추정하며 60초 단위 실시간 실행횟수로 오해하지 않도록 표기.
- 별도 **SQL Server 프로세스 CPU 사용률(%)** 카드: 인스턴스 전체 및 호스트 전체 사용률을 분리 표시. 개별 DB CPU%가 아님.
- `DBPULSE_SQLSERVER_CPU_ENABLED=true` 로컬 명시 허용 시에만, 60초마다 진단 DMV를 조회. 기본 OFF, 권한 부족/데이터 미지원 시 수치 추측 금지.
- Microsoft는 `sys.dm_os_ring_buffers.record`의 내부 XML 호환성을 보장하지 않으므로 실험적 로컬 기능으로 제한; 상용 서비스에는 지원되는 호스트 CPU 메트릭 도입 필요.
- [기능·권한·보안·검증 방법](docs/PHASE13_SQLSERVER_CPU_METRICS.md)

</details>

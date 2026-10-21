# v0.14 — Windows 통합 실행 미리보기 (React + Spring Boot)

## 개발 목표와 현재 범위

기존 개발 단계에서는 PowerShell에서 Spring Boot 백엔드를 실행한 뒤 또 다른 PowerShell에서 Vite(`npm.cmd run dev`)를 실행해야 했습니다. 이 단계에서는 **React 배포 파일을 Spring Boot JAR 내부에 포함**하여 **Windows에서 Java 백엔드 프로세스 하나만 실행하면 화면과 API를 같은 로컬 주소에서 서비스**하도록 만듭니다.

- 화면: `http://127.0.0.1:18081/#targets` (브라우저 해시 경로)
- API: `http://127.0.0.1:18081/api/v1/...`
- 사용자 비밀번호: Windows 사용자 계정의 기존 DPAPI 암호문을 그대로 사용. 재입력/암호화 저장/연결 해제 기능 유지
- 메타데이터: 기존 **Docker PostgreSQL 포트 5544/볼륨 유지**. DBPulse 자체 메타데이터용 PostgreSQL은 **아직 내장되어 있지 않음**
- 독립 실행 .exe 설치 프로그램, Windows 서비스 자동 시작, 다중 DB 동시 수집은 **미구현**
- Docker 백엔드 빌드와 Vite 개발 서버는 계속 사용 가능하며 손대지 않음

## 첫 실행 — Windows PowerShell 개발환경

Windows에서 Java 21 JDK(jar.exe), Node/npm, Docker Desktop이 실행되어 있어야 합니다. Maven은 없어도 Docker Maven 빌더를 사용할 수 있습니다.

```powershell
# 1) 새 실행 버전으로 전환하기 전, 로컬 수정사항부터 확인
Set-Location D:\Projects\dbpulse-platform
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14

# 2) 이 PowerShell 창에서만 로컬 실행 정책 허용
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

# 3) React 테스트/배포 빌드 → Java 통합 JAR 제작 →
#    기존 Docker backend 중지(데이터/볼륨 유지) →
#    Windows 사용자 계정에서 Spring Boot 실행 →
#    준비 완료 후 localhost:18081 브라우저 자동 열기
.\tools\run_dbpulse_windows_ui.ps1
```

**이번부터 `npm.cmd run dev`를 실행할 필요가 없습니다.** 기존 Vite `http://127.0.0.1:5174` 창이 떠 있다면 닫고, **`http://127.0.0.1:18081`**로 들어가세요.

PowerShell 창은 현재 개발용 Java 런처를 관리하므로 열어 두어야 합니다. Ctrl+C 종료 시 백엔드 Java 프로세스도 중지하도록 설계했습니다.

## 로컬 검증 체크리스트

1. `http://127.0.0.1:18081/` 페이지에서 기존 Navy/Midnight Dark 화면이 나오는지 확인.
2. 개발자 도구 Network에서 `/assets/...` JS/CSS 요청이 **같은 18081 포트에서 200**인지 확인 (5174 참조 없어야 함).
3. `/#targets`에서 기존 등록된 `DBPulseLab`과 암호화 자격증명 저장 여부가 유지되는지 확인.
4. `저장된 비밀번호로 모니터링 시작`으로 DB 재접속, 실시간 DMV collector ONLINE 확인. 로컬 자체 서명 인증서 사용 시 **고급 연결 설정의 개발용 TLS 옵션을 명시적으로 선택**.
5. 개요 CPU/요청 지표, Deadlock, Blocking, 성능, Query Store 페이지가 기존대로 표시되는지 확인.
6. `현재 DB 연결 해제` 뒤 동일/다른 DB 재연결하고 이전 이력 혼합이 없는지 확인.
7. 종료·재실행 후 Windows DPAPI 암호문이 유지되고 비밀번호 원문이 HTML/GET API/브라우저 저장소에 나타나지 않는지 확인.

## 빌드 내부 동작

- `frontend`: `npm.cmd install --no-package-lock --no-audit --no-fund` → `npm.cmd run test` → `npm.cmd run build` (Vite dist 생성).
- `backend`: Maven 프로필 `desktop-bundle`이 `../frontend/dist`만 `classpath:/static/` 리소스로 복사, Spring Boot fat JAR 패키징.
- Maven이 없는 Windows에서는 `tools/Dockerfile.windows-ui-bundle`로 **Java 빌드만 수행**. Docker 빌드 컨텍스트는 root `.dockerignore`로 backend/src·pom 및 frontend/dist만 허용하므로 **.env/로컬 보안 파일이 포함되지 않습니다**.
- Java JAR 내부 `BOOT-INF/classes/static/index.html`과 `assets/*.js` 존재 여부를 빌드 스크립트가 검증한 다음 실행합니다.
- 새 GitHub CI 작업은 PostgreSQL 서비스를 띄워 빌드된 JAR의 `GET /`·JS asset·`GET /api/v1/targets`를 **동일한 포트 18081에서 검사**합니다. GitHub Linux CI는 실제 Windows DPAPI 동작까지 증명하지 않습니다.

## 다음 단계(아직 구현하지 않음)

1. 사용자 PC에서 위 **단일 프로세스 통합 실행 회귀 테스트**.
2. Java 21 실행 환경을 포함한 Windows `jpackage` **app-image / exe 런처** 설계와 실행 검증.
3. Windows 시작 메뉴·제거 프로그램·서명된 설치 패키지, OS 업데이트/백업/데이터 경로 정리.
4. Docker PostgreSQL에 의존하지 않는 설치형 메타데이터 데이터베이스 아키텍처를 별도 설계·마이그레이션. **기존 Postgres 볼륨을 절대 삭제하거나 자동 초기화하지 말 것.**

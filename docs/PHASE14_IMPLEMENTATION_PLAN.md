# Phase 14 — Windows 설치형 로컬 웹 대시보드 · MSSQL 직접 등록

> **2026-10-21 제품 방향 변경:** DBPulse는 SaaS가 아닌 Windows PC에서 실행되는 로컬 웹 애플리케이션. 사용자 회원가입/로그인, 도메인/호스팅/클라우드 멀티테넌트는 제품 필수 범위에서 **제외**합니다.

## 제품 계약
- 설치한 PC에서 DBPulse 백엔드(Spring Boot)·프론트엔드(React)·로컬 메트릭 저장소를 실행하고 `http://127.0.0.1`을 통해 접근합니다. 개발 단계는 Docker + Vite; 최종 목표는 단일 Windows 설치기.
- 사용자가 `대상 서버`에서 연결 이름, SQL Server 호스트, 포트, DB명, 읽기 전용 로그인/비밀번호를 입력합니다.
- 연결 테스트는 사용자가 허용한 SQL Server 호스트에 대해서만, 짧은 타임아웃과 **읽기 전용** 연결/SELECT로 수행합니다.
- 로그인은 요구하지 않지만 **로컬 API를 LAN/인터넷에 노출하지 않고**, 브라우저에서의 예기치 않은 POST를 방지해야 합니다. 로컬 소유자=단일 사용자로 가정하며 Windows 사용자 간 접근격리는 설치·서비스 사용자 계정 설계에서 따로 고려합니다.
- SQL Server DB명은 모니터링 대상 라벨이 됩니다. 인스턴스 전체 CPU(%)를 DB별 CPU라고 표시하지 않습니다.

## 14.3 Windows 사용자 DPAPI 비밀번호 저장 (개발 구현)

- **서버 OS 경계:** native Windows signed-in user JVM에서만 `DBPULSE_WINDOWS_DPAPI_ENABLED=true`로 실행 시 DPAPI 자격증명 암호화 사용. Docker/Linux JVM 또는 Windows 서비스 공용 계정은 **암호화 저장 기능을 제공하지 않음**.
- **암호화 저장 형식:** 현재 사용자 `%LOCALAPPDATA%\\DBPulse\\credentials\\<UUID>.dpapi`에 DPAPI 보호 바이트만 저장. 파일 이름 UUID 검증, 심볼릭 링크 차단, 임시 암호문 파일 및 원자적 교체, 복호화 바이트 배열 초기화. Java String은 JDBC 수명 동안 메모리에 있을 수 있으며 영구 평문 파일/HTTP 응답은 금지.
- **저장 API:** 현재 활성/검증된 UI 대상만 `remember-credential`로 명시적 저장. `GET credential-capability`은 지원 여부만 반환. Registry GET에는 비밀 대신 `savedCredential` 불리언만 추가.
- **재접속 API:** `/{id}/start-saved`는 등록 UUID를 서버 저장소에서 읽고 동일한 허용 호스트/TLS 정책과 읽기 전용 DB 접속 검증 후 대상 활성화. 실행 중 활성 DB가 있으면 409. 브라우저에는 비밀번호를 돌려주지 않음.
- **삭제 API:** `forget-credential`로 암호문만 삭제, 등록 해제 시 연결 정보 행 제거 전에 해당 암호문 정리. 원본 MSSQL 데이터·기존 메트릭 이력은 절대로 삭제하지 않음.
- **Windows 실행 모드:** 개발 PowerShell 도구 `tools/run_dbpulse_windows_host_lab.ps1`는 Docker 메타 PostgreSQL만 유지하고 기존 Docker backend를 멈춘 뒤, Windows 사용자 계정으로 Java 21 API를 loopback에 실행. Maven 미설치 시 Docker Builder stage를 사용. 프론트엔드는 기존 `npm.cmd run dev`.
- **다음 필요한 개발:** 실제 Windows DPAPI 저장/복호화/계정 변경/잠금 테스트, 내장된 React 정적 파일·Java 런타임·로컬 메타 저장소를 묶은 Windows 패키징, 사용자의 명시적인 자동재연결 선택 옵션(현재는 버튼으로 수동 연결).
- 보안/실행 안내: `docs/PHASE14_WINDOWS_DPAPI_HANDOFF.md`. GitHub CI는 Linux에서 **DPAPI 네이티브 복호화를 테스트하지 못합니다**. Windows 수용검증 전에는 Draft 유지.

---

## 14.2 등록된 DB 관리 — 표시 이름 변경·등록 해제 (2026-10-21)

- `POST /api/v1/local-targets/{id}/rename` : 화면 표시 이름만 변경합니다(`name`, 최대 100자). 기존 `target_id`, 호스트/포트/DB명/로그인, 이력과 수집기의 연결 동작은 유지합니다.
- `POST /api/v1/local-targets/{id}/unregister` : **등록 테이블의 해당 UUID 메타데이터 한 행만 삭제**합니다. SQL Server의 실제 데이터베이스나 테이블을 삭제하지 않으며, DBPulse의 과거 메트릭/Deadlock/Query Store/인시던트/Wait 이력도 삭제하지 않습니다. 단, 나중에 같은 호스트/DB를 새로 등록하면 새 UUID이므로 과거 이력은 새 대상의 현재 이력으로 자동 합쳐지지 않습니다.
- **활성 DB 수정/삭제 차단:** 해당 UUID가 현재 UI 수집 대상이면 두 작업 모두 HTTP 409. 먼저 모니터링을 안전하게 해제해야 합니다. `local-mssql` 같은 기존 대상 또는 UUID가 아닌 ID는 허용하지 않습니다.
- **API 보안:** 기존 localhost 커스텀 헤더 `X-DBPulse-Local-Request: 1`/Origin 정책을 동일 적용. 표시 이름 서버 측 검증/정규화, 대상 삭제 전 사용자 확인, 비밀번호 전송/저장 없음. 미등록 UUID는 404.
- **UI:** 등록된 대상의 카드 클릭은 기존처럼 연결 선택; 별도의 `수정`·`삭제` 버튼을 제공해 중첩 버튼을 피함. 활성 항목 버튼은 비활성화. 작은 화면에서도 별도의 관리 버튼 줄 제공.
- **연결 정체성 변경:** 호스트·DB명·포트·로그인 변경은 수집 이력을 혼동시키지 않도록 기존 행에서 직접 변경할 수 없습니다. 새 연결을 검증하고 등록한 후 구 등록을 해제하는 흐름으로 수행합니다.
- **테스트:** 서버 유효 UUID/활성 보호/legacy 보호/명칭 검증/등록 행만 DELETE, 로컬 비활성화/미등록 404. React 액션·확인창·보안 메시지·Navy/Midnight 테마 회귀. 실제 Windows 수용검증은 계속 필요합니다.

---

## 14.1 안전한 UI 연결 해제·등록 DB 다시 연결 (2026-10-21)

- **백엔드 재시작 버튼 대신 안전한 수집기 정지:** `POST /api/v1/local-targets/stop-monitoring` 에서 사용자가 선택한 활성 DB의 ID를 검사한 뒤 중지합니다. 운영체제 명령 실행이나 Docker 소켓 접근은 절대 필요하지 않습니다. `DBPULSE_MSSQL_ENABLED=true`로 실행 중인 기존 고정 DB는 브라우저에서 중지할 수 없습니다.
- **진행 중 쿼리 보호:** 공통 `MssqlTargetProperties`의 공정한 읽기/쓰기 락을 사용합니다. DMV·Blocking·Deadlock·Wait·Query Store·CPU 수집 작업과 SQL 텍스트/실행계획 상세 조회는 읽기 락, 연결 해제와 새 대상 활성화는 쓰기 락을 잡습니다. 완료되지 않은 수집 작업이 끝나기 전에는 연결 비밀·DB ID가 바뀌지 않습니다.
- **상태 초기화:** 연결 해제/새 활성화 시 수집 상태, Query Store adaptive guard/통계, Incident 연속 샘플 판단 상태를 초기화합니다. PostgreSQL 이력이나 등록 DB 행은 삭제하지 않습니다. 백엔드를 중지하는 동작이 아니며 기존 서버 프로세스는 계속 실행됩니다.
- **등록 목록을 실제 선택 UI로:** `GET /api/v1/local-targets`에서 등록 DB의 호스트/포트/DB명/읽기 전용 로그인 이름을 반환합니다(비밀번호는 절대 반환하지 않음). 목록을 클릭하면 해당 필드가 자동 입력되고, 비밀번호 재입력 후 `선택한 DB 모니터링 시작` 버튼을 누르면 연결 검증을 자동 수행한 뒤 수집을 시작합니다. 목록에서 선택했다는 사실만으로 인증되지 않은 DB 접속을 시작하지 않습니다.
- **현재 제한:** 한 번에 DB 1개만 활성 모니터링합니다. 여러 DB를 동시에 돌리는 작업, Windows Credential Manager/DPAPI를 통한 암호화 자격증명 영구 저장 및 비밀번호 없는 자동 재연결은 후속 구현입니다.
- **검증:** 정상 A→연결 해제→등록 B 전환, 연결 해제 전 기존 쿼리 종료 기다림, 옛 DB 지표가 새로운 ID로 저장되지 않음, CSRF/Origin 보호, 기존 `local-mssql` 브라우저 해제 거부, 브라우저 버튼 연타/실패 상태 복구, 테마 및 좁은 화면 확인.

---

## 14.1 안정화 — 고급 TLS 설정·연결 진단·수집 상태 (2026-10-21)

- **UI:** TLS 서버 인증서 검증을 기본으로 유지하고, "로컬 개발용 자체 서명 인증서 허용"은 기본적으로 접힌 `고급 연결 설정`에 이동. 이 항목을 선택하면 백엔드의 `DBPULSE_LOCAL_TRUST_SELF_SIGNED=true`도 필요하며 localhost/host.docker.internal 테스트에서만 사용할 수 있습니다. 외부/운영 DB에 대한 인증서 검증을 자동으로 해제하지 않습니다.
- **연결 실패 코드:** 인증 오류·DB 접근 거부·TCP 연결 문제·타임아웃·TLS 협상 및 인증서 신뢰 오류를 가능한 근거 안에서 구분합니다. SQLState `08`만으로 TCP 장애라고 확정하지 않고, TLS 오류가 인증서 때문인지 모르면 일반 TLS 협상 오류로 표시합니다. SQL 원문·JDBC 예외 메시지·계정 비밀번호를 API에 반환하지 않습니다.
- **반복 연결:** 접속 중 동시 시도를 Semaphore로 제한하고, **실패한 연결**은 1.5초 쿨다운을 적용합니다. 연결 테스트 성공 직후 사용자가 `모니터링 시작`을 눌러도 이전의 500ms 대기 제한 때문에 잘못 429를 받지 않습니다.
- **상태 정확도:** `모니터링 시작` 성공은 접속 확인 및 런타임 대상 활성화이며, 첫 5초 DMV 수집 성공과 구별합니다. 최초 수집 전 `INITIALIZING` 표시, 이후 `/api/v1/targets` 실제 `ONLINE/OFFLINE` 상태를 화면에서 5초마다 확인합니다. 활성화만으로 `수집 정상`이라고 표시하지 않습니다.
- **회귀:** 기존 DBPulseLab 이력 보존, UUID 분리, 단일 활성 대상/명시적 안전한 연결 해제, 비밀번호 영구 미저장, 기존 Navy/Midnight Dark UI 유지. Java/React 자동 테스트 추가.
- **로컬 확인 대기:** TLS 신뢰 인증서/개발용 자체 서명 시나리오, OFFLINE→ONLINE 표시, 비밀번호가 재시작 후 남지 않는지, 시작 버튼 연속 클릭, 실측 Query Store/Blocking/Wait 회귀 확인.

## 14.1 추가 구현 — UI에서 실제 1개 MSSQL 수집 시작 (2026-10-21)

**사용자 피드백:** 기존 `.env`의 `DBPulseLab` 자동 수집을 끄고, 대상 서버 화면에 입력한 DB를 실제 라이브 모니터링 대상으로 사용하면서 직접 시험할 수 있어야 함.

- 기존 DBPulseLab 이력과 PostgreSQL volume은 **삭제하지 않습니다**. 기존 `local-mssql`의 수집을 `.env`의 `DBPULSE_MSSQL_ENABLED=false`로 중지합니다. 기존 DB 로그인/비밀번호 값도 `.env`에서 비워 보관하지 않습니다.
- 사용자는 `대상 서버` 폼에서 승인된 SQL Server 호스트/포트/DB명/읽기 전용 로그인/비밀번호를 입력하고 `연결 테스트` 이후 `선택한 DB 모니터링 시작`을 누릅니다.
- 신규 `POST /api/v1/local-targets/start-monitoring`: 방금 입력한 DB 연결을 다시 확인하고 registry의 고유 ID(없으면 생성)를 사용해 **메모리에서만** `MssqlTargetProperties`를 단 한 번 활성화. 5초 DMV·Blocking/Wait, Deadlock, Query Store 및 선택 CPU 수집기가 동일한 새 DB 설정을 참조합니다.
- 새 연결 대상의 **UUID가 메트릭/인시던트/Query Store API 키**가 되어 기존 `local-mssql` 이력과 섞이지 않습니다. 기존 `local-mssql` API는 별도 구버전 데이터로 남지만, 현재 UI는 새 활성 대상 ID를 사용합니다.
- SQL Server 로그인 비밀번호는 **실행 중인 Java 백엔드 메모리에만 유지**하고 PostgreSQL/브라우저 저장소/로그에는 저장하지 않습니다. 백엔드를 다시 실행하면 연결 비밀이 사라지고 사용자가 다시 입력해야 합니다. 이는 아직 Windows DPAPI 암호화 자격증명 저장을 구현하지 않은 임시 단계입니다.
- 이미 `.env`에 활성 연결이 있으면 시작을 거부합니다(HTTP 409). 또한 **새 DB로 변경하기 전에는 현재 DB를 화면에서 안전하게 연결 해제해야 합니다**. 공통 수집 락을 이용해 진행 중인 쿼리가 끝나야 전환되며, 서버 재시작은 필요하지 않습니다.
- `SELECT DB_NAME()` 성공만으로 서버의 DMV·Deadlock/Query Store 조회 권한을 보증하지 않습니다. 시작 후 개요의 **수집 ONLINE/OFFLINE** 및 쿼리 분석 상태를 별도로 확인해야 합니다.
- **자동 복구·여러 DB 동시 수집·암호화된 비밀번호 영속 저장은 미구현**. 운영 DB에 검증 없이 사용하지 않습니다.

### 새 엔드포인트
`POST /api/v1/local-targets/start-monitoring`: 기존 연결 테스트와 동일한 JSON 입력 및 `X-DBPulse-Local-Request: 1` 요구. 반환 `ACTIVE_IN_MEMORY` 및 새 대상 ID. 기존 대상 수집 중이면 HTTP 409. 연결 해제 뒤에 다시 실행할 수 있습니다.

### 실제 로컬 검증 항목
1. `DBPULSE_MSSQL_ENABLED=false` 적용 후 `/api/v1/targets`가 `enabled=false` 및 `NOT_CONFIGURED` 상태인지 확인.
2. 브라우저에서 이름·서버·포트·테스트 DB 입력 후 연결 테스트 성공.
3. **모니터링 시작** 누른 뒤 `/api/v1/targets` ID가 이전 `local-mssql`이 아닌 새 UUID이고, DB명은 입력값인지 확인.
4. 5~15초 이내 DMV 수집 ONLINE 및 새 ID의 스냅샷 존재(권한·부하에 따라 값은 0일 수 있음).
5. SQL Server Query Store가 활성화된 테스트 DB에서 60~120초 후 쿼리 분석을 확인. Query Store 비활성/권한 부족이면 표시할 수 없음을 확인.
6. 기존 `local-mssql`의 메트릭·인시던트 이력이 새 ID에 섞이지 않음을 확인.
7. 활성 대상이 있으면 다른 DB 시작 시 HTTP 409. `stop-monitoring` 이후 기존 PostgreSQL 기록을 보존하며 등록한 다른 DB에 연결. 백엔드 재시작 시 비밀번호는 남지 않음.
8. 시스템 상태 값과 사용자 제공 비밀번호가 로그나 API 응답에 노출되지 않음을 확인.

---

## 첫 번째 실제 코드 단계: 14.1 로컬 테스트 / 대상 메타데이터 등록
- `POST /api/v1/local-targets/test-connection`: 검증된 호스트·포트·DB명·SQL 로그인·비밀번호를 **한 번만 사용**하고 연결 결과(성공/권한·TLS·네트워크 실패 코드)를 반환합니다. 무분별한 접속/비밀 출력 방지를 위해 해당 API는 기본 **비활성**, 허용 호스트 목록과 로컬 전용 헤더/Origin 검사 필요.
- `POST /api/v1/local-targets`: 같은 읽기 전용 연결 검증 성공 시 **이름·호스트·포트·DB명·로그인 등 비밀이 아닌 연결 메타데이터만** PostgreSQL에 기록. 비밀번호/자격증명 저장 **없음**. 저장 상태는 `REGISTERED_INACTIVE`이고 모니터링은 시작되지 않음.
- `GET /api/v1/local-targets`: 로컬에서 등록한 메타데이터만 조회하고 로그인/패스워드·JDBC URL 원문은 응답하지 않습니다.
- 연결 설정 `DBPULSE_LOCAL_TARGETS_ENABLED=false` 기본값; 허용 호스트 기본값은 `host.docker.internal,localhost,127.0.0.1`, 로컬 개발용. 사내/원격 SQL Server를 사용하려면 사용자가 명시적으로 서버 측 허용 목록에 추가합니다.
- 기존 `/api/v1/targets`의 `local-mssql` 실시간 수집기·이력·Query Store는 변경 없이 유지합니다.
- 로컬 개발 SQL Server의 자체 서명 TLS는 서버 `DBPULSE_LOCAL_TRUST_SELF_SIGNED=true` 와 폼의 명시적 체크가 모두 있어야 허용. `host.docker.internal`, `127.0.0.1`, `localhost`에서만 가능하며 원격/운영 호스트는 무조건 인증서 검증 유지.
- UI에서는 `연결 확인`과 `연결 검증 후 대상 등록`을 표시합니다. 등록 정보가 **현재 실시간 모니터링에 연결된 것처럼 표현하지 않습니다**.

## 다음 실제 코드 단계: 14.2 영속 자격증명 및 동적 Collector 연결
- Windows DPAPI/Credential Manager 또는 Windows 사용자 경계와 결합된 암호화 저장소로 실제 연결 비밀 보호. Docker 개발 컨테이너와 최종 Windows 설치 프로세스 간의 보안 모델이 다르므로 **검토 없이 평문 DB 저장, localStorage 또는 기기 공용 키 재사용 금지**.
- 암호화된 비밀을 복구해 등록한 대상별 JDBC 연결/collector를 안전하게 시작·중지하며 활성 대상 선택 기능을 구현.
- 1:N 다중 DB 수집에서 `target_id`별 스냅샷·사건·Query Store·CPU 관련 단위/권한 경계 유지.
- DB가 VPN/방화벽 뒤에 있으면 사용자 PC에서 명시적으로 접근할 수 있어야 함.

## 이후 단계
- 14.3: 로컬 시작 관리자, 안전한 백업/마이그레이션, Windows 사용자별 보안/재설치/삭제.
- 14.4: Docker 없이도 동작하는 Windows 설치기 + 로컬 브라우저 자동 실행(React 정적 빌드 제공).
- 검증: 잘못된 인증/TLS/허용되지 않은 호스트/비밀번호 비노출/중복 대상/기존 `DBPulseLab` 회귀 테스트. 개발기능을 인터넷에 공개하지 않음.

## 현재 미완료
비밀번호 **영구 보안 저장**, 비밀번호 없는 자동 재연결, 복수 DB **동시 수집**, 설치 파일은 **아직 미구현**입니다. 한 번에 1개 DB의 수집을 화면에서 해제/전환할 수 있습니다.

# Phase 14 — Windows 설치형 로컬 웹 대시보드 · MSSQL 직접 등록

> **2026-10-21 제품 방향 변경:** DBPulse는 SaaS가 아닌 Windows PC에서 실행되는 로컬 웹 애플리케이션. 사용자 회원가입/로그인, 도메인/호스팅/클라우드 멀티테넌트는 제품 필수 범위에서 **제외**합니다.

## 제품 계약
- 설치한 PC에서 DBPulse 백엔드(Spring Boot)·프론트엔드(React)·로컬 메트릭 저장소를 실행하고 `http://127.0.0.1`을 통해 접근합니다. 개발 단계는 Docker + Vite; 최종 목표는 단일 Windows 설치기.
- 사용자가 `대상 서버`에서 연결 이름, SQL Server 호스트, 포트, DB명, 읽기 전용 로그인/비밀번호를 입력합니다.
- 연결 테스트는 사용자가 허용한 SQL Server 호스트에 대해서만, 짧은 타임아웃과 **읽기 전용** 연결/SELECT로 수행합니다.
- 로그인은 요구하지 않지만 **로컬 API를 LAN/인터넷에 노출하지 않고**, 브라우저에서의 예기치 않은 POST를 방지해야 합니다. 로컬 소유자=단일 사용자로 가정하며 Windows 사용자 간 접근격리는 설치·서비스 사용자 계정 설계에서 따로 고려합니다.
- SQL Server DB명은 모니터링 대상 라벨이 됩니다. 인스턴스 전체 CPU(%)를 DB별 CPU라고 표시하지 않습니다.

## 14.1 추가 구현 — UI에서 실제 1개 MSSQL 수집 시작 (2026-10-21)

**사용자 피드백:** 기존 `.env`의 `DBPulseLab` 자동 수집을 끄고, 대상 서버 화면에 입력한 DB를 실제 라이브 모니터링 대상으로 사용하면서 직접 시험할 수 있어야 함.

- 기존 DBPulseLab 이력과 PostgreSQL volume은 **삭제하지 않습니다**. 기존 `local-mssql`의 수집을 `.env`의 `DBPULSE_MSSQL_ENABLED=false`로 중지합니다. 기존 DB 로그인/비밀번호 값도 `.env`에서 비워 보관하지 않습니다.
- 사용자는 `대상 서버` 폼에서 승인된 SQL Server 호스트/포트/DB명/읽기 전용 로그인/비밀번호를 입력하고 `연결 테스트` 이후 `선택한 DB 모니터링 시작`을 누릅니다.
- 신규 `POST /api/v1/local-targets/start-monitoring`: 방금 입력한 DB 연결을 다시 확인하고 registry의 고유 ID(없으면 생성)를 사용해 **메모리에서만** `MssqlTargetProperties`를 단 한 번 활성화. 5초 DMV·Blocking/Wait, Deadlock, Query Store 및 선택 CPU 수집기가 동일한 새 DB 설정을 참조합니다.
- 새 연결 대상의 **UUID가 메트릭/인시던트/Query Store API 키**가 되어 기존 `local-mssql` 이력과 섞이지 않습니다. 기존 `local-mssql` API는 별도 구버전 데이터로 남지만, 현재 UI는 새 활성 대상 ID를 사용합니다.
- SQL Server 로그인 비밀번호는 **실행 중인 Java 백엔드 메모리에만 유지**하고 PostgreSQL/브라우저 저장소/로그에는 저장하지 않습니다. 백엔드를 다시 실행하면 연결 비밀이 사라지고 사용자가 다시 입력해야 합니다. 이는 아직 Windows DPAPI 암호화 자격증명 저장을 구현하지 않은 임시 단계입니다.
- 이미 `.env`에 활성 연결이 있으면 시작을 거부합니다(HTTP 409). 또한 **한 번 시작한 DB를 같은 백엔드 프로세스에서 다른 DB로 변경할 수 없습니다**. 변경하려면 백엔드를 재시작 후 다시 연결해야 하며, 그래야 이전 DB의 수집 상태·Incident/Wait 추적기가 혼합되지 않습니다.
- `SELECT DB_NAME()` 성공만으로 서버의 DMV·Deadlock/Query Store 조회 권한을 보증하지 않습니다. 시작 후 개요의 **수집 ONLINE/OFFLINE** 및 쿼리 분석 상태를 별도로 확인해야 합니다.
- **자동 복구·실시간 DB 전환·암호화된 비밀번호 영속 저장은 미구현**. 운영 DB에 검증 없이 사용하지 않습니다.

### 새 엔드포인트
`POST /api/v1/local-targets/start-monitoring`: 기존 연결 테스트와 동일한 JSON 입력 및 `X-DBPulse-Local-Request: 1` 요구. 반환 `ACTIVE_IN_MEMORY` 및 새 대상 ID. 재시작 또는 기존 대상 수집 중이면 HTTP 409.

### 실제 로컬 검증 항목
1. `DBPULSE_MSSQL_ENABLED=false` 적용 후 `/api/v1/targets`가 `enabled=false` 및 `NOT_CONFIGURED` 상태인지 확인.
2. 브라우저에서 이름·서버·포트·테스트 DB 입력 후 연결 테스트 성공.
3. **모니터링 시작** 누른 뒤 `/api/v1/targets` ID가 이전 `local-mssql`이 아닌 새 UUID이고, DB명은 입력값인지 확인.
4. 5~15초 이내 DMV 수집 ONLINE 및 새 ID의 스냅샷 존재(권한·부하에 따라 값은 0일 수 있음).
5. SQL Server Query Store가 활성화된 테스트 DB에서 60~120초 후 쿼리 분석을 확인. Query Store 비활성/권한 부족이면 표시할 수 없음을 확인.
6. 기존 `local-mssql`의 메트릭·인시던트 이력이 새 ID에 섞이지 않음을 확인.
7. 다른 DB로 시작하려는 요청은 HTTP 409, 백엔드 재시작 시 기본 미연결로 돌아옴. 등록 메타데이터는 남지만 비밀번호는 남지 않음.
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
등록한 대상의 비밀번호 **영구 보안 저장**, 자동 재연결, 실행 중 다중 DB 전환/동시 수집, 설치 파일은 **아직 미구현**입니다. 단일 DB를 UI에서 활성화하는 기능은 현재 백엔드 프로세스 수명 동안만 지원합니다.

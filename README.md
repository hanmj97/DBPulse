# DBPulse — Real-time Database Monitoring, Recovery & Optimization Platform

> **실제 DB 운영 서비스**를 목표로 개발하는 프로젝트. MSSQL 우선 구현 후 다른 DBMS 지원 확장.

## 제품 핵심 목표

1. **장애 감지·원인 파악·안전한 자동복구** — Blocking/Deadlock/연결 장애 등, 위험 조치는 승인 후 실행
2. **쿼리·트랜잭션 성능 분석** — 실시간/누적 통계, 실행계획, Wait, Lock, 성능 변화
3. **저성능 SQL 진단·튜닝 추천** — 쿼리 자동 수집, 원인 분석, 개선안 및 검증

[불변 프로젝트 헌장](docs/PROJECT_CHARTER.md) · [아키텍처](docs/ARCHITECTURE.md) · [로드맵 및 검증 기준](docs/ROADMAP.md)

## 현재 개발 버전: v0.10.0-alpha / Query Store 실행계획 연산 분석 (로컬 검증 전)

작동하는 **제품 기본 흐름**: React 화면 → Spring Boot REST API → PostgreSQL 메트릭 저장소 ← SQL Server DMV Collector.

- MSSQL에서 `sys.dm_exec_requests`를 읽어 현재 활성 요청, Blocking, 장시간 실행을 매 5초 집계.
- 실제 데이터만 PostgreSQL `metric_snapshots` 테이블에 저장.
- 대시보드에 3초 간격으로 마지막 상태와 최근 샘플을 표시.
- 설정 누락/연결 실패 시 `NOT_CONFIGURED` / `OFFLINE` 표시. 가짜 성능 값 없음.
- **Phase 2 구현 코드**: SQL Server Query Store의 최근 15분간 실행계획별 요약 통계를 30초마다 수집해 PostgreSQL에 기록, React에서 조회. SQL 원문·파라미터는 저장하지 않음. 운영 수준의 튜닝 추천이 아니라 수치 기반 점검 후보를 보여줌. 로컬 환경의 통합 검증은 진행 중.
- **아직 미구현**: 회원가입, 외부 고객 DB 등록, 다중 사용자, Deadlock 이벤트 상세 수집, 자동복구, 실제 SQL 튜닝 추천. UI에 '예정'으로 구분.

> ⚠️ **현재 버전은 로컬 개발용으로만 실행하세요.** 사용자 인증과 외부 접근 통제가 아직 없으며 공개 서비스용 보안 구성이 아닙니다. 외부 고객 DB 연결은 추후 별도 보안 설계 이후 지원합니다.

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

# Vite 개발 서버 시작 (이 창은 열린 상태로 유지)
npm run dev
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

# DBPulse 단계별 개발 및 인수 기준

| Phase | 제품 기능 | 완료 판정 |
|---|---|---|
| 1 | API, 저장소, MSSQL Collector, 대시보드 | 실제 SQL Server 활성 세션을 수집하고 화면에 반영. 끊김 시 OFFLINE 처리 |
| 2 | 실시간 분석 강화 | DMV + Query Store, SQL 지문/실행계획/Wait/Blocking 체인 구분 및 성능 비교. 개인정보/SQL 리터럴 마스킹 |
| 3 | 회원·연결 관리 | 인증, 사용자별 복수 DB, 비밀정보 암호화/회전, 최소 권한 검증, 접근격리/SSRF 방지 |
| 4 | 장애 탐지 엔진 | 장애 규칙, 알림, Deadlock 이벤트, 오류율, 중복 경보 억제, 사고 이력/재현 테스트 |
| 5 | 안전한 복구 정책 | 자동 재접속, 사전 검증된 실행계획 복귀 등 승인 정책/감사/롤백 테스트 |
| 6 | SQL Tuning Advisor | 느린 쿼리 자동 분류, 실행계획 비교, 후보 인덱스/SQL 추천, 환경별 A/B 검증 |
| 7 | 부하·장애 통합 시험 | 격리된 전용 DB에서 10초당 1,000~2,000 SQL 실행(목표), Blocking/Deadlock 재현, 정확도·오버헤드 기록 |
| 8 | 외부 사용자 배포 | HTTPS, 안전한 수집 에이전트, 운영 모니터링/백업/장애 대응, 실제 온보딩 검증 |

## Phase 1 검증 목록
- [ ] `docker compose up --build -d` 성공
- [ ] `/api/v1/health`에서 서비스 정보 응답
- [ ] MSSQL 비활성화 시 대시보드에 '설정 필요' 표시
- [ ] SSMS에서 `DBPulseLab` 생성하고 제한적 모니터 계정 준비
- [ ] `.env` 등록, `docker compose up -d --force-recreate backend` 후 ONLINE 전환
- [ ] SSMS에서 긴 SELECT 실행 시 실제 active/long-running 지표 관찰
- [ ] MSSQL 접근 중단 시 상태 OFFLINE(이전 기록 그대로 유지)
- [ ] `metric_snapshots`에 원본 표본 시간과 집계 값 저장 확인

## 추후 핵심 테스트 방법
- 처리량 QPS/TPS는 부하 발생기/Query Store의 완료 건수로 검증하고, DMV의 Active requests를 QPS로 오인하지 않는다.
- 수집기로 인한 운영 DB 부하는 별도로 측정한다.
- 네트워크 장애, 잠금 충돌, 권한 부족 등의 실패를 개별 유형으로 처리한다.

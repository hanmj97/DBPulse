# DBPulse Phase 15–16 — SQLite Desktop Migration

## 구현 및 검증 완료

- PostgreSQL 메타데이터 10개 테이블 SQLite 이관
- 기존 43,471건 데이터 무결성 검증
- MSSQL 실제 수집 데이터 SQLite 저장 확인
- 신규 33건 저장 및 기존 데이터 보존 확인
- Docker PostgreSQL 없이 Windows EXE 실행
- SQLite 영구 저장소 및 재시작 후 데이터 유지
- SQLite 백업 및 임시 복원 검증
- WAL, Commit, Rollback, 비정상 종료 복구 실험
- Windows 사용자별 DPAPI 자격증명 보호 유지

## 검증 범위 및 제한

- 로컬 SQL Server Developer 환경에서 검증
- SQLite 실수집 확인: 43,471건 → 43,504건
- Query Store 신규 저장 0건: 추가 검증 필요
- WAL 강제 종료 테스트는 별도 Python 실험 환경에서 수행
- 실제 Java 앱 강제 종료 복구는 별도 검증 필요
- 현재 EXE는 콘솔 + 브라우저 실행 방식
- Windows 설치 프로그램은 아직 미구현
- 일반 SQL Server의 DB별 CPU 점유율은 아직 미지원
- 외부 사용자 대상 운영 환경 검증은 미완료

## 후속 개발

- SQLite 자동 백업 및 보존 정책
- 독립 Windows 프로그램 창
- 콘솔 없는 실행과 안전한 종료
- Windows 설치 마법사 및 바탕화면 바로가기
- 선택 DB의 Query Store CPU 시간 분석
- 보안, 성능, 장시간 운영 검증 및 정식 릴리스

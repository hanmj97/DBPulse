# DBPulse — 최종 프로젝트 정리 (2026-10-22)

## 릴리스 현황

- **최종 패키지 버전:** v0.21.3
- **플랫폼:** Windows x64, 로컬 WebView2 데스크톱 UI + Spring Boot API + SQLite
- **배포:** [GitHub Release v0.21.3](https://github.com/hanmj97/DBPulse/releases/tag/v0.21.3)
- **아티팩트:** `DBPulse-Setup-v0.21.3.exe`, `SHA256SUMS.txt`
- **형태:** 공개 다운로드 가능한 미서명 Windows 설치 프로그램
- **최종 수정:** 같은 사용자 세션에서 바로가기를 다시 눌러도 기존 DBPulse 창을 복원·활성화하며 창을 추가로 만들지 않는 단일 인스턴스 실행

## 구현 기능

1. SQL Server 연결 대상 등록·테스트·선택 및 명시적 모니터링 시작
2. DMV 기반 실시간 활성 요청, Blocking 및 장시간 실행 요청 수집
3. Blocking Chain, Deadlock, Wait Diagnostics, Incident Investigation
4. Query Store 기반 실행 이력·실행계획·CPU 시간·논리 읽기 및 SQL 튜닝 조사
5. 선택 DB Query Store CPU 작업량 지표와 인스턴스 CPU 정보 구분 표시
6. SQLite 로컬 이력 및 Windows DPAPI 자격증명 보호, 백업·복구 검증 도구
7. 통합 Windows 설치기, 전용 앱 아이콘, 반응형 초기 창 크기 및 단일 창 실행

## 최종 배포 검증

- GitHub에서 **v0.21.3 릴리스 공개 상태**, Setup EXE 및 체크섬 파일이 존재함을 확인했습니다.
- 사용자 보고: 최종 점검 항목을 통과했습니다. 단, 공개된 CI 성공이나 사용자 보고가 모든 타사 PC·운영 DB·장시간 부하 환경에서의 안전성을 증명하는 것은 아닙니다.
- Windows 설치형 로컬 제품의 개발과 포트폴리오용 공개 배포를 **완료** 상태로 관리합니다.

## 명시적인 제한

- 설치 프로그램에 디지털 서명이 없습니다. SHA-256 체크섬은 파일 무결성 검사용입니다.
- 기존에 선택적으로 생략한 60분 연속 검사, 24시간 내구성 시험, 독립 보안 감사, 광범위한 새 PC 검증은 완료로 간주하지 않습니다.
- 선택 DB CPU 부하는 Query Store 기준 **1코어 환산 작업량 지표**로, 정확한 실시간 DB CPU 점유율(%)이 아닙니다.
- SQL 튜닝 추천 및 장애 진단은 판단을 돕는 읽기 전용 정보이며, 안전한 자동복구나 근본 원인 확정을 보장하지 않습니다.
- 로컬 앱을 인터넷에 공개하지 않고, 소유·운영 권한이 있는 SQL Server에서 최소 권한 계정으로 사용해야 합니다.

## 관련 문서

- [README](../README.md)
- [최종 제품 소개 및 아키텍처](RELEASE_OVERVIEW_V0212.md)
- [v0.21.3 변경사항](releases/v0.21.3.md)
- [Windows 설치/데이터 보존 안내](USER_INSTALL_GUIDE_V021.md)
- [검증 범위 및 남은 안전성 항목](RELEASE_READINESS_V021.md)

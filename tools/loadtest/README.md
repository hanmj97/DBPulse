# 격리된 로컬 트래픽 발생 도구 (DBPulse 제품 검증 전용)

이 폴더는 **서비스가 아닌** 제품 검증용 보조 도구다. 실제 사용자 DB에서는 절대 실행하지 않는다.

1. SSMS에서 `sql/03_prepare_load_test_data.sql` 실행: 전용 `DBPulseLab`에 테스트 계정 2만 건 생성 + Query Store 켬.
2. Windows에 Microsoft ODBC Driver 18 for SQL Server 설치, 테스트용 pyodbc 준비.
3. 테스트용 **Windows 인증 계정**에만 테스트 DB 테이블 쓰기 권한을 제공. 모니터링 전용 SQL 계정에는 쓰기 권한을 주지 않는다.

```powershell
# 프로젝트 루트에서 Python 테스트 환경 생성
py -m venv .venv

# 테스트 전용 패키지 설치 (DBPulse API는 Java 기반, 이 패키지 불필요)
.\.venv\Scripts\python.exe -m pip install -r tools\loadtest\requirements.txt

# 로컬 SQL Server에 TCP 포트 1434를 실제로 사용하는 경우 예시
# 기본 Windows 인증, 반드시 DBPulseLab 데이터베이스만 허용됨
$env:DBPULSE_CONN_STR = 'DRIVER={ODBC Driver 18 for SQL Server};SERVER=localhost,1434;DATABASE=DBPulseLab;Trusted_Connection=yes;Encrypt=yes;TrustServerCertificate=yes;APP=DBPulseLabTest;'

# 초당 50 SQL 배치 x 10초 = 목표 500건 (실제 완료 건수는 결과 보고서 확인)
.\.venv\Scripts\python.exe tools\loadtest\load.py --scenario normal --qps 50 --duration 10 --workers 8

# 초당 150 SQL 배치 x 10초 = 목표 1,500건. 별도 DBPulse 웹 화면 관찰
.\.venv\Scripts\python.exe tools\loadtest\load.py --scenario normal --qps 150 --duration 10 --workers 16
```

- 1 SQL 배치를 1회의 발행 호출로 계산하므로 QPS는 서버의 TPS와 구분한다.
- 스크립트가 자체적으로 `DBPulseLab` + localhost + Windows 인증만 허용하고, QPS 최대 300 / 실행 30초 / worker 최대 32로 제한한다.
- SQL Server의 쿼리가 빠르게 종료되면 DMV 5초 스냅샷에 잡히지 않아도 **쿼리 실행이 실패했다는 뜻이 아니다.** 완료 쿼리는 Phase 2에서 Query Store로 집계한다.

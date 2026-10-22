import React from 'react';
import {Cpu, ShieldAlert} from 'lucide-react';

// These are Query Store CPU TIMES attributed to the selected database.
// They are not the OS-level per-database CPU percentage (SQL Server does not
// expose that as an exact per-database on-premises DMV measurement).
export default function SelectedDatabaseCpuPanel({queryState, databaseName, compact = false}) {
  const status = queryState?.status || 'INITIALIZING';
  const fresh = queryState?.lastCollectedAt &&
    Date.now() - Date.parse(queryState.lastCollectedAt) < 180000 &&
    Date.now() - Date.parse(queryState.lastCollectedAt) > -10000;
  const valid = ['ONLINE', 'QUERY_STORE_READ_ONLY'].includes(status) && fresh &&
    queryState?.databaseCpuMs != null;
  const cpuMs = Number(queryState?.databaseCpuMs);
  const executions = Number(queryState?.databaseExecutions);
  const formatted = valid && Number.isFinite(cpuMs) && cpuMs >= 0
    ? cpuMs.toLocaleString('ko-KR', {maximumFractionDigits: 1}) + ' ms' : '—';
  const reason = {
    INITIALIZING:'Query Store 첫 수집을 기다리고 있습니다.',
    NOT_CONFIGURED:'모니터링 대상을 먼저 연결해 주세요.',
    QUERY_STORE_DISABLED:'선택 DB에서 Query Store가 비활성화되어 있습니다.',
    PERMISSION_DENIED:'선택 DB의 Query Store 조회 권한이 부족합니다.',
    OFFLINE:'Query Store 수집이 실패했습니다. 권한 및 수집 로그를 확인해 주세요.',
    QUERY_STORE_READ_ONLY:'Query Store가 읽기 전용 상태입니다.'
  }[status] || (valid ? '' : '선택 DB의 최신 Query Store CPU 표본이 없습니다.');

  return <section className={compact
    ? 'rev-panel rev-ops-chart-card rev-ops-cpu-chart'
    : 'rev-cpu-panel'} aria-label="선택 데이터베이스 Query Store CPU 사용 시간">
    <div className="rev-cpu-panel-heading">
      <div>
        <h3><Cpu size={17}/> 선택 DB 쿼리 CPU 시간 <small>인스턴스 전체 아님</small></h3>
        <p>{databaseName || '현재 연결 DB'} · Query Store에 기록된 완료 쿼리 기준</p>
      </div>
      <span className="rev-cpu-panel-status">{valid?'집계 완료':status}</span>
    </div>
    <div className="rev-cpu-panel-grid">
      <div><span>CPU 누적 시간</span><strong>{formatted}</strong>
        <small>최근 15분 내 실행 기록이 있는 통계 구간의 합계</small></div>
      <div><span>기록된 실행 건수</span>
        <strong>{valid&&Number.isFinite(executions)
          ? executions.toLocaleString('ko-KR'):'—'}</strong>
        <small>Query Store 통계 구간 기준 (새 실행 건수 아님)</small></div>
      <div><span>표본 갱신</span><strong className="rev-cpu-panel-date">{fresh
        ? new Date(queryState.lastCollectedAt).toLocaleTimeString('ko-KR',{hour12:false})
        :'—'}</strong><small>기본 60초 수집, 부하 시 자동 완화</small></div>
    </div>
    {!valid && <p className="rev-cpu-panel-note"><ShieldAlert size={15}/>{reason}</p>}
    <p className="rev-cpu-panel-limitation">
      이 수치는 선택 DB의 Query Store가 포착한 완료 쿼리 CPU 시간을 합산한 값이며
      실시간 CPU 점유율(%)이나 전체 DB 작업의 완전한 사용량이 아닙니다.
      Query Store 집계 구간에는 최근 15분 이전 실행이 포함될 수도 있습니다.
    </p>
  </section>;
}

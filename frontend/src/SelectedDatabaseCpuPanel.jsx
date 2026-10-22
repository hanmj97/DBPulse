import React from 'react';
import {Cpu, ShieldAlert} from 'lucide-react';

// Per-target Query Store CPU chart: one logical core = 100%.
// This is NOT precise per-database host/SQL Server CPU utilization.
function DbCpuTrendGraph({history}) {
  const rows=history.slice(-30);
  const valid=rows.filter(r=>r.corePercent!=null && Number.isFinite(r.corePercent));
  const maximum=Math.max(100,...valid.map(r=>r.corePercent));
  const ceiling=Math.max(100,Math.ceil(maximum/25)*25);
  const y=v=>177-(v/ceiling)*145;
  const x=i=>40+i*480/Math.max(1,rows.length-1);
  const path=[];
  const points=[];
  rows.forEach((row,index)=>{
    if(row.corePercent==null || !Number.isFinite(row.corePercent))return;
    points.push({x:x(index),y:y(row.corePercent),row});
    if(index>0 && rows[index-1].corePercent!=null &&
      Number.isFinite(rows[index-1].corePercent)){
      path.push(<line key={row.at} x1={x(index-1)} y1={y(rows[index-1].corePercent)}
        x2={x(index)} y2={y(row.corePercent)}
        stroke="#ffbd60" strokeWidth="2.5" strokeLinecap="round"/>);
    }
  });
  return <div className="rev-db-cpu-graph" aria-label="선택 DB Query Store 1코어 환산 CPU 추이">
    <div className="rev-db-cpu-graph-title">
      <span>선택 DB CPU 추이 · 1코어 환산 %</span>
      <small>{valid.length}개 유효 표본 · 수집 간격 약 60~120초</small>
    </div>
    <svg viewBox="0 0 560 210" preserveAspectRatio="xMidYMid meet"
      role="img" aria-label="선택 DB의 Query Store 기록 CPU를 논리 CPU 1개 기준으로 환산한 추이입니다. 100%를 넘을 수 있으며 정확한 인스턴스 CPU 점유율이 아닙니다.">
      {[0,0.25,0.5,0.75,1].map(f=><g key={f}>
        <line x1="40" y1={177-145*f} x2="520" y2={177-145*f}
          stroke="var(--t-chart-grid)" strokeWidth="1"/>
        <text x="32" y={181-145*f} fontSize="11" textAnchor="end"
          fill="var(--t-secondary)">{Math.round(ceiling*f)}%</text>
      </g>)}
      {path}
      {points.map(p=><circle key={p.row.at} cx={p.x} cy={p.y} r="3.5" fill="#ffbd60">
        <title>{new Date(p.row.at).toLocaleTimeString('ko-KR',{hour12:false})}
          {' · '}{p.row.corePercent.toFixed(1)}% (1코어 기준)</title>
      </circle>)}
      {valid.length===0 && <text x="280" y="108" textAnchor="middle"
        fill="var(--t-secondary)" fontSize="13">
        Query Store 수집 2회 이상 완료 후 추이가 표시됩니다
      </text>}
      {rows.length>0 && <text x="40" y="199" fontSize="10" fill="var(--t-secondary)">
        {new Date(rows[0].at).toLocaleTimeString('ko-KR',{hour12:false})}
      </text>}
      {rows.length>1 && <text x="520" y="199" fontSize="10" fill="var(--t-secondary)" textAnchor="end">
        {new Date(rows.at(-1).at).toLocaleTimeString('ko-KR',{hour12:false})}
      </text>}
    </svg>
  </div>;
}

export default function SelectedDatabaseCpuPanel({queryState, databaseName, trend = [], compact = false}) {
  const status=queryState?.status || 'INITIALIZING';
  const stamp=queryState?.lastCollectedAt ? Date.parse(queryState.lastCollectedAt) : NaN;
  const fresh=Number.isFinite(stamp) && Date.now()-stamp < 180000 && Date.now()-stamp > -10000;
  const valid=['ONLINE','QUERY_STORE_READ_ONLY'].includes(status) && fresh &&
    queryState?.databaseCpuMs != null;
  const cpuMs=Number(queryState?.databaseCpuMs);
  const executions=Number(queryState?.databaseExecutions);
  const last=trend.at(-1);
  const rateValid=valid && last?.at===stamp &&
    last?.corePercent!=null && Number.isFinite(last.corePercent);
  const rate=rateValid ? last.corePercent.toLocaleString('ko-KR',{maximumFractionDigits:1})+'%' : '—';
  const cpuText=valid && Number.isFinite(cpuMs) && cpuMs>=0
    ? cpuMs.toLocaleString('ko-KR',{maximumFractionDigits:1})+' ms':'—';
  const reason={
    INITIALIZING:'첫 Query Store 수집을 기다리는 중입니다.',
    NOT_CONFIGURED:'모니터링 대상을 연결해 주세요.',
    QUERY_STORE_DISABLED:'선택 DB의 Query Store가 비활성화되어 있습니다.',
    PERMISSION_DENIED:'선택 DB의 Query Store 조회 권한이 부족합니다.',
    OFFLINE:'Query Store 수집에 실패했습니다. 상태와 오류 코드를 확인해 주세요.'
  }[status] || (valid ? '' : '선택 DB의 최신 Query Store 표본이 없습니다.');
  const title=<>
    <Cpu size={17}/> 선택 DB CPU 추이 <small>1코어 환산 추정치</small>
  </>;
  return <section className={compact
    ? 'rev-panel rev-ops-chart-card rev-ops-cpu-chart rev-db-cpu-card-compact'
    : 'rev-cpu-panel rev-db-cpu-card'}
    aria-label="선택 DB Query Store CPU 코어 환산율 및 그래프">
    <div className="rev-cpu-panel-heading">
      <div><h3>{title}</h3>
        <p>{databaseName || '현재 연결 DB'} · Query Store가 기록한 완료 쿼리 CPU 시간 기준</p>
      </div>
      <span className="rev-cpu-panel-status">{valid?'표본 정상':status}</span>
    </div>
    <div className="rev-cpu-panel-grid">
      <div><span>CPU 부하 (1코어=100%)</span><strong className="rev-cpu-rate">{rate}</strong>
        <small>새로 기록된 CPU ms / 수집 간격의 환산율</small></div>
      <div><span>Query Store 기록 CPU</span><strong>{cpuText}</strong>
        <small>최근 실행 통계 구간의 누적값</small></div>
      {!compact && <div><span>기록된 실행 횟수</span><strong>{valid&&Number.isFinite(executions)
        ? executions.toLocaleString('ko-KR'):'—'}</strong><small>통계 구간 기준</small></div>}
    </div>
    <DbCpuTrendGraph history={trend}/>
    {!valid && <p className="rev-cpu-panel-note"><ShieldAlert size={15}/>{reason}</p>}
    <p className="rev-cpu-panel-limitation">
      100%는 논리 CPU 1코어 상당이며 병렬 실행 시 100%를 초과할 수 있습니다.
      선택 DB의 정확한 실시간 CPU 점유율이나 SQL Server 인스턴스 전체 CPU%가 아닙니다.
      Query Store 지연 집계·15분 구간 변경·누락된 짧은 쿼리에 따라 추정치가 왜곡되거나 공백이 나타날 수 있습니다.
    </p>
  </section>;
}

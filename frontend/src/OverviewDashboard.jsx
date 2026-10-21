import React, { useEffect, useMemo, useState } from 'react';
import {nonZeroSegments} from './chartVisibility.js';
import {
  Activity, ArrowRight, Bell, CircleAlert, CheckCircle2,
  Clock3, Database, GitBranch, LockKeyhole, Search, ShieldAlert, Sparkles,
  Server, Gauge, ArrowUpRight
} from 'lucide-react';

const num = value => value == null ? '—' : new Intl.NumberFormat('ko-KR').format(value);
const clock = value => value ? new Date(value).toLocaleTimeString('ko-KR', {hour12:false}) : '—';
const dateTime = value => value ? new Date(value).toLocaleString('ko-KR') : '—';
const KINDS = {BLOCKING:'Blocking', LONG_RUNNING:'장시간 요청', COLLECTION_FAILURE:'수집 오류'};
// Map only known internal collector summaries. Unknown messages remain unchanged
// rather than fabricating an incident description.
const INCIDENT_SUMMARIES = {
  'Long-running requests persisted across samples.': '장시간 실행 요청이 연속된 수집 시점에서 감지됐습니다.',
  'Blocked requests persisted across samples.': '차단된 요청이 연속된 수집 시점에서 감지됐습니다.'
};
const summary = item => INCIDENT_SUMMARIES[item.summary] || item.summary ||
  ((KINDS[item.type] || item.type) + ' 인시던트가 기록됐습니다.');
const TONES = {blocking:'teal', incidents:'red', deadlock:'pink', long:'violet', waits:'blue', targets:'blue'};
const INITIAL_FEED = {waits:null, deadlocks:null, deadlockStatus:null, waitError:false, deadlockError:false};

function KpiCard({icon:Icon, label, value, note, tone, onClick}) {
  return <button className={'rev-kpi rev-kpi-' + (TONES[tone] || 'blue')} type="button" onClick={onClick}>
    <span className="rev-kpi-icon"><Icon size={20} strokeWidth={2.1}/></span>
    <span className="rev-kpi-info"><small>{label}</small><strong>{num(value)}</strong><span>{note}</span></span>
    <ArrowRight size={15} className="rev-kpi-arrow"/>
  </button>;
}

// This GET returns a cached JVM snapshot only (no MSSQL query per page refresh).
// Keep an in-memory history of distinct server samples while Overview is open.
// Never use these instance-wide percentages as per-database CPU utilization.
function useOverviewCpuSamples(targetId) {
  const [cpuState,setCpuState] = useState({status:'INITIALIZING',samples:[]});
  useEffect(()=>{
    setCpuState({status:targetId?'INITIALIZING':'NOT_CONFIGURED',samples:[]});
    if (!targetId) return undefined;
    let active=true;
    const refresh=async()=>{
      try {
        const response=await fetch(
          `/api/v1/targets/${encodeURIComponent(targetId)}/instance-cpu`,
          {cache:'no-store'}
        );
        if(!response.ok)throw new Error('instance cpu cache unavailable');
        const data=await response.json();
        if(!active)return;
        const at=Date.parse(data?.collectedAt || '');
        const cpu=Number(data?.sqlProcessCpuPercent);
        const valid=data?.status==='ONLINE' &&
          data.sqlProcessCpuPercent != null &&
          Number.isFinite(at) &&
          Number.isFinite(cpu) && cpu>=0 && cpu<=100 &&
          at>=Date.now()-180000 && at<=Date.now()+10000;
        setCpuState(previous=>{
          const prev=previous.samples;
          const next=valid && !prev.some(item=>item.at===at)
            ? [...prev,{at,cpu}].slice(-60)
            : prev;
          return {status:data?.status || 'UNAVAILABLE',samples:next};
        });
      } catch {
        if(active)setCpuState(previous=>({...previous,status:'UNAVAILABLE'}));
      }
    };
    refresh();
    const timer=setInterval(refresh,15000);
    return ()=>{active=false;clearInterval(timer);};
  },[targetId]);
  return cpuState;
}

function TrendChart({history, sampleLimit, cpuState, databaseName}) {
  const [highlightedIndex, setHighlightedIndex] = useState(null);
  const rows = history.slice(-sampleLimit);
  const lines = [
    {name:'활성 요청',key:'activeRequests',color:'var(--t-accent)'},
    {name:'Blocking',key:'blockedRequests',color:'#ff6582'},
    {name:'장시간 요청',key:'longRunningRequests',color:'#ae83ff'}
  ];
  const cpuSamples = cpuState?.samples || [];
  const firstMs=rows.length ? Date.parse(rows[0].collectedAt) : NaN;
  const lastMs=rows.length ? Date.parse(rows.at(-1).collectedAt) : NaN;
  const cpuPoints = Number.isFinite(firstMs) && Number.isFinite(lastMs) && lastMs>firstMs
    ? cpuSamples.filter(p=>p.at>=firstMs && p.at<=lastMs)
    : [];
  const cpuReady = cpuState?.status==='ONLINE';
  // Distinct right-hand scale: requests use dynamic 0..peak, CPU uses 0..100%.
  const cpuY = percent=>187-(percent/100)*149;
  const cpuX = at=>42+((at-firstMs)/(lastMs-firstMs))*688;
  // Zero-valued samples remain valid data, but do not paint an orange
  // line over the same neutral 0-axis as zero-valued request series.
  const cpuVisibleSegments = nonZeroSegments(cpuPoints, p=>p.cpu, p=>p.at, 150000);
  const hasRequestActivity = rows.some(row=>
    lines.some(l=>Number(row[l.key])>0));
  const hasCpuActivity = cpuReady && cpuPoints.some(p=>p.cpu>0);
  const hasVisibleActivity = hasRequestActivity || hasCpuActivity;
  const peak = Math.max(1, ...rows.flatMap(r => lines.map(l => Number(l.key in r ? r[l.key] : 0) || 0)));
  const step = Math.max(1, Math.ceil(peak / 4));
  const yTicks = Array.from({length:Math.floor(peak/step) + 1},(_,i)=>i*step).reverse();
  if(yTicks[0] !== peak) yTicks.unshift(peak);
  const plotLeft = 42;
  const plotRight = 730;
  const sx = index => plotLeft + index * ((plotRight-plotLeft) / Math.max(1,rows.length-1));
  const sy = value => 187 - ((Number(value)||0) / peak) * 149;
  // Only highlight observed non-zero segments. Preserve real edges
  // to/from 0, and avoid bridging interrupted 5-second collections.
  const requestSegments = key=>nonZeroSegments(
    rows,row=>row[key],row=>Date.parse(row.collectedAt),20000);
  const focusIndex = highlightedIndex != null && highlightedIndex < rows.length
    ? highlightedIndex : rows.length - 1;
  const focused = rows[focusIndex];
  const focusTime=focused?.collectedAt ? Date.parse(focused.collectedAt) : NaN;
  const focusCpu=cpuReady && Number.isFinite(focusTime)
    ? cpuPoints.reduce((closest,p)=>{
        return !closest || Math.abs(p.at-focusTime)<Math.abs(closest.at-focusTime)?p:closest;
      },null)
    : null;
  // A CPU point may be about a minute apart; explicitly mark the sample time
  // rather than pretending it was recorded every 5s with DMV metrics.
  const shownCpu=focusCpu && Math.abs(focusCpu.at-focusTime)<=90000 ? focusCpu : null;
  const pickIndex = event => {
    if (!rows.length) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (!rect.width) return;
    const point = (event.clientX - rect.left) * (760 / rect.width);
    const index = Math.round((point - plotLeft) * (rows.length-1) / (plotRight-plotLeft));
    setHighlightedIndex(Math.max(0,Math.min(rows.length-1,index)));
  };
  const handleKey = event => {
    if (!rows.length || !['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    if (event.key==='Home') setHighlightedIndex(0);
    else if(event.key==='End') setHighlightedIndex(rows.length-1);
    else setHighlightedIndex(Math.max(0,Math.min(rows.length-1,
      focusIndex + (event.key==='ArrowRight' ? 1 : -1))));
  };
  return <article className="rev-panel rev-performance-chart rev-ops-trend">
    <div className="rev-panel-heading">
      <div><h2>실시간 성능 추이</h2>
        <p>{databaseName ? databaseName+' · ' : ''}SQL Server DMV · 최근 {rows.length}개 샘플 · 5초 기본 수집</p></div>
      <span className="rev-panel-tag">실제 관측</span>
    </div>
    <div className="rev-chart-legend" aria-label="그래프 범례">
      {lines.map(l => <span key={l.key}><i style={{background:l.color}}/>{l.name}</span>)}
      <span className="rev-ops-cpu-legend">
        <i style={{background:'#ffc06f'}}/>
        SQL Server 전체 프로세스 CPU (%) · 60초 표본
        {!cpuReady && <small>({cpuState?.status==='DISABLED'?'수집 비활성':'표본 확인 중'})</small>}
      </span>
    </div>
    {rows.length ? <>
      <div className="rev-chart-area rev-ops-chart-area">
        <svg viewBox="0 0 760 208" preserveAspectRatio="none" role="img"
          tabIndex={0} onMouseMove={pickIndex} onMouseLeave={()=>setHighlightedIndex(null)}
          onKeyDown={handleKey} onBlur={()=>setHighlightedIndex(null)}
          aria-label={'활성 요청, Blocking, 장시간 요청의 수집 시점별 실제 추이. 좌우 화살표로 시점을 선택할 수 있습니다. 최대 값 '+peak+'건'}>
          {yTicks.map((value,index) => <g key={value+'-'+index}>
            <line x1={plotLeft} x2={plotRight} y1={sy(value)} y2={sy(value)}
              stroke="var(--t-chart-grid)" strokeDasharray="3 5" strokeWidth="0.8"/>
            <text x="33" y={sy(value)+4} textAnchor="end" fontSize="11">{value}</text>
          </g>)}
          {[0,1,2,3,4,5].map(i=>
            <line key={i} x1={plotLeft+i*(plotRight-plotLeft)/5}
              x2={plotLeft+i*(plotRight-plotLeft)/5} y1="38" y2="187"
              stroke="var(--t-chart-grid)" strokeWidth=".7"/>)}
          {/* One neutral zero baseline across the chart; measured 0-to-0
              periods remain visible without misleading per-series colors. */}
          <line className="rev-ops-neutral-zero" x1={plotLeft} x2={plotRight}
            y1={sy(0)} y2={sy(0)} strokeWidth="2.1"/>
          {rows.length===1
            ? lines.filter(l=>Number(rows[0][l.key])>0).map(l=>
                <circle key={l.key} cx={sx(0)} cy={sy(rows[0][l.key])}
                  r="3.5" fill={l.color}/>)
            : lines.flatMap(l=>requestSegments(l.key).map(([a,b])=>
                <line key={l.key+'-'+a} x1={sx(a)} y1={sy(rows[a][l.key])}
                  x2={sx(b)} y2={sy(rows[b][l.key])}
                  stroke={l.color} strokeWidth="2.5" strokeLinecap="round"/>))}
          {cpuReady && cpuVisibleSegments.map(([a,b])=>
            <line className="rev-ops-cpu-line" key={'cpu-'+a}
              x1={cpuX(cpuPoints[a].at)} y1={cpuY(cpuPoints[a].cpu)}
              x2={cpuX(cpuPoints[b].at)} y2={cpuY(cpuPoints[b].cpu)}
              stroke="#ffc06f" strokeDasharray="5 4" strokeWidth="2.4"
              strokeLinecap="round"/>)}
          {cpuReady && cpuPoints.filter(p=>p.cpu>0).map(p=>
            <circle key={p.at} cx={cpuX(p.at)} cy={cpuY(p.cpu)}
              r="3.5" fill="#ffc06f" stroke="var(--t-card)" strokeWidth="1.3"/>)}
          {cpuReady && cpuPoints.length>0 && <>
            <text className="rev-ops-cpu-axis-title" x="754" y="26"
              textAnchor="end" fill="#ffc06f" fontSize="10">CPU %</text>
            {[0,50,100].map(n=><text key={'cpu-'+n} className="rev-ops-cpu-axis-tick" x="754" y={cpuY(n)+4}
              textAnchor="end" fill="#ffc06f" fontSize="10">{n}%</text>)}
          </>}
          {!hasVisibleActivity && <text className="rev-ops-chart-idle"
            x="380" y="112" textAnchor="middle" fontSize="12">
              관측된 요청 없음 · 기준선은 0
            </text>}
          {focused && <>
            <line x1={sx(focusIndex)} x2={sx(focusIndex)} y1="38" y2="187"
              stroke="var(--t-muted)" strokeOpacity=".65" strokeDasharray="4 4"/>
            {lines.filter(l=>Number(focused[l.key])>0).map(l=>
              <circle key={l.key} cx={sx(focusIndex)}
                cy={sy(focused[l.key])} r="4" fill={l.color}
                stroke="var(--t-card)" strokeWidth="1.5"/>)}
          </>}
        </svg>
        <div className="rev-chart-times">
          <span>{clock(rows[0].collectedAt)}</span>
          <span>{clock(rows.at(-1).collectedAt)}</span>
        </div>
      </div>
      <div className="rev-ops-chart-readout" aria-live="polite">
        <span className="rev-ops-chart-at">{highlightedIndex===null?'최신':'선택'} · {clock(focused?.collectedAt)}</span>
        {lines.map(l=><span key={l.key}>{l.name} <strong>{num(focused?.[l.key])}건</strong></span>)}
        <span className="rev-ops-cpu-readout">SQL Server CPU
          <strong>{shownCpu?shownCpu.cpu+'%':'—'}</strong>
          {shownCpu && <small>표본 {clock(new Date(shownCpu.at))}</small>}
        </span>
      </div>
      <p className="rev-ops-chart-hint">그래프에 마우스를 올리거나 키보드 좌우 화살표를 누르면 관측 시점별 수치를 확인할 수 있습니다.</p>
    </> : <div className="rev-overview-empty">아직 성능 샘플이 없습니다. 수집기 연결 상태를 확인해 주세요.</div>}
    <p className="rev-chart-caution">왼쪽 축은 실제 DMV 요청 수, 오른쪽 축은 SQL Server 프로세스 CPU(%)입니다. CPU는 선택적 약 60초 표본이며 화면을 연 이후의 표본만 연결합니다. 특정 데이터베이스의 CPU(%)가 아닙니다. 데이터 공백은 추정하지 않습니다.</p>
  </article>;
}

export default function OverviewDashboard({
 target, history, incidents, incidentError, queryAnalysis,
 sampleLimit, onNavigate, onInvestigate
}) {
  const [feed, setFeed] = useState(INITIAL_FEED);
  const [historyView,setHistoryView] = useState('table');
  const targetId = target?.id;
  const cpuState = useOverviewCpuSamples(targetId);
  useEffect(() => {
    if (!targetId) return undefined;
    let active = true;
    const refresh = async () => {
      const base = '/api/v1/targets/'+encodeURIComponent(targetId);
      const fetchJSON = async path => {
        try {
          const response = await fetch(base+path);
          if (!response.ok) throw new Error('fetch failed');
          return await response.json();
        } catch { return null; }
      };
      const [waits, deadlockStatus, deadlocks] = await Promise.all([
        fetchJSON('/waits'), fetchJSON('/deadlocks/status'),
        fetchJSON('/deadlocks?limit=50')
      ]);
      if(active)setFeed({waits, deadlocks, deadlockStatus,
        waitError:!waits,deadlockError:!deadlocks||!deadlockStatus});
    };
    setFeed(INITIAL_FEED);
    refresh();
    const timer=setInterval(refresh, 15000);
    return ()=>{active=false;clearInterval(timer);};
  },[targetId]);

  const latest = history.at(-1);
  const latestAtMs = latest?.collectedAt ? Date.parse(latest.collectedAt) : NaN;
  // Avoid presenting a stale 5-second DMV sample as current health.
  const ageMs = Date.now() - latestAtMs;
  const recentSample = Number.isFinite(ageMs) && ageMs >= -10000 && ageMs <= 30000;
  const online = target?.collectorStatus === 'ONLINE' && recentSample;
  const currentWaits = feed.waits?.status==='ONLINE' ? feed.waits.observations || [] : null;
  const waitGroups = useMemo(() => {
    const count = new Map();
    for(const wait of currentWaits||[])count.set(wait.waitCategory,(count.get(wait.waitCategory)||0)+1);
    return [...count.entries()].sort((a,b)=>b[1]-a[1]).slice(0,5);
  },[currentWaits]);
  const waitTotal = currentWaits?.length || 0;
  const recentDeadlocks = feed.deadlockStatus?.status === 'ONLINE' ? feed.deadlocks : null;
  const open = incidents.filter(i=>i.status==='OPEN');
  const hasIncidentData = !incidentError && Boolean(targetId);
  const nav = (name) => {
    const hashes = {overview:'overview',incidents:'incidents',blocking:'blocking-chains',
      waits:'wait-diagnostics',deadlocks:'deadlocks',queries:'query-performance'};
    window.location.hash = '#' + (hashes[name]||name);
    onNavigate(name);
  };
  // Overview is a concise *preview*, not an unbounded incident feed.
  // Show live DMV signals first, then open incident details. No events are discarded:
  // individual incident records remain reachable from the timeline/table and detail page.
  const alerts = [
    ...(online&&latest.blockedRequests>0?[{key:'blocking',kind:'Blocking',title:'현재 차단 요청 '+num(latest.blockedRequests)+'건',
      note:'DMV 최신 스냅샷',level:'warning',action:()=>nav('blocking')}]:[]),
    ...(online&&latest.longRunningRequests>0?[{key:'long',kind:'장시간 요청',title:'현재 장시간 실행 요청 '+num(latest.longRunningRequests)+'건',
      note:'DMV 최신 스냅샷',level:'warning',action:()=>nav('performance')}]:[]),
    ...open.map(i=>({key:'incident-'+i.id, kind:KINDS[i.type]||i.type,
      title:summary(i),
      note:'진행 중 · '+dateTime(i.startedAt),level:'alert',action:()=>onInvestigate(i.id)}))
  ];
  const PREVIEW_LIMIT = 2;
  const previewAlerts = alerts.slice(0, PREVIEW_LIMIT);
  const hiddenAlertCount = Math.max(0, alerts.length - previewAlerts.length);
  // Limit Wait category rows as well; 5 distinct categories must not stretch
  // the right rail and create an empty row below the main chart.
  const visibleWaitGroups = waitGroups.slice(0, PREVIEW_LIMIT);
  const hiddenWaitGroupCount = waitGroups.length - visibleWaitGroups.length;

  const unknownHealth = !online || !hasIncidentData;
  const hasLiveConcern = !unknownHealth &&
    (open.length>0 || (Number(latest?.blockedRequests)||0)>0 ||
     (Number(latest?.longRunningRequests)||0)>0);
  const operationLabel = unknownHealth ? '운영 상태 확인 중'
    : hasLiveConcern ? '관측된 이상 신호 있음' : '관측 지표상 이상 없음';
  const operationClass = unknownHealth ? 'unknown' : hasLiveConcern ? 'warning' : 'good';

  return <div className="rev-dashboard rev-ops-dashboard">
    <section className="rev-ops-server-bar" aria-label="모니터링 서버 상태 요약">
      <div className="rev-ops-server-primary">
        <span className="rev-ops-server-icon"><Server size={23}/></span>
        <div className="rev-ops-server-text">
          <span className="rev-ops-eyebrow">MONITORING TARGET</span>
          <strong>{target?.databaseName || target?.name || '데이터베이스 확인 중'}</strong>
          <span>{target?.name || 'SQL Server'} · MSSQL · 최근 DMV {online ? clock(latest.collectedAt) : '관측 확인 중'}</span>
        </div>
      </div>
      <div className="rev-ops-server-meta">
        <span className={'rev-ops-health-pill '+operationClass}>
          {operationClass==='good' ? <CheckCircle2 size={15}/> : <CircleAlert size={15}/>}
          {operationLabel}
        </span>
        <span className="rev-ops-server-secondary">
          {online ? 'DMV 수집 정상 · 5초 기본 주기' :
            target?.collectorStatus === 'OFFLINE' ? '연결 또는 수집 오류' :
            target?.collectorStatus === 'NOT_CONFIGURED' ? '연결 설정 필요' :
            '현재 표본 확인 중'}
        </span>
        <button type="button" className="rev-ops-server-link" onClick={()=>nav('targets')}>
          대상 정보 <ArrowRight size={14}/>
        </button>
      </div>
    </section>

    <section className="rev-kpi-grid rev-ops-kpis" aria-label="실시간 운영 지표">
      <KpiCard icon={ShieldAlert} label="열린 인시던트" value={hasIncidentData ? open.length : null}
        note="진행 중인 사건" tone="incidents" onClick={()=>nav('incidents')}/>
      <KpiCard icon={LockKeyhole} label="Blocking 요청" value={online ? latest.blockedRequests : null}
        note="최신 DMV 스냅샷" tone="blocking" onClick={()=>nav('blocking')}/>
      <KpiCard icon={Clock3} label="장시간 실행 요청" value={online ? latest.longRunningRequests : null}
        note="수집기 임계값 기준" tone="long" onClick={()=>nav('performance')}/>
      <KpiCard icon={GitBranch} label="최근 Deadlock" value={recentDeadlocks?.length ?? null}
        note="최근 조회 최대 50건" tone="deadlock" onClick={()=>nav('deadlocks')}/>
      <KpiCard icon={Bell} label="현재 Wait 요청" value={currentWaits?.length ?? null}
        note="최근 1회 Wait 표본" tone="waits" onClick={()=>nav('waits')}/>
    </section>

    <section className="rev-ops-wide-chart" aria-label="데이터베이스 성능">
      <TrendChart history={online ? history : []} sampleLimit={sampleLimit} cpuState={cpuState} databaseName={target?.databaseName}/>
    </section>

    <section className="rev-panel rev-ops-unified-board" aria-label="운영 진단 요약">
      <div className="rev-ops-unified-heading">
        <div><h2>운영 진단 요약</h2><p>최근 Wait · 관측 신호 · 분석 바로가기</p></div>
        <span className="rev-panel-tag">LIVE STATUS</span>
      </div>
      <div className="rev-ops-unified-grid">
<article className={'rev-ops-summary-cell rev-wait-summary rev-ops-mini-panel'+(waitGroups.length===0?' rev-ops-compact':'')}>
          <div className="rev-panel-heading">
            <div><h2>주요 대기 유형</h2><p>가장 최근 Wait 요청 표본</p></div>
            <button type="button" className="rev-text-link" onClick={()=>nav('waits')}>상세 <ArrowRight size={14}/></button>
          </div>
          {currentWaits===null
            ? <div className="rev-ops-compact-state unavailable"><CircleAlert size={19}/>
                <span>Wait 수집 상태 확인 필요</span></div>
            : waitGroups.length===0
              ? <div className="rev-ops-compact-state"><CheckCircle2 size={19}/>
                  <span>현재 관측된 Wait 요청 없음</span></div>
              : <>
                  <div className="rev-wait-bars">{visibleWaitGroups.map(([kind,count],index)=>
                    <div className="rev-wait-row" key={kind}>
                      <span title={kind}>{kind}</span>
                      <div className="rev-wait-track"><div className={'rev-wait-fill rev-wait-fill-'+index}
                        style={{width:(count/waitTotal*100)+'%'}}/></div>
                      <strong>{num(count)}건</strong>
                    </div>)}</div>
                  {hiddenWaitGroupCount>0 &&
                    <button type="button" className="rev-ops-more-link" onClick={()=>nav('waits')}>
                      다른 대기 유형 {hiddenWaitGroupCount}개 · 전체 보기 <ArrowRight size={13}/>
                    </button>}
                </>}
          {waitGroups.length>0 &&
            <p className="rev-card-foot">최근 한 시점의 요청 표본입니다.</p>}
        </article>
<article className={'rev-ops-summary-cell rev-current-signals rev-ops-mini-panel'+(!alerts.length?' rev-ops-compact':'')}>
          <div className="rev-panel-heading">
            <div><h2>현재 관측 신호</h2><p>열린 인시던트와 최신 DMV</p></div>
            <button type="button" className="rev-text-link" onClick={()=>nav('incidents')}>이력 <ArrowRight size={14}/></button>
          </div>
          {alerts.length
            ? <>
                <div className="rev-alert-items rev-ops-alert-preview">{previewAlerts.map(a=>
                  <button className="rev-alert-item" key={a.key} type="button" onClick={a.action}
                    title={a.title}>
                    <span className={'rev-alert-icon '+a.level}><CircleAlert size={17}/></span>
                    <span><strong>{a.title}</strong><small>{a.kind} · {a.note}</small></span>
                    <ArrowRight size={15}/>
                  </button>)}</div>
                {hiddenAlertCount>0 &&
                  <div className="rev-ops-more-signals">
                    <span>추가 신호 {num(hiddenAlertCount)}개</span>
                    <button type="button" onClick={()=>nav('incidents')}>
                      인시던트 이력 <ArrowRight size={13}/>
                    </button>
                  </div>}
              </>
            : <div className={'rev-ops-compact-state'+(unknownHealth?' unavailable':'')}>
                {unknownHealth ? <CircleAlert size={19}/> : <CheckCircle2 size={19}/>}
                <span>{unknownHealth ? '수집 상태를 확인 중입니다.' :
                  '최근 관측된 주요 이상 신호 없음'}</span>
              </div>}
          <p className="rev-card-foot">최신 신호만 요약합니다. Blocking·장시간 요청은 상단 KPI에서, 전체 사건은 인시던트 화면에서 확인할 수 있습니다.</p>
        </article>
<article className="rev-ops-summary-cell rev-ops-action-panel">
        <div className="rev-panel-heading">
          <div><h2>운영 바로가기</h2><p>문제 진단에 필요한 화면으로 이동</p></div>
          <span className="rev-panel-tag">READ ONLY</span>
        </div>
        <div className="rev-ops-action-list">
          <button type="button" onClick={()=>nav('blocking')}>
            <LockKeyhole size={18}/><span><strong>Blocking 분석</strong><small>대기 세션과 차단 관계 확인</small></span><ArrowUpRight size={16}/>
          </button>
          <button type="button" onClick={()=>nav('waits')}>
            <Clock3 size={18}/><span><strong>Wait Diagnostics</strong><small>최근 대기 유형 및 관측 이력</small></span><ArrowUpRight size={16}/>
          </button>
          <button type="button" onClick={()=>nav('queries')}>
            <Gauge size={18}/><span><strong>쿼리 성능 분석</strong><small>Query Store 통계와 튜닝 후보</small></span><ArrowUpRight size={16}/>
          </button>
        </div>
      </article>
      </div>
    </section>

    <section className="rev-panel rev-ops-history-board" aria-label="최근 인시던트 기록">
      <div className="rev-ops-history-top">
        <div><h2>최근 인시던트</h2><p>최신 기록을 표 또는 타임라인으로 확인합니다.</p></div>
        <div className="rev-ops-history-actions">
          <div className="rev-ops-history-switch" role="group" aria-label="인시던트 표시 방식">
            <button type="button" className={historyView==='table'?'active':''}
              aria-pressed={historyView==='table'} onClick={()=>setHistoryView('table')}>표</button>
            <button type="button" className={historyView==='timeline'?'active':''}
              aria-pressed={historyView==='timeline'} onClick={()=>setHistoryView('timeline')}>타임라인</button>
          </div>
          <button type="button" className="rev-text-link" onClick={()=>nav('incidents')}>
            전체 보기 <ArrowRight size={14}/>
          </button>
        </div>
      </div>
      <div className="rev-ops-history-content">
        {historyView==='table' ? (
<article className="rev-recent-table rev-ops-history-mode">
      <div className="rev-panel-heading">
        <div><h2>최근 인시던트</h2><p>사건 선택 시 저장된 근거를 조사할 수 있습니다.</p></div>
        <button className="rev-text-link" onClick={()=>nav('incidents')}>전체 보기 <ArrowRight size={14}/></button>
      </div>
      {incidentError ? <div className="rev-overview-empty">이력을 조회하지 못했습니다.</div> :
      !incidents.length ? <div className="rev-overview-empty">표시할 사건이 없습니다.</div> :
      <div className="rev-table-scroll"><table className="rev-table">
        <thead><tr><th>발생 시각</th><th>유형</th><th>관측 내용</th><th>상태</th><th>조사</th></tr></thead>
        <tbody>{incidents.slice(0,6).map(item=>
          <tr key={item.id}>
            <td>{dateTime(item.startedAt)}</td>
            <td>{KINDS[item.type]||item.type}</td>
            <td>{summary(item)}</td>
            <td><span className={'rev-table-badge '+(item.status==='OPEN'?'open':'resolved')}>
              {item.status==='OPEN'?'진행 중':'해결됨'}</span></td>
            <td><button className="rev-table-action" onClick={()=>onInvestigate(item.id)}>분석 <ArrowRight size={13}/></button></td>
          </tr>)}</tbody></table></div>}
    </article>
        ) : (
<article className="rev-incident-timeline rev-ops-history-mode">
        <div className="rev-panel-heading">
          <div><h2>최근 인시던트 타임라인</h2><p>발생 시각순 최근 4건</p></div>
          <button className="rev-text-link" onClick={()=>nav('incidents')}>전체 보기 <ArrowRight size={14}/></button>
        </div>
        {incidentError ? <div className="rev-overview-empty">인시던트 API에 접근할 수 없습니다.</div> :
          !incidents.length ? <div className="rev-overview-empty">아직 저장된 인시던트가 없습니다.</div> :
          <div className="rev-timeline">{incidents.slice(0,4).map(item=>
            <button key={item.id} className="rev-timeline-item" onClick={()=>onInvestigate(item.id)}>
              <time>{clock(item.startedAt)}</time>
              <span className={'rev-timeline-dot '+(item.status==='OPEN'?'open':'resolved')}/>
              <span className="rev-timeline-description"><strong>{KINDS[item.type]||item.type} <small>#{item.id}</small></strong>
                <span>{item.status === 'OPEN' ? '진행 중' : '해결됨'} · {dateTime(item.startedAt)}</span></span>
              <ArrowRight size={15}/></button>)}</div>}
      </article>
        )}
      </div>
    </section>

    {queryAnalysis?.length>0 && <p className="rev-dashboard-foot">
      Query Store 통계 {num(queryAnalysis.length)}개 항목도 수집 중입니다.
      <button onClick={()=>nav('queries')}>쿼리 분석에서 확인 <ArrowRight size={14}/></button>
    </p>}
  </div>;
}

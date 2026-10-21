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

// Request counts and instance-wide CPU are different measurements.
// Deliberately render them in separate cards with independent axes and
// hover selection so a Blocking/long-running spike is never read as CPU%.
const REQUEST_SERIES = [
  {name:'활성 요청',key:'activeRequests',color:'#58adff'},
  {name:'Blocking',key:'blockedRequests',color:'#ff6582'},
  {name:'장시간 요청',key:'longRunningRequests',color:'#ae83ff'}
];
const CHART_LEFT = 43;
const CHART_RIGHT = 520;
const CHART_TOP = 30;
const CHART_BOTTOM = 177;
const CHART_WIDTH = 560;
const chartX = (index,length) => CHART_LEFT +
  (CHART_RIGHT - CHART_LEFT) * index / Math.max(1,length-1);
const pickChartIndex = (event,length) => {
  const rect=event.currentTarget.getBoundingClientRect();
  if(!rect.width||!length)return 0;
  const x=(event.clientX-rect.left)*CHART_WIDTH/rect.width;
  return Math.min(length-1,Math.max(0,
    Math.round((x-CHART_LEFT)/(CHART_RIGHT-CHART_LEFT)*Math.max(1,length-1))));
};
const chartArrowIndex = (event,current,length,setIndex) => {
  if(!length||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  event.preventDefault();
  const next=event.key==='Home'?0:event.key==='End'?length-1:
    Math.max(0,Math.min(length-1,current+(event.key==='ArrowRight'?1:-1)));
  setIndex(next);
};

function RequestTrendPanel({history,sampleLimit,databaseName}) {
  const [selectedIndex,setSelectedIndex]=useState(null);
  const rows=history.slice(-sampleLimit);
  const peak=Math.max(1,...rows.flatMap(row=>REQUEST_SERIES.map(series=>
    Number(row[series.key])||0)));
  const step=Math.max(1,Math.ceil(peak/4));
  const ticks=Array.from({length:Math.floor(peak/step)+1},
    (_,index)=>index*step).reverse();
  if(ticks[0]!==peak)ticks.unshift(peak);
  const y=value=>CHART_BOTTOM-(Number(value)||0)/peak*(CHART_BOTTOM-CHART_TOP);
  const currentIndex=selectedIndex!==null && selectedIndex<rows.length
    ?selectedIndex:rows.length-1;
  const selected=rows[currentIndex];
  const active=rows.some(row=>REQUEST_SERIES.some(series=>
    Number(row[series.key])>0));
  const segments=key=>nonZeroSegments(
    rows,row=>row[key],row=>Date.parse(row.collectedAt),20000);
  return <article className="rev-panel rev-ops-chart-card rev-ops-request-chart">
    <div className="rev-panel-heading">
      <div><h2>실시간 요청 현황</h2>
        <p>{databaseName ? databaseName+' · ' : ''}SQL Server DMV · 최근 {rows.length}개 표본 · 5초 기본 수집</p></div>
      <span className="rev-panel-tag">단위: 건</span>
    </div>
    <div className="rev-chart-legend" aria-label="요청 수 그래프 범례">
      {REQUEST_SERIES.map(series=><span key={series.key}>
        <i style={{background:series.color}}/>{series.name}
      </span>)}
    </div>
    {rows.length ? <>
      <div className="rev-chart-area rev-ops-chart-area">
        <div className="rev-ops-chart-frame">
          <svg viewBox="0 0 560 210" preserveAspectRatio="xMidYMid meet"
            role="img" tabIndex={0}
            aria-label="활성 요청, Blocking, 장시간 실행 요청. 왼쪽 축은 요청 수(건). 좌우 방향키로 실제 DMV 수집 시점을 선택하세요."
            onMouseMove={event=>setSelectedIndex(pickChartIndex(event,rows.length))}
            onMouseLeave={()=>setSelectedIndex(null)}
            onKeyDown={event=>chartArrowIndex(event,currentIndex,rows.length,setSelectedIndex)}
            onBlur={()=>setSelectedIndex(null)}>
            {ticks.map(tick=><g key={tick}>
              <line x1={CHART_LEFT} x2={CHART_RIGHT} y1={y(tick)} y2={y(tick)}
                strokeWidth=".8" strokeDasharray="3 5"/>
              <text x="32" y={y(tick)+4} textAnchor="end" fontSize="11">{tick}</text>
            </g>)}
            {[0,1,2,3,4].map(index=><line key={index}
              x1={CHART_LEFT+index*(CHART_RIGHT-CHART_LEFT)/4}
              x2={CHART_LEFT+index*(CHART_RIGHT-CHART_LEFT)/4}
              y1={CHART_TOP} y2={CHART_BOTTOM} strokeWidth=".7"/>)}
            <path className="rev-ops-neutral-zero"
              d={'M'+CHART_LEFT+' '+y(0)+' L'+CHART_RIGHT+' '+y(0)}
              stroke="#718ca9" strokeWidth="1.8" fill="none"/>
            {rows.length===1
              ? REQUEST_SERIES.filter(series=>Number(rows[0][series.key])>0)
                .map(series=><circle key={series.key}
                  cx={CHART_RIGHT} cy={y(rows[0][series.key])}
                  r="3.5" fill={series.color}/>)
              : REQUEST_SERIES.flatMap(series=>segments(series.key).map(([a,b])=>
                  <path className="rev-ops-metric-segment"
                    key={series.key+'-'+a}
                    d={'M'+chartX(a,rows.length)+' '+y(rows[a][series.key])+
                       ' L'+chartX(b,rows.length)+' '+y(rows[b][series.key])}
                    stroke={series.color} strokeWidth="2.8" fill="none"
                    strokeLinecap="round" strokeLinejoin="round"/>))}
            {selected && <>
              <line className="rev-ops-selected-marker"
                x1={chartX(currentIndex,rows.length)}
                x2={chartX(currentIndex,rows.length)}
                y1={CHART_TOP} y2={CHART_BOTTOM}
                strokeDasharray="4 4" strokeWidth="1"/>
              {REQUEST_SERIES.filter(series=>Number(selected[series.key])>0)
                .map(series=><circle key={series.key}
                  cx={chartX(currentIndex,rows.length)}
                  cy={y(selected[series.key])} r="4"
                  fill={series.color} stroke="var(--t-card)" strokeWidth="1.5"/>)}
            </>}
            {!active && <text className="rev-ops-chart-idle"
              x="280" y="96" fontSize="12" textAnchor="middle">
              관측 구간에 활성 · 차단 · 장시간 요청 없음
            </text>}
          </svg>
          <div className="rev-chart-times">
            <span>{clock(rows[0].collectedAt)}</span>
            <span>{clock(rows.at(-1).collectedAt)}</span>
          </div>
        </div>
      </div>
      <div className="rev-ops-chart-readout" aria-live="polite">
        <span className="rev-ops-chart-at">
          {selectedIndex===null?'최신':'선택'} · {clock(selected?.collectedAt)}
        </span>
        {REQUEST_SERIES.map(series=><span key={series.key}>{series.name}
          <strong>{num(selected?.[series.key])}건</strong>
        </span>)}
      </div>
    </> : <div className="rev-overview-empty">
      아직 DMV 성능 샘플이 없습니다. 수집 상태를 확인해 주세요.
    </div>}
    <p className="rev-ops-chart-hint">5초 DMV 표본의 실제 요청 수(건)입니다. 선에 마우스를 올리거나 좌우 방향키로 시점을 선택할 수 있습니다.</p>
  </article>;
}

function CpuTrendPanel({cpuState}) {
  const [selectedIndex,setSelectedIndex]=useState(null);
  const rows=cpuState?.samples?.slice(-60)||[];
  const cpuStatus=cpuState?.status||'INITIALIZING';
  const online=cpuStatus==='ONLINE';
  const firstAt=rows[0]?.at;
  const lastAt=rows.at(-1)?.at;
  const x=index=>rows.length===1?CHART_RIGHT:
    Number.isFinite(firstAt)&&Number.isFinite(lastAt)&&lastAt>firstAt
      ? CHART_LEFT+(rows[index].at-firstAt)/(lastAt-firstAt)*(CHART_RIGHT-CHART_LEFT)
      :chartX(index,rows.length);
  const y=value=>CHART_BOTTOM-Number(value)/100*(CHART_BOTTOM-CHART_TOP);
  const pickCpuIndex=event=>{
    if(!rows.length)return;
    const rect=event.currentTarget.getBoundingClientRect();
    if(!rect.width)return;
    const position=(event.clientX-rect.left)*CHART_WIDTH/rect.width;
    let nearest=0;
    for(let index=1;index<rows.length;index++){
      if(Math.abs(x(index)-position)<Math.abs(x(nearest)-position))nearest=index;
    }
    setSelectedIndex(nearest);
  };
  const currentIndex=selectedIndex!==null&&selectedIndex<rows.length
    ?selectedIndex:rows.length-1;
  const selected=rows[currentIndex];
  // CPU is collected roughly every 60 seconds. A missing or late sample
  // must not be silently joined over a long unobserved interval.
  const cpuSegments=[];
  for(let index=1;index<rows.length;index++){
    const a=rows[index-1],b=rows[index];
    if(b.at>a.at&&b.at-a.at<=150000)cpuSegments.push([index-1,index]);
  }
  const statusLabel=cpuStatus==='ONLINE'?'실제 CPU 표본':
    cpuStatus==='DISABLED'?'CPU 수집 비활성':
    cpuStatus==='PERMISSION_DENIED'?'CPU 조회 권한 없음':
    cpuStatus==='INITIALIZING'?'CPU 표본 대기':'CPU 수집 상태 확인';
  return <article className="rev-panel rev-ops-chart-card rev-ops-cpu-chart">
    <div className="rev-panel-heading">
      <div><h2>SQL Server CPU 사용률</h2>
        <p>인스턴스 전체 프로세스 · 선택 DB만의 CPU가 아님</p></div>
      <span className="rev-panel-tag">단위: %</span>
    </div>
    <div className="rev-chart-legend" aria-label="CPU 그래프 범례">
      <span className="rev-ops-cpu-legend">
        <i style={{background:'#ffbd60'}}/>SQL Server 전체 프로세스 CPU (%) · 60초 표본
      </span>
    </div>
    <div className="rev-chart-area rev-ops-chart-area">
      <div className="rev-ops-chart-frame">
        <svg viewBox="0 0 560 210" preserveAspectRatio="xMidYMid meet"
          role="img" tabIndex={rows.length?0:-1}
          aria-label="SQL Server 전체 프로세스 CPU 사용률. 세로축 0~100퍼센트. 방향키로 CPU 표본 시점을 선택하세요."
          onMouseMove={pickCpuIndex}
          onMouseLeave={()=>setSelectedIndex(null)}
          onKeyDown={event=>chartArrowIndex(event,currentIndex,rows.length,setSelectedIndex)}
          onBlur={()=>setSelectedIndex(null)}>
          {[0,25,50,75,100].map(tick=><g key={tick}>
            <line x1={CHART_LEFT} x2={CHART_RIGHT} y1={y(tick)} y2={y(tick)}
              strokeWidth=".8" strokeDasharray="3 5"/>
            <text x="34" y={y(tick)+4} textAnchor="end" fontSize="10">{tick}%</text>
          </g>)}
          {[0,1,2,3,4].map(index=><line key={index}
            x1={CHART_LEFT+index*(CHART_RIGHT-CHART_LEFT)/4}
            x2={CHART_LEFT+index*(CHART_RIGHT-CHART_LEFT)/4}
            y1={CHART_TOP} y2={CHART_BOTTOM} strokeWidth=".7"/>)}
          <path className="rev-ops-neutral-zero"
            d={'M'+CHART_LEFT+' '+CHART_BOTTOM+' L'+CHART_RIGHT+' '+CHART_BOTTOM}
            stroke="#718ca9" strokeWidth="1.8" fill="none"/>
          {online && cpuSegments.map(([a,b])=>
            <path key={'cpu-'+a} className="rev-ops-cpu-line"
              d={'M'+x(a)+' '+y(rows[a].cpu)+' L'+x(b)+' '+y(rows[b].cpu)}
              stroke="#ffbd60" strokeWidth="2.8" fill="none"
              strokeLinejoin="round" strokeLinecap="round"/>)}
          {online && rows.map((point,index)=>
            <circle key={point.at} cx={x(index)} cy={y(point.cpu)}
              r="3.2" fill="#ffbd60" stroke="var(--t-card)" strokeWidth="1"/>)}
          {online && selected && <line className="rev-ops-selected-marker"
            x1={x(currentIndex)} x2={x(currentIndex)}
            y1={CHART_TOP} y2={CHART_BOTTOM}
            strokeDasharray="4 4" strokeWidth="1"/>}
          {(!online||!rows.length) && <text className="rev-ops-chart-idle"
            x="280" y="105" textAnchor="middle" fontSize="12">
            {statusLabel}
          </text>}
        </svg>
        <div className="rev-chart-times">
          <span>{online&&rows.length?clock(new Date(firstAt)):'—'}</span>
          <span>{online&&rows.length?clock(new Date(lastAt)):'—'}</span>
        </div>
      </div>
    </div>
    <div className="rev-ops-chart-readout" aria-live="polite">
      <span className="rev-ops-chart-at">
        {online&&selected?clock(new Date(selected.at)):statusLabel}
      </span>
      <span className="rev-ops-cpu-readout">SQL Server CPU
        <strong>{online&&selected?selected.cpu+'%':'—'}</strong>
      </span>
      <span>{online&&rows.length?rows.length+'개 실측 표본':'수집된 표본 없음'}</span>
    </div>
    <p className="rev-ops-chart-hint">실제 약 60초 간격 CPU 표본입니다. 화면을 연 이후 표본만 표시하며, 누락 구간은 연결하지 않습니다. 특정 DB의 CPU(%)가 아닙니다.</p>
  </article>;
}

function TrendChart({history,sampleLimit,cpuState,databaseName}) {
  return <div className="rev-ops-split-charts" aria-label="요청 수 및 CPU 성능 그래프">
    <RequestTrendPanel history={history} sampleLimit={sampleLimit}
      databaseName={databaseName}/>
    <CpuTrendPanel cpuState={cpuState}/>
  </div>;
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
    // Count every observed Wait category so the collapsed "N more" link is accurate.
    // Only the two most frequent groups are actually rendered in Overview.
    return [...count.entries()].sort((a,b)=>b[1]-a[1]);
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

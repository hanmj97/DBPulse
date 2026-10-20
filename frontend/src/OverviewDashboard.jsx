import React, { useEffect, useMemo, useState } from 'react';
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

function TrendChart({history, sampleLimit}) {
  const [highlightedIndex, setHighlightedIndex] = useState(null);
  const rows = history.slice(-sampleLimit);
  const lines = [
    {name:'활성 요청',key:'activeRequests',color:'var(--t-accent)'},
    {name:'Blocking',key:'blockedRequests',color:'#ff6582'},
    {name:'장시간 요청',key:'longRunningRequests',color:'#ae83ff'}
  ];
  const peak = Math.max(1, ...rows.flatMap(r => lines.map(l => Number(l.key in r ? r[l.key] : 0) || 0)));
  const step = Math.max(1, Math.ceil(peak / 4));
  const yTicks = Array.from({length:Math.floor(peak/step) + 1},(_,i)=>i*step).reverse();
  if(yTicks[0] !== peak) yTicks.unshift(peak);
  const plotLeft = 42;
  const plotRight = 730;
  const sx = index => plotLeft + index * ((plotRight-plotLeft) / Math.max(1,rows.length-1));
  const sy = value => 187 - ((Number(value)||0) / peak) * 149;
  const path = key => rows.map((row,index) => (index === 0 ? 'M':'L') +
    sx(index).toFixed(1)+','+sy(row[key]).toFixed(1)).join(' ');
  const focusIndex = highlightedIndex != null && highlightedIndex < rows.length
    ? highlightedIndex : rows.length - 1;
  const focused = rows[focusIndex];
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
        <p>SQL Server DMV · 최근 {rows.length}개 샘플 · 5초 기본 수집</p></div>
      <span className="rev-panel-tag">실제 관측</span>
    </div>
    <div className="rev-chart-legend" aria-label="그래프 범례">
      {lines.map(l => <span key={l.key}><i style={{background:l.color}}/>{l.name}</span>)}
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
          {rows.length===1
            ? lines.map(l=><circle key={l.key} cx={sx(0)} cy={sy(rows[0][l.key])}
                r="3.5" fill={l.color}/>)
            : lines.map(l=><path key={l.key} d={path(l.key)}
                stroke={l.color} strokeWidth="2.5" strokeLinejoin="round"
                strokeLinecap="round" fill="none"/>)}
          {focused && <>
            <line x1={sx(focusIndex)} x2={sx(focusIndex)} y1="38" y2="187"
              stroke="var(--t-muted)" strokeOpacity=".65" strokeDasharray="4 4"/>
            {lines.map(l=><circle key={l.key} cx={sx(focusIndex)}
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
      </div>
      <p className="rev-ops-chart-hint">그래프에 마우스를 올리거나 키보드 좌우 화살표를 누르면 관측 시점별 수치를 확인할 수 있습니다.</p>
    </> : <div className="rev-overview-empty">아직 성능 샘플이 없습니다. 수집기 연결 상태를 확인해 주세요.</div>}
    <p className="rev-chart-caution">이 그래프는 활성·차단·장시간 요청 수이며 CPU 사용률이나 누적 Wait 통계가 아닙니다.</p>
  </article>;
}

export default function OverviewDashboard({
 target, history, incidents, incidentError, queryAnalysis,
 sampleLimit, onNavigate, onInvestigate
}) {
  const [feed, setFeed] = useState(INITIAL_FEED);
  const targetId = target?.id;
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
  const online = target?.collectorStatus === 'ONLINE' && Boolean(latest);
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
  const alerts = [
    ...open.slice(0,3).map(i=>({key:'incident-'+i.id, kind:KINDS[i.type]||i.type,
      title:summary(i),
      note:'진행 중 · '+dateTime(i.startedAt),level:'alert',action:()=>onInvestigate(i.id)})),
    ...(online&&latest.blockedRequests>0?[{key:'blocking',kind:'Blocking',title:'현재 차단 요청 '+num(latest.blockedRequests)+'건',
      note:'DMV 최신 스냅샷',level:'warning',action:()=>nav('blocking')}]:[]),
    ...(online&&latest.longRunningRequests>0?[{key:'long',kind:'장시간 요청',title:'현재 장시간 실행 요청 '+num(latest.longRunningRequests)+'건',
      note:'DMV 최신 스냅샷',level:'warning',action:()=>nav('queries')}]:[])
  ].slice(0,4);

  return <div className="rev-dashboard">
    <section className="rev-kpi-grid" aria-label="주요 실시간 지표">
      <KpiCard icon={Database} label="모니터링 대상" value={target ? 1 : null}
        note={target?.name || '대상 확인 중'} tone="targets" onClick={()=>nav('targets')}/>
      <KpiCard icon={ShieldAlert} label="열린 인시던트" value={hasIncidentData ? open.length : null}
        note="진행 중인 사건" tone="incidents" onClick={()=>nav('incidents')}/>
      <KpiCard icon={LockKeyhole} label="Blocking 요청" value={online ? latest.blockedRequests : null}
        note="현재 수집된 차단 요청" tone="blocking" onClick={()=>nav('blocking')}/>
      <KpiCard icon={GitBranch} label="최근 Deadlock" value={recentDeadlocks?.length ?? null}
        note="조회된 최근 최대 50건" tone="deadlock" onClick={()=>nav('deadlocks')}/>
      <KpiCard icon={Clock3} label="장시간 실행 요청" value={online ? latest.longRunningRequests : null}
        note="수집기 임계값 기준" tone="long" onClick={()=>nav('performance')}/>
      <KpiCard icon={Bell} label="현재 Wait 요청" value={currentWaits?.length ?? null}
        note="최근 1회 Wait 스냅샷" tone="waits" onClick={()=>nav('waits')}/>
    </section>

    <div className="rev-overview-primary">
      <TrendChart history={online ? history : []} sampleLimit={sampleLimit}/>
      <article className="rev-panel rev-wait-summary">
        <div className="rev-panel-heading">
          <div><h2>주요 대기 유형</h2><p>최근 수집 시점의 Wait 분류</p></div>
          <button className="rev-text-link" onClick={()=>nav('waits')}>상세 <ArrowRight size={14}/></button>
        </div>
        {currentWaits === null ? <div className="rev-overview-empty">
          Wait 수집 상태를 확인할 수 없습니다.</div>
          : waitGroups.length===0 ? <div className="rev-overview-empty">
            현재 관측된 대기 요청이 없습니다.</div>
          : <div className="rev-wait-bars">{waitGroups.map(([kind,count],index)=>
              <div className="rev-wait-row" key={kind}>
                <span>{kind}</span>
                <div className="rev-wait-track"><div className={'rev-wait-fill rev-wait-fill-'+index}
                  style={{width:(count/waitTotal*100)+'%'}}/></div>
                <strong>{num(count)}건</strong>
              </div>)}</div>}
        <p className="rev-card-foot">대기 유형별 횟수는 단일 시점의 요청 표본입니다. 누적 Wait 통계가 아닙니다.</p>
      </article>
    </div>

    <div className="rev-overview-secondary">
      <article className="rev-panel rev-incident-timeline">
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
      <article className="rev-panel rev-current-signals">
        <div className="rev-panel-heading">
          <div><h2>현재 관측 신호</h2><p>실시간 지표와 열린 인시던트</p></div>
          <button className="rev-text-link" onClick={()=>nav('incidents')}>이력 확인 <ArrowRight size={14}/></button>
        </div>
        {alerts.length ? <div className="rev-alert-items">{alerts.map(a=>
          <button className="rev-alert-item" key={a.key} onClick={a.action}>
            <span className={'rev-alert-icon '+a.level}><CircleAlert size={17}/></span>
            <span><strong>{a.title}</strong><small>{a.kind} · {a.note}</small></span>
            <ArrowRight size={15}/>
          </button>)}</div> : <div className="rev-overview-empty">
          {hasIncidentData && online ? '현재 조회된 열린 인시던트나 성능 이상 신호가 없습니다.'
            : '수집 상태를 확인 중입니다. 관측 공백을 정상으로 단정하지 않습니다.'}
        </div>}
        <p className="rev-card-foot">전용 알림 정책은 아직 개발 중이며, 이 패널은 관측 결과만 표시합니다.</p>
      </article>
    </div>

    <article className="rev-panel rev-recent-table">
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

    {queryAnalysis?.length>0 && <p className="rev-dashboard-foot">
      Query Store 통계 {num(queryAnalysis.length)}개 항목도 수집 중입니다.
      <button onClick={()=>nav('queries')}>쿼리 분석에서 확인 <ArrowRight size={14}/></button>
    </p>}
  </div>;
}

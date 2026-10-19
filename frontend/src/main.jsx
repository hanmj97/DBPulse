import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Activity, AlertCircle, ArrowUpRight, BellRing, CircleCheck, Clock3,
  Database, Gauge, Layers3, LockKeyhole, Radio, RefreshCw, Server,
  ShieldAlert, Sparkles, Terminal, WifiOff, Network,
} from 'lucide-react';
import './style.css';
import IncidentInvestigation from './IncidentInvestigation.jsx';
import WaitDiagnostics from './WaitDiagnostics.jsx';

const fmt = (n) => (n == null ? '—' : new Intl.NumberFormat('ko-KR').format(n));
const timeFmt = (iso) => iso ? new Date(iso).toLocaleTimeString('ko-KR', {hour12: false}) : '—';
const STATUS = {
  ONLINE: { label: '수집 정상', tone: 'success' },
  OFFLINE: { label: '연결 오류', tone: 'error' },
  NOT_CONFIGURED: { label: '설정 필요', tone: 'neutral' },
  INITIALIZING: { label: '연결 확인 중', tone: 'neutral' },
};

function Metric({ label, value, unit, Icon, note, tone }) {
  return <article className="metric card">
    <div className="metric-title"><span>{label}</span><Icon size={18} /></div>
    <div className={`metric-value ${tone || ''}`}>{fmt(value)}<small>{value == null ? '' : unit}</small></div>
    <div className="metric-note">{note}</div>
  </article>;
}

function MiniChart({ items, field, emptyText, tone = 'blue' }) {
  const maxValue = Math.max(1, ...items.map(x => x[field] || 0));
  return <div className={`chart chart-${tone}`} role="img" aria-label={`${field} 최근 수집 데이터`}> 
    {items.length ? items.map((item, index) => <div key={`${item.collectedAt}-${index}`} className="chart-column"
      title={`${timeFmt(item.collectedAt)} | ${field}: ${item[field]}`}> 
      <div className="chart-bar" style={{ height: `${item[field] ? Math.max(2, (item[field] / maxValue) * 100) : 0}%` }} />
    </div>) : <div className="chart-empty">{emptyText}</div>}
  </div>;
}


function BlockingChainsPanel({ targetId }) {
  const [current, setCurrent] = useState(null);
  const [history, setHistory] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!targetId) return undefined;
    let active = true;
    const refresh = async () => {
      try {
        const base = `/api/v1/targets/${encodeURIComponent(targetId)}/blocking-chains`;
        const [currentRes, historyRes] = await Promise.all([
          fetch(base), fetch(`${base}/history?limit=8`)
        ]);
        if (!currentRes.ok || !historyRes.ok) throw new Error('blocking API error');
        const [now, past] = await Promise.all([currentRes.json(), historyRes.json()]);
        if (active) { setCurrent(now); setHistory(past); setError(''); }
      } catch {
        if (active) { setError('Blocking Chain API 연결에 실패했습니다.'); setCurrent(null); }
      }
    };
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [targetId]);

  const online = current?.status === 'ONLINE';
  const edges = online ? (current?.edges || []) : [];
  return <section id="blocking-chains" className="card blocking-chain-panel">
    <div className="section-heading">
      <div><h2>Blocking Chain Analyzer</h2>
        <p>대기 세션 → 차단 세션 관계 · 실시간 DMV 관측 및 PostgreSQL 이력</p></div>
      <span className="small-pill">5s collect</span>
    </div>
    {error && <div className="banner error"><AlertCircle size={16}/>{error}</div>}
    <div className="blocking-chain-overview">
      <span className={`blocking-chain-indicator ${online ? 'good' : 'muted'}`}>
        {online ? '수집 정상' : (current?.status || '확인 중')}
      </span>
      <span>현재 대기 관계 <strong>{online ? edges.length : '—'}</strong>건</span>
      <span>마지막 수집 {timeFmt(current?.collectedAt)}</span>
    </div>
    {current && !online && <div className="banner error"><AlertCircle size={16}/>수집이 일시 중단되었거나 데이터가 오래됐습니다. 마지막 상태를 현재 Blocking으로 표시하지 않습니다.</div>}
    {online && !edges.length && <div className="blocking-chain-empty">
      <CircleCheck size={19}/>현재 관측된 Blocking 관계가 없습니다.
    </div>}
    {edges.length > 0 && <div className="blocking-chain-list">
      {edges.map((edge, index) => <div className="blocking-chain-item"
          key={`${current.collectedAt}-${edge.blockedSessionId}-${index}`}>
        <div className="blocking-chain-flow">
          <div className="blocking-session blocker">
            <small>차단 세션</small><strong>#{edge.blockingSessionId}</strong>
          </div>
          <div className="blocking-flow-arrow"><Network size={16}/><span>차단</span></div>
          <div className="blocking-session waiter">
            <small>대기 세션</small><strong>#{edge.blockedSessionId}</strong>
          </div>
          <span className="blocking-wait-type">{edge.waitType}</span>
        </div>
        <div className="blocking-chain-details">
          <span>대기 <strong>{fmt(edge.waitMs)} ms</strong></span>
          <span>요청 경과 {fmt(edge.elapsedMs)} ms</span>
          <span>차단 세션 상태 {edge.blockerStatus}</span>
          <span>열린 트랜잭션 {fmt(edge.blockerOpenTransactions)}건</span>
        </div>
        <div className="blocking-chain-root">
          {edge.cycleDetected
            ? '관측된 관계에서 순환 감지 — 최상위 세션 특정 불가'
            : edge.rootCandidateSessionId != null
              ? `관측 범위의 최상위 차단 후보 #${edge.rootCandidateSessionId} · 관계 깊이 ${edge.chainDepth}`
              : '최상위 차단 후보를 특정할 수 없습니다.'}
        </div>
      </div>)}
    </div>}
    <div className="blocking-history-head">최근 Blocking 관측 이력 <small>최대 8개 · 수집 시점별 기록</small></div>
    {history.length ? <div className="blocking-history-list">
      {history.map((item, i) =>
        <div key={`${item.collectedAt}-${item.edge.blockedSessionId}-${i}`} className="blocking-history-row">
          <span>{timeFmt(item.collectedAt)}</span>
          <span>#{item.edge.blockingSessionId} → #{item.edge.blockedSessionId}</span>
          <span>{item.edge.waitType}</span>
          <strong>{fmt(item.edge.waitMs)} ms</strong>
        </div>)}
    </div> : <div className="blocking-history-empty">저장된 Blocking 이력이 없습니다.</div>}
    <p className="blocking-chain-note">최상위 차단 세션은 현재 수집된 최대 100개 관계에서 계산한 후보이며 근본 원인을 확정하지 않습니다. 차단 세션의 트랜잭션 수가 0이 아니더라도 해당 트랜잭션이 직접적인 원인인지는 별도 확인이 필요합니다. SQL 원문 및 파라미터는 수집하지 않습니다.</p>
  </section>;
}


function DeadlockHistoryPanel({ targetId }) {
  const [status, setStatus] = useState(null);
  const [events, setEvents] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!targetId) return undefined;
    let active = true;
    const refresh = async () => {
      try {
        const base = `/api/v1/targets/${encodeURIComponent(targetId)}/deadlocks`;
        const [statusResult, eventsResult] = await Promise.all([
          fetch(`${base}/status`), fetch(`${base}?limit=20`)
        ]);
        if (!statusResult.ok || !eventsResult.ok) throw new Error('deadlock API failure');
        const [nextStatus, nextEvents] = await Promise.all([
          statusResult.json(), eventsResult.json()
        ]);
        if (active) {
          setStatus(nextStatus);
          setEvents(nextEvents);
          setError('');
        }
      } catch {
        if (active) setError('Deadlock 이벤트 API 연결에 실패했습니다.');
      }
    };
    refresh();
    const timer = setInterval(refresh, 15000);
    return () => { active = false; clearInterval(timer); };
  }, [targetId]);

  return <section id="deadlocks" className="card deadlock-panel">
    <div className="section-heading">
      <div><h2>Deadlock Event Analysis</h2>
        <p>SQL Server system_health Extended Events · 실제 데드락 이력</p></div>
      <span className="small-pill">15s collect</span>
    </div>
    <div className="deadlock-state">
      <span className={`deadlock-status ${status?.status === 'ONLINE' ? 'online' : 'unknown'}`}>
        {status?.status === 'ONLINE' ? '이벤트 수집 정상'
          : status?.status === 'OFFLINE' ? '이벤트 수집 오류'
          : status?.status || '상태 확인 중'}</span>
      <span>마지막 수집 {timeFmt(status?.lastCollectedAt)}</span>
      <span>기록된 이벤트 <strong>{fmt(events.length)}</strong>건</span>
    </div>
    {error && <div className="banner error"><AlertCircle size={16}/>{error}</div>}
    {status?.status === 'OFFLINE' && <div className="banner error"><AlertCircle size={16}/>
      system_health XEL 접근 권한, 파일 대상, SQL Server 이벤트 수집 로그를 확인해 주세요.
      ({status.errorCode || 'UNKNOWN'})</div>}
    {events.length === 0
      ? <div className="deadlock-empty"><CircleCheck size={18}/>
          저장된 Deadlock 이벤트가 없습니다. 미발생 상태와 수집 오류는 다릅니다.
        </div>
      : <div className="deadlock-list">
          {events.map(event => <article className="deadlock-event" key={event.id}>
            <div className="deadlock-event-head">
              <div><span className="deadlock-id">DEADLOCK #{event.id}</span>
                <strong>{new Date(event.occurredAt).toLocaleString('ko-KR')}</strong></div>
              <span className="deadlock-event-pill">SQL Server 감지 이벤트</span>
            </div>
            <div className="deadlock-stat-row">
              <span>해당 DB 참여 세션 <strong>{fmt(event.localParticipantCount)}</strong>개</span>
              <span>해당 DB 희생 세션 <strong>
                {event.victimSessionId == null ? '알 수 없음' : `#${event.victimSessionId}`}
              </strong></span>
            </div>
            <div className="deadlock-participants">
              {(event.participants || []).map(p => <div
                  className={`deadlock-participant ${p.victim ? 'victim' : ''}`}
                  key={p.sessionId}>
                <span>Session #{p.sessionId}</span>
                <strong>{p.victim ? 'VICTIM' : 'PARTICIPANT'}</strong>
              </div>)}
            </div>
          </article>)}
        </div>}
    <p className="deadlock-footnote">
      원본 데드락 그래프와 SQL/파라미터는 수집하거나 저장하지 않습니다.
      참여 세션은 현재 연결한 DB 기준으로 필터링되며, 다른 DB에 속한 희생자는 표시되지 않을 수 있습니다.
      XEL 파일 반영 시점에 따라 이벤트 표시가 지연될 수 있습니다. 데드락 분석 결과는 자동 KILL 명령으로 연결되지 않습니다.
    </p>
  </section>;
}

function App() {
  const [target, setTarget] = useState(null);
  const [history, setHistory] = useState([]);
  const [error, setError] = useState('');
  const [updated, setUpdated] = useState(null);
  const [queryAnalysis, setQueryAnalysis] = useState([]);
  const [queryState, setQueryState] = useState(null);
  const [queryError, setQueryError] = useState('');
  const [incidents, setIncidents] = useState([]);
  const [incidentError, setIncidentError] = useState('');
  const [selectedIncidentId, setSelectedIncidentId] = useState(null);

  useEffect(() => {
    let active = true;
    const refresh = async () => {
      try {
        const targetsResponse = await fetch('/api/v1/targets');
        if (!targetsResponse.ok) throw new Error('targets API error');
        const targets = await targetsResponse.json();
        const next = targets[0];
        if (!next) throw new Error('target missing');
        const historyResponse = await fetch(`/api/v1/targets/${encodeURIComponent(next.id)}/metrics?limit=60`);
        if (!historyResponse.ok) throw new Error('metrics API error');
        const rows = await historyResponse.json();
        if (!active) return;
        setTarget(next);
        setHistory(rows);
        setUpdated(new Date());
        setError('');
      } catch {
        if (active) setError('API 연결 실패 — Docker 백엔드가 실행 중인지 확인해 주세요.');
      }
    };
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => { active = false; clearInterval(timer); };
  }, []);

  // Query Store is a separate service with a slower cadence than the DMV collector.
  useEffect(() => {
    if (!target?.id) return undefined;
    let active = true;
    const fetchAnalysis = async () => {
      try {
        const base = `/api/v1/targets/${encodeURIComponent(target.id)}/query-performance`;
        const [statusRes, rowsRes] = await Promise.all([
          fetch(`${base}/status`), fetch(`${base}?limit=20`)
        ]);
        if (!statusRes.ok || !rowsRes.ok) throw new Error('query performance API error');
        const [status, rows] = await Promise.all([statusRes.json(), rowsRes.json()]);
        if (!active) return;
        setQueryState(status);
        setQueryAnalysis(rows);
        setQueryError('');
      } catch {
        if (active) setQueryError('Query Store 분석 API에 접근할 수 없습니다. 백엔드 빌드 상태를 확인하세요.');
      }
    };
    fetchAnalysis();
    const timer = setInterval(fetchAnalysis, 15000);
    return () => { active = false; clearInterval(timer); };
  }, [target?.id]);

  // Separate from provisional signals; this shows persisted incident episodes.
  useEffect(() => {
    if (!target?.id) return undefined;
    let active = true;
    const refresh = async () => {
      try {
        const response = await fetch(`/api/v1/targets/${encodeURIComponent(target.id)}/incidents?limit=30`);
        if (!response.ok) throw new Error('incident API error');
        const rows = await response.json();
        if (active) { setIncidents(rows); setIncidentError(''); }
      } catch {
        if (active) setIncidentError('장애 이력 API에 연결할 수 없습니다.');
      }
    };
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [target?.id]);

  const INCIDENT_LABELS = {
    BLOCKING: 'Blocking 감지',
    LONG_RUNNING: '장시간 요청 감지',
    COLLECTION_FAILURE: '수집기 오류 감지',
  };

  const DIAGNOSIS = {
    SLOW_HIGH_READS: ['읽기량과 실행 시간 증가', '기존 인덱스·실행계획을 확인할 후보'],
    SLOW_HIGH_CPU: ['CPU와 실행 시간 증가', '실행계획·연산 및 대기시간을 확인할 후보'],
    SLOW_AVERAGE: ['평균 실행 시간 증가', '대기·Blocking·실행계획을 확인할 후보'],
    HIGH_READS: ['논리 읽기량 높음', '필터 조건과 인덱스 효율을 확인할 후보'],
    HIGH_CPU: ['CPU 사용량 높음', '연산·정렬 및 쿼리 형태를 확인할 후보'],
    OBSERVE: ['관찰 대상', '현재 임계값 기준 문제를 확정할 수 없음'],
  };

  const latest = history.at(-1);
  const hasData = !!latest && target?.collectorStatus === 'ONLINE';
  const status = STATUS[target?.collectorStatus] || STATUS.INITIALIZING;
  const events = useMemo(() => history.filter((row) => row.blockedRequests > 0 || row.longRunningRequests > 0).slice(-4).reverse(), [history]);

  return <div className="layout">
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Activity size={22} strokeWidth={3} /></div><span>DBPulse<sup>alpha</sup></span></div>
      <div className="nav-caption">WORKSPACE</div>
      <div className="nav-item active"><Gauge size={19} />Overview</div>
      <div className="nav-item disabled"><Database size={19} />Databases <span>예정</span></div>
      <a className="nav-item" href="#incidents"><ShieldAlert size={19} />Incidents <span>beta</span></a>
      <a className="nav-item" href="#blocking-chains"><Network size={19} />Blocking Chains <span>beta</span></a>
      <a className="nav-item" href="#wait-diagnostics"><Clock3 size={19} />Wait Diagnostics <span>beta</span></a>
      <a className="nav-item" href="#deadlocks"><ShieldAlert size={19} />Deadlocks <span>beta</span></a>
      <a className="nav-item" href="#query-performance"><Sparkles size={19} />Query Performance <span>beta</span></a>
      <div className="nav-caption second">SYSTEM</div>
      <div className="nav-item disabled"><BellRing size={19} />Notifications <span>예정</span></div>
      <div className="sidebar-bottom"><div className="tag"><Layers3 size={15}/> Phase 8 · Local Development</div><div>v0.8.0 alpha — Incident wait correlation</div></div>
    </aside>
    <main className="main">
      <div className="topbar"><div className="breadcrumbs">Workspace <span>/</span> Overview</div><div className="liveflag"><span className="live-dot" />LIVE DATA <small>3s UI refresh</small></div></div>
      <div className="content">
        <div className="title-row"><div><div className="eyebrow">DATABASE OBSERVABILITY</div><h1>Overview</h1><p>연결된 SQL Server의 현재 요청과 대기 상태를 확인합니다.</p></div><div className="updated"><RefreshCw size={15}/> 마지막 화면 갱신 {updated ? timeFmt(updated.toISOString()) : '—'}</div></div>
        {error && <div className="banner error"><AlertCircle size={18}/>{error}</div>}
        <section className="target-bar card">
          <div className="server-icon"><Server size={23}/></div>
          <div className="target-info"><strong>{target?.name || 'Local SQL Server'}</strong><span>MSSQL · {target?.databaseName || 'DBPulseLab'} · 전용 수집기</span></div>
          <div className={`status status-${status.tone}`}><span className="status-dot"/>{status.label}</div>
        </section>
        {target?.collectorStatus === 'NOT_CONFIGURED' && <div className="banner info"><Terminal size={18}/>MSSQL 수집을 시작하려면 프로젝트의 <code>.env</code>에서 연결 값을 설정하고 백엔드를 재시작하세요.</div>}
        {target?.collectorStatus === 'OFFLINE' && <div className="banner error"><WifiOff size={18}/>연결 또는 수집에 실패했어요. MSSQL 계정, TCP 포트, DMV 조회 권한을 확인하세요. ({target.errorCode || 'UNKNOWN'})</div>}
        <div className="metrics-grid">
          <Metric label="Active requests" value={hasData ? latest.activeRequests : null} unit="건" Icon={Activity} note="현재 실행 중인 요청 수" />
          <Metric label="Blocked requests" value={hasData ? latest.blockedRequests : null} unit="건" Icon={LockKeyhole} note="다른 세션에 의해 대기 중" tone={latest?.blockedRequests ? 'red' : ''} />
          <Metric label="Long-running" value={hasData ? latest.longRunningRequests : null} unit="건" Icon={Clock3} note="실행 1,000ms 이상" tone={latest?.longRunningRequests ? 'amber' : ''} />
          <Metric label="Max elapsed" value={hasData ? latest.maxElapsedMs : null} unit="ms" Icon={Gauge} note="현재 요청의 최장 실행 시간" />
        </div>
        <div className="charts-grid">
          <article className="card graph"><div className="section-heading"><div><h2>Active request trend</h2><p>최근 최대 60개 샘플 · 실제 DMV 조회 결과</p></div><span className="small-pill">5s collect</span></div><MiniChart items={history} field="activeRequests" emptyText="아직 수집된 데이터가 없습니다." /><div className="chart-foot"><span>{history.length ? timeFmt(history[0].collectedAt) : '—'}</span><span>{history.length ? timeFmt(latest.collectedAt) : '—'}</span></div></article>
          <article className="card graph"><div className="section-heading"><div><h2>Blocking trend</h2><p>다른 트랜잭션으로 인해 차단된 요청 수</p></div><span className="small-pill">5s collect</span></div><MiniChart items={history} field="blockedRequests" tone="orange" emptyText="아직 수집된 데이터가 없습니다." /><div className="chart-foot"><span>{history.length ? timeFmt(history[0].collectedAt) : '—'}</span><span>{history.length ? timeFmt(latest.collectedAt) : '—'}</span></div></article>
        </div>
        <section className="card activity-panel"><div className="section-heading"><div><h2>Recent performance signals</h2><p>수집 샘플 중 Blocking 또는 장시간 요청이 확인된 시점</p></div><span className="small-pill">READ ONLY</span></div>
          {events.length ? events.map((item, i) => <div className="event" key={`${item.collectedAt}-${i}`}><div className="event-icon"><AlertCircle size={18}/></div><div><strong>{item.blockedRequests ? `Blocking ${item.blockedRequests}건` : `장시간 요청 ${item.longRunningRequests}건`}</strong><p>최장 실행 {fmt(item.maxElapsedMs)}ms · {timeFmt(item.collectedAt)}</p></div><ArrowUpRight size={16} className="muted" /></div>) : <div className="empty-events"><CircleCheck size={21}/><div>현재 표시할 성능 이상 신호가 없습니다.<small>수집 데이터가 없거나, 아직 탐지된 항목이 없습니다.</small></div></div>}
        </section>
        <BlockingChainsPanel targetId={target?.id} />
        <WaitDiagnostics targetId={target?.id} />
        <DeadlockHistoryPanel targetId={target?.id} />
        <section id="incidents" className="card incident-panel">
          <div className="section-heading">
            <div><h2>Incident History</h2><p>Blocking · 장시간 요청 · 수집 실패의 지속 상태를 기록합니다.</p></div>
            <span className="small-pill">5s collect</span>
          </div>
          {incidentError && <div className="banner error"><AlertCircle size={17}/>{incidentError}</div>}
          <div className="incident-legend">
            <span>진행 중 {incidents.filter(row => row.status === 'OPEN').length}건</span>
            <span>조회된 이력 {incidents.length}건</span>
          </div>
          {incidents.length ? incidents.map(item => (
            <div className="incident-entry" key={item.id}>
              <div className={`incident-indicator ${item.status === 'OPEN' ? 'open' : 'resolved'}`}>
                {item.status === 'OPEN' ? <ShieldAlert size={18}/> : <CircleCheck size={18}/>}
              </div>
              <div className="incident-details">
                <div className="incident-entry-header">
                  <strong>{INCIDENT_LABELS[item.type] || item.type}</strong>
                  <span className={`incident-state ${item.status === 'OPEN' ? 'open' : 'resolved'}`}>
                    {item.status}
                  </span>
                </div>
                <p>{item.type === 'COLLECTION_FAILURE'
                  ? '수집 오류가 반복되었습니다. MSSQL 서버 자체 장애라고 단정할 수 없습니다.'
                  : item.type === 'BLOCKING'
                  ? `관측된 차단 요청: ${fmt(item.observedValue)}건`
                  : `관측된 최장 실행 시간: ${fmt(item.observedValue)}ms`}</p>
                <small>최초 감지 {timeFmt(item.startedAt)} · 최근 관측 {timeFmt(item.lastObservedAt)}
                  {item.resolvedAt ? ` · 해결 {timeFmt(item.resolvedAt)}` : ''}</small>
                <button type="button" className="investigation-open"
                  aria-pressed={selectedIncidentId === item.id}
                  onClick={() => setSelectedIncidentId(item.id)}>
                  {selectedIncidentId === item.id ? '조사 보고서 표시 중' : '원인 분석 근거 보기 →'}
                </button>
              </div>
            </div>
          )) : <div className="incident-empty">기록된 장애 이벤트가 없습니다. 실제 수집 중 이상 신호가 지속되면 이곳에 표시됩니다.</div>}
          <p className="incident-note">두 번 연속 이상 신호 시 OPEN, 두 번 연속 정상 관측 시 RESOLVED. 사건의 분석 버튼에서 당시의 수집 근거와 한계를 확인할 수 있습니다. 자동복구는 아직 제공하지 않습니다.</p>
        </section>
                <IncidentInvestigation targetId={target?.id} incidentId={selectedIncidentId}
          onClose={() => setSelectedIncidentId(null)} />
        <section id="query-performance" className="card query-panel">
          <div className="section-heading">
            <div><h2>Query Performance Analyzer</h2><p>최근 15분의 SQL Server Query Store 실행 통계 · 상위 20개 쿼리/실행계획</p></div>
            <span className="small-pill">30s collect</span>
          </div>
          <div className="query-state">
            분석 상태: <strong>{({ONLINE:'수집 중', QUERY_STORE_READ_ONLY:'읽기 전용', QUERY_STORE_DISABLED:'Query Store 비활성', NOT_CONFIGURED:'설정 필요', OFFLINE:'수집 실패', INITIALIZING:'초기화 중'})[queryState?.status] || '확인 중'}</strong>
            {queryState?.lastCollectedAt && <span>마지막 분석 {timeFmt(queryState.lastCollectedAt)}</span>}
          </div>
          {queryError && <div className="banner error"><AlertCircle size={16}/>{queryError}</div>}
          {queryState?.status === 'QUERY_STORE_DISABLED' && <div className="banner info"><Terminal size={16}/>DBPulseLab에서 Query Store를 활성화해야 완료된 쿼리 통계를 분석할 수 있어요.</div>}
          <div className="query-table-scroll"><table className="query-table"><thead><tr>
            <th>Query ID / Plan</th><th>실행 횟수*</th><th>평균 시간</th><th>평균 CPU</th><th>논리 읽기</th><th>분석 신호</th>
          </tr></thead><tbody>
          {queryAnalysis.length ? queryAnalysis.map(row => {
            const [label, suggestion] = DIAGNOSIS[row.diagnosisCode] || DIAGNOSIS.OBSERVE;
            return <tr key={`${row.queryId}-${row.planId}-${row.collectedAt}`}>
              <td><strong>#{row.queryId}</strong><small>plan {row.planId}</small></td>
              <td>{fmt(row.executionCount)}</td>
              <td>{Number(row.avgDurationMs).toFixed(2)} ms</td>
              <td>{Number(row.avgCpuMs).toFixed(2)} ms</td>
              <td>{Number(row.avgLogicalReadsPages).toFixed(1)} pages</td>
              <td><span className={row.diagnosisCode === 'OBSERVE' ? 'hint hint-neutral' : 'hint'}>{label}</span><small>{suggestion}</small></td>
            </tr>;
          }) : <tr><td className="query-empty" colSpan="6">아직 분석할 쿼리가 없습니다. Query Store 설정과 테스트 쿼리 실행 여부를 확인하세요.</td></tr>}
          </tbody></table></div>
          <p className="query-note">* 실행 횟수는 최근 15분 Query Store 통계의 집계값이며 30초 동안 새로 발생한 건수가 아닙니다. 분석 신호는 수치 기반 점검 후보이지 확정된 원인이나 인덱스 추천이 아닙니다. SQL 원문·파라미터는 수집하지 않습니다.</p>
        </section>
        <div className="disclaimer"><Radio size={14}/> 수집 값은 SQL Server의 해당 DB에서 관찰된 실행 중 요청의 시점별 스냅샷입니다. 전체 처리량이나 완료된 쿼리 건수를 의미하지 않습니다.</div>
      </div>
    </main>
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);

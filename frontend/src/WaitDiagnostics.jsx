import React, { useEffect, useState } from 'react';

const fmt = value => value == null ? '—' : new Intl.NumberFormat('ko-KR').format(value);
const time = iso => iso ? new Date(iso).toLocaleTimeString('ko-KR', { hour12:false }) : '—';

const CATEGORY_NAMES = {
  LOCK:'Lock 대기', STORAGE_IO:'스토리지 I/O', TRANSACTION_LOG:'트랜잭션 로그',
  MEMORY:'메모리', PARALLELISM:'병렬 처리', SCHEDULER:'스케줄러',
  NETWORK:'네트워크', AVAILABILITY:'고가용성', OTHER:'기타'
};

/** Request-level point-in-time DMV samples; not lifetime wait statistics. */
export default function WaitDiagnostics({ targetId }) {
  const [current, setCurrent] = useState(null);
  const [history, setHistory] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!targetId) return undefined;
    let live = true;
    setCurrent(null);
    setHistory([]);
    const refresh = async () => {
      try {
        const base = `/api/v1/targets/${encodeURIComponent(targetId)}/waits`;
        const [response, pastResponse] = await Promise.all([
          fetch(base), fetch(`${base}/history?limit=20`)
        ]);
        if (!response.ok || !pastResponse.ok) throw new Error('Wait API unavailable');
        const [now, past] = await Promise.all([response.json(), pastResponse.json()]);
        if (live) { setCurrent(now); setHistory(past); setError(''); }
      } catch {
        if (live) {
          setCurrent(null);
          setError('Wait Diagnostics API 연결에 실패했습니다. 백엔드 상태를 확인해 주세요.');
        }
      }
    };
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => { live = false; clearInterval(timer); };
  }, [targetId]);

  const isOnline = current?.status === 'ONLINE';
  const waits = isOnline ? current.observations || [] : [];
  const byCategory = waits.reduce((acc, w) => {
    acc[w.waitCategory] = (acc[w.waitCategory] || 0) + 1;
    return acc;
  }, {});
  const categories = Object.entries(byCategory).sort((a,b) => b[1]-a[1]);

  return <section id="wait-diagnostics" className="card wait-panel">
    <div className="section-heading">
      <div><h2>Wait Diagnostics</h2><p>실행 중 요청의 대기 유형·시간·리소스 종류 관측</p></div>
      <span className="small-pill">5s collect</span>
    </div>
    <div className="wait-statusline">
      <span className={'wait-state ' + (isOnline ? 'online' : 'offline')}>
        {isOnline ? '수집 정상' : current?.status || '확인 중'}
      </span>
      <span>마지막 수집 {time(current?.collectedAt)}</span>
      <span>현재 관측 요청 <strong>{isOnline ? fmt(current.observedCount) : '—'}</strong>건</span>
    </div>
    {error && <div className="banner error">{error}</div>}
    {current && !isOnline && <div className="banner error">대기 수집이 중단되었거나 오래됐습니다. 과거 상태를 현재 상태로 표시하지 않습니다. ({current.errorCode || 'UNKNOWN'})</div>}
    {isOnline && current.limitReached &&
      <div className="banner info">최대 100개의 대기 요청만 수집했습니다. 더 많은 대기 요청은 이 샘플에서 제외됐을 수 있습니다.</div>}
    {isOnline && !waits.length && <div className="wait-empty">현재 샘플에 관측된 대기 요청이 없습니다.</div>}
    {categories.length > 0 && <div className="wait-category-list">
      {categories.map(([category, count]) => <div key={category} className="wait-category-pill">
        <span>{CATEGORY_NAMES[category] || category}</span><strong>{count}건</strong>
      </div>)}
    </div>}
    {waits.length > 0 && <div className="wait-table-scroller">
      <table className="wait-table"><thead><tr>
        <th>세션</th><th>Wait Type</th><th>분류</th><th>리소스</th><th>대기 시간</th><th>차단 세션</th>
      </tr></thead><tbody>
      {waits.map((w,i) => <tr key={`${current.collectedAt}-${w.sessionId}-${i}`}>
        <td><strong>#{w.sessionId}</strong></td>
        <td>{w.waitType}</td>
        <td>{CATEGORY_NAMES[w.waitCategory] || w.waitCategory}</td>
        <td>{w.resourceKind}</td>
        <td>{fmt(w.waitMs)}ms</td>
        <td>{w.blockingSessionId == null ? '—' : '#' + w.blockingSessionId}</td>
      </tr>)}
      </tbody></table>
    </div>}
    <div className="wait-history-head"><strong>최근 대기 관측 이력</strong><small>최대 20개 기록</small></div>
    {history.length
      ? <div className="wait-history-list">
          {history.map((entry,i) =>
            <div key={`${entry.collectedAt}-${entry.observation.sessionId}-${i}`} className="wait-history-item">
              <span>{time(entry.collectedAt)}</span>
              <strong>#{entry.observation.sessionId}</strong>
              <span>{entry.observation.waitType}</span>
              <span>{fmt(entry.observation.waitMs)}ms</span>
            </div>)}
        </div>
      : <div className="wait-empty">저장된 대기 관측 이력이 없습니다.</div>}
    <p className="wait-footnote">이 값은 5초 간격으로 캡처된 실행 요청의 순간 대기 정보이며, SQL Server 전체 누적 Wait Statistics나 확정된 성능 저하 원인이 아닙니다.
      원본 wait_resource, SQL 원문, 파라미터 및 사용자 정보는 수집하지 않습니다.</p>
  </section>;
}

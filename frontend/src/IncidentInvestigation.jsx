import React, { useEffect, useState } from 'react';

const fmt = v => v == null ? '—' : new Intl.NumberFormat('ko-KR').format(v);
const time = v => v ? new Date(v).toLocaleString('ko-KR') : '—';

export default function IncidentInvestigation({ targetId, incidentId, onClose }) {
  const [report, setReport] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!incidentId || !targetId) return undefined;
    let alive = true;
    setReport(null);
    const fetchReport = async () => {
      setLoading(true);
      try {
        const url = `/api/v1/targets/${encodeURIComponent(targetId)}/incidents/${incidentId}/investigation`;
        const response = await fetch(url);
        if (!response.ok) throw new Error('investigation API failed');
        const data = await response.json();
        if (alive) { setReport(data); setError(''); }
      } catch {
        if (alive) setError('조사 API를 조회하지 못했습니다. 백엔드 로그를 확인해 주세요.');
      } finally {
        if (alive) setLoading(false);
      }
    };
    fetchReport();
    const timer = setInterval(fetchReport, 10000);
    return () => { alive = false; clearInterval(timer); };
  }, [targetId, incidentId]);

  if (!incidentId) return null;
  return <section id="incident-investigation" className="card incident-investigation">
    <div className="section-heading">
      <div><h2>Incident Investigation <small>#{incidentId}</small></h2>
        <p>사건 당시의 성능 지표·Blocking·Wait·Deadlock의 저장된 관측값을 함께 조회합니다.</p></div>
      <button type="button" className="investigation-close" onClick={onClose}>닫기</button>
    </div>
    {loading && !report && <p className="investigation-empty">관련 증거 조회 중...</p>}
    {error && <div className="banner error">{error}</div>}
    {report && <>
      <div className="investigation-window">
        관측 구간: {time(report.window.from)} ~ {time(report.window.to)}
        {report.window.truncated && <strong>· 15분 조회 제한으로 과거 구간 일부가 제외됐습니다.</strong>}
      </div>
      <div className="investigation-stats">
        <div><small>지표 샘플</small><strong>{fmt(report.metrics.samples)}건</strong></div>
        <div><small>최대 Blocking</small><strong>{fmt(report.metrics.peakBlocked)}건</strong></div>
        <div><small>최대 장시간 요청</small><strong>{fmt(report.metrics.peakLongRunning)}건</strong></div>
        <div><small>최장 실행</small><strong>{fmt(report.metrics.peakElapsedMs)}ms</strong></div>
      </div>
      <h3 className="investigation-subtitle">관측 근거와 판단 한계</h3>
      <div className="investigation-findings">
        {report.findings.map((finding, i) =>
          <div className="investigation-finding" key={finding.code + i}>
            <span className={'investigation-kind ' + (finding.kind === 'OBSERVED' ? 'observed' : 'limitation')}>
              {finding.kind === 'OBSERVED' ? '관측 사실' : '판단 한계'}
            </span>
            <div><strong>{finding.title}</strong><p>{finding.detail}</p></div>
          </div>)}
      </div>
      <h3 className="investigation-subtitle">Blocking 세션 관계 <small>최근 {report.blocking.length}개 관측</small></h3>
      {report.blocking.length
        ? <div className="investigation-observations">
          {report.blocking.map((edge, i) =>
            <div className="investigation-observation" key={edge.collectedAt + i}>
              <strong>#{edge.blockingSessionId} → #{edge.blockedSessionId}</strong>
              <span>{edge.waitType} · {fmt(edge.waitMs)}ms 대기</span>
              <small>{time(edge.collectedAt)}</small>
            </div>)}
        </div>
        : <div className="investigation-empty">저장된 세션별 Blocking 근거가 없습니다.</div>}
      <h3 className="investigation-subtitle">Wait 관측 근거
        <small> 수집 시점 {fmt(report.waitEvidence?.sampleCount)}개 · 반복 관측 {fmt(report.waitEvidence?.sampledWaitCount)}건</small>
      </h3>
      {report.waitEvidence?.truncatedSampleCount > 0 &&
        <div className="banner info">
          Wait 요청 최대 100건 수집 제한에 도달한 시점이 {fmt(report.waitEvidence.truncatedSampleCount)}회 있습니다.
          아래 내용은 전체 대기 통계가 아닙니다.
        </div>}
      {(report.waitEvidence?.categories || []).length
        ? <div className="investigation-wait-categories">
          {report.waitEvidence.categories.map(category =>
            <div className="investigation-wait-category" key={category.category}>
              <strong>{category.category}</strong>
              <span>{fmt(category.sampledCount)}개 표본 관측</span>
              <small>최대 {fmt(category.peakWaitMs)}ms</small>
            </div>)}
        </div>
        : <div className="investigation-empty">해당 시간대에 저장된 Wait 관측이 없습니다. 수집 공백과 대기 미발생은 서로 다른 상태입니다.</div>}
      {(report.waitEvidence?.recent || []).length > 0 &&
        <div className="investigation-observations investigation-wait-observations">
          {report.waitEvidence.recent.map((wait, i) =>
            <div className="investigation-observation" key={wait.collectedAt + '-' + wait.sessionId + '-' + i}>
              <strong>세션 #{wait.sessionId} · {wait.waitType}</strong>
              <span>{wait.waitCategory} · {wait.resourceKind} · {fmt(wait.waitMs)}ms
                {wait.blockingSessionId == null ? '' : ` · 차단 세션 #${wait.blockingSessionId}`}</span>
              <small>{time(wait.collectedAt)}</small>
            </div>)}
        </div>}
      <p className="investigation-wait-disclaimer">동일 요청이 여러 수집 시점에 반복 관측될 수 있어 합계를 실제 고유 대기 횟수나 전체 발생 건수로 해석하면 안 됩니다.</p>
      <h3 className="investigation-subtitle">인접한 Deadlock 이벤트 <small>{report.deadlocks.length}건</small></h3>
      {report.deadlocks.length
        ? <div className="investigation-observations">
          {report.deadlocks.map(d =>
            <div className="investigation-observation" key={d.id}>
              <strong>Deadlock #{d.id}</strong>
              <span>참여 세션 {fmt(d.localParticipantCount)}개 · Victim {d.victimSessionId == null ? '미확인' : '#' + d.victimSessionId}</span>
              <small>{time(d.occurredAt)}</small>
            </div>)}
        </div>
        : <div className="investigation-empty">해당 기간의 Deadlock 이벤트 기록이 없습니다.</div>}
      <p className="investigation-disclaimer">
        같은 시간대의 사건은 관련 가능성을 조사하는 단서일 뿐, 확정 원인이 아닙니다.
        과거에 수집되지 않은 정보는 표시할 수 없으며 이 화면은 DB 수정·KILL·복구 명령을 실행하지 않습니다.
      </p>
    </>}
  </section>;
}

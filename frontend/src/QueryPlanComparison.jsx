import React, { useEffect, useState } from 'react';
import { GitCompareArrows, AlertTriangle } from 'lucide-react';

const fmt = n => n === null || n === undefined
  ? '—'
  : Number(n).toLocaleString('ko-KR',{maximumFractionDigits:2});
const labels = {
  MEAN_DURATION_DIFFERENCE:'평균 실행 시간 차이 관측',
  INSUFFICIENT_SAMPLES:'비교 표본 부족',
  SMALL_DURATION_BASELINE:'짧은 실행 시간 — 배율 비교 유보',
  NO_LARGE_MEAN_DIFFERENCE:'큰 평균 시간 차이 미관측'
};

export default function QueryPlanComparison({targetId,queryId,planId}) {
  const [report,setReport] = useState(null);
  const [error,setError] = useState('');
  const [loading,setLoading] = useState(false);

  useEffect(()=>{setReport(null);setError('');},[targetId,queryId,planId]);

  async function load() {
    if(!targetId || loading) return;
    setLoading(true);setError('');
    try{
      const url=`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/plan-comparison/${queryId}/${planId}`;
      const response=await fetch(url);
      if(!response.ok) throw new Error(String(response.status));
      setReport(await response.json());
    } catch(e) {
      setReport(null);
      setError(e.message==='404'
        ? '선택한 계획이 최신 수집 대상에 없습니다. Query Store 수집 후 다시 선택해 주세요.'
        : e.message==='503'
          ? 'Query Store 수집이 중단되었거나 최신 데이터가 없습니다.'
          : '계획 비교 API를 조회할 수 없습니다. 백엔드 버전과 로그를 확인해 주세요.');
    } finally {setLoading(false);}
  }

  return <section className="rev-plan-compare" aria-label="Query Store 실행계획별 성능 비교">
    <div className="rev-plan-compare-heading">
      <div>
        <h4><GitCompareArrows size={16}/> 같은 SQL의 다른 계획 비교</h4>
        <p>같은 Query ID의 여러 Plan ID가 수집됐을 때만 서로 비교할 수 있습니다.</p>
      </div>
      <button type="button" disabled={loading} onClick={load}>
        {loading?'비교 중…':report?'계획 비교 새로고침':'실행계획별 성능 비교'}
      </button>
    </div>
    {error && <p className="rev-plan-compare-warning" role="alert">{error}</p>}
    {report && <>
      <div className="rev-plan-compare-selected">
        <span>선택한 기준 계획 <strong>Query #{report.queryId} · Plan #{report.selectedPlanId}</strong></span>
        <div className="rev-plan-compare-stats">
          <span>평균 실행 <b>{fmt(report.selected.avgDurationMs)} ms</b></span>
          <span>평균 CPU <b>{fmt(report.selected.avgCpuMs)} ms</b></span>
          <span>논리 읽기 <b>{fmt(report.selected.avgLogicalReadsPages)} pages</b></span>
          <span>집계 실행 횟수 <b>{fmt(report.selected.executionCount)}</b></span>
        </div>
      </div>
      {report.status==='ONLY_ONE_PLAN_SAMPLED'
        ? <p className="rev-plan-compare-empty">현재 수집된 목록에서 이 Query ID의 다른 Plan ID를 찾지 못했습니다. 다른 계획이 실제로 존재하지 않는다는 뜻은 아닙니다.</p>
        : <div className="rev-plan-compare-list">
          {(report.comparisons||[]).map(item=><article key={item.planId} className="rev-plan-compare-peer">
            <div className="rev-plan-compare-peer-head">
              <strong>비교 계획 · Plan #{item.planId}</strong>
              <span className={item.assessment==='MEAN_DURATION_DIFFERENCE'?'rev-plan-compare-review':''}>
                {labels[item.assessment]||item.assessment}
              </span>
            </div>
            <div className="rev-plan-compare-stats">
              <span>평균 실행 <b>{fmt(item.metrics.avgDurationMs)} ms</b></span>
              <span>평균 CPU <b>{fmt(item.metrics.avgCpuMs)} ms</b></span>
              <span>논리 읽기 <b>{fmt(item.metrics.avgLogicalReadsPages)} pages</b></span>
              <span>집계 실행 횟수 <b>{fmt(item.metrics.executionCount)}</b></span>
              <span>기준 계획 대비 실행시간 <b>{item.durationRatioLabel||'비교 유보'}</b></span>
            </div>
            <p className="rev-plan-compare-next">{item.nextCheck}</p>
          </article>)}
        </div>}
      <p className="rev-plan-compare-limitation"><AlertTriangle size={15}/>{report.limitation}</p>
    </>}
  </section>;
}

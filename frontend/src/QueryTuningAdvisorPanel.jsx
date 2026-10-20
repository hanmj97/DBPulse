import React, { useEffect, useState } from 'react';
import { ChevronDown, CircleHelp, Sparkles, ShieldCheck } from 'lucide-react';
import PlanOperatorEvidence from './PlanOperatorEvidence.jsx';
import QueryPlanComparison from './QueryPlanComparison.jsx';
import { filterQueryCandidates, filterQuerySources } from './queryCandidateFilters.js';

const fmt = value => value == null ? '—' : Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 });
const LABELS = {HIGH:'우선 점검', MEDIUM:'점검 권장', OBSERVE:'관찰', INTERNAL:'수집 부하'};
const SIGNALS = {
  SLOW_HIGH_READS:'실행 지연·논리 읽기 높음', SLOW_HIGH_CPU:'실행 지연·CPU 높음',
  SLOW_AVERAGE:'평균 실행 시간 높음', HIGH_READS:'논리 읽기 높음',
  HIGH_CPU:'CPU 높음', OBSERVE:'현재 임계값 미만',
  COLLECTOR_OVERHEAD:'DBPulse 내부 수집 SQL · 관측 전용'
};

export default function QueryTuningAdvisorPanel({ targetId, sourceFilter = 'exclude-dbpulse', onShowSql }) {
  const [report,setReport] = useState(null);
  const [error,setError] = useState('');
  const [selected,setSelected] = useState(null);
  const [filter,setFilter] = useState('ALL');
  const [sort,setSort] = useState('recent');
  // Lab IDs are examples only; filters stay reusable for any monitored database.
  const [queryIdFilter,setQueryIdFilter] = useState('');
  const [planIdFilter,setPlanIdFilter] = useState('');
  useEffect(() => {
    if (!targetId) return undefined;
    let active = true;
    setReport(null);
    const refresh = async () => {
      try {
        const res = await fetch(`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/advisor?limit=50&sort=${encodeURIComponent(sort)}`);
        if(!res.ok) throw new Error('Advisor API failed');
        const data = await res.json();
        if(active) { setReport(data);setError(''); }
      } catch {
        if(active) { setError('SQL Tuning Advisor API 조회 실패 — 백엔드 버전과 로그를 확인해 주세요.');setReport(null); }
      }
    };
    refresh();
    const timer = setInterval(refresh, 15000);
    return () => {active = false;clearInterval(timer);};
  },[targetId,sort]);

  const rows = filterQuerySources(report?.candidates || [], sourceFilter);
  const shown = filterQueryCandidates(rows, {
    priority: filter, queryId: queryIdFilter, planId: planIdFilter
  });
  const hasIdSearch = queryIdFilter !== '' || planIdFilter !== '';
  const clearIdSearch = () => {setQueryIdFilter('');setPlanIdFilter('');};
  const ready = report && ['ONLINE','QUERY_STORE_READ_ONLY'].includes(report.status);
  return <section id="sql-tuning-advisor" className="card rev-tuning-panel">
    <div className="section-heading">
      <div><span className="rev-tuning-eyebrow"><Sparkles size={14}/>QUERY STORE INSIGHTS</span>
        <h2>SQL Tuning Advisor <small>v0.12 alpha</small></h2>
        <p>실행시간·평균 CPU 시간·집계 실행 횟수·논리 읽기를 함께 비교해 점검할 항목을 제안합니다.</p>
      </div>
      <span className="small-pill">읽기 전용</span>
    </div>
    {error && <div className="banner error">{error}</div>}
    {report && !ready && <p className="rev-tuning-empty">현재 조회할 수 있는 최신 Query Store 데이터가 없습니다.
      수집 상태: <strong>{report.status}</strong> {report.errorCode && `(${report.errorCode})`}</p>}
    {ready && <>
      <div className="rev-tuning-stats">
        {[
          ['분석 대상',rows.length],
          ['우선 점검',rows.filter(x=>x.priority==='HIGH').length],
          ['점검 권장',rows.filter(x=>x.priority==='MEDIUM').length],
          ['수집 시각',report.collectedAt ? new Date(report.collectedAt).toLocaleTimeString('ko-KR',{hour12:false}) : '—']
        ].map(([name,value])=><div key={name}><small>{name}</small><strong>{value}</strong></div>)}
      </div>
      <div className="rev-tuning-controls">
        <div className="rev-tuning-sort">
          <label htmlFor="dbpulse-query-sort">정렬 기준</label>
          <select id="dbpulse-query-sort" value={sort} onChange={event=>setSort(event.target.value)}>
            <option value="recent">최근 실행순 (기본)</option>
            <option value="priority">튜닝 우선순위순</option>
            <option value="duration">평균 실행시간순</option>
          </select>
        </div>
        <small>최근 실행 시각은 Query Store에 기록된 값이며 약간 늦게 반영될 수 있습니다.</small>
      </div>
      <div className="rev-tuning-id-search" role="search" aria-label="Query ID 및 Plan ID 검색">
        <div className="rev-tuning-id-fields">
          <label htmlFor="dbpulse-tuning-query-id">Query ID
            <input id="dbpulse-tuning-query-id" type="text" inputMode="numeric"
              autoComplete="off" placeholder="예: 239" value={queryIdFilter}
              onChange={event=>setQueryIdFilter(event.target.value.replace(/[^0-9]/g,'').slice(0,19))}/>
          </label>
          <label htmlFor="dbpulse-tuning-plan-id">Plan ID
            <input id="dbpulse-tuning-plan-id" type="text" inputMode="numeric"
              autoComplete="off" placeholder="예: 178" value={planIdFilter}
              onChange={event=>setPlanIdFilter(event.target.value.replace(/[^0-9]/g,'').slice(0,19))}/>
          </label>
          <button type="button" onClick={clearIdSearch} disabled={!hasIdSearch}
            className="rev-tuning-clear-search">검색 초기화</button>
        </div>
        <div className="rev-tuning-search-caption">
          <span aria-live="polite">현재 수집된 {rows.length}개 후보 중 {shown.length}개 표시</span>
          <small>두 ID를 입력하면 모두 일치하는 계획만 표시합니다. 입력 즉시 필터링됩니다.</small>
        </div>
      </div>
      <div className="rev-tuning-filters" role="group" aria-label="튜닝 후보 우선순위 필터">
        {[['ALL','전체'],['HIGH','우선 점검'],['MEDIUM','점검 권장'],['OBSERVE','관찰'],...(sourceFilter!=='exclude-dbpulse'?[['INTERNAL','수집 부하']]:[])].map(([code,label])=>
          <button type="button" className={filter===code?'active':''} aria-pressed={filter===code}
            key={code} onClick={()=>setFilter(code)}>{label}</button>)}
      </div>
      {shown.length===0 && <p className="rev-tuning-empty" role="status">
        {rows.length===0
          ? '현재 Query Store에서 수집된 후보가 없습니다. 수집기와 Query Store 상태를 확인해 주세요.'
          : hasIdSearch
            ? '현재 수집된 후보에서 입력한 Query ID / Plan ID를 찾지 못했습니다. SQL Server Query Store 반영 후 다음 수집 주기(기본 약 60초)를 기다려 보거나 우선순위 필터를 전체로 선택해 주세요. 전체 Query Store를 직접 조회하는 검색은 아닙니다.'
            : '해당 우선순위의 후보가 없습니다.'}
      </p>}
      <div className="rev-tuning-candidates">{shown.map(row=>{
        const key = `${row.queryId}:${row.planId}`;
        const open = selected===key;
        return <article className="rev-tuning-item" key={key}>
          <div className="rev-tuning-row-actions">
          <button type="button" className="rev-tuning-toggle" onClick={()=>setSelected(open?null:key)}
            aria-expanded={open}>
            <span className={'rev-tuning-rank '+row.priority.toLowerCase()}>{LABELS[row.priority]||row.priority}</span>
            <span className="rev-tuning-title"><strong>Query #{row.queryId} · Plan #{row.planId}</strong>
              <small>{SIGNALS[row.diagnosisCode]||row.diagnosisCode}</small>
              <span className="rev-tuning-last-run">
                최근 실행: {row.lastExecutedAt ? new Date(row.lastExecutedAt).toLocaleString('ko-KR',{hour12:false}) : '기록 없음'}
              </span></span>
            <span className="rev-tuning-numeric"><small>평균 실행</small><strong>{fmt(row.metrics.avgDurationMs)} ms</strong></span>
            <span className="rev-tuning-numeric"><small>평균 CPU</small><strong>{fmt(row.metrics.avgCpuMs)} ms</strong></span>
            <span className="rev-tuning-numeric"><small>집계 실행 횟수</small><strong>{fmt(row.metrics.executionCount)}회</strong></span>
            <span className="rev-tuning-numeric"><small>논리 읽기</small><strong>{fmt(row.metrics.avgLogicalReadsPages)} pages</strong></span>
            <ChevronDown className={open?'expanded':''} size={18}/>
          </button>
          <button type="button" className="rev-query-sql-link rev-tuning-row-sql"
            aria-label={`Query ${row.queryId}, Plan ${row.planId} SQL 원문 보기`}
            onClick={()=>onShowSql?.(row.queryId,row.planId)}>
            SQL 보기
          </button>
          </div>
          {open && <div className="rev-tuning-detail">
            <div className="rev-tuning-metrics">
              <span>집계 CPU 총량 추정 <strong>{fmt(Number(row.metrics.avgCpuMs)*Number(row.metrics.executionCount))} ms</strong></span>
              <span>CPU 시간/전체 경과시간과 인스턴스 CPU(%)는 별도 지표</span>
              <span>최근 실행 <strong>{row.lastExecutedAt?new Date(row.lastExecutedAt).toLocaleString('ko-KR'):'—'}</strong></span>
            </div>
            <h3>권장 점검 순서</h3>
            {(row.actions||[]).map((action,index)=><div className="rev-tuning-action" key={action.code}>
              <span>{index+1}</span><div><strong>{action.title}</strong><p>{action.description}</p></div></div>)}
            <QueryPlanComparison key={'compare-'+key} targetId={targetId}
              queryId={row.queryId} planId={row.planId}/>
            <PlanOperatorEvidence key={key} targetId={targetId}
              queryId={row.queryId} planId={row.planId}/>
            <p className="rev-tuning-disclaimer"><CircleHelp size={15}/>{row.limitation}</p>
          </div>}
        </article>;
      })}</div>
    </>}
    <p className="rev-tuning-footer"><ShieldCheck size={17}/>
      DBPulse 수집 SQL 패턴을 기본적으로 숨깁니다. 나머지는 최근 실행 20개 + 실행 비용 상위 20개, DBPulse 패턴은 최근 10개까지 수집합니다. 실행 횟수와 CPU 총량은 Query Store 집계 범위(최근 15분 필터) 기준이며 수집 주기(기본 60초)의 증가량이 아닙니다. CPU 총량은 평균 CPU×집계 횟수의 추정치입니다.
      SQL 원문은 자동 저장하지 않으며, 보안 허용 시 버튼으로만 조회합니다. 인덱스를 자동 추천하거나 생성하지 않습니다.</p>
  </section>;
}

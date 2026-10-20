import React, { useEffect, useState } from 'react';
import { ChevronDown, CircleHelp, Sparkles, ShieldCheck } from 'lucide-react';

const fmt = value => value == null ? '—' : Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 });
const LABELS = {HIGH:'우선 점검', MEDIUM:'점검 권장', OBSERVE:'관찰'};
const SIGNALS = {
  SLOW_HIGH_READS:'실행 지연·논리 읽기 높음', SLOW_HIGH_CPU:'실행 지연·CPU 높음',
  SLOW_AVERAGE:'평균 실행 시간 높음', HIGH_READS:'논리 읽기 높음',
  HIGH_CPU:'CPU 높음', OBSERVE:'현재 임계값 미만'
};

export default function QueryTuningAdvisorPanel({ targetId }) {
  const [report,setReport] = useState(null);
  const [error,setError] = useState('');
  const [selected,setSelected] = useState(null);
  const [filter,setFilter] = useState('ALL');
  useEffect(() => {
    if (!targetId) return undefined;
    let active = true;
    setReport(null);
    const refresh = async () => {
      try {
        const res = await fetch(`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/advisor?limit=20`);
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
  },[targetId]);

  const rows = report?.candidates || [];
  const shown = rows.filter(row=>filter==='ALL'||row.priority===filter);
  const ready = report && ['ONLINE','QUERY_STORE_READ_ONLY'].includes(report.status);
  return <section id="sql-tuning-advisor" className="card rev-tuning-panel">
    <div className="section-heading">
      <div><span className="rev-tuning-eyebrow"><Sparkles size={14}/>QUERY STORE INSIGHTS</span>
        <h2>SQL Tuning Advisor <small>v0.9 alpha</small></h2>
        <p>실제 집계 수치로 검토 우선순위를 정하고 점검할 항목을 제안합니다.</p>
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
      <div className="rev-tuning-filters" role="group" aria-label="튜닝 후보 우선순위 필터">
        {[['ALL','전체'],['HIGH','우선 점검'],['MEDIUM','점검 권장'],['OBSERVE','관찰']].map(([code,label])=>
          <button type="button" className={filter===code?'active':''} aria-pressed={filter===code}
            key={code} onClick={()=>setFilter(code)}>{label}</button>)}
      </div>
      {shown.length===0 && <p className="rev-tuning-empty">
        {rows.length===0 ? '현재 조회된 Query Store 후보가 없습니다.' : '해당 우선순위의 후보가 없습니다.'}
      </p>}
      <div className="rev-tuning-candidates">{shown.map(row=>{
        const key = `${row.queryId}:${row.planId}`;
        const open = selected===key;
        return <article className="rev-tuning-item" key={key}>
          <button type="button" className="rev-tuning-toggle" onClick={()=>setSelected(open?null:key)}
            aria-expanded={open}>
            <span className={'rev-tuning-rank '+row.priority.toLowerCase()}>{LABELS[row.priority]||row.priority}</span>
            <span className="rev-tuning-title"><strong>Query #{row.queryId} · Plan #{row.planId}</strong>
              <small>{SIGNALS[row.diagnosisCode]||row.diagnosisCode}</small></span>
            <span className="rev-tuning-numeric"><small>평균 실행</small><strong>{fmt(row.metrics.avgDurationMs)} ms</strong></span>
            <span className="rev-tuning-numeric"><small>논리 읽기</small><strong>{fmt(row.metrics.avgLogicalReadsPages)} pages</strong></span>
            <ChevronDown className={open?'expanded':''} size={18}/>
          </button>
          {open && <div className="rev-tuning-detail">
            <div className="rev-tuning-metrics">
              <span>평균 CPU <strong>{fmt(row.metrics.avgCpuMs)} ms</strong></span>
              <span>집계 실행 횟수 <strong>{fmt(row.metrics.executionCount)}</strong></span>
              <span>최근 실행 <strong>{row.lastExecutedAt?new Date(row.lastExecutedAt).toLocaleString('ko-KR'):'—'}</strong></span>
            </div>
            <h3>권장 점검 순서</h3>
            {(row.actions||[]).map((action,index)=><div className="rev-tuning-action" key={action.code}>
              <span>{index+1}</span><div><strong>{action.title}</strong><p>{action.description}</p></div></div>)}
            <p className="rev-tuning-disclaimer"><CircleHelp size={15}/>{row.limitation}</p>
          </div>}
        </article>;
      })}</div>
    </>}
    <p className="rev-tuning-footer"><ShieldCheck size={17}/>
      Query Store 상위 20개 이내 query/plan 집계 기반입니다. 실행 횟수는 30초 간 증가량이 아닙니다.
      실제 SQL 원문·실행계획 없이 특정 인덱스를 추천하거나 자동 생성하지 않습니다.</p>
  </section>;
}

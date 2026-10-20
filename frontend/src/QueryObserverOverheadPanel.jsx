import React, { useEffect, useState } from 'react';
import { Activity, Gauge, ShieldCheck, ShieldAlert } from 'lucide-react';

const fmt = (n, digits = 0) => Number(n ?? 0).toLocaleString('ko-KR', {
  maximumFractionDigits: digits
});

const LABELS = {
  NOT_MEASURED:'아직 수집 비용을 관측하지 않았습니다.',
  NOT_CONFIGURED:'MSSQL 수집 대상이 설정되지 않았습니다.',
  INTERNAL_AVG_LOGICAL_READS_HIGH:'DBPulse 내부 SQL의 평균 논리 읽기가 기준을 넘었습니다.',
  INTERNAL_AVG_CPU_HIGH:'DBPulse 내부 SQL의 평균 CPU가 기준을 넘었습니다.',
  COLLECTOR_WALL_TIME_HIGH:'Query Store 수집기 JDBC 작업이 오래 걸렸습니다.',
  QUERY_STORE_COLLECTION_FAILED:'Query Store 수집 실패가 발생했습니다.',
  WITHIN_HEURISTIC_LIMITS:'현재 관측된 징후는 조절 기준 아래입니다.',
  NO_INTERNAL_QUERY_STORE_SAMPLES:'내부 SQL 표본이 없어 서버측 부하를 평가할 수 없습니다.'
};

/**
 * Status is read only from the DBPulse JVM. Refreshing this widget DOES NOT
 * run a new query against the monitored MSSQL instance.
 */
export default function QueryObserverOverheadPanel({targetId}) {
  const [data,setData] = useState(null);
  const [error,setError] = useState(false);

  useEffect(()=>{
    if(!targetId) return undefined;
    let active = true;
    const refresh = async ()=>{
      try {
        const res = await fetch(`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/overhead`);
        if(!res.ok) throw new Error('overhead status unavailable');
        const json = await res.json();
        if(active){setData(json);setError(false);}
      } catch {
        if(active){setError(true);setData(null);}
      }
    };
    refresh();
    const timer=setInterval(refresh,15000);
    return ()=>{active=false;clearInterval(timer);};
  },[targetId]);

  if(error) return <section className="rev-observer-guard" role="status">
    <h3><Gauge size={16}/> 수집 부하 보호</h3>
    <p>수집 부하 상태 API를 조회할 수 없습니다. 백엔드 버전을 확인해 주세요.</p>
  </section>;

  const isBackoff=data?.mode==='THROTTLED';
  return <section className="rev-observer-guard" aria-label="DBPulse 자체 수집 부하 상태">
    <div className="rev-observer-guard-head">
      <div>
        <h3><Activity size={16}/> 수집 부하 보호 <small>v0.13 alpha</small></h3>
        <p>Query Store 수집기의 관측 비용을 확인하고, 부담 징후가 반복되면 이 수집만 완화합니다.</p>
      </div>
      <span className={`rev-observer-guard-status ${isBackoff?'throttled':'normal'}`}>
        {isBackoff?<ShieldAlert size={14}/>:<ShieldCheck size={14}/>}
        {!data?'관측 대기':isBackoff?'수집 간격 완화 중':'기본 간격'}
      </span>
    </div>
    {data && <>
      <div className="rev-observer-guard-metrics">
        <div><span>기준 수집 주기</span><strong>{fmt(data.baseIntervalMs/1000)}초</strong></div>
        <div><span>현재 유효 주기</span><strong>{fmt(data.effectiveIntervalMs/1000)}초</strong></div>
        <div><span>최근 JDBC 수집 소요</span><strong>{fmt(data.lastCollectorWallMs)}ms</strong></div>
        <div><span>내부 SQL 평균 논리 읽기 최대</span>
          <strong>{data.sampledInternalPlans>0?fmt(data.peakInternalAvgLogicalReadsPages,1)+' pages':'표본 없음'}</strong></div>
        <div><span>내부 SQL 평균 CPU 최대</span>
          <strong>{data.sampledInternalPlans>0?fmt(data.peakInternalAvgCpuMs,2)+'ms':'표본 없음'}</strong></div>
        <div><span>보호로 생략한 수집</span><strong>{fmt(data.skippedCycles)}회</strong></div>
      </div>
      <div className="rev-observer-guard-reason" aria-live="polite">
        <strong>현재 판단</strong>
        <span>{LABELS[data.reason]||data.reason}</span>
        <small>최근 관측 {data.lastObservedAt
          ? new Date(data.lastObservedAt).toLocaleString('ko-KR',{hour12:false})
          : '아직 없음'}</small>
      </div>
      <p className="rev-observer-guard-disclaimer">{data.limitation}</p>
    </>}
  </section>;
}

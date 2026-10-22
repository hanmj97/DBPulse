import React, {useEffect, useState} from 'react';
import {Cpu, ShieldAlert} from 'lucide-react';

/** The HTTP endpoint returns cached JVM state; it does not query MSSQL. */
export default function InstanceCpuPanel({targetId}) {
  const [data,setData]=useState(null);
  const [failed,setFailed]=useState(false);
  useEffect(()=>{
    if(!targetId)return undefined;
    let alive=true;
    async function load(){
      try {
        const r=await fetch(`/api/v1/targets/${encodeURIComponent(targetId)}/instance-cpu`,
          {cache:'no-store'});
        if(!r.ok)throw new Error('CPU read failed');
        const next=await r.json();
        if(alive){setData(next);setFailed(false);}
      }catch{
        if(alive){setData(null);setFailed(true);}
      }
    }
    load();
    const timer=setInterval(load,15000);
    return ()=>{alive=false;clearInterval(timer);};
  },[targetId]);

  const online=data?.status==='ONLINE';
  const reason={
    DISABLED:'선택적 CPU 수집이 꺼져 있습니다. 로컬 테스트에서 DBPULSE_SQLSERVER_CPU_ENABLED=true로 활성화하세요.',
    INITIALIZING:'CPU 표본 수집을 기다리고 있습니다.',
    NOT_CONFIGURED:'SQL Server 모니터링 대상이 설정되지 않았습니다.',
    PERMISSION_DENIED:'현재 모니터링 계정으로 서버 수준 CPU 진단 데이터에 접근할 수 없습니다. 임의로 관리자 권한을 부여하지 마세요.',
    UNAVAILABLE:'CPU 표본이 없거나 오래되었거나 SQL Server 진단 기능이 지원되지 않습니다.'
  };
  const sqlPercent=online?data.sqlProcessCpuPercent:null;
  const hostPercent=online?data.hostBusyCpuPercent:null;
  return <section className="rev-cpu-panel" aria-label="SQL Server 인스턴스 CPU 상태">
    <div className="rev-cpu-panel-heading">
      <div>
        <h3><Cpu size={17}/> SQL Server CPU 사용률 <small>인스턴스 전체</small></h3>
        <p>선택한 DB만의 CPU가 아닌 SQL Server 프로세스와 호스트의 사용률입니다.</p>
      </div>
      <span className="rev-cpu-panel-status">{online?'관측 중':failed?'API 연결 오류':data?.status||'확인 중'}</span>
    </div>
    <div className="rev-cpu-panel-grid">
      <div><span>SQL Server 프로세스 CPU</span>
        <strong>{sqlPercent == null?'—':sqlPercent+'%'}</strong>
        <small>모든 DB의 작업을 포함</small></div>
      <div><span>호스트 전체 CPU</span>
        <strong>{hostPercent == null?'—':hostPercent+'%'}</strong>
        <small>다른 프로세스 사용량 포함</small></div>
      <div><span>최근 CPU 표본</span>
        <strong className="rev-cpu-panel-date">{data?.collectedAt
          ? new Date(data.collectedAt).toLocaleTimeString('ko-KR',{hour12:false})
          :'—'}</strong>
        <small>기본 수집 주기 60초 · 실험적 진단</small></div>
    </div>
    {(failed||!online)&&<p className="rev-cpu-panel-note">
      <ShieldAlert size={15}/>{failed?'CPU 조회 API를 사용할 수 없습니다. 백엔드 상태를 확인하세요.':
        (reason[data?.status]||'CPU 표본을 기다리고 있습니다.')}
    </p>}
    <p className="rev-cpu-panel-limitation">{data?.limitation||
      '인스턴스 CPU 사용률(%)과 쿼리별 평균 CPU 시간(ms)은 다른 지표입니다. 프로세스별 CPU 표본은 선택적 기능입니다.'}</p>
  </section>;
}

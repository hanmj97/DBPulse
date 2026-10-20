import React, { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, SearchCheck } from 'lucide-react';

const label = {
  TABLE_SCAN:'Table Scan',
  CLUSTERED_INDEX_SCAN:'Clustered Index Scan',
  INDEX_SCAN:'Index Scan',
  KEY_LOOKUP:'Key Lookup',
  RID_LOOKUP:'RID Lookup',
  SORT:'Sort',
  HASH_MATCH:'Hash Match',
  NESTED_LOOPS:'Nested Loops',
  MERGE_JOIN:'Merge Join',
  INDEX_SEEK:'Index Seek',
  CLUSTERED_INDEX_SEEK:'Clustered Index Seek',
  FILTER:'Filter',
  TABLE_INSERT:'Table Insert',
  CLUSTERED_INDEX_INSERT:'Clustered Index Insert',
  INDEX_INSERT:'Index Insert',
  TABLE_UPDATE:'Table Update',
  CLUSTERED_INDEX_UPDATE:'Clustered Index Update',
  INDEX_UPDATE:'Index Update',
  TABLE_DELETE:'Table Delete',
  CLUSTERED_INDEX_DELETE:'Clustered Index Delete',
  INDEX_DELETE:'Index Delete',
  OTHER_OPERATOR:'기타 연산'
};
const explanation = {
  TABLE_SCAN:'테이블 스캔 연산이 계획에 있습니다. 데이터 크기·선택도·실제 행 수를 확인해야 성능 문제인지 판단할 수 있습니다.',
  CLUSTERED_INDEX_SCAN:'클러스터드 인덱스 스캔이 있습니다. 범위나 반환 건수에 따라 정상적인 선택일 수도 있습니다.',
  INDEX_SCAN:'인덱스 스캔이 있습니다. SQL의 필터 조건과 반환 건수를 함께 살펴보세요.',
  KEY_LOOKUP:'Key Lookup 연산이 확인됐습니다. 반복 수행 횟수·커버링 가능성을 실제 계획에서 추가 확인하세요.',
  RID_LOOKUP:'RID Lookup 연산이 확인됐습니다. 호출 횟수와 기본 테이블의 구조를 확인하세요.',
  SORT:'Sort 연산이 확인됐습니다. 실제 메모리 사용과 정렬 행 수를 검토할 수 있습니다.',
  HASH_MATCH:'Hash Match 연산이 확인됐습니다. 실제 행 수와 메모리 사용을 확인하세요.',
  NESTED_LOOPS:'Nested Loops 연산이 있습니다. 입력 건수와 반복 횟수를 확인하세요.',
  MERGE_JOIN:'Merge Join 연산이 있습니다. 입력 정렬과 행 수를 확인하세요.',
  INDEX_SEEK:'Index Seek 연산이 있습니다. Seek 존재만으로 충분한 성능을 보장하지 않습니다.',
  CLUSTERED_INDEX_SEEK:'Clustered Index Seek 연산이 있습니다. 실제 읽기량과 반환 행 수를 확인하세요.',
  FILTER:'Filter 연산이 확인됐습니다. 필터가 어느 단계에서 적용되는지 확인하세요.',
  TABLE_INSERT:'테이블 Insert 연산이 존재합니다. 해당 연산만으로 쓰기 병목을 확정할 수 없습니다.',
  CLUSTERED_INDEX_INSERT:'Clustered Index Insert 연산이 존재합니다. 실제 처리량 및 잠금·로그 지표와 함께 확인하세요.',
  INDEX_INSERT:'Index Insert 연산이 존재합니다. 인덱스 유지 비용은 별도 근거를 통해 검토해야 합니다.',
  TABLE_UPDATE:'Table Update 연산이 존재합니다. 실제 행 수와 트랜잭션 영향을 추가 확인하세요.',
  CLUSTERED_INDEX_UPDATE:'Clustered Index Update 연산이 확인됐습니다. UPDATE 작업의 구조이며 이 연산만으로 튜닝 필요 여부를 판단하지 않습니다.',
  INDEX_UPDATE:'Index Update 연산이 존재합니다. 실제 변경 행 수와 다른 인덱스 유지 작업을 추가 확인하세요.',
  TABLE_DELETE:'Table Delete 연산이 존재합니다. 삭제 처리량 및 트랜잭션 상황과 함께 확인하세요.',
  CLUSTERED_INDEX_DELETE:'Clustered Index Delete 연산이 존재합니다. 실제 비용은 추가 측정이 필요합니다.',
  INDEX_DELETE:'Index Delete 연산이 존재합니다. 해당 연산만으로 비용이나 문제를 확정하지 않습니다.',
  OTHER_OPERATOR:'기타 연산이 집계됐습니다. 해당 연산의 원문 이름은 전송하지 않습니다.'
};
const statusMessage={
  PLAN_NOT_FOUND:'선택한 실행계획이 Query Store에서 더 이상 조회되지 않습니다.',
  PLAN_XML_UNAVAILABLE:'이 실행계획의 XML이 제공되지 않았습니다.',
  PLAN_TOO_LARGE:'실행계획 크기가 안전한 조회 제한(1MB)을 초과했습니다.',
  NO_OPERATORS_OBSERVED:'계획에 분석 가능한 RelOp 연산이 없거나 XML을 해석할 수 없습니다.',
  OPERATOR_LIMIT_REACHED:'최대 100개 연산까지만 집계했습니다. 아래 개수는 전체 계획의 합계가 아닙니다.'
};

export default function PlanOperatorEvidence({targetId,queryId,planId}) {
  const [report,setReport]=useState(null);
  const [loading,setLoading]=useState(false);
  const [error,setError]=useState('');
  useEffect(()=>{setReport(null);setError('');},[targetId,queryId,planId]);
  const inspect=async()=>{
    if(loading||!targetId)return;
    setLoading(true);setError('');
    try{
      const url=`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/plan-evidence/${queryId}/${planId}`;
      const response=await fetch(url);
      if(!response.ok)throw new Error(String(response.status));
      setReport(await response.json());
    }catch(err){
      setError(err.message==='404'
        ? '해당 Query/Plan이 최근 수집된 최신·고비용 및 수집 패턴 최대 50개 목록에 없거나 더 이상 존재하지 않습니다.'
        : '실행계획 연산을 조회하지 못했습니다. 수집기 상태·Query Store 권한·백엔드 로그를 확인해 주세요.');
    }finally{setLoading(false);}
  };

  return <div className="rev-plan-evidence">
    <div className="rev-plan-evidence-heading">
      <div><h4>실행계획 연산 근거</h4>
        <p>필요한 계획만 조회합니다. SQL 원문·실행계획 XML은 DBPulse에 전송하거나 저장하지 않습니다.</p></div>
      <button type="button" disabled={loading} onClick={inspect}>
        <SearchCheck size={15}/>{loading?'분석 중…':report?'다시 확인':'연산 정보 확인'}
      </button>
    </div>
    {error&&<div className="rev-plan-warning"><AlertTriangle size={14}/>{error}</div>}
    {report&&<>
      {report.status==='AVAILABLE'
        ?<div className="rev-plan-status"><CheckCircle2 size={16}/>실행계획 구조 분석 완료</div>
        :<div className="rev-plan-warning"><AlertTriangle size={15}/>
          {statusMessage[report.status]||report.status}</div>}
      {report.operators?.length>0&&<>
        <div className="rev-plan-metadata">
          <span>확인한 연산 <strong>{report.sampledOperators}개</strong></span>
          <span>병렬 계획 <strong>{report.parallelPlan?'예':'아니요'}</strong></span>
          <span>강제 계획 <strong>{report.forcedPlan?'예':'아니요'}</strong></span>
        </div>
        <div className="rev-plan-operator-list">
          {report.operators.map(operator=><div key={operator.type} className="rev-plan-operator">
            <span className="rev-plan-operator-name"><strong>{label[operator.type]||'기타 연산'}</strong>
              <small>{explanation[operator.type]||explanation.OTHER_OPERATOR}</small></span>
            <b>{operator.count}개</b>
          </div>)}
        </div>
      </>}
      {report.correlation && <section className="rev-plan-correlation" aria-label="성능 지표와 실행계획 연계 분석">
        <div className="rev-plan-correlation-title">
          <div>
            <h4>성능 지표 × 실행계획 연계 분석</h4>
            <p>같은 Query ID·Plan ID의 Query Store 집계와 확인된 연산을 연결합니다.</p>
          </div>
          <span className={'rev-plan-correlation-state '+(report.correlation.status==='PARTIAL_EVIDENCE'?'partial':'')}>
            {report.correlation.status==='EVIDENCE_AVAILABLE'?'근거 연결 완료':
              report.correlation.status==='PARTIAL_EVIDENCE'?'일부 연산만 확인':'연결 근거 부족'}
          </span>
        </div>
        <div className="rev-plan-findings">
          {(report.correlation.findings||[]).map(item =>
            <article key={item.code} className="rev-plan-finding">
              <div className="rev-plan-finding-heading">
                <span className={'rev-plan-finding-kind '+(item.kind==='REVIEW'?'review':'info')}>
                  {item.kind==='REVIEW'?'추가 점검':'관측 안내'}
                </span>
                <strong>{item.title}</strong>
              </div>
              <p className="rev-plan-finding-evidence">{item.evidence}</p>
              <div className="rev-plan-finding-next"><b>다음 확인</b><p>{item.nextCheck}</p></div>
            </article>
          )}
        </div>
        <p className="rev-plan-disclaimer">{report.correlation.limitation}</p>
      </section>}
      <p className="rev-plan-disclaimer">{report.interpretation}</p>
    </>}
  </div>;
}

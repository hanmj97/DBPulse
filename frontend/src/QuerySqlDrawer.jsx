import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Clipboard, FileCode2, LockKeyhole, X } from 'lucide-react';

/** Read-only right-hand drawer. Fetch only after explicit user selection.
 * Never log SQL text; close/unmount clears the in-memory response.
 */
export default function QuerySqlDrawer({targetId,selection,onClose}) {
  const [report,setReport] = useState(null);
  const [loading,setLoading] = useState(false);
  const [error,setError] = useState('');
  const [copied,setCopied] = useState(false);
  const closeRef = useRef(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(()=>{
    if(!selection || !targetId) {setReport(null);setError('');return undefined;}
    const abort = new AbortController();
    setReport(null);setError('');setCopied(false);setLoading(true);
    const read = async () => {
      try {
        const url=`/api/v1/targets/${encodeURIComponent(targetId)}/query-performance/query-text/${selection.queryId}/${selection.planId}`;
        const response=await fetch(url,{
          cache:'no-store',credentials:'same-origin',signal:abort.signal
        });
        if(!response.ok) throw new Error(String(response.status));
        const data=await response.json();
        if(!abort.signal.aborted) setReport(data);
      } catch(e) {
        if(abort.signal.aborted) return;
        const message = e.message==='403'
          ? '보안 보호를 위해 SQL 원문 조회가 비활성화되어 있습니다. 앱을 종료한 뒤 PowerShell에서 DBPULSE_QUERY_TEXT_ENABLED 값을 true로 설정하고 설치된 DBPulse.WindowHost.exe를 같은 창에서 실행해 주세요. 조회를 마치면 앱을 닫고 변수를 해제해 주세요.'
          : e.message==='404'
            ? '선택한 쿼리가 현재 Query Store 수집 대상에 없습니다. 다음 수집 주기 후 다시 확인해 주세요.'
            : e.message==='503'
              ? '현재 Query Store를 조회할 수 없습니다. 수집 상태나 MSSQL 계정의 Query Store 읽기 권한을 확인해 주세요.'
              : 'SQL 원문 조회에 실패했습니다. 백엔드 로그와 연결 상태를 확인해 주세요.';
        setError(message);
      } finally {
        if(!abort.signal.aborted) setLoading(false);
      }
    };
    read();
    return ()=>abort.abort();
  },[targetId,selection?.queryId,selection?.planId]);

  useEffect(()=>{
    if(!selection)return undefined;
    const before=document.activeElement;
    closeRef.current?.focus();
    const esc=(event)=>{if(event.key==='Escape')onCloseRef.current();};
    document.addEventListener('keydown',esc);
    return ()=>{
      document.removeEventListener('keydown',esc);
      if(before && typeof before.focus==='function' && before.isConnected) before.focus();
    };
  },[Boolean(selection)]);

  if(!selection)return null;

  const copySql=async()=>{
    if(!report?.queryText || !navigator.clipboard?.writeText)return;
    try{
      await navigator.clipboard.writeText(report.queryText);
      setCopied(true);
    }catch{
      setCopied(false);
    }
  };
  // The theme variables normally live on .rev-layout, while this drawer portals to body.
  const activeTheme = document.querySelector('.rev-layout')?.getAttribute('data-theme') === 'dark' ? 'dark' : 'navy';
  return createPortal(<div className="rev-sql-drawer-root" data-theme={activeTheme}>
    <button className="rev-sql-drawer-backdrop" type="button"
      aria-label="SQL 원문 창 닫기" onClick={onClose}/>
    <aside className="rev-sql-drawer" role="dialog" aria-modal="true"
      aria-labelledby="rev-sql-drawer-heading">
      <div className="rev-sql-drawer-head">
        <div>
          <span className="rev-sql-drawer-eyebrow"><FileCode2 size={15}/> QUERY STORE / SQL TEXT</span>
          <h2 id="rev-sql-drawer-heading">실행된 SQL 조회</h2>
          <p>Query #{selection.queryId} · Plan #{selection.planId}</p>
        </div>
        <button type="button" ref={closeRef} className="rev-sql-drawer-close"
          aria-label="닫기" onClick={onClose}><X size={20}/></button>
      </div>
      <div className="rev-sql-drawer-alert">
        <LockKeyhole size={18}/>
        <p>이 SQL에는 조건값·개인정보·접속 토큰 등이 포함될 수 있습니다.
          원문은 클릭한 시점에만 조회하며 DBPulse 이력 DB에 저장하지 않습니다.
          공개 화면 공유나 복사에 주의해 주세요.</p>
      </div>
      <div className="rev-sql-drawer-content">
        {loading && <p className="rev-sql-drawer-message" role="status">SQL 원문을 조회하고 있습니다…</p>}
        {error && <p className="rev-sql-drawer-message error" role="alert">{error}</p>}
        {!loading && !error && report?.status==='QUERY_TEXT_UNAVAILABLE' &&
          <p className="rev-sql-drawer-message">이 계획에 연결된 Query Store SQL 원문을 확인할 수 없습니다.</p>}
        {!loading && !error && report?.status==='AVAILABLE' && <>
          <div className="rev-sql-drawer-summary">
            <span>Query Store에 캡처된 쿼리 텍스트</span>
            <button type="button" onClick={copySql}
              disabled={!report.queryText || !navigator.clipboard?.writeText}>
              <Clipboard size={15}/>{copied?'복사됨':'SQL 복사'}
            </button>
          </div>
          {report.truncated && <p className="rev-sql-drawer-truncated">
            <AlertTriangle size={16}/>
            원본이 길어 앞부분 최대 65,536자만 표시합니다. 전체 SQL 전문이 아닙니다.
          </p>}
          <pre className="rev-sql-drawer-code"><code>{report.queryText}</code></pre>
        </>}
      </div>
      <p className="rev-sql-drawer-footer">
        표시되는 문장은 SQL Server Query Store에 저장된 텍스트입니다.
        자동 매개변수화나 정규화로 인해 실행 당시 전달된 실제 파라미터 값·전체 배치 문장과 다를 수 있습니다.
        SQL 실행·수정 기능은 제공하지 않습니다.
      </p>
    </aside>
  </div>,document.body);
}

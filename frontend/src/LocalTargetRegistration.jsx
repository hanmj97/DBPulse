import React,{useEffect,useState} from 'react';
import {Database,ShieldCheck,PlugZap,AlertCircle,RefreshCcw} from 'lucide-react';
import './local-target-registration.css';

const INITIAL = {
  name:'',host:'host.docker.internal',port:'1433',
  databaseName:'',username:'',password:''
};
const CODES={
  READ_ONLY_CONNECTION_CONFIRMED:'데이터베이스 연결을 확인했습니다. 읽기 전용 연결로 확인했습니다.',
  DATABASE_MISMATCH:'연결된 데이터베이스가 입력한 이름과 다릅니다.',
  AUTHENTICATION_FAILED:'SQL Server 로그인이나 비밀번호를 확인해 주세요.',
  CONNECTION_FAILED:'SQL Server에 연결하지 못했습니다. 호스트·포트·방화벽을 확인해 주세요.',
  CONNECTION_OR_TLS_FAILED:'연결 또는 TLS 인증서 검증에 실패했습니다.',
  TARGET_ALREADY_REGISTERED:'같은 서버·DB·로그인 대상이 이미 등록되어 있습니다.',
  CREDENTIAL_NOT_STORED_COLLECTOR_NOT_STARTED:'대상 정보가 등록되었습니다. 비밀번호는 저장하지 않았으며 모니터링은 아직 시작되지 않습니다.'
};

export default function LocalTargetRegistration(){
  const [form,setForm]=useState(INITIAL);
  const [items,setItems]=useState([]);
  const [enabled,setEnabled]=useState(null);
  const [busy,setBusy]=useState(false);
  const [feedback,setFeedback]=useState(null);
  const [verified,setVerified]=useState(false);

  const load=async()=>{
    try{
      const response=await fetch('/api/v1/local-targets',{cache:'no-store'});
      if(response.status===404){setEnabled(false);return;}
      if(!response.ok)throw Error('registry unavailable');
      const data=await response.json();
      setItems(Array.isArray(data)?data:[]);
      setEnabled(true);
    }catch{setEnabled(null);}
  };
  useEffect(()=>{load();},[]);

  const update=(field,value)=>{
    setForm(previous=>({...previous,[field]:value}));
    setVerified(false);
    setFeedback(null);
  };
  const submit=async(shouldRegister)=>{
    if(busy)return;
    setBusy(true);setFeedback(null);
    try{
      const response=await fetch(
        shouldRegister?'/api/v1/local-targets':'/api/v1/local-targets/test-connection',{
          method:'POST',
          headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
          body:JSON.stringify({...form,port:Number(form.port)})
        });
      if(response.status===429)throw Error('잠시 후 다시 시도해 주세요. 연결 시도 횟수가 제한되어 있습니다.');
      if(response.status===404)throw Error('로컬 등록 기능을 먼저 활성화해야 합니다.');
      if(response.status===400)throw Error('입력값 또는 서버 측 허용 호스트 목록을 확인해 주세요.');
      if(!response.ok)throw Error('로컬 API가 요청을 처리하지 못했습니다.');
      const result=await response.json();
      const ok=result.status==='CONNECTED'||result.status==='REGISTERED_INACTIVE';
      setFeedback({ok,text:CODES[result.code]||'연결 또는 등록을 확인하지 못했습니다.'});
      setVerified(!shouldRegister && ok);
      if(shouldRegister&&ok){
        setForm(previous=>({...previous,password:''}));
        await load();
      }
    }catch(error){
      setFeedback({ok:false,text:error.message||'연결 테스트에 실패했습니다.'});
      setVerified(false);
    }finally{setBusy(false);}
  };

  return <section className="rev-panel dbp-local-targets">
    <div className="rev-panel-heading">
      <div><h2>로컬 SQL Server 연결 등록</h2>
        <p>Windows PC에서 실행하는 DBPulse · 회원가입 없이 로컬에서 사용</p></div>
      <span className="rev-panel-tag">v0.14-alpha · MSSQL</span>
    </div>
    <div className="dbp-local-caution">
      <ShieldCheck size={19}/>
      <span>현재 단계는 <strong>읽기 전용 연결 테스트와 대상 정보 등록</strong>까지 지원합니다.
        비밀번호를 저장하지 않으므로 등록한 대상의 지속 수집은 아직 시작하지 않습니다.</span>
    </div>
    {enabled===false && <div className="dbp-local-note">
      기능이 기본 비활성 상태입니다. 로컬 `.env`의
      <code> DBPULSE_LOCAL_TARGETS_ENABLED=true</code> 설정 후 백엔드를 다시 실행해 주세요.
    </div>}
    {enabled===null && <div className="dbp-local-note">
      로컬 대상 등록 API 상태를 확인하지 못했습니다. 백엔드 연결을 확인해 주세요.
    </div>}
    <form className="dbp-local-form" onSubmit={e=>{e.preventDefault();submit(false);}}>
      <label>연결 이름
        <input required maxLength={100} autoComplete="off" value={form.name}
          onChange={e=>update('name',e.target.value)} placeholder="예: 운영 MSSQL"/>
      </label>
      <label>SQL Server 호스트
        <input required maxLength={253} autoComplete="off" value={form.host}
          onChange={e=>update('host',e.target.value)} placeholder="host.docker.internal"/>
      </label>
      <label>포트
        <input required type="number" min="1" max="65535" value={form.port}
          onChange={e=>update('port',e.target.value)}/>
      </label>
      <label>데이터베이스 이름
        <input required maxLength={128} autoComplete="off" value={form.databaseName}
          onChange={e=>update('databaseName',e.target.value)} placeholder="예: DBPulseLab"/>
      </label>
      <label>SQL Server 로그인
        <input required maxLength={128} autoComplete="off" value={form.username}
          onChange={e=>update('username',e.target.value)} placeholder="읽기 전용 계정"/>
      </label>
      <label>비밀번호 (저장되지 않음)
        <input required type="password" maxLength={512} autoComplete="new-password"
          value={form.password} onChange={e=>update('password',e.target.value)}/>
      </label>
      <div className="dbp-local-actions">
        <button type="submit" disabled={busy||enabled!==true}>
          <PlugZap size={16}/>{busy?'검사 중…':'연결 테스트'}
        </button>
        <button className="secondary" type="button" disabled={busy||!verified}
          onClick={()=>submit(true)}>
          <Database size={16}/>검증한 대상 정보 등록
        </button>
      </div>
    </form>
    {feedback && <div className={'dbp-local-feedback '+(feedback.ok?'ok':'fail')}
      role="status">
      {feedback.ok?<ShieldCheck size={17}/>:<AlertCircle size={17}/>}
      {feedback.text}
    </div>}
    <div className="dbp-local-registered-header">
      <h3>로컬 등록 대상</h3>
      <button type="button" onClick={load}><RefreshCcw size={14}/>새로고침</button>
    </div>
    {items.length ? <div className="dbp-local-list">
      {items.map(item=><div className="dbp-local-item" key={item.id}>
        <Database size={18}/>
        <div><strong>{item.databaseName}</strong>
          <small>{item.name} · {item.host}:{item.port}</small></div>
        <span>등록됨 · 수집 미시작</span>
      </div>)}
    </div>:<div className="dbp-local-empty">
      등록한 대상이 없습니다. 연결을 검사한 다음 대상 정보를 등록해 보세요.
    </div>}
    <p className="dbp-local-foot">실시간 모니터링 중인 기존 DB는 위쪽의 연결 대상 정보에 그대로 표시됩니다. 안전한 비밀번호 보관 및 여러 대상 전환은 다음 단계에서 구현합니다.</p>
  </section>;
}

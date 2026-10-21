import React,{useEffect,useState} from 'react';
import {Database,ShieldCheck,PlugZap,AlertCircle,RefreshCcw,SlidersHorizontal,ChevronDown,Unplug} from 'lucide-react';
import './local-target-registration.css';

const INITIAL = {
  name:'',host:'host.docker.internal',port:'1433',
  databaseName:'',username:'',password:'',trustLocalCertificate:false
};
const CODES={
  READ_ONLY_QUERY_SUCCEEDED:'SQL Server 연결 성공. 읽기 전용 SELECT만 실행했습니다. 계정의 쓰기 권한까지 검증한 것은 아닙니다.',
  DATABASE_MISMATCH:'연결된 데이터베이스가 입력한 이름과 다릅니다.',
  AUTHENTICATION_FAILED:'SQL Server 로그인이나 비밀번호를 확인해 주세요.',
  CONNECTION_FAILED:'SQL Server TCP 연결이 거부되었거나 호스트에 접근할 수 없습니다. 호스트·포트·방화벽을 확인해 주세요.',
  CONNECTION_TIMEOUT:'SQL Server 연결 시간이 초과됐습니다. 네트워크 상태와 서버 응답을 확인해 주세요.',
  TLS_CERTIFICATE_UNTRUSTED:'SQL Server TLS 인증서를 신뢰할 수 없습니다. 서버 인증서를 확인하세요. 자신이 관리하는 로컬 개발 DB인 경우에만 고급 설정의 예외를 검토해 주세요.',
  TLS_HANDSHAKE_FAILED:'SQL Server TLS 협상에 실패했습니다. 인증서 또는 서버 TLS 설정을 확인해 주세요. 개발용 로컬 DB가 아니라면 인증서 검증 예외를 사용하지 마세요.',
  DATABASE_ACCESS_FAILED:'DB 로그인은 확인됐지만 지정한 데이터베이스에 접근할 수 없습니다. DB 이름과 사용자 매핑을 확인해 주세요.',
  CONNECTION_OR_TLS_FAILED:'SQL Server 연결에 실패했습니다. TLS 설정, SQL Server 로그, 서버 접근 권한을 확인해 주세요.',
  TARGET_ALREADY_REGISTERED:'같은 서버·DB·로그인 대상이 이미 등록되어 있습니다.',
  CREDENTIAL_NOT_STORED_COLLECTOR_NOT_STARTED:'대상 정보만 등록되었습니다. 비밀번호는 저장되지 않았고 아직 모니터링하지 않습니다.',
  MONITORING_STARTED_SESSION_ONLY:'선택한 DB 모니터링 시작 요청을 완료했습니다. 실제 수집 ONLINE 여부를 확인해 주세요.',
  MONITORING_STOPPED:'현재 DB 수집을 중지하고 연결을 해제했습니다. 등록된 DB를 선택하고 비밀번호를 다시 입력하면 다른 DB에 연결할 수 있습니다.'
};

export default function LocalTargetRegistration(){
  const [form,setForm]=useState(INITIAL);
  const [items,setItems]=useState([]);
  const [enabled,setEnabled]=useState(null);
  const [busy,setBusy]=useState(false);
  const [feedback,setFeedback]=useState(null);
  const [verified,setVerified]=useState(false);
  const [selectedId,setSelectedId]=useState(null);
  const [collector,setCollector]=useState(null);
  const activeItem=items.find(item=>item.status==='ACTIVE_IN_MEMORY');
  const activeCollector=activeItem && collector?.id===activeItem.id ? collector : null;

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
  useEffect(()=>{
    if(!activeItem){setCollector(null);return undefined;}
    let mounted=true;
    const refresh=async()=>{
      try{
        const response=await fetch('/api/v1/targets',{cache:'no-store'});
        if(!response.ok)throw Error('status unavailable');
        const targets=await response.json();
        const current=Array.isArray(targets)
          ?targets.find(target=>target.id===activeItem.id):null;
        if(mounted)setCollector(current||{id:activeItem.id,collectorStatus:'UNKNOWN'});
      }catch{
        if(mounted)setCollector({id:activeItem.id,collectorStatus:'UNKNOWN'});
      }
    };
    refresh();
    const timer=setInterval(refresh,5000);
    return ()=>{mounted=false;clearInterval(timer);};
  },[activeItem?.id]);

  const chooseRegistered=(item)=>{
    setSelectedId(item.id);
    setForm({
      name:item.name,host:item.host,port:String(item.port),
      databaseName:item.databaseName,username:item.username || '',
      password:'',trustLocalCertificate:false
    });
    setVerified(false);
    setFeedback({ok:true,text:'등록된 DB 정보를 불러왔습니다. 비밀번호를 입력한 후 모니터링 시작을 누르면 연결 테스트를 자동으로 수행합니다.'});
  };

  const update=(field,value)=>{
    if(['name','host','port','databaseName','username'].includes(field))setSelectedId(null);
    setForm(previous=>({...previous,[field]:value}));
    setVerified(false);
    setFeedback(null);
  };
  const stopCurrent=async()=>{
    if(busy||!activeItem)return;
    if(!window.confirm('현재 DB 수집을 중지하고 연결을 해제할까요? 기존 수집 이력은 삭제되지 않습니다.'))return;
    setBusy(true);setFeedback(null);
    try{
      const response=await fetch('/api/v1/local-targets/stop-monitoring',{
        method:'POST',
        headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
        body:JSON.stringify({id:activeItem.id})
      });
      if(response.status===409)throw Error('현재 대상이 변경됐거나 기존 .env 자동 수집 중입니다. 화면을 새로고침해 주세요.');
      if(!response.ok)throw Error('모니터링 중지 요청에 실패했습니다.');
      const result=await response.json();
      if(result.status!=='DISCONNECTED')throw Error('중지 상태를 확인하지 못했습니다.');
      setVerified(false);
      setCollector(null);
      setForm(previous=>({...previous,password:''}));
      await load();
      setFeedback({ok:true,text:CODES[result.code]});
    }catch(error){
      setFeedback({ok:false,text:error.message||'연결 해제에 실패했습니다.'});
      await load();
    }finally{setBusy(false);}
  };

  const submit=async(action)=>{
    if(busy)return;
    setBusy(true);setFeedback(null);
    try{
      const response=await fetch(
        action==='start'?'/api/v1/local-targets/start-monitoring'
          : action==='register'?'/api/v1/local-targets'
          : '/api/v1/local-targets/test-connection',{
          method:'POST',
          headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
          body:JSON.stringify({...form,port:Number(form.port)})
        });
      if(response.status===429)throw Error('잠시 후 다시 시도해 주세요. 연결 시도 횟수가 제한되어 있습니다.');
      if(response.status===409)throw Error('이미 다른 DB가 수집 중입니다. 아래의 연결 해제 버튼으로 수집을 중지한 뒤 다시 시도해 주세요. 기존 .env 자동 연결은 비활성화해야 합니다.');
      if(response.status===404)throw Error('로컬 등록 기능을 먼저 활성화해야 합니다.');
      if(response.status===400)throw Error('입력값 또는 서버 측 허용 호스트 목록을 확인해 주세요.');
      if(!response.ok)throw Error('로컬 API가 요청을 처리하지 못했습니다.');
      const result=await response.json();
      const ok=['CONNECTED','REGISTERED_INACTIVE','ACTIVE_IN_MEMORY'].includes(result.status);
      setFeedback({ok,text:CODES[result.code]||'연결 또는 등록을 확인하지 못했습니다.'});
      setVerified(action==='test' && ok);
      if(action!=='test'&&ok){
        setForm(previous=>({...previous,password:''}));
        await load();
      }
    }catch(error){
      setFeedback({ok:false,text:error.message||'연결 테스트에 실패했습니다.'});
      // A rapid test -> start request may hit the local rate limit.
      // Keep prior successful verification so clicking Start again works.
      if(action==='test')setVerified(false);
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
      <span><strong>직접 입력한 MSSQL DB를 모니터링할 수 있습니다.</strong>
        기존 .env 자동 연결을 비활성화한 다음 연결 테스트 → 모니터링 시작을 진행해 주세요.
        비밀번호는 디스크에 저장하지 않으며, 현재 백엔드 실행 중에만 연결을 유지합니다.
        다른 DB로 전환할 때는 ‘현재 DB 연결 해제’ 버튼을 사용하세요. 백엔드나 Docker를 재시작할 필요는 없습니다.</span>
    </div>
    {enabled===false && <div className="dbp-local-note">
      기능이 기본 비활성 상태입니다. 로컬 `.env`의
      <code> DBPULSE_LOCAL_TARGETS_ENABLED=true</code> 설정 후 백엔드를 다시 실행해 주세요.
    </div>}
    {enabled===null && <div className="dbp-local-note">
      로컬 대상 등록 API 상태를 확인하지 못했습니다. 백엔드 연결을 확인해 주세요.
    </div>}
    <form className="dbp-local-form" onSubmit={e=>{e.preventDefault();submit('test');}}>
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
      <details className="dbp-local-advanced">
        <summary><SlidersHorizontal size={16}/><span>고급 연결 설정</span>
          <small>기본값은 TLS 서버 인증서 검증</small><ChevronDown size={16} className="dbp-local-advanced-chevron"/></summary>
        <div className="dbp-local-advanced-content">
          <label className="dbp-local-tls-option">
            <input type="checkbox" checked={form.trustLocalCertificate}
              onChange={e=>update('trustLocalCertificate',e.target.checked)}/>
            <span>로컬 개발용 자체 서명 인증서 허용
              <small>암호화 연결은 유지하지만 서버 인증서의 신원을 확인하지 않습니다.
                자신이 관리하는 로컬 SQL Server Developer 환경에 한해서만 사용하세요.
                백엔드의 DBPULSE_LOCAL_TRUST_SELF_SIGNED=true 설정이 추가로 필요합니다.
                원격 DB에서는 사용할 수 없습니다.</small>
            </span>
          </label>
          <p>운영용 SQL Server는 신뢰할 수 있는 인증서를 설치하고 위 옵션을 해제한 상태에서 연결하세요.</p>
        </div>
      </details>
      <div className="dbp-local-actions">
        <button type="submit" disabled={busy||enabled!==true}>
          <PlugZap size={16}/>{busy?'검사 중…':'연결 테스트'}
        </button>
        <button className="secondary" type="button" disabled={busy||!verified}
          onClick={()=>submit('register')}>
          <Database size={16}/>대상 정보만 등록
        </button>
        <button className="dbp-local-start" type="button"
          disabled={busy||enabled!==true||!form.password||Boolean(activeItem)}
          onClick={()=>submit('start')}>
          <PlugZap size={16}/>선택한 DB 모니터링 시작
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
    {activeItem && <div className="dbp-local-feedback ok" role="status">
      <ShieldCheck size={17}/>선택 대상 <strong>{activeItem.databaseName}</strong>
      <span className="dbp-local-collector-status">
        {activeCollector?.collectorStatus==='ONLINE'?'실제 수집 정상 (ONLINE)'
          :activeCollector?.collectorStatus==='OFFLINE'?'실제 수집 실패 (OFFLINE) · 수집 권한/DB 상태 확인'
          :activeCollector?.collectorStatus==='INITIALIZING'?'수집 초기화 중'
          :'수집 상태 확인 중'}
      </span>
      <button type="button" className="dbp-local-disconnect"
        onClick={stopCurrent} disabled={busy}>
        <Unplug size={15}/>{busy?'처리 중…':'현재 DB 연결 해제 · 다른 DB 선택'}
      </button>
      <a href="#overview">개요에서 확인 →</a>
    </div>}
    {items.length ? <div className="dbp-local-list">
      {items.map(item=><button type="button" className={
          'dbp-local-item dbp-local-selectable'+(selectedId===item.id?' selected':'')
        } key={item.id} aria-pressed={selectedId===item.id}
        onClick={()=>chooseRegistered(item)} disabled={busy}>
        <Database size={18}/>
        <div><strong>{item.databaseName}</strong>
          <small>{item.name} · {item.host}:{item.port}</small></div>
        <span className={item.status==='ACTIVE_IN_MEMORY'?'active':''}>
          {item.status==='ACTIVE_IN_MEMORY'?'현재 모니터링 대상':'선택하여 연결'}
        </span>
      </button>)}
    </div>:<div className="dbp-local-empty">
      등록한 대상이 없습니다. 연결을 검사한 다음 대상 정보를 등록해 보세요.
    </div>}
    <p className="dbp-local-foot">등록 목록의 DB를 클릭하면 연결 정보가 자동 입력됩니다. 비밀번호는 보안상 저장하지 않으므로 다시 입력해야 하며, ‘모니터링 시작’이 연결 검증도 자동 수행합니다. 현재 DB는 화면에서 안전하게 연결 해제할 수 있고 수집 이력은 유지됩니다.</p>
  </section>;
}

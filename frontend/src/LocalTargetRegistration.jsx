import React,{useEffect,useState} from 'react';
import {Database,ShieldCheck,PlugZap,AlertCircle,RefreshCcw,SlidersHorizontal,ChevronDown,Unplug,Pencil,Trash2,LockKeyhole,KeyRound,ShieldOff} from 'lucide-react';
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
  MONITORING_STOPPED:'현재 DB 수집을 중지하고 연결을 해제했습니다. 등록된 DB를 선택하고 비밀번호를 다시 입력하면 다른 DB에 연결할 수 있습니다.',
  TARGET_DISPLAY_NAME_UPDATED:'등록 대상의 표시 이름을 변경했습니다. DB 접속 정보와 기존 수집 기록은 그대로입니다.',
  TARGET_UNREGISTERED_HISTORY_PRESERVED:'등록 목록에서 삭제했습니다. SQL Server DB와 기존 수집 기록은 삭제되지 않았습니다.',
  ENCRYPTED_FOR_CURRENT_WINDOWS_USER:'현재 Windows 사용자 계정의 DPAPI로 DB 비밀번호를 암호화해 저장했습니다.',
  SAVED_CREDENTIAL_REMOVED:'이 DB의 저장된 비밀번호를 삭제했습니다. 등록 정보와 기존 모니터링 이력은 유지됩니다.'
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
  const [secureStorage,setSecureStorage]=useState(false);
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
      try{
        const capability=await fetch('/api/v1/local-targets/credential-capability',{cache:'no-store'});
        if(capability.ok){
          const info=await capability.json();
          setSecureStorage(Boolean(info.available&&info.provider==='WINDOWS_DPAPI_USER'));
        }else setSecureStorage(false);
      }catch{setSecureStorage(false);}
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
  const manageTarget=async(action,item)=>{
    if(busy||item.status==='ACTIVE_IN_MEMORY')return;
    let newName='';
    if(action==='rename'){
      const typed=window.prompt('새로운 연결 표시 이름을 입력하세요.',item.name);
      if(typed===null)return;
      newName=typed.trim();
      if(!newName||newName.length>100){
        setFeedback({ok:false,text:'연결 이름을 1~100자로 입력해 주세요.'});
        return;
      }
    }else if(!window.confirm(
      '이 DB를 로컬 등록 목록에서 삭제할까요? 실제 SQL Server 데이터베이스와 과거 DBPulse 수집 기록은 삭제되지 않습니다.'
    ))return;

    setBusy(true);setFeedback(null);
    try{
      const url='/api/v1/local-targets/'+encodeURIComponent(item.id)+'/'+
        (action==='rename'?'rename':'unregister');
      const response=await fetch(url,{
        method:'POST',
        headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
        body:action==='rename'?JSON.stringify({name:newName}):'{}'
      });
      if(response.status===409)throw Error('현재 모니터링 중인 DB입니다. 먼저 연결을 해제해 주세요.');
      if(response.status===404)throw Error('이미 삭제됐거나 찾을 수 없는 등록 대상입니다.');
      if(response.status===400)throw Error('변경 요청 또는 연결 이름이 유효하지 않습니다.');
      if(!response.ok)throw Error('등록 대상 관리 요청이 실패했습니다.');
      const result=await response.json();
      if(!['RENAMED','UNREGISTERED'].includes(result.status))
        throw Error('변경 결과를 확인하지 못했습니다.');
      if(selectedId===item.id){
        if(action==='unregister'){
          setSelectedId(null);
          setForm(INITIAL);
          setVerified(false);
        }else{
          setForm(previous=>({...previous,name:newName}));
        }
      }
      await load();
      setFeedback({ok:true,text:CODES[result.code]});
    }catch(error){
      setFeedback({ok:false,text:error.message||'등록 대상 변경에 실패했습니다.'});
      await load();
    }finally{setBusy(false);}
  };

  const credentialAction=async(action,item)=>{
    if(busy||!secureStorage)return;
    const prompts={
      save:'이 PC에 SQL Server 로그인 비밀번호를 Windows 사용자 계정 DPAPI로 암호화해 저장할까요? 같은 Windows 계정으로 DBPulse를 실행할 때만 재사용됩니다.',
      forget:'이 DB의 저장된 비밀번호를 삭제할까요? 이미 실행 중인 모니터링 세션은 연결 해제 전까지 계속됩니다.'
    };
    if(!window.confirm(prompts[action]))return;
    setBusy(true);setFeedback(null);
    try{
      const response=await fetch('/api/v1/local-targets/'+encodeURIComponent(item.id)+
        (action==='save'?'/remember-credential':'/forget-credential'),{
          method:'POST',
          headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
          body:'{}'
        });
      if(response.status===409)throw Error('Windows DPAPI를 사용할 수 없거나 현재 대상 상태가 변경됐습니다. Windows 호스트 모드와 선택된 DB를 확인해 주세요.');
      if(!response.ok)throw Error('저장된 자격증명 변경에 실패했습니다.');
      const result=await response.json();
      await load();
      setFeedback({ok:result.status==='SAVED'||result.status==='FORGOTTEN',
        text:CODES[result.code]||'요청을 처리했습니다.'});
    }catch(error){
      setFeedback({ok:false,text:error.message||'비밀번호 저장소 요청에 실패했습니다.'});
      await load();
    }finally{setBusy(false);}
  };

  const startSaved=async(item)=>{
    if(busy||!secureStorage||activeItem||!item.savedCredential)return;
    setBusy(true);setFeedback(null);
    try{
      const response=await fetch('/api/v1/local-targets/'+encodeURIComponent(item.id)+'/start-saved',{
        method:'POST',
        headers:{'Content-Type':'application/json','X-DBPulse-Local-Request':'1'},
        body:JSON.stringify({trustLocalCertificate:form.trustLocalCertificate})
      });
      if(response.status===409)throw Error('보관된 비밀번호를 사용할 수 없거나 이미 다른 DB가 수집 중입니다. 현재 연결 상태를 확인해 주세요.');
      if(!response.ok)throw Error('저장된 비밀번호로 연결하지 못했습니다. TLS 설정 또는 DB 계정 상태를 확인해 주세요.');
      const result=await response.json();
      const ok=result.status==='ACTIVE_IN_MEMORY';
      setFeedback({ok,text:CODES[result.code]||'연결 결과를 확인할 수 없습니다.'});
      if(ok){
        setForm(previous=>({...previous,password:''}));
        await load();
      }
    }catch(error){
      setFeedback({ok:false,text:error.message||'저장된 연결을 시작하지 못했습니다.'});
    }finally{setBusy(false);}
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
    {secureStorage
      ? <div className="dbp-local-credential-availability">
          <LockKeyhole size={16}/>Windows 사용자 계정 DPAPI 보호 활성화 · 연결 후 명시적으로 저장 가능
        </div>
      : <div className="dbp-local-credential-availability inactive">
          <ShieldOff size={16}/>비밀번호는 저장하지 않습니다. 암호화 저장은 Windows 호스트 실행 모드에서만 제공됩니다.
        </div>}
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
      {secureStorage && <button type="button" className="dbp-local-vault-save"
        disabled={busy||activeItem.savedCredential}
        onClick={()=>credentialAction('save',activeItem)}>
        <LockKeyhole size={15}/>{activeItem.savedCredential?'Windows DPAPI 저장됨':'이 PC에 암호화 저장'}
      </button>}
      <button type="button" className="dbp-local-disconnect"
        onClick={stopCurrent} disabled={busy}>
        <Unplug size={15}/>{busy?'처리 중…':'현재 DB 연결 해제 · 다른 DB 선택'}
      </button>
      <a href="#overview">개요에서 확인 →</a>
    </div>}
    {items.length ? <div className="dbp-local-list">
      {items.map(item=><div className="dbp-local-registry-entry" key={item.id}>
        <button type="button" className={
          'dbp-local-item dbp-local-selectable'+(selectedId===item.id?' selected':'')
        } aria-pressed={selectedId===item.id} onClick={()=>chooseRegistered(item)}
          disabled={busy}>
          <Database size={18}/>
          <div><strong>{item.databaseName}</strong>
            <small>{item.name} · {item.host}:{item.port}</small></div>
          <span className={item.status==='ACTIVE_IN_MEMORY'?'active':''}>
            {item.status==='ACTIVE_IN_MEMORY'?'현재 모니터링 대상':'선택하여 연결'}
          </span>
        </button>
        <div className="dbp-local-registry-actions">
          {secureStorage && item.savedCredential && <button type="button"
            className="dbp-local-vault-reconnect"
            disabled={busy||Boolean(activeItem)}
            onClick={()=>startSaved(item)}
            title="현재 Windows 사용자 계정에 저장된 비밀번호로 연결">
            <KeyRound size={14}/>저장된 연결
          </button>}
          {secureStorage && item.savedCredential && <button type="button"
            className="dbp-local-vault-forget" disabled={busy}
            onClick={()=>credentialAction('forget',item)}
            title="Windows DPAPI에 저장한 비밀번호만 삭제">
            <ShieldOff size={14}/>암호 해제
          </button>}
          <button type="button" onClick={()=>manageTarget('rename',item)}
            disabled={busy||item.status==='ACTIVE_IN_MEMORY'}
            title="등록 대상 표시 이름 수정" aria-label={item.databaseName+' 표시 이름 수정'}>
            <Pencil size={14}/>수정
          </button>
          <button type="button" className="danger" onClick={()=>manageTarget('unregister',item)}
            disabled={busy||item.status==='ACTIVE_IN_MEMORY'}
            title="등록 대상 목록에서만 삭제" aria-label={item.databaseName+' 등록 해제'}>
            <Trash2 size={14}/>삭제
          </button>
        </div>
      </div>)}
    </div>:<div className="dbp-local-empty">
      등록한 대상이 없습니다. 연결을 검사한 다음 대상 정보를 등록해 보세요.
    </div>}
    <p className="dbp-local-foot">등록된 DB 클릭 시 연결 정보가 채워집니다. 비밀번호는 기본적으로 저장하지 않습니다. Windows 호스트 실행 모드에서는 수집 중 명시적으로 DPAPI 암호화 저장을 선택하고, 이후 저장된 연결로 재접속할 수 있습니다. 실제 SQL Server DB 및 과거 수집 이력은 삭제되지 않습니다.</p>
  </section>;
}

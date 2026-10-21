import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const form=readFileSync(new URL('../src/LocalTargetRegistration.jsx',import.meta.url),'utf8');
const app=readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/local-target-registration.css',import.meta.url),'utf8');

test('Targets tab contains a real local connection form and preserves existing target',()=>{
  assert.match(app,/import LocalTargetRegistration from '\.\/LocalTargetRegistration\.jsx'/);
  assert.match(app,/<LocalTargetRegistration\/>/);
  assert.match(app,/target\?\.collectorStatus/);
  for(const field of ['name','host','port','databaseName','username','password']) {
    assert.ok(form.includes(field));
  }
  assert.match(form,/type="password"/);
  assert.match(form,/autoComplete="new-password"/);
  assert.match(form,/trustLocalCertificate:false/);
  assert.match(form,/type="checkbox" checked=\{form\.trustLocalCertificate\}/);
  assert.match(form,/<details className="dbp-local-advanced">/);
  assert.match(form,/<summary><SlidersHorizontal size=\{16\}/);
  assert.doesNotMatch(form,/<details className="dbp-local-advanced" open/);
  assert.match(css,/\.dbp-local-advanced\[open\]>summary/);
  assert.match(form,/TLS_CERTIFICATE_UNTRUSTED/);
  assert.match(form,/CONNECTION_TIMEOUT/);
  assert.match(form,/className="dbp-local-collector-status"/);
  assert.match(form,/collectorStatus==='ONLINE'/);
  assert.match(form,/collectorStatus==='OFFLINE'/);
  assert.match(form,/setInterval\(refresh,5000\)/);
  assert.match(form,/DBPULSE_LOCAL_TRUST_SELF_SIGNED=true/);
});

test('connection testing uses only local backend API and custom CSRF guard header',()=>{
  assert.match(form,/\/api\/v1\/local-targets\/test-connection/);
  assert.match(form,/\/api\/v1\/local-targets/);
  assert.match(form,/'X-DBPulse-Local-Request':'1'/);
  assert.match(form,/method:'POST'/);
  assert.match(form,/JSON\.stringify/);
  assert.doesNotMatch(form,/localStorage/);
  assert.doesNotMatch(form,/sessionStorage/);
  assert.doesNotMatch(form,/jdbc:sqlserver:/);
});

test('the target is clearly inactive and sensitive password is cleared after registration',()=>{
  assert.match(form,/CREDENTIAL_NOT_STORED_COLLECTOR_NOT_STARTED/);
  assert.match(form,/REGISTERED_INACTIVE/);
  assert.match(form,/password:''/);
  assert.match(form,/선택하여 연결/);
  assert.match(form,/chooseRegistered\(item\)/);
  assert.match(form,/비밀번호는 디스크에 저장하지 않으며/);
  assert.match(form,/\/api\/v1\/local-targets\/start-monitoring/);
  assert.match(form,/ACTIVE_IN_MEMORY/);
  assert.match(form,/선택한 DB 모니터링 시작/);
  assert.match(form,/disabled=\{busy\|\|!verified\}/);
  assert.match(form,/현재 모니터링 대상/);
  assert.match(form,/\/api\/v1\/local-targets\/stop-monitoring/);
  assert.match(form,/현재 DB 연결 해제 · 다른 DB 선택/);
  assert.match(form,/aria-pressed=\{selectedId===item\.id\}/);
  assert.match(form,/window\.confirm/);
  assert.match(form,/disabled=\{busy\|\|enabled!==true\|\|!form\.password\|\|Boolean\(activeItem\)\}/);
  assert.match(css,/\.dbp-local-form/);
  assert.match(css,/@media\(max-width:670px\)/);
});

test('saved targets have separate accessible rename and unregister actions',()=>{
  assert.match(form,/\/api\/v1\/local-targets\//);
  assert.match(form,/\('rename',item\)/);
  assert.match(form,/\('unregister',item\)/);
  assert.match(form,/encodeURIComponent\(item\.id\)/);
  assert.match(form,/\.status==='ACTIVE_IN_MEMORY'/);
  assert.match(form,/item\.status==='ACTIVE_IN_MEMORY'/);
  assert.match(form,/disabled=\{busy\|\|item\.status==='ACTIVE_IN_MEMORY'\}/);
  assert.match(form,/aria-label=\{item\.databaseName\+' 표시 이름 수정'\}/);
  assert.match(form,/aria-label=\{item\.databaseName\+' 등록 해제'\}/);
  assert.match(form,/window\.prompt/);
  assert.match(form,/window\.confirm/);
  assert.match(form,/SQL Server 데이터베이스와 과거 DBPulse 수집 기록은 삭제되지 않습니다/);
  assert.match(form,/setForm\(INITIAL\)/);
  assert.match(css,/\.dbp-local-registry-entry/);
  assert.match(css,/\.dbp-local-registry-actions/);
  assert.match(css,/@media\(max-width:650px\)/);
});

test('Windows DPAPI controls are opt-in, unavailable in Docker, and never leak secrets',()=>{
  assert.match(form,/\/api\/v1\/local-targets\/credential-capability/);
  assert.match(form,/info\.available&&info\.provider==='WINDOWS_DPAPI_USER'/);
  assert.match(form,/const \[secureStorage,setSecureStorage\]=useState\(false\)/);
  assert.match(form,/\/remember-credential/);
  assert.match(form,/\/forget-credential/);
  assert.match(form,/\/start-saved/);
  assert.match(form,/secureStorage && item\.savedCredential/);
  assert.match(form,/secureStorage && <button/);
  assert.match(form,/Windows 사용자 계정/);
  assert.match(form,/password:''/);
  assert.match(form,/body:'\{\}'/);
  assert.match(form,/window\.confirm/);
  assert.doesNotMatch(form,/localStorage/);
  assert.doesNotMatch(form,/sessionStorage/);
  assert.match(css,/\.dbp-local-credential-availability/);
  assert.match(css,/\.dbp-local-vault-save/);
  assert.match(css,/\.dbp-local-registry-actions button\.dbp-local-vault-reconnect/);
});

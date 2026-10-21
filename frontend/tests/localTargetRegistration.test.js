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
  assert.match(form,/등록됨 · 수집 미시작/);
  assert.match(form,/비밀번호는 디스크에 저장하지 않으며/);
  assert.match(form,/\/api\/v1\/local-targets\/start-monitoring/);
  assert.match(form,/ACTIVE_IN_MEMORY/);
  assert.match(form,/선택한 DB 모니터링 시작/);
  assert.match(form,/disabled=\{busy\|\|!verified\}/);
  assert.match(form,/현재 선택됨 · 수집 중/);
  assert.match(css,/\.dbp-local-form/);
  assert.match(css,/@media\(max-width:670px\)/);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const app=readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const widget=readFileSync(new URL('../src/QueryObserverOverheadPanel.jsx',import.meta.url),'utf8');

test('Query analysis page includes a live observer overhead indicator',()=>{
  assert.match(app,/import QueryObserverOverheadPanel from ['"]\.\/QueryObserverOverheadPanel\.jsx['"]/);
  assert.match(app,/<QueryObserverOverheadPanel targetId=\{target\?\.id\}\s*\/>/);
});
test('observer status polling reads ONLY DBPulse API and never starts MSSQL collection',()=>{
  assert.match(widget,/query-performance\/overhead/);
  assert.match(widget,/setInterval\(refresh,15000\)/);
  assert.doesNotMatch(widget,/\/query-performance\/plan-evidence|\/query-performance\/query-text/);
  assert.doesNotMatch(widget,/fetch\([^\n]*query_store/);
});
test('widget clearly separates heuristic cost indications from server-side impact',()=>{
  assert.match(widget,/data\.limitation/);
  assert.match(widget,/표본 없음/);
  assert.match(widget,/수집 간격 완화 중/);
});

test('lab-only fixed baseline policy is labelled without changing the dark theme layout',()=>{
  assert.match(widget,/FIXED_BASELINE_LAB_ONLY/);
  assert.match(widget,/고정 간격 · LAB/);
  assert.match(widget,/rev-observer-guard-status/);
});

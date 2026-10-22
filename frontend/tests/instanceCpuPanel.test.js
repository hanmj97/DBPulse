import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const page=readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const advisor=readFileSync(new URL('../src/QueryTuningAdvisorPanel.jsx',import.meta.url),'utf8');
const cpu=readFileSync(new URL('../src/InstanceCpuPanel.jsx',import.meta.url),'utf8');
test('selected DB CPU appears in query UI without unrelated instance CPU panel',()=>{
  assert.match(page,/<SelectedDatabaseCpuPanel queryState=\{queryState\}/);
  assert.doesNotMatch(page,/<InstanceCpuPanel targetId=\{target\?\.id\}/);
  assert.match(cpu,/SQL Server 프로세스 CPU/);
  assert.match(cpu,/호스트 전체 CPU/);
  assert.match(cpu,/선택한 DB만의 CPU가 아닌/);
});
test('Query tuning row displays average duration, CPU time, count and reads',()=>{
  assert.match(advisor,/<small>평균 실행<\/small>/);
  assert.match(advisor,/<small>평균 CPU<\/small>/);
  assert.match(advisor,/<small>집계 실행 횟수<\/small>/);
  assert.match(advisor,/<small>논리 읽기<\/small>/);
});
test('CPU total is derived from aggregate CPU and aggregate executions',()=>{
  assert.match(advisor,/Number\(row\.metrics\.avgCpuMs\)\*Number\(row\.metrics\.executionCount\)/);
  assert.match(page,/Number\(row\.avgCpuMs\)\*Number\(row\.executionCount\)/);
  assert.match(page,/CPU 총량 추정/);
});
test('CPU status panel polls only the DBPulse API, not MSSQL',()=>{
  assert.match(cpu,/\/instance-cpu/);
  assert.match(cpu,/setInterval\(load,15000\)/);
  assert.doesNotMatch(cpu,/sys\.dm_os_ring_buffers/);
});

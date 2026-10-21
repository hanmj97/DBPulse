import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('overview lower cards size to content rather than stretching with incidents',()=>{
  assert.match(overview,/rev-ops-secondary/);
  assert.match(overview,/rev-ops-action-panel/);
  assert.match(css,/\.rev-ops-secondary\s*\{[^}]*align-items:start/);
  assert.match(css,/\.rev-ops-secondary>\.rev-panel\s*\{[^}]*height:auto/);
  assert.match(css,/\.rev-ops-secondary>\.rev-ops-action-panel\s*\{[^}]*height:fit-content/);
});

test('CPU is fetched only from cached DBPulse GET API and not queried from MSSQL per page view',()=>{
  assert.match(overview,/function useOverviewCpuSamples\(targetId\)/);
  assert.match(overview,/\/instance-cpu/);
  assert.match(overview,/cache:'no-store'/);
  assert.match(overview,/setInterval\(refresh,15000\)/);
  assert.match(overview,/Number\.isFinite\(cpu\) && cpu>=0 && cpu<=100/);
  assert.match(overview,/data\?\.status==='ONLINE'/);
  assert.match(overview,/data\.sqlProcessCpuPercent != null/);
  assert.match(overview,/!prev\.some\(item=>item\.at===at\)/);
  assert.doesNotMatch(overview,/query_store_runtime_stats/);
});

test('CPU series uses distinct 0-100 percent axis and real timestamps only',()=>{
  assert.match(overview,/const cpuY = percent=>187-\(percent\/100\)\*149/);
  assert.match(overview,/const cpuX = at=>42\+\(\(at-firstMs\)\/\(lastMs-firstMs\)\)\*688/);
  assert.match(overview,/cpuSamples\.filter\(p=>p\.at>=firstMs && p\.at<=lastMs\)/);
  assert.match(overview,/p\.at-previous\.at>150000/);
  assert.match(overview,/cpuReady && cpuPoints\.length>1/);
  assert.match(overview,/cpuReady && cpuPoints\.map\(p=>/);
  assert.match(overview,/\[0,50,100\]\.map\(n=>/);
  assert.match(overview,/SQL Server 프로세스 CPU \(%\)/);
  assert.match(overview,/특정 데이터베이스의 CPU\(%\)가 아닙니다/);
  assert.match(css,/\.rev-ops-cpu-line/);
  assert.match(css,/\.rev-ops-cpu-readout/);
  assert.doesNotMatch(overview,/Math\.random\(/);
});

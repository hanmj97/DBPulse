import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('Overview preserves server status, stable diagnostics, incident views and DB label',()=>{
  for(const klass of ['rev-ops-wide-chart','rev-ops-unified-board','rev-ops-unified-grid','rev-ops-history-board']) {
    assert.ok(overview.includes(klass));
  }
  assert.match(overview,/historyView==='table'/);
  assert.match(overview,/setHistoryView\('timeline'\)/);
  assert.match(overview,/target\?\.databaseName \|\| target\?\.name/);
  assert.match(overview,/databaseName=\{target\?\.databaseName\}/);
  assert.match(css,/grid-template-rows:220px/);
  assert.match(css,/@media\(max-width:670px\)/);
});

test('Overview CPU reads only cached DBPulse GET and retains real samples',()=>{
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

test('request and instance CPU are separate React charts with independent axes',()=>{
  assert.match(overview,/function RequestTrendPanel\(/);
  assert.match(overview,/function CpuTrendPanel\(/);
  assert.match(overview,/function TrendChart\(/);
  assert.match(overview,/className="rev-ops-split-charts"/);
  assert.match(overview,/<RequestTrendPanel history=\{history\} sampleLimit=\{sampleLimit\}/);
  assert.match(overview,/<CpuTrendPanel cpuState=\{cpuState\}/);
  assert.match(overview,/REQUEST_SERIES/);
  assert.match(overview,/nonZeroSegments\(/);
  assert.match(overview,/const y=value=>CHART_BOTTOM-Number\(value\)\/100/);
  assert.match(overview,/\[0,25,50,75,100\]\.map/);
  assert.match(overview,/단위: 건/);
  assert.match(overview,/단위: %/);
  assert.match(overview,/선택 DB만의 CPU가 아님/);
  assert.match(overview,/특정 DB의 CPU\(%\)가 아닙니다/);
  assert.match(css,/\.rev-ops-wide-chart \.rev-ops-split-charts \{/);
  assert.match(css,/grid-template-columns:minmax\(0,1fr\) minmax\(0,1fr\)/);
  assert.match(css,/aspect-ratio:560 \/ 210/);
  assert.match(css,/@media\(max-width:1150px\)/);
  assert.doesNotMatch(overview,/Math\.random\(/);
});

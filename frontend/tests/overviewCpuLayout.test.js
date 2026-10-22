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

test('Overview selected-DB CPU does not poll SQL Server process CPU%',()=>{
  assert.match(overview,/SelectedDatabaseCpuPanel/);
  assert.match(overview,/queryState=\{queryState\}/);
  assert.doesNotMatch(overview,/function useOverviewCpuSamples/);
  assert.doesNotMatch(overview,/function CpuTrendPanel/);
  assert.doesNotMatch(overview,/instance-cpu/);
  const selected=readFileSync(new URL('../src/SelectedDatabaseCpuPanel.jsx',import.meta.url),'utf8');
  assert.match(selected,/databaseCpuMs/);
  assert.match(selected,/databaseName/);
  assert.match(selected,/Query Store/);
  assert.match(selected,/점유율/);
  assert.doesNotMatch(selected,/sqlProcessCpuPercent/);
});

test('DMV requests and Query Store CPU are distinct measurements',()=>{
  assert.match(overview,/function RequestTrendPanel\(/);
  assert.match(overview,/function TrendChart\(/);
  assert.match(overview,/className="rev-ops-split-charts"/);
  assert.match(overview,/<RequestTrendPanel history=\{history\} sampleLimit=\{sampleLimit\}/);
  assert.match(overview,/<SelectedDatabaseCpuPanel queryState=\{queryState\} databaseName=\{databaseName\} compact\/>/);
  assert.match(overview,/REQUEST_SERIES/);
  assert.match(overview,/nonZeroSegments\(/);
  assert.match(overview,/단위: 건/);
  assert.match(css,/\.rev-ops-wide-chart \.rev-ops-split-charts \{/);
  assert.match(css,/grid-template-columns:minmax\(0,1fr\) minmax\(0,1fr\)/);
  assert.match(css,/@media\(max-width:1150px\)/);
  assert.doesNotMatch(overview,/Math\.random\(/);
});

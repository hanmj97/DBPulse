import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const app=readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const graph=readFileSync(new URL('../src/SelectedDatabaseCpuPanel.jsx',import.meta.url),'utf8');
const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const drawer=readFileSync(new URL('../src/QuerySqlDrawer.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');
test('overview has a true selected-DB CPU timeline, not an instance CPU graph',()=>{
  assert.match(overview,/selectedDbCpuTrend/);
  assert.match(overview,/trend=\{selectedDbCpuTrend\} compact/);
  assert.match(app,/appendSelectedDbCpuSample/);
  assert.match(app,/setSelectedDbCpuTrend\(\[\]\)/);
  assert.match(graph,/DbCpuTrendGraph/);
  assert.match(graph,/<svg viewBox="0 0 560 210"/);
  assert.match(graph,/corePercent/);
  assert.match(graph,/CPU 부하 \(1코어=100%\)/);
  assert.match(graph,/100%를 초과할 수/);
  assert.match(css,/\.rev-db-cpu-graph svg/);
  assert.doesNotMatch(overview,/sqlProcessCpuPercent/);
});
test('SQL text remains explicit secure local-only opt-in',()=>{
  assert.match(drawer,/DBPULSE_QUERY_TEXT_ENABLED/);
  assert.match(drawer,/cache:'no-store'/);
  assert.match(drawer,/abort\.abort\(\)/);
  assert.doesNotMatch(drawer,/console\.(log|debug)\(/);
});

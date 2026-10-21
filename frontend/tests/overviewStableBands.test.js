import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {nonZeroSegments} from '../src/chartVisibility.js';

const jsx=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('overview has three stable vertical bands, not two unconstrained drifting columns',()=>{
  const chart=jsx.indexOf('className="rev-ops-wide-chart"');
  const board=jsx.indexOf('className="rev-panel rev-ops-unified-board"');
  const history=jsx.indexOf('className="rev-panel rev-ops-history-board"');
  assert.ok(chart>0 && board>chart && history>board);
  assert.doesNotMatch(jsx,/className="rev-ops-independent-columns"/);
  assert.match(css,/\.rev-ops-unified-grid\{[\s\S]*?grid-template-rows:203px/);
  assert.match(css,/\.rev-ops-unified-grid>\.rev-ops-summary-cell\s*\{[\s\S]*?overflow:auto/);
  assert.match(jsx,/const PREVIEW_LIMIT = 2/);
  assert.match(jsx,/const hiddenWaitGroupCount/);
  assert.match(jsx,/const hiddenAlertCount/);
});

test('history table and timeline are alternate views of same bounded incidents, not two growing cards',()=>{
  assert.match(jsx,/useState\('table'\)/);
  assert.match(jsx,/historyView==='table' \? \(/);
  assert.match(jsx,/setHistoryView\('table'\)/);
  assert.match(jsx,/setHistoryView\('timeline'\)/);
  assert.match(jsx,/incidents\.slice\(0,6\)/);
  assert.match(jsx,/incidents\.slice\(0,4\)/);
  assert.match(jsx,/onInvestigate\(item\.id\)/);
  assert.match(css,/\.rev-ops-history-switch button:focus-visible/);
});

test('neutral shared zero baseline stays visible while real positive values keep different colored segments',()=>{
  assert.match(jsx,/className="rev-ops-neutral-zero"/);
  assert.match(css,/\.rev-ops-neutral-zero\{/);
  assert.match(jsx,/REQUEST_SERIES\.flatMap\(series=>segments\(series\.key\)\.map/);
  assert.match(jsx,/stroke=\{series\.color\}/);
  const zeros=[0,0,0,0].map((value,i)=>({value,t:i*5000}));
  assert.deepEqual(nonZeroSegments(zeros,p=>p.value,p=>p.t,20000),[]);
  const events=[0,0,2,2,0,0].map((value,i)=>({value,t:i*5000}));
  assert.deepEqual(nonZeroSegments(events,p=>p.value,p=>p.t,20000),
    [[1,2],[2,3],[3,4]]);
});

test('SQL Server instance CPU legend is bright in both themes even when CPU is zero',()=>{
  assert.match(jsx,/className="rev-ops-cpu-legend"/);
  assert.match(jsx,/function CpuTrendPanel\(/);
  assert.match(jsx,/\[0,25,50,75,100\]\.map/);
  assert.match(jsx,/SQL Server 전체 프로세스 CPU \(%\)/);
  assert.match(css,/\.rev-ops-split-charts \.rev-chart-legend \.rev-ops-cpu-legend/);
  assert.match(css,/background:#ffbd60 !important/);
  assert.match(css,/\.rev-ops-wide-chart \.rev-ops-split-charts/);
  assert.match(css,/@media\(max-width:670px\)/);
});

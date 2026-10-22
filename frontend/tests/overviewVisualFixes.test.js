import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {nonZeroSegments} from '../src/chartVisibility.js';

const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('DBPulse request graph cannot be mistaken for selected database CPU time',()=>{
  assert.match(overview,/function RequestTrendPanel\(/);
  assert.match(overview,/function TrendChart\(/);
  assert.match(overview,/className="rev-ops-split-charts"/);
  assert.match(overview,/실시간 요청 현황/);
  assert.match(overview,/<SelectedDatabaseCpuPanel queryState=\{queryState\} databaseName=\{databaseName\} trend=\{selectedDbCpuTrend\} compact\/>/);
  assert.match(overview,/단위: 건/);
  assert.match(overview,/REQUEST_SERIES\.flatMap\(series=>segments\(series\.key\)\.map/);
  assert.match(overview,/stroke=\{series\.color\}/);
  assert.doesNotMatch(overview,/function CpuTrendPanel\(/);
  assert.doesNotMatch(overview,/SQL Server CPU 사용률/);
  assert.match(css,/\.rev-layout\[data-theme\] \.rev-chart-area line\{stroke:var\(--t-chart-grid\)\}/);
  assert.match(overview,/<path className="rev-ops-metric-segment"/);
  assert.deepEqual(nonZeroSegments([0,2,0].map((v,i)=>({v,t:i*5000})),
    x=>x.v,x=>x.t,20000),[[0,1],[1,2]]);
});

test('request SVG retains readable aspect ratio and keyboard access',()=>{
  assert.match(overview,/viewBox="0 0 560 210" preserveAspectRatio="xMidYMid meet"/);
  assert.doesNotMatch(overview,/preserveAspectRatio="none"/);
  assert.match(overview,/const pickChartIndex = \(event,length\) =>/);
  assert.match(css,/\.rev-ops-wide-chart \.rev-ops-split-charts \.rev-ops-chart-frame svg \{/);
  assert.match(css,/aspect-ratio:560 \/ 210/);
  assert.match(css,/max-width:620px/);
  assert.match(css,/grid-template-columns:minmax\(0,1fr\) minmax\(0,1fr\)/);
  assert.match(css,/@media\(max-width:1150px\)/);
  assert.match(overview,/onKeyDown=\{event=>chartArrowIndex/);
});

test('operational summary keeps two-item previews, accurate remaining counts, no scrollbars',()=>{
  assert.match(overview,/const PREVIEW_LIMIT = 2/);
  assert.match(overview,/const previewAlerts = alerts\.slice\(0, PREVIEW_LIMIT\)/);
  assert.match(overview,/const visibleWaitGroups = waitGroups\.slice\(0, PREVIEW_LIMIT\)/);
  assert.doesNotMatch(overview,/sort\(\(a,b\)=>b\[1\]-a\[1\]\)\.slice\(0,5\)/);
  assert.match(overview,/const hiddenWaitGroupCount = waitGroups\.length - visibleWaitGroups\.length/);
  assert.match(overview,/hiddenAlertCount>0/);
  assert.match(overview,/hiddenWaitGroupCount>0/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-unified-grid\{[\s\S]*?grid-template-rows:220px;[\s\S]*?overflow:hidden;/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-unified-grid > \.rev-ops-summary-cell\{[\s\S]*?overflow:hidden;/);
  assert.match(overview,/onClick=\{\(\)=>nav\('incidents'\)\}/);
  assert.match(overview,/onClick=\{\(\)=>nav\('waits'\)\}/);
});

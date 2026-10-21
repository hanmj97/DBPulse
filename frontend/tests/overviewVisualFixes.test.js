import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {nonZeroSegments} from '../src/chartVisibility.js';

const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('measured request and CPU series use SVG paths, not gridline-colored SVG lines',()=>{
  // A pre-existing themed rule forces ALL SVG line strokes to the grid color.
  // Measured paths are immune to that rule.
  assert.match(css,/\.rev-layout\[data-theme\] \.rev-chart-area line\{stroke:var\(--t-chart-grid\)\}/);
  assert.match(overview,/lines\.flatMap\(l=>requestSegments\(l\.key\)\.map/);
  assert.match(overview,/<path key=\{l\.key\+'-'\+a\} className="rev-ops-metric-segment"/);
  assert.match(overview,/stroke=\{l\.color\} strokeWidth="2\.8"/);
  assert.match(overview,/<path className="rev-ops-cpu-line" key=\{'cpu-'\+a\}/);
  assert.match(overview,/stroke="#ffbd60" strokeDasharray="5 4"/);
  assert.doesNotMatch(overview,/<line key=\{l\.key\+'-'\+a\}/);
  assert.match(overview,/className="rev-ops-neutral-zero"/);
  assert.deepEqual(nonZeroSegments([0,0,0].map((v,i)=>({v,t:i*5000})),
    x=>x.v,x=>x.t,20000),[]);
  assert.deepEqual(nonZeroSegments([0,2,0].map((v,i)=>({v,t:i*5000})),
    x=>x.v,x=>x.t,20000),[[0,1],[1,2]]);
});

test('graph text preserves aspect ratio in a centered readable chart frame',()=>{
  assert.match(overview,/className="rev-ops-chart-frame"/);
  assert.match(overview,/preserveAspectRatio="xMidYMid meet"/);
  assert.doesNotMatch(overview,/preserveAspectRatio="none"/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-chart-area \.rev-ops-chart-frame\{/);
  assert.match(css,/max-width:940px/);
  assert.match(css,/aspect-ratio:760 \/ 208/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-chart-area \.rev-ops-chart-frame \.rev-chart-times\{/);
  assert.match(overview,/onMouseMove=\{pickIndex\}/);
  assert.match(overview,/onKeyDown=\{handleKey\}/);
});

test('operator summary previews have no drag scrollbar at any breakpoint',()=>{
  assert.match(overview,/const PREVIEW_LIMIT = 2/);
  assert.match(overview,/const previewAlerts = alerts\.slice\(0, PREVIEW_LIMIT\)/);
  assert.match(overview,/const visibleWaitGroups = waitGroups\.slice\(0, PREVIEW_LIMIT\)/);
  // The category inventory itself must not be truncated at five before
  // computing hiddenWaitGroupCount; 10 categories must show "8 more".
  assert.doesNotMatch(overview,/sort\(\(a,b\)=>b\[1\]-a\[1\]\)\.slice\(0,5\)/);
  assert.match(overview,/const hiddenWaitGroupCount = waitGroups\.length - visibleWaitGroups\.length/);
  assert.match(overview,/hiddenAlertCount>0/);
  assert.match(overview,/hiddenWaitGroupCount>0/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-unified-grid\{[\s\S]*?grid-template-rows:220px;[\s\S]*?overflow:hidden;/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-unified-grid > \.rev-ops-summary-cell\{[\s\S]*?overflow:hidden;/);
  assert.match(css,/\.rev-ops-dashboard \.rev-ops-unified-grid \.rev-ops-action-list button\{[\s\S]*?height:42px/);
  assert.match(css,/grid-template-rows:180px 210px 220px/);
  assert.match(overview,/onClick=\{\(\)=>nav\('incidents'\)\}/);
  assert.match(overview,/onClick=\{\(\)=>nav\('waits'\)\}/);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const overview = readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css = readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');
const shell = readFileSync(new URL('../src/DashboardShell.jsx',import.meta.url),'utf8');

test('operations hero derives server health from actual target and recent DMV',()=>{
  assert.match(overview,/rev-ops-server-bar/);
  assert.match(overview,/target\?\.name/);
  assert.match(overview,/target\?\.databaseName/);
  assert.match(overview,/latest\?\.collectedAt/);
  assert.match(overview,/target\?\.collectorStatus === 'ONLINE' && recentSample/);
  assert.match(overview,/open\.length>0/);
  assert.match(overview,/observed|관측 지표상 이상 없음/);
  assert.match(overview,/데이터 공백을 정상으로 판단하지 않습니다/);
});

test('time-series chart remains bound to DMV samples and offers keyboard inspection',()=>{
  assert.match(overview,/history\.slice\(-sampleLimit\)/);
  for(const metric of ['activeRequests','blockedRequests','longRunningRequests']) {
    assert.ok(overview.includes(metric));
  }
  assert.match(overview,/onMouseMove=\{pickIndex\}/);
  assert.match(overview,/onKeyDown=\{handleKey\}/);
  assert.match(overview,/ArrowLeft/);
  assert.match(overview,/ArrowRight/);
  assert.match(overview,/rev-ops-chart-readout/);
  assert.match(overview,/aria-live="polite"/);
  assert.doesNotMatch(overview,/Math\.random\(/);
});

test('empty Wait and current signals use compact cards without inventing events',()=>{
  assert.match(overview,/rev-ops-compact-state/);
  assert.match(overview,/waitGroups\.length===0/);
  assert.match(overview,/currentWaits===null/);
  assert.match(overview,/!alerts\.length/);
  assert.match(overview,/현재 관측된 Wait 요청 없음/);
  assert.match(overview,/수집 상태를 확인 중입니다/);
  assert.match(overview,/incidents\.slice\(0,6\)/);
  assert.match(overview,/onInvestigate\(item\.id\)/);
});

test('operational shortcuts keep existing navigation and do not perform DB mutations',()=>{
  for(const path of ['blocking','waits','queries','incidents','targets']) {
    assert.ok(overview.includes("nav('"+path+"')"));
  }
  assert.match(overview,/rev-ops-action-list/);
  assert.doesNotMatch(overview,/fetch\([^)]*method:\s*['"]POST/);
});

test('Navy and Midnight Dark + responsive operations layout are preserved',()=>{
  assert.match(shell,/id:'navy'/);
  assert.match(shell,/id:'dark'/);
  assert.match(css,/\.rev-layout\[data-theme="navy"\]/);
  assert.match(css,/\.rev-layout\[data-theme="dark"\]/);
  assert.match(css,/\.rev-ops-server-bar/);
  assert.match(css,/\.rev-ops-kpis/);
  assert.match(css,/\.rev-ops-compact-state/);
  assert.match(css,/\.rev-ops-chart-readout/);
  assert.match(css,/@media\(max-width:1150px\)/);
  assert.match(css,/@media\(max-width:670px\)/);
  assert.match(css,/background:var\(--t-card\)/);
});

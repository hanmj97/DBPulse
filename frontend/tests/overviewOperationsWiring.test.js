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
  assert.match(overview,/const unknownHealth = !online \\|\\| !hasIncidentData/);
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

test('right-hand rail renders a bounded signal and Wait preview, not every event',()=>{
  assert.match(overview,/const PREVIEW_LIMIT = 2/);
  assert.match(overview,/const previewAlerts = alerts\.slice\(0, PREVIEW_LIMIT\)/);
  assert.match(overview,/const visibleWaitGroups = waitGroups\.slice\(0, PREVIEW_LIMIT\)/);
  assert.match(overview,/previewAlerts\.map\(a=>/);
  assert.match(overview,/visibleWaitGroups\.map\(\(\[kind,count\],index\)=>/);
  assert.match(overview,/hiddenAlertCount>0/);
  assert.match(overview,/hiddenWaitGroupCount>0/);
  assert.match(overview,/인시던트 이력/);
  assert.match(overview,/다른 대기 유형/);
  // Only the preview is capped; the incident table and route remain intact.
  assert.match(overview,/open\.map\(i=>/);
  assert.match(overview,/incidents\.slice\(0,6\)/);
  assert.match(css,/\.rev-ops-alert-preview \.rev-alert-item\{/);
  assert.match(css,/height:49px/);
  assert.match(css,/overflow:hidden/);
  assert.match(css,/\.rev-ops-more-signals/);
  assert.match(css,/\.rev-ops-more-link/);
});

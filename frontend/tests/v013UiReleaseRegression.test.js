import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Lightweight release regression gate. These assert source wiring and
// styling contracts only; real browser/API behavior still needs local review.
const read = path => readFileSync(new URL(path,import.meta.url),'utf8');
const shell = read('../src/DashboardShell.jsx');
const app = read('../src/main.jsx');
const css = read('../src/dashboard.css');
const advisor = read('../src/QueryTuningAdvisorPanel.jsx');
const overhead = read('../src/QueryObserverOverheadPanel.jsx');

test('Navy and Midnight Dark presets are selectable and persisted',()=>{
  assert.match(shell,/\{id:'navy', name:'DBPulse 네이비'\}/);
  assert.match(shell,/\{id:'dark', name:'미드나잇 다크'\}/);
  assert.match(shell,/localStorage\.getItem\(THEME_STORAGE_KEY\)/);
  assert.match(shell,/localStorage\.setItem\(THEME_STORAGE_KEY, next\)/);
  assert.match(shell,/data-theme=\{theme\}/);
  assert.match(shell,/id="dbpulse-theme-select"/);
  assert.match(css,/\.rev-layout\[data-theme="navy"\]/);
  assert.match(css,/\.rev-layout\[data-theme="dark"\]/);
  assert.match(css,/\.rev-sql-drawer-root\[data-theme="navy"\]/);
  assert.match(css,/\.rev-sql-drawer-root\[data-theme="dark"\]/);
});

test('all existing navigation hashes remain mapped to pages',()=>{
  for(const hash of [
    '#overview','#incidents','#wait-diagnostics','#deadlocks',
    '#blocking-chains','#performance','#query-performance',
    '#investigation','#targets'
  ]){
    assert.ok(app.includes("'"+hash+"'"), 'missing navigation hash '+hash);
  }
  assert.match(app,/window\.addEventListener\('hashchange', sync\)/);
  assert.match(app,/<DashboardShell/);
  assert.match(app,/<OverviewDashboard/);
  assert.match(app,/<WaitDiagnostics/);
  assert.match(app,/<IncidentInvestigation/);
});

test('query views retain observer status, advisor, plan and SQL drawer',()=>{
  assert.match(app,/<QueryObserverOverheadPanel targetId=\{target\?\.id\}\s*\/>/);
  assert.match(app,/<QueryTuningAdvisorPanel targetId=\{target\?\.id\}/);
  assert.match(app,/<QuerySqlDrawer targetId=\{target\?\.id\}/);
  assert.match(advisor,/<QueryPlanComparison key=/);
  assert.match(advisor,/<PlanOperatorEvidence key=/);
  assert.match(overhead,/query-performance\/overhead/);
  assert.match(app,/value="exclude-dbpulse"/);
  assert.match(app,/value="dbpulse-only"/);
});

test('v0.13 visible labels do not imply a fixed polling interval or old release',()=>{
  assert.ok(shell.includes('v0.13.0-alpha · 로컬 단일 대상'));
  assert.ok(app.includes('60s 기본 · 자동 완화'));
  assert.ok(advisor.includes('SQL Tuning Advisor <small>v0.13 alpha</small>'));
  assert.doesNotMatch(shell,/v0\.8\.0/);
  assert.doesNotMatch(app,/<span className="small-pill">60s collect<\/span>/);
  assert.doesNotMatch(advisor,/v0\.12 alpha/);
});

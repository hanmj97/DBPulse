import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const app=readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const drawer=readFileSync(new URL('../src/QuerySqlDrawer.jsx',import.meta.url),'utf8');
const graph=readFileSync(new URL('../src/SelectedDatabaseCpuPanel.jsx',import.meta.url),'utf8');
test('history switch is explicit and live window remains separate',()=>{
 assert.match(app,/최근 15분/);
 assert.match(app,/저장 이력 · 최근 7일/);
 assert.match(app,/query-performance\/history\?limit=100/);
 assert.match(app,/queryView==='history'\?queryHistory:queryAnalysis/);
 assert.match(app,/setQueryHistory\(\[\]\)/);
});
test('desktop drawer requires its local request header and does not store text',()=>{
 assert.match(drawer,/X-DBPulse-Local-Request/);
 assert.match(drawer,/cache:'no-store'/);
 assert.match(drawer,/if\(!selection \|\| !targetId\)/);
 assert.doesNotMatch(drawer,/localStorage|sessionStorage|console\.log/);
});
test('CPU visual paints graph grid without waiting for measurements',()=>{
 assert.match(graph,/<svg viewBox="0 0 560 210"/);
 assert.match(graph,/Query Store 수집 2회 이상 완료/);
 assert.match(graph,/corePercent/);
});

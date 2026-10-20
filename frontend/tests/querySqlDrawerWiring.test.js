import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const app = readFileSync(new URL('../src/main.jsx',import.meta.url),'utf8');
const advisor = readFileSync(new URL('../src/QueryTuningAdvisorPanel.jsx',import.meta.url),'utf8');
const drawer = readFileSync(new URL('../src/QuerySqlDrawer.jsx',import.meta.url),'utf8');
const css = readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('SQL drawer correctly imported and wired to both query surfaces',()=>{
  assert.match(app,/import QuerySqlDrawer from ['"]\.\/QuerySqlDrawer\.jsx['"]/);
  assert.match(app,/<QuerySqlDrawer targetId=\{target\?\.id\}/);
  assert.match(app,/onShowSql=\{\(queryId,planId\)=>setSqlSelection\(\{queryId,planId\}\)\}/);
  assert.match(app,/SQL 보기/);
  assert.match(advisor,/className="rev-query-sql-link rev-tuning-row-sql"/);
});
test('SQL text is fetched only when a query-plan selection is present',()=>{
  assert.match(drawer,/if\(!selection \|\| !targetId\)/);
  assert.match(drawer,/query-text\/\$\{selection\.queryId\}\/\$\{selection\.planId\}/);
  assert.match(drawer,/cache:'no-store'/);
  assert.match(drawer,/abort\.abort\(\)/);
  assert.doesNotMatch(drawer,/setInterval\s*\(/);
});
test('untrusted SQL is rendered as escaped JSX text, never injected into HTML',()=>{
  assert.match(drawer,/<pre className="rev-sql-drawer-code"><code>\{report\.queryText\}<\/code><\/pre>/);
  assert.doesNotMatch(drawer,/dangerouslySetInnerHTML|innerHTML\s*=/);
  assert.doesNotMatch(drawer,/console\.(log|debug|warn|error)\(/);
});
test('only the two approved themes have drawer variable palettes',()=>{
  assert.match(drawer,/data-theme=\{activeTheme\}/);
  assert.match(css,/\.rev-sql-drawer-root\[data-theme="navy"\]/);
  assert.match(css,/\.rev-sql-drawer-root\[data-theme="dark"\]/);
});
test('drawer is explicitly user-dismissible',()=>{
  assert.match(drawer,/aria-modal="true"/);
  assert.match(drawer,/event\.key==='Escape'/);
  assert.match(drawer,/aria-label="SQL 원문 창 닫기"/);
});

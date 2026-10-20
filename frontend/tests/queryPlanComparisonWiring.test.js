import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const advisor = readFileSync(new URL('../src/QueryTuningAdvisorPanel.jsx',import.meta.url),'utf8');
const panel = readFileSync(new URL('../src/QueryPlanComparison.jsx',import.meta.url),'utf8');

test('Query Plan Comparison component is imported and shown only on expanded candidate',()=>{
  assert.match(advisor,/import QueryPlanComparison from ['"]\.\/QueryPlanComparison\.jsx['"]/);
  assert.match(advisor,/<QueryPlanComparison key=\{'compare-'/);
  assert.match(advisor,/\{open && <div className="rev-tuning-detail">/);
});
test('Plan comparisons are opt-in and not fetched in a polling effect',()=>{
  assert.match(panel,/onClick=\{load\}/);
  assert.match(panel,/plan-comparison\/\$\{queryId\}\/\$\{planId\}/);
  assert.doesNotMatch(panel,/setInterval\(/);
});
test('No fixed lab query IDs or SQL-changing buttons',()=>{
  assert.doesNotMatch(panel,/(?:queryId|planId)\s*=\s*(?:239|178)/);
  assert.doesNotMatch(panel,/CREATE\s+INDEX|ALTER\s+INDEX|KILL\s+\d+/i);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// A Vite production build can pass even with an undefined JSX component.
// Prevent a recurrence of the blank Query page caused by a mistyped tag.
const main = readFileSync(new URL('../src/main.jsx', import.meta.url), 'utf8');

test('Query page renders the actual imported QueryTuningAdvisorPanel', () => {
  assert.match(main, /import QueryTuningAdvisorPanel from ['"]\.\/QueryTuningAdvisorPanel\.jsx['"]/);
  assert.match(main, /\{page === 'queries' &&[\s\S]*?<QueryTuningAdvisorPanel\s+targetId=/);
  assert.doesNotMatch(main, /<QueryTuningAdvisor(?:\s|\/?>)/);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { filterQueryCandidates } from '../src/queryCandidateFilters.js';

const rows = [
  {queryId:239,planId:178,priority:'OBSERVE'},
  {queryId:95,planId:29,priority:'MEDIUM'},
  {queryId:239,planId:179,priority:'HIGH'},
  {queryId:2399,planId:178,priority:'HIGH'}
];

test('queryId 239 and planId 178 match only the requested pair', () => {
  assert.deepEqual(filterQueryCandidates(rows,{queryId:'239',planId:'178'}),[rows[0]]);
});
test('query ID and plan ID can each be filtered independently', () => {
  assert.deepEqual(filterQueryCandidates(rows,{queryId:'239'}),[rows[0],rows[2]]);
  assert.deepEqual(filterQueryCandidates(rows,{planId:'178'}),[rows[0],rows[3]]);
});
test('exact match never confuses 239 with 2399 and preserves API ordering', () => {
  assert.deepEqual(filterQueryCandidates(rows,{queryId:'239',planId:'999'}),[]);
  assert.deepEqual(filterQueryCandidates(rows,{}),rows);
});
test('priority tabs combine with ID filters', () => {
  assert.deepEqual(filterQueryCandidates(rows,{priority:'HIGH',queryId:'239'}),[rows[2]]);
  assert.deepEqual(filterQueryCandidates(rows,{priority:'MEDIUM',queryId:'239'}),[]);
});
test('empty or missing candidate list is handled safely', () => {
  assert.deepEqual(filterQueryCandidates([], {queryId:'239'}),[]);
  assert.deepEqual(filterQueryCandidates(null, {queryId:'239'}),[]);
});

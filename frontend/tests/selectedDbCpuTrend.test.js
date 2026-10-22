import test from 'node:test';
import assert from 'node:assert/strict';
import {appendSelectedDbCpuSample as step} from '../src/selectedDbCpuTrend.js';

const snap=(iso,ms,status='ONLINE')=>({status,lastCollectedAt:iso,databaseCpuMs:ms});
test('requires two distinct Query Store collection intervals before showing a percentage',()=>{
  const a=step([],snap('2026-10-22T12:00:00Z',12000),'db1');
  assert.equal(a.length,1);
  assert.equal(a[0].corePercent,null);
  const b=step(a,snap('2026-10-22T12:01:00Z',12450),'db1');
  assert.equal(b.length,2);
  assert.equal(b[1].corePercent,0.75);
  assert.deepEqual(step(b,snap('2026-10-22T12:01:00Z',12450),'db1'),b);
});

test('100 percent means one full logical core equivalent, not server CPU%',()=>{
  const a=step([],snap('2026-10-22T12:00:00Z',10000),'db1');
  const b=step(a,snap('2026-10-22T12:01:00Z',70000),'db1');
  assert.equal(b.at(-1).corePercent,100);
  const c=step(b,snap('2026-10-22T12:02:00Z',190000),'db1');
  assert.equal(c.at(-1).corePercent,200); // Parallel CPU can exceed 100.
});

test('rolling-window reset and missing samples are unknown, not zero',()=>{
  let a=step([],snap('2026-10-22T12:00:00Z',10000),'db1');
  a=step(a,snap('2026-10-22T12:01:00Z',7000),'db1');
  assert.equal(a[1].corePercent,null);
  a=step(a,snap('2026-10-22T12:15:00Z',9000),'db1');
  assert.equal(a[2].corePercent,null);
});

test('changing target cannot carry over cumulative CPU baseline',()=>{
  const a=step([],snap('2026-10-22T12:00:00Z',10000),'db1');
  const b=step(a,snap('2026-10-22T12:01:00Z',100),'db2');
  assert.equal(b.length,1);
  assert.equal(b[0].corePercent,null);
  assert.equal(b[0].targetId,'db2');
});

test('offline/stale polls do not create synthetic samples',()=>{
  const a=step([],snap('2026-10-22T12:00:00Z',10000),'db1');
  assert.deepEqual(step(a,snap('2026-10-22T12:01:00Z',11000,'OFFLINE'),'db1'),a);
  assert.deepEqual(step(a,{status:'ONLINE',databaseCpuMs:null},'db1'),a);
});

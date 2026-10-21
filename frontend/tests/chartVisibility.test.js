import test from 'node:test';
import assert from 'node:assert/strict';
import {nonZeroSegments} from '../src/chartVisibility.js';
import {readFileSync} from 'node:fs';

const overview=readFileSync(new URL('../src/OverviewDashboard.jsx',import.meta.url),'utf8');
const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');

test('all zero DMV series do not render overlapping colored baselines',()=>{
  const values=[{v:0,t:1000},{v:0,t:6000},{v:0,t:11000}];
  assert.deepEqual(nonZeroSegments(values,x=>x.v,x=>x.t,20000),[]);
  assert.match(overview,/REQUEST_SERIES\.flatMap\(series=>segments\(series\.key\)\.map/);
  assert.match(overview,/nonZeroSegments\(/);
  assert.match(overview,/rev-ops-chart-idle/);
  assert.match(css,/\.rev-ops-chart-idle/);
});

test('genuine positive samples and transitions to/from zero still render',()=>{
  const values=[0,0,3,3,0,0,2].map((v,i)=>({v,t:i*5000}));
  assert.deepEqual(nonZeroSegments(values,x=>x.v,x=>x.t,20000),
    [[1,2],[2,3],[3,4],[5,6]]);
  assert.deepEqual(nonZeroSegments([{v:2,t:0},{v:3,t:60000}],
    x=>x.v,x=>x.t,20000),[]);
});

test('CPU zero samples remain valid 0 percent in its own chart, without any mixed request axis',()=>{
  const values=[{v:0,t:0},{v:0,t:60000},{v:0,t:120000}];
  assert.deepEqual(nonZeroSegments(values,x=>x.v,x=>x.t,150000),[]);
  assert.match(overview,/online && rows\.map\(\(point,index\)=>/);
  assert.match(overview,/online && cpuSegments\.map/);
  assert.match(overview,/function CpuTrendPanel\(/);
  assert.match(overview,/cpuSegments\.push\(\[index-1,index\]\)/);
  assert.match(css,/\.rev-ops-split-charts \.rev-chart-legend \.rev-ops-cpu-legend/);
  assert.match(css,/\.rev-ops-split-charts \.rev-ops-cpu-readout/);
});

test('missing values, backward timestamps and large gaps are never colored as real measurements',()=>{
  const points=[{v:2,t:0},{v:null,t:5000},{v:3,t:10000},{v:4,t:0},{v:5,t:200000}];
  const read=x=>x.v===null?NaN:x.v;
  assert.deepEqual(nonZeroSegments(points,read,x=>x.t,20000),[]);
  assert.deepEqual(nonZeroSegments([{v:0,t:0},{v:5,t:60000},{v:0,t:120000}],
    x=>x.v,x=>x.t,150000),[[0,1],[1,2]]);
  assert.deepEqual(nonZeroSegments([{v:0,t:0},{v:0,t:0}],x=>x.v),[]);
});

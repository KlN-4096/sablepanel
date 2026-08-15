import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/web/js/preview/spm2.js', import.meta.url), 'utf8');
const context = {TextDecoder, Uint8Array, Uint16Array, Uint32Array, ArrayBuffer, DataView, JSON, Error, Object, Math, globalThis:{}};
context.globalThis = context;
vm.runInNewContext(source, context);
const parse = context.SablePreviewSpm2.parse;

function encode(recordBytes, records, metadata) {
  const text = new TextEncoder().encode(JSON.stringify(metadata));
  const padded = (text.length + 3) & ~3;
  const shell = new Uint8Array(Math.ceil(records.length / 4 / 8));
  const payload = padded + records.length * (recordBytes / 4) + shell.length;
  const bytes = new ArrayBuffer(32 + payload), view = new DataView(bytes);
  new Uint8Array(bytes, 0, 4).set([83,80,77,50]);
  view.setUint16(4, 2, true); view.setUint16(6, 1, true); view.setUint32(8, 32, true);
  view.setUint32(12, text.length, true); view.setUint32(16, records.length / 4, true);
  view.setUint32(20, shell.length, true); view.setUint16(24, recordBytes, true); view.setUint32(28, payload, true);
  new Uint8Array(bytes, 32, text.length).set(text);
  const offset = 32 + padded;
  for (let i = 0; i < records.length; i++) recordBytes === 8
    ? view.setUint16(offset + i * 2, records[i], true)
    : view.setUint32(offset + i * 4, records[i], true);
  return bytes;
}

const states = Array.from({length:5}, (_, index) => ({id:'test:' + index}));
const u16 = parse(encode(8, [1,2,3,4], {states, voxel_count:1, width:2, height:3, depth:4}));
assert.equal(u16.voxelCount, 1);
assert.deepEqual(Array.from(u16.records.subarray(0, 4)), [1,2,3,4]);
assert.equal(u16.isShell(0), false);

const u32 = parse(encode(16, [70000,2,3,0],
  {states:[{id:'test:wide'}], voxel_count:1, width:70001, height:3, depth:4}));
assert.deepEqual(Array.from(u32.records.subarray(0, 4)), [70000,2,3,0]);

const bad = new Uint8Array(encode(8, [1,2,3,4],
  {states, voxel_count:1, width:2, height:3, depth:4}));
bad[0] = 0;
assert.throws(() => parse(bad.buffer), /magic/);
bad[0] = 83; bad[31] = 1;
assert.throws(() => parse(bad.buffer), /长度/);
assert.throws(() => parse(encode(8, [2,2,3,4],
  {states, voxel_count:1, width:2, height:3, depth:4})), /越界/);
const tooMany = encode(8, [], {states:[], voxel_count:0, width:0, height:0, depth:0});
new DataView(tooMany).setUint32(16, 400001, true);
assert.throws(() => parse(tooMany), /体素数量/);

/* 旋转组(轴承上的 Create contraption):体素本身存装配姿态,角度走 metadata,
   由 runtime 逐实例乘一个绕轴矩阵。组区间必须有序不重叠 —— 一个体素落进两个组的话,
   施加哪个矩阵没有确定答案,画面会随 Map 迭代顺序漂。 */
const spun = (groups) => encode(8, [1,2,3,0, 1,2,3,0, 1,2,3,0],
  {states, voxel_count:3, width:2, height:3, depth:4, groups});
const okGroup = {first:1, count:2, pivot:[1,2,3], axis:'y', angle:-155.78};
assert.equal(parse(spun([okGroup])).metadata.groups[0].angle, -155.78);
assert.equal(parse(spun([])).metadata.groups.length, 0);
assert.throws(() => parse(spun([{...okGroup, first:2, count:2}])), /区间/, '越过体素总数必须拒绝');
assert.throws(() => parse(spun([{...okGroup, first:0, count:0}])), /区间/, '空组必须拒绝');
assert.throws(() => parse(spun([{first:0, count:2, pivot:[0,0,0], axis:'y', angle:0},
  {first:1, count:2, pivot:[0,0,0], axis:'y', angle:0}])), /区间/, '相互重叠必须拒绝');
assert.throws(() => parse(spun([{...okGroup, axis:'w'}])), /轴或角度/);
assert.throws(() => parse(spun([{...okGroup, angle:'x'}])), /轴或角度/);
assert.throws(() => parse(spun([{...okGroup, pivot:[1,2]}])), /pivot/);
console.log('SPM2 JS checks passed');

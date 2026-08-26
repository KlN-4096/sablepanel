import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/web/js/preview/model-worker.js', import.meta.url), 'utf8');
const encoder = new TextEncoder();
const files = new Map([
  ['assets/minecraft/blockstates/stone.json', encoder.encode('{"variants":{"":{"model":"minecraft:block/stone"}}}')],
  ['assets/minecraft/models/block/stone.json', encoder.encode('{"textures":{"all":"minecraft:block/stone"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"north":{"texture":"#all"},"south":{"texture":"#all"},"east":{"texture":"#all"},"west":{"texture":"#all"},"up":{"texture":"#all"},"down":{"texture":"#all"}}}]}')],
  ['assets/minecraft/textures/block/stone.png', encoder.encode('not-a-png')]
]);
const shard = Buffer.concat([...files.values()].map(value => Buffer.from(value)));
const entries = [];
let offset = 0;
for (const [path, value] of files) {
  const bytes = Buffer.from(value);
  entries.push({path, sha256:crypto.createHash('sha256').update(bytes).digest('hex'), size:bytes.length,
    shard:crypto.createHash('sha256').update(shard).digest('hex'), offset, length:bytes.length, layer:'minecraft'});
  offset += bytes.length;
}
const manifest = {version:2, fingerprint:'a'.repeat(64), entries};
const responses = {
  manifest: {ok:true,status:200,json:async()=>manifest},
  shard: {ok:true,status:200,arrayBuffer:async()=>shard.buffer.slice(shard.byteOffset, shard.byteOffset + shard.byteLength)}
};
const posted = [];
const canvasTransforms = [];
class FakeOffscreenCanvas {
  constructor(width, height) { this.width = width; this.height = height; }
  getContext() {
    return {
      clearRect(){}, drawImage(){},
      translate(x,y){ canvasTransforms.push(['translate',x,y]); },
      scale(x,y){ canvasTransforms.push(['scale',x,y]); },
      getImageData:(_x,_y,width,height) => {
        const data = new Uint8ClampedArray(width * height * 4);
        for (let index = 3; index < data.length; index += 4) data[index] = 255;
        return {data};
      }
    };
  }
  transferToImageBitmap() { return {width:this.width, height:this.height}; }
}
let fetchCount = 0, decodeCount = 0;
const sandbox = {
  self: {crypto:crypto.webcrypto, postMessage:value=>posted.push(value)},
  fetch: async url => { fetchCount++; return String(url).includes('/shard/') ? responses.shard : responses.manifest; },
  TextDecoder, TextEncoder, Uint8Array, Uint8ClampedArray, Uint16Array, Uint32Array, Float32Array, DataView,
  ArrayBuffer, Blob, Promise, Map, Set, Math, BigInt, Number, JSON, Object, String, Error,
  createImageBitmap: async () => { decodeCount++; return {width:16, height:16}; },
  OffscreenCanvas: FakeOffscreenCanvas,
  console, setTimeout, clearTimeout
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(source, sandbox, {filename:'model-worker.js'});
const identityAssemblyRotation = vm.runInContext('IDENTITY_CUBE_ROTATION', sandbox);
const loaded = await sandbox.loadResources('/api/preview/resources/' + 'b'.repeat(64) + '/manifest', '', '');
assert.equal(loaded.byteLength, shard.length);
const corruptedShard = Buffer.from(shard);
corruptedShard[0] ^= 1;
responses.shard.arrayBuffer = async() => corruptedShard.buffer.slice(
  corruptedShard.byteOffset, corruptedShard.byteOffset + corruptedShard.byteLength);
await assert.rejects(
  () => sandbox.loadResources('/api/preview/resources/' + 'b'.repeat(64) + '/manifest', '', ''),
  /分片哈希/, 'Worker 必须拒绝与内容地址不一致的资源分片');
responses.shard.arrayBuffer = async() => shard.buffer.slice(shard.byteOffset, shard.byteOffset + shard.byteLength);
const badEntryManifest = {...manifest, entries:manifest.entries.map((entry, index) =>
  index ? entry : {...entry, sha256:'0'.repeat(64)})};
await assert.rejects(
  () => sandbox.loadResources('/api/preview/resources/' + 'b'.repeat(64) + '/manifest', '', '',
    Infinity, badEntryManifest),
  /文件哈希/, 'Worker 必须拒绝清单中文件摘要与切片内容不一致的资源');
await assert.rejects(() => sandbox.loadResources('/manifest', '', '', Infinity,
  {...manifest, version:1}), /协议版本/, 'Worker 必须拒绝不兼容的资源协议');
await assert.rejects(() => sandbox.loadResources('/manifest', '', '', Infinity,
  manifest, 'b'.repeat(64)), /指纹不一致/, '请求声明与清单资源指纹不一致时不得复用缓存');
assert.equal(loaded.files.get('assets/minecraft/blockstates/stone.json').buffer,
  loaded.files.get('assets/minecraft/models/block/stone.json').buffer,
  '同一分片中的文件必须共享已校验分片，不能再复制一整套字节');
await assert.rejects(
  () => sandbox.loadResources('/api/preview/resources/' + 'b'.repeat(64) + '/manifest', '', '', shard.length - 1),
  /内存预算/,
  '超预算闭包必须在下载资源分片前停止');
const noAtlas = await sandbox.packAtlases(
  [{path:'tiny', alpha:'solid', bitmap:{width:16, height:16}}], [], [],
  {atlasSize:512, textureBytes:1});
assert.equal(noAtlas.textures.length, 0, '图集页必须服从 GPU 字节预算');
const sharedBitmap = {width:16,height:16};
const layered = await sandbox.packAtlases(
  [{path:'shared',alpha:'solid',bitmap:sharedBitmap}],
  [{texture:'shared',renderType:'cutout',uvs:new Float32Array([0,0]),stateIndex:0},
    {texture:'shared',renderType:'translucent',uvs:new Float32Array([0,0]),stateIndex:0}], [],
  {atlasSize:512,textureBytes:8*1024*1024});
assert.equal(layered.textures.length, 2, '同一纹理的不同渲染层必须放进不同图集页');
assert.notEqual(layered.batches[0].texture, layered.batches[1].texture);
const prioritized = await sandbox.packAtlases([
  {path:'a-optional',alpha:'solid',bitmap:{width:500,height:500}},
  {path:'z-base',alpha:'solid',bitmap:{width:500,height:500}}
], [
  {key:'optional',texture:'a-optional',renderType:'solid',uvs:new Float32Array([0,0]),stateIndex:0,assembly:true},
  {key:'base',texture:'z-base',renderType:'solid',uvs:new Float32Array([0,0]),stateIndex:0,assembly:false}
], [], {atlasSize:512,textureBytes:2*1024*1024});
assert.equal(prioritized.batches.map(batch => batch.key).join(','), 'base',
  '图集预算只能容纳一张纹理时必须先保留静态外壳，不能让可选组装纹理抢占页面');
assert.ok(canvasTransforms.some(value => value[0] === 'translate' && value[2] === 512)
  && canvasTransforms.some(value => value[0] === 'scale' && value[1] === 1 && value[2] === -1),
  'ImageBitmap 图集必须在 Canvas 中预先纵向翻转');
const records = new Uint16Array([0,0,0,0,1,0,0,0]);
sandbox.self.onmessage({data:{type:'bake', manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'', server:'', resourceFingerprint:manifest.fingerprint, recordBytes:8, records:records.buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],origin_x:0,origin_y:0,origin_z:0,plot_x:0,plot_z:0,
    width:2,height:1,depth:1,biome_colors:{}}}});
await new Promise(resolve => setTimeout(resolve, 20));
assert.deepEqual(posted.map(value => value.type), ['bake_textures','bake_state','bake_done']);
const stateBatch = posted[1].batches[0];
assert.equal(stateBatch.instances.length, 6, '两处方块应共享一份几何');
assert.equal(stateBatch.positions.length, 24 * 3, '基础立方体只烘焙一次');
assert.match(stateBatch.texture, /^__atlas__\//);
assert.ok(stateBatch.uvs.every(value => value >= 0 && value <= 1));
assert.ok(posted[2].stats && posted[2].stats.timings
  && ['resources','geometry','decode','atlas'].every(key => typeof posted[2].stats.timings[key] === 'number'),
  'bake_done 必须带分段计时(缩略图队列的耗时诊断)');
/* worker 常驻复用契约(R7):同一 manifestUrl 的后续 bake 不再下载分片、不再解码纹理 ——
   跨 bake 共享缓存正是缩略图队列逐体渲染的提速来源。 */
const fetchesAfterFirstBake = fetchCount, decodesAfterFirstBake = decodeCount;

const enclosedRecords = new Uint16Array([
  1,1,1,0, 0,1,1,0, 2,1,1,0, 1,0,1,0, 1,2,1,0, 1,1,0,0, 1,1,2,0
]);
const enclosed = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:enclosedRecords.buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:3,height:3,depth:3,biome_colors:{}},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
assert.equal(enclosed.batches[0].instances.length, 6 * 3,
  '只有被六个已确认不透明完整立方体包围的实例可从场景中剔除');
assert.equal(fetchCount, fetchesAfterFirstBake, '同一资源指纹的第二次 bake 不得重新下载分片');

/* 多方块哑方块(CURATED_INVISIBLE):游戏里不渲染,预览既不画降级方块也不进简化清单。 */
const invisibleRecords = new Uint16Array([0,0,0,0]);
const invisibleBake = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:invisibleRecords.buffer,
  palette:[{id:'create:water_wheel_structure',state:'create:water_wheel_structure[facing=up]'}],
  metadata:{states:[{id:'create:water_wheel_structure',state:'create:water_wheel_structure[facing=up]'}],
    width:1,height:1,depth:1,biome_colors:{}},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
assert.equal(invisibleBake.batches.length + invisibleBake.fallback.length, 0, '哑方块不得产生任何几何或降级方块');
assert.equal(invisibleBake.simplified.length, 0, '哑方块不进简化清单');
assert.deepEqual([...invisibleBake.upgraded], [0],
  '哑方块必须算"升级成空"——runtime 只对 upgraded 名单撤半透明外壳占位盒,漏掉它外壳会永远留着');
assert.equal(decodeCount, decodesAfterFirstBake, '同一资源指纹的第二次 bake 不得重新解码纹理');
const lowBudgetUrl = '/api/preview/resources/' + 'd'.repeat(64) + '/manifest';
const lowBudgetCache = sandbox.sharedFor(lowBudgetUrl, '', manifest.fingerprint, 2);
lowBudgetCache.loaded = {files, byteLength:49 * 1024 * 1024, manifest};
await assert.rejects(() => sandbox.bake({manifestUrl:lowBudgetUrl, token:'', server:'',
  resourceFingerprint:manifest.fingerprint,recordBytes:8,records:new Uint16Array([0,0,0,0]).buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:1,height:1,depth:1,biome_colors:{}},
  budget:{mainMemoryBytes:64*1024*1024,gpuBytes:256*1024*1024,atlasSize:2048}}),
/内存预算/, '复用大闭包时仍必须服从当前 bake 的较低工作内存预算');
assert.equal(lowBudgetCache.loaded, null, '拒绝低预算 bake 时必须释放复用闭包，不能继续常驻超额字节');
/* 结构边界上的方块不能因为"邻居越界"被当成被包围。整数序号必须对越界返回哨兵值:
   (y*depth+z)*width+x 在 x=width 时正好等于下一行的 x=0,不判界就会绕回去。
   下面是一块 2x3x2 的实心石头 —— 宽只有 2,每个方块的 ±x 邻居必有一侧在界外,
   所以一个都不该被剔除;不判界的话中间四个会被误判为六面包围。 */
const solidRecords = new Uint16Array([
  0,0,0,0, 1,0,0,0, 0,0,1,0, 1,0,1,0,
  0,1,0,0, 1,1,0,0, 0,1,1,0, 1,1,1,0,
  0,2,0,0, 1,2,0,0, 0,2,1,0, 1,2,1,0
]);
const solid = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:solidRecords.buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:2,height:3,depth:2,biome_colors:{}},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
assert.equal(solid.batches[0].instances.length, 12 * 3,
  '结构边界上的方块不得因邻居坐标越界绕回而被剔除');

/* 旋转组(轴承上的 Create contraption)。同一份七体素十字,把最后一个划进组后:
   它自己得单独成批(整批共用一个绕轴矩阵),而且不能再充当遮挡体 —— 它转开之后
   中心那块就露出来了,拿装配姿态去剔除会在螺旋桨底下留一个洞。 */
const spun = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:enclosedRecords.slice().buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:3,height:3,depth:3,biome_colors:{},
    groups:[{first:6, count:1, pivot:[1,1,1], axis:'y', angle:90}]},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
const spunGroup = spun.batches.filter(batch => batch.group === 0);
const spunStatic = spun.batches.filter(batch => batch.group === -1);
assert.equal(spunGroup.length, 1, '旋转组必须单独成批,不能和静态方块混进同一批实例');
assert.equal(spunStatic.length, 1);
assert.deepEqual(Array.from(spunGroup[0].instances), [1, 1, 2], '组里必须正好是第 6 号体素');
assert.equal(spunStatic[0].instances.length, 6 * 3,
  '组内方块不得充当遮挡体 —— 中心那块因此不再被六面包围');

/* 跨闭包资产复用(R7,2026-08-15 实测教训):资源闭包按体定制,杂类体每体换一个
   manifestUrl。分片布局每闭包不同(要重下),但 blockstate/模型/纹理内容来自同一
   资源栈 —— JSON 解析、模型烘焙、纹理解码必须跨闭包命中,不得随闭包换代清空。 */
{
  const decodesBefore = decodeCount;
  const other = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'c'.repeat(64) + '/manifest',
    token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,
    records:new Uint16Array([0,0,0,0]).buffer,
    palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
    metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:1,height:1,depth:1,biome_colors:{}},
    budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
  assert.equal(other.batches.length, 1, '新闭包的 bake 必须正常出几何');
  assert.equal(decodeCount, decodesBefore, '换闭包不得重新解码纹理 —— 位图缓存按 path 全局累积');
}

/*
 * blockstate 的 x/y 旋转方向。判据不来自矩阵代数,来自这些数字在实际 blockstate 里的含义:
 *   create:shaft   axis=x → {x:90, y:90}    竖着的基础模型必须变成沿 +X 的杆
 *   create:shaft   axis=z → {x:90, y:180}   必须变成沿 +Z 的杆
 *   minecraft:furnace facing=east → {y:90}  炉口(基础模型朝北)必须变成朝东
 * 转成逆旋转时,纯 y 的情形里 0°/180° 照样对、90°/270° 互换 —— 四个朝向仍然"各不相同",
 * 所以只看"它们不一样"的检查抓不住;必须钉具体落点。
 */
const rotated = (rx, ry, direction) => {
  const face = {positions:[], normals:[], uvs:[], colors:[], indices:[]};
  sandbox.addFace(face, {normal:[0,1,0], corners:[
    {position:direction,uv:[0,0]}, {position:[0,0,0],uv:[1,0]}, {position:[0,0,0],uv:[0,1]}
  ]}, sandbox.matrix(0, 0, 0, rx, ry), null);
  return Array.from(face.positions).slice(0, 3).map(value => Math.round(value) || 0);
};
assert.deepEqual(rotated(90, 90, [0,1,0]), [1,0,0], 'shaft axis=x 必须沿 +X 躺下');
assert.deepEqual(rotated(90, 180, [0,1,0]), [0,0,1], 'shaft axis=z 必须沿 +Z 躺下');
assert.deepEqual(rotated(0, 90, [0,0,-1]), [1,0,0], 'furnace facing=east:朝北的炉口必须转到朝东');
assert.deepEqual(rotated(0, 270, [0,0,-1]), [-1,0,0], 'facing=west 反过来必须朝西');
assert.deepEqual(rotated(0, 180, [0,0,-1]), [0,0,1], '180° 是自逆的,方向必须翻到南');

const triangle = {positions:[], normals:[], uvs:[], colors:[], indices:[]};
const identity = new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
const count = sandbox.addFace(triangle, {normal:[0,1,0], corners:[
  {position:[0,0,0],uv:[0,0]}, {position:[1,0,0],uv:[1,0]}, {position:[0,1,0],uv:[0,1]}
]}, identity, null);
assert.equal(count, 1);
assert.deepEqual(Array.from(triangle.indices), [0,1,2], 'OBJ 三角面不能生成不存在的第四顶点');
const invalidObjFiles = new Map([['assets/test/models/block/bad.obj', encoder.encode(
  'v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nv .5 .5 1\nf 1 2 3 4 5\n')]]);
assert.equal(sandbox.bakeObj(invalidObjFiles,
  {loader:'neoforge:obj', model:'test:models/block/bad.obj'}, {modelId:'test:block/bad'}), null,
  '五边形以上必须局部拒绝，不能静默三角化');
invalidObjFiles.set('assets/test/models/block/bad.obj', encoder.encode('v NaN 0 0\nf 1 1 1\n'));
assert.equal(sandbox.bakeObj(invalidObjFiles,
  {loader:'neoforge:obj', model:'test:models/block/bad.obj'}, {modelId:'test:block/bad'}), null,
  'NaN 顶点必须局部拒绝');
const objFiles = new Map([
  ['assets/test/models/block/fixture.json', encoder.encode('{"parent":"test:block/parent","loader":"neoforge:obj","model":"test:models/block/fixture.obj","flip_v":true}')],
  ['assets/test/models/block/parent.json', encoder.encode('{"textures":{"0":"test:block/brass"}}')],
  ['assets/test/models/block/fixture.obj', encoder.encode('mtllib fixture.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvc .5 .6 .7\nvt 0 0\nvt 1 0\nvt 0 1\nusemtl body\nf 1/1//1 2/2//1 3/3//1\n')],
  ['assets/test/models/block/fixture.mtl', encoder.encode('newmtl body\nmap_Kd #0\n')]
]);
const objFaces = sandbox.bakeObj(objFiles,
  {parent:'test:block/parent',loader:'neoforge:obj',model:'test:models/block/fixture.obj',flip_v:true},
  {modelId:'test:block/fixture'});
assert.equal(objFaces[0].texturePath, 'assets/test/textures/block/brass.png',
  'OBJ 的 #纹理槽必须沿标准模型父链解析');
assert.deepEqual(Array.from(objFaces[0].corners[0].position), [-.5,-.5,-.5],
  'OBJ 坐标按 NeoForge 原始块坐标解释，不能启发式缩放或偏移');
assert.deepEqual(Array.from(objFaces[0].corners[0].color), [.5,.6,.7]);
const visibleObj = new Map([
  ['assets/test/models/block/visible.obj', encoder.encode(
    'v 0 0 0\nv 1 0 0\nv 0 1 0\ng hidden\nf 1 2 3\ng shown\nf 1 2 3\n')]
]);
const visibleFaces = sandbox.bakeObj(visibleObj,
  {loader:'neoforge:obj',model:'test:models/block/visible.obj',visibility:{hidden:false},shade_quads:false},
  {modelId:'test:block/visible'});
assert.equal(visibleFaces.length, 1, 'OBJ visibility=false 必须只隐藏对应静态部件');
assert.equal(visibleFaces[0].shade, false);
const transformedFaces = sandbox.facesFromModel({textures:{all:'test:block/brass'},
  transform:{translation:[1,0,0],origin:'corner'}, ambientOcclusion:false,
  elements:[{from:[0,0,0],to:[16,16,16],shade:false,faces:{north:{texture:'#all'}}}]});
assert.equal(Math.min(...transformedFaces[0].corners.map(corner => corner.position[0])), .5,
  'NeoForge 根变换必须在模型首次烘焙时应用');
assert.equal(transformedFaces[0].shade, false);
assert.equal(transformedFaces[0].ambientOcclusion, false);
const compositeFaces = sandbox.bakeComposite(new Map(), {
  loader:'neoforge:composite', textures:{all:'test:block/brass'}, visibility:{hidden:false},
  children:{
    hidden:{textures:{all:'test:block/brass'},elements:[{from:[0,0,0],to:[16,16,16],faces:{north:{texture:'#all'}}}]},
    malformed:null,
    shown:{textures:{all:'test:block/brass'},elements:[{from:[0,0,0],to:[16,16,16],faces:{south:{texture:'#all'}}}]},
    broken:{loader:'test:unknown'}
  }
}, {modelId:'test:block/composite'});
assert.equal(compositeFaces.length, 1, 'Composite visibility=false 必须跳过命名子模型');
assert.equal(compositeFaces.partial, true, '单个 Composite 子项失败时保留其他子项并标记部分简化');
assert.equal(sandbox.modelFailureReason(new Map([
  ['assets/test/models/block/unknown.json', encoder.encode('{"loader":"test:private"}')]
]), [{model:'test:block/unknown'}], false), 'unknown_loader');

/* 动态 partial 不在 blockstate 的资源图里，但完整物品模型常把静态外壳与这些部件组装在一起。
   当前状态故意选择与物品基准不同的 end 外壳：补全逻辑必须从全部 blockstate 模型里找到
   可证明的 single 基准，只叠加物品模型多出的越界横杆，不能把 single 外壳替换回来。 */
const assemblyElement = (from, to) => ({from,to,faces:{east:{texture:'#all'},west:{texture:'#all'}}});
const assemblyShell = [
  assemblyElement([0,0,0],[2,16,16]), assemblyElement([14,0,0],[16,16,16]),
  assemblyElement([2,0,0],[14,2,16]), assemblyElement([2,14,0],[14,16,16]),
  assemblyElement([0,2,0],[3,6,5])
];
const normalRotor = [
  assemblyElement([16,3,3],[24,5,5]), assemblyElement([16,6,6],[24,8,8]),
  assemblyElement([16,9,9],[22,11,12]), assemblyElement([16,12,12],[21,16,14])
];
const reversedRotor = [
  assemblyElement([-8,3,3],[0,5,5]), assemblyElement([-8,6,6],[0,8,8]),
  assemblyElement([-6,9,9],[0,11,12]), assemblyElement([-5,12,12],[0,16,14])
];
const activeRotor = [
  assemblyElement([3,3,16],[5,5,24]), assemblyElement([6,6,16],[8,8,24]),
  assemblyElement([9,9,16],[12,11,22]), assemblyElement([12,12,16],[14,16,21])
];
const rotorFaces = names => Object.fromEntries(names.map(name => [name, {texture:'#all'}]));
const proxyRotor = elements => elements.map(element => ({...element,
  faces:rotorFaces(['down','up','north','south','west','east'])}));
const exactRotor = elements => elements.map(element => ({...element,
  faces:rotorFaces(['down','up','north','south','east'])}));
const assembledFiles = new Map([
  ['assets/test/blockstates/assembled.json', encoder.encode(JSON.stringify({variants:{
    'part=single':{model:'test:block/assembled/single'}, 'part=end':{model:'test:block/assembled/end'}
  }}))],
  ['assets/test/models/block/assembled/single.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:assemblyShell.concat([assemblyElement([2,2,0],[14,14,16])])
  }))],
  ['assets/test/models/block/assembled/end.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:assemblyShell.concat([assemblyElement([2,2,0],[14,12,16])])
  }))],
  ['assets/test/models/item/assembled.json', encoder.encode('{"parent":"test:block/assembled/item"}')],
  ['assets/test/models/block/assembled/item.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:assemblyShell
      .concat([assemblyElement([2,2,0],[14,14,16])], proxyRotor(normalRotor))
  }))],
  ['assets/test/models/block/assembled/rotor.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:exactRotor(normalRotor)
  }))],
  ['assets/test/models/block/assembled/rotor_reversed.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:exactRotor(reversedRotor)
  }))],
  ['assets/test/models/block/assembled/rotor_active.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:exactRotor(activeRotor)
  }))]
]);
const assembledState = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'test:assembled',state:'test:assembled[part=end]'}], {files:assembledFiles},
  id => sandbox.bakeModel(assembledFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
const assembledMaxX = Math.max(...[...assembledState.batches.values()].flatMap(batch => batch.positions));
assert.ok(assembledMaxX > .5,
  '可证明为物品模型补充几何的越界部件必须叠加到当前 blockstate 外壳，而不是静默丢失');
assert.equal(sandbox.assemblyFaces(assembledFiles, 'test:assembled',
  'test:block/assembled/end', 'test:assembled[part=end]')?.length, 20,
  '默认状态也必须采用 sibling 的精确面，不能继续复用物品代理里多出来的内部面');
const reversedAssembly = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'test:assembled',state:'test:assembled[part=end,reversed=true]'}], {files:assembledFiles},
  id => sandbox.bakeModel(assembledFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
const reversedMinX = Math.min(...[...reversedAssembly.batches.values()].flatMap(batch => batch.positions));
assert.ok(reversedMinX < -.5,
  '同拓扑 sibling 模型名与布尔状态属性一致时，必须选择反向静态部件而非普通 item 代理');
assert.equal(sandbox.assemblyFaces(assembledFiles, 'test:assembled', 'test:block/assembled/end',
  'test:assembled[active=true,part=end,reversed=true]'), null,
  '多个状态属性同时命中不同静态部件时必须拒绝歧义，不能按属性名顺序猜一个');
/* 拆除全覆盖前置闸的回归:blockstate 里存在一个对不上的小变体(alien)时,
   参考推导不得被一票否决——对得上的变体照常组装,小变体自己返回 null 只渲染壳。
   tiny 走"小于最小匹配数→全元素精确匹配"的放宽分支。 */
const gateFiles = new Map([
  ['assets/test/blockstates/gate.json', encoder.encode(JSON.stringify({variants:{
    'part=full':{model:'test:block/gate/full'}, 'part=tiny':{model:'test:block/gate/tiny'},
    'part=alien':{model:'test:block/gate/alien'}
  }}))],
  ['assets/test/models/block/gate/full.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:assemblyShell
  }))],
  ['assets/test/models/block/gate/tiny.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:[assemblyShell[0], assemblyShell[4]]
  }))],
  ['assets/test/models/block/gate/alien.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:[assemblyElement([5,5,5],[9,9,9])]
  }))],
  ['assets/test/models/item/gate.json', encoder.encode('{"parent":"test:block/gate/item"}')],
  ['assets/test/models/block/gate/item.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'}, elements:assemblyShell.concat(proxyRotor(normalRotor))
  }))]
]);
assert.ok(sandbox.inferAssemblyReference(gateFiles, 'test:gate'),
  '存在对不上的小变体时参考推导不得被整体否决');
assert.equal(sandbox.assemblyFaces(gateFiles, 'test:gate', 'test:block/gate/full',
  'test:gate[part=full]')?.length, 24, '对得上的变体必须拿到物品模型补充的内部件');
assert.equal(sandbox.assemblyFaces(gateFiles, 'test:gate', 'test:block/gate/tiny',
  'test:gate[part=tiny]')?.length, 24, '小于最小匹配数的变体按全元素精确匹配放行');
assert.equal(sandbox.assemblyFaces(gateFiles, 'test:gate', 'test:block/gate/alien',
  'test:gate[part=alien]'), null, '对不上的变体自己返回 null,不组装也不报错');

const uncertainFiles = new Map([
  ['assets/test/blockstates/uncertain.json', encoder.encode('{"variants":{"":{"model":"test:block/uncertain/block"}}}')],
  ['assets/test/models/block/uncertain/block.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'},elements:[assemblyElement([0,0,0],[16,16,16])]}))],
  ['assets/test/models/item/uncertain.json', encoder.encode('{"parent":"test:block/uncertain/item"}')],
  ['assets/test/models/block/uncertain/item.json', encoder.encode(JSON.stringify({
    textures:{all:'test:block/assembled'},elements:normalRotor}))]
]);
assert.equal(sandbox.assemblyFaces(uncertainFiles, 'test:uncertain', 'test:block/uncertain/block',
  'test:uncertain'), null, '物品模型无法高置信包含 blockstate 外壳时不得猜测补充几何');
const unrelatedTextureBase = [
  {...assemblyElement([0,0,0],[2,3,4]),faces:{up:{texture:'#base'}}},
  {...assemblyElement([3,0,0],[6,5,7]),faces:{up:{texture:'#base'}}},
  {...assemblyElement([0,6,1],[4,9,8]),faces:{up:{texture:'#base'}}},
  {...assemblyElement([8,2,3],[15,6,10]),faces:{up:{texture:'#base'}}}
];
const unrelatedTextureFiles = new Map([
  ['assets/test/blockstates/unrelated_texture.json', encoder.encode(
    '{"variants":{"":{"model":"test:block/unrelated_texture/block"}}}')],
  ['assets/test/models/block/unrelated_texture/block.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/block_base'},elements:unrelatedTextureBase
  }))],
  ['assets/test/models/item/unrelated_texture.json', encoder.encode(
    '{"parent":"test:block/unrelated_texture/item"}')],
  ['assets/test/models/block/unrelated_texture/item.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/item_base',detail:'test:block/detail'},
    elements:unrelatedTextureBase.map(element => ({...element,faces:{up:{texture:'#base'}}}))
      .concat([{...assemblyElement([6,6,-4],[10,10,20]),faces:{east:{texture:'#detail'}}}])
  }))]
]);
assert.equal(sandbox.assemblyFaces(unrelatedTextureFiles, 'test:unrelated_texture',
  'test:block/unrelated_texture/block', 'test:unrelated_texture'), null,
  '外壳只有几何相同但材质/面语义完全不相干时不得把物品独有元素叠到方块上');
const vanillaAssemblyFiles = new Map([
  ['assets/minecraft/blockstates/preview_machine.json', encoder.encode(JSON.stringify({variants:{
    '':{model:'minecraft:block/preview_machine/base'}
  }}))],
  ['assets/minecraft/models/block/preview_machine/base.json', encoder.encode(JSON.stringify({
    textures:{all:'minecraft:block/stone'},elements:assemblyShell.slice(0,4)
  }))],
  ['assets/minecraft/models/item/preview_machine.json', encoder.encode(
    '{"parent":"minecraft:block/preview_machine/item"}')],
  ['assets/minecraft/models/block/preview_machine/item.json', encoder.encode(JSON.stringify({
    textures:{all:'minecraft:block/stone'},elements:assemblyShell.slice(0,4)
      .concat([assemblyElement([6,6,-4],[10,10,20])])
  }))]
]);
assert.ok(sandbox.assemblyFaces(vanillaAssemblyFiles, 'minecraft:preview_machine',
  'minecraft:block/preview_machine/base', 'minecraft:preview_machine')?.length,
  '通用静态组装推导不能按 minecraft namespace 整体跳过');
const duplicateBase = {...assemblyElement([0,0,0],[4,4,4]),faces:{up:{texture:'#base'}}};
const duplicateOptional = {...assemblyElement([0,0,0],[4,4,4]),faces:{up:{texture:'#optional'}}};
const duplicateMatch = sandbox.matchAssemblyElements([duplicateBase, duplicateOptional], [duplicateBase],
  identityAssemblyRotation);
assert.deepEqual([...duplicateMatch.sourceIndices], [0],
  '重复几何必须按面语义匹配，不能任意把基础元素留成补充层');
assert.equal(sandbox.matchAssemblyElements([duplicateBase, duplicateOptional], [
  {...assemblyElement([0,0,0],[4,4,4]),faces:{up:{texture:'#third'}}}
], identityAssemblyRotation), null,
'重复几何的面语义互相冲突且目标无唯一匹配时必须拒绝');
const adversarialSource = Array.from({length:64}, () => assemblyElement([0,0,0],[1,1,1]));
const adversarialTarget = Array.from({length:256}, () => assemblyElement([0,0,0],[1,1,1]));
const alignmentStarted = Date.now();
assert.equal(sandbox.bestTranslatedAssemblyAlignment(adversarialSource, adversarialTarget, 1), null,
  '候选平移工作量超过固定上限时必须保守拒绝');
assert.ok(Date.now() - alignmentStarted < 2000, '恶意重复元素不能把一次组装匹配拖到秒级以上');
const componentFiles = new Map([
  ['assets/test/blockstates/component_machine.json', encoder.encode(
    '{"variants":{"":{"model":"test:block/component_machine/block"}}}')],
  ['assets/test/models/block/component_machine/block.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:[assemblyElement([0,0,0],[16,4,16])]}))],
  ['assets/test/models/item/component_machine.json', encoder.encode(
    '{"parent":"test:block/component_machine/item"}')],
  ['assets/test/models/block/component_machine/item.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base',part:'test:block/part'},elements:[
      {...assemblyElement([0,0,0],[16,5,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([0,5,0],[2,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([14,5,0],[16,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([2,14,0],[14,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([4,4,4],[11,20,12]),faces:{up:{texture:'#part'}}}
    ]}))],
  ['assets/test/models/block/component_machine/arm.json', encoder.encode(JSON.stringify({
    textures:{part:'test:block/part'},elements:[{...assemblyElement([4,4,4],[11,20,12]),faces:{up:{texture:'#part'}}}]
  }))]
]);
const componentReference = sandbox.inferAssemblyReference(componentFiles, 'test:component_machine');
assert.deepEqual([...componentReference.components].map(value => value.modelId),
  ['test:block/component_machine/arm'], '外壳几何不相同时仍可由物品模型内的精确 sibling 子模型补全');
componentFiles.set('assets/test/models/block/component_machine/complete.json', encoder.encode(JSON.stringify({
  textures:{base:'test:block/base',part:'test:block/part'},elements:[
    {...assemblyElement([0,0,0],[1,1,1]),faces:{up:{texture:'#base'}}},
    {...assemblyElement([4,4,4],[11,20,12]),faces:{up:{texture:'#part'}}}
  ]
})));
assert.equal(sandbox.assemblyFaces(componentFiles, 'test:component_machine',
  'test:block/component_machine/complete', 'test:component_machine')?.length, 0,
  '当前静态模型已经包含可证明部件时必须视为已处理，不能重复叠加同一组件');
const rolePartHorizontal = {...assemblyElement([16,4,4],[24,12,12]),faces:{up:{texture:'#part'}}};
const rolePartVertical = {...assemblyElement([4,16,4],[12,24,12]),faces:{up:{texture:'#part'}}};
const roleFiles = new Map([
  ['assets/test/blockstates/role_machine.json', encoder.encode(
    '{"variants":{"":{"model":"test:block/role_machine/horizontal"}}}')],
  ['assets/test/models/block/role_machine/horizontal.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:[
      {...assemblyElement([0,0,0],[16,3,16]),faces:{up:{texture:'#base'}}}
    ]
  }))],
  ['assets/test/models/block/role_machine/vertical.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:[
      {...assemblyElement([0,0,0],[3,16,7]),faces:{up:{texture:'#base'}}}
    ]
  }))],
  ['assets/test/models/item/role_machine.json', encoder.encode(
    '{"parent":"test:block/role_machine/item"}')],
  ['assets/test/models/block/role_machine/item.json', encoder.encode(JSON.stringify({
    textures:{part:'test:block/part'},elements:[rolePartHorizontal,
      {...assemblyElement([2,2,2],[5,7,9]),faces:{up:{texture:'#part'}}}]
  }))],
  ['assets/test/models/block/role_machine/shaft_horizontal.json', encoder.encode(JSON.stringify({
    textures:{part:'test:block/part'},elements:[rolePartHorizontal]
  }))],
  ['assets/test/models/block/role_machine/shaft_vertical.json', encoder.encode(JSON.stringify({
    textures:{part:'test:block/part'},elements:[rolePartVertical]
  }))]
]);
const roleFaces = sandbox.assemblyFaces(roleFiles, 'test:role_machine',
  'test:block/role_machine/vertical', 'test:role_machine');
assert.ok(roleFaces?.length && Math.max(...roleFaces.flatMap(face =>
  face.corners.flatMap(corner => corner.position[1]))) > .5,
  '模型角色由 horizontal 切换为 vertical 时必须选择拓扑兼容的同名静态部件');
const conflictTarget = {...assemblyElement([2,3,4],[9,14,12]),faces:{
  up:{texture:'#a'},down:{texture:'#b'}
}};
const conflictFiles = new Map([
  ['assets/test/models/item/conflict.json', encoder.encode('{"parent":"test:block/conflict/item"}')],
  ['assets/test/models/block/conflict/item.json', encoder.encode(JSON.stringify({
    textures:{a:'test:block/a',b:'test:block/b'},elements:[conflictTarget]
  }))],
  ['assets/test/models/block/conflict/arm_a.json', encoder.encode(JSON.stringify({
    textures:{a:'test:block/a'},elements:[
      {...assemblyElement([2,3,4],[9,14,12]),faces:{up:{texture:'#a'}}}
    ]
  }))],
  ['assets/test/models/block/conflict/arm_b.json', encoder.encode(JSON.stringify({
    textures:{b:'test:block/b'},elements:[
      {...assemblyElement([2,3,4],[9,14,12]),faces:{up:{texture:'#b'}}}
    ]
  }))]
]);
const conflictItemJson = sandbox.modelJson(conflictFiles, 'test:item/conflict');
const conflictItem = sandbox.mergeModel(conflictFiles, 'test:item/conflict', 0, new Set());
assert.equal(sandbox.inferAssemblyComponents({files:conflictFiles, itemJson:conflictItemJson,
  item:conflictItem, targetElements:conflictItem.elements, excluded:new Set(), minimum:1,
  allowFull:true}).length, 0,
'同一目标几何的 sibling 候选输出不同材质时必须拒绝，不能按文件名选一个');
const compatibleBase = {transform:null,textures:{part:'test:block/part'},elements:[
  {...assemblyElement([0,0,0],[2,10,3]),faces:{up:{texture:'#part'}}}
]};
const incompatibleVariant = {transform:null,textures:{part:'test:block/part'},elements:[
  {...assemblyElement([0,0,0],[6,6,6]),faces:{up:{texture:'#part'}}}
]};
assert.equal(sandbox.compatibleAssemblyVariant(compatibleBase, incompatibleVariant), false,
  '状态后缀变体必须保留每个部件的形状拓扑，不能只比较元素/面/纹理数量');
const uvFace = sandbox.facesFromModel({textures:{all:'test:block/a'},elements:[
  {from:[0,0,0],to:[16,16,16],faces:{up:{texture:'#all',rotation:0}}}
]})[0];
const rotatedUvFace = sandbox.facesFromModel({textures:{all:'test:block/a'},elements:[
  {from:[0,0,0],to:[16,16,16],faces:{up:{texture:'#all',rotation:180}}}
]})[0];
assert.notEqual(sandbox.assemblyFaceSignature(uvFace), sandbox.assemblyFaceSignature(rotatedUvFace),
  '组装歧义签名必须包含角点 UV，不能把方向纹理的 0°/180° 当成同一结果');
const novelFiles = new Map([
  ['assets/test/blockstates/novel_machine.json', encoder.encode(
    '{"variants":{"":{"model":"test:block/novel_machine/block"}}}')],
  ['assets/test/models/block/novel_machine/block.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:[
      {...assemblyElement([0,0,0],[16,8,16]),faces:{up:{texture:'#base'}}}]}))],
  ['assets/test/models/item/novel_machine.json', encoder.encode('{"parent":"test:block/novel_machine/item"}')],
  ['assets/test/models/block/novel_machine/item.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base',detail:'test:block/detail'},elements:[
      {...assemblyElement([0,0,0],[16,9,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([0,9,0],[2,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([14,9,0],[16,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([2,14,0],[14,16,16]),faces:{up:{texture:'#base'}}},
      {...assemblyElement([6,8,6],[10,20,10]),faces:{up:{texture:'#detail'}}}
    ]}))]
]);
const novelReference = sandbox.inferAssemblyReference(novelFiles, 'test:novel_machine');
assert.ok(novelReference && !novelReference.components.length && novelReference.extras.length === 1,
  '单一 blockstate 模型可用物品模型中的新增纹理元素补全，但不得替换原外壳');
const relaxedBase = [
  assemblyElement([0,0,0],[2,16,16]), assemblyElement([2,0,0],[14,2,16]),
  assemblyElement([15,0,0],[16,16,16])
];
const relaxedFiles = new Map([
  ['assets/test/blockstates/relaxed_machine.json', encoder.encode(JSON.stringify({variants:{
    'mode=a':{model:'test:block/relaxed_machine/a'}, 'mode=b':{model:'test:block/relaxed_machine/b'}
  }}))],
  ['assets/test/models/block/relaxed_machine/a.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:relaxedBase.concat([assemblyElement([2,2,0],[14,14,8])])}))],
  ['assets/test/models/block/relaxed_machine/b.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base'},elements:relaxedBase.concat([assemblyElement([2,2,8],[14,14,16])])}))],
  ['assets/test/models/item/relaxed_machine.json', encoder.encode('{"parent":"test:block/relaxed_machine/item"}')],
  ['assets/test/models/block/relaxed_machine/item.json', encoder.encode(JSON.stringify({
    textures:{base:'test:block/base',part:'test:block/part'},elements:relaxedBase
      .concat([assemblyElement([3,3,0],[13,13,8]),
        {...assemblyElement([6,6,-4],[10,10,20]),faces:{up:{texture:'#part'}}}])}))],
  ['assets/test/models/block/relaxed_machine/shaft.json', encoder.encode(JSON.stringify({
    textures:{part:'test:block/part'},elements:[
      {...assemblyElement([6,6,-4],[10,10,20]),faces:{up:{texture:'#part'}}}]
  }))]
]);
assert.ok(sandbox.assemblyFaces(relaxedFiles, 'test:relaxed_machine', 'test:block/relaxed_machine/b',
  'test:relaxed_machine[mode=b]')?.length,
  '精确 sibling 部件允许用较低但仍有界的外壳对齐覆盖多个 blockstate 模型');
const assemblyLimited = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'test:assembled',state:'test:assembled[part=end]'}], {files:assembledFiles},
  id => sandbox.bakeModel(assembledFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 28);
assert.ok(assemblyLimited && assemblyLimited.partial,
  '补充几何超出三角预算时必须只舍弃补充层并保留静态外壳');
assert.ok([...assemblyLimited.batches.values()].every(batch => !batch.assembly));
const assemblyGroupLimited = sandbox.bakeState([{x:0,y:0,z:0,g:-1},{x:1,y:0,z:0,g:-1}], 0,
  [{id:'test:assembled',state:'test:assembled[part=end]'}], {files:assembledFiles},
  id => sandbox.bakeModel(assembledFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 56);
assert.ok(assemblyGroupLimited && assemblyGroupLimited.partial
  && [...assemblyGroupLimited.batches.values()].every(batch => !batch.assembly),
  '同状态多实例中途超预算时必须撤掉先前实例的补充层，不能让可选层挤掉后续静态外壳');
assert.ok([...assemblyGroupLimited.batches.values()].every(batch => batch.instances.length === 6));
const baseBatch = {indices:[0,1,2],instances:[0,0,0],assembly:false};
const optionalBatch = {indices:[0,1,2],instances:[0,0,0],assembly:true};
const baseOnly = sandbox.withoutAssembly({batches:new Map([['base',baseBatch],['assembly',optionalBatch]]),
  triangles:2,partial:false});
assert.deepEqual([...baseOnly.batches.keys()], ['base'],
  'draw call/工作内存不足时必须可整层去掉可选组装几何');
assert.equal(baseOnly.triangles, 1);
assert.equal(baseOnly.partial, true);
const waterloggedGrid = new Map([['0,0,0', {type:'water',height:8/9}]]);
const waterloggedPalette = [{id:'test:assembled',
  state:'test:assembled[part=end,waterlogged=true]'}];
const waterloggedFull = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0, waterloggedPalette,
  {files:assembledFiles}, id => sandbox.bakeModel(assembledFiles, id), new Map(), waterloggedGrid,
  {}, 0, 0, 0, 0, 10_000);
const waterloggedBase = sandbox.withoutAssembly(waterloggedFull);
const waterloggedLimited = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0, waterloggedPalette,
  {files:assembledFiles}, id => sandbox.bakeModel(assembledFiles, id), new Map(), waterloggedGrid,
  {}, 0, 0, 0, 0, waterloggedBase.triangles);
assert.ok(waterloggedLimited && !waterloggedLimited.failure && waterloggedLimited.partial
  && [...waterloggedLimited.batches.values()].every(batch => !batch.assembly),
'流体加入后超出预算时必须回滚可选组装层，保留静态模型与流体');

const stoneTexture = 'assets/minecraft/textures/block/stone.png';
const fakeBatch = (key, stateIndex, value, assembly) => ({
  key,texture:stoneTexture,renderType:'solid',emissive:false,shade:true,
  positions:[0,0,0,1,0,0,0,1,0],normals:[0,0,1,0,0,1,0,0,1],
  uvs:[0,0,1,0,0,1],colors:[1,1,1,1,1,1,1,1,1],indices:[0,1,2],
  instances:[value.x,value.y,value.z],faceKeys:new Set(),stateIndex,group:-1,assembly
});
const fakeResult = (values, stateIndex, includeAssembly) => {
  const batches = new Map(), base = fakeBatch('base-' + stateIndex, stateIndex, values[0], false);
  batches.set(base.key, base);
  if (includeAssembly) {
    const optional = fakeBatch('assembly-' + stateIndex, stateIndex, values[0], true);
    batches.set(optional.key, optional);
  }
  return {batches,triangles:[...batches.values()].reduce((sum,batch) =>
    sum + sandbox.batchTriangleCost(batch),0),texturePaths:new Set([stoneTexture]),
  count:values.length,partial:false,fullCube:false};
};
const realBakeState = sandbox.bakeState;
const budgetRecords = new Uint16Array([0,0,0,0,1,0,0,1]);
const budgetPalette = [
  {id:'minecraft:stone',state:'minecraft:stone'},
  {id:'minecraft:stone',state:'minecraft:stone'}
];
try {
  sandbox.__fakeBakeState = (...args) => {
    const values = args[0], stateIndex = args[1], currentTriangles = args[11];
    if (stateIndex === 0) return fakeResult(values, stateIndex, true);
    return currentTriangles > 1 ? {failure:'budget'} : fakeResult(values, stateIndex, false);
  };
  vm.runInContext('bakeState = __fakeBakeState', sandbox);
  const trianglePriority = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'c'.repeat(64) + '/manifest',
    token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:budgetRecords.slice().buffer,
    palette:budgetPalette,metadata:{states:budgetPalette,width:2,height:1,depth:1,biome_colors:{}},
    budget:{triangles:2,drawCalls:8,gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
  assert.deepEqual([...trianglePriority.upgraded].sort(), [0,1]);
  assert.ok(trianglePriority.batches.every(batch => !batch.assembly),
    '后续基础状态触及三角预算时必须回收此前可选组装层');

  sandbox.__fakeBakeState = (values, stateIndex) => fakeResult(values, stateIndex, stateIndex === 0);
  vm.runInContext('bakeState = __fakeBakeState', sandbox);
  const drawPriority = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'c'.repeat(64) + '/manifest',
    token:'',server:'',resourceFingerprint:manifest.fingerprint,recordBytes:8,records:budgetRecords.slice().buffer,
    palette:budgetPalette,metadata:{states:budgetPalette,width:2,height:1,depth:1,biome_colors:{}},
    budget:{triangles:100,drawCalls:2,gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
  assert.deepEqual([...drawPriority.upgraded].sort(), [0,1]);
  assert.ok(drawPriority.batches.every(batch => !batch.assembly),
    '后续基础状态触及 draw-call 预算时必须回收此前可选组装层');
} finally {
  sandbox.__realBakeState = realBakeState;
  vm.runInContext('bakeState = __realBakeState', sandbox);
  delete sandbox.__fakeBakeState; delete sandbox.__realBakeState;
}

const incompleteAssemblyFiles = new Map([...assembledFiles].filter(([path]) =>
  !path.includes('/models/item/') && !path.endsWith('/assembled/item.json') && !path.includes('/rotor')));
const cacheA = sandbox.sharedFor('/cache-a', '', manifest.fingerprint, 2);
cacheA.loaded = {files:incompleteAssemblyFiles,byteLength:0,manifest};
assert.equal(sandbox.assemblyFaces(incompleteAssemblyFiles, 'test:assembled',
  'test:block/assembled/end', 'test:assembled[part=end]'), null);
const cacheB = sandbox.sharedFor('/cache-b', '', manifest.fingerprint, 2);
cacheB.loaded = {files:assembledFiles,byteLength:0,manifest};
assert.ok(sandbox.assemblyFaces(assembledFiles, 'test:assembled',
  'test:block/assembled/end', 'test:assembled[part=end]')?.length,
'换到包含完整 sibling 的闭包后不能继续命中上一闭包缓存的 null 组装结果');
assert.equal(sandbox.modelTint({id:'minecraft:oak_leaves',state:'minecraft:oak_leaves'}, 0,
  {grass:0x112233,foliage:0x445566,water:0x778899}), 0x445566);
assert.equal(sandbox.modelTint({id:'test:custom_leaves',state:'test:custom_leaves'}, 0,
  {grass:1,foliage:2,water:3}), null, '未知模组 tint 不得套用原版猜测');
assert.notEqual(sandbox.modelTint({id:'minecraft:redstone_wire',state:'minecraft:redstone_wire[power=15]'}, 0, {}),
  sandbox.modelTint({id:'minecraft:redstone_wire',state:'minecraft:redstone_wire[power=0]'}, 0, {}));
const randomCases = [
  [0,0,0,'0',13,11],
  [1,64,2,'-117935115545999',7,2],
  [-17,255,300,'21552085071799',3,6],
  [12345,-20,-987,'-12599022851278',3,9]
];
for (const [x,y,z,seed,variant,multipart] of randomCases) {
  const actualSeed = sandbox.minecraftModelSeed(x,y,z);
  assert.equal(actualSeed.toString(), seed, '坐标种子必须与 Minecraft 1.21.1 的 Mth.getSeed 一致');
  assert.equal(sandbox.weightedIndex(actualSeed, 17, false), variant);
  assert.equal(sandbox.weightedIndex(actualSeed, 17, true), multipart);
}
const fluidGrid = new Map([
  ['0,0,0', {type:'water',height:8/9}],
  ['1,0,0', {type:'water',height:4/9}]
]);
const slopedFluid = sandbox.fluidFaces(
  {id:'minecraft:water',state:'minecraft:water[level=0]'}, {x:0,y:0,z:0}, fluidGrid);
assert.ok(slopedFluid.faces.some(face => face.texturePath.endsWith('water_flow.png')),
  '相邻液面高度不同时必须生成流动纹理斜坡');
const top = slopedFluid.faces.find(face => face.normal[1] > .5);
assert.ok(top && new Set(top.corners.map(corner => corner.position[1].toFixed(4))).size > 1,
  '流体顶面必须按相邻高度生成非水平四角');
/* 角点顺序就是交给 GPU 的三角绕序。THREE 的材质默认 side:FrontSide,
   法线必须跟实际绕序一致。Minecraft FaceBakery 也会从烘焙后的角点重算 Direction；
   因而 from > to 的元素不是坏数据，而是可用于生成内向面的合法模型语义。 */
function windingNormal(face) {
  const [a, b, c] = face.corners.map(corner => corner.position);
  const ab = [b[0]-a[0], b[1]-a[1], b[2]-a[2]], ac = [c[0]-a[0], c[1]-a[1], c[2]-a[2]];
  return [ab[1]*ac[2]-ab[2]*ac[1], ab[2]*ac[0]-ab[0]*ac[2], ab[0]*ac[1]-ab[1]*ac[0]];
}
function assertFacesFront(faces, label) {
  for (const face of faces) {
    const cross = windingNormal(face);
    const dot = cross[0]*face.normal[0] + cross[1]*face.normal[1] + cross[2]*face.normal[2];
    assert.ok(dot > 0, label + ' 的面绕序与法线 ' + JSON.stringify(face.normal) + ' 反向,会被背面剔除');
  }
}
const reversedExtentFaces = sandbox.facesFromModel({textures:{all:'minecraft:block/stone'},
  elements:[{from:[0,0,10],to:[16,16,6],
    faces:{east:{texture:'#all'},west:{texture:'#all'},up:{texture:'#all'},down:{texture:'#all'}}}]});
assert.equal(reversedExtentFaces.length, 4);
const declaredNormals = {east:[1,0,0],west:[-1,0,0],up:[0,1,0],down:[0,-1,0]};
for (const face of reversedExtentFaces) {
  const declared = declaredNormals[face.direction];
  assert.ok(declared, 'direction 必须保留 JSON 声明面；FaceBakery 在重算法线前先用它处理 uvlock');
  assert.ok(face.normal.reduce((sum, value, axis) => sum + value * declared[axis], 0) < 0,
    '反向坐标元素必须像 Minecraft FaceBakery 一样保留内向面，不能强制翻回声明方向');
}
assertFacesFront(reversedExtentFaces, '反向坐标元素');
/* 每个面的 u/v 轴方向必须与同文件 defaultFaceUv 声明的原版约定一致。
   基准锚点是真机实测出来的:把 torch 渲染出来逐像素量,火焰(纹理 y=5..8,即 PNG 顶部)
   出现在屏幕下半部 —— 即 uv.y=0 取到的是 PNG 底行,所以 PNG 顶行对应 uv.y=1。
   由此把 defaultFaceUv 的六个矩形反解成"角点位置 → 期望 uv",全部是恒等式:
     south [x,y]   north [16-x,y]   west [z,y]   east [16-z,y]
     up    [x,16-z]  down [x,z]     (坐标均为 0..16 的元素内坐标)
   改前 down/north/south/west/east 五个面都不满足 —— 表现就是火把、草方块侧面上下颠倒。 */
const UV_RULE = {
  south:(x, y, z) => [x, y], north:(x, y, z) => [16 - x, y],
  west:(x, y, z) => [z, y], east:(x, y, z) => [16 - z, y],
  up:(x, y, z) => [x, 16 - z], down:(x, y, z) => [x, z]
};
for (const [name, rule] of Object.entries(UV_RULE)) {
  const faces = sandbox.facesFromModel({textures:{all:'minecraft:block/stone'},
    elements:[{from:[0,0,0], to:[16,16,16], faces:{[name]:{texture:'#all'}}}]});
  assert.equal(faces.length, 1, name + ' 面必须烘焙出来');
  for (const corner of faces[0].corners) {
    const [x, y, z] = corner.position.map(value => Math.round((value + .5) * 16));
    const [eu, ev] = rule(x, y, z);
    /* 比较对象必须在宿主侧用字面量造:vm 里的数组用的是沙箱自己的 intrinsic Array,
       原型对不上,deepStrictEqual 会失败而两边打印一模一样(往沙箱全局挂 Array 也没用)。 */
    const actual = [Math.round(corner.uv[0] * 16) || 0, Math.round(corner.uv[1] * 16) || 0];
    assert.deepEqual(actual, [eu, ev],
      `${name} 面上位置 (${x},${y},${z}) 的角点 uv 与原版 defaultFaceUv 约定不符`);
  }
}

/* uvlock 的定义就是"模型整体旋转后,纹理仍锁在世界坐标上",判据可以直接从定义写,
   不必照抄原版的矩阵管线:某个面带 uvlock 旋转之后,它落到哪个朝向,其世界位置上的纹理
   就必须和"未旋转时本来就朝那个方向的那个面"完全一致。楼梯/栅栏/墙/按钮/活板门的顶底面
   靠它才不跟着朝向转(1062 个原版 blockstate 里 125 个用到)。 */
const CUBE_FACES = ['down', 'up', 'north', 'south', 'west', 'east'];
const faceOf = name => sandbox.facesFromModel({textures:{all:'minecraft:block/stone'},
  elements:[{from:[0,0,0], to:[16,16,16], faces:{[name]:{texture:'#all'}}}]})[0];
function worldUv(face, turn, rotationX, rotationY) {
  const m = sandbox.matrix(0, 0, 0, rotationX, rotationY);
  const spin = v => [v[0]*m[0] + v[1]*m[4] + v[2]*m[8],
    v[0]*m[1] + v[1]*m[5] + v[2]*m[9], v[0]*m[2] + v[1]*m[6] + v[2]*m[10]];
  const map = new Map();
  for (const corner of face.corners) {
    let [a, b] = corner.uv;
    for (let i = 0; i < turn / 90; i++) [a, b] = [1 - b, a];
    map.set(spin(corner.position).map(value => (Math.round(value * 2) / 2) || 0).join(','),
      [Math.round(a * 16) || 0, Math.round(b * 16) || 0].join(','));
  }
  return {normal:spin(face.normal).map(value => Math.round(value) || 0).join(','), map};
}
const canonicalUv = new Map();
for (const name of CUBE_FACES) {
  const value = worldUv(faceOf(name), 0, 0, 0);
  canonicalUv.set(value.normal, value.map);
}
let uncorrectedDrift = 0;
for (const name of CUBE_FACES) {
  const face = faceOf(name);
  for (const rotationX of [0, 90, 180, 270]) for (const rotationY of [0, 90, 180, 270]) {
    const locked = worldUv(face, sandbox.uvLockTurn(name, rotationX, rotationY), rotationX, rotationY);
    for (const [world, uv] of locked.map) {
      assert.equal(uv, canonicalUv.get(locked.normal).get(world),
        `uvlock: ${name} 面在 x=${rotationX},y=${rotationY} 下,世界位置 (${world}) 的纹理朝向`
        + '必须与未旋转时朝同一方向的面一致');
    }
    const raw = worldUv(face, 0, rotationX, rotationY);
    for (const [world, uv] of raw.map) if (uv !== canonicalUv.get(raw.normal).get(world)) uncorrectedDrift++;
  }
}
assert.ok(uncorrectedDrift > 0, '对照组:不做修正时必须有面的纹理朝向随旋转漂移,否则上面的断言是空的');

const cubeFaces = sandbox.facesFromModel({textures:{all:'minecraft:block/stone'},
  elements:[{from:[0,0,0], to:[16,16,16], faces:Object.fromEntries(
    ['down','up','north','south','west','east'].map(name => [name, {texture:'#all'}]))}]});
assert.equal(cubeFaces.length, 6);
assertFacesFront(cubeFaces, '满立方体');
assertFacesFront(slopedFluid.faces, '液面');
assert.equal(new Set(slopedFluid.faces.filter(face => Math.abs(face.normal[1]) < .5)
  .map(face => face.normal.join(','))).size, 4, '该样例四个侧向都应可见,绕序检查才覆盖得全');

/* 定制拼装表:管道按方向布尔接管臂(multipart、blockstate 无旋转),部件缺失只标 partial。 */
const pipeElement = (from, to) => ({from, to, faces:{up:{texture:'#0'}, down:{texture:'#0'}}});
const pipeFiles = new Map([
  ['assets/create/blockstates/fluid_pipe.json', encoder.encode(JSON.stringify({multipart:[
    {when:{east:'true', west:'true'}, apply:{model:'create:block/fluid_pipe/lr_x'}}
  ]}))],
  ['assets/create/models/block/fluid_pipe/lr_x.json', encoder.encode(JSON.stringify({
    textures:{0:'create:block/pipes'}, elements:[pipeElement([4,4,4],[12,12,12])]
  }))],
  ['assets/create/models/block/fluid_pipe/connection/east.json', encoder.encode(JSON.stringify({
    textures:{0:'create:block/pipes'}, elements:[pipeElement([12,4,4],[16,12,12])]
  }))]
  // 故意不给 connection/west.json:缺部件必须只跳过自己并标 partial
]);
const pipeState = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'create:fluid_pipe', state:'create:fluid_pipe[east=true,west=true,up=false]'}], {files:pipeFiles},
  id => sandbox.bakeModel(pipeFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
const pipeAssembly = [...pipeState.batches.values()].filter(batch => batch.assembly);
assert.equal(pipeAssembly.length, 1, '管道拼装:east=true 必须接出东向管臂,west 部件缺失只跳过');
assert.ok(Math.max(...pipeAssembly[0].positions) > .49, '管臂几何应伸到东侧块缘(核心止于 .25)');
assert.ok(pipeState.partial, '缺 west 部件必须把状态标为 partial');
const pipeIdle = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'create:fluid_pipe', state:'create:fluid_pipe[east=true,west=true,up=true]'}], {files:pipeFiles},
  id => sandbox.bakeModel(pipeFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
assert.equal([...pipeIdle.batches.values()].filter(batch => batch.assembly).length, 1,
  'false 方向不得接臂(up=true 但 connection/up 不存在,东臂仍在)');

/* Forge texture_size 扩展:UV 按声明的网格归一(安山螺旋桨 32×32),不能一律 /16;且要沿 parent 链继承。 */
const sizedFiles = new Map([
  ['assets/ns/models/block/sized.json', encoder.encode(JSON.stringify({parent:'ns:block/sized_base'}))],
  ['assets/ns/models/block/sized_base.json', encoder.encode(JSON.stringify({
    texture_size:[32, 32], textures:{0:'ns:block/t'},
    elements:[{from:[0,0,0], to:[16,16,16], faces:{up:{uv:[8,8,16,16], texture:'#0'}}}]
  }))]
]);
const sizedFaces = sandbox.bakeModel(sizedFiles, 'ns:block/sized');
assert.ok(sizedFaces && sizedFaces.length === 1, 'texture_size 模型必须正常烘焙');
assert.ok(sizedFaces[0].corners.every(corner => corner.uv[0] >= .25 - 1e-6 && corner.uv[0] <= .5 + 1e-6),
  'texture_size=32 时 uv[8..16] 应归一到 0.25..0.5,而非 /16 得到 0.5..1.0');

/* face.rotation 必须在 uv 子矩形内旋转(BlockFaceUV.getShiftedIndex 语义),不是绕整张贴图转:
   子矩形不居中时后者会采到贴图另一块区域。 */
const rotatedModel = {textures:{0:'ns:block/t'}, elements:[{from:[0,0,0], to:[16,16,16],
  faces:{up:{uv:[8, 8, 16, 16], rotation:180, texture:'#0'}}}]};
const rotatedFaces = sandbox.facesFromElements(rotatedModel, rotatedModel.elements);
assert.ok(rotatedFaces[0].corners.every(corner =>
  corner.uv[0] >= .5 - 1e-6 && corner.uv[0] <= 1 + 1e-6
  && corner.uv[1] >= 0 - 1e-6 && corner.uv[1] <= .5 + 1e-6),
  'uv[8..16]+rotation:180 必须仍在该子矩形内采样(u∈[.5,1],翻转后 v∈[0,.5])');

/* loader 挂在 parent 上(辉光管:顶层模型只有 parent,composite 在 nixie_tube/block.json)。 */
const parentLoaderFiles = new Map([
  ['assets/ns/models/block/tube.json', encoder.encode(JSON.stringify({parent:'ns:block/tube/block'}))],
  ['assets/ns/models/block/tube/block.json', encoder.encode(JSON.stringify({
    loader:'neoforge:composite', textures:{0:'ns:block/tube'},
    children:{body:{elements:[{from:[5,0,5], to:[11,12,11],
      faces:{up:{texture:'#0'}, north:{texture:'#0'}}}]}}
  }))]
]);
const tubed = sandbox.bakeModel(parentLoaderFiles, 'ns:block/tube');
assert.ok(tubed && tubed.length === 2, 'composite loader 挂在 parent 上也必须烘出子元素');
assert.equal(tubed[0].texturePath, 'assets/ns/textures/block/tube.png',
  '父级 composite 的纹理表必须传导到子元素');

/* 链窗背板:part=start/middle/end 得到涂链内衬盒且独占(替代推导),part=none 不触发。
   背板缩进 2.2/13.8,不得与壳件的 2/14 平面共面(z-fighting)。 */
const cogFiles = new Map([
  ['assets/create_connected/blockstates/encased_chain_cogwheel.json', encoder.encode(JSON.stringify({variants:{
    'part=middle':{model:'create_connected:block/ecc/shell'},
    'part=none':{model:'create_connected:block/ecc/shell'}
  }}))],
  ['assets/create_connected/models/block/ecc/shell.json', encoder.encode(JSON.stringify({
    textures:{0:'create_connected:block/shell'},
    elements:[{from:[0,0,0], to:[16,2,16], faces:{up:{texture:'#0'}}}]
  }))]
]);
const cogMiddle = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'create_connected:encased_chain_cogwheel',
    state:'create_connected:encased_chain_cogwheel[axis=x,part=middle]'}], {files:cogFiles},
  id => sandbox.bakeModel(cogFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
const cogAssembly = [...cogMiddle.batches.values()].filter(batch => batch.assembly);
assert.equal(cogAssembly.length, 1, 'part=middle 必须得到链窗背板');
assert.ok(cogAssembly[0].texture.endsWith('encased_chain_drive_middle.png'),
  'middle 段背板必须用 _middle 贴图(该段闭包必带)');
assert.ok(cogAssembly[0].positions.every(value => Math.abs(value) < .5 - 1e-3),
  '背板不得与 0/16 边界面共面(z-fighting)');
assert.ok(Math.max(...cogAssembly[0].positions.map(Math.abs)) > .46,
  '背板必须几乎齐边封住窗口(缩进太深会从边缘和相邻段的缝漏风)');
const cogNone = sandbox.bakeState([{x:0,y:0,z:0,g:-1}], 0,
  [{id:'create_connected:encased_chain_cogwheel',
    state:'create_connected:encased_chain_cogwheel[axis=x,part=none]'}], {files:cogFiles},
  id => sandbox.bakeModel(cogFiles, id), new Map(), new Map(), {}, 0, 0, 0, 0, 10_000);
assert.equal([...cogNone.batches.values()].filter(batch => batch.assembly).length, 0,
  'part=none 不触发背板(保留通用推导的齿轮路径)');

console.log('model worker checks passed');

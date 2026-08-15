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
const manifest = {version:1, fingerprint:'a'.repeat(64), entries};
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
  self: {crypto:null, postMessage:value=>posted.push(value)},
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
const loaded = await sandbox.loadResources('/api/preview/resources/' + 'b'.repeat(64) + '/manifest', '', '');
assert.equal(loaded.byteLength, shard.length);
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
assert.ok(canvasTransforms.some(value => value[0] === 'translate' && value[2] === 512)
  && canvasTransforms.some(value => value[0] === 'scale' && value[1] === 1 && value[2] === -1),
  'ImageBitmap 图集必须在 Canvas 中预先纵向翻转');
const records = new Uint16Array([0,0,0,0,1,0,0,0]);
sandbox.self.onmessage({data:{type:'bake', manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'', server:'', recordBytes:8, records:records.buffer,
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
  token:'',server:'',recordBytes:8,records:enclosedRecords.buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:3,height:3,depth:3,biome_colors:{}},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
assert.equal(enclosed.batches[0].instances.length, 6 * 3,
  '只有被六个已确认不透明完整立方体包围的实例可从场景中剔除');
assert.equal(fetchCount, fetchesAfterFirstBake, '同一资源指纹的第二次 bake 不得重新下载分片');
assert.equal(decodeCount, decodesAfterFirstBake, '同一资源指纹的第二次 bake 不得重新解码纹理');
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
  token:'',server:'',recordBytes:8,records:solidRecords.buffer,
  palette:[{id:'minecraft:stone',state:'minecraft:stone'}],
  metadata:{states:[{id:'minecraft:stone',state:'minecraft:stone'}],width:2,height:3,depth:2,biome_colors:{}},
  budget:{gpuBytes:256*1024*1024,mainMemoryBytes:512*1024*1024,atlasSize:2048}});
assert.equal(solid.batches[0].instances.length, 12 * 3,
  '结构边界上的方块不得因邻居坐标越界绕回而被剔除');

/* 旋转组(轴承上的 Create contraption)。同一份七体素十字,把最后一个划进组后:
   它自己得单独成批(整批共用一个绕轴矩阵),而且不能再充当遮挡体 —— 它转开之后
   中心那块就露出来了,拿装配姿态去剔除会在螺旋桨底下留一个洞。 */
const spun = await sandbox.bake({manifestUrl:'/api/preview/resources/' + 'b'.repeat(64) + '/manifest',
  token:'',server:'',recordBytes:8,records:enclosedRecords.slice().buffer,
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
    token:'',server:'',recordBytes:8,records:new Uint16Array([0,0,0,0]).buffer,
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
   绕序与法线反向的面会被整片背面剔除 —— 真机上表现为方块能看穿、栅栏柱只剩两个面。
   实测抓到过:west/east 两面和四个液体侧面都是反的。 */
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

console.log('model worker checks passed');

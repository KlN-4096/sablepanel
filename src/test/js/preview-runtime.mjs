import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/web/js/preview/runtime.js', import.meta.url), 'utf8');
const overlay = {style:{display:'block'}};
const host = {
  firstChild:{id:'controls'},
  insertBefore(node, before) { this.inserted = [node, before]; node.parentElement = this; }
};
const sandbox = {
  globalThis:null,
  localStorage:{getItem:() => null, setItem(){}},
  document:{getElementById:id => id === 'fsOverlay' ? overlay : null},
  requestAnimationFrame:() => 1,
  performance:{now:() => 0}
};
sandbox.globalThis = sandbox;
vm.runInNewContext(source, sandbox, {filename:'runtime.js'});

const runtime = new sandbox.SablePreviewRuntime({host});
assert.equal(runtime.budget.gpuBytes, 256 * 1024 * 1024);
assert.equal(runtime.budget.mainMemoryBytes, 512 * 1024 * 1024);
class Geometry {
  constructor() { this.attributes = {position:{count:24}}; }
  getAttribute(name) { return this.attributes[name]; }
  setAttribute(name, value) { this.attributes[name] = value; }
  dispose() { this.disposed = true; }
}
class Mesh {
  constructor(geometry) { this.geometry = geometry; this.instanceMatrix = {}; this.instanceColor = {}; this.userData = {}; }
  setMatrixAt() {}
  setColorAt() {}
}
sandbox.THREE = {
  Matrix4:class { makeTranslation() { return this; } },
  Color:class { setHex() { return this; } clone() { return this; } multiplyScalar() { return this; } },
  BoxGeometry:Geometry,
  BufferAttribute:class { constructor(array,itemSize) { this.array = array; this.itemSize = itemSize; } },
  MeshLambertMaterial:class { constructor() { this.userData = {}; } dispose() { this.disposed = true; } },
  MeshBasicMaterial:class { constructor() { this.userData = {}; } dispose() { this.disposed = true; } },
  InstancedMesh:Mesh,
  GridHelper:class { constructor() { this.position = {set(){}}; } },
  // 悬停描边(showHoverBox)的最小件:pick 命中后会建白框叠黑框
  Group:class { constructor() { this.position = {set(){}}; this.children = []; } add(child) { this.children.push(child); } },
  LineSegments:class { constructor(geometry, material) { this.geometry = geometry; this.material = material; } },
  EdgesGeometry:class {},
  LineBasicMaterial:class {}
};
runtime.scene = {add(){}};
runtime.buildEdges = () => {};
runtime.buildFallback({voxelCount:1,records:new Uint16Array([0,0,0,0]),isShell:()=>true,
  metadata:{states:[{color:0x777777}]}});
const fallbackColor = runtime.lowGroups.get(0).geometry.getAttribute('color').array;
assert.equal(fallbackColor.length, 24 * 3);
assert.ok(fallbackColor.every(value => value === 1), '降级实例必须有白色顶点色，不能被缺失属性乘成黑色');
runtime.lowGroups.clear();
runtime.structure = {metadata:{states:[{color:0x777777}],width:2,depth:1}};
runtime.restoreFallback(0);
assert.ok(runtime.lowGroups.get(0).geometry.getAttribute('color').array.every(value => value === 1),
  'LOD/上下文丢失后的恢复方块也必须保留白色顶点色');
runtime.lowGroups.clear();

/* 体素索引改成整数序号后,写入端(buildFallback)和拾取端(pick 的 DDA)必须用同一套
   width/depth 算键 —— 任一端读错尺寸,悬停就会静默全部落空。 */
runtime.voxelIndex.clear(); runtime.fallbackValues.clear();
const indexed = {voxelCount:2, records:new Uint16Array([0,0,0,0, 1,2,3,0]), isShell:() => true,
  metadata:{states:[{color:0x777777}], width:2, height:3, depth:4}};
runtime.structure = indexed;
runtime.scene = {add(){}, remove(){}};
runtime.renderer = {domElement:{}};
runtime.buildFallback(indexed);
assert.equal(runtime.voxelIndex.size, 2, '每个体素只占一个整数键');
sandbox.THREE.Raycaster = class {
  constructor() { this.ray = {origin:{x:-5.5, y:2, z:3}, direction:{x:1, y:0, z:0}}; }
  setFromCamera() {}
};
let hovered = 'none';
runtime.options.onHover = index => { hovered = index; };
runtime.pick();
assert.equal(hovered, 1, '射线穿过 (1,2,3) 必须命中第 1 条体素记录');
runtime.lowGroups.clear(); runtime.voxelIndex.clear(); runtime.fallbackValues.clear();
runtime.structure = null; runtime.options.onHover = null;

/* 半透明排序改为按相机位移触发,相机不动时必须原样保留上一次的绘制顺序。 */
runtime.camera = {position:{x:0, y:0, z:0}, updateProjectionMatrix(){}};
runtime.translucentMeshes = [{userData:{chunkCenter:[0,0,1]}}, {userData:{chunkCenter:[0,0,10]}}];
runtime.lastSortOrigin = [Infinity, Infinity, Infinity];
runtime.sortTranslucent();
assert.deepEqual(runtime.translucentMeshes.map(mesh => mesh.userData.chunkCenter[2]), [10, 1],
  '半透明必须由远及近绘制');
runtime.translucentMeshes.reverse();
runtime.sortTranslucent();
assert.deepEqual(runtime.translucentMeshes.map(mesh => mesh.renderOrder), [2, 1],
  '相机未移动时不得重排半透明列表');
runtime.camera.position = {x:50, y:0, z:0};
runtime.sortTranslucent();
assert.deepEqual(runtime.translucentMeshes.map(mesh => mesh.renderOrder), [1, 2],
  '相机移动后必须重新排序');
runtime.camera = {updateProjectionMatrix(){}};
const canvas = {parentElement:{id:'fsCanvasBox'}};
runtime.renderer = {domElement:canvas, setSize(){}};
runtime.camera = {updateProjectionMatrix(){}};
runtime.fullscreen = true;
runtime.closeFullscreen();
assert.equal(runtime.fullscreen, false);
assert.equal(overlay.style.display, 'none');
assert.deepEqual(host.inserted, [canvas, host.firstChild]);

/* 切换物理体时旧渲染必须真正离开场景图。dispose 只释放 GPU 资源,
   Three 会在下一帧照常重传并继续绘制 —— 用户实测就是"点了别的组画面还是上一个体"。
   分块网格(disposeResources=false)共用几何、只由第 0 块释放资源,但每一块都得摘出场景。 */
const sceneChildren = new Set();
runtime.scene = {add(node){ sceneChildren.add(node); }, remove(node){ sceneChildren.delete(node); }};
runtime.structure = {metadata:{states:[{color:0x777777}], width:1, depth:1}};
runtime.fallbackValues.set(0, [{i:0, x:0, y:0, z:0}]);
runtime.restoreFallback(0);
const shared = new sandbox.THREE.InstancedMesh(new Geometry());
shared.userData = {stateIndex:0, disposeResources:false};
runtime.highMeshes.push(shared); runtime.scene.add(shared);
runtime.edgeLines = new sandbox.THREE.InstancedMesh(new Geometry()); runtime.scene.add(runtime.edgeLines);
runtime.gridHelper = new sandbox.THREE.GridHelper(); runtime.scene.add(runtime.gridHelper);
assert.equal(sceneChildren.size, 4, '前置条件:降级组/高保真分块/边框/网格都在场景里');

let terminated = false;
runtime.worker = {terminate(){ terminated = true; }};
runtime.structure = {voxelCount:1};
runtime.disposeObjects();
assert.equal(terminated, true);
assert.equal(runtime.worker, null);
assert.equal(runtime.structure, null);
assert.equal(sceneChildren.size, 0, '切换物理体后场景里不得残留上一个体的任何对象');

/* 渲染参数相同的批次必须共用材质实例:每批各 new 一个,等于每个 draw call 前
   都让 Three 重建一遍着色器状态,而图集打包后真正需要的材质只有个位数。 */
const shadedBatch = {texture:'__atlas__/0', stateIndex:0, colors:true, shade:true};
const firstMaterial = runtime.materialFor(shadedBatch);
assert.equal(runtime.materialFor({...shadedBatch, stateIndex:7}), firstMaterial,
  '同图集页/同着色参数的批次必须共用一个材质实例');
assert.notEqual(runtime.materialFor({...shadedBatch, texture:'__atlas__/1'}), firstMaterial,
  '不同图集页不能混用材质');
assert.notEqual(runtime.materialFor({...shadedBatch, shade:false}), firstMaterial,
  'shade=false 走 MeshBasicMaterial,不能复用 Lambert');
const sharedHolder = new sandbox.THREE.InstancedMesh(new Geometry());
sharedHolder.material = firstMaterial;
runtime.disposeMesh(sharedHolder);
assert.equal(firstMaterial.disposed, undefined, '单个网格不得替其他批次销毁共享材质');
runtime.disposeObjects();
assert.equal(firstMaterial.disposed, true, '共享材质由 disposeObjects 统一释放');
assert.equal(runtime.materials.size, 0);

/* draw call 数只是"会不会卡"的代理指标。帧率正常就不该把方块打回纯色。 */
let degraded = 0;
runtime.degradeCostliest = () => { degraded++; return true; };
runtime.highMeshes = [{userData:{}}];
runtime.renderer = {info:{render:{calls:runtime.budget.drawCalls + 500}}};
runtime.performanceWindow = {start:0, frames:180};
runtime.samplePerformance(3000, 16);
assert.equal(degraded, 0, '帧率正常时不得仅因 draw call 数超预算就降级');
runtime.performanceWindow = {start:0, frames:30};
runtime.samplePerformance(3000, 16);
assert.equal(degraded, 1, '帧率真的掉到 30 以下才降级');
runtime.highMeshes = []; runtime.performanceWindow = null;

const triangleGeometry = {
  getAttribute:() => ({array:new Float32Array([-1,-1,0, 1,-1,0, 0,1,0])}),
  index:{array:new Uint32Array([0,1,2])}
};
runtime.pickGeometries.set(0, new Set([{geometry:triangleGeometry,cells:new Float64Array([0])}]));
assert.equal(runtime.intersectsState(0, 0, 0, 0,
  {x:0,y:0,z:-1}, {x:0,y:0,z:1}, 0, 2), true);
assert.equal(runtime.intersectsState(0, 0, 0, 0,
  {x:2,y:2,z:-1}, {x:0,y:0,z:1}, 0, 2), false);
runtime.pickGeometries.set(0, new Set([{geometry:triangleGeometry,cells:new Float64Array([1])}]));
assert.equal(runtime.intersectsState(0, 0, 0, 0,
  {x:0,y:0,z:-1}, {x:0,y:0,z:1}, 0, 2), false,
  '带权 variant 的其他实例几何不能参与当前体素精确拾取');

let transferStopped = false;
sandbox.Worker = class {
  postMessage() { throw new Error('no transfer'); }
  terminate() { transferStopped = true; }
};
runtime.highFidelityAvailable = true;
runtime.options.onStatus = value => { status = value; };
let status = '';
runtime.startWorker({recordBytes:8,records:new Uint16Array(4),metadata:{states:[]}}, {manifestUrl:'/manifest'});
assert.equal(transferStopped, true);
assert.equal(runtime.worker, null);
assert.equal(runtime.highFidelityAvailable, false);
assert.equal(status, 'resource_unavailable');

let prevented = false, contextWorkerStopped = false;
runtime.options.onStatus = value => { status = value; };
runtime.scene = {remove(){}};
runtime.worker = {terminate(){ contextWorkerStopped = true; }};
runtime.handleContextLost({preventDefault(){ prevented = true; }});
assert.equal(prevented, true);
assert.equal(contextWorkerStopped, true);
assert.equal(runtime.highFidelityAvailable, false);
assert.equal(status, 'resource_unavailable');

/* 旋转组(轴承上的 Create contraption)。用真的 three.js 矩阵,不自己写一份矩阵乘法 ——
   否则这条测的就是我手写的矩阵而不是运行时的合成顺序。共轭写反(T(-p)·R·T(p))时距离守恒
   依然成立,所以必须钉一个具体落点。 */
const threeSource = fs.readFileSync(
  new URL('../../main/resources/web/vendor/three.min.js', import.meta.url), 'utf8');
const threeBox = {console:{warn(){}}, window:{}, self:{}, Math, Object, Array, Number, String,
  Float32Array, Uint32Array, Uint16Array, Uint8Array, Int32Array, ArrayBuffer, DataView, JSON, Error, Symbol};
threeBox.globalThis = threeBox;
vm.runInNewContext(threeSource, threeBox, {filename:'three.min.js'});
sandbox.THREE.Matrix4 = threeBox.THREE.Matrix4;
sandbox.THREE.Vector3 = threeBox.THREE.Vector3;

const placed = [];
sandbox.THREE.InstancedMesh = class extends Mesh {
  setMatrixAt(index, matrix) { placed[index] = Array.from(matrix.elements).slice(12, 15); }
  setColorAt() {}
};
const spun = {voxelCount:2, isShell:() => true,
  records:new Uint16Array([0,0,0,0, 13,5,10,0]),
  metadata:{states:[{color:0x777777}], width:20, height:20, depth:20,
    groups:[{first:1, count:1, pivot:[10,5,10], axis:'y', angle:90}]}};
runtime.voxelIndex.clear(); runtime.fallbackValues.clear(); runtime.lowGroups.clear();
runtime.groupMatrices.clear();
runtime.scene = {add(){}, remove(){}};
runtime.buildEdges = () => {};
runtime.structure = spun;
runtime.buildFallback(spun);
assert.deepEqual(placed[0], [0, 0, 0], '组外体素不能被任何矩阵碰到');
// 支点正 x 方向 3 格,绕 +Y 转 90°,按 three 的右手系应落到 -z 方向 3 格
assert.deepEqual(placed[1].map(value => Math.round(value)), [10, 5, 7]);

runtime.groupMatrices.clear(); runtime.lowGroups.clear(); runtime.voxelIndex.clear();
spun.metadata.groups[0].angle = 360;
runtime.buildFallback(spun);
assert.deepEqual(placed[1].map(value => Math.round(value)), [13, 5, 10], '整圈必须回到原位');

console.log('preview runtime checks passed');

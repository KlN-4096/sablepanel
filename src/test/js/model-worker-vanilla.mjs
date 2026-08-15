/*
 * 用真实的 Minecraft 1.21.1 原版资源驱动模型解析与烘焙。
 *
 * 其余前端测试用的是三个手写文件的合成夹具(PNG 内容甚至是 "not-a-png"),
 * 从没验证过 blockstate 变体匹配、multipart、父模型链和纹理槽解析在真实资源上成不成立。
 * 这里直接读 ModDevGradle already 解出来的客户端资源 jar —— 不往仓库里提交任何 Mojang 资源。
 *
 * 找不到那个 jar(没跑过 ./gradlew 的机器)就跳过,不算失败。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';

const JAR = 'build/moddev/artifacts/neoforge-21.1.233-client-extra-aka-minecraft-resources.jar';
const ROOT = 'build/adhoc/mcassets';

if (!fs.existsSync(JAR)) {
  console.log('vanilla bake checks skipped: 缺少 ' + JAR + '(先跑一次 ./gradlew)');
  process.exit(0);
}
if (!fs.existsSync(path.join(ROOT, 'assets/minecraft/blockstates/stone.json'))) {
  try {
    fs.mkdirSync(ROOT, {recursive: true});
    execFileSync('unzip', ['-qo', JAR, 'assets/minecraft/blockstates/*',
      'assets/minecraft/models/block/*', '-d', ROOT], {stdio: 'ignore'});
  } catch (error) {
    console.log('vanilla bake checks skipped: 无法解压资源 jar (' + (error.message || error) + ')');
    process.exit(0);
  }
}

const source = fs.readFileSync('src/main/resources/web/js/preview/model-worker.js', 'utf8');
const sandbox = {
  self: {crypto: null, postMessage() {}},
  fetch: async () => { throw new Error('vanilla 检查不该发起网络请求'); },
  TextDecoder, TextEncoder, Uint8Array, Uint8ClampedArray, Uint16Array, Uint32Array, Float32Array,
  DataView, ArrayBuffer, Blob, Promise, Map, Set, Math, BigInt, Number, JSON, Object, String, Error,
  Array, Boolean, isNaN, parseInt, parseFloat,
  createImageBitmap: async () => ({width: 16, height: 16}),
  console, setTimeout, clearTimeout
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(source, sandbox, {filename: 'model-worker.js'});

const files = new Map();
(function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) walk(full);
    else files.set(full.slice(ROOT.length + 1).replaceAll('\\', '/'), new Uint8Array(fs.readFileSync(full)));
  }
})(ROOT);
assert.ok(files.size > 2000, '原版方块资源应有数千个文件，实际 ' + files.size);

const SEED = sandbox.minecraftModelSeed(0, 0, 0);
const choicesOf = (id, state) => sandbox.blockstateModels(files, id, state || id, SEED);
const bake = model => sandbox.bakeModel(files, model);
const triangles = faces => faces.reduce((sum, face) => sum + face.corners.length - 2, 0);

/* 完整立方体:纹理要沿 oak_stairs -> block/stairs -> block/block 这类父链解析出来,
   而且必须被判定为完整立方体 —— 遮挡剔除只信这个判定。 */
const stone = bake(choicesOf('minecraft:stone')[0].model);
assert.equal(stone.length, 6, '石头应烘焙出六个面');
assert.equal(triangles(stone), 12);
assert.equal(sandbox.isFullCubeFaces(stone), true, '石头必须被判定为完整立方体');
assert.ok(stone.every(face => face.texturePath === 'assets/minecraft/textures/block/stone.png'),
  '#all 纹理槽必须沿 cube_all 父链解析到真实路径');
assert.ok(stone.every(face => face.corners.every(corner =>
  corner.position.every(value => value >= -0.5 - 1e-9 && value <= 0.5 + 1e-9))),
  '完整方块的顶点必须落在以方块中心为原点的单位立方体内');

/* 楼梯:两个元素、非完整立方体。若这里误判成完整立方体,遮挡剔除会把楼梯后面的方块整片删掉。 */
const stairChoices = choicesOf('minecraft:oak_stairs',
  'minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]');
assert.equal(stairChoices.length, 1);
const stairs = bake(stairChoices[0].model);
assert.ok(triangles(stairs) > 12, '直角楼梯的三角形数应多于单个立方体，实际 ' + triangles(stairs));
assert.equal(sandbox.isFullCubeFaces(stairs), false, '楼梯不得被判定为完整立方体');

/* 变体键匹配必须把旋转带出来:同一模型不同朝向靠 variant 的 x/y 旋转区分,
   几何只烘焙一次,旋转在实例化时施加。 */
assert.equal(Number(stairChoices[0].y) || 0, 0, 'facing=east 是 oak_stairs 的基准朝向');
const northStair = choicesOf('minecraft:oak_stairs',
  'minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]')[0];
assert.equal(northStair.model, stairChoices[0].model, '不同朝向共用同一个模型');
assert.equal(northStair.y, 270, 'facing=north 必须带出 y=270 旋转');

/* multipart:栅栏没有 variants,只有 when/apply。连接数变化必须改变被选中的部件数。 */
const fenceAlone = choicesOf('minecraft:oak_fence',
  'minecraft:oak_fence[east=false,north=false,south=false,west=false,waterlogged=false]');
const fenceEast = choicesOf('minecraft:oak_fence',
  'minecraft:oak_fence[east=true,north=false,south=false,west=false,waterlogged=false]');
assert.equal(fenceAlone.length, 1, '孤立栅栏只有柱子');
assert.equal(fenceEast.length, 2, '向东连接的栅栏应是柱子 + 一段侧栏');
assert.ok(fenceEast.some(choice => choice.model.includes('side')), 'multipart 必须选中侧栏部件');

/* 染色:树叶整块带 tintindex 0,石头一个都没有;草方块是混合的(顶面覆盖层染色,土壁不染)。 */
const leaves = bake(choicesOf('minecraft:oak_leaves',
  'minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]')[0].model);
assert.ok(leaves.every(face => face.tintIndex === 0), '树叶所有面都应带 tintindex 0');
assert.ok(stone.every(face => face.tintIndex === -1), '石头不得带 tintindex');
const grass = bake(choicesOf('minecraft:grass_block', 'minecraft:grass_block[snowy=false]')[0].model);
const grassTints = new Set(grass.map(face => face.tintIndex));
assert.ok(grassTints.has(0) && grassTints.has(-1),
  '草方块必须同时有染色的覆盖层和不染色的面，实际 ' + [...grassTints]);
assert.ok(new Set(grass.map(face => face.texturePath)).size >= 3,
  '草方块应引用顶面/侧面/覆盖层等多张纹理');

/* 非完整方块的代表:火把是两片交叉面片,绝不能进遮挡剔除。 */
const torch = bake(choicesOf('minecraft:torch')[0].model);
assert.ok(torch && torch.length > 0, '火把必须能烘焙');
assert.equal(sandbox.isFullCubeFaces(torch), false, '火把不得被判定为完整立方体');

console.log('vanilla bake checks passed (' + files.size + ' 个原版资源文件)');

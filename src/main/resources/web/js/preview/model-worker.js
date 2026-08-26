/* eslint-disable no-restricted-globals */
'use strict';
/*
 * 预览 Worker:资源闭包读取、标准 blockstate/model 解析和静态四边形烘焙。
 * 这里只返回可转移的数值缓冲；Three.js、DOM 和面板业务状态留在主线程。
 */

const MAX_HIGH_TRIANGLES = 1_500_000;
/* 上限存在只为兜底,不该是常见情形的天花板。256 会让一个中等模组建筑的后半截状态
   直接拿不到纹理(groups 按体素记录顺序遍历,谁在后面谁降级,纯看运气)。 */
const MAX_DRAW_CALLS = 1024;
const MAX_MODEL_FACES = 50_000;
const MAX_MODEL_DEPTH = 32;
const MAX_SOURCE_TEXTURE_EDGE = 4096;
const MAX_OBJ_FACES = 50_000;
const MAX_OBJ_MATERIALS = 128;
const MAX_OBJ_LINE = 64 * 1024;
const RESOURCE_PROTOCOL_VERSION = 2;
const MAX_ASSEMBLY_ELEMENTS = 256;
const MAX_ASSEMBLY_MODELS = 64;
const MAX_ASSEMBLY_COMPONENT_ELEMENTS = 64;
const MAX_ASSEMBLY_TRANSLATION_PAIRS = 65_536;
const MAX_ASSEMBLY_TRANSLATIONS = 64;
const MAX_ASSEMBLY_CACHE_ENTRIES = 1024;
const MIN_ASSEMBLY_MATCHES = 4;
const MIN_ASSEMBLY_RATIO = .8;
const MIN_COMPONENT_ALIGNMENT_MATCHES = 3;
const MIN_COMPONENT_ALIGNMENT_RATIO = .6;
const ASSEMBLY_COORDINATE_LIMIT = 64;
const ASSEMBLY_QUANTIZE = 20;
const MAX_ATLAS_PAGES = 8;
const ATLAS_SIZE = 2048;
const ATLAS_PADDING = 4;
/*
 * 每项: [nx, ny, nz, 角点(u,v) 列表, u 轴反向, v 轴反向]
 *
 * 角点顺序即三角绕序,必须与该面的法线同向:反了会被 FrontSide 整面背面剔除。
 *
 * 后两位是纹理轴方向。位置一律按 +x/+y/+z 线性插值,而原版每个面的 uv 轴方向各不相同
 * (见本文件 defaultFaceUv:north 的 u = 16−x、side 的 v = 16−y、down 的 v = 16−z),
 * 一个公式套不住六个面,方向差异必须在这里显式声明。写错的表现是纹理上下或左右镜像 ——
 * 火把的火焰跑到底下、草方块的草皮长在下沿。
 */
const FACE_AXES = {
  down:  [0, -1, 0, [[0, 0], [16, 0], [16, 16], [0, 16]], false, true],
  up:    [0, 1, 0, [[0, 0], [0, 16], [16, 16], [16, 0]], false, false],
  north: [0, 0, -1, [[16, 0], [0, 0], [0, 16], [16, 16]], true, true],
  south: [0, 0, 1, [[0, 0], [16, 0], [16, 16], [0, 16]], false, true],
  west:  [-1, 0, 0, [[16, 0], [16, 16], [0, 16], [0, 0]], false, true],
  east:  [1, 0, 0, [[0, 0], [0, 16], [16, 16], [16, 0]], true, true]
};

function minecraftModelSeed(x, y, z) {
  const xTerm = BigInt.asIntN(32, BigInt(x) * 3129871n);
  let value = BigInt.asIntN(64, xTerm ^ BigInt.asIntN(64, BigInt(z) * 116129781n) ^ BigInt(y));
  value = BigInt.asIntN(64, value * value * 42317861n + value * 11n);
  return BigInt.asIntN(64, value >> 16n);
}

function legacyNextLong(seed) {
  const mask = (1n << 48n) - 1n, multiplier = 25214903917n;
  let current = (BigInt.asIntN(64, seed) ^ multiplier) & mask;
  current = (current * multiplier + 11n) & mask;
  const high = BigInt.asIntN(32, current >> 16n);
  current = (current * multiplier + 11n) & mask;
  const low = BigInt.asIntN(32, current >> 16n);
  return BigInt.asIntN(64, (high << 32n) + low);
}

function weightedIndex(seed, total, multipart) {
  const selectedSeed = multipart ? legacyNextLong(seed) : seed;
  const low = Number(BigInt.asIntN(32, legacyNextLong(selectedSeed)));
  const absolute = low === -2147483648 ? low : Math.abs(low);
  return absolute % total;
}

function appendServer(url, server) {
  if (!server) return url;
  return url + (url.includes('?') ? '&' : '?') + 'server=' + encodeURIComponent(server);
}

async function fetchResource({url, token, server, label, progress, arrayBuffer}) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const response = await fetch(appendServer(url, server), {headers:{'X-Token':token || ''}});
    if (response.status === 202 || response.status === 503) {
      const body = await response.json().catch(() => ({}));
      if (response.status === 503 && body.error !== 'preview_resource_busy') {
        throw new Error(label + ' ' + response.status);
      }
      if (progress) self.postMessage({type:'progress', phase:body.phase || '', source:body.source || '',
        downloaded:Number(body.downloaded), total:Number(body.total), detail:body.detail || ''});
      /* 闭包现场构建通常几百 ms,按 retry_after 睡满 1 秒是每个新闭包的固定税 —— 快问慢退。
         缩略图队列会提前预热下一体的闭包(thumbrender.prewarm),轮到时多半已就绪,首跳压到 100ms */
      await new Promise(resolve => setTimeout(resolve, Math.min(100 * 2 ** attempt, 5000)));
      continue;
    }
    if (!response.ok) throw new Error(label + ' ' + response.status);
    return arrayBuffer ? response.arrayBuffer() : response.json();
  }
  throw new Error(label + '准备超时');
}

async function fetchJson(url, token, server) {
  return fetchResource({url, token, server, label:'资源清单', progress:true, arrayBuffer:false});
}

async function fetchShard(entry, baseUrl, token, server) {
  const slash = baseUrl.lastIndexOf('/');
  const root = slash < 0 ? baseUrl : baseUrl.slice(0, slash);
  const url = root + '/shard/' + entry.shard;
  return fetchResource({url, token, server, label:'资源分片', progress:false, arrayBuffer:true});
}

async function sha256Hex(bytes) {
  const subtle = self.crypto && self.crypto.subtle;
  if (!subtle) throw new Error('浏览器不支持资源哈希校验');
  const digest = new Uint8Array(await subtle.digest('SHA-256', bytes));
  return [...digest].map(value => value.toString(16).padStart(2, '0')).join('');
}

async function loadResources(manifestUrl, token, server, maxBytes = Infinity, suppliedManifest = null,
                             expectedFingerprint = '') {
  const manifest = suppliedManifest || await fetchJson(manifestUrl, token, server);
  if (!manifest || !Array.isArray(manifest.entries)) throw new Error('资源清单无效');
  if (Number(manifest.version) !== RESOURCE_PROTOCOL_VERSION) throw new Error('资源协议版本不兼容');
  if (!/^[0-9a-f]{64}$/.test(String(manifest.fingerprint || ''))) throw new Error('资源指纹无效');
  if (expectedFingerprint && manifest.fingerprint !== expectedFingerprint) throw new Error('资源指纹不一致');
  const groups = new Map();
  for (const entry of manifest.entries) {
    if (!/^[0-9a-f]{64}$/.test(String(entry.shard || ''))) throw new Error('资源分片哈希无效');
    if (!/^[0-9a-f]{64}$/.test(String(entry.sha256 || ''))) throw new Error('资源文件哈希无效');
    let group = groups.get(entry.shard);
    if (!group) { group = []; groups.set(entry.shard, group); }
    group.push(entry);
  }
  const declaredBytes = [...groups.values()].reduce((sum, entries) => sum + entries.reduce((maximum, entry) => {
    const end = Number(entry.offset) + Number(entry.length);
    return Number.isSafeInteger(end) && end >= 0 ? Math.max(maximum, end) : Infinity;
  }, 0), 0);
  if (!Number.isSafeInteger(declaredBytes) || declaredBytes > maxBytes) {
    throw new Error('资源闭包超过浏览器内存预算');
  }
  const files = new Map();
  const grouped = [...groups.entries()];
  let byteLength = 0;
  for (let i = 0; i < grouped.length; i += 2) {
    const current = grouped.slice(i, i + 2);
    const parts = await Promise.all(current.map(([, entries]) => fetchShard(entries[0], manifestUrl, token, server)));
    for (let groupIndex = 0; groupIndex < current.length; groupIndex++) {
      const [shardHash, entries] = current[groupIndex], shard = new Uint8Array(parts[groupIndex]);
      if (await sha256Hex(shard) !== shardHash) throw new Error('资源分片哈希不一致');
      if (byteLength + shard.byteLength > maxBytes) throw new Error('资源闭包超过浏览器内存预算');
      byteLength += shard.byteLength;
      for (const entry of entries) {
        if (entry.offset < 0 || entry.length < 0
            || entry.offset + entry.length > shard.byteLength) throw new Error('资源清单偏移无效');
        const bytes = shard.subarray(entry.offset, entry.offset + entry.length);
        if (await sha256Hex(bytes) !== entry.sha256) throw new Error('资源文件哈希不一致');
        files.set(entry.path, bytes);
      }
    }
  }
  return {manifest, files, byteLength};
}

/* 跨 bake 共享缓存,worker 常驻时生效。JSON/模型/位图可在同一资源指纹的闭包间复用；
   分片文件表和静态组装推导按 server + manifestUrl + fingerprint + protocol 隔离。
   资源栈变化时清空全部派生缓存，避免同路径资源更新后继续使用旧模型。 */
const BITMAP_CACHE_BYTES = 64 * 1024 * 1024;
const assets = {fingerprint:'', jsons:new Map(), models:new Map(), assemblies:new Map(),
  bitmaps:new Map(), bitmapBytes:0};
let shared = null;

function clearAssetCaches() {
  for (const value of assets.bitmaps.values()) if (value.bitmap && value.bitmap.close) value.bitmap.close();
  assets.jsons.clear(); assets.models.clear(); assets.assemblies.clear(); assets.bitmaps.clear();
  assets.bitmapBytes = 0;
}

function sharedFor(manifestUrl, server = '', fingerprint = '', version = RESOURCE_PROTOCOL_VERSION) {
  if (fingerprint && assets.fingerprint && assets.fingerprint !== fingerprint) clearAssetCaches();
  if (fingerprint) assets.fingerprint = fingerprint;
  const key = [server || '', manifestUrl || '', fingerprint || 'uncached', version].join('|');
  if (!shared || shared.key !== key) {
    assets.assemblies.clear();
    shared = {key, fingerprint, loaded:null};
  }
  return shared;
}

function jsonFile(files, path) {
  /* blockstate 定义在 bakeState 里逐体素查询,几千个同种方块=几千次重复 parse 同一文件;
     只有正式管线(files=当前闭包的共享表)吃缓存,外部直接传自建 files 的调用
     (测试/OBJ 工具路径)不受影响。键=path,内容跨闭包恒定,全局累积。 */
  const cache = shared && shared.loaded && shared.loaded.files === files ? assets.jsons : null;
  if (cache && cache.has(path)) return cache.get(path);
  const bytes = files.get(path);
  let result = null;
  if (bytes) {
    try { result = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes)); }
    catch (_) { result = null; }
  }
  if (cache && bytes) cache.set(path, result);
  return result;
}

function resourcePath(value, category, suffix) {
  if (!value || value.startsWith('#') || value === 'builtin/generated') return null;
  const colon = value.indexOf(':');
  const namespace = colon >= 0 ? value.slice(0, colon) : 'minecraft';
  let path = colon >= 0 ? value.slice(colon + 1) : value;
  if (path.startsWith(category + '/')) path = path.slice(category.length + 1);
  if (!namespace || !path || path.includes('..') || path.startsWith('/')) return null;
  return 'assets/' + namespace + '/' + category + '/' + path + (path.endsWith(suffix) ? '' : suffix);
}

function parseProperties(state) {
  const open = state.indexOf('['), out = {};
  if (open < 0) return out;
  const close = state.lastIndexOf(']');
  if (close < open) return out;
  for (const pair of state.slice(open + 1, close).split(',')) {
    const split = pair.indexOf('=');
    if (split > 0) out[pair.slice(0, split)] = pair.slice(split + 1);
  }
  return out;
}

function matchesWhen(when, properties) {
  if (!when) return true;
  if (Array.isArray(when)) return when.every(item => matchesWhen(item, properties));
  if (when.OR) return when.OR.some(item => matchesWhen(item, properties));
  if (when.AND) return when.AND.every(item => matchesWhen(item, properties));
  return Object.entries(when).every(([key, value]) => {
    const actual = properties[key];
    return Array.isArray(value) ? value.includes(actual) : String(value).split('|').includes(actual);
  });
}

function chooseWeighted(value, seed, multipart) {
  if (!Array.isArray(value)) return value;
  if (!value.length) return null;
  const total = value.reduce((sum, item) => sum + Math.max(1, Number(item.weight) || 1), 0);
  let pick = weightedIndex(seed, total, !!multipart);
  for (const item of value) {
    pick -= Math.max(1, Number(item.weight) || 1);
    if (pick < 0) return item;
  }
  return value[value.length - 1];
}

function blockstateModels(files, id, state, seed) {
  const colon = id.indexOf(':'), namespace = colon >= 0 ? id.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? id.slice(colon + 1) : id;
  const definition = jsonFile(files, 'assets/' + namespace + '/blockstates/' + name + '.json');
  if (!definition) return [];
  const properties = parseProperties(state), result = [];
  const variants = definition.variants || {};
  for (const [when, raw] of Object.entries(variants)) {
    const condition = {};
    if (when) for (const part of when.split(',')) {
      const split = part.indexOf('=');
      if (split > 0) condition[part.slice(0, split)] = part.slice(split + 1);
    }
    if (!Object.entries(condition).every(([key, value]) => String(value).split('|').includes(properties[key]))) continue;
    const picked = chooseWeighted(raw, seed, false);
    if (picked && picked.model) result.push(picked);
  }
  for (const part of definition.multipart || []) {
    if (!matchesWhen(part.when, properties)) continue;
    const picked = chooseWeighted(part.apply, seed, true);
    if (picked && picked.model) result.push(picked);
  }
  return result;
}

function blockstateModelIds(files, id) {
  const colon = id.indexOf(':'), namespace = colon >= 0 ? id.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? id.slice(colon + 1) : id;
  const definition = jsonFile(files, 'assets/' + namespace + '/blockstates/' + name + '.json');
  if (!definition) return [];
  const result = new Set();
  const collect = value => {
    if (Array.isArray(value)) { for (const item of value) collect(item); return; }
    if (value && typeof value === 'object' && typeof value.model === 'string') result.add(value.model);
  };
  for (const value of Object.values(definition.variants || {})) collect(value);
  for (const part of definition.multipart || []) collect(part && part.apply);
  return [...result].sort().slice(0, MAX_ASSEMBLY_MODELS);
}

function blockItemModelId(id) {
  const colon = id.indexOf(':'), namespace = colon >= 0 ? id.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? id.slice(colon + 1) : id;
  return namespace + ':item/' + name;
}

function permutationParity(value) {
  let inversions = 0;
  for (let left = 0; left < value.length; left++) for (let right = left + 1; right < value.length; right++) {
    if (value[left] > value[right]) inversions++;
  }
  return inversions % 2 ? -1 : 1;
}

const CUBE_ROTATIONS = (() => {
  const permutations = [[0,1,2],[0,2,1],[1,0,2],[1,2,0],[2,0,1],[2,1,0]], result = [];
  for (const permutation of permutations) for (const sx of [-1,1]) for (const sy of [-1,1]) for (const sz of [-1,1]) {
    const signs = [sx,sy,sz];
    if (permutationParity(permutation) * sx * sy * sz === 1) result.push({permutation, signs});
  }
  result.sort((left, right) => {
    const score = value => value.permutation.every((axis, index) => axis === index)
      && value.signs.every(sign => sign === 1) ? 0 : 1;
    return score(left) - score(right) || left.permutation.join('').localeCompare(right.permutation.join(''))
      || left.signs.join('').localeCompare(right.signs.join(''));
  });
  return result;
})();
const IDENTITY_CUBE_ROTATION = CUBE_ROTATIONS[0];

function rotateAssemblyPoint(point, rotation, centered) {
  const source = centered ? point : point.map(value => value - 8);
  const result = rotation.permutation.map((axis, index) => rotation.signs[index] * source[axis]);
  return centered ? result : result.map(value => value + 8);
}

function assemblyElementPoints(element, rotation, translation = [0,0,0]) {
  const from = element && element.from, to = element && element.to;
  if (!Array.isArray(from) || from.length !== 3 || !Array.isArray(to) || to.length !== 3) return null;
  const values = from.concat(to).map(Number);
  if (!values.every(Number.isFinite) || values.some(value => Math.abs(value) > ASSEMBLY_COORDINATE_LIMIT)) return null;
  if (!Array.isArray(translation) || translation.length !== 3 || !translation.every(Number.isFinite)
      || translation.some(value => Math.abs(value) > ASSEMBLY_COORDINATE_LIMIT)) return null;
  const points = [];
  for (const x of [from[0], to[0]]) for (const y of [from[1], to[1]]) for (const z of [from[2], to[2]]) {
    const point = rotateAssemblyPoint(rotateElement([x,y,z], element.rotation), rotation, false);
    if (!point.every(Number.isFinite)) return null;
    const translated = point.map((value, axis) => value + translation[axis]);
    if (translated.some(value => Math.abs(value) > ASSEMBLY_COORDINATE_LIMIT)) return null;
    points.push(translated);
  }
  return points;
}

function assemblyPointsKey(points) {
  return points.map(point => point.map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(','))
    .sort().join(';');
}

function assemblyElementKey(element, rotation, translation) {
  const points = assemblyElementPoints(element, rotation, translation);
  return points && assemblyPointsKey(points);
}

function assemblyShapeKey(element, rotation) {
  const points = assemblyElementPoints(element, rotation);
  if (!points) return null;
  const center = [0,1,2].map(axis => points.reduce((sum, point) => sum + point[axis], 0) / points.length);
  return assemblyPointsKey(points.map(point => point.map((value, axis) => value - center[axis])));
}

function assemblyElementCenter(element, rotation) {
  const points = assemblyElementPoints(element, rotation);
  return points && [0,1,2].map(axis => points.reduce((sum, point) => sum + point[axis], 0) / points.length);
}

function assemblyElementVisualKey(element) {
  const faces = Object.entries(element && element.faces || {}).sort(([left], [right]) => left.localeCompare(right))
    .map(([name, face]) => [name, face && face.texture || '',
      Array.isArray(face && face.uv) ? face.uv.map(Number).join(',') : '',
      Number(face && face.rotation) || 0, Number.isInteger(face && face.tintindex) ? face.tintindex : -1,
      face && face.shade === false ? 'unshaded' : 'shaded'].join('|'));
  return (element && element.shade === false ? 'unshaded' : 'shaded') + '||' + faces.join('||');
}

function matchAssemblyElements(source, target, rotation, translation = [0,0,0]) {
  const buckets = new Map();
  for (let index = 0; index < source.length; index++) {
    const key = assemblyElementKey(source[index], rotation, translation);
    if (!key) return null;
    let values = buckets.get(key); if (!values) { values = []; buckets.set(key, values); }
    values.push(index);
  }
  const sourceIndices = new Set(), targetIndices = new Set(), pairs = [];
  for (let targetIndex = 0; targetIndex < target.length; targetIndex++) {
    const element = target[targetIndex];
    const key = assemblyElementKey(element, IDENTITY_CUBE_ROTATION);
    const values = key && buckets.get(key);
    if (!values || !values.length) continue;
    let candidates = values;
    if (values.length > 1) {
      const visual = assemblyElementVisualKey(element);
      const matching = values.filter(index => assemblyElementVisualKey(source[index]) === visual);
      if (matching.length) candidates = matching;
      else if (new Set(values.map(index => assemblyElementVisualKey(source[index]))).size > 1) return null;
    }
    const sourceIndex = candidates[candidates.length - 1];
    values.splice(values.indexOf(sourceIndex), 1);
    sourceIndices.add(sourceIndex); targetIndices.add(targetIndex);
    pairs.push([sourceIndex, targetIndex]);
  }
  return {count:sourceIndices.size, sourceIndices, targetIndices, pairs, rotation, translation};
}

function bestAssemblyAlignment(source, target, minimum = MIN_ASSEMBLY_MATCHES, ratio = MIN_ASSEMBLY_RATIO) {
  if (!Array.isArray(source) || !Array.isArray(target) || target.length < minimum
      || source.length > MAX_ASSEMBLY_ELEMENTS || target.length > MAX_ASSEMBLY_ELEMENTS) return null;
  let best = [];
  for (const rotation of CUBE_ROTATIONS) {
    const candidate = matchAssemblyElements(source, target, rotation);
    if (!candidate) continue;
    if (!best.length || candidate.count > best[0].count) best = [candidate];
    else if (candidate.count === best[0].count) best.push(candidate);
  }
  if (!best.length || best[0].count < minimum || best[0].count / target.length < ratio) return null;
  return {...best[0], alternatives:best};
}

function bestTranslatedAssemblyAlignment(source, target, minimum = MIN_ASSEMBLY_MATCHES) {
  if (!Array.isArray(source) || !Array.isArray(target) || source.length < minimum
      || source.length > MAX_ASSEMBLY_COMPONENT_ELEMENTS || target.length > MAX_ASSEMBLY_ELEMENTS) return null;
  const targetShapes = new Map();
  for (const element of target) {
    const shape = assemblyShapeKey(element, IDENTITY_CUBE_ROTATION);
    const center = assemblyElementCenter(element, IDENTITY_CUBE_ROTATION);
    if (!shape || !center) continue;
    let centers = targetShapes.get(shape); if (!centers) { centers = []; targetShapes.set(shape, centers); }
    centers.push(center);
  }
  let best = [], translationPairs = 0;
  for (const rotation of CUBE_ROTATIONS) {
    const translations = new Map();
    for (const element of source) {
      const shape = assemblyShapeKey(element, rotation), center = assemblyElementCenter(element, rotation);
      if (!shape || !center) continue;
      for (const targetCenter of targetShapes.get(shape) || []) {
        if (++translationPairs > MAX_ASSEMBLY_TRANSLATION_PAIRS) return null;
        const value = center.map((coordinate, axis) =>
          Math.round((targetCenter[axis] - coordinate) * ASSEMBLY_QUANTIZE) / ASSEMBLY_QUANTIZE);
        const key = value.join(','), current = translations.get(key);
        if (current) current.votes++;
        else translations.set(key, {value, votes:1});
      }
    }
    const candidates = [...translations.values()].sort((left, right) =>
      right.votes - left.votes || left.value.join(',').localeCompare(right.value.join(',')))
      .slice(0, MAX_ASSEMBLY_TRANSLATIONS);
    for (const {value:translation} of candidates) {
      const candidate = matchAssemblyElements(source, target, rotation, translation);
      if (!candidate) continue;
      if (!best.length || candidate.count > best[0].count) best = [candidate];
      else if (candidate.count === best[0].count) best.push(candidate);
    }
  }
  return best.length && best[0].count === source.length ? {...best[0], alternatives:best} : null;
}

function mergeModel(files, modelId, depth, seen) {
  if (depth > MAX_MODEL_DEPTH || seen.has(modelId)) return null;
  const colon = modelId.indexOf(':'), namespace = colon >= 0 ? modelId.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? modelId.slice(colon + 1) : modelId;
  const modelName = name.replace(/^models\//, '');
  const path = 'assets/' + namespace + '/models/' + modelName + (modelName.endsWith('.json') ? '' : '.json');
  const json = jsonFile(files, path);
  if (!json) return null;
  const nextSeen = new Set(seen); nextSeen.add(modelId);
  let parent = null;
  if (json.parent) parent = mergeModel(files, json.parent, depth + 1, nextSeen);
  const textures = Object.assign({}, parent ? parent.textures : {}, json.textures || {});
  const elements = Array.isArray(json.elements) ? json.elements : (parent ? parent.elements : []);
  return {textures, elements, renderType:json.render_type || (parent && parent.renderType) || null,
    textureSize:Array.isArray(json.texture_size) ? json.texture_size : (parent && parent.textureSize),
    transform:json.transform !== undefined ? json.transform : (parent && parent.transform),
    visibility:Object.assign({}, parent ? parent.visibility : {}, json.visibility || {}),
    ambientOcclusion:json.ambientocclusion !== undefined ? json.ambientocclusion
      : parent ? parent.ambientOcclusion : true};
}

function resolveTexture(model, token) {
  let value = token;
  const seen = new Set();
  while (value && value.startsWith('#') && !seen.has(value)) {
    seen.add(value); value = model.textures[value.slice(1)];
  }
  if (!value || value.startsWith('#')) return null;
  return resourcePath(value, 'textures', '.png');
}

function faceGeometry(element, faceName, face, textureSize) {
  const axis = FACE_AXES[faceName];
  if (!axis || !face || !face.texture) return null;
  const from = element.from || [0,0,0], to = element.to || [16,16,16];
  const corners = [];
  const uv = face.uv || defaultFaceUv(from, to, faceName);
  /* Forge texture_size 扩展:模型 UV 按声明的网格归一(安山螺旋桨是 32×32),一律 /16 会采到错误象限 */
  const sizeU = Array.isArray(textureSize) && textureSize[0] > 0 ? textureSize[0] : 16;
  const sizeV = Array.isArray(textureSize) && textureSize[1] > 0 ? textureSize[1] : 16;
  for (const point of axis[3]) {
    // 只影响纹理坐标;下面的位置插值一律直接用 point[...]，不走这两个变量
    const u = axis[4] ? 1 - point[0] / 16 : point[0] / 16;
    const v = axis[5] ? 1 - point[1] / 16 : point[1] / 16;
    let x = from[0] + (to[0] - from[0]) * (point[0] / 16);
    let y = from[1] + (to[1] - from[1]) * (point[1] / 16);
    let z = from[2] + (to[2] - from[2]) * (point[0] / 16);
    if (faceName === 'up' || faceName === 'down') { x = from[0] + (to[0] - from[0]) * (point[0] / 16); z = from[2] + (to[2] - from[2]) * (point[1] / 16); y = faceName === 'up' ? to[1] : from[1]; }
    else if (faceName === 'north' || faceName === 'south') { x = from[0] + (to[0] - from[0]) * (point[0] / 16); y = from[1] + (to[1] - from[1]) * (point[1] / 16); z = faceName === 'south' ? to[2] : from[2]; }
    else { x = faceName === 'east' ? to[0] : from[0]; y = from[1] + (to[1] - from[1]) * (point[1] / 16); z = from[2] + (to[2] - from[2]) * (point[0] / 16); }
    const rotated = rotateElement([x, y, z], element.rotation);
    let tu = uv[0] / sizeU + u * ((uv[2]-uv[0]) / sizeU), tv = uv[1] / sizeV + v * ((uv[3]-uv[1]) / sizeV);
    for (let turn = ((Number(face.rotation) || 0) % 360 + 360) % 360; turn > 0; turn -= 90) [tu, tv] = [tv, 1 - tu];
    corners.push({position:[rotated[0] / 16 - .5, rotated[1] / 16 - .5, rotated[2] / 16 - .5], uv:[tu, 1 - tv]});
  }
  // FaceBakery 按最终角点绕序重算烘焙方向；模型可故意用 from > to 生成内向配对面。
  // 若强制翻回 faceName，内壁会被改成与外壁同向，换角度观察时就会漏空。
  const normal = faceNormal(corners);
  return {texture:face.texture, direction:faceName,
    tintIndex:Number.isInteger(face.tintindex) ? face.tintindex : -1,
    shade:face.shade !== false, normal, corners};
}

function defaultFaceUv(from, to, face) {
  if (face === 'down') return [from[0], 16 - to[2], to[0], 16 - from[2]];
  if (face === 'up') return [from[0], from[2], to[0], to[2]];
  if (face === 'south') return [from[0], 16 - to[1], to[0], 16 - from[1]];
  if (face === 'west') return [from[2], 16 - to[1], to[2], 16 - from[1]];
  if (face === 'east') return [16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]];
  return [16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]];
}

function rotateElement(point, rotation) {
  if (!rotation || !rotation.angle) return point;
  const origin = rotation.origin || [8,8,8], angle = Number(rotation.angle) * Math.PI / 180;
  const p = [point[0]-origin[0], point[1]-origin[1], point[2]-origin[2]], c=Math.cos(angle), s=Math.sin(angle);
  if (rotation.axis === 'x') [p[1],p[2]]=[p[1]*c-p[2]*s,p[1]*s+p[2]*c];
  else if (rotation.axis === 'y') [p[0],p[2]]=[p[0]*c+p[2]*s,-p[0]*s+p[2]*c];
  else if (rotation.axis === 'z') [p[0],p[1]]=[p[0]*c-p[1]*s,p[0]*s+p[1]*c];
  if (rotation.rescale) {
    const scale = 1 / Math.cos(Math.abs(angle));
    if (rotation.axis !== 'x') p[0] *= scale;
    if (rotation.axis !== 'y') p[1] *= scale;
    if (rotation.axis !== 'z') p[2] *= scale;
  }
  return [p[0]+origin[0],p[1]+origin[1],p[2]+origin[2]];
}

function identityMatrix() {
  return new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
}

function multiplyMatrix(left, right) {
  const result = new Float32Array(16);
  for (let column = 0; column < 4; column++) for (let row = 0; row < 4; row++) {
    let value = 0;
    for (let index = 0; index < 4; index++) value += left[index * 4 + row] * right[column * 4 + index];
    result[column * 4 + row] = value;
  }
  return result;
}

function translationMatrix(value) {
  const result = identityMatrix(); result[12] = value[0]; result[13] = value[1]; result[14] = value[2]; return result;
}

function scaleMatrix(value) {
  const result = identityMatrix(); result[0] = value[0]; result[5] = value[1]; result[10] = value[2]; return result;
}

function axisRotationMatrix(axis, degrees) {
  const angle = Number(degrees) * Math.PI / 180, c = Math.cos(angle), s = Math.sin(angle);
  if (!Number.isFinite(angle)) return null;
  if (axis === 'x') return new Float32Array([1,0,0,0, 0,c,s,0, 0,-s,c,0, 0,0,0,1]);
  if (axis === 'y') return new Float32Array([c,0,-s,0, 0,1,0,0, s,0,c,0, 0,0,0,1]);
  if (axis === 'z') return new Float32Array([c,s,0,0, -s,c,0,0, 0,0,1,0, 0,0,0,1]);
  return null;
}

function quaternionMatrix(value) {
  let [x, y, z, w] = value.map(Number);
  const length = Math.hypot(x, y, z, w); if (!length || !Number.isFinite(length)) return null;
  x /= length; y /= length; z /= length; w /= length;
  return new Float32Array([
    1-2*y*y-2*z*z, 2*x*y+2*w*z, 2*x*z-2*w*y, 0,
    2*x*y-2*w*z, 1-2*x*x-2*z*z, 2*y*z+2*w*x, 0,
    2*x*z+2*w*y, 2*y*z-2*w*x, 1-2*x*x-2*y*y, 0,
    0,0,0,1
  ]);
}

function rotationTransform(value) {
  if (Array.isArray(value) && value.length === 4 && value.every(Number.isFinite)) return quaternionMatrix(value);
  if (Array.isArray(value) && value.length === 3 && value.every(Number.isFinite)) {
    return multiplyMatrix(multiplyMatrix(axisRotationMatrix('x', value[0]), axisRotationMatrix('y', value[1])),
      axisRotationMatrix('z', value[2]));
  }
  if (Array.isArray(value) && value.every(item => item && typeof item === 'object')) {
    let result = identityMatrix();
    for (const item of value) {
      const next = rotationTransform(item); if (!next) return null; result = multiplyMatrix(result, next);
    }
    return result;
  }
  if (value && typeof value === 'object') {
    const keys = Object.keys(value); if (keys.length !== 1) return null;
    return axisRotationMatrix(keys[0], value[keys[0]]);
  }
  return null;
}

function directMatrix(value) {
  if (!Array.isArray(value) || value.length !== 3 || value.some(row => !Array.isArray(row) || row.length !== 4)) return null;
  const result = identityMatrix();
  for (let row = 0; row < 3; row++) for (let column = 0; column < 4; column++) {
    const entry = Number(value[row][column]); if (!Number.isFinite(entry)) return null;
    result[column * 4 + row] = entry;
  }
  return result;
}

function parseTransform(value) {
  if (value === undefined || value === null || value === 'identity') return identityMatrix();
  if (Array.isArray(value)) return directMatrix(value);
  if (!value || typeof value !== 'object') return null;
  if (value.matrix !== undefined) return Object.keys(value).length === 1 ? directMatrix(value.matrix) : null;
  const allowed = new Set(['translation','rotation','left_rotation','scale','right_rotation','post-rotation','origin']);
  if (Object.keys(value).some(key => !allowed.has(key))) return null;
  const translation = value.translation === undefined ? [0,0,0] : value.translation;
  const scale = value.scale === undefined ? [1,1,1] : Array.isArray(value.scale) ? value.scale : [value.scale,value.scale,value.scale];
  if (!Array.isArray(translation) || translation.length !== 3 || !translation.every(Number.isFinite)
      || !Array.isArray(scale) || scale.length !== 3 || !scale.every(Number.isFinite)) return null;
  const left = value.rotation !== undefined ? rotationTransform(value.rotation)
    : value.left_rotation !== undefined ? rotationTransform(value.left_rotation) : identityMatrix();
  const right = value.right_rotation !== undefined ? rotationTransform(value.right_rotation)
    : value['post-rotation'] !== undefined ? rotationTransform(value['post-rotation']) : identityMatrix();
  if (!left || !right) return null;
  let origin = [1,1,1];
  if (value.origin === 'center') origin = [.5,.5,.5];
  else if (value.origin === 'corner') origin = [0,0,0];
  else if (value.origin === 'opposing-corner' || value.origin === undefined) origin = [1,1,1];
  else if (Array.isArray(value.origin) && value.origin.length === 3 && value.origin.every(Number.isFinite)) origin = value.origin;
  else return null;
  const core = multiplyMatrix(multiplyMatrix(multiplyMatrix(translationMatrix(translation), left), scaleMatrix(scale)), right);
  return multiplyMatrix(multiplyMatrix(translationMatrix(origin), core), translationMatrix(origin.map(item => -item)));
}

function transformFaces(faces, transform) {
  if (!faces || transform === undefined || transform === null || transform === 'identity') return faces;
  const parsed = parseTransform(transform); if (!parsed) return null;
  const centered = multiplyMatrix(multiplyMatrix(translationMatrix([-.5,-.5,-.5]), parsed), translationMatrix([.5,.5,.5]));
  const result = faces.map(face => {
    const corners = face.corners.map(corner => {
      const point = corner.position, x = point[0] * centered[0] + point[1] * centered[4] + point[2] * centered[8] + centered[12];
      const y = point[0] * centered[1] + point[1] * centered[5] + point[2] * centered[9] + centered[13];
      const z = point[0] * centered[2] + point[1] * centered[6] + point[2] * centered[10] + centered[14];
      return {...corner, position:[x,y,z]};
    });
    return {...face, corners, normal:faceNormal(corners)};
  });
  if (faces.partial) result.partial = true;
  return result;
}

function bakeModel(files, modelId) {
  const json = modelJson(files, modelId);
  const loader = loaderId(json && json.loader);
  if (loader.includes('obj')) return bakeObj(files, json, {modelId});
  if (loader === 'neoforge:composite') return bakeComposite(files, json, {modelId});
  if (loader && !(json.loader && typeof json.loader === 'object' && json.loader.optional)) return null;
  const model = mergeModel(files, modelId, 0, new Set());
  if (!model) return null;
  return facesFromModel(model);
}

function facesFromModel(model) {
  return facesFromElements(model, model.elements || []);
}

function facesFromElements(model, elements) {
  const faces = [];
  for (const element of elements) {
    for (const name of Object.keys(FACE_AXES)) {
      const face = faceGeometry(element, name, element.faces && element.faces[name], model.textureSize);
      if (face) faces.push({...face, texturePath:resolveTexture(model, face.texture), renderType:model.renderType || null,
        shade:element.shade !== false && face.shade !== false,
        ambientOcclusion:model.ambientOcclusion !== false});
    }
  }
  return faces.length ? transformFaces(faces, model.transform) : null;
}

function directionFromNormal(normal) {
  let best = null, score = 0;
  for (const [name, axis] of Object.entries(FACE_AXES)) {
    const dot = normal[0] * axis[0] + normal[1] * axis[1] + normal[2] * axis[2];
    if (dot > score) { score = dot; best = name; }
  }
  return score > .999 ? best : null;
}

function rotateAssemblyFaces(faces, rotation, translation = [0,0,0]) {
  if (!faces) return null;
  return faces.map(face => {
    const corners = face.corners.map(corner => ({...corner,
      position:rotateAssemblyPoint(corner.position, rotation, true)
        .map((value, axis) => value + translation[axis] / 16)}));
    const normal = rotateAssemblyPoint(face.normal || faceNormal(corners), rotation, true);
    return {...face, corners, normal, direction:directionFromNormal(normal)};
  });
}

function assemblyFaceSignature(face) {
  const corners = face.corners.map(corner => {
    const position = corner.position.map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(',');
    const uv = (corner.uv || []).map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(',');
    return position + '@' + uv;
  }).sort().join(';');
  const normal = (face.normal || []).map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(',');
  const color = (face.color || []).map(value => Math.round(value * 255)).join(',');
  return [face.texturePath || '', face.renderType || '', face.direction || '', normal, corners, color,
    Number.isInteger(face.tintIndex) ? face.tintIndex : -1, face.emissive ? 'emissive' : '',
    face.shade === false ? 'unshaded' : 'shaded',
    face.ambientOcclusion === false ? 'no_ao' : 'ao'].join('|');
}

function assemblyFacesSignature(faces) {
  return (faces || []).map(assemblyFaceSignature).sort().join('||');
}

function assemblyFacePhaseSignature(face) {
  const corners = face.corners.map(corner => corner.position
    .map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(',')).sort().join(';');
  const normal = (face.normal || []).map(value => Math.round(value * ASSEMBLY_QUANTIZE)).join(',');
  return [face.texturePath || '', face.renderType || '', face.direction || '', normal, corners,
    Number.isInteger(face.tintIndex) ? face.tintIndex : -1, face.emissive ? 'emissive' : '',
    face.shade === false ? 'unshaded' : 'shaded',
    face.ambientOcclusion === false ? 'no_ao' : 'ao'].join('|');
}

function assemblyFacesPhaseSignature(faces) {
  return (faces || []).map(assemblyFacePhaseSignature).sort().join('||');
}

function unambiguousRotatedFaces(faces, alignment, options = {}) {
  const variants = (alignment.alternatives || [alignment])
    .map(candidate => rotateAssemblyFaces(faces, candidate.rotation, candidate.translation));
  if (new Set(variants.map(assemblyFacesSignature)).size === 1) return variants[0];
  if (options.allowUvPhase && new Set(variants.map(assemblyFacesPhaseSignature)).size === 1) {
    variants[0].partial = true;
    return variants[0];
  }
  return null;
}

function assemblyFaceMatchCount(sourceFaces, targetFaces) {
  const counts = new Map();
  for (const face of sourceFaces || []) {
    const key = assemblyFaceSignature(face); counts.set(key, (counts.get(key) || 0) + 1);
  }
  let score = 0;
  for (const face of targetFaces || []) {
    const key = assemblyFaceSignature(face), count = counts.get(key) || 0;
    if (!count) continue;
    score++; counts.set(key, count - 1);
  }
  return score;
}

function preferVisualAssemblyAlignment(sourceModel, targetModel, sourceElements, targetElements, alignment) {
  if (!alignment) return null;
  const scored = (alignment.alternatives || [alignment]).map(candidate => {
    let score = 0;
    for (const [sourceIndex, targetIndex] of candidate.pairs || []) {
      const sourceFaces = rotateAssemblyFaces(facesFromElements(sourceModel, [sourceElements[sourceIndex]]),
        candidate.rotation, candidate.translation);
      const targetFaces = facesFromElements(targetModel, [targetElements[targetIndex]]);
      score += assemblyFaceMatchCount(sourceFaces, targetFaces);
    }
    return {candidate, score};
  });
  const best = Math.max(...scored.map(value => value.score));
  if ((alignment.alternatives || []).length < 2 || best <= 0) return {...alignment, visualMatches:best};
  const candidates = scored.filter(value => value.score === best).map(value => value.candidate);
  return {...candidates[0], alternatives:candidates, visualMatches:best};
}

function modelIdFromPath(path) {
  if (!path.startsWith('assets/') || !path.endsWith('.json')) return null;
  const marker = path.indexOf('/models/');
  if (marker < 0) return null;
  const namespace = path.slice('assets/'.length, marker);
  const name = path.slice(marker + '/models/'.length, -'.json'.length);
  return namespace && name ? namespace + ':' + name : null;
}

function assemblySiblingModelIds(files, itemJson) {
  const parentPath = itemJson && typeof itemJson.parent === 'string'
    ? resourcePath(itemJson.parent, 'models', '.json') : null;
  if (!parentPath || !parentPath.endsWith('/item.json') || !parentPath.includes('/models/block/')) return [];
  const directory = parentPath.slice(0, parentPath.lastIndexOf('/') + 1), result = [];
  for (const path of [...files.keys()].sort()) {
    if (!path.startsWith(directory) || path === parentPath || !path.endsWith('.json')) continue;
    if (path.slice(directory.length).includes('/')) continue;
    const id = modelIdFromPath(path);
    if (id) result.push(id);
    if (result.length >= MAX_ASSEMBLY_MODELS) break;
  }
  return result;
}

function assemblyTextureSet(model) {
  const result = new Set();
  for (const element of model.elements || []) for (const face of Object.values(element.faces || {})) {
    const path = face && face.texture ? resolveTexture(model, face.texture) : null;
    if (path) result.add(path);
  }
  return result;
}

function assemblyElementTextures(model, element) {
  const result = new Set();
  for (const face of Object.values(element.faces || {})) {
    const path = face && face.texture ? resolveTexture(model, face.texture) : null;
    if (path) result.add(path);
  }
  return result;
}

function assemblyBounds(elements) {
  const points = [];
  for (const element of elements || []) {
    const value = assemblyElementPoints(element, IDENTITY_CUBE_ROTATION);
    if (!value) return null;
    points.push(...value);
  }
  if (!points.length) return null;
  return [0,1,2].map(axis => [Math.min(...points.map(point => point[axis])),
    Math.max(...points.map(point => point[axis]))]);
}

function boundsCoverage(container, content) {
  if (!container || !content) return 0;
  let intersection = 1, volume = 1;
  for (let axis = 0; axis < 3; axis++) {
    intersection *= Math.max(0, Math.min(container[axis][1], content[axis][1])
      - Math.max(container[axis][0], content[axis][0]));
    volume *= Math.max(0, content[axis][1] - content[axis][0]);
  }
  return volume ? intersection / volume : 0;
}

function assemblyElementInvariant(model, element) {
  const points = assemblyElementPoints(element, IDENTITY_CUBE_ROTATION);
  if (!points) return null;
  const distances = [];
  for (let left = 0; left < points.length; left++) for (let right = left + 1; right < points.length; right++) {
    const distance = points[left].reduce((sum, value, axis) =>
      sum + (value - points[right][axis]) ** 2, 0);
    distances.push(Math.round(distance * ASSEMBLY_QUANTIZE * ASSEMBLY_QUANTIZE));
  }
  const textures = [...assemblyElementTextures(model, element)].sort().join(',');
  return distances.sort((left, right) => left - right).join(',') + '|' + textures + '|'
    + Object.keys(element.faces || {}).length + '|' + (element.shade === false ? 'unshaded' : 'shaded');
}

function compatibleAssemblyVariant(base, variant) {
  if (!base || !variant || base.transform != null || variant.transform != null
      || base.elements.length !== variant.elements.length) return false;
  const signature = model => {
    const values = model.elements.map(element => assemblyElementInvariant(model, element));
    return values.every(Boolean) ? values.sort().join('||') : null;
  };
  const baseSignature = signature(base), variantSignature = signature(variant);
  return !!baseSignature && baseSignature === variantSignature;
}

function inferAssemblyComponents(options) {
  const {files, itemJson, item, targetElements, excluded, minimum, allowFull} = options;
  const groups = new Map(), itemTextures = assemblyTextureSet(item);
  for (const modelId of assemblySiblingModelIds(files, itemJson)) {
    if (excluded.has(modelId)) continue;
    const json = modelJson(files, modelId);
    if (!json || loaderId(json.loader)) continue;
    const model = mergeModel(files, modelId, 0, new Set());
    if (!model || model.transform != null || !Array.isArray(model.elements)
        || model.elements.length < minimum
        || model.elements.length > MAX_ASSEMBLY_COMPONENT_ELEMENTS) continue;
    if ([...assemblyTextureSet(model)].some(texture => !itemTextures.has(texture))) continue;
    let alignment = bestTranslatedAssemblyAlignment(model.elements, targetElements, minimum);
    alignment = preferVisualAssemblyAlignment(model, item, model.elements, targetElements, alignment);
    if (!alignment || alignment.visualMatches <= 0
        || (!allowFull && alignment.count === targetElements.length)) continue;
    const selected = alignment;
    const targetKey = [...selected.targetIndices].sort((left, right) => left - right).join(',');
    const alignedFaces = unambiguousRotatedFaces(facesFromElements(model, model.elements), selected);
    if (!alignedFaces) continue;
    const signature = assemblyFacesSignature(alignedFaces);
    const current = groups.get(targetKey);
    if (current && current.signature !== signature) {
      groups.set(targetKey, {ambiguous:true});
    } else if (!current || (!current.ambiguous && modelId.localeCompare(current.modelId) < 0)) {
      groups.set(targetKey, {modelId, model, alignment:selected, signature});
    }
  }
  const result = [], used = new Set();
  for (const component of [...groups.values()].filter(value => !value.ambiguous).sort((left, right) =>
    right.alignment.count - left.alignment.count || left.modelId.localeCompare(right.modelId))) {
    if ([...component.alignment.targetIndices].some(index => used.has(index))) continue;
    component.alignment.targetIndices.forEach(index => used.add(index));
    result.push(component);
  }
  return result;
}

function selectedAssemblyComponent(files, component, state) {
  if (!component) return null;
  const properties = parseProperties(state || ''), variants = new Map();
  for (const [name, value] of Object.entries(properties).sort()) {
    const suffix = value === 'true' ? name : value === 'false' ? null : value;
    if (!suffix || !/^[a-z0-9_.-]+$/.test(suffix)) continue;
    const modelId = component.modelId + '_' + suffix, json = modelJson(files, modelId);
    if (!json || loaderId(json.loader)) continue;
    const model = mergeModel(files, modelId, 0, new Set());
    if (compatibleAssemblyVariant(component.model, model)) variants.set(modelId, {modelId, model});
  }
  if (!variants.size) return {modelId:component.modelId, model:component.model};
  const choices = [...variants.values()].sort((left, right) => left.modelId.localeCompare(right.modelId));
  const signatures = new Set(choices.map(choice =>
    assemblyFacesSignature(facesFromElements(choice.model, choice.model.elements))));
  return signatures.size === 1 ? choices[0] : null;
}

function modelRole(modelId) {
  const slash = String(modelId || '').lastIndexOf('/');
  return slash >= 0 ? modelId.slice(slash + 1) : String(modelId || '').split(':').pop();
}

function roleAssemblyFaces(files, reference, modelId, selected) {
  const components = reference.components || [], claimed = new Set();
  for (const component of components) component.alignment.targetIndices.forEach(index => claimed.add(index));
  if (!components.length || selected.some(value => !value) || claimed.size !== reference.extras.length) return null;
  const from = modelRole(reference.modelId), to = modelRole(modelId);
  if (!from || !to || from === to || !/^[a-z0-9_.-]+$/.test(from + to)) return null;
  const pattern = new RegExp('(^|[_/])' + from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '([_/]|$)');
  const faces = [];
  for (let index = 0; index < components.length; index++) {
    const source = selected[index];
    const candidateId = source.modelId.replace(pattern, '$1' + to + '$2');
    if (candidateId === source.modelId) return null;
    const json = modelJson(files, candidateId), model = json && !loaderId(json.loader)
      ? mergeModel(files, candidateId, 0, new Set()) : null;
    if (!compatibleAssemblyVariant(source.model, model)) return null;
    const value = facesFromElements(model, model.elements);
    if (!value) return null;
    faces.push(...value);
  }
  return faces.length ? faces : null;
}

function containsAssemblyFaces(current, expected) {
  const counts = new Map();
  for (const face of current || []) {
    const key = assemblyFaceSignature(face);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  for (const face of expected || []) {
    const key = assemblyFaceSignature(face), count = counts.get(key) || 0;
    if (!count) return false;
    counts.set(key, count - 1);
  }
  return true;
}

function modelContainsAssemblyFaces(model, faces) {
  const current = facesFromElements(model, model.elements);
  if (!current || !faces?.length) return false;
  return CUBE_ROTATIONS.some(rotation => containsAssemblyFaces(current, rotateAssemblyFaces(faces, rotation)));
}

function inferComponentOnlyReference(files, id, itemJson, item, modelIds) {
  if (modelIds.length !== 1) return null;
  const modelId = modelIds[0], json = modelJson(files, modelId);
  const model = json && !loaderId(json.loader) ? mergeModel(files, modelId, 0, new Set()) : null;
  if (!model || model.transform != null || !Array.isArray(model.elements)) return null;
  const components = inferAssemblyComponents({files, itemJson, item, targetElements:item.elements,
    excluded:new Set(modelIds), minimum:1, allowFull:false});
  if (!components.length) return null;
  const sourceIndices = new Set();
  for (const component of components) component.alignment.targetIndices.forEach(index => sourceIndices.add(index));
  const extras = item.elements.filter((_, index) => sourceIndices.has(index));
  remapAssemblyComponents(components, sourceIndices);
  const faces = facesFromElements(item, extras);
  return faces && faces.length ? {modelId, model, item, extras,
    itemRotation:IDENTITY_CUBE_ROTATION, faces, components} : null;
}

function remapAssemblyComponents(components, sourceIndices) {
  const remap = new Map(), ordered = [...sourceIndices].sort((left, right) => left - right);
  ordered.forEach((source, index) => remap.set(source, index));
  for (const component of components) component.alignment = {...component.alignment,
    targetIndices:new Set([...component.alignment.targetIndices].map(index => remap.get(index)))};
}

function inferAlignedComponentReference(files, itemJson, item, modelIds) {
  const components = inferAssemblyComponents({files, itemJson, item, targetElements:item.elements,
    excluded:new Set(modelIds), minimum:1, allowFull:false});
  if (!components.length) return null;
  const sourceIndices = new Set();
  for (const component of components) component.alignment.targetIndices.forEach(index => sourceIndices.add(index));
  const componentFaces = facesFromElements(item, item.elements.filter((_, index) => sourceIndices.has(index)));
  let best = null;
  for (const modelId of modelIds) {
    const json = modelJson(files, modelId), model = json && !loaderId(json.loader)
      ? mergeModel(files, modelId, 0, new Set()) : null;
    if (!model || model.transform != null || !Array.isArray(model.elements)) continue;
    let alignment = bestAssemblyAlignment(item.elements, model.elements,
      MIN_COMPONENT_ALIGNMENT_MATCHES, MIN_COMPONENT_ALIGNMENT_RATIO);
    alignment = preferVisualAssemblyAlignment(item, model, item.elements, model.elements, alignment);
    if (!alignment || alignment.visualMatches <= 0
        || [...sourceIndices].some(index => alignment.sourceIndices.has(index))) continue;
    const variants = alignment.alternatives.map(candidate =>
      rotateAssemblyFaces(componentFaces, candidate.rotation));
    if (new Set(variants.map(assemblyFacesSignature)).size !== 1) continue;
    if (!best || alignment.count > best.alignment.count) best = {modelId, model, alignment, faces:variants[0]};
  }
  if (!best) return null;
  const extras = item.elements.filter((_, index) => sourceIndices.has(index));
  remapAssemblyComponents(components, sourceIndices);
  return {modelId:best.modelId, model:best.model, item, extras, itemRotation:best.alignment.rotation,
    faces:best.faces, components, alignmentMinimum:MIN_COMPONENT_ALIGNMENT_MATCHES,
    alignmentRatio:MIN_COMPONENT_ALIGNMENT_RATIO};
}

function inferNovelTextureReference(files, item, modelIds) {
  if (modelIds.length !== 1) return null;
  const modelId = modelIds[0], json = modelJson(files, modelId);
  const model = json && !loaderId(json.loader) ? mergeModel(files, modelId, 0, new Set()) : null;
  if (!model || model.transform != null || !Array.isArray(model.elements)
      || item.elements.length <= model.elements.length) return null;
  const baseTextures = assemblyTextureSet(model), itemTextures = assemblyTextureSet(item);
  if (![...baseTextures].some(texture => itemTextures.has(texture))) return null;
  const extras = item.elements.filter(element =>
    [...assemblyElementTextures(item, element)].some(texture => !baseTextures.has(texture)));
  if (!extras.length || extras.length > item.elements.length - model.elements.length + 2) return null;
  const baseBounds = assemblyBounds(model.elements), itemBounds = assemblyBounds(item.elements);
  if (boundsCoverage(itemBounds, baseBounds) < MIN_ASSEMBLY_RATIO) return null;
  const faces = facesFromElements(item, extras);
  return faces && faces.length ? {modelId, model, item, extras,
    itemRotation:IDENTITY_CUBE_ROTATION, faces, components:[]} : null;
}

function inferAssemblyReference(files, id) {
  const itemId = blockItemModelId(id), itemJson = modelJson(files, itemId);
  if (!itemJson || loaderId(itemJson.loader)) return null;
  const item = mergeModel(files, itemId, 0, new Set());
  if (!item || item.transform != null || !Array.isArray(item.elements)
      || !item.elements.length || item.elements.length > MAX_ASSEMBLY_ELEMENTS) return null;
  const modelIds = blockstateModelIds(files, id);
  let best = null;
  for (const modelId of modelIds) {
    const json = modelJson(files, modelId);
    if (!json || loaderId(json.loader)) continue;
    const model = mergeModel(files, modelId, 0, new Set());
    if (!model || model.transform != null || !Array.isArray(model.elements)) continue;
    let alignment = bestAssemblyAlignment(item.elements, model.elements);
    alignment = preferVisualAssemblyAlignment(item, model, item.elements, model.elements, alignment);
    if (!alignment || alignment.visualMatches <= 0 || alignment.count >= item.elements.length) continue;
    if (!best || alignment.count > best.alignment.count
        || (alignment.count === best.alignment.count && model.elements.length > best.model.elements.length)) {
      best = {modelId, model, alignment};
    }
  }
  if (!best) return inferComponentOnlyReference(files, id, itemJson, item, modelIds)
    || inferAlignedComponentReference(files, itemJson, item, modelIds)
    || inferNovelTextureReference(files, item, modelIds);
  const aligned = best.alignment.alternatives.map(alignment => {
    const extras = item.elements.filter((_, index) => !alignment.sourceIndices.has(index));
    const faces = rotateAssemblyFaces(facesFromElements(item, extras), alignment.rotation);
    return {alignment, extras, faces};
  });
  if (!aligned.length || new Set(aligned.map(value => assemblyFacesSignature(value.faces))).size !== 1) return null;
  const {alignment, extras, faces} = aligned[0];
  if (!extras.length || extras.length > MAX_ASSEMBLY_ELEMENTS - MIN_ASSEMBLY_MATCHES) return null;
  const components = inferAssemblyComponents({files, itemJson, item, targetElements:extras,
    excluded:new Set(modelIds), minimum:MIN_ASSEMBLY_MATCHES, allowFull:true});
  /* 不做"参考面必须映射到全部变体模型"的前置全覆盖检查:它会让一个对不上的小变体
     (如链式传动箱 middle 只有 3 元素)连坐掉所有本可修补的状态。assemblyFaces 里
     本来就逐模型对齐并做歧义守卫,对不上的状态自然只渲染壳。 */
  return faces && faces.length ? {modelId:best.modelId, model:best.model, item, extras,
    itemRotation:alignment.rotation, faces, components} : null;
}

function referenceAssemblyFaces(files, reference, selected) {
  const components = reference.components || [];
  if (selected.some(value => !value)) return null;
  const claimed = new Set();
  for (const component of components) component.alignment.targetIndices.forEach(index => claimed.add(index));
  let faces = facesFromElements(reference.item, reference.extras.filter((_, index) => !claimed.has(index))) || [];
  for (let index = 0; index < components.length; index++) {
    const component = components[index], choice = selected[index];
    const componentFaces = unambiguousRotatedFaces(
      facesFromElements(choice.model, choice.model.elements), component.alignment);
    if (!componentFaces) return null;
    faces = faces.concat(componentFaces);
  }
  return rotateAssemblyFaces(faces, reference.itemRotation);
}

function assemblyFaces(files, id, modelId, state) {
  const cache = shared && shared.loaded && shared.loaded.files === files ? assets.assemblies : null;
  const referenceKey = id + '|reference';
  if (cache && !cache.has(referenceKey)) cacheAssembly(referenceKey, inferAssemblyReference(files, id));
  const reference = cache ? cache.get(referenceKey) : inferAssemblyReference(files, id);
  if (!reference) return null;
  const selected = (reference.components || []).map(component => selectedAssemblyComponent(files, component, state));
  if (selected.some(value => !value)) return null;
  const key = id + '|' + modelId + '|' + (selected.map(value => value.modelId).join(',') || 'item');
  if (cache && cache.has(key)) return cache.get(key);
  const referenceFaces = referenceAssemblyFaces(files, reference, selected);
  let result = null;
  if (modelId === reference.modelId) result = referenceFaces;
  else {
    const json = modelJson(files, modelId), model = json && !loaderId(json.loader)
      ? mergeModel(files, modelId, 0, new Set()) : null;
    /* 变体模型比最小匹配数还小(传动箱 middle=3 元素)时按"全元素精确匹配"放行,
       否则 target.length < minimum 直接判 null,小变体永远得不到内部件。 */
    const modelMinimum = reference.alignmentMinimum || MIN_ASSEMBLY_MATCHES;
    const relaxed = model && Array.isArray(model.elements) && model.elements.length < modelMinimum;
    let alignment = model && model.transform == null
      ? bestAssemblyAlignment(reference.model.elements, model.elements,
        relaxed ? model.elements.length : modelMinimum,
        relaxed ? 1 : (reference.alignmentRatio || MIN_ASSEMBLY_RATIO)) : null;
    alignment = preferVisualAssemblyAlignment(reference.model, model,
      reference.model.elements, model && model.elements || [], alignment);
    if (model && modelContainsAssemblyFaces(model, referenceFaces)) result = [];
    else if (alignment) result = unambiguousRotatedFaces(referenceFaces, alignment, {allowUvPhase:true});
    else result = roleAssemblyFaces(files, reference, modelId, selected);
  }
  if (cache) cacheAssembly(key, result);
  return result;
}

/*
 * 定制拼装表:部件在资产里、装配在模组代码里的惯犯名单。
 * 这些方块的内部件既不在 blockstate 模型里,也偷不到(物品模型为空或 blockstate 是 multipart),
 * 但部件模型文件真实存在 —— 按属性把它们直接摆上去,等价于模组运行时干的事:
 * 管道按六个方向布尔接管臂(connection/<dir> 以世界朝向建模,不吃 blockstate 旋转,
 * 而这些 multipart 条目本来就不带 x/y);传动轮/水车的轮子随 blockstate 的 x/y 转。
 * 部件缺失或烘焙失败时静默跳过、标 partial(半成品守则)。
 */
const PIPE_DIRECTIONS = ['down', 'up', 'north', 'south', 'east', 'west'];
const CURATED_ASSEMBLY = {
  'create:fluid_pipe': properties => PIPE_DIRECTIONS.filter(direction => properties[direction] === 'true')
    .map(direction => 'create:block/fluid_pipe/connection/' + direction),
  'create:chain_conveyor': () => ['create:block/chain_conveyor/wheel',
    'create:block/chain_conveyor/shaft', 'create:block/chain_conveyor/guard'],
  'create:water_wheel': () => ['create:block/water_wheel/wheel']
};

function curatedAssemblyModelIds(id, state) {
  const recipe = CURATED_ASSEMBLY[id];
  return recipe ? recipe(parseProperties(state || '')) : null;
}

/* 多方块结构的哑方块:游戏里不渲染(本体由控制器整体画,如大水车的 OBJ 轮),
   模型无元素走降级会变成贴图完整方块、罩在本体上。这类直接不画、也不进简化清单。 */
const CURATED_INVISIBLE = new Set(['create:water_wheel_structure']);

function cacheAssembly(key, value) {
  if (assets.assemblies.has(key)) assets.assemblies.delete(key);
  assets.assemblies.set(key, value);
  while (assets.assemblies.size > MAX_ASSEMBLY_CACHE_ENTRIES) {
    assets.assemblies.delete(assets.assemblies.keys().next().value);
  }
}

function bakeComposite(files, json, options) {
  const modelId = options.modelId, depth = options.depth || 0;
  const counter = options.counter || {nodes:0}, inheritedTextures = options.inheritedTextures || {};
  if (depth > 8 || counter.nodes >= 64) return null;
  const parent = json.parent ? mergeModel(files, json.parent, 1, new Set()) : null;
  const ownTextures = Object.assign({}, inheritedTextures, parent ? parent.textures : {}, json.textures || {}), faces = [];
  const visibility = Object.assign({}, parent ? parent.visibility : {}, options.visibility || {}, json.visibility || {});
  let partial = false;
  for (const [name, child] of Object.entries(json.children || {})) {
    if (visibility[name] === false) continue;
    if (!child || typeof child !== 'object') { partial = true; continue; }
    if (++counter.nodes > 64) { partial = true; break; }
    let childFaces = null;
    if (child.loader === 'neoforge:composite') childFaces = bakeComposite(files, child, {
      modelId, depth:depth + 1, counter, inheritedTextures:ownTextures, visibility
    });
    else if (child.loader && String(child.loader).includes('obj')) childFaces = bakeObj(files, child, {
      modelId, inheritedTextures:ownTextures, visibility
    });
    else {
      let childParent = null;
      if (child.parent) childParent = mergeModel(files, child.parent, 1, new Set());
      const model = {textures:Object.assign({}, childParent ? childParent.textures : {}, ownTextures, child.textures || {}),
        elements:Array.isArray(child.elements) ? child.elements : (childParent ? childParent.elements : []),
        renderType:child.render_type || (childParent && childParent.renderType) || json.render_type || null,
        textureSize:Array.isArray(child.texture_size) ? child.texture_size
          : Array.isArray(json.texture_size) ? json.texture_size : (childParent && childParent.textureSize),
        transform:child.transform !== undefined ? child.transform : childParent && childParent.transform,
        ambientOcclusion:child.ambientocclusion !== undefined ? child.ambientocclusion
          : childParent ? childParent.ambientOcclusion : true};
      childFaces = facesFromModel(model);
    }
    if (childFaces) faces.push(...childFaces); else partial = true;
  }
  if (!faces.length) return null;
  const transformed = transformFaces(faces, json.transform !== undefined ? json.transform : parent && parent.transform);
  if (transformed && partial) transformed.partial = true;
  return transformed;
}

function modelJson(files, modelId) {
  const colon = modelId.indexOf(':'), namespace = colon >= 0 ? modelId.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? modelId.slice(colon + 1) : modelId;
  const modelName = name.replace(/^models\//, '');
  return jsonFile(files, 'assets/' + namespace + '/models/' + modelName + (modelName.endsWith('.json') ? '' : '.json'));
}

function assetPathFromFile(value, basePath) {
  if (!value) return null;
  if (value.startsWith('assets/')) return value;
  const colon = value.indexOf(':');
  if (colon >= 0) return 'assets/' + value.slice(0, colon) + '/' + value.slice(colon + 1).replace(/^\//, '');
  const slash = basePath.lastIndexOf('/');
  const base = slash >= 0 ? basePath.slice(0, slash + 1) : '';
  const parts = (base + value).split('/'), result = [];
  for (const part of parts) { if (!part || part === '.') continue; if (part === '..') result.pop(); else result.push(part); }
  const path = result.join('/'); return path.startsWith('assets/') ? path : null;
}

function parseMtl(files, path) {
  const bytes = files.get(path); if (!bytes) return new Map();
  const result = new Map(), text = new TextDecoder().decode(bytes); let material = null;
  for (const line of text.split(/\r?\n/)) {
    if (line.length > MAX_OBJ_LINE) return null;
    const value = line.trim();
    if (value.startsWith('newmtl ')) {
      const name = value.slice(7).trim();
      material = {name, texture:null, color:null, tintIndex:-1, ambient:false, source:path};
      result.set(name, material);
    } else if (value.startsWith('map_Kd ') && material) {
      material.texture = value.slice(7).trim().split(/\s+/).pop() || null;
    } else if ((value.startsWith('Kd ') || value.startsWith('Ka ')) && material) {
      const color = value.slice(3).trim().split(/\s+/).slice(0,3).map(Number);
      if (color.length !== 3 || !color.every(Number.isFinite)) return null;
      if (value.startsWith('Kd ')) material.color = color.map(channel => Math.max(0, Math.min(1, channel)));
      else material.ambient = color.some(channel => channel > 0);
    } else if ((value.startsWith('neoforge_TintIndex ') || value.startsWith('forge_TintIndex ')) && material) {
      const tint = Number(value.slice(value.indexOf(' ') + 1).trim());
      if (!Number.isInteger(tint)) return null;
      material.tintIndex = tint;
    }
    if (result.size > MAX_OBJ_MATERIALS) return null;
  }
  return result;
}

function resolveObjTexture(value, textures, materialPath) {
  if (!value) return null;
  if (value.startsWith('#')) return resolveTexture({textures}, value);
  if (value.includes(':')) return resourcePath(value, 'textures', '.png');
  const path = assetPathFromFile(value, materialPath);
  return path && path.startsWith('assets/') ? path : null;
}

function bakeObj(files, json, options) {
  const modelId = options.modelId, inheritedTextures = options.inheritedTextures || {};
  const colon = modelId.indexOf(':'), namespace = colon >= 0 ? modelId.slice(0, colon) : 'minecraft';
  const name = colon >= 0 ? modelId.slice(colon + 1) : modelId;
  const modelName = name.replace(/^models\//, '');
  const modelPath = 'assets/' + namespace + '/models/' + modelName + (modelName.endsWith('.json') ? '' : '.json');
  const objValue = json.model || json.obj || json.path;
  const objPath = assetPathFromFile(String(objValue || ''), modelPath);
  const source = objPath && files.get(objPath); if (!source) return null;
  const vertices = [], uvs = [], normals = [], colors = [], faces = [], materials = new Map();
  const usedMaterials = new Set();
  let material = '', mtllib = null, currentGroup = '', currentObject = '', objectAboveGroup = false;
  const text = new TextDecoder().decode(source);
  for (const line of text.split(/\r?\n/)) {
    if (line.length > MAX_OBJ_LINE) return null;
    const value = line.trim(); if (!value || value.startsWith('#')) continue;
    const parts = value.split(/\s+/), command = parts.shift();
    if (command === 'v' && parts.length >= 3) {
      const point = parts.slice(0,3).map(Number); if (!point.every(Number.isFinite)) return null; vertices.push(point);
    }
    else if (command === 'vt' && parts.length >= 2) {
      const uv = [Number(parts[0]), Number(parts[1])]; if (!uv.every(Number.isFinite)) return null; uvs.push(uv);
    }
    else if (command === 'vn' && parts.length >= 3) {
      const normal = parts.slice(0,3).map(Number); if (!normal.every(Number.isFinite)) return null; normals.push(normal);
    }
    else if (command === 'vc') {
      const color = parts.slice(0,4).map(Number); if (color.length < 3 || !color.every(Number.isFinite)) return null;
      while (color.length < 4) color.push(1); colors.push(color);
    }
    else if (command === 'usemtl') {
      material = parts.join(' '); usedMaterials.add(material); if (usedMaterials.size > MAX_OBJ_MATERIALS) return null;
    }
    else if (command === 'mtllib') mtllib = parts.join(' ');
    else if (command === 'g' && parts.length) {
      const name = parts[0];
      if (objectAboveGroup) currentObject = currentGroup + '/' + name;
      else { currentGroup = name; currentObject = ''; }
    }
    else if (command === 'o' && parts.length) {
      const name = parts[0];
      if (objectAboveGroup || !currentGroup) {
        objectAboveGroup = true; currentGroup = name; currentObject = '';
      } else currentObject = currentGroup + '/' + name;
    }
    else if (command === 'f') {
      if (parts.length < 3 || parts.length > 4 || faces.length >= MAX_OBJ_FACES) return null;
      faces.push({parts, material, component:currentObject || currentGroup});
    }
  }
  const libraries = json.mtl_override ? [String(json.mtl_override)] : (mtllib ? mtllib.split(/\s+/) : []);
  for (const path of libraries) {
    const mtl = assetPathFromFile(path, objPath); if (!mtl) continue;
    const parsed = parseMtl(files, mtl); if (parsed === null) return null;
    for (const [key, value] of parsed) materials.set(key, value);
    if (materials.size > MAX_OBJ_MATERIALS) return null;
  }
  const merged = mergeModel(files, modelId, 0, new Set());
  const textures = Object.assign({}, merged ? merged.textures : {}, inheritedTextures, json.textures || {});
  const visibility = Object.assign({}, merged ? merged.visibility : {}, options.visibility || {}, json.visibility || {});
  const result = [];
  const index = (value, length) => value < 0 ? length + value : value - 1;
  for (const polygon of faces) {
    const rootComponent = polygon.component.split('/')[0];
    if (visibility[rootComponent] === false || visibility[polygon.component] === false) continue;
    const refs = polygon.parts.map(value => value.split('/'));
    for (let i = 1; i + 1 < refs.length; i++) {
      const triplet = [refs[0], refs[i], refs[i + 1]], corners = [];
      for (const ref of triplet) {
        const vertexIndex = Number(ref[0]);
        if (!Number.isInteger(vertexIndex) || vertexIndex === 0) return null;
        const point = vertices[index(vertexIndex, vertices.length)]; if (!point) return null;
        const uvIndex = ref[1] ? Number(ref[1]) : 0;
        const normalIndex = ref[2] ? Number(ref[2]) : 0;
        const colorIndex = ref[3] ? Number(ref[3]) : 0;
        if (ref[1] && (!Number.isInteger(uvIndex) || uvIndex === 0)) return null;
        if (ref[2] && (!Number.isInteger(normalIndex) || normalIndex === 0)) return null;
        if (ref[3] && (!Number.isInteger(colorIndex) || colorIndex === 0)) return null;
        const uv = ref[1] ? uvs[index(uvIndex, uvs.length)] : [0, 0];
        const normal = ref[2] ? normals[index(normalIndex, normals.length)] : null;
        const color = ref[3] ? colors[index(colorIndex, colors.length)] : null;
        if (!uv || (ref[2] && !normal) || (ref[3] && !color)) return null;
        corners.push({position:[point[0] - .5, point[1] - .5, point[2] - .5],
          uv:[uv[0], json.flip_v ? 1 - uv[1] : uv[1]], color:color ? color.slice(0,3) : null});
        if (corners.length === 1 && normal) corners.normal = normal;
      }
      if (corners.length === 3) {
        const definition = materials.get(polygon.material);
        result.push({texturePath:definition ? resolveObjTexture(definition.texture, textures, definition.source || objPath) : null,
          tintIndex:definition ? definition.tintIndex : -1,
          color:definition && definition.color || null,
          emissive:!!(json.emissive_ambient && definition && definition.ambient),
          renderType:json.render_type || null,
          shade:json.shade_quads !== false,
          normal:corners.normal || faceNormal(corners), corners});
      }
    }
  }
  const transform = json.transform !== undefined ? json.transform : merged && merged.transform;
  return result.length ? transformFaces(result, transform) : null;
}

function firstModelTexture(files, modelId) {
  const model = mergeModel(files, modelId, 0, new Set());
  if (!model) return null;
  const value = model.textures.particle || Object.values(model.textures).find(Boolean);
  return resolveTexture(model, value);
}

function loaderId(loader) {
  if (typeof loader === 'string') return loader;
  return loader && typeof loader === 'object' ? String(loader.id || '') : '';
}

function modelFailureReason(files, choices, hasFluid) {
  if ((!choices || !choices.length) && !hasFluid) return 'blockstate_missing';
  for (const choice of choices || []) {
    const json = modelJson(files, choice.model);
    if (!json) return 'model_missing';
    const loader = loaderId(json.loader);
    if (!loader) continue;
    if (loader.includes('obj')) return 'obj_invalid_or_limit';
    if (loader === 'neoforge:composite') return 'composite_invalid_or_limit';
    if (loader && !(json.loader && typeof json.loader === 'object' && json.loader.optional)) return 'unknown_loader';
  }
  return 'model_invalid';
}

function fluidCell(state) {
  const properties = parseProperties(state.state || state.id || '');
  let fluid = null;
  if (state.id === 'minecraft:water' || properties.waterlogged === 'true') fluid = 'water';
  else if (state.id === 'minecraft:lava') fluid = 'lava';
  if (!fluid) return null;
  const level = Number(properties.level);
  const height = properties.waterlogged === 'true' || !Number.isFinite(level) || level === 0 || level >= 8
    ? 8 / 9 : Math.max(1 / 9, (8 - level) / 9);
  return {type:fluid, height};
}

function fluidFaces(state, value, grid) {
  const cell = fluidCell(state); if (!cell) return null;
  const {x, y, z} = value, type = cell.type;
  const at = (dx, dy, dz) => grid.get((x + dx) + ',' + (y + dy) + ',' + (z + dz));
  const same = (dx, dy, dz) => { const other = at(dx, dy, dz); return other && other.type === type ? other : null; };
  const corner = (dx, dz) => {
    const samples = [[0,0],[dx,0],[0,dz],[dx,dz]];
    let total = 0, weight = 0;
    for (const [sx, sz] of samples) {
      const sample = same(sx, 0, sz); if (!sample) continue;
      if (same(sx, 1, sz)) return 1;
      const sampleWeight = sample.height >= 8 / 9 ? 10 : 1;
      total += sample.height * sampleWeight; weight += sampleWeight;
    }
    return weight ? total / weight : cell.height;
  };
  const nw = corner(-1, -1), ne = corner(1, -1), se = corner(1, 1), sw = corner(-1, 1);
  const north = same(0, 0, -1), south = same(0, 0, 1), west = same(-1, 0, 0), east = same(1, 0, 0);
  const visible = {
    top:!same(0, 1, 0), bottom:!same(0, -1, 0),
    north:!north || north.height + 1e-6 < Math.max(nw, ne),
    south:!south || south.height + 1e-6 < Math.max(sw, se),
    west:!west || west.height + 1e-6 < Math.max(nw, sw),
    east:!east || east.height + 1e-6 < Math.max(ne, se)
  };
  const average = other => other ? other.height : cell.height;
  const flowX = average(west) - average(east), flowZ = average(north) - average(south);
  const flowing = Math.abs(flowX) + Math.abs(flowZ) > 1e-5;
  const stillTexture = 'assets/minecraft/textures/block/' + type + '_still.png';
  const flowTexture = 'assets/minecraft/textures/block/' + type + '_flow.png';
  const rotateUv = (u, v) => {
    if (!flowing) return [u, v];
    const angle = Math.atan2(flowZ, flowX), c = Math.cos(angle), s = Math.sin(angle);
    const px = (u - .5) * .5, py = (v - .5) * .5;
    return [.5 + px * c - py * s, .5 + px * s + py * c];
  };
  const face = (normal, corners, texturePath) => ({normal, corners, texturePath, renderType:'translucent'});
  const faces = [];
  const topCorners = [
    {position:[-.5,nw-.5,-.5],uv:rotateUv(0,0)}, {position:[-.5,sw-.5,.5],uv:rotateUv(0,1)},
    {position:[.5,se-.5,.5],uv:rotateUv(1,1)}, {position:[.5,ne-.5,-.5],uv:rotateUv(1,0)}
  ];
  if (visible.top) faces.push(face(faceNormal(topCorners), topCorners, flowing ? flowTexture : stillTexture));
  if (visible.bottom) faces.push(face([0,-1,0], [
    {position:[-.5,-.5,-.5],uv:[0,0]}, {position:[.5,-.5,-.5],uv:[1,0]},
    {position:[.5,-.5,.5],uv:[1,1]}, {position:[-.5,-.5,.5],uv:[0,1]}
  ], stillTexture));
  // 自上而下的绕序与 normal 同向;倒过来写这四面会被背面剔除,水池只剩顶面和底面。
  const side = (shown, normal, first, second) => {
    if (!shown) return;
    faces.push(face(normal, [
      {position:[first[0],first[1]-.5,first[2]],uv:[0,1-first[1]]},
      {position:[second[0],second[1]-.5,second[2]],uv:[1,1-second[1]]},
      {position:[second[0],-.5,second[2]],uv:[1,1]}, {position:[first[0],-.5,first[2]],uv:[0,1]}
    ], flowTexture));
  };
  side(visible.north, [0,0,-1], [-.5,nw,-.5], [.5,ne,-.5]);
  side(visible.south, [0,0,1], [.5,se,.5], [-.5,sw,.5]);
  side(visible.west, [-1,0,0], [-.5,sw,.5], [-.5,nw,-.5]);
  side(visible.east, [1,0,0], [.5,ne,-.5], [.5,se,.5]);
  const signature = [type, nw, ne, se, sw, flowing ? 1 : 0,
    visible.top, visible.bottom, visible.north, visible.south, visible.west, visible.east]
    .map(item => typeof item === 'number' ? item.toFixed(5) : typeof item === 'boolean' ? (item ? 1 : 0) : String(item)).join('|');
  return {signature, faces};
}

function faceNormal(corners) {
  const a = corners[0].position, b = corners[1].position, c = corners[2].position;
  const ab = [b[0]-a[0], b[1]-a[1], b[2]-a[2]], ac = [c[0]-a[0], c[1]-a[1], c[2]-a[2]];
  const normal = [ab[1]*ac[2]-ab[2]*ac[1], ab[2]*ac[0]-ab[0]*ac[2], ab[0]*ac[1]-ab[1]*ac[0]];
  const length = Math.hypot(...normal) || 1;
  return normal.map(value => value / length);
}

function isFullCubeFaces(faces) {
  if (!faces || !faces.length) return false;
  const planes = new Set();
  for (const face of faces) {
    if (!face.corners || face.corners.length < 4) continue;
    for (const [axis, low, high] of [[0,'west','east'],[1,'down','up'],[2,'north','south']]) {
      const first = face.corners[0].position[axis];
      if (Math.abs(Math.abs(first) - .5) > 1e-5
          || face.corners.some(corner => Math.abs(corner.position[axis] - first) > 1e-5)) continue;
      const other = [0,1,2].filter(value => value !== axis);
      if (other.every(value => {
        const coordinates = face.corners.map(corner => corner.position[value]);
        return Math.min(...coordinates) <= -.5 + 1e-5 && Math.max(...coordinates) >= .5 - 1e-5;
      })) planes.add(first < 0 ? low : high);
    }
  }
  return ['west','east','down','up','north','south'].every(value => planes.has(value));
}

/*
 * blockstate 的整体旋转,原版语义是 Ry(−y)·Rx(−x)(见 BlockModelRotation)。
 *
 * 这里此前写的是它的转置 —— 也就是逆旋转。纯 y 的情形里 0°/180° 自逆、90°/270° 互换,
 * 四个朝向看上去仍然"各不相同",所以之前所有"它们不一样"式的检查都放它过去了;
 * 真正露馅的是 x 和 y 同时非零的 variant(create:shaft 的 axis=x 是 {x:90,y:90},
 * 转置之后杆躺到了 Z 轴上),以及朝东/朝西互换的炉子、楼梯、墙上火把。
 */
function matrix(x, y, z, rx, ry) {
  const sx = Math.sin(rx * Math.PI / 180), cx = Math.cos(rx * Math.PI / 180);
  const sy = Math.sin(ry * Math.PI / 180), cy = Math.cos(ry * Math.PI / 180);
  // column-major affine matrix, rotation Y then X and translation to voxel center
  return new Float32Array([cy, 0, sy, 0, sx*sy, cx, -sx*cy, 0, -cx*sy, sx, cx*cy, 0, x, y, z, 1]);
}

/*
 * uvlock:blockstate 里整体旋转模型后,纹理仍锁在世界坐标上 —— 楼梯/栅栏/墙/按钮/活板门
 * 的顶底面靠它才不会跟着转(1062 个原版 blockstate 里 125 个用到)。
 *
 * 旋转只有 90° 的整数倍,所以修正量必然是 uv 的 k×90°。这里不照搬原版的矩阵管线,而是直接用
 * 本文件已校准的每面 uv 轴方向解 k:把该面的 (u 轴, w 轴) 过同一个 matrix(),再问要转几次
 * 才能和旋转后所落到那个面的标准轴重合。一次 uv 旋转对最终 uv 是 (A,B) → (1−B,A),
 * 对应世界轴 (U,W) → (−W,U)。
 *
 * 每项: [法线, u 轴世界方向, 最终 uv.y 增大的世界方向]。
 */
const FACE_UV_FRAME = {
  down:  [[0,-1,0], [1,0,0], [0,0,1]],
  up:    [[0,1,0], [1,0,0], [0,0,-1]],
  north: [[0,0,-1], [-1,0,0], [0,1,0]],
  south: [[0,0,1], [1,0,0], [0,1,0]],
  west:  [[-1,0,0], [0,0,1], [0,1,0]],
  east:  [[1,0,0], [0,0,-1], [0,1,0]]
};

function uvLockTurn(direction, rotationX, rotationY) {
  const frame = FACE_UV_FRAME[direction];
  if (!frame || (!rotationX && !rotationY)) return 0;
  const m = matrix(0, 0, 0, rotationX, rotationY);
  // 90° 的整数倍旋转下分量非 0 即 ±1;sin(180°) 在浮点里是 1.2e-16,取整即可
  const apply = value => [
    value[0] * m[0] + value[1] * m[4] + value[2] * m[8],
    value[0] * m[1] + value[1] * m[5] + value[2] * m[9],
    value[0] * m[2] + value[1] * m[6] + value[2] * m[10]
  ].map(item => Math.round(item) || 0);
  const same = (left, right) => left[0] === right[0] && left[1] === right[1] && left[2] === right[2];
  const landed = apply(frame[0]);
  const target = Object.values(FACE_UV_FRAME).find(item => same(item[0], landed));
  if (!target) return 0;
  let u = apply(frame[1]), w = apply(frame[2]);
  for (let turn = 0; turn < 360; turn += 90) {
    if (same(u, target[1])) return turn;
    [u, w] = [w.map(item => -item || 0), u];
  }
  return 0;
}

function rotateFaceUv(face, turns) {
  return {...face, corners:face.corners.map(corner => {
    let [a, b] = corner.uv;
    for (let i = 0; i < turns; i++) [a, b] = [1 - b, a];
    return {...corner, uv:[a, b]};
  })};
}

function addFace(batch, face, transform, tintColor) {
  const corners = face.corners || [];
  if (corners.length < 3) return 0;
  const base = batch.positions.length / 3;
  const sourceNormal = face.normal || [0, 1, 0], m = transform;
  let nx = sourceNormal[0] * m[0] + sourceNormal[1] * m[4] + sourceNormal[2] * m[8];
  let ny = sourceNormal[0] * m[1] + sourceNormal[1] * m[5] + sourceNormal[2] * m[9];
  let nz = sourceNormal[0] * m[2] + sourceNormal[1] * m[6] + sourceNormal[2] * m[10];
  const normalLength = Math.hypot(nx, ny, nz) || 1;
  nx /= normalLength; ny /= normalLength; nz /= normalLength;
  for (const corner of corners) {
    const p = corner.position;
    const x = p[0] * m[0] + p[1] * m[4] + p[2] * m[8] + m[12];
    const y = p[0] * m[1] + p[1] * m[5] + p[2] * m[9] + m[13];
    const z = p[0] * m[2] + p[1] * m[6] + p[2] * m[10] + m[14];
    batch.positions.push(x, y, z); batch.normals.push(nx, ny, nz); batch.uvs.push(...(corner.uv || [0, 0]));
    const color = tintColor || [1, 1, 1], vertexColor = corner.color || [1, 1, 1];
    batch.colors.push(color[0] * vertexColor[0], color[1] * vertexColor[1], color[2] * vertexColor[2]);
  }
  for (let index = 1; index + 1 < corners.length; index++) {
    batch.indices.push(base, base + index, base + index + 1);
  }
  return corners.length - 2;
}

function placeTexture(page, item) {
  const width = item.bitmap.width + ATLAS_PADDING * 2;
  const height = item.bitmap.height + ATLAS_PADDING * 2;
  if (width > page.size || height > page.size) return null;
  if (page.x + width > page.size) {
    page.x = 0; page.y += page.rowHeight; page.rowHeight = 0;
  }
  if (page.y + height > page.size) return null;
  const placement = {page:page.index, size:page.size, x:page.x + ATLAS_PADDING, y:page.y + ATLAS_PADDING,
    width:item.bitmap.width, height:item.bitmap.height};
  page.items.push({item, placement});
  page.x += width; page.rowHeight = Math.max(page.rowHeight, height);
  return placement;
}

function drawWithPadding(context, bitmap, placement) {
  const {x, y, width, height} = placement, padding = ATLAS_PADDING;
  context.drawImage(bitmap, x, y, width, height);
  context.drawImage(bitmap, 0, 0, 1, height, x - padding, y, padding, height);
  context.drawImage(bitmap, width - 1, 0, 1, height, x + width, y, padding, height);
  context.drawImage(bitmap, 0, 0, width, 1, x, y - padding, width, padding);
  context.drawImage(bitmap, 0, height - 1, width, 1, x, y + height, width, padding);
  context.drawImage(bitmap, 0, 0, 1, 1, x - padding, y - padding, padding, padding);
  context.drawImage(bitmap, width - 1, 0, 1, 1, x + width, y - padding, padding, padding);
  context.drawImage(bitmap, 0, height - 1, 1, 1, x - padding, y + height, padding, padding);
  context.drawImage(bitmap, width - 1, height - 1, 1, 1, x + width, y + height, padding, padding);
}

function atlasUv(placement) {
  return [placement.x / placement.size,
    (placement.size - placement.y - placement.height) / placement.size,
    placement.width / placement.size, placement.height / placement.size];
}

function atlasPageBytes(alpha, size) {
  const mipFactor = alpha === 'cutout' ? 1 : 4 / 3;
  return Math.ceil(size * size * 4 * mipFactor);
}

async function packAtlases(textures, batches, fallback, budget, retained) {
  if (typeof OffscreenCanvas === 'undefined' || !textures.length) {
    return {textures, batches, fallback};
  }
  const atlasSize = Math.max(512, Math.min(ATLAS_SIZE, Number(budget && budget.atlasSize) || ATLAS_SIZE));
  const textureBudget = Math.max(0, Number(budget && budget.textureBytes) || 0);
  const pages = [], placements = new Map(), sources = new Map(textures.map(item => [item.path, item]));
  let pageBytes = 0;
  const requested = new Map();
  for (const batch of batches) {
    const source = sources.get(batch.texture); if (!source) continue;
    const alpha = normalizeRenderType(batch.renderType) || source.alpha;
    batch.atlasKey = batch.texture + '|' + alpha;
    const previous = requested.get(batch.atlasKey);
    requested.set(batch.atlasKey, {...source, path:batch.atlasKey, alpha,
      optional:previous ? previous.optional && !!batch.assembly : !!batch.assembly});
  }
  for (const item of fallback) {
    const source = sources.get(item.texture); if (!source) continue;
    const alpha = normalizeRenderType(item.renderType) || source.alpha;
    item.atlasKey = item.texture + '|' + alpha;
    requested.set(item.atlasKey, {...source, path:item.atlasKey, alpha, optional:false});
  }
  const ordered = [...requested.values()].sort((a, b) => Number(a.optional) - Number(b.optional)
    || a.alpha.localeCompare(b.alpha)
    || b.bitmap.height - a.bitmap.height || b.bitmap.width - a.bitmap.width || a.path.localeCompare(b.path));
  for (const item of ordered) {
    let placement = null;
    for (const page of pages) {
      if (page.alpha !== item.alpha) continue;
      placement = placeTexture(page, item);
      if (placement) break;
    }
    const nextPageBytes = atlasPageBytes(item.alpha, atlasSize);
    if (!placement && pages.length < MAX_ATLAS_PAGES && pageBytes + nextPageBytes <= textureBudget) {
      const page = {index:pages.length, alpha:item.alpha, size:atlasSize, x:0, y:0, rowHeight:0, items:[]};
      pages.push(page); pageBytes += nextPageBytes; placement = placeTexture(page, item);
    }
    if (placement) placements.set(item.path, placement);
  }
  const atlasTextures = [], builtPages = new Set();
  for (const page of pages) {
    const canvas = new OffscreenCanvas(page.size, page.size), context = canvas.getContext('2d');
    if (!context) continue;
    context.clearRect(0, 0, page.size, page.size);
    // ImageBitmap uploads ignore WebGL flipY, so bake that flip into the atlas itself.
    context.translate(0, page.size);
    context.scale(1, -1);
    for (const value of page.items) drawWithPadding(context, value.item.bitmap, value.placement);
    atlasTextures.push({path:'__atlas__/' + page.index, bitmap:canvas.transferToImageBitmap(), alpha:page.alpha});
    builtPages.add(page.index);
  }
  for (const bitmap of new Set(textures.map(item => item.bitmap))) {
    if (retained && retained.has(bitmap)) continue;   // 共享缓存里的源位图下一 bake 还要画进新图集
    if (bitmap && bitmap.close) bitmap.close();
  }
  const packedBatches = [];
  for (const batch of batches) {
    const placement = placements.get(batch.atlasKey);
    if (!placement || !builtPages.has(placement.page)) continue;
    const uv = atlasUv(placement);
    for (let index = 0; index < batch.uvs.length; index += 2) {
      batch.uvs[index] = uv[0] + batch.uvs[index] * uv[2];
      batch.uvs[index + 1] = uv[1] + batch.uvs[index + 1] * uv[3];
    }
    batch.texture = '__atlas__/' + placement.page;
    packedBatches.push(batch);
  }
  const packedFallback = [];
  for (const item of fallback) {
    const placement = placements.get(item.atlasKey);
    if (!placement || !builtPages.has(placement.page)) continue;
    packedFallback.push({...item, atlasKey:undefined, texture:'__atlas__/' + placement.page, uv:atlasUv(placement)});
  }
  return {textures:atlasTextures, batches:packedBatches, fallback:packedFallback};
}

function batchWorkingBytes(batch) {
  return (batch.positions.length + batch.normals.length + batch.uvs.length + batch.colors.length
    + batch.indices.length + batch.instances.length) * 8;
}

function batchGpuBytes(batch) {
  const geometryValues = batch.positions.length + batch.normals.length + batch.uvs.length
    + batch.colors.length + batch.indices.length;
  const instanceMatrices = batch.instances.length / 3 * 16;
  return (geometryValues + instanceMatrices) * 4;
}

function withoutAssembly(result) {
  if (!result || !result.batches || ![...result.batches.values()].some(batch => batch.assembly)) return result;
  const batches = new Map([...result.batches].filter(([, batch]) => !batch.assembly));
  const triangles = [...batches.values()].reduce((sum, batch) => sum + batchTriangleCost(batch), 0);
  return {...result, batches, triangles, partial:true};
}

function removeCommittedAssembly(batches, partialStates) {
  let removed = false;
  for (const [key, batch] of batches) {
    if (!batch.assembly) continue;
    partialStates.add(batch.stateIndex);
    batches.delete(key); removed = true;
  }
  return removed;
}

function createBatch(key, texture, renderType) {
  return {key, texture, renderType:normalizeRenderType(renderType), emissive:false, shade:true,
    positions:[], normals:[], uvs:[], colors:[], indices:[], instances:[], faceKeys:new Set(),
    stateIndex:-1, group:-1, assembly:false};
}

function normalizeRenderType(value) {
  const type = String(value || '').split(':').pop();
  return ['solid', 'cutout', 'cutout_mipped', 'translucent'].includes(type) ? type : '';
}

function vanillaRenderType(id) {
  if (!String(id || '').startsWith('minecraft:')) return '';
  const name = String(id).slice('minecraft:'.length);
  if (name === 'water' || name === 'lava' || name === 'ice' || name === 'frosted_ice'
      || name === 'tinted_glass' || name === 'honey_block' || name === 'slime_block'
      || name === 'beacon' || name.includes('glass')) return 'translucent';
  if (name.endsWith('_leaves') || name === 'iron_bars') return 'cutout_mipped';
  if (name.endsWith('_door') || name.includes('sapling') || name.includes('flower')
      || name.includes('mushroom') || name.includes('grass') || name.includes('fern')
      || name.includes('crop') || name.includes('roots') || name.includes('vine')
      || name.includes('torch') || name.includes('rail') || name === 'ladder'
      || name === 'tripwire' || name === 'redstone_wire') return 'cutout';
  return '';
}

function tintColor(value, base) {
  if (value == null && !base) return null;
  const color = value == null ? [1, 1, 1]
    : [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
  return base ? color.map((channel, index) => channel * base[index]) : color;
}

function modelTint(state, tintIndex, biome) {
  if (tintIndex < 0 || !String(state.id || '').startsWith('minecraft:')) return null;
  const name = String(state.id).slice('minecraft:'.length);
  const properties = parseProperties(state.state || state.id || '');
  if (name === 'water' || name === 'bubble_column' || name === 'water_cauldron') return biome.water;
  if (name === 'spruce_leaves') return 0x619961;
  if (name === 'birch_leaves') return 0x80a755;
  if (name.endsWith('_leaves') || name === 'vine') return biome.foliage;
  if (name === 'redstone_wire') {
    const power = Math.max(0, Math.min(15, Number(properties.power) || 0)), value = power / 15;
    const red = value * .6 + (value > 0 ? .4 : .3);
    const green = Math.max(0, Math.min(1, value * value * .7 - .5));
    const blue = Math.max(0, Math.min(1, value * value * .6 - .7));
    return (Math.round(red * 255) << 16) | (Math.round(green * 255) << 8) | Math.round(blue * 255);
  }
  if (name === 'melon_stem' || name === 'pumpkin_stem') {
    const age = Math.max(0, Math.min(7, Number(properties.age) || 0));
    return (age * 32 << 16) | ((255 - age * 8) << 8) | age * 4;
  }
  if (name === 'lily_pad') return 0x208030;
  if (name === 'grass_block' || name.includes('grass') || name.includes('fern')
      || name === 'sugar_cane' || name === 'large_fern') return biome.grass;
  return null;
}

function appendModelFaces(options) {
  const {local, faces, kind, modelKey, choice, value, state, stateIndex, biome, texturePaths} = options;
  const rotationX = Number(choice.x) || 0, rotationY = Number(choice.y) || 0;
  const transform = matrix(0, 0, 0, rotationX, rotationY), touched = new Set();
  let triangles = 0;
  for (let faceIndex = 0; faceIndex < faces.length; faceIndex++) {
    const face = faces[faceIndex];
    if (!face.texturePath || !face.corners || face.corners.length < 3) continue;
    const tintValue = modelTint(state, face.tintIndex, biome);
    const renderType = normalizeRenderType(face.renderType) || vanillaRenderType(state.id);
    const key = stateIndex + '|g' + value.g + '|' + kind + '|' + modelKey + '|' + face.texturePath + '|'
      + rotationX + '|' + rotationY + '|' + (tintValue == null ? '' : tintValue)
      + '|' + renderType + '|' + (face.emissive ? 'e' : '')
      + '|' + (face.shade === false ? 'unshaded' : 'shaded')
      + '|' + (choice.uvlock === true ? 'lock' : '');
    let batch = local.get(key);
    if (!batch) {
      batch = createBatch(key, face.texturePath, renderType);
      batch.stateIndex = stateIndex; batch.group = value.g; batch.emissive = !!face.emissive;
      batch.shade = face.shade !== false; batch.assembly = kind === 'assembly'; local.set(key, batch);
    }
    if (!batch.faceKeys.has(faceIndex)) {
      const lock = choice.uvlock === true ? uvLockTurn(face.direction, rotationX, rotationY) : 0;
      addFace(batch, lock ? rotateFaceUv(face, lock / 90) : face,
        transform, tintColor(tintValue, face.color));
      batch.faceKeys.add(faceIndex);
    }
    triangles += face.corners.length - 2;
    touched.add(batch); texturePaths.add(face.texturePath);
  }
  for (const batch of touched) batch.instances.push(value.x, value.y, value.z);
  return {triangles, ready:touched.size > 0};
}

function batchTriangleCost(batch) {
  return batch.indices.length / 3 * (batch.instances.length / 3);
}

/* Build one state in a private map.  A state is committed only when every one of
   its instances fits the budget; otherwise its complete low-fidelity group stays
   visible.  This prevents a shared state group from losing unprocessed blocks. */
function bakeState(values, stateIndex, palette, loaded, cachedModel, fluidCache, fluidGrid, biome,
                   originX, originY, originZ, currentTriangles, maxTriangles) {
  const state = palette[stateIndex];
  if (!state || !state.id) return null;
  const local = new Map(), texturePaths = new Set();
  let cost = 0, partial = false, fullCube = true, assemblyBudgetExceeded = false;
  for (const value of values) {
    const choices = blockstateModels(loaded.files, state.id, state.state || state.id,
      minecraftModelSeed(value.x + originX, value.y + originY, value.z + originZ));
    const fluidDefinition = fluidFaces(state, value, fluidGrid);
    let fluid = null;
    if (fluidDefinition) {
      if (!fluidCache.has(fluidDefinition.signature)) fluidCache.set(fluidDefinition.signature, fluidDefinition.faces);
      fluid = fluidCache.get(fluidDefinition.signature);
    }
    let voxelReady = false, voxelFullCube = false;
    for (const choice of choices) {
      const faces = cachedModel(choice.model);
      if (!faces || faces.length > MAX_MODEL_FACES) continue;
      if (faces.partial) partial = true;
      if (isFullCubeFaces(faces)) voxelFullCube = true;
      const appended = appendModelFaces({local, faces, kind:'model', modelKey:choice.model, choice, value,
        state, stateIndex, biome, texturePaths});
      if (appended.ready) voxelReady = true;
    }
    if (voxelReady && choices.length === 1 && !assemblyBudgetExceeded) {
      const choice = choices[0], assembled = assemblyFaces(loaded.files, state.id, choice.model,
        state.state || state.id);
      if (assembled && assembled.length <= MAX_MODEL_FACES) {
        if (assembled.partial) partial = true;
        appendModelFaces({local, faces:assembled, kind:'assembly', modelKey:state.id,
          choice, value, state, stateIndex, biome, texturePaths});
      }
    }
    if (voxelReady && !assemblyBudgetExceeded) {
      const curated = curatedAssemblyModelIds(state.id, state.state || state.id);
      if (curated) {
        const choice = choices[0] || {};
        for (const curatedId of curated) {
          const faces = cachedModel(curatedId);
          if (!faces || faces.length > MAX_MODEL_FACES) { partial = true; continue; }
          appendModelFaces({local, faces, kind:'assembly', modelKey:'curated|' + curatedId,
            choice, value, state, stateIndex, biome, texturePaths});
        }
      }
    }
    if (fluid && fluid.length) {
      voxelFullCube = false;
      const transform = matrix(0, 0, 0, 0, 0);
      const tintValue = state.id === 'minecraft:water' || String(state.state || '').includes('waterlogged=true')
        ? biome.water : null;
      const touched = new Set();
      for (let faceIndex = 0; faceIndex < fluid.length; faceIndex++) {
        const face = fluid[faceIndex];
        if (!face.texturePath || !face.corners || face.corners.length < 3) continue;
        const key = stateIndex + '|g' + value.g + '|fluid|' + fluidDefinition.signature + '|'
          + face.texturePath + '|' + (tintValue == null ? '' : tintValue);
        let batch = local.get(key);
        if (!batch) {
          batch = createBatch(key, face.texturePath, 'translucent');
          batch.stateIndex = stateIndex; batch.group = value.g; local.set(key, batch);
        }
        if (!batch.faceKeys.has(faceIndex)) {
          addFace(batch, face, transform, tintColor(tintValue));
          batch.faceKeys.add(faceIndex);
        }
        touched.add(batch); texturePaths.add(face.texturePath);
      }
      for (const batch of touched) batch.instances.push(value.x, value.y, value.z);
      if (touched.size) voxelReady = true;
    }
    if (!voxelReady) return null;
    let nextCost = [...local.values()].reduce((sum, batch) => sum + batchTriangleCost(batch), 0);
    if (currentTriangles + nextCost > maxTriangles
        && [...local.values()].some(batch => batch.assembly)) {
      partial = true; assemblyBudgetExceeded = true;
      for (const [key, batch] of local) if (batch.assembly) local.delete(key);
      nextCost = [...local.values()].reduce((sum, batch) => sum + batchTriangleCost(batch), 0);
    }
    if (currentTriangles + nextCost > maxTriangles) return {failure:'budget'};
    fullCube = fullCube && voxelFullCube;
    cost = nextCost;
  }
  if (!local.size || !cost) return null;
  return {batches:local, triangles:cost, texturePaths, count:values.length, partial, fullCube};
}

function removeEnclosedInstances(output, groups, fullCubeStates, textures, bounds) {
  const modes = new Map(textures.map(texture => [texture.path, texture.alpha]));
  const stateModes = new Map();
  for (const batch of output) {
    if (batch.assembly) continue;
    let values = stateModes.get(batch.stateIndex); if (!values) { values = []; stateModes.set(batch.stateIndex, values); }
    values.push(normalizeRenderType(batch.renderType) || modes.get(batch.texture));
  }
  const opaque = new Set([...fullCubeStates].filter(state => {
    const values = stateModes.get(state);
    return values && values.length && values.every(mode => mode === 'solid');
  }));
  if (!opaque.size) return;
  /* 整数序号占用表。此前每个体素建一个 "x,y,z" 字符串键,每次遮挡判定再拼六个,
     并且每次调用还重新分配一遍六方向偏移数组。越界返回 -1,占用表里永远没有 -1,
     所以结构边界上的方块不会被误判为被包围。 */
  const width = Number(bounds && bounds.width) || 0;
  const height = Number(bounds && bounds.height) || 0;
  const depth = Number(bounds && bounds.depth) || 0;
  const cell = (x, y, z) => x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth
    ? -1 : (y * depth + z) * width + x;
  const occupied = new Map();
  // 转起来的方块存的是装配姿态,拿它当遮挡体会把转开后露出来的静态方块剔没
  for (const [state, values] of groups) for (const value of values) {
    if (value.g < 0) occupied.set(cell(value.x, value.y, value.z), state);
  }
  const enclosed = (x, y, z) => opaque.has(occupied.get(cell(x, y, z)));
  const hidden = (x, y, z) => enclosed(x + 1, y, z) && enclosed(x - 1, y, z)
    && enclosed(x, y + 1, z) && enclosed(x, y - 1, z)
    && enclosed(x, y, z + 1) && enclosed(x, y, z - 1);
  for (const batch of output) {
    if (!opaque.has(batch.stateIndex) || batch.group >= 0) continue;
    const kept = [];
    for (let index = 0; index < batch.instances.length; index += 3) {
      const x = batch.instances[index], y = batch.instances[index + 1], z = batch.instances[index + 2];
      if (!hidden(x, y, z)) kept.push(x, y, z);
    }
    batch.instances = new Float32Array(kept);
  }
}

async function bake(request) {
  const requestedBudget = request.budget || {};
  const maxWorkingBytes = Math.floor(Math.max(64 * 1024 * 1024,
    Number(requestedBudget.mainMemoryBytes) || 512 * 1024 * 1024) * .75);
  const recordBytes = request.records && request.records.byteLength || 0;
  const timings = {resources:0, geometry:0, decode:0, atlas:0};
  let mark = Date.now();
  const lap = key => { timings[key] += Date.now() - mark; mark = Date.now(); };
  let manifest = null, resourceFingerprint = String(request.resourceFingerprint || '');
  if (!resourceFingerprint) {
    manifest = await fetchJson(request.manifestUrl, request.token, request.server);
    resourceFingerprint = String(manifest && manifest.fingerprint || '');
  }
  const protocolVersion = manifest ? Number(manifest.version) : RESOURCE_PROTOCOL_VERSION;
  const cache = sharedFor(request.manifestUrl, request.server, resourceFingerprint, protocolVersion);
  if (!cache.loaded) {
    cache.loaded = await loadResources(request.manifestUrl, request.token, request.server,
      Math.max(0, maxWorkingBytes - recordBytes), manifest, resourceFingerprint);
  }
  const loaded = cache.loaded;
  if (loaded.byteLength + recordBytes > maxWorkingBytes) {
    cache.loaded = null;
    throw new Error('资源闭包超过浏览器内存预算');
  }
  lap('resources');
  const records = request.recordBytes === 8 ? new Uint16Array(request.records) : new Uint32Array(request.records);
  const palette = request.palette || (request.metadata && request.metadata.states) || [];
  const batches = new Map(), upgraded = new Set(), fallback = new Map(), reasons = new Map();
  const invisibleStates = [];
  const fluidCache = new Map();
  const cachedModel = id => {
    if (assets.models.has(id)) return assets.models.get(id);
    const value = bakeModel(loaded.files, id);
    if (value) assets.models.set(id, value);
    return value;
  };
  const biome = (request.metadata && request.metadata.biome_colors) || {};
  const maxTriangles = Math.max(1, Math.min(MAX_HIGH_TRIANGLES,
    Number(requestedBudget.triangles) || MAX_HIGH_TRIANGLES));
  const maxDrawCalls = Math.max(1, Math.min(MAX_DRAW_CALLS,
    Number(requestedBudget.drawCalls) || MAX_DRAW_CALLS));
  const maxTextureEdge = Math.max(16, Math.min(1024,
    Number(requestedBudget.textureEdge) || 1024));
  const maxGpuBytes = Math.max(16 * 1024 * 1024, Number(requestedBudget.gpuBytes) || 256 * 1024 * 1024);
  const baseWorkingBytes = loaded.byteLength + records.byteLength;
  let triangles = 0, upgradedVoxels = 0, workingBytes = baseWorkingBytes;
  const partialStates = new Set(), fullCubeStates = new Set();
  const originX = (Number(request.metadata && request.metadata.origin_x) || 0)
    + (Number(request.metadata && request.metadata.plot_x) || 0);
  const originY = Number(request.metadata && request.metadata.origin_y) || 0;
  const originZ = (Number(request.metadata && request.metadata.origin_z) || 0)
    + (Number(request.metadata && request.metadata.plot_z) || 0);
  const groups = new Map(), fluidGrid = new Map();
  /* 旋转组的体素在表里连续且有序(线格式已校验),所以只要一个随下标推进的游标,
     不用给每个体素查一次区间。-1 表示不转的静态方块。 */
  const rotations = (request.metadata && request.metadata.groups) || [];
  let cursor = 0;
  for (let index = 0; index < records.length / 4; index++) {
    const o = index * 4, stateIndex = records[o + 3];
    while (cursor < rotations.length && index >= rotations[cursor].first + rotations[cursor].count) cursor++;
    const group = cursor < rotations.length && index >= rotations[cursor].first ? cursor : -1;
    let values = groups.get(stateIndex);
    if (!values) { values = []; groups.set(stateIndex, values); }
    values.push({i:index, x:records[o], y:records[o + 1], z:records[o + 2], g:group});
    const fluid = fluidCell(palette[stateIndex] || {});
    if (fluid) fluidGrid.set(records[o] + ',' + records[o + 1] + ',' + records[o + 2], fluid);
  }
  for (const [stateIndex, values] of groups) {
    let result = bakeState(values, stateIndex, palette, loaded, cachedModel, fluidCache, fluidGrid, biome,
      originX, originY, originZ, triangles, maxTriangles);
    const fits = candidate => {
      if (!candidate || !candidate.batches) return false;
      const bytes = [...candidate.batches.values()].reduce((sum, batch) => sum + batchWorkingBytes(batch), 0);
      return batches.size + candidate.batches.size <= maxDrawCalls && workingBytes + bytes <= maxWorkingBytes
        ? bytes : false;
    };
    let selected = result, resultBytes = fits(selected);
    if (resultBytes === false) { selected = withoutAssembly(result); resultBytes = fits(selected); }
    if (result && (resultBytes === false || result.failure === 'budget')
        && removeCommittedAssembly(batches, partialStates)) {
      triangles = [...batches.values()].reduce((sum, batch) => sum + batchTriangleCost(batch), 0);
      workingBytes = baseWorkingBytes
        + [...batches.values()].reduce((sum, batch) => sum + batchWorkingBytes(batch), 0);
      if (result && result.failure === 'budget') {
        result = bakeState(values, stateIndex, palette, loaded, cachedModel, fluidCache, fluidGrid, biome,
          originX, originY, originZ, triangles, maxTriangles);
        selected = result; resultBytes = fits(selected);
        if (resultBytes === false) { selected = withoutAssembly(result); resultBytes = fits(selected); }
      } else {
        resultBytes = fits(selected);
      }
    }
    if (resultBytes !== false) {
      for (const [key, batch] of selected.batches) batches.set(key, batch);
      triangles += selected.triangles; workingBytes += resultBytes;
      upgraded.add(stateIndex); upgradedVoxels += result.count;
      if (selected.partial) partialStates.add(stateIndex);
      if (selected.fullCube) fullCubeStates.add(stateIndex);
      continue;
    }
    const state = palette[stateIndex];
    if (!state || !state.id) continue;
    /* 隐形哑方块要算"升级成空":runtime 只对 upgraded 名单撤半透明外壳占位盒,
       单纯跳过会让哑方块的外壳永远留着——大水车周围八格被"填充"的真凶。 */
    if (CURATED_INVISIBLE.has(state.id)) { invisibleStates.push(stateIndex); continue; }
    const choices = blockstateModels(loaded.files, state.id, state.state || state.id,
      minecraftModelSeed(originX, originY, originZ));
    const hasFluid = !!fluidCell(state);
    reasons.set(stateIndex, result && result.failure ? result.failure
      : result ? 'budget' : modelFailureReason(loaded.files, choices, hasFluid));
    const candidate = choices.length ? cachedModel(choices[0].model) : null;
    const texture = (candidate && candidate.map(face => face.texturePath).find(Boolean))
      || (choices.length ? firstModelTexture(loaded.files, choices[0].model) : null)
      || (state.id === 'minecraft:water' ? 'assets/minecraft/textures/block/water_still.png' : null)
      || (state.id === 'minecraft:lava' ? 'assets/minecraft/textures/block/lava_still.png' : null);
    if (texture) fallback.set(stateIndex, texture);
  }
  const output = [];
  for (const batch of batches.values()) {
    const value = {
      key:batch.key, texture:batch.texture, stateIndex:batch.stateIndex, group:batch.group,
      renderType:batch.renderType, emissive:batch.emissive, shade:batch.shade, assembly:batch.assembly,
      positions:new Float32Array(batch.positions), normals:new Float32Array(batch.normals),
      uvs:new Float32Array(batch.uvs), colors:new Float32Array(batch.colors), indices:new Uint32Array(batch.indices),
      instances:new Float32Array(batch.instances)
    };
    output.push(value);
    batch.positions.length = batch.normals.length = batch.uvs.length = batch.colors.length = 0;
    batch.indices.length = batch.instances.length = 0; batch.faceKeys.clear();
  }
  batches.clear(); fluidCache.clear();
  lap('geometry');
  const baseTexturePaths = new Set(), assemblyTexturePaths = new Set();
  for (const batch of output) {
    if (!batch.texture) continue;
    (batch.assembly ? assemblyTexturePaths : baseTexturePaths).add(batch.texture);
  }
  for (const texture of fallback.values()) if (texture) baseTexturePaths.add(texture);
  const texturePaths = [...baseTexturePaths,
    ...[...assemblyTexturePaths].filter(path => !baseTexturePaths.has(path))];
  const textures = [];
  const retained = new Set();   // 进了共享缓存的位图,packAtlases 之后不 close,下一 bake 还要用
  let decodedBytes = 0;
  for (const path of texturePaths) {
    const hit = assets.bitmaps.get(path);
    if (hit) {
      if (workingBytes + decodedBytes + hit.bytes > maxWorkingBytes) continue;
      decodedBytes += hit.bytes;
      retained.add(hit.bitmap);
      textures.push({path, bitmap:hit.bitmap, alpha:hit.alpha});
      continue;
    }
    const bytes = loaded.files.get(path);
    if (!bytes) continue;
    try {
      let bitmap = await createImageBitmap(new Blob([bytes], {type:'image/png'}));
      if (bitmap.width < 1 || bitmap.height < 1 || bitmap.width > MAX_SOURCE_TEXTURE_EDGE
          || bitmap.height > MAX_SOURCE_TEXTURE_EDGE) {
        if (bitmap.close) bitmap.close();
        continue;
      }
      const animationBytes = loaded.files.get(path + '.mcmeta');
      if (animationBytes && bitmap.height > bitmap.width) {
        try {
          const animation = JSON.parse(new TextDecoder().decode(animationBytes)).animation || {};
          const frameWidth = Number(animation.width) || bitmap.width;
          const frameHeight = Number(animation.height) || frameWidth;
          const first = Array.isArray(animation.frames) && animation.frames.length
            ? Number(typeof animation.frames[0] === 'object' ? animation.frames[0].index : animation.frames[0]) || 0 : 0;
          if (frameWidth > 0 && frameHeight > 0 && first * frameHeight + frameHeight <= bitmap.height) {
            const frame = await createImageBitmap(bitmap, 0, first * frameHeight, frameWidth, frameHeight);
            if (bitmap.close) bitmap.close(); bitmap = frame;
          }
        } catch (_) { }
      }
      const largest = Math.max(bitmap.width, bitmap.height);
      if (largest > maxTextureEdge) {
        const scale = maxTextureEdge / largest;
        const width = Math.max(1, Math.round(bitmap.width * scale));
        const height = Math.max(1, Math.round(bitmap.height * scale));
        const resized = await createImageBitmap(bitmap, {resizeWidth:width, resizeHeight:height, resizeQuality:'high'});
        if (bitmap.close) bitmap.close();
        bitmap = resized;
      }
      const bitmapBytes = bitmap.width * bitmap.height * 4;
      if (workingBytes + decodedBytes + bitmapBytes > maxWorkingBytes) {
        if (bitmap.close) bitmap.close();
        continue;
      }
      let alpha = 'solid';
      if (typeof OffscreenCanvas !== 'undefined') {
        try {
          const canvas = new OffscreenCanvas(bitmap.width, bitmap.height), context = canvas.getContext('2d', {willReadFrequently:true});
          context.drawImage(bitmap, 0, 0); const pixels = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
          let hasTransparent = false, hasPartial = false;
          for (let i = 3; i < pixels.length; i += 4) { if (pixels[i] === 0) hasTransparent = true; else if (pixels[i] !== 255) hasPartial = true; }
          alpha = hasPartial ? 'translucent' : hasTransparent ? 'cutout' : 'solid';
        } catch (_) { }
      }
      decodedBytes += bitmapBytes;
      if (assets.bitmapBytes + bitmapBytes <= BITMAP_CACHE_BYTES) {
        assets.bitmaps.set(path, {bitmap, alpha, bytes:bitmapBytes});
        assets.bitmapBytes += bitmapBytes;
        retained.add(bitmap);
      }
      textures.push({path, bitmap, alpha});
    } catch (_) {
      // 浏览器不支持 Worker 图像解码时,主线程仍可保留低保真方块。
    }
  }
  lap('decode');
  const available = new Set(textures.map(texture => texture.path));
  removeEnclosedInstances(output, groups, fullCubeStates, textures, request.metadata);
  const requiredBaseByState = new Map(), decodedBaseByState = new Map();
  for (const batch of output) {
    if (batch.assembly) continue;
    requiredBaseByState.set(batch.stateIndex, (requiredBaseByState.get(batch.stateIndex) || 0) + 1);
    if (available.has(batch.texture)) decodedBaseByState.set(batch.stateIndex,
      (decodedBaseByState.get(batch.stateIndex) || 0) + 1);
  }
  const finalUpgraded = new Set([...upgraded].filter(stateIndex =>
    requiredBaseByState.get(stateIndex) > 0
      && requiredBaseByState.get(stateIndex) === decodedBaseByState.get(stateIndex)));
  for (const stateIndex of upgraded) if (!finalUpgraded.has(stateIndex)) reasons.set(stateIndex, 'texture_missing');
  let usable = output.filter(batch => {
    if (!finalUpgraded.has(batch.stateIndex)) return false;
    if (available.has(batch.texture)) return true;
    if (batch.assembly) partialStates.add(batch.stateIndex);
    return false;
  });
  const fallbackOutput = [...fallback]
    .filter(([, texture]) => available.has(texture))
    .map(([stateIndex, texture]) => ({stateIndex, texture,
      renderType:vanillaRenderType((palette[stateIndex] || {}).id)}));
  const fallbackReserve = records.length / 4 * 80;
  let geometryBytes = usable.reduce((sum, batch) => sum + batchGpuBytes(batch), 0);
  if (geometryBytes + fallbackReserve > maxGpuBytes) {
    for (const batch of usable) if (batch.assembly) partialStates.add(batch.stateIndex);
    usable = usable.filter(batch => !batch.assembly);
    geometryBytes = usable.reduce((sum, batch) => sum + batchGpuBytes(batch), 0);
  }
  const packed = await packAtlases(textures, usable, fallbackOutput, {
    atlasSize:Number(requestedBudget.atlasSize) || ATLAS_SIZE,
    textureBytes:Math.max(0, maxGpuBytes - geometryBytes - fallbackReserve)
  }, retained);
  const packedBaseCounts = new Map(), packedKeys = new Set();
  for (const batch of packed.batches) {
    packedKeys.add(batch.key);
    if (!batch.assembly) packedBaseCounts.set(batch.stateIndex,
      (packedBaseCounts.get(batch.stateIndex) || 0) + 1);
  }
  for (const batch of usable) if (batch.assembly && !packedKeys.has(batch.key)) partialStates.add(batch.stateIndex);
  const packedUpgraded = new Set([...finalUpgraded].filter(stateIndex =>
    packedBaseCounts.get(stateIndex) === requiredBaseByState.get(stateIndex)));
  for (const stateIndex of finalUpgraded) if (!packedUpgraded.has(stateIndex)) reasons.set(stateIndex, 'atlas_budget');
  packed.batches = packed.batches.filter(batch => packedUpgraded.has(batch.stateIndex));
  upgradedVoxels = [...packedUpgraded].reduce((sum, stateIndex) =>
    sum + (groups.get(stateIndex) || []).length, 0);
  const simplified = [...groups.entries()].filter(([stateIndex]) => !packedUpgraded.has(stateIndex)
      && !CURATED_INVISIBLE.has((palette[stateIndex] || {}).id))
    .map(([stateIndex, values]) => ({stateIndex, instances:values.length,
      reason:reasons.get(stateIndex) || 'model_invalid'}));
  for (const stateIndex of partialStates) if (packedUpgraded.has(stateIndex)) {
    simplified.push({stateIndex, instances:(groups.get(stateIndex) || []).length, reason:'partial_model'});
  }
  lap('atlas');
  return {batches:packed.batches, textures:packed.textures,
    fallback:packed.fallback, upgraded:[...packedUpgraded, ...invisibleStates],
    simplified, stats:{highStates:packedUpgraded.size, simplifiedStates:simplified.length,
      highInstances:upgradedVoxels, simplifiedInstances:simplified.reduce((sum, item) => sum + item.instances, 0),
      timings}};
}

self.onmessage = event => {
  const request = event.data || {};
  if (request.type !== 'bake') return;
  bake(request).then(result => {
    const textureTransfer = [];
    for (const texture of result.textures || []) if (texture.bitmap) textureTransfer.push(texture.bitmap);
    self.postMessage({type:'bake_textures', textures:result.textures || [], fallback:result.fallback || []}, textureTransfer);
    const byState = new Map();
    for (const batch of result.batches || []) {
      let values = byState.get(batch.stateIndex); if (!values) { values = []; byState.set(batch.stateIndex, values); }
      values.push(batch);
    }
    for (const stateIndex of result.upgraded || []) {
      const batches = byState.get(stateIndex) || [], transfer = [];
      for (const batch of batches) transfer.push(batch.positions.buffer, batch.normals.buffer, batch.uvs.buffer,
        batch.colors.buffer, batch.indices.buffer, batch.instances.buffer);
      self.postMessage({type:'bake_state', upgraded:[stateIndex], batches}, transfer);
    }
    self.postMessage({type:'bake_done', simplified:result.simplified || [], stats:result.stats || {}});
  }).catch(error => self.postMessage({type:'failed', message:error && error.message || String(error)}));
};

/*
 * 两条会直接导致误删/误操作的前端契约,用 node:vm 加载真实 web/js 源文件跑一遍。
 *
 *   node src/test/js/detach-and-dimphysics.mjs
 *
 * 覆盖:
 *   DET-01 主体位置存疑时,清残骸的确认框必须多出那句警告(判定可能整个反过来)
 *   DET-02 不存疑就不加,免得每次都喊狼来了
 *   DIM-01 整维度停物理必须先确认;取消就不发请求
 *   DIM-02 确认后按 dim/paused 发到 /api/ops/dim_physics
 *   DIM-03 看板按维度那张表:停跑的维度显示"已停跑"而不是 ms,按钮切到反向动作
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/resources/web');
const WEB = path.join(ROOT, 'js');

function indexHtmlScripts() {
  const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
  return [...html.matchAll(/<script src="\/js\/([^"]+)"><\/script>/g)]
    .map(match => match[1]).filter(file => file !== 'main.js');
}

function makeElement(id) {
  return {
    id, value: '', textContent: '', innerHTML: '', checked: false,
    style: {}, dataset: {}, classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
    appendChild(){}, insertBefore(){}, remove(){}, focus(){}, querySelector(){ return null; },
    querySelectorAll(){ return []; }, closest(){ return null; }, addEventListener(){},
  };
}
const noop = function noop(){};
const permissive = base => new Proxy(base, { get: (t, k) => (k in t ? t[k] : noop) });

function makeContext(state) {
  const elements = new Map();
  const document = permissive({
    getElementById(id){
      if (!elements.has(id)) elements.set(id, makeElement(id));
      return elements.get(id);
    },
    querySelector(){ return null; },
    querySelectorAll(){ return []; },
    createElement(){ return makeElement('new'); },
    addEventListener(){},
    body: makeElement('body'),
    documentElement: makeElement('html'),
  });
  const sandbox = {
    console, JSON, Math, Date, Set, Map, Promise, Array, Object, String, Number, Boolean, Error,
    URL, URLSearchParams, TextDecoder, TextEncoder, isNaN, parseInt, parseFloat,
    setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
    document, localStorage: permissive({ getItem: () => null, setItem(){}, removeItem(){} }),
    location: { href: 'http://localhost/', search: '', reload(){} },
    navigator: { language: 'zh-CN' }, performance: { now: () => 0 },
    fetch: (...args) => state.fetch(...args),
    addEventListener(){}, removeEventListener(){}, matchMedia: () => ({ matches: false, addEventListener(){} }),
    __asks: [], __toasts: [], __elements: elements,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  for (const file of indexHtmlScripts()) {
    vm.runInContext(fs.readFileSync(path.join(WEB, file), 'utf8'), sandbox, { filename: file });
  }
  // askModal / toast 换成可观测的桩:确认框文案本身就是这次要断言的东西
  vm.runInContext(`
    askModal = (title, msg) => { __asks.push({title, msg}); return Promise.resolve(__askAnswer); };
    toast = (msg, kind) => { __toasts.push({msg, kind}); };
    __askAnswer = true;
  `, sandbox);
  return sandbox;
}

const evalIn = (ctx, expr) => vm.runInContext(expr, ctx);
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/* ---------- 断链确认框 ---------- */

const group = extra => ({
  gid: 'g1', name: '糖音气球', members: 3, blocks: 24010, detached: 1,
  bodies: [
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000001', blocks: 22463 },
    { uuid: 'bbbbbbbb-0000-0000-0000-000000000002', blocks: 167 },
    { uuid: 'cccccccc-0000-0000-0000-000000000003', blocks: 9, detached: true },
  ],
  ...extra,
});

test('DET-01 主体位置存疑时确认框必须带出警告', async () => {
  const ctx = makeContext({ fetch: async () => ({ ok: true, status: 200, json: async () => ({ ok: true }) }) });
  evalIn(ctx, '__askAnswer = false');   // 停在第一个确认框,只看文案
  await evalIn(ctx, 'dropDetachedBefore')(group({ detach_unsure: true }));
  const asks = evalIn(ctx, '__asks');
  assert.equal(asks.length, 1, '应当只弹了清残骸这一个确认框');
  assert.match(asks[0].msg, /将删除这 1 个,保留 2 个/, '原有的数量说明不能丢');
  assert.match(asks[0].msg, /多份位置不同的存盘条目/, '存疑警告必须出现在同一个确认框里');
});

test('DET-02 主体位置不存疑就不加警告', async () => {
  const ctx = makeContext({ fetch: async () => ({ ok: true, status: 200, json: async () => ({ ok: true }) }) });
  evalIn(ctx, '__askAnswer = false');
  await evalIn(ctx, 'dropDetachedBefore')(group());
  const asks = evalIn(ctx, '__asks');
  assert.equal(asks.length, 1);
  assert.doesNotMatch(asks[0].msg, /多份位置不同的存盘条目/, '不存疑时不能喊狼来了');
});

test('恢复 tick 批量确认显示依赖组去重后的实际影响', async () => {
  const ctx = makeContext({ fetch: async () => ({ ok: true, status: 200, json: async () => ({ ok: true }) }) });
  evalIn(ctx, `
    const g1={gid:'g1',members:3,blocks:100}, g2={gid:'g2',members:2,blocks:20};
    BODY_BY_UUID=new Map([
      ['a',{g:g1}],['b',{g:g1}],['c',{g:g2}]
    ]);
    SELECTED=new Set(['a','b','c','missing']);
    FROZEN=new Set(['a','b','c','missing']);
    __askAnswer=false;
  `);

  await evalIn(ctx, 'doFreezeSelected')(false);
  const message = evalIn(ctx, '__asks[0].msg');
  assert.match(message, /所选 4 个物理体/);
  assert.match(message, /实际影响 6 个物理体、共 120 块/);
});

/* ---------- 整维度物理开关 ---------- */

function captureFetch() {
  const calls = [];
  return { calls, fetch: async (url, init) => {
    calls.push({ url: String(url), init });
    return { ok: true, status: 200, json: async () => ({ ok: true, dim: 'minecraft:overworld', paused: true }) };
  } };
}

test('DIM-01 取消确认就不发请求', async () => {
  const cap = captureFetch();
  const ctx = makeContext(cap);
  evalIn(ctx, '__askAnswer = false');
  await evalIn(ctx, 'toggleDimPhysics')('minecraft:overworld', true);
  assert.equal(cap.calls.filter(c => c.url.includes('dim_physics')).length, 0, '没确认就不能动物理');
});

test('DIM-02 确认后按 dim/paused 发请求', async () => {
  const cap = captureFetch();
  const ctx = makeContext(cap);
  await evalIn(ctx, 'toggleDimPhysics')('minecraft:overworld', true);
  const call = cap.calls.find(c => c.url.includes('/api/ops/dim_physics'));
  assert.ok(call, '应当打到 /api/ops/dim_physics');
  assert.equal(call.init.method, 'POST');
  assert.deepEqual(JSON.parse(call.init.body), { dim: 'minecraft:overworld', paused: true });
  assert.match(evalIn(ctx, '__asks')[0].msg, /所有人的船和物理结构都会原地静止/, '要说清影响范围');
});

test('DIM-03 看板按维度的表:停跑的显示已停跑,按钮切到恢复', () => {
  const ctx = makeContext({ fetch: async () => ({ ok: true, status: 200, json: async () => ({}) }) });
  evalIn(ctx, `STATS = {phys_1m:{'minecraft:overworld':12.5,'minecraft:the_nether':1.0},
                         loaded:{'minecraft:overworld':3},
                         phys_paused:['minecraft:overworld']};`);
  evalIn(ctx, 'renderStatPop()');
  const html = evalIn(ctx, "document.getElementById('statPop').innerHTML");
  assert.match(html, /已停跑/, '停跑的维度不该再显示一个假的 ms 数字');
  assert.doesNotMatch(html, /12\.50 ms/, '停跑时旧的物理耗时不能继续挂在那儿');
  assert.match(html, /toggleDimPhysics\('minecraft:overworld',false\)/, '停跑的维度按钮应当是"恢复"');
  assert.match(html, /toggleDimPhysics\('minecraft:the_nether',true\)/, '在跑的维度按钮应当是"停跑"');
  assert.match(html, /1\.00 ms/, '没停跑的维度照常显示耗时');
});

let failures = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failures++;
    console.log(`FAIL ${name}\n     ${error.message}`);
  }
}
console.log(`\n${tests.length - failures}/${tests.length} passed`);
process.exit(failures ? 1 : 0);

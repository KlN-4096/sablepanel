/*
 * 前端竞态回归。用 node:vm 加载真实的 web/js 源文件(不是复制的逻辑),用可控的
 * fetch / 定时器把审计里复现过的乱序场景重放一遍。
 *
 *   node src/test/js/frontend-races.mjs
 *
 * 覆盖:UI-01 终态契约、UI-02 并发登录、UI-03 切服隔离、UI-04 预览旧失败、
 *      PERF-03 忙碌轮询与注销、PERF-05 批量收养只发一次请求刷一次。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/resources/web');
const WEB = path.join(ROOT, 'js');
/* 顺序必须和 index.html 一致,而且不能挑着加载:少一个文件就少一批顶层 let,
   `tpFilledFor`(bodies.js)这类跨文件全局会在别的引擎上直接 ReferenceError。
   只排除 vendor/three.min.js 和 main.js —— 后者是启动序列,一加载就开始打请求。 */
const FILES = indexHtmlScripts();

function indexHtmlScripts() {
  const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
  return [...html.matchAll(/<script src="\/js\/([^"]+)"><\/script>/g)]
    .map(match => match[1])
    .filter(file => file !== 'main.js');
}

/* ---------- 最小 DOM / 浏览器环境 ---------- */
function makeElement(id) {
  return {
    id, value: '', textContent: '', innerHTML: '', checked: false,
    style: {}, dataset: {}, classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
    appendChild(){}, insertBefore(){}, remove(){}, focus(){}, querySelector(){ return null; },
    querySelectorAll(){ return []; }, closest(){ return null; }, addEventListener(){},
  };
}

const noop = function noop(){};
/** 未知成员一律给空操作函数,免得为每个视图 API 手写桩 */
const permissive = base => new Proxy(base, { get: (target, key) => (key in target ? target[key] : noop) });

function makeSandbox(state) {
  const elements = new Map();
  const document = {
    getElementById(id){
      if (!elements.has(id)) elements.set(id, makeElement(id));
      return elements.get(id);
    },
    querySelector(){ return null; },
    querySelectorAll(){ return []; },
    createElement(tag){ return makeElement(tag); },
    addEventListener(){},
    body: { classList: { add(){}, remove(){}, toggle(){} } },
    hidden: false,
  };
  const store = new Map();
  // 只放浏览器特有的东西;标准内置(Boolean/Array/…)由 Proxy 回落到宿主 globalThis ——
  // 手写清单漏一个就会被兜成 noop,`parts.filter(Boolean)` 会静悄悄把整个数组过滤空
  const sandbox = {
    ResizeObserver: class { observe(){} disconnect(){} },
    document: permissive(document),
    window: permissive({ devicePixelRatio: 1, confirm: () => true }),
    location: { href: 'http://127.0.0.1:25580/' },
    history: { replaceState(){} },
    URL,
    localStorage: {
      getItem: key => (store.has(key) ? store.get(key) : null),
      setItem: (key, value) => store.set(key, String(value)),
      removeItem: key => store.delete(key),
    },
    fetch: (...args) => state.fetch(...args),
    elements,
  };
  sandbox.globalThis = sandbox;
  return sandbox;
}

/** 未定义的标识符一律解析成空操作函数:视图层的 render* 不是本文件要测的东西 */
function makeContext(state) {
  const sandbox = makeSandbox(state);
  const proxy = new Proxy(sandbox, {
    has: () => true,
    get: (target, key) => {
      if (key in target) return target[key];
      if (key === Symbol.unscopables) return undefined;
      if (key in globalThis) return globalThis[key];   // 标准内置对象
      return noop;                                     // 没加载的视图函数
    },
  });
  // 一次性拼成一个脚本再跑:浏览器里各 script 的顶层 let 共享同一个词法作用域,分次
  // runInContext 就不是了 —— state.js 的 let 会对 data.js 不可见
  const context = vm.createContext(proxy);
  const source = FILES.map(file => fs.readFileSync(path.join(WEB, file), 'utf8')).join('\n;\n');
  vm.runInContext(source, context, { filename: 'sablepanel-web-bundle.js' });
  // 只桩掉真的需要 WebGL / canvas 2d 的入口,其余渲染代码照常跑(顺带当成冒烟测试)。
  // 确认框要等用户点按钮才 resolve,这里一律当成"已确认" —— 被测的是确认之后的行为。
  vm.runInContext(`
    askModal = () => Promise.resolve(true);
    initGL = () => {}; resizeGL = () => {}; loop = () => {}; disposeMesh = () => {};
    drawPhysChart = () => {}; renderComposition = () => {};
    connectEventStream = async () => {};   // SSE 不在本文件的测试范围,但 start/stop 的代次记账保持真实
  `, context);
  return context;
}

/** 每个用例一个全新环境;state.fetch 由用例自己接管 */
function setup() {
  const state = { fetch: async () => ({ ok: true, status: 200, json: async () => ({}) }) };
  return { sandbox: makeContext(state), state };
}

function evalIn(context, expr) {
  return vm.runInContext(expr, context);
}

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};
const jsonResponse = body => ({ ok: true, status: 200, json: async () => body });
const errorResponse = status => ({ ok: false, status, json: async () => ({ error: 'nope' }) });
const tick = () => new Promise(res => setImmediate(res));
/** /api/bodies 的真实响应形状。缺字段会让渲染在 loadBodies 的 catch 里静悄悄变成"加载失败" */
const bodiesResponse = (extra = {}) => jsonResponse({
  scan_time: 0, total_bodies: 0, total_entries: 0, total_groups: 0, shown_groups: 0,
  groups: [], block_palette: [], clone_sets: [], paused: [], forced: [], busy: [],
  rec_policy: { blocks: 20, types: 4, be: 3 }, reach: { void_below: -64, sky_above: 1000 },
  ...extra,
});

/* ---------- 用例 ---------- */
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// UI-01:终态契约
test('UI-01 部分失败和全部失败都不再显示为完成', () => {
  const { sandbox } = setup();
  const outcome = job => evalIn(sandbox, 'jobOutcome')(job);
  const failed = job => evalIn(sandbox, 'jobFailed')(job);
  const tag = job => evalIn(sandbox, 'jobStateTag')(job);

  assert.equal(outcome({ state: 'done', outcome: 'ok', message: '3/3' }), 'ok');
  assert.equal(outcome({ state: 'done', outcome: 'partial', message: '1/3' }), 'partial');
  assert.equal(outcome({ state: 'done', outcome: 'fail', message: '0/3' }), 'fail');
  assert.equal(outcome({ state: 'failed', outcome: 'fail', message: 'boom' }), 'fail');
  assert.equal(outcome({ state: 'done', outcome: 'ok', message: '3/3 warnings=2' }), 'ok');

  // 这就是审计复现的那一条:从前 jobFailed({state:'done',message:'0/3'}) === false
  assert.equal(failed({ state: 'done', outcome: 'fail', message: '0/3' }), true);
  assert.equal(failed({ state: 'done', outcome: 'partial', message: '1/3' }), true);
  assert.equal(failed({ state: 'done', outcome: 'ok', message: '3/3' }), false);
  // 历史日志文件里没有 outcome 字段,回落解析也要认得 0/3 和 1/3
  assert.equal(failed({ state: 'done', message: '0/3' }), true);
  assert.equal(failed({ state: 'done', message: '1/3' }), true);
  assert.equal(failed({ state: 'done', message: '3/3' }), false);
  // 进行中的作业不算失败
  assert.equal(failed({ state: 'running', message: '' }), false);

  assert.match(tag({ state: 'done', outcome: 'fail', message: '0/3' }), /tag bad/);
  assert.match(tag({ state: 'done', outcome: 'partial', message: '1/3' }), /tag warn/);
  assert.match(tag({ state: 'done', outcome: 'ok', message: '3/3' }), /tag ok/);
});

// UI-02:并发登录
test('UI-02 旧登录请求的失败不能推翻新登录的成功', async () => {
  const slowBad = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    const token = opts && opts.headers && opts.headers['X-Token'];
    if (token === 'wrong') return slowBad.promise;
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true, host: true }] });
  };
  const authenticate = evalIn(sandbox, 'authenticate');
  const first = authenticate('wrong', false);       // 先提交的错口令,回得慢
  const second = authenticate('right', false);      // 后提交的对口令,先回来
  assert.equal(await second, true);
  slowBad.resolve(errorResponse(401));
  assert.equal(await first, false);
  await tick();
  assert.equal(evalIn(sandbox, 'authenticated'), true, '最终状态必须由最新一次提交决定');
  assert.equal(evalIn(sandbox, 'token'), 'right');
});

test('UI-02 旧登录请求的成功不能覆盖新登录的失败', async () => {
  const slowGood = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    const token = opts && opts.headers && opts.headers['X-Token'];
    if (token === 'old') return slowGood.promise;
    return errorResponse(401);
  };
  const authenticate = evalIn(sandbox, 'authenticate');
  const first = authenticate('old', false);
  const second = authenticate('new-wrong', false);
  assert.equal(await second, false);
  slowGood.resolve(jsonResponse({ self: 'A', servers: [] }));
  assert.equal(await first, false);
  await tick();
  assert.equal(evalIn(sandbox, 'authenticated'), false);
  assert.equal(evalIn(sandbox, 'token'), '');
});

test('UI-02 记住的 token 自动登录不能覆盖用户手动输入的结果', async () => {
  const slowRemembered = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    const token = opts && opts.headers && opts.headers['X-Token'];
    if (token === 'stale') return slowRemembered.promise;
    return jsonResponse({ self: 'A', servers: [] });
  };
  const authenticate = evalIn(sandbox, 'authenticate');
  const auto = authenticate('stale', true);
  const manual = authenticate('typed', false);
  assert.equal(await manual, true);
  slowRemembered.resolve(errorResponse(401));
  assert.equal(await auto, false);
  assert.equal(evalIn(sandbox, 'token'), 'typed');
  assert.equal(evalIn(sandbox, 'authenticated'), true);
});

// UI-03:切服隔离
test('UI-03 切服后旧服的 recycle/jobs 慢响应都不落地', async () => {
  const slowRecycle = deferred();
  const slowJobs = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/recycle') && url.includes('server=B')) return slowRecycle.promise;
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '', marker: 'A' });
    if (url.startsWith('/api/jobs') && url.includes('server=B')) return slowJobs.promise;
    if (url.startsWith('/api/jobs')) return jsonResponse({ running: [], log: [], files: [], marker: 'A' });
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    return jsonResponse({ servers: [], self: 'A' });
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, "CURSRV = 'B'");
  evalIn(sandbox, "VIEW = 'jobs'");
  evalIn(sandbox, "SERVERS = [{id:'A',self:true},{id:'B',self:false}]");
  const pendingRecycle = evalIn(sandbox, 'loadRecycle')();   // 在 B 上发出,回得慢
  const pendingJobs = evalIn(sandbox, 'loadJobs')();
  await evalIn(sandbox, 'switchServer')('A');                // 切回 A
  slowRecycle.resolve(jsonResponse({ groups: [{ id: 'g1', bodies: [] }], block_palette: [], next_cursor: '', marker: 'B' }));
  slowJobs.resolve(jsonResponse({ running: [], log: [{ seq: 1 }], files: [], marker: 'B' }));
  await pendingRecycle;
  await pendingJobs;
  await tick();
  const recycle = evalIn(sandbox, 'RECYCLE');
  assert.ok(!recycle || recycle.marker !== 'B', '旧服的回收站数据不得写进新服的界面');
  const jobs = evalIn(sandbox, 'JOBS');
  assert.ok(!jobs || jobs.marker !== 'B', '旧服的作业日志不得留在新服标题下');
  assert.equal(evalIn(sandbox, 'CURSRV'), '');
});

test('UI-03 切服清空作业 watch、忙碌定时器和日志页', async () => {
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    if (url.startsWith('/api/jobs')) return jsonResponse({ running: [], log: [], files: [] });
    return jsonResponse({ servers: [], self: 'A' });
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, "JOB_WATCH.set(7, '传送')");
  evalIn(sandbox, "ACTIVE_JOBS = [{seq:7, op:'传送', targets:[], since:0, state:'running'}]");
  evalIn(sandbox, 'syncBusyPolling()');
  assert.notEqual(evalIn(sandbox, 'busyTimer'), null, '有作业时应当有轮询定时器');
  evalIn(sandbox, "JOBS = {running:[],log:[{seq:7}],files:[]}; jobsFile = 'jobs-20260101-000000.jsonl'");
  evalIn(sandbox, "SERVERS = [{id:'A',self:true},{id:'B',self:false}]");
  await evalIn(sandbox, 'switchServer')('B');
  assert.equal(evalIn(sandbox, 'busyTimer'), null, '切服必须停掉旧服的忙碌轮询');
  assert.equal(evalIn(sandbox, 'JOB_WATCH').size, 0, 'job seq 每个服都从 1 开始,不清会张冠李戴');
  assert.equal(evalIn(sandbox, 'jobsFile'), '', '日志文件选择属于旧服');
});

// PERF-03:忙碌轮询
test('PERF-03 注销后忙碌轮询必须停止', () => {
  const { sandbox } = setup();
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, "ACTIVE_JOBS = [{seq:1, op:'传送', targets:[], since:0, state:'running'}]");
  evalIn(sandbox, 'syncBusyPolling()');
  assert.notEqual(evalIn(sandbox, 'busyTimer'), null);
  evalIn(sandbox, "showLogin('')");
  assert.equal(evalIn(sandbox, 'authenticated'), false);
  assert.equal(evalIn(sandbox, 'busyTimer'), null, '注销后定时器还活着就会继续打请求');
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 0);
});

test('PERF-03 慢响应期间不重叠请求,期间的请求合并成完事后再跑一次', async () => {
  const slow = deferred();
  let calls = 0, live = 0, maxLive = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (!url.startsWith('/api/bodies')) return jsonResponse({});
    calls++;
    maxLive = Math.max(maxLive, ++live);
    const body = calls === 1 ? await slow.promise : bodiesResponse();
    live--;
    return body;
  };
  evalIn(sandbox, 'authenticated = true');
  const loadBodies = evalIn(sandbox, 'loadBodies');
  const first = loadBodies();
  await loadBodies();                   // 上一轮还在路上:合并,不新发请求
  assert.equal(calls, 1, '同一时刻只允许一个 bodies 请求在途');
  slow.resolve(bodiesResponse());
  await first;
  await tick();
  await tick();
  // 合并掉的那次必须补跑 —— 直接丢弃的话,切服时新服的加载会被旧服的在途请求吞掉
  assert.equal(calls, 2, '被合并的请求要在上一轮结束后补跑一次');
  assert.equal(maxLive, 1, '任何时刻只有一个在途请求');
});

test('PERF-03 切服时新服的加载不会被旧服的在途请求吞掉', async () => {
  const slowOld = deferred();
  const seen = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) {
      seen.push(url);
      if (url.includes('server=B')) return slowOld.promise;
      return bodiesResponse({ total_bodies: 7, marker: 'A' });
    }
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ servers: [], self: 'A' });
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, "CURSRV = 'B'; SERVERS = [{id:'A',self:true},{id:'B',self:false}]");
  const pendingOld = evalIn(sandbox, 'loadBodies')();     // B 上发出,回得慢
  await evalIn(sandbox, 'switchServer')('A');             // 切回 A,期间 loadBodies 被合并掉
  slowOld.resolve(bodiesResponse({ total_bodies: 999, marker: 'B' }));
  await pendingOld;
  await tick();
  await tick();
  const data = evalIn(sandbox, 'DATA');
  assert.ok(data && data.marker === 'A', '新服的列表必须被真正加载出来,而不是空等 60 秒兜底');
  assert.ok(seen.some(url => !url.includes('server=')), '应当真的对新服发过一次请求');
});

test('PERF-03/UI-01 作业跑完时完成 toast 不会被轮询自己清掉', async () => {
  const toasts = [];
  const { sandbox, state } = setup();
  let bodiesCalls = 0;
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) {
      bodiesCalls++;
      return bodiesResponse();   // 作业已经跑完,busy 空了
    }
    if (url.startsWith('/api/jobs')) {
      return jsonResponse({ running: [], files: [], log: [
        { seq: 9, op: '批量删除', name: '飞艇 等 3 个', state: 'done', outcome: 'fail', message: '0/3' }] });
    }
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, 'toast = (msg, cls) => globalThis.__toasts.push([msg, cls])');
  evalIn(sandbox, '__toasts = []');
  evalIn(sandbox, "JOB_WATCH.set(9, '批量删除')");
  await evalIn(sandbox, 'loadBodies')();
  await tick();
  toasts.push(...evalIn(sandbox, '__toasts'));
  assert.equal(bodiesCalls, 1);
  assert.equal(toasts.length, 1, '作业结束必须弹一次终态 toast');
  assert.match(toasts[0][0], /批量删除/);
  assert.match(toasts[0][0], /失败/);
  assert.equal(toasts[0][1], 'bad', '0/3 必须是红色而不是绿色');
});

// PERF-05:批量收养
test('PERF-05 批量收养只发一次请求、只刷一次列表', async () => {
  const posts = [];
  let bodiesLoads = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    if (opts && opts.method === 'POST') { posts.push(url); return jsonResponse({ ok: true, accepted: true, job: 1, op: '批量收养' }); }
    if (url.startsWith('/api/bodies')) { bodiesLoads++; return bodiesResponse(); }
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, `
    BODY_BY_UUID = new Map([
      ['u1', {b:{uuid:'u1', state:'orphan'}, g:{}}],
      ['u2', {b:{uuid:'u2', state:'orphan'}, g:{}}],
      ['u3', {b:{uuid:'u3', state:'orphan'}, g:{}}],
    ]);
    SELECTED = new Set(['u1','u2','u3']);
  `);
  await evalIn(sandbox, 'doAdoptSelected')();
  assert.equal(posts.length, 1, '3 个孤儿体从前会产生 3 次 POST');
  assert.equal(posts[0], '/api/ops/batch_adopt');
  assert.equal(bodiesLoads, 1, '3 个孤儿体从前会产生 3 次全量刷新');
});

// UI-04:预览旧失败
test('UI-04 旧预览请求的失败不得改写新选择的提示', async () => {
  const slowFail = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.includes('/body/A/mesh')) return slowFail.promise;
    return jsonResponse({ shell: 0, total: 0, voxels: [], palette: [] });
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, "SEL = {uuid:'A'}");
  const pendingA = evalIn(sandbox, 'loadMesh')('A');
  evalIn(sandbox, "SEL = {uuid:'B'}");
  await evalIn(sandbox, 'loadMesh')('B');
  const afterB = evalIn(sandbox, "document.getElementById('pvInfo')").textContent;
  slowFail.reject(new Error('A 读取失败'));
  await pendingA;
  const info = evalIn(sandbox, "document.getElementById('pvInfo')").textContent;
  assert.equal(info, afterB, 'A 的延迟失败不能盖掉 B 已经渲染好的提示');
  assert.ok(!info.includes('A 读取失败'));
});

/* ---------- 运行 ---------- */
let failures = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log('  ok  ' + name);
  } catch (error) {
    failures++;
    console.log('FAIL  ' + name);
    console.log('      ' + (error && error.message));
  }
}
console.log(`\n${tests.length - failures}/${tests.length} passed`);
process.exit(failures ? 1 : 0);

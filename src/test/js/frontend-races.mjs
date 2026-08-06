/*
 * 前端竞态回归。用 node:vm 加载真实的 web/js 源文件(不是复制的逻辑),用可控的
 * fetch / 定时器把审计里复现过的乱序场景重放一遍。
 *
 *   node src/test/js/frontend-races.mjs
 *
 * 覆盖:UI-01 终态契约、UI-02 并发登录、UI-03 切服隔离、UI-04 预览旧失败、
 *      LOAD-01 加载失败不伪装成空、PERF-03 忙碌轮询与注销、PERF-04 作业轮询与 bodies 解耦、PERF-05 批量收养只发一次请求刷一次、回收站版本/清除交互。
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
    realAskModal = askModal;   // 确认框本身要被测(切服要作废它),留一个真身
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
  groups: [], block_palette: [], clone_sets: [], paused: [], forced: [],
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

test('UI-02 旧会话的在途响应不能落到重新登录后的新会话上', async () => {
  // fresh() 从前只看 authenticated 这个布尔:注销再登录它又变回 true,
  // 旧会话还在路上的响应就重新满足条件了。会话身份得是 authSeq
  const slowOld = deferred();
  let phase = 'old';
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (phase === 'old') return slowOld.promise;
    if (url.startsWith('/api/servers')) return jsonResponse({ self: 'NEW', servers: [{ id: 'NEW', self: true }] });
    if (url.startsWith('/api/bodies')) return bodiesResponse({ total_bodies: 2 });
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; toast = () => {}");
  const staleServers = evalIn(sandbox, 'loadServers')();
  const staleBodies = evalIn(sandbox, 'loadBodies')();

  evalIn(sandbox, "showLogin('')");                       // 注销:authSeq++
  phase = 'new';
  assert.equal(await evalIn(sandbox, 'authenticate')('t', false), true);

  // 一份同时满足两种形状的旧响应:两个在途请求共用这个 deferred。
  // bodies 那半边必须是合法快照,否则会被快照校验挡下,测不到会话代次
  slowOld.resolve(jsonResponse({
    self: 'OLD', servers: [{ id: 'OLD', self: true }],
    scan_time: 0, total_bodies: 111, total_entries: 0, groups: [], block_palette: [],
    clone_sets: [], paused: [], forced: [], reach: { void_below: -64, sky_above: 1000 },
  }));
  await Promise.all([staleServers, staleBodies]);
  await tick();
  await tick();
  assert.deepEqual(evalIn(sandbox, 'SERVERS.map(s => s.id)'), ['NEW'], '旧会话的成员表不得盖掉新会话的');
  assert.notEqual(evalIn(sandbox, 'DATA && DATA.total_bodies'), 111, '旧会话的快照不得落地');
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

test('UI-03 切服要作废旧服的确认框,晚点的"确定"不能打到新服上', async () => {
  // 破坏性操作一律是"先把 uuid 攒进闭包,再 await 确认",而 server= 参数是确认之后
  // 才按 CURSRV 拼的。B 消失自动退回本机、用户回头点"确定",发出的就是一条不带
  // server=B、却带着 B 的 uuid 的 batch_delete —— 两个服从同一份存档复制出来时会真的删掉东西
  const sent = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    sent.push(url);
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, `
    authenticated = true; toast = () => {}; askModal = realAskModal;
    SERVERS = [{id:'A',self:true},{id:'B'}]; CURSRV = 'B';
    SELECTED = new Set(['u-of-B']);
    BODY_BY_UUID = new Map([['u-of-B', {b:{uuid:'u-of-B',blocks:1},
      g:{gid:'g1',members:1,blocks:1,bodies:[{uuid:'u-of-B'}]}}]]);
  `);
  const pending = evalIn(sandbox, 'doDeleteSelected')();   // 确认框弹出来,一直挂着
  await tick();
  await evalIn(sandbox, 'switchServer')('A');
  assert.equal(evalIn(sandbox, "document.getElementById('modalBack').style.display"), 'none',
    '框本身要收掉:它问的是"确定删除 B 上的 1 个体吗",而界面已经是本机了');
  evalIn(sandbox, 'modalConfirm()');                       // 用户回头点了"确定"
  await pending;
  await tick();
  assert.ok(!sent.some(url => url.startsWith('/api/ops/batch_delete')),
    '切服之后这个确认框只能作废,不能改成对新服执行');

  // 第二层:将来新增的切服入口忘了关弹层时,askModal 自己也要认得代次
  const late = evalIn(sandbox, 'realAskModal')('t', 'm', false);
  evalIn(sandbox, 'SRVGEN++');
  evalIn(sandbox, 'modalConfirm()');
  assert.equal(await late, false, '代次变了就只能是"取消"');
});

test('UI-03 切服要收掉 3D 预览:关全屏、释放网格、不再请求旧服的 mesh', async () => {
  // reset 从前只把 MESH_* 置空:几何体还在场景里(GPU 资源不释放),全屏层还开着并显示
  // 旧服的体名,pvInfo 还停在上一次的文字。closeServerModals 还会无条件调 closeDedupe,
  // 那个函数末尾 `if (SEL) loadMesh(SEL.uuid)` —— 弹层根本没开也白发一次旧服的 mesh 请求
  let meshHits = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.includes('/mesh')) { meshHits++; return jsonResponse({ shell: 0, total: 0, voxels: [], palette: [] }); }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'bodies'; SERVERS = [{id:'A',self:true},{id:'B'}]; toast = () => {}");
  // A 服:选了一个体,开着全屏
  evalIn(sandbox, `
    renderer = { domElement: {} };
    SEL = { uuid: 'u-old', name: 'OLD_FS', blocks: 1, dim: 'minecraft:overworld' };
    MESH_DATA = {}; MESH_UUID = 'u-old'; MESH_SOURCE = 'body';
    fsMode = true;
    document.getElementById('fsOverlay').style.display = 'block';
    document.getElementById('fsName').textContent = 'OLD_FS';
    document.getElementById('pvInfo').textContent = '加载预览…';
    __disposed = 0; disposeMesh = () => { __disposed++; };
  `);
  meshHits = 0;

  const switching = evalIn(sandbox, 'switchServer')('B');
  assert.equal(meshHits, 0, '切服过程中不该再对旧服发 mesh 请求');
  assert.equal(evalIn(sandbox, 'fsMode'), false, '全屏必须关掉');
  assert.equal(evalIn(sandbox, "document.getElementById('fsOverlay').style.display"), 'none');
  assert.ok(evalIn(sandbox, '__disposed') > 0, '网格要真的释放,不能只把 MESH_* 置空');
  assert.equal(evalIn(sandbox, 'MESH_UUID'), null);
  assert.equal(evalIn(sandbox, "document.getElementById('pvInfo').textContent"), '');
  await switching;
});

test('UI-03 切服后列表元数据和回收站配置不得留着上一个服的', async () => {
  // 这些区域从前只有加载成功那条路写:维度筛选、方块清单、扫描信息、页签、工具条,
  // 以及回收站的上限输入框/磁盘用量/维度。切服后全都留着 A 的内容 ——
  // 上限输入框尤其危险:用户一按保存就把 A 的配置写到 B 上
  const slowB = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.includes('server=B')) return slowB.promise;
    if (url.startsWith('/api/bodies')) {
      return bodiesResponse({
        total_bodies: 4, total_entries: 4, scan_time: 0,
        block_palette: [{ id: 'sp:old_block', en: 'OLD_BLOCK', zh: 'OLD_BLOCK' }],
        groups: [{ gid: 'g1', name: 'g1', members: 1, blocks: 1, bodies: [
          { uuid: 'u1', name: 'b1', dim: 'sp:old_dim', state: 'stored', blocks: 1,
            pos: [0, 0, 0], size: [1, 1, 1], blk: [] }] }],
      });
    }
    if (url.startsWith('/api/recycle')) {
      return jsonResponse({ groups: [], block_palette: [], next_cursor: '',
        limit: 777, file_count: 3, disk_bytes: 4096 });
    }
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'bodies'; SERVERS = [{id:'A',self:true},{id:'B'}]; toast = () => {}");
  await evalIn(sandbox, 'loadBodies')();
  evalIn(sandbox, "VIEW = 'recycle'");
  await evalIn(sandbox, 'loadRecycle')();
  await tick();
  assert.match(evalIn(sandbox, "document.getElementById('fDims').innerHTML"), /old_dim/);
  assert.match(evalIn(sandbox, "document.getElementById('scanMeta').innerHTML"), /4/);
  assert.equal(evalIn(sandbox, "document.getElementById('rLimit').value"), 777);

  const switching = evalIn(sandbox, 'switchServer')('B');   // B 的数据还在路上
  assert.equal(evalIn(sandbox, "document.getElementById('rLimit').value"), '',
    '上限输入框不能还是 A 的 777 —— 按一下保存就写到 B 上了');
  assert.equal(evalIn(sandbox, "document.getElementById('rUsage').textContent"), '');
  assert.equal(evalIn(sandbox, "document.getElementById('rDims').innerHTML"), '');

  evalIn(sandbox, "VIEW = 'bodies'; renderAll()");
  assert.equal(evalIn(sandbox, "document.getElementById('fDims').innerHTML"), '',
    '维度筛选不能还是 A 的,否则是拿 A 的维度筛 B');
  assert.equal(evalIn(sandbox, "document.getElementById('scanMeta').innerHTML"), '');
  assert.equal(evalIn(sandbox, "document.getElementById('blockList').innerHTML"), '');
  assert.equal(evalIn(sandbox, "document.getElementById('tabs').innerHTML"), '');
  assert.equal(evalIn(sandbox, "document.getElementById('toolbar').innerHTML"), '');
  slowB.resolve(bodiesResponse());
  await switching;
});

test('UI-03 切服后总览不得留着上一个服的图表和"最吃性能"', async () => {
  // 从前 switchServer 清完 DATA/STATS 只画横幅,总览的 physLegend / dashTopCost / toolCard
  // 都还是 A 的 HTML —— 在"最吃性能"里点一下 A 的体,focusBody 撞上 DATA===null 抛异常
  const slowB = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.includes('server=B')) return slowB.promise;
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/stats')) {
      return jsonResponse({ t: [], phys: { 'minecraft:overworld': [] }, phys_1m: {}, loaded: {},
        body_cost_total: 1.5, top_cost: [{ uuid: 'OLD_SERVER_BODY', name: 'A 的巨型体', cost: 9 }] });
    }
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'dash'; SERVERS = [{id:'A',self:true},{id:'B'}]");
  await evalIn(sandbox, 'loadBodies')();
  await evalIn(sandbox, 'loadStats')();
  assert.match(evalIn(sandbox, "document.getElementById('dashTopCost').innerHTML"), /OLD_SERVER_BODY/);

  const switching = evalIn(sandbox, 'switchServer')('B');   // B 的 bodies 还没回来
  await tick();
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('dashTopCost').innerHTML"), /OLD_SERVER_BODY/,
    '切服后"最吃性能"必须立刻清空,不能等 loadAll 回来');
  assert.equal(evalIn(sandbox, "document.getElementById('physLegend').innerHTML"), '',
    '图例同理');
  // 就算 DOM 上还残留旧条目,点它也不能把页面搞崩
  evalIn(sandbox, "DATA = null");
  evalIn(sandbox, 'focusBody')('OLD_SERVER_BODY');
  slowB.resolve(bodiesResponse());
  await switching;
});

test('UI-03 切服后顶栏统计和统计弹层也要立刻清空', async () => {
  // pillCost / pillLoaded / pillSpark / statPop / 图表控件从前只在 loadStats 成功时更新。
  // switchServer 清了 STATS 却没人重画这些 —— 顶栏一直挂着上一个服的数字,
  // 新服的统计请求要是失败,就一直挂到下一次成功为止
  const slowStats = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/stats')) {
      return url.includes('server=B') ? slowStats.promise : jsonResponse({
        t: [], phys: {}, phys_1m: {}, loaded: { 'minecraft:overworld': 3 }, body_cost_total: 9,
        top_cost: [{ uuid: 'A_SERVER_BODY', name: 'A 的巨型体', cost: 9 }] });
    }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; SERVERS = [{id:'A',self:true},{id:'B'}]; toast = () => {}");
  await evalIn(sandbox, 'loadStats')();
  assert.equal(evalIn(sandbox, "document.getElementById('pillCost').textContent"), '9.00');
  assert.equal(evalIn(sandbox, "document.getElementById('pillLoaded').textContent"), 3);
  assert.match(evalIn(sandbox, "document.getElementById('statPop').innerHTML"), /A_SERVER_BODY/);

  evalIn(sandbox, 'switchServer')('B');   // B 的统计还在路上(而且可能永远不回来)
  await tick();
  assert.equal(evalIn(sandbox, "document.getElementById('pillCost').textContent"), '--',
    '顶栏不能继续显示上一个服的开销');
  assert.equal(evalIn(sandbox, "document.getElementById('pillLoaded').textContent"), '--');
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('statPop').innerHTML"), /A_SERVER_BODY/,
    '统计弹层同理');
  slowStats.resolve(jsonResponse({ t: [], phys: {}, phys_1m: {}, loaded: {}, body_cost_total: 0, top_cost: [] }));
});

test('UI-03 断开远端再登录时,旧远端的界面不能再露出来', async () => {
  // disconnectGateway 从前只清 DATA/STATS/RECYCLE,也不重画;而 authenticate 在
  // await loadAll 之前就 remove('locked') —— 新远端的 bodies 慢一点,旧远端的顶栏数字、
  // 统计弹层、列表就都又露出来了
  const slowBodies = deferred();
  let phase = 'old';
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/gateway/')) return jsonResponse({ ok: true });
    if (url.startsWith('/api/stats')) {
      return jsonResponse({ t: [], phys: {}, phys_1m: {}, loaded: {}, body_cost_total: 9,
        top_cost: [{ uuid: 'OLD_REMOTE_BODY', name: '旧远端的体', cost: 9 }] });
    }
    if (url.startsWith('/api/bodies')) {
      return phase === 'old' ? bodiesResponse({ total_bodies: 11 }) : slowBodies.promise;
    }
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: phase === 'old' ? 'OLD' : 'NEW',
      servers: [{ id: phase === 'old' ? 'OLD' : 'NEW', self: true }], running: [], log: [] });
  };
  evalIn(sandbox, "gatewayMode = 'client'; authenticated = true; toast = () => {}");
  evalIn(sandbox, "document.getElementById('loginAddress').value = '10.0.0.9'");
  await evalIn(sandbox, 'loadStats')();
  await evalIn(sandbox, 'loadBodies')();
  assert.equal(evalIn(sandbox, "document.getElementById('pillCost').textContent"), '9.00');
  assert.equal(evalIn(sandbox, 'DATA.total_bodies'), 11);

  await evalIn(sandbox, 'disconnectGateway')();
  phase = 'new';
  const login = evalIn(sandbox, 'authenticate')('t', false);
  await tick();
  await tick();
  // 此时已经解锁,而新远端的 bodies 还挂着
  assert.equal(evalIn(sandbox, 'DATA'), null, '旧远端的快照必须已经没了');
  assert.equal(evalIn(sandbox, "document.getElementById('pillCost').textContent"), '--',
    '顶栏不能还是旧远端的 9.00');
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('statPop').innerHTML"), /OLD_REMOTE_BODY/,
    '统计弹层同理');
  slowBodies.resolve(bodiesResponse());
  await login;
});

test('UI-03 切服要清掉图表区间和悬浮提示', async () => {
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; SERVERS = [{id:'A',self:true},{id:'B'}]; toast = () => {}");
  evalIn(sandbox, "STATS = {t:[], loaded:{}, body_cost_total:1, top_cost:[]}");
  // A 服上用户拖过时间轴、鼠标停在某个点上
  evalIn(sandbox, "CHART.from = 1000; CHART.to = 2000; CHART.live = false; CHART.preset = null; CHART.hoverIndex = 5");
  evalIn(sandbox, "const tip = document.getElementById('chartTip'); tip.style.display = 'block'; tip.innerHTML = 'A 服的悬浮'");

  // 断言点是"重置完成、新数据还没到"这一段:loadStats 一旦回来就会按 live 重设区间,
  // 那时 from/to 非零是对的。switchServer 的重置是同步的,所以这里不 await
  const switching = evalIn(sandbox, 'switchServer')('B');
  assert.equal(evalIn(sandbox, 'CHART.from'), 0,
    '不清 from/to 的话,页面写着"实时 5 分钟",日期输入框还是上一个服的自定义区间');
  assert.equal(evalIn(sandbox, 'CHART.to'), 0);
  assert.equal(evalIn(sandbox, 'CHART.hoverIndex'), -1);
  // 用户看见的是输入框,不是变量:1000 秒会被画成 1970-01-01
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('chartFrom').value"), /^1970/,
    '日期输入框必须跟着回到实时窗口');
  assert.equal(evalIn(sandbox, "document.getElementById('chartTip').style.display"), 'none',
    '空图上不该还挂着上一个服的悬浮提示');
  assert.equal(evalIn(sandbox, "document.getElementById('chartTip').innerHTML"), '');
  await switching;
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

test('PERF-04 作业期间的轮询只打 /api/jobs,不重建 bodies 快照', async () => {
  // 从前忙碌轮询打的是 /api/bodies:作业跑十分钟就是 300 次全量重建 + 序列化 + 下发,
  // 而变的只有 busy 那一小段。bodies 快照上限 12 MiB,这是纯烧 CPU 和带宽
  const hits = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    hits.push(url.split('?')[0]);
    if (url.startsWith('/api/jobs')) {
      return jsonResponse({ running: [{ seq: 1, op: '批量删除', state: 'running',
        phase: '定位磁盘条目', started_at: 1, queued_at: 0, targets: ['u1'] }], log: [], files: [] });
    }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  assert.deepEqual(hits, ['/api/jobs'], '一轮轮询只该有一次 /api/jobs');
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 1);
  assert.equal(evalIn(sandbox, "BUSY.has('u1')"), true, 'targets 要展开成行徽章');
  // /api/jobs 给的是 started_at/queued_at,顶栏指示器读的是 since —— 漏了归一化就是 "NaNs"
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS[0].since'), 1, 'since 必须从 started_at 归一化过来');
  assert.notEqual(evalIn(sandbox, 'busyTimer'), null, '有作业就要续上下一轮');

  // 续期的那一轮也必须打 /api/jobs。只断言"定时器存在"是不够的:把回调改回
  // loadBodies 照样有定时器,测试照样绿 —— 得把它真的执行一次
  evalIn(sandbox, 'clearBusyTimer(); __fired = null; setTimeout = fn => { __fired = fn; return 7; }');
  evalIn(sandbox, 'syncBusyPolling()');
  hits.length = 0;
  await evalIn(sandbox, '__fired')();
  await tick();
  assert.deepEqual(hits, ['/api/jobs'], '2 秒续期那一轮也不该重建 bodies 快照');
});

test('PERF-04 作业从有到无时才刷新一次 bodies', async () => {
  let bodies = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/jobs')) return jsonResponse({ running: [], log: [], files: [] });
    if (url.startsWith('/api/bodies')) { bodies++; return bodiesResponse(); }
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');

  // 本来就没有作业:不该白拉一次快照
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  assert.equal(bodies, 0, '没有作业结束就不该刷新列表');

  // 有作业 → 这轮空了:乐观更新过的字段要用服务端真值纠正回来
  evalIn(sandbox, "ACTIVE_JOBS = [{seq:1, op:'批量删除', targets:[], since:0, state:'running'}]");
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  assert.equal(bodies, 1, '作业跑完必须刷新一次,而且只刷一次');
  assert.equal(evalIn(sandbox, 'busyTimer'), null, '没作业了要停掉轮询');
});

const RUNNING_JOB = { seq: 7, op: '批量删除', state: 'running', phase: '定位磁盘条目',
  started_at: 1, queued_at: 0, targets: ['u1'] };

test('PERF-04 晚到的旧轮询响应不能覆盖新状态', async () => {
  // 提交后的立即轮询、2 秒轮询、60 秒兜底刷新会重叠。只有服务器代次没有请求序号时,
  // 旧的那份空响应后到就会把刚起的作业抹掉:徽章消失、轮询停摆、还白刷一次 bodies
  const slowOld = deferred();
  let calls = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/jobs')) {
      return ++calls === 1 ? slowOld.promise : jsonResponse({ running: [RUNNING_JOB], log: [] });
    }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');
  const pollJobs = evalIn(sandbox, 'pollJobs');
  const stale = pollJobs();
  await pollJobs();                    // 新的先回:界面上是"作业 7 在跑"
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 1);

  slowOld.resolve(jsonResponse({ running: [], log: [] }));
  await stale;
  await tick();
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 1, '旧的空响应不得把刚起的作业抹掉');
  assert.equal(evalIn(sandbox, "BUSY.has('u1')"), true, '行徽章也不能跟着消失');
  assert.notEqual(evalIn(sandbox, 'busyTimer'), null, '更不能顺手把 2 秒轮询停掉');
});

test('PERF-04 轮询失败一次不能让 2 秒轮询就此停摆', async () => {
  // 定时器回调进来时已经把 busyTimer 清空了,失败路径不续期就再没有人续
  let fail = true;
  const hits = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    hits.push(url.split('?')[0]);
    if (!url.startsWith('/api/jobs')) return jsonResponse({});
    return fail ? errorResponse(500) : jsonResponse({ running: [RUNNING_JOB], log: [] });
  };
  // 作业刚提交:JOB_WATCH 里有它,但首轮查询就失败,ACTIVE_JOBS 还是空的
  evalIn(sandbox, "authenticated = true; JOB_WATCH.set(7, '批量删除'); toast = () => {}");
  evalIn(sandbox, '__fired = null; setTimeout = fn => { __fired = fn; return 7; }');
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 0);
  assert.notEqual(evalIn(sandbox, 'busyTimer'), null,
    '首轮就失败时 ACTIVE_JOBS 是空的,只看它就等于放弃这个作业,界面连"已经开始了"都不知道');

  fail = false;
  hits.length = 0;
  await evalIn(sandbox, '__fired')();
  await tick();
  await tick();
  await tick();
  assert.deepEqual(hits, ['/api/jobs'], '续期的那一轮必须真的再查一次');
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 1, '恢复之后要能看到作业已经在跑');
});

test('PERF-04 作业在首轮轮询之前就结束时也要刷新一次 bodies', async () => {
  // 快作业赶在第一轮 /api/jobs 之前跑完:ACTIVE_JOBS 从来没有过它,had 是 0。
  // 从前只看"从有到无",于是完成 toast 弹了、列表却停在旧值,只能等 60 秒兜底
  let bodies = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/jobs')) {
      return jsonResponse({ running: [], log: [
        { seq: 7, op: '删除', state: 'done', outcome: 'ok', queued_at: 0, message: '1/1' }] });
    }
    if (url.startsWith('/api/bodies')) { bodies++; return bodiesResponse(); }
    return jsonResponse({});
  };
  evalIn(sandbox, "authenticated = true; JOB_WATCH.set(7, '删除'); __toasts = []; toast = m => __toasts.push(m)");
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  assert.equal(evalIn(sandbox, '__toasts').length, 1, '完成 toast 本来就弹得出来');
  assert.equal(bodies, 1, '终态既然消费掉了,列表也必须跟着刷一次');
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

test('PERF-03 旧服的 bodies 请求挂住时,新服的加载不能跟着一起挂', async () => {
  // 在途标记从前是个跨服务器的布尔,合并没有代次概念:新服那次只能设个"完事后补跑",
  // 而补跑要等旧请求进 finally。对端进程还在、TCP 不回包时,那一刻永远不会到 ——
  // 界面就一直停在"加载中",连 60 秒兜底都救不了(兜底走的也是同一个锁)
  const stuck = deferred();
  const seen = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) {
      seen.push(url);
      return url.includes('server=B') ? stuck.promise : bodiesResponse({ marker: 'A' });
    }
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; toast = () => {}");
  evalIn(sandbox, "CURSRV = 'B'; SERVERS = [{id:'A',self:true},{id:'B',self:false}]");
  const stuckOld = evalIn(sandbox, 'loadBodies')();   // 发在 B 上,再也不回来
  await evalIn(sandbox, 'switchServer')('A');
  await tick();
  assert.ok(seen.some(url => !url.includes('server=')), 'B 卡着不回,A 的请求也必须真的发出去');
  assert.equal(evalIn(sandbox, 'DATA && DATA.marker'), 'A');

  // 真回来了(比如几分钟后超时)也不能反过来接管:它属于上一个代次
  stuck.resolve(bodiesResponse({ marker: 'B' }));
  await stuckOld;
  await tick();
  assert.equal(evalIn(sandbox, 'DATA && DATA.marker'), 'A');
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
  await evalIn(sandbox, 'pollJobs')();
  await tick();
  toasts.push(...evalIn(sandbox, '__toasts'));
  // 这条从前断言的是 0("本来就没在跑就别刷") —— 那正是 bug:作业真的跑完了,
  // 列表还停在提交前的状态。PERF-04 要省的是每 2 秒一次的全量快照,不是完成时的那一次
  assert.equal(bodiesCalls, 1, '终态被消费掉了,列表要跟着刷一次');
  assert.equal(toasts.length, 1, '作业结束必须弹一次终态 toast');
  assert.match(toasts[0][0], /批量删除/);
  assert.match(toasts[0][0], /失败/);
  assert.equal(toasts[0][1], 'bad', '0/3 必须是红色而不是绿色');
});

// PERF-05:批量收养
test('PERF-05 批量收养只发一次请求、只刷一次列表', async () => {
  const posts = [];
  let jobPolls = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    if (opts && opts.method === 'POST') { posts.push(url); return jsonResponse({ ok: true, accepted: true, job: 1, op: '批量收养' }); }
    if (url.startsWith('/api/jobs')) { jobPolls++; return jsonResponse({ running: [], log: [], files: [] }); }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
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
  assert.equal(jobPolls, 1, '3 个孤儿体从前会产生 3 次全量刷新');
});

test('回收站切换版本页签会清选择但保留筛选条件', async () => {
  const urls = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    urls.push(url);
    if (url.startsWith('/api/recycle')) return jsonResponse({
      groups: [], block_palette: [], next_cursor: '', total_groups: 0,
      latest_groups: 2, old_groups: 3, file_count: 5, disk_bytes: 1024, limit: 500,
    });
    return jsonResponse({});
  };
  evalIn(sandbox, `
    authenticated = true;
    RECYCLE = {groups:[], latest_groups:2, old_groups:3};
    R_SELECTED = new Set(['group-1']);
    R_DIM_DISABLED.add('minecraft:the_nether');
    document.getElementById('rSearch').value = '飞艇';
    document.getElementById('rNamedOnly').checked = true;
  `);

  evalIn(sandbox, 'setRecycleTab')('old');
  await tick();
  await tick();

  assert.equal(evalIn(sandbox, 'R_TAB'), 'old');
  assert.equal(evalIn(sandbox, 'R_SELECTED').size, 0, '切版本页签后不能保留隐藏选择');
  assert.equal(evalIn(sandbox, "document.getElementById('rSearch').value"), '飞艇');
  assert.equal(evalIn(sandbox, "document.getElementById('rNamedOnly').checked"), true);
  assert.equal(evalIn(sandbox, "R_DIM_DISABLED.has('minecraft:the_nether')"), true);
  assert.ok(urls.some(url => url.startsWith('/api/recycle?version=old')),
    '旧版本页签必须请求服务端全局分类后的 old 分页');
});

test('旧版本恢复和需恢复彻底删除都会给出对应警告', async () => {
  const { sandbox } = setup();
  evalIn(sandbox, `
    __modals = [];
    askModal = (title, message) => { __modals.push([title, message]); return Promise.resolve(false); };
  `);
  const oldRecovery = {
    id: 'old-group', members: 1, blocks: 10, file_count: 1,
    version_state: 'old', state: 'recovery_required', bodies: [],
  };

  await evalIn(sandbox, 'confirmRestore')([oldRecovery]);
  await evalIn(sandbox, 'confirmPurge')([oldRecovery]);

  const modals = evalIn(sandbox, '__modals');
  assert.match(modals[0][1], /旧版本/);
  assert.ok(!modals[0][1].includes('先清除同 UUID 残留'), '旧版本恢复不得暗示会覆盖当前结构');
  assert.match(modals[1][1], /唯一的完整恢复材料/);
  assert.match(modals[1][1], /无法恢复/);
});

test('回收站作业结束后自动刷新版本统计', async () => {
  let recycleLoads = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/jobs')) return jsonResponse({ running: [], files: [], log: [
      { seq: 12, op: '回收站彻底删除', state: 'done', outcome: 'partial', message: '1/2',
        warnings: ['missing-group: 回收组不存在'] },
    ] });
    if (url.startsWith('/api/recycle')) {
      recycleLoads++;
      return jsonResponse({ groups: [], block_palette: [], next_cursor: '', total_groups: 0 });
    }
    return jsonResponse({});
  };
  evalIn(sandbox, `
    authenticated = true; JOB_WATCH.set(12, '回收站彻底删除');
    __toasts = []; toast = (message, cls) => __toasts.push([message, cls]);
  `);

  await evalIn(sandbox, 'pollJobs')();
  await tick();
  await tick();

  assert.equal(recycleLoads, 1, '彻底删除完成后必须刷新组数和磁盘占用');
  assert.match(evalIn(sandbox, '__toasts[0][0]'), /missing-group: 回收组不存在/);
});

test('副本当前版本未知或混用时禁止处理并显示完整影响范围', async () => {
  const { sandbox } = setup();
  evalIn(sandbox, `
    COPY_UUID = '00000000-0000-0000-0000-000000000001';
    COPY_VERSION = 'v1';
    COPY_SCAN = {
      current_state:'unknown', members:178, active_members:1, incomplete:[],
      versions:[{id:'v1',complete:true,current:false,members:64,blocks:100,
        active_members:1,locations:[],missing_dependencies:[],copies:[]}]
    };
    renderDedupe(COPY_SCAN);
  `);
  assert.equal(evalIn(sandbox, "document.getElementById('dedupeConfirm').disabled"), true);
  assert.match(evalIn(sandbox, "document.getElementById('copyPanelBody').innerHTML"), /114/);
  assert.match(evalIn(sandbox, "document.getElementById('copyPanelStatus').textContent"), /禁止处理/);
  evalIn(sandbox, "__submitted=0; submitJob=async()=>{ __submitted++; return true; }");
  await evalIn(sandbox, 'confirmDedupe')();
  assert.equal(evalIn(sandbox, '__submitted'), 0, '直接调用确认函数也不能绕过未知基准闸门');

  evalIn(sandbox, "COPY_SCAN.current_state='mixed'; renderDedupe(COPY_SCAN)");
  assert.equal(evalIn(sandbox, "document.getElementById('dedupeConfirm').disabled"), true);
  assert.match(evalIn(sandbox, "document.getElementById('copyPanelStatus').textContent"), /横跨多个版本/);

  evalIn(sandbox, "COPY_SCAN.current_state='known'; COPY_SCAN.current_version='v1'; renderDedupe(COPY_SCAN)");
  assert.equal(evalIn(sandbox, "document.getElementById('dedupeConfirm').disabled"), false);
});

test('副本面板即使已知当前版本也必须由用户显式选择', async () => {
  const { sandbox, state } = setup();
  state.fetch = async url => url.includes('/copy/')
    ? jsonResponse({shell:0,total:0,voxels:[],palette:[]})
    : jsonResponse({
        uuid:'00000000-0000-0000-0000-000000000001', current_state:'known', current_version:'v1',
        members:1, active_members:1, incomplete:[],
        versions:[{id:'v1',complete:true,current:true,members:1,blocks:1,
          active_members:1,locations:[],missing_dependencies:[],copies:[]}],
      });
  evalIn(sandbox, `authenticated=true; SEL={uuid:'00000000-0000-0000-0000-000000000001',name:'测试体'}`);

  await evalIn(sandbox, 'openDedupe')();

  assert.equal(evalIn(sandbox, 'COPY_VERSION'), null);
  assert.equal(evalIn(sandbox, "document.getElementById('dedupeConfirm').disabled"), true);
});

// LOAD-01:加载失败不得伪装成"没有数据"
test('LOAD-01 首次加载失败要显示加载失败,不是空列表也不是永远加载中', async () => {
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return errorResponse(500);
    if (url.startsWith('/api/recycle')) return errorResponse(500);
    return jsonResponse({ running: [], log: [], files: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'bodies'; toast = () => {}");
  await evalIn(sandbox, 'loadBodies')();
  const list = evalIn(sandbox, "document.getElementById('list').innerHTML");
  assert.match(list, /加载失败/, '服务端已经明确报错,界面不能停在"加载中…"');
  assert.equal(evalIn(sandbox, 'DATA'), null);

  evalIn(sandbox, "VIEW = 'recycle'");
  await evalIn(sandbox, 'loadRecycle')();
  await tick();
  const rList = evalIn(sandbox, "document.getElementById('rList').innerHTML");
  assert.match(rList, /加载失败/, '从前失败会写一份空数据,显示成"回收站为空"');
  assert.doesNotMatch(rList, /回收站为空/, '"加载失败"和"真的没有备份"必须区分得开');
  assert.equal(evalIn(sandbox, 'RECYCLE'), null, '失败不得伪造出一份空快照');
});

test('LOAD-01 已有数据时刷新失败要保留旧数据并标明是上次的结果', async () => {
  let ok = true;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return ok ? bodiesResponse({ total_bodies: 7 }) : errorResponse(500);
    if (url.startsWith('/api/recycle')) {
      return ok ? jsonResponse({ groups: [], block_palette: [], next_cursor: 'c1', total_groups: 3 })
                : errorResponse(500);
    }
    return jsonResponse({ running: [], log: [], files: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'bodies'; toast = () => {}");
  await evalIn(sandbox, 'loadBodies')();
  await evalIn(sandbox, 'loadRecycle')();
  await tick();
  assert.equal(evalIn(sandbox, 'DATA.total_bodies'), 7);

  ok = false;
  await evalIn(sandbox, 'loadBodies')();
  assert.equal(evalIn(sandbox, 'DATA.total_bodies'), 7, '刷新失败不能把已有数据抹掉');
  assert.match(evalIn(sandbox, "document.getElementById('toolbar').innerHTML"), /上一次的结果/);

  evalIn(sandbox, "VIEW = 'recycle'");
  await evalIn(sandbox, 'loadRecycle')(true);   // 加载更多失败
  await tick();
  assert.equal(evalIn(sandbox, 'RECYCLE_CURSOR'), 'c1', '加载更多失败要保留游标,能原地再点一次');
  assert.equal(evalIn(sandbox, 'RECYCLE_LOADING'), false, '按钮不能卡在禁用态');
});

test('LOAD-01 默认总览页首屏失败也要说明,不是一片空白', async () => {
  // 默认视图就是总览。loadBodies 的失败分支从前只调 render(),那个函数不在 bodies 页
  // 就立刻返回;renderDash 自己又挡着一句 if (!DATA) return —— 于是整页空白,
  // 用户只看到一闪而过的 toast
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies') || url.startsWith('/api/recycle')) return errorResponse(500);
    return jsonResponse({ running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'dash'; toast = () => {}");
  await evalIn(sandbox, 'loadBodies')();
  await evalIn(sandbox, 'loadRecycle')();
  await tick();
  assert.match(evalIn(sandbox, "document.getElementById('dashTop').innerHTML"), /加载失败/,
    '首屏失败,总览必须自己说出来');
  assert.match(evalIn(sandbox, "document.getElementById('cleanCard').innerHTML"), /加载失败/,
    '回收卡片从前一律画"加载中…",服务端已经 500 了也照样转圈');
});

test('LOAD-01 总览有数据时刷新失败要标明是上一次的结果', async () => {
  let ok = true;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) return ok ? bodiesResponse({ total_bodies: 7 }) : errorResponse(500);
    return jsonResponse({ running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'dash'; toast = () => {}");
  await evalIn(sandbox, 'loadBodies')();
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('dashSrv').innerHTML"), /上一次的结果/);

  ok = false;
  await evalIn(sandbox, 'loadBodies')();
  assert.equal(evalIn(sandbox, 'DATA.total_bodies'), 7, '刷新失败不能把已有数据抹掉');
  assert.match(evalIn(sandbox, "document.getElementById('dashSrv').innerHTML"), /上一次的结果/,
    '旧数字照常显示,但要说明它是上一次的');
  assert.doesNotMatch(evalIn(sandbox, "document.getElementById('dashTop').innerHTML"), /加载失败/,
    '有数据就不该画成"加载失败"');
});

test('LOAD-01 成员表加载失败要保留上一次的结果', async () => {
  let ok = true;
  const { sandbox, state } = setup();
  state.fetch = async () => ok
    ? jsonResponse({ self: 'A', servers: [{ id: 'A', self: true, host: true }, { id: 'B' }] })
    : errorResponse(500);
  evalIn(sandbox, 'authenticated = true');
  await evalIn(sandbox, 'loadServers')();
  assert.equal(evalIn(sandbox, 'SERVERS').length, 2);

  ok = false;
  await evalIn(sandbox, 'loadServers')();
  assert.equal(evalIn(sandbox, 'SERVERS').length, 2, '20 秒轮询抖一下不能让成员表清空');
  assert.notEqual(evalIn(sandbox, "document.getElementById('srvWrap').style.display"), 'none',
    '切服器不能因为一次失败就整个消失 —— 用户会以为集群掉了');
});

test('UI-03 PEER 消失时替用户收起的一致性报告,不算用户读过', async () => {
  // "已读"是永久的:scan_id 只在重新扫描时才变。B 停了服自动收起来也记一笔的话,
  // B 回来之后同一份报告再也不会弹 —— 而用户从头到尾没看见过它。
  // 更早的一版还把这一笔记到了 self 头上(applyServersResponse 先清了 CURSRV),
  // 那等于连本机的报告一起永久忽略,所以两个键都要判
  let hasB = true;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/servers')) {
      return jsonResponse({ self: 'A', servers: hasB
        ? [{ id: 'A', self: true }, { id: 'B' }] : [{ id: 'A', self: true }] });
    }
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; toast = () => {}");
  await evalIn(sandbox, 'loadServers')();
  // 正在看 B,B 的一致性报告开着
  evalIn(sandbox, `
    CURSRV = 'B';
    CONSISTENCY = { scan_id: 'B_SCAN', ready: true, issue_count: 1 };
    document.getElementById('consistencyBack').style.display = 'flex';
  `);

  hasB = false;
  await evalIn(sandbox, 'loadServers')();
  await tick();
  assert.equal(evalIn(sandbox, "document.getElementById('consistencyBack').style.display"), 'none',
    '弹层还是要收起来:它显示的是一个已经不在的服务器');
  assert.equal(evalIn(sandbox, "localStorage.getItem('spConsistencyDismissed:B')"), null,
    '替用户收起来不等于用户读过 —— 记了这一笔,B 回来时同一份报告就再也不提醒了');
  assert.equal(evalIn(sandbox, "localStorage.getItem('spConsistencyDismissed:self')"), null,
    '更不能记到本机头上 —— 那会让本机的报告被永久忽略');

  // 用户自己点关闭才算读过
  evalIn(sandbox, `
    CURSRV = ''; CONSISTENCY = { scan_id: 'SELF_SCAN', ready: true, issue_count: 1 };
    closeConsistency();
  `);
  assert.equal(evalIn(sandbox, "localStorage.getItem('spConsistencyDismissed:self')"), 'SELF_SCAN');
});

test('UI-03 正在看的服务器从成员表消失时要走完整切服,不能只把 CURSRV 清空', async () => {
  // 从前只有 CURSRV = ''。代次不推进 → B 的在途响应当成本机的落地;localStorage 不改 →
  // 刷新页面又回到那个死服;DATA 还是 B 的,而后续请求已经打向本机
  let hasB = true;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/servers')) {
      return jsonResponse({ self: 'A', servers: hasB
        ? [{ id: 'A', self: true, host: true }, { id: 'B' }] : [{ id: 'A', self: true, host: true }] });
    }
    if (url.startsWith('/api/bodies')) return bodiesResponse({ total_bodies: 5 });
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ running: [], log: [] });
  };
  evalIn(sandbox, "authenticated = true; toast = () => {}; localStorage.setItem('spServer', 'B')");
  await evalIn(sandbox, 'loadServers')();
  evalIn(sandbox, "CURSRV = 'B'");
  await evalIn(sandbox, 'loadBodies')();
  const gen = evalIn(sandbox, 'SRVGEN');
  assert.equal(evalIn(sandbox, 'DATA.total_bodies'), 5);

  hasB = false;
  await evalIn(sandbox, 'loadServers')();
  await tick();
  assert.equal(evalIn(sandbox, 'CURSRV'), '');
  assert.ok(evalIn(sandbox, 'SRVGEN') > gen, '代次必须推进,否则那个服的在途响应会当成本机的落地');
  assert.equal(evalIn(sandbox, "localStorage.getItem('spServer')"), '',
    '不改 localStorage 的话,刷新页面又回到那个已经没了的服');
});

test('LOAD-01 日志页加载失败要说明,不能停在"加载中…"', async () => {
  const { sandbox, state } = setup();
  state.fetch = async () => errorResponse(500);
  evalIn(sandbox, "authenticated = true; VIEW = 'jobs'; toast = () => {}");
  await evalIn(sandbox, 'loadJobs')();
  assert.match(evalIn(sandbox, "document.getElementById('jobsList').innerHTML"), /加载失败/,
    '从前只弹一下 toast,页面永远停在"加载中…"');
});

test('LOAD-01 切换日志文件失败,不能拿旧文件的内容冒充新文件', async () => {
  let ok = true;
  const { sandbox, state } = setup();
  state.fetch = async () => ok
    ? jsonResponse({ running: [], log: [{ seq: 1, op: 'OLD_FILE_ROW', state: 'done', queued_at: 0 }], files: [] })
    : errorResponse(500);
  evalIn(sandbox, "authenticated = true; VIEW = 'jobs'; toast = () => {}");
  await evalIn(sandbox, 'loadJobs')();
  assert.match(evalIn(sandbox, "document.getElementById('jobsList').innerHTML"), /OLD_FILE_ROW/);

  ok = false;
  await evalIn(sandbox, 'setJobsFile')('jobs-20260101-000000.jsonl');
  await tick();
  const list = evalIn(sandbox, "document.getElementById('jobsList').innerHTML");
  assert.doesNotMatch(list, /OLD_FILE_ROW/, '换文件失败时旧文件的记录必须先消失');
  assert.match(list, /加载失败/, '而且要说明失败了,不能只留一条 toast');
});

test('LOAD-01 同一个日志文件刷新失败保留旧记录,但标明是上一次的结果', async () => {
  let ok = true;
  const { sandbox, state } = setup();
  state.fetch = async () => ok
    ? jsonResponse({ running: [], log: [{ seq: 1, op: 'SAME_FILE_ROW', state: 'done', queued_at: 0 }],
        files: [], workers: 4 })
    : errorResponse(500);
  evalIn(sandbox, "authenticated = true; VIEW = 'jobs'; toast = () => {}");
  await evalIn(sandbox, 'loadJobs')();
  ok = false;
  await evalIn(sandbox, 'loadJobs')();
  assert.match(evalIn(sandbox, "document.getElementById('jobsList').innerHTML"), /SAME_FILE_ROW/,
    '同一个文件刷新失败不该把已有记录抹掉');
  assert.match(evalIn(sandbox, "document.getElementById('jobsWorkers').innerHTML"), /上一次的结果/);
});

test('日志页和作业轮询各用各的请求序号,不能互相作废', async () => {
  const { sandbox, state } = setup();
  state.fetch = async url => url.includes('poll=1')
    ? jsonResponse({ running: [RUNNING_JOB], log: [] })
    : jsonResponse({ running: [], log: [{ seq: 1, op: '删除', state: 'done' }], files: [] });
  evalIn(sandbox, "authenticated = true; VIEW = 'jobs'");
  await Promise.all([evalIn(sandbox, 'pollJobs')(), evalIn(sandbox, 'loadJobs')()]);
  await tick();
  assert.equal(evalIn(sandbox, 'ACTIVE_JOBS').length, 1, '轮询的结果不能被日志页作废');
  assert.equal(evalIn(sandbox, 'JOBS').log.length, 1, '日志页的结果不能被轮询作废');
});

test('LOAD-01 一致性报告的等待循环失败一次不能整个放弃', async () => {
  // 两个等待循环都是 catch { return; }:抖一次网络,报告就永远不出现,
  // 用户点了"一致性扫描"却什么都没等到 —— 循环本来就有次数上限,重试是安全的
  let calls = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (!url.startsWith('/api/consistency')) return jsonResponse({});
    return ++calls === 1 ? errorResponse(500)
      : jsonResponse({ ready: true, scan_id: 's2', issue_count: 0 });
  };
  evalIn(sandbox, "authenticated = true; CONSISTENCY = null; toast = () => {}");
  evalIn(sandbox, 'setTimeout = fn => { fn(); return 0; }');   // 跳过 1 秒退避
  await evalIn(sandbox, 'scheduleStartupConsistency')();
  assert.ok(calls > 1, '失败之后必须继续等,实际只请求了 ' + calls + ' 次');
  assert.equal(evalIn(sandbox, 'CONSISTENCY.scan_id'), 's2', '恢复之后要拿到报告');
});

// UI-04:预览旧失败
test('UI-04 同一个体连点两次时,先发的 mesh 响应不得盖掉后发的', async () => {
  // isCurrent() 只看 SEL.uuid,认不出是哪一次请求:X→Y→X 之后 X 的第一次响应照样满足它,
  // 于是往场景里再加一套网格、覆盖全局 mesh —— 后加的那套从此没人能释放
  const firstX = deferred();
  let xCalls = 0;
  const { sandbox, state } = setup();
  const emptyMesh = marker => jsonResponse({ shell: 0, total: 0, voxels: [], palette: [], marker });
  state.fetch = async (url) => {
    if (url.includes('/u-x/mesh')) return ++xCalls === 1 ? firstX.promise : emptyMesh('x2');
    if (url.includes('/u-y/mesh')) return emptyMesh('y');
    return jsonResponse({});
  };
  evalIn(sandbox, "authenticated = true; SEL = {uuid:'u-x'}");
  const pendingFirst = evalIn(sandbox, 'loadMesh')('u-x');
  evalIn(sandbox, "SEL = {uuid:'u-y'}");
  await evalIn(sandbox, 'loadMesh')('u-y');
  evalIn(sandbox, "SEL = {uuid:'u-x'}");
  await evalIn(sandbox, 'loadMesh')('u-x');
  assert.equal(evalIn(sandbox, 'MESH_DATA && MESH_DATA.marker'), 'x2');

  firstX.resolve(emptyMesh('x1'));
  await pendingFirst;
  await tick();
  assert.equal(evalIn(sandbox, 'MESH_DATA && MESH_DATA.marker'), 'x2',
    '先发的那次回来时,界面上早就是后发的那次了');
});

test('UI-03 跨服撞上同一个 uuid 时,旧服的 mesh 响应不得落到新服的预览上', async () => {
  // 同机多服常见做法就是把存档目录复制一份,uuid 会真的一模一样 —— isCurrent() 认不出服务器
  const slowA = deferred();
  const { sandbox, state } = setup();
  const emptyMesh = marker => jsonResponse({ shell: 0, total: 0, voxels: [], palette: [], marker });
  state.fetch = async (url) => {
    if (url.includes('/mesh')) return url.includes('server=B') ? emptyMesh('B') : slowA.promise;
    if (url.startsWith('/api/bodies')) return bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ self: 'A', servers: [{ id: 'A', self: true }, { id: 'B' }], running: [], log: [] });
  };
  evalIn(sandbox, `
    authenticated = true; VIEW = 'bodies'; toast = () => {};
    SERVERS = [{id:'A',self:true},{id:'B'}]; SEL = {uuid:'same'};
  `);
  const pendingA = evalIn(sandbox, 'loadMesh')('same');
  await evalIn(sandbox, 'switchServer')('B');
  evalIn(sandbox, "SEL = {uuid:'same'}");        // 在 B 上选中同一个 uuid
  await evalIn(sandbox, 'loadMesh')('same');
  assert.equal(evalIn(sandbox, 'MESH_DATA && MESH_DATA.marker'), 'B');

  slowA.resolve(emptyMesh('A'));
  await pendingA;
  await tick();
  assert.equal(evalIn(sandbox, 'MESH_DATA && MESH_DATA.marker'), 'B', 'A 的响应属于上一个服务器');
});

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

test('UI-03 快速连切时旧那次的成功 toast 不能弹出来', async () => {
  const slowA = deferred();
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    // 只有 A 回得慢。不能按 server= 一概而论 —— 切到 B 之后 B 会真的自己发一次请求
    // (在途合并按代次隔离),那次要是也返回 A 的 deferred,整个用例就死等在这儿
    if (url.startsWith('/api/bodies')) return url.includes('server=A') ? slowA.promise : bodiesResponse();
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({ servers: [], self: 'S' });
  };
  evalIn(sandbox, 'authenticated = true');
  evalIn(sandbox, 'toast = (msg) => globalThis.__toasts.push(msg)');
  evalIn(sandbox, '__toasts = []');
  evalIn(sandbox, "SERVERS = [{id:'S',self:true},{id:'A',self:false},{id:'B',self:false}]");

  const pendingA = evalIn(sandbox, 'switchServer')('A');   // A 的 bodies 回得慢
  await evalIn(sandbox, 'switchServer')('B');              // 界面已经是 B 了
  slowA.resolve(bodiesResponse());
  await pendingA;
  await tick();

  const toasts = evalIn(sandbox, '__toasts');
  assert.ok(!toasts.some(msg => /切换到 A/.test(msg)),
    '界面已经切到 B,再弹"已切换到 A"就是骗人:' + JSON.stringify(toasts));
  assert.ok(toasts.some(msg => /切换到 B/.test(msg)), '当前那次的反馈还是要有');
});

test('PERF-03 注销后不得补跑一次带空 token 的 bodies 请求', async () => {
  const slow = deferred();
  const urls = [];
  const { sandbox, state } = setup();
  state.fetch = async (url) => {
    if (url.startsWith('/api/bodies')) {
      urls.push(url);
      return urls.length === 1 ? slow.promise : bodiesResponse();
    }
    return jsonResponse({});
  };
  evalIn(sandbox, 'authenticated = true');
  const pending = evalIn(sandbox, 'loadBodies')();
  evalIn(sandbox, 'loadBodies')();      // 重叠,登记成"完事后再跑一次"
  evalIn(sandbox, 'showLogin')('');     // 用户在这期间注销:token 已经清空
  slow.resolve(bodiesResponse());
  await pending;
  await tick();

  assert.equal(urls.length, 1, '注销后补跑的那次会带空 token 出去,白吃 401 再把人推进登录流程');
  assert.equal(evalIn(sandbox, 'authenticated'), false, '注销状态不能被补跑翻回来');
});

test('UI-02 旧请求晚到的 401 不能注销已经重新登录的会话', async () => {
  const slow401 = deferred();
  let bodies = 0;
  const { sandbox, state } = setup();
  state.fetch = async (url, opts) => {
    const sent = opts && opts.headers && opts.headers['X-Token'];
    // 第一次是旧 token 发出的那笔,回得慢;之后(重新登录触发的补跑)用新 token,正常成功
    if (url.startsWith('/api/bodies')) return ++bodies === 1 ? slow401.promise : bodiesResponse();
    if (url.startsWith('/api/servers')) {
      return sent === 'new' ? jsonResponse({ servers: [], self: 'S' }) : errorResponse(401);
    }
    if (url.startsWith('/api/recycle')) return jsonResponse({ groups: [], block_palette: [], next_cursor: '' });
    return jsonResponse({});
  };
  evalIn(sandbox, "token = 'old'; authenticated = true");
  const pending = evalIn(sandbox, 'loadBodies')();
  assert.equal(await evalIn(sandbox, 'authenticate')('new', false), true, '新口令要能登录成功');

  slow401.resolve(errorResponse(401));   // 旧 token 的那次请求现在才失败
  await pending;
  await tick();

  assert.equal(evalIn(sandbox, 'authenticated'), true, '旧凭据的 401 不能把刚登录成功的人踢回登录页');
  assert.equal(evalIn(sandbox, 'token'), 'new', '更不能顺手清掉新 token');
});

test('UI-02 当前凭据的 401 仍然要注销', async () => {
  const { sandbox, state } = setup();
  state.fetch = async () => errorResponse(401);
  evalIn(sandbox, "token = 'cur'; authenticated = true");
  await evalIn(sandbox, 'loadBodies')();
  assert.equal(evalIn(sandbox, 'authenticated'), false, '口令真被改掉时必须锁页,别把保护也一起关了');
});

test('LIMIT-01 组内成员被截断时禁止整组选择,删除确认按真实成员数', async () => {
  const { sandbox } = setup();
  const group = { gid: 'g1', name: '', members: 100, members_omitted: 40, blocks: 9,
    dims: 'minecraft:overworld', loaded: 0, orphans: 0, holding: 0, types: 1, be: 0, contents: 0,
    bodies: [{ uuid: 'u1', dim: 'minecraft:overworld', blocks: 9, state: 'stored', blk: [] }] };
  evalIn(sandbox, 'DATA = { groups: [], truncated: true, shown_groups: 1, total_groups: 1, omitted_members: 40 }');
  evalIn(sandbox, '__group = null');
  sandbox.__group = group;

  // 确认弹窗按 members 而不是可见 uuid 数报数:后端会把依赖组重新展开成完整组
  const asked = [];
  evalIn(sandbox, 'askModal = (title, msg) => { globalThis.__asked.push(msg); return Promise.resolve(false); }');
  evalIn(sandbox, '__asked = []');
  evalIn(sandbox, "BODY_BY_UUID = new Map([['u1', {b: __group.bodies[0], g: __group}]]); SELECTED = new Set(['u1'])");
  await evalIn(sandbox, 'doDeleteSelected')();
  asked.push(...evalIn(sandbox, '__asked'));
  assert.equal(asked.length, 1);
  assert.match(asked[0], /100/, '确认数必须是展开后的 100,不是可见的 1:' + asked[0]);
  assert.ok(!/\b1 个物理体/.test(asked[0]), '不能按可见 uuid 数报数');

  // 截断提示要分开说,不能套"只显示 N / M 组"的模板报出自相矛盾的数字
  evalIn(sandbox, 'renderToolbar')(1, 1);
  const bar = evalIn(sandbox, "document.getElementById('toolbar')").innerHTML;
  assert.ok(!/3000/.test(bar), '组数没被截断时不该出现组数上限:' + bar);
  assert.match(bar, /40/, '应当说清省略了 40 个成员');
});

test('LIMIT-01 加载更多成功后按钮要恢复可用', async () => {
  const { sandbox, state } = setup();
  let page = 0;
  state.fetch = async (url) => {
    if (!url.startsWith('/api/recycle')) return jsonResponse({});
    page++;
    return jsonResponse({ groups: [], block_palette: [], total_groups: 9, limit: 500,
      file_count: 0, disk_bytes: 0, next_cursor: page < 3 ? 'c' + page : '' });
  };
  evalIn(sandbox, "authenticated = true; VIEW = 'recycle'");
  await evalIn(sandbox, 'loadRecycle')();
  await evalIn(sandbox, 'loadRecycle')(true);
  await tick();

  assert.equal(evalIn(sandbox, 'RECYCLE_LOADING'), false);
  const html = evalIn(sandbox, "document.getElementById('rToolbar')").innerHTML;
  assert.ok(!/disabled/.test(html), '按钮渲染在清 loading 之前就会永久停在禁用的"加载中…":' + html);
});

test('UI-04 组被截断时成员复选框只切自己,完整组仍整组切', () => {
  const { sandbox } = setup();
  const select = (map) => {
    evalIn(sandbox, 'BODY_BY_UUID = new Map(); SELECTED = new Set();');
    evalIn(sandbox, 'globalThis.__fixture = ' + JSON.stringify(map));
    evalIn(sandbox, `
      for (const [u, g] of Object.entries(__fixture)) BODY_BY_UUID.set(u, {b:{uuid:u}, g});
    `);
  };
  const partial = {gid:'g1', members:9, members_omitted:6,
    bodies:[{uuid:'u1'},{uuid:'u2'},{uuid:'u3'}]};
  select({u1: partial, u2: partial, u3: partial});
  evalIn(sandbox, 'toggleSel')('u1');
  assert.deepEqual([...evalIn(sandbox, 'SELECTED')], ['u1'],
    '组只下发了 3/9 个成员,整组切就等于让后续操作作用在"可见的那些"上');

  const whole = {gid:'g2', members:3, bodies:[{uuid:'v1'},{uuid:'v2'},{uuid:'v3'}]};
  select({v1: whole, v2: whole, v3: whole});
  evalIn(sandbox, 'toggleSel')('v1');
  assert.deepEqual([...evalIn(sandbox, 'SELECTED')].sort(), ['v1','v2','v3'],
    '完整组是删除原子单位,整组选择的语义不变');
});

test('LIMIT-01 摘要组(bodies 为空)不能让回收站整页崩掉', () => {
  const { sandbox } = setup();
  evalIn(sandbox, `
    VIEW = 'recycle';
    RECYCLE = {
      groups: [
        {id:'20260101-000009-000-aabbccdd', state:'deleted', deleted_at: 1, name:'',
         members: 900, blocks: 12345, bodies: [], blocks_omitted: true, bodies_omitted: true},
        {id:'20260101-000001-000-11223344', state:'deleted', deleted_at: 2, name:'飞艇',
         members: 1, blocks: 7, bodies: [{uuid:'u1', name:'飞艇', dim:'minecraft:overworld', blocks:7}]},
      ],
      block_palette: [], latest_groups: 2, old_groups: 0, limit: 500, file_count: 2,
    };
    RECYCLE_BY_ID = new Map(RECYCLE.groups.map(g=>[g.id,g]));
    RECYCLE_TOTAL = 2;
  `);
  // 筛选控件按"全选"接管:沙箱里的 DOM 是宽松代理,querySelectorAll 会返回空,
  // 不接管的话所有组都会被筛掉,测不到渲染
  evalIn(sandbox, `
    globalThis.__els = {};
    document.getElementById = id => __els[id] || (__els[id] =
      {innerHTML:'', value:'', checked:false, textContent:'', style:{}, querySelectorAll:()=>[]});
    document.querySelectorAll = sel =>
      sel.startsWith('.rFState') ? [{value:'deleted'},{value:'restored'},{value:'recovery_required'}]
      : sel.startsWith('.rFSize') ? ['huge','large','mid','small','frag'].map(v=>({value:v}))
      : [];
  `);
  // 从前这里直接读 group.bodies[0].uuid 拼标题,摘要组一到就 TypeError,整个列表白屏
  evalIn(sandbox, 'renderRecycleDims')();
  evalIn(sandbox, 'renderRecycle')();
  const html = evalIn(sandbox, "document.getElementById('rList')").innerHTML;
  assert.ok(html.includes('20260101-000009'), '标题要回落到组 id:' + html.slice(0, 200));
  assert.ok(html.includes('飞艇'), '正常组照常渲染');
  assert.ok(html.includes(evalIn(sandbox, "t('rBodiesOmitted')")), '要说明成员明细被省略');
  // 摘要组没有成员,按维度筛选时不能把它静默滤掉
  assert.ok(evalIn(sandbox, 'RECYCLE.groups').length === 2);
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

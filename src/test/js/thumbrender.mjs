/*
 * 缩略图离屏渲染队列回归(R7):用 node:vm 加载真实的 thumbrender.js,mock 掉
 * transport/runtime/fetch,验证签名握手与调度契约。
 *
 *   node src/test/js/thumbrender.mjs
 *
 * 覆盖:邀请函→渲染→带签名上传→onDone;too_large 永久放弃且不再发请求;
 *      资源未就绪不上传(半成品守则);详情页开着让路;切服 reset 后旧渲染不回执;
 *      去重;worker 常驻复用(keepWorker 构造 + reset 真杀)。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
  '../../main/resources/web/js/preview/thumbrender.js');

function flush(rounds = 20) {
  let p = Promise.resolve();
  for (let i = 0; i < rounds; i++) p = p.then(() => new Promise(r => setTimeout(r, 0)));
  return p;
}

function makeWorld() {
  const world = {
    meshCalls: [],            // transport.request 的每次调用路径
    meshScript: [],           // 依次弹出的应答;耗尽后重复最后一个
    posts: [],                // fetch POST 记录 {url, body}
    statusOnLoad: 'high',     // runtime.load 后异步发出的终态
    loads: 0,
  };
  const sandbox = {
    console, Promise, Math, Date, JSON,
    setTimeout, clearTimeout, setInterval, clearInterval,
    token: 'tok', CURSRV: '',
    URL: { createObjectURL: () => 'blob:fake' },
    document: {
      visibilityState: 'visible',
      addEventListener() {},
      createElement: () => ({ style: {} }),
      body: { appendChild() {} },
    },
    THREE: {},
    SablePreviewTransport: {
      async request(p) {
        world.meshCalls.push(p);
        const next = world.meshScript.length > 1 ? world.meshScript.shift() : world.meshScript[0];
        if (next instanceof Error) throw next;
        return next;
      },
    },
    SablePreviewRuntime: class {
      constructor(options) { this.options = options; world.runtimeOptions = options; }
      init() {
        this.renderer = {
          render() {},
          domElement: { toBlob: cb => cb({ size: 42, fake: 'png' }) },
        };
        this.scene = {}; this.camera = { position: { set() {} }, lookAt() {} };
        this.center = [0, 0, 0]; this.distance = 10; this.rotX = .5; this.rotY = .7;
        return this;
      }
      load() {
        world.loads++;
        setTimeout(() => this.options.onStatus(world.statusOnLoad), 0);
      }
      disposeObjects() {}
      sortTranslucent() {}
      terminateWorker() { world.workerKills = (world.workerKills || 0) + 1; }
    },
    fetch: async (url, options) => {
      world.posts.push({ url, body: options && options.body });
      return { ok: true };
    },
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(SRC, 'utf8'), sandbox, { filename: 'thumbrender.js' });
  world.sandbox = sandbox;
  world.api = sandbox.SableThumbRender;
  return world;
}

const readyMesh = (resources = { status: 'ready', manifest: '/api/preview/resources/x/manifest' }) =>
  ({ status: 'ready', mesh: { metadata: { resources } } });

/* 1. 主通路:邀请函 → mesh → 渲染 → 带签名上传 → onDone(url) */
{
  const world = makeWorld();
  world.meshScript = [readyMesh()];
  const done = [];
  world.api.onDone = (uuid, url) => done.push([uuid, url]);
  world.api.enqueue('u-1', 'f1|sig-a');
  await flush();
  assert.equal(world.meshCalls[0], '/api/body/u-1/mesh');
  assert.equal(world.loads, 1);
  assert.equal(world.posts.length, 1, '渲完必须上传');
  assert.match(world.posts[0].url, /^\/api\/thumb\/u-1\?sig=f1%7Csig-a$/, '签名必须原样带回');
  assert.deepEqual(done, [['u-1', 'blob:fake']], '上传成功后回执本地位图');
}

/* 2. too_large:永久放弃,onDone(null),再邀请也不发请求 */
{
  const world = makeWorld();
  world.meshScript = [{ status: 'too_large' }];
  const done = [];
  world.api.onDone = (uuid, url) => done.push([uuid, url]);
  world.api.enqueue('u-2', 's');
  await flush();
  assert.deepEqual(done, [['u-2', null]], '永久放弃要回执 null 摘掉「生成中」');
  assert.equal(world.posts.length, 0);
  const callsBefore = world.meshCalls.length;
  world.api.enqueue('u-2', 's2');
  await flush();
  assert.equal(world.meshCalls.length, callsBefore, '本会话不得再为它发 mesh 请求');
}

/* 3. 半成品守则:资源失败不渲不传,非永久(冷却后还有机会) */
{
  const world = makeWorld();
  world.meshScript = [readyMesh({ status: 'failed' })];
  const done = [];
  world.api.onDone = (uuid, url) => done.push([uuid, url]);
  world.api.enqueue('u-3', 's');
  await flush();
  assert.equal(world.loads, 0, '资源没就绪就不该动渲染器');
  assert.equal(world.posts.length, 0, '纯色半成品绝不能入服务端缓存');
  assert.deepEqual(done, [], '瞬态失败不回执 null(冷却重试)');
}

/* 4. 详情页开着让路;关掉后再邀请立即渲 */
{
  const world = makeWorld();
  world.meshScript = [readyMesh()];
  world.sandbox.SEL = { uuid: 'watching' };
  world.api.onDone = () => {};
  world.api.enqueue('u-4', 's');
  await flush();
  assert.equal(world.meshCalls.length, 0, '大预览在用 GPU,队列必须让路');
  world.sandbox.SEL = null;
  world.api.enqueue('u-4', 's');
  await flush();
  assert.equal(world.posts.length, 1, '详情页关掉后恢复渲染');
}

/* 5. 切服 reset:渲染中代次作废,不上传不回执;常驻 worker 必须真杀(bake 可能还在跑) */
{
  const world = makeWorld();
  let releaseMesh;
  const gate = new Promise(r => { releaseMesh = r; });
  world.sandbox.SablePreviewTransport.request = async p => {
    world.meshCalls.push(p);
    await gate;
    return readyMesh();
  };
  const done = [];
  world.api.onDone = (uuid, url) => done.push([uuid, url]);
  world.api.enqueue('u-5', 's');
  await flush(3);
  world.api.reset();
  releaseMesh();
  await flush();
  assert.equal(world.posts.length, 0, '切服后的旧渲染不得上传');
  assert.deepEqual(done, [], '切服后的旧渲染不得回执');
}

/* 7. worker 常驻:runtime 必须以 keepWorker 构造(跨体缓存的开关),reset 时真杀 worker */
{
  const world = makeWorld();
  world.meshScript = [readyMesh()];
  world.api.onDone = () => {};
  world.api.enqueue('u-7', 's');
  await flush();
  assert.equal(world.posts.length, 1);
  assert.equal(world.runtimeOptions.keepWorker, true, '离屏渲染队列必须开启 worker 复用');
  world.api.reset();
  assert.equal(world.workerKills, 1, 'reset 必须终止常驻 worker,进行中的 bake 不得串进下一代');
}

/* 6. 去重:同 uuid 连续两次邀请只渲一次,后到的签名生效 */
{
  const world = makeWorld();
  world.meshScript = [readyMesh()];
  world.sandbox.SEL = { hold: true };   // 先按住队列,让两次 enqueue 都停在队里
  world.api.onDone = () => {};
  world.api.enqueue('u-6', 'old-sig');
  world.api.enqueue('u-6', 'new-sig');
  world.sandbox.SEL = null;
  world.api.enqueue('u-6', 'new-sig'); // 触发泵
  await flush();
  assert.equal(world.loads, 1, '同一体只渲一次');
  assert.equal(world.posts.length, 1);
  assert.match(world.posts[0].url, /sig=new-sig/, '后到的签名覆盖先到的');
}

/* 8. 流水线预热:渲 A 时必须把 B 的 mesh 提取和闭包构建先在服务端排上 */
{
  const world = makeWorld();
  world.meshScript = [readyMesh()];
  world.api.onDone = () => {};
  world.api.enqueue('u-8a', 's');
  world.api.enqueue('u-8b', 's');
  await flush(40);
  const uploads = world.posts.filter(p => p.url.startsWith('/api/thumb/'));
  assert.equal(uploads.length, 2, '两体都要渲完上传');
  const bFirstAsk = world.meshCalls.indexOf('/api/body/u-8b/mesh');
  assert.ok(bFirstAsk >= 0 && bFirstAsk <= 1, '渲 A 期间就该发出 B 的 mesh 预热(流水线),实际首问序=' + bFirstAsk);
  const manifestWarm = world.posts.filter(p => p.url.startsWith('/api/preview/resources/') && !p.body);
  assert.ok(manifestWarm.length >= 1, '预热必须 GET manifest 触发服务端闭包构建');
}

console.log('thumbrender.mjs: 8 组契约全部通过');

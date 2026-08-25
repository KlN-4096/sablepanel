'use strict';
/* 缩略图离屏渲染队列:与详情预览同一套 SablePreviewRuntime,渲一帧上传服务端缓存。
   服务端只发「签名邀请函」(GET /api/thumb 404 附 sig / 200 附 X-Thumb-Stale),
   首个看到该体的浏览器渲完 POST 回去,之后所有人直接吃缓存 —— 服务端零渲染开销。
   顶层零副作用:监听与 WebGL 上下文都在首次 enqueue 时才建(node:vm 回归会加载本文件)。 */
(function (global) {
  const RETRY_COOLDOWN_MS = 5 * 60 * 1000;
  const MESH_POLL_LIMIT = 120;
  const SETTLE_TIMEOUT_MS = 180000;

  const queue = new Map();      // uuid -> sig(后到的 sig 覆盖先到)
  const cooldown = new Map();   // uuid -> 冷却到期毫秒;Infinity=本会话永久放弃
  let runtime = null, statusHandler = null;
  let rendering = false, unsupported = false, listening = false;
  let generation = 0, pendingTimer = 0, currentUuid = null;

  function enqueue(uuid, sig) {
    if (unsupported || !uuid || !sig || uuid === currentUuid) return;
    if ((cooldown.get(uuid) || 0) > Date.now()) return;
    if (!listening && typeof document !== 'undefined' && document.addEventListener) {
      listening = true;
      // 后台标签页 rAF 停摆,渲不动也不该渲;回到前台再把队列泵起来
      document.addEventListener('visibilitychange', pump);
    }
    queue.set(uuid, sig);
    pump();
  }

  function reset() {
    generation++;
    queue.clear();
    cooldown.clear();
    if (pendingTimer) { clearTimeout(pendingTimer); pendingTimer = 0; }
    // 进行中的 bake 必须真杀:worker 常驻复用的前提是上一次 bake 已终态
    if (runtime) { runtime.terminateWorker(); runtime.disposeObjects(); }
  }

  /* 详情页大预览开着就让路:同一块 GPU,列表缩略图不抢交互体验。
     SEL/RSEL 是 views 层顶层 let,typeof 探测,单文件加载(vm 测试)也不炸。 */
  function busy() {
    return !!(typeof SEL !== 'undefined' && SEL) || !!(typeof RSEL !== 'undefined' && RSEL);
  }

  function schedule(ms) {
    if (pendingTimer) return;
    pendingTimer = setTimeout(() => { pendingTimer = 0; pump(); }, ms);
  }

  function pump() {
    if (rendering || unsupported || !queue.size) return;
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    if (busy()) { schedule(3000); return; }
    const next = queue.entries().next().value;
    queue.delete(next[0]);
    rendering = true;
    currentUuid = next[0];   // 渲慢过 fetchThumb 的 30s 重试节奏时,别把正渲的体又排一遍
    renderOne(next[0], next[1])
      .catch(() => cooldown.set(next[0], Date.now() + RETRY_COOLDOWN_MS))
      .finally(() => { rendering = false; currentUuid = null; pump(); });
  }

  async function renderOne(uuid, sig) {
    const gen = generation;
    let result = null;
    try {
      for (let attempt = 0; attempt < MESH_POLL_LIMIT; attempt++) {
        if (gen !== generation) return;
        result = await SablePreviewTransport.request('/api/body/' + uuid + '/mesh');
        if (result.status === 'ready' || result.status === 'too_large') break;
        /* 不听服务端的 retry_after(=1s,给交互式预览的通用建议):提取通常几十 ms 就完,
           按它睡满 1 秒是实测里每体 85% 的耗时(2026-08-15 计时行:mesh 恒 ~1s,烘焙 2~27ms)。
           150ms 起步指数回退,大体提取慢时自然退到 5s 上限,状态查询本身很便宜。 */
        await sleep(Math.min(150 * 2 ** attempt, 5000));
      }
    } catch (error) {
      if (gen !== generation) return;
      const code = String(error && error.message || '');
      // 副本歧义/提取失败不会自愈;网络抖动之类给个冷却下次再来
      return giveUp(uuid, code === 'preview_version_ambiguous' || code === 'preview_failed');
    }
    if (gen !== generation) return;
    if (!result || result.status !== 'ready') return giveUp(uuid, !!result && result.status === 'too_large');
    const meta = result.mesh.metadata && result.mesh.metadata.resources;
    // 半成品守则:资源没就绪不渲不传 —— 纯色版一旦入缓存就以该签名为键永久留存
    if (!meta || !meta.manifest || meta.status === 'unavailable' || meta.status === 'failed') {
      return giveUp(uuid, false);
    }
    const rt = ensureRuntime();
    if (!rt) return;
    const settled = waitSettled(SETTLE_TIMEOUT_MS);
    rt.load(result.mesh, {manifestUrl: meta.manifest, token: currentToken(), server: currentServer(),
      fingerprint:meta.fingerprint || ''});
    const upNext = queue.keys().next().value;
    if (upNext) prewarm(upNext);
    const outcome = await settled;
    if (gen !== generation) { rt.disposeObjects(); return; }
    if (outcome.status !== 'high') {
      // 超时=bake 可能还在跑,复用会把旧消息串进下一个体,必须真杀(缓存一并丢,罕见路径不心疼)
      if (outcome.status === 'timeout') rt.terminateWorker();
      rt.disposeObjects();
      // 空体/浏览器能力不足=不会自愈;超时与资源准备失败(基线有退避重试)冷却后再试
      return giveUp(uuid, outcome.status === 'empty' || outcome.status === 'unsupported'
        || outcome.status === 'resource_unavailable');
    }
    const blob = await snapshot(rt);
    rt.disposeObjects();
    if (gen !== generation || !blob) return;
    // 409 thumb_stale = 渲染期间体变了:丢弃即可,fetchThumb 下轮拿新签名再来
    const ok = await upload(uuid, sig, blob);
    if (ok && gen === generation && api.onDone) api.onDone(uuid, URL.createObjectURL(blob));
  }

  function giveUp(uuid, permanent) {
    cooldown.set(uuid, permanent ? Infinity : Date.now() + RETRY_COOLDOWN_MS);
    if (permanent && api.onDone) api.onDone(uuid, null);
  }

  /* 流水线预热:当前体的烘焙在 worker/GPU 里跑时,把队列下一体的 mesh 提取和资源闭包
     构建先在服务端排上 —— 轮到它正式渲染时两样都已就绪,资源段只剩下载
     (2026-08-15 计时行:串行时每体资源段恒 ~570ms=闭包构建等待+轮询税)。
     纯 fire-and-forget:不碰队列/冷却,失败静默,裁决一律留给正式 renderOne。 */
  let prewarming = null;
  async function prewarm(uuid) {
    if (uuid === prewarming) return;
    prewarming = uuid;
    try {
      for (let attempt = 0; attempt < 15; attempt++) {
        const r = await SablePreviewTransport.request('/api/body/' + uuid + '/mesh');
        if (r.status === 'too_large') return;
        if (r.status === 'ready') {
          const meta = r.mesh.metadata && r.mesh.metadata.resources;
          if (!meta || !meta.manifest) return;
          // GET 一次即触发服务端闭包构建排队,202 也达成目的,响应丢弃
          let url = meta.manifest;
          const server = currentServer();
          if (server) url += (url.includes('?') ? '&' : '?') + 'server=' + encodeURIComponent(server);
          await fetch(url, {headers: {'X-Token': currentToken()}});
          return;
        }
        await sleep(Math.min(150 * 2 ** attempt, 5000));
      }
    } catch (ignored) {
      // 预热失败无所谓:正式 renderOne 会自己走完整轮询并做冷却裁决
    } finally {
      if (prewarming === uuid) prewarming = null;
    }
  }

  function ensureRuntime() {
    if (runtime) return runtime.renderer ? runtime : null;
    if (typeof SablePreviewRuntime === 'undefined' || typeof THREE === 'undefined'
        || typeof document === 'undefined' || !document.body) {
      unsupported = true;
      return null;
    }
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:-10000px;top:0;width:320px;height:240px;pointer-events:none';
    document.body.appendChild(host);
    // keepWorker:跨体复用同一 worker,资源分片/模型解析/纹理解码缓存在 worker 里跨体生效
    runtime = new SablePreviewRuntime({host, keepWorker: true,
      onStatus: (s, d) => statusHandler && statusHandler(s, d)}).init();
    runtime.autoRotate = false;   // 构造读 localStorage 的自转开关;截帧要确定角度
    if (runtime.unsupported || !runtime.renderer) { unsupported = true; return null; }
    return runtime;
  }

  /* load() 同步发 'fallback'(不终结);高保真完成或明确失败才落定。
     detail 透传:'high' 时是 worker 的 stats(含分段计时)。 */
  function waitSettled(timeoutMs) {
    return new Promise(resolve => {
      const timer = setTimeout(() => { statusHandler = null; resolve({status: 'timeout'}); }, timeoutMs);
      statusHandler = (status, detail) => {
        if (status === 'high' || status === 'resource_failed' || status === 'resource_unavailable'
            || status === 'unsupported' || status === 'empty') {
          clearTimeout(timer);
          statusHandler = null;
          resolve({status, detail});
        }
      };
    });
  }

  /* 与 runtime.loop 同一相机公式(rotX=.5/rotY=.7 即详情页初始视角),同步 render 后
     立即 toBlob —— preserveDrawingBuffer:false 下只有同一任务内读取才保证有效 */
  function snapshot(rt) {
    const c = rt.center, rx = rt.rotX, ry = rt.rotY;
    /* 相机距离不用详情页的 span*1.8+8 —— 那是给交互浏览留余地的,+8 会把几个方块的
       小体缩成画面中央一粒(用户实机反馈"软光栅时缩放刚刚好":旧管线是包围盒撑满画布)。
       按包围球拟合视锥:center=max/2,实际尺寸=max+1;直径约占画面 84%,任意朝向不裁边。 */
    const r = Math.max(1, Math.hypot(c[0] * 2 + 1, c[1] * 2 + 1, c[2] * 2 + 1) / 2);
    const fov = (rt.camera.fov || 50) * Math.PI / 180;
    const d = r / Math.sin(fov / 2) * 1.08;
    rt.camera.position.set(c[0] + d * Math.cos(rx) * Math.sin(ry),
      c[1] + d * Math.sin(rx), c[2] + d * Math.cos(rx) * Math.cos(ry));
    rt.camera.lookAt(c[0], c[1], c[2]);
    rt.sortTranslucent();
    rt.renderer.render(rt.scene, rt.camera);
    return new Promise(resolve => rt.renderer.domElement.toBlob(resolve, 'image/png'));
  }

  async function upload(uuid, sig, blob) {
    const server = currentServer();
    const response = await fetch('/api/thumb/' + uuid + '?sig=' + encodeURIComponent(sig)
      + (server ? '&server=' + encodeURIComponent(server) : ''),
      {method: 'POST', headers: {'X-Token': currentToken()}, body: blob});
    return response.ok;
  }

  function currentToken() { return typeof token !== 'undefined' ? token : (global.token || ''); }
  function currentServer() { return (typeof CURSRV !== 'undefined' ? CURSRV : global.CURSRV) || ''; }
  function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

  const api = {enqueue, reset, onDone: null};
  global.SableThumbRender = api;
})(globalThis);

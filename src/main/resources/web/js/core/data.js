'use strict';
/* 数据编排层:从后端拉取 bodies/stats/recycle/servers 并更新全局状态、触发渲染 */
/* 同一上下文的 bodies 请求合并,换服/换凭据就取消旧的那次。只记一个活动请求:
   旧服假死时不能让新服排队,快速连切也不能把每一代的 12 MiB 请求都留在网络层。 */
let bodiesFlight = null, bodiesRerun = false;

function cancelBodiesFlight(){
  if (bodiesFlight) bodiesFlight.controller.abort();
  bodiesFlight = null;
  bodiesRerun = false;
}
/* 在途请求与作业轮询属于本层,切服由自己收 */
onServerReset(() => {
  cancelBodiesFlight();
  // 作业状态按服务器隔离:JobService 的 seq 在每个服务端都从 1 开始,不清就会张冠李戴
  stopBusyPolling();
});

/* 每个加载器都要做同样三件事:丢弃过期响应、失败时记下原因、然后重绘。
   这三件事从前在七个加载器里各写各的 —— bodiesRequest / CHART.request / RECYCLE_REQ
   三种序号写法,loadServers、pollJobs、loadJobs 干脆没有。每轮审计都能在缺的那几个里
   再找出一条:pollJobs 没有序号,旧的空响应会把刚起的作业抹掉、顺手停掉轮询、还多刷一次
   bodies;loadServers 失败把成员表清空,20 秒轮询抖一下切服器就整个消失。
   收敛到一处,新加载器不会再漏。apply / onFail 只在"这一次仍然是最新的一次"时才跑,
   所以里面可以放心改全局状态。 */
const LOAD_SEQ = {};
async function load(key, request, apply, onFail){
  const seq = LOAD_SEQ[key] = (LOAD_SEQ[key] || 0) + 1;
  // 会话身份必须用 authSeq(captureCtx 已含),不能只看 authenticated:后者是布尔,
  // 注销再登录又变回 true,旧会话的在途响应就重新满足条件了。
  // 每键序号(LOAD_SEQ)与上下文代次是两回事:前者管同键请求的先后,后者管切服/换凭据
  const ctx = captureCtx();
  const fresh = () => seq === LOAD_SEQ[key] && ctx.fresh();
  try {
    const result = await request();
    if (fresh()) apply(result);
  } catch (e) {
    if (fresh() && onFail) onFail(e.message || String(e));
  }
}
/* 成员表低频变化,失败保留旧表并标记过期:抖一下就把切服器藏起来,用户会以为集群掉了。
   真断开由 showLogin 和网关状态负责。 */
function loadServers(){
  return load('servers', () => api('/api/servers'), r => {
    // 正在看的服从成员表里消失了(停服/被接管)。applyServersResponse 只会把 CURSRV 清空,
    // 那不够:代次不推进,它的在途响应会当成本机的落地;localStorage 不改,刷新页面又回到
    // 那个死服;DATA/选中还是它的,而后续请求已经打向本机了。这里要走完整的切服。
    // 检测放在这儿而不是 applyServersResponse 里:登录路径也调那个函数,但那时没有任何
    // 脏状态,紧接着的 loadAll 会正常加载本机 —— 在那里切一次只会白跑一遍还弹错提示
    const gone = CURSRV && !(r.servers || []).some(s => s.id === CURSRV);
    const lost = CURSRV;
    // 关弹层必须早于任何人改 CURSRV:applyServersResponse 紧接着就会把它清空,而弹层
    // 里攒着的是那个已经消失的服的 uuid,晚一步取消就等于把它们交给本机去执行
    if (gone) closeServerModals();
    applyServersResponse(r);
    maybeWarnDefaultToken(r);
    if (gone) { toast(T.srvGone(lost), 'bad'); switchServer(r.self); }
  }, message => {
    SERVERS_ERROR = message;
    const self = SERVERS.find(server => server.self);
    renderServerPicker(self ? self.id : CURSRV);
  });
}
/* ===================== 数据 ===================== */
async function loadAll(manual){
  await loadBodies();
  pollJobs();
  loadStats();
  if (manual) loadRecycle();
  if (manual && VIEW === 'jobs') loadJobs();   // 切服后日志页不能留着上一个服的记录等手动刷新
}
async function loadBodies() {
  // 同一时刻只允许一个在途请求(/api/bodies 慢过 2 秒时忙碌轮询会一轮压一轮),
  // 但期间来的请求要合并成"完事后再跑一次" —— 直接丢掉的话,切服时新服的那次加载会被
  // 旧服还没回来的请求吞掉,列表一直空到 60 秒兜底刷新。
  // 合并只在同一个服务器 + 会话代次里成立:换了上下文就取消旧的那次
  const ctx = captureCtx();
  if (bodiesFlight && bodiesFlight.ctx.srv === ctx.srv && bodiesFlight.ctx.auth === ctx.auth) {
    bodiesRerun = true;
    return;
  }
  cancelBodiesFlight();
  const run = {ctx, controller: new AbortController()};
  bodiesFlight = run;
  const keepUuid = SEL && SEL.uuid;
  try {
    await load('bodies', () => api('/api/bodies', {signal:run.controller.signal}), result => {
      // 先确认这是一份快照再发布 DATA。渲染层(summarize/renderTabs/render)一律假设
      // "DATA 非空 = groups 和 block_palette 都在";从前是先赋值后使用,网关或版本不匹配
      // 返回一个 200 的别的东西时,DATA 会留下半份,连"加载失败"那块提示自己都会再崩一次
      if (!Array.isArray(result.groups) || !Array.isArray(result.block_palette)) {
        throw new Error('响应不是一份完整的快照');
      }
      DATA = result;
      BODIES_ERROR = '';
      BODY_BY_UUID = new Map();
      CLONE_SETS = new Map((DATA.clone_sets || []).map(set=>[Number(set.id), set]));
      PAUSED = new Set(DATA.paused || []);
      FORCED = new Set(DATA.forced || []);
      FROZEN = new Set(DATA.frozen || []);
      REACH = DATA.reach || REACH;
      DATA.groups.forEach(g => g.bodies.forEach(b => BODY_BY_UUID.set(b.uuid, {b, g})));
      SELECTED = new Set([...SELECTED].filter(u => BODY_BY_UUID.has(u)));
      renderBodiesMeta();
      renderAll();
      refreshTimer = 60;
      if (keepUuid) reselect(keepUuid);
    }, message => {
      // 有旧数据就留着并标记"这是上次的结果";没有的话要明说加载失败,不能停在"加载中…"
      BODIES_ERROR = message;
      toast(T.loadFail + message, 'bad');
      renderAll();   // 总览也要改口径:从前只调 render(),不在 bodies 页就什么都不画
    });
  } finally {
    // 只有自己还是当前那一次才收尾。旧请求的 abort/迟到不能清掉新请求的记号,
    // 也不能替新上下文补跑
    if (bodiesFlight === run) {
      bodiesFlight = null;
      // 补跑也要过 fresh():请求重叠期间用户注销的话,这一跑会带着空 token 发出去,
      // 白吃一个 401 再把人往登录流程里推一次
      const rerun = bodiesRerun;
      bodiesRerun = false;
      if (rerun && ctx.fresh()) loadBodies();
    }
  }
}
/* 作业状态。打 /api/jobs 而不是 /api/bodies —— running[] 里就有忙碌徽章需要的
   seq/op/state/phase/name/targets,而 bodies 快照最大 12 MiB,作业期间每 2 秒
   重建、序列化、下发一整份纯属白烧 CPU 和带宽。作业从有到无时才刷新一次列表。 */
function pollJobs(){
  return load('jobs', () => api('/api/jobs?poll=1'), result => {
    const had = ACTIVE_JOBS.length;
    applyJobs(result.running || []);
    const reaped = reapFinishedJobs(result.log || []);
    // 作业刚跑完:这时候的列表才是服务端真值(乐观更新过的字段要纠正回来)。
    // 消费掉终态也算:快作业可能在第一轮轮询之前就结束了,那时 had 还是 0 ——
    // 完成 toast 照弹,列表却停在旧值,只能等 SSE 或 60 秒兜底
    if (reaped || (had && !ACTIVE_JOBS.length)) loadBodies();
    syncBusyPolling();
  }, () => {
    // 失败什么都不改,但一定要续期:定时器回调进来时已经把 busyTimer 清空了,
    // 这里不续就再没有人续 —— 抖一次网络,进度显示会一直冻到 60 秒兜底刷新才动
    syncBusyPolling();
  });
}
/* 每个作业一条,按 targets 展开成"体 → 作业"给行徽章用;
   没有目标体的作业(回收站恢复/重扫磁盘)只进 ACTIVE_JOBS,靠顶栏指示器显示 */
function applyJobs(list){
  const previousTargets = [...BUSY.keys()].sort().join('\n');
  // /api/jobs 给的是 started_at / queued_at 两个字段,顶栏指示器算已耗时用的是 since。
  // 不归一化的话 Date.now() - undefined 就是 NaN,界面上显示 "NaNs"
  ACTIVE_JOBS = list.map(job => job.since === undefined
    ? {...job, since: job.started_at || job.queued_at || 0} : job);
  BUSY = new Map();
  for (const job of ACTIVE_JOBS) for (const u of (job.targets || [])) BUSY.set(u, job);
  renderJobPill();
  const currentTargets = [...BUSY.keys()].sort().join('\n');
  if (previousTargets !== currentTargets) renderAll();
  else if (typeof refreshBusyLabels === 'function') refreshBusyLabels();
}
/* 有作业在跑时把作业状态轮询加速到 2 秒,跑完自动停。
   取代从前散落在各操作里的 setTimeout(loadBodies, 1200/1500/4000) —— 那些是对
   "多久能好"的猜测,猜短了看不到结果,猜长了白等,巨型体两头都不对。
   用 setTimeout 自续期而不是 setInterval:上一轮真的回来了才排下一轮,请求不会重叠。 */
function syncBusyPolling(){
  // 只管定时器。作业跑完时不能顺手清 JOB_WATCH —— 紧接着的 reapFinishedJobs 正是靠它
  // 去取终态弹 toast,清了就永远弹不出"完成/失败"。
  // JOB_WATCH 也要算进续期条件:作业刚提交、首轮查询就失败时 ACTIVE_JOBS 还是空的,
  // 只看它就等于放弃这个作业 —— 界面连"已经开始了"都不知道
  if (!ACTIVE_JOBS.length && !JOB_WATCH.size) { clearBusyTimer(); return; }
  if (busyTimer) return;
  const ctx = captureCtx();
  busyTimer = setTimeout(() => {
    busyTimer = null;
    if (ctx.fresh()) pollJobs();
  }, 2000);
}
function clearBusyTimer(){
  if (busyTimer) { clearTimeout(busyTimer); busyTimer = null; }
}
/* 注销、断网关、切服要连作业状态一起丢:定时器活着就会继续打请求,401 还会反复触发登录流程;
   旧服的 JOB_WATCH 留着还会跟新服相同 seq 的作业错配 */
function stopBusyPolling(){
  clearBusyTimer();
  ACTIVE_JOBS = []; BUSY = new Map(); JOB_WATCH.clear(); JOB_RESULTS.clear();
  for (const waiter of JOB_WAITERS.values()) {
    clearTimeout(waiter.timer);
    waiter.resolve('fail');
  }
  JOB_WAITERS.clear();
  renderJobPill();
}
/* 顶栏"处理中"指示:唯一能显示无目标体作业(回收站恢复/重扫磁盘)进度的地方 */
function renderJobPill(){
  const host = document.getElementById('jobPill');
  if (!host) return;
  if (!ACTIVE_JOBS.length) { host.style.display = 'none'; return; }
  const job = ACTIVE_JOBS[0];
  const secs = Math.max(0, Math.round((Date.now() - job.since) / 1000));
  const label = job.state === 'queued' ? T.jobQueued : (job.phase || '');
  host.style.display = 'flex';
  host.innerHTML = `<i class="spin"></i><b>${esc(job.op)}</b>${job.name ? ' · ' + esc(job.name) : ''}`
    + `<span class="muted">${esc(label)} ${secs}s</span>`
    + (ACTIVE_JOBS.length > 1 ? `<span class="tag">+${ACTIVE_JOBS.length - 1}</span>` : '');
}
/* 作业结束回报:本页提交过的作业一旦从活动列表消失,从同一次 /api/jobs 的日志里取终态弹一次
   toast。日志由调用方传进来 —— 它和 running[] 本来就是同一个响应,再单独请求一次是白跑一趟,
   还多一个"切服后旧服结果弹在新服界面上"的时间窗 */
/* 返回值 = 这一轮有没有 watched 作业到达终态,调用方据此决定要不要刷新列表 */
function reapFinishedJobs(log){
  if (!JOB_WATCH.size) return false;
  const running = new Set(ACTIVE_JOBS.map(job => job.seq));
  const finished = [...JOB_WATCH.keys()].filter(seq => !running.has(seq));
  if (!finished.length) return false;
  let refreshRecycle = false;
  for (const seq of finished) {
    JOB_WATCH.delete(seq);
    const job = log.find(entry => entry.seq === seq);
    if (!job) {
      const missing = JOB_WAITERS.get(seq);
      if (missing) {
        JOB_WAITERS.delete(seq);
        clearTimeout(missing.timer);
        missing.resolve('fail');
      }
      continue;
    }
    // 终态一律走 outcome:从前 0/3(全部失败)在这里弹的是绿色"完成"
    const outcome = jobOutcome(job);
    const waiter = JOB_WAITERS.get(seq);
    if (waiter) {
      JOB_WAITERS.delete(seq);
      clearTimeout(waiter.timer);
      waiter.resolve(waiter.ctx.fresh() ? outcome : 'fail');
    } else {
      JOB_RESULTS.set(seq, outcome);
      if (JOB_RESULTS.size > 64) JOB_RESULTS.delete(JOB_RESULTS.keys().next().value);
    }
    const label = outcome === 'fail' ? T.jobFailed : outcome === 'partial' ? T.jobPartial : T.jobDone;
    const parts = [job.op, job.name, label, job.message];
    if (job.op === '回收站彻底删除' && (job.warnings || []).length) parts.push(job.warnings[0]);
    toast(parts.filter(Boolean).join(' · '), outcome === 'ok' ? 'ok' : 'bad');
    if ((job.op==='删除'||job.op==='批量删除')&&outcome==='fail'
      &&String(job.message||'').includes('处理副本')&&SEL&&SEL.copies>1) openDedupe();
    if (job.op === '回收站恢复' || job.op === '回收站彻底删除') refreshRecycle = true;
  }
  if (refreshRecycle) loadRecycle();
  dedupeJobsFinished(finished);   // 副本对话框的唤醒作业结束 → 自动重扫(dedupe.js)
  return true;
}
function reselect(uuid){
  for (const g of DATA.groups) for (const b of g.bodies) if (b.uuid === uuid) {
    SEL = b; SELG = g; renderDetail();
    document.querySelector(`.member[data-uuid="${uuid}"]`)?.classList.add('sel');
    return;
  }
  SEL = null; SELG = null;
}
/* 统计失败不影响主体操作,保留上一次的数值但记录错误,由 renderStats() 常驻标记过期状态。
   不能只弹 toast:轮询失败时 toast 会消失,用户会把旧服/旧时间段的数字当成实时值。 */
function loadStats(){
  return load('stats', () => api('/api/stats'), result => {
    STATS = result;
    STATS_ERROR = '';
    renderStats();   // 写完状态就交给它,顶栏那几块归它管
    if (VIEW === 'dash') renderDash();
  }, message => {
    STATS_ERROR = message;
    renderStats();
  });
}
/* 在线玩家列表:15s 节流,失败保留旧列表并显式标记,不能把网络错误伪装成"没有在线玩家" */
function loadPlayers(force){
  if (!force && Date.now() - playersFetchedAt < 15000) { renderPlayerSelect(); return; }
  playersFetchedAt = Date.now();
  return load('players', () => api('/api/players'),
    r => { PLAYERS = r.players || []; PLAYERS_ERROR = ''; renderPlayerSelect(); },
    message => { PLAYERS_ERROR = message; renderPlayerSelect(); });
}
/* 回收站游标分页。append=false 是重新从第一页拉(切服/删除/恢复之后),true 是"加载更多"。
   服务端一页只读这一页的 manifest,不会像从前那样先把全部备份建成一个对象再发。 */
function loadRecycle(append){
  RECYCLE_LOADING = true;
  const cursor = append ? RECYCLE_CURSOR : '';
  const query = `?version=${R_TAB}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
  // 整表重拉可能和翻页撞上,晚到的那次不能落地 —— 否则会把一页追加到另一份列表上。
  // 过期的那次不用管 RECYCLE_LOADING:更新的那次自己会清
  return load('recycle', () => api('/api/recycle' + query), page => {
    // 必须在渲染之前清:下面的 renderRecycle 会照着 RECYCLE_LOADING 画按钮,
    // 只改变量不重绘的话,"加载更多"会永久停在 disabled 的"加载中…"
    RECYCLE_LOADING = false;
    RECYCLE_ERROR = '';
    const groups = page.groups || [];
    if (append && RECYCLE) {
      RECYCLE.groups = RECYCLE.groups.concat(groups);
      mergePalette(RECYCLE, page.block_palette || [], groups);
    } else {
      RECYCLE = page;
      RECYCLE.groups = groups;
      RECYCLE.block_palette = page.block_palette || [];
      // 只有整表重拉才清理选中:翻页时未加载页里的选中必须保留
      R_SELECTED = new Set([...R_SELECTED].filter(id => groups.some(g => g.id === id)));
    }
    RECYCLE_CURSOR = page.next_cursor || '';
    RECYCLE_TOTAL = page.total_groups ?? RECYCLE.groups.length;
    RECYCLE_BY_ID = new Map(RECYCLE.groups.map(g=>[g.id,g]));
    document.getElementById('rLimit').value = RECYCLE.limit || 500;
    const usage = document.getElementById('rUsage');
    usage.textContent = T.recycleUsage(RECYCLE.file_count || 0, RECYCLE.pending_files || 0, RECYCLE.limit || 500)
      + ' · ' + T.recycleDisk(fmtBytes(RECYCLE.disk_bytes || 0));
    // 顶到上限时标红:批量删除会在这里失败,用量数字不能和其它说明文字长一个样
    const used = (RECYCLE.file_count || 0) + (RECYCLE.pending_files || 0);
    usage.style.color = used >= (RECYCLE.limit || 500) ? 'var(--bad)' : '';
    renderRecycleDims();
    if (RSEL) {
      const group = RECYCLE_BY_ID.get(RSELG && RSELG.id);
      const body = group && (group.bodies||[]).find(b=>b.uuid===RSEL.uuid);
      if (body) { RSEL=body; RSELG=group; if (VIEW==='recycle') renderRecycleDetail(); }
      else if (!append) clearRecycleDetail();
    }
    if (VIEW==='dash') renderDash();
    if (VIEW==='recycle') renderRecycle();
  }, message => {
    RECYCLE_LOADING = false;   // 同上:失败时也要在重绘之前清,否则按钮卡在禁用态
    // 失败就是失败:既不写一份空数据(那会显示成"回收站为空"),也不动游标 ——
    // "加载更多"要能原地再点一次
    RECYCLE_ERROR = message;
    if (append) toast(T.loadFail + message, 'bad');
    renderAll();   // 总览的回收卡片也要改口径,不能只管当前是不是回收站页
  });
}
function loadMoreRecycle(){ if (RECYCLE_CURSOR && !RECYCLE_LOADING) loadRecycle(true); }

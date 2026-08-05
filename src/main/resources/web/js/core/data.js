'use strict';
/* 数据编排层:从后端拉取 bodies/stats/recycle/servers 并更新全局状态、触发渲染 */
let bodiesRequest = 0;
let bodiesInFlight = false, bodiesRerun = false;
async function loadServers(){
  try {
    const r = await api('/api/servers');
    applyServersResponse(r);
    maybeWarnDefaultToken(r);
  } catch (e) { SERVERS = []; document.getElementById('srvWrap').style.display = 'none'; }
}
/* ===================== 数据 ===================== */
async function loadAll(manual){
  await loadBodies();
  loadStats();
  if (manual) loadRecycle();
  if (manual && VIEW === 'jobs') loadJobs();   // 切服后日志页不能留着上一个服的记录等手动刷新
}
async function loadBodies() {
  // 同一时刻只允许一个在途请求(/api/bodies 慢过 2 秒时忙碌轮询会一轮压一轮),
  // 但期间来的请求要合并成"完事后再跑一次" —— 直接丢掉的话,切服时新服的那次加载会被
  // 旧服还没回来的请求吞掉,列表一直空到 60 秒兜底刷新
  if (bodiesInFlight) { bodiesRerun = true; return; }
  bodiesInFlight = true;
  const request = ++bodiesRequest;
  const gen = srvGen();
  try {
    const keepUuid = SEL && SEL.uuid;
    const result = await api('/api/bodies');
    if (request !== bodiesRequest || gen !== srvGen()) return;
    DATA = result;
    BODY_BY_UUID = new Map();
    CLONE_SETS = new Map((DATA.clone_sets || []).map(set=>[Number(set.id), set]));
    PAUSED = new Set(DATA.paused || []);
    FORCED = new Set(DATA.forced || []);
    // 每个作业一条,按 targets 展开成"体 → 作业"给行徽章用;
    // 没有目标体的作业(回收站恢复/重扫磁盘)只进 ACTIVE_JOBS,靠顶栏指示器显示
    ACTIVE_JOBS = DATA.busy || [];
    BUSY = new Map();
    for (const job of ACTIVE_JOBS) for (const u of (job.targets || [])) BUSY.set(u, job);
    REACH = DATA.reach || REACH;
    renderJobPill();
    syncBusyPolling();
    reapFinishedJobs();
    DATA.groups.forEach(g => g.bodies.forEach(b => BODY_BY_UUID.set(b.uuid, {b, g})));
    SELECTED = new Set([...SELECTED].filter(u => BODY_BY_UUID.has(u)));
    const dims = new Set();
    DATA.groups.forEach(g => g.bodies.forEach(b => dims.add(b.dim)));
    const prevChecked = new Set([...document.querySelectorAll('.fDim:checked')].map(x=>x.value));
    const hadAny = document.querySelectorAll('.fDim').length > 0;
    document.getElementById('fDims').innerHTML = [...dims].map(d =>
      `<label><input type="checkbox" class="fDim" value="${esc(d)}" ${(!hadAny || prevChecked.has(d))?'checked':''} onchange="render()"> ${esc(d.replace('minecraft:',''))}</label>`).join('');
    document.getElementById('scanMeta').innerHTML =
      `${fmt(DATA.total_bodies)} ${t('bodies')} · ${fmt(DATA.total_entries)} ${t('entries')}<br>${t('scanAt')} ${new Date(DATA.scan_time).toLocaleTimeString()}`;
    refreshBlockList();
    renderAll();
    refreshTimer = 60;
    if (keepUuid) reselect(keepUuid);
  } catch (e) {
    if (request === bodiesRequest && gen === srvGen()) toast(t('loadFail') + e.message, 'bad');
  } finally {
    bodiesInFlight = false;
    // 补跑要看认证状态:请求重叠期间用户注销的话,这一跑会带着空 token 发出去,
    // 白吃一个 401 再把人往登录流程里推一次
    if (bodiesRerun) { bodiesRerun = false; if (authenticated) loadBodies(); }
  }
}
/* 有作业在跑时把列表刷新加速到 2 秒,跑完自动停。
   取代从前散落在各操作里的 setTimeout(loadBodies, 1200/1500/4000) —— 那些是对
   "多久能好"的猜测,猜短了看不到结果,猜长了白等,巨型体两头都不对。
   用 setTimeout 自续期而不是 setInterval:上一轮真的回来了才排下一轮,请求不会重叠。 */
function syncBusyPolling(){
  // 只管定时器。作业跑完时不能顺手清 JOB_WATCH —— 紧接着的 reapFinishedJobs 正是靠它
  // 去取终态弹 toast,清了就永远弹不出"完成/失败"
  if (!ACTIVE_JOBS.length) { clearBusyTimer(); return; }
  if (busyTimer) return;
  const gen = srvGen();
  busyTimer = setTimeout(() => {
    busyTimer = null;
    if (authenticated && gen === srvGen()) loadBodies();
  }, 2000);
}
function clearBusyTimer(){
  if (busyTimer) { clearTimeout(busyTimer); busyTimer = null; }
}
/* 注销、断网关、切服要连作业状态一起丢:定时器活着就会继续打请求,401 还会反复触发登录流程;
   旧服的 JOB_WATCH 留着还会跟新服相同 seq 的作业错配 */
function stopBusyPolling(){
  clearBusyTimer();
  ACTIVE_JOBS = []; BUSY = new Map(); JOB_WATCH.clear();
  renderJobPill();
}
/* 顶栏"处理中"指示:唯一能显示无目标体作业(回收站恢复/重扫磁盘)进度的地方 */
function renderJobPill(){
  const host = document.getElementById('jobPill');
  if (!host) return;
  if (!ACTIVE_JOBS.length) { host.style.display = 'none'; return; }
  const job = ACTIVE_JOBS[0];
  const secs = Math.max(0, Math.round((Date.now() - job.since) / 1000));
  const label = job.state === 'queued' ? t('jobQueued') : (job.phase || '');
  host.style.display = 'flex';
  host.innerHTML = `<i class="spin"></i><b>${esc(job.op)}</b>${job.name ? ' · ' + esc(job.name) : ''}`
    + `<span class="muted">${esc(label)} ${secs}s</span>`
    + (ACTIVE_JOBS.length > 1 ? `<span class="tag">+${ACTIVE_JOBS.length - 1}</span>` : '');
}
/* 作业结束回报:本页提交过的作业一旦从活动列表消失,去日志取终态弹一次 toast */
async function reapFinishedJobs(){
  if (!JOB_WATCH.size) return;
  const running = new Set(ACTIVE_JOBS.map(job => job.seq));
  const finished = [...JOB_WATCH.keys()].filter(seq => !running.has(seq));
  if (!finished.length) return;
  const gen = srvGen();
  let log;
  try { log = (await api('/api/jobs')).log || []; } catch(e){ return; }
  if (gen !== srvGen()) return;   // 切服后旧服的作业结果不该弹在新服的界面上
  let refreshRecycle = false;
  for (const seq of finished) {
    JOB_WATCH.delete(seq);
    const job = log.find(entry => entry.seq === seq);
    if (!job) continue;
    // 终态一律走 outcome:从前 0/3(全部失败)在这里弹的是绿色"完成"
    const outcome = jobOutcome(job);
    const label = outcome === 'fail' ? t('jobFailed') : outcome === 'partial' ? t('jobPartial') : t('jobDone');
    const parts = [job.op, job.name, label, job.message];
    if (job.op === '回收站彻底删除' && (job.warnings || []).length) parts.push(job.warnings[0]);
    toast(parts.filter(Boolean).join(' · '), outcome === 'ok' ? 'ok' : 'bad');
    if (job.op === '回收站恢复' || job.op === '回收站彻底删除') refreshRecycle = true;
  }
  if (refreshRecycle) loadRecycle();
}
function reselect(uuid){
  for (const g of DATA.groups) for (const b of g.bodies) if (b.uuid === uuid) {
    SEL = b; SELG = g; renderDetail();
    document.querySelector(`.member[data-uuid="${uuid}"]`)?.classList.add('sel');
    return;
  }
  SEL = null; SELG = null;
}
async function loadStats(){
  const request = (CHART.request || 0) + 1;
  CHART.request = request;
  const gen = srvGen();
  const now = Math.floor(Date.now()/1000);
  if (CHART.live) { CHART.to = now; CHART.from = now - CHART.span; }
  try {
    const result = await api(`/api/stats?from=${CHART.from}&to=${CHART.to}&max_points=2000`);
    if (request !== CHART.request || gen !== srvGen()) return;
    STATS = result;
    CHART.from = Number(STATS.range_from ?? CHART.from);
    CHART.to = Number(STATS.range_to ?? CHART.to);
    const loaded = Object.values(STATS.loaded||{}).reduce((a,b)=>a+b,0);
    document.getElementById('pillCost').textContent = (STATS.body_cost_total ?? 0).toFixed(2);
    document.getElementById('pillLoaded').textContent = loaded;
    updateChartControls();
    drawPhysChart(document.getElementById('pillSpark'), false);
    renderStatPop();
    if (VIEW === 'dash') renderDash();
  } catch(e){ /* 统计失败不影响主体操作 */ }
}
/* 在线玩家列表:15s 节流,失败静默(下拉显示"没有在线玩家") */
async function loadPlayers(force){
  if (!force && Date.now() - playersFetchedAt < 15000) { renderPlayerSelect(); return; }
  playersFetchedAt = Date.now();
  const gen = srvGen();
  try {
    const r = await api('/api/players');
    if (gen !== srvGen()) return;
    PLAYERS = r.players || [];
  } catch(e){ if (gen !== srvGen()) return; PLAYERS = []; }
  renderPlayerSelect();
}
/* 回收站游标分页。append=false 是重新从第一页拉(切服/删除/恢复之后),true 是"加载更多"。
   服务端一页只读这一页的 manifest,不会像从前那样先把全部备份建成一个对象再发。 */
async function loadRecycle(append){
  const req = ++RECYCLE_REQ;
  RECYCLE_LOADING = true;
  const gen = srvGen();
  const cursor = append ? RECYCLE_CURSOR : '';
  try {
    const query = `?version=${R_TAB}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
    const page = await api('/api/recycle' + query);
    // 整表重拉可能和翻页撞上,晚到的那次不能落地 —— 否则会把一页追加到另一份列表上
    if (gen !== srvGen() || req !== RECYCLE_REQ) return;
    // 必须在渲染之前清:下面的 renderRecycle 会照着 RECYCLE_LOADING 画按钮,
    // 留到 finally 里清就只改变量不重绘,"加载更多"会永久停在 disabled 的"加载中…"
    RECYCLE_LOADING = false;
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
    document.getElementById('rUsage').textContent = t('recycleUsage')(RECYCLE.file_count || 0, RECYCLE.limit || 500)
      + ' · ' + t('recycleDisk')(fmtBytes(RECYCLE.disk_bytes || 0));
    renderRecycleDims();
    if (RSEL) {
      const group = RECYCLE_BY_ID.get(RSELG && RSELG.id);
      const body = group && (group.bodies||[]).find(b=>b.uuid===RSEL.uuid);
      if (body) { RSEL=body; RSELG=group; if (VIEW==='recycle') renderRecycleDetail(); }
      else if (!append) clearRecycleDetail();
    }
    if (VIEW==='dash') renderDash();
    if (VIEW==='recycle') renderRecycle();
  } catch(e){
    if (gen !== srvGen() || req !== RECYCLE_REQ) return;
    RECYCLE_LOADING = false;   // 同上:失败时也要在重绘之前清,否则按钮卡在禁用态
    if (!append) {
      RECYCLE = {groups:[],block_palette:[],file_count:0,disk_bytes:0,limit:500,latest_groups:0,old_groups:0};
      RECYCLE_CURSOR=''; RECYCLE_TOTAL=0;
    }
    else toast(t('loadFail') + e.message, 'bad');
    if (VIEW==='recycle') renderRecycle();
  } finally {
    if (req === RECYCLE_REQ) RECYCLE_LOADING = false;
  }
}
function loadMoreRecycle(){ if (RECYCLE_CURSOR && !RECYCLE_LOADING) loadRecycle(true); }
/* 每页的调色板是这一页自己的,索引也只对这一页有效 —— 追加时把新页的 blk 重映射到合并后的表 */
function mergePalette(store, palette, appended){
  const index = new Map(store.block_palette.map((item,i)=>[item.id,i]));
  const remap = palette.map(item => {
    if (!index.has(item.id)) { index.set(item.id, store.block_palette.length); store.block_palette.push(item); }
    return index.get(item.id);
  });
  appended.forEach(group => (group.bodies||[]).forEach(body => {
    body.blk = (body.blk || []).map(i => remap[i] ?? 0);
  }));
}
/* 维度筛选按已加载的组重建,保留用户已经取消勾选的项 */
function renderRecycleDims(){
  const host = document.getElementById('rDims');
  host.querySelectorAll('.rFDim').forEach(input => input.checked
    ? R_DIM_DISABLED.delete(input.value) : R_DIM_DISABLED.add(input.value));
  const dims = new Set();
  // 摘要组的 bodies 是空的,不能直接 forEach
  RECYCLE.groups.forEach(g=>(g.bodies||[]).forEach(b=>dims.add(b.dim || 'minecraft:overworld')));
  host.innerHTML = [...dims].map(d=>
    `<label><input type="checkbox" class="rFDim" value="${esc(d)}" ${R_DIM_DISABLED.has(d)?'':'checked'} onchange="renderRecycle()"> ${esc(d.replace('minecraft:',''))}</label>`).join('');
}

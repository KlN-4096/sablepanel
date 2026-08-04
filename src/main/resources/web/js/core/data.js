'use strict';
/* 数据编排层:从后端拉取 bodies/stats/recycle/servers 并更新全局状态、触发渲染 */
let bodiesRequest = 0;
async function loadServers(){
  try {
    const r = await api('/api/servers');
    applyServersResponse(r);
    maybeWarnDefaultToken(r);
  } catch (e) { SERVERS = []; document.getElementById('srvWrap').style.display = 'none'; }
}
/* ===================== 数据 ===================== */
async function loadAll(manual){ await loadBodies(); loadStats(); if (manual) loadRecycle(); }
async function loadBodies() {
  const request = ++bodiesRequest;
  try {
    const keepUuid = SEL && SEL.uuid;
    const result = await api('/api/bodies');
    if (request !== bodiesRequest) return;
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
  } catch (e) { if (request === bodiesRequest) toast(t('loadFail') + e.message, 'bad'); }
}
/* 有作业在跑时把列表刷新加速到 2 秒,跑完自动停。
   取代从前散落在各操作里的 setTimeout(loadBodies, 1200/1500/4000) —— 那些是对
   "多久能好"的猜测,猜短了看不到结果,猜长了白等,巨型体两头都不对 */
function syncBusyPolling(){
  const active = ACTIVE_JOBS.length > 0;
  if (active && !busyTimer) busyTimer = setInterval(loadBodies, 2000);
  else if (!active && busyTimer) { clearInterval(busyTimer); busyTimer = null; }
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
  let log;
  try { log = (await api('/api/jobs')).log || []; } catch(e){ return; }
  for (const seq of finished) {
    JOB_WATCH.delete(seq);
    const job = log.find(entry => entry.seq === seq);
    if (!job) continue;
    const failed = job.state === 'failed' || /failed=[1-9]/.test(job.message || '');
    const parts = [job.op, job.name, failed ? t('jobFailed') : t('jobDone'), job.message];
    toast(parts.filter(Boolean).join(' · '), failed ? 'bad' : 'ok');
  }
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
  const now = Math.floor(Date.now()/1000);
  if (CHART.live) { CHART.to = now; CHART.from = now - CHART.span; }
  try {
    const result = await api(`/api/stats?from=${CHART.from}&to=${CHART.to}&max_points=2000`);
    if (request !== CHART.request) return;
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
  try {
    const r = await api('/api/players');
    PLAYERS = r.players || [];
  } catch(e){ PLAYERS = []; }
  renderPlayerSelect();
}
async function loadRecycle(){
  try {
    RECYCLE = await api('/api/recycle');
    RECYCLE.groups = RECYCLE.groups || [];
    RECYCLE.block_palette = RECYCLE.block_palette || [];
    RECYCLE_BY_ID = new Map(RECYCLE.groups.map(g=>[g.id,g]));
    R_SELECTED = new Set([...R_SELECTED].filter(id=>RECYCLE_BY_ID.has(id)));
    document.getElementById('rLimit').value = RECYCLE.limit || 500;
    document.getElementById('rUsage').textContent = t('recycleUsage')(RECYCLE.file_count || 0, RECYCLE.limit || 500);
    const dims = new Set();
    RECYCLE.groups.forEach(g=>g.bodies.forEach(b=>dims.add(b.dim || 'minecraft:overworld')));
    document.getElementById('rDims').innerHTML = [...dims].map(d=>
      `<label><input type="checkbox" class="rFDim" value="${esc(d)}" checked onchange="renderRecycle()"> ${esc(d.replace('minecraft:',''))}</label>`).join('');
    if (RSEL) {
      const group = RECYCLE_BY_ID.get(RSELG && RSELG.id);
      const body = group && group.bodies.find(b=>b.uuid===RSEL.uuid);
      if (body) { RSEL=body; RSELG=group; if (VIEW==='recycle') renderRecycleDetail(); }
      else clearRecycleDetail();
    }
    if (VIEW==='dash') renderDash();
    if (VIEW==='recycle') renderRecycle();
  } catch(e){
    RECYCLE = {groups:[],block_palette:[],file_count:0,limit:500};
    if (VIEW==='recycle') renderRecycle();
  }
}

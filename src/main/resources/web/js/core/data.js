'use strict';
/* 数据编排层:从后端拉取 bodies/stats/recycle/servers 并更新全局状态、触发渲染 */
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
  try {
    const keepUuid = SEL && SEL.uuid;
    DATA = await api('/api/bodies');
    BODY_BY_UUID = new Map();
    CLONE_SETS = new Map((DATA.clone_sets || []).map(set=>[Number(set.id), set]));
    PAUSED = new Set(DATA.paused || []);
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
  } catch (e) { toast(t('loadFail') + e.message, 'bad'); }
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

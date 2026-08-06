'use strict';
/* 总览视图:汇总卡片、状态/规模/维度分布、顶栏统计弹层 */
/* ===================== 总览 ===================== */
function summarize(){
  const s = {bodies:0, groups:DATA.groups.length, blocks:0, entries:DATA.total_entries,
    state:{loaded:0,stored:0,holding:0,orphan:0}, size:{huge:0,large:0,mid:0,small:0,frag:0},
    dims:{}, dup:0, clone:0, rec:{groups:0,bodies:0,blocks:0}};
  for (const g of DATA.groups) {
    s.blocks += g.blocks;
    for (const b of g.bodies) {
      s.bodies++;
      s.state[b.state] = (s.state[b.state]||0)+1;
      s.size[sizeClass(b.blocks)]++;
      s.dims[b.dim] = (s.dims[b.dim]||0)+1;
      if (b.copies) s.dup++;
      if (b.clone) s.clone++;
    }
    if (g.rec) { s.rec.groups++; s.rec.bodies += g.members; s.rec.blocks += g.blocks; }
  }
  return s;
}
function barRow(items, colors){
  const total = items.reduce((s,i)=>s+i[1],0) || 1;
  return `<div class="bar">${items.filter(i=>i[1]>0).map(i=>`<i style="width:${i[1]/total*100}%;background:${colors[i[0]]}"></i>`).join('')}</div>
    <div class="legend">${items.map(i=>`<span><i style="background:${colors[i[0]]}"></i>${i[2]} <b>${fmt(i[1])}</b></span>`).join('')}</div>`;
}
/* 总览顶部的"当前服务器"横幅:多服时直接列出所有成员,点一下就切 */
function renderDashServer(){
  const box = document.getElementById('dashSrv');
  if (!box) return;
  const self = SERVERS.find(x => x.self);
  const cur = CURSRV || (self ? self.id : '');
  if (!cur) { box.innerHTML = ''; return; }
  const multi = SERVERS.length > 1;
  box.innerHTML = `<div class="srvBanner">
    <div class="srvNow">
      <span class="srvDot"></span>
      <div>
        <div class="srvLabel">${t('dashServer')}</div>
        <div class="srvName">${esc(cur)}</div>
      </div>
    </div>
    <div class="srvList">${
      multi ? SERVERS.map((x,i)=>`<button class="srvPick ${x.id===cur?'on':''}" onclick="switchServer(SERVERS[${i}].id)">
                 ${esc(x.id)}${x.host?`<span class="badge">${t('srvHost')}</span>`:''}</button>`).join('')
            : `<span class="muted" style="font-size:11.5px">${t('dashServerOnly')}</span>`}</div>
    ${multi?`<div class="srvHint">${t('dashServerPeer')(SERVERS.length)}</div>`:''}
  </div>`;
}
const SIZE_COLORS = {huge:'#5aa9ff',large:'#4b8ed6',mid:'#3d72ab',small:'#345a80',frag:'#2a3d55'};
const STATE_COLORS = {loaded:'#46c96b',stored:'#3b475e',holding:'#e0a33a',orphan:'#f2635c'};

/* 回收站卡片三态:加载中 / 加载失败 / 有数据。
   从前 RECYCLE===null 一律画"加载中…",服务端已经 500 了也照样转圈 */
function renderDashClean(){
  document.getElementById('cleanCard').innerHTML = `
    <h4><span>${t('recycleT')}</span></h4>
    ${RECYCLE ? `<div class="bignum">${fmt((RECYCLE.latest_groups||0)+(RECYCLE.old_groups||0))}</div>
         <div class="hint">${t('recycleStructures')}</div>
         <div class="kvgrid">
           <div class="k">${t('rTabLatest')}</div><div class="v">${fmt(RECYCLE.latest_groups||0)}</div>
           <div class="k">${t('rTabOld')}</div><div class="v">${fmt(RECYCLE.old_groups||0)}</div>
         </div>
         <div class="hint">${t('recycleDisk')(fmtBytes(RECYCLE.disk_bytes||0))}</div>`
      : RECYCLE_ERROR ? `<div class="empty" style="color:var(--bad)">⚠ ${t('loadFail')}${esc(RECYCLE_ERROR)}</div>`
      : `<div class="empty">${t('loading')}</div>`}`;
}
function renderDash(){
  renderDashServer();
  // 已有数据时刷新失败:旧数字照常显示,但要说明它是上一次的结果
  const stale = (DATA && BODIES_ERROR) || (RECYCLE && RECYCLE_ERROR) || '';
  if (stale) document.getElementById('dashSrv').innerHTML +=
    `<div class="srvHint" style="color:var(--bad)">${t('staleData')}${esc(stale)}</div>`;
  // 首屏就失败:从前直接 return,总览是一片空白,用户只看到一闪而过的 toast
  if (!DATA) {
    document.getElementById('dashTop').innerHTML = BODIES_ERROR
      ? `<div class="card"><h4>${t('dashBodies')}</h4>
           <div class="sub" style="color:var(--bad)">⚠ ${t('loadFail')}${esc(BODIES_ERROR)}</div></div>`
      : `<div class="card"><div class="sub">${t('loading')}</div></div>`;
    document.getElementById('dashMid').innerHTML = '';
    renderDashClean();
    return;
  }
  const s = summarize();
  const loadedCost = STATS ? (STATS.body_cost_total ?? 0) : null;
  document.getElementById('dashTop').innerHTML = `
    <div class="card"><h4>${t('dashBodies')}</h4><div class="bignum">${fmt(s.bodies)}<small>${t('bodies')}</small></div>
      <div class="sub">${fmt(s.groups)} ${t('groupsUnit')} · ${fmt(s.entries)} ${t('entries')}</div></div>
    <div class="card"><h4>${t('dashBlocks')}</h4><div class="bignum">${fmt(s.blocks)}<small>${t('blocksUnit')}</small></div>
      <div class="sub">${t('szHuge')}: ${s.size.huge} · ${t('szLarge')}: ${s.size.large}</div></div>
    <div class="card"><h4>${t('dashLoaded')}</h4><div class="bignum" style="color:var(--ok)">${s.state.loaded}</div>
      <div class="sub">${loadedCost !== null ? t('physBodies')+': '+loadedCost.toFixed(2)+' ms/t' : ''}</div></div>
    <div class="card ${s.rec.groups?'accent-warn':''}"><h4><span>${t('dashClean')}</span>${s.rec.groups?`<span class="more" onclick="setView('bodies',{tab:'rec',reset:true})">${t('dashGo')}</span>`:''}</h4>
      ${s.rec.groups
        ? `<div class="bignum" style="color:var(--warn)">${fmt(s.rec.bodies)}<small>${t('bodies')} / ${s.rec.groups} ${t('groupsUnit')}</small></div>
           <div class="sub">${t('dashRecBlocks')}: ${fmt(s.rec.blocks)}</div>`
        : `<div class="bignum" style="color:var(--ok)">✓</div><div class="sub">${t('dashHealthy')}</div>`}</div>`;

  document.getElementById('dashMid').innerHTML = `
    <div class="card"><h4><span>${t('dashState')}</span><span class="more" onclick="setView('bodies',{tab:'all',reset:true})">${t('dashGo')}</span></h4>
      ${barRow([['loaded',s.state.loaded,t('stLoaded')],['stored',s.state.stored,t('stStored')],
        ['holding',s.state.holding,t('holdingX')],['orphan',s.state.orphan,t('orphanX')]], STATE_COLORS)}</div>
    <div class="card"><h4>${t('dashScale')}</h4>
      ${barRow([['huge',s.size.huge,t('szHuge').split(' ')[0]],['large',s.size.large,t('szLarge').split(' ')[0]],
        ['mid',s.size.mid,t('szMid').split(' ')[0]],['small',s.size.small,t('szSmall').split(' ')[0]],
        ['frag',s.size.frag,t('szFrag').split(' ')[0]]], SIZE_COLORS)}</div>
    <div class="card"><h4>${t('dashDims')}</h4><div class="kvgrid">
      ${Object.entries(s.dims).sort((a,b)=>b[1]-a[1]).map(([d,n])=>
        `<div class="k">${esc(d.replace('minecraft:',''))}</div><div class="v">${fmt(n)}</div>`).join('')}</div></div>
    <div class="card ${(s.state.orphan)?'accent-bad':''}"><h4><span>${t('dashAnom')}</span>${(s.state.orphan+s.dup+s.clone)?`<span class="more" onclick="setView('bodies',{tab:'anom',reset:true})">${t('dashGo')}</span>`:''}</h4>
      <div class="kvgrid">
        <div class="k"><i class="dot" style="background:var(--bad)"></i>${t('dashOrphans')}</div><div class="v" style="color:${s.state.orphan?'var(--bad)':'var(--dim)'}">${s.state.orphan}</div>
        <div class="k"><i class="dot" style="background:var(--warn)"></i>${t('dashDup')}</div><div class="v" style="color:${s.dup?'var(--warn)':'var(--dim)'}">${s.dup}</div>
        <div class="k"><i class="dot" style="background:var(--clone)"></i>${t('dashClone')}</div><div class="v" style="color:${s.clone?'var(--clone)':'var(--dim)'}">${s.clone}</div>
        <div class="k"><i class="dot" style="background:var(--warn)"></i>${t('dashHolding')}</div><div class="v">${s.state.holding}</div>
      </div></div>`;

  renderDashClean();
  document.getElementById('toolCard').innerHTML = `
    <h4>${t('tools')}</h4>
    <div style="display:flex;gap:9px;flex-wrap:wrap">
      <button onclick="doRescan()">${t('rescan')}</button>
      <button onclick="runConsistencyScan()">${t('consistencyScan')}</button>
      <button onclick="loadAll(true)">${t('refresh')}</button>
      <button onclick="doChangeToken()">${t('tokenChange')}</button>
      <button onclick="openManual()">${t('manualOpen')}</button>
    </div>
    <div class="hint">${t('scanInfo')}</div>
    <div class="hint">${t('tokenHint')}</div>`;

  drawPhysChart(document.getElementById('physChart'), true);
  const dims = STATS ? Object.keys(STATS.phys||{}) : [];
  document.getElementById('physLegend').innerHTML = STATS
    ? dims.map((d,i)=>`<span><i style="background:${DIM_COLORS[i%DIM_COLORS.length]}"></i>${esc(d.replace('minecraft:',''))}
        <b>${(STATS.phys_1m?.[d]??0).toFixed(2)} ms</b></span>`).join('')
      + `<span><i style="background:${BODY_COLOR}"></i>${t('physBodies')} <b>${(STATS.body_cost_total??0).toFixed(2)} ms</b></span>`
    : '';
  document.getElementById('dashTopCost').innerHTML = topCostTable(8);
}
function topCostTable(n){
  const tc = (STATS && STATS.top_cost) || [];
  if (!tc.length) return `<div class="empty">${t('statNone')}</div>`;
  return `<table>${tc.slice(0,n).map(x=>`<tr class="clickable" onclick="focusBody('${x.uuid}')">
      <td>${esc(x.name || x.uuid.slice(0,8))}</td><td>${x.cost.toFixed(3)} ms/t</td></tr>`).join('')}
    <tr><td class="muted">${t('bodyCostTotal')}</td><td>${(STATS.body_cost_total??0).toFixed(3)} ms/t</td></tr></table>`;
}
function renderStatPop(){
  const pop = document.getElementById('statPop');
  if (!STATS) { pop.innerHTML = `<div class="empty">${t('statNone')}</div>`; return; }
  const dims = new Set([...Object.keys(STATS.phys_1m||{}), ...Object.keys(STATS.loaded||{})]);
  const rows = [...dims].map(d =>
    `<tr><td>${esc(d.replace('minecraft:',''))}</td><td>${(STATS.phys_1m?.[d]??0).toFixed(2)} ms</td><td>${STATS.loaded?.[d]??0}</td></tr>`).join('');
  pop.innerHTML = `<b style="font-size:12px">${t('physEngine')}</b>
    <table><tr class="muted"><td></td><td>${t('statPhys')}</td><td>${t('statLoaded')}</td></tr>
    ${rows || `<tr><td colspan=3 class="muted">${t('statNone')}</td></tr>`}</table>
    <b style="font-size:12px;display:block;margin-top:11px">${t('topCost')}</b><div class="mini">${topCostTable(5)}</div>`;
}
function toggleStatPop(){
  const pop = document.getElementById('statPop');
  pop.style.display = pop.style.display === 'block' ? 'none' : 'block';
}

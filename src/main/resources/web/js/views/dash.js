'use strict';
/* 总览视图:汇总卡片、状态/规模/维度分布、顶栏统计弹层 */
/* ===================== 总览 ===================== */
function summarize(){
  const s = {bodies:0, groups:DATA.groups.length, blocks:0, entries:DATA.total_entries,
    state:{loaded:0,stored:0,holding:0,orphan:0},
    groupSize:{huge:0,large:0,mid:0,small:0,frag:0}, bodySize:{huge:0,large:0,mid:0,small:0,frag:0},
    dims:{}, dup:0, clone:0, rec:{groups:0,bodies:0,blocks:0}};
  for (const g of DATA.groups) {
    s.blocks += g.blocks;
    s.groupSize[sizeClass(g.blocks)]++;
    for (const b of g.bodies) {
      s.bodies++;
      s.state[b.state] = (s.state[b.state]||0)+1;
      s.bodySize[sizeClass(b.blocks)]++;
      s.dims[b.dim] = (s.dims[b.dim]||0)+1;
      if (b.copies) s.dup++;
      if (b.clone) s.clone++;
    }
    if (g.rec) { s.rec.groups++; s.rec.bodies += g.members; s.rec.blocks += g.blocks; }
  }
  return s;
}
function barRow(items, colors, unit){
  const total = items.reduce((s,i)=>s+i[1],0) || 1;
  return `<div class="bar">${items.filter(i=>i[1]>0).map(i=>`<i style="width:${i[1]/total*100}%;background:${colors[i[0]]}"></i>`).join('')}</div>
    <div class="legend">${items.map(i=>`<span><i style="background:${colors[i[0]]}"></i>${i[2]} <b>${fmt(i[1])} ${unit}</b></span>`).join('')}</div>`;
}
function sizeBar(size, unit){
  return barRow([['huge',size.huge,T.szHuge.split(' ')[0]],['large',size.large,T.szLarge.split(' ')[0]],
    ['mid',size.mid,T.szMid.split(' ')[0]],['small',size.small,T.szSmall.split(' ')[0]],
    ['frag',size.frag,T.szFrag.split(' ')[0]]], SIZE_COLORS, unit);
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
        <div class="srvLabel">${T.dashServer}</div>
        <div class="srvName">${esc(cur)}</div>
      </div>
    </div>
    <div class="srvList">${
      multi ? SERVERS.map((x,i)=>`<button class="srvPick ${x.id===cur?'on':''}" onclick="switchServer(SERVERS[${i}].id)">
                 ${esc(x.id)}${x.host?`<span class="badge">${T.srvHost}</span>`:''}</button>`).join('')
            : `<span class="muted" style="font-size:11.5px">${T.dashServerOnly}</span>`}</div>
    ${multi?`<div class="srvHint">${T.dashServerPeer(SERVERS.length)}</div>`:''}
  </div>`;
}
/* R3.6 黄铜盘:规模分层=黄铜明度阶,状态色对齐语义 token(加载=绿,暂存=铜,孤儿=红) */
const SIZE_COLORS = {huge:'#d0a354',large:'#b98a2f',mid:'#96701f',small:'#74561a',frag:'#544012'};
const STATE_COLORS = {loaded:'#5cab62',stored:'#4a4438',holding:'#d08a3e',orphan:'#dd5c4d'};

/* 回收站卡片三态:加载中 / 加载失败 / 有数据。
   从前 RECYCLE===null 一律画"加载中…",服务端已经 500 了也照样转圈 */
function renderDashClean(){
  document.getElementById('cleanCard').innerHTML = `
    <h4><span>${T.recycleT}</span></h4>
    ${RECYCLE ? `<div class="statNum">${fmt((RECYCLE.latest_groups||0)+(RECYCLE.old_groups||0))}</div>
         <div class="hint">${T.recycleStructures}</div>
         <div class="kvgrid">
           <div class="k">${T.rTabLatest}</div><div class="v">${fmt(RECYCLE.latest_groups||0)}</div>
           <div class="k">${T.rTabOld}</div><div class="v">${fmt(RECYCLE.old_groups||0)}</div>
         </div>
         <div class="hint">${T.recycleDisk(fmtBytes(RECYCLE.disk_bytes||0))}</div>`
      : RECYCLE_ERROR ? `<div class="empty" style="color:var(--bad)">⚠ ${T.loadFail}${esc(RECYCLE_ERROR)}</div>`
      : `<div class="empty">${T.loading}</div>`}`;
}
function renderDash(){
  renderDashServer();
  // 已有数据时刷新失败:旧数字照常显示,但要说明它是上一次的结果
  const stale = (DATA && BODIES_ERROR) || (RECYCLE && RECYCLE_ERROR) || '';
  if (stale) document.getElementById('dashSrv').innerHTML +=
    `<div class="srvHint" style="color:var(--bad)">${T.staleData}${esc(stale)}</div>`;
  // 首屏就失败:从前直接 return,总览是一片空白,用户只看到一闪而过的 toast
  renderDashClean();   // RECYCLE 驱动
  renderDashTools();   // 静态,但每次都要写
  renderDashStats();   // STATS 驱动
  if (!DATA) {
    document.getElementById('dashTop').innerHTML = BODIES_ERROR
      ? `<div class="stat"><div class="statLabel">${T.dashBodies}</div>
           <div class="statSub" style="color:var(--bad)">⚠ ${T.loadFail}${esc(BODIES_ERROR)}</div></div>`
      : `<div class="stat"><div class="statSub">${T.loading}</div></div>`;
    document.getElementById('dashMid').innerHTML = '';
    return;
  }
  const s = summarize();
  const loadedCost = statsEnabled() ? (STATS.body_cost_total ?? 0) : null;
  const loadFrac = s.bodies ? s.state.loaded / s.bodies : 0;
  document.getElementById('dashTop').innerHTML = `
    <div class="stat"><div class="statLabel">${T.dashBodies}</div>
      <div class="statNum">${fmt(s.bodies)}</div>
      <div class="statSub">${fmt(s.groups)} ${T.groupsUnit} · ${fmt(s.entries)} ${T.entries}</div></div>
    <div class="stat"><div class="statLabel">${T.dashBlocks}</div>
      <div class="statNum">${fmt(s.blocks)}</div>
      <div class="statSub">${T.szHuge} ${s.groupSize.huge} · ${T.szLarge} ${s.groupSize.large}</div></div>
    <div class="stat"><div class="statLabel">${T.dashLoaded}</div>
      <div class="statNum" style="color:var(--acc)">${s.state.loaded}<small>/${fmt(s.bodies)}</small></div>
      <div class="ratio"><i style="width:${(loadFrac*100).toFixed(1)}%"></i></div>
      <div class="statSub">${loadedCost !== null ? T.physBodies+' '+loadedCost.toFixed(2)+' ms/t' : ''}</div></div>
    <div class="stat"${s.rec.groups?` onclick="setView('bodies',{tab:'rec',reset:true})" style="cursor:pointer"`:''}>
      <div class="statLabel">${T.dashClean}</div>
      <div class="statNum" style="color:${s.rec.groups?'var(--warn)':'var(--ok)'}">${s.rec.groups?fmt(s.rec.bodies):'✓'}</div>
      <div class="statSub">${s.rec.groups
        ? `${s.rec.groups} ${T.groupsUnit} · ${fmt(s.rec.blocks)} ${T.blocksUnit} · ${T.dashGo}`
        : T.dashHealthy}</div></div>`;

  document.getElementById('dashMid').innerHTML = `
    <div class="card"><h4><span>${T.dashState}</span><span class="more" onclick="setView('bodies',{tab:'all',reset:true})">${T.dashGo}</span></h4>
      ${barRow([['loaded',s.state.loaded,T.stLoaded],['stored',s.state.stored,T.stStored],
        ['holding',s.state.holding,T.holdingX],['orphan',s.state.orphan,T.orphanX]], STATE_COLORS, T.bodies)}</div>
    <div class="card"><h4>${T.dashGroupScale}</h4>${sizeBar(s.groupSize,T.groupsUnit)}</div>
    <div class="card"><h4>${T.dashBodyScale}</h4>${sizeBar(s.bodySize,T.bodies)}</div>
    <div class="card"><h4>${T.dashDims}</h4><div class="kvgrid">
      ${Object.entries(s.dims).sort((a,b)=>b[1]-a[1]).map(([d,n])=>
        `<div class="k">${esc(d.replace('minecraft:',''))}</div><div class="v">${fmt(n)} ${T.bodies}</div>`).join('')}</div></div>
    <div class="card ${(s.state.orphan)?'accent-bad':''}"><h4><span>${T.dashAnom}</span>${(s.state.orphan+s.dup+s.clone)?`<span class="more" onclick="setView('bodies',{tab:'anom',reset:true})">${T.dashGo}</span>`:''}</h4>
      <div class="kvgrid">
        <div class="k"><i class="dot" style="background:var(--bad)"></i>${T.dashOrphans}</div><div class="v" style="color:${s.state.orphan?'var(--bad)':'var(--dim)'}">${s.state.orphan}</div>
        <div class="k"><i class="dot" style="background:var(--warn)"></i>${T.dashDup}</div><div class="v" style="color:${s.dup?'var(--warn)':'var(--dim)'}">${s.dup}</div>
        <div class="k"><i class="dot" style="background:var(--clone)"></i>${T.dashClone}</div><div class="v" style="color:${s.clone?'var(--clone)':'var(--dim)'}">${s.clone}</div>
        <div class="k"><i class="dot" style="background:var(--warn)"></i>${T.dashHolding}</div><div class="v">${s.state.holding}</div>
      </div></div>`;

}
function renderDashTools(){
  // 退出集群只在"本机网关连着远端面板"时才有意义;网页由面板服务端直出时没有可退的东西
  const canExit = typeof gatewayMode !== 'undefined' && gatewayMode === 'client' && gatewayConnected;
  document.getElementById('toolCard').innerHTML = `
    <h4>${T.tools}</h4>
    <div style="display:flex;gap:9px;flex-wrap:wrap">
      <button onclick="doRescan()">${T.rescan}</button>
      <button onclick="runConsistencyScan()">${T.consistencyScan}</button>
      <button onclick="loadAll(true)">${T.refresh}</button>
      <button onclick="doChangeToken()">${T.tokenChange}</button>
      <button onclick="openManual()">${T.manualOpen}</button>
      ${canExit ? `<button class="warnb" onclick="disconnectGateway()">${T.exitCluster}</button>` : ''}
    </div>
    <div class="hint">${T.scanInfo}</div>
    <div class="hint">${T.tokenHint}</div>
    ${canExit ? `<div class="hint">${T.exitClusterHint}</div>` : ''}`;
}
/* 物理图表这三块归 STATS 管,不归 DATA。从前写在 renderDash 的有数据分支里,
   切服后 DATA 和 STATS 都清了,它们却还留着上一个服的 HTML —— 在"最吃性能"里
   点一下旧服的体,focusBody 就撞上 DATA===null */
function renderDashStats(){
  drawPhysChart(document.getElementById('physChart'), true);   // STATS 为空时它自己 clearRect
  const enabled = statsEnabled();
  const dims = enabled ? Object.keys(STATS.phys||{}) : [];
  document.getElementById('statsDisabled').style.display = STATS && !enabled ? 'flex' : 'none';
  document.getElementById('statsDisabled').textContent = T.statsDisabled;
  document.getElementById('physLegend').innerHTML = enabled
    ? dims.map((d,i)=>`<span><i style="background:${DIM_COLORS[i%DIM_COLORS.length]}"></i>${esc(d.replace('minecraft:',''))}
        <b>${(STATS.phys_1m?.[d]??0).toFixed(2)} ms/t</b></span>`).join('')
      + `<span><i style="background:${BODY_COLOR}"></i>${T.physBodies} <b>${(STATS.body_cost_total??0).toFixed(2)} ms/t</b></span>`
    : '';
  document.getElementById('dashTopCost').innerHTML = topCostTable(8);   // STATS 为空时是"暂无数据"
}
function topCostTable(n){
  if (STATS?.enabled === false) return `<div class="empty">${T.statsDisabled}</div>`;
  const tc = (STATS && STATS.top_cost) || [];
  if (!tc.length) return `<div class="empty">${T.statNone}</div>`;
  return `<table>${tc.slice(0,n).map(x=>`<tr class="clickable" onclick="focusBody('${x.uuid}')">
      <td>${esc(x.name || x.uuid.slice(0,8))}</td><td>${x.cost.toFixed(3)} ms/t</td></tr>`).join('')}
    <tr><td class="muted">${T.bodyCostTotal}</td><td>${(STATS.body_cost_total??0).toFixed(3)} ms/t</td></tr></table>`;
}
function renderStatPop(){
  const pop = document.getElementById('statPop');
  const status = statsErrorLabel();
  const notice = status ? `<div class="staleHint">${esc(status)}</div>` : '';
  if (!STATS) { pop.innerHTML = notice + `<div class="empty">${T.statNone}</div>`; return; }
  if (STATS.enabled === false) { pop.innerHTML = notice + `<div class="empty">${T.statsDisabled}</div>`; return; }
  const dims = new Set([...Object.keys(STATS.phys_1m||{}), ...Object.keys(STATS.loaded||{})]);
  const stopped = new Set(STATS.phys_paused || []);
  const rows = [...dims].map(d => {
    const off = stopped.has(d);
    return `<tr${off ? ' class="bad"' : ''}><td>${esc(d.replace('minecraft:',''))}</td>` +
      `<td>${off ? T.physOff : (STATS.phys_1m?.[d]??0).toFixed(2) + ' ms'}</td><td>${STATS.loaded?.[d]??0}</td>` +
      `<td><button class="${off ? '' : 'warnb'}" title="${T.dimPhysTip}" ` +
      `onclick="toggleDimPhysics('${esc(d)}',${!off})">${off ? '▶' : '⏹'}</button></td></tr>`;
  }).join('');
  pop.innerHTML = notice + `<b style="font-size:12px">${T.physEngine}</b>
    <table><tr class="muted"><td></td><td>${T.statPhys}</td><td>${T.statLoaded}</td><td></td></tr>
    ${rows || `<tr><td colspan=4 class="muted">${T.statNone}</td></tr>`}</table>
    <b style="font-size:12px;display:block;margin-top:11px">${T.topCost}</b><div class="mini">${topCostTable(5)}</div>`;
}
function toggleStatPop(){
  const pop = document.getElementById('statPop');
  pop.style.display = pop.style.display === 'block' ? 'none' : 'block';
}
/* 总览随窗口尺寸重排:监听器归视图所有者(自 preview.js 挪入) */
window.addEventListener('resize', () => { if (VIEW==='dash') renderDash(); });

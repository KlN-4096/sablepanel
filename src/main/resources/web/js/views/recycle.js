'use strict';
/* 回收站视图:页签/筛选/分组列表/详情 */
/* ===================== 回收站 ===================== */
const R_TABS = [
  {k:'latest', label:'rTabLatest', count:'latest_groups'},
  {k:'old', label:'rTabOld', count:'old_groups'},
];
/* 维度筛选是按维度 id 记的,两个服的 minecraft:overworld 是同一个字符串:不清的话,
   在 A 服取消勾选主世界,切到 B 之后 B 的主世界组会整批消失,而勾选框看着是正常的。
   #rDims 里的旧 DOM 由钩子后统一的 renderAll() 回收站空态清掉 */
onServerReset(() => { R_DIM_DISABLED.clear(); clearRecycleDetail(); });
function setRecycleTab(tab){
  if (tab===R_TAB) return;
  R_TAB=tab;
  R_SELECTED.clear(); RECYCLE_BY_ID=new Map(); RECYCLE_CURSOR=''; RECYCLE_TOTAL=0; RECYCLE=null;
  clearRecycleDetail(); renderRecycle(); loadRecycle();
}
function renderRecycleTabs(){
  const data=RECYCLE||{};
  document.getElementById('rTabs').innerHTML = R_TABS.map(tab=>
    `<button class="${R_TAB===tab.k?'on':''}" onclick="setRecycleTab('${tab.k}')">${T[tab.label]}
      <span class="cnt">${fmt(data[tab.count]||0)}</span></button>`).join('');
}
function recycleStateTag(state){
  if (state==='restored') return `<span class="tag ok">${T.recycleRestored}</span>`;
  if (state==='recovery_required') return `<span class="tag warn">${T.recycleRecovery}</span>`;
  if (state==='incomplete') return `<span class="tag bad">${T.recycleIncomplete}</span>`;
  return `<span class="tag acc">${T.recycleDeleted}</span>`;
}
/* 回收站里属于"服务器配置"的三块:上限输入框、磁盘用量、维度筛选。
   它们只有 loadRecycle 成功那条路写,所以没数据时必须有人负责清 —— 上限输入框尤其危险,
   切服后它还是上一个服的数字,用户一按保存就把旧服的配置写到新服上。清空即等于禁用:
   saveRecycleLimit 挡住了非整数。
   由 renderAll 在 RECYCLE 为空时调,不挂在 renderRecycle 上 —— 那个只在回收站页跑。
   清 #rDims 还有个连带作用:renderRecycleDims 会从那批 .rFDim 反向重建 R_DIM_DISABLED */
function clearRecycleMeta(){
  document.getElementById('rLimit').value = '';
  document.getElementById('rUsage').textContent = '';
  document.getElementById('rDims').innerHTML = '';
}
function renderRecycle(){
  renderRecycleTabs();
  const list = document.getElementById('rList');
  if (!RECYCLE) {
    list.innerHTML = RECYCLE_ERROR
      ? `<div class="listEmpty"><span class="big">⚠</span>${T.loadFail}${esc(RECYCLE_ERROR)}</div>`
      : `<div class="listEmpty">${T.loading}</div>`;
    RECYCLE_VISIBLE = [];   // 列表都没了,全选的名单不能还留着上一轮的
    renderRecycleToolbar(0, 0);
    clearRecycleMeta();
    return;
  }
  const needle = document.getElementById('rSearch').value.trim().toLowerCase();
  const states = new Set(checkedValues('rFState'));
  const sizes = new Set(checkedValues('rFSize'));
  const dimInputs = [...document.querySelectorAll('.rFDim')];
  const dims = new Set(dimInputs.filter(x=>x.checked).map(x=>x.value));
  const namedOnly = document.getElementById('rNamedOnly').checked;
  const visible = RECYCLE.groups.filter(group => {
    if (!states.has(group.state || 'deleted') || !sizes.has(sizeClass(group.blocks || 0))) return false;
    if (namedOnly && !group.name) return false;
    // 摘要组的 bodies 是空的(服务端连元数据都装不下时只发固定尺寸摘要),
    // 按成员筛选一律放行 —— 否则它会被静默滤掉,用户连"这里还有一组"都看不见
    const members = group.bodies || [];
    if (dimInputs.length && members.length && !members.some(body=>dims.has(body.dim || 'minecraft:overworld'))) return false;
    if (!needle) return true;
    return String(group.name||'').toLowerCase().includes(needle)
      || String(group.id||'').toLowerCase().includes(needle)
      || members.some(body=>String(body.name||'').toLowerCase().includes(needle)
        || body.uuid.toLowerCase().includes(needle));
  }).sort((a,b)=>(b.deleted_at||0)-(a.deleted_at||0));
  RECYCLE_VISIBLE = visible;   // 全选按钮要和列表所见完全一致
  if (!visible.length) {
    const allVersions=(RECYCLE.latest_groups||0)+(RECYCLE.old_groups||0);
    const emptyText=RECYCLE_TOTAL?T.recycleNoMatch:(allVersions?T.recycleVersionEmpty:T.recycleEmpty);
    list.innerHTML=`<div class="listEmpty"><span class="big">♲</span>${emptyText}</div>`;
  } else {
    // R3.5 卡片化:一组一卡,与物理体网格同族(占位立方体+组 id 色相+名称/块数/状态徽章)。
    // 点卡选组内块数最大的成员,多体组在详情面板里换成员;摘要组(bodies 空)只可勾选不可点开
    list.innerHTML = visible.map(group => {
      const bodies = group.bodies || [];
      const primary = bodies.reduce((a,b)=>(b.blocks>(a?a.blocks:-1)?b:a), bodies[0]);
      const title = esc(group.name) || (bodies.length
        ? `${bodies[0].uuid.slice(0,8)}…` : `${esc(group.id).slice(0,16)}…`);
      const selected = RSELG && RSELG.id === group.id;
      const clickable = bodies.length ? ` onclick="selectRecycleBody('${group.id}','${primary.uuid}')"` : '';
      return `<div class="bcard rcard ${group.state==='restored'?'is-restored':''} ${selected?'is-sel':''}"${clickable}>
        <div class="bthumb" ${primary?`data-tu="${primary.uuid}"`:''} style="color:hsl(${hueOf(group.id)} 32% 56%)">
          ${primary ? thumbHtml(primary.uuid, primary.blocks||0, true) : THUMB_CUBE}
          <input type="checkbox" class="gsel" ${R_SELECTED.has(group.id)?'checked':''}
            onclick="event.stopPropagation();toggleRecycleGroup('${group.id}')">
          <span class="thSize pix">${fmt(group.blocks||0)}</span>
        </div>
        <div class="bmeta">
          <div class="bname">${title}</div>
          <div class="bsub">${group.members} ${T.bodies} · ${fmtDate(group.deleted_at)}</div>
          <div class="btags">${recycleStateTag(group.state)}${group.bodies_omitted?`<span class="tag warn">${T.rBodiesOmitted}</span>`:''}</div>
        </div></div>`;
    }).join('');
  }
  renderRecycleToolbar(visible.length, RECYCLE.groups.length);
}
function renderRecycleToolbar(visible, total){
  const selectedGroups=[...R_SELECTED].map(id=>RECYCLE_BY_ID.get(id)).filter(Boolean);
  const selectedBodies=selectedGroups.reduce((sum,group)=>sum+group.members,0);
  // 已有数据时刷新失败:旧列表照常可用,但要说明它是上一次的结果
  const stale=RECYCLE&&RECYCLE_ERROR
    ? `<span style="color:var(--bad)">· ${T.staleData}${esc(RECYCLE_ERROR)}</span>` : '';
  const restoreable=selectedGroups.every(group=>group.state!=='incomplete');
  // 列表是分页拉的,所以要同时告诉用户"已加载多少 / 服务端一共多少",筛选只作用在已加载部分
  const loaded=(RECYCLE&&RECYCLE.groups.length)||0;
  const more=RECYCLE_CURSOR
    ? `<span class="muted">${T.rLoaded(loaded,RECYCLE_TOTAL||loaded)}</span>
       <button onclick="loadMoreRecycle()" ${RECYCLE_LOADING?'disabled':''}>${
         RECYCLE_LOADING?T.rLoading:T.rLoadMore}</button>` : '';
  document.getElementById('rToolbar').innerHTML =
    `<span>${T.rShowing(visible,total)}</span>${stale}
     ${visible ? `<button onclick="recycleSelectAll.toggle()">${recycleSelectAll.label()}</button>` : ''}
     ${more}
     ${selectedGroups.length?`<span id="rSelSeg"><span class="selInfo">${T.rSelectInfo(selectedGroups.length,selectedBodies)}</span>
       <button class="primary" onclick="restoreSelectedGroups()" ${restoreable?'':'disabled'}>${T.restoreSelected}</button>
       <button class="danger" onclick="purgeSelectedGroups()">${T.purgeSelected}</button>
       <button class="ghost" onclick="clearRecycleSelection()">${T.selClear}</button></span>`:''}`;
}
function toggleRecycleGroup(id){
  R_SELECTED.has(id)?R_SELECTED.delete(id):R_SELECTED.add(id);
  renderRecycle();
}
/* 一键全选/取消(与物理体同一副骨架):只动当前筛选后可见的组 ——
   选择不碰服务端还没分页拉下来的部分,取消不碰被筛选藏起来的已选组 */
const recycleSelectAll = makeSelectAll({
  items: () => RECYCLE_VISIBLE,
  keys: group => [group.id],
  sel: () => R_SELECTED,
  after: () => renderRecycle(),
});
function clearRecycleSelection(){ R_SELECTED.clear(); renderRecycle(); }
function selectRecycleBody(groupId, uuid){
  const group=RECYCLE_BY_ID.get(groupId), body=group&&(group.bodies||[]).find(item=>item.uuid===uuid);
  if (!body) return;
  if (!RSEL || RSEL.uuid!==uuid) compExpanded={};
  RSELG=group; RSEL=body; renderRecycleDetail(); renderRecycle();
  loadRecycleMesh(groupId,uuid);
}
function renderRecycleDetail(){
  if (!RSEL || !RSELG) return;
  const body=RSEL, group=RSELG;
  // 卡片化后组内成员不再挂在列表里,多体组在这里换成员
  const memberStrip = (group.bodies||[]).length > 1
    ? `<div class="rMembers">${group.bodies.map(item=>
        `<button class="${item.uuid===body.uuid?'on':''}" onclick="selectRecycleBody('${group.id}','${item.uuid}')">
          ${esc(item.name)||item.uuid.slice(0,8)}<span class="cnt">${fmt(item.blocks||0)}</span></button>`).join('')}</div>`
    : '';
  const rows=[];
  rows.push([T.name,esc(body.name)||`<span class="muted">${T.unnamed}</span>`]);
  rows.push(['UUID',`<span class="val" style="font-size:10.5px">${body.uuid}</span><button class="copyBtn" onclick="copyText('${body.uuid}')">⧉</button>`]);
  rows.push([T.state,recycleStateTag(group.state)]);
  rows.push([T.dim,esc(body.dim||'minecraft:overworld')]);
  rows.push([T.coord,`<span class="val">${(body.pos||[0,0,0]).map(v=>Number(v).toFixed(1)).join(', ')}</span>`]);
  rows.push([T.bbox,`<span class="val">${(body.size||[0,0,0]).map(v=>Number(v).toFixed(1)).join(' × ')}</span>`]);
  rows.push([T.blockCount,`<span class="val">${fmt(body.blocks||0)}</span>`]);
  if (body.be) rows.push([T.beRow,`<span class="val">${fmt(body.be)}</span>`]);
  if (body.contents) rows.push([T.contentsRow,`<span class="val">${fmt(body.contents)}</span>`]);
  if (body.dependencies&&body.dependencies.length) rows.push([T.deps,fmt(body.dependencies.length)]);
  rows.push([T.group,T.groupVal(group.members,group.blocks)]);
  rows.push([T.deletedAt,fmtDateTime(group.deleted_at)]);
  if (group.restored_at) rows.push([T.restoredAt,fmtDateTime(group.restored_at)]);
  rows.push([T.backupFiles,`<span class="val">${body.backup_count||1}</span>`]);
  rows.push([T.backupGroup,`<span class="val" style="font-size:10.5px">${group.id}</span>`]);
  // 服务端对单页超预算的巨型组只发元数据。不说的话构成条就是空的,看起来像"这个体没有方块"
  if (group.blocks_omitted) rows.push(['',`<span class="tag warn">${T.rBlocksOmitted}</span>`]);
  document.getElementById('rBody').innerHTML=
    memberStrip+`<table>${rows.map(row=>`<tr><td>${row[0]}</td><td>${row[1]}</td></tr>`).join('')}</table><div id="rCompList"></div>`;
  document.getElementById('rOps').style.display='block';
  document.getElementById('restoreGroupBtn').disabled=group.state==='incomplete';
  renderComposition();
}
function clearRecycleDetail(){
  RSEL=RSELG=null;
  document.getElementById('rBody').innerHTML=`<div id="rDetailEmpty"><span class="big">♲</span><span>${T.pickRecycle}</span></div>`;
  document.getElementById('rOps').style.display='none';
  if (VIEW==='recycle') { disposeMesh(); MESH_DATA=MESH_UUID=MESH_SOURCE=null; document.getElementById('pvInfo').textContent=''; }
}
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
    `<label class="fchip"><input type="checkbox" class="rFDim" value="${esc(d)}" ${R_DIM_DISABLED.has(d)?'':'checked'} onchange="renderRecycle()"><span>${esc(d.replace('minecraft:',''))}</span></label>`).join('');
}

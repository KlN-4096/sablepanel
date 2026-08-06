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
    `<button class="${R_TAB===tab.k?'on':''}" onclick="setRecycleTab('${tab.k}')">${t(tab.label)}
      <span class="cnt">${fmt(data[tab.count]||0)}</span></button>`).join('');
}
function recycleStateTag(state){
  if (state==='restored') return `<span class="tag ok">${t('recycleRestored')}</span>`;
  if (state==='recovery_required') return `<span class="tag warn">${t('recycleRecovery')}</span>`;
  if (state==='incomplete') return `<span class="tag bad">${t('recycleIncomplete')}</span>`;
  return `<span class="tag acc">${t('recycleDeleted')}</span>`;
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
      ? `<div class="listEmpty"><span class="big">⚠</span>${t('loadFail')}${esc(RECYCLE_ERROR)}</div>`
      : `<div class="listEmpty">${t('loading')}</div>`;
    renderRecycleToolbar(0, 0);
    clearRecycleMeta();
    return;
  }
  const needle = document.getElementById('rSearch').value.trim().toLowerCase();
  const states = new Set([...document.querySelectorAll('.rFState:checked')].map(x=>x.value));
  const sizes = new Set([...document.querySelectorAll('.rFSize:checked')].map(x=>x.value));
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
  if (!visible.length) {
    const allVersions=(RECYCLE.latest_groups||0)+(RECYCLE.old_groups||0);
    const emptyText=RECYCLE_TOTAL?t('recycleNoMatch'):(allVersions?t('recycleVersionEmpty'):t('recycleEmpty'));
    list.innerHTML=`<div class="listEmpty"><span class="big">♲</span>${emptyText}</div>`;
  } else {
    list.innerHTML = visible.map(group => {
      // 摘要组没有成员明细,标题只能回落到组 id —— 从前直接读 bodies[0].uuid,整页崩
      const bodies = group.bodies || [];
      const title = esc(group.name) || (bodies.length
        ? `${bodies[0].uuid.slice(0,8)}…` : `${esc(group.id).slice(0,16)}…`);
      const state = recycleStateTag(group.state);
      const omitted = group.bodies_omitted
        ? `<div class="member muted">${t('rBodiesOmitted')}</div>` : '';
      const members = omitted + bodies.map(body=>
        `<div class="member ${RSEL&&RSELG&&RSELG.id===group.id&&RSEL.uuid===body.uuid?'sel':''}"
          data-rkey="${group.id}/${body.uuid}" onclick="selectRecycleBody('${group.id}','${body.uuid}')">
          <span class="mname">${esc(body.name)||`<span class="muted">${body.uuid.slice(0,8)}</span>`}</span>
          <span class="num">${fmt(body.blocks||0)} ${t('blocksUnit')}</span>
          <span class="tag">${esc((body.dim||'minecraft:overworld').replace('minecraft:',''))}</span>
        </div>`).join('');
      const opened=RSELG&&RSELG.id===group.id;
      return `<div class="group rgroup ${group.state==='restored'?'restored':''}" data-rid="${group.id}">
        <div class="ghead" onclick="toggleGroupEl(this)">
          <input type="checkbox" class="gsel" ${R_SELECTED.has(group.id)?'checked':''}
            onclick="event.stopPropagation();toggleRecycleGroup('${group.id}')">
          <span class="caret">${opened?'▼':'▶'}</span><span class="gname">${title}</span>${state}
          <span class="rmeta">${group.members} ${t('bodies')} · ${fmt(group.blocks||0)} · ${new Date(group.deleted_at).toLocaleString()}</span>
        </div><div class="members" style="${opened?'display:block':''}">${members}</div></div>`;
    }).join('');
  }
  renderRecycleToolbar(visible.length, RECYCLE.groups.length);
}
function toggleGroupEl(head){
  const members=head.nextElementSibling, open=members.style.display==='block';
  members.style.display=open?'none':'block'; head.querySelector('.caret').textContent=open?'▶':'▼';
}
function expandRecycle(open){
  document.querySelectorAll('#rList .group').forEach(group=>{
    group.querySelector('.members').style.display=open?'block':'none';
    group.querySelector('.caret').textContent=open?'▼':'▶';
  });
}
function renderRecycleToolbar(visible, total){
  const selectedGroups=[...R_SELECTED].map(id=>RECYCLE_BY_ID.get(id)).filter(Boolean);
  const selectedBodies=selectedGroups.reduce((sum,group)=>sum+group.members,0);
  // 已有数据时刷新失败:旧列表照常可用,但要说明它是上一次的结果
  const stale=RECYCLE&&RECYCLE_ERROR
    ? `<span style="color:var(--bad)">· ${t('staleData')}${esc(RECYCLE_ERROR)}</span>` : '';
  const restoreable=selectedGroups.every(group=>group.state!=='incomplete');
  // 列表是分页拉的,所以要同时告诉用户"已加载多少 / 服务端一共多少",筛选只作用在已加载部分
  const loaded=(RECYCLE&&RECYCLE.groups.length)||0;
  const more=RECYCLE_CURSOR
    ? `<span class="muted">${t('rLoaded')(loaded,RECYCLE_TOTAL||loaded)}</span>
       <button onclick="loadMoreRecycle()" ${RECYCLE_LOADING?'disabled':''}>${
         RECYCLE_LOADING?t('rLoading'):t('rLoadMore')}</button>` : '';
  document.getElementById('rToolbar').innerHTML =
    `<span>${t('rShowing')(visible,total)}</span>${stale}
     <button onclick="expandRecycle(true)">${t('expandAll')}</button>
     <button onclick="expandRecycle(false)">${t('collapseAll')}</button>
     ${more}
     ${selectedGroups.length?`<span id="rSelSeg"><span class="selInfo">${t('rSelectInfo')(selectedGroups.length,selectedBodies)}</span>
       <button class="primary" onclick="restoreSelectedGroups()" ${restoreable?'':'disabled'}>${t('restoreSelected')}</button>
       <button class="danger" onclick="purgeSelectedGroups()">${t('purgeSelected')}</button>
       <button class="ghost" onclick="clearRecycleSelection()">${t('selClear')}</button></span>`:''}`;
}
function toggleRecycleGroup(id){
  R_SELECTED.has(id)?R_SELECTED.delete(id):R_SELECTED.add(id);
  renderRecycle();
}
function clearRecycleSelection(){ R_SELECTED.clear(); renderRecycle(); }
function selectRecycleBody(groupId, uuid){
  const group=RECYCLE_BY_ID.get(groupId), body=group&&(group.bodies||[]).find(item=>item.uuid===uuid);
  if (!body) return;
  if (!RSEL || RSEL.uuid!==uuid) compExpanded={compList:false,rCompList:false,copyComp:false,fsComp:false};
  RSELG=group; RSEL=body; renderRecycleDetail(); renderRecycle();
  loadRecycleMesh(groupId,uuid);
}
function renderRecycleDetail(){
  if (!RSEL || !RSELG) return;
  const body=RSEL, group=RSELG;
  const rows=[];
  rows.push([t('name'),esc(body.name)||`<span class="muted">${t('unnamed')}</span>`]);
  rows.push(['UUID',`<span class="val" style="font-size:10.5px">${body.uuid}</span><button class="copyBtn" onclick="copyText('${body.uuid}')">⧉</button>`]);
  rows.push([t('state'),recycleStateTag(group.state)]);
  rows.push([t('dim'),esc(body.dim||'minecraft:overworld')]);
  rows.push([t('coord'),`<span class="val">${(body.pos||[0,0,0]).map(v=>Number(v).toFixed(1)).join(', ')}</span>`]);
  rows.push([t('bbox'),`<span class="val">${(body.size||[0,0,0]).map(v=>Number(v).toFixed(1)).join(' × ')}</span>`]);
  rows.push([t('blockCount'),`<span class="val">${fmt(body.blocks||0)}</span>`]);
  if (body.be) rows.push([t('beRow'),`<span class="val">${fmt(body.be)}</span>`]);
  if (body.contents) rows.push([t('contentsRow'),`<span class="val">${fmt(body.contents)}</span>`]);
  if (body.dependencies&&body.dependencies.length) rows.push([t('deps'),fmt(body.dependencies.length)]);
  rows.push([t('group'),t('groupVal')(group.members,group.blocks)]);
  rows.push([t('deletedAt'),new Date(group.deleted_at).toLocaleString()]);
  if (group.restored_at) rows.push([t('restoredAt'),new Date(group.restored_at).toLocaleString()]);
  rows.push([t('backupFiles'),`<span class="val">${body.backup_count||1}</span>`]);
  rows.push([t('backupGroup'),`<span class="val" style="font-size:10.5px">${group.id}</span>`]);
  // 服务端对单页超预算的巨型组只发元数据。不说的话构成条就是空的,看起来像"这个体没有方块"
  if (group.blocks_omitted) rows.push(['',`<span class="tag warn">${t('rBlocksOmitted')}</span>`]);
  document.getElementById('rBody').innerHTML=
    `<table>${rows.map(row=>`<tr><td>${row[0]}</td><td>${row[1]}</td></tr>`).join('')}</table><div id="rCompList"></div>`;
  document.getElementById('rOps').style.display='block';
  document.getElementById('restoreGroupBtn').disabled=group.state==='incomplete';
  renderComposition();
}
function clearRecycleDetail(){
  RSEL=RSELG=null;
  document.getElementById('rBody').innerHTML=`<div id="rDetailEmpty"><span class="big">♲</span><span>${t('pickRecycle')}</span></div>`;
  document.getElementById('rOps').style.display='none';
  if (VIEW==='recycle') { disposeMesh(); MESH_DATA=MESH_UUID=MESH_SOURCE=null; document.getElementById('pvInfo').textContent=''; }
}

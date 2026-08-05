'use strict';
/* 回收站视图:页签/筛选/分组列表/详情 */
/* ===================== 回收站 ===================== */
const R_TABS = [
  {k:'all', label:'rTabAll', test:()=>true},
  {k:'named', label:'rTabNamed', test:g=>!!g.name},
  {k:'unnamed', label:'rTabUnnamed', test:g=>!g.name},
  {k:'restored', label:'rTabRestored', test:g=>g.state==='restored'},
];
function recycleTabTest(group){ return (R_TABS.find(x=>x.k===R_TAB)||R_TABS[0]).test(group); }
function setRecycleTab(tab){ R_TAB=tab; renderRecycle(); }
function renderRecycleTabs(){
  if (!RECYCLE) return;
  document.getElementById('rTabs').innerHTML = R_TABS.map(tab=>
    `<button class="${R_TAB===tab.k?'on':''}" onclick="setRecycleTab('${tab.k}')">${t(tab.label)}
      <span class="cnt">${RECYCLE.groups.filter(tab.test).length}</span></button>`).join('');
}
function recycleStateTag(state){
  if (state==='restored') return `<span class="tag ok">${t('recycleRestored')}</span>`;
  if (state==='recovery_required') return `<span class="tag warn">${t('recycleRecovery')}</span>`;
  return `<span class="tag acc">${t('recycleDeleted')}</span>`;
}
function renderRecycle(){
  renderRecycleTabs();
  const list = document.getElementById('rList');
  if (!RECYCLE) { list.innerHTML=`<div class="listEmpty">${t('loading')}</div>`; return; }
  const needle = document.getElementById('rSearch').value.trim().toLowerCase();
  const states = new Set([...document.querySelectorAll('.rFState:checked')].map(x=>x.value));
  const sizes = new Set([...document.querySelectorAll('.rFSize:checked')].map(x=>x.value));
  const dimInputs = [...document.querySelectorAll('.rFDim')];
  const dims = new Set(dimInputs.filter(x=>x.checked).map(x=>x.value));
  const namedOnly = document.getElementById('rNamedOnly').checked;
  const tabGroups = RECYCLE.groups.filter(recycleTabTest);
  const visible = tabGroups.filter(group => {
    if (!states.has(group.state || 'deleted') || !sizes.has(sizeClass(group.blocks || 0))) return false;
    if (namedOnly && !group.name) return false;
    if (dimInputs.length && !group.bodies.some(body=>dims.has(body.dim || 'minecraft:overworld'))) return false;
    if (!needle) return true;
    return String(group.name||'').toLowerCase().includes(needle)
      || group.bodies.some(body=>String(body.name||'').toLowerCase().includes(needle)
        || body.uuid.toLowerCase().includes(needle));
  }).sort((a,b)=>(b.deleted_at||0)-(a.deleted_at||0));
  if (!visible.length) {
    list.innerHTML=`<div class="listEmpty"><span class="big">♲</span>${RECYCLE.groups.length?t('rEmpty'):t('recycleEmpty')}</div>`;
  } else {
    list.innerHTML = visible.map(group => {
      const title = esc(group.name) || `${group.bodies[0].uuid.slice(0,8)}…`;
      const state = recycleStateTag(group.state);
      const members = group.bodies.map(body=>
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
  renderRecycleToolbar(visible.length, tabGroups.length);
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
  // 列表是分页拉的,所以要同时告诉用户"已加载多少 / 服务端一共多少",筛选只作用在已加载部分
  const loaded=(RECYCLE&&RECYCLE.groups.length)||0;
  const more=RECYCLE_CURSOR
    ? `<span class="muted">${t('rLoaded')(loaded,RECYCLE_TOTAL||loaded)}</span>
       <button onclick="loadMoreRecycle()" ${RECYCLE_LOADING?'disabled':''}>${
         RECYCLE_LOADING?t('rLoading'):t('rLoadMore')}</button>` : '';
  document.getElementById('rToolbar').innerHTML =
    `<span>${t('rShowing')(visible,total)}</span>
     <button onclick="expandRecycle(true)">${t('expandAll')}</button>
     <button onclick="expandRecycle(false)">${t('collapseAll')}</button>
     ${more}
     ${selectedGroups.length?`<span id="rSelSeg"><span class="selInfo">${t('rSelectInfo')(selectedGroups.length,selectedBodies)}</span>
       <button class="primary" onclick="restoreSelectedGroups()">${t('restoreSelected')}</button>
       <button class="ghost" onclick="clearRecycleSelection()">${t('selClear')}</button></span>`:''}`;
}
function toggleRecycleGroup(id){
  R_SELECTED.has(id)?R_SELECTED.delete(id):R_SELECTED.add(id);
  renderRecycle();
}
function clearRecycleSelection(){ R_SELECTED.clear(); renderRecycle(); }
function selectRecycleBody(groupId, uuid){
  const group=RECYCLE_BY_ID.get(groupId), body=group&&group.bodies.find(item=>item.uuid===uuid);
  if (!body) return;
  if (!RSEL || RSEL.uuid!==uuid) compExpanded={compList:false,rCompList:false,fsComp:false};
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
  renderComposition();
}
function clearRecycleDetail(){
  RSEL=RSELG=null;
  document.getElementById('rBody').innerHTML=`<div id="rDetailEmpty"><span class="big">♲</span><span>${t('pickRecycle')}</span></div>`;
  document.getElementById('rOps').style.display='none';
  if (VIEW==='recycle') { disposeMesh(); MESH_DATA=MESH_UUID=MESH_SOURCE=null; document.getElementById('pvInfo').textContent=''; }
}

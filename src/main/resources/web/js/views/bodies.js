'use strict';
/* 物理体视图:筛选/排序/页签/分组列表/多选 UI/详情面板/方块构成 */
let blockFilterIdx = -1;
function refreshBlockList(){
  if (!DATA) return;
  document.getElementById('blockList').innerHTML = DATA.block_palette.map(p =>
    `<option value="${esc(p.zh)} (${esc(p.id)})"></option>`).join('');
  onBlockFilter(true);
}
function onBlockFilter(skipRender){
  const v = document.getElementById('fBlock').value.trim().toLowerCase();
  blockFilterIdx = -1;
  const chip = document.getElementById('blockChip');
  if (v && DATA) {
    const mId = v.match(/\(([a-z0-9_:.\-]+)\)\s*$/);
    const needle = mId ? mId[1] : v;
    let hit = DATA.block_palette.findIndex(p => p.id === needle);
    if (hit < 0) hit = DATA.block_palette.findIndex(p =>
      p.id.includes(needle) || p.zh.toLowerCase().includes(needle) || p.en.toLowerCase().includes(needle));
    blockFilterIdx = hit;
    const p = hit >= 0 ? DATA.block_palette[hit] : null;
    chip.innerHTML = p ? `<span class="tag acc">${esc(p.zh)} · ${esc(p.id)}</span>` : '<span class="tag bad">?</span>';
    chip.style.display = 'block';
  } else chip.style.display = 'none';
  if (!skipRender) render();
}

/* ===================== 多条件排序 ===================== */
const SORT_KEYS = ['named','blocks','members','loaded','cost','rec','orphan','alpha'];
let sortCfg;
try { sortCfg = JSON.parse(localStorage.getItem('spSort2') || 'null') || [{k:'named',d:-1},{k:'blocks',d:-1}]; }
catch(e){ sortCfg = [{k:'named',d:-1},{k:'blocks',d:-1}]; }
function sortLabel(k){
  return {named:t('sNamed'), blocks:t('sBlocks'), members:t('sMembers'), loaded:t('sLoaded'),
    cost:t('sCost'), rec:t('sRec'), orphan:t('sOrphan'), alpha:t('sAlpha')}[k];
}
function renderSortRows(){
  const box = document.getElementById('sortRows');
  if (!box) return;
  box.innerHTML = sortCfg.map((s,i) => `<div class="sortRow">
      <select onchange="setSortKey(${i}, this.value)">
        ${SORT_KEYS.map(k=>`<option value="${k}" ${k===s.k?'selected':''}>${esc(sortLabel(k))}</option>`).join('')}
      </select>
      <button onclick="flipDir(${i})" title="asc/desc">${s.d<0?'↓':'↑'}</button>
      ${sortCfg.length>1?`<button class="ghost" onclick="delSort(${i})">✕</button>`:''}
    </div>`).join('');
  document.getElementById('addSort').style.display = sortCfg.length >= 4 ? 'none' : 'block';
}
function saveSort(){ localStorage.setItem('spSort2', JSON.stringify(sortCfg)); }
function setSortKey(i, k){ sortCfg[i].k = k; saveSort(); render(); }
function flipDir(i){ sortCfg[i].d *= -1; saveSort(); renderSortRows(); render(); }
function delSort(i){ sortCfg.splice(i,1); saveSort(); renderSortRows(); render(); }
function addSort(){
  const used = new Set(sortCfg.map(s=>s.k));
  const next = SORT_KEYS.find(k=>!used.has(k)) || 'blocks';
  sortCfg.push({k:next, d:-1}); saveSort(); renderSortRows(); render();
}
function sortVal(g, k){
  switch(k){
    case 'named': return g.name ? 1 : 0;
    case 'blocks': return g.blocks;
    case 'members': return g.members;
    case 'loaded': return g.loaded;
    case 'cost': return g.bodies.reduce((s,b)=>s+((b.runtime&&b.runtime.cost_ms)||0),0);
    case 'rec': return g.rec ? 1 : 0;
    case 'orphan': return g.orphans || 0;
    default: return null;
  }
}
function groupForced(g){ return g.bodies.some(b => FORCED.has(b.uuid)) ? 1 : 0; }
function cmpGroups(a, b){
  // 常驻加载的组恒置顶,不受用户排序配置影响
  const fa = groupForced(a), fb = groupForced(b);
  if (fa !== fb) return fb - fa;
  for (const s of sortCfg) {
    if (s.k === 'alpha') {
      const c = (a.name||'￿').localeCompare(b.name||'￿', 'zh');
      if (c) return c * (s.d<0?1:-1);
    } else {
      const va = sortVal(a,s.k), vb = sortVal(b,s.k);
      if (va !== vb) return (vb - va) * (s.d<0?1:-1);
    }
  }
  return b.blocks - a.blocks;
}

/* ===================== 页签 ===================== */
/* 人力不可达判定:整个包围盒都越过阈值(bug 飞出去的结构)。
   阈值来自服务端配置(voidBelowY/skyAboveY),不按建筑上限——航空服的飞艇本来就飞得高 */
function isVoid(b){ return (b.pos[1] + b.size[1]) < REACH.void_below; }
function isSky(b){ return b.pos[1] > REACH.sky_above; }

const TABS = [
  {k:'all',    label:'tabAll',     test:()=>true},
  {k:'fav',    label:'tabFav',     test:g=>FAV.has(g.gid)},
  {k:'named',  label:'tabNamed',   test:g=>!!g.name},
  {k:'unnamed',label:'tabUnnamed', test:g=>!g.name},
  {k:'rec',    label:'tabRec',     test:g=>!!g.rec},
  {k:'anom',   label:'tabAnom',    test:g=>g.orphans>0 || g.dup || g.clone},
  {k:'void',   label:'tabVoid',    test:g=>g.bodies.some(isVoid)},
  {k:'sky',    label:'tabSky',     test:g=>g.bodies.some(isSky)},
];
function tabTest(k){ return (TABS.find(x=>x.k===k)||TABS[0]).test; }
function renderTabs(){
  document.getElementById('tabs').innerHTML = TABS.map(tb => {
    const n = DATA.groups.filter(tb.test).length;
    const style = TAB!==tb.k && n ? (tb.k==='rec' ? 'style="color:var(--warn)"' : tb.k==='anom' ? 'style="color:var(--bad)"' : '') : '';
    return `<button class="${TAB===tb.k?'on':''}" onclick="setTab('${tb.k}')" ${style}>${t(tb.label)}<span class="cnt">${n}</span></button>`;
  }).join('');
}
function setTab(k){ TAB = k; renderLimit = 400; renderTabs(); render(); }

/* ===================== 列表 ===================== */
function sizeClass(b){ return b>=10000?'huge':b>=1000?'large':b>=100?'mid':b>=10?'small':'frag'; }
function checked(cls){ return [...document.querySelectorAll('.'+cls+':checked')].map(x=>x.value); }
const STATE_DOT = {loaded:'var(--ok)', stored:'#495468', holding:'var(--warn)', orphan:'var(--bad)'};
let renderLimit = 400;
let lastVisibleGroups = [];
/* 目的坐标输入框已预填过的体:同一个体的周期刷新不再覆盖用户输入 */
let tpFilledFor = null;

/* 处理中徽章:以 /api/bodies 的 busy 为单一事实源,显示阶段和已耗时。
   巨型体的操作可能跑几分钟,这个徽章就是"看得见在动"的全部意义所在 */
function busyTag(uuid){
  const job = BUSY.get(uuid);
  if (!job) return '';
  const secs = Math.max(0, Math.round((Date.now() - job.since) / 1000));
  const label = job.state === 'queued' ? t('jobQueued') : (job.phase || job.op);
  return `<span class="tag busy" title="${esc(job.op)}"><i class="spin"></i>${esc(label)} ${secs}s</span>`;
}

/* 收藏以依赖组为单位(按组根 uuid 存),避免组内个别成员收藏造成状态歧义 */
function toggleFav(gid){
  FAV.has(gid) ? FAV.delete(gid) : FAV.add(gid);
  saveFav();
  renderTabs(); render();
  if (SELG && SELG.gid === gid) renderDetail();
}

function cloneSetOf(body){ return body && body.clone_set !== undefined ? CLONE_SETS.get(Number(body.clone_set)) : null; }
function clonePeerCount(body){ const set=cloneSetOf(body); return set ? Math.max(0,(set.members||[]).length-1) : 0; }

function render() {
  if (!DATA || VIEW !== 'bodies') return;
  const states = checked('fState'), sizes = checked('fSize'), dims = checked('fDim');
  const search = document.getElementById('fSearch').value.toLowerCase();
  const namedOnly = document.getElementById('fNamedOnly').checked;
  const groupOnly = document.getElementById('fGroupOnly').checked;
  const dupMode = document.getElementById('fDup').value;
  const blkIdx = blockFilterIdx;
  const blkActive = document.getElementById('fBlock').value.trim() !== '';
  const list = document.getElementById('list');
  const inTab = tabTest(TAB);
  list.innerHTML = '';

  const bodyPass = (b, g) =>
    states.includes(b.state) && sizes.includes(sizeClass(b.blocks)) && dims.includes(b.dim)
    && (!namedOnly || b.name)
    && (dupMode==='all' || (dupMode==='dup' && b.copies) || (dupMode==='clone' && b.clone) || (dupMode==='any' && (b.copies||b.clone)))
    && (!blkActive || (blkIdx >= 0 && b.blk && b.blk.includes(blkIdx)))
    && (!search || (b.name||'').toLowerCase().includes(search) || b.uuid.includes(search) || (g.name||'').toLowerCase().includes(search));

  const groups = [...DATA.groups].sort(cmpGroups);
  let shown = 0, matchedGroups = 0;
  lastVisibleGroups = [];
  for (const g of groups) {
    if (!inTab(g)) continue;
    if (groupOnly && g.members < 2) continue;
    const vis = g.bodies.filter(b => bodyPass(b, g));
    if (!vis.length) continue;
    matchedGroups++;
    lastVisibleGroups.push(g);
    if (shown >= renderLimit) continue;
    shown++;
    const div = document.createElement('div');
    const forcedN = g.bodies.filter(b=>FORCED.has(b.uuid)).length;
    div.className = 'group' + (forcedN ? ' is-forced' : g.orphans ? ' is-orphan' : g.rec ? ' is-rec' : '');
    div.dataset.gid = g.gid;
    const tags = [];
    const busyMember = g.bodies.find(b => BUSY.has(b.uuid));
    if (busyMember) { div.classList.add('is-busy'); tags.push(busyTag(busyMember.uuid)); }
    if (forcedN) tags.push(`<span class="tag forced" title="${t('forcedTag')}">${t('forcedBadge')}${forcedN>1?' ×'+forcedN:''}</span>`);
    const pausedN = g.bodies.filter(b=>PAUSED.has(b.uuid)).length;
    if (pausedN) tags.push(`<span class="tag warn">⏸${pausedN>1?'×'+pausedN:''}</span>`);
    if (g.rec) tags.push(`<span class="tag warn">${t('recTag')}</span>`);
    if (g.members > 1) tags.push(`<span class="tag acc">${g.members} ${t('combo')}</span>`);
    if (g.loaded) tags.push(`<span class="tag ok"><i class="dot" style="background:var(--ok)"></i>${g.loaded}</span>`);
    if (g.holding) tags.push(`<span class="tag warn">${t('holdingX')}×${g.holding}</span>`);
    if (g.orphans) tags.push(`<span class="tag bad">${t('orphanX')}×${g.orphans}</span>`);
    if (g.dup) tags.push(`<span class="tag warn">${t('copiesX')}</span>`);
    if (g.clone) tags.push(`<span class="tag clone">${t('cloneTag')}</span>`);
    const gcost = sortVal(g,'cost');
    if (gcost > 0) tags.push(`<span class="tag acc mono">${gcost.toFixed(2)} ms/t</span>`);
    tags.push(`<span class="tag mono">${fmt(g.blocks)} ${t('blocksUnit')}</span>`);
    tags.push(`<span class="tag">${esc(g.dims.replace(/minecraft:/g,''))}</span>`);
    // 组内成员被截断时禁止整组选择:复选框看着是"选中整组",实际只选中已发下来的那部分,
    // 而后端删除会按依赖链重新展开成完整组 —— 确认数和真实动作对不上
    const partial = g.members_omitted > 0;
    if (partial) tags.push(
      `<span class="tag warn" title="${t('groupPartialTip')(g.members_omitted)}">${t('groupPartial')}</span>`);
    div.innerHTML = `<div class="ghead">
        <input type="checkbox" class="gsel" ${partial ? `disabled title="${t('groupPartialTip')(g.members_omitted)}"` : ''}>
        <span class="caret">▶</span>
        <span class="favStar ${FAV.has(g.gid)?'on':''}" title="${t('favTip')}">${FAV.has(g.gid)?'★':'☆'}</span>
        <span class="gname">${esc(g.name) || '<span class="muted">'+t('unnamed')+'</span>'}</span>
        ${tags.join('')}
      </div><div class="members"></div>`;
    const mem = div.querySelector('.members');
    const caret = div.querySelector('.caret');
    div.querySelector('.gsel').onclick = e => { e.stopPropagation(); toggleSelGroup(g.bodies); };
    div.querySelector('.favStar').onclick = e => { e.stopPropagation(); toggleFav(g.gid); };
    for (const b of vis) {
      const m = document.createElement('div'); m.className = 'member'; m.dataset.uuid = b.uuid;
      if (SEL && SEL.uuid === b.uuid) m.classList.add('sel');
      const extra = [];
      if (BUSY.has(b.uuid)) { m.classList.add('is-busy'); extra.push(busyTag(b.uuid)); }
      if (isVoid(b)) extra.push(`<span class="tag bad" title="${t('voidTag')(REACH.void_below)}">${t('voidBadge')}</span>`);
      if (isSky(b)) extra.push(`<span class="tag warn" title="${t('skyTag')(REACH.sky_above)}">${t('skyBadge')}</span>`);
      if (FORCED.has(b.uuid)) extra.push(`<span class="tag forced" title="${t('forcedTag')}">${t('forcedBadge')}</span>`);
      if (PAUSED.has(b.uuid)) extra.push(`<span class="tag warn" title="${t('pausedTag')}">⏸</span>`);
      if (b.copies) extra.push(`<span class="tag warn">${b.copies} ${t('copiesX')}</span>`);
      if (b.clone) extra.push(`<span class="tag clone">${t('cloneWith')(clonePeerCount(b))}</span>`);
      if (b.deps) extra.push(`<span class="tag">${t('depsX')} ${b.deps}</span>`);
      if (b.runtime && b.runtime.cost_ms !== undefined)
        extra.push(`<span class="tag acc mono">${b.runtime.cost_ms.toFixed(2)} ms/t</span>`);
      m.innerHTML = `<input type="checkbox" class="msel">
        <i class="dot" style="background:${STATE_DOT[b.state]}" title="${stateLabel(b.state)}"></i>
        <span class="mname">${esc(b.name) || '<span class="muted mono">'+b.uuid.slice(0,8)+'</span>'}</span>
        ${extra.join('')}
        <span class="num">${fmt(b.blocks)} ${t('blocksUnit')}</span>
        <span class="num">${b.pos.map(v=>v|0).join(', ')}</span>`;
      m.onclick = () => select(b, g);
      m.querySelector('.msel').onclick = e => { e.stopPropagation(); toggleSel(b.uuid); };
      mem.appendChild(m);
    }
    div.querySelector('.ghead').onclick = () => {
      const open = mem.style.display === 'block';
      mem.style.display = open ? 'none' : 'block';
      caret.textContent = open ? '▶' : '▼';
      EXPAND_STATE.set(g.gid, !open);
    };
    // 用户显式折叠/展开过的组永远遵从用户;没碰过的才用默认策略(单成员组或含选中体的组展开)
    const expandPref = EXPAND_STATE.get(g.gid);
    if (expandPref !== undefined ? expandPref
        : ((vis.length === 1 && g.members === 1) || (SEL && vis.some(b=>b.uuid===SEL.uuid)))) {
      mem.style.display = 'block'; caret.textContent = '▼';
    }
    list.appendChild(div);
  }
  if (!shown) {
    list.innerHTML = `<div id="listEmpty"><span class="big">${TAB==='rec'?'✓':'⬡'}</span>${TAB==='rec'?t('recNone'):t('noMatch')}</div>`;
  } else if (matchedGroups > shown) {
    const btn = document.createElement('button');
    btn.id = 'moreBtn';
    btn.textContent = t('showMore')(matchedGroups - shown);
    btn.onclick = () => { renderLimit += 800; render(); };
    list.appendChild(btn);
  }
  renderToolbar(matchedGroups, DATA.groups.filter(inTab).length);
  updateSelUI();
}

function renderToolbar(matched, tabTotal){
  const parts = [`<span>${t('filterBar')(matched, tabTotal)}</span>`];
  // 服务端对单次响应有硬上限。三种截断后果不同,分开说 —— 从前一律套"只显示 N / M 组"的
  // 模板,单个巨型组被按成员截断时会显示成自相矛盾的"只显示 3000 / 1 组"
  if (DATA && DATA.truncated) {
    const why = [];
    if (DATA.shown_groups < DATA.total_groups) why.push(t('bodiesTruncated')(DATA.shown_groups, DATA.total_groups));
    if (DATA.omitted_members) why.push(t('bodiesMembersOmitted')(DATA.omitted_members));
    if (DATA.palette_truncated) why.push(t('bodiesPaletteTruncated'));
    parts.push(`<span style="color:var(--warn)">· ${why.join(' · ')}</span>`);
  }
  if (matched < tabTotal) parts.push(`<span style="color:var(--warn)">· ${t('filterActive')}</span>
    <button class="warnb" onclick="resetFilters()">${t('resetFilters')}</button>`);
  parts.push(`<button onclick="expandAll(true)">${t('expandAll')}</button>`);
  parts.push(`<button onclick="expandAll(false)">${t('collapseAll')}</button>`);
  parts.push(`<span id="selSeg"></span>`);
  if (TAB === 'rec' && lastVisibleGroups.length) {
    const bodies = lastVisibleGroups.reduce((s,g)=>s+g.members, 0);
    parts.push(`<button class="warnb" style="margin-left:auto" onclick="doDeleteRecommended()">${t('recBatch')(lastVisibleGroups.length, bodies)}</button>`);
    parts.push(`<div style="flex-basis:100%;font-size:11px;line-height:1.55;color:var(--dim);padding-top:3px">${t('recSafe')(recPolicy())}</div>`);
  }
  document.getElementById('toolbar').innerHTML = parts.join(' ');
}
function expandAll(open){
  document.querySelectorAll('#list .group').forEach(div => {
    div.querySelector('.members').style.display = open ? 'block' : 'none';
    div.querySelector('.caret').textContent = open ? '▼' : '▶';
    if (div.dataset.gid) EXPAND_STATE.set(div.dataset.gid, open);
  });
}
function resetFilters(silent){
  document.getElementById('fSearch').value = '';
  document.getElementById('fBlock').value = '';
  document.getElementById('fNamedOnly').checked = false;
  document.getElementById('fGroupOnly').checked = false;
  document.getElementById('fDup').value = 'all';
  document.querySelectorAll('.fState,.fSize,.fDim').forEach(x => x.checked = true);
  renderLimit = 400;
  if (!silent) onBlockFilter();
}
/* ===================== 多选 ===================== */
function toggleSel(u){
  const entry=BODY_BY_UUID.get(u);
  // 整组切换的前提是 g.bodies 就是全组。组被截断时它只是"已下发的那部分",
  // 再按整组切就等于让暂停/常驻/收养作用在可见成员上,而不是用户点的那一个 ——
  // 组头复选框在这种组上是禁用的,成员复选框就退回单选
  const bodies=entry&&!(entry.g.members_omitted>0)?entry.g.bodies:[{uuid:u}];
  const all=bodies.every(body=>SELECTED.has(body.uuid));
  for (const body of bodies) all?SELECTED.delete(body.uuid):SELECTED.add(body.uuid);
  updateSelUI();
}
function toggleSelGroup(bodies){
  // 依赖组是删除原子单位，组头和成员复选框都整组切换。
  const all = bodies.every(b => SELECTED.has(b.uuid));
  for (const b of bodies) all ? SELECTED.delete(b.uuid) : SELECTED.add(b.uuid);
  updateSelUI();
}
function clearSel(){ SELECTED.clear(); updateSelUI(); }
function updateSelUI(){
  document.querySelectorAll('#list .group').forEach(div => {
    let on = 0, all = 0;
    div.querySelectorAll('.msel').forEach(cb => {
      cb.checked = SELECTED.has(cb.closest('.member').dataset.uuid);
      if (cb.checked) on++;
      all++;
    });
    const g = div.querySelector('.gsel');
    if (g) { g.checked = on > 0 && on === all; g.indeterminate = on > 0 && on < all; }
  });
  const seg = document.getElementById('selSeg');
  if (!seg) return;
  if (!SELECTED.size) { seg.innerHTML = ''; return; }
  let blocks = 0, orphans = 0, pausedN = 0, runningN = 0, forcedN = 0, freeN = 0;
  for (const u of SELECTED) {
    const e = BODY_BY_UUID.get(u);
    if (e) { blocks += e.b.blocks; if (e.b.state === 'orphan') orphans++; }
    if (PAUSED.has(u)) pausedN++; else runningN++;
    if (FORCED.has(u)) forcedN++; else freeN++;
  }
  seg.innerHTML = `<span class="selInfo">${t('selInfo')(SELECTED.size, blocks)}</span>
    <button class="danger" onclick="doDeleteSelected()">${t('selDel')}</button>
    ${freeN ? `<button class="primary" onclick="doForceSelected(true)">${t('selForce')(freeN)}</button>` : ''}
    ${forcedN ? `<button onclick="doForceSelected(false)">${t('selUnforce')(forcedN)}</button>` : ''}
    ${runningN ? `<button class="warnb" onclick="doPauseSelected(true)">${t('selPause')(runningN)}</button>` : ''}
    ${pausedN ? `<button onclick="doPauseSelected(false)">${t('selResume')(pausedN)}</button>` : ''}
    ${orphans ? `<button onclick="doAdoptSelected()">${t('selAdopt')(orphans)}</button>` : ''}
    <button class="ghost" onclick="clearSel()">${t('selClear')}</button>`;
}
function focusBody(uuid){
  setView('bodies', {tab:'all', reset:true});
  const found = DATA.groups.find(g => g.bodies.some(b => b.uuid === uuid));
  if (!found) return;
  select(found.bodies.find(b=>b.uuid===uuid), found);
  setTimeout(()=>document.querySelector(`.member[data-uuid="${uuid}"]`)?.scrollIntoView({block:'center'}), 60);
}
/* ===================== 详情 ===================== */
function select(b, g) {
  const sameMesh = SEL && SEL.uuid === b.uuid;
  if (!sameMesh) compExpanded = {compList:false, rCompList:false, fsComp:false};
  SEL = b; SELG = g;
  document.querySelectorAll('.member.sel').forEach(x=>x.classList.remove('sel'));
  document.querySelector(`.member[data-uuid="${b.uuid}"]`)?.classList.add('sel');
  renderDetail();
  if (!sameMesh) loadMesh(b.uuid);
}
function stateLabel(s){ return s==='loaded'?t('stateLoaded'):s==='orphan'?t('stateOrphan'):s==='holding'?t('stateHolding'):t('stateStored'); }
function reasonLabel(r){ return t('r' + r[0].toUpperCase() + r.slice(1)); }
function protLabel(p){ return t('p' + p[0].toUpperCase() + p.slice(1)); }
function recPolicy(){ return (DATA && DATA.rec_policy) || {blocks:20, types:4, be:3}; }
function cloneDetail(body){
  const set = cloneSetOf(body);
  if (!set) return '';
  const reason = set.mode === 'named' ? t('cloneNamedReason') : t('cloneUnnamedReason');
  const size = (set.rounded_size || []).join(' × ');
  const peers = (set.members || []).filter(uuid=>uuid!==body.uuid).map(uuid=>BODY_BY_UUID.get(uuid)).filter(Boolean);
  return `<div class="cloneReason">${reason}<br>${fmt(set.blocks||0)} ${t('blocksUnit')} · ${esc(size)}</div>` +
    peers.map(({b})=>`<button class="clonePeer" onclick="focusBody('${b.uuid}')">
      <span><b>${esc(b.name)||t('unnamed')}</b><small class="mono">${b.uuid}</small></span>
      <span>${esc((b.dim||'').replace('minecraft:',''))}<br>${(b.pos||[0,0,0]).map(v=>Number(v).toFixed(0)).join(', ')}</span>
    </button>`).join('');
}
function renderDetail(){
  const b = SEL, g = SELG;
  if (!b) return;
  // 该体有作业在跑时整片操作区禁用:重复提交后端会 409 挡掉,但按钮先灰掉更诚实
  const ops = document.getElementById('ops');
  if (ops) {
    const job = BUSY.get(b.uuid);
    ops.classList.toggle('is-busy', !!job);
    ops.querySelectorAll('button').forEach(btn => btn.disabled = !!job);
  }
  const rt = b.runtime || {};
  const live = rt.x !== undefined;
  const pos = live ? [rt.x, rt.y, rt.z] : b.pos;
  const rows = [];
  const favBtn = `<button class="copyBtn favStar ${FAV.has(g.gid)?'on':''}" title="${t('favTip')}" onclick="toggleFav('${g.gid}')">${FAV.has(g.gid)?'★':'☆'}</button>`;
  rows.push([t('name'), (esc(b.name) || `<span class="muted">${t('unnamed')}</span>`) + favBtn]);
  rows.push(['UUID', `<span class="val" style="font-size:10.5px">${b.uuid}</span><button class="copyBtn" onclick="copyText('${b.uuid}')">⧉</button>`]);
  rows.push([t('state'), `<i class="dot" style="background:${STATE_DOT[b.state]};margin-right:6px"></i>${stateLabel(b.state)}`
    + (PAUSED.has(b.uuid) ? ` <span class="tag warn">⏸ ${t('pausedTag')}</span>` : '')]);
  rows.push([t('dim'), esc(b.dim)]);
  rows.push([t('coord'), `<span class="val">${pos.map(v=>Number(v).toFixed(1)).join(', ')}</span> <span class="tag ${live?'ok':''}">${live?t('rtLive'):t('rtSaved')}</span>`]);
  if (rt.cost_ms !== undefined) rows.push([t('costRow'),
    `<span class="val" style="color:var(--acc)">${t('costVal')(rt.cost_ms.toFixed(3))}</span>` +
    `<div class="muted" style="font-size:10.5px;line-height:1.45">${t('costHint')}</div>`]);
  if (rt.lin_vel !== undefined) rows.push([t('vel'), `<span class="val">${rt.lin_vel} m/s</span>`]);
  if (rt.mass !== undefined) rows.push([t('mass'), `<span class="val">${fmt(rt.mass)}</span>`]);
  if (rt.players !== undefined) rows.push([t('players'), `<span class="val">${rt.players}</span>`]);
  rows.push([t('bbox'), `<span class="val">${b.size.map(v=>Number(v).toFixed(1)).join(' × ')}</span>`]);
  rows.push([t('blockCount'), `<span class="val">${fmt(b.blocks)}</span>`]);
  if (b.be) rows.push([t('beRow'), `<span class="val">${fmt(b.be)}</span>`]);
  if (b.contents) rows.push([t('contentsRow'),
    `<span class="val" style="color:var(--warn)">${fmt(b.contents)}</span>` +
    `<div class="muted" style="font-size:10.5px;line-height:1.45">${t('contentsHint')}</div>`]);
  if (b.copies) {
    const entries = (b.entries||[]).map(e=>`<div class="val" style="font-size:10.5px">${esc(e)}</div>`).join('');
    rows.push([t('copiesRow'), `${b.copies} ${t('copiesShow')}${entries}`]);
  }
  if (b.clone) rows.push([t('cloneRelation'), cloneDetail(b)]);
  if (b.deps) rows.push([t('deps'), b.deps]);
  rows.push([t('group'), t('groupVal')(g.members, g.blocks)]);
  if (g.rec) rows.push([t('recWhy'),
    `<span class="tag warn">${t('recTag')}</span> ` + g.rec.reasons.map(r=>esc(reasonLabel(r))).join('、')]);
  else if (g.prot && g.prot.length) rows.push([t('protWhy'),
    `<span class="tag ok">${t('protTag')}</span> ` + g.prot.map(p=>esc(protLabel(p))).join('、')]);
  rows.push([t('entry'), `<span class="val" style="font-size:10.5px">${esc(b.entry)}</span>`]);
  document.getElementById('dbody').innerHTML =
    `<table>${rows.map(r=>`<tr><td>${r[0]}</td><td>${r[1]}</td></tr>`).join('')}</table><div id="compList"></div>`;
  renderComposition();
  document.getElementById('ops').style.display = 'block';
  // 目的坐标与当前坐标分离:仅在切换选中体时预填一次,周期刷新不覆盖用户输入
  if (tpFilledFor !== b.uuid) {
    tpFilledFor = b.uuid;
    document.getElementById('tx').value = pos[0]|0;
    document.getElementById('ty').value = pos[1]|0;
    document.getElementById('tz').value = pos[2]|0;
  }
  document.getElementById('adoptRow').style.display = b.state === 'orphan' ? 'flex' : 'none';
  document.getElementById('dedupeBtn').style.display = b.copies > 1 ? '' : 'none';
  document.getElementById('delBodyBtn').textContent = g.members > 1 ? t('delGroup')(g.members) : t('delBody');
  const pauseBtn = document.getElementById('pauseBtn');
  pauseBtn.textContent = PAUSED.has(b.uuid) ? t('resumeBody') : t('pauseBody');
  pauseBtn.classList.toggle('warnb', !PAUSED.has(b.uuid));
  const forceBtn = document.getElementById('forceBtn');
  forceBtn.textContent = FORCED.has(b.uuid) ? t('unforceBody') : t('forceBody');
  forceBtn.classList.toggle('primary', !FORCED.has(b.uuid));
  forceBtn.title = t('forceHint');
  loadPlayers();
}
/* 把当前坐标填进目的坐标输入框(想基于当前位置微调时用) */
function fillCurrentPos(){
  if (!SEL) return;
  const rt = SEL.runtime || {};
  const pos = rt.x !== undefined ? [rt.x, rt.y, rt.z] : SEL.pos;
  document.getElementById('tx').value = pos[0]|0;
  document.getElementById('ty').value = pos[1]|0;
  document.getElementById('tz').value = pos[2]|0;
}
/* 传送玩家下拉:保留已选玩家,列表为空时禁用按钮 */
function renderPlayerSelect(){
  const sel = document.getElementById('tpPlayer');
  if (!sel) return;
  const cur = sel.value;
  sel.innerHTML = PLAYERS.length
    ? PLAYERS.map(p=>`<option value="${p.uuid}" ${p.uuid===cur?'selected':''}>${esc(p.name)}</option>`).join('')
    : `<option value="">${t('tpNoPlayers')}</option>`;
  sel.disabled = !PLAYERS.length;
  const btn = document.getElementById('tpPlayerBtn');
  if (btn) btn.disabled = !PLAYERS.length;
}
/* 成分表默认只列前 30 种,点"展开"看全部;切体时复位 */
const COMP_PAGE = 30;
let compExpanded = {compList:false, rCompList:false, copyComp:false, fsComp:false};
function toggleComp(id){ compExpanded[id] = !compExpanded[id]; renderComposition(); }
function renderComposition(){
  const selected = VIEW==='recycle' ? RSEL : SEL;
  for (const box of [document.getElementById('compList'), document.getElementById('rCompList'),
    document.getElementById('copyComp'), document.getElementById('fsComp')]) {
    if (!box) continue;
    if (!MESH_DATA || !selected || MESH_UUID !== selected.uuid) { box.innerHTML = ''; continue; }
    const pal = [...MESH_DATA.palette].sort((a,b)=>b.count-a.count);
    const total = pal.reduce((s,p)=>s+p.count,0) || 1;
    const open = compExpanded[box.id];
    const shown = open ? pal : pal.slice(0, COMP_PAGE);
    const rest = pal.length - shown.length;
    box.innerHTML = (box.id==='compList'||box.id==='rCompList'||box.id==='copyComp'
      ? `<h4>${t('composition')}${MESH_DATA.truncated?t('pvTrunc'):''}</h4>` : '') +
      shown.map(p => `<div class="compRow">
        <span class="chip" style="background:#${(p.color>>>0).toString(16).padStart(6,'0')}"></span>
        <span class="cname" title="${esc(p.id)}">${esc(p.zh)}</span>
        <span class="cnum">${fmt(p.count)} · ${(p.count/total*100).toFixed(1)}%</span>
      </div>`).join('') +
      (rest > 0 ? `<div class="compRow compMore" onclick="toggleComp('${box.id}')">▾ ${t('compMore')(rest)}</div>`
       : open && pal.length > COMP_PAGE ? `<div class="compRow compMore" onclick="toggleComp('${box.id}')">▴ ${t('compLess')}</div>` : '');
  }
}

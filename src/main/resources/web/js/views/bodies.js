'use strict';
/* 物理体视图:页签+chips 筛选/排序弹层/依赖组卡片网格/全屏详情页(#body/<uuid> 深链)/多选/方块构成。
   详情态以 DETAIL_UUID 为唯一事实源,SEL/SELG 每次 render 由 BODY_BY_UUID 重新派生 ——
   快照 60s 刷新后旧对象作废,攥引用会让详情页显示上一份数据(与 data.js 的 reselect 同一约定)。 */
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

/* ===================== 顶部弹层(排序/更多筛选) ===================== */
function togglePop(id){
  for (const pid of ['sortPop','mfPop','rCfgPop']) {
    const el = document.getElementById(pid);
    if (el) el.classList.toggle('open', pid === id ? !el.classList.contains('open') : false);
  }
}
document.addEventListener('click', e => {
  if (!e.target.closest('.popWrap')) togglePop('');
});

/* ===================== 卡片尺寸档位 =====================
   纯 CSS 变量换挡(#list[data-size]),不重渲;脚本在 body 尾部加载,DOM 已就绪 */
let cardSize;
try { cardSize = localStorage.getItem('spCardSize') || 'm'; } catch(e){ cardSize = 'm'; }
if (!['s','m','l'].includes(cardSize)) cardSize = 'm';
function applyCardSize(){
  // 物理体与回收站两张网格共用同一档位(spCardSize),两处分段控件状态同步
  for (const id of ['list','rList']) {
    const el = document.getElementById(id);
    if (el) el.dataset.size = cardSize;
  }
  for (const segId of ['sizeSeg','rSizeSeg']) {
    const seg = document.getElementById(segId);
    if (seg) for (const b of seg.querySelectorAll('button'))
      b.classList.toggle('on', b.dataset.size === cardSize);
  }
}
function setCardSize(s){
  cardSize = s;
  try { localStorage.setItem('spCardSize', s); } catch(e){}
  applyCardSize();
}
applyCardSize();

/* ===================== 多条件排序 ===================== */
const SORT_KEYS = ['named','blocks','members','loaded','cost','rec','orphan','alpha'];
let sortCfg;
try { sortCfg = JSON.parse(localStorage.getItem('spSort2') || 'null') || [{k:'named',d:-1},{k:'blocks',d:-1}]; }
catch(e){ sortCfg = [{k:'named',d:-1},{k:'blocks',d:-1}]; }
function sortLabel(k){
  return {named:T.sNamed, blocks:T.sBlocks, members:T.sMembers, loaded:T.sLoaded,
    cost:T.sCost, rec:T.sRec, orphan:T.sOrphan, alpha:T.sAlpha}[k];
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
/* 快照派生的几块:维度 chips、方块清单、扫描信息、方块徽标。
   只在"快照换了"(loadBodies)和"快照没了"(renderAll)时重建 —— 不能挂到 render() 上
   每次都跑:那批 .fDim input 自己带着 onchange="render()",重画会在事件中途把触发它的元素
   销毁掉,搜索框的焦点也会被吃。 */
function renderBodiesMeta(){
  if (!DATA) {
    document.getElementById('fDims').innerHTML = '';
    document.getElementById('blockList').innerHTML = '';
    document.getElementById('scanMeta').innerHTML = '';
    blockFilterIdx = -1;
    document.getElementById('blockChip').style.display = 'none';
    return;
  }
  const dims = new Set();
  DATA.groups.forEach(g => g.bodies.forEach(b => dims.add(b.dim)));
  const prevChecked = new Set([...document.querySelectorAll('.fDim:checked')].map(x=>x.value));
  const hadAny = document.querySelectorAll('.fDim').length > 0;
  document.getElementById('fDims').innerHTML = [...dims].map(d =>
    `<label class="fchip"><input type="checkbox" class="fDim" value="${esc(d)}" ${(!hadAny || prevChecked.has(d))?'checked':''} onchange="render()"><span>${esc(d.replace('minecraft:',''))}</span></label>`).join('');
  document.getElementById('scanMeta').innerHTML =
    `${fmt(DATA.total_bodies)} ${T.bodies} · ${fmt(DATA.total_entries)} ${T.entries}<br>${T.scanAt} ${fmtTime(DATA.scan_time)}`;
  refreshBlockList();
}
function renderTabs(){
  if (!DATA) return;   // 分发是全函数,没数据时这里没什么可画,由 render() 去说明原因
  document.getElementById('tabs').innerHTML = TABS.map(tb => {
    const n = DATA.groups.filter(tb.test).length;
    const style = TAB!==tb.k && n ? (tb.k==='rec' ? 'style="color:var(--warn)"' : tb.k==='anom' ? 'style="color:var(--bad)"' : '') : '';
    return `<button class="${TAB===tb.k?'on':''}" onclick="setTab('${tb.k}')" ${style}>${T[tb.label]}<span class="cnt">${n}</span></button>`;
  }).join('');
}
function setTab(k){ TAB = k; renderLimit = 400; renderTabs(); render(); }

/* ===================== 详情态(全屏页 + #body/<uuid> 深链) ===================== */
function parseBodyHash(h){
  const m = /^#body\/([0-9a-fA-F-]{8,36})$/.exec(h || '');
  return m ? m[1] : null;
}
let DETAIL_UUID = null;               // 详情页唯一事实源;SEL/SELG 由它派生
/* 深链意图不存变量,渲染时直接读 location.hash 派生 —— 登录门/认证流程会走一次
   resetServerContext,攥在变量里的意图会被启动流程清掉,URL 本身才活得过登录 */
let HASH_MISS = null;                 // 已解析且不存在的 hash,防止每次 render 重复 toast
let DETAIL_PUSHED = false;            // 详情项是否由本页 push 进历史(决定返回走 back 还是清 hash)
let browseScroll = 0;
if (parseBodyHash(location.hash)) VIEW = 'bodies';   // 带深链启动时无视上次停留的视图
function stripHash(){
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);
}
function syncDetailVisibility(showDetail){
  document.getElementById('bBrowse').style.display = showDetail ? 'none' : 'flex';
  document.getElementById('bDetail').style.display = showDetail ? 'flex' : 'none';
}
/* 返回列表:user=true 是点面包屑(尽量走 history.back 让"前进"还能回来),false 是被动收敛 */
function exitDetail(user){
  const pushed = DETAIL_PUSHED;
  DETAIL_UUID = null; DETAIL_PUSHED = false;
  SEL = null; SELG = null; tpFilledFor = null;
  disposeMesh(); MESH_DATA = null; MESH_UUID = MESH_SOURCE = null;
  const info = document.getElementById('pvInfo'); if (info) info.textContent = '';
  if (user && pushed) history.back(); else stripHash();
  render();
  requestAnimationFrame(() => { const l = document.getElementById('list'); if (l) l.scrollTop = browseScroll; });
}
window.addEventListener('hashchange', () => {
  HASH_MISS = null;
  const u = parseBodyHash(location.hash);
  if (VIEW !== 'bodies') { if (u) setView('bodies'); return; }
  if (!u && DETAIL_UUID) exitDetail(false);
  else if (u && u !== DETAIL_UUID) { DETAIL_UUID = null; render(); }
});

/* ===================== 列表 ===================== */
function sizeClass(b){ return b>=10000?'huge':b>=1000?'large':b>=100?'mid':b>=10?'small':'frag'; }
const STATE_DOT = {loaded:'var(--ok)', stored:'var(--dim)', holding:'var(--warn)', orphan:'var(--bad)'};
let renderLimit = 400;
let lastVisibleGroups = [];
/* 目的坐标输入框已预填过的体:同一个体的周期刷新不再覆盖用户输入 */
let tpFilledFor = null;

/* 本视图的输入记忆与详情态,切服归零 */
onServerReset(() => {
  tpFilledFor = null;
  DETAIL_UUID = null; DETAIL_PUSHED = false; HASH_MISS = null; browseScroll = 0;
  // 不动 location.hash:登录门/认证流程也会走到这里,清了 URL 深链就活不过登录。
  // 切服后 hash 指向的体在新服不存在时,由 render 的派生逻辑 toast 并清除
  document.getElementById('dbody').innerHTML = '';
  const members = document.getElementById('dMembers');
  members.innerHTML = ''; members.style.display = 'none';
  document.getElementById('ops').style.display = 'none';
});

/* 处理中徽章:以 /api/jobs?poll=1 的 running[] 为单一事实源,显示阶段和已耗时。
   巨型体的操作可能跑几分钟,这个徽章就是"看得见在动"的全部意义所在 */
function busyTag(uuid){
  const job = BUSY.get(uuid);
  if (!job) return '';
  const secs = Math.max(0, Math.round((Date.now() - job.since) / 1000));
  const label = job.state === 'queued' ? T.jobQueued : (job.phase || job.op);
  return `<span class="tag busy" data-busy="${uuid}" title="${esc(job.op)}"><i class="spin"></i>${esc(label)} ${secs}s</span>`;
}
function refreshBusyLabels(){
  for (const tag of document.querySelectorAll('[data-busy]')) {
    const job = BUSY.get(tag.dataset.busy);
    if (!job) continue;
    const secs = Math.max(0, Math.round((Date.now() - job.since) / 1000));
    const label = job.state === 'queued' ? T.jobQueued : (job.phase || job.op);
    tag.title = job.op;
    tag.innerHTML = `<i class="spin"></i>${esc(label)} ${secs}s`;
  }
}

/* 收藏以依赖组为单位(按组根 uuid 存),避免组内个别成员收藏造成状态歧义 */
function toggleFav(gid){
  FAV.has(gid) ? FAV.delete(gid) : FAV.add(gid);
  saveFav();
  renderTabs(); render();
}

function cloneSetOf(body){ return body && body.clone_set !== undefined ? CLONE_SETS.get(Number(body.clone_set)) : null; }
function clonePeerCount(body){ const set=cloneSetOf(body); return set ? Math.max(0,(set.members||[]).length-1) : 0; }

/* 外部加载 = 已加载 ∧ 非面板常驻(定义,不是探测):区块加载器/其他票/玩家在附近都算。
   取消常驻对这类体无效,不标出来"取消了为什么还在跑"无迹可循(create_power_loader 实案) */
function externalKept(b){ return b.state === 'loaded' && !FORCED.has(b.uuid); }

/* 组级徽章(卡片和详情页共用口径);skipMeta=true 时省去方块数/维度(卡片另有专位) */
function groupTags(g){
  const tags = [];
  const busyMember = g.bodies.find(b => BUSY.has(b.uuid));
  if (busyMember) tags.push(busyTag(busyMember.uuid));
  const forcedN = g.bodies.filter(b=>FORCED.has(b.uuid)).length;
  if (forcedN) tags.push(`<span class="tag forced" title="${T.forcedTag}">${T.forcedBadge}${forcedN>1?' ×'+forcedN:''}</span>`);
  const lostN = g.bodies.filter(b=>FORCED_LOST.has(b.uuid)).length;
  if (lostN) tags.push(`<span class="tag bad" title="${T.forcedLostTag}">${T.forcedLostBadge}${lostN>1?' ×'+lostN:''}</span>`);
  const externalN = g.bodies.filter(externalKept).length;
  if (externalN) tags.push(`<span class="tag" title="${T.forcedExternalTag}">${T.forcedExternalBadge}${externalN>1?' ×'+externalN:''}</span>`);
  const pausedN = g.bodies.filter(b=>PAUSED.has(b.uuid)).length;
  if (pausedN) tags.push(`<span class="tag warn" title="${T.pausedTag}">⏸${pausedN>1?'×'+pausedN:''}</span>`);
  const frozenN = g.bodies.filter(b=>FROZEN.has(b.uuid)).length;
  if (frozenN) tags.push(`<span class="tag warn" title="${T.frozenTag}">❄${frozenN>1?'×'+frozenN:''}</span>`);
  if (g.detached) tags.push(`<span class="tag bad" title="${T.detachedTag}">${T.detachedBadge}×${g.detached}</span>`);
  if (g.rec) tags.push(`<span class="tag warn">${T.recTag}</span>`);
  if (g.loaded) tags.push(`<span class="tag ok"><i class="dot" style="background:var(--ok)"></i>${g.loaded}</span>`);
  if (g.holding) tags.push(`<span class="tag warn">${T.holdingX}×${g.holding}</span>`);
  if (g.orphans) tags.push(`<span class="tag bad">${T.orphanX}×${g.orphans}</span>`);
  if (g.dup) tags.push(`<span class="tag warn">${T.copiesX}</span>`);
  if (g.clone) tags.push(`<span class="tag clone">${T.cloneTag}</span>`);
  const gcost = sortVal(g,'cost');
  if (gcost > 0) tags.push(`<span class="tag acc pix">${gcost.toFixed(2)} ms/t</span>`);
  if (g.members_omitted > 0) tags.push(
    `<span class="tag warn" title="${T.groupPartialTip(g.members_omitted)}">${T.groupPartial}</span>`);
  return tags;
}
/* 卡片占位缩略图:等距立方体图标 + 尺寸标注(无图/生成中/永久占位时出现) */
const THUMB_CUBE = `<svg class="thCube" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round">
  <path d="M24 6 6 15v18l18 9 18-9V15L24 6z"/><path d="M24 42V24M6 15l18 9 18-9" opacity=".5"/></svg>`;

/* ===================== R4 缩略图加载器(物理体/回收站两网格共用) =====================
   uuid -> {url}|{until}|{busy}。img 标签带不了 X-Token 鉴权头,所以走 fetch→blob objectURL;
   渲染在本浏览器(R7):404 附当前签名=「请你渲」,200 附 X-Thumb-Stale=旧图先亮后台重渲,
   都交给 SableThumbRender 离屏队列,渲完上传服务端缓存,别的浏览器直接吃现成图;
   blocks>40万 前端直接永久占位,连请求都不发。命中后就地补进已渲染的卡片,重画时直接内联。 */
const THUMBS = new Map();
const THUMB_MAX_BLOCKS = 400000;
const thumbQueue = [];
let thumbInFlight = 0;
/* silent=true(回收站):没图也不标"生成中"——死体永远不会再渲,标了就是说谎;
   命中缓存靠的是该体生前渲好的图还躺在服务端磁盘缓存里 */
function thumbHtml(uuid, blocks, silent){
  const cached = uuid && THUMBS.get(uuid);
  if (cached && cached.url) return `<img class="thImg" src="${cached.url}" alt="">`;
  if (!uuid || blocks > THUMB_MAX_BLOCKS) return THUMB_CUBE;
  return THUMB_CUBE + (silent ? '' : `<span class="thPend">${T.thumbPending}</span>`);
}
let thumbObserver = null;
function observeThumbs(){
  const boxes = [...document.querySelectorAll('.bthumb[data-tu]')]
    .filter(box => Number(box.dataset.tb) <= THUMB_MAX_BLOCKS);
  // typeof 一道闸不够:races 的 vm 沙箱(以及别的半真环境)会把未定义全局兜成假构造器,
  // 造出来的实例没有方法 —— 三连方法探测在真浏览器里恒真,在这类环境里是唯一的回退开关
  if (!thumbObserver && typeof IntersectionObserver !== 'undefined') {
    try {
      const observer = new IntersectionObserver(entries => {
        for (const entry of entries) if (entry.isIntersecting) {
          observer.unobserve(entry.target);
          queueThumb(entry.target.dataset.tu);
        }
      }, {rootMargin:'240px'});
      if (typeof observer.observe === 'function'
          && typeof observer.unobserve === 'function'
          && typeof observer.disconnect === 'function') thumbObserver = observer;
    } catch (_) { /* 旧浏览器回退到立即排队 */ }
  }
  if (!thumbObserver) {
    boxes.forEach(box => queueThumb(box.dataset.tu));
    return;
  }
  thumbObserver.disconnect();
  boxes.forEach(box => thumbObserver.observe(box));
}
function queueThumb(uuid){
  const cached = THUMBS.get(uuid);
  if (cached && (cached.url || cached.busy || cached.until > Date.now())) return;
  THUMBS.set(uuid, {busy:true});
  thumbQueue.push(uuid);
  pumpThumbs();
}
function pumpThumbs(){
  while (thumbInFlight < 4 && thumbQueue.length) fetchThumb(thumbQueue.shift());
}
async function fetchThumb(uuid){
  thumbInFlight++;
  const gen = SRVGEN;
  try {
    const srv = CURSRV ? '?server=' + encodeURIComponent(CURSRV) : '';
    const r = await fetch('/api/thumb/' + uuid + srv, {headers:{'X-Token':token}});
    if (gen !== SRVGEN) return;               // 切服期间的旧响应整个作废
    if (r.ok) {
      const url = URL.createObjectURL(await r.blob());
      replaceThumbUrl(uuid, url);
      patchThumb(uuid, url);
      // 内容已过期:旧图先亮着,后台重渲上传,渲完就地替换
      const stale = r.headers.get('X-Thumb-Stale');
      if (stale) SableThumbRender.enqueue(uuid, stale);
    } else {
      const e = await r.json().catch(()=>({}));
      // 404 附签名=服务端的「请你渲」邀请函;没有签名(彻底删除只剩备份/旧节点)基本不会
      // 自愈 —— 30 秒一轮的全列表重问是实测里的 404 风暴,拉长到 10 分钟
      if (e.sig) SableThumbRender.enqueue(uuid, e.sig);
      THUMBS.set(uuid, {until: Date.now() + (e.sig ? 30000 : 600000)});
    }
  } catch (_) {
    if (gen === SRVGEN) THUMBS.set(uuid, {until: Date.now() + 30000});
  } finally {
    thumbInFlight--;
    pumpThumbs();
  }
}
/* 就地点亮:图到了别等下一次整表重画,替换占位立方体并淡入 */
function patchThumb(uuid, url){
  for (const box of document.querySelectorAll(`.bthumb[data-tu="${uuid}"]`)) {
    const current = box.querySelector('.thImg');
    if (current) {
      current.src = url;
      current.classList.add('thIn');
      continue;
    }
    const cube = box.querySelector('.thCube');
    if (!cube) continue;
    const img = document.createElement('img');
    img.className = 'thImg thIn';
    img.src = url;
    cube.replaceWith(img);
    const pending = box.querySelector('.thPend');
    if (pending) pending.remove();
  }
}
/* 离屏渲染的回执:url=渲好并已上传(直接用本地位图,不再 GET);null=永久放弃
   (too_large/副本歧义/空体),摘掉"生成中"并且本会话不再问 */
SableThumbRender.onDone = (uuid, url) => {
  if (url) { replaceThumbUrl(uuid, url); patchThumb(uuid, url); return; }
  THUMBS.set(uuid, {until: Infinity});
  for (const pending of document.querySelectorAll(`.bthumb[data-tu="${uuid}"] .thPend`)) pending.remove();
};
function replaceThumbUrl(uuid, url){
  const previous = THUMBS.get(uuid);
  if (previous && previous.url && previous.url !== url) URL.revokeObjectURL(previous.url);
  THUMBS.set(uuid, {url});
}
onServerReset(() => {
  SableThumbRender.reset();
  for (const cached of THUMBS.values()) if (cached.url) URL.revokeObjectURL(cached.url);
  THUMBS.clear();
  thumbQueue.length = 0;
  if (thumbObserver) thumbObserver.disconnect();
});

function render() {
  if (VIEW !== 'bodies') return;
  // 直达/前进后退/登录后带来的目标体:意图直接从 URL 派生,快照在手才能解析
  if (!DETAIL_UUID && DATA) {
    const target = parseBodyHash(location.hash);
    if (target && target !== HASH_MISS) {
      if (BODY_BY_UUID.has(target)) DETAIL_UUID = target;
      else { HASH_MISS = target; toast(T.bodyGone, 'bad'); stripHash(); }
    }
  }
  // 详情态:每次都按 uuid 重新派生 SEL/SELG(快照刷新后旧对象作废)
  if (DETAIL_UUID) {
    const entry = DATA && BODY_BY_UUID.get(DETAIL_UUID);
    if (entry) {
      SEL = entry.b; SELG = entry.g;
      syncDetailVisibility(true);
      renderDetailPage();
      updateSelUI();
      return;
    }
    if (DATA) { exitDetail(false); return; }   // 数据在但体没了(被删/被 sable 收走)
    syncDetailVisibility(true);
    renderDetailLoading();
    return;
  }
  syncDetailVisibility(false);
  updateMfCount();
  if (!DATA) {
    document.getElementById('list').innerHTML = BODIES_ERROR
      ? `<div id="listEmpty"><span class="big">⚠</span>${T.loadFail}${esc(BODIES_ERROR)}</div>`
      : `<div id="listEmpty">${T.loading}</div>`;
    document.getElementById('tabs').innerHTML = '';
    document.getElementById('toolbar').innerHTML = '';
    return;
  }
  const states = checkedValues('fState'), sizes = checkedValues('fSize'), dims = checkedValues('fDim');
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
    list.appendChild(buildCard(g, vis));
  }
  if (!shown) {
    list.innerHTML = `<div id="listEmpty"><span class="big">${TAB==='rec'?'✓':'⬡'}</span>${TAB==='rec'?T.recNone:T.noMatch}</div>`;
  } else if (matchedGroups > shown) {
    const btn = document.createElement('button');
    btn.id = 'moreBtn';
    btn.textContent = T.showMore(matchedGroups - shown);
    btn.onclick = () => { renderLimit += 800; render(); };
    list.appendChild(btn);
  }
  renderToolbar(matchedGroups, DATA.groups.filter(inTab).length);
  updateSelUI();
  observeThumbs();
}

/* 一组一卡。primary=可见成员里块数最大的:组根(gid)常是绳链上的小碎片,
   按它取尺寸/详情入口会把"青鸢V4a1"显示成 4×3×4(2026-08-14 重机副本实测) */
function primaryBody(vis){
  return vis.reduce((a, b) => (b.blocks > a.blocks ? b : a), vis[0]);
}
function buildCard(g, vis){
  const primary = primaryBody(vis);
  const forcedN = g.bodies.filter(b=>FORCED.has(b.uuid)).length;
  const div = document.createElement('div');
  div.className = 'bcard' + (forcedN ? ' is-forced' : g.orphans ? ' is-orphan' : g.rec ? ' is-rec' : '')
    + (g.bodies.some(b => BUSY.has(b.uuid)) ? ' is-busy' : '');
  div.dataset.gid = g.gid;
  div._bodies = g.bodies;   // updateSelUI 用:组卡复选框的勾选态按全组成员算
  const partial = g.members_omitted > 0;
  const size = primary.size.map(v=>Math.round(v)).join('×');
  const stateCounts = {};
  for (const b of g.bodies) stateCounts[b.state] = (stateCounts[b.state]||0)+1;
  const stateDots = Object.entries(stateCounts).map(([s,n]) =>
    `<i class="dot" style="background:${STATE_DOT[s]}" title="${stateLabel(s)}"></i>${g.members>1?n:''}`).join('');
  div.innerHTML = `
    <div class="bthumb" data-tu="${primary.uuid}" data-tb="${primary.blocks}" style="color:hsl(${hueOf(g.gid)} 32% 56%)">
      ${thumbHtml(primary.uuid, primary.blocks)}
      <input type="checkbox" class="gsel" ${partial ? `disabled title="${T.groupPartialTip(g.members_omitted)}"` : ''}>
      <button class="favStar ${FAV.has(g.gid)?'on':''}" title="${T.favTip}">${FAV.has(g.gid)?'★':'☆'}</button>
      <span class="thState">${stateDots}</span>
      <span class="thSize pix">${esc(size)}</span>
    </div>
    <div class="bmeta">
      <div class="bname">${esc(g.name) || '<span class="muted">'+T.unnamed+'</span>'}</div>
      <div class="bsub"><b>${fmt(g.blocks)}</b> ${T.blocksUnit} · ${esc(g.dims.replace(/minecraft:/g,''))}${g.members>1?` · ${g.members} ${T.bodies}`:''}</div>
      <div class="btags">${groupTags(g).join('')}</div>
    </div>`;
  div.onclick = () => openGroup(g, primary);
  div.querySelector('.gsel').onclick = e => { e.stopPropagation(); toggleSelGroup(g.bodies); };
  div.querySelector('.favStar').onclick = e => { e.stopPropagation(); toggleFav(g.gid); };
  return div;
}
function openGroup(g, b){
  browseScroll = document.getElementById('list').scrollTop;
  select(b, g);
}

function renderToolbar(matched, tabTotal){
  const parts = [`<span>${T.filterBar(matched, tabTotal)}</span>`];
  // 有旧数据时刷新失败:留着旧数据,但必须说清这不是当前状态
  if (BODIES_ERROR) parts.push(`<span style="color:var(--bad)">· ${T.staleData}${esc(BODIES_ERROR)}</span>`);
  // 服务端对单次响应有硬上限。三种截断后果不同,分开说
  if (DATA && DATA.truncated) {
    const why = [];
    if (DATA.shown_groups < DATA.total_groups) why.push(T.bodiesTruncated(DATA.shown_groups, DATA.total_groups));
    if (DATA.omitted_members) why.push(T.bodiesMembersOmitted(DATA.omitted_members));
    if (DATA.palette_truncated) why.push(T.bodiesPaletteTruncated);
    parts.push(`<span style="color:var(--warn)">· ${why.join(' · ')}</span>`);
  }
  if (matched < tabTotal) parts.push(`<span style="color:var(--warn)">· ${T.filterActive}</span>
    <button class="warnb" onclick="resetFilters()">${T.resetFilters}</button>`);
  // 按钮上的数字是"真能选中的组数":成员被截断的组不参与,不能让数字骗人。
  // 全选后同一按钮就地变"取消全选"(2026-08-14 用户点名:和右端"清空"隔太远)
  const selectable = lastVisibleGroups.filter(g => !(g.members_omitted > 0)).length;
  if (selectable) parts.push(`<button id="selAllBtn" onclick="toggleSelectAllVisible()">${selAllLabel()}</button>`);
  parts.push(`<span id="selSeg"></span>`);
  if (TAB === 'rec' && lastVisibleGroups.length) {
    const bodies = lastVisibleGroups.reduce((s,g)=>s+g.members, 0);
    parts.push(`<button class="warnb" style="margin-left:auto" onclick="doDeleteRecommended()">${T.recBatch(lastVisibleGroups.length, bodies)}</button>`);
    parts.push(`<div style="flex-basis:100%;font-size:11px;line-height:1.55;color:var(--dim);padding-top:3px">${T.recSafe(recPolicy())}</div>`);
  }
  document.getElementById('toolbar').innerHTML = parts.join(' ');
}
function updateMfCount(){
  let n = 0;
  if (document.getElementById('fBlock').value.trim()) n++;
  if (document.getElementById('fDup').value !== 'all') n++;
  if (document.getElementById('fNamedOnly').checked) n++;
  if (document.getElementById('fGroupOnly').checked) n++;
  const badge = document.getElementById('mfCount');
  badge.style.display = n ? '' : 'none';
  badge.textContent = n;
  document.getElementById('mfBtn').classList.toggle('mf-on', n > 0);
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
  // 依赖组是删除原子单位:成员复选框也整组切换。组被截断时 g.bodies 只是"已下发的那部分",
  // 这种组退回单选(和卡片复选框禁用是同一条规则)
  const bodies=entry&&!(entry.g.members_omitted>0)?entry.g.bodies:[{uuid:u}];
  const all=bodies.every(body=>SELECTED.has(body.uuid));
  for (const body of bodies) all?SELECTED.delete(body.uuid):SELECTED.add(body.uuid);
  updateSelUI();
}
function toggleSelGroup(bodies){
  const all = bodies.every(b => SELECTED.has(b.uuid));
  for (const b of bodies) all ? SELECTED.delete(b.uuid) : SELECTED.add(b.uuid);
  updateSelUI();
}
/* 一键全选/取消(共用骨架):选中当前筛选后可见的全部组(含超出渲染上限、只在计数里的那些);
   成员被截断的组跳过 —— 和卡片复选框同一条规则,选中时对跳过数弹提示 */
const bodySelectAll = makeSelectAll({
  items: () => lastVisibleGroups.filter(g => !(g.members_omitted > 0)),
  keys: g => g.bodies.map(b => b.uuid),
  sel: () => SELECTED,
  after: () => updateSelUI(),
});
function clearSel(){ SELECTED.clear(); updateSelUI(); }
function selAllLabel(){ return bodySelectAll.label(); }
function toggleSelectAllVisible(){
  const selecting = !bodySelectAll.all();
  bodySelectAll.toggle();
  if (selecting) {
    const skipped = lastVisibleGroups.filter(g => g.members_omitted > 0).length;
    if (skipped) toast(T.selAllSkipped(skipped), 'bad');
  }
}
function updateSelUI(){
  // 全选按钮随选择状态就地翻转文案
  const selAllBtn = document.getElementById('selAllBtn');
  if (selAllBtn) selAllBtn.textContent = selAllLabel();
  // 浏览网格:组卡复选框按全组成员的勾选态显示(全选/半选)
  document.querySelectorAll('#list .bcard').forEach(div => {
    const cb = div.querySelector('.gsel');
    if (!cb || !div._bodies) return;
    const on = div._bodies.filter(b => SELECTED.has(b.uuid)).length;
    cb.checked = on > 0 && on === div._bodies.length;
    cb.indeterminate = on > 0 && on < div._bodies.length;
  });
  // 详情页成员表
  document.querySelectorAll('#dMembers .member .msel').forEach(cb => {
    cb.checked = SELECTED.has(cb.closest('.member').dataset.uuid);
  });
  const seg = document.getElementById('selSeg');
  if (!seg) return;
  if (!SELECTED.size) { seg.innerHTML = ''; return; }
  let blocks = 0, orphans = 0, pausedN = 0, runningN = 0, forcedN = 0, freeN = 0, frozenN = 0, tickingN = 0;
  for (const u of SELECTED) {
    const e = BODY_BY_UUID.get(u);
    if (e) { blocks += e.b.blocks; if (e.b.state === 'orphan') orphans++; }
    if (PAUSED.has(u)) pausedN++; else runningN++;
    if (FORCED.has(u)) forcedN++; else freeN++;
    if (FROZEN.has(u)) frozenN++; else tickingN++;
  }
  seg.innerHTML = `<span class="selInfo">${T.selInfo(SELECTED.size, blocks)}</span>
    <button class="danger" onclick="doDeleteSelected()">${T.selDel}</button>
    ${freeN ? `<button class="primary" onclick="doForceSelected(true)">${T.selForce(freeN)}</button>` : ''}
    ${forcedN ? `<button onclick="doForceSelected(false)">${T.selUnforce(forcedN)}</button>` : ''}
    ${runningN ? `<button class="warnb" onclick="doPauseSelected(true)">${T.selPause(runningN)}</button>` : ''}
    ${pausedN ? `<button onclick="doPauseSelected(false)">${T.selResume(pausedN)}</button>` : ''}
    ${tickingN ? `<button class="warnb" onclick="doFreezeSelected(true)">${T.selFreeze(tickingN)}</button>` : ''}
    ${frozenN ? `<button onclick="doFreezeSelected(false)">${T.selThaw(frozenN)}</button>` : ''}
    ${orphans ? `<button onclick="doAdoptSelected()">${T.selAdopt(orphans)}</button>` : ''}
    <button class="ghost" onclick="clearSel()">${T.selClear}</button>`;
}
function focusBody(uuid){
  setView('bodies', {tab:'all', reset:true});
  if (!DATA) return;   // 入口来自可能过期的 DOM(总览"最吃性能"),切服后 DATA 是空的
  const found = DATA.groups.find(g => g.bodies.some(b => b.uuid === uuid));
  if (!found) return;
  select(found.bodies.find(b=>b.uuid===uuid), found);
}
/* ===================== 详情 ===================== */
function select(b, g) {
  const sameMesh = SEL && SEL.uuid === b.uuid && MESH_UUID === b.uuid;
  SEL = b; SELG = g;
  const opening = DETAIL_UUID !== b.uuid;
  const fromBrowse = DETAIL_UUID === null;
  DETAIL_UUID = b.uuid;
  const target = '#body/' + b.uuid;
  if (location.hash !== target) {
    // 只有"列表→详情"push 一条历史;详情内切换成员/跳转一律 replace ——
    // 否则浏览器返回和面包屑都会退到上一个成员而不是列表
    if (fromBrowse) { location.hash = target; DETAIL_PUSHED = true; }
    else history.replaceState(null, '', target);
  }
  render();
  if (opening) setTimeout(resizeGL, 30);
  if (!sameMesh) loadMesh(b.uuid);
}
function stateLabel(s){ return s==='loaded'?T.stateLoaded:s==='orphan'?T.stateOrphan:s==='holding'?T.stateHolding:T.stateStored; }
function reasonLabel(r){ return T['r' + r[0].toUpperCase() + r.slice(1)] || r; }
function protLabel(p){ return T['p' + p[0].toUpperCase() + p.slice(1)] || p; }
function recPolicy(){ return (DATA && DATA.rec_policy) || {blocks:20, types:4, be:3}; }
function cloneDetail(body){
  const set = cloneSetOf(body);
  if (!set) return '';
  const reason = set.mode === 'named' ? T.cloneNamedReason : T.cloneUnnamedReason;
  const size = (set.rounded_size || []).join(' × ');
  const peers = (set.members || []).filter(uuid=>uuid!==body.uuid).map(uuid=>BODY_BY_UUID.get(uuid)).filter(Boolean);
  return `<div class="cloneReason">${reason}<br>${fmt(set.blocks||0)} ${T.blocksUnit} · ${esc(size)}</div>` +
    peers.map(({b})=>`<button class="clonePeer" onclick="focusBody('${b.uuid}')">
      <span><b>${esc(b.name)||T.unnamed}</b><small class="mono">${b.uuid}</small></span>
      <span>${esc((b.dim||'').replace('minecraft:',''))}<br>${(b.pos||[0,0,0]).map(v=>Number(v).toFixed(0)).join(', ')}</span>
    </button>`).join('');
}
function renderCrumb(){
  const g = SELG, b = SEL;
  document.getElementById('dCrumb').innerHTML =
    `<button class="crumbBack" onclick="exitDetail(true)">← ${T.crumbBodies}</button>
     <span class="crumbSep">/</span><span>${esc((b.dim||'').replace('minecraft:',''))}</span>
     <span class="crumbSep">/</span><span class="crumbCur">${esc(g.name||b.name) || T.unnamed}</span>`;
}
function renderDetailLoading(){
  document.getElementById('dCrumb').innerHTML =
    `<button class="crumbBack" onclick="exitDetail(true)">← ${T.crumbBodies}</button>`;
  document.getElementById('dbody').innerHTML = BODIES_ERROR
    ? `<div class="empty" style="color:var(--bad)">⚠ ${T.loadFail}${esc(BODIES_ERROR)}</div>`
    : `<div class="empty">${T.loading}</div>`;
  const members = document.getElementById('dMembers');
  members.innerHTML = ''; members.style.display = 'none';
  document.getElementById('ops').style.display = 'none';
}
function renderDetailPage(){
  renderCrumb();
  renderDetail();
  renderMembers();
  // 全屏渲染中切成员:标题/元信息跟着换(成员抽屉此刻就搬在全屏层里)
  if (typeof fsMode !== 'undefined' && fsMode && SEL) {
    const title = document.getElementById('fsName');
    if (title) title.textContent = SEL.name || SEL.uuid.slice(0, 8);
    const meta = document.getElementById('fsMeta');
    if (meta) meta.textContent = `${SEL.uuid} · ${fmt(SEL.blocks)} ${T.blocksUnit} · ${SEL.dim}`;
  }
}
/* ===================== 成员抽屉 =====================
   详情页挂在视口左侧一列,全屏渲染时整个节点被搬进 #fsMembers(preview.js);
   折叠态全局记一份(spMembersOpen),单成员组没有可切换的东西,整个隐藏 */
function membersFolded(){ return localStorage.getItem('spMembersOpen') === '0'; }
function toggleMembersDrawer(){
  localStorage.setItem('spMembersOpen', membersFolded() ? '1' : '0');
  syncMembersDrawer();
}
function syncMembersDrawer(){
  const box = document.getElementById('dMembers');
  if (box) box.classList.toggle('folded', membersFolded());
}
function renderMembers(){
  const g = SELG;
  const box = document.getElementById('dMembers');
  if (!g || g.members < 2) { box.innerHTML = ''; box.style.display = 'none'; return; }
  box.style.display = '';
  const rows = g.bodies.map(b => {
    const extra = [];
    if (BUSY.has(b.uuid)) extra.push(busyTag(b.uuid));
    if (isVoid(b)) extra.push(`<span class="tag bad" title="${T.voidTag(REACH.void_below)}">${T.voidBadge}</span>`);
    if (isSky(b)) extra.push(`<span class="tag warn" title="${T.skyTag(REACH.sky_above)}">${T.skyBadge}</span>`);
    if (FORCED.has(b.uuid)) extra.push(`<span class="tag forced">${T.forcedBadge}</span>`);
    if (FORCED_LOST.has(b.uuid)) extra.push(`<span class="tag bad" title="${T.forcedLostTag}">${T.forcedLostBadge}</span>`);
    if (externalKept(b)) extra.push(`<span class="tag" title="${T.forcedExternalTag}">${T.forcedExternalBadge}</span>`);
    if (PAUSED.has(b.uuid)) extra.push(`<span class="tag warn">⏸</span>`);
    if (FROZEN.has(b.uuid)) extra.push(`<span class="tag warn">❄</span>`);
    if (b.detached) extra.push(`<span class="tag bad" title="${T.detachedTag}">${T.detachedBadge}</span>`);
    if (b.copies) extra.push(`<span class="tag warn">${b.copies} ${T.copiesX}</span>`);
    if (b.clone) extra.push(`<span class="tag clone">${T.cloneWith(clonePeerCount(b))}</span>`);
    if (b.deps) extra.push(`<span class="tag">${T.depsX} ${b.deps}</span>`);
    if (b.runtime && b.runtime.cost_ms !== undefined)
      extra.push(`<span class="tag acc pix">${b.runtime.cost_ms.toFixed(2)} ms/t</span>`);
    return `<div class="member ${SEL && SEL.uuid===b.uuid?'sel':''} ${BUSY.has(b.uuid)?'is-busy':''}" data-uuid="${b.uuid}">
      <input type="checkbox" class="msel">
      <i class="dot" style="background:${STATE_DOT[b.state]}" title="${stateLabel(b.state)}"></i>
      <span class="mname">${esc(b.name) || '<span class="muted mono">'+b.uuid.slice(0,8)+'</span>'}</span>
      ${extra.join('')}
      <span class="num">${fmt(b.blocks)} ${T.blocksUnit}</span>
      <span class="num">${b.pos.map(v=>v|0).join(', ')}</span>
    </div>`;
  }).join('');
  box.innerHTML = `
    <div class="mHead">
      <h4>${T.membersTitle(g.members)}${g.members_omitted>0
        ? ` <span class="tag warn">${T.groupPartial}</span>` : ''}</h4>
      <button class="mFold" onclick="toggleMembersDrawer()" title="${T.membersFold}">⟨</button>
    </div>
    <button class="mUnfold" onclick="toggleMembersDrawer()" title="${T.membersUnfold}">${T.membersRail(g.members)}</button>
    <div class="mBody">${rows}</div>`;
  syncMembersDrawer();
  box.querySelectorAll('.member').forEach(m => {
    m.onclick = () => { const e = BODY_BY_UUID.get(m.dataset.uuid); if (e) select(e.b, e.g); };
    m.querySelector('.msel').onclick = e => { e.stopPropagation(); toggleSel(m.dataset.uuid); };
  });
}
function renderDetail(){
  const b = SEL, g = SELG;
  if (!b) return;
  const job = BUSY.get(b.uuid);
  // 该体有作业在跑时整片操作区禁用:重复提交后端会 409 挡掉,但按钮先灰掉更诚实
  const ops = document.getElementById('ops');
  if (ops) {
    ops.classList.toggle('is-busy', !!job);
    ops.querySelectorAll('button').forEach(btn => btn.disabled = !!job);
  }
  const rt = b.runtime || {};
  const live = rt.x !== undefined;
  const pos = live ? [rt.x, rt.y, rt.z] : b.pos;
  // 徽章行:状态 + 组级异常/意图
  const badges = [`<span class="tag ${b.state==='loaded'?'ok':b.state==='orphan'?'bad':b.state==='holding'?'warn':''}">
      <i class="dot" style="background:${STATE_DOT[b.state]}"></i>${stateLabel(b.state)}</span>`,
    ...groupTags(g)];
  // 2×2 数据格:方块数 / 尺寸 / 组成员 / 逻辑开销(无则质量)
  const stat4 = rt.cost_ms !== undefined
    ? `<div class="dStat"><b>${rt.cost_ms.toFixed(3)}</b><span>${T.statCostL}</span></div>`
    : rt.mass !== undefined
      ? `<div class="dStat"><b>${fmt(rt.mass)}</b><span>${T.mass}</span></div>`
      : `<div class="dStat"><b>${b.copies||1}</b><span>${T.copiesRow}</span></div>`;
  const favBtn = `<button class="favStar ${FAV.has(g.gid)?'on':''}" title="${T.favTip}" onclick="toggleFav('${g.gid}')">${FAV.has(g.gid)?'★':'☆'}</button>`;
  const rows = [];
  rows.push([T.coord, `<span class="val">${pos.map(v=>Number(v).toFixed(1)).join(', ')}</span> <span class="tag ${live?'ok':''}">${live?T.rtLive:T.rtSaved}</span>`]);
  if (rt.cost_ms !== undefined) rows.push([T.costRow,
    `<span class="val" style="color:var(--acc)">${T.costVal(rt.cost_ms.toFixed(3))}</span>` +
    `<div class="muted" style="font-size:10.5px;line-height:1.45">${T.costHint}</div>`]);
  if (rt.lin_vel !== undefined) rows.push([T.vel, `<span class="val">${rt.lin_vel} m/s</span>`]);
  if (rt.mass !== undefined) rows.push([T.mass, `<span class="val">${fmt(rt.mass)}</span>`]);
  if (rt.players !== undefined) rows.push([T.players, `<span class="val">${rt.players}</span>`]);
  if (b.be) rows.push([T.beRow, `<span class="val">${fmt(b.be)}</span>`]);
  if (b.contents) rows.push([T.contentsRow,
    `<span class="val" style="color:var(--warn)">${fmt(b.contents)}</span>` +
    `<div class="muted" style="font-size:10.5px;line-height:1.45">${T.contentsHint}</div>`]);
  if (b.copies) {
    const entries = (b.entries||[]).map(e=>`<div class="val" style="font-size:10.5px">${esc(e)}</div>`).join('');
    rows.push([T.copiesRow, `${b.copies} ${T.copiesShow}${entries}`]);
  }
  if (b.clone) rows.push([T.cloneRelation, cloneDetail(b)]);
  if (b.deps) rows.push([T.deps, b.deps]);
  rows.push([T.group, T.groupVal(g.members, g.blocks)]);
  if (g.rec) rows.push([T.recWhy,
    `<span class="tag warn">${T.recTag}</span> ` + g.rec.reasons.map(r=>esc(reasonLabel(r))).join('、')]);
  else if (g.prot && g.prot.length) rows.push([T.protWhy,
    `<span class="tag ok">${T.protTag}</span> ` + g.prot.map(p=>esc(protLabel(p))).join('、')]);
  rows.push([T.entry, `<span class="val" style="font-size:10.5px">${esc(b.entry)}</span>`]);
  document.getElementById('dbody').innerHTML =
    `<div class="dBadges">${badges.join('')}</div>
     <h2>${esc(b.name) || `<span class="muted">${T.unnamed}</span>`}${favBtn}</h2>
     <div class="dUuid">${b.uuid}<button class="copyBtn" onclick="copyText('${b.uuid}')">⧉</button></div>
     <div class="dStats">
       <div class="dStat"><b>${fmt(b.blocks)}</b><span>${T.statBlocks}</span></div>
       <div class="dStat"><b>${b.size.map(v=>Math.round(v)).join('×')}</b><span>${T.statSizeL}</span></div>
       <div class="dStat"><b>${g.members}</b><span>${T.statMembers}</span></div>
       ${stat4}
     </div>
     <table>${rows.map(r=>`<tr><td>${r[0]}</td><td>${r[1]}</td></tr>`).join('')}</table>
     <div id="compList"></div>`;
  renderComposition();
  document.getElementById('ops').style.display = 'flex';
  // 目的坐标与当前坐标分离:仅在切换选中体时预填一次,周期刷新不覆盖用户输入
  if (tpFilledFor !== b.uuid) {
    tpFilledFor = b.uuid;
    fillCoordInputs(pos);
  }
  document.getElementById('adoptRow').style.display = b.state === 'orphan' ? 'flex' : 'none';
  document.getElementById('dedupeBtn').style.display = (b.copies > 1 || g.dup) ? '' : 'none';
  document.getElementById('delBodyBtn').textContent = g.members > 1 ? T.delGroup(g.members) : T.delBody;
  const fullyLoaded = g.loaded === g.members;
  const pauseBtn = document.getElementById('pauseBtn');
  pauseBtn.textContent = PAUSED.has(b.uuid) ? T.resumeBody : T.pauseBody;
  pauseBtn.classList.toggle('warnb', !PAUSED.has(b.uuid));
  pauseBtn.disabled = !!job || (!fullyLoaded && !PAUSED.has(b.uuid));
  pauseBtn.title = pauseBtn.disabled ? T.stateNeedsLoad : T.pauseTip;
  const freezeBtn = document.getElementById('freezeBtn');
  freezeBtn.textContent = FROZEN.has(b.uuid) ? T.thawBody : T.freezeBody;
  freezeBtn.classList.toggle('warnb', !FROZEN.has(b.uuid));
  freezeBtn.disabled = !!job || (!fullyLoaded && !FROZEN.has(b.uuid));
  freezeBtn.title = freezeBtn.disabled ? T.stateNeedsLoad : T.freezeTip;
  const forceBtn = document.getElementById('forceBtn');
  forceBtn.textContent = FORCED.has(b.uuid) ? T.unforceBody : T.forceBody;
  forceBtn.classList.toggle('primary', !FORCED.has(b.uuid));
  // 副本歧义挡常驻(B 组门槛前置到按钮):冷组多副本后端选不出版本,先去处理副本。
  // 整组已加载=运行证据齐、常驻必成,不挡;取消常驻只摘票,也不挡。
  const forceGated = !FORCED.has(b.uuid) && !fullyLoaded && !!g.dup;
  forceBtn.disabled = !!job || forceGated;
  forceBtn.title = forceGated ? T.forceCopiesFirst : T.forceHint;
  const clearVelBtn = document.getElementById('clearVelBtn');
  clearVelBtn.disabled = !!job || !g.loaded;
  clearVelBtn.title = g.loaded ? T.clearVelTip : T.clearVelNeedsLoad;
  const autoRepairBtn = document.getElementById('autoRepairBtn');
  autoRepairBtn.textContent = AUTO_REPAIR_RUN ? T.autoRepairing : T.autoRepairGroup;
  autoRepairBtn.disabled = !!job || !!AUTO_REPAIR_RUN;
  autoRepairBtn.title = T.autoRepairHint;
  loadPlayers();
}
function fillCoordInputs(pos){
  document.getElementById('tx').value = pos[0]|0;
  document.getElementById('ty').value = pos[1]|0;
  document.getElementById('tz').value = pos[2]|0;
}
/* 把当前坐标填进目的坐标输入框(想基于当前位置微调时用) */
function fillCurrentPos(){
  if (!SEL) return;
  const rt = SEL.runtime || {};
  fillCoordInputs(rt.x !== undefined ? [rt.x, rt.y, rt.z] : SEL.pos);
}
/* 传送玩家下拉:保留已选玩家,列表为空时禁用按钮。请求失败不能伪装成无人在线。 */
function renderPlayerSelect(){
  const sel = document.getElementById('tpPlayer');
  if (!sel) return;
  const cur = sel.value;
  const status = staleLabel(PLAYERS_ERROR, false);
  staleMark(sel, status);
  const notice = status && PLAYERS.length ? `<option value="" disabled>${esc(status)}</option>` : '';
  sel.innerHTML = PLAYERS.length
    ? notice + PLAYERS.map(p=>`<option value="${p.uuid}" ${p.uuid===cur?'selected':''}>${esc(p.name)}</option>`).join('')
    : `<option value="">${esc(status || T.tpNoPlayers)}</option>`;
  sel.disabled = !PLAYERS.length;
  const btn = document.getElementById('tpPlayerBtn');
  // 玩家列表异步回来时别把 renderDetail 的作业置灰覆写掉(传送自身会依链加载,不设加载门)
  if (btn) btn.disabled = !PLAYERS.length || !!(SEL && BUSY.get(SEL.uuid));
}
/* 成分表默认只列前 30 种,剩余折进原生 <details>;切体时 innerHTML 重建自动复位折叠态 */
const COMP_PAGE = 30;
function renderComposition(){
  const selected = VIEW==='recycle' ? RSEL : SEL;
  for (const box of [document.getElementById('compList'), document.getElementById('rCompList'),
    document.getElementById('copyComp'), document.getElementById('fsComp')]) {
    if (!box) continue;
    if (!MESH_DATA || !selected || MESH_UUID !== selected.uuid) { box.innerHTML = ''; continue; }
    const pal = [...MESH_DATA.palette].sort((a,b)=>b.count-a.count);
    const total = pal.reduce((s,p)=>s+p.count,0) || 1;
    const row = p => `<div class="compRow">
        <span class="chip" style="background:#${(p.color>>>0).toString(16).padStart(6,'0')}"></span>
        <span class="cname" title="${esc(p.id)}">${esc(p.zh)}</span>
        <span class="cnum">${fmt(p.count)} · ${(p.count/total*100).toFixed(1)}%</span>
      </div>`;
    const rest = pal.slice(COMP_PAGE);
    box.innerHTML = (box.id==='compList'||box.id==='rCompList'||box.id==='copyComp'
      ? `<h4>${T.composition}${MESH_DATA.truncated?T.pvTrunc:''}</h4>` : '') +
      pal.slice(0, COMP_PAGE).map(row).join('') +
      (rest.length ? `<details class="compRest"><summary class="compRow compMore"><span
        class="ifClosed">▾ ${T.compMore(rest.length)}</span><span class="ifOpen">▴ ${T.compLess}</span></summary>${
        rest.map(row).join('')}</details>` : '');
  }
}

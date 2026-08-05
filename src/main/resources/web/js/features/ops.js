'use strict';
/* 操作层:传送/删除/收养/恢复/口令/重扫。
   除"改口令""改回收站上限"这两个纯配置写入外,所有会改变物理体状态的操作都走后台作业:
   请求只负责入队并立刻返回,进度和结果由 /api/bodies 的 busy 与 /api/jobs 的日志回报。
   —— 从前是同步等待,巨型体能让一次请求跑十几分钟,浏览器 30 秒就超时,
   用户看不到任何进展就会重复点击,最终把传输层的在飞槽位占满、整个面板 503。 */

/* 提交一个后台作业。目标体已有作业在跑时后端返回 409,这里当普通失败提示。 */
async function submitJob(path, opts, label){
  try {
    const r = await api(path, opts);
    if (r && r.job) JOB_WATCH.set(r.job, r.op || label || '');
    await loadBodies();   // 立刻把"处理中"画出来,不再靠写死的 setTimeout 等
    return r;
  } catch(e){
    toast((label ? label + ' · ' : '') + e.message, 'bad');
    return null;
  }
}
/* 改访问口令:后端会同步给集群所有成员,改完本地也要跟着换,否则下一个请求就 401 */
async function doChangeToken(){
  if (!await askModal(t('tokenChangeT'), t('tokenChangeMsg'), true)) return;
  const next = document.getElementById('modalInput').value.trim();
  if (!next) return;
  if (next === token) { toast(t('tokenSame')); return; }
  busy(t('loading'));
  try {
    const r = await api('/api/cluster/token', {method:'POST', body: JSON.stringify({token: next})});
    token = r.token;
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    if (r.failed && r.failed.length) toast(t('tokenPartial')(r.failed.join('、')), 'bad');
    else toast(t('tokenOk'), 'ok');
    if (r.warn) toast(r.warn, 'bad');
    await loadServers();
    await loadAll(true);
    startEventStream();
  } catch(e){ toast(t('tokenFail') + e.message, 'bad'); }
  busy(null);
}
async function doRescan(){
  await submitJob('/api/rescan', {method:'POST'}, t('rescanOp'));
}
async function doDeleteSelected(){
  const groups=[];
  const seen=new Set();
  for (const uuid of SELECTED) {
    const entry=BODY_BY_UUID.get(uuid);
    if (entry&&!seen.has(entry.g.gid)) { seen.add(entry.g.gid); groups.push(entry.g); }
  }
  let uuids=groups.flatMap(group=>group.bodies.map(body=>body.uuid));
  if (!uuids.length) return;
  const blocks=groups.reduce((sum,group)=>sum+group.blocks,0);
  if (!await askModal(t('selDelT'), t('selDelMsg')(uuids.length, blocks), false)) return;
  if (uuids.length > 500) { toast(t('recTooMany'), 'bad'); return; }
  await batchDelete(uuids);
  clearSel();
}
async function doAdoptSelected(){
  const orphans = [...SELECTED].map(u => BODY_BY_UUID.get(u))
    .filter(e => e && e.b.state === 'orphan').map(e => e.b.uuid);
  if (!orphans.length) return;
  if (orphans.length > 500) { toast(t('recTooMany'), 'bad'); return; }
  if (!await askModal(t('selAdoptT'), t('selAdoptMsg')(orphans.length), false)) return;
  // 整批一个作业。从前是逐个 POST:N 个体 = N 次提交 + N 次全量 bodies 刷新,选区一大就线性放大
  await submitJob('/api/ops/batch_adopt', {method:'POST', body: JSON.stringify({uuids: orphans})}, t('adoptOp'));
  clearSel();
}
async function saveRecycleLimit(){
  const limit=Number(document.getElementById('rLimit').value);
  if (!Number.isInteger(limit)||limit<1) return;
  if (RECYCLE&&limit<(RECYCLE.file_count||0)) {
    const confirmed=await askModal(t('limitConfirmT'),t('limitConfirmMsg')(RECYCLE.file_count,limit),false);
    if (!confirmed) { document.getElementById('rLimit').value=RECYCLE.limit||500; return; }
  }
  busy(t('loading'));
  try {
    await api('/api/recycle/config',{method:'POST',body:JSON.stringify({max_files:limit})});
    toast(t('saveLimitOk'),'ok'); await loadRecycle();
  } catch(e){ toast(t('saveLimitFail')+e.message,'bad'); }
  busy(null);
}
async function restoreCurrentGroup(){
  if (!RSELG) return;
  await confirmRestore([RSELG]);
}
async function restoreSelectedGroups(){
  const groups=[...R_SELECTED].map(id=>RECYCLE_BY_ID.get(id)).filter(Boolean);
  if (groups.length) await confirmRestore(groups);
}
async function confirmRestore(groups){
  const bodies=groups.reduce((sum,group)=>sum+group.members,0);
  const blocks=groups.reduce((sum,group)=>sum+(group.blocks||0),0);
  const recovery=groups.filter(group=>group.state==='recovery_required').length;
  const message=t('restoreSelectedMsg')(groups.length,bodies,blocks)+(recovery?t('restoreRecoveryWarn')(recovery):'');
  if (!await askModal(t('restoreSelectedT'),message,false)) return;
  const r = await submitJob('/api/recycle/restore',
    {method:'POST',body:JSON.stringify({ids:groups.map(g=>g.id)})}, t('restoreOp'));
  if (r) { R_SELECTED.clear(); loadRecycle(); }
}
/* 单体物理暂停:无确认直接执行;内存态,重启服务端自动恢复运行 */
async function setPausedBodies(uuids, paused){
  if (!uuids.length) return;
  const r = await submitJob('/api/ops/pause', {method:'POST', body: JSON.stringify({uuids, paused})},
    t(paused ? 'pauseOp' : 'resumeOp'));
  if (!r) return;
  for (const u of uuids) paused ? PAUSED.add(u) : PAUSED.delete(u);
  renderAll();
  if (SEL) renderDetail();
}
function doPauseCurrent(){
  if (SEL) setPausedBodies([SEL.uuid], !PAUSED.has(SEL.uuid));
}
function doPauseSelected(paused){
  setPausedBodies([...SELECTED].filter(u => paused !== PAUSED.has(u)), paused);
}
/* 常驻加载(sable force-load ticket):开启时后端会先把体加载出来,大体可能耗时数分钟;
   票由 sable 持久化,重启后仍然生效 */
async function setForcedBodies(uuids, forced){
  if (!uuids.length) return;
  const r = await submitJob('/api/ops/force_load', {method:'POST', body: JSON.stringify({uuids, forced})},
    t(forced ? 'forceOp' : 'unforceOp'));
  if (!r) return;
  // 乐观更新;作业失败时下一次 loadBodies 会用服务端真值纠正回来
  for (const u of uuids) forced ? FORCED.add(u) : FORCED.delete(u);
  renderAll();
  if (SEL) renderDetail();
}
function doForceCurrent(){
  if (SEL) setForcedBodies([SEL.uuid], !FORCED.has(SEL.uuid));
}
function doForceSelected(forced){
  setForcedBodies([...SELECTED].filter(u => forced !== FORCED.has(u)), forced);
}
/* 传送玩家到选中结构顶面中心(必须落进包围盒,否则体在人到达前就被卸载);
   体未加载后端会先按依赖链强制加载 */
async function doTeleportPlayer(){
  if (!SEL) return;
  const pu = document.getElementById('tpPlayer').value;
  if (!pu) { toast(t('tpNoPlayers'), 'bad'); return; }
  const p = PLAYERS.find(x=>x.uuid===pu);
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('tpPlayerT'), t('tpPlayerMsg')(p ? p.name : pu, nm), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/teleport_player?player=${pu}`, {method:'POST'}, t('tpPlayerOp'));
}
async function doTeleport() {
  if (!SEL) return;
  const x = document.getElementById('tx').value, y = document.getElementById('ty').value, z = document.getElementById('tz').value;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('tpConfirmT'), t('tpConfirm')(nm,x,y,z), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/teleport?x=${x}&y=${y}&z=${z}`, {method:'POST'}, t('tpOp'));
}
async function doDelete() {
  if (!SEL || !SELG) return;
  const group=SELG;
  const title=group.members>1?t('delGroupT'):t('delConfirmT');
  const message=group.members>1?t('delGroupMsg')(group.members,group.blocks)
    :t('delConfirm')(SEL.name||SEL.uuid.slice(0,8),SEL.blocks);
  if (!await askModal(title,message,false)) return;
  await batchDelete(group.bodies.map(body=>body.uuid));
}
async function doDeleteRecommended(){
  const groups = lastVisibleGroups.filter(g => g.rec);
  if (!groups.length) return;
  let uuids = groups.flatMap(g => g.bodies.map(b=>b.uuid));
  const blocks = groups.reduce((s,g)=>s+g.blocks,0);
  if (!await askModal(t('recBatchT'), t('recBatchMsg')(groups.length, uuids.length, blocks), false)) return;
  if (uuids.length > 500) { toast(t('recTooMany'), 'bad'); return; }
  await batchDelete(uuids);
}
async function batchDelete(uuids){
  const r = await submitJob('/api/ops/batch_delete', {method:'POST', body: JSON.stringify({uuids})}, t('delOp'));
  if (!r) return;
  SEL = null; SELG = null;
  loadRecycle();
}
async function doAdopt() {
  if (!SEL) return;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('adoptT'), t('adoptMsg')(nm), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/adopt`, {method:'POST'}, t('adoptOp'));
}

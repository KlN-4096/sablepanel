'use strict';
/* 操作层:传送/删除/收养/恢复/口令/重扫 —— 全部经确认后调 api,完成后刷新数据 */
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
  } catch(e){ toast(t('tokenFail') + e.message, 'bad'); }
  busy(null);
}
async function doRescan(){
  try { await api('/api/rescan', {method:'POST'}); toast(t('rescanOk'), 'ok'); setTimeout(loadBodies, 4000); }
  catch(e){ toast(t('loadFail') + e.message, 'bad'); }
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
  if (!await askModal(t('selAdoptT'), t('selAdoptMsg')(orphans.length), false)) return;
  busy(t('loading'));
  let ok = 0;
  for (const u of orphans) {   // 串行:收养走服务端主线程,别并发轰
    try { const r = await api(`/api/body/${u}/adopt`, {method:'POST'}); if (r.ok) ok++; }
    catch(e){}
  }
  busy(null);
  toast(t('selAdoptDone')(ok, orphans.length), ok === orphans.length ? 'ok' : 'bad');
  clearSel();
  setTimeout(loadBodies, 1200);
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
  busy(t('loading'));
  try {
    const result=await api('/api/recycle/restore',{method:'POST',body:JSON.stringify({ids:groups.map(g=>g.id)})});
    const failures=(result.results||[]).filter(item=>!item.ok);
    const detail=failures.slice(0,3).map(item=>`${item.id}: ${item.error}`).join('; ');
    toast(t('restoreDone')(result.ok,result.total)+(detail?` · ${detail}`:'')+warnText(result),result.ok===result.total?'ok':'bad');
    R_SELECTED.clear();
    setTimeout(()=>{loadBodies();loadRecycle();},1200);
  } catch(e){ toast(t('restoreFail')+e.message,'bad'); }
  busy(null);
}
/* 单体物理暂停:无确认直接执行;内存态,重启服务端自动恢复运行 */
async function setPausedBodies(uuids, paused){
  if (!uuids.length) return;
  try {
    await api('/api/ops/pause', {method:'POST', body: JSON.stringify({uuids, paused})});
    for (const u of uuids) paused ? PAUSED.add(u) : PAUSED.delete(u);
    toast(t(paused ? 'pauseOk' : 'resumeOk')(uuids.length), 'ok');
    renderAll();
    if (SEL) renderDetail();
  } catch(e){ toast(t('pauseFail') + e.message, 'bad'); }
}
function doPauseCurrent(){
  if (SEL) setPausedBodies([SEL.uuid], !PAUSED.has(SEL.uuid));
}
function doPauseSelected(paused){
  setPausedBodies([...SELECTED].filter(u => paused !== PAUSED.has(u)), paused);
}
/* 传送玩家到选中结构上方(包围盒顶面中心 +1);体未加载后端会先强制加载 */
async function doTeleportPlayer(){
  if (!SEL) return;
  const pu = document.getElementById('tpPlayer').value;
  if (!pu) { toast(t('tpNoPlayers'), 'bad'); return; }
  const p = PLAYERS.find(x=>x.uuid===pu);
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('tpPlayerT'), t('tpPlayerMsg')(p ? p.name : pu, nm), false)) return;
  busy(t('loading'));
  try {
    const r = await api(`/api/body/${SEL.uuid}/teleport_player?player=${pu}`, {method:'POST'});
    toast(t('tpPlayerOk')(r.player || ''), 'ok');
  } catch(e){ toast(t('tpFail') + e.message, 'bad'); }
  busy(null);
}
async function doTeleport() {
  if (!SEL) return;
  const x = document.getElementById('tx').value, y = document.getElementById('ty').value, z = document.getElementById('tz').value;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('tpConfirmT'), t('tpConfirm')(nm,x,y,z), false)) return;
  busy(t('loading'));
  try {
    const result = await api(`/api/body/${SEL.uuid}/teleport?x=${x}&y=${y}&z=${z}`, {method:'POST'});
    toast(t('tpOk'), 'ok');
    const pos = [result.x, result.y, result.z].map(Number);
    SEL.pos = pos;
    SEL.runtime = {...(SEL.runtime || {}), dim:result.dim, x:pos[0], y:pos[1], z:pos[2]};
    render(); renderDetail();
    loadBodies();
  } catch(e){ toast(t('tpFail') + e.message, 'bad'); }
  busy(null);
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
  busy(t('loading'));
  try {
    const r = await api('/api/ops/batch_delete', {method:'POST', body: JSON.stringify({uuids})});
    const failed = (r.results || []).filter(x => !x.ok);
    const detail = failed.slice(0, 3)
      .map(x => `${x.uuid.slice(0,8)}: ${x.error || t('delUnknownFail')}`).join('; ');
    toast(t('delGroupDone')(r.ok, r.total) + (detail ? ` · ${detail}` : '') + warnText(r),
      r.ok === r.total ? 'ok' : 'bad');
    SEL = null; SELG = null;
    setTimeout(()=>{loadBodies();loadRecycle();}, 1500);
  } catch(e){ toast(t('delFail') + e.message, 'bad'); }
  busy(null);
}
async function doAdopt() {
  if (!SEL) return;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(t('adoptT'), t('adoptMsg')(nm), false)) return;
  busy(t('loading'));
  try {
    const r = await api(`/api/body/${SEL.uuid}/adopt`, {method:'POST'});
    const bad = Object.values(r.chain||{}).filter(v => v!=='adopted' && v!=='already_loaded').length;
    toast(r.ok ? (bad ? t('adoptPart') : t('adoptOk')) : t('adoptFail'), r.ok ? 'ok' : 'bad');
    if (r.truncated) toast(t('adoptTrunc')(r.truncated), 'bad');
    setTimeout(loadBodies, 1200);
  } catch(e){ toast(t('adoptFail') + e.message, 'bad'); }
  busy(null);
}

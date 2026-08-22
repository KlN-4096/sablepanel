'use strict';
/* 操作层:传送/删除/收养/恢复/口令/重扫。
   除"改口令""改回收站上限"这两个纯配置写入外,所有会改变物理体状态的操作都走后台作业:
   请求只负责入队并立刻返回,进度和结果由 /api/jobs?poll=1 的 running[] 与 log[] 回报。
   —— 从前是同步等待,巨型体能让一次请求跑十几分钟,浏览器 30 秒就超时,
   用户看不到任何进展就会重复点击,最终把传输层的在飞槽位占满、整个面板 503。 */

/* 提交一个后台作业。目标体已有作业在跑时后端返回 409,这里当普通失败提示。
   POST 发出后也可能切服:旧服的接受响应不能把 job seq 写进新服的 watch,更不能让
   调用方拿旧服的成功结果去乐观更新新服。 */
async function submitJob(path, opts){
  const ctx = captureCtx();
  try {
    const r = await api(path, opts);
    if (!ctx.fresh()) return null;
    if (r && r.job) JOB_WATCH.set(r.job, r.op || '');
    await pollJobs();   // 立刻把"处理中"画出来,不再靠写死的 setTimeout 等
    return ctx.fresh() ? r : null;
  } catch(e){
    if (ctx.fresh()) toast(e.message, 'bad');
    return null;
  }
}
/* 改访问口令:后端会同步给集群所有成员,改完本地也要跟着换,否则下一个请求就 401 */
async function doChangeToken(){
  if (!await askModal(T.tokenChangeT, T.tokenChangeMsg, true)) return;
  const next = document.getElementById('modalInput').value.trim();
  if (!next) return;
  if (next === token) { toast(T.tokenSame); return; }
  // 改口令是集群级操作,收尾只看会话代次(authFresh):中途切换查看的服务器不该作废它
  let ctx = captureCtx();
  busy(T.loading);
  try {
    const r = await api('/api/cluster/token', {method:'POST', body: JSON.stringify({token: next})});
    if (!ctx.authFresh()) return;
    token = r.token;
    // 凭据已经改变,旧 token 发出的所有读取/写入响应从这里起都属于上一会话
    authSeq++;
    ctx = captureCtx();
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    if (r.failed && r.failed.length) toast(T.tokenPartial(r.failed.join('、')), 'bad');
    else toast(T.tokenOk, 'ok');
    if (r.warn) toast(r.warn, 'bad');
    await loadServers();
    if (!ctx.authFresh()) return;
    await loadAll(true);
    if (ctx.authFresh()) startEventStream();
  } catch(e){ if (ctx.authFresh()) toast(T.tokenFail + e.message, 'bad'); }
  finally { busy(null); }
}
async function doRescan(){
  await submitJob('/api/rescan', {method:'POST'});
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
  // 后端按依赖链把每个组重新展开成完整组,所以确认数和 500 上限都要按 members(真值)算。
  // 按可见 uuid 算的话:提示说删 60 个实际删 100 个,展开后超 500 还会直接失败
  const total=groups.reduce((sum,group)=>sum+group.members,0);
  if (!await askModal(T.selDelT, T.selDelMsg(total, blocks), false)) return;
  if (total > 500) { toast(T.recTooMany, 'bad'); return; }
  await batchDelete(uuids);
  clearSel();
}
async function doAdoptSelected(){
  const orphans = [...SELECTED].map(u => BODY_BY_UUID.get(u))
    .filter(e => e && e.b.state === 'orphan').map(e => e.b.uuid);
  if (!orphans.length) return;
  if (orphans.length > 500) { toast(T.recTooMany, 'bad'); return; }
  if (!await askModal(T.selAdoptT, T.selAdoptMsg(orphans.length), false)) return;
  // 整批一个作业。从前是逐个 POST:N 个体 = N 次提交 + N 次全量 bodies 刷新,选区一大就线性放大
  await submitJob('/api/ops/batch_adopt', {method:'POST', body: JSON.stringify({uuids: orphans})});
  clearSel();
}
async function saveRecycleLimit(){
  const limit=Number(document.getElementById('rLimit').value);
  if (!Number.isInteger(limit)||limit<1) return;
  if (RECYCLE&&limit<(RECYCLE.file_count||0)) {
    const confirmed=await askModal(T.limitConfirmT,T.limitConfirmMsg(RECYCLE.file_count,limit),false);
    if (!confirmed) { document.getElementById('rLimit').value=RECYCLE.limit||500; return; }
  }
  const ctx=captureCtx();
  busy(T.loading);
  try {
    await api('/api/recycle/config',{method:'POST',body:JSON.stringify({max_files:limit})});
    if (!ctx.fresh()) return;
    toast(T.saveLimitOk,'ok'); await loadRecycle();
  } catch(e){ if (ctx.fresh()) toast(T.saveLimitFail+e.message,'bad'); }
  finally { busy(null); }
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
  if (groups.some(group=>group.state==='incomplete')) { toast(T.restoreIncomplete,'bad'); return; }
  const bodies=groups.reduce((sum,group)=>sum+group.members,0);
  const blocks=groups.reduce((sum,group)=>sum+(group.blocks||0),0);
  const old=groups.filter(group=>group.version_state==='old').length;
  const recovery=groups.filter(group=>group.version_state!=='old'&&group.state==='recovery_required').length;
  const message=T.restoreSelectedMsg(groups.length,bodies,blocks)
    +(old?T.restoreOldWarn(old):'')+(recovery?T.restoreRecoveryWarn(recovery):'');
  if (!await askModal(T.restoreSelectedT,message,false)) return;
  const r = await submitJob('/api/recycle/restore',
    {method:'POST',body:JSON.stringify({ids:groups.map(g=>g.id)})});
  if (r) { R_SELECTED.clear(); renderRecycle(); }
}
async function purgeCurrentGroup(){
  if (RSELG) await confirmPurge([RSELG]);
}
async function purgeSelectedGroups(){
  const groups=[...R_SELECTED].map(id=>RECYCLE_BY_ID.get(id)).filter(Boolean);
  if (groups.length) await confirmPurge(groups);
}
async function confirmPurge(groups){
  const bodies=groups.reduce((sum,group)=>sum+group.members,0);
  const files=groups.reduce((sum,group)=>sum+(group.file_count||0),0);
  const recovery=groups.filter(group=>group.state==='recovery_required').length;
  const message=T.purgeMsg(groups.length,bodies,files)+(recovery?T.purgeRecoveryWarn(recovery):'');
  if (!await askModal(T.purgeT,message,false)) return;
  const r=await submitJob('/api/recycle/purge',
    {method:'POST',body:JSON.stringify({ids:groups.map(group=>group.id)})});
  if (r) { R_SELECTED.clear(); clearRecycleDetail(); renderRecycle(); }
}
function visibleGroupUuids(uuids){
  const expanded = new Set(uuids);
  for (const uuid of uuids) {
    const group = BODY_BY_UUID.get(uuid)?.g;
    if (group) for (const body of group.bodies) expanded.add(body.uuid);
  }
  return [...expanded];
}
/* 暂停/冻结/常驻三个整组开关共用一条提交路径:提交作业→乐观更新对应集合→重画。
   作业失败时下一次 loadBodies 会用服务端真值纠正回来。 */
async function setBodyFlag(route, key, uuids, on, set){
  if (!uuids.length) return null;
  const r = await submitJob(route, {method:'POST', body: JSON.stringify({uuids, [key]: on})});
  if (!r) return null;
  for (const u of visibleGroupUuids(uuids)) on ? set.add(u) : set.delete(u);
  renderAll();
  if (SEL) renderDetail();
  return r;
}
/* 物理暂停按完整依赖组执行:全部固定后清除线速度和角速度,重启后保持。 */
function setPausedBodies(uuids, paused){
  return setBodyFlag('/api/ops/pause', 'paused', uuids, paused, PAUSED);
}
function doPauseCurrent(){
  if (SEL) setPausedBodies([SEL.uuid], !PAUSED.has(SEL.uuid));
}
function doPauseSelected(paused){
  setPausedBodies([...SELECTED].filter(u => paused !== PAUSED.has(u)), paused);
}
/* 暂停 tick(冻结):后端按整个依赖组生效,点一个体会连坐全组。
   恢复 tick 才是危险动作 —— 大组一旦重新开跑就是主线程被压垮的那种崩,所以只在这头弹确认。 */
function setFrozenBodies(uuids, frozen){
  return setBodyFlag('/api/ops/freeze', 'frozen', uuids, frozen, FROZEN);
}
async function doFreezeCurrent(){
  if (!SEL) return;
  const frozen = !FROZEN.has(SEL.uuid);
  if (!frozen && !await askModal(T.thawT,
      T.thawMsg(SEL.name || SEL.uuid.slice(0,8), SELG ? SELG.members : 1, SELG ? SELG.blocks : SEL.blocks), false)) return;
  setFrozenBodies([SEL.uuid], frozen);
}
async function doFreezeSelected(frozen){
  const uuids = [...SELECTED].filter(u => frozen !== FROZEN.has(u));
  if (!frozen && uuids.length) {
    const impacted = new Map();
    for (const u of uuids) {
      const body = BODY_BY_UUID.get(u), group = body?.g;
      impacted.set(group ? `g:${group.gid}` : `u:${u}`,
        group || {members:1,blocks:body?.b?.blocks || 0});
    }
    const members = [...impacted.values()].reduce((sum,g)=>sum+g.members,0);
    const blocks = [...impacted.values()].reduce((sum,g)=>sum+g.blocks,0);
    if (!await askModal(T.thawT, T.thawSelMsg(uuids.length,members,blocks), false)) return;
  }
  setFrozenBodies(uuids, frozen);
}
/* 常驻加载只管理 sable force-load ticket:不清速度、不固定物理、不暂停 tick。
   取消时整组保存到磁盘并退出活动态;取消不清暂停/冻结意图(独立功能,下次加载重新生效)。
   返回值给自动修复等调用方拿 job seq 等终态。 */
function setForcedBodies(uuids, forced){
  return setBodyFlag('/api/ops/force_load', 'forced', uuids, forced, FORCED);
}
/**
 * 整维度停跑/恢复物理 —— sable 自己的 setPaused,跳掉 Rapier3D.step 那一整段。
 * 影响的是这个维度里所有人的船,所以停之前必须确认。不走作业队列:一次字段写,没有等待。
 */
async function toggleDimPhysics(dim, paused){
  const label = dim.replace('minecraft:','');
  if (!await askModal(T.dimPhysT, paused ? T.dimPhysStopMsg(label) : T.dimPhysStartMsg(label), false)) return;
  const r = await api('/api/ops/dim_physics', {method:'POST', body: JSON.stringify({dim, paused})}).catch(()=>null);
  if (!r || !r.ok) { toast(T.opFail, 'bad'); return; }
  toast(paused ? T.dimPhysStopped(label) : T.dimPhysStarted(label), paused ? 'bad' : 'ok');
  loadStats();
}
async function doForceCurrent(){
  if (!SEL) return;
  const forced = !FORCED.has(SEL.uuid);
  if (forced && !await dropDetachedBefore(SELG)) return;
  setForcedBodies([SEL.uuid], forced);
}

/* 当前物理组的一键收敛。底层仍复用删除/常驻/副本三个既有事务；这里仅负责按终态串接，
   不另写一套存档修改逻辑，也永远不调用回收站恢复。 */
const AUTO_REPAIR_MAX_ROUNDS = 3;
const AUTO_REPAIR_POLL_MS = 2000;
const AUTO_REPAIR_POLL_LIMIT = 150;
let AUTO_REPAIR_RUN = null;
onServerReset(() => { AUTO_REPAIR_RUN = null; });

function autoRepairView(snapshot, uuid){
  for (const group of (snapshot.groups || [])) {
    if ((group.bodies || []).some(body => body.uuid === uuid)) {
      return {group, forced:new Set(snapshot.forced || [])};
    }
  }
  throw new Error(T.autoRepairMissing);
}
function autoRepairDetachedSignature(group){
  return group.bodies.filter(body => body.detached).map(body => body.uuid).sort().join(',');
}
function requireCompleteRepairView(view, acceptedDetached = ''){
  const group = view.group;
  if (group.members_omitted || (group.bodies || []).length !== group.members) {
    throw new Error(T.autoRepairTruncated);
  }
  if (group.detach_unsure && autoRepairDetachedSignature(group) !== acceptedDetached) {
    throw new Error(T.autoRepairUnsure);
  }
  return view;
}
function autoRepairFullyForced(view){
  const group = view.group;
  return group.loaded === group.members && group.bodies.every(body => view.forced.has(body.uuid));
}
function autoRepairHealthy(view){
  const group = view.group;
  if (!autoRepairFullyForced(view) || group.detached || group.dup) return false;
  return group.bodies.every(body => (body.copies || 1) === 1
    && (body.deps || 0) === group.members - 1);
}
function autoRepairCopyNeeded(scan){
  const versions = scan.versions || [];
  const expected = scan.runtime_members || scan.members || 0;
  return (scan.incomplete || []).length > 0 || versions.length > 1
    || versions.some(version => !version.complete || (version.redundant || 0) > 0
      || (version.members || 0) !== expected)
    || (scan.evidence_mismatches || []).length > 0;
}
function autoRepairCopyClean(scan){
  const versions = scan.versions || [];
  const expected = scan.runtime_members || scan.members || 0;
  const current = versions.find(version => version.id === scan.current_version);
  return !autoRepairCopyNeeded(scan) && versions.length === 1 && !!current?.complete
    && scan.current_state === 'known' && expected > 0
    && (scan.active_members || 0) === expected && (current.active_members || 0) === expected;
}
function autoRepairCopyDecision(scan){
  const expected = scan.runtime_members || scan.members || 0;
  const evidenceComplete = expected > 0 && (scan.active_members || 0) === expected;
  if (!autoRepairCopyNeeded(scan)) {
    return autoRepairCopyClean(scan) ? {kind:'clean'}
      : {kind:'blocked', reason:T.autoRepairEvidenceIncomplete(scan.active_members || 0, expected)};
  }
  const verdict = copyVerdict(scan);
  if (['READY_CLEAN','READY_REPAIR'].includes(verdict.kind)
      && !verdict.removed && evidenceComplete) {
    return {kind:'resolve', version:verdict.pick};
  }
  if (verdict.removed) return {kind:'blocked', reason:T.autoRepairCopyLoss(verdict.removed)};
  if (['READY_CLEAN','READY_REPAIR'].includes(verdict.kind) && !evidenceComplete) {
    return {kind:'blocked', reason:T.autoRepairEvidenceIncomplete(scan.active_members || 0, expected)};
  }
  const reason = ({
    EVIDENCE_SPLIT:T.copyCurrentMixed,
    EVIDENCE_STRAY:T.copyEvidenceStray,
    EVIDENCE_AMBIGUOUS:T.copyEvidenceAmbiguous,
    COLD_SELECT:T.copyColdSelectHint,
    RUNTIME_UNPROVEN:T.copyRuntimeUnproven,
    QUARANTINE_ONLY:T.autoRepairNoVersion,
    NOTHING_TO_DO:T.autoRepairNoVersion,
  })[verdict.kind] || T.copyCurrentUnknown;
  return {kind:'blocked', reason};
}
function requireAutoRepairRun(run){
  if (AUTO_REPAIR_RUN !== run || !run.ctx.fresh()) throw new Error('');
}
function autoRepairDelay(){
  return new Promise(resolve => setTimeout(resolve, AUTO_REPAIR_POLL_MS));
}
async function waitAutoRepairState(read, accept, run){
  for (let attempt = 0; attempt < AUTO_REPAIR_POLL_LIMIT; attempt++) {
    requireAutoRepairRun(run);
    const value = await read();
    requireAutoRepairRun(run);
    if (accept(value)) return value;
    await autoRepairDelay();
  }
  throw new Error(T.autoRepairRefreshTimeout);
}
function waitRepairGroup(uuid, accept, run){
  return waitAutoRepairState(async () => requireCompleteRepairView(
    autoRepairView(await api('/api/bodies'), uuid), run.acceptedDetached), accept, run);
}
function waitRepairCopies(uuid, accept, run){
  return waitAutoRepairState(() => api(`/api/body/${uuid}/copies`), accept, run);
}
async function runAutoRepairGroup(uuid, run){
  // 确认框可能挂很久；任何写操作前必须重新读取权威组，旧 SELG 只用于确认文案。
  let view = await waitRepairGroup(uuid, () => true, run);
  let removed = 0;
  for (let round = 0; round < AUTO_REPAIR_MAX_ROUNDS; round++) {
    requireAutoRepairRun(run);
    let detached = view.group.bodies.filter(body => body.detached);
    if (detached.length !== (view.group.detached || 0)) throw new Error(T.autoRepairTruncated);
    const anchor = view.group.bodies.find(body => !body.detached);
    if (!anchor) throw new Error(T.autoRepairMissing);
    uuid = anchor.uuid;
    run.uuid = uuid;
    if (detached.length) {
      const targets = detached.map(body => body.uuid);
      if (await batchDelete(targets, false) !== 'ok') throw new Error(T.autoRepairPartial);
      removed += targets.length;
      view = await waitRepairGroup(uuid,
        candidate => targets.every(target => !candidate.group.bodies.some(body => body.uuid === target)), run);
    }

    let scan = null;
    if (!autoRepairFullyForced(view)) {
      const accepted = await setForcedBodies([uuid], true);
      if (!accepted || (accepted.job && await awaitJob(accepted.job) !== 'ok')) {
        // 冷成员带多份副本时常驻必然选不出版本 —— 面板不猜,导流到「处理副本」由人选
        throw new Error(view.group.bodies.some(body => (body.copies || 1) > 1)
          ? T.autoRepairForceCopies : T.autoRepairPartial);
      }
      scan = await waitAutoRepairState(() => api(`/api/body/${uuid}/copies`), candidate => {
        const expected = candidate.runtime_members || 0;
        return expected > 0 && (candidate.active_members || 0) === expected;
      }, run);
      view = await waitRepairGroup(uuid, () => true, run);
    }

    if (!scan) {
      scan = await api(`/api/body/${uuid}/copies`);
      requireAutoRepairRun(run);
    }
    const copy = autoRepairCopyDecision(scan);
    if (copy.kind === 'blocked') throw new Error(copy.reason);
    if (copy.kind === 'resolve') {
      const accepted = await submitJob(`/api/body/${uuid}/resolve_copies`, {
        method:'POST', body:JSON.stringify({version:copy.version})
      });
      if (!accepted || (accepted.job && await awaitJob(accepted.job) !== 'ok')) {
        throw new Error(T.autoRepairPartial);
      }
      scan = await waitRepairCopies(uuid, autoRepairCopyClean, run);
    }

    view = await waitRepairGroup(uuid, candidate => !candidate.group.dup, run);
    detached = view.group.bodies.filter(body => body.detached);
    if (detached.length) continue;
    if (autoRepairHealthy(view) && autoRepairCopyClean(scan)) {
      return {members:view.group.members, removed};
    }
  }
  throw new Error(T.autoRepairChanging);
}
async function doAutoRepairCurrent(){
  if (!SEL || !SELG || AUTO_REPAIR_RUN) return;
  const group = SELG;
  const anchor = group.bodies.find(body => !body.detached);
  if (!anchor) { toast(T.autoRepairFailed + T.autoRepairMissing, 'bad'); return; }
  const uuid = anchor.uuid;
  const name = SEL.name || group.name || uuid.slice(0,8);
  const acceptedDetached = group.detach_unsure ? autoRepairDetachedSignature(group) : '';
  try { requireCompleteRepairView({group, forced:new Set(FORCED)}, acceptedDetached); }
  catch (error) { toast(T.autoRepairFailed + error.message, 'bad'); return; }
  const duplicateMembers = group.bodies.filter(body => (body.copies || 1) > 1).length;
  const ctx = captureCtx();
  if (!await askModal(T.autoRepairT, T.autoRepairMsg(
      name, group.members, group.detached || 0, duplicateMembers)
      + (group.detach_unsure ? T.autoRepairUnsureConfirm(group.detached || 0) : ''), false)) return;
  if (!ctx.fresh()) return;
  const run = {uuid, ctx, acceptedDetached};
  AUTO_REPAIR_RUN = run;
  renderDetail();
  try {
    const result = await runAutoRepairGroup(uuid, run);
    requireAutoRepairRun(run);
    toast(T.autoRepairDone(result.members, result.removed), 'ok');
  } catch (error) {
    if (AUTO_REPAIR_RUN === run && run.ctx.fresh() && error.message) {
      toast(T.autoRepairFailed + error.message, 'bad');
    }
  } finally {
    if (AUTO_REPAIR_RUN === run) {
      AUTO_REPAIR_RUN = null;
      await loadBodies();
      if (SEL) renderDetail();
    }
  }
}
/**
 * 常驻加载前先清掉断链残骸。常驻加载必须整组,而"整组"在 sable 眼里包含那些甩出去几百格
 * 的残骸(轴承方块记着对方 UUID 不撒手)—— 不清就是把几百个体一起钉进内存。
 * 删除走 expand:false,否则后端按依赖链一展开就把主体也删了。
 * 返回 false = 用户取消或删除失败,调用方不要继续。
 */
async function dropDetachedBefore(g){
  if (!g || !g.detached) return true;
  const det = g.bodies.filter(b => b.detached);
  // 成员被截断时手上的名单不全,删了等于只清一半 —— 交回给用户,不自作主张
  if (g.members_omitted || det.length !== g.detached) {
    return await askModal(T.detachedT, T.detachedTruncated(g.detached), false);
  }
  const blocks = det.reduce((sum, b) => sum + b.blocks, 0);
  // 参照系存疑时判定可能整个反过来(留下的和删掉的对调),这话必须在按确认之前说
  const msg = T.detachedMsg(g.name || T.unnamed, g.members, det.length, blocks, g.members - det.length)
    + (g.detach_unsure ? '\n\n' + T.detachedUnsure : '');
  if (!await askModal(T.detachedT, msg, false)) return false;
  if (await batchDelete(det.map(b => b.uuid), false) === 'ok') return true;
  // 没清干净就挂票 = 照样要去几千格外同步拉区块,实测就是这么把主线程卡死的。让用户自己定
  return await askModal(T.detachedT, T.detachedPartial, false);
}
async function doForceSelected(forced){
  const uuids = [...SELECTED].filter(u => forced !== FORCED.has(u));
  if (forced) {
    const groups = [...new Set(uuids.map(u => BODY_BY_UUID.get(u)?.g).filter(Boolean))];
    for (const g of groups) if (!await dropDetachedBefore(g)) return;
  }
  setForcedBodies(uuids, forced);
}
/* 传送玩家到选中结构顶面中心(必须落进包围盒,否则体在人到达前就被卸载);
   体未加载后端会先按依赖链强制加载 */
async function doTeleportPlayer(){
  if (!SEL) return;
  const pu = document.getElementById('tpPlayer').value;
  if (!pu) { toast(T.tpNoPlayers, 'bad'); return; }
  const p = PLAYERS.find(x=>x.uuid===pu);
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(T.tpPlayerT, T.tpPlayerMsg(p ? p.name : pu, nm), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/teleport_player?player=${pu}`, {method:'POST'});
}
async function doTeleport() {
  if (!SEL) return;
  const x = document.getElementById('tx').value, y = document.getElementById('ty').value, z = document.getElementById('tz').value;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(T.tpConfirmT, T.tpConfirm(nm,x,y,z), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/teleport?x=${x}&y=${y}&z=${z}`, {method:'POST'});
}
async function doDelete() {
  if (!SEL || !SELG) return;
  const group=SELG;
  const title=group.members>1?T.delGroupT:T.delConfirmT;
  const message=group.members>1?T.delGroupMsg(group.members,group.blocks)
    :T.delConfirm(SEL.name||SEL.uuid.slice(0,8),SEL.blocks);
  if (!await askModal(title,message,false)) return;
  await batchDelete(group.bodies.map(body=>body.uuid));
}
async function doDeleteRecommended(){
  const groups = lastVisibleGroups.filter(g => g.rec);
  if (!groups.length) return;
  let uuids = groups.flatMap(g => g.bodies.map(b=>b.uuid));
  const blocks = groups.reduce((s,g)=>s+g.blocks,0);
  const total = groups.reduce((s,g)=>s+g.members,0);   // 同上:后端展开后的真实数量
  if (!await askModal(T.recBatchT, T.recBatchMsg(groups.length, total, blocks), false)) return;
  if (total > 500) { toast(T.recTooMany, 'bad'); return; }
  await batchDelete(uuids);
}
async function batchDelete(uuids, expand = true){
  const r = await submitJob('/api/ops/batch_delete',
    {method:'POST', body: JSON.stringify({uuids, expand})});
  if (!r) return 'fail';
  const outcome = r.job ? await awaitJob(r.job) : 'ok';
  loadRecycle();
  return outcome;
}
/**
 * 等一个已提交的作业跑到终态,返回 'ok' | 'partial' | 'fail'。
 * submitJob 只等到"作业已受理" —— 拿它的返回值当成功判据,失败的删除也会一路放行。
 * 2026-08-08 实测:清理残骸 0/173 全失败,后续的常驻加载照样跑了,192 个体一起挂票崩服。
 */
async function awaitJob(seq){
  if (JOB_RESULTS.has(seq)) {
    const outcome = JOB_RESULTS.get(seq);
    JOB_RESULTS.delete(seq);
    return outcome;
  }
  const ctx = captureCtx();
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      JOB_WAITERS.delete(seq);
      resolve('fail');
    }, 900000);
    JOB_WAITERS.set(seq, {ctx, resolve, timer});
    syncBusyPolling();
  });
}
async function doAdopt() {
  if (!SEL) return;
  const nm = SEL.name || SEL.uuid.slice(0,8);
  if (!await askModal(T.adoptT, T.adoptMsg(nm), false)) return;
  await submitJob(`/api/body/${SEL.uuid}/adopt`, {method:'POST'});
}

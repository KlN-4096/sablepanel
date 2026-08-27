'use strict';
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

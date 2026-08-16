'use strict';
/* 完整依赖组副本选择:只读扫描/预览，确认后交给后台事务切换。 */

/* 副本处置的唯一判定入口:页面只读它的返回值,不再各处自己拼 current_state/active_members。
   终态表见 docs/tasks/copy-verdict-frontend.md。分界是用户 2026-08-07 拍板的:
   只有 READY_CLEAN(移出=0,零损失)自动选中;READY_WITH_LOSS 那道"看移出数字"的视线不能省。 */
function copyVerdict(scan){
  const versions   = scan.versions || [];
  const selectable = versions.filter(v => v.complete || v.repairable_current);
  const incomplete = (scan.incomplete || []).length;
  const evidence   = scan.active_members || 0;   // 全组有运行时活动指针的成员数
  const total      = scan.members || 0;

  // ① 没有任何完整候选 —— 只能隔离,不能设主版本
  if (!selectable.length)
    return incomplete ? {kind:'QUARANTINE_ONLY'} : {kind:'NOTHING_TO_DO'};

  // ② 证据分属不同版本 —— 唯一必须停手的终态
  if (scan.current_state === 'mixed')
    return {kind:'EVIDENCE_SPLIT'};

  // ③ 证据收敛 —— 唯一可以处理的终态
  if (scan.current_state === 'known'){
    const pick = selectable.find(v => v.id === scan.current_version);
    if (!pick) return {kind:'EVIDENCE_STRAY'};   // known 却不落在完整候选上:证据指着没法用的条目
    const removed = Math.max(0, total - (pick.members || 0));
    if (pick.repairable_current) return {
      kind:'READY_REPAIR', pick:pick.id, removed, missing:(pick.missing_dependencies||[]).length
    };
    return {kind: removed ? 'READY_WITH_LOSS' : 'READY_CLEAN', pick: pick.id, removed};
  }

  // ④ unknown 分两种,可行动性完全不同
  if (evidence === 0)
    return {kind:'NOT_LOADED'};                  // 体没加载 → 有明确出路(唤醒)

  // 有指针,但没落在唯一一个完整版本上
  const best = Math.max(...selectable.map(v => v.active_members || 0));
  return {kind: best < evidence ? 'EVIDENCE_STRAY' : 'EVIDENCE_AMBIGUOUS'};
}

async function openDedupe(){
  if (!SEL) return;
  const uuid = SEL.uuid;
  COPY_UUID = uuid; COPY_SCAN = null; COPY_VERSION = null; COPY_WAKE_SEQ = null;
  document.getElementById('copyBack').showModal();
  document.getElementById('copyPanelBody').innerHTML = `<div class="empty">${T.dedupeScanning}</div>`;
  document.getElementById('copyPanelStatus').textContent = '';
  document.getElementById('copyComp').innerHTML = '';
  document.getElementById('dedupeConfirm').disabled = true;
  movePreviewTo('copyPreviewHost');
  document.getElementById('copyPreviewNote').textContent = T.copyPreviewPending;
  disposeMesh(); MESH_DATA = MESH_UUID = MESH_SOURCE = null;
  document.getElementById('pvInfo').textContent = T.copyPreviewPending;
  renderComposition();
  resizeGL();
  return loadDedupeScan(uuid);
}
/* openDedupe 首扫与唤醒后的重扫共用;load('copies') 的序号会丢弃过期响应 */
function loadDedupeScan(uuid){
  return load('copies', () => api(`/api/body/${uuid}/copies`), result => {
    if (COPY_UUID !== uuid || result.uuid !== uuid) return;
    COPY_SCAN = result;
    // READY_CLEAN 自动选中 —— 仅当用户没手选过(重扫回来不抢人已选的版本)
    const verdict = copyVerdict(result);
    if (verdict.kind === 'READY_CLEAN' && COPY_VERSION === null) return selectCopyVersion(verdict.pick);
    renderDedupe(result);
  }, message => {
    if (COPY_UUID !== uuid) return;
    document.getElementById('copyPanelBody').innerHTML = `<div class="empty" style="color:var(--bad)">${esc(message)}</div>`;
    document.getElementById('copyPanelStatus').textContent = T.dedupeFail + message;
  });
}
/* NOT_LOADED 的出口:一键常驻加载(后端自动整组展开+冻结),作业结束后自动重扫。
   与列表入口同一条路:断链残骸确认流 + setForcedBodies 的乐观更新一并生效。 */
async function wakeDedupeTarget(){
  if (!COPY_UUID || COPY_WAKE_SEQ !== null) return;
  const uuid = COPY_UUID;
  const group = typeof BODY_BY_UUID !== 'undefined' && BODY_BY_UUID.get(uuid)?.g;
  if (group && !await dropDetachedBefore(group)) return;
  if (COPY_UUID !== uuid) return;   // 确认框挂起期间可能已换体/关弹层
  const r = await setForcedBodies([uuid], true);
  if (!r || !r.job || COPY_UUID !== uuid) return;
  COPY_WAKE_SEQ = r.job;
  // 快作业可能在 submitJob 内部的首轮轮询就已终态:那一轮收割看不到 WAKE_SEQ,这里自己补
  if (!JOB_WATCH.has(r.job) && !ACTIVE_JOBS.some(job => job.seq === r.job)) return dedupeJobsFinished([r.job]);
  if (COPY_SCAN) renderDedupe(COPY_SCAN);
}
/* data.js 的 reapFinishedJobs 每轮把到达终态的 seq 递过来 */
function dedupeJobsFinished(finished){
  if (COPY_WAKE_SEQ === null || !finished.includes(COPY_WAKE_SEQ)) return;
  COPY_WAKE_SEQ = null;
  if (COPY_UUID) loadDedupeScan(COPY_UUID);
}
function renderDedupe(scan){
  const versions = scan.versions || [];
  const current = versions.find(version=>version.id===scan.current_version);
  const currentBlocks = current ? current.blocks||0 : null;
  const incomplete = scan.incomplete || [];
  const rows = versions.map((version,index)=>{
    const delta = currentBlocks===null ? null : (version.blocks||0)-currentBlocks;
    const locations = (version.locations||[]).map(location=>
      `${location.dim} · ${location.x}, ${location.z}`).join(' / ');
    const missing = version.missing_dependencies||[];
    return `<button class="copyVersion ${COPY_VERSION===version.id?'on':''}" onclick="selectCopyVersion('${version.id}')">
      <input type="radio" tabindex="-1" ${COPY_VERSION===version.id?'checked':''}>
      <span class="copyVersionMain">
        <span class="copyVersionHead"><b>${T.copyVersionN(index+1)}</b>
          ${version.current?`<span class="tag acc">${T.copyCurrent}</span>`:''}
          <span class="tag ${version.complete?'ok':version.repairable_current?'warn':'bad'}">${version.complete?T.copyComplete:version.repairable_current?T.copyRepairable:T.copyIncomplete}</span>
          ${version.redundant?`<span class="tag warn">${T.copyRedundant(version.redundant)}</span>`:''}
        </span>
        <span class="copyVersionMeta">${T.copyVersionMeta(version.members||0,version.blocks||0,delta)} · ${T.copyActiveEvidence(version.active_members||0,version.members||0)}<br>${esc(locations||T.copyUnreachable)}
          ${missing.length?`<br>${T.copyMissing}: ${missing.map(esc).join(', ')}`:''}</span>
      </span></button>`;
  }).join('');
  const warning = incomplete.length
    ? `<div class="copyWarning">${T.copyQuarantineWarn(incomplete.length)}</div>` : '';
  const raw = !versions.length && incomplete.length ? `<table class="copyTable"><tbody>${incomplete.map(copy=>
    `<tr><td class="entry">${esc(copy.entry)}</td><td>${fmt(copy.blocks||0)}</td><td>${esc(copy.dim)}</td></tr>`).join('')}</tbody></table>` : '';
  const selected = versions.find(version=>version.id===COPY_VERSION);
  /* 从前 baseline 提示/按钮禁用/状态栏各拼一遍散字段 —— 同一个判定的三个入口会各自漂移。
     现在只问 copyVerdict 一次,其余全是查表。 */
  const verdict = copyVerdict(scan);
  const notice = ({
    EVIDENCE_SPLIT: `<div class="copyWarning">${T.copyCurrentMixed}</div>`,
    NOT_LOADED: `<div class="copyWarning">${T.copyNotLoadedHint} ${COPY_WAKE_SEQ!==null
      ? esc(T.copyWaking)
      : `<button onclick="wakeDedupeTarget()" title="${esc(T.copyWakeRisk)}">${T.copyWakeBtn}</button>`}</div>`,
    EVIDENCE_STRAY: `<div class="copyWarning">${T.copyEvidenceStray}</div>`,
    EVIDENCE_AMBIGUOUS: `<div class="copyWarning">${T.copyEvidenceAmbiguous}</div>`,
  })[verdict.kind] || '';
  document.getElementById('copyPanelBody').innerHTML = `<div class="copySummary">${T.copyGroupSummary(scan.members||0,versions.length)}</div>${notice}${warning}${rows||raw||`<div class="empty">${T.dedupeSingle}</div>`}${copySelectionDetails(scan,selected)}`;
  const confirm = document.getElementById('dedupeConfirm');
  confirm.disabled = verdict.kind==='QUARANTINE_ONLY' ? false
    : !['READY_CLEAN','READY_WITH_LOSS','READY_REPAIR'].includes(verdict.kind)
      || !selected || (!selected.complete && !selected.repairable_current);
  confirm.textContent = verdict.kind==='QUARANTINE_ONLY' ? T.copyQuarantineAll : T.dedupeConfirm;
  const status = ({
    READY_CLEAN: T.copyReadyClean,
    READY_WITH_LOSS: T.copyReadyLoss(verdict.removed),
    READY_REPAIR: T.copyReadyRepair(verdict.missing,verdict.removed),
    NOT_LOADED: COPY_WAKE_SEQ!==null ? T.copyWaking : T.copyCurrentUnknown,
    EVIDENCE_STRAY: T.copyEvidenceStray,
    EVIDENCE_AMBIGUOUS: T.copyEvidenceAmbiguous,
    EVIDENCE_SPLIT: T.copyCurrentMixed,
    QUARANTINE_ONLY: T.copyOnlyIncomplete,
    NOTHING_TO_DO: T.copyNoVersion,
  })[verdict.kind];
  document.getElementById('copyPanelStatus').textContent =
    selected && !selected.complete && !selected.repairable_current ? T.copyCannotSelect : status;
}
function copySelectionDetails(scan,selected){
  if (!selected) return '';
  const kept=selected.members||0, removed=Math.max(0,(scan.members||0)-kept);
  const impact=(selected.complete||selected.repairable_current)?`<div class="copyImpact">
    <span><b>${fmt(scan.members||0)}</b>${T.copyImpactTotal}</span>
    <span><b>${fmt(kept)}</b>${T.copyImpactKeep}</span>
    <span class="${removed?'warn':''}"><b>${fmt(removed)}</b>${T.copyImpactRemove}</span></div>`:'';
  const rows=(selected.copies||[]).filter(copy=>!copy.redundant).map(copy=>{
    const pos=Array.isArray(copy.pos)?copy.pos.map(value=>fmt(Math.round(value))).join(', '):copy.dim;
    return `<div class="copyMemberRow"><span><b>${esc(copy.name||copy.uuid.slice(0,8))}</b><br><small>${esc(copy.uuid)}</small></span><span>${fmt(copy.blocks||0)}<br><small>${esc(pos)}</small></span></div>`;
  }).join('');
  return `${impact}<details class="copyMembers"><summary>${T.copyMemberList(kept)}</summary>${rows}</details>`;
}
function selectCopyVersion(versionId){
  if (!COPY_SCAN || !COPY_UUID) return;
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===versionId);
  if (!version) return;
  COPY_VERSION = versionId;
  renderDedupe(COPY_SCAN);
  const target = (version.copies||[]).find(copy=>copy.uuid===COPY_UUID);
  document.getElementById('copyPreviewNote').textContent = T.copyPreviewSingle(
    target?.name || SEL?.name || COPY_UUID.slice(0,8));
  loadCopyVersionMesh(COPY_UUID, versionId);
}
/* restorePreview=false 用于切服/断开:那时旧服的 SEL 马上就要作废,再去拉一次它的
   mesh 是白跑一趟旧服,而且会把 pvInfo 写成"加载预览…"挂在那儿 */
function closeDedupe(restorePreview = true){
  document.getElementById('copyBack').close();
  movePreviewTo('bodyPreviewHost');
  COPY_SCAN = null; COPY_UUID = null; COPY_VERSION = null; COPY_WAKE_SEQ = null;
  document.getElementById('copyPreviewNote').textContent = '';
  document.getElementById('copyComp').innerHTML = '';
  resizeGL();
  if (restorePreview && SEL) loadMesh(SEL.uuid);
}
async function confirmDedupe(){
  if (!COPY_SCAN || !COPY_UUID) return;
  const selectable = (COPY_SCAN.versions||[]).filter(candidate=>candidate.complete||candidate.repairable_current);
  if (!selectable.length) {
    const count=(COPY_SCAN.incomplete||[]).length;
    if (!count||!await askModal(T.dedupeTitle,T.copyQuarantineAsk(count),false)) return;
    const accepted=await submitJob(`/api/body/${COPY_UUID}/quarantine_copies`,{method:'POST'});
    if (accepted) closeDedupe();
    return;
  }
  if (!COPY_VERSION) return;
  if (COPY_SCAN.current_state!=='known' || !COPY_SCAN.current_version) {
    toast(COPY_SCAN.current_state==='mixed'?T.copyCurrentMixed:T.copyCurrentUnknown,'bad');
    return;
  }
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===COPY_VERSION);
  if (!version || (!version.complete && !version.repairable_current)) return;
  const archived = Math.max(0,selectable.length-1);
  const quarantined = (COPY_SCAN.incomplete||[]).length;
  const kept=version.members||0, removed=Math.max(0,(COPY_SCAN.members||0)-kept);
  const message=T.copyResolveAsk({total:COPY_SCAN.members||0,keep:kept,removed,old:archived,
    incomplete:quarantined,repair:version.repairable_current?(version.missing_dependencies||[]).length:0});
  if (!await askModal(T.dedupeTitle,message,false)) return;
  const uuid = COPY_UUID, selected = COPY_VERSION;
  const accepted = await submitJob(`/api/body/${uuid}/resolve_copies`,
    {method:'POST',body:JSON.stringify({version:selected})});
  if (!accepted) return;
  const ctx = captureCtx();
  closeDedupe(false);
  const outcome = accepted.job ? await awaitJob(accepted.job) : 'ok';
  if (outcome==='ok' && ctx.fresh() && SEL?.uuid===uuid) loadMesh(uuid);
}
/* 弹层遮罩点击收起:监听器归弹层所有者(自 preview.js 挪入) */
document.getElementById('copyBack').addEventListener('mousedown',event=>{ if(event.target.id==='copyBack') closeDedupe(); });
/* ESC 也要走 closeDedupe:预览宿主与 SEL 的 mesh 要恢复,原生默认关闭做不了这些 */
document.getElementById('copyBack').addEventListener('cancel',event=>{ event.preventDefault(); closeDedupe(); });

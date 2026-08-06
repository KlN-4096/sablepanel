'use strict';
/* 完整依赖组副本选择:只读扫描/预览，确认后交给后台事务切换。 */
async function openDedupe(){
  if (!SEL) return;
  const uuid = SEL.uuid;
  COPY_UUID = uuid; COPY_SCAN = null; COPY_VERSION = null;
  document.getElementById('copyBack').style.display = 'flex';
  document.getElementById('copyPanelBody').innerHTML = `<div class="empty">${t('dedupeScanning')}</div>`;
  document.getElementById('copyPanelStatus').textContent = '';
  document.getElementById('copyComp').innerHTML = '';
  document.getElementById('dedupeConfirm').disabled = true;
  const preview = document.getElementById('previewWrap');
  document.getElementById('copyPreviewHost').appendChild(preview);
  document.getElementById('copyPreviewNote').textContent = t('copyPreviewPending');
  if (renderer && renderer.domElement.parentElement !== preview) preview.insertBefore(renderer.domElement, preview.firstChild);
  disposeMesh(); MESH_DATA = MESH_UUID = MESH_SOURCE = null;
  document.getElementById('pvInfo').textContent = t('copyPreviewPending');
  renderComposition();
  resizeGL();
  return load('copies', () => api(`/api/body/${uuid}/copies`), result => {
    if (COPY_UUID !== uuid || result.uuid !== uuid) return;
    COPY_SCAN = result;
    COPY_VERSION = null;
    renderDedupe(result);
  }, message => {
    if (COPY_UUID !== uuid) return;
    document.getElementById('copyPanelBody').innerHTML = `<div class="empty" style="color:var(--bad)">${esc(message)}</div>`;
    document.getElementById('copyPanelStatus').textContent = t('dedupeFail') + message;
  });
}
function renderDedupe(scan){
  const versions = scan.versions || [];
  const currentState = scan.current_state || 'unknown';
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
        <span class="copyVersionHead"><b>${t('copyVersionN')(index+1)}</b>
          ${version.current?`<span class="tag acc">${t('copyCurrent')}</span>`:''}
          <span class="tag ${version.complete?'ok':'bad'}">${version.complete?t('copyComplete'):t('copyIncomplete')}</span>
          ${version.redundant?`<span class="tag warn">${t('copyRedundant')(version.redundant)}</span>`:''}
        </span>
        <span class="copyVersionMeta">${t('copyVersionMeta')(version.members||0,version.blocks||0,delta)} · ${t('copyActiveEvidence')(version.active_members||0,version.members||0)}<br>${esc(locations||t('copyUnreachable'))}
          ${missing.length?`<br>${t('copyMissing')}: ${missing.map(esc).join(', ')}`:''}</span>
      </span></button>`;
  }).join('');
  const warning = incomplete.length
    ? `<div class="copyWarning">${t('copyQuarantineWarn')(incomplete.length)}</div>` : '';
  const raw = !versions.length && incomplete.length ? `<table class="copyTable"><tbody>${incomplete.map(copy=>
    `<tr><td class="entry">${esc(copy.entry)}</td><td>${fmt(copy.blocks||0)}</td><td>${esc(copy.dim)}</td></tr>`).join('')}</tbody></table>` : '';
  const selected = versions.find(version=>version.id===COPY_VERSION);
  const baseline = currentState==='mixed' ? `<div class="copyWarning">${t('copyCurrentMixed')}</div>`
    : currentState==='unknown' ? `<div class="copyWarning">${t('copyCurrentUnknown')}</div>` : '';
  document.getElementById('copyPanelBody').innerHTML = `<div class="copySummary">${t('copyGroupSummary')(scan.members||0,versions.length)}</div>${baseline}${warning}${rows||raw||`<div class="empty">${t('dedupeSingle')}</div>`}${copySelectionDetails(scan,selected)}`;
  const complete = versions.filter(version=>version.complete);
  const quarantineOnly = !complete.length && incomplete.length;
  const confirm = document.getElementById('dedupeConfirm');
  confirm.disabled = !quarantineOnly && (currentState!=='known' || !selected || !selected.complete);
  confirm.textContent = quarantineOnly ? t('copyQuarantineAll') : t('dedupeConfirm');
  let status = versions.length ? t('copyChooseVersion') : t('copyNoVersion');
  if (selected) status = selected.complete ? t('copyReady') : t('copyCannotSelect');
  if (currentState==='unknown') status = t('copyCurrentUnknown');
  if (currentState==='mixed') status = t('copyCurrentMixed');
  if (quarantineOnly) status = t('copyOnlyIncomplete');
  document.getElementById('copyPanelStatus').textContent = status;
}
function copySelectionDetails(scan,selected){
  if (!selected) return '';
  const kept=selected.members||0, removed=Math.max(0,(scan.members||0)-kept);
  const impact=selected.complete?`<div class="copyImpact">
    <span><b>${fmt(scan.members||0)}</b>${t('copyImpactTotal')}</span>
    <span><b>${fmt(kept)}</b>${t('copyImpactKeep')}</span>
    <span class="${removed?'warn':''}"><b>${fmt(removed)}</b>${t('copyImpactRemove')}</span></div>`:'';
  const rows=(selected.copies||[]).filter(copy=>!copy.redundant).map(copy=>{
    const pos=Array.isArray(copy.pos)?copy.pos.map(value=>fmt(Math.round(value))).join(', '):copy.dim;
    return `<div class="copyMemberRow"><span><b>${esc(copy.name||copy.uuid.slice(0,8))}</b><br><small>${esc(copy.uuid)}</small></span><span>${fmt(copy.blocks||0)}<br><small>${esc(pos)}</small></span></div>`;
  }).join('');
  return `${impact}<details class="copyMembers"><summary>${t('copyMemberList')(kept)}</summary>${rows}</details>`;
}
function selectCopyVersion(versionId){
  if (!COPY_SCAN || !COPY_UUID) return;
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===versionId);
  if (!version) return;
  COPY_VERSION = versionId;
  renderDedupe(COPY_SCAN);
  const target = (version.copies||[]).find(copy=>copy.uuid===COPY_UUID);
  document.getElementById('copyPreviewNote').textContent = t('copyPreviewSingle')(
    target?.name || SEL?.name || COPY_UUID.slice(0,8));
  loadCopyVersionMesh(COPY_UUID, versionId);
}
/* restorePreview=false 用于切服/断开:那时旧服的 SEL 马上就要作废,再去拉一次它的
   mesh 是白跑一趟旧服,而且会把 pvInfo 写成"加载预览…"挂在那儿 */
function closeDedupe(restorePreview = true){
  document.getElementById('copyBack').style.display = 'none';
  const preview = document.getElementById('previewWrap');
  const host = document.getElementById('bodyPreviewHost');
  if (preview.parentElement!==host) host.appendChild(preview);
  if (renderer && renderer.domElement.parentElement!==preview) preview.insertBefore(renderer.domElement, preview.firstChild);
  COPY_SCAN = null; COPY_UUID = null; COPY_VERSION = null;
  document.getElementById('copyPreviewNote').textContent = '';
  document.getElementById('copyComp').innerHTML = '';
  resizeGL();
  if (restorePreview && SEL) loadMesh(SEL.uuid);
}
async function confirmDedupe(){
  if (!COPY_SCAN || !COPY_UUID) return;
  const complete = (COPY_SCAN.versions||[]).filter(candidate=>candidate.complete);
  if (!complete.length) {
    const count=(COPY_SCAN.incomplete||[]).length;
    if (!count||!await askModal(t('dedupeTitle'),t('copyQuarantineAsk')(count),false)) return;
    const accepted=await submitJob(`/api/body/${COPY_UUID}/quarantine_copies`,{method:'POST'},t('copyQuarantineOp'));
    if (accepted) closeDedupe();
    return;
  }
  if (!COPY_VERSION) return;
  if (COPY_SCAN.current_state!=='known' || !COPY_SCAN.current_version) {
    toast(COPY_SCAN.current_state==='mixed'?t('copyCurrentMixed'):t('copyCurrentUnknown'),'bad');
    return;
  }
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===COPY_VERSION);
  if (!version || !version.complete) return;
  const archived = Math.max(0,(COPY_SCAN.versions||[]).filter(candidate=>candidate.complete).length-1);
  const quarantined = (COPY_SCAN.incomplete||[]).length;
  const kept=version.members||0, removed=Math.max(0,(COPY_SCAN.members||0)-kept);
  const message=t('copyResolveAsk')({total:COPY_SCAN.members||0,keep:kept,removed,old:archived,incomplete:quarantined});
  if (!await askModal(t('dedupeTitle'),message,false)) return;
  const uuid = COPY_UUID, selected = COPY_VERSION;
  const accepted = await submitJob(`/api/body/${uuid}/resolve_copies`,
    {method:'POST',body:JSON.stringify({version:selected})},t('copyResolveOp'));
  if (accepted) closeDedupe();
}

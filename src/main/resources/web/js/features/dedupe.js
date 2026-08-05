'use strict';
/* 完整依赖组副本选择:只读扫描/预览，确认后交给后台事务切换。 */
async function openDedupe(){
  if (!SEL) return;
  const uuid = SEL.uuid;
  COPY_UUID = uuid; COPY_SCAN = null; COPY_VERSION = null;
  document.getElementById('copyBack').style.display = 'flex';
  document.getElementById('copyPanelBody').innerHTML = `<div class="empty">${t('dedupeScanning')}</div>`;
  document.getElementById('copyPanelStatus').textContent = '';
  document.getElementById('dedupeConfirm').disabled = true;
  const preview = document.getElementById('previewWrap');
  document.getElementById('copyPreviewHost').appendChild(preview);
  if (renderer && renderer.domElement.parentElement !== preview) preview.insertBefore(renderer.domElement, preview.firstChild);
  resizeGL();
  try {
    const result = await api(`/api/body/${uuid}/copies`);
    if (COPY_UUID !== uuid || result.uuid !== uuid) return;
    COPY_SCAN = result;
    const versions = result.versions || [];
    const initial = versions.find(version=>version.id===result.current_version)
      || versions.find(version=>version.complete) || versions[0];
    COPY_VERSION = initial ? initial.id : null;
    renderDedupe(result);
    if (initial) loadCopyVersionMesh(uuid, initial.id);
  } catch(e) {
    if (COPY_UUID !== uuid) return;
    document.getElementById('copyPanelBody').innerHTML = `<div class="empty" style="color:var(--bad)">${esc(e.message)}</div>`;
    document.getElementById('copyPanelStatus').textContent = t('dedupeFail') + e.message;
  }
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
        <span class="copyVersionHead"><b>${t('copyVersionN')(index+1)}</b>
          ${version.current?`<span class="tag acc">${t('copyCurrent')}</span>`:''}
          <span class="tag ${version.complete?'ok':'bad'}">${version.complete?t('copyComplete'):t('copyIncomplete')}</span>
          ${version.redundant?`<span class="tag warn">${t('copyRedundant')(version.redundant)}</span>`:''}
        </span>
        <span class="copyVersionMeta">${t('copyVersionMeta')(version.members||0,version.blocks||0,delta)}<br>${esc(locations||t('copyUnreachable'))}
          ${missing.length?`<br>${t('copyMissing')}: ${missing.map(esc).join(', ')}`:''}</span>
      </span></button>`;
  }).join('');
  const warning = incomplete.length
    ? `<div class="copyWarning">${t('copyQuarantineWarn')(incomplete.length)}</div>` : '';
  const raw = !versions.length && incomplete.length ? `<table class="copyTable"><tbody>${incomplete.map(copy=>
    `<tr><td class="entry">${esc(copy.entry)}</td><td>${fmt(copy.blocks||0)}</td><td>${esc(copy.dim)}</td></tr>`).join('')}</tbody></table>` : '';
  document.getElementById('copyPanelBody').innerHTML = `<div class="copySummary">${t('copyGroupSummary')(scan.members||0,versions.length)}</div>${warning}${rows||raw||`<div class="empty">${t('dedupeSingle')}</div>`}`;
  const selected = versions.find(version=>version.id===COPY_VERSION);
  const complete = versions.filter(version=>version.complete);
  const quarantineOnly = !complete.length && incomplete.length;
  const confirm = document.getElementById('dedupeConfirm');
  confirm.disabled = !quarantineOnly && (!selected || !selected.complete);
  confirm.textContent = quarantineOnly ? t('copyQuarantineAll') : t('dedupeConfirm');
  document.getElementById('copyPanelStatus').textContent = quarantineOnly ? t('copyOnlyIncomplete') : selected
    ? (selected.complete?t('copyReady')+(scan.current_version?'':` · ${t('copyCurrentUnknown')}`):t('copyCannotSelect')) : t('copyNoVersion');
}
function selectCopyVersion(versionId){
  if (!COPY_SCAN || !COPY_UUID) return;
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===versionId);
  if (!version) return;
  COPY_VERSION = versionId;
  renderDedupe(COPY_SCAN);
  loadCopyVersionMesh(COPY_UUID, versionId);
}
function closeDedupe(){
  document.getElementById('copyBack').style.display = 'none';
  const preview = document.getElementById('previewWrap');
  const host = document.getElementById('bodyPreviewHost');
  if (preview.parentElement!==host) host.appendChild(preview);
  if (renderer && renderer.domElement.parentElement!==preview) preview.insertBefore(renderer.domElement, preview.firstChild);
  COPY_SCAN = null; COPY_UUID = null; COPY_VERSION = null;
  document.getElementById('copyComp').innerHTML = '';
  resizeGL();
  if (SEL) loadMesh(SEL.uuid);
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
  const version = (COPY_SCAN.versions||[]).find(candidate=>candidate.id===COPY_VERSION);
  if (!version || !version.complete) return;
  const archived = Math.max(0,(COPY_SCAN.versions||[]).filter(candidate=>candidate.complete).length-1);
  const quarantined = (COPY_SCAN.incomplete||[]).length;
  const message=t('copyResolveAsk')(archived,quarantined)
    +(COPY_SCAN.current_version?'':`\n${t('copyCurrentUnknownWarn')}`);
  if (!await askModal(t('dedupeTitle'),message,false)) return;
  const uuid = COPY_UUID, selected = COPY_VERSION;
  const accepted = await submitJob(`/api/body/${uuid}/resolve_copies`,
    {method:'POST',body:JSON.stringify({version:selected})},t('copyResolveOp'));
  if (accepted) closeDedupe();
}

'use strict';
/* 副本去重面板:实时扫描展示 + 确认执行 */
/* ===================== 操作 ===================== */
async function openDedupe(){
  if (!SEL) return;
  const uuid = SEL.uuid;
  COPY_UUID = uuid; COPY_SCAN = null;
  document.getElementById('copyBack').style.display = 'flex';
  document.getElementById('copyPanelBody').innerHTML = `<div class="empty">${t('dedupeScanning')}</div>`;
  document.getElementById('copyPanelStatus').textContent = '';
  document.getElementById('dedupeConfirm').disabled = true;
  try {
    const result = await api(`/api/body/${uuid}/copies`);
    if (COPY_UUID !== uuid || result.uuid !== uuid) return;
    COPY_SCAN = result; renderDedupe(result);
  } catch(e) {
    if (COPY_UUID !== uuid) return;
    document.getElementById('copyPanelBody').innerHTML = `<div class="empty" style="color:var(--bad)">${esc(e.message)}</div>`;
    document.getElementById('copyPanelStatus').textContent = t('dedupeFail') + e.message;
  }
}
function renderDedupe(scan){
  const copies = scan.copies || [];
  const safe = scan.identical && copies.length > 1;
  const summary = copies.length < 2 ? t('dedupeSingle') : scan.identical ? t('dedupeSafe')(copies.length) : t('dedupeUnsafe');
  document.getElementById('copyPanelBody').innerHTML = `<div class="copySummary">${summary}</div>
    <table class="copyTable"><thead><tr><th>${t('copyEntry')}</th><th>${t('copyPointer')}</th><th>${t('copyBlocks')}</th><th>${t('copyPlace')}</th><th>${t('copyCompare')}</th></tr></thead>
    <tbody>${copies.map(copy=>`<tr>
      <td class="entry">${copy.keep?`<span class="tag acc">${t('copyKeep')}</span><br>`:''}${esc(copy.entry)}</td>
      <td>${copy.reachable?`<span class="tag ok">${t('copyReachable')(copy.pointer_count||0)}</span>`:`<span class="tag bad">${t('copyUnreachable')}</span>`}</td>
      <td class="mono">${fmt(copy.blocks||0)}</td>
      <td class="mono">${esc(copy.dim)}<br>${(copy.pos||[]).map(v=>Number(v).toFixed(1)).join(', ')}<br>${(copy.size||[]).map(v=>Number(v).toFixed(1)).join(' × ')}</td>
      <td><span class="tag ${copy.identical?'ok':'bad'}">${copy.identical?t('copySame'):t('copyDifferent')}</span></td>
    </tr>`).join('')}</tbody></table>`;
  document.getElementById('copyPanelStatus').textContent = summary;
  document.getElementById('dedupeConfirm').disabled = !safe;
}
function closeDedupe(){
  document.getElementById('copyBack').style.display = 'none';
  COPY_SCAN = null; COPY_UUID = null;
}
async function confirmDedupe(){
  if (!COPY_SCAN || !COPY_SCAN.identical || (COPY_SCAN.copies||[]).length < 2 || !COPY_UUID) return;
  const uuid = COPY_UUID;
  const removeCount = COPY_SCAN.copies.length - 1;
  if (!await askModal(t('dedupeTitle'),t('dedupeAsk')(removeCount),false)) return;
  if (COPY_UUID !== uuid) return;
  busy(t('loading'));
  try {
    const result = await api(`/api/body/${uuid}/deduplicate`,{method:'POST'});
    closeDedupe();
    toast(t('dedupeDone')(result.removed||0),'ok');
    await loadBodies();
  } catch(e) { toast(t('dedupeFail')+e.message,'bad'); }
  busy(null);
}

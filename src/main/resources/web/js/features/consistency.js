'use strict';
/* 启动只读一致性报告 + 显式选择修复。 */
let CONSISTENCY = null, CONSISTENCY_POLL_GEN = 0;
/* 报告与等待循环都属于当前服务器;POLL_GEN 另管"新一轮扫描作废旧等待",与切服代次独立 */
onServerReset(() => { CONSISTENCY = null; CONSISTENCY_POLL_GEN++; });
function consistencyDismissKey(){ return `spConsistencyDismissed:${CURSRV||'self'}`; }
async function scheduleStartupConsistency(){
  const gen = ++CONSISTENCY_POLL_GEN;
  const ctx = captureCtx();
  const fresh = () => ctx.fresh() && gen===CONSISTENCY_POLL_GEN;
  for (let attempt=0;attempt<45;attempt++) {
    if (!fresh()) return;
    try {
      const report = await api('/api/consistency');
      if (!fresh()) return;
      if (report.ready) {
        CONSISTENCY = report;
        const dismissed = localStorage.getItem(consistencyDismissKey());
        if ((report.issue_count||0)>0 && !report.error && dismissed!==report.scan_id) openConsistency(report);
        return;
      }
    } catch(e){ /* 抖一次就重试:循环本来就有 45 次上限,放弃等于报告永远不出现 */ }
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
}
async function openConsistency(report){
  try { CONSISTENCY = report || await api('/api/consistency'); }
  catch(e){ toast(t('consistencyFail')+e.message,'bad'); return; }
  document.getElementById('consistencyBack').style.display='flex';
  renderConsistency();
}
/* dismiss=false 是"替用户收起来",不是"用户读过了"。
   记了"已读"的后果是永久的:scan_id 只在重新扫描时才变,所以那个服恢复回来之后,
   同一份报告再也不会弹 —— 而用户从头到尾没看见过它。切服/断开走的就是这条路。 */
function closeConsistency(dismiss = true){
  document.getElementById('consistencyBack').style.display='none';
  if (dismiss&&CONSISTENCY&&CONSISTENCY.scan_id) localStorage.setItem(consistencyDismissKey(),CONSISTENCY.scan_id);
}
function renderConsistency(){
  const report=CONSISTENCY||{};
  const pointers=report.dangling_pointers||[], forced=report.stale_forced||[], paused=report.stale_paused||[];
  const section=(title,cls,items,row)=>items.length?`<section class="consistencySection"><h4>${title}<span class="tag warn">${items.length}</span></h4>${items.map(item=>
    `<label class="consistencyRow"><input type="checkbox" class="${cls}" value="${typeof item==='string'?item:item.id}" checked>${row(item)}</label>`).join('')}</section>`:'';
  let content=`<div class="consistencySummary">
    <div class="consistencyMetric"><b>${fmt(pointers.reduce((sum,item)=>sum+(item.count||1),0))}</b><span>${t('consistencyPointers')}</span></div>
    <div class="consistencyMetric"><b>${fmt(forced.length)}</b><span>${t('consistencyForced')}</span></div>
    <div class="consistencyMetric"><b>${fmt(paused.length)}</b><span>${t('consistencyPaused')}</span></div></div>`;
  const repaired=report.last_repair;
  if (repaired) {
    const failed=repaired.failed||[];
    content+=`<div class="copyWarning"><b>${t('consistencyRepairResult')(repaired.ok||0,repaired.total||0)}</b>`
      +(repaired.warning?`<br>${esc(repaired.warning)}`:'')
      +(repaired.backup?`<br>${t('consistencyBackup')}: <span class="mono">${esc(repaired.backup)}</span>`:'')
      +(failed.length?`<br>${t('consistencyRepairFailed')(failed.length)}<div class="mono">${failed.slice(0,100).map(esc).join('<br>')}</div>`:'')
      +`</div>`;
  }
  if (report.error) content+=`<div class="copyWarning">${esc(report.error)}</div>`;
  else if (!(report.issue_count||0)) content+=`<div class="empty">${t('consistencyHealthy')}</div>`;
  else content+=section(t('consistencyPointers'),'cPointer',pointers,item=>
      `<span class="mono">${esc(item.target)}<br><small>${esc(item.dim)} · chunk ${item.chunk_x}, ${item.chunk_z}</small></span><small>×${item.count||1}</small>`)
    +section(t('consistencyForced'),'cForced',forced,item=>`<span class="mono">${item}</span><small>${t('consistencyMissingBody')}</small>`)
    +section(t('consistencyPaused'),'cPaused',paused,item=>`<span class="mono">${item}</span><small>${t('consistencyMissingBody')}</small>`);
  if (report.truncated) content+=`<div class="copyWarning">${t('consistencyTruncated')}</div>`;
  document.getElementById('consistencyBody').innerHTML=content;
  document.getElementById('consistencyStatus').textContent=report.scanned_at?new Date(report.scanned_at).toLocaleString():t('loading');
  document.getElementById('consistencyRepair').disabled=!!report.error||!(report.issue_count||0);
}
function selectedConsistency(cls){
  return [...document.querySelectorAll(`.${cls}:checked`)].map(input=>input.value);
}
async function repairConsistency(){
  if (!CONSISTENCY) return;
  const pointers=selectedConsistency('cPointer'), forced=selectedConsistency('cForced'), paused=selectedConsistency('cPaused');
  const total=pointers.length+forced.length+paused.length;
  if (!total) { toast(t('consistencyNone'),'bad'); return; }
  if (!await askModal(t('consistencyTitle'),t('consistencyAsk')(total),false)) return;
  const previous=CONSISTENCY.scan_id;
  const accepted=await submitJob('/api/consistency/repair',{method:'POST',body:JSON.stringify({
    scan_id:previous,pointers,forced,paused})},t('consistencyRepairOp'));
  if (!accepted) return;
  closeConsistency();
  waitForConsistencyChange(previous,true);
}
async function runConsistencyScan(){
  const previous=CONSISTENCY&&CONSISTENCY.scan_id;
  const accepted=await submitJob('/api/consistency/scan',{method:'POST'},t('consistencyScanOp'));
  if (accepted) waitForConsistencyChange(previous,true);
}
async function waitForConsistencyChange(previous,open){
  const gen=++CONSISTENCY_POLL_GEN;
  const ctx=captureCtx();
  const fresh=()=>ctx.fresh()&&gen===CONSISTENCY_POLL_GEN;
  for (let attempt=0;attempt<90;attempt++) {
    await new Promise(resolve=>setTimeout(resolve,1000));
    if (!fresh()) return;
    try {
      const report=await api('/api/consistency');
      if (!fresh()) return;
      if (report.ready&&report.scan_id&&report.scan_id!==previous) {
        CONSISTENCY=report;
        if (open) openConsistency(report);
        return;
      }
    } catch(e){ /* 同上:这是作业跑完之后等新报告,中途一次抖动不该让人白点一次扫描 */ }
  }
}

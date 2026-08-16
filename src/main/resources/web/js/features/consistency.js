'use strict';
/* 启动只读一致性报告 + 显式选择修复。 */
let CONSISTENCY = null, CONSISTENCY_POLL_GEN = 0;
/* 报告与等待循环都属于当前服务器;POLL_GEN 另管"新一轮扫描作废旧等待",与切服代次独立 */
onServerReset(() => { CONSISTENCY = null; CONSISTENCY_POLL_GEN++; });
function consistencyDismissKey(){ return `spConsistencyDismissed:${CURSRV||'self'}`; }
/* 报告轮询骨架(启动等待/作业后等待共用):gen+ctx 双守卫,谓词命中返回报告,超时/作废返回 null。
   delayFirst=false 立刻探一次(启动路径);true 先睡一秒(作业刚提交,报告不可能立刻变) */
async function pollConsistency(attempts, delayFirst, done){
  const gen = ++CONSISTENCY_POLL_GEN;
  const ctx = captureCtx();
  const fresh = () => ctx.fresh() && gen===CONSISTENCY_POLL_GEN;
  for (let attempt=0;attempt<attempts;attempt++) {
    if (attempt || delayFirst) await new Promise(resolve=>setTimeout(resolve,1000));
    if (!fresh()) return null;
    try {
      const report = await api('/api/consistency');
      if (!fresh()) return null;
      if (done(report)) return report;
    } catch(e){ /* 抖一次就重试:循环有上限,放弃等于结果永远不出现 */ }
  }
  return null;
}
async function scheduleStartupConsistency(){
  const report = await pollConsistency(45, false, r => r.ready);
  if (!report) return;
  CONSISTENCY = report;
  const dismissed = localStorage.getItem(consistencyDismissKey());
  if ((report.issue_count||0)>0 && !report.error && dismissed!==report.scan_id) openConsistency(report);
}
async function openConsistency(report){
  try { CONSISTENCY = report || await api('/api/consistency'); }
  catch(e){ toast(T.consistencyFail+e.message,'bad'); return; }
  document.getElementById('consistencyBack').showModal();
  renderConsistency();
}
/* dismiss=false 是"替用户收起来",不是"用户读过了"。
   记了"已读"的后果是永久的:scan_id 只在重新扫描时才变,所以那个服恢复回来之后,
   同一份报告再也不会弹 —— 而用户从头到尾没看见过它。切服/断开走的就是这条路。 */
function closeConsistency(dismiss = true){
  document.getElementById('consistencyBack').close();
  if (dismiss&&CONSISTENCY&&CONSISTENCY.scan_id) localStorage.setItem(consistencyDismissKey(),CONSISTENCY.scan_id);
}
function renderConsistency(){
  const report=CONSISTENCY||{};
  const pointers=report.dangling_pointers||[], tracking=report.stale_tracking_points||[],
    forced=report.stale_forced||[], paused=report.stale_paused||[];
  const section=(title,cls,items,row)=>items.length?`<section class="consistencySection"><h4>${title}<span class="tag warn">${items.length}</span></h4>${items.map(item=>
    `<label class="consistencyRow"><input type="checkbox" class="${cls}" value="${typeof item==='string'?item:item.id}" checked>${row(item)}</label>`).join('')}</section>`:'';
  let content=`<div class="consistencySummary">
    <div class="consistencyMetric"><b>${fmt(pointers.reduce((sum,item)=>sum+(item.count||1),0))}</b><span>${T.consistencyPointers}</span></div>
    <div class="consistencyMetric"><b>${fmt(tracking.length)}</b><span>${T.consistencyTracking}</span></div>
    <div class="consistencyMetric"><b>${fmt(forced.length)}</b><span>${T.consistencyForced}</span></div>
    <div class="consistencyMetric"><b>${fmt(paused.length)}</b><span>${T.consistencyPaused}</span></div></div>`;
  const repaired=report.last_repair;
  if (repaired) {
    const failed=repaired.failed||[];
    content+=`<div class="copyWarning"><b>${T.consistencyRepairResult(repaired.ok||0,repaired.total||0)}</b>`
      +(repaired.warning?`<br>${esc(repaired.warning)}`:'')
      +(repaired.backup?`<br>${T.consistencyBackup}: <span class="mono">${esc(repaired.backup)}</span>`:'')
      +(failed.length?`<br>${T.consistencyRepairFailed(failed.length)}<div class="mono">${failed.slice(0,100).map(esc).join('<br>')}</div>`:'')
      +`</div>`;
  }
  if (report.error) content+=`<div class="copyWarning">${esc(report.error)}</div>`;
  else if (!(report.issue_count||0)) content+=`<div class="empty">${T.consistencyHealthy}</div>`;
  else content+=section(T.consistencyPointers,'cPointer',pointers,item=>
      `<span class="mono">${esc(item.target)}<br><small>${esc(item.dim)} · chunk ${item.chunk_x}, ${item.chunk_z}</small></span><small>×${item.count||1}</small>`)
    +section(T.consistencyTracking,'cTracking',tracking,item=>
      `<span class="mono">${esc(item.tracking_id)}<br><small>${esc(item.target)} · chunk ${item.chunk_x}, ${item.chunk_z}</small></span><small>${T.consistencyMissingSlot}</small>`)
    +section(T.consistencyForced,'cForced',forced,item=>`<span class="mono">${item}</span><small>${T.consistencyMissingBody}</small>`)
    +section(T.consistencyPaused,'cPaused',paused,item=>`<span class="mono">${item}</span><small>${T.consistencyMissingBody}</small>`);
  if (report.truncated) content+=`<div class="copyWarning">${T.consistencyTruncated}</div>`;
  document.getElementById('consistencyBody').innerHTML=content;
  document.getElementById('consistencyStatus').textContent=report.scanned_at?fmtDateTime(report.scanned_at):T.loading;
  document.getElementById('consistencyRepair').disabled=!!report.error||!(report.issue_count||0);
}
async function repairConsistency(){
  if (!CONSISTENCY) return;
  const pointers=checkedValues('cPointer'), tracking=checkedValues('cTracking'),
    forced=checkedValues('cForced'), paused=checkedValues('cPaused');
  const total=pointers.length+tracking.length+forced.length+paused.length;
  if (!total) { toast(T.consistencyNone,'bad'); return; }
  if (!await askModal(T.consistencyTitle,T.consistencyAsk(total),false)) return;
  const previous=CONSISTENCY.scan_id;
  const accepted=await submitJob('/api/consistency/repair',{method:'POST',body:JSON.stringify({
    scan_id:previous,pointers,tracking,forced,paused})});
  if (!accepted) return;
  closeConsistency();
  waitForConsistencyChange(previous,true);
}
async function runConsistencyScan(){
  const previous=CONSISTENCY&&CONSISTENCY.scan_id;
  const accepted=await submitJob('/api/consistency/scan',{method:'POST'});
  if (accepted) waitForConsistencyChange(previous,true);
}
async function waitForConsistencyChange(previous,open){
  const report = await pollConsistency(90, true, r => r.ready && r.scan_id && r.scan_id!==previous);
  if (!report) return;
  CONSISTENCY = report;
  if (open) openConsistency(report);
}
/* 弹层遮罩点击收起:监听器归弹层所有者(自 preview.js 挪入) */
document.getElementById('consistencyBack').addEventListener('mousedown',event=>{ if(event.target.id==='consistencyBack') closeConsistency(); });
/* ESC(原生 cancel)= 用户主动关闭,与点遮罩同语义:记"已读" */
document.getElementById('consistencyBack').addEventListener('cancel',event=>{ event.preventDefault(); closeConsistency(); });

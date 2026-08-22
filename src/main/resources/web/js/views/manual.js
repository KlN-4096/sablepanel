'use strict';
/* 使用说明面板 */
function openManual(){
  document.getElementById('manualBack').showModal();
  renderManual();
}
function closeManual(){ document.getElementById('manualBack').close(); }
function setManualTab(key){ MANUAL_TAB = key; renderManual(); }
function renderManual(){
  const pages = MANUAL[LANG] || MANUAL.zh;
  const page = pages.find(item=>item.k===MANUAL_TAB) || pages[0];
  MANUAL_TAB = page.k;
  document.getElementById('manualTabs').innerHTML = pages.map(item=>
    `<button class="${item.k===MANUAL_TAB?'on':''}" onclick="setManualTab('${item.k}')">${item.label}</button>`).join('');
  document.getElementById('manualContent').innerHTML = `<h3>${page.label}</h3>` +
    page.sections.map(section=>`<h4>${section.h}</h4>${section.body}`).join('');
}
/* 弹层遮罩点击收起(点 ::backdrop 时事件目标就是 dialog 自身)+ ESC 统一走 closeManual */
document.getElementById('manualBack').addEventListener('mousedown',event=>{ if(event.target.id==='manualBack') closeManual(); });
document.getElementById('manualBack').addEventListener('cancel',event=>{ event.preventDefault(); closeManual(); });

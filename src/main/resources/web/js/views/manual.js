'use strict';
/* 使用说明面板 */
function openManual(){
  document.getElementById('manualBack').style.display = 'flex';
  renderManual();
}
function closeManual(){ document.getElementById('manualBack').style.display = 'none'; }
function setManualTab(key){ MANUAL_TAB = key; renderManual(); }
function renderManual(){
  const pages = MANUAL[LANG];
  const page = pages.find(item=>item.k===MANUAL_TAB) || pages[0];
  MANUAL_TAB = page.k;
  document.getElementById('manualTabs').innerHTML = pages.map(item=>
    `<button class="${item.k===MANUAL_TAB?'on':''}" onclick="setManualTab('${item.k}')">${t(item.label)}</button>`).join('');
  document.getElementById('manualContent').innerHTML = `<h3>${t(page.label)}</h3>` +
    page.sections.map(section=>`<h4>${section.h}</h4>${section.body}`).join('');
}

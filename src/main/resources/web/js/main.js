'use strict';
/* 装配层:视图切换、全局快捷键与点击收起、启动序列、轮询定时器 */
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    if (document.getElementById('modalBack').style.display === 'flex') modalCancel();
    else if (document.getElementById('copyBack').style.display === 'flex') closeDedupe();
    else if (document.getElementById('manualBack').style.display === 'flex') closeManual();
    else if (document.getElementById('fsOverlay').style.display === 'block') closePreviewFs();
  }
});
/* ===================== 视图 ===================== */
function setView(v, opts){
  if (!['dash','bodies','recycle'].includes(v)) v = 'dash';
  VIEW = v;
  localStorage.setItem('spView', v);
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('on', b.dataset.view === v));
  document.querySelectorAll('.view').forEach(s => s.classList.remove('on'));
  document.getElementById(v === 'dash' ? 'viewDash' : v === 'bodies' ? 'viewBodies' : 'viewRecycle').classList.add('on');
  const previewHost = document.getElementById(v === 'recycle' ? 'recyclePreviewHost' : 'bodyPreviewHost');
  const preview = document.getElementById('previewWrap');
  if (!fsMode && preview.parentElement !== previewHost) previewHost.appendChild(preview);
  const selectedPreview = v==='recycle' ? RSEL : v==='bodies' ? SEL : null;
  const expectedSource = v==='recycle'&&RSELG ? `recycle:${RSELG.id}` : v==='bodies' ? 'body' : null;
  if ((v==='recycle'||v==='bodies') && (!selectedPreview||MESH_UUID!==selectedPreview.uuid||MESH_SOURCE!==expectedSource)) {
    disposeMesh(); MESH_DATA=null; MESH_UUID=MESH_SOURCE=null; document.getElementById('pvInfo').textContent='';
    if (selectedPreview) setTimeout(()=>v==='recycle'
      ?loadRecycleMesh(RSELG.id,selectedPreview.uuid):loadMesh(selectedPreview.uuid),30);
  }
  if (opts && opts.tab) { TAB = opts.tab; renderLimit = 400; }
  if (opts && opts.sortCost) { sortCfg = [{k:'cost',d:-1}]; saveSort(); renderSortRows(); }
  if (opts && opts.reset) resetFilters(true);
  renderAll();
  if (v === 'bodies' || v === 'recycle') setTimeout(resizeGL, 30);
  if (authenticated && (v === 'dash' || v === 'recycle') && RECYCLE === null) loadRecycle();
}
function renderAll(){
  if (VIEW === 'recycle') { renderRecycle(); return; }
  if (!DATA) return;
  if (VIEW === 'dash') renderDash();
  else { renderTabs(); render(); }
}
document.addEventListener('click', e => {
  if (!e.target.closest('#loadPill') && !e.target.closest('#statPop'))
    document.getElementById('statPop').style.display = 'none';
  if (!e.target.closest('#srvWrap'))
    document.getElementById('srvPop').style.display = 'none';
});
/* ===================== 启动 ===================== */
loadFav();
applyI18n();
renderSortRows();
initChartInteractions();
if (typeof THREE !== 'undefined') initGL();
else document.getElementById('pvInfo').textContent = 'three.js missing';
setView(VIEW);
loadGatewayState().then(() => {
  if (token) authenticate(token, true);
  else showLogin('');
}).catch(() => showLogin(t('loginBad')));
// 成员表会变(有服启动/停服/接管),定期重拉。
// 后台标签页一律停止轮询:页面没人看就别打请求,服务端才能进入空闲态(扫描全停)
setInterval(() => { if (authenticated && !document.hidden) loadServers(); }, 20000);
setInterval(() => { if (authenticated && !document.hidden && CHART.live) loadStats(); }, 15000);
setInterval(() => {
  if (!authenticated || document.hidden) return;
  refreshTimer--;
  document.getElementById('countdown').textContent = refreshTimer > 0 ? refreshTimer + 's' : '';
  if (refreshTimer <= 0) { refreshTimer = 60; loadBodies(); }
}, 1000);
// 切回前台立刻补一次统计,曲线不留豁口
document.addEventListener('visibilitychange', () => { if (authenticated && !document.hidden && CHART.live) loadStats(); });

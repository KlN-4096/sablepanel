'use strict';
/* 装配层:全局快捷键与点击收起、启动序列、轮询定时器。视图切换和分发在 views/shell.js */
document.addEventListener('keydown', e => {
  // 弹层的 ESC 归原生 <dialog> 的 cancel 事件管(各弹层自己监听);这里只剩全屏预览
  if (e.key === 'Escape' && document.getElementById('fsOverlay').style.display === 'block') closePreviewFs();
});
document.addEventListener('click', e => {
  if (!e.target.closest('#loadPill') && !e.target.closest('#statPop'))
    document.getElementById('statPop').style.display = 'none';
  if (!e.target.closest('#srvWrap'))
    document.getElementById('srvPop').style.display = 'none';
});
/* ===================== 启动 ===================== */
loadFav();
renderSortRows();
initChartInteractions();
if (typeof THREE !== 'undefined') initGL();
else document.getElementById('pvInfo').textContent = 'three.js missing';
setView(VIEW);
loadGatewayState().then(() => {
  if (token) authenticate(token, true);
  else showLogin('');
}).catch(() => showLogin(T.loginBad));
// 成员表会变(有服启动/停服/接管),定期重拉。
// 后台标签页一律停止轮询:页面没人看就别打请求,服务端才能进入空闲态(扫描全停)
setInterval(() => { if (authenticated && !document.hidden) loadServers(); }, 20000);
setInterval(() => { if (authenticated && !document.hidden) loadStats(); }, 15000);
setInterval(() => {
  if (!authenticated || document.hidden) return;
  refreshTimer--;
  document.getElementById('countdown').textContent = refreshTimer > 0 ? refreshTimer + 's' : '';
  if (refreshTimer <= 0) { refreshTimer = 60; loadBodies(); pollJobs(); }
}, 1000);
// 后台关闭事件流；切回前台先补真值，再重连并接收后续变化。
document.addEventListener('visibilitychange', () => {
  if (!authenticated) return;
  if (document.hidden) { stopEventStream(); return; }
  loadStats();
  loadBodies().finally(startEventStream);
  pollJobs();   // 后台期间别的客户端可能起了作业,回前台要立刻看到
});

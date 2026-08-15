'use strict';
/* 集群功能:服务器选择器与切换(切换时作废全部旧状态) */
function renderServerPicker(selfId){
  const wrap = document.getElementById('srvWrap');
  if (!SERVERS.length) { wrap.style.display = 'none'; return; }
  // 始终显示 —— 一眼知道自己正在看哪个服,是多服场景最容易搞错的事
  wrap.style.display = 'block';
  const cur = CURSRV || selfId;
  const button = document.getElementById('srvBtn');
  const status = staleLabel(SERVERS_ERROR, false);
  staleMark(button, status);
  button.innerHTML =
    `<span style="color:var(--acc)">◆</span> ${esc(cur)} ${status ? '<span style="color:var(--warn)">⚠</span>' : ''}`
      + ` <span style="opacity:.55">▾</span>`;
  // 服务器名是用户可填的任意字符串,别往 onclick 里拼 —— 走下标派发
  document.getElementById('srvPop').innerHTML = SERVERS.map((s, i) =>
    `<div class="srv ${s.id===cur?'on':''}" onclick="switchServer(SERVERS[${i}].id)">
       <span>${s.id===cur?'●':'○'}</span><span>${esc(s.id)}</span>
       ${s.host?`<span class="badge">${T.srvHost}</span>`:''}</div>`).join('');
}
function toggleSrvPop(){
  const p = document.getElementById('srvPop');
  p.style.display = p.style.display === 'block' ? 'none' : 'block';
}
/* 关掉属于当前服务器的弹层。它们显示的、以及攒在闭包里的,都是旧服的东西,
   所以要在改 CURSRV 之前调 —— 弹层里的内容跟界面上的服务器名对不上是最轻的后果。 */
function closeServerModals(){
  // 通用确认框最要紧:doDeleteSelected / confirmRestore / confirmPurge 都是先把 uuid
  // 攒进闭包再 await 确认,而 server= 参数是确认之后才按 CURSRV 拼的。不取消的话,
  // 那个 Promise 会一直挂着,用户回头一点"确定",旧服的 uuid 就打到新服上了
  modalCancel();
  // 下面两个只在真的开着时关。closeDedupe 会把预览恢复成 SEL 的 mesh —— 弹层没开时
  // 白调一次,等于对着马上要作废的旧服再发一个 mesh 请求
  if (document.getElementById('copyBack').open) closeDedupe(false);
  // 自动关闭不是用户读过:记了"已读",这个服回来时同一份报告就再也不提醒了
  if (document.getElementById('consistencyBack').open) closeConsistency(false);
}
/* 服务器上下文归零。切服和断开远端共用 —— 两个入口各自手写一份的话总有一份漏:
   断开远端从前只清 DATA/STATS/RECYCLE,而 authenticate 在 loadAll 之前就解锁,
   新远端的 bodies 慢一点,旧远端的顶栏数字、列表、统计弹层就会再露一次。
   调用方负责先定好 CURSRV(收藏按服务器隔离,loadFav 要读它)并先关弹层。
   具体清什么由各模块用 onServerReset() 自己注册 —— 谁的状态谁清。 */
function resetServerContext(){
  // 代次先自增:在途的 bodies/recycle/stats/players/jobs 响应从这一刻起全部作废,
  // 否则旧服的慢响应回来会直接盖掉新服的界面
  SRVGEN++;
  for (const hook of SERVER_RESET_HOOKS) hook();
  // 状态清空之后必须立刻整页重画。不画的话总览的图表、"最吃性能"、顶栏数字、日志页
  // 都还挂着上一个服的 HTML —— 点一下就是拿旧服的 uuid 查新服。
  // 各钩子只清状态不重画,重画统一在这里,所以钩子之间没有顺序依赖
  renderAll();
}
async function switchServer(id){
  document.getElementById('srvPop').style.display = 'none';
  closeServerModals();
  const self = SERVERS.find(s => s.self);
  CURSRV = (self && id === self.id) ? '' : id;
  localStorage.setItem('spServer', CURSRV);
  resetServerContext();
  const ctx = captureCtx();
  // 顶栏立刻切过去 —— 数据还在路上时也别显示旧服务器名
  renderServerPicker(self ? self.id : id);
  await loadAll(true);
  scheduleStartupConsistency();
  // A→B 快速连切时,A 那次的慢请求回来后界面已经是 B 了,再弹"已切换到 A"就是骗人。
  // 数据落地有代次保护,操作反馈也要有
  if (ctx.fresh()) toast(T.srvSwitched(id));
}

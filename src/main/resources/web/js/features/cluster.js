'use strict';
/* 集群功能:服务器选择器与切换(切换时作废全部旧状态) */
function renderServerPicker(selfId){
  const wrap = document.getElementById('srvWrap');
  if (!SERVERS.length) { wrap.style.display = 'none'; return; }
  // 始终显示 —— 一眼知道自己正在看哪个服,是多服场景最容易搞错的事
  wrap.style.display = 'block';
  const cur = CURSRV || selfId;
  document.getElementById('srvBtn').innerHTML =
    `<span style="color:var(--acc)">◆</span> ${esc(cur)} <span style="opacity:.55">▾</span>`;
  // 服务器名是用户可填的任意字符串,别往 onclick 里拼 —— 走下标派发
  document.getElementById('srvPop').innerHTML = SERVERS.map((s, i) =>
    `<div class="srv ${s.id===cur?'on':''}" onclick="switchServer(SERVERS[${i}].id)">
       <span>${s.id===cur?'●':'○'}</span><span>${esc(s.id)}</span>
       ${s.host?`<span class="badge">${t('srvHost')}</span>`:''}</div>`).join('');
}
function toggleSrvPop(){
  const p = document.getElementById('srvPop');
  p.style.display = p.style.display === 'block' ? 'none' : 'block';
}
/* 关掉属于当前服务器的弹层。必须在改 CURSRV 之前调:closeConsistency 会按
   consistencyDismissKey() 记"已读",而那个键是按服务器隔离的 —— 改完再关就记到新服头上了 */
function closeServerModals(){
  // 只在真的开着时关。closeDedupe 会把预览恢复成 SEL 的 mesh —— 弹层没开时白调一次,
  // 等于对着马上要作废的旧服再发一个 mesh 请求
  if (document.getElementById('copyBack').style.display==='flex') closeDedupe(false);
  if (document.getElementById('consistencyBack').style.display==='flex') closeConsistency();
}
/* 服务器上下文归零。切服和断开远端共用 —— 两个入口各自手写一份的话总有一份漏:
   断开远端从前只清 DATA/STATS/RECYCLE,而 authenticate 在 loadAll 之前就解锁,
   新远端的 bodies 慢一点,旧远端的顶栏数字、列表、统计弹层就会再露一次。
   调用方负责先定好 CURSRV(收藏按服务器隔离,loadFav 要读它)并先关弹层。 */
function resetServerContext(){
  // 代次先自增:在途的 bodies/recycle/stats/players/jobs 响应从这一刻起全部作废,
  // 否则旧服的慢响应回来会直接盖掉新服的界面
  SRVGEN++;
  // 作业状态按服务器隔离:JobService 的 seq 在每个服务端都从 1 开始,不清就会张冠李戴
  stopBusyPolling();
  JOBS = null; JOBS_ERROR = ''; jobsFile = ''; jobsExpanded.clear();
  CONSISTENCY = null; CONSISTENCY_POLL_GEN++;   // 作废还在等新报告的那个循环
  // 换了一整套数据,旧的一律作废
  DATA = STATS = RECYCLE = null; SEL = SELG = RSEL = RSELG = null;
  BODIES_ERROR = RECYCLE_ERROR = '';   // 旧服的失败提示不能挂在新服界面上
  // 3D 预览也是服务器级的:只把 MESH_* 置空的话,场景里的几何体还在(GPU 资源不释放),
  // 全屏层还开着并显示旧服的体名,pvInfo 还停在上一次的文字
  if (fsMode) closePreviewFs();
  disposeMesh();
  MESH_DATA = MESH_UUID = MESH_SOURCE = null;
  hideTip();
  document.getElementById('pvInfo').textContent = '';
  CLONE_SETS = new Map();
  // from/to 也要清:updateChartControls 优先用非零区间,不清的话页面写着"实时 5 分钟",
  // 日期输入框里还是上一个服的自定义区间
  CHART.live = true; CHART.span = 300; CHART.preset = 300; CHART.hoverIndex = -1;
  CHART.from = 0; CHART.to = 0;
  SELECTED = new Set(); BODY_BY_UUID = new Map();
  R_SELECTED = new Set(); RECYCLE_BY_ID = new Map();
  RECYCLE_CURSOR = ''; RECYCLE_TOTAL = 0;
  EXPAND_STATE.clear(); tpFilledFor = null; loadFav();
  PLAYERS = []; playersFetchedAt = 0; PAUSED = new Set(); FORCED = new Set();
  document.getElementById('dbody').innerHTML =
    `<div id="detailEmpty"><span class="big">⬢</span><span>${t('pickBody')}</span></div>`;
  document.getElementById('ops').style.display = 'none';
  clearRecycleDetail();
  // 状态清空之后必须立刻整页重画。不画的话总览的图表、"最吃性能"、顶栏数字、日志页
  // 都还挂着上一个服的 HTML —— 点一下就是拿旧服的 uuid 查新服
  renderAll();
}
async function switchServer(id){
  document.getElementById('srvPop').style.display = 'none';
  closeServerModals();
  const self = SERVERS.find(s => s.self);
  CURSRV = (self && id === self.id) ? '' : id;
  localStorage.setItem('spServer', CURSRV);
  resetServerContext();
  const gen = srvGen();
  // 顶栏立刻切过去 —— 数据还在路上时也别显示旧服务器名
  renderServerPicker(self ? self.id : id);
  await loadAll(true);
  scheduleStartupConsistency();
  // A→B 快速连切时,A 那次的慢请求回来后界面已经是 B 了,再弹"已切换到 A"就是骗人。
  // 数据落地有代次保护,操作反馈也要有
  if (gen === srvGen()) toast(t('srvSwitched')(id));
}

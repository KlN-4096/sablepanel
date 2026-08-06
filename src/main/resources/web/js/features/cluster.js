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
async function switchServer(id){
  document.getElementById('srvPop').style.display = 'none';
  closeDedupe();
  if (document.getElementById('consistencyBack').style.display==='flex') closeConsistency();
  const self = SERVERS.find(s => s.self);
  CURSRV = (self && id === self.id) ? '' : id;
  localStorage.setItem('spServer', CURSRV);
  // 代次先自增:在途的 bodies/recycle/stats/players/jobs 响应从这一刻起全部作废,
  // 否则旧服的慢响应回来会直接盖掉新服的界面
  SRVGEN++;
  const gen = srvGen();
  // 作业状态按服务器隔离:JobService 的 seq 在每个服务端都从 1 开始,不清就会张冠李戴
  stopBusyPolling();
  JOBS = null; JOBS_ERROR = ''; jobsFile = ''; jobsExpanded.clear();
  // 切服等于换了一整套数据,旧的一律作废
  DATA = STATS = RECYCLE = null; SEL = SELG = RSEL = RSELG = MESH_DATA = MESH_UUID = MESH_SOURCE = null;
  BODIES_ERROR = RECYCLE_ERROR = '';   // 旧服的失败提示不能挂在新服界面上
  CLONE_SETS = new Map();
  CHART.live = true; CHART.span = 300; CHART.preset = 300; CHART.hoverIndex = -1;
  SELECTED = new Set(); BODY_BY_UUID = new Map();
  R_SELECTED = new Set(); RECYCLE_BY_ID = new Map();
  RECYCLE_CURSOR = ''; RECYCLE_TOTAL = 0;
  EXPAND_STATE.clear(); tpFilledFor = null; loadFav();
  PLAYERS = []; playersFetchedAt = 0; PAUSED = new Set(); FORCED = new Set();
  // 日志页立刻进空态,不能留着上一个服的记录等 loadAll 回来
  if (VIEW === 'jobs') renderJobs();
  document.getElementById('dbody').innerHTML =
    `<div id="detailEmpty"><span class="big">⬢</span><span>${t('pickBody')}</span></div>`;
  document.getElementById('ops').style.display = 'none';
  clearRecycleDetail();
  // 顶栏和总览横幅立刻切过去 —— 数据还在路上时也别显示旧服务器名
  renderServerPicker(self ? self.id : id);
  renderDashServer();
  await loadAll(true);
  CONSISTENCY=null; scheduleStartupConsistency();
  // A→B 快速连切时,A 那次的慢请求回来后界面已经是 B 了,再弹"已切换到 A"就是骗人。
  // 数据落地有代次保护,操作反馈也要有
  if (gen === srvGen()) toast(t('srvSwitched')(id));
}

'use strict';
/* 全局共享状态:服务端数据快照 + 选中/多选 + 视图态(视图私有状态仍留在各视图文件) */
let DATA = null, STATS = null, RECYCLE = null;
/* 最近一次加载失败的原因(空串=没失败)。协议超限、网关挂了、token 失效都会走到这儿 ——
   从前 bodies 失败只弹个 toast 就完事、回收站失败干脆写一份空数据,于是"加载失败"被
   画成了"回收站为空"。用户看不出区别,只会反复刷新,把同样的压力再造一遍 */
let BODIES_ERROR = '', RECYCLE_ERROR = '', STATS_ERROR = '';
let CLONE_SETS = new Map();
let SEL = null, SELG = null, RSEL = null, RSELG = null, MESH_DATA = null, MESH_UUID = null, MESH_SOURCE = null;
let VIEW = localStorage.getItem('spView') || 'dash';
let TAB = 'all';
let R_TAB = 'latest';
let refreshTimer = 60;
const CHART_PRESETS = [300,900,3600,21600,86400,604800,2592000];
let CHART = {from:0,to:0,span:300,live:true,preset:300,hoverIndex:-1,fetchTimer:null};
let COPY_SCAN = null, COPY_UUID = null, COPY_VERSION = null, MANUAL_TAB = 'states';

/* 集群:同机多服共用 apiPort,顶栏切换;CURSRV 为空表示"本机 HOST 自己" */
let SERVERS = [], SERVERS_ERROR = '', CURSRV = localStorage.getItem('spServer') || '';
/* 服务器代次:每次切服 +1。所有异步加载在提交时捕获、落地时校验 —— 否则切到 B 之后
   A 的慢响应回来会直接盖掉界面,而 job seq 在每个服务端都从 1 开始,还会张冠李戴 */
let SRVGEN = 0;
function srvGen(){ return SRVGEN; }

/* 在线玩家(传送玩家用):选中体时拉取,15s 节流;切服作废 */
let PLAYERS = [], PLAYERS_ERROR = '', playersFetchedAt = 0;

/* 暂停集合:以 /api/bodies 的 paused 为单一事实源,操作成功后本地乐观更新 */
let PAUSED = new Set();

/* 常驻加载集合(sable force-load ticket):以 /api/bodies 的 forced 为单一事实源;
   含未加载体的意图。命中的组在列表里变色并恒置顶 */
let FORCED = new Set();

/* 正在排队/执行的作业(每个作业一条,含 targets),来自 /api/jobs?poll=1 的 running[] */
let ACTIVE_JOBS = [];
/* 由 ACTIVE_JOBS 按 targets 展开:uuid → 作业。命中的行显示转圈+阶段+已耗时,按钮禁用。
   注意"回收站恢复""重扫磁盘"没有目标体(体已被删,列表里没有行),只出现在顶栏指示器里 */
let BUSY = new Map();
/* 本页提交过、还没弹过结果的作业:seq → 操作名。从 BUSY 消失时去 /api/jobs 取终态 */
let JOB_WATCH = new Map();
/* 有作业在跑时的加速轮询句柄(2 秒),跑完自动停 */
let busyTimer = null;

/* "虚空中/极高空"的高度阈值,来自 /api/bodies 的 reach(服主可在配置文件里调) */
let REACH = {void_below:-64, sky_above:1000};

/* 多选:跨页签/筛选保留,切服清空;BODY_BY_UUID 随 DATA 重建 */
let SELECTED = new Set(), BODY_BY_UUID = new Map();
let R_SELECTED = new Set(), RECYCLE_BY_ID = new Map();
let R_DIM_DISABLED = new Set();
/* 回收站游标分页:服务端按 latest/old 分页并返回全局版本计数。
   RECYCLE.groups 仍只是当前版本页签已经加载的页。 */
let RECYCLE_CURSOR = '', RECYCLE_TOTAL = 0, RECYCLE_LOADING = false;

/* 折叠记忆:用户显式展开/折叠过的组(gid → bool),优先于默认展开策略;切服清空 */
let EXPAND_STATE = new Map();

/* 收藏:以依赖组为单位(存组根 uuid=gid),持久化 localStorage,按当前查看的服务器隔离
   (不自动清理:holding 期组会短暂从索引消失,自动清理会误删收藏) */
let FAV = new Set();
function favKey(){ return 'spFav:' + (CURSRV || 'self'); }
function loadFav(){
  try { FAV = new Set(JSON.parse(localStorage.getItem(favKey()) || '[]')); }
  catch(e){ FAV = new Set(); }
}
function saveFav(){ localStorage.setItem(favKey(), JSON.stringify([...FAV])); }

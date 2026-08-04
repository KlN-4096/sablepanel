'use strict';
/* 全局共享状态:服务端数据快照 + 选中/多选 + 视图态(视图私有状态仍留在各视图文件) */
let DATA = null, STATS = null, RECYCLE = null;
let CLONE_SETS = new Map();
let SEL = null, SELG = null, RSEL = null, RSELG = null, MESH_DATA = null, MESH_UUID = null, MESH_SOURCE = null;
let VIEW = localStorage.getItem('spView') || 'dash';
let TAB = 'all';
let R_TAB = 'all';
let refreshTimer = 60;
const CHART_PRESETS = [300,900,3600,21600,86400,604800,2592000];
let CHART = {from:0,to:0,span:300,live:true,preset:300,hoverIndex:-1,fetchTimer:null};
let COPY_SCAN = null, COPY_UUID = null, MANUAL_TAB = 'states';

/* 集群:同机多服共用 apiPort,顶栏切换;CURSRV 为空表示"本机 HOST 自己" */
let SERVERS = [], CURSRV = localStorage.getItem('spServer') || '';

/* 在线玩家(传送玩家用):选中体时拉取,15s 节流;切服作废 */
let PLAYERS = [], playersFetchedAt = 0;

/* 暂停集合:以 /api/bodies 的 paused 为单一事实源,操作成功后本地乐观更新 */
let PAUSED = new Set();

/* 常驻加载集合(sable force-load ticket):以 /api/bodies 的 forced 为单一事实源;
   含未加载体的意图。命中的组在列表里变色并恒置顶 */
let FORCED = new Set();

/* 处理中的作业:uuid → {seq,op,state,phase,since},来自 /api/bodies 的 busy。
   命中的行显示转圈+阶段+已耗时,操作按钮禁用 */
let BUSY = new Map();
/* 本页提交过、还没弹过结果的作业:seq → 操作名。从 BUSY 消失时去 /api/jobs 取终态 */
let JOB_WATCH = new Map();
/* 有作业在跑时的加速轮询句柄(2 秒),跑完自动停 */
let busyTimer = null;

/* 各维度建筑高度上下限,来自 /api/bodies 的 dims。
   绝不能在前端写死 -64/320:模组会改,实测本地存档主世界上限就是 480 */
let DIMS = {};

/* 多选:跨页签/筛选保留,切服清空;BODY_BY_UUID 随 DATA 重建 */
let SELECTED = new Set(), BODY_BY_UUID = new Map();
let R_SELECTED = new Set(), RECYCLE_BY_ID = new Map();

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

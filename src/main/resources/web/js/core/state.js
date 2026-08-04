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

/* 集群:同机多服共用一个端口,顶栏切换;CURSRV 为空表示"本机 HOST 自己" */
let SERVERS = [], CURSRV = localStorage.getItem('spServer') || '';

/* 在线玩家(传送玩家用):选中体时拉取,15s 节流;切服作废 */
let PLAYERS = [], playersFetchedAt = 0;

/* 暂停集合:以 /api/bodies 的 paused 为单一事实源,操作成功后本地乐观更新 */
let PAUSED = new Set();

/* 多选:跨页签/筛选保留,切服清空;BODY_BY_UUID 随 DATA 重建 */
let SELECTED = new Set(), BODY_BY_UUID = new Map();
let R_SELECTED = new Set(), RECYCLE_BY_ID = new Map();

/* 折叠记忆:用户显式展开/折叠过的组(gid → bool),优先于默认展开策略;切服清空 */
let EXPAND_STATE = new Map();

/* 收藏:按体 uuid 持久化到 localStorage,按当前查看的服务器隔离(不自动清理,
   holding 期体会短暂从索引消失,自动清理会误删收藏) */
let FAV = new Set();
function favKey(){ return 'spFav:' + (CURSRV || 'self'); }
function loadFav(){
  try { FAV = new Set(JSON.parse(localStorage.getItem(favKey()) || '[]')); }
  catch(e){ FAV = new Set(); }
}
function saveFav(){ localStorage.setItem(favKey(), JSON.stringify([...FAV])); }

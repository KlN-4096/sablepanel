'use strict';
/* 服务端日志为跨客户端共享的原始记录，仍按服务端语言落盘；本文件只负责当前界面的显示翻译。 */

const SERVER_TEXT_EXACT_EN = Object.freeze({
  '重扫磁盘':'Rescan disk', '回收站恢复':'Restore from recycle bin',
  '回收站彻底删除':'Purge recycle bin', '一致性检查':'Consistency check',
  '一致性修复':'Consistency repair', '删除':'Delete', '批量删除':'Batch delete',
  '清理断链残骸':'Clean broken-link remnants', '批量收养':'Batch adopt',
  '暂停':'Pause physics', '恢复':'Resume physics', '清除速度':'Clear velocity',
  '常驻加载':'Force-load', '取消常驻':'Cancel force-load',
  '冻结':'Pause ticking', '恢复 tick':'Resume ticking', '传送':'Teleport',
  '传送玩家':'Teleport player', '收养':'Adopt', '处理副本':'Manage copies',
  '隔离不完整副本':'Quarantine incomplete copies',
  '定位磁盘条目':'Locate disk entries', '确认当前运行组':'Verify current runtime group',
  '等待磁盘扫描':'Wait for disk scan', '彻底删除回收组':'Purge recycle group',
  '摘常驻票':'Remove force-load tickets', '挂常驻票':'Add force-load tickets',
  '恢复物理组':'Restore physics group', '恢复依赖组':'Restore dependency group',
  '保存存档':'Save data', '复核前置状态':'Verify preconditions',
  '卸载到存档':'Unload to disk', '资源准备失败':'Resource preparation failed',
  '需要 WebGL2':'WebGL 2 is required', 'WebGL 初始化失败':'WebGL initialization failed',
  '服务器不在线':'Server is offline', 'token 无效':'Invalid token', 'token 缺失':'Missing token',
  '需要 POST':'POST is required', '网页网关已关闭':'Web gateway is closed',
  '未连接到服务器':'Not connected to the server', '面板节点已关闭':'Panel node is closed',
  '当前节点不是 HOST':'Current node is not the HOST', '日志文件名非法':'Invalid log file name',
  '玩家不在线':'Player is offline', '物理体容器不存在':'Physics body container does not exist',
});

const SERVER_TEXT_PARTS_EN = Object.freeze(Object.entries({
  '依赖成员存在多份副本,无法选择常驻版本':'A dependency member has multiple copies; unable to select a force-load version',
  '依赖成员存在多份副本，无法选择常驻版本':'A dependency member has multiple copies; unable to select a force-load version',
  '当前运行物理组在操作期间发生变化':'The current runtime physics group changed during the operation',
  '当前运行依赖组在操作期间发生变化':'The current runtime dependency group changed during the operation',
  '当前面板常驻票在操作期间发生变化':'Panel force-load tickets changed during the operation',
  '当前运行物理组在观察上限内仍出现新成员,未挂常驻票':'New runtime members kept appearing within the observation limit; force-load tickets were not added',
  '常驻票收敛后出现未观察到的运行成员':'An unobserved runtime member appeared after force-load convergence',
  '当前运行依赖组没有完整卸载锚点':'The current runtime dependency group has no complete unload anchor',
  '当前运行物理组没有完整卸载锚点':'The current runtime physics group has no complete unload anchor',
  '当前运行组快照与候选版本成员不一致':'The runtime group snapshot does not match the candidate version members',
  '当前运行版本在确认期间发生变化':'The current runtime version changed during verification',
  '运行状态在确认期间发生变化':'Runtime state changed during verification',
  '副本活动证据在处理期间发生变化':'Active copy evidence changed during processing',
  '副本物理组在确认期间发生变化':'The copied physics group changed during verification',
  '副本成员在确认期间发生变化':'Copy members changed during verification',
  '副本版本已经变化':'The copy version has changed',
  '所选副本在准备期间发生变化':'The selected copy changed during preparation',
  '常驻候选条目在准备期间发生变化':'The force-load candidate changed during preparation',
  '当前活动条目不在同次磁盘扫描中':'The active entry is not present in the same disk scan',
  '准备阶段物理组相互重叠但不一致':'Physics groups overlap inconsistently during preparation',
  '物理组在确认期间已经加载':'The physics group loaded during verification',
  '条目在准备阶段被 sable 搬迁':'The entry was moved by Sable during preparation',
  '在删除前被 sable 搬迁':'was moved by Sable before deletion',
  '运行依赖组根成员未加载':'Runtime dependency-group root members are not loaded',
  '运行物理组根成员未加载':'Runtime physics-group root members are not loaded',
  '物理结构组未完整加载,请先常驻加载':'The physics group is not fully loaded; force-load it first',
  '依赖组根成员不存在':'Dependency-group root members do not exist',
  '物理组根成员不存在':'Physics-group root members do not exist',
  '当前运行组成员已卸载':'A current runtime-group member unloaded',
  '当前运行物理组为空':'The current runtime physics group is empty',
  '取消常驻后存在内容冲突副本':'Conflicting copies remain after cancelling force-load',
  '取消常驻后磁盘复核失败':'Disk verification failed after cancelling force-load',
  '取消常驻状态复核失败':'State verification failed after cancelling force-load',
  '取消常驻失败后的状态恢复失败':'State restoration failed after force-load cancellation failed',
  '取消常驻失败后无法重新加载原结构':'Unable to reload the original structure after force-load cancellation failed',
  '取消常驻失败后原状态恢复不完整':'Original state was not fully restored after force-load cancellation failed',
  '取消常驻失败后快照无法解析':'Unable to parse the snapshot after force-load cancellation failed',
  '取消常驻失败后 Sable 拒绝恢复':'Sable rejected restoration after force-load cancellation failed',
  '存在其他模组的常驻票,未取消常驻':'Another mod owns force-load tickets; force-load was not cancelled',
  '物理组存在其他模组的常驻票,未卸载':'Another mod owns force-load tickets for this group; it was not unloaded',
  '部分常驻组恢复失败':'Some force-loaded groups failed to restore',
  '常驻加载失败':'Force-load failed', '固定物理失败':'Failed to pause physics',
  '解除固定物理失败':'Failed to resume physics', '移动后固定物理失败':'Failed to pause physics after moving',
  '原位置固定物理失败':'Failed to pause physics at the original position',
  '移动物理体失败':'Failed to move the physics body', '旧约束移除失败':'Failed to remove the old constraint',
  '暂停物理失败后的状态恢复失败':'State restoration failed after pausing physics failed',
  '暂停物理失败':'Pause physics failed', '传送位置复核失败':'Teleport position verification failed',
  '旧暂停状态迁移期间持续发生变化':'The legacy pause state kept changing during migration',
  '传送磁盘写入失败':'Teleport disk write failed', '传送磁盘复核失败':'Teleport disk verification failed',
  '传送保存前缺少活动磁盘条目':'No active disk entry before saving the teleport',
  '传送目标坐标无效':'Invalid teleport destination', '物理体包围盒坐标无效':'Invalid physics-body bounding-box coordinates',
  '物理体包围盒范围无效':'Invalid physics-body bounding-box range',
  '没有已加载成员,未清除速度。冷体请用传送(会顺带清速度并落盘)':'No loaded members; velocity was not cleared. Teleport cold bodies instead (it also clears velocity and saves)',
  '副本处理已完成,但审计日志写入失败':'Copy processing completed, but writing the audit log failed',
  '副本处理已完成,但磁盘索引重扫触发失败':'Copy processing completed, but triggering a disk rescan failed',
  '不能选择依赖不完整的版本':'Cannot select a version with incomplete dependencies',
  '副本物理组缺少可读取的磁盘条目,未执行副本处理':'The copied physics group has no readable disk entry; copies were not processed',
  '副本切换清理失败':'Copy-switch cleanup failed', '外部历史依赖归一失败':'Failed to normalize external historical dependencies',
  '存在完整候选版本,请选择主版本;未归属条目会随切换一起隔离':'Complete candidate versions exist; select the primary version. Unassigned entries will be quarantined during the switch',
  '没有可隔离的不完整副本':'No incomplete copies are available to quarantine',
  '隔离清理失败':'Quarantine cleanup failed', '删除事务失败':'Delete transaction failed',
  '删除前容量统计失败':'Pre-delete capacity calculation failed', '删除前临时备份失败':'Pre-delete temporary backup failed',
  '回收站提交失败':'Recycle-bin commit failed', '删除未发生,但暂停/常驻状态恢复失败':'Nothing was deleted, but pause/force-load state restoration failed',
  '删除前存储校验失败':'Pre-delete storage verification failed', '删除前指针校验失败':'Pre-delete pointer verification failed',
  '删除前未能清理暂停/冻结/常驻状态':'Could not clear pause/ticking/force-load state before deletion',
  'Sable 删除阶段失败':'Sable deletion stage failed', '补充副本删除队列失败':'Failed to queue additional copies for deletion',
  '删除后指针定位失败':'Post-delete pointer lookup failed', '删除后指针复核失败':'Post-delete pointer verification failed',
  '删除后仍有':'Still present after deletion:', '删除后验收失败':'Post-delete verification failed',
  '删除后追踪点验收失败':'Post-delete tracking-point verification failed',
  '幸存体依赖更新失败':'Surviving-body dependency update failed', '幸存体依赖裁剪失败':'Surviving-body dependency pruning failed',
  '依赖裁剪目标副本已变化':'The dependency-pruning target copy changed',
  '依赖裁剪目标维度不可用':'The dependency-pruning target dimension is unavailable',
  '依赖裁剪 NBT 无法解析':'Unable to parse dependency-pruning NBT',
  '依赖裁剪槽位复核失败':'Dependency-pruning slot verification failed',
  '目标没有规范副本':'Target has no canonical copy', '存储副本所在维度不可用':'The stored copy dimension is unavailable',
  '指针清理前存储槽已被复用':'A storage slot was reused before pointer cleanup',
  '指针清理前 holding 元数据已变化':'Holding metadata changed before pointer cleanup',
  '回滚前仍有磁盘条目':'Disk entries remain before rollback',
  '回滚前运行时或操作状态仍存在':'Runtime or operation state remains before rollback',
  '回收组没有可备份条目':'Recycle group has no entries to back up',
  '回滚前残留清理失败':'Failed to clean remaining data before rollback',
  '依赖不完整的隔离副本不能直接恢复':'Quarantined copies with incomplete dependencies cannot be restored directly',
  '回收站已占用':'Recycle bin already uses', '待提交事务已占用':'pending transaction uses',
  '剩余容量不足':'remaining capacity is insufficient', '请先人工彻底删除旧组':'purge old groups first',
  '创建回收站事务失败':'Failed to create recycle-bin transaction',
  '找不到该物理结构的磁盘条目':'No disk entry found for this physics structure',
  '副本条目超过':'Copy entries exceed', '副本物理组超过':'Copied physics group exceeds',
  '副本槽位已经变化':'The copy slot changed', '磁盘定位被中断':'Disk lookup was interrupted',
  '常驻候选磁盘扫描存在损坏':'The force-load candidate disk scan found corruption',
  '常驻意图磁盘扫描存在损坏':'The force-load-intent disk scan found corruption',
  '常驻候选指针扫描存在损坏':'The force-load candidate pointer scan found corruption',
  '常驻候选存在多个 holding 指针':'The force-load candidate has multiple holding pointers',
  '无法加载该物理体(条目缺失或 sable 拒绝加载,详见服务器日志)':'Unable to load this physics body (entry missing or rejected by Sable; see the server log)',
  '面板作业队列已满,请等当前操作结束后重试':'The panel job queue is full; retry after current jobs finish',
  '响应不是 ArrayBuffer':'response is not an ArrayBuffer', '头部截断':'header is truncated',
  '响应超过 30 MiB 上限':'response exceeds the 30 MiB limit', 'magic 错误':'invalid magic',
  '体素数量超过 400000':'voxel count exceeds 400000', '头部字段无效':'invalid header fields',
  '记录宽度无效':'invalid record width', '长度不一致':'length mismatch',
  'metadata 无效':'invalid metadata', 'metadata padding 非零':'metadata padding is non-zero',
  'metadata 与主体不一致':'metadata does not match the payload', '体素记录越界':'voxel record is out of bounds',
  'groups 不是数组':'groups is not an array', '旋转组区间越界或重叠':'rotation-group range is out of bounds or overlaps',
  '旋转组 pivot 无效':'invalid rotation-group pivot', '旋转组轴或角度无效':'invalid rotation-group axis or angle',
  '响应不是一份完整的快照':'Response is not a complete snapshot',
  '请求体超过 1 MiB':'Request body exceeds 1 MiB', '服务器地址为空':'Server address is empty',
  '请输入 host:port,不要包含协议或路径':'Enter host:port without a protocol or path',
  '服务器地址格式无效':'Invalid server address format', '服务器地址无效':'Invalid server address',
  'IPv6 地址格式无效':'Invalid IPv6 address format', '请先断开当前集群':'Disconnect the current cluster first',
  '指纹确认已失效,请重新连接':'Fingerprint confirmation expired; reconnect',
  '访问口令':'access token', '无效':'is invalid', '缺失':'is missing', '不能为空':'cannot be empty',
  '必须是布尔值':'must be a boolean', '必须是 latest 或 old':'must be latest or old',
  '单次最多处理':'At most', '单次最多':'At most', '含无效值':'contains an invalid value',
  '未执行副本处理':'copies were not processed', '未执行删除':'deletion was not performed',
  '请重新扫描':'rescan', '请重试':'retry', '详见服务端日志':'see the server log',
  '影响根':'affected roots', '个物理组':'physics groups', '个成员':'members',
  '个文件':'files', '个 holding 指针':'holding pointers', '个磁盘条目':'disk entries',
  '另':'plus', '项':'items',
}).sort((a,b)=>b[0].length-a[0].length));

function blockLabel(block){
  if (!block) return '?';
  return LANG === 'zh' ? (block.zh || block.en || block.id || '?') : (block.en || block.id || block.zh || '?');
}

function jobTargetLabel(value, targetCount){
  const raw = value == null ? '' : String(value);
  if (LANG === 'zh') return raw;
  const multiple = /^(.*) 等 (\d+) 个$/.exec(raw);
  if (multiple && targetCount > 1) return `${multiple[1]} and ${multiple[2]} total`;
  return raw.replace(/^(\d+) 个物理组$/, '$1 physics groups').replace(/^(\d+) 项$/, '$1 items');
}

function serverText(value){
  const raw = value == null ? '' : String(value);
  if (!raw || LANG === 'zh') return raw;
  const conflict = /^该(操作正在执行中|物理体正在处理中)\((.+)\),请等它结束$/.exec(raw);
  if (conflict) {
    const subject = conflict[1] === '操作正在执行中' ? 'operation is already running' : 'physics body is already being processed';
    return `This ${subject} (${serverText(conflict[2])}); wait for it to finish`;
  }
  const exact = SERVER_TEXT_EXACT_EN[raw];
  if (exact) return exact;
  let translated = raw;
  for (const [source, target] of SERVER_TEXT_PARTS_EN) translated = translated.split(source).join(target);
  translated = translated
    .replace(/(\d+) 个物理组/g, '$1 physics groups')
    .replace(/(\d+) 个成员/g, '$1 members')
    .replace(/(\d+) 个文件/g, '$1 files')
    .replace(/(\d+) 个/g, '$1')
    .replace(/，/g, ', ').replace(/；/g, '; ').replace(/。/g, '. ').replace(/：/g, ': ');
  if (!/[\u3400-\u9fff]/.test(translated)) return translated;
  const diagnostic = translated.replace(/[\u3400-\u9fff]+/g, ' ').replace(/\s+/g, ' ').trim();
  return diagnostic ? `${T.serverTextUnknown}: ${diagnostic}` : T.serverTextUnknown;
}

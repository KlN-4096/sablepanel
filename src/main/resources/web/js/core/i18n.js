'use strict';
/* 中文词典:I18N / MANUAL 文案、t()、applyI18n(fmt 为词条函数所依赖,随词典同文件) */
/* ===================== i18n ===================== */
const I18N = {
  zh: {
    navDash:'总览', navBodies:'物理体', navRecycle:'回收站', refresh:'刷新',
    pillCost:'物理体开销 ms/t', pillLoaded:'加载中',
    search:'搜索', searchPh:'名称 / UUID', containsBlock:'包含方块', blockPh:'方块名或 id',
    sort:'排序(可多条件)', addSort:'添加条件', state:'状态',
    stLoaded:'加载中', stStored:'未加载', stHolding:'暂存中', stOrphan:'孤儿',
    dup:'重复', dupAll:'全部显示', dupOnly:'仅多副本', cloneOnly:'仅疑似克隆', dupAny:'两者任一',
    scale:'规模', szHuge:'巨型 ≥10000', szLarge:'大型 ≥1000', szMid:'中型 ≥100', szSmall:'小型 ≥10', szFrag:'碎片 <10',
    dims:'维度', other:'其他', namedOnly:'仅命名体', groupOnly:'仅组合体',
    pickBody:'选择一个物理体查看详情', teleportTo:'传送结构', adopt:'收养找回(含依赖)',
    tpDest:'目的坐标', tpUseCur:'取当前', tabFav:'收藏', favTip:'收藏/取消收藏',
    opMove:'传送', opPlayer:'玩家', opPhysics:'物理与恢复', opDanger:'危险操作',
    srvHost:'主', srvSwitched:(n)=>`已切换到 ${n}`,
    adoptHint:'重建加载指针,数据不动盘', delBody:'删除该物理体', delHint:'删除严格验收成功后才会进入回收站',
    close:'关闭', cancel:'取消', confirm:'确认', composition:'方块构成', reload:'重载',
    loginAddress:'服务器地址', loginToken:'访问口令', loginEnter:'进入', loginBad:'地址或访问口令无效', loginChanged:'连接或访问口令已失效,请重新输入',
    certConfirm:(f)=>`首次连接或服务器证书已变化。\n确认 TLS 证书指纹:\n${f}`,
    defaultTokenT:'建议修改访问口令', defaultTokenMsg:'当前正在使用默认访问口令,建议在维护卡片中修改。',
    neverRemind:'永不提醒', later:'暂不修改', changeNow:'立即修改',
    compMore:(n)=>`展开剩余 ${n} 种方块`, compLess:'收起',
    dashServer:'当前服务器', dashServerOnly:'本机唯一面板', dashServerPeer:(n)=>`集群共 ${n} 个服务器`,
    sNamed:'有名称优先', sBlocks:'方块数', sMembers:'成员数', sLoaded:'加载数', sAlpha:'名称字母',
    sCost:'性能开销', sRec:'推荐清理优先', sOrphan:'孤儿优先',
    loading:'处理中…', unnamed:'未命名', bodies:'体', entries:'存档条目', scanAt:'扫描于', groupsUnit:'组',
    stateLoaded:'加载中', stateStored:'未加载(存档)', stateHolding:'暂存中(内存持有,待落盘)', stateOrphan:'孤儿(条目在盘、无加载指针)',
    combo:'体组合', loadedX:'加载', holdingX:'暂存', orphanX:'孤儿', copiesX:'副本', cloneTag:'疑似克隆', depsX:'依赖',
    blocksUnit:'块', name:'名称', dim:'维度', coord:'坐标', rtLive:'实时', rtSaved:'存档快照',
    bbox:'包围盒', blockCount:'方块数', copiesRow:'存档副本', copiesShow:'份(展示最优)', mass:'质量', vel:'速度', players:'追踪玩家',
    group:'所属组', groupVal:(n,b)=>`${n} 个物理体 / 共 ${fmt(b)} 块`, entry:'存档条目', deps:'依赖',
    pvLoad:'加载预览…', pvNone:'无方块数据', pvFail:'预览失败: ', pvStat:(s,t)=>`外壳 ${fmt(s)} / 总 ${fmt(t)} 体素`, pvTrunc:'(已截断)',
    pvHoverOff:'体过大,悬停识别已关闭',
    tpConfirmT:'传送物理体', tpConfirm:(n,x,y,z)=>`将「${n}」传送到 ${x}, ${y}, ${z}\n未加载的体会被强制加载(孤儿体自动收养)。`,
    tpOk:'传送完成', tpFail:'传送失败: ',
    tpPlayerBtn:'传送玩家', tpNoPlayers:'没有在线玩家', tpPlayerT:'传送玩家',
    tpPlayerMsg:(p,n)=>`将玩家「${p}」传送到「${n}」上方。\n未加载的体会被强制加载(孤儿体自动收养)。`,
    tpPlayerOk:(p)=>`已传送玩家 ${p}`,
    pauseBody:'⏸ 暂停物理', resumeBody:'▶ 恢复物理', pausedTag:'已暂停',
    pauseOk:(n)=>`已暂停 ${n} 个物理体`, resumeOk:(n)=>`已恢复 ${n} 个物理体`, pauseFail:'暂停操作失败: ',
    selPause:(n)=>`暂停所选 (${n})`, selResume:(n)=>`恢复所选 (${n})`,
    pauseHint:'引擎约束锁定(同物理手杖),重启后仍保持',
    forceBody:'常驻加载', unforceBody:'取消常驻', forcedTag:'面板常驻加载', forcedBadge:'常驻',
    forceOk:(n)=>`已常驻加载 ${n} 个物理体`, unforceOk:(n)=>`已取消 ${n} 个常驻`,
    forceFail:'常驻加载操作失败: ', forcePartial:(ok,bad)=>`成功 ${ok} 个,${bad} 个加载失败(详见服务器日志)`,
    selForce:(n)=>`常驻加载所选 (${n})`, selUnforce:(n)=>`取消常驻所选 (${n})`,
    forceHint:'sable 常驻票:结构随时保持加载(区块跟着走),重启后仍生效',
    delConfirmT:'删除物理体', delConfirm:(n,b)=>`确认删除「${n}」?该体 ${fmt(b)} 块。\n删除前会自动备份到回收站,可随时恢复。`,
    delOk:'已删除,回收站: ', delFail:'删除失败: ', delUnknownFail:'最终验收未通过',
    delGroup:(n)=>`删除整组(${n} 体)`, delGroupT:'删除整组',
    delGroupMsg:(n,b)=>`将删除该组全部 ${n} 个物理体,共 ${fmt(b)} 块。\n每个体都会先备份到回收站,可随时恢复。`,
    delGroupDone:(o,n)=>`删除完成:成功 ${o}/${n}`,
    adoptT:'收养孤儿物理体', adoptMsg:(n)=>`将把「${n}」重新接入 sable 加载管线(依赖体一并收养)。\n不修改任何磁盘数据,失败也无副作用。`,
    adoptOk:'收养成功,已重新加载', adoptPart:'收养完成(部分成员未能加载)', adoptFail:'收养失败: ',
    adoptTrunc:(n)=>`依赖链超过 ${n} 个成员上限,本次只收养了扫描到的前 ${n} 个`,
    recycleT:'回收站', recycleEmpty:'回收站为空', recycleVersionEmpty:'此版本页签暂无回收组', recycleHint:'显示已验收删除和删除中断后保留的完整备份。',
    recycleState:'恢复状态', recycleDeleted:'待恢复', recycleRecovery:'需恢复', recycleRestored:'已恢复', recycleStorage:'存储上限',
    apply:'应用', recycleUsage:(n,m)=>`${n} / ${m} 个备份文件`, recycleDisk:n=>`磁盘占用 ${n}`,
    recycleStructures:'物理结构', pickRecycle:'选择一个备份物理体查看详情',
    restoreGroup:'恢复该依赖组', restoreHint:'恢复后会在保存位置重新加载',
    rTabLatest:'最新版本', rTabOld:'旧版本',
    rShowing:(n,m)=>`显示 ${n} / ${m} 个依赖组`, rSelectInfo:(g,b)=>`已选 ${g} 组 · ${b} 个物理体`,
    rLoaded:(n,m)=>`已加载 ${n} / ${m}`, rLoadMore:'加载更多', rLoading:'加载中…',
    bodiesTruncated:(n,m)=>`依赖组过多,只显示体积最大的 ${n} / ${m} 组`,
    bodiesMembersOmitted:(n)=>`单组成员过多,已省略 ${n} 个体的明细`,
    bodiesPaletteTruncated:'方块种类过多,部分构成未下发',
    groupPartial:'不完整',
    groupPartialTip:(n)=>`还有 ${n} 个成员未下发,整组选择已禁用 —— 可逐个选中已显示的成员`,
    rBlocksOmitted:'该依赖组过大,已省略方块构成(其余信息完整)',
    rBodiesOmitted:'该依赖组过大,成员明细已省略;恢复/彻底删除仍按整组执行',
    restoreSelected:'恢复所选', restoreSelectedT:'恢复所选物理体',
    restoreSelectedMsg:(g,b,blk)=>`将恢复勾选的 ${g} 个依赖组,共 ${b} 个物理体、${fmt(blk)} 块。\n恢复后会在原保存位置重新加载。`,
    restoreOldWarn:(n)=>`\n其中 ${n} 个是旧版本；不会覆盖世界中已有的同 UUID 物理体，当前版本仍存在时恢复会失败。`,
    restoreRecoveryWarn:(n)=>`\n其中 ${n} 个“需恢复”事务会先清除同 UUID 残留,再从备份重建整组。`,
    purgeGroup:'彻底删除该依赖组', purgeSelected:'彻底删除所选', purgeT:'彻底删除回收组',
    purgeMsg:(g,b,f)=>`将彻底删除 ${g} 个依赖组，共 ${b} 个物理体、${f} 个备份文件。\n磁盘备份删除后无法恢复。`,
    purgeRecoveryWarn:(n)=>`\n其中 ${n} 个是“需恢复”记录，可能包含唯一的完整恢复材料。`,
    restoreDone:(n,m)=>`恢复完成:成功 ${n}/${m} 组`, restoreFail:'恢复失败: ',
    deletedAt:'删除时间', restoredAt:'恢复时间', backupFiles:'备份文件', backupGroup:'回收组',
    saveLimitOk:'回收站上限已更新', saveLimitFail:'更新上限失败: ', recycleNoMatch:'没有符合条件的回收组',
    limitConfirmT:'调整回收站上限',
    limitConfirmMsg:(n,m)=>`当前有 ${n} 个备份文件。将上限降到 ${m} 后,会立即删除日期最早的完整依赖组。`,
    copied:'已复制', showMore:(n)=>`显示其余 ${n} 组`, noMatch:'没有符合条件的物理体',
    loadFail:'加载失败: ',
    statPhys:'物理引擎', statLoaded:'加载体', statNone:'暂无数据', confirmMismatch:'输入不匹配,已取消',
    costRow:'性能开销', costVal:(ms)=>`${ms} ms/tick`,
    costHint:'该体每 tick 的 Java 逻辑耗时(方块实体、力、同步)',
    topCost:'最吃性能的物理体', bodyCostTotal:'全部加载体合计',
    filterBar:(n,m)=>`显示 ${n} / ${m} 组`, filterActive:'部分内容被筛选隐藏', resetFilters:'重置筛选',
    expandAll:'展开全部', collapseAll:'折叠全部',
    tabAll:'全部', tabNamed:'命名', tabUnnamed:'未命名', tabRec:'推荐删除', tabAnom:'异常',
    tabVoid:'虚空中', tabSky:'极高空',
    voidTag:y=>`整个包围盒低于 y=${y},玩家无法到达(阈值可在配置文件调)`, voidBadge:'虚空',
    skyTag:y=>`整个包围盒高于 y=${y},玩家无法到达(阈值可在配置文件调)`, skyBadge:'极高空',
    /* 作业:后端返回的 op 名是服务端写的中文,日志是服务端记录,不随界面语言切换 */
    navJobs:'日志', jobQueued:'排队中', jobDone:'完成', jobFailed:'失败', jobPartial:'部分成功',
    jobsRunning:'进行中', jobsEmpty:'暂无记录', jobsFile:'日志文件', jobsCurrent:'本次运行',
    jobsOnlyFailed:'只看失败', jobsWorkers:n=>`并发上限 ${n}`,
    jobTime:'时间', jobOp:'操作', jobTarget:'目标', jobState:'状态', jobCost:'耗时', jobMsg:'消息',
    jobTrail:'过程', jobWarn:'告警',
    rescanOp:'重扫磁盘', adoptOp:'收养', restoreOp:'回收站恢复', purgeOp:'回收站彻底删除', pauseOp:'暂停', resumeOp:'恢复',
    forceOp:'常驻加载', unforceOp:'取消常驻', tpOp:'传送', tpPlayerOp:'传送玩家', delOp:'删除',
    recTag:'推荐删除',
    rEmpty:'空体(0 块)', rFragment:'微型碎片(<10 块)', rDebris:'小型残块',
    rOrphan:'全组无加载指针', rDup:'存在多余副本条目', rClone:'疑似克隆重复',
    recWhy:'推荐理由', protWhy:'保护原因', protTag:'不推荐删除',
    pNamed:'有名称', pTracked:'有玩家乘坐', pUserdata:'带第三方数据',
    pContents:'方块实体里有物品/文字', pSize:'体量够大', pVariety:'方块种类多', pMachinery:'机械/家具密集',
    beRow:'方块实体', contentsRow:'有内容的容器', typesRow:'方块种类',
    contentsHint:'箱子/物品栏里有东西 —— 判定为玩家资产,一票否决推荐删除',
    recSafe:(p)=>`只推荐真正的残渣:同时满足 无名称、总计 <${p.blocks} 块、方块种类 <${p.types}、方块实体 <${p.be}、容器全空、无人乘坐、无第三方数据。按整组判定,组里只要有一个值得留的成员就整组排除(删依赖成员会让同组其他体加载失败)。阈值可在 config/sablepanel-server.json 里调。`,
    recBatch:(g,b)=>`删除全部推荐(${g} 组 / ${b} 体)`,
    recBatchT:'批量删除推荐项', recBatchMsg:(g,b,blk)=>`将删除 ${g} 个推荐组,共 ${b} 个物理体、${fmt(blk)} 块。\n每个体都会先备份到回收站,可随时恢复。`,
    recNone:'没有需要清理的物理体', recTooMany:'单次最多处理 500 个物理体,未执行删除',
    dashBodies:'物理体总数', dashBlocks:'方块总量', dashLoaded:'加载中', dashClean:'可清理',
    dashState:'状态分布', dashScale:'规模分布', dashDims:'维度分布', dashAnom:'异常',
    physChartT:'物理性能历史',
    physHint:'维度曲线是物理引擎整体步进耗时,粉色曲线是全部加载体的 Java 逻辑耗时;均不含其它 mod。',
    dashGo:'查看 →', dashHealthy:'存档干净', dashRecBlocks:'可回收方块', dashRecGroups:'推荐组',
    dashOrphans:'孤儿体', dashDup:'多副本体', dashClone:'疑似克隆体', dashHolding:'暂存中',
    tools:'维护', rescan:'立即重扫磁盘', rescanOk:'已触发重扫,数秒后刷新',
    manualOpen:'使用说明', manualTitle:'SablePanel 使用说明',
    manualStates:'状态与判断', manualCleanup:'清理判断', manualOps:'操作', manualRecycle:'回收站',
    manualPerformance:'性能数据', manualMaintenance:'面板维护',
    dedupe:'去重副本', dedupeTitle:'整理重复存档副本', dedupeConfirm:'确认去重',
    dedupeScanning:'正在实时扫描所有副本和 holding 指针…',
    dedupeSafe:(n)=>`共 ${n} 个副本,完整 NBT 全部一致。将保留标记为“主副本”的条目。`,
    dedupeUnsafe:'副本完整 NBT 不一致,只能查看差异,禁止自动删除。',
    dedupeSingle:'实时扫描只找到一个条目,无需去重。',
    dedupeAsk:(n)=>`确认通过 Sable 保存流程清理 ${n} 个完全相同的多余条目?\n主副本会保留,本操作不生成回收站记录。`,
    dedupeDone:(n)=>`副本去重完成,已清理 ${n} 个条目`, dedupeFail:'副本去重失败: ',
    copyEntry:'条目 ID', copyPointer:'指针', copyBlocks:'方块', copyPlace:'位置 / 尺寸', copyCompare:'与主副本',
    copyKeep:'主副本', copySame:'完全一致', copyDifferent:'内容不同', copyReachable:(n)=>`有效 ×${n}`, copyUnreachable:'无有效指针',
    cloneWith:(n)=>`与 ${n} 个物理体疑似克隆`, cloneRelation:'疑似克隆关联', clonePeers:'关联物理结构',
    cloneNamedReason:'名称、方块数和取整包围盒相同',
    cloneUnnamedReason:'均未命名且不少于 50 块,方块数和取整包围盒相同',
    chartLive:'回到实时', chartInvalid:'起止时间无效', chartRaw:'原始采样', chartPeak:'区间峰值',
    chartMeta:(n,s,a)=>`${n} 点 · ${s} · ${a}`, chartInterval:(s)=>`代表 ${s}`,
    tokenChange:'修改访问口令', tokenChangeT:'修改访问口令',
    tokenChangeMsg:'新口令(字母、数字和 . - _ ~,1~64 位)。\n集群内所有服务器会一起改,改完本页会自动换用新口令。',
    tokenHint:'访问口令即本面板的密码,集群内共用一个;改动会同步到所有成员并写入各自的配置文件。',
    tokenOk:'口令已修改并同步到集群', tokenSame:'和当前口令一样,没有改动',
    tokenPartial:(n)=>`口令已改,但这些服务器没同步上:${n}(它们会在下次心跳时自动采纳)`,
    tokenFail:'修改口令失败:',
    scanInfo:'磁盘索引每 120 秒自动刷新;删除/传送后会立即触发',
    physBodies:'物理体逻辑', physEngine:'物理引擎',
    selInfo:(n,b)=>`已选 ${n} 体 · ${fmt(b)} 块`, selDel:'删除所选', selClear:'清空',
    selAdopt:(n)=>`收养所选孤儿 (${n})`,
    selDelT:'删除所选物理体',
    selDelMsg:(n,b)=>`将删除勾选依赖组中的 ${n} 个物理体,共 ${fmt(b)} 块。\n验收成功后整组进入回收站,可随时恢复。`,
    selAdoptT:'批量收养孤儿',
    selAdoptMsg:(n)=>`将逐个收养勾选中的 ${n} 个孤儿体(依赖一并接入,不修改磁盘数据)。`,
    selAdoptDone:(o,n)=>`收养完成:${o}/${n}`,
  }
};
const MANUAL = {
  zh: [
    {k:'states',label:'manualStates',sections:[
      {h:'运行与存档状态',body:'<ul><li><b>loaded</b>：物理体已加载并参与运行。</li><li><b>stored</b>：磁盘中有条目和有效 holding 指针，但当前未加载。</li><li><b>holding</b>：由 holding chunk 在内存中持有，尚待正常保存流程处理。</li><li><b>orphan</b>：磁盘条目仍在，但没有任何有效 holding 指针，正常流程无法再找到它。</li></ul>'},
      {h:'组合与异常',body:'<ul><li><b>依赖组</b>：按 Sable 依赖关系合并的一组物理体。删除和恢复以完整依赖组为边界。</li><li><b>副本</b>：同一个 UUID 在磁盘中出现多个条目，不代表内容一定相同。</li><li><b>疑似克隆</b>：不同 UUID 命中相同外形摘要，仅用于提示，不等同于确认重复。</li></ul>'},
      {h:'疑似克隆条件',body:'<p>命名体比较完整名称、方块数和取整后的包围盒；未命名体还要求不少于 50 块，再比较方块数和取整包围盒。该判断不比较体素、方块组成、维度和位置，因此可能误报，也可能漏报。</p>'}
    ]},
    {k:'cleanup',label:'manualCleanup',sections:[
      {h:'推荐删除',body:'<p>必须同时满足：整组无名称、总方块数低于动态阈值、方块种类低于动态阈值、方块实体数低于动态阈值、容器全空、无人乘坐、没有第三方数据。阈值来自 <code>config/sablepanel-server.json</code>。</p>'},
      {h:'保护条件',body:'<p>依赖组中任一成员有名称、玩家乘坐、第三方数据、非空容器，或整组达到任一保护阈值，就不会进入推荐删除。副本和疑似克隆只是异常提示，不会单独证明内容可删。</p>'}
    ]},
    {k:'operations',label:'manualOps',sections:[
      {h:'日常操作',body:'<ul><li><b>重扫</b>：立即重建磁盘索引；后台也会每 120 秒自动扫描。</li><li><b>传送</b>：坐标指结构包围盒的<b>底面中心</b>（目的 y=100 表示结构底部落在 y=100）。未加载体会先强制加载；孤儿会先尝试连同依赖一起收养。</li><li><b>收养</b>：重建 holding 指针并接回加载管线，不删除或改写原有物理体条目。</li><li><b>删除</b>：按完整依赖组执行，严格验收成功后才提交回收站备份。</li><li><b>暂停</b>：用引擎固定约束把结构锁定在原地（与"创造模式物理手杖"的锁定同机制）。已持久化，重启后仍保持，需手动恢复。</li><li><b>批量操作</b>：勾选会跨筛选保留；批量删除仍会按依赖组去重并逐组验收。</li></ul>'},
      {h:'副本去重',body:'<p>打开后会重新读取同 UUID 的每个槽位和全部 holding 指针。优先保留运行时正在使用的条目，其次保留有有效指针的条目，最后按条目 ID 稳定选择。只有所有完整 CompoundTag 深度相等时才可执行；操作通过 Sable 的 queueDeletion 与 saveAll 清理其它槽位，不直接改 region 文件，也不生成回收站备份。</p>'}
    ]},
    {k:'recycle',label:'manualRecycle',sections:[
      {h:'何时进入回收站',body:'<p>删除前会暂存完整依赖组备份；磁盘条目和 holding 指针通过删除后验收时记为待恢复。删除或自动回滚中断时，完整备份会记为需恢复，不再隐藏。</p>'},
      {h:'恢复与容量',body:'<ul><li>恢复始终恢复完整依赖组，并在保存的维度和位置重新加载；旧版本不会覆盖世界中已有的同 UUID 物理体。旧备份若没有维度则按主世界处理。</li><li><b>待恢复</b>表示删除成功且备份可用；最新版本的<b>需恢复</b>会先清除同 UUID 残留再从快照重建整组，且不会被容量策略自动淘汰；<b>已恢复</b>表示该记录已经成功执行过恢复。</li><li>容量按实际备份文件计数。超过上限时，按删除日期淘汰最早的普通完整依赖组；需恢复备份占满容量时，新删除会在执行前被拒绝。</li></ul>'}
    ]},
    {k:'performance',label:'manualPerformance',sections:[
      {h:'曲线口径',body:'<ul><li><b>各维度物理引擎</b>：该维度物理步进耗时按服务器 tick 折算的毫秒值。</li><li><b>物理体逻辑</b>：所有已加载物理体 Java 逻辑每 tick 的采样合计，不包含服务器其它 mod。</li><li>历史同时保存 tick 数、平均 MSPT 与峰值 MSPT，供区间聚合和接口查询。</li></ul>'},
      {h:'查询与交互',body:'<p>每秒采样异步写入 <code>config/sablepanel/stats/</code>。短范围读取秒级原始数据，长范围读取分钟数据并按时间桶保留峰值。悬停查看精确时间和各曲线值；滚轮以指针所在时间为锚点缩放；日期输入查看固定历史区间，此时停止自动刷新，点击“回到实时”恢复。</p>'},
      {h:'保留时间',body:'<p>默认保留 30 天，由 <code>statsRetentionDays</code> 控制。跨日文件会压缩，损坏行或未写完整的末行会跳过，不影响实时面板。</p>'}
    ]},
    {k:'maintenance',label:'manualMaintenance',sections:[
      {h:'口令与监听',body:'<p>访问口令作为页面密码保存在浏览器当前设备中；维护卡片修改后会同步到当前集群。网页默认使用 <code>webPort=25580</code>，TLS 数据接口默认使用 <code>apiPort=25581</code>；配置位于 <code>config/sablepanel-server.json</code>。</p>'},
      {h:'集群',body:'<p>同机且使用同一 <code>apiPort</code> 的实例组成面板集群。先占用数据端口的实例成为 HOST 并按 <code>webPort</code> 托管页面，其它实例通过回环地址注册为 PEER 并采纳 HOST 口令；顶部服务器选择器只切换查看目标，不迁移物理体数据。</p>'}
    ]}
  ]
};
function t(k){ const v = I18N.zh[k]; return v !== undefined ? v : k; }
function fmt(n){ return Number(n).toLocaleString('en-US'); }
function fmtBytes(value){
  let size=Math.max(0,Number(value)||0), unit=0;
  const units=['B','KiB','MiB','GiB','TiB'];
  while(size>=1024&&unit<units.length-1){ size/=1024; unit++; }
  return `${size.toFixed(unit===0||size>=100?0:size>=10?1:2)} ${units[unit]}`;
}
function applyI18n(){
  document.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.dataset.i18n); });
  document.querySelectorAll('[data-i18n-ph]').forEach(el => { el.placeholder = t(el.dataset.i18nPh); });
  document.getElementById('physChartTitle').textContent = t('physChartT');
  renderChartPresets();
  updateChartControls();
  if (document.getElementById('manualBack').style.display === 'flex') renderManual();
  if (COPY_SCAN && document.getElementById('copyBack').style.display === 'flex') renderDedupe(COPY_SCAN);
  renderSortRows();
}

package com.klnon.sablepanel.panel.bodies;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.audit.EventLog;
import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.klnon.sablepanel.panel.metrics.StatsCollector;
import com.klnon.sablepanel.panel.storage.BlockNames;
import com.klnon.sablepanel.panel.storage.ByteBudget;
import com.klnon.sablepanel.panel.storage.DiskScanner;
import com.klnon.sablepanel.panel.storage.UnionFind;

/**
 * 统一体索引:磁盘扫描快照(全量,含未加载/孤儿)+ 运行时加载态 + holding 内存态(均主线程刷新)。
 * 面板一切读取都走这里的内存缓存,HTTP 请求不触碰 sable 与磁盘。
 * 状态定义:loaded(运行时)> stored(盘上有指针)> holding(盘上无指针但 sable 内存持有,落盘延迟)> orphan(全都没有)。
 */
public final class BodyIndex {
    /** 磁盘快照超过这个年龄就不拿来做孤儿/holding 判定(正常扫描周期 120s) */
    private static final long STALE_SNAPSHOT_MS = 300_000L;
    /**
     * 断链判定的间隙容差。实测糖音气球组的答案在 0~100 格之间恒为同一批 19 体,
     * 150 格才开始把 250 格外那摊误并进来 —— 取 8 格留 12 倍余量。
     */
    private static final double DETACH_GAP = 8.0;
    /**
     * 断链判定的规模上限。
     * <p>
     * ponytail: 成对比较是 O(n²),2048² ≈ 420 万次(毫秒级),超过就不判、不发标记。
     * 真出现更大的组再上按 X 排序的扫描线。
     */
    private static final int DETACH_MAX_MEMBERS = 2048;
    /**
     * {@code /api/bodies} 单次响应最多输出这么多依赖组。
     * <p>
     * ponytail: 固定上限而非游标分页。这个接口喂的是整页的筛选/排序/多选/看板聚合,
     * 全部在前端本地算;真改成分页要把这几样一起搬到服务端,是另一个量级的改动。
     * 真有服务器越过这个数,再上分页。
     */
    private static final int MAX_VIEW_GROUPS = 3000;
    /**
     * 单次响应的字节预算。每个候选片段都用 {@link JsonSize} 量真实序列化字节后再决定收不收,
     * 不再按字段类型估 —— 估算漏掉哪个字段是无声的,而名称类字段(NBT display_name 上限 65535
     * 字节)一条就能顶穿协议上限。留给 32 MiB 协议上限的余量用来兜住"最后一条无条件收下的记录"。
     */
    private static final long VIEW_BYTE_BUDGET = 12L << 20;
    /** 克隆集合是去重提示,不是权威数据,超过这个数就不再往外发 */
    private static final int MAX_CLONE_SETS = 500;
    /** 克隆集合的总成员数上限:集合数封顶了,单个集合能有多少成员并没有封顶 */
    private static final int MAX_CLONE_MEMBERS = 20_000;
    /**
     * 克隆集合的字节子预算。它排在组列表之前构建,不给它单独划一块的话,
     * 500 个带超长名称的集合能把整个预算吃光,组列表反而一个都发不出去。
     */
    private static final long CLONE_BYTE_BUDGET = 1L << 20;
    /**
     * 调色板条数上限。它是全局表,一个成员的 blockIds 有多少种就能往里塞多少条,而每条都带
     * 中英文名 —— 组和成员都封顶之后,单个成员仍能靠这张表把响应顶过协议上限。
     * 取值远高于任何整合包的方块注册表规模,正常存档碰不到。
     */
    private static final int MAX_PALETTE = 20_000;
    /** 单个体最多列出这么多个冗余条目 id;真实数量由 copies 字段给出 */
    private static final int MAX_ENTRY_IDS = 50;


    private volatile DiskState disk = DiskState.empty();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong diskRevision = new AtomicLong();
    /** 主线程周期刷新:uuid -> 运行时摘要 */
    private volatile Map<UUID, RuntimeBody> runtime = Map.of();
    /** 主线程周期刷新:盘上无指针、但存在于 sable 内存 holding 表的 uuid */
    private volatile Set<UUID> holding = Set.of();
    /** 孤儿告警去抖:上一轮的孤儿集合 */
    private Set<UUID> prevOrphans = Set.of();
    /** 推荐删除的保护阈值,来自面板配置(服主可调) */
    private volatile PanelConfig config = new PanelConfig();

    record RuntimeBody(String dim, double x, double y, double z, double linearVelocity,
                       double mass, int players, boolean paused, double costMs) {
        static RuntimeBody positionOnly(String dim, double x, double y, double z) {
            return new RuntimeBody(dim, x, y, z, Double.NaN, Double.NaN, -1, false, Double.NaN);
        }

        RuntimeBody withPosition(String nextDim, double nextX, double nextY, double nextZ) {
            return new RuntimeBody(nextDim, nextX, nextY, nextZ, this.linearVelocity, this.mass,
                    this.players, this.paused, this.costMs);
        }

        RuntimeBody withCost(double nextCostMs) {
            return new RuntimeBody(this.dim, this.x, this.y, this.z, this.linearVelocity, this.mass,
                    this.players, this.paused, nextCostMs);
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("dim", this.dim);
            out.addProperty("x", this.x);
            out.addProperty("y", this.y);
            out.addProperty("z", this.z);
            if (Double.isFinite(this.linearVelocity)) out.addProperty("lin_vel", this.linearVelocity);
            if (Double.isFinite(this.mass)) out.addProperty("mass", this.mass);
            if (this.players >= 0) out.addProperty("players", this.players);
            if (this.paused) out.addProperty("paused", true);
            if (Double.isFinite(this.costMs)) out.addProperty("cost_ms", this.costMs);
            return out;
        }
    }

    /** 替换扫描快照；返回可见内容是否变化，避免无变化扫描触发 SSE 全量重拉。 */
    public boolean updateDisk(List<DiskScanner.DiskEntry> entries) {
        List<DiskScanner.DiskEntry> snapshot = List.copyOf(entries);
        DiskState previous = this.disk;
        boolean changed = previous.scanTime == 0 || !sameDiskEntries(previous.entries, snapshot);
        DiskLookup lookup = changed ? DiskLookup.from(snapshot) : previous.lookup;
        this.disk = new DiskState(snapshot, System.currentTimeMillis(), lookup);
        if (changed) this.diskRevision.incrementAndGet();
        this.version.incrementAndGet();
        return changed;
    }

    public long version() {
        return this.version.get();
    }

    long diskRevision() {
        return this.diskRevision.get();
    }

    private static boolean sameDiskEntries(List<DiskScanner.DiskEntry> previous,
                                           List<DiskScanner.DiskEntry> current) {
        if (previous.size() != current.size()) return false;
        Map<DiskScanner.EntryKey, DiskScanner.DiskEntry> byKey = new HashMap<>();
        for (DiskScanner.DiskEntry entry : previous) byKey.put(entry.key(), entry);
        for (DiskScanner.DiskEntry entry : current) {
            if (!sameDiskEntry(byKey.remove(entry.key()), entry)) return false;
        }
        return byKey.isEmpty();
    }

    private static boolean sameDiskEntry(DiskScanner.DiskEntry left, DiskScanner.DiskEntry right) {
        return left != null
                && Objects.equals(left.uuid(), right.uuid())
                && Objects.equals(left.name(), right.name())
                && Arrays.equals(left.pos(), right.pos())
                && Arrays.equals(left.size(), right.size())
                && left.blocks() == right.blocks()
                && left.deps().equals(right.deps())
                && left.reachable() == right.reachable()
                && left.plotX() == right.plotX()
                && left.plotZ() == right.plotZ()
                && left.blockIds().equals(right.blockIds())
                && left.userData() == right.userData()
                && left.blockEntities() == right.blockEntities()
                && left.contents() == right.contents();
    }

    /** 运行时操作完成后立即修正坐标缓存,避免面板等到下一次周期刷新。 */
    public void updateRuntimePosition(UUID uuid, String dim, double[] position) {
        if (position == null || position.length != 3) {
            throw new IllegalArgumentException("position 必须包含 x/y/z");
        }
        Map<UUID, RuntimeBody> updated = new HashMap<>(this.runtime);
        RuntimeBody state = updated.get(uuid);
        updated.put(uuid, state == null
                ? RuntimeBody.positionOnly(dim, position[0], position[1], position[2])
                : state.withPosition(dim, position[0], position[1], position[2]));
        this.runtime = Map.copyOf(updated);
        this.version.incrementAndGet();
    }

    /** 主线程调用:刷新运行时加载态 + holding 态 + 逐体耗时 + 孤儿告警 */
    public void refreshRuntime(MinecraftServer server, int ticksSinceLast) {
        // 先守护常驻体(sable 只在世界 initialize 时按票加载一次,掉线后不会自愈),
        // 这样本轮拉回的体能立刻计入下面的运行时视图
        com.klnon.sablepanel.panel.ops.ForceLoadService.guardOnMain(server);
        // 停跑物理的意图也在这里重放:维度可能后加载,重启后也要压回去
        com.klnon.sablepanel.panel.ops.PhysicsService.guardOnMain(server);
        Map<UUID, RuntimeBody> map = new HashMap<>();
        Map<String, Integer> loadedPerDim = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                String dim = level.dimension().location().toString();
                int n = 0;
                for (ServerSubLevel sl : c.getAllSubLevels()) {
                    // 面板坐标统一为包围盒底面中心(与传送目标语义一致);包围盒异常时退回 pose 原点
                    var p = sl.logicalPose().position();
                    double ax = p.x(), ay = p.y(), az = p.z();
                    try {
                        var bb = sl.boundingBox();
                        double cx = (bb.minX() + bb.maxX()) / 2, cy = bb.minY(), cz = (bb.minZ() + bb.maxZ()) / 2;
                        if (Double.isFinite(cx) && Double.isFinite(cy) && Double.isFinite(cz)
                                && bb.maxX() >= bb.minX()) {
                            ax = cx;
                            ay = cy;
                            az = cz;
                        }
                    } catch (Throwable ignored) {
                    }
                    double mass = Double.NaN;
                    try {
                        mass = r1(sl.getMassTracker().getMass());
                    } catch (Throwable ignored) {
                    }
                    int players = -1;
                    try {
                        players = sl.getTrackingPlayers().size();
                    } catch (Throwable ignored) {
                    }
                    boolean paused = com.klnon.sablepanel.panel.ops.PauseService.isPaused(sl.getUniqueId());
                    map.put(sl.getUniqueId(), new RuntimeBody(dim, r1(ax), r1(ay), r1(az),
                            r1(sl.latestLinearVelocity.length()), mass, players, paused, Double.NaN));
                    n++;
                }
                if (n > 0) loadedPerDim.put(dim, n);
            } catch (Throwable ignored) {
            }
        }

        // 逐体耗时(mixin 采样):附到 runtime,并产出 Top 列表给 /api/stats
        try {
            Map<UUID, Double> cost = com.klnon.sablepanel.panel.metrics.BodyCostTracker.drain(ticksSinceLast, map.keySet());
            PriorityQueue<Map.Entry<UUID, Double>> top = new PriorityQueue<>(
                    Comparator.comparingDouble(Map.Entry::getValue));
            JsonArray topArr = new JsonArray();
            double totalCost = 0;
            for (Map.Entry<UUID, Double> entry : cost.entrySet()) {
                totalCost += entry.getValue();
                if (top.size() < 10) top.offer(entry);
                else if (entry.getValue() > top.element().getValue()) {
                    top.remove();
                    top.offer(entry);
                }
            }
            List<Map.Entry<UUID, Double>> orderedTop = new ArrayList<>(top);
            orderedTop.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (Map.Entry<UUID, Double> en : orderedTop) {
                JsonObject t = new JsonObject();
                t.addProperty("uuid", en.getKey().toString());
                DiskScanner.DiskEntry de = findEntry(en.getKey());
                if (de != null && de.name() != null) t.addProperty("name", de.name());
                t.addProperty("cost", r3(en.getValue()));
                topArr.add(t);
            }
            for (Map.Entry<UUID, Double> en : cost.entrySet()) {
                RuntimeBody runtimeBody = map.get(en.getKey());
                if (runtimeBody != null) map.put(en.getKey(), runtimeBody.withCost(r3(en.getValue())));
            }
            StatsCollector.INSTANCE.setBodyCost(topArr, r3(totalCost));
        } catch (Throwable ignored) {
        }

        this.runtime = Map.copyOf(map);
        StatsCollector.INSTANCE.setLoadedPerDim(Map.copyOf(loadedPerDim));

        // holding/孤儿判定要求磁盘快照与现实足够接近。快照过旧(面板空闲时扫描暂停,
        // 或扫描故障)就跳过:拿陈旧条目对比现实会把正常体误报成孤儿
        DiskState disk = this.disk;
        if (System.currentTimeMillis() - disk.scanTime > STALE_SNAPSHOT_MS) {
            this.version.incrementAndGet();
            return;
        }

        // holding 态:只查"盘上无任何可达条目"的候选(小集合),避免主线程开销
        Map<UUID, String> unreachable = disk.lookup.unreachableDimensions;
        Set<UUID> holdingSet = new HashSet<>();
        Set<UUID> orphans = new HashSet<>();
        Map<String, ServerSubLevelContainer> containers = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c != null) containers.put(level.dimension().location().toString(), c);
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<UUID, String> en : unreachable.entrySet()) {
            if (map.containsKey(en.getKey())) continue;
            ServerSubLevelContainer c = containers.get(en.getValue());
            boolean held = false;
            try {
                held = c != null && c.getHoldingChunkMap().getHoldingSubLevel(en.getKey()) != null;
            } catch (Throwable ignored) {
            }
            if (held) holdingSet.add(en.getKey());
            else orphans.add(en.getKey());
        }
        this.holding = holdingSet;

        // 孤儿告警:新出现的孤儿(上一轮不是)写事件日志
        if (!this.prevOrphans.isEmpty() || !orphans.isEmpty()) {
            for (UUID u : orphans) {
                if (!this.prevOrphans.contains(u)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("ev", "alert_orphan");
                    o.addProperty("uuid", u.toString());
                    o.addProperty("dim", unreachable.get(u));
                    EventLog.write(o);
                    SablePanel.LOGGER.warn("sablepanel: body {} became orphan (entry on disk, no pointer, not loaded, not holding)", u);
                }
            }
        }
        this.prevOrphans = orphans;
        this.version.incrementAndGet();
    }

    public DiskScanner.DiskEntry findEntry(UUID uuid) {
        return this.disk.lookup.best.get(uuid);
    }

    public String thumbnailSignature(UUID uuid) {
        return this.disk.lookup.thumbnailSignatures.get(uuid);
    }

    public record PreviewSelection(DiskScanner.DiskEntry entry, boolean ambiguous) {
    }

    /** Refuses to guess when more than one reachable/current-looking disk copy exists. */
    public PreviewSelection previewSelection(UUID uuid) {
        List<DiskScanner.DiskEntry> candidates = this.disk.lookup.byUuid.getOrDefault(uuid, List.of());
        if (candidates.isEmpty()) return new PreviewSelection(null, false);
        List<DiskScanner.DiskEntry> reachable = candidates.stream().filter(DiskScanner.DiskEntry::reachable).toList();
        if (reachable.size() == 1) return new PreviewSelection(reachable.get(0), false);
        if (reachable.size() > 1 || candidates.size() > 1) return new PreviewSelection(null, true);
        return new PreviewSelection(candidates.get(0), false);
    }

    /** 全量视图 JSON:组聚合 + 体明细 + 方块调色板。五段流水,段间以只读 record 传递 */
    public JsonObject view() {
        DiskState diskState = this.disk;
        List<DiskScanner.DiskEntry> disk = diskState.entries;
        Map<UUID, RuntimeBody> rt = this.runtime;
        Set<UUID> held = this.holding;

        DiskAggregate agg = aggregate(disk);
        CloneSets clones = cloneSets(agg.byUuid());
        List<Map.Entry<UUID, List<UUID>>> ordered = orderedGroups(agg.byUuid());
        FreshGroups fresh = freshGroups(rt, agg.byUuid());
        Emission emission = emitGroups(agg, clones, fresh, ordered, rt, held);
        return summarize(disk, agg, clones, fresh, emission, ordered.size(), diskState.scanTime);
    }

    private record DiskState(List<DiskScanner.DiskEntry> entries, long scanTime, DiskLookup lookup) {
        static DiskState empty() {
            return new DiskState(List.of(), 0, DiskLookup.empty());
        }
    }

    private record DiskLookup(Map<UUID, List<DiskScanner.DiskEntry>> byUuid,
                              Map<UUID, DiskScanner.DiskEntry> best,
                              Map<UUID, String> unreachableDimensions,
                              Map<UUID, String> thumbnailSignatures) {
        static DiskLookup empty() {
            return new DiskLookup(Map.of(), Map.of(), Map.of(), Map.of());
        }

        static DiskLookup from(List<DiskScanner.DiskEntry> entries) {
            Map<UUID, List<DiskScanner.DiskEntry>> grouped = new HashMap<>();
            Map<UUID, DiskScanner.DiskEntry> best = new HashMap<>();
            Map<UUID, Boolean> reachable = new HashMap<>();
            Map<UUID, String> dimensions = new HashMap<>();
            for (DiskScanner.DiskEntry entry : entries) {
                UUID uuid = entry.uuid();
                if (uuid == null) continue;
                grouped.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(entry);
                best.compute(uuid, (ignored, current) -> better(current, entry));
                reachable.merge(uuid, entry.reachable(), Boolean::logicalOr);
                dimensions.putIfAbsent(uuid, entry.key().dim());
            }
            Map<UUID, List<DiskScanner.DiskEntry>> byUuid = new HashMap<>();
            Map<UUID, String> signatures = new HashMap<>();
            grouped.forEach((uuid, mine) -> {
                List<DiskScanner.DiskEntry> immutable = List.copyOf(mine);
                byUuid.put(uuid, immutable);
                signatures.put(uuid,
                        com.klnon.sablepanel.panel.preview.thumb.ThumbService.signature(immutable));
            });
            Map<UUID, String> unreachable = new HashMap<>();
            dimensions.forEach((uuid, dim) -> {
                if (!reachable.getOrDefault(uuid, false)) unreachable.put(uuid, dim);
            });
            return new DiskLookup(Map.copyOf(byUuid), Map.copyOf(best), Map.copyOf(unreachable),
                    Map.copyOf(signatures));
        }

        private static DiskScanner.DiskEntry better(DiskScanner.DiskEntry current,
                                                     DiskScanner.DiskEntry candidate) {
            if (current == null || (candidate.reachable() && !current.reachable())
                    || (candidate.reachable() == current.reachable()
                    && candidate.blocks() > current.blocks())) return candidate;
            return current;
        }
    }

    /**
     * 磁盘条目按 uuid 聚合的结果:最优条目、冗余计数、条目 id 采样、可达性、位置存疑集合。
     *
     * @param ambiguousPos 同一 uuid 的多份存盘条目彼此位置不同 —— {@link #aggregate} 只留一条
     *                     (可达优先、块数多优先),块数相同就是任挑一份。这种体的坐标是几份互相
     *                     矛盾的历史快照里的一份,不能拿来判断它在不在本体旁边。
     */
    private record DiskAggregate(Map<UUID, DiskScanner.DiskEntry> byUuid, Map<UUID, Integer> copies,
                                 Map<UUID, List<String>> entryIds, Map<UUID, Boolean> anyReachable,
                                 Set<UUID> ambiguousPos) {
    }

    /** 疑似克隆集合:uuid → 集合 id、发出去的集合数组、已花掉的字节(并进总账) */
    private record CloneSets(Map<UUID, Integer> setByUuid, JsonArray sets, long bytes) {
    }

    /** 纯运行时组:发出去的组、进组的 uuid、真实总数(可能大于显示数)、已花字节 */
    private record FreshGroups(JsonArray groups, List<UUID> uuids, int total, long bytes) {
    }

    /** 预算发射的产物:组数组、调色板、真正输出出去的体、两类截断标记 */
    private record Emission(JsonArray groups, JsonArray palette, Set<UUID> bodies,
                            int omittedMembers, boolean paletteFull) {
    }

    private static DiskAggregate aggregate(List<DiskScanner.DiskEntry> disk) {
        // uuid -> 最优条目(可达优先,blocks 大优先);同 uuid 冗余条目单独计数
        Map<UUID, DiskScanner.DiskEntry> byUuid = new HashMap<>();
        Map<UUID, Integer> copies = new HashMap<>();
        Map<UUID, List<String>> entryIds = new HashMap<>();
        Map<UUID, double[]> firstBox = new HashMap<>();
        Set<UUID> ambiguousPos = new HashSet<>();
        for (DiskScanner.DiskEntry e : disk) {
            if (e.uuid() == null) continue;
            copies.merge(e.uuid(), 1, Integer::sum);
            double[] box = boxOf(e.pos(), e.size());
            double[] seen = firstBox.putIfAbsent(e.uuid(), box);
            if (seen != null && !Arrays.equals(seen, box)) ambiguousPos.add(e.uuid());
            // 采集阶段就封顶:输出只发前 MAX_ENTRY_IDS 条,为剩下那些永远不会显示的条目
            // 拼字符串纯属白费堆和 CPU,而条目数是随存档损坏程度增长的
            List<String> ids = entryIds.computeIfAbsent(e.uuid(), k -> new ArrayList<>());
            if (ids.size() < MAX_ENTRY_IDS) ids.add(e.key().id() + (e.reachable() ? "" : " (无指针)"));
            DiskScanner.DiskEntry cur = byUuid.get(e.uuid());
            if (cur == null || (e.reachable() && !cur.reachable())
                    || (e.reachable() == cur.reachable() && e.blocks() > cur.blocks())) {
                byUuid.put(e.uuid(), e);
            }
        }
        // uuid 可达性 = 任一条目可达
        Map<UUID, Boolean> anyReachable = new HashMap<>();
        for (DiskScanner.DiskEntry e : disk) {
            if (e.uuid() != null) anyReachable.merge(e.uuid(), e.reachable(), Boolean::logicalOr);
        }

        return new DiskAggregate(byUuid, copies, entryIds, anyReachable, ambiguousPos);
    }

    private static CloneSets cloneSets(Map<UUID, DiskScanner.DiskEntry> byUuid) {
        // 疑似克隆:不同 uuid、同(名称|方块数|包围盒),未命名的要求 ≥50 块
        Map<String, Set<UUID>> cloneKeys = new HashMap<>();
        for (var en : byUuid.entrySet()) {
            DiskScanner.DiskEntry e = en.getValue();
            String key = cloneKey(e);
            if (key != null) cloneKeys.computeIfAbsent(key, k -> new HashSet<>()).add(en.getKey());
        }
        Map<UUID, Integer> cloneSetByUuid = new HashMap<>();
        JsonArray cloneSetArr = new JsonArray();
        List<Map.Entry<String, Set<UUID>>> orderedCloneSets = cloneKeys.entrySet().stream()
                .filter(en -> en.getValue().size() >= 2)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        ByteBudget cloneBudget = new ByteBudget(CLONE_BYTE_BUDGET);
        int cloneMembers = 0;
        for (int setId = 0; setId < Math.min(orderedCloneSets.size(), MAX_CLONE_SETS); setId++) {
            List<UUID> matches = new ArrayList<>(orderedCloneSets.get(setId).getValue());
            // 只封顶集合数不够:几万个同尺寸残骸会挤进同一个集合,一个集合就能撑爆响应
            if (cloneMembers + matches.size() > MAX_CLONE_MEMBERS) break;
            matches.sort(UUID::compareTo);
            DiskScanner.DiskEntry sample = byUuid.get(matches.get(0));
            JsonObject set = new JsonObject();
            set.addProperty("id", setId);
            set.addProperty("mode", sample.name() != null ? "named" : "unnamed");
            // name 直接来自 NBT 的 display_name,单条上限 65535 字节 —— 500 个这样的集合
            // 就是 31 MiB,光靠"集合数 × 固定开销"的估算完全看不见
            if (sample.name() != null) set.addProperty("name", sample.name());
            set.addProperty("blocks", sample.blocks());
            JsonArray roundedSize = new JsonArray();
            for (double axis : sample.size()) roundedSize.add(Math.round(axis));
            set.add("rounded_size", roundedSize);
            JsonArray setMembers = new JsonArray();
            for (UUID match : matches) setMembers.add(match.toString());
            set.add("members", setMembers);
            // 量真值再决定收不收。收不下就整个停掉:后面的集合只会更靠后,没有必要继续试
            if (!cloneBudget.offer(set)) break;
            cloneMembers += matches.size();
            for (UUID match : matches) cloneSetByUuid.put(match, setId);
            cloneSetArr.add(set);
        }

        return new CloneSets(cloneSetByUuid, cloneSetArr, cloneBudget.spent());
    }

    private static List<Map.Entry<UUID, List<UUID>>> orderedGroups(Map<UUID, DiskScanner.DiskEntry> byUuid) {
        // 并查集分组(按 deps,双向)
        UnionFind linked = new UnionFind();
        for (UUID u : byUuid.keySet()) linked.add(u);
        for (DiskScanner.DiskEntry e : byUuid.values()) {
            for (UUID d : e.deps()) {
                if (linked.contains(d)) linked.union(e.uuid(), d);
            }
        }
        Map<UUID, List<UUID>> groups = new HashMap<>();
        for (UUID u : byUuid.keySet()) {
            groups.computeIfAbsent(linked.find(u), k -> new ArrayList<>()).add(u);
        }

        // 单页硬上限。从前是无条件全量构建:响应大小只随存档增长,32 MiB 的协议上限拦不住
        // "先把整个对象建到堆里"。只按组数截断还不够 —— 3000 个巨型组照样能撑爆,所以组数之外
        // 再算一份字节预算(按各字段上界估),两条中先到的那条生效。
        // 排序按总方块数降序:真被砍掉的是最小的那些组,total_bodies 仍是真值,前端显式提示已截断。
        List<Map.Entry<UUID, List<UUID>>> ordered = new ArrayList<>(groups.entrySet());
        Map<UUID, Long> weight = new HashMap<>();
        for (Map.Entry<UUID, List<UUID>> g : ordered) {
            long sum = 0;
            for (UUID u : g.getValue()) sum += byUuid.get(u).blocks();
            weight.put(g.getKey(), sum);
        }
        ordered.sort(Comparator.comparingLong((Map.Entry<UUID, List<UUID>> g) -> weight.get(g.getKey())).reversed());

        return ordered;
    }

    private static FreshGroups freshGroups(Map<UUID, RuntimeBody> rt, Map<UUID, DiskScanner.DiskEntry> byUuid) {
        // 运行时存在但磁盘还没有条目的体(刚生成/未保存):单独成组显示,可传送不可预览
        JsonArray freshArr = new JsonArray();
        List<UUID> freshUuids = new ArrayList<>();
        // fresh 组先走一份自己的账本,最后并进总账
        ByteBudget freshBudget = new ByteBudget(VIEW_BYTE_BUDGET);
        int freshTotal = 0;
        for (Map.Entry<UUID, RuntimeBody> en : rt.entrySet()) {
            if (byUuid.containsKey(en.getKey())) continue;
            freshTotal++;
            if (freshArr.size() >= MAX_VIEW_GROUPS || freshBudget.exhausted()) continue;
            RuntimeBody runtime = en.getValue();
            JsonObject rto = runtime.toJson();
            JsonObject m = new JsonObject();
            m.addProperty("uuid", en.getKey().toString());
            m.addProperty("entry", "");
            m.addProperty("dim", runtime.dim());
            m.addProperty("blocks", 0);
            JsonArray pos = new JsonArray();
            pos.add(runtime.x());
            pos.add(runtime.y());
            pos.add(runtime.z());
            m.add("pos", pos);
            JsonArray sz = new JsonArray();
            sz.add(0); sz.add(0); sz.add(0);
            m.add("size", sz);
            m.addProperty("state", "loaded");
            m.addProperty("fresh", true);
            m.add("blk", new JsonArray());
            m.add("runtime", rto);
            JsonObject go = new JsonObject();
            go.addProperty("gid", en.getKey().toString());
            go.addProperty("name", "");
            go.addProperty("members", 1);
            go.addProperty("blocks", 0);
            go.addProperty("dims", runtime.dim());
            go.addProperty("loaded", 1);
            go.addProperty("orphans", 0);
            go.addProperty("holding", 0);
            go.addProperty("types", 0);
            go.addProperty("be", 0);
            go.addProperty("contents", 0);
            JsonArray ma = new JsonArray();
            ma.add(m);
            go.add("bodies", ma);
            // runtime 是 sable 给的对象,字段随版本变;量真值就不必跟着它改估算
            if (!freshBudget.offer(go)) continue;
            freshUuids.add(en.getKey());
            freshArr.add(go);
        }

        return new FreshGroups(freshArr, freshUuids, freshTotal, freshBudget.spent());
    }

    private Emission emitGroups(DiskAggregate agg, CloneSets clones, FreshGroups fresh,
                                List<Map.Entry<UUID, List<UUID>>> ordered,
                                Map<UUID, RuntimeBody> rt, Set<UUID> held) {
        Map<UUID, DiskScanner.DiskEntry> byUuid = agg.byUuid();
        Map<UUID, Integer> copies = agg.copies();
        Map<UUID, List<String>> entryIds = agg.entryIds();
        Map<UUID, Boolean> anyReachable = agg.anyReachable();
        Map<UUID, Integer> cloneSetByUuid = clones.setByUuid();
        JsonArray freshArr = fresh.groups();
        List<UUID> freshUuids = fresh.uuids();
        // 方块调色板(全局去重,body 引用索引)。只收真正输出出去的成员用到的方块 ——
        // 从前是先按全部磁盘条目建一张完整表,截断之后表里全是没人引用的条目,白占字节
        Map<String, Integer> paletteIdx = new LinkedHashMap<>();
        JsonArray paletteArr = new JsonArray();

        JsonArray groupArr = new JsonArray();
        groupArr.addAll(freshArr);
        // 预算是一份运行总账,记的全是量出来的真实字节:已发出去的 fresh 组、clone_sets
        // 先记进去,组列表用剩下的额度,调色板边建边记
        ByteBudget budget = new ByteBudget(VIEW_BYTE_BUDGET);
        budget.charge(fresh.bytes());
        budget.charge(clones.bytes());
        // 组数上限管的是"发出去多少组",fresh 组也占名额,分开计数就会一起超过 MAX_VIEW_GROUPS
        int shownGroups = freshArr.size();
        int omittedMembers = 0;
        boolean paletteFull = false;
        // 真正发出去的体。paused/forced 只发这些体的状态,见下面
        Set<UUID> emitted = new HashSet<>(freshUuids);
        for (Map.Entry<UUID, List<UUID>> g : ordered) {
            // 至少出一组:否则单个超预算的巨型组会让整个列表空着
            if (shownGroups > 0 && (shownGroups >= MAX_VIEW_GROUPS || budget.exhausted())) break;
            shownGroups++;
            List<UUID> members = g.getValue();
            long totalBlocks = 0;
            String bestName = null;
            int bestNameBlocks = -1;
            Set<String> dims = new HashSet<>();
            int loadedCount = 0, orphanCount = 0, holdingCount = 0;
            boolean groupDup = false, groupClone = false;
            // 推荐删除判定用:任一成员命中即保护整组(依赖组一荣俱荣)
            int maxBlocks = 0;
            boolean anyNamed = false, anyTracked = false, anyUserData = false;
            int nonOrphan = 0;
            int groupBe = 0, groupContents = 0;
            Set<String> groupBlockIds = new HashSet<>();
            JsonArray memberArr = new JsonArray();
            members.sort((a, b) -> Integer.compare(byUuid.get(b).blocks(), byUuid.get(a).blocks()));
            // 断链残骸判定要在成员截断之前做:截断只影响明细,组上的计数必须是真值。
            // 加载中的体用运行时坐标,那是现实本身,再多存盘条目也不影响可信度
            Set<UUID> detached = detachedMembers(members, u -> {
                DiskScanner.DiskEntry de = byUuid.get(u);
                return boxOf(displayPos(rt.get(u), de.pos()), de.size());
            }, u -> rt.containsKey(u) || !agg.ambiguousPos().contains(u));
            // 主体自己的坐标就存疑时,整份判定是围着一个说不准的参照系转的 —— 删除前要说清楚
            boolean detachUnsure = !detached.isEmpty() && !rt.containsKey(members.get(0))
                    && agg.ambiguousPos().contains(members.get(0));
            for (UUID u : members) {
                DiskScanner.DiskEntry e = byUuid.get(u);
                RuntimeBody runtime = rt.get(u);
                boolean loaded = runtime != null;
                boolean reach = anyReachable.getOrDefault(u, false);
                String state = loaded ? "loaded" : reach ? "stored" : held.contains(u) ? "holding" : "orphan";
                totalBlocks += e.blocks();
                dims.add(e.key().dim());
                if (loaded) loadedCount++;
                if (state.equals("orphan")) orphanCount++;
                else nonOrphan++;
                if (state.equals("holding")) holdingCount++;
                maxBlocks = Math.max(maxBlocks, e.blocks());
                groupBe += e.blockEntities();
                groupContents += e.contents();
                groupBlockIds.addAll(e.blockIds());
                if (e.name() != null && !e.name().isBlank()) anyNamed = true;
                if (e.userData()) anyUserData = true;
                if (runtime != null && runtime.players() > 0) anyTracked = true;
                if (e.name() != null && e.blocks() > bestNameBlocks) {
                    bestName = e.name();
                    bestNameBlocks = e.blocks();
                }
                // 预算要按成员查,不能只在组的入口查:一条几万成员的依赖链就是一个单组的
                // 超大响应。上面的聚合计数仍按全部成员算,所以 members/blocks 是真值,
                // 只有 bodies 明细会少。真正的收/不收在下面 offer 那里,这里只是提前跳过构建
                if (!memberArr.isEmpty() && budget.exhausted()) {
                    omittedMembers++;
                    continue;
                }
                JsonObject m = new JsonObject();
                m.addProperty("uuid", u.toString());
                m.addProperty("entry", e.key().id());
                if (e.name() != null) m.addProperty("name", e.name());
                m.addProperty("dim", e.key().dim());
                m.addProperty("blocks", e.blocks());
                m.add("pos", arr(displayPos(runtime, e.pos())));
                m.add("size", arr(e.size()));
                m.addProperty("state", state);
                if (detached.contains(u)) m.addProperty("detached", true);
                if (e.blockEntities() > 0) m.addProperty("be", e.blockEntities());
                if (e.contents() > 0) m.addProperty("contents", e.contents());
                int cp = copies.getOrDefault(u, 1);
                if (cp > 1) {
                    m.addProperty("copies", cp);
                    groupDup = true;
                    // 同 UUID 的冗余条目数没有上限(存档损坏/反复搬迁能刷出成千上万条),
                    // 真实数量由 copies 带出去,列表只发前几条够看即可(采集阶段已经截过)
                    JsonArray ea = new JsonArray();
                    for (String id : entryIds.getOrDefault(u, List.of())) ea.add(id);
                    m.add("entries", ea);
                }
                Integer cloneSet = cloneSetByUuid.get(u);
                if (cloneSet != null) {
                    m.addProperty("clone", true);
                    m.addProperty("clone_set", cloneSet);
                    groupClone = true;
                }
                if (!e.deps().isEmpty()) m.addProperty("deps", e.deps().size());
                JsonArray blk = new JsonArray();
                for (String id : e.blockIds()) {
                    Integer at = paletteIdx.get(id);
                    if (at == null) {
                        // 条数和字节都要在"加入之前"判:预算只在进成员之前查一次的话,
                        // 进来之后这个循环还能继续无限追加 —— 方块 id 直接来自 NBT,
                        // 解析不出来时 BlockNames 会把原串同时放进 en/zh,一条就能有几十 KB
                        if (paletteIdx.size() >= MAX_PALETTE) {
                            paletteFull = true;
                            continue;
                        }
                        JsonObject p = BlockNames.paletteEntry(id);
                        if (!budget.offer(p)) {
                            paletteFull = true;
                            continue;
                        }
                        at = paletteIdx.size();
                        paletteIdx.put(id, at);
                        paletteArr.add(p);
                    }
                    blk.add(at);
                }
                m.add("blk", blk);
                if (loaded) m.add("runtime", runtime.toJson());
                // 组内第一条必须发得出去(否则这个组就是个空壳),其余按预算收
                if (memberArr.isEmpty()) budget.charge(m);
                else if (!budget.offer(m)) {
                    omittedMembers++;
                    continue;
                }
                emitted.add(u);
                memberArr.add(m);
            }
            JsonObject go = new JsonObject();
            go.addProperty("gid", g.getKey().toString());
            go.addProperty("name", bestName != null ? bestName : "");
            go.addProperty("members", members.size());
            go.addProperty("blocks", totalBlocks);
            go.addProperty("dims", String.join(",", dims));
            go.addProperty("loaded", loadedCount);
            go.addProperty("orphans", orphanCount);
            go.addProperty("holding", holdingCount);
            if (groupDup) go.addProperty("dup", true);
            if (groupClone) go.addProperty("clone", true);
            go.addProperty("types", groupBlockIds.size());
            go.addProperty("be", groupBe);
            go.addProperty("contents", groupContents);
            if (memberArr.size() < members.size()) go.addProperty("members_omitted", members.size() - memberArr.size());
            if (!detached.isEmpty()) go.addProperty("detached", detached.size());
            if (detachUnsure) go.addProperty("detach_unsure", true);
            JsonObject verdict = recommend(new RecInput(totalBlocks, maxBlocks, groupBlockIds.size(), groupBe,
                    groupContents, anyNamed, anyTracked, anyUserData, orphanCount, nonOrphan, groupDup, groupClone));
            if (verdict.has("reasons")) go.add("rec", verdict);
            else go.add("prot", verdict.getAsJsonArray("protected_by"));
            // 趁 bodies 还没挂上去量组自身的开销 —— 成员的字节在上面已经逐个记过了。
            // 组名是某个成员名称的副本,同样可以有 65535 字节,不量就是又一个漏记的字段
            budget.charge(go);
            go.add("bodies", memberArr);
            groupArr.add(go);
        }

        return new Emission(groupArr, paletteArr, emitted, omittedMembers, paletteFull);
    }

    private JsonObject summarize(List<DiskScanner.DiskEntry> disk, DiskAggregate agg, CloneSets clones,
                                 FreshGroups fresh, Emission emission, int groupCount, long scanTime) {
        Map<UUID, DiskScanner.DiskEntry> byUuid = agg.byUuid();
        int freshTotal = fresh.total();
        JsonArray groupArr = emission.groups();
        int omittedMembers = emission.omittedMembers();
        boolean paletteFull = emission.paletteFull();
        Set<UUID> emitted = emission.bodies();
        JsonArray paletteArr = emission.palette();
        JsonArray cloneSetArr = clones.sets();
        JsonObject out = new JsonObject();
        out.addProperty("scan_time", scanTime);
        // freshArr 是被截断过的显示量,总数必须用真值,否则截断时连"少了多少"都看不出来
        out.addProperty("total_bodies", byUuid.size() + freshTotal);
        out.addProperty("total_entries", disk.size());
        int totalGroups = groupCount + freshTotal;
        out.addProperty("total_groups", totalGroups);
        out.addProperty("shown_groups", groupArr.size());
        // 组数上限、字节预算、组内成员截断、调色板封顶,任一条生效都算截断。
        // 三种截断的后果完全不同(少了组 / 组内少了成员 / 构成不全),提示要分开说,
        // 否则"只显示 3000 / 1 组"这种自相矛盾的话就会出现在界面上
        if (groupArr.size() < totalGroups || omittedMembers > 0 || paletteFull) {
            out.addProperty("truncated", true);
        }
        if (omittedMembers > 0) out.addProperty("omitted_members", omittedMembers);
        if (paletteFull) out.addProperty("palette_truncated", true);
        // 暂停 / 常驻集合(含未加载体的意图):前端以此为单一事实源渲染 ⏸ 和 📌。
        // 只发已经输出出去的体 —— 徽章画在行上,没有行的体发过去没人看,而这两个集合
        // 自身没有任何上限,整份发就是响应里最后一处只随存档增长的字段
        Set<UUID> pausedAll = com.klnon.sablepanel.panel.ops.PauseService.snapshot();
        Set<UUID> forcedAll = com.klnon.sablepanel.panel.ops.ForceLoadService.snapshot();
        // 冻结与暂停是两回事:暂停只锁物理(机器照转),冻结连方块实体都不 tick。分开发,别合并
        Set<UUID> frozenAll = com.klnon.sablepanel.panel.ops.FreezeService.snapshot();
        // 常驻掉线 = 意图在、票不在:守护剥了拉不回来的票,或周期恢复还没成功。不单发的话
        // 它和"从未常驻"在界面上不可分辨 —— 2026-08-22 恢复失败的体徽章消失,被误读成取消成功
        Set<UUID> forcedIntents = com.klnon.sablepanel.panel.ops.ForceLoadService.requestedSnapshot();
        // 非面板票(sable 指令/其他模组):uuid → 票种 id 数组,画"常驻·外部"来源徽章
        Map<UUID, Set<String>> foreignAll = com.klnon.sablepanel.panel.ops.ForceLoadService.foreignSnapshot();
        JsonArray pausedArr = new JsonArray();
        JsonArray forcedArr = new JsonArray();
        JsonArray frozenArr = new JsonArray();
        JsonArray forcedLostArr = new JsonArray();
        JsonObject foreignObj = new JsonObject();
        for (UUID u : emitted) {
            if (pausedAll.contains(u)) pausedArr.add(u.toString());
            if (forcedAll.contains(u)) forcedArr.add(u.toString());
            else if (forcedIntents.contains(u)) forcedLostArr.add(u.toString());
            if (frozenAll.contains(u)) frozenArr.add(u.toString());
            Set<String> foreignTypes = foreignAll.get(u);
            if (foreignTypes != null) {
                JsonArray types = new JsonArray();
                for (String type : foreignTypes) types.add(type);
                foreignObj.add(u.toString(), types);
            }
        }
        out.add("paused", pausedArr);
        out.add("forced", forcedArr);
        out.add("frozen", frozenArr);
        out.add("forced_lost", forcedLostArr);
        out.add("forced_foreign", foreignObj);
        JsonObject policy = new JsonObject();
        policy.addProperty("blocks", this.config.protectBlocks);
        policy.addProperty("types", this.config.protectBlockTypes);
        policy.addProperty("be", this.config.protectBlockEntities);
        out.add("rec_policy", policy);
        out.add("block_palette", paletteArr);
        out.add("clone_sets", cloneSetArr);
        out.add("groups", groupArr);
        // 这里的字节预算是内容目标,只算这个方法自己产出的部分 —— 调用方还会往上追加
        // busy/reach。真正不可绕过的上限在 PanelWire.response(),所有响应的必经之路
        return out;
    }

    public void setConfig(PanelConfig config) {
        if (config != null) this.config = config;
    }

    /** 推荐删除判定的一组输入信号(全部按组聚合) */
    record RecInput(long totalBlocks, int maxBlocks, int blockTypes, int blockEntities, int contents,
                    boolean anyNamed, boolean anyTracked, boolean anyUserData,
                    int orphanCount, int nonOrphan, boolean dup, boolean cloneSuspect) {
    }

    /**
     * 推荐删除判定,**以组为单位**:依赖组内任一成员值得保留,整组都不推荐
     * (删掉依赖成员会让剩下的体加载失败,这正是 sable 的已知缺陷)。
     * 仅为建议,永不自动执行;删除仍走回收站备份。
     *
     * <p>只看块数会误伤:实测一架 99 块的玩家飞行器(24 种方块、63 个方块实体、含帆/轴承/
     * 油门杆/座椅)恰好卡在旧的 100 块保护线下被推荐。故改为四类保护信号并行:
     * <ul>
     *   <li>体量:组总块数 ≥ protectBlocks</li>
     *   <li>多样性:方块种类数 ≥ protectBlockTypes —— 残骸几乎都是单一种类,建造物种类多</li>
     *   <li>机械/家具密度:方块实体数 ≥ protectBlockEntities —— 残骸最多带 1 个</li>
     *   <li>内容物:任一方块实体里有物品或告示牌文字 —— 玩家资产铁证,一票保护</li>
     * </ul>
     * 外加原有的:带名称(玩家会给 1~3 块的传送点/门牌命名)、有玩家追踪、带第三方 user_data。
     */
    private JsonObject recommend(RecInput in) {
        PanelConfig cfg = this.config;
        List<String> protectedBy = new ArrayList<>();
        if (in.anyNamed()) protectedBy.add("named");
        if (in.anyTracked()) protectedBy.add("tracked");
        if (in.anyUserData()) protectedBy.add("userdata");
        if (in.contents() > 0) protectedBy.add("contents");
        if (in.totalBlocks() >= cfg.protectBlocks) protectedBy.add("size");
        if (in.blockTypes() >= cfg.protectBlockTypes) protectedBy.add("variety");
        if (in.blockEntities() >= cfg.protectBlockEntities) protectedBy.add("machinery");
        if (!protectedBy.isEmpty()) {
            // 不推荐,但把"为什么保护"回给面板 —— 判定过程对服主可见,才敢用
            JsonObject p = new JsonObject();
            JsonArray pr = new JsonArray();
            for (String s : protectedBy) pr.add(s);
            p.add("protected_by", pr);
            return p;
        }
        List<String> reasons = new ArrayList<>();
        if (in.totalBlocks() == 0) reasons.add("empty");
        else if (in.maxBlocks() < 10) reasons.add("fragment");
        else reasons.add("debris");
        if (in.nonOrphan() == 0 && in.orphanCount() > 0) reasons.add("orphan");
        if (in.dup()) reasons.add("dup");
        if (in.cloneSuspect()) reasons.add("clone");
        JsonObject o = new JsonObject();
        JsonArray r = new JsonArray();
        for (String s : reasons) r.add(s);
        o.add("reasons", r);
        return o;
    }

    private static String cloneKey(DiskScanner.DiskEntry e) {
        String sz = Math.round(e.size()[0]) + "x" + Math.round(e.size()[1]) + "x" + Math.round(e.size()[2]);
        if (e.name() != null) return "n|" + e.name() + "|" + e.blocks() + "|" + sz;
        if (e.blocks() >= 50) return "u|" + e.blocks() + "|" + sz;
        return null;
    }

    /**
     * 面板显示坐标:加载中的体以运行时坐标为准。磁盘条目要等 autosave 才回写,
     * 传送完/物理漂移后列表会一直显示旧位置(虚空/极高空筛选也跟着错)。
     */
    static double[] displayPos(RuntimeBody runtime, double[] diskPos) {
        if (runtime == null) return diskPos;
        return new double[]{runtime.x(), runtime.y(), runtime.z()};
    }

    /**
     * 包围盒连不上主体(块数最大者)的成员 —— 物理轴承断开后留在原地的残骸。
     * <p>
     * sable 的 {@code SubLevelHelper.getLoadingDependencyChain} 是"包围盒相交 ∪ 轴承 actor 引用"
     * 两条并起来的。相交那半在这里复现;拆不开的是 actor 那半 —— 轴承方块记着对方的 UUID,
     * 两边被甩开几百格之后引用还在,于是整组永远同生共死(实测糖音气球 192 体里只有 19 个
     * 真挂在本体上,其余 173 个散在 214~3600 格外,加起来才 2001 块)。
     * <p>
     * 判据只认几何:连不上主体,那个轴承在物理上就已经没有意义了。
     * {@code members} 必须已按块数降序 —— 第一个就是主体。
     *
     * @param positionKnown 这个体的坐标可不可信。不可信的一律并入主体(判为相连)——
     *                      实测糖音气球:6 个推进器有 2~4 份存盘条目,挑中的那份把它们放在
     *                      1300 格外,加载后其实就贴在本体旁边。判据只在坐标可信时才敢说话。
     *                      主体不受此约束:它是整个判定的参照系,退出比较就没有体连得上它了。
     */
    static Set<UUID> detachedMembers(List<UUID> membersByBlocksDesc,
                                     java.util.function.Function<UUID, double[]> boxOf,
                                     java.util.function.Predicate<UUID> positionKnown) {
        int n = membersByBlocksDesc.size();
        if (n < 2 || n > DETACH_MAX_MEMBERS) return Set.of();
        List<double[]> boxes = new ArrayList<>(n);
        UnionFind uf = new UnionFind();
        for (UUID u : membersByBlocksDesc) {
            uf.add(u);
            boxes.add(boxOf.apply(u));
        }
        UUID hub = membersByBlocksDesc.get(0);
        // 参照系自己都量不出来,整组无从判起(否则 NaN 比较处处为 false,全组一起判成残骸)
        if (!finite(boxes.get(0))) return Set.of();
        // 参与几何比较的成员。坐标不可信的体留在外面:让它们参与只会拿假坐标去连别人
        List<Integer> anchored = new ArrayList<>();
        anchored.add(0);
        for (int i = 1; i < n; i++) {
            UUID u = membersByBlocksDesc.get(i);
            // 量不出包围盒 / 多份存盘位置打架:宁可漏判,也不能把它当残骸交给删除
            if (finite(boxes.get(i)) && positionKnown.test(u)) anchored.add(i);
            else uf.union(u, hub);
        }
        for (int a = 0; a < anchored.size(); a++) {
            for (int b = a + 1; b < anchored.size(); b++) {
                int i = anchored.get(a), j = anchored.get(b);
                if (gap(boxes.get(i), boxes.get(j)) <= DETACH_GAP) {
                    uf.union(membersByBlocksDesc.get(i), membersByBlocksDesc.get(j));
                }
            }
        }
        UUID hubRoot = uf.find(hub);
        Set<UUID> detached = new java.util.LinkedHashSet<>();
        for (UUID u : membersByBlocksDesc) {
            if (!uf.find(u).equals(hubRoot)) detached.add(u);
        }
        return detached;
    }

    /** 两个 [minX,minY,minZ,maxX,maxY,maxZ] 之间最大的轴向间隙;<=0 即相交。含 NaN 时返回 NaN(判不连通) */
    private static double gap(double[] a, double[] b) {
        double worst = Double.NEGATIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            worst = Math.max(worst, Math.max(a[axis] - b[axis + 3], b[axis] - a[axis + 3]));
        }
        return worst;
    }

    private static boolean finite(double[] box) {
        for (double v : box) if (!Double.isFinite(v)) return false;
        return true;
    }

    /** 面板坐标语义是包围盒底面中心 → [minX,minY,minZ,maxX,maxY,maxZ] */
    static double[] boxOf(double[] pos, double[] size) {
        return new double[]{pos[0] - size[0] / 2, pos[1], pos[2] - size[2] / 2,
                pos[0] + size[0] / 2, pos[1] + size[1], pos[2] + size[2] / 2};
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(r1(d));
        return a;
    }

    private static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

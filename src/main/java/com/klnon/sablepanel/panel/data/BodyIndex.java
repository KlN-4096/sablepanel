package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.EventLog;
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
import java.util.Set;
import java.util.UUID;

/**
 * 统一体索引:磁盘扫描快照(全量,含未加载/孤儿)+ 运行时加载态 + holding 内存态(均主线程刷新)。
 * 面板一切读取都走这里的内存缓存,HTTP 请求不触碰 sable 与磁盘。
 * 状态定义:loaded(运行时)> stored(盘上有指针)> holding(盘上无指针但 sable 内存持有,落盘延迟)> orphan(全都没有)。
 */
public final class BodyIndex {
    /** 磁盘快照超过这个年龄就不拿来做孤儿/holding 判定(正常扫描周期 120s) */
    private static final long STALE_SNAPSHOT_MS = 300_000L;
    /**
     * {@code /api/bodies} 单次响应最多输出这么多依赖组。
     * <p>
     * ponytail: 固定上限而非游标分页。这个接口喂的是整页的筛选/排序/多选/看板聚合,
     * 全部在前端本地算;真改成分页要把这几样一起搬到服务端,是另一个量级的改动。
     * 真有服务器越过这个数,再上分页。
     */
    private static final int MAX_VIEW_GROUPS = 3000;
    /**
     * 单次响应的估算字节预算。取值远低于 32 MiB 的协议上限:估算只能是上界近似,而且 JsonObject
     * 在堆上比它的文本形态大好几倍,真正要防的是"先把整个对象建出来才发现发不出去"。
     */
    private static final long VIEW_BYTE_BUDGET = 12L << 20;
    /** 一条成员记录除 blk/name 之外的固定开销上界(uuid、条目 id、维度、pos/size、状态字段) */
    private static final int MEMBER_BASE_BYTES = 320;
    /** 一个 blk 索引的上界(五位数 + 逗号) */
    private static final int BLOCK_INDEX_BYTES = 7;
    /** 已加载体附带的 runtime 对象上界 */
    private static final int RUNTIME_BYTES = 256;
    /** 克隆集合是去重提示,不是权威数据,超过这个数就不再往外发 */
    private static final int MAX_CLONE_SETS = 500;
    /** 克隆集合的总成员数上限:集合数封顶了,单个集合能有多少成员并没有封顶 */
    private static final int MAX_CLONE_MEMBERS = 20_000;
    /** 一条组记录除成员之外的固定开销上界(gid、名称、各类计数、rec/prot) */
    private static final int GROUP_BASE_BYTES = 512;
    /** 一条调色板记录的上界(命名空间 id + 中英文名) */
    private static final int PALETTE_ENTRY_BYTES = 160;
    /**
     * 调色板条数上限。它是全局表,一个成员的 blockIds 有多少种就能往里塞多少条,而每条都带
     * 中英文名 —— 组和成员都封顶之后,单个成员仍能靠这张表把响应顶过协议上限。
     * 取值远高于任何整合包的方块注册表规模,正常存档碰不到。
     */
    private static final int MAX_PALETTE = 20_000;
    /** 一个 UUID 文本加引号逗号 */
    private static final int UUID_TEXT_BYTES = 40;

    /** 成员记录的字节上界估算,用于 {@link #VIEW_BYTE_BUDGET} */
    private static long memberBytes(DiskScanner.DiskEntry entry, boolean loaded) {
        long name = entry.name() == null ? 0 : entry.name().length() * 3L;
        return MEMBER_BASE_BYTES + name + (long) entry.blockIds().size() * BLOCK_INDEX_BYTES
                + (loaded ? RUNTIME_BYTES : 0);
    }

    private volatile List<DiskScanner.DiskEntry> diskSnapshot = List.of();
    private volatile long diskScanTime;
    /** 主线程周期刷新:uuid -> 运行时摘要 */
    private volatile Map<UUID, JsonObject> runtime = Map.of();
    /** 主线程周期刷新:盘上无指针、但存在于 sable 内存 holding 表的 uuid */
    private volatile Set<UUID> holding = Set.of();
    /** 孤儿告警去抖:上一轮的孤儿集合 */
    private Set<UUID> prevOrphans = Set.of();
    /** 推荐删除的保护阈值,来自面板配置(服主可调) */
    private volatile PanelConfig config = new PanelConfig();

    /** 替换扫描快照；返回可见内容是否变化，避免无变化扫描触发 SSE 全量重拉。 */
    public boolean updateDisk(List<DiskScanner.DiskEntry> entries) {
        boolean changed = this.diskScanTime == 0 || !sameDiskEntries(this.diskSnapshot, entries);
        this.diskSnapshot = entries;
        this.diskScanTime = System.currentTimeMillis();
        return changed;
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

    /**
     * 快照口径的"已加载"判定,任意线程可读、零磁盘 IO。
     * <p>
     * 用来在建依赖链前先问一句"这体是不是本来就加载着"——是的话整条链都是白建的。
     * 快照最多滞后一轮刷新,判错了后续主线程还会再核一次,代价只是一次重试。
     */
    public boolean isLoaded(UUID uuid) {
        return this.runtime.containsKey(uuid);
    }

    /** 运行时操作完成后立即修正坐标缓存,避免面板等到下一次周期刷新。 */
    public void updateRuntimePosition(UUID uuid, String dim, double[] position) {
        if (position == null || position.length != 3) {
            throw new IllegalArgumentException("position 必须包含 x/y/z");
        }
        Map<UUID, JsonObject> updated = new HashMap<>(this.runtime);
        JsonObject state = updated.containsKey(uuid)
                ? updated.get(uuid).deepCopy()
                : new JsonObject();
        state.addProperty("dim", dim);
        state.addProperty("x", position[0]);
        state.addProperty("y", position[1]);
        state.addProperty("z", position[2]);
        updated.put(uuid, state);
        this.runtime = Map.copyOf(updated);
    }

    /** 主线程调用:刷新运行时加载态 + holding 态 + 逐体耗时 + 孤儿告警 */
    public void refreshRuntime(MinecraftServer server, int ticksSinceLast) {
        // 先守护常驻体(sable 只在世界 initialize 时按票加载一次,掉线后不会自愈),
        // 这样本轮拉回的体能立刻计入下面的运行时视图
        com.klnon.sablepanel.panel.service.ForceLoadService.guardOnMain(server);
        Map<UUID, JsonObject> map = new HashMap<>();
        Map<String, Integer> loadedPerDim = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c == null) continue;
                String dim = level.dimension().location().toString();
                int n = 0;
                for (ServerSubLevel sl : c.getAllSubLevels()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("dim", dim);
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
                    o.addProperty("x", r1(ax));
                    o.addProperty("y", r1(ay));
                    o.addProperty("z", r1(az));
                    o.addProperty("lin_vel", r1(sl.latestLinearVelocity.length()));
                    try {
                        o.addProperty("mass", r1(sl.getMassTracker().getMass()));
                    } catch (Throwable ignored) {
                    }
                    try {
                        o.addProperty("players", sl.getTrackingPlayers().size());
                    } catch (Throwable ignored) {
                    }
                    if (com.klnon.sablepanel.panel.service.PauseService.isPaused(sl.getUniqueId())) {
                        o.addProperty("paused", true);
                    }
                    map.put(sl.getUniqueId(), o);
                    n++;
                }
                if (n > 0) loadedPerDim.put(dim, n);
            } catch (Throwable ignored) {
            }
        }

        // 逐体耗时(mixin 采样):附到 runtime,并产出 Top 列表给 /api/stats
        try {
            Map<UUID, Double> cost = com.klnon.sablepanel.BodyCostTracker.drain(ticksSinceLast, map.keySet());
            List<Map.Entry<UUID, Double>> top = new ArrayList<>(cost.entrySet());
            top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            JsonArray topArr = new JsonArray();
            double totalCost = 0;
            for (Map.Entry<UUID, Double> en : cost.entrySet()) totalCost += en.getValue();
            for (int i = 0; i < top.size() && i < 10; i++) {
                Map.Entry<UUID, Double> en = top.get(i);
                JsonObject rto = map.get(en.getKey());
                if (rto != null) rto.addProperty("cost_ms", r3(en.getValue()));
                JsonObject t = new JsonObject();
                t.addProperty("uuid", en.getKey().toString());
                DiskScanner.DiskEntry de = findEntry(en.getKey());
                if (de != null && de.name() != null) t.addProperty("name", de.name());
                t.addProperty("cost", r3(en.getValue()));
                topArr.add(t);
            }
            for (Map.Entry<UUID, Double> en : cost.entrySet()) {
                JsonObject rto = map.get(en.getKey());
                if (rto != null && !rto.has("cost_ms")) rto.addProperty("cost_ms", r3(en.getValue()));
            }
            StatsCollector.INSTANCE.setBodyCost(topArr, r3(totalCost));
        } catch (Throwable ignored) {
        }

        this.runtime = map;
        StatsCollector.INSTANCE.setLoadedPerDim(loadedPerDim);

        // holding/孤儿判定要求磁盘快照与现实足够接近。快照过旧(面板空闲时扫描暂停,
        // 或扫描故障)就跳过:拿陈旧条目对比现实会把正常体误报成孤儿
        if (System.currentTimeMillis() - this.diskScanTime > STALE_SNAPSHOT_MS) return;

        // holding 态:只查"盘上无任何可达条目"的候选(小集合),避免主线程开销
        List<DiskScanner.DiskEntry> disk = this.diskSnapshot;
        Map<UUID, Boolean> anyReachable = new HashMap<>();
        Map<UUID, String> dimOf = new HashMap<>();
        for (DiskScanner.DiskEntry e : disk) {
            if (e.uuid() == null) continue;
            anyReachable.merge(e.uuid(), e.reachable(), Boolean::logicalOr);
            dimOf.putIfAbsent(e.uuid(), e.key().dim());
        }
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
        for (Map.Entry<UUID, Boolean> en : anyReachable.entrySet()) {
            if (en.getValue() || map.containsKey(en.getKey())) continue;
            ServerSubLevelContainer c = containers.get(dimOf.get(en.getKey()));
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
                    o.addProperty("dim", dimOf.get(u));
                    EventLog.write(o);
                    SablePanel.LOGGER.warn("sablepanel: body {} became orphan (entry on disk, no pointer, not loaded, not holding)", u);
                }
            }
        }
        this.prevOrphans = orphans;
    }

    public DiskScanner.DiskEntry findEntry(UUID uuid) {
        DiskScanner.DiskEntry best = null;
        for (DiskScanner.DiskEntry e : this.diskSnapshot) {
            if (!e.uuid().equals(uuid)) continue;
            if (best == null || (e.reachable() && !best.reachable())
                    || (e.reachable() == best.reachable() && e.blocks() > best.blocks())) {
                best = e;
            }
        }
        return best;
    }

    public DiskScanner.DiskEntry findEntry(String entryId) {
        for (DiskScanner.DiskEntry e : this.diskSnapshot) {
            if (e.key().id().equals(entryId)) return e;
        }
        return null;
    }

    /** 全量视图 JSON:组聚合 + 体明细 + 方块调色板 */
    public JsonObject view() {
        List<DiskScanner.DiskEntry> disk = this.diskSnapshot;
        Map<UUID, JsonObject> rt = this.runtime;
        Set<UUID> held = this.holding;

        // uuid -> 最优条目(可达优先,blocks 大优先);同 uuid 冗余条目单独计数
        Map<UUID, DiskScanner.DiskEntry> byUuid = new HashMap<>();
        Map<UUID, Integer> copies = new HashMap<>();
        Map<UUID, List<String>> entryIds = new HashMap<>();
        for (DiskScanner.DiskEntry e : disk) {
            if (e.uuid() == null) continue;
            copies.merge(e.uuid(), 1, Integer::sum);
            entryIds.computeIfAbsent(e.uuid(), k -> new ArrayList<>()).add(e.key().id() + (e.reachable() ? "" : " (无指针)"));
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
        long cloneBytes = 0;
        int cloneMembers = 0;
        for (int setId = 0; setId < Math.min(orderedCloneSets.size(), MAX_CLONE_SETS); setId++) {
            List<UUID> matches = new ArrayList<>(orderedCloneSets.get(setId).getValue());
            // 只封顶集合数不够:几万个同尺寸残骸会挤进同一个集合,一个集合就能撑爆响应
            if (cloneMembers + matches.size() > MAX_CLONE_MEMBERS) break;
            cloneMembers += matches.size();
            cloneBytes += GROUP_BASE_BYTES + (long) matches.size() * UUID_TEXT_BYTES;
            matches.sort(UUID::compareTo);
            DiskScanner.DiskEntry sample = byUuid.get(matches.get(0));
            JsonObject set = new JsonObject();
            set.addProperty("id", setId);
            set.addProperty("mode", sample.name() != null ? "named" : "unnamed");
            if (sample.name() != null) set.addProperty("name", sample.name());
            set.addProperty("blocks", sample.blocks());
            JsonArray roundedSize = new JsonArray();
            for (double axis : sample.size()) roundedSize.add(Math.round(axis));
            set.add("rounded_size", roundedSize);
            JsonArray setMembers = new JsonArray();
            for (UUID match : matches) {
                cloneSetByUuid.put(match, setId);
                setMembers.add(match.toString());
            }
            set.add("members", setMembers);
            cloneSetArr.add(set);
        }

        // 方块调色板(全局去重,body 引用索引)。只收真正输出出去的成员用到的方块 ——
        // 从前是先按全部磁盘条目建一张完整表,截断之后表里全是没人引用的条目,白占字节
        Map<String, Integer> paletteIdx = new LinkedHashMap<>();

        // 并查集分组(按 deps,双向)
        Map<UUID, UUID> parent = new HashMap<>();
        for (UUID u : byUuid.keySet()) parent.put(u, u);
        for (DiskScanner.DiskEntry e : byUuid.values()) {
            for (UUID d : e.deps()) {
                if (parent.containsKey(d)) union(parent, e.uuid(), d);
            }
        }
        Map<UUID, List<UUID>> groups = new HashMap<>();
        for (UUID u : byUuid.keySet()) {
            groups.computeIfAbsent(find(parent, u), k -> new ArrayList<>()).add(u);
        }

        // 运行时存在但磁盘还没有条目的体(刚生成/未保存):单独成组显示,可传送不可预览
        JsonArray freshArr = new JsonArray();
        long freshBytes = 0;
        int freshTotal = 0;
        for (Map.Entry<UUID, JsonObject> en : rt.entrySet()) {
            if (byUuid.containsKey(en.getKey())) continue;
            freshTotal++;
            if (freshArr.size() >= MAX_VIEW_GROUPS || freshBytes >= VIEW_BYTE_BUDGET) continue;
            freshBytes += MEMBER_BASE_BYTES + RUNTIME_BYTES;
            JsonObject rto = en.getValue();
            JsonObject m = new JsonObject();
            m.addProperty("uuid", en.getKey().toString());
            m.addProperty("entry", "");
            m.addProperty("dim", rto.get("dim").getAsString());
            m.addProperty("blocks", 0);
            JsonArray pos = new JsonArray();
            pos.add(rto.get("x").getAsDouble());
            pos.add(rto.get("y").getAsDouble());
            pos.add(rto.get("z").getAsDouble());
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
            go.addProperty("dims", rto.get("dim").getAsString());
            go.addProperty("loaded", 1);
            go.addProperty("orphans", 0);
            go.addProperty("holding", 0);
            go.addProperty("types", 0);
            go.addProperty("be", 0);
            go.addProperty("contents", 0);
            JsonArray ma = new JsonArray();
            ma.add(m);
            go.add("bodies", ma);
            freshArr.add(go);
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

        // 暂停集合(含未加载体的暂停意图):前端以此为单一事实源渲染 ⏸ 标记
        JsonArray pausedArr = new JsonArray();
        for (UUID u : com.klnon.sablepanel.panel.service.PauseService.snapshot()) pausedArr.add(u.toString());
        // 常驻加载集合(sable force-load ticket,含未加载体的意图):前端据此变色/置顶/渲染 📌
        JsonArray forcedArr = new JsonArray();
        for (UUID u : com.klnon.sablepanel.panel.service.ForceLoadService.snapshot()) forcedArr.add(u.toString());

        JsonArray groupArr = new JsonArray();
        groupArr.addAll(freshArr);
        // 预算是一份运行总账:整份发出去的 paused/forced/clone_sets 先记进去,组列表用剩下的额度,
        // 调色板则边建边记。只算组成员的话,这几项加起来照样能把响应顶过协议上限。
        // ponytail: paused/forced 不截断 —— 它们是徽章的唯一事实源。二者只随用户手动暂停/常驻的
        // 体增长(单次操作上限 500 个),真涨到几十万条再给它们单独开接口。
        long budget = freshBytes + cloneBytes
                + (long) (pausedArr.size() + forcedArr.size()) * UUID_TEXT_BYTES;
        // 组数上限管的是"发出去多少组",fresh 组也占名额,分开计数就会一起超过 MAX_VIEW_GROUPS
        int shownGroups = freshArr.size();
        int omittedMembers = 0;
        boolean paletteFull = false;
        for (Map.Entry<UUID, List<UUID>> g : ordered) {
            // 至少出一组:否则单个超预算的巨型组会让整个列表空着
            if (shownGroups > 0 && (shownGroups >= MAX_VIEW_GROUPS || budget >= VIEW_BYTE_BUDGET)) break;
            shownGroups++;
            budget += GROUP_BASE_BYTES;
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
            for (UUID u : members) {
                DiskScanner.DiskEntry e = byUuid.get(u);
                JsonObject rto = rt.get(u);
                boolean loaded = rto != null;
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
                if (rto != null && rto.has("players") && rto.get("players").getAsInt() > 0) anyTracked = true;
                if (e.name() != null && e.blocks() > bestNameBlocks) {
                    bestName = e.name();
                    bestNameBlocks = e.blocks();
                }
                // 预算要按成员查,不能只在组的入口查:一条几万成员的依赖链就是一个单组的
                // 超大响应,入口那次检查放它进来之后再无第二道闸。上面的聚合计数仍按全部成员算,
                // 所以 members/blocks 是真值,只有 bodies 明细会少。
                if (!memberArr.isEmpty() && budget >= VIEW_BYTE_BUDGET) {
                    omittedMembers++;
                    continue;
                }
                budget += memberBytes(e, loaded);
                JsonObject m = new JsonObject();
                m.addProperty("uuid", u.toString());
                m.addProperty("entry", e.key().id());
                if (e.name() != null) m.addProperty("name", e.name());
                m.addProperty("dim", e.key().dim());
                m.addProperty("blocks", e.blocks());
                m.add("pos", arr(displayPos(rto, e.pos())));
                m.add("size", arr(e.size()));
                m.addProperty("state", state);
                if (e.blockEntities() > 0) m.addProperty("be", e.blockEntities());
                if (e.contents() > 0) m.addProperty("contents", e.contents());
                int cp = copies.getOrDefault(u, 1);
                if (cp > 1) {
                    m.addProperty("copies", cp);
                    groupDup = true;
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
                        // 表满就不再收:多出来的方块索引直接丢掉(前端当未知方块),
                        // 这样 blk 每条也顺带被封在 MAX_PALETTE 之内
                        if (paletteIdx.size() >= MAX_PALETTE) {
                            paletteFull = true;
                            continue;
                        }
                        at = paletteIdx.size();
                        paletteIdx.put(id, at);
                        budget += PALETTE_ENTRY_BYTES;
                    }
                    blk.add(at);
                }
                m.add("blk", blk);
                if (loaded) m.add("runtime", rto);
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
            JsonObject verdict = recommend(new RecInput(totalBlocks, maxBlocks, groupBlockIds.size(), groupBe,
                    groupContents, anyNamed, anyTracked, anyUserData, orphanCount, nonOrphan, groupDup, groupClone));
            if (verdict.has("reasons")) go.add("rec", verdict);
            else go.add("prot", verdict.getAsJsonArray("protected_by"));
            go.add("bodies", memberArr);
            groupArr.add(go);
        }

        JsonArray paletteArr = new JsonArray();
        for (String id : paletteIdx.keySet()) {
            String[] names = BlockNames.of(id);
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("en", names[0]);
            p.addProperty("zh", names[1]);
            paletteArr.add(p);
        }

        JsonObject out = new JsonObject();
        out.addProperty("scan_time", this.diskScanTime);
        // freshArr 是被截断过的显示量,总数必须用真值,否则截断时连"少了多少"都看不出来
        out.addProperty("total_bodies", byUuid.size() + freshTotal);
        out.addProperty("total_entries", disk.size());
        int totalGroups = groups.size() + freshTotal;
        out.addProperty("total_groups", totalGroups);
        out.addProperty("shown_groups", groupArr.size());
        out.addProperty("group_limit", MAX_VIEW_GROUPS);
        // 组数上限、字节预算、组内成员截断、调色板封顶,任一条生效都算截断:
        // 前端据此提示,别让用户以为体没了
        if (groupArr.size() < totalGroups || omittedMembers > 0 || paletteFull) {
            out.addProperty("truncated", true);
        }
        out.add("paused", pausedArr);
        out.add("forced", forcedArr);
        JsonObject policy = new JsonObject();
        policy.addProperty("blocks", this.config.protectBlocks);
        policy.addProperty("types", this.config.protectBlockTypes);
        policy.addProperty("be", this.config.protectBlockEntities);
        out.add("rec_policy", policy);
        out.add("block_palette", paletteArr);
        out.add("clone_sets", cloneSetArr);
        out.add("groups", groupArr);
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

    private static UUID find(Map<UUID, UUID> parent, UUID u) {
        UUID root = u;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        while (!parent.get(u).equals(root)) {
            UUID next = parent.get(u);
            parent.put(u, root);
            u = next;
        }
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        parent.put(find(parent, a), find(parent, b));
    }

    /**
     * 面板显示坐标:加载中的体以运行时坐标为准。磁盘条目要等 autosave 才回写,
     * 传送完/物理漂移后列表会一直显示旧位置(虚空/极高空筛选也跟着错)。
     */
    static double[] displayPos(JsonObject runtime, double[] diskPos) {
        if (runtime == null || !runtime.has("x")) return diskPos;
        return new double[]{runtime.get("x").getAsDouble(),
                runtime.get("y").getAsDouble(), runtime.get("z").getAsDouble()};
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

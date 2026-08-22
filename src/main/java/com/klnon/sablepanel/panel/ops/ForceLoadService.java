package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面板常驻加载 = sable 原生 force-load ticket,与 {@code /sable forceload} 同机制但独立票种,
 * 便于面板单独列出与清理。常驻票只负责保持加载，不改变速度、物理约束或方块实体 tick。
 * <p>
 * 效果来自 {@code PhysicsChunkTicketManager}:持票体走 {@code inhabitChunk} 分支,逐 tick 用
 * <b>当前</b>包围盒给覆盖区块挂 ENTITY_TICKING ticket(旧区块 20 tick 后过期),因此票跟着体移动;
 * 且该分支不做 {@code isChunkLoadedEnough} 判定,体不会再因"周边区块没加载"被 {@code moveToUnloaded}。
 * sable 的 {@code collectForceLoadedSubLevels()} 会把持票体的<b>运行时依赖闭包</b>(包围盒相交 +
 * 方块实体 actor 依赖)一并算作 force-loaded,所以相交/绳连的体自动受保护,无需逐个挂票。
 * <p>
 * 票由 sable 持久化到 {@code <world>/data/sable_sub_level_force_load_tickets.dat};删除体前必须由
 * 面板显式摘票，Sable 的 {@code removeSubLevel(REMOVED)} 不会清理 {@code allTickets}。
 * Sable 只在世界 {@code initialize()} 时按票加载一次
 * ({@code loadForceLoadedSubLevels}),体一旦被 UNLOADED 就不会自愈 —— 故本类带
 * {@link #guardOnMain} 守护:每次运行时刷新把掉线的常驻体按票中指针重新 snatch 回来。
 * <p>
 * 线程约定:sable 的 {@code allTickets} 是非并发结构,只在主线程访问;{@link #REQUESTED}
 * 持久化跨重启意图,{@link #ACTIVE} 供 HTTP 线程展示当前实际状态。
 */
public final class ForceLoadService {
    /**
     * 面板专属票种。<b>必须在世界读档前完成注册</b>(mod 构造期 {@link #init()} 触发类加载):
     * {@code SubLevelTicketsSavedData} 反序列化时用 {@code byName} 查类型,查不到只打一行 ERROR
     * 并丢弃该票 —— 注册晚了会导致重启后常驻意图静默消失。
     */
    public static final SubLevelLoadingTicketType<Unit> PANEL_FORCED = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(SablePanel.MOD_ID, "panel_forced"), Unit.CODEC);

    /** 常驻意图，独立于 Sable 当前票持久化。 */
    private static final Set<UUID> REQUESTED = ConcurrentHashMap.newKeySet();
    /** 当前实际持票集合，供 HTTP 线程展示。 */
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    /**
     * "外部保持加载"镜像:非面板 sable 票(指令/其他模组),或体被外部区块加载源罩住
     * (原版 forceload / 其他 mod 的区块票如 create_power_loader / 出生点区块)。
     * 用户只关心内外之分,不细分来源。{@link #guardOnMain} 每轮整体重建后原子替换,
     * 供 HTTP 线程展示"外部加载"徽章 —— 不标出来的话,"取消常驻了为什么还在跑"无迹可循。
     */
    private static volatile Set<UUID> EXTERNAL = Set.of();
    private static final int STOP_DETACH_ATTEMPTS = 3;
    private static final IntentFile FILE = new IntentFile("forced.json");

    private ForceLoadService() {
    }

    /** mod 构造期调用:触发类加载,完成票种注册 */
    public static void init() {
        SablePanel.LOGGER.debug("sablepanel: force-load ticket type registered as {}", PANEL_FORCED.name());
    }

    /** 当前常驻集合快照(HTTP 线程 /api/bodies 输出用) */
    public static Set<UUID> snapshot() {
        return Set.copyOf(ACTIVE);
    }

    public static Set<UUID> requestedSnapshot() {
        return Set.copyOf(REQUESTED);
    }

    /** 外部保持加载镜像(HTTP 线程 /api/bodies 输出用);新鲜度与运行态刷新同节奏 */
    public static Set<UUID> externalSnapshot() {
        return EXTERNAL;
    }

    /**
     * 把一批常驻意图重新登记回意图表(不挂票、不加载)。副本处理的临时清场会连意图一起清,
     * 恢复所选版本后必须把用户原有的意图还回来,加载则交给周期恢复按既定机制执行。
     * 必须在共享操作锁内调用(与周期恢复/取消常驻串行)。
     */
    static void reinstateIntents(Collection<UUID> uuids) {
        if (REQUESTED.addAll(uuids)) persist();
    }

    public static void load() {
        REQUESTED.addAll(FILE.loadUuids("forced bodies"));
    }

    public static void persist() {
        FILE.saveUuids(REQUESTED);
    }

    public static void reset() {
        REQUESTED.clear();
        ACTIVE.clear();
        EXTERNAL = Set.of();
    }

    public static void captureNativeIntentsOnMain(MinecraftServer server) {
        if (REQUESTED.addAll(forcedOnMain(server))) persist();
    }

    public static boolean isLoadedOnMain(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c != null && OpKit.loadedBody(c, uuid) != null) return true;
        }
        return false;
    }

    /** 主线程:直接读取 Sable 票表，删除/恢复事务不能依赖异步维护的镜像。 */
    public static boolean isForcedOnMain(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            SubLevelTicketInfo info = c.getAllTickets().get(uuid);
            if (info != null && info.tickets().stream().anyMatch(ForceLoadService::isPanelTicket)) return true;
        }
        return false;
    }

    /** 主线程:体加载回调只查它所属维度，世界尚未完全挂入 server 时也能识别当前票。 */
    static boolean isForcedOnMain(ServerSubLevel body) {
        ServerSubLevelContainer c = container(body.getLevel());
        if (c == null) return false;
        SubLevelTicketInfo info = c.getAllTickets().get(body.getUniqueId());
        return info != null && info.tickets().stream().anyMatch(ForceLoadService::isPanelTicket);
    }

    static boolean isForcedOnMain(MinecraftServer server, String dimension, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!dimension.equals(level.dimension().location().toString())) continue;
            ServerSubLevelContainer c = container(level);
            SubLevelTicketInfo info = c == null ? null : c.getAllTickets().get(uuid);
            return info != null && info.tickets().stream().anyMatch(ForceLoadService::isPanelTicket);
        }
        return false;
    }

    public static Set<UUID> forcedOnMain(MinecraftServer server) {
        Set<UUID> forced = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            for (Map.Entry<UUID, SubLevelTicketInfo> entry : c.getAllTickets().entrySet()) {
                if (entry.getValue().tickets().stream().anyMatch(ForceLoadService::isPanelTicket)) {
                    forced.add(entry.getKey());
                }
            }
        }
        return Set.copyOf(forced);
    }

    /** 主线程:取消面板常驻前确认没有其他票种仍拥有该体。 */
    static boolean hasOtherTicketOnMain(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            SubLevelTicketInfo info = c.getAllTickets().get(uuid);
            if (info != null && info.tickets().stream().anyMatch(ticket -> !isPanelTicket(ticket))) return true;
        }
        return false;
    }

    /** 主线程:记录面板票实际落在哪些维度，供取消常驻失败时精确恢复。 */
    static Map<UUID, Set<String>> panelTicketDimensionsOnMain(MinecraftServer server,
                                                              Collection<UUID> uuids) {
        Map<UUID, Set<String>> found = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            String dimension = level.dimension().location().toString();
            for (UUID uuid : uuids) {
                SubLevelTicketInfo info = c.getAllTickets().get(uuid);
                if (info != null && info.tickets().stream().anyMatch(ForceLoadService::isPanelTicket)) {
                    found.computeIfAbsent(uuid, ignored -> new LinkedHashSet<>()).add(dimension);
                }
            }
        }
        Map<UUID, Set<String>> snapshot = new LinkedHashMap<>();
        found.forEach((uuid, dimensions) -> snapshot.put(uuid, Set.copyOf(dimensions)));
        return Map.copyOf(snapshot);
    }

    /** 主线程:按取消前快照恢复面板票；卸载路径会保留对应的 ticket info 和指针。 */
    static Set<String> restorePanelTicketsOnMain(MinecraftServer server,
                                                 Map<UUID, Set<String>> snapshot) {
        Set<String> missing = new LinkedHashSet<>();
        for (var entry : snapshot.entrySet()) {
            UUID uuid = entry.getKey();
            for (String dimension : entry.getValue()) {
                ServerLevel level = levelOf(server, dimension);
                ServerSubLevelContainer c = level == null ? null : container(level);
                SubLevelTicketInfo info = c == null ? null : c.getAllTickets().get(uuid);
                if (info == null) {
                    missing.add(uuid + " @ " + dimension);
                }
            }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("无法恢复原常驻票: " + missing);

        Set<String> changed = new LinkedHashSet<>();
        for (var entry : snapshot.entrySet()) {
            UUID uuid = entry.getKey();
            for (String dimension : entry.getValue()) {
                ServerLevel level = levelOf(server, dimension);
                ServerSubLevelContainer c = container(level);
                SubLevelTicketInfo info = c.getAllTickets().get(uuid);
                info.tickets().add(new SubLevelLoadingTicket<>(PANEL_FORCED, uuid, Unit.INSTANCE));
                SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                changed.add(dimension);
                REQUESTED.add(uuid);
                ACTIVE.add(uuid);
            }
        }
        return Set.copyOf(changed);
    }

    /** 主线程:给已加载的体挂票 */
    public static void addOnMain(ServerSubLevel sl) {
        ServerSubLevelContainer c = container(sl.getLevel());
        if (c == null) throw new IllegalStateException("常驻票所属容器不存在: " + sl.getUniqueId());
        c.addForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
        if (!isForcedOnMain(sl)) throw new IllegalStateException("常驻票写入后未生效: " + sl.getUniqueId());
        REQUESTED.add(sl.getUniqueId());
        ACTIVE.add(sl.getUniqueId());
    }

    /**
     * 主线程:摘票。已加载体走原生 API;未加载体没有 ServerSubLevel 实例可传,直接从
     * {@code allTickets} 的 info 里摘 —— {@code getAllTickets()} 是不可变<i>视图</i>,
     * 但 {@code info.tickets()} 返回内部集合引用,可安全移除。留下的空 info 无害:
     * 加载时 {@code if (!tickets.isEmpty())} 才登记,存档读回时空条目直接被跳过。
     */
    public static Set<String> removeOnMain(MinecraftServer server, UUID uuid) {
        Set<String> changedDimensions = new HashSet<>();
        List<Throwable> failures = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = container(level);
                if (c == null) continue;
                SubLevelTicketInfo info = c.getAllTickets().get(uuid);
                boolean hadPanelTicket = info != null && info.tickets().stream()
                        .anyMatch(ForceLoadService::isPanelTicket);
                ServerSubLevel sl = OpKit.loadedBody(c, uuid);
                if (sl != null) {
                    c.removeForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
                    if (hadPanelTicket) changedDimensions.add(level.dimension().location().toString());
                    continue;
                }
                if (info != null && info.tickets().removeIf(ForceLoadService::isPanelTicket)) {
                    SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                    changedDimensions.add(level.dimension().location().toString());
                }
            } catch (Throwable t) {
                failures.add(t);
                SablePanel.LOGGER.warn("sablepanel: clearing force-load ticket {} failed", uuid, t);
            }
        }
        boolean residual = isForcedOnMain(server, uuid);
        if (!failures.isEmpty() || residual) {
            IllegalStateException failure = new IllegalStateException("常驻票摘除不完整: " + uuid);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        REQUESTED.remove(uuid);
        ACTIVE.remove(uuid);
        return Set.copyOf(changedDimensions);
    }

    /** 主线程:严格摘除目标 UUID 在所有维度中的面板票；任一残留都会让取消常驻失败。 */
    public static Set<String> clearStrictOnMain(MinecraftServer server, UUID uuid) {
        Set<String> changedDimensions = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            SubLevelTicketInfo info = c.getAllTickets().get(uuid);
            if (info == null || info.tickets().stream().noneMatch(ForceLoadService::isPanelTicket)) continue;
            ServerSubLevel sl = OpKit.loadedBody(c, uuid);
            boolean removed;
            if (sl != null) {
                removed = c.removeForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
            } else {
                removed = info.tickets().removeIf(ForceLoadService::isPanelTicket);
                if (removed) SubLevelTicketsSavedData.getOrLoad(level).setDirty();
            }
            if (!removed) throw new IllegalStateException("常驻票摘除失败: " + uuid);
            SubLevelTicketInfo remaining = c.getAllTickets().get(uuid);
            if (remaining != null && remaining.tickets().stream().anyMatch(ForceLoadService::isPanelTicket)) {
                throw new IllegalStateException("常驻票删除后仍残留: " + uuid + " @ "
                        + level.dimension().location());
            }
            changedDimensions.add(level.dimension().location().toString());
        }
        if (isForcedOnMain(server, uuid)) throw new IllegalStateException("常驻票删除后仍残留: " + uuid);
        REQUESTED.remove(uuid);
        ACTIVE.remove(uuid);
        return Set.copyOf(changedDimensions);
    }

    /** 主线程:仅撤回目标体所属容器的新票，不能误删其他维度的异常同 UUID 票。 */
    public static void removeStrictOnMain(MinecraftServer server, ServerSubLevel sl) {
        removeStrictOnMain(server, sl.getLevel().dimension().location().toString(), sl.getUniqueId());
    }

    /** 主线程:按本轮记下的精确维度撤票，体在补偿前已经卸载也能完成回滚。 */
    static void removeStrictOnMain(MinecraftServer server, String dimension, UUID uuid) {
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (dimension.equals(candidate.dimension().location().toString())) {
                level = candidate;
                break;
            }
        }
        ServerSubLevelContainer c = level == null ? null : container(level);
        if (c == null) throw new IllegalStateException("常驻票所属容器不存在: " + uuid + " @ " + dimension);
        SubLevelTicketInfo info = c.getAllTickets().get(uuid);
        boolean hadPanelTicket = info != null && info.tickets().stream().anyMatch(ForceLoadService::isPanelTicket);
        if (!hadPanelTicket) return;
        ServerSubLevel sl = OpKit.loadedBody(c, uuid);
        if (sl != null) c.removeForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
        else {
            info.tickets().removeIf(ForceLoadService::isPanelTicket);
            SubLevelTicketsSavedData.getOrLoad(level).setDirty();
        }
        SubLevelTicketInfo remaining = c.getAllTickets().get(uuid);
        if (remaining != null && remaining.tickets().stream().anyMatch(ForceLoadService::isPanelTicket)) {
            throw new IllegalStateException("常驻票删除后仍残留: " + uuid + " @ " + dimension);
        }
        if (!isForcedOnMain(server, uuid)) {
            REQUESTED.remove(uuid);
            ACTIVE.remove(uuid);
        }
    }

    /** 停服保存前剥离面板原生票，避免 Sable 把即将搬迁的旧序列化指针写进下次启动。 */
    public static void prepareForStopOnMain(MinecraftServer server) {
        Set<UUID> actual = forcedOnMain(server);
        REQUESTED.addAll(actual);
        persist();
        retryStopDetach(STOP_DETACH_ATTEMPTS, () -> detachNativeTicketsOnMain(server, actual));
    }

    static void retryStopDetach(int attempts, Runnable detach) {
        if (attempts < 1) throw new IllegalArgumentException("attempts 必须大于 0");
        RuntimeException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                detach.run();
                return;
            } catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw new IllegalStateException("停服前无法剥离面板原生常驻票", last);
    }

    public static void detachNativeTicketsOnMain(MinecraftServer server, Collection<UUID> targets) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer c = container(level);
            if (c == null) continue;
            boolean changed = false;
            for (UUID uuid : targets) {
                SubLevelTicketInfo info = c.getAllTickets().get(uuid);
                if (info == null || info.tickets().stream().noneMatch(ForceLoadService::isPanelTicket)) continue;
                ServerSubLevel body = OpKit.loadedBody(c, uuid);
                if (body != null) c.removeForceLoadTicket(body, PANEL_FORCED, Unit.INSTANCE);
                if (info.tickets().removeIf(ForceLoadService::isPanelTicket)) changed = true;
                changed = true;
            }
            if (changed) SubLevelTicketsSavedData.getOrLoad(level).setDirty();
        }
        Set<UUID> residual = targets.stream().filter(uuid -> isForcedOnMain(server, uuid))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!residual.isEmpty()) throw new IllegalStateException("原生常驻票剥离不完整: " + residual);
        ACTIVE.removeAll(targets);
    }

    /**
     * 主线程(每次运行时刷新):把镜像与 sable 的票对齐(含重启后从存档恢复的票),
     * 并把掉线的常驻体按票中指针重新拉起。
     */
    public static void guardOnMain(MinecraftServer server) {
        Set<UUID> forced = new HashSet<>();
        Set<UUID> recover = new LinkedHashSet<>();
        Set<UUID> external = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = container(level);
                if (c == null) continue;
                // 先收集再操作:snatchAndLoad 期间可能有体被 REMOVED 而写 allTickets,
                // 直接在 getAllTickets() 视图上边遍历边加载会 ConcurrentModificationException
                List<Reload> pending = new ArrayList<>();
                for (Map.Entry<UUID, SubLevelTicketInfo> en : c.getAllTickets().entrySet()) {
                    SubLevelTicketInfo info = en.getValue();
                    // 常态守护路径,每票一个 Stream 分配不值得。非面板票=外部来源之一
                    boolean panelTicket = false;
                    for (SubLevelLoadingTicket<?> ticket : info.tickets()) {
                        if (isPanelTicket(ticket)) panelTicket = true;
                        else external.add(en.getKey());
                    }
                    if (!panelTicket) continue;
                    UUID uuid = en.getKey();
                    forced.add(uuid);
                    if (OpKit.loadedBody(c, uuid) != null) {
                        continue;
                    }
                    var holding = c.getHoldingChunkMap().getHoldingSubLevel(uuid);
                    GlobalSavedSubLevelPointer current = holding == null ? null : holding.pointer();
                    if (!OpKit.samePointer(info.getPointer(), current)) {
                        recover.add(uuid);
                        continue;
                    }
                    pending.add(new Reload(uuid, info.getPointer()));
                }
                for (Reload r : pending) {
                    try {
                        c.getHoldingChunkMap().snatchAndLoad(r.pointer(), r.uuid());
                    } catch (Throwable t) {
                        SablePanel.LOGGER.warn("sablepanel: force-load guard snatch {} failed", r.uuid(), t);
                    }
                    if (OpKit.loadedBody(c, r.uuid()) == null) {
                        recover.add(r.uuid());
                    }
                }
                collectChunkPinnedOnMain(server, level, c, external);
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: force-load guard failed", t);
            }
        }
        if (!recover.isEmpty()) {
            detachNativeTicketsOnMain(server, recover);
            forced.removeAll(recover);
        }
        ACTIVE.retainAll(forced);
        ACTIVE.addAll(forced);
        EXTERNAL = Set.copyOf(external);
    }

    /**
     * 被外部区块加载源罩住的已加载体也算"外部":体自身没有任何 sable 票,但所在区块被
     * 原版 forceload、其他 mod 的区块票(如 create_power_loader)或出生点区块钉着,
     * sable 对"区块已加载"的体天然保持运行 —— 取消常驻后 1~9 毫秒就会被重新拉起
     * (2026-08-22 实测)。钉住的区块按 ±2 格膨胀比对,近似加载度的边界扩散。
     */
    private static void collectChunkPinnedOnMain(MinecraftServer server, ServerLevel level,
                                                 ServerSubLevelContainer c, Set<UUID> external) {
        LongSet pinned = pinnedChunksOnMain(server, level);
        if (pinned.isEmpty()) return;
        for (ServerSubLevel body : c.getAllSubLevels()) {
            if (external.contains(body.getUniqueId())) continue;
            var bounds = body.boundingBox();
            if (!Double.isFinite(bounds.minX()) || !Double.isFinite(bounds.maxX())
                    || !Double.isFinite(bounds.minZ()) || !Double.isFinite(bounds.maxZ())) continue;
            int minX = ((int) Math.floor(bounds.minX() - 1.0)) >> 4;
            int maxX = ((int) Math.floor(bounds.maxX() + 1.0)) >> 4;
            int minZ = ((int) Math.floor(bounds.minZ() - 1.0)) >> 4;
            int maxZ = ((int) Math.floor(bounds.maxZ() + 1.0)) >> 4;
            // 失控包围盒防线(b5 病例那种上万格的盒):范围离谱就不逐区块扫了
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 4096) continue;
            search:
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (nearPinned(pinned, cx, cz)) {
                        external.add(body.getUniqueId());
                        break search;
                    }
                }
            }
        }
    }

    private static boolean nearPinned(LongSet pinned, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (pinned.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    /** 本维度被钉住的区块:原版 forceload + NeoForge 方块/实体区块票 + 出生点区块(仅主世界) */
    private static LongSet pinnedChunksOnMain(MinecraftServer server, ServerLevel level) {
        LongSet pinned = new LongOpenHashSet(level.getForcedChunks());
        ForcedChunksSavedData data = level.getDataStorage()
                .get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        if (data != null) {
            for (LongSet set : data.getBlockForcedChunks().getChunks().values()) pinned.addAll(set);
            for (LongSet set : data.getBlockForcedChunks().getTickingChunks().values()) pinned.addAll(set);
            for (LongSet set : data.getEntityForcedChunks().getChunks().values()) pinned.addAll(set);
            for (LongSet set : data.getEntityForcedChunks().getTickingChunks().values()) pinned.addAll(set);
        }
        if (level == server.overworld()) {
            int radius = level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
            if (radius > 0) {
                ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        pinned.add(ChunkPos.asLong(spawn.x + dx, spawn.z + dz));
                    }
                }
            }
        }
        return pinned;
    }

    private static boolean isPanelTicket(SubLevelLoadingTicket<?> ticket) {
        // sable 2.0.4 起 ticket 是 record,访问器从 getType() 变为 type()
        return PANEL_FORCED.equals(ticket.type());
    }

    private static ServerSubLevelContainer container(ServerLevel level) {
        try {
            return SubLevelContainer.getContainer(level);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension.equals(level.dimension().location().toString())) return level;
        }
        return null;
    }

    private record Reload(UUID uuid, GlobalSavedSubLevelPointer pointer) {
    }
}

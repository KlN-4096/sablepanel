package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面板常驻加载 = sable 原生 force-load ticket,与 {@code /sable forceload} 同机制但独立票种,
 * 便于面板单独列出与清理。
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
 * 线程约定:sable 的 {@code allTickets} 是非并发结构,只在主线程访问;{@link #MIRROR} 是给
 * HTTP 线程(/api/bodies 输出)读的镜像,由主线程维护。
 */
public final class ForceLoadService {
    /**
     * 面板专属票种。<b>必须在世界读档前完成注册</b>(mod 构造期 {@link #init()} 触发类加载):
     * {@code SubLevelTicketsSavedData} 反序列化时用 {@code byName} 查类型,查不到只打一行 ERROR
     * 并丢弃该票 —— 注册晚了会导致重启后常驻意图静默消失。
     */
    public static final SubLevelLoadingTicketType<Unit> PANEL_FORCED = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(SablePanel.MOD_ID, "panel_forced"), Unit.CODEC);

    /** 常驻意图镜像(主线程写,HTTP 线程读) */
    private static final Set<UUID> MIRROR = ConcurrentHashMap.newKeySet();

    /** 守护重载连续失败计数(仅主线程);达到 MAX_RETRY 后停手,重新挂票清零 */
    private static final Map<UUID, Integer> FAILED = new HashMap<>();
    private static final int MAX_RETRY = 3;

    private ForceLoadService() {
    }

    /** mod 构造期调用:触发类加载,完成票种注册 */
    public static void init() {
        SablePanel.LOGGER.debug("sablepanel: force-load ticket type registered as {}", PANEL_FORCED.name());
    }

    /** 当前常驻集合快照(HTTP 线程 /api/bodies 输出用) */
    public static Set<UUID> snapshot() {
        return Set.copyOf(MIRROR);
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

    /** 主线程:给已加载的体挂票 */
    public static void addOnMain(ServerSubLevel sl) {
        ServerSubLevelContainer c = container(sl.getLevel());
        if (c == null) return;
        c.addForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
        MIRROR.add(sl.getUniqueId());
        FAILED.remove(sl.getUniqueId());
    }

    /**
     * 主线程:摘票。已加载体走原生 API;未加载体没有 ServerSubLevel 实例可传,直接从
     * {@code allTickets} 的 info 里摘 —— {@code getAllTickets()} 是不可变<i>视图</i>,
     * 但 {@code info.tickets()} 返回内部集合引用,可安全移除。留下的空 info 无害:
     * 加载时 {@code if (!tickets.isEmpty())} 才登记,存档读回时空条目直接被跳过。
     */
    public static void removeOnMain(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = container(level);
                if (c == null) continue;
                ServerSubLevel sl = loaded(c, uuid);
                if (sl != null) {
                    c.removeForceLoadTicket(sl, PANEL_FORCED, Unit.INSTANCE);
                    continue;
                }
                SubLevelTicketInfo info = c.getAllTickets().get(uuid);
                if (info != null && info.tickets().removeIf(ForceLoadService::isPanelTicket)) {
                    SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: clearing force-load ticket {} failed", uuid, t);
            }
        }
        MIRROR.remove(uuid);
        FAILED.remove(uuid);
    }

    /**
     * 主线程(每次运行时刷新):把镜像与 sable 的票对齐(含重启后从存档恢复的票),
     * 并把掉线的常驻体按票中指针重新拉起。
     */
    public static void guardOnMain(MinecraftServer server) {
        Set<UUID> forced = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                ServerSubLevelContainer c = container(level);
                if (c == null) continue;
                // 先收集再操作:snatchAndLoad 期间可能有体被 REMOVED 而写 allTickets,
                // 直接在 getAllTickets() 视图上边遍历边加载会 ConcurrentModificationException
                List<Reload> pending = new ArrayList<>();
                for (Map.Entry<UUID, SubLevelTicketInfo> en : c.getAllTickets().entrySet()) {
                    SubLevelTicketInfo info = en.getValue();
                    // 常态守护路径,每票一个 Stream 分配不值得
                    boolean panelTicket = false;
                    for (SubLevelLoadingTicket<?> ticket : info.tickets()) {
                        if (isPanelTicket(ticket)) {
                            panelTicket = true;
                            break;
                        }
                    }
                    if (!panelTicket) continue;
                    UUID uuid = en.getKey();
                    forced.add(uuid);
                    if (loaded(c, uuid) != null) {
                        FAILED.remove(uuid);
                        continue;
                    }
                    if (FAILED.getOrDefault(uuid, 0) >= MAX_RETRY) continue;
                    if (info.getPointer() != null) pending.add(new Reload(uuid, info.getPointer()));
                }
                for (Reload r : pending) {
                    try {
                        c.getHoldingChunkMap().snatchAndLoad(r.pointer(), r.uuid());
                    } catch (Throwable t) {
                        SablePanel.LOGGER.warn("sablepanel: force-load guard snatch {} failed", r.uuid(), t);
                    }
                    if (loaded(c, r.uuid()) == null) {
                        int fails = FAILED.merge(r.uuid(), 1, Integer::sum);
                        if (fails >= MAX_RETRY) {
                            SablePanel.LOGGER.warn(
                                    "sablepanel: force-loaded body {} could not be reloaded after {} attempts, "
                                            + "giving up until re-applied from the panel", r.uuid(), fails);
                        }
                    } else {
                        FAILED.remove(r.uuid());
                    }
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: force-load guard failed", t);
            }
        }
        MIRROR.retainAll(forced);
        MIRROR.addAll(forced);
        FAILED.keySet().retainAll(forced);
    }

    private static boolean isPanelTicket(SubLevelLoadingTicket<?> ticket) {
        return PANEL_FORCED.equals(ticket.getType());
    }

    private static ServerSubLevelContainer container(ServerLevel level) {
        try {
            return SubLevelContainer.getContainer(level);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerSubLevel loaded(ServerSubLevelContainer c, UUID uuid) {
        var sl = c.getSubLevel(uuid);
        return sl instanceof ServerSubLevel ssl && !ssl.isRemoved() ? ssl : null;
    }

    private record Reload(UUID uuid, GlobalSavedSubLevelPointer pointer) {
    }
}

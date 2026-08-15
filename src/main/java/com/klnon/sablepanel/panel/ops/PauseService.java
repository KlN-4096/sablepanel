package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单体物理暂停 = 引擎级固定约束(与机械动力航空学"创造模式物理手杖"的锁定同机制):
 * {@code pipeline.addConstraint(null, body, FixedConstraintConfiguration(position, rotationPoint, orientation))}
 * 把结构与世界锁死,恢复时 {@code handle.remove()}。无逐 tick 干预,无网络同步噪声。
 * <p>
 * 暂停意图按 uuid 持久化到 {@code config/sablepanel/paused.json},重启后由
 * {@link PanelObserver#onSubLevelAdded} 在体加载时重新挂约束 —— 与手杖锁定一样跨重启保持。
 * 体卸载时约束随体消亡,意图保留;显式恢复才解除。
 * <p>
 * 约束的挂/拆都必须在主线程(调用方经 OpsService.onMain 保证);
 * {@code REQUESTED} 是 HTTP 线程与主线程共享的意图表,{@code HANDLES} 仅主线程访问。
 */
public final class PauseService {
    /** 暂停意图(HTTP 线程读,主线程写),持久化 */
    private static final Set<UUID> REQUESTED = ConcurrentHashMap.newKeySet();
    /** 已挂到管线上的约束(仅主线程) */
    private static final Map<UUID, PhysicsConstraintHandle> HANDLES = new HashMap<>();

    private PauseService() {
    }

    /**
     * 用户显式的暂停意图 —— 只反映用户点过的那些,不含常驻加载自动加的。
     * 面板要把"暂停"和"冻结"分开展示,所以这里刻意不并进 {@link FreezeService}。
     */
    public static boolean isPaused(UUID uuid) {
        return REQUESTED.contains(uuid);
    }

    /**
     * 约束是否该挂着 = 用户暂停 ∪ 冻结。冻结必然连带锁物理:2026-08-08 实测只冻方块实体、
     * 不锁物理时,{@code Rapier3D.step} 单独就能把 192 体的链拖到单 tick 60 秒。
     */
    private static boolean shouldHold(UUID uuid) {
        return REQUESTED.contains(uuid) || FreezeService.isFrozen(uuid);
    }

    /** 主线程:冻结集合变化后重挂/解开约束(FreezeService 改完意图就调) */
    public static void refreshOnMain(net.minecraft.server.MinecraftServer server, Collection<UUID> uuids) {
        for (UUID uuid : uuids) {
            if (shouldHold(uuid)) {
                ServerSubLevel sl = findLoaded(server, uuid);
                if (sl != null) lock(sl);
            } else {
                unlock(uuid);
            }
        }
    }

    /** 当前暂停集合快照(HTTP 线程 /api/bodies 输出用) */
    public static Set<UUID> snapshot() {
        return Set.copyOf(REQUESTED);
    }

    /** 主线程:登记/解除暂停,并对已加载体立即挂/拆约束;改动后落盘 */
    public static void applyOnMain(net.minecraft.server.MinecraftServer server, Collection<UUID> uuids, boolean paused) {
        if (paused) REQUESTED.addAll(uuids);
        else uuids.forEach(REQUESTED::remove);
        refreshOnMain(server, uuids);
        save();
    }

    /** 主线程(PanelObserver 体加载回调):有暂停或冻结意图的体重新锁定 */
    public static void onBodyLoaded(ServerSubLevel sl) {
        if (shouldHold(sl.getUniqueId())) lock(sl);
    }

    /** 主线程(PanelObserver 体卸载/移除回调):约束随体消亡,只丢弃句柄,意图保留 */
    public static void onBodyUnloaded(UUID uuid) {
        HANDLES.remove(uuid);
    }

    /** 主线程(面板传送暂停体后):在新位置重新锁定 */
    public static void reanchor(ServerSubLevel sl) {
        if (!REQUESTED.contains(sl.getUniqueId())) return;
        unlock(sl.getUniqueId());
        lock(sl);
    }

    private static void lock(ServerSubLevel sl) {
        if (HANDLES.containsKey(sl.getUniqueId())) return;
        try {
            var pipeline = SubLevelPhysicsSystem.get(sl.getLevel()).getPipeline();
            var pose = sl.logicalPose();
            PhysicsConstraintHandle handle = pipeline.addConstraint(null, sl,
                    new FixedConstraintConfiguration(pose.position(), pose.rotationPoint(), pose.orientation()));
            HANDLES.put(sl.getUniqueId(), handle);
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: locking paused body {} failed", sl.getUniqueId(), t);
        }
    }

    private static void unlock(UUID uuid) {
        PhysicsConstraintHandle handle = HANDLES.remove(uuid);
        if (handle == null) return;
        try {
            handle.remove();
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: unlocking body {} failed", uuid, t);
        }
    }

    private static ServerSubLevel findLoaded(net.minecraft.server.MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            try {
                var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                if (container == null) continue;
                for (ServerSubLevel sl : container.getAllSubLevels()) {
                    if (sl.getUniqueId().equals(uuid)) return sl;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ===================== 持久化 ===================== */

    private static final IntentFile FILE = new IntentFile("paused.json");

    /** 服务端启动时调用;之后体加载回调会逐个重挂约束 */
    public static void load() {
        REQUESTED.addAll(FILE.loadUuids("paused bodies"));
    }

    private static void save() {
        FILE.saveUuids(REQUESTED);
    }

    /** 服务端关闭时清空运行时句柄(约束随世界消亡);意图文件保留 */
    public static void reset() {
        HANDLES.clear();
        REQUESTED.clear();
    }
}

package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 物理暂停 = 引擎级固定约束(与机械动力航空学"创造模式物理手杖"的锁定同机制):
 * 固定约束的本体锚点必须取自当前 plot，再换算出对应世界锚点；存档里的 rotationPoint
 * 可能仍属于旧 plot，Sable 会拒绝这种约束。
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
    /** 暂停意图代次；启动迁移据此避免把用户刚恢复的组重新暂停。 */
    private static final AtomicLong REVISION = new AtomicLong();
    /** 已挂到管线上的约束(仅主线程) */
    private static final Map<UUID, PhysicsConstraintHandle> HANDLES = new HashMap<>();

    private PauseService() {
    }

    /**
     * 用户显式的暂停意图。常驻加载和暂停 tick 都不会隐式写入这里。
     */
    public static boolean isPaused(UUID uuid) {
        return REQUESTED.contains(uuid);
    }

    /** 只有显式物理暂停才固定;冻结/常驻不参与该判定 */
    private static boolean shouldHold(UUID uuid) {
        return REQUESTED.contains(uuid);
    }

    /** 主线程:显式暂停集合变化后重挂/解开约束。 */
    public static Set<UUID> refreshOnMain(net.minecraft.server.MinecraftServer server, Collection<UUID> uuids) {
        Set<UUID> toLock = new java.util.HashSet<>();
        Set<UUID> failed = new java.util.HashSet<>();
        for (UUID uuid : uuids) {
            if (shouldHold(uuid)) toLock.add(uuid);
            else if (!unlock(uuid)) failed.add(uuid);
        }
        if (toLock.isEmpty()) return Set.copyOf(failed);
        Map<UUID, ServerSubLevel> loaded = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                if (container == null) continue;
                for (ServerSubLevel body : container.getAllSubLevels()) {
                    if (toLock.contains(body.getUniqueId())) loaded.put(body.getUniqueId(), body);
                }
            } catch (Throwable ignored) {
            }
        }
        for (UUID uuid : toLock) {
            ServerSubLevel body = loaded.get(uuid);
            if (body != null && !lock(body)) failed.add(uuid);
        }
        return Set.copyOf(failed);
    }

    /** 主线程:启动完成后兜底恢复显式物理暂停。 */
    public static Set<UUID> refreshAllOnMain(net.minecraft.server.MinecraftServer server) {
        return refreshOnMain(server, REQUESTED);
    }

    /** 当前暂停集合快照(HTTP 线程 /api/bodies 输出用) */
    public static Set<UUID> snapshot() {
        return Set.copyOf(REQUESTED);
    }

    record IntentSnapshot(Set<UUID> uuids, long revision) {
    }

    static IntentSnapshot snapshotWithRevision() {
        return new IntentSnapshot(Set.copyOf(REQUESTED), REVISION.get());
    }

    static long revision() {
        return REVISION.get();
    }

    /** 主线程:登记/解除暂停,并对已加载体立即挂/拆约束;调用方离开主线程后再 {@link #persist()} */
    public static void applyOnMain(net.minecraft.server.MinecraftServer server, Collection<UUID> uuids, boolean paused) {
        Set<UUID> added = new java.util.HashSet<>();
        Set<UUID> removed = new java.util.HashSet<>();
        if (paused) {
            for (UUID uuid : uuids) if (REQUESTED.add(uuid)) added.add(uuid);
        } else {
            for (UUID uuid : uuids) if (REQUESTED.remove(uuid)) removed.add(uuid);
        }
        Set<UUID> failed = refreshOnMain(server, uuids);
        if (!failed.isEmpty()) {
            if (paused) {
                REQUESTED.removeAll(added);
                refreshOnMain(server, added);
                throw new IllegalStateException("固定物理失败: " + failed);
            }
            REQUESTED.addAll(removed);
            refreshOnMain(server, removed);
            throw new IllegalStateException("解除固定物理失败: " + failed);
        }
        if (!added.isEmpty() || !removed.isEmpty()) REVISION.incrementAndGet();
    }

    /** 主线程(PanelObserver 体加载回调):显式暂停的体重新锁定。 */
    public static boolean onBodyLoaded(ServerSubLevel sl) {
        return !shouldHold(sl.getUniqueId()) || lock(sl);
    }

    /** 主线程(PanelObserver 体卸载/移除回调):约束随体消亡,只丢弃句柄,意图保留 */
    public static void onBodyUnloaded(UUID uuid) {
        HANDLES.remove(uuid);
    }

    /** 主线程:移动锁定体时旧约束必须先拆，避免新旧世界锚点把体拉到中间。 */
    public static void moveOnMain(ServerSubLevel sl, Runnable move, Runnable rollback) {
        boolean held = shouldHold(sl.getUniqueId());
        moveWithConstraint(held, () -> detachStrict(sl.getUniqueId()),
                new MoveActions(move, () -> lock(sl), rollback));
    }

    record MoveActions(Runnable move, BooleanSupplier lock, Runnable rollback) {
    }

    static void moveWithConstraint(boolean held, Runnable detach, MoveActions actions) {
        if (held) detach.run();
        try {
            actions.move().run();
            if (held && !actions.lock().getAsBoolean()) throw new IllegalStateException("移动后固定物理失败");
        } catch (Throwable moveFailure) {
            rollbackMove(held, actions, moveFailure);
            if (moveFailure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("移动物理体失败", moveFailure);
        }
    }

    private static void rollbackMove(boolean held, MoveActions actions, Throwable moveFailure) {
        try {
            actions.rollback().run();
        } catch (Throwable rollbackFailure) {
            moveFailure.addSuppressed(rollbackFailure);
        }
        if (!held) return;
        try {
            if (!actions.lock().getAsBoolean()) throw new IllegalStateException("原位置固定物理失败");
        } catch (Throwable lockFailure) {
            moveFailure.addSuppressed(lockFailure);
        }
    }

    private static boolean lock(ServerSubLevel sl) {
        if (HANDLES.containsKey(sl.getUniqueId())) return true;
        try {
            replaceConstraint(sl.getUniqueId(), () -> createConstraint(sl));
            return true;
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: locking paused body {} failed", sl.getUniqueId(), t);
            return false;
        }
    }

    private static PhysicsConstraintHandle createConstraint(ServerSubLevel sl) {
        var pipeline = SubLevelPhysicsSystem.get(sl.getLevel()).getPipeline();
        return pipeline.addConstraint(null, sl, fixedConstraint(sl.logicalPose(), sl.getPlot().getCenterBlock()));
    }

    static FixedConstraintConfiguration fixedConstraint(Pose3dc pose, BlockPos plotAnchor) {
        Vector3d localAnchor = plotAnchor(plotAnchor);
        return new FixedConstraintConfiguration(pose.transformPosition(localAnchor, new Vector3d()), localAnchor,
                new Quaterniond(pose.orientation()));
    }

    static Vector3d plotAnchor(BlockPos block) {
        return new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
    }

    static void replaceConstraint(UUID uuid, Supplier<PhysicsConstraintHandle> factory) {
        PhysicsConstraintHandle previous = HANDLES.get(uuid);
        PhysicsConstraintHandle replacement = createBeforeRemoving(previous, factory,
                PhysicsConstraintHandle::remove, PhysicsConstraintHandle::remove);
        HANDLES.put(uuid, replacement);
    }

    static <T> T createBeforeRemoving(T previous, Supplier<? extends T> factory,
                                      Consumer<T> removePrevious, Consumer<T> removeReplacement) {
        T replacement = java.util.Objects.requireNonNull(factory.get(), "replacement");
        if (previous == null) return replacement;
        try {
            removePrevious.accept(previous);
        } catch (Throwable removalFailure) {
            try {
                removeReplacement.accept(replacement);
            } catch (Throwable rollbackFailure) {
                removalFailure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("旧约束移除失败", removalFailure);
        }
        return replacement;
    }

    private static boolean unlock(UUID uuid) {
        PhysicsConstraintHandle handle = HANDLES.remove(uuid);
        if (handle == null) return true;
        try {
            handle.remove();
            return true;
        } catch (Throwable t) {
            HANDLES.put(uuid, handle);
            SablePanel.LOGGER.warn("sablepanel: unlocking body {} failed", uuid, t);
            return false;
        }
    }

    private static void detachStrict(UUID uuid) {
        PhysicsConstraintHandle handle = HANDLES.remove(uuid);
        if (handle == null) return;
        try {
            handle.remove();
        } catch (Throwable failure) {
            HANDLES.put(uuid, handle);
            throw new IllegalStateException("旧约束移除失败: " + uuid, failure);
        }
    }

    /* ===================== 持久化 ===================== */

    private static final IntentFile FILE = new IntentFile("paused.json");

    /** 服务端启动时调用;之后体加载回调会逐个重挂约束 */
    public static void load() {
        if (REQUESTED.addAll(FILE.loadUuids("paused bodies"))) REVISION.incrementAndGet();
    }

    public static void persist() {
        FILE.saveUuids(REQUESTED);
    }

    /** 服务端关闭时清空运行时句柄(约束随世界消亡);意图文件保留 */
    public static void reset() {
        HANDLES.clear();
        if (!REQUESTED.isEmpty()) {
            REQUESTED.clear();
            REVISION.incrementAndGet();
        }
    }
}

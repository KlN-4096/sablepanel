package com.klnon.sablepanel.panel.ops;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冻结 tick —— <b>与物理暂停完全独立</b>:
 * <ul>
 *   <li><b>暂停</b>({@link PauseService})= 引擎级固定约束。体被钉住不动,但方块实体照常 tick,
 *       机器照转。砍掉的是 Rapier 求解器那份开销。</li>
 *   <li><b>冻结</b>(本类)= 该体 plot 内的方块实体<b>整个不 tick</b>。Create 的流体网络、应力网络、
 *       传送带全部停摆,时间对这个结构静止。砍掉的是方块实体那份开销。</li>
 * </ul>
 * 暂停 tick 不会创建物理约束，也不会清除速度；需要整组停住时由用户另行点击“暂停物理”。
 * <p>
 * 判定走 sable 自己的 plot 反查({@code container.getPlot(chunkX, chunkZ).getSubLevel()}),
 * 纯位移加数组索引。冻结集合为空时直接短路,常态开销是一次 {@code isEmpty()}。
 */
public final class FreezeService {
    /** 冻结意图(主线程写,tick 线程读),持久化 */
    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();

    private FreezeService() {
    }

    public static boolean isFrozen(UUID uuid) {
        return FROZEN.contains(uuid);
    }

    public static Set<UUID> snapshot() {
        return Set.copyOf(FROZEN);
    }

    /** 主线程:登记/解除冻结。方块实体下一 tick 自动生效;调用方离开主线程后再 {@link #persist()}。 */
    public static void applyOnMain(Collection<UUID> uuids, boolean frozen) {
        if (frozen) FROZEN.addAll(uuids);
        else uuids.forEach(FROZEN::remove);
    }

    /**
     * mixin 每个方块实体每 tick 调一次,必须便宜且绝不抛。
     * <p>
     * ponytail: 每个方块实体一次 plot 反查(位移+数组索引)。真嫌贵就按 chunk long 缓存一层,
     * 但那要处理 plot 装卸时的失效 —— 冻结本来就是"服务端已经要死了"才用的,先不做。
     */
    public static boolean shouldSkipTick(Level level, BlockPos pos) {
        if (FROZEN.isEmpty()) return false;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return false;
            LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
            SubLevel body = plot == null ? null : plot.getSubLevel();
            return body != null && FROZEN.contains(body.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ===================== 持久化 ===================== */

    private static final IntentFile FILE = new IntentFile("frozen.json");

    /** 服务端启动时调用:冻结意图跨重启保持,否则起服那一刻就被同一条链压死。 */
    public static void load() {
        FROZEN.addAll(FILE.loadUuids("frozen bodies"));
    }

    /**
     * 作业线程:意图落盘。AtomicIo 的 Windows 重试最长睡半秒,不能睡在主线程上。
     * 已知窗(setPaused 同病):onMain 20 秒超时后作业线程先 persist,迟到的主线程任务
     * 再改内存就无人落盘 —— 封窗要换 onMainUntilComplete,待裁决。
     */
    public static void persist() {
        FILE.saveUuids(FROZEN);
    }

    public static void reset() {
        FROZEN.clear();
    }
}

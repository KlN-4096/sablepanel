package com.klnon.sablepanel.panel.ops;

import com.klnon.sablepanel.SablePanel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整维度物理开关 —— 走 sable 自己的 {@code SubLevelPhysicsSystem.setPaused(boolean)}:
 * 关掉之后 {@code tick()} 里那句 {@code if (!paused) tickPipelinePhysics(...)} 直接跳过,
 * 整条 {@code prePhysicsTick → Rapier3D.step → updateAllPoses} 一步都不走。
 * <p>
 * <b>这是急救阀,不是修复</b>。它和"暂停/冻结"三件事互不重叠:
 * <ul>
 *   <li>{@link PauseService} 暂停 = 给<b>单个体</b>挂固定约束。体仍在场景里,求解器照样算它。</li>
 *   <li>{@link FreezeService} 冻结 = <b>单个体</b>的方块实体不 tick。物理照跑。</li>
 *   <li>本类 = <b>整个维度</b>不跑物理。所有船、所有结构一起静止,谁也不掉、不碰撞、不被推动。</li>
 * </ul>
 * 2026-08-09 实测:糖音气球组清到 26 体、全部冻结+锁定,常驻加载后 3 分钟仍被看门狗杀,
 * 吊死在 {@code Rapier3D.step}(原生)。逐体 tick 合计只有 1.45 ms/t —— 时间全在原生求解器里,
 * 面板改不动它。能做的就是给服主一个开关,让服务器先活着。
 * <p>
 * 意图按维度 id 持久化,重启后由 {@link #guardOnMain} 重放 —— 起服就被同一个维度压死时,
 * 不持久化等于没有开关。
 */
public final class PhysicsService {
    /** 停跑物理的维度 id(HTTP 线程读,主线程写),持久化 */
    private static final Set<String> PAUSED = ConcurrentHashMap.newKeySet();

    private PhysicsService() {
    }

    public static Set<String> snapshot() {
        return Set.copyOf(PAUSED);
    }

    /** 主线程:登记意图并立即生效;调用方离开主线程后再 {@link #persist()} */
    public static void applyOnMain(MinecraftServer server, String dim, boolean paused) {
        if (paused) PAUSED.add(dim);
        else PAUSED.remove(dim);
        guardOnMain(server);
    }

    /**
     * 主线程(每轮运行时刷新):把意图重放到各维度的物理系统上。
     * <p>
     * {@code setPaused} 只是一次字段写,重放比"记住哪些已经设过"便宜,也顺带覆盖了
     * 重启恢复、维度后加载、以及别的模组把它改回去这三种情况。
     */
    public static void guardOnMain(MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
                if (system == null) continue;
                boolean want = PAUSED.contains(level.dimension().location().toString());
                if (system.getPaused() != want) {
                    system.setPaused(want);
                    SablePanel.LOGGER.warn("sablepanel: physics for {} is now {}",
                            level.dimension().location(), want ? "PAUSED" : "running");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* ===================== 持久化 ===================== */

    private static final IntentFile FILE = new IntentFile("physics-paused.json");

    /** 服务端启动时调用;之后每轮 guardOnMain 把意图压到物理系统上 */
    public static void load() {
        for (String dim : FILE.load()) if (!dim.isBlank()) PAUSED.add(dim);
        if (!PAUSED.isEmpty()) {
            SablePanel.LOGGER.warn("sablepanel: physics stays paused for {} after restart", PAUSED);
        }
    }

    public static void persist() {
        FILE.save(PAUSED);
    }

    public static void reset() {
        PAUSED.clear();
    }
}

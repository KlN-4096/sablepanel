package com.klnon.sablepanel.panel.metrics;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理 tick 计时:Pre/Post 事件按 physicsSystem 配对,喂给 StatsCollector 按维度聚合。
 * 事件可能来自非主线程,全部走并发安全结构。
 */
public final class PhysicsTimer {
    private static final ConcurrentHashMap<SubLevelPhysicsSystem, Long> BEGIN = new ConcurrentHashMap<>();
    /** 维度 id 每物理步拼一次字符串太浪费,按物理系统缓存(系统与维度一一对应且不改名) */
    private static final ConcurrentHashMap<SubLevelPhysicsSystem, String> DIM_NAME = new ConcurrentHashMap<>();

    private PhysicsTimer() {
    }

    public static void begin(SubLevelPhysicsSystem system) {
        try {
            BEGIN.put(system, System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    public static void end(SubLevelPhysicsSystem system) {
        try {
            Long t0 = BEGIN.remove(system);
            if (t0 == null) {
                return;
            }
            long dur = System.nanoTime() - t0;
            try {
                String dim = DIM_NAME.computeIfAbsent(system,
                        s -> s.getLevel().dimension().location().toString());
                StatsCollector.INSTANCE.physics(dim, dur);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 停服清引用:两张表都以物理系统为键,跨启动残留会钉住旧世界对象 */
    public static void reset() {
        BEGIN.clear();
        DIM_NAME.clear();
    }
}

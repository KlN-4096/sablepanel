package com.klnon.sablepanel;

import com.klnon.sablepanel.panel.data.StatsCollector;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理 tick 计时:Pre/Post 事件按 physicsSystem 配对,喂给 StatsCollector 按维度聚合。
 * 事件可能来自非主线程,全部走并发安全结构。
 */
public final class PhysicsTimer {
    private static final ConcurrentHashMap<SubLevelPhysicsSystem, Long> BEGIN = new ConcurrentHashMap<>();

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
                StatsCollector.INSTANCE.physics(system.getLevel().dimension().location().toString(), dur);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
}

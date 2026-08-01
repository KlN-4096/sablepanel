package com.klnon.sablepanel;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理 tick 计时:Pre/Post 事件按 physicsSystem 配对,按维度聚合。
 * 事件可能来自非主线程,全部走并发安全结构。
 */
public final class PhysicsTimer {
    private static final ConcurrentHashMap<SubLevelPhysicsSystem, Long> BEGIN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SubLevelPhysicsSystem, Stat> STATS = new ConcurrentHashMap<>();

    private PhysicsTimer() {
    }

    public static void begin(SubLevelPhysicsSystem system) {
        try {
            BEGIN.put(system, System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    public static void end(SubLevelPhysicsSystem system, double timeStep) {
        try {
            Long t0 = BEGIN.remove(system);
            if (t0 == null) {
                return;
            }
            STATS.computeIfAbsent(system, k -> new Stat()).add(System.nanoTime() - t0);
        } catch (Throwable ignored) {
        }
    }

    /** 取出并重置所有统计,key = 维度 id */
    public static Map<String, Snapshot> drain() {
        Map<String, Snapshot> result = new HashMap<>();
        for (Map.Entry<SubLevelPhysicsSystem, Stat> entry : STATS.entrySet()) {
            Snapshot snap = entry.getValue().drain();
            if (snap.count() == 0) {
                continue;
            }
            String dim;
            try {
                dim = entry.getKey().getLevel().dimension().location().toString();
            } catch (Throwable t) {
                dim = "unknown@" + Integer.toHexString(System.identityHashCode(entry.getKey()));
            }
            result.merge(dim, snap, Snapshot::merge);
        }
        return result;
    }

    public record Snapshot(long count, long sumNanos, long maxNanos) {
        public double avgMs() {
            return this.count == 0 ? 0 : Math.round(this.sumNanos / (double) this.count / 1000.0) / 1000.0;
        }

        public double maxMs() {
            return Math.round(this.maxNanos / 1000.0) / 1000.0;
        }

        static Snapshot merge(Snapshot a, Snapshot b) {
            return new Snapshot(a.count + b.count, a.sumNanos + b.sumNanos, Math.max(a.maxNanos, b.maxNanos));
        }
    }

    private static final class Stat {
        private long count;
        private long sumNanos;
        private long maxNanos;

        synchronized void add(long nanos) {
            this.count++;
            this.sumNanos += nanos;
            if (nanos > this.maxNanos) {
                this.maxNanos = nanos;
            }
        }

        synchronized Snapshot drain() {
            Snapshot snap = new Snapshot(this.count, this.sumNanos, this.maxNanos);
            this.count = 0;
            this.sumNanos = 0;
            this.maxNanos = 0;
            return snap;
        }
    }
}

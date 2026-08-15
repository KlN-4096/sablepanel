package com.klnon.sablepanel.panel.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 逐体 Java 侧耗时:mixin 从 ServerSubLevel.tick()/prePhysicsTick() 喂纳秒,
 * 主线程周期 drain 成 ms/tick 并做 EMA 平滑。写入端可能在物理线程,全并发安全。
 * 注意:rapier 物理步进按维度整体执行,不含在单体值内(单体值=该体的 Java 逻辑成本)。
 */
public final class BodyCostTracker {
    /**
     * 面板启用时置位。未启用(enabled=false)就没有人来 drain,
     * mixin 喂进来的采样必须直接丢弃,否则 ACC 会按出现过的 uuid 无限囤积。
     * 关闭状态下 mixin 在 nanoTime 之前短路,只剩这一个 volatile 读。
     */
    public static volatile boolean ENABLED;

    private static final ConcurrentHashMap<UUID, LongAdder> ACC = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Double> EMA = new ConcurrentHashMap<>();

    private BodyCostTracker() {
    }

    public static void add(UUID uuid, long nanos) {
        if (!ENABLED || uuid == null || nanos <= 0) return;
        ACC.computeIfAbsent(uuid, k -> new LongAdder()).add(nanos);
    }

    /**
     * 主线程周期调用。
     *
     * @param ticks 距上次 drain 的服务器 tick 数
     * @param alive 当前加载中的体(其余清理)
     * @return uuid -> ms/tick(EMA)
     */
    public static Map<UUID, Double> drain(int ticks, Set<UUID> alive) {
        for (var it = ACC.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            long ns = e.getValue().sumThenReset();
            double ms = ns / 1e6 / Math.max(1, ticks);
            EMA.merge(e.getKey(), ms, (old, cur) -> old * 0.6 + cur * 0.4);
            if (!alive.contains(e.getKey())) it.remove();
        }
        EMA.keySet().retainAll(alive);
        return new HashMap<>(EMA);
    }
}

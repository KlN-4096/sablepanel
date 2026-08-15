package com.klnon.sablepanel.panel.metrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 物理/逐体耗时采集:tick 计数与物理事件喂入,按秒聚合进环形缓冲(保留 15 分钟)。
 * <p>
 * 写路径无锁:主线程 tick 与各物理线程写同一个每秒累加槽(LongAdder),互不阻塞;
 * 跨秒换槽只发生在主线程 tick 里。ring 只由主线程追加,HTTP 读取时短锁拷贝引用、
 * 在锁外构建 JSON —— 请求线程不再有机会把主线程挡在统计锁外。
 * 换槽瞬间物理线程可能还写着旧槽,秒边界最多丢一两个物理步采样,对图表不可见。
 */
public final class StatsCollector {
    public static final StatsCollector INSTANCE = new StatsCollector();
    private static final int WINDOW_SECONDS = 900;

    private record Sec(long t, long ticks, Map<String, long[]> phys, double bodyCostMs) {
    }

    /** 每维度的物理耗时累加对。LongAdder 自带跨线程可见性,主线程冻结时不会读到撕裂的 long */
    private static final class DimAdder {
        final LongAdder sumNs = new LongAdder();
        final LongAdder steps = new LongAdder();
    }

    /** 一秒的累加槽。dim 槽按需建,LongAdder 保证并发累加不丢数 */
    private static final class Slot {
        final long sec;
        final LongAdder ticks = new LongAdder();
        final ConcurrentHashMap<String, DimAdder> phys = new ConcurrentHashMap<>();

        Slot(long sec) {
            this.sec = sec;
        }
    }

    private volatile Slot current = new Slot(0);
    /** 只由主线程追加、start() 清空;读取方短锁拷贝 */
    private final ArrayDeque<Sec> ring = new ArrayDeque<>();
    /** 主线程周期采样:dim -> 加载体数 */
    private volatile Map<String, Integer> loadedPerDim = Map.of();
    /** 主线程周期采样:逐体耗时 Top 列表与合计(ms/tick) */
    private volatile JsonArray topCost = new JsonArray();
    private volatile double bodyCostTotal;

    public void start() {
        synchronized (this.ring) {
            this.ring.clear();
        }
        this.current = new Slot(System.currentTimeMillis() / 1000);
    }

    /** 仅主线程调用;跨秒换槽也只发生在这里 */
    public void tick() {
        rollIfNeeded().ticks.increment();
    }

    /** 物理线程调用:只写当前槽,不参与换槽 */
    public void physics(String dim, long durationNs) {
        DimAdder acc = this.current.phys.computeIfAbsent(dim, ignored -> new DimAdder());
        acc.sumNs.add(durationNs);
        acc.steps.increment();
    }

    public void setLoadedPerDim(Map<String, Integer> counts) {
        this.loadedPerDim = counts;
    }

    public void setBodyCost(JsonArray top, double total) {
        this.topCost = top;
        this.bodyCostTotal = total;
    }

    private Slot rollIfNeeded() {
        Slot slot = this.current;
        long sec = System.currentTimeMillis() / 1000;
        if (sec == slot.sec) return slot;
        Slot fresh = new Slot(sec);
        this.current = fresh;   // 先换槽再冻结,把秒边界的采样丢失窗口压到最小
        long ticks = slot.ticks.sum();
        if (slot.sec != 0 && ticks > 0) {
            Map<String, long[]> phys = new HashMap<>();
            slot.phys.forEach((dim, acc) -> phys.put(dim, new long[]{acc.sumNs.sum(), acc.steps.sum()}));
            Sec completed = new Sec(slot.sec, ticks, phys, this.bodyCostTotal);
            synchronized (this.ring) {
                this.ring.addLast(completed);
                while (this.ring.size() > WINDOW_SECONDS) this.ring.removeFirst();
            }
        }
        return fresh;
    }

    /** 完整内存窗口的序列 + 汇总(phys_1m 为 60s 均值);展示区间由前端裁剪 */
    public JsonObject toJson() {
        List<Sec> secs;
        synchronized (this.ring) {
            secs = List.copyOf(this.ring);
        }
        JsonArray t = new JsonArray();
        JsonArray bodyLogic = new JsonArray();
        Map<String, JsonArray> physArr = new HashMap<>();
        // 先收集本窗口出现过的所有维度,保证每条序列等长
        java.util.Set<String> dims = new java.util.TreeSet<>();
        for (Sec s : secs) dims.addAll(s.phys().keySet());
        for (String d : dims) physArr.put(d, new JsonArray());
        long ticks60 = 0;
        Map<String, long[]> phys60 = new HashMap<>();
        int n60Start = Math.max(0, secs.size() - 60);
        int i = 0;
        for (Sec s : secs) {
            if (i++ >= n60Start) {
                ticks60 += s.ticks();
                for (var e : s.phys().entrySet()) {
                    long[] acc = phys60.computeIfAbsent(e.getKey(), k -> new long[2]);
                    acc[0] += e.getValue()[0];
                    acc[1] += e.getValue()[1];
                }
            }
            t.add(s.t());
            bodyLogic.add(round2(s.bodyCostMs()));
            for (String d : dims) {
                long[] acc = s.phys().get(d);
                physArr.get(d).add(acc == null ? 0 : round2(acc[0] / 1e6 / s.ticks()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("t", t);
        out.add("body_logic", bodyLogic);
        JsonObject physObj = new JsonObject();
        for (var e : physArr.entrySet()) physObj.add(e.getKey(), e.getValue());
        out.add("phys", physObj);
        JsonObject phys1m = new JsonObject();
        for (var e : phys60.entrySet()) {
            phys1m.addProperty(e.getKey(), round2(ticks60 > 0 ? e.getValue()[0] / 1e6 / ticks60 : 0));
        }
        out.add("phys_1m", phys1m);
        JsonObject loaded = new JsonObject();
        for (var e : this.loadedPerDim.entrySet()) loaded.addProperty(e.getKey(), e.getValue());
        out.add("loaded", loaded);
        // 停跑物理的维度:看板那张按维度的表要能显示并切换
        JsonArray physPaused = new JsonArray();
        for (String dim : com.klnon.sablepanel.panel.ops.PhysicsService.snapshot()) physPaused.add(dim);
        out.add("phys_paused", physPaused);
        out.add("top_cost", this.topCost);
        out.addProperty("body_cost_total", this.bodyCostTotal);
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

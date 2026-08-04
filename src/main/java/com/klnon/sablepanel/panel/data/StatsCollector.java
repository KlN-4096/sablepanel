package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * TPS/MSPT/物理耗时采集:tick 与物理事件喂入,按秒聚合进环形缓冲(默认保留 15 分钟)。
 * 所有写入路径都可能来自不同线程,统一 synchronized(单点低频,无竞争压力)。
 */
public final class StatsCollector {
    public static final StatsCollector INSTANCE = new StatsCollector();
    private static final int WINDOW_SECONDS = 900;

    private record Sec(long t, int ticks, long sumNs, long maxNs, Map<String, long[]> phys,
                       double bodyCostMs) {
    }

    private final ArrayDeque<Sec> ring = new ArrayDeque<>();
    private long curSec;
    private int ticks;
    private long sumNs, maxNs;
    /** dim -> [sumNs, steps] */
    private Map<String, long[]> phys = new HashMap<>();
    /** 主线程周期采样:dim -> 加载体数 */
    private volatile Map<String, Integer> loadedPerDim = Map.of();
    /** 主线程周期采样:逐体耗时 Top 列表与合计(ms/tick) */
    private volatile JsonArray topCost = new JsonArray();
    private volatile double bodyCostTotal;
    private volatile StatsHistoryStore history;

    public void start(PanelConfig config) {
        stop();
        synchronized (this) {
            this.ring.clear();
            this.curSec = 0;
            this.ticks = 0;
            this.sumNs = 0;
            this.maxNs = 0;
            this.phys = new HashMap<>();
        }
        try {
            this.history = new StatsHistoryStore(
                    FMLPaths.CONFIGDIR.get().resolve("sablepanel").resolve("stats"),
                    config.statsRetentionDays);
        } catch (Exception error) {
            SablePanel.LOGGER.warn("sablepanel: persistent stats history disabled", error);
        }
    }

    public void stop() {
        StatsHistoryStore store = this.history;
        this.history = null;
        if (store != null) store.close();
    }

    public synchronized void tick(long durationNs) {
        roll();
        this.ticks++;
        this.sumNs += durationNs;
        if (durationNs > this.maxNs) this.maxNs = durationNs;
    }

    public synchronized void physics(String dim, long durationNs) {
        roll();
        long[] acc = this.phys.computeIfAbsent(dim, k -> new long[2]);
        acc[0] += durationNs;
        acc[1]++;
    }

    public void setLoadedPerDim(Map<String, Integer> counts) {
        this.loadedPerDim = counts;
    }

    public void setBodyCost(JsonArray top, double total) {
        this.topCost = top;
        this.bodyCostTotal = total;
    }

    private void roll() {
        long sec = System.currentTimeMillis() / 1000;
        if (sec == this.curSec) return;
        if (this.curSec != 0 && this.ticks > 0) {
            Sec completed = new Sec(this.curSec, this.ticks, this.sumNs, this.maxNs, this.phys,
                    this.bodyCostTotal);
            this.ring.addLast(completed);
            while (this.ring.size() > WINDOW_SECONDS) this.ring.removeFirst();
            StatsHistoryStore store = this.history;
            if (store != null) {
                store.offer(new StatsHistoryStore.Sample(completed.t(), completed.ticks(), completed.sumNs(),
                        completed.maxNs(), completed.phys(), completed.bodyCostMs()));
            }
        }
        this.curSec = sec;
        this.ticks = 0;
        this.sumNs = 0;
        this.maxNs = 0;
        this.phys = new HashMap<>();
    }

    /** 最近 lastN 秒的序列 + 汇总(mspt/tps 为 60s 均值) */
    public JsonObject toJson(int lastN) {
        JsonObject out;
        StatsHistoryStore store;
        long now = System.currentTimeMillis() / 1000;
        synchronized (this) {
            roll();
            out = inMemoryJson(lastN);
            store = this.history;
        }
        if (store != null) replaceSeries(out, store.query(now - Math.max(1, lastN) + 1, now, 2000));
        return out;
    }

    public JsonObject toJson(long from, long to, int maxPoints) {
        JsonObject out;
        StatsHistoryStore store;
        synchronized (this) {
            roll();
            out = inMemoryJson(300);
            store = this.history;
        }
        if (store != null) replaceSeries(out, store.query(from, to, maxPoints));
        return out;
    }

    private JsonObject inMemoryJson(int lastN) {
        JsonArray t = new JsonArray(), mspt = new JsonArray(), msptMax = new JsonArray();
        JsonArray bodyLogic = new JsonArray();
        Map<String, JsonArray> physArr = new HashMap<>();
        int skip = Math.max(0, this.ring.size() - lastN);
        int i = 0;
        // 先收集本窗口出现过的所有维度,保证每条序列等长
        java.util.Set<String> dims = new java.util.TreeSet<>();
        int j = 0;
        for (Sec s : this.ring) {
            if (j++ < skip) continue;
            dims.addAll(s.phys().keySet());
        }
        for (String d : dims) physArr.put(d, new JsonArray());
        long sum60 = 0;
        int ticks60 = 0;
        Map<String, long[]> phys60 = new HashMap<>();
        int n60Start = Math.max(0, this.ring.size() - 60);
        for (Sec s : this.ring) {
            if (i >= n60Start) {
                sum60 += s.sumNs();
                ticks60 += s.ticks();
                for (var e : s.phys().entrySet()) {
                    long[] acc = phys60.computeIfAbsent(e.getKey(), k -> new long[2]);
                    acc[0] += e.getValue()[0];
                    acc[1] += e.getValue()[1];
                }
            }
            if (i++ < skip) continue;
            t.add(s.t());
            mspt.add(round2(s.sumNs() / 1e6 / s.ticks()));
            msptMax.add(round2(s.maxNs() / 1e6));
            bodyLogic.add(round2(s.bodyCostMs()));
            for (String d : dims) {
                long[] acc = s.phys().get(d);
                physArr.get(d).add(acc == null ? 0 : round2(acc[0] / 1e6 / s.ticks()));
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("now", System.currentTimeMillis());
        out.add("t", t);
        out.add("mspt", mspt);
        out.add("mspt_max", msptMax);
        out.add("body_logic", bodyLogic);
        JsonObject physObj = new JsonObject();
        for (var e : physArr.entrySet()) physObj.add(e.getKey(), e.getValue());
        out.add("phys", physObj);
        double avgMspt = ticks60 > 0 ? sum60 / 1e6 / ticks60 : 0;
        out.addProperty("mspt_1m", round2(avgMspt));
        out.addProperty("tps_1m", round2(avgMspt <= 0 ? 20.0 : Math.min(20.0, 1000.0 / avgMspt)));
        JsonObject phys1m = new JsonObject();
        for (var e : phys60.entrySet()) {
            phys1m.addProperty(e.getKey(), round2(ticks60 > 0 ? e.getValue()[0] / 1e6 / ticks60 : 0));
        }
        out.add("phys_1m", phys1m);
        JsonObject loaded = new JsonObject();
        for (var e : this.loadedPerDim.entrySet()) loaded.addProperty(e.getKey(), e.getValue());
        out.add("loaded", loaded);
        out.add("top_cost", this.topCost);
        out.addProperty("body_cost_total", this.bodyCostTotal);
        return out;
    }

    private static void replaceSeries(JsonObject target, JsonObject history) {
        for (String key : new String[]{"range_from", "range_to", "step_seconds", "aggregation",
                "t", "mspt", "mspt_max", "phys", "body_logic"}) {
            if (history.has(key)) target.add(key, history.get(key));
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

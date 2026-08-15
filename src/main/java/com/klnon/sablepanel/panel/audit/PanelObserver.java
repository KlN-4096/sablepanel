package com.klnon.sablepanel.panel.audit;

import com.klnon.sablepanel.panel.ops.PauseService;
import com.google.gson.JsonObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import org.joml.Vector3dc;

import java.util.UUID;
import com.klnon.sablepanel.SablePanel;

/**
 * 生命周期观察者:add/remove 各写一行 JSONL。
 * 附带碎片风暴告警:1 分钟内 split 出的新体超阈值时 WARN(增殖事故现行抓捕)。
 * 回调在容器主循环里,任何异常都必须吞掉,不能影响 sable 本体。
 */
public final class PanelObserver implements SubLevelObserver {
    private static final int STORM_WINDOW_MS = 60_000;
    private static final int STORM_THRESHOLD = 30;

    private final String dim;
    private final Runnable bodyChanged;
    /** 最近 split 时间的环形窗口:风暴时旧写法的 ArrayDeque<Long> 会装箱堆积数千个元素 */
    private final long[] splitTimes = new long[120];
    private int splitStart, splitCount;
    private long lastStormAlert;

    public PanelObserver(String dim, Runnable bodyChanged) {
        this.dim = dim;
        this.bodyChanged = bodyChanged;
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        try {
            JsonObject o = base("add", subLevel);
            if (subLevel instanceof ServerSubLevel ssl) {
                o.addProperty("runtime_id", ssl.getRuntimeId());
                UUID splitFrom = ssl.getSplitFromSubLevel();
                if (splitFrom != null) {
                    o.addProperty("split_from", splitFrom.toString());
                    checkFragmentStorm(splitFrom);
                }
                // 有暂停意图的体(含重启恢复)在加载时重新挂固定约束
                PauseService.onBodyLoaded(ssl);
            }
            EventLog.write(o);
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: observer add failed", t);
        } finally {
            markBodyChanged();
        }
    }

    private void checkFragmentStorm(UUID splitFrom) {
        long now = System.currentTimeMillis();
        while (this.splitCount > 0 && now - this.splitTimes[this.splitStart] > STORM_WINDOW_MS) {
            this.splitStart = (this.splitStart + 1) % this.splitTimes.length;
            this.splitCount--;
        }
        if (this.splitCount == this.splitTimes.length) {   // 环满:挤掉最老的,计数封顶
            this.splitStart = (this.splitStart + 1) % this.splitTimes.length;
            this.splitCount--;
        }
        this.splitTimes[(this.splitStart + this.splitCount) % this.splitTimes.length] = now;
        this.splitCount++;
        if (this.splitCount >= STORM_THRESHOLD && now - this.lastStormAlert > STORM_WINDOW_MS) {
            this.lastStormAlert = now;
            SablePanel.LOGGER.warn("sablepanel: FRAGMENT STORM in {} - {} split-bodies within 1min (last from {})",
                    this.dim, this.splitCount, splitFrom);
            JsonObject alert = new JsonObject();
            alert.addProperty("ev", "alert");
            alert.addProperty("kind", "fragment_storm");
            alert.addProperty("dim", this.dim);
            alert.addProperty("splits_1min", this.splitCount);
            alert.addProperty("last_split_from", splitFrom.toString());
            EventLog.write(alert);
        }
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        try {
            PauseService.onBodyUnloaded(subLevel.getUniqueId());
            JsonObject o = base("remove", subLevel);
            o.addProperty("reason", reason.name());
            EventLog.write(o);
            // REMOVED 是永久删除(sable 的 Y 越界清理走这条)。它只打 "extreme Y coordinate range"
            // 不打数字,而磁盘上的 world_bounds 往往完全正常 —— 把当场重算的盒子记下来,
            // 才能分清是真的飞出世界了,还是加载没走完就 tick 出了个垃圾盒子。
            if (reason == SubLevelRemovalReason.REMOVED) {
                var box = subLevel.boundingBox();
                SablePanel.LOGGER.warn("sablepanel: body {} removed by sable, runtime bounds y=[{}, {}] xz=[{}, {}]",
                        subLevel.getUniqueId(), box.minY(), box.maxY(), box.minX(), box.minZ());
            }
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: observer remove failed", t);
        } finally {
            markBodyChanged();
        }
    }

    private void markBodyChanged() {
        try {
            this.bodyChanged.run();
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: observer refresh signal failed", t);
        }
    }

    private JsonObject base(String ev, SubLevel subLevel) {
        JsonObject o = new JsonObject();
        o.addProperty("ev", ev);
        o.addProperty("dim", this.dim);
        o.addProperty("uuid", String.valueOf(subLevel.getUniqueId()));
        if (subLevel.getName() != null) {
            o.addProperty("name", subLevel.getName());
        }
        Vector3dc pos = subLevel.logicalPose().position();
        o.addProperty("x", round1(pos.x()));
        o.addProperty("y", round1(pos.y()));
        o.addProperty("z", round1(pos.z()));
        BoundingBox3dc bb = subLevel.boundingBox();
        o.addProperty("size", round1(bb.maxX() - bb.minX()) + "x"
                + round1(bb.maxY() - bb.minY()) + "x"
                + round1(bb.maxZ() - bb.minZ()));
        return o;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
